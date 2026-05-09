package com.thinkview.kiosk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.spotify.connectstate.Connect;

import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.audio.decoders.AudioQuality;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.PlayerConfiguration;

import java.io.File;

/**
 * Foreground service that runs librespot-java's Zeroconf Spotify Connect receiver.
 *
 * Two non-obvious requirements that bit us in earlier versions:
 *   1. ZeroconfServer enumerates network interfaces in its constructor. If we start it before
 *      Wi-Fi has associated (cold-boot race), it binds to nothing useful and the kiosk is
 *      invisible on the LAN forever. We register a NetworkCallback and only initialize once a
 *      network with INTERNET capability is up.
 *   2. Android only delivers multicast traffic to apps holding a WifiManager.MulticastLock.
 *      librespot's mDNS announcements (so phones can find the kiosk) need it. We acquire one
 *      for the service's whole lifetime.
 *
 * Auth: no on-device login. The user opens Spotify on their phone (same Wi-Fi), taps "Connect
 * to a device", picks our display name, and Spotify hands credentials over Zeroconf. They cache
 * to {@code cacheDir/spotify/credentials.json} so subsequent boots don't need re-auth.
 *
 * Audio output: AudioTrackSink (our SinkOutput implementation, registered via outputClass).
 * librespot's stock MixerOutput uses javax.sound which doesn't exist on Android.
 *
 * Spotify Premium account required (Spotify's licensing for Connect receivers).
 */
public class SpotifyConnectService extends Service {
    private static final String TAG = "ThinkViewKiosk/Spotify";
    private static final String CHANNEL_ID = "kiosk-spotify";
    private static final int NOTIFICATION_ID = 9001;

    private WifiManager.MulticastLock multicastLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ZeroconfServer zeroconf;
    private Session session;
    private Player player;
    private volatile boolean started = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for Wi-Fi…"));

        // Multicast lock held for the service lifetime -- librespot's Zeroconf needs to send
        // mDNS announcements to the LAN, and Android gates that behind a held lock.
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("kiosk-spotify-mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            Log.i(TAG, "multicast lock acquired");
        }

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            // Best-effort: try anyway.
            startLater();
            return;
        }
        if (isOnline(cm)) {
            startLater();
        } else {
            Log.i(TAG, "no network yet; waiting for it before starting Zeroconf");
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            networkCallback = new SpotifyNetworkCallback(this);
            cm.registerNetworkCallback(req, networkCallback);
        }
    }

    private boolean isOnline(ConnectivityManager cm) {
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /// Called by SpotifyNetworkCallback once the network is available.
    void onNetworkAvailable() {
        if (started) return;
        startLater();
    }

    private void startLater() {
        if (started) return;
        started = true;
        // Brief grace for DHCP/DNS to settle before NetworkInterface enumeration.
        Thread t = new Thread(new SpotifyConnectStarter(this), "spotify-connect-startup");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { if (player != null) player.close(); } catch (Exception ignore) {}
        try { if (session != null) session.close(); } catch (Exception ignore) {}
        try { if (zeroconf != null) zeroconf.close(); } catch (Exception ignore) {}
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignore) {}
            networkCallback = null;
        }
        if (multicastLock != null) {
            try { multicastLock.release(); } catch (Exception ignore) {}
            multicastLock = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    void startConnect() throws Exception {
        // Brief grace before enumerating interfaces; on Android 8.1 the network can flap as
        // SoftAp / suplicant settle even after onAvailable() fires.
        try { Thread.sleep(2000); } catch (InterruptedException ignore) {}

        SharedPreferences prefs = getSharedPreferences("kiosk-prefs", MODE_PRIVATE);
        String displayName = prefs.getString("display-name", "ThinkView Kiosk");
        Log.i(TAG, "starting Spotify Connect target: " + displayName);

        File cacheDir = new File(getCacheDir(), "spotify");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        Session.Configuration sessionConf = new Session.Configuration.Builder()
                .setStoreCredentials(true)
                .setStoredCredentialsFile(new File(cacheDir, "credentials.json"))
                .setCacheEnabled(true)
                .setCacheDir(cacheDir)
                .setDoCacheCleanUp(true)
                .build();

        zeroconf = new ZeroconfServer.Builder(sessionConf)
                .setPreferredLocale("en")
                .setDeviceName(displayName)
                .setDeviceType(Connect.DeviceType.SPEAKER)
                .create();
        zeroconf.addSessionListener(new ZeroconfSessionListener(this));
        updateNotification(displayName + " — Spotify Connect ready");
        Log.i(TAG, "Zeroconf server up; advertising as '" + displayName + "'");
    }

    void onSessionStarted(Session newSession) {
        Log.i(TAG, "Spotify session started");
        this.session = newSession;
        try {
            PlayerConfiguration playerConf = new PlayerConfiguration.Builder()
                    .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
                    .setOutputClass(AudioTrackSink.class.getName())
                    .setPreferredQuality(AudioQuality.HIGH)
                    .setEnableNormalisation(true)
                    .build();
            this.player = new Player(playerConf, newSession);
            updateNotification("Playing — " + getDisplayName());
        } catch (Exception ex) {
            Log.w(TAG, "couldn't start player: " + ex.getMessage(), ex);
        }
    }

    private String getDisplayName() {
        return getSharedPreferences("kiosk-prefs", MODE_PRIVATE)
                .getString("display-name", "ThinkView Kiosk");
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Spotify Connect", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("ThinkView Kiosk")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
