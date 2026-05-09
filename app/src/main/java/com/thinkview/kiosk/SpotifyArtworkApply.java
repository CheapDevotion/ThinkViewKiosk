package com.thinkview.kiosk;

import android.graphics.Bitmap;

/**
 * Trivial main-thread Runnable that applies a fetched cover-art Bitmap to the footer view,
 * but only if the URL we fetched still matches what the footer is currently displaying. Stale
 * fetches (the user skipped to another track while we were waiting on the network) get
 * silently discarded. Top-level class to avoid anonymous-inner-class shapes that have given
 * d8 trouble in this codebase.
 */
class SpotifyArtworkApply implements Runnable {
    private final SpotifyFooterView footer;
    private final String url;
    private final Bitmap bitmap;

    SpotifyArtworkApply(SpotifyFooterView footer, String url, Bitmap bitmap) {
        this.footer = footer;
        this.url = url;
        this.bitmap = bitmap;
    }

    @Override
    public void run() {
        footer.applyArtworkIfMatch(url, bitmap);
    }
}
