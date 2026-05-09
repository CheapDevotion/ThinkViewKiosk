package com.thinkview.kiosk;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoSession;

import java.util.regex.Pattern;

/**
 * Background runnable: do an mDNS lookup for {@code host}, substitute the IP into {@code url},
 * and post the load back to the activity's main thread. Lives as a top-level class to side-step
 * d8's flakiness with anonymous Runnable shapes (we hit this earlier on this build setup).
 */
class MdnsLoadTask implements Runnable {
    private static final String TAG = "ThinkViewKiosk";

    private final Activity activity;
    private final GeckoSession session;
    private final String host;
    private final String url;
    private final TextView statusOverlay;

    MdnsLoadTask(Activity activity, GeckoSession session, String host, String url, TextView statusOverlay) {
        this.activity = activity;
        this.session = session;
        this.host = host;
        this.url = url;
        this.statusOverlay = statusOverlay;
    }

    @Override
    public void run() {
        String ip = MdnsResolver.resolve(activity, host, 3000);
        String finalUrl;
        String overlayText;
        if (ip != null) {
            finalUrl = url.replaceFirst("://" + Pattern.quote(host), "://" + ip);
            overlayText = null;
            Log.i(TAG, "mDNS " + host + " -> " + ip);
        } else {
            finalUrl = url;
            overlayText = "mDNS lookup failed for " + host + "\nFalling back to system DNS...";
            Log.w(TAG, "mDNS " + host + " did not resolve; loading original URL");
        }
        activity.runOnUiThread(new SessionLoadAction(session, finalUrl, statusOverlay, overlayText));
    }
}
