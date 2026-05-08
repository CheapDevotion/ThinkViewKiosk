package com.thinkview.kiosk;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Single-activity kiosk that loads a configured URL in a fullscreen WebView.
 *
 * The dashboard URL is set via the Intent extra "url" on launch, OR via the Intent's data field
 * (so http(s) VIEW intents work too). Either form persists the URL to SharedPreferences so
 * subsequent launches (boot autostart, foreground re-entry) reuse it.
 *
 * Set this app as the system home launcher to make it the kiosk surface — its manifest declares
 * android.intent.category.HOME so `cmd package set-home-activity` works.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "kiosk-prefs";
    private static final String KEY_URL = "dashboard-url";
    private static final String KEY_REPO_OWNER = "repo-owner";
    private static final String KEY_REPO_NAME  = "repo-name";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        applyImmersive();

        // Persist any repo config the launcher passed in. Then kick off an opportunistic
        // update check; the AlarmManager (scheduled by BootReceiver) handles the recurring path.
        absorbRepoExtras(getIntent());
        triggerUpdateCheck();

        String url = resolveUrl(getIntent());
        if (url == null || url.isEmpty()) {
            setContentView(buildPlaceholder());
            return;
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);
        web.loadUrl(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        absorbRepoExtras(intent);
        String url = resolveUrl(intent);
        if (url != null && !url.isEmpty() && web != null) {
            web.loadUrl(url);
        }
    }

    private void absorbRepoExtras(Intent intent) {
        if (intent == null) return;
        String owner = intent.getStringExtra("repo_owner");
        String repo  = intent.getStringExtra("repo_name");
        if ((owner != null && !owner.isEmpty()) || (repo != null && !repo.isEmpty())) {
            SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            if (owner != null && !owner.isEmpty()) ed.putString(KEY_REPO_OWNER, owner);
            if (repo != null && !repo.isEmpty())   ed.putString(KEY_REPO_NAME,  repo);
            ed.apply();
        }
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
        // Intercept BACK so a stray gesture can't exit the kiosk; let WebView navigate back if it can.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /// Read the URL from (in order): Intent extra "url", Intent data, persisted prefs.
    /// Persists whatever it finds to prefs so the next cold start has it.
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
}
