package com.thinkview.kiosk;

/**
 * Main-thread task that stops the siren and finishes the AlarmActivity. Top-level Runnable
 * for the same d8 reasons we keep flagging in this codebase. Captures the activity at
 * construction; the activity's stopSirenAndFinish guards against being called twice or after
 * destruction.
 */
class AlarmDismissTask implements Runnable {
    private final AlarmActivity activity;

    AlarmDismissTask(AlarmActivity activity) {
        this.activity = activity;
    }

    @Override
    public void run() {
        if (activity != null) activity.stopSirenAndFinish();
    }
}
