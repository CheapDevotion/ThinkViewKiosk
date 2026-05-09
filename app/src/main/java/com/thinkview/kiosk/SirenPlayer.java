package com.thinkview.kiosk;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Generates a continuous swept siren wail via AudioTrack on STREAM_ALARM. No audio asset
 * required -- the tone is synthesized in code, which dodges the need to bundle a license-clean
 * sound file and guarantees the siren plays even when the device's notification sounds are
 * silent.
 *
 * The wail sweeps between 600 Hz and 1200 Hz at 0.5 Hz (one full sweep per 2 seconds) -- that
 * "wee-ooo wee-ooo" pattern that's hard to ignore.
 *
 * STREAM_ALARM bypasses ringer/media volume controls -- the siren plays even if the device is
 * silenced.
 */
class SirenPlayer {
    private static final String TAG = "ThinkViewKiosk/Alarm";
    private static final int SAMPLE_RATE = 44100;

    private AudioTrack track;
    private Thread feedThread;
    private volatile boolean playing = false;

    void start() {
        if (playing) return;
        try {
            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.setVolume(1.0f);
            track.play();
            playing = true;
            feedThread = new Thread(new SirenFeed(this), "siren-feed");
            feedThread.setDaemon(true);
            feedThread.start();
            Log.i(TAG, "siren started");
        } catch (Exception ex) {
            Log.w(TAG, "siren start failed: " + ex.getMessage(), ex);
            playing = false;
        }
    }

    void stop() {
        playing = false;
        if (feedThread != null) {
            try { feedThread.join(500); } catch (InterruptedException ignore) {}
            feedThread = null;
        }
        if (track != null) {
            try { track.stop(); } catch (Exception ignore) {}
            try { track.release(); } catch (Exception ignore) {}
            track = null;
        }
        Log.i(TAG, "siren stopped");
    }

    boolean isPlaying() { return playing; }

    /// Called from SirenFeed -- writes the next chunk of synthesized samples and returns
    /// whether playback should continue.
    boolean writeChunk(short[] buf, long startSample) {
        if (!playing || track == null) return false;
        synthesize(buf, startSample);
        try {
            int written = track.write(buf, 0, buf.length);
            return written >= 0 && playing;
        } catch (Exception ex) {
            Log.w(TAG, "siren write failed: " + ex.getMessage());
            return false;
        }
    }

    /// Fills {@code buf} with siren samples starting at sample index {@code startSample}. The
    /// frequency wails between 600 Hz and 1200 Hz with a 2-second period.
    private static void synthesize(short[] buf, long startSample) {
        // We track the carrier-wave phase across calls implicitly via the absolute sample index.
        // Phase = integral of 2*pi*f(t) dt. With f(t) = 900 + 300*sin(2*pi*0.5*t), the integral
        // is 900t - (300/(2*pi*0.5)) * cos(2*pi*0.5*t) + C. We compute that closed-form per
        // sample to avoid drift across buffer boundaries.
        double sweepHz = 0.5;
        double centerHz = 900.0;
        double swingHz = 300.0;
        double cosCoeff = swingHz / (2 * Math.PI * sweepHz); // amplitude of the cos term
        for (int i = 0; i < buf.length; i++) {
            double t = (startSample + i) / (double) SAMPLE_RATE;
            double phase = 2 * Math.PI * (centerHz * t - cosCoeff * Math.cos(2 * Math.PI * sweepHz * t));
            buf[i] = (short) (Math.sin(phase) * 28000);
        }
    }
}
