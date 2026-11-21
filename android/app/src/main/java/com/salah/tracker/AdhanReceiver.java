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
        String kind = intent.getStringExtra("kind"), d = intent.getStringExtra("d");
        int p = intent.getIntExtra("p", -1);
        if (kind != null && !kind.equals("adhan")) {
            // a reminder: skipped if the prayer was ticked in the meantime
            if (!Ticks.has(c, d, p)) Ticks.remind(c, intent.getIntExtra("id", 0), intent.getStringExtra("title"), intent.getStringExtra("body"), d, p);
            return;
        }
        AdhanService.log(c, intent.getIntExtra("id", 0), intent.getStringExtra("title"), "alarm went off");
        Intent s = new Intent(c, AdhanService.class)
                .putExtra("id", intent.getIntExtra("id", 0))
                .putExtra("title", intent.getStringExtra("title"))
                .putExtra("body", intent.getStringExtra("body"))
                .putExtra("d", d).putExtra("p", p);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception e) {
            AdhanService.log(c, intent.getIntExtra("id", 0), intent.getStringExtra("title"), "phone refused to start the player: " + e.getClass().getSimpleName());
            // the phone refused to start the player: still show the adhān notification with the phone's own sound
            AdhanService.plainNotice(c, intent.getIntExtra("id", 0), intent.getStringExtra("title"), intent.getStringExtra("body"), true);
        }
    }
}
