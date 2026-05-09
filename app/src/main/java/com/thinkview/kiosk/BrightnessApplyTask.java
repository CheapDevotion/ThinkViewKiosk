package com.thinkview.kiosk;

/**
 * Main-thread Runnable that re-applies the persisted screen-brightness pref to the running
 * MainActivity's window. Top-level class for d8 sanity (anonymous-inner-class issues have
 * bitten us enough times to make a habit of avoiding them).
 *
 * Captures the activity at construction; MainActivity.applyBrightness reads the latest pref
 * value, so this task doesn't need to know what the new brightness is.
 */
class BrightnessApplyTask implements Runnable {
    private final MainActivity activity;

    BrightnessApplyTask(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public void run() {
        if (activity != null) activity.applyBrightness();
    }
}
