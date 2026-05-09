package com.thinkview.kiosk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen, attention-grabbing overlay shown when the home alarm fires. Built entirely in
 * code (no XML layout) for the same reason MainActivity is -- one less moving part. Wakes the
 * screen, locks orientation to whatever the kiosk uses, plays the siren, and (on devices
 * that opt in) offers a "SILENCE" button.
 *
 * Gating the silence button (alarm-can-disarm pref):
 *   - true  (default): SILENCE button visible. User can tap it to stop the local siren.
 *   - false: button replaced with a "DISARM FROM ALARMO PANEL" hint. Forces the user to a
 *            specific physical location to silence the alarm -- prevents a child from
 *            tapping any nearby kiosk to make the noise stop.
 *
 * The overlay also auto-dismisses when HA reports the alarm has cleared (state goes back to
 * something other than 'triggered'). That path runs through HaWebSocketClient ->
 * AlarmListenerService.onAlarmCleared -> AlarmActivity.dismissIfShown.
 */
public class AlarmActivity extends Activity {
    private static final String TAG = "ThinkViewKiosk/Alarm";
    private static final String PREFS = "kiosk-prefs";
    private static final String KEY_ALARM_CAN_DISARM = "alarm-can-disarm";

    private static volatile AlarmActivity instance;
    private SirenPlayer siren;

    /// Live reference to the running activity (or null) so AlarmListenerService can dismiss
    /// us when HA reports the alarm cleared. Mirrors the AlarmListenerService.getInstance
    /// pattern.
    public static void dismissIfShown() {
        AlarmActivity a = instance;
        if (a == null) return;
        Log.i(TAG, "alarm cleared -- auto-dismissing overlay");
        a.runOnUiThread(new AlarmDismissTask(a));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);

        String triggerInfo = getIntent().getStringExtra("trigger");
        if (triggerInfo == null) triggerInfo = "Unknown source";
        setContentView(buildView(triggerInfo));

        siren = new SirenPlayer(this);
        siren.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Re-fire as if the activity just opened (in case alarm re-triggers while we're up).
        String triggerInfo = intent.getStringExtra("trigger");
        if (triggerInfo == null) triggerInfo = "Unknown source";
        setContentView(buildView(triggerInfo));
        if (siren == null || !siren.isPlaying()) {
            siren = new SirenPlayer(this);
            siren.start();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Eat BACK so users can't accidentally dismiss without tapping the button.
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        if (siren != null) siren.stop();
        super.onDestroy();
    }

    /// Called from AlarmDismissTask on the main thread when HA reports alarm cleared. Stops
    /// the siren and finishes the activity. Package-private; the dismissal task lives in its
    /// own top-level class for d8 sanity.
    void stopSirenAndFinish() {
        if (siren != null) {
            siren.stop();
            siren = null;
        }
        finish();
    }

    private View buildView(String triggerInfo) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#C62828")); // emergency red

        TextView heading = new TextView(this);
        heading.setText("ALARM");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setGravity(Gravity.CENTER);

        TextView trigger = new TextView(this);
        trigger.setText(triggerInfo);
        trigger.setTextColor(Color.WHITE);
        trigger.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        trigger.setGravity(Gravity.CENTER);
        trigger.setPadding(48, 24, 48, 48);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean canDisarm = prefs.getBoolean(KEY_ALARM_CAN_DISARM, true);

        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams triggerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        root.addView(heading, headingLp);
        root.addView(trigger, triggerLp);

        if (canDisarm) {
            Button silence = new Button(this);
            silence.setText("SILENCE SIREN");
            silence.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            silence.setTypeface(Typeface.DEFAULT_BOLD);
            silence.setTextColor(Color.parseColor("#C62828"));
            silence.setBackgroundColor(Color.WHITE);
            silence.setOnClickListener(new SilenceClickListener(this));

            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(600, 140);
            buttonLp.topMargin = 32;
            root.addView(silence, buttonLp);
        } else {
            // No silence button on this panel. Show a hint instead so it's clear *why*
            // there's no obvious dismiss action -- and where to go to actually disarm.
            TextView hint = new TextView(this);
            hint.setText("Disarm from the Alarmo panel");
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            hint.setTypeface(Typeface.DEFAULT_BOLD);
            hint.setGravity(Gravity.CENTER);
            hint.setAlpha(0.85f);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = 32;
            root.addView(hint, hintLp);
        }
        return root;
    }
}
