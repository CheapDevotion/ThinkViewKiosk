package com.thinkview.kiosk;

import android.app.Activity;
import android.util.Log;
import android.view.View;

/**
 * Click handler for AlarmActivity's "SILENCE SIREN" button.
 *
 * v23 and earlier: just called activity.finish(), which stopped the local siren but left HA
 *   thinking the alarm was still triggered. Other kiosk panels in the house kept blaring.
 *
 * v24+: fires HA's alarm_control_panel.alarm_disarm service. The state change propagates
 *   back over the WebSocket to every connected kiosk; each one's onAlarmCleared path
 *   dismisses the overlay automatically. So tapping silence on one designated panel
 *   (e.g. master bedroom) silences and dismisses the alarm fleet-wide.
 *
 *   We still finish the local activity immediately on tap for instant feedback -- the user
 *   sees their silence happen at hand-touch latency rather than waiting for the HTTPS round
 *   trip. The other devices catch up via the WebSocket within a couple hundred ms.
 *
 * Top-level class to dodge d8's anonymous-inner-class issues.
 */
class SilenceClickListener implements View.OnClickListener {
    private static final String TAG = "ThinkViewKiosk/Alarm";
    private final Activity activity;

    SilenceClickListener(Activity activity) { this.activity = activity; }

    @Override
    public void onClick(View v) {
        Log.i(TAG, "silence tapped -- firing HA disarm");

        // 1. Local immediate dismissal so the user feels the click. Stops siren via
        //    activity's onDestroy -> SirenPlayer.stop().
        if (activity instanceof AlarmActivity) {
            ((AlarmActivity) activity).stopSirenAndFinish();
        } else {
            activity.finish();
        }

        // 2. Fire HA disarm in the background. Don't block on it -- if it fails, this
        //    device is already silenced (good local UX) and the HA dashboard is still
        //    available for manual disarm. The only thing lost is the "all devices auto-
        //    dismiss" propagation.
        Thread t = new Thread(new AlarmDisarmTask(activity.getApplicationContext()),
                "alarm-disarm");
        t.setDaemon(true);
        t.start();
    }
}
