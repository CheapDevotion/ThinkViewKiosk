package com.thinkview.kiosk;

import android.util.Log;

/**
 * Worker that closes the existing Zeroconf advertisement and re-creates it with the current
 * display name. Top-level class to avoid d8's known issues with anonymous-inner-class shapes
 * (we've eaten that bug enough times in this codebase to make a habit of avoiding it).
 *
 * Synchronizes on the service so a rapid double-rename can't race two rebuilds against each
 * other and leak a ZeroconfServer.
 */
class SpotifyRebuildTask implements Runnable {
    private static final String TAG = "ThinkViewKiosk/Spotify";
    private final SpotifyConnectService service;

    SpotifyRebuildTask(SpotifyConnectService service) {
        this.service = service;
    }

    @Override
    public void run() {
        synchronized (service) {
            service.tearDownLibrespotState();
            try {
                service.doStartConnect();
            } catch (Exception ex) {
                Log.w(TAG, "rebuild doStartConnect failed: " + ex.getMessage(), ex);
            }
        }
    }
}
