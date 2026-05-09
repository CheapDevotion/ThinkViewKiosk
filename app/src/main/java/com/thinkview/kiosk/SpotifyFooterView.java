package com.thinkview.kiosk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Objects;

/**
 * Now-playing footer for Spotify Connect. A self-contained LinearLayout that owns its child
 * views and click handlers. Implementing OnClickListener directly on the class itself avoids
 * d8 anonymous-inner-class issues while keeping the click dispatch in one place.
 *
 * Visibility is driven externally (MainActivity calls setVisibility(View.GONE) when no
 * active Spotify session is up). Layout is pure code -- no XML resources to keep the
 * KioskApp's resource set as small as possible.
 */
public class SpotifyFooterView extends LinearLayout implements View.OnClickListener {
    private final ImageView artworkImage;
    private final TextView trackText;
    private final ImageButton btnPrev;
    private final ImageButton btnPlayPause;
    private final ImageButton btnNext;
    private final ImageButton btnVolDown;
    private final ImageButton btnVolUp;

    /// URL the artwork ImageView is currently displaying (or fetching). Used by
    /// SpotifyArtworkApply to decide whether a just-completed fetch should still be applied
    /// or quietly discarded because the user has already skipped to a different track.
    private String currentArtworkUrl;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public SpotifyFooterView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // 88% black so the dashboard underneath is faintly visible -- doubles as a hint that
        // the footer is overlaid rather than part of the page.
        setBackgroundColor(Color.parseColor("#E0000000"));
        int padH = dp(16);
        int padV = dp(8);
        setPadding(padH, padV, padH, padV);

        // Album art on the left. Dark gray placeholder before any image is fetched so the
        // footer doesn't reflow when artwork lands later.
        artworkImage = new ImageView(context);
        artworkImage.setBackgroundColor(Color.parseColor("#222222"));
        artworkImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int artSize = dp(48);
        LayoutParams artLp = new LayoutParams(artSize, artSize);
        artLp.setMarginEnd(dp(12));
        addView(artworkImage, artLp);

        trackText = new TextView(context);
        trackText.setTextColor(Color.WHITE);
        trackText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        trackText.setSingleLine(true);
        trackText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trackText.setText("");
        LayoutParams textLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(trackText, textLp);

        btnPrev      = makeButton(context, android.R.drawable.ic_media_previous);
        btnPlayPause = makeButton(context, android.R.drawable.ic_media_pause);
        btnNext      = makeButton(context, android.R.drawable.ic_media_next);
        btnVolDown   = makeButton(context, android.R.drawable.ic_lock_silent_mode);
        btnVolUp     = makeButton(context, android.R.drawable.ic_lock_silent_mode_off);

        addView(btnPrev);
        addView(btnPlayPause);
        addView(btnNext);
        // Small gap separating transport controls from volume.
        addView(spacer(context, dp(8)));
        addView(btnVolDown);
        addView(btnVolUp);
    }

    private ImageButton makeButton(Context context, int iconRes) {
        ImageButton b = new ImageButton(context);
        b.setImageResource(iconRes);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setColorFilter(Color.WHITE);
        b.setOnClickListener(this);
        int size = dp(48);
        LayoutParams lp = new LayoutParams(size, size);
        lp.setMarginStart(dp(4));
        lp.setMarginEnd(dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private View spacer(Context context, int width) {
        View v = new View(context);
        v.setLayoutParams(new LayoutParams(width, 1));
        return v;
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    /// Updates the track label. Called from the main thread.
    public void setTrackText(String title, String artist) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty()) sb.append(title);
        if (artist != null && !artist.isEmpty()) {
            if (sb.length() > 0) sb.append("  •  ");
            sb.append(artist);
        }
        trackText.setText(sb.toString());
    }

    /// Flips the play/pause button between the play and pause icons.
    public void setPaused(boolean paused) {
        btnPlayPause.setImageResource(paused
                ? android.R.drawable.ic_media_play
                : android.R.drawable.ic_media_pause);
    }

    /// Updates the cover art. If the URL hasn't changed, no-op. If it has, kicks off a
    /// background fetch; the result lands via applyArtworkIfMatch on the main thread.
    public void setArtworkUrl(String url) {
        if (Objects.equals(url, currentArtworkUrl)) return;
        currentArtworkUrl = url;
        if (url == null || url.isEmpty()) {
            artworkImage.setImageDrawable(null);
            return;
        }
        // Show the placeholder while the fetch is in flight so the previous track's art
        // doesn't sit on screen pretending to be the new one.
        artworkImage.setImageDrawable(null);
        Thread t = new Thread(new SpotifyArtworkFetcher(this, url, mainHandler), "spotify-art-fetch");
        t.setDaemon(true);
        t.start();
    }

    /// Called from SpotifyArtworkApply on the main thread when a fetch completes. Applies the
    /// bitmap only if the URL we fetched still matches what we're meant to show -- track
    /// changes that happened while the fetch was in flight win the race.
    void applyArtworkIfMatch(String forUrl, Bitmap bitmap) {
        if (!Objects.equals(forUrl, currentArtworkUrl)) return;
        artworkImage.setImageBitmap(bitmap);
    }

    @Override
    public void onClick(View v) {
        SpotifyConnectService svc = SpotifyConnectService.getInstance();
        if (svc == null) return;
        if (v == btnPrev)         svc.controlPrevious();
        else if (v == btnPlayPause) svc.controlPlayPause();
        else if (v == btnNext)      svc.controlNext();
        else if (v == btnVolDown)   svc.controlVolumeDown();
        else if (v == btnVolUp)     svc.controlVolumeUp();
    }
}
