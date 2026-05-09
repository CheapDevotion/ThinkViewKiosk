package com.thinkview.kiosk;

import android.app.Activity;
import android.view.View;

/**
 * Click handler for AlarmActivity's "SILENCE SIREN" button. Top-level class to dodge d8's
 * anonymous-inner-class issues; finishes the activity (which stops the siren via onDestroy).
 */
class SilenceClickListener implements View.OnClickListener {
    private final Activity activity;

    SilenceClickListener(Activity activity) { this.activity = activity; }

    @Override
    public void onClick(View v) {
        activity.finish();
    }
}
