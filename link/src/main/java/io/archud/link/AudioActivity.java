package io.archud.link;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * ARC AUDIO on the phone: every song on the phone as a searchable list. Tap a song and it plays on the TV;
 * the songs after it follow. Long-press songs to pick your own set. Songs are sent to the TV as files (the
 * playing one, the next four and the one before), so the phone can be put away while they play. The TV is
 * polled so the TV remote (pause, next, previous) is followed here. Shuffle applies to the next queue started;
 * repeat applies at once.
 */
public class AudioActivity extends JarvisActivity {
    /** Start this screen with these words (from Jarvis) to find them among the songs and play them. */
    static final String EXTRA_PLAY_QUERY = "play_query";
    private static final int REQUEST_AUDIO = 31;
    private static final long POLL_MS = 1500;
    /** The TV takes about 32 MB per song; longer files are left out of the list. */
    private static final long MAX_SONG_BYTES = 31L * 1024 * 1024;
    private static final long MIN_MS = 30_000;
    private static final int AHEAD = 4;
    private static final int COVER_EDGE = 400;
    private static final long EXPECT_MS = 15_000;

    private static final class Song {
        final long id;
        final String title;
        final String artist;
        final long durationMs;
        final long size;
        final String name;
        final Uri uri;

        Song(long id, String title, String artist, long durationMs, long size, String name, Uri uri) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
            this.size = size;
            this.name = name;
            this.uri = uri;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Sends songs and covers to the TV, one at a time. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Reads the song list. */
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    /** Asks the TV what it is doing, apart from the worker so a long upload never holds it up. */
    private final ExecutorService poller = Executors.newSingleThreadExecutor();
    /** Songs the TV is known to hold, by queue index. Touched on the worker thread only. */
    private final Set<Integer> sent = new HashSet<>();
    /** JPEG covers already made (an empty array = the song has none). Guarded by itself. */
    private final Map<Long, byte[]> covers = new HashMap<>();
    private final Set<Long> picked = new HashSet<>();

    private List<Song> all = new ArrayList<>();
    private List<Song> shown = new ArrayList<>();
    private volatile List<Song> queue = new ArrayList<>();
    private volatile String session = "";
    private volatile int gen;
    private volatile boolean onTv;
    private volatile boolean polling;
    private String host;
    private String pin;
    private float density;
    /** Words to play as soon as the song list has loaded (set by a voice command). */
    private String voiceQuery;
    private int qpos = -1;
    private int expectPos = -1;
    private long expectUntil;
    private boolean playing;
    private boolean shuffle;
    private boolean repeat;
    private boolean dragging;
    /** True once the TV has been seen playing the current song, so a fresh start is not mistaken for the end. */
    private boolean sawPlay;

    private Hud.Status status;
    private EditText search;
    private TextView pickedButton;
    private LinearLayout nowPanel;
    private ImageView cover;
    private TextView nowTitle;
    private TextView nowArtist;
    private TextView nowTime;
    private SeekBar seek;
    private TextView playButton;
    private TextView shuffleButton;
    private TextView repeatButton;
    private ListView list;
    private BaseAdapter adapter;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollTv();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        host = Store.host(this);
        pin = Store.pin(this);
        buildScreen();
        if (!Store.paired(this)) {
            Sender.say(this, "Pair with the TV first.");
            finish();
            return;
        }
        voiceQuery = getIntent().getStringExtra(EXTRA_PLAY_QUERY);
        loadSongs();
        handler.postDelayed(poll, POLL_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String q = intent.getStringExtra(EXTRA_PLAY_QUERY);
        if (q != null) {
            playWords(q);
        }
    }

    /** Filters the list to these words and plays what matches, first match first. */
    private void playWords(String words) {
        if (all.isEmpty()) {
            voiceQuery = words;
            return;
        }
        voiceQuery = null;
        search.setText(words);
        if (!shown.isEmpty()) {
            playQueue(new ArrayList<>(shown), 0);
        }
    }

