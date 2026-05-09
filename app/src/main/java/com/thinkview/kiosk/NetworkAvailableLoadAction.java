package com.thinkview.kiosk;

/**
 * UI-thread Runnable that calls back into MainActivity to start the URL load via the existing
 * mDNS-aware path. Top-level to avoid d8's anonymous-class issues.
 */
class NetworkAvailableLoadAction implements Runnable {
    private final MainActivity activity;
    private final String url;

    NetworkAvailableLoadAction(MainActivity activity, String url) {
        this.activity = activity;
        this.url = url;
    }

    @Override
    public void run() {
        activity.loadResolved(url);
    }
}
