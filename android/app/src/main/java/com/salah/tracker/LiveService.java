package com.salah.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import java.util.Calendar;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The masjid's live audio from LiveMasjid (https://relay.livemasjid.com:8443/<stream>).
 *  - PLAY: listen now (tapped in the app).
 *  - WAIT: at adhān time for a salāh set to "Live": listen for the masjid for a few minutes;
 *          if it doesn't come on air, play the recorded adhān instead.
 *  - WATCH: other broadcasts (Jumu'ah bayān and khutbah, other bayāns): checks every 45 seconds
 *          in the background and, per the person's choice, notifies them or plays it. Broadcasts
 *          during an adhān/salāh window are left to the adhān setting.
 * At adhān time the live stream stops by itself when the adhān is over: whichever comes first of
 * the masjid switching off its broadcast, 15 seconds of quiet after the adhān, or 5 minutes.
 * Only one sound at a time: the live stream waits while the recorded adhān plays, and the
 * recorded adhān is skipped while the masjid is playing live (see AdhanReceiver).
 */
@OptIn(markerClass = UnstableApi.class)
public class LiveService extends Service {
    static final String A_PLAY = "com.salah.tracker.LIVE_PLAY", A_STOP = "com.salah.tracker.LIVE_STOP",
            A_WAIT = "com.salah.tracker.LIVE_WAIT", A_WATCH = "com.salah.tracker.LIVE_WATCH", A_UNWATCH = "com.salah.tracker.LIVE_UNWATCH",
            A_SLEEP = "com.salah.tracker.LIVE_SLEEP";
    static final String PREFS = "live_stream", CH_LIVE = "live_play", CH_WATCH = "live_watch", CH_ALERT = "live_alert";
    static final String RELAY = "https://relay.livemasjid.com:8443/";
    static final int NOTE = 7900;
    static final long WAIT_MS = 6 * 60 * 1000L;            // listen from 2 min before the adhān to 4 min after
    static final long ADHAN_MAX = 5 * 60 * 1000L, QUIET_MS = 15000L, HEARD_MS = 20000L;
    static volatile boolean playing = false, liveNow = false, watching = false, waiting = false;

    private HandlerThread ht;
    private Handler h;
    private ExoPlayer xp;
    private final LevelMeter meter = new LevelMeter();
    private boolean adhanOnly = false;
    private long playStart = 0;
    private AudioManager am;
    private PowerManager.WakeLock wl;
    private String slug = "", name = "Your masjid";
    private boolean manual = false, fg = false;
    /** How the current broadcast was handled: null = not yet, s = salāh time, j = Jumu'ah, o = other, m = playing on request, x = stopped by the person. */
    private String hk = null;
    private String cur = "", curName = "", shown = "";
    private long waitUntil = 0;
    private Intent waitInfo;
    private final Runnable tick = this::tick, guard = this::guard;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        ht = new HandlerThread("live");
        ht.start();
        h = new Handler(ht.getLooper());
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    /** After a reboot or app update: check again a little later (an alarm may start the player; boot may not). */
    static void resume(Context c) {
        if (!watchOn(c) || prefs(c).getString("slug", "").isEmpty()) return;
        AdhanPlayer.setAlarm(c, System.currentTimeMillis() + 30000, AdhanPlayer.alarmIntent(c, 989, "", "", "livewatch", null, -1));
    }

    /** Should the background listener run now? "Other bayāns" on: always. Only Jumu'ah on: only in the Friday Jumu'ah window. */
    static boolean needWatch(Context c, long now) {
        SharedPreferences p = prefs(c);
        if (p.getString("slug", "").isEmpty()) return false;
        if (p.getInt("ot", 0) > 0) return true;
        if (p.getInt("jm", 0) == 0) return false;
        long[] w = nextJ(c, now);
        return w != null && now >= w[0] - 60000 && now < w[1];
    }

    /** The current or next Jumu'ah window from the times the app sent, or next Friday 11:30–14:30 if it hasn't sent any. */
    static long[] nextJ(Context c, long now) {
        long[] best = null;
        try {
            JSONArray a = new JSONArray(prefs(c).getString("win", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray w = a.getJSONArray(i);
                if (!"j".equals(w.getString(2))) continue;
                long s = w.getLong(0), e = w.getLong(1);
                if (e > now && (best == null || s < best[0])) best = new long[]{s, e};
            }
        } catch (Exception ignored) {}
        if (best != null) return best;
        for (int i = 0; i < 8; i++) {
            Calendar d = Calendar.getInstance();
            d.setTimeInMillis(now);
            d.add(Calendar.DAY_OF_YEAR, i);
            if (d.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) continue;
            d.set(Calendar.HOUR_OF_DAY, 11); d.set(Calendar.MINUTE, 30); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
            long s = d.getTimeInMillis(), e = s + 3 * 3600000L;
            if (e > now) return new long[]{s, e};
        }
        return null;
    }

    /** Start listening if it's needed now; otherwise stop, and set an alarm for the next Jumu'ah if that's switched on. */
    static void arm(Context c) {
        long now = System.currentTimeMillis();
        if (needWatch(c, now)) { if (!watching) start(c, A_WATCH, null); return; }
        if (watching) start(c, A_SLEEP, null);
        scheduleNext(c, now);
    }

    static void scheduleNext(Context c, long now) {
        SharedPreferences p = prefs(c);
        if (p.getInt("jm", 0) == 0 || p.getInt("ot", 0) > 0 || p.getString("slug", "").isEmpty()) return;
        long[] w = nextJ(c, now + 60000);
        if (w != null) AdhanPlayer.setAlarm(c, Math.max(now + 5000, w[0] - 60000), AdhanPlayer.alarmIntent(c, 989, "", "", "livewatch", null, -1));
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Listening in the background is only needed if Jumu'ah or other broadcasts should notify or play. */
    static boolean watchOn(Context c) { SharedPreferences p = prefs(c); return p.getInt("jm", 0) > 0 || p.getInt("ot", 0) > 0; }

    static void start(Context c, String action, Intent extras) {
        Intent s = new Intent(c, LiveService.class).setAction(action);
        if (extras != null && extras.getExtras() != null) s.putExtras(extras.getExtras());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception e) {
            AdhanService.log(c, 0, "Live", "phone refused to start the live player: " + e.getClass().getSimpleName());
            // couldn't wait for the live adhān: play the recorded one so the salāh isn't missed
            if (A_WAIT.equals(action) && extras != null) recorded(c, extras);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = prefs(this);
        slug = p.getString("slug", "");
        name = p.getString("name", "Your masjid");
        String a = intent == null || intent.getAction() == null ? A_WATCH : intent.getAction(); // restarted by the system: carry on watching
        boolean always = prefs(this).getInt("ot", 0) > 0;   // only "other bayāns" needs listening all the time
        if (!foreground()) return START_NOT_STICKY;
        h.post(() -> {
            switch (a) {
                case A_STOP:
                    if (playing) hk = "x";                  // don't start again until this broadcast ends
                    stopPlayer();
                    waitUntil = 0; waiting = false; manual = false;
                    break;
                case A_UNWATCH:
                    prefs(this).edit().putInt("jm", 0).putInt("ot", 0).apply();
                    watching = false;
                    break;
                case A_SLEEP:
                    watching = false;
                    break;
                case A_PLAY:
                    manual = true;
                    cancelAlert();
                    String os = intent == null ? null : intent.getStringExtra("slug");
                    if (playing && os != null && !os.equals(cur)) stopPlayer();   // a different masjid was asked for
                    cur = os != null && !os.isEmpty() ? os : slug;
                    curName = intent != null && intent.getStringExtra("name") != null ? intent.getStringExtra("name") : name;
                    if (cur.equals(slug)) hk = "m";
                    if (!playing && !cur.isEmpty()) startPlay(false);
                    break;
                case A_WAIT:
                    waitUntil = System.currentTimeMillis() + WAIT_MS; waiting = true;
                    waitInfo = intent == null ? null : new Intent(intent);
                    break;
                default: // A_WATCH
                    watching = needWatch(this, System.currentTimeMillis());
                    if (!watching) scheduleNext(this, System.currentTimeMillis());
            }
            if (!watching && a.equals(A_WATCH) && !playing && !waiting) { end(); return; }
            update();
            h.removeCallbacks(tick);
            h.post(tick);
        });
        return always ? START_STICKY : START_NOT_STICKY;
    }

    /** startForeground right away (Android requires it within seconds of starting). */
    private boolean foreground() {
        channels(this);
        try {
            Notification n = note();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTE, n);
            fg = true;
            return true;
        } catch (Exception e) {
            AdhanService.log(this, 0, "Live", "live player blocked: " + e.getClass().getSimpleName());
            stopSelf();
            return false;
        }
    }

    /** One check of the masjid's stream; runs on the background thread. */
    private void tick() {
        long now = System.currentTimeMillis();
        if (watching && !needWatch(this, now)) { watching = false; scheduleNext(this, now); }   // Jumu'ah time is over
        if (!playing && !waiting && !watching && !manual) { end(); return; }
        if (!playing && !slug.isEmpty()) {
            boolean live = isLive(slug);
            liveNow = live;
            if (!live) {
                if (hk != null) cancelAlert();          // that broadcast is over
                hk = null;
                if (waiting && now > waitUntil) {
                    AdhanService.log(this, waitInfo == null ? 0 : waitInfo.getIntExtra("id", 0), name, "not live at adhān time: recorded adhān instead");
                    waiting = false; waitUntil = 0;
                    if (waitInfo != null) recorded(this, waitInfo);
                }
            } else if (waiting) {
                if (AdhanService.playing) { h.postDelayed(tick, 5000); return; } // let the recorded adhān finish first
                if (silent()) {
                    AdhanService.log(this, 0, name, "live adhān: phone on silent, not played");
                    waiting = false; waitUntil = 0; hk = "s";
                } else {
                    cur = slug; curName = name;
                    startPlay(true);                    // the adhān at prayer time stops when it's over
                }
            } else if (watching) {
                String k = kindAt(now);
                // a new broadcast, or one that carried on past salāh time into a bayān
                if (hk == null || (hk.equals("s") && !k.equals("s"))) {
                    if (!handle(k)) { h.postDelayed(tick, 5000); return; }
                }
            }
        }
        if (!playing && !waiting && !watching && !manual) { end(); return; }
        update();
        h.postDelayed(tick, playing ? 30000 : waiting ? 5000 : 45000);
    }

    private boolean silent() { return am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL; }

    /** Salāh time (s), Jumu'ah bayān and khutbah (j) or any other time (o), from the windows the app sends. */
    private String kindAt(long now) {
        String r = "o";
        try {
            JSONArray a = new JSONArray(prefs(this).getString("win", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray w = a.getJSONArray(i);
                if (now >= w.getLong(0) && now <= w.getLong(1)) { if ("j".equals(w.getString(2))) return "j"; r = "s"; }
            }
        } catch (Exception ignored) {}
        return r;
    }

    /** The masjid went live outside the adhān: notify, play or ignore, as chosen. False = try again shortly. */
    private boolean handle(String k) {
        if (k.equals("s")) { hk = "s"; return true; }
        int mode = prefs(this).getInt(k.equals("j") ? "jm" : "ot", 0);
        if (mode == 0) { hk = k; return true; }
        if (mode == 2 && !silent()) {
            if (AdhanService.playing) return false;
            hk = k; cur = slug; curName = name;
            AdhanService.log(this, 0, name, k.equals("j") ? "live: Jumu'ah broadcast, playing" : "live: broadcast, playing");
            startPlay(false);
            return true;
        }
        hk = k;
        alert(k);
        return true;
    }

    private void alert(String k) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent li = new Intent(this, LiveService.class).setAction(A_PLAY);
        PendingIntent listen = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, 3, li, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getService(this, 3, li, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String body = k.equals("j") ? "The Jumu'ah bayān or khutbah is on. Tap Listen to hear it." : "A bayān or talk is on. Tap Listen to hear it.";
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_salah).setColor(AdhanService.COLOR)
                .setContentTitle("● " + name + " is live").setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EVENT)
                .setContentIntent(AdhanService.openApp(this)).setAutoCancel(true)
                .addAction(0, "Listen", listen);
        try { nm.notify(NOTE + 1, b.build()); AdhanService.log(this, 0, name, "live: notified"); } catch (Exception ignored) {}
    }

    private void cancelAlert() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) try { nm.cancel(NOTE + 1); } catch (Exception ignored) {}
    }

    static void recorded(Context c, Intent info) {
        Intent s = new Intent(c, AdhanService.class)
                .putExtra("id", info.getIntExtra("id", 0)).putExtra("title", info.getStringExtra("title"))
                .putExtra("body", info.getStringExtra("body")).putExtra("d", info.getStringExtra("d")).putExtra("p", info.getIntExtra("p", -1));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception e) {
            AdhanService.plainNotice(c, info.getIntExtra("id", 0), info.getStringExtra("title"), info.getStringExtra("body"), true);
        }
    }

    /** Icecast answers 200 with audio while the masjid is on air, 404 when it isn't. Only the headers are read. */
    static boolean isLive(String slug) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(RELAY + slug).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Icy-MetaData", "0");
            c.setRequestProperty("User-Agent", "SalahTracker");
            int code = c.getResponseCode();
            String ct = c.getContentType();
            return code == 200 && (ct == null || ct.startsWith("audio") || ct.contains("ogg") || ct.contains("mpeg") || ct.contains("octet"));
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private void startPlay(boolean adhan) {
        stopPlayer();
        try {
            meter.clear();
            adhanOnly = adhan;
            playStart = System.currentTimeMillis();
            Context ctx = getApplicationContext();
            AudioProcessor[] chain = new AudioProcessor[]{meter};
            RenderersFactory rf = (handler, video, audio, text, meta) -> new Renderer[]{
                    new MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, handler, audio,
                            new DefaultAudioSink.Builder(ctx).setAudioProcessors(chain).build())};
            xp = new ExoPlayer.Builder(ctx, rf)
                    .setLooper(ht.getLooper())
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_NETWORK)
                    .build();
            xp.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY && playing) { AdhanService.log(LiveService.this, 0, name, "live: playing"); update(); }
                    if (state == Player.STATE_ENDED) h.post(() -> done("live: broadcast ended", null));
                }
                @Override public void onPlayerError(PlaybackException e) { h.post(() -> done("live: stream ended (" + e.getErrorCodeName() + ")", null)); }
                @Override public void onPlayWhenReadyChanged(boolean go, int reason) {
                    // another app took the sound, or headphones were unplugged: stop for this broadcast
                    if (!go && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) h.post(() -> done("live: stopped (sound taken or headphones out)", "x"));
                }
            });
            xp.setMediaItem(MediaItem.fromUri(RELAY + (cur.isEmpty() ? slug : cur)));
            playing = true; waiting = false; waitUntil = 0;       // the masjid's adhān is on: no recorded fallback
            xp.prepare();
            xp.setPlayWhenReady(true);
            h.removeCallbacks(guard);
            if (adhan) h.postDelayed(guard, 2000);
            update();
        } catch (Exception e) {
            AdhanService.log(this, 0, name, "live: couldn't play " + e.getClass().getSimpleName());
            stopPlayer();
            manual = false;
        }
    }

    /** At adhān time: stop once the adhān is over (15 s of quiet after it), or after 5 minutes at most. */
    private void guard() {
        if (!playing || !adhanOnly) return;
        long now = System.currentTimeMillis();
        if (now - playStart > ADHAN_MAX) { done("live: adhān time over (5 min)", "s"); return; }
        if (meter.loudMs >= HEARD_MS && now - meter.lastLoud > QUIET_MS) { done("live: masjid went quiet after the adhān", "s"); return; }
        h.postDelayed(guard, 2000);
    }

    /** The broadcast ended or was stopped. "mark": how this broadcast counts from now on (s = salāh time is over
     *  but a bayān after it may still notify; x = stopped, nothing more until the masjid's next broadcast). */
    private void done(String why, String mark) {
        if (!playing) return;
        AdhanService.log(this, 0, name, why);
        if (mark != null) hk = mark;
        stopPlayer();
        afterPlay();
    }

    /** The broadcast stopped or the person stopped it: go back to watching, or close. */
    private void afterPlay() {
        manual = false;
        if (watching) { update(); h.removeCallbacks(tick); h.postDelayed(tick, 15000); }
        else end();
    }

    private void stopPlayer() {
        playing = false;
        adhanOnly = false;
        h.removeCallbacks(guard);
        if (xp != null) {
            try { xp.stop(); } catch (Exception ignored) {}
            try { xp.release(); } catch (Exception ignored) {}
            xp = null;
        }
    }

    /** Keeps the phone awake enough to check while waiting or watching (released when idle). */
    private void wake(boolean on) {
        try {
            if (on) {
                if (wl == null) { PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE); wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "salah:live"); wl.setReferenceCounted(false); }
                if (!wl.isHeld()) wl.acquire(12 * 60 * 60 * 1000L);
            } else if (wl != null && wl.isHeld()) wl.release();
        } catch (Exception ignored) {}
    }

    private void update() {
        wake(waiting || watching);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String k = playing + "|" + waiting + "|" + watching + "|" + adhanOnly + "|" + curName + "|" + name;
        if (k.equals(shown)) return;              // unchanged: leave it alone (if it was swiped away, it stays away)
        shown = k;
        if (nm != null && fg) try { nm.notify(NOTE, note()); } catch (Exception ignored) {}
    }

    private void end() {
        stopPlayer();
        waiting = false; watching = false; waitUntil = 0; manual = false;
        wake(false);
        h.removeCallbacks(tick);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        fg = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        playing = false; waiting = false; watching = false;
        wake(false);
        if (h != null) { h.removeCallbacksAndMessages(null); h.post(this::stopPlayer); } // the player lives on the "live" thread
        if (ht != null) ht.quitSafely();
        super.onDestroy();
    }

    private PendingIntent act(String action, int rc) {
        return PendingIntent.getService(this, rc, new Intent(this, LiveService.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification note() {
        boolean play = playing;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, play || waiting ? CH_LIVE : CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat_salah).setColor(AdhanService.COLOR)
                .setContentIntent(AdhanService.openApp(this)).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (play) {
            b.setContentTitle("● Live · " + (curName.isEmpty() ? name : curName)).setContentText(adhanOnly ? "The masjid's adhān · stops when it's over" : "Playing the masjid's live broadcast")
                    .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .addAction(0, "Stop", act(A_STOP, 1));
        } else if (waiting) {
            b.setContentTitle("Waiting for " + name + "'s adhān")
                    .setContentText("Plays live when the masjid comes on air, or the recorded adhān if it doesn't")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT).addAction(0, "Cancel", act(A_STOP, 1));
        } else {
            boolean jOnly = prefs(this).getInt("ot", 0) == 0;
            b.setContentTitle("Listening for " + name + " to go live")
                    .setContentText(jOnly ? "During Jumu'ah time only" : "For Jumu'ah and bayāns, as set in the app")
                    .setPriority(NotificationCompat.PRIORITY_MIN).addAction(0, "Turn off", act(A_UNWATCH, 2));
        }
        return b.build();
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel live = new NotificationChannel(CH_LIVE, "Live from the masjid", NotificationManager.IMPORTANCE_DEFAULT);
        live.setDescription("Shows while the masjid's live broadcast plays, with a Stop button");
        live.setSound(null, null);
        live.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(live);
        NotificationChannel watch = new NotificationChannel(CH_WATCH, "Listening for the masjid", NotificationManager.IMPORTANCE_MIN);
        watch.setDescription("A quiet notification while the app waits for the masjid to go live");
        watch.setSound(null, null);
        nm.createNotificationChannel(watch);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "Masjid is live", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("When your masjid goes live for Jumu'ah or a bayān");
        nm.createNotificationChannel(alert);
    }
}
