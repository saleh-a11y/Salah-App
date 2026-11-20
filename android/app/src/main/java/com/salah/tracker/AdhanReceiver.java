package com.salah.tracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Fires at adhān time (starts the player) and after a reboot or app update (puts the alarms back). */
public class AdhanReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction() == null ? "" : intent.getAction();
        if (a.equals(Intent.ACTION_BOOT_COMPLETED) || a.equals(Intent.ACTION_LOCKED_BOOT_COMPLETED) || a.equals(Intent.ACTION_MY_PACKAGE_REPLACED)
                || a.equals("android.intent.action.QUICKBOOT_POWERON") || a.equals(Intent.ACTION_TIME_CHANGED) || a.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
            AdhanPlayer.schedule(c);
            return;
        }
        Intent s = new Intent(c, AdhanService.class)
                .putExtra("id", intent.getIntExtra("id", 0))
                .putExtra("title", intent.getStringExtra("title"))
                .putExtra("body", intent.getStringExtra("body"));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception e) {
            // the phone refused to start the player: still show the adhān notification with the phone's own sound
            AdhanService.plainNotice(c, intent.getIntExtra("id", 0), intent.getStringExtra("title"), intent.getStringExtra("body"), true);
        }
    }
}
