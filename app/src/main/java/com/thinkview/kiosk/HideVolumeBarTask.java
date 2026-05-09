package com.thinkview.kiosk;

/**
 * Runnable that fades the volume bar out and brings the track text back. Posted to the
 * main-thread Handler when setVolume() is called, with the auto-hide delay. Top-level class
 * (rather than an anonymous Runnable inline in SpotifyFooterView) for the same d8-avoidance
 * habit we keep flagging.
 */
class HideVolumeBarTask implements Runnable {
    private final SpotifyFooterView footer;

    HideVolumeBarTask(SpotifyFooterView footer) {
        this.footer = footer;
    }

    @Override
    public void run() {
        if (footer != null) footer.hideVolumeBar();
    }
}
