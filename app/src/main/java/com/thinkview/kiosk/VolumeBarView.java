package com.thinkview.kiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Slim horizontal bar showing the current Spotify Connect volume. Pure-code custom view --
 * Holo-themed ProgressBar would tint the orange we just spent v21+v23+v26 evicting from the
 * footer.
 *
 * Visual: a 25%-white track behind a 100%-white fill, both rounded ends, fill width
 * proportional to the 0.0-1.0 volume value. Drawn directly with Canvas.drawRoundRect.
 *
 * Visibility lifecycle is owned by SpotifyFooterView -- this view just renders whatever
 * volume value it's been told and tracks alpha for the fade animation.
 */
class VolumeBarView extends View {
    private final Paint trackPaint;
    private final Paint fillPaint;
    private float volume = 1.0f;

    VolumeBarView(Context context) {
        super(context);
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(Color.parseColor("#40FFFFFF")); // 25% white
        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(Color.WHITE);
    }

    /// Updates the bar's fill ratio. Clamps to [0, 1]. Called from SpotifyFooterView when
    /// the service reports a new volume.
    void setVolume(float v) {
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;
        if (Math.abs(v - volume) < 0.001f) return; // skip needless redraws
        this.volume = v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        float barH = Math.min(h * 0.5f, dp(8f));
        float top = (h - barH) / 2f;
        float radius = barH / 2f;
        canvas.drawRoundRect(0f, top, w, top + barH, radius, radius, trackPaint);
        float fillEnd = w * volume;
        if (fillEnd > 0f) {
            canvas.drawRoundRect(0f, top, fillEnd, top + barH, radius, radius, fillPaint);
        }
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
