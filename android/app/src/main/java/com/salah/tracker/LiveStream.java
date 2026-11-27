package com.salah.tracker;

import android.content.Context;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** The page's side of the masjid's live broadcast (LiveService does the work). */
@CapacitorPlugin(name = "LiveStream")
public class LiveStream extends Plugin {

    /** The main masjid's stream (LiveMasjid name) and name; Jumu'ah and other broadcasts: 0 off, 1 notify, 2 play;
     *  and the salāh / Jumu'ah time windows ([start ms, end ms, "s" or "j"]) for the coming days. */
    @PluginMethod
    public void config(PluginCall call) {
        Context c = getContext();
        String slug = call.getString("slug", ""), name = call.getString("name", "Your masjid");
        int jm = slug.isEmpty() ? 0 : call.getInt("jm", 0), ot = slug.isEmpty() ? 0 : call.getInt("ot", 0);
        String win = call.getArray("win") == null ? LiveService.prefs(c).getString("win", "[]") : call.getArray("win").toString();
        boolean was = LiveService.watchOn(c);
        String old = LiveService.prefs(c).getString("slug", "");
        LiveService.prefs(c).edit().putString("slug", slug).putString("name", name).putInt("jm", jm).putInt("ot", ot).putString("win", win).remove("always").apply();
        boolean on = jm > 0 || ot > 0;
        if (on && (!was || !old.equals(slug) || !LiveService.watching)) LiveService.start(c, LiveService.A_WATCH, null);
        else if (!on && was) LiveService.start(c, LiveService.A_UNWATCH, null);
        call.resolve();
    }

    /** Is this stream on air right now? */
    @PluginMethod
    public void check(PluginCall call) {
        String slug = call.getString("slug", "");
        new Thread(() -> {
            JSObject r = new JSObject();
            r.put("live", !slug.isEmpty() && LiveService.isLive(slug));
            call.resolve(r);
        }).start();
    }

    @PluginMethod
    public void play(PluginCall call) {
        android.content.Intent x = new android.content.Intent();
        if (call.getString("slug") != null) x.putExtra("slug", call.getString("slug")).putExtra("name", call.getString("name", ""));
        LiveService.start(getContext(), LiveService.A_PLAY, x);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (LiveService.playing || LiveService.waiting) LiveService.start(getContext(), LiveService.A_STOP, null);
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("playing", LiveService.playing);
        r.put("waiting", LiveService.waiting);
        r.put("watching", LiveService.watching);
        call.resolve(r);
    }
}
