package com.thinkview.kiosk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;

import xyz.gianlu.librespot.audio.MetadataWrapper;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.player.Player;

/**
 * Bridges librespot Player events into SpotifyConnectService state. Top-level class to
 * dodge d8's anonymous-inner-class shape issues (we've eaten that bug enough times in this
 * codebase to make a habit of avoiding it).
 *
 * Most events are no-ops -- we only care about the slice that drives the now-playing footer
 * UI: track changes, pause/resume, volume changes, and session lifecycle.
 */
class SpotifyPlayerEventsListener implements Player.EventsListener {
    private final SpotifyConnectService service;

    SpotifyPlayerEventsListener(SpotifyConnectService service) {
        this.service = service;
    }

    @Override
    public void onTrackChanged(@NotNull Player player, @NotNull PlayableId id,
                               @Nullable MetadataWrapper metadata, boolean userInitiated) {
        if (metadata != null) service.onSpotifyMetadata(metadata);
    }

    @Override
    public void onMetadataAvailable(@NotNull Player player, @NotNull MetadataWrapper metadata) {
        service.onSpotifyMetadata(metadata);
    }

    @Override
    public void onPlaybackPaused(@NotNull Player player, long trackTime) {
        service.onSpotifyPlaybackPaused(true);
    }

    @Override
    public void onPlaybackResumed(@NotNull Player player, long trackTime) {
        service.onSpotifyPlaybackPaused(false);
    }

    @Override
    public void onPlaybackEnded(@NotNull Player player) {
        service.onSpotifyPlaybackEnded();
    }

    @Override
    public void onInactiveSession(@NotNull Player player, boolean timeout) {
        service.onSpotifyPlaybackEnded();
    }

    @Override
    public void onVolumeChanged(@NotNull Player player,
                                @Range(from = 0, to = 1) float volume) {
        service.onSpotifyVolume(volume);
    }

    // No-ops -- not surfaced in the footer.
    @Override public void onContextChanged(@NotNull Player player, @NotNull String newUri) {}
    @Override public void onPlaybackFailed(@NotNull Player player, @NotNull Exception e) {}
    @Override public void onTrackSeeked(@NotNull Player player, long trackTime) {}
    @Override public void onPlaybackHaltStateChanged(@NotNull Player player, boolean halted, long trackTime) {}
    @Override public void onPanicState(@NotNull Player player) {}
    @Override public void onStartedLoading(@NotNull Player player) {}
    @Override public void onFinishedLoading(@NotNull Player player) {}
}
