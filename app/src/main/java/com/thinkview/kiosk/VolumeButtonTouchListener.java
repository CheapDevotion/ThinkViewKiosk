package com.thinkview.kiosk;

import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;

/**
 * OnTouchListener that fires a volume up or down command on press, then auto-repeats while
 * the finger stays down. Pattern: long delay before the first repeat (so a single tap is
 * still a single step) followed by tighter intervals (so holding feels responsive).
 *
 * Used by SpotifyFooterView's volume +/- buttons. Top-level class to keep d8 happy and to
 * make the touch lifecycle easier to reason about than a lambda field.
 */
class VolumeButtonTouchListener implements View.OnTouchListener {

    /// Wait this long before the first auto-repeat fires, so a quick tap-and-release is
    /// always exactly one step.
    private static final long INITIAL_DELAY_MS = 380;

    /// Subsequent repeats while the finger is still down. ~7 taps/sec; fast enough that a
    /// 1-2 second hold sweeps a meaningful chunk of the volume range.
    private static final long REPEAT_INTERVAL_MS = 140;

    enum Direction { UP, DOWN }

    private final Direction direction;
    private final Handler handler;
    private final RepeatTask repeater;

    VolumeButtonTouchListener(Direction direction, Handler handler) {
        this.direction = direction;
        this.handler = handler;
        this.repeater = new RepeatTask(this);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                fireOnce();
                v.setPressed(true); // triggers the StateListDrawable press state
                handler.postDelayed(repeater, INITIAL_DELAY_MS);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(repeater);
                v.setPressed(false);
                // performClick for accessibility / lint silencing. Doesn't trigger any
                // additional volume change because we didn't register an OnClickListener
                // on these buttons (see SpotifyFooterView.makeButton -- the volume buttons
                // get their listener replaced).
                v.performClick();
                return true;
        }
        return false;
    }

    void fireOnce() {
        SpotifyConnectService svc = SpotifyConnectService.getInstance();
        if (svc == null) return;
        if (direction == Direction.UP) svc.controlVolumeUp();
        else                            svc.controlVolumeDown();
    }

    long repeatInterval() { return REPEAT_INTERVAL_MS; }

    Handler handler() { return handler; }
}
