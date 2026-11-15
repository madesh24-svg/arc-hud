package io.archud.link;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires every ~10 minutes (while allowed by Doze) and reschedules itself. */
public final class HeartbeatReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Heartbeat.sendInBackground();
        Heartbeat.schedule(context);
    }
}
