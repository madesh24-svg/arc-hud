package io.archud.link;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** The "ARC LINK (TV)" entry in the phone's share sheet. Sends and closes at once. */
public class ShareActivity extends Activity {
    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        if (!Store.paired(this)) {
            Sender.say(this, "Open ARC LINK once and pair with the TV first.");
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            String type = in.getType();
            Uri stream = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null && type != null && type.startsWith("video/")) {
                Sender.video(this, stream);
            } else if (in.getStringExtra(Intent.EXTRA_TEXT) != null) {
                Sender.link(this, in.getStringExtra(Intent.EXTRA_TEXT));
            } else {
                Sender.say(this, "ARC LINK can send links and videos.");
            }
        }
        finish();
    }
}
