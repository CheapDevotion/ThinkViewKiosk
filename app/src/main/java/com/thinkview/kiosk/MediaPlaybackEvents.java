package com.thinkview.kiosk;

import android.media.MediaPlayer;
import android.util.Log;

/**
 * Implements all three MediaPlayer listener interfaces for the play_media path. Top-level
 * class to avoid the anonymous-inner-class shapes that have given d8 trouble across this
 * codebase. One instance is created per play_media call and attached to the MediaPlayer.
 */
class MediaPlaybackEvents implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener {

    private static final String TAG = "ThinkViewKiosk/Media";

    @Override
    public void onPrepared(MediaPlayer mp) {
        try {
            mp.start();
            Log.i(TAG, "play_media playback started");
        } catch (Exception ex) {
            Log.w(TAG, "play_media start failed: " + ex.getMessage());
            MediaPlaybackHelper.releaseFromCallback(mp);
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.i(TAG, "play_media completed");
        MediaPlaybackHelper.releaseFromCallback(mp);
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        // what + extra codes are documented at
        // https://developer.android.com/reference/android/media/MediaPlayer.OnErrorListener
        Log.w(TAG, "play_media error: what=" + what + " extra=" + extra);
        MediaPlaybackHelper.releaseFromCallback(mp);
        return true;
    }
}
