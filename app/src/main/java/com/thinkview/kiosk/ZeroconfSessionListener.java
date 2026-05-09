package com.thinkview.kiosk;

import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.core.Session;

/**
 * Forwards librespot Zeroconf session events back into SpotifyConnectService. Top-level class
 * to dodge d8's anonymous-inner-class shape issues.
 */
class ZeroconfSessionListener implements ZeroconfServer.SessionListener {
    private final SpotifyConnectService service;

    ZeroconfSessionListener(SpotifyConnectService service) {
        this.service = service;
    }

    @Override
    public void sessionClosing(Session session) {
        // No-op -- Zeroconf server stays alive, ready for the next pairing.
    }

    @Override
    public void sessionChanged(Session session) {
        service.onSessionStarted(session);
    }
}
