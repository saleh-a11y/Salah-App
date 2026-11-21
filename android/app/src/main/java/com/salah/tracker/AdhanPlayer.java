package com.salah.tracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.media.AudioManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.Build;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

/** Schedules the adhān as exact alarms; each alarm starts AdhanService, which plays the full recording. */
@CapacitorPlugin(name = "AdhanPlayer")
public class AdhanPlayer extends Plugin {
    static final String PREFS = "adhan_player";
    static final int RC_BASE = 50000;

    @PluginMethod
    public void setSchedule(PluginCall call) {
        JSArray items = call.getArray("items", new JSArray());
        try {
            store(getContext(), items.toString());
            schedule(getContext());
            JSObject r = new JSObject();
            r.put("exact", canExact(getContext()));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Couldn't schedule the adhān: " + e.getMessage());
        }
    }

    @PluginMethod
    public void test(PluginCall call) {
        Context c = getContext();
        long at = System.currentTimeMillis() + call.getInt("delay", 5000);
        PendingIntent pi = alarmIntent(c, 999, call.getString("title", "Adhān test"), call.getString("body", "This is how the adhān will sound at prayer time."), "adhan", null, -1);
        setAlarm(c, at, pi);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent i = new Intent(getContext(), AdhanService.class).setAction(AdhanService.ACTION_STOP);
        try { getContext().startService(i); } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        r.put("exact", canExact(c));
        r.put("playing", AdhanService.playing);
        r.put("maker", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase());
        r.put("sdk", Build.VERSION.SDK_INT);
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString("items", "[]"));
            long now = System.currentTimeMillis(), best = Long.MAX_VALUE; JSONObject next = null; int future = 0;
            for (int k = 0; k < a.length(); k++) { JSONObject o = a.getJSONObject(k); long at = o.optLong("at"); if (at > now) { future++; if (at < best) { best = at; next = o; } } }
            r.put("scheduled", future);
            if (next != null) r.put("next", new JSObject(next.toString()));
            r.put("log", new JSArray(p.getString("log", "[]")));
        } catch (Exception ignored) {}
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            r.put("ringer", am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL ? "sound" : am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "silent");
            r.put("alarmVol", am.getStreamVolume(AudioManager.STREAM_ALARM));
            r.put("alarmMax", am.getStreamMaxVolume(AudioManager.STREAM_ALARM));
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            r.put("notifications", nm.areNotificationsEnabled());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = nm.getNotificationChannel(AdhanService.CH_PLAY);
                r.put("channelOff", ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            r.put("batteryFree", pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()));
        } else r.put("batteryFree", true);
        call.resolve(r);
    }

    @PluginMethod
    public void openBattery(PluginCall call) {
        Context c = getContext();
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try { Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i); } catch (Exception ignored) {}
        }
        call.resolve();
    }

    /** Ticks made from notification buttons while the app was closed; handed over once. */
    @PluginMethod
    public void takeTicks(PluginCall call) {
        SharedPreferences p = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSObject r = new JSObject();
        try { r.put("ticks", new JSArray(p.getString("ticks", "[]"))); } catch (Exception e) { r.put("ticks", new JSArray()); }
        p.edit().remove("ticks").apply();
        call.resolve(r);
    }

    /** The prayers already ticked in the app, so reminders for them are skipped. */
    @PluginMethod
    public void setDone(PluginCall call) {
        JSArray a = call.getArray("done", new JSArray());
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("done", a.toString()).apply();
        call.resolve();
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Context c = getContext();
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void openExact(PluginCall call) {
        Context c = getContext();
        try {
            Intent i = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.getPackageName()))
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void clearLog(PluginCall call) {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("log").apply();
        call.resolve();
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    static void store(Context c, String json) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // cancel everything scheduled before
        try {
            JSONArray old = new JSONArray(p.getString("items", "[]"));
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            for (int k = 0; k < old.length(); k++) {
                int id = old.getJSONObject(k).optInt("id");
                am.cancel(alarmIntent(c, id, "", "", "", null, -1));
            }
        } catch (Exception ignored) {}
        p.edit().putString("items", json).apply();
    }

    /** (Re)sets an alarm for every future item that was stored. Also used after a reboot. */
    static void schedule(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(p.getString("items", "[]"));
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                long at = o.optLong("at");
                if (at <= now) continue;
                String kind = o.optString("kind", "adhan"), d = o.optString("d", null);
                int p = o.optInt("p", -1);
                if (!kind.equals("adhan") && Ticks.has(c, d, p)) continue;
                setAlarm(c, at, alarmIntent(c, o.optInt("id"), o.optString("title"), o.optString("body"), kind, d, p));
            }
        } catch (Exception ignored) {}
    }

    static PendingIntent alarmIntent(Context c, int id, String title, String body, String kind, String d, int p) {
        Intent i = new Intent(c, AdhanReceiver.class).setAction("com.salah.tracker.ADHAN." + id);
        i.putExtra("id", id).putExtra("title", title).putExtra("body", body).putExtra("kind", kind).putExtra("d", d).putExtra("p", p);
        return PendingIntent.getBroadcast(c, RC_BASE + id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void setAlarm(Context c, long at, PendingIntent pi) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (canExact(c)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }
}
