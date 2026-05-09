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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.spotify.connectstate.Connect;

import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.audio.MetadataWrapper;
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

    // Now-playing footer support: state cached here, observed by MainActivity.
    // The static instance pointer mirrors AlarmListenerService's pattern.
    private static volatile SpotifyConnectService instance;
    private volatile boolean hasActiveTrack = false;
    private volatile boolean isPaused       = false;
    private volatile String currentTitle    = null;
    private volatile String currentArtist   = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /// MainActivity registers/unregisters here in onResume/onPause to receive UI updates.
    /// Single-observer (the kiosk only ever has one Activity); a list is overkill.
    public interface PlaybackObserver {
        void onSpotifyStateChanged();
    }
    private static volatile PlaybackObserver observer;
    public static void setObserver(PlaybackObserver o) { observer = o; }
    public static SpotifyConnectService getInstance() { return instance; }

    public boolean hasActiveTrack() { return hasActiveTrack; }
    public boolean isPaused()       { return isPaused; }
    public String  currentTitle()   { return currentTitle; }
    public String  currentArtist()  { return currentArtist; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
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
        if (intent != null && intent.getBooleanExtra("rebuild", false)) {
            Log.i(TAG, "rebuild requested -- closing existing Zeroconf and restarting");
            rebuildZeroconfAsync();
        }
        return START_STICKY;
    }

    /// In-place rebuild of the Zeroconf advertisement, used when the display name changes.
    /// Compared to stopService + startForegroundService, this:
    ///   - keeps the multicast lock held continuously (no Android-side mDNS gap)
    ///   - keeps the foreground service notification up (no UI flicker)
    ///   - eliminates the service lifecycle race where startForegroundService coalesces with
    ///     a pending stop and onStartCommand is delivered to the *old* instance, leaving
    ///     ZeroconfServer with the previous device name
    ///
    /// Off the main thread because Player/Session close calls can block on network I/O.
    private void rebuildZeroconfAsync() {
        Thread t = new Thread(new SpotifyRebuildTask(this), "spotify-rebuild");
        t.setDaemon(true);
        t.start();
    }

    /// Closes any live librespot objects so they can be re-created. Called from
    /// SpotifyRebuildTask under the service monitor; package-private so the top-level task
    /// can call into it without us exposing the field set.
    /// Don't reset `started` here -- we never tore down the service, just the librespot
    /// state. `started` tracks service-lifecycle state for the cold-boot path
    /// (NetworkCallback fires once) and must stay true so a late onNetworkAvailable doesn't
    /// double-start during a rebuild.
    void tearDownLibrespotState() {
        try { if (player != null) player.close(); } catch (Exception ignore) {}
        try { if (session != null) session.close(); } catch (Exception ignore) {}
        try { if (zeroconf != null) zeroconf.close(); } catch (Exception ignore) {}
        player = null;
        session = null;
        zeroconf = null;
        // Clear UI-visible state too so the footer hides during a rebuild.
        hasActiveTrack = false;
        currentTitle = null;
        currentArtist = null;
        notifyObserver();
    }

    // -------------------------------------------------------------------------------------
    // Now-playing footer support
    // -------------------------------------------------------------------------------------

    /// Called from SpotifyPlayerEventsListener on track change / metadata-available events.
    void onSpotifyMetadata(MetadataWrapper md) {
        try {
            currentTitle  = md.getName();
            currentArtist = md.getArtist();
        } catch (Exception ex) {
            Log.w(TAG, "metadata read failed: " + ex.getMessage());
            return;
        }
        hasActiveTrack = true;
        notifyObserver();
    }

    void onSpotifyPlaybackPaused(boolean paused) {
        isPaused = paused;
        notifyObserver();
    }

    void onSpotifyPlaybackEnded() {
        hasActiveTrack = false;
        currentTitle = null;
        currentArtist = null;
        notifyObserver();
    }

    void onSpotifyVolume(float volume) {
        // Volume is reflected in the system slider, not the footer. The footer only has +/-
        // buttons. Nothing to redraw on volume changes.
    }

    private void notifyObserver() {
        PlaybackObserver o = observer;
        if (o == null) return;
        // Always hop to the main thread -- librespot's event callbacks come from internal
        // worker threads.
        mainHandler.post(new SpotifyObserverDispatch(o));
    }

    public void controlPlayPause() {
        Player p = this.player;
        if (p != null) p.playPause();
    }

    public void controlNext() {
        Player p = this.player;
        if (p != null) p.next();
    }

    public void controlPrevious() {
        Player p = this.player;
        if (p != null) p.previous();
    }

    public void controlVolumeUp() {
        Player p = this.player;
        if (p != null) p.volumeUp();
    }

    public void controlVolumeDown() {
        Player p = this.player;
        if (p != null) p.volumeDown();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
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
        // SoftAp / suplicant settle even after onAvailable() fires. Only matters on cold boot --
        // rebuilds skip this via doStartConnect() directly.
        try { Thread.sleep(2000); } catch (InterruptedException ignore) {}
        doStartConnect();
    }

    /// Package-private so SpotifyRebuildTask can call it directly during a rename.
    void doStartConnect() throws Exception {
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
            // Subscribe to player events so the now-playing footer in MainActivity reflects
            // current state without polling. SpotifyPlayerEventsListener is a top-level class
            // (d8 has bitten us with anonymous inner classes; we don't take chances on a
            // 14-method interface).
            this.player.addEventsListener(new SpotifyPlayerEventsListener(this));
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
