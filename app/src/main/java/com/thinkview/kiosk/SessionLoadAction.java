package com.thinkview.kiosk;

import android.view.View;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoSession;

/**
 * UI-thread Runnable that updates the status overlay (if any) and tells GeckoView to load a URL.
 * Top-level class to side-step the d8 inner-class shape bug that bit us repeatedly.
 */
class SessionLoadAction implements Runnable {
    private final GeckoSession session;
    private final String url;
    private final TextView overlay;
    private final String overlayText;

    SessionLoadAction(GeckoSession session, String url, TextView overlay, String overlayText) {
        this.session = session;
        this.url = url;
        this.overlay = overlay;
        this.overlayText = overlayText;
    }

    @Override
    public void run() {
        if (overlay != null && overlayText != null) {
            overlay.setText(overlayText);
            overlay.setVisibility(View.VISIBLE);
        }
        session.loadUri(url);
    }
}
