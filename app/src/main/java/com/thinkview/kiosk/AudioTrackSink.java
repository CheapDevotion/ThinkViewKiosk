package com.thinkview.kiosk;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat;
import xyz.gianlu.librespot.player.mixing.output.SinkException;
import xyz.gianlu.librespot.player.mixing.output.SinkOutput;

/**
 * librespot SinkOutput backed by Android's AudioTrack. Spotify pushes decoded PCM here in the
 * format declared by start(); we wire up an AudioTrack of matching shape and forward bytes via
 * write(). Volume + lifecycle methods proxy to AudioTrack.
 *
 * Instantiated reflectively by librespot's AudioSink when PlayerConfiguration.AudioOutput.CUSTOM
 * is set with outputClass = "com.thinkview.kiosk.AudioTrackSink". Must therefore have a
 * no-arg public constructor.
 */
public class AudioTrackSink implements SinkOutput {
    private static final String TAG = "ThinkViewKiosk/Audio";

    private AudioTrack track;
    private float currentVolume = 1.0f;

    public AudioTrackSink() {
        // librespot reflects via a no-arg constructor.
    }

    @Override
    public boolean start(@NotNull OutputAudioFormat format) throws SinkException {
        int sampleRate    = (int) format.getSampleRate();
        int channelConfig = format.getChannels() == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;
        int encoding      = format.getSampleSizeInBits() == 8
                ? AudioFormat.ENCODING_PCM_8BIT
                : AudioFormat.ENCODING_PCM_16BIT;

        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding);
        if (minBuf <= 0) {
            throw new SinkException("AudioTrack rejected format "
                    + sampleRate + "Hz/" + format.getChannels() + "ch/"
                    + format.getSampleSizeInBits() + "bit", null);
        }
        // Triple the minimum -- WiFi jitter on the kiosk can otherwise underrun and click.
        int bufferSize = minBuf * 3;

        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(encoding)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.setVolume(currentVolume);
            track.play();
            Log.i(TAG, "AudioTrack opened: " + sampleRate + "Hz "
                    + format.getChannels() + "ch " + format.getSampleSizeInBits() + "bit, buf="
                    + bufferSize);
            return true;
        } catch (Exception ex) {
            throw new SinkException("AudioTrack init failed", ex);
        }
    }

    @Override
    public void write(byte[] buffer, int offset, int len) {
        if (track == null) return;
        int written = track.write(buffer, offset, len);
        if (written < 0) {
            Log.w(TAG, "AudioTrack.write returned " + written + " (error)");
        }
    }

    @Override
    public boolean setVolume(float volume) {
        currentVolume = Math.max(0f, Math.min(1f, volume));
        if (track != null) track.setVolume(currentVolume);
        return true;
    }

    @Override
    public void drain() {
        // No-op -- AudioTrack.MODE_STREAM doesn't expose drain pre-API 24.
    }

    @Override
    public void flush() {
        if (track != null) {
            try { track.pause(); track.flush(); track.play(); } catch (Exception ignore) {}
        }
    }

    @Override
    public void stop() {
        if (track != null) {
            try { track.stop(); } catch (Exception ignore) {}
        }
    }

    @Override
    public void release() {
        if (track != null) {
            try { track.release(); } catch (Exception ignore) {}
            track = null;
        }
    }

    @Override
    public void close() {
        release();
    }
}