    @Override
    protected void onDestroy() {
        // the TV keeps playing the songs it already holds; STOP (or BACK on the TV) ends it
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        reader.shutdownNow();
        poller.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- screen

    private void buildScreen() {
        Hud.bars(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Hud.background(this));
        root.setPadding(dp(16), dp(24), dp(16), dp(10));
        setContentView(root);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout back = new LinearLayout(this);
        back.setGravity(Gravity.CENTER);
        back.setBackground(new Hud.Frame(this, 8, Hud.PANEL, 0xDD0F3350, Hud.FRAME, Hud.CYAN));
        back.addView(new Hud.Icon(this, Hud.ICON_BACK, Hud.CYAN), new LinearLayout.LayoutParams(dp(24), dp(24)));
        back.setClickable(true);
        back.setFocusable(true);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        brand.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(14), 0, 0, 0);
        names.addView(Hud.label(this, "AUDIO", 22, Hud.WHITE, true));
        names.addView(Hud.label(this, "PLAY ON THE TV", 11, Hud.CYAN, false));
        brand.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        brand.addView(new Hud.Ring(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(brand);
        root.addView(Hud.rule(this));

        status = new Hud.Status(this);
        root.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        search = Hud.field(this, "Search songs or artists", InputType.TYPE_CLASS_TEXT);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                // nothing to do before the text changes
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
                // the filter already ran in onTextChanged
            }
        });
        root.addView(search);

        pickedButton = Hud.button(this, "PLAY PICKED", Hud.GOLDEN, false, this::playPicked);
        pickedButton.setVisibility(View.GONE);
        root.addView(pickedButton);

