package com.thinkview.kiosk;

import android.util.Log;

/**
 * Background runnable that initializes librespot's Zeroconf server. Top-level class to dodge
 * d8's anonymous-class shape issues; also gets the heavy network/crypto setup off the main
 * thread (Spotify's ZeroconfServer constructor does protocol handshakes + DNS).
 */
class SpotifyConnectStarter implements Runnable {
    private static final String TAG = "ThinkViewKiosk/Spotify";
    private final SpotifyConnectService service;

    SpotifyConnectStarter(SpotifyConnectService service) {
        this.service = service;
    }

    @Override
    public void run() {
        try {
            service.startConnect();
        } catch (Exception ex) {
            Log.w(TAG, "Spotify Connect startup failed: " + ex.getMessage(), ex);
        }
    }
}
