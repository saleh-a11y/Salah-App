package com.salah.tracker;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;

/** The buttons on prayer notifications: "In jamā'ah", "Alone" and "Later". Works with the app closed;
 *  the app picks the ticks up the next time it opens. */
public class Ticks extends BroadcastReceiver {
    static final String ACTION_TICK = "com.salah.tracker.TICK", ACTION_LATER = "com.salah.tracker.LATER";
    static final String CH_REMIND = "prayer_remind";
    static final String[] NAMES = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};

    @Override
    public void onReceive(Context c, Intent in) {
        String a = in.getAction() == null ? "" : in.getAction();
        String d = in.getStringExtra("d");
        int p = in.getIntExtra("p", -1), nid = in.getIntExtra("nid", 0);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (a.equals(ACTION_TICK) && d != null && p >= 0) {
            record(c, d, p, in.getBooleanExtra("j", false));
            if (AdhanService.playing) { try { c.startService(new Intent(c, AdhanService.class).setAction(AdhanService.ACTION_STOP)); } catch (Exception ignored) {} }
            if (nm != null) nm.cancel(nid);
            AdhanService.log(c, p, NAMES[Math.max(0, Math.min(4, p))], in.getBooleanExtra("j", false) ? "ticked from the notification (jamā'ah)" : "ticked from the notification");
        } else if (a.equals(ACTION_LATER) && d != null && p >= 0) {
            if (nm != null) nm.cancel(nid);
            int mins = in.getIntExtra("mins", 15);
            String n = NAMES[Math.max(0, Math.min(4, p))];
            AdhanPlayer.setAlarm(c, System.currentTimeMillis() + mins * 60000L,
                    AdhanPlayer.alarmIntent(c, 950 + p, "Did you pray " + n + "?", "Tap a button to tick it.", "ask", d, p));
        }
    }

    static synchronized void record(Context c, String d, int p, boolean j) {
        try {
            SharedPreferences sp = c.getSharedPreferences(AdhanPlayer.PREFS, Context.MODE_PRIVATE);
            JSONArray t = new JSONArray(sp.getString("ticks", "[]"));
            JSONObject o = new JSONObject();
            o.put("d", d); o.put("p", p); o.put("j", j); o.put("t", System.currentTimeMillis());
            t.put(o);
            JSONArray done = new JSONArray(sp.getString("done", "[]")), keep = new JSONArray();
            for (int k = Math.max(0, done.length() - 59); k < done.length(); k++) keep.put(done.get(k));
            keep.put(d + ":" + p);
            sp.edit().putString("ticks", t.toString()).putString("done", keep.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** True if this prayer was already ticked (in the app or from a notification). */
    static boolean has(Context c, String d, int p) {
        if (d == null || p < 0) return false;
        try {
            JSONArray done = new JSONArray(c.getSharedPreferences(AdhanPlayer.PREFS, Context.MODE_PRIVATE).getString("done", "[]"));
            for (int k = 0; k < done.length(); k++) if ((d + ":" + p).equals(done.getString(k))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    static PendingIntent pi(Context c, String action, int nid, String d, int p, boolean j, int code) {
        Intent i = new Intent(c, Ticks.class).setAction(action + "." + nid + "." + code)
                .putExtra("d", d).putExtra("p", p).putExtra("j", j).putExtra("nid", nid);
        return PendingIntent.getBroadcast(c, 60000 + nid * 4 + code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Adds the tick buttons; "Later" only when there's room. */
    static void actions(Context c, NotificationCompat.Builder b, int nid, String d, int p, boolean later) {
        if (d == null || p < 0) return;
        b.addAction(0, "✓ In jamā'ah", pi(c, ACTION_TICK, nid, d, p, true, 1));
        b.addAction(0, "✓ Prayed", pi(c, ACTION_TICK, nid, d, p, false, 2));
        if (later) b.addAction(0, "Later", pi(c, ACTION_LATER, nid, d, p, false, 3));
    }

    /** A reminder ("Asr ends in 10 minutes", "Did you pray Asr?") with the tick buttons. */
    static void remind(Context c, int id, String title, String body, String d, int p) {
        AdhanService.channels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        int nid = 7000 + id;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_REMIND)
                .setSmallIcon(R.drawable.ic_stat_salah).setColor(AdhanService.COLOR)
                .setContentTitle(title).setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(AdhanService.openApp(c)).setAutoCancel(true);
        actions(c, b, nid, d, p, true);
        nm.notify(nid, b.build());
    }
}
