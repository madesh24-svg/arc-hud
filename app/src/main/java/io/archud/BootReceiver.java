package io.archud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings ARC LINK up when the TV starts, so the phone can reach it without opening the app first. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LinkServer.sync(context);
    }
}
