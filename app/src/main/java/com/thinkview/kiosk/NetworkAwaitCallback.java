package com.thinkview.kiosk;

import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

/**
 * Registered against ConnectivityManager when the kiosk launches before Wi-Fi is up
 * (cold-boot autostart races with system Wi-Fi association). When a network with INTERNET
 * capability becomes available, unregisters itself and triggers the URL load on the UI thread.
 *
 * Top-level class to side-step d8's flakiness with anonymous-inner-class shapes.
 */
class NetworkAwaitCallback extends ConnectivityManager.NetworkCallback {
    private static final String TAG = "ThinkViewKiosk";

    private final MainActivity activity;
    private final ConnectivityManager cm;
    private final String url;
    private boolean fired = false;

    NetworkAwaitCallback(MainActivity activity, ConnectivityManager cm, String url) {
        this.activity = activity;
        this.cm = cm;
        this.url = url;
    }

    @Override
    public void onAvailable(Network network) {
        if (fired) return;
        fired = true;
        Log.i(TAG, "network available; loading URL");
        try { cm.unregisterNetworkCallback(this); } catch (Exception ignore) {}
        // Brief grace for DNS resolvers to come up after IP is assigned. Without it, the very
        // first request after WiFi associates can resolve to NXDOMAIN.
        try { Thread.sleep(2000); } catch (InterruptedException ignore) {}
        activity.runOnUiThread(new NetworkAvailableLoadAction(activity, url));
    }
}