        nowPanel = Hud.panel(this, "NOW PLAYING");
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFF0F3350);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        coverLp.topMargin = dp(8);
        top.addView(cover, coverLp);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), dp(8), 0, 0);
        nowTitle = Hud.label(this, "", 15, Hud.WHITE, true);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowArtist = Hud.label(this, "", 12, Hud.DIM, false);
        nowArtist.setSingleLine(true);
        nowArtist.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(nowTitle);
        words.addView(nowArtist);
        top.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nowPanel.addView(top);

        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(Hud.CYAN));
        seek.setThumbTintList(ColorStateList.valueOf(Hud.GOLD));
        seek.setMax(1);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    showTime(progress, s.getMax());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                control("/audioseek", s.getProgress());
            }
        });
        nowPanel.addView(seek);
        nowTime = Hud.label(this, "0:00 / 0:00", 11, Hud.CYAN, false);
        nowPanel.addView(nowTime);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(Hud.button(this, "PREV", Hud.OUTLINE, true, this::previous));
        playButton = Hud.button(this, "PAUSE", Hud.SOLID, true, this::togglePlay);
        row1.addView(playButton);
        row1.addView(Hud.button(this, "NEXT", Hud.OUTLINE, true, this::next));
        nowPanel.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        shuffleButton = Hud.button(this, "SHUFFLE OFF", Hud.OUTLINE, true, this::toggleShuffle);
        repeatButton = Hud.button(this, "REPEAT OFF", Hud.OUTLINE, true, this::toggleRepeat);
        row2.addView(shuffleButton);
        row2.addView(repeatButton);
        row2.addView(Hud.button(this, "STOP", Hud.DANGER, true, this::stopCasting));
        nowPanel.addView(row2);
        nowPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nowLp.topMargin = dp(10);
        root.addView(nowPanel, nowLp);

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        adapter = new SongAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                playQueue(new ArrayList<>(shown), position);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                togglePick(shown.get(position).id);
                return true;
            }
        });
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = dp(10);
        root.addView(list, listLp);
    }

    private final class SongAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return shown.get(i).id;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView title;
            TextView sub;
            if (convert == null) {
                row = new LinearLayout(AudioActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                title = Hud.label(AudioActivity.this, "", 14, Hud.WHITE, true);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                sub = Hud.label(AudioActivity.this, "", 11, Hud.DIM, false);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(title);
                row.addView(sub);
                row.setTag(new TextView[] {title, sub});
            } else {
                row = (LinearLayout) convert;
                TextView[] pair = (TextView[]) row.getTag();
                title = pair[0];
                sub = pair[1];
            }
            Song s = shown.get(i);
            boolean isPicked = picked.contains(s.id);
            boolean isNow = isCurrent(s);
            title.setText(s.title);
            title.setTextColor(isNow ? Hud.CYAN : (isPicked ? Hud.GOLD : Hud.WHITE));
            sub.setText((isPicked ? "PICKED   " : "") + (s.artist.isEmpty() ? "" : s.artist + "   ")
                    + clock(s.durationMs / 1000));
            row.setBackgroundColor(isNow ? 0x330F3350 : (isPicked ? 0x22FFB93C : 0x00000000));
            return row;
        }
    }

    private boolean isCurrent(Song s) {
        List<Song> q = queue;
        int p = qpos;
        return !session.isEmpty() && p >= 0 && p < q.size() && q.get(p).id == s.id;
    }

    // ---------------------------------------------------------------- the song list

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void loadSongs() {
        if (checkSelfPermission(audioPermission()) != PackageManager.PERMISSION_GRANTED) {
            status.set("ALLOW ARC LINK TO SEE YOUR MUSIC TO BROWSE SONGS.", Hud.GOLD);
            requestPermissions(new String[] {audioPermission()}, REQUEST_AUDIO);
            return;
        }
        status.set("READING YOUR SONGS...", Hud.CYAN);
        try {
            reader.execute(new Runnable() {
                @Override
                public void run() {
                    final List<Song> found = new ArrayList<>();
                    int big = 0;
                    Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME};
                    String where = MediaStore.Audio.Media.DURATION + " >= " + MIN_MS
                            + " AND " + MediaStore.Audio.Media.IS_RINGTONE + " = 0"
                            + " AND " + MediaStore.Audio.Media.IS_NOTIFICATION + " = 0"
                            + " AND " + MediaStore.Audio.Media.IS_ALARM + " = 0";
                    try (Cursor c = getContentResolver().query(base, cols, where, null,
                            MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                        while (c != null && c.moveToNext()) {
                            long size = c.getLong(4);
                            if (size <= 0) {
                                continue;
                            }
                            if (size > MAX_SONG_BYTES) {
                                big++;
                                continue;
                            }
                            String artist = c.getString(2);
                            if (artist == null || artist.contains("<unknown>")) {
                                artist = "";
                            }
                            String title = c.getString(1);
                            String name = c.getString(5) == null ? "" : c.getString(5);
                            found.add(new Song(c.getLong(0), title == null || title.isEmpty() ? name : title,
                                    artist, c.getLong(3), size, name, ContentUris.withAppendedId(base, c.getLong(0))));
                        }
                    } catch (RuntimeException e) {
                        // no access: the list stays empty and the screen says so
                    }
                    final int tooBig = big;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            all = found;
                            applyFilter();
                            if (found.isEmpty()) {
                                status.set("NO SONGS FOUND. CHECK THAT ARC LINK MAY SEE MUSIC AND AUDIO "
                                        + "(PHONE SETTINGS, APPS, ARC LINK, PERMISSIONS).", Hud.WARN);
                            } else {
                                status.set(found.size() + " SONGS. TAP ONE TO PLAY IT ON THE TV."
                                        + (tooBig > 0 ? " " + tooBig + " OVER 31 MB LEFT OUT." : ""), Hud.CYAN);
                                if (voiceQuery != null) {
                                    playWords(voiceQuery);
                                }
                            }
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            // the screen is closing
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadSongs();
            } else {
                status.set("ARC LINK CANNOT SEE YOUR MUSIC. ALLOW IT IN THE PHONE'S APP SETTINGS.", Hud.WARN);
            }
        }
    }

    private void applyFilter() {
        // every word typed (or spoken) must be somewhere in the title or the artist
        List<String> words = LocalSongs.words(search.getText().toString());
        List<Song> out = new ArrayList<>();
        for (Song s : all) {
            String hay = (s.title + " " + s.artist).toLowerCase(Locale.ROOT);
            boolean match = true;
            for (String w : words) {
                if (!hay.contains(w)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                out.add(s);
            }
        }
        shown = out;
        adapter.notifyDataSetChanged();
    }

    private void togglePick(long id) {
        if (!picked.remove(id)) {
            picked.add(id);
        }
        pickedButton.setText("PLAY PICKED (" + picked.size() + ")");
        pickedButton.setVisibility(picked.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void playPicked() {
        List<Song> q = new ArrayList<>();
        for (Song s : all) {
            if (picked.contains(s.id)) {
                q.add(s);
            }
        }
        if (!q.isEmpty()) {
            playQueue(q, 0);
        }
    }

    // ---------------------------------------------------------------- playing

    /** Starts a new queue at start. With shuffle on, the chosen song goes first and the rest follow in random order. */
    private void playQueue(List<Song> songs, int start) {
        if (songs.isEmpty() || start < 0 || start >= songs.size()) {
            return;
        }
        List<Song> q = new ArrayList<>(songs);
        int first = start;
        if (shuffle) {
            Song chosen = q.remove(start);
            Collections.shuffle(q);
            q.add(0, chosen);
            first = 0;
        }
        gen++;
        queue = q;
        session = String.format(Locale.US, "au%08x", new SecureRandom().nextInt());
        onTv = false;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                sent.clear();
            }
        });
        go(first);
    }

    private void go(int target) {
        List<Song> q = queue;
        if (q.isEmpty()) {
            return;
        }
        if (target >= q.size()) {
            if (!repeat) {
                status.set("END OF THE QUEUE.", Hud.CYAN);
                return;
            }
            target = 0;
        }
        qpos = Math.max(0, target);
        sawPlay = false;
        expectPos = qpos;
        expectUntil = System.currentTimeMillis() + EXPECT_MS;
        playing = true;
        showNow();
        status.set("SENDING TO THE TV...", Hud.CYAN);
        cast(qpos, ++gen, true);
    }

    private void next() {
        if (qpos >= 0) {
            go(qpos + 1);
        }
    }

    private void previous() {
        if (qpos < 0) {
            return;
        }
        if (seek.getProgress() > 3) {
            control("/audioseek", 0);
        } else {
            go(Math.max(0, qpos - 1));
        }
    }

    private void togglePlay() {
        if (qpos < 0 || !onTv) {
            return;
        }
        playing = !playing;
        playButton.setText(playing ? "PAUSE" : "PLAY");
        control("/audiotoggle", 0);
    }

    private void toggleShuffle() {
        shuffle = !shuffle;
        shuffleButton.setText(shuffle ? "SHUFFLE ON" : "SHUFFLE OFF");
        if (qpos >= 0) {
            status.set(shuffle ? "SHUFFLE ON FROM THE NEXT SONG YOU TAP." : "SHUFFLE OFF FROM THE NEXT SONG YOU TAP.",
                    Hud.CYAN);
        }
    }

    private void toggleRepeat() {
        repeat = !repeat;
        repeatButton.setText(repeat ? "REPEAT ON" : "REPEAT OFF");
    }

    private void stopCasting() {
        gen++;
        session = "";
        queue = new ArrayList<>();
        qpos = -1;
        onTv = false;
        playing = false;
        nowPanel.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        status.set("STOPPED.", Hud.CYAN);
        control("/audiostop", 0);
    }

    /** One-shot command to the TV on the worker thread; seconds is used by /audioseek. */
    private void control(final String path, final int seconds) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    TvClient.send(host, path, pin, new String[] {"X-Pos: " + seconds}, new byte[0]);
                } catch (IOException e) {
                    setStatus(gen, "COULD NOT REACH THE TV: " + e.getMessage(), Hud.WARN);
                }
            }
        });
    }

    private void showNow() {
        List<Song> q = queue;
        if (qpos < 0 || qpos >= q.size()) {
            return;
        }
        Song s = q.get(qpos);
        nowPanel.setVisibility(View.VISIBLE);
        nowTitle.setText(s.title);
        nowArtist.setText((s.artist.isEmpty() ? "" : s.artist + "   ") + (qpos + 1) + " / " + q.size());
        playButton.setText(playing ? "PAUSE" : "PLAY");
        seek.setMax(Math.max(1, (int) (s.durationMs / 1000)));
        seek.setProgress(0);
        showTime(0, seek.getMax());
        cover.setImageDrawable(null);
        adapter.notifyDataSetChanged();
        final Song song = s;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final byte[] jpg = coverFor(song);
                if (jpg == null) {
                    return;
                }
                final Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (b != null && isCurrent(song)) {
                            cover.setImageBitmap(b);
                        }
                    }
                });
            }
        });
    }

    private void showTime(int sec, int length) {
        nowTime.setText(clock(sec) + " / " + clock(length));
    }

    private static String clock(long seconds) {
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ---------------------------------------------------------------- sending to the TV

    /** Makes the TV hold (and, when showIt, play) this song plus the next few. Runs on the worker thread. */
    private void cast(final int target, final int myGen, final boolean showIt) {
        final List<Song> q = queue;
        final String sess = session;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (myGen != gen) {
                    return;
                }
                try {
                    sent.removeIf(k -> k < target - 1 || k > target + AHEAD);
                    ensureSent(q, sess, target);
                    if (myGen != gen) {
                        return;
                    }
                    if (showIt) {
                        playOnTv(q, sess, target);
                    }
                    setStatus(myGen, "ON THE TV", Hud.OK);
                    for (int k = target + 1; k <= target + AHEAD && k < q.size(); k++) {
                        if (myGen != gen) {
                            return;
                        }
                        ensureSent(q, sess, k);
                    }
                    if (target > 0 && myGen == gen) {
                        ensureSent(q, sess, target - 1);
                    }
                } catch (IOException e) {
                    setStatus(myGen, "COULD NOT REACH THE TV: " + e.getMessage(), Hud.WARN);
                }
            }
        });
    }

    private void playOnTv(List<Song> q, String sess, int idx) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            TvClient.Reply r = TvClient.send(host, "/audioplay", pin, headers(sess, idx, q.size(), null), new byte[0]);
            if (r.ok()) {
                onTv = true;
                return;
            }
            if (r.code != 409) {
                throw new IOException(describe(r));
            }
            // the TV player was closed since the last song: it no longer holds any of them
            sent.clear();
            ensureSent(q, sess, idx);
        }
        throw new IOException("the TV would not play the song");
    }

    private void ensureSent(List<Song> q, String sess, int idx) throws IOException {
        if (sent.contains(idx)) {
            return;
        }
        Song s = q.get(idx);
        try (InputStream in = getContentResolver().openInputStream(s.uri)) {
            if (in == null) {
                throw new IOException("a song could not be read");
            }
            TvClient.Reply r = TvClient.send(host, "/audioput", pin, headers(sess, idx, q.size(), s), in, s.size);
            if (!r.ok()) {
                throw new IOException(describe(r));
            }
        } catch (SecurityException e) {
            throw new IOException("a song could not be read");
        }
        sent.add(idx);
        byte[] jpg = coverFor(s);
        if (jpg != null) {
            try {
                TvClient.send(host, "/audiocover", pin, headers(sess, idx, q.size(), null), jpg);
            } catch (IOException e) {
                // the song is there; only its picture is missing
            }
        }
    }

    private static String[] headers(String sess, int idx, int n, Song s) {
        List<String> h = new ArrayList<>();
        h.add("X-Session: " + sess);
        h.add("X-Pos: " + (idx + 1));
        h.add("X-Total: " + n);
        if (s != null) {
            h.add("X-Title: " + encode(s.title));
            h.add("X-Artist: " + encode(s.artist));
            h.add("X-Name: " + encode(s.name));
        }
        return h.toArray(new String[0]);
    }

    private static String encode(String text) {
        String t = text.length() > 80 ? text.substring(0, 80) : text;
        try {
            return URLEncoder.encode(t, "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static String describe(TvClient.Reply r) {
        return r.text.isEmpty() ? "the TV answered " + r.code : r.text;
    }

    private void setStatus(final int myGen, final String text, final int color) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (myGen == gen || color == Hud.WARN) {
                    status.set(text, color);
                }
            }
        });
    }

    /** The cover picture inside the song file as a small JPEG, or null when it has none. */
    private byte[] coverFor(Song s) {
        synchronized (covers) {
            byte[] known = covers.get(s.id);
            if (known != null) {
                return known.length == 0 ? null : known;
            }
        }
        byte[] out = new byte[0];
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, s.uri);
            byte[] art = r.getEmbeddedPicture();
            if (art != null) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(art, 0, art.length, bounds);
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_EDGE) {
                    sample *= 2;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap b = BitmapFactory.decodeByteArray(art, 0, art.length, o);
                if (b != null) {
                    int edge = Math.max(b.getWidth(), b.getHeight());
                    if (edge > COVER_EDGE) {
                        float k = COVER_EDGE / (float) edge;
                        Bitmap scaled = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k),
                                Math.round(b.getHeight() * k), true);
                        b.recycle();
                        b = scaled;
                    }
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, 82, buf);
                    b.recycle();
                    if (buf.size() > 0 && buf.size() <= 500 * 1024) {
                        out = buf.toByteArray();
                    }
                }
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            out = new byte[0];
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // nothing left to release (release() declares IOException on newer SDKs)
            }
        }
        synchronized (covers) {
            if (covers.size() > 16) {
                covers.clear();
            }
            covers.put(s.id, out);
        }
        return out.length == 0 ? null : out;
    }

    // ---------------------------------------------------------------- the TV poll

    /** Asks the TV what it is playing, so the TV remote (pause, next, previous) is followed here. */
    private void pollTv() {
        if (polling || session.isEmpty()) {
            return;
        }
        polling = true;
        try {
            poller.execute(new Runnable() {
                @Override
                public void run() {
                    String text = null;
                    try {
                        TvClient.Reply r = TvClient.send(host, "/audiostate", pin, new String[0], new byte[0]);
                        if (r.ok()) {
                            text = r.text;
                        }
                    } catch (IOException e) {
                        // keep what is shown; the next poll tries again
                    }
                    final String answer = text;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            polling = false;
                            applyState(answer);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            polling = false;
        }
    }

    private void applyState(String text) {
        if (text == null || session.isEmpty()) {
            return;
        }
        if ("none".equals(text)) {
            if (onTv && System.currentTimeMillis() > expectUntil) {
                onTv = false;
                playing = false;
                status.set("THE TV CLOSED THE PLAYER.", Hud.WARN);
                playButton.setText("PLAY");
            }
            return;
        }
        String[] p = text.split(" ");
        if (p.length < 6 || !p[0].equals(session)) {
            return;
        }
        int tvPos;
        int sec;
        int length;
        try {
            tvPos = Integer.parseInt(p[1]) - 1;
            sec = Integer.parseInt(p[4]);
            length = Integer.parseInt(p[5]);
        } catch (NumberFormatException e) {
            return;
        }
        boolean tvPlaying = "1".equals(p[3]);
        if (System.currentTimeMillis() < expectUntil) {
            if (tvPos != expectPos) {
                return;
            }
            expectUntil = 0;
        }
        onTv = true;
        List<Song> q = queue;
        if (tvPos >= 0 && tvPos < q.size() && tvPos != qpos) {
            // the TV moved on by itself or with its remote: follow it and keep the next songs coming
            qpos = tvPos;
            sawPlay = false;
            playing = tvPlaying;
            showNow();
            cast(qpos, ++gen, false);
        }
        if (tvPlaying != playing) {
            playing = tvPlaying;
            playButton.setText(playing ? "PAUSE" : "PLAY");
        }
        if (!dragging && length > 0) {
            seek.setMax(length);
            seek.setProgress(sec);
            showTime(sec, length);
        }
        if (tvPlaying && length > 0) {
            sawPlay = true;
        }
        if (sawPlay && !tvPlaying && length == 0 && tvPos == q.size() - 1) {
            // the TV played the last song of the queue
            sawPlay = false;
            if (repeat) {
                go(0);
            } else {
                status.set("FINISHED THE QUEUE.", Hud.CYAN);
            }
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
