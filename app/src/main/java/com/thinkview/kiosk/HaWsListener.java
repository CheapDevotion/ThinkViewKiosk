package com.thinkview.kiosk;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Forwards OkHttp WebSocketListener callbacks into HaWebSocketClient. Top-level subclass to
 * dodge d8's flakiness with anonymous-inner-class shapes (we've hit this several times now).
 */
class HaWsListener extends WebSocketListener {
    private final HaWebSocketClient client;

    HaWsListener(HaWebSocketClient client) { this.client = client; }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        client.onOpen();
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        client.onTextMessage(text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        // HA WebSocket API is JSON-over-text; binary frames aren't part of the contract.
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        client.onClosing(code, reason);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        client.onClosed(code, reason);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        client.onFailure(t);
    }
}
