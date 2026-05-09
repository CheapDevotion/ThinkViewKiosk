package com.thinkview.kiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

/**
 * Single-activity kiosk that loads a configured URL in a fullscreen GeckoView.
 *
 * The CD-18781Y ships with Chromium 61 (2017) as its system WebView, which is too old to parse
 * Home Assistant's modern frontend (ES6 modules, custom elements, dynamic imports). Mozilla's
 * GeckoView ships as a standalone embeddable engine -- we bundle it directly so we're not at
 * the mercy of the device's stock WebView.
 */
public class MainActivity extends Activity implements SpotifyConnectService.PlaybackObserver {

    private static final String TAG = "ThinkViewKiosk";
    private static final String PREFS = "kiosk-prefs";
    private static final String KEY_URL = "dashboard-url";
    private static final String KEY_REPO_OWNER          = "repo-owner";
    private static final String KEY_REPO_NAME           = "repo-name";
    private static final String KEY_ORIENTATION         = "orientation";
    private static final String KEY_DISPLAY_NAME        = "display-name";
    private static final String KEY_HA_TOKEN            = "ha-token";
    private static final String KEY_ALARM_ENTITY        = "alarm-entity";
    private static final String KEY_ALARM_SIREN_ENABLED = "alarm-siren-enabled";
    private static final String KEY_ALARM_CAN_DISARM    = "alarm-can-disarm";
    private static final String KEY_ALARM_DISARM_CODE   = "alarm-disarm-code";
    private static final String KEY_SCREEN_BRIGHTNESS   = "screen-brightness";

    private static GeckoRuntime sRuntime;

    private GeckoView geckoView;
    private GeckoSession session;
    private TextView statusOverlay;
    private SpotifyFooterView spotifyFooter;

