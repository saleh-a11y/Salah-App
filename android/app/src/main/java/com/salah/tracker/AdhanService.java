package com.salah.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

/** Plays the full adhān recording as an alarm sound, with a Stop button on the notification. */
public class AdhanService extends Service {
    static final String ACTION_STOP = "com.salah.tracker.ADHAN_STOP";
    static final String CH_PLAY = "adhan_play", CH_SILENT = "adhan_notice", CH_FALLBACK = "adhan_fallback";
    static final int COLOR = 0xFF1F7A4C;
    static volatile boolean playing = false;

    private MediaPlayer mp;
    private AudioManager am;
    private AudioFocusRequest focus;
    private int noteId = 7000;
    private String title = "Adhān", body = "";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            finish(true);
            return START_NOT_STICKY;
        }
        if (intent != null) {
            title = orDefault(intent.getStringExtra("title"), "Adhān");
            body = orDefault(intent.getStringExtra("body"), "");
            noteId = 7000 + intent.getIntExtra("id", 0);
        }
        channels(this);
        Notification n = build(true);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(noteId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(noteId, n);
        } catch (Exception e) {
            plainNotice(this, noteId - 7000, title, body, true);
            stopSelf();
            return START_NOT_STICKY;
        }
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        // phone on silent or vibrate: show the notification quietly, don't play
        if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
            finish(false);
            return START_NOT_STICKY;
        }
        play();
        return START_NOT_STICKY;
    }

    private void play() {
        stopPlayer();
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(aa)
                        .setOnAudioFocusChangeListener(ch -> { if (ch == AudioManager.AUDIOFOCUS_LOSS) finish(false); })
                        .build();
                am.requestAudioFocus(focus);
            }
            mp = new MediaPlayer();
            mp.setAudioAttributes(aa);
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(this, Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.adhan));
            mp.setOnCompletionListener(m -> finish(false));
            mp.setOnErrorListener((m, w, x) -> { finish(false); return true; });
            mp.prepare();
            mp.start();
            playing = true;
        } catch (Exception e) {
            finish(false);
        }
    }

    /** Stops playback. The notification stays in the shade (without Stop) unless the person dismissed or stopped it. */
    private void finish(boolean removeNote) {
        stopPlayer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(removeNote ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        else stopForeground(removeNote);
        if (!removeNote) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(noteId, build(false));
        }
        stopSelf();
    }

    private void stopPlayer() {
        playing = false;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        if (am != null && focus != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { am.abandonAudioFocusRequest(focus); } catch (Exception ignored) {}
            focus = null;
        }
    }

    @Override public void onDestroy() { stopPlayer(); super.onDestroy(); }

    private Notification build(boolean withStop) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, withStop ? CH_PLAY : CH_SILENT)
                .setSmallIcon(R.drawable.ic_stat_salah)
                .setColor(COLOR)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openApp(this))
                .setSilent(true)
                .setAutoCancel(true);
        if (withStop) {
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, AdhanService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0, "Stop adhān", stop).setDeleteIntent(stop).setOngoing(true);
        }
        return b.build();
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel play = new NotificationChannel(CH_PLAY, "Adhān", NotificationManager.IMPORTANCE_HIGH);
        play.setDescription("Plays the adhān at each prayer time");
        play.setSound(null, null);
        play.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(play);
        NotificationChannel quiet = new NotificationChannel(CH_SILENT, "Adhān (finished)", NotificationManager.IMPORTANCE_DEFAULT);
        quiet.setSound(null, null);
        nm.createNotificationChannel(quiet);
        NotificationChannel fb = new NotificationChannel(CH_FALLBACK, "Adhān (backup alert)", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(fb);
    }

    /** A normal notification, used when the player can't start (or as a quiet notice). */
    static void plainNotice(Context c, int id, String title, String body, boolean withSound) {
        channels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, withSound ? CH_FALLBACK : CH_SILENT)
                .setSmallIcon(R.drawable.ic_stat_salah).setColor(COLOR)
                .setContentTitle(orDefault(title, "Adhān")).setContentText(orDefault(body, ""))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(openApp(c)).setAutoCancel(true);
        nm.notify(7000 + id, b.build());
    }

    private static String orDefault(String s, String d) { return s == null || s.isEmpty() ? d : s; }
}
