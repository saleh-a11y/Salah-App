package com.salah.tracker;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AppUpdater.class);
        registerPlugin(AdhanPlayer.class);
        super.onCreate(savedInstanceState);
    }
}
