package io.archud.link;

import android.app.Activity;

/**
 * Base for every ARC LINK screen: Jarvis listens only while one of them is in front, and stops the moment
 * the app is left (see Jarvis). A screen that has a Hud.Status line for Jarvis returns it from voiceLine().
 */
abstract class JarvisActivity extends Activity {
    /** The line where Jarvis shows what it is doing, or null on screens that have none. */
    protected Hud.Status voiceLine() {
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Jarvis.attach(this, voiceLine());
    }

    @Override
    protected void onPause() {
        Jarvis.detach(this);
        super.onPause();
    }
}
