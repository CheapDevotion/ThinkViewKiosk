package com.thinkview.kiosk;

class ReconnectTask implements Runnable {
    private final HaWebSocketClient client;
    private final long delayMs;

    ReconnectTask(HaWebSocketClient client, long delayMs) {
        this.client = client;
        this.delayMs = delayMs;
    }

    @Override
    public void run() {
        try { Thread.sleep(delayMs); } catch (InterruptedException ignore) {}
        client.doReconnect();
    }
}
