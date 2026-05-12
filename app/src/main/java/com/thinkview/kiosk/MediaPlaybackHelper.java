package com.thinkview.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * Plays an arbitrary audio URL via Android's MediaPlayer. Driven by the play_media /
 * stop_media kiosk_commands. Primary use case is HA-driven alarm clocks: HA fires
 * play_media with a URL pointing at /local/wakeup.mp3 (or a TTS proxy URL), the kiosk
 * fetches and plays it.
 *
 * Volume model (v33+):
 *   The kiosk reports a 0.0-1.0 "media volume" preference, set via the set_volume
 *   kiosk_command and stored in SharedPreferences under media-volume. While play_media is
 *   active we:
 *     1. Save the current STREAM_ALARM index
 *     2. Peg STREAM_ALARM to its hardware max (so we have the full dynamic range to
 *        attenuate from)
 *     3. Apply the 0.0-1.0 fraction via MediaPlayer.setVolume(left, right) -- a linear
 *        per-sample software gain that's much more predictable than Android's stream-
 *        volume curve (which on the CD-18781Y maps the lower half of the 0-15 range to
 *        effectively inaudible attenuations, producing the surprising "below 60% is
 *        silent" behavior we hit in v31).
 *   On stop / completion we restore STREAM_ALARM to whatever it was before. The security
 *   siren (SirenPlayer) does its own independent peg/restore around its playback, so
 *   the two paths don't fight each other.
 *
 * Audio attributes: USAGE_ALARM + CONTENT_TYPE_MUSIC. That maps to STREAM_ALARM so:
 *   - playback bypasses do-not-disturb / silent ringer modes
 *   - foreground media (e.g. Spotify Connect via librespot) receives an
 *     AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK and ducks while the alarm plays
 *
 * Single-track only: only one play_media can be active at a time. Firing play_media
 * again replaces the current track. Firing stop_media (or letting it complete) releases
 * the MediaPlayer.
 */
class MediaPlaybackHelper {
    private static final String TAG = "ThinkViewKiosk/Media";
    private static final String PREFS = "kiosk-prefs";
    private static final String KEY_MEDIA_VOLUME = "media-volume";

    private static final Object LOCK = new Object();
    private static MediaPlayer current;
    private static int savedAlarmVolume = -1;
    /// Cached application context so MediaPlaybackEvents (which doesn't have one) can
    /// trigger restore via releaseFromCallback. Captured on the first play() call.
    private static Context appContextRef;

    static void play(Context context, String url) {
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "play_media with empty url; ignoring");
            return;
        }
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            appContextRef = app;
            stopInternal(app);
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                // Apply current media-volume pref as a software gain. Default 1.0 if pref
                // hasn't been set yet -- the user has to opt INTO quieter playback.
                float vol = readMediaVolume(app);
                mp.setVolume(vol, vol);

                MediaPlaybackEvents events = new MediaPlaybackEvents();
                mp.setOnPreparedListener(events);
                mp.setOnCompletionListener(events);
                mp.setOnErrorListener(events);
                mp.setDataSource(url);
                mp.prepareAsync();
                current = mp;
                pegStreamAlarm(app);
                Log.i(TAG, "play_media starting: " + url + " (software gain " + vol + ")");
            } catch (Exception ex) {
                Log.w(TAG, "play_media setup failed: " + ex.getMessage());
                try { mp.release(); } catch (Exception ignore) {}
                current = null;
            }
        }
    }

    static void stop(Context context) {
        synchronized (LOCK) {
            stopInternal(context.getApplicationContext());
        }
    }

    /// Called by MediaPlaybackEvents.onCompletion / onError; package-private. Uses the
    /// cached application context (captured by play()) so the events class doesn't need
    /// to thread a Context through.
    static void releaseFromCallback(MediaPlayer mp) {
        synchronized (LOCK) {
            if (current == mp) {
                try { mp.release(); } catch (Exception ignore) {}
                current = null;
                if (appContextRef != null) restoreStreamAlarm(appContextRef);
                Log.i(TAG, "play_media released");
            }
        }
    }

    /// Live-applies a new media volume to the currently-playing track. Called from the
    /// set_volume kiosk_command path. No-op if nothing is playing -- the pref is still
    /// persisted, and the next play_media will pick it up.
    static void applyVolumeToCurrent(float volume) {
        synchronized (LOCK) {
            if (current == null) return;
            try {
                current.setVolume(volume, volume);
                Log.i(TAG, "media volume applied live: " + volume);
            } catch (Exception ex) {
                Log.w(TAG, "couldn't apply volume live: " + ex.getMessage());
            }
        }
    }

    private static void stopInternal(Context app) {
        if (current == null) return;
        try { current.stop(); } catch (Exception ignore) {}
        try { current.release(); } catch (Exception ignore) {}
        current = null;
        restoreStreamAlarm(app);
        Log.i(TAG, "play_media stopped");
    }

    /// Stores STREAM_ALARM's current index and pegs it to hardware max so MediaPlayer's
    /// per-sample software gain has the full dynamic range to work against. Idempotent:
    /// if we already saved (i.e. another play_media is starting before the previous one
    /// stopped), we don't overwrite the original saved value with the already-max value.
    private static void pegStreamAlarm(Context app) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (savedAlarmVolume < 0) {
                savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            Log.i(TAG, "STREAM_ALARM pegged to " + max + " (saved " + savedAlarmVolume + ")");
        } catch (Exception ignore) {}
    }

    private static void restoreStreamAlarm(Context app) {
        if (savedAlarmVolume < 0) return;
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
            } catch (Exception ignore) {}
        }
        savedAlarmVolume = -1;
    }

    private static float readMediaVolume(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float v = prefs.getFloat(KEY_MEDIA_VOLUME, 1.0f);
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;
        return v;
    }
}
