package com.salah.tracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
        PendingIntent pi = alarmIntent(c, 999, call.getString("title", "Adhān test"), call.getString("body", "This is how the adhān will sound at prayer time."));
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
        JSObject r = new JSObject();
        r.put("exact", canExact(getContext()));
        r.put("playing", AdhanService.playing);
        call.resolve(r);
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
                am.cancel(alarmIntent(c, id, "", ""));
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
                setAlarm(c, at, alarmIntent(c, o.optInt("id"), o.optString("title"), o.optString("body")));
            }
        } catch (Exception ignored) {}
    }

    static PendingIntent alarmIntent(Context c, int id, String title, String body) {
        Intent i = new Intent(c, AdhanReceiver.class).setAction("com.salah.tracker.ADHAN." + id);
        i.putExtra("id", id).putExtra("title", title).putExtra("body", body);
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
