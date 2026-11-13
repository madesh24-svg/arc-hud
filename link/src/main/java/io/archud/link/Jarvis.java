package io.archud.link;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Window;
import android.view.WindowManager;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Jarvis: listens through the phone's own speech recognition, only while an ARC LINK screen is in front.
 * Asleep, it waits for "Jarvis" or "arc arc" (see JarvisBrain); awake, every sentence is a command, until the app
 * is closed (or until the "go back to sleep after" time in settings). A sentence that is not a command is
 * ignored. Commands run on the phone or are sent to the TV over the same PIN-protected link as everything else.
 * Everything here runs on the main thread except the calls to the TV.
 */
final class Jarvis {
    private static final long LEAVE_GRACE_MS = 3000;
    private static final long APPS_KEEP_MS = 5 * 60 * 1000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Random RANDOM = new Random();
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        ALIASES.put("youtube", "smarttube");
        ALIASES.put("yt", "smarttube");
        ALIASES.put("youtubetv", "smarttube");
        ALIASES.put("browser", "browse");
        ALIASES.put("internet", "browse");
        ALIASES.put("web", "browse");
        ALIASES.put("webbrowser", "browse");
    }

    private static volatile boolean awake;
    private static Activity host;
    private static Hud.Status sink;
    private static Context app;
    private static SpeechRecognizer recognizer;
    private static TextToSpeech tts;
    private static boolean ttsReady;
    private static String pendingSpeech;
    private static boolean wanted;
    private static boolean listening;
    private static boolean speaking;
    private static boolean muted;
    private static boolean addedKeepOn;
    private static boolean netWarned;
    private static long lastCommandAt;
    private static long errorSince;
    private static int errorCount;
    private static int utterance;
    /** What was last played from YouTube (so "next" or "another one" moves to the next result), and how. */
    private static volatile String lastQuery = "";
    private static volatile int lastIndex;
    private static volatile int lastSource;
    private static volatile List<String[]> apps;
    private static volatile long appsAt;

    private static final Runnable RESTART = new Runnable() {
        @Override
        public void run() {
            beginSession();
        }
    };

    private static final Runnable LEFT_APP = new Runnable() {
        @Override
        public void run() {
            if (host == null) {
                awake = false;
                destroyRecognizer();
                if (tts != null) {
                    tts.shutdown();
                    tts = null;
                    ttsReady = false;
                }
            }
        }
    };

    private static final Runnable IDLE = new Runnable() {
        @Override
        public void run() {
            int minutes = app == null ? 0 : Store.sleepMinutes(app);
            if (awake && minutes > 0) {
                long left = lastCommandAt + minutes * 60_000L - System.currentTimeMillis();
                if (left <= 0) {
                    goSleep("Going back to sleep.");
                } else {
                    MAIN.postDelayed(this, left);
                }
            }
        }
    };

    private static final RecognitionListener LISTENER = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            // nothing to do: the recognizer is ready
        }

        @Override
        public void onBeginningOfSpeech() {
            // nothing to do: speech has started
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            // nothing to do: no level meter
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
            // nothing to do: raw audio is not kept
        }

        @Override
        public void onEndOfSpeech() {
            // nothing to do: the result follows
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            // nothing to do: only whole sentences are acted on
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
            // nothing to do: no extra events used
        }

        @Override
        public void onResults(Bundle results) {
            listening = false;
            errorCount = 0;
            ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            handleHeard(heard);
            if (!speaking) {
                schedule(120);
            }
        }

        @Override
        public void onError(int error) {
            listening = false;
            if (!wanted) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - errorSince > 8000) {
                errorSince = now;
                errorCount = 0;
            }
            errorCount++;
            long delay = 800;
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                delay = 100;
            } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                destroyRecognizer();
                delay = 700;
            } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wanted = false;
                show("JARVIS NEEDS MICROPHONE ACCESS (PHONE SETTINGS, APPS, ARC LINK, PERMISSIONS).", Hud.WARN);
                return;
            } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || error == SpeechRecognizer.ERROR_SERVER) {
                if (!netWarned) {
                    netWarned = true;
                    show("SPEECH RECOGNITION NEEDS INTERNET (OR AN OFFLINE ENGLISH PACK IN THE GOOGLE APP).",
                            Hud.WARN);
                }
                delay = 3000;
            } else {
                destroyRecognizer();
            }
            schedule(errorCount > 8 ? 6000 : delay);
        }
    };

    private Jarvis() {
    }

    static boolean awake() {
        return awake;
    }

    static boolean hasMic(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- lifecycle

    /** An ARC LINK screen came to the front. */
    static void attach(Activity a, Hud.Status line) {
        MAIN.removeCallbacks(LEFT_APP);
        host = a;
        sink = line;
        app = a.getApplicationContext();
        start();
    }

    /** An ARC LINK screen left the front. Jarvis goes to sleep unless another screen takes over within moments. */
    static void detach(Activity a) {
        if (host != a) {
            return;
        }
        stop();
        if (addedKeepOn) {
            a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            addedKeepOn = false;
        }
        host = null;
        sink = null;
        MAIN.postDelayed(LEFT_APP, LEAVE_GRACE_MS);
    }

    /** The settings changed (switched on or off): apply them now. */
    static void settingsChanged() {
        if (host != null) {
            stop();
            start();
        }
    }

    private static void start() {
        if (host == null) {
            return;
        }
        if (!Store.voiceOn(app)) {
            show("JARVIS IS OFF. TAP THE JARVIS BOX TO TURN IT ON.", Hud.DIM);
            return;
        }
        if (!hasMic(app)) {
            show("JARVIS NEEDS MICROPHONE ACCESS. TAP THE JARVIS BOX TO ALLOW IT.", Hud.GOLD);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            show("THIS PHONE HAS NO SPEECH RECOGNITION. INSTALL OR ENABLE THE GOOGLE APP.", Hud.WARN);
            return;
        }
        Window w = host.getWindow();
        if ((w.getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            addedKeepOn = true;
        }
        wanted = true;
        netWarned = false;
        showState();
        beginSession();
    }

    private static void stop() {
        wanted = false;
        speaking = false;
        MAIN.removeCallbacks(RESTART);
        if (recognizer != null && listening) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
                // already stopped
            }
        }
        listening = false;
        mute(false);
        if (tts != null) {
            tts.stop();
        }
    }

    private static void schedule(long ms) {
        MAIN.removeCallbacks(RESTART);
        MAIN.postDelayed(RESTART, ms);
    }

    private static void beginSession() {
        if (!wanted || host == null || speaking || listening) {
            return;
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(app);
                recognizer.setRecognitionListener(LISTENER);
            }
            mute(true);
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.getPackageName());
            listening = true;
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            listening = false;
            destroyRecognizer();
            schedule(1500);
        }
    }

    private static void destroyRecognizer() {
        listening = false;
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
                // already gone
            }
            recognizer = null;
        }
    }

    /** Silences the phone's media sound while listening, which also hides the recognizer's start beep on most phones. */
    private static void mute(boolean on) {
        if (on == muted || app == null) {
            return;
        }
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, on ? AudioManager.ADJUST_MUTE
                    : AudioManager.ADJUST_UNMUTE, 0);
            muted = on;
        } catch (RuntimeException ignored) {
            // the phone would not let the volume change: the beeps may be heard
        }
    }

    // ---------------------------------------------------------------- what was heard

    private static void handleHeard(List<String> heard) {
        if (heard == null || heard.isEmpty()) {
            return;
        }
        if (!awake) {
            for (String h : heard) {
                String rest = JarvisBrain.afterWake(h);
                if (rest != null) {
                    wake(rest);
                    return;
                }
            }
            show("ASLEEP. HEARD: \"" + clip(heard.get(0)) + "\"", Hud.DIM);
            return;
        }
        // awake: take the first of the recognizer's guesses that is a real command
        JarvisBrain.Cmd best = null;
        String bestText = heard.get(0);
        boolean addressed = false;
        for (String h : heard) {
            String rest = JarvisBrain.afterWake(h);
            String text = rest != null ? rest : h;
            if (rest != null && rest.isEmpty()) {
                if (best == null) {
                    bestText = "";
                    addressed = true;
                }
                continue;
            }
            JarvisBrain.Cmd c = JarvisBrain.parse(text);
            if (c.type != JarvisBrain.UNKNOWN && (best == null || (best.soft && !c.soft))) {
                best = c;
                bestText = text;
                addressed = rest != null;
                if (!c.soft) {
                    break;
                }
            }
        }
        if (best == null) {
            if (bestText.isEmpty() && addressed) {
                lastCommandAt = System.currentTimeMillis();
                say("Yes, Maddy?");
            } else {
                show("HEARD: \"" + clip(heard.get(0)) + "\"  (NOT A COMMAND)", Hud.GOLD);
            }
            return;
        }
        lastCommandAt = System.currentTimeMillis();
        run(best, bestText, addressed);
    }

    private static void wake(String rest) {
        awake = true;
        lastCommandAt = System.currentTimeMillis();
        MAIN.removeCallbacks(IDLE);
        if (Store.sleepMinutes(app) > 0) {
            MAIN.postDelayed(IDLE, Store.sleepMinutes(app) * 60_000L);
        }
        showState();
        if (rest.isEmpty()) {
            say("Yes, Maddy?");
            return;
        }
        JarvisBrain.Cmd c = JarvisBrain.parse(rest);
        if (c.type == JarvisBrain.UNKNOWN) {
            say("Yes? I did not catch that.");
        } else {
            run(c, rest, true);
        }
    }

    private static void goSleep(String speech) {
        awake = false;
        MAIN.removeCallbacks(IDLE);
        showState();
        say(speech);
    }

    private static void run(final JarvisBrain.Cmd c, String text, boolean addressed) {
        show("HEARD: \"" + clip(text) + "\"", Hud.CYAN);
        switch (c.type) {
            case JarvisBrain.SLEEP:
                goSleep("Going to sleep. Say Jarvis when you need me.");
                return;
            case JarvisBrain.HELP:
                say("You can say: open SmartTube. Play a song from a movie. Play a song by name. Show my photos. "
                        + "Next. Stop. Or go to sleep.");
                return;
            case JarvisBrain.ASK_PLAY:
                say("Play what?");
                return;
            case JarvisBrain.NEEDS_REMOTE:
                say("I cannot do that yet. Volume and controls inside other apps need the remote feature.");
                return;
            case JarvisBrain.PHOTOS:
                if (!Store.paired(app)) {
                    say("Pair with the TV first.");
                    return;
                }
                openPhotos();
                say("Opening your photos.");
                return;
            case JarvisBrain.OPEN_AUDIO:
                openAudio(null);
                say("Opening your music.");
                return;
            case JarvisBrain.UNKNOWN:
                if (addressed) {
                    say("Sorry, I did not get that.");
                }
                return;
            default:
                break;
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                act(c);
            }
        });
    }

    // ---------------------------------------------------------------- doing it (not on the main thread)

    private static void act(JarvisBrain.Cmd c) {
        if (!Store.paired(app)) {
            say("Pair with the TV first.");
            return;
        }
        String tv = Store.host(app);
        String pin = Store.pin(app);
        try {
            switch (c.type) {
                case JarvisBrain.OPEN_APP:
                    openApp(tv, pin, c);
                    break;
                case JarvisBrain.PLAY:
                    play(tv, pin, c);
                    break;
                case JarvisBrain.NEXT:
                    next(tv, pin);
                    break;
                case JarvisBrain.PREV:
                    audioControl(tv, pin, "/audioprev");
                    break;
                case JarvisBrain.TOGGLE:
                    audioControl(tv, pin, "/audiotoggle");
                    break;
                case JarvisBrain.STOP:
                    TvClient.send(tv, "/imagestop", pin, new String[0], new byte[0]);
                    TvClient.send(tv, "/audiostop", pin, new String[0], new byte[0]);
                    say("Stopped.");
                    break;
                default:
                    break;
            }
        } catch (IOException e) {
            say("I could not reach the TV.");
        }
    }

    private static void openApp(String tv, String pin, JarvisBrain.Cmd c) throws IOException {
        List<String[]> list = appsOnTv(tv, pin);
        String[] found = matchApp(list, c.arg);
        if (found == null) {
            if (!c.soft) {
                say("I could not find " + c.arg + " on the TV.");
            }
            return;
        }
        TvClient.Reply r = TvClient.post(tv, "/launch", pin, "package=" + URLEncoder.encode(found[1], "UTF-8"));
        say(r.ok() ? "Opening " + found[0] + "." : "The TV could not open " + found[0] + ".");
    }

    private static void play(String tv, String pin, JarvisBrain.Cmd c) throws IOException {
        if (c.local != null && LocalSongs.has(app, c.local)) {
            lastSource = 2;
            openAudio(c.local);
            say("Playing " + c.local + " from your phone.");
            return;
        }
        youtube(tv, pin, c.arg, c.random ? RANDOM.nextInt(5) : 0);
    }

    private static void youtube(String tv, String pin, String query, int n) throws IOException {
        TvClient.Reply r = TvClient.post(tv, "/ytplay", pin,
                "q=" + URLEncoder.encode(query, "UTF-8") + "&n=" + n);
        if (r.ok()) {
            lastQuery = query;
            lastIndex = n;
            lastSource = 1;
            say(r.text.length() > 100 ? r.text.substring(0, 100) : r.text);
        } else {
            say(r.text.isEmpty() ? "SmartTube did not answer." : r.text);
        }
    }

    private static void next(String tv, String pin) throws IOException {
        if (lastSource == 1 && !lastQuery.isEmpty()) {
            youtube(tv, pin, lastQuery, lastIndex + 1);
            return;
        }
        TvClient.Reply r = TvClient.send(tv, "/audionext", pin, new String[] {"X-Pos: 0"}, new byte[0]);
        if (r.code == 409) {
            if (!lastQuery.isEmpty()) {
                youtube(tv, pin, lastQuery, lastIndex + 1);
            } else {
                say("Nothing is playing yet.");
            }
        }
    }

    private static void audioControl(String tv, String pin, String path) throws IOException {
        TvClient.Reply r = TvClient.send(tv, path, pin, new String[] {"X-Pos: 0"}, new byte[0]);
        if (r.code == 409) {
            say("Nothing is playing in ARC AUDIO. For other apps I need the remote feature.");
        }
    }

    // ---------------------------------------------------------------- the TV's apps

    private static List<String[]> appsOnTv(String tv, String pin) throws IOException {
        List<String[]> cached = apps;
        if (cached != null && System.currentTimeMillis() - appsAt < APPS_KEEP_MS) {
            return cached;
        }
        TvClient.Reply r = TvClient.post(tv, "/apps", pin, "");
        List<String[]> list = new ArrayList<>();
        if (r.ok()) {
            for (String line : r.text.split("\n")) {
                String[] parts = line.split("\t", 2);
                if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
                    list.add(new String[] {parts[0].trim(), parts[1].trim()});
                }
            }
        }
        if (!list.isEmpty()) {
            apps = list;
            appsAt = System.currentTimeMillis();
        }
        return list;
    }

    /** The app whose name best matches what was said (name, package), or null. */
    private static String[] matchApp(List<String[]> list, String said) {
        String q = key(said);
        if (q.isEmpty()) {
            return null;
        }
        String[] best = bestApp(list, q);
        if (best == null && ALIASES.containsKey(q)) {
            best = bestApp(list, ALIASES.get(q));
        }
        return best;
    }

    private static String[] bestApp(List<String[]> list, String q) {
        String[] best = null;
        int bestScore = 0;
        for (String[] a : list) {
            String l = key(a[0]);
            int score = 0;
            if (l.equals(q)) {
                score = 100;
            } else if ((q.length() >= 3 && l.contains(q)) || (l.length() >= 4 && q.contains(l))) {
                score = 80;
            } else if (q.length() >= 5 && distance(l, q) <= 2) {
                score = 60;
            }
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best;
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ---------------------------------------------------------------- screens

    private static void openPhotos() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Activity h = host;
                if (h != null) {
                    h.startActivity(new Intent(h, PhotoActivity.class).putExtra(PhotoActivity.EXTRA_GALLERY, true));
                }
            }
        });
    }

    private static void openAudio(final String query) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Activity h = host;
                if (h != null) {
                    Intent i = new Intent(h, AudioActivity.class);
                    if (query != null) {
                        i.putExtra(AudioActivity.EXTRA_PLAY_QUERY, query);
                    }
                    h.startActivity(i);
                }
            }
        });
    }

    // ---------------------------------------------------------------- showing and speaking

    private static void showState() {
        if (awake) {
            show("AWAKE. LISTENING FOR YOUR COMMAND...", Hud.GOLD);
        } else {
            show("ASLEEP. SAY \"JARVIS\" OR \"ARC ARC\".", Hud.CYAN);
        }
    }

    private static void show(String text, int color) {
        Hud.Status s = sink;
        if (s != null) {
            s.set(text, color);
        }
    }

    private static String clip(String text) {
        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }

    /** Shows what Jarvis says and, when spoken replies are on, says it. Safe from any thread. */
    private static void say(final String text) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                sayNow(text);
            }
        });
    }

    private static void sayNow(String text) {
        show("JARVIS: " + text, Hud.CYAN);
        if (app == null || host == null || !Store.spoken(app)) {
            return;
        }
        if (tts == null) {
            pendingSpeech = text;
            tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    if (ttsReady) {
                        int r = tts.setLanguage(new Locale("en", "IN"));
                        if (r < 0) {
                            tts.setLanguage(Locale.US);
                        }
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override
                            public void onStart(String utteranceId) {
                                // nothing to do: speech has started
                            }

                            @Override
                            public void onDone(String utteranceId) {
                                MAIN.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        speaking = false;
                                        if (wanted) {
                                            schedule(250);
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onError(String utteranceId) {
                                onDone(utteranceId);
                            }
                        });
                        String p = pendingSpeech;
                        pendingSpeech = null;
                        if (p != null) {
                            speak(p);
                        }
                    }
                }
            });
            return;
        }
        if (ttsReady) {
            speak(text);
        } else {
            pendingSpeech = text;
        }
    }

    /** Stops listening while talking (so Jarvis does not hear itself), then listening resumes in onDone. */
    private static void speak(String text) {
        if (tts == null || !ttsReady) {
            return;
        }
        speaking = true;
        MAIN.removeCallbacks(RESTART);
        if (recognizer != null && listening) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
                // already stopped
            }
            listening = false;
        }
        mute(false);
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis" + (++utterance));
        if (result != TextToSpeech.SUCCESS) {
            speaking = false;
            if (wanted) {
                schedule(250);
            }
        }
    }
}
