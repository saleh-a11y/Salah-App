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

    /** Which stream (LiveMasjid name), the masjid's name, and whether to play whenever it goes live. */
    @PluginMethod
    public void config(PluginCall call) {
        Context c = getContext();
        String slug = call.getString("slug", ""), name = call.getString("name", "Your masjid");
        boolean always = call.getBoolean("always", false) && !slug.isEmpty();
        boolean was = LiveService.prefs(c).getBoolean("always", false);
        String old = LiveService.prefs(c).getString("slug", "");
        LiveService.prefs(c).edit().putString("slug", slug).putString("name", name).putBoolean("always", always).apply();
        if (always && (!was || !old.equals(slug) || !LiveService.watching)) LiveService.start(c, LiveService.A_WATCH, null);
        else if (!always && was) LiveService.start(c, LiveService.A_UNWATCH, null);
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
        LiveService.start(getContext(), LiveService.A_PLAY, null);
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
