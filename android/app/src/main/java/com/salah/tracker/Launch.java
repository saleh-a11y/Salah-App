package com.salah.tracker;

import android.content.Intent;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * ready(): the page calls it once the opening scene is drawn, so the launch screen hands over without a white gap.
 * shared(): text that another app (e.g. WhatsApp) shared to Salah Tracker, to add as a dhikr.
 */
@CapacitorPlugin(name = "Launch")
public class Launch extends Plugin {
    private static String pending;

    @Override
    public void load() {
        take(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (take(intent)) {
            JSObject r = new JSObject();
            r.put("text", pending);
            notifyListeners("shared", r, true);
        }
    }

    private static boolean take(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return false;
        String type = i.getType();
        if (type == null || !type.startsWith("text/")) return false;
        CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (t == null) return false;
        String s = t.toString();
        pending = s.length() > 8000 ? s.substring(0, 8000) : s;
        i.setAction(Intent.ACTION_MAIN); // handle it once
        return true;
    }

    @PluginMethod
    public void ready(PluginCall call) {
        MainActivity.webReady = true;
        call.resolve();
    }

    @PluginMethod
    public void shared(PluginCall call) {
        JSObject r = new JSObject();
        if (pending != null) r.put("text", pending);
        pending = null;
        call.resolve(r);
    }
}