    /// Static instance pointer so KioskCommandHandler.set_brightness can reach into the live
    /// activity to update the window brightness without going through the launch path.
    /// Mirrors the AlarmActivity / AlarmListenerService / SpotifyConnectService pattern.
    private static volatile MainActivity instance;
    public static MainActivity getInstance() { return instance; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        // Window brightness override. Defaults to 1.0 (full) for wall-mounted readability;
        // can be dimmed via the set_brightness kiosk_command (e.g. "alarm armed -> dim
        // bedroom panel to 20%"). Persists in SharedPreferences across reboots.
        applyBrightness();

        applyImmersive();

        absorbRepoExtras(getIntent());
        // Apply persisted orientation BEFORE super.onCreate completes its layout pass would be
        // ideal, but we have to settle for setRequestedOrientation here -- there'll be a brief
        // flicker on first cold start after orientation changes. Subsequent boots are clean.
        applyOrientation();
        // Pin ourselves as the persistent home launcher so Android stops prompting "Choose a
        // Home app" on every reboot. Requires device-owner; idempotent.
        pinAsPersistentLauncher();
        // (Re)schedule the recurring update alarm. Idempotent (same PendingIntent replaces the
        // existing one), so safe to call on every cold start. Belt + braces vs. relying solely
        // on BootReceiver, which doesn't fire on adb-install dev iterations.
        BootReceiver.scheduleUpdateAlarm(this);
        triggerUpdateCheck();

        try {
            startForegroundService(new Intent(this, SpotifyConnectService.class));
        } catch (Exception ex) {
            Log.w(TAG, "couldn't start Spotify Connect service: " + ex.getMessage());
        }

        // AlarmListenerService starts unconditionally now (v22+). It serves two purposes: HA
        // websocket subscription for kiosk_command events (fleet diagnostics, remote control)
        // AND alarm trigger detection. Earlier versions gated the start on
        // KEY_ALARM_SIREN_ENABLED, which meant kid-room devices never connected the websocket
        // and were unreachable via kiosk_command. Now the service always runs; the alarm
        // overlay is gated inside onAlarmTriggered.
        try {
            startForegroundService(new Intent(this, AlarmListenerService.class));
        } catch (Exception ex) {
            Log.w(TAG, "couldn't start alarm listener service: " + ex.getMessage());
        }

        String url = resolveUrl(getIntent());
        Log.i(TAG, "onCreate; resolved url = " + url);
        if (url == null || url.isEmpty()) {
            setContentView(buildPlaceholder());
            return;
        }

        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(getApplicationContext());
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        geckoView = new GeckoView(this);
        session = new GeckoSession();
        session.setNavigationDelegate(new KioskNavigationDelegate());
        session.setProgressDelegate(new KioskProgressDelegate());
        session.open(sRuntime);
        geckoView.setSession(session);

        statusOverlay = new TextView(this);
        statusOverlay.setText("Loading\n" + url);
        statusOverlay.setTextColor(Color.WHITE);
        statusOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        statusOverlay.setBackgroundColor(Color.parseColor("#88000000"));
        statusOverlay.setGravity(android.view.Gravity.CENTER);
        statusOverlay.setPadding(48, 48, 48, 48);

        root.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(statusOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Spotify now-playing footer pinned to the bottom. Hidden until a track starts
        // playing; shown by onSpotifyStateChanged when SpotifyConnectService has metadata.
        spotifyFooter = new SpotifyFooterView(this);
        spotifyFooter.setVisibility(View.GONE);
        FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(spotifyFooter, footerLp);

        setContentView(root);
        loadWhenOnline(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SpotifyConnectService.setObserver(this);
        // Service might already have a track playing when the activity comes back -- pull
        // current state once on resume so the footer reflects reality without waiting for
        // the next event.
        onSpotifyStateChanged();
    }

    @Override
    protected void onPause() {
        SpotifyConnectService.setObserver(null);
        super.onPause();
    }

    /// Called from SpotifyObserverDispatch on the main thread when the service's playback
    /// state changes. Reads the current snapshot off the service and pushes it into the
    /// footer view.
    @Override
    public void onSpotifyStateChanged() {
        if (spotifyFooter == null) return;
        SpotifyConnectService svc = SpotifyConnectService.getInstance();
        if (svc == null || !svc.hasActiveTrack()) {
            spotifyFooter.setVisibility(View.GONE);
            return;
        }
        spotifyFooter.setTrackText(svc.currentTitle(), svc.currentArtist());
        spotifyFooter.setArtworkUrl(svc.currentArtworkUrl());
        spotifyFooter.setPaused(svc.isPaused());
        spotifyFooter.setVolume(svc.currentVolume());
        spotifyFooter.setVisibility(View.VISIBLE);
    }

    /// Cold boots from BootReceiver race system Wi-Fi association: the kiosk app is up before
    /// the device has an IP, so the first URL load fails with ERR_INTERNET_DISCONNECTED and the
    /// user has to manually relaunch. Instead, register a NetworkCallback and only fire the
    /// load once a network with INTERNET capability is available.
    private void loadWhenOnline(String url) {
        if (isOnline()) {
            Log.i(TAG, "network available; loading immediately");
            loadResolved(url);
            return;
        }
        Log.i(TAG, "no network; deferring load");
        showOverlay("Waiting for Wi-Fi...");
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            // No connectivity manager somehow -- best-effort load anyway.
            loadResolved(url);
            return;
        }
        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cm.registerNetworkCallback(req, new NetworkAwaitCallback(this, cm, url));
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /// Loads the URL into GeckoView, doing an mDNS lookup first if the host ends in .local.
    /// Android 8.1 has no native .local resolver, so we substitute the resolved IP.
    /// Package-private so NetworkAvailableLoadAction can call back into it.
    void loadResolved(final String url) {
        final Uri parsed = Uri.parse(url);
        final String host = parsed.getHost();
        if (host == null || !host.toLowerCase().endsWith(".local")) {
            session.loadUri(url);
            return;
        }
        showOverlay("Resolving " + host + " ...");

        Thread t = new Thread(new MdnsLoadTask(this, session, host, url, statusOverlay), "kiosk-mdns");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        absorbRepoExtras(intent);
        String url = resolveUrl(intent);
        Log.i(TAG, "onNewIntent; resolved url = " + url);
        if (url != null && !url.isEmpty() && session != null) {
            loadWhenOnline(url);
        }
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        if (session != null) session.close();
        super.onDestroy();
    }

    /// Reads screen-brightness pref and applies it to the window. Called from onCreate and
    /// from the set_brightness kiosk_command path. Clamps to [0.05, 1.0] -- below 5% the
    /// screen is functionally unreadable and we don't want a remote command to lock the
    /// user out of touch interaction. Above 1.0 is undefined per Android docs.
    public void applyBrightness() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        float brightness = prefs.getFloat(KEY_SCREEN_BRIGHTNESS, 1.0f);
        if (brightness < 0.05f) brightness = 0.05f;
        if (brightness > 1.0f)  brightness = 1.0f;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = brightness;
        getWindow().setAttributes(lp);
        Log.i(TAG, "screen brightness applied = " + brightness);
    }

    private void absorbRepoExtras(Intent intent) {
        if (intent == null) return;
        String owner       = intent.getStringExtra("repo_owner");
        String repo        = intent.getStringExtra("repo_name");
        String orientation = intent.getStringExtra("orientation");
        String displayName = intent.getStringExtra("display_name");
        String haToken     = intent.getStringExtra("ha_token");
        String alarmEntity = intent.getStringExtra("alarm_entity");
        String disarmCode  = intent.getStringExtra("alarm_disarm_code");
        boolean hasSirenFlag = intent.hasExtra("alarm_siren_enabled");
        boolean sirenEnabled = intent.getBooleanExtra("alarm_siren_enabled", false);
        boolean hasCanDisarmFlag = intent.hasExtra("alarm_can_disarm");
        boolean canDisarm = intent.getBooleanExtra("alarm_can_disarm", false);
        boolean hasBrightness = intent.hasExtra("screen_brightness");
        float brightness = intent.getFloatExtra("screen_brightness", 1.0f);

        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        boolean dirty = false;
        if (owner != null && !owner.isEmpty())             { ed.putString(KEY_REPO_OWNER,   owner);       dirty = true; }
        if (repo != null && !repo.isEmpty())               { ed.putString(KEY_REPO_NAME,    repo);        dirty = true; }
        if (orientation != null && !orientation.isEmpty()) { ed.putString(KEY_ORIENTATION,  orientation); dirty = true; }
        if (displayName != null && !displayName.isEmpty()) { ed.putString(KEY_DISPLAY_NAME, displayName); dirty = true; }
        if (haToken != null && !haToken.isEmpty())         { ed.putString(KEY_HA_TOKEN,     haToken);     dirty = true; }
        if (alarmEntity != null && !alarmEntity.isEmpty()) { ed.putString(KEY_ALARM_ENTITY, alarmEntity); dirty = true; }
        if (disarmCode != null)                            { ed.putString(KEY_ALARM_DISARM_CODE, disarmCode); dirty = true; }
        if (hasSirenFlag)                                  { ed.putBoolean(KEY_ALARM_SIREN_ENABLED, sirenEnabled); dirty = true; }
        if (hasCanDisarmFlag)                              { ed.putBoolean(KEY_ALARM_CAN_DISARM, canDisarm); dirty = true; }
        if (hasBrightness)                                 { ed.putFloat(KEY_SCREEN_BRIGHTNESS, brightness); dirty = true; }
        if (dirty) ed.apply();
    }

    /// Tells Android we are THE home launcher, period. With device-owner privileges (set during
    /// provisioning) this overrides the system's "Choose a Home app" prompt that otherwise
    /// fires every reboot when more than one app declares CATEGORY_HOME. Idempotent -- safe to
    /// call on every onCreate.
    private void pinAsPersistentLauncher() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) return;
        if (!dpm.isDeviceOwnerApp(getPackageName())) {
            Log.i(TAG, "not device-owner; can't pin launcher");
            return;
        }
        try {
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            ComponentName home  = new ComponentName(this, MainActivity.class);
            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_HOME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            // clear-then-add to keep the list from growing if Android stores duplicates.
            dpm.clearPackagePersistentPreferredActivities(admin, getPackageName());
            dpm.addPersistentPreferredActivity(admin, filter, home);
            Log.i(TAG, "pinned as persistent home launcher");
        } catch (Exception ex) {
            Log.w(TAG, "couldn't pin launcher: " + ex.getMessage());
        }
    }

    private void applyOrientation() {
        String pref = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ORIENTATION, "landscape");
        int requested;
        switch (pref) {
            case "portrait":         requested = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "reversePortrait":  requested = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case "reverseLandscape": requested = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            case "landscape":
            default:                 requested = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
        }
        Log.i(TAG, "orientation = " + pref);
        setRequestedOrientation(requested);
    }

    private void triggerUpdateCheck() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        UpdateChecker.checkAsync(this,
                prefs.getString(KEY_REPO_OWNER, null),
                prefs.getString(KEY_REPO_NAME,  null));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Eat BACK so a stray gesture can't drop the user out of the kiosk.
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    private String resolveUrl(Intent intent) {
        String fromExtra = intent != null ? intent.getStringExtra("url") : null;
        if (fromExtra != null && !fromExtra.isEmpty()) {
            persist(fromExtra);
            return fromExtra;
        }
        Uri data = intent != null ? intent.getData() : null;
        if (data != null) {
            String fromData = data.toString();
            if (!fromData.isEmpty()) {
                persist(fromData);
                return fromData;
            }
        }
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, null);
    }

    private void persist(String url) {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        ed.putString(KEY_URL, url);
        ed.apply();
    }

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decor.setSystemUiVisibility(flags);
    }

    void showOverlay(String text) {
        if (statusOverlay == null) return;
        statusOverlay.setText(text);
        statusOverlay.setVisibility(View.VISIBLE);
    }

    void hideOverlay() {
        if (statusOverlay == null) return;
        statusOverlay.setVisibility(View.GONE);
    }

    private View buildPlaceholder() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        TextView tv = new TextView(this);
        tv.setText("ThinkView Kiosk\n\nNo dashboard URL configured.\n\n"
                + "Set one via:\n  adb shell am start --es url \"https://...\" \\\n"
                + "    -n com.thinkview.kiosk/.MainActivity");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setPadding(48, 48, 48, 48);
        tv.setGravity(android.view.Gravity.CENTER);
        root.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private class KioskNavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override
        public GeckoResult<String> onLoadError(GeckoSession session, String uri,
                                               WebRequestError error) {
            Log.w(TAG, "Gecko load error " + error.code + " on " + uri);
            showOverlay("Couldn't load page:\n" + uri + "\n\nError " + error.code
                    + "\n\nWill retry on next load.");
            return null;
        }
    }

    private class KioskProgressDelegate implements GeckoSession.ProgressDelegate {
        @Override
        public void onPageStart(GeckoSession session, String url) {
            Log.i(TAG, "page start: " + url);
            showOverlay("Loading\n" + url);
        }

        @Override
        public void onPageStop(GeckoSession session, boolean success) {
            Log.i(TAG, "page stop, success=" + success);
            if (success) hideOverlay();
        }
    }
}
