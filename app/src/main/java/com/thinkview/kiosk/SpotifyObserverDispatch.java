package com.thinkview.kiosk;

/**
 * Trivial Runnable that fires SpotifyConnectService.PlaybackObserver.onSpotifyStateChanged
 * on the main thread. Top-level class to avoid anonymous-inner-class shapes that have caused
 * d8 issues in this codebase.
 *
 * Captures the observer reference at construction time so a late-arriving event after the
 * observer has been cleared doesn't NPE -- we just no-op if it was nulled.
 */
class SpotifyObserverDispatch implements Runnable {
    private final SpotifyConnectService.PlaybackObserver observer;

    SpotifyObserverDispatch(SpotifyConnectService.PlaybackObserver observer) {
        this.observer = observer;
    }

    @Override
    public void run() {
        if (observer != null) {
            try {
                observer.onSpotifyStateChanged();
            } catch (Exception ignore) {
                // Observer's job to be defensive; we just don't propagate.
            }
        }
    }
}
