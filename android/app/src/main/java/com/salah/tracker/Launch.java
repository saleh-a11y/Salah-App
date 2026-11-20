package com.salah.tracker;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** The page calls ready() once the opening scene is drawn, so the launch screen can hand over without a white gap. */
@CapacitorPlugin(name = "Launch")
public class Launch extends Plugin {
    @PluginMethod
    public void ready(PluginCall call) {
        MainActivity.webReady = true;
        call.resolve();
    }
}
