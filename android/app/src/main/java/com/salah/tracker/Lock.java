package com.salah.tracker;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Optional app lock: fingerprint, face or the phone's PIN. */
@CapacitorPlugin(name = "Lock")
public class Lock extends Plugin {
    static final int HOW = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    @PluginMethod
    public void available(PluginCall call) {
        JSObject r = new JSObject();
        int s = BiometricManager.from(getContext()).canAuthenticate(HOW);
        r.put("ok", s == BiometricManager.BIOMETRIC_SUCCESS);
        r.put("code", s);
        call.resolve(r);
    }

    @PluginMethod
    public void auth(PluginCall call) {
        FragmentActivity a = getActivity();
        if (a == null) { call.reject("no activity"); return; }
        a.runOnUiThread(() -> {
            try {
                BiometricPrompt p = new BiometricPrompt(a, ContextCompat.getMainExecutor(a), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult res) { JSObject r = new JSObject(); r.put("ok", true); call.resolve(r); }
                    @Override public void onAuthenticationError(int code, CharSequence msg) { JSObject r = new JSObject(); r.put("ok", false); r.put("error", String.valueOf(msg)); call.resolve(r); }
                });
                BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Unlock Salah Tracker").setSubtitle("Your prayers and qaḍā are private")
                        .setAllowedAuthenticators(HOW).build();
                p.authenticate(info);
            } catch (Exception e) { JSObject r = new JSObject(); r.put("ok", false); r.put("error", e.getMessage()); call.resolve(r); }
        });
    }
}
