package io.archud.link;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** MULTIMEDIA: everything you send to the TV to look at or listen to (photos, video, link, audio). */
public class MediaActivity extends Activity {
    private static final int PICK_VIDEO = 7;

    private float density;
    private EditText linkField;
    private LinearLayout linkPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        Hud.bars(this);
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Hud.background(this));
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(32));
        scroll.addView(col);
        setContentView(scroll);

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
        names.addView(Hud.label(this, "MULTIMEDIA", 22, Hud.WHITE, true));
        names.addView(Hud.label(this, "SEND TO THE TV", 11, Hud.CYAN, false));
        brand.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        brand.addView(new Hud.Ring(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        col.addView(brand);
        col.addView(Hud.rule(this));

        LinearLayout tilesA = new LinearLayout(this);
        tilesA.setOrientation(LinearLayout.HORIZONTAL);
        tilesA.addView(Hud.tile(this, Hud.ICON_PHOTO, "PHOTOS", true, this::pickPhotos));
        tilesA.addView(Hud.tile(this, Hud.ICON_VIDEO, "VIDEO", true, this::pickVideo));
        LinearLayout tilesB = new LinearLayout(this);
        tilesB.setOrientation(LinearLayout.HORIZONTAL);
        linkPanel = Hud.panel(this, "SEND A LINK");
        tilesB.addView(Hud.tile(this, Hud.ICON_LINK, "LINK", true, new Runnable() {
            @Override
            public void run() {
                boolean show = linkPanel.getVisibility() != View.VISIBLE;
                linkPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    scroll.post(() -> scroll.smoothScrollTo(0, linkPanel.getTop()));
                    linkField.requestFocus();
                }
            }
        }));
        tilesB.addView(Hud.tile(this, Hud.ICON_AUDIO, "AUDIO SOON", false, this::pickVideo));

        linkField = Hud.field(this, "Paste a YouTube link or web address", InputType.TYPE_TEXT_VARIATION_URI);
        linkPanel.addView(linkField);
        linkPanel.addView(Hud.button(this, "SEND LINK TO THE TV", Hud.SOLID, false, this::sendLink));
        linkPanel.addView(Hud.label(this, "Tip: in YouTube, Chrome or Gallery use Share, then ARC LINK (TV).",
                11, Hud.DIM, false));
        linkPanel.setVisibility(View.GONE);

        col.addView(tilesA);
        col.addView(tilesB, Hud.stack(this));
        col.addView(linkPanel, Hud.stack(this));
    }

    private boolean paired() {
        if (Store.paired(this)) {
            return true;
        }
        new AlertDialog.Builder(this)
                .setMessage("Pair with the TV first: go back and open the cog on the home screen.")
                .setPositiveButton("OK", null)
                .show();
        return false;
    }

    private void pickPhotos() {
        if (paired()) {
            startActivity(new Intent(this, PhotoActivity.class).putExtra(PhotoActivity.EXTRA_GALLERY, true));
        }
    }

    private void pickVideo() {
        if (!paired()) {
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*");
        startActivityForResult(pick, PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // not every provider offers a persistable grant; the one-time grant is enough
            }
            Sender.video(this, uri);
        }
    }

    private void sendLink() {
        if (paired()) {
            Sender.link(this, linkField.getText().toString());
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
