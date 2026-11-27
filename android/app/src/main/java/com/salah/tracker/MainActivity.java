package com.salah.tracker;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.activity.OnBackPressedCallback;
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
        registerPlugin(Lock.class);
        registerPlugin(QuranFile.class);
        super.onCreate(savedInstanceState);
        // back button / gesture: let the page close a sheet, leave settings or go back to Today first;
        // if it has nothing to close, send the app to the background like other apps
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (bridge == null || bridge.getWebView() == null) { moveTaskToBack(true); return; }
                bridge.getWebView().evaluateJavascript("(window.salahBack&&window.salahBack())?1:0",
                        r -> { if (!"1".equals(r)) moveTaskToBack(true); });
            }
        });
    }
}
