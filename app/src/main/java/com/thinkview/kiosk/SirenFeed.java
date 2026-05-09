package com.thinkview.kiosk;

/**
 * Pumps synthesized siren samples into SirenPlayer's AudioTrack. Top-level Runnable to dodge
 * d8's anonymous-inner-class issues.
 */
class SirenFeed implements Runnable {
    private final SirenPlayer player;

    SirenFeed(SirenPlayer player) { this.player = player; }

    @Override
    public void run() {
        short[] buf = new short[2048];
        long sample = 0;
        while (player.writeChunk(buf, sample)) {
            sample += buf.length;
        }
    }
}
