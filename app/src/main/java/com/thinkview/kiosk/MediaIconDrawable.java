package com.thinkview.kiosk;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Programmatic flat media-control icons. Replaces the Unicode glyph approach (U+23F8 etc.)
 * which renders as orange/yellow color-emoji on Android 8.1 because the Lenovo CD-18781Y's
 * font fallback chain picks NotoColorEmoji for those codepoints. The variation selector
 * U+FE0E ("text presentation") is supposed to force monochrome rendering but isn't honored
 * consistently across Android 8.1 builds, so drawing the shapes ourselves is the only fully
 * reliable path.
 *
 * Six shapes:
 *   PLAY   - rightward filled triangle
 *   PAUSE  - two filled rectangles
 *   PREV   - leftward double-triangle (skip-back)
 *   NEXT   - rightward double-triangle (skip-forward)
 *   MINUS  - thick horizontal bar (volume down)
 *   PLUS   - thick plus sign (volume up)
 *
 * Each shape is drawn proportional to bounds with ~20% padding so the icon doesn't crowd
 * the touch target.
 */
class MediaIconDrawable extends Drawable {

    enum Shape { PLAY, PAUSE, PREV, NEXT, MINUS, PLUS }

    private final Shape shape;
    private final Paint paint;

    MediaIconDrawable(Shape shape) {
        this.shape = shape;
        this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.paint.setColor(Color.WHITE);
        this.paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float pad = Math.min(b.width(), b.height()) * 0.22f;
        float left   = b.left + pad;
        float right  = b.right - pad;
        float top    = b.top + pad;
        float bottom = b.bottom - pad;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();

        switch (shape) {
            case PLAY: {
                Path p = new Path();
                p.moveTo(left, top);
                p.lineTo(right, cy);
                p.lineTo(left, bottom);
                p.close();
                canvas.drawPath(p, paint);
                break;
            }
            case PAUSE: {
                float gap = (right - left) * 0.20f;
                float barW = ((right - left) - gap) / 2f;
                canvas.drawRect(left, top, left + barW, bottom, paint);
                canvas.drawRect(right - barW, top, right, bottom, paint);
                break;
            }
            case PREV:
            case NEXT: {
                // Two stacked triangles + a side bar. PREV points left, NEXT points right.
                boolean rightward = shape == Shape.NEXT;
                float w = right - left;
                float barW = w * 0.12f;
                float midX = rightward ? right - barW : left + barW;
                Path tri = new Path();
                if (rightward) {
                    // Two right-pointing triangles touching at midX, then a bar at the right.
                    tri.moveTo(left, top);
                    tri.lineTo(midX, cy);
                    tri.lineTo(left, bottom);
                    tri.close();
                    tri.moveTo(midX - (midX - left), top); // mirror starting position
                    // Actually simpler: draw the two-triangle skip glyph as one path
                    tri.reset();
                    float halfX = (left + midX) / 2f;
                    // Left half-triangle
                    tri.moveTo(left, top);
                    tri.lineTo(halfX, cy);
                    tri.lineTo(left, bottom);
                    tri.close();
                    // Right half-triangle (touches halfX)
                    tri.moveTo(halfX, top);
                    tri.lineTo(midX, cy);
                    tri.lineTo(halfX, bottom);
                    tri.close();
                    canvas.drawPath(tri, paint);
                    // Vertical bar on the right
                    canvas.drawRect(right - barW, top, right, bottom, paint);
                } else {
                    // Mirror image: bar on the left, two left-pointing triangles to the right.
                    canvas.drawRect(left, top, left + barW, bottom, paint);
                    float startX = left + barW;
                    float halfX  = (startX + right) / 2f;
                    tri.moveTo(halfX, top);
                    tri.lineTo(startX, cy);
                    tri.lineTo(halfX, bottom);
                    tri.close();
                    tri.moveTo(right, top);
                    tri.lineTo(halfX, cy);
                    tri.lineTo(right, bottom);
                    tri.close();
                    canvas.drawPath(tri, paint);
                }
                break;
            }
            case MINUS: {
                float thickness = (bottom - top) * 0.20f;
                canvas.drawRect(left, cy - thickness / 2f, right, cy + thickness / 2f, paint);
                break;
            }
            case PLUS: {
                float thickness = Math.min(right - left, bottom - top) * 0.20f;
                // Horizontal bar
                canvas.drawRect(left, cy - thickness / 2f, right, cy + thickness / 2f, paint);
                // Vertical bar
                canvas.drawRect(cx - thickness / 2f, top, cx + thickness / 2f, bottom, paint);
                break;
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
