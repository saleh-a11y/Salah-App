package com.salah.tracker;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.core.splashscreen.SplashScreen;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    /** set by the Launch plugin once the page has drawn its opening scene */
    static volatile boolean webReady = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        webReady = false;
        final long t0 = SystemClock.uptimeMillis();
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        // keep the green launch screen until the page is ready (at most 4 seconds)
        splash.setKeepOnScreenCondition(() -> !webReady && SystemClock.uptimeMillis() - t0 < 4000);
        splash.setOnExitAnimationListener(v -> v.getView().animate().alpha(0f).setDuration(220).withEndAction(v::remove).start());
        registerPlugin(AppUpdater.class);
        registerPlugin(AdhanPlayer.class);
        registerPlugin(Compass.class);
        registerPlugin(Launch.class);
        super.onCreate(savedInstanceState);
    }
}
