package com.thinkview.kiosk;

import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

/**
 * Forwards ConnectivityManager.NetworkCallback events into SpotifyConnectService. Top-level
 * subclass to dodge d8's anonymous-inner-class shape issues.
 */
class SpotifyNetworkCallback extends ConnectivityManager.NetworkCallback {
    private static final String TAG = "ThinkViewKiosk/Spotify";
    private final SpotifyConnectService service;
    private boolean fired = false;

    SpotifyNetworkCallback(SpotifyConnectService service) {
        this.service = service;
    }

    @Override
    public void onAvailable(Network network) {
        if (fired) return;
        fired = true;
        Log.i(TAG, "network available; starting Spotify Connect");
        service.onNetworkAvailable();
    }
}
