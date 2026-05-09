package com.thinkview.kiosk;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private final TextView trackText;
    private final ImageButton btnPrev;
    private final ImageButton btnPlayPause;
    private final ImageButton btnNext;
    private final ImageButton btnVolDown;
    private final ImageButton btnVolUp;

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
