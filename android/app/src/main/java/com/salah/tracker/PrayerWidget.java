package com.salah.tracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget: next prayer with a live countdown; the big one also shows today's five prayers and qaḍā progress.
 *  The app hands over the times (AdhanPlayer.widget); the widget refreshes itself at each prayer time. */
public abstract class PrayerWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.salah.tracker.WIDGET_REFRESH";
    abstract boolean large();

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) m.updateAppWidget(id, build(c, large())); scheduleNext(c); }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (ACTION_REFRESH.equals(i.getAction())) refreshAll(c);
    }

    static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (Class<?> k : new Class<?>[]{WidgetSmall.class, WidgetLarge.class}) {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, k));
            boolean big = k == WidgetLarge.class;
            for (int id : ids) m.updateAppWidget(id, build(c, big));
        }
        scheduleNext(c);
    }

    static JSONObject data(Context c) {
        try { return new JSONObject(c.getSharedPreferences(AdhanPlayer.PREFS, Context.MODE_PRIVATE).getString("widget", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static String hm(long t) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t)); }
    static long when(JSONObject p) { long j = p.optLong("j"), a = p.optLong("a"); return j > 0 ? j : a > 0 ? a : p.optLong("s"); }

    /** The next prayer still to come, and the day it belongs to. */
    static JSONObject[] next(JSONObject d, long now) {
        JSONArray days = d.optJSONArray("days");
        if (days == null) return null;
        for (int k = 0; k < days.length(); k++) {
            JSONObject day = days.optJSONObject(k); if (day == null) continue;
            JSONArray ps = day.optJSONArray("p"); if (ps == null) continue;
            for (int i = 0; i < ps.length(); i++) { JSONObject p = ps.optJSONObject(i); if (p != null && when(p) > now) return new JSONObject[]{p, day}; }
        }
        return null;
    }

    static RemoteViews build(Context c, boolean big) {
        RemoteViews v = new RemoteViews(c.getPackageName(), big ? R.layout.widget_large : R.layout.widget_small);
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        JSONObject d = data(c);
        long now = System.currentTimeMillis();
        JSONObject[] nx = next(d, now);
        if (nx == null) {
            v.setTextViewText(R.id.w_name, "Salah Tracker");
            v.setTextViewText(R.id.w_time, "Open the app");
            v.setTextViewText(R.id.w_label, "to load prayer times");
            v.setChronometer(R.id.w_cd, SystemClock.elapsedRealtime(), "", false);
            return v;
        }
        JSONObject p = nx[0];
        long at = when(p);
        v.setTextViewText(R.id.w_name, p.optString("n") + "  " + p.optString("ar"));
        v.setTextViewText(R.id.w_time, hm(at));
        v.setTextViewText(R.id.w_label, p.optLong("j") > 0 ? p.optString("jl", "Jamā'ah") : p.optLong("a") > 0 ? "Adhān" : "Time starts");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) v.setChronometerCountDown(R.id.w_cd, true);
        v.setChronometer(R.id.w_cd, SystemClock.elapsedRealtime() + (at - now), null, true);
        if (big) {
            v.setTextViewText(R.id.w_masjid, d.optString("masjid", ""));
            JSONObject day = nx[1];
            JSONArray today = null; int mask = 0;
            // the five rows show today until Isha's time has passed, then tomorrow
            JSONArray days = d.optJSONArray("days");
            if (days != null) for (int k = 0; k < days.length(); k++) { JSONObject x = days.optJSONObject(k); if (x != null && x.optString("d").equals(day.optString("d"))) { today = x.optJSONArray("p"); mask = x.optInt("m"); } }
            int[] tn = {R.id.w_p0, R.id.w_p1, R.id.w_p2, R.id.w_p3, R.id.w_p4}, tt = {R.id.w_t0, R.id.w_t1, R.id.w_t2, R.id.w_t3, R.id.w_t4};
            for (int i = 0; i < 5; i++) {
                JSONObject q = today == null ? null : today.optJSONObject(i);
                boolean done = (mask >> i & 1) == 1;
                v.setTextViewText(tn[i], (done ? "✓ " : "") + (q == null ? "" : q.optString("n")));
                v.setTextViewText(tt[i], q == null ? "" : hm(when(q)));
                v.setTextColor(tn[i], q != null && q.optString("n").equals(p.optString("n")) && q.optLong("s") == p.optLong("s") ? 0xFFF1D792 : done ? 0xFF9FD9B4 : 0xFFE8EFE9);
            }
            v.setTextViewText(R.id.w_foot, d.optString("foot", ""));
        }
        return v;
    }

    /** Refresh at the next prayer time so the widget moves on to the following one. */
    static void scheduleNext(Context c) {
        JSONObject[] nx = next(data(c), System.currentTimeMillis());
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 4, new Intent(c, WidgetSmall.class).setAction(ACTION_REFRESH), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = nx == null ? System.currentTimeMillis() + 3600_000L : when(nx[0]) + 60_000L;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi); else am.set(AlarmManager.RTC, at, pi);
    }
}
