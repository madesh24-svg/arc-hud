package io.archud;

import android.service.dreams.DreamService;

/** Screensaver entry point: the same HUD, shown when the device goes idle. */
public class HudDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(new HudView(this));
    }
}
