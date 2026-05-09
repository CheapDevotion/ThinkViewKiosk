package com.thinkview.kiosk;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches a Spotify cover-art image from i.scdn.co and hands the decoded Bitmap back to the
 * footer on the main thread. Top-level Runnable to avoid d8 anonymous-inner-class issues.
 *
 * Stale-fetch handling lives in {@link SpotifyFooterView#applyArtworkIfMatch}: this fetcher
 * tags its result with the URL it was asked to fetch, and the view drops the bitmap if the
 * user has skipped to a different track in the meantime. That keeps us from flashing the
 * wrong cover when track changes pile up faster than the network can keep up.
 */
class SpotifyArtworkFetcher implements Runnable {
    private static final String TAG = "ThinkViewKiosk/Spotify";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS    = 15000;

    private final SpotifyFooterView footer;
    private final String url;
    private final Handler mainHandler;

    SpotifyArtworkFetcher(SpotifyFooterView footer, String url, Handler mainHandler) {
        this.footer = footer;
        this.url = url;
        this.mainHandler = mainHandler;
    }

    @Override
    public void run() {
        Bitmap bmp = fetch();
        if (bmp == null) return;
        mainHandler.post(new SpotifyArtworkApply(footer, url, bmp));
    }

    private Bitmap fetch() {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "artwork HTTP " + code + " for " + url);
                return null;
            }
            in = new BufferedInputStream(conn.getInputStream());
            // BitmapFactory will scale down on its own if we set inSampleSize, but Spotify's
            // LARGE size (300x300) is already small enough to fit comfortably in a 48dp
            // ImageView without measurable memory pressure. Skip the two-pass decode.
            return BitmapFactory.decodeStream(in);
        } catch (Exception ex) {
            Log.w(TAG, "artwork fetch failed for " + url + ": " + ex.getMessage());
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) {}
            if (conn != null) conn.disconnect();
        }
    }
}
