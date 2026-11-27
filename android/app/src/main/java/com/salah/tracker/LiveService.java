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
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The masjid's live audio from LiveMasjid (https://relay.livemasjid.com:8443/<stream>).
 *  - PLAY: listen now (tapped in the app).
 *  - WAIT: at adhān time for a salāh set to "Live": listen for the masjid for a few minutes;
 *          if it doesn't come on air, play the recorded adhān instead.
 *  - WATCH: "play whenever the masjid goes live": checks every 45 seconds in the background.
 * At adhān time the live stream stops by itself when the adhān is over: whichever comes first of
 * the masjid switching off its broadcast, 15 seconds of quiet after the adhān, or 5 minutes.
 * Only one sound at a time: the live stream waits while the recorded adhān plays, and the
 * recorded adhān is skipped while the masjid is playing live (see AdhanReceiver).
 */
@OptIn(markerClass = UnstableApi.class)
public class LiveService extends Service {
    static final String A_PLAY = "com.salah.tracker.LIVE_PLAY", A_STOP = "com.salah.tracker.LIVE_STOP",
            A_WAIT = "com.salah.tracker.LIVE_WAIT", A_WATCH = "com.salah.tracker.LIVE_WATCH", A_UNWATCH = "com.salah.tracker.LIVE_UNWATCH";
    static final String PREFS = "live_stream", CH_LIVE = "live_play", CH_WATCH = "live_watch";
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
    private boolean manual = false, dismissed = false, fg = false;
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

    /** After a reboot or app update: start watching again a little later (an alarm may start the player; boot may not). */
    static void resume(Context c) {
        if (!prefs(c).getBoolean("always", false) || prefs(c).getString("slug", "").isEmpty()) return;
        AdhanPlayer.setAlarm(c, System.currentTimeMillis() + 30000, AdhanPlayer.alarmIntent(c, 989, "", "", "livewatch", null, -1));
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

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
        boolean always = p.getBoolean("always", false);
        if (!foreground()) return START_NOT_STICKY;
        h.post(() -> {
            switch (a) {
                case A_STOP:
                    if (playing) dismissed = true;          // don't start again until this broadcast ends
                    stopPlayer();
                    waitUntil = 0; waiting = false; manual = false;
                    break;
                case A_UNWATCH:
                    prefs(this).edit().putBoolean("always", false).apply();
                    watching = false;
                    break;
                case A_PLAY:
                    manual = true; dismissed = false;
                    if (!playing) startPlay(false);
                    break;
                case A_WAIT:
                    waitUntil = System.currentTimeMillis() + WAIT_MS; waiting = true;
                    waitInfo = intent == null ? null : new Intent(intent);
                    dismissed = false;
                    break;
                default: // A_WATCH
                    watching = always;
            }
            if (!watching && a.equals(A_WATCH) && !playing && !waiting) { end(); return; }
            update();
            h.removeCallbacks(tick);
            h.post(tick);
        });
        return watching || always ? START_STICKY : START_NOT_STICKY;
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
        if (slug.isEmpty() || (!playing && !waiting && !watching && !manual)) { end(); return; }
        long now = System.currentTimeMillis();
        if (!playing) {
            boolean live = isLive(slug);
            liveNow = live;
            if (!live) dismissed = false;               // that broadcast is over
            if (live && !dismissed && (waiting || watching)) {
                if (AdhanService.playing) { h.postDelayed(tick, 5000); return; } // let the recorded adhān finish first
                if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
                    AdhanService.log(this, 0, name, "live: phone on silent, not played");
                    dismissed = true;
                    if (waiting) { waiting = false; waitUntil = 0; }
                } else {
                    startPlay(waiting);              // the adhān at prayer time stops when it's over
                }
            } else if (waiting && now > waitUntil) {
                AdhanService.log(this, waitInfo == null ? 0 : waitInfo.getIntExtra("id", 0), name, "not live at adhān time: recorded adhān instead");
                waiting = false; waitUntil = 0;
                if (waitInfo != null) recorded(this, waitInfo);
            }
        }
        if (!playing && !waiting && !watching && !manual) { end(); return; }
        update();
        h.postDelayed(tick, playing ? 30000 : waiting ? 5000 : 45000);
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
                    if (state == Player.STATE_ENDED) h.post(() -> done("live: broadcast ended", false));
                }
                @Override public void onPlayerError(PlaybackException e) { h.post(() -> done("live: stream ended (" + e.getErrorCodeName() + ")", false)); }
                @Override public void onPlayWhenReadyChanged(boolean go, int reason) {
                    // another app took the sound, or headphones were unplugged: stop for this broadcast
                    if (!go && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) h.post(() -> done("live: stopped (sound taken or headphones out)", true));
                }
            });
            xp.setMediaItem(MediaItem.fromUri(RELAY + slug));
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
        if (now - playStart > ADHAN_MAX) { done("live: adhān time over (5 min)", true); return; }
        if (meter.loudMs >= HEARD_MS && now - meter.lastLoud > QUIET_MS) { done("live: masjid went quiet after the adhān", true); return; }
        h.postDelayed(guard, 2000);
    }

    /** The broadcast ended or was stopped; "dismiss" keeps it from starting again until the masjid's next broadcast. */
    private void done(String why, boolean dismiss) {
        if (!playing) return;
        AdhanService.log(this, 0, name, why);
        if (dismiss) dismissed = true;
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
                .setContentIntent(AdhanService.openApp(this)).setOngoing(true).setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (play) {
            b.setContentTitle("● Live · " + name).setContentText(adhanOnly ? "The masjid's adhān · stops when it's over" : "Playing the masjid's live broadcast")
                    .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .addAction(0, "Stop", act(A_STOP, 1));
        } else if (waiting) {
            b.setContentTitle("Waiting for " + name + "'s adhān")
                    .setContentText("Plays live when the masjid comes on air, or the recorded adhān if it doesn't")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT).addAction(0, "Cancel", act(A_STOP, 1));
        } else {
            b.setContentTitle("Listening for " + name + " to go live")
                    .setContentText("Plays automatically when the masjid comes on air")
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
    }
}
