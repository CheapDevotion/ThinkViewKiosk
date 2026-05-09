package com.thinkview.kiosk;

import android.app.Activity;
import android.content.Intent;
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
public class MainActivity extends Activity {

    private static final String TAG = "ThinkViewKiosk";
    private static final String PREFS = "kiosk-prefs";
    private static final String KEY_URL = "dashboard-url";
    private static final String KEY_REPO_OWNER   = "repo-owner";
    private static final String KEY_REPO_NAME    = "repo-name";
    private static final String KEY_ORIENTATION  = "orientation";
    private static final String KEY_DISPLAY_NAME = "display-name";

    private static GeckoRuntime sRuntime;

    private GeckoView geckoView;
    private GeckoSession session;
    private TextView statusOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        // Force max window brightness regardless of system's adaptive-brightness setting.
        // Without this the kiosk inherits whatever Android decided was "dim enough", which on
        // wall-mounted devices is too low to read across the room.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);

        applyImmersive();

        absorbRepoExtras(getIntent());
        // Apply persisted orientation BEFORE super.onCreate completes its layout pass would be
        // ideal, but we have to settle for setRequestedOrientation here -- there'll be a brief
        // flicker on first cold start after orientation changes. Subsequent boots are clean.
        applyOrientation();
        // (Re)schedule the recurring update alarm. Idempotent (same PendingIntent replaces the
        // existing one), so safe to call on every cold start. Belt + braces vs. relying solely
        // on BootReceiver, which doesn't fire on adb-install dev iterations.
        BootReceiver.scheduleUpdateAlarm(this);
        triggerUpdateCheck();

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

        setContentView(root);
        loadWhenOnline(url);
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
        if (session != null) session.close();
        super.onDestroy();
    }

    private void absorbRepoExtras(Intent intent) {
        if (intent == null) return;
        String owner       = intent.getStringExtra("repo_owner");
        String repo        = intent.getStringExtra("repo_name");
        String orientation = intent.getStringExtra("orientation");
        String displayName = intent.getStringExtra("display_name");
        if ((owner != null && !owner.isEmpty())
                || (repo != null && !repo.isEmpty())
                || (orientation != null && !orientation.isEmpty())
                || (displayName != null && !displayName.isEmpty())) {
            SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            if (owner != null && !owner.isEmpty())             ed.putString(KEY_REPO_OWNER,   owner);
            if (repo != null && !repo.isEmpty())               ed.putString(KEY_REPO_NAME,    repo);
            if (orientation != null && !orientation.isEmpty()) ed.putString(KEY_ORIENTATION,  orientation);
            if (displayName != null && !displayName.isEmpty()) ed.putString(KEY_DISPLAY_NAME, displayName);
            ed.apply();
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
