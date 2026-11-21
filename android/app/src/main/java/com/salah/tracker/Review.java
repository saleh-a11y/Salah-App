package com.salah.tracker;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

/** Google Play's in-app review card. Does nothing when the app wasn't installed from Play. */
@CapacitorPlugin(name = "Review")
public class Review extends Plugin {
    @PluginMethod
    public void request(PluginCall call) {
        try {
            ReviewManager m = ReviewManagerFactory.create(getContext());
            m.requestReviewFlow().addOnCompleteListener(t -> {
                if (t.isSuccessful() && getActivity() != null) m.launchReviewFlow(getActivity(), t.getResult()).addOnCompleteListener(x -> call.resolve());
                else call.resolve();
            });
        } catch (Exception e) { call.resolve(); }
    }
}
