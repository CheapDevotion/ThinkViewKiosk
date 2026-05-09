package com.thinkview.kiosk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    /// Spotify's brand green. Used as the footer's solid background to match the host
    /// service's identity. Material flatness: no gradient, no transparency.
    private static final int SPOTIFY_GREEN = Color.parseColor("#1DB954");

    // Unicode media glyphs render as flat monochrome shapes from Android's system font, so
    // we don't need PNG/vector drawable assets for the controls. Compared to the system
    // android.R.drawable.ic_media_* set, these have no built-in shadow/emboss -- which was
    // the "glowy" effect that looked out of place against the green background.
    private static final String GLYPH_PREV  = "⏮"; // ⏮ skip-back
    private static final String GLYPH_PLAY  = "▶"; // ▶ play
    private static final String GLYPH_PAUSE = "⏸"; // ⏸ pause
    private static final String GLYPH_NEXT  = "⏭"; // ⏭ skip-forward
    private static final String GLYPH_VOL_DOWN = "−"; // − minus
    private static final String GLYPH_VOL_UP   = "+";

    private final ImageView artworkImage;
    private final TextView trackText;
    private final TextView btnPrev;
    private final TextView btnPlayPause;
    private final TextView btnNext;
    private final TextView btnVolDown;
    private final TextView btnVolUp;

    /// URL the artwork ImageView is currently displaying (or fetching). Used by
    /// SpotifyArtworkApply to decide whether a just-completed fetch should still be applied
    /// or quietly discarded because the user has already skipped to a different track.
    private String currentArtworkUrl;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public SpotifyFooterView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // Solid Spotify Green -- branded, no transparency, Material flatness.
        setBackgroundColor(SPOTIFY_GREEN);
        int padH = dp(16);
        int padV = dp(8);
        setPadding(padH, padV, padH, padV);

        // Album art on the left. Slightly darker green placeholder so it disappears against
        // the footer until artwork lands; keeps the layout stable without drawing attention
        // to the empty slot.
        artworkImage = new ImageView(context);
        artworkImage.setBackgroundColor(Color.parseColor("#159443"));
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

        btnPrev      = makeButton(context, GLYPH_PREV);
        btnPlayPause = makeButton(context, GLYPH_PAUSE);
        btnNext      = makeButton(context, GLYPH_NEXT);
        btnVolDown   = makeButton(context, GLYPH_VOL_DOWN);
        btnVolUp     = makeButton(context, GLYPH_VOL_UP);

        addView(btnPrev);
        addView(btnPlayPause);
        addView(btnNext);
        // Small gap separating transport controls from volume.
        addView(spacer(context, dp(8)));
        addView(btnVolDown);
        addView(btnVolUp);
    }

    /// Builds a flat text-glyph button. We dodge ImageButton with system ic_media_* drawables
    /// because those have shadow/emboss baked into the bitmaps -- white-tinted, they read as
    /// glowy against the green background. Pure TextView with a Unicode glyph stays flat.
    ///
    /// Press feedback: we hand-build a StateListDrawable rather than using the theme's
    /// selectableItemBackgroundBorderless. The kiosk activity uses Theme.NoTitleBar.Fullscreen
    /// (pre-Material) and on this device's Lenovo skin that attribute resolves to an
    /// orange-tinted Holo focus ring, which (a) clashes with the Spotify Green and
    /// (b) sticks on whichever button was last tapped because Holo focus is sticky in touch
    /// mode. Manual StateListDrawable + explicit focus disabling sidesteps both problems.
    private TextView makeButton(Context context, String glyph) {
        TextView b = new TextView(context);
        b.setText(glyph);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);

        // 25% white overlay only while finger is down -- gives a subtle Material-style press
        // confirmation that goes away the instant you lift, no orange Holo focus ring.
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(Color.parseColor("#40FFFFFF")));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        b.setBackground(bg);

        b.setClickable(true);
        // Explicitly NOT focusable. This is a touch-only kiosk, so D-pad focus is moot, and
        // Holo's focused state (which is what was painting the persistent orange background
        // on the pause button) doesn't apply if the button can't receive focus.
        b.setFocusable(false);
        b.setFocusableInTouchMode(false);

        b.setOnClickListener(this);
        int size = dp(48);
        LayoutParams lp = new LayoutParams(size, size);
        lp.setMarginStart(dp(2));
        lp.setMarginEnd(dp(2));
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

    /// Flips the play/pause button between the play and pause glyphs.
    public void setPaused(boolean paused) {
        btnPlayPause.setText(paused ? GLYPH_PLAY : GLYPH_PAUSE);
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
