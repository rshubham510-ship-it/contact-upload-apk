package com.surrogacyguide.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-uploads after the phone restarts, so one install keeps reporting. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (AutoSync.hasPermission(context)) {
            AutoSync.run(context, true);
        }
    }
}
