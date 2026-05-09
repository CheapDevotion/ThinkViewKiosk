package com.thinkview.kiosk;

/**
 * Runnable that fires the next volume increment and re-schedules itself on the same Handler.
 * Lives as a top-level class to dodge anonymous-Runnable shapes (d8 has bitten this codebase
 * enough times to make it routine) and to make the self-reschedule explicit.
 *
 * The owning VolumeButtonTouchListener calls handler.removeCallbacks(repeater) on touch UP
 * which cancels any pending re-fire, so this runnable is naturally bounded by finger contact.
 */
class RepeatTask implements Runnable {
    private final VolumeButtonTouchListener owner;

    RepeatTask(VolumeButtonTouchListener owner) {
        this.owner = owner;
    }

    @Override
    public void run() {
        owner.fireOnce();
        owner.handler().postDelayed(this, owner.repeatInterval());
    }
}
