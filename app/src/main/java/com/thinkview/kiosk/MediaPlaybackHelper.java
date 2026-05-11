package com.thinkview.kiosk;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * Plays an arbitrary audio URL via Android's MediaPlayer. Driven by the play_media /
 * stop_media kiosk_commands. Primary use case is HA-driven alarm clocks: HA fires
 * play_media with a URL pointing at /local/wakeup.mp3 (or a TTS proxy URL), the kiosk
 * fetches and plays it.
 *
 * Audio attributes: USAGE_ALARM + CONTENT_TYPE_MUSIC. That maps to STREAM_ALARM so:
 *   - playback bypasses do-not-disturb / silent ringer modes (an alarm clock needs to
 *     actually wake you up regardless of what your phone settings look like)
 *   - on most Android builds, foreground media (e.g. Spotify Connect via librespot)
 *     receives an AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK and ducks, so the alarm rides on
 *     top of any currently-playing music without us having to manually pause Spotify
 *
 * We don't peg STREAM_ALARM to max here -- that's SirenPlayer's job for the security
 * siren. Routine alarm clocks play at whatever STREAM_ALARM volume the device is set
 * to (defaults to ~40% out of the box; tunable via volume keys).
 *
 * Single-track only: only one play_media can be active at a time. Firing play_media
 * again replaces the current track. Firing stop_media (or letting it complete) releases
 * the MediaPlayer.
 */
class MediaPlaybackHelper {
    private static final String TAG = "ThinkViewKiosk/Media";

    private static final Object LOCK = new Object();
    private static MediaPlayer current;

    static void play(Context context, String url) {
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "play_media with empty url; ignoring");
            return;
        }
        synchronized (LOCK) {
            stopInternal();
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                MediaPlaybackEvents events = new MediaPlaybackEvents();
                mp.setOnPreparedListener(events);
                mp.setOnCompletionListener(events);
                mp.setOnErrorListener(events);
                mp.setDataSource(url);
                mp.prepareAsync();
                current = mp;
                Log.i(TAG, "play_media starting: " + url);
            } catch (Exception ex) {
                Log.w(TAG, "play_media setup failed: " + ex.getMessage());
                try { mp.release(); } catch (Exception ignore) {}
                current = null;
            }
        }
    }

    static void stop() {
        synchronized (LOCK) {
            stopInternal();
        }
    }

    /// Called by MediaPlaybackEvents.onCompletion / onError; package-private. Always
    /// holds LOCK because it's invoked from within a synchronized block.
    static void releaseFromCallback(MediaPlayer mp) {
        synchronized (LOCK) {
            if (current == mp) {
                try { mp.release(); } catch (Exception ignore) {}
                current = null;
                Log.i(TAG, "play_media released");
            }
        }
    }

    private static void stopInternal() {
        if (current == null) return;
        try { current.stop(); } catch (Exception ignore) {}
        try { current.release(); } catch (Exception ignore) {}
        current = null;
        Log.i(TAG, "play_media stopped");
    }
}
