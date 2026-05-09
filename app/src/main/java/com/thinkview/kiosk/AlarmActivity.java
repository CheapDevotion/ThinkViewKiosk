package com.thinkview.kiosk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
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
 * screen, locks orientation to whatever the kiosk uses, plays the siren, and offers a single
 * "SILENCE" button that stops the local sound and dismisses the overlay.
 *
 * The button silences this device. To actually disarm the alarm in HA, the user goes to the
 * dashboard's alarm panel card -- this overlay is the noisemaker, not the security panel.
 */
public class AlarmActivity extends Activity {
    private SirenPlayer siren;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        siren = new SirenPlayer();
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
            siren = new SirenPlayer();
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
        if (siren != null) siren.stop();
        super.onDestroy();
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

        Button silence = new Button(this);
        silence.setText("SILENCE SIREN");
        silence.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        silence.setTypeface(Typeface.DEFAULT_BOLD);
        silence.setTextColor(Color.parseColor("#C62828"));
        silence.setBackgroundColor(Color.WHITE);
        silence.setOnClickListener(new SilenceClickListener(this));

        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams triggerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                600, 140);
        buttonLp.topMargin = 32;

        root.addView(heading, headingLp);
        root.addView(trigger, triggerLp);
        root.addView(silence, buttonLp);
        return root;
    }
}
