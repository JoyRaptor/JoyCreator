package com.fadcam.ui.lobby;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;

/**
 * WHAT THE LOBBY HERO SHOWS WHEN IT HAS NO PICTURE.
 *
 * <p>A project made from a title card, a clip whose frame will not decode, the Capture room (its
 * hero is readiness, not footage), a sheet with no thumbnail: each of these used to leave the hero
 * as a flat near-black rectangle, which reads as a failure on the front door. This is the designed
 * stand-in, drawn inside the same rectangle the picture would have filled.</p>
 *
 * <p>Three layers, and only the first is opaque:</p>
 * <ol>
 *   <li><b>Old black.</b> {@link Studio#GROUND}, fully opaque, so nothing behind the lobby can
 *       ever show through the hero (this drawable reports {@link PixelFormat#OPAQUE}).</li>
 *   <li><b>A soft radial wash in the room's own colours.</b> The room's gradient start at 10%
 *       fading through its end stop at 5% to nothing. Colour guides; it does not shout.</li>
 *   <li><b>A large line glyph for the room</b>, centred and faint, in zinc grey. It is the icon
 *       font's glyph drawn as an outline (stroke, not fill), so it is a quiet drawing rather
 *       than a button. The glyph name is the room's own chip glyph, so the hero and the chip
 *       agree.</li>
 * </ol>
 *
 * <p>No JoyBot face and no recorder art on purpose: this is the Lobby's ground, not a preview.</p>
 *
 * <p>It is a plain {@link Drawable} so it can be the hero {@code ImageView}'s image: a real
 * thumbnail simply replaces it when one arrives, and when none ever does it stays.</p>
 */
public final class LobbyHeroPlaceholder extends Drawable {

    /** Wash strength at the centre, from the owner's 8-12% band. */
    private static final int WASH_A = 0x38;   // ~22%
    private static final int WASH_B = 0x1C;   // ~11%
    /** Glyph outline strength: zinc ink, faint. */
    private static final int GLYPH_ALPHA = 0x80;

    private final int gradA, gradB;
    private final String glyph;
    private final float strokePx;

    private final Paint ground = new Paint();
    private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBox = new Rect();

    private float glyphX, glyphY;

    /**
     * @param gradA    the room's gradient start (the lobby's {@code Room.gradA})
     * @param gradB    the room's gradient end
     * @param glyph    icon-font ligature name for the room ({@code Room.glyph}); null draws no glyph
     * @param iconFont the Material icon font the lobby chips use
     * @param density  display density, so the outline is 1.5dp
     */
    public LobbyHeroPlaceholder(int gradA, int gradB, @Nullable String glyph,
                                @Nullable Typeface iconFont, float density) {
        this.gradA = gradA;
        this.gradB = gradB;
        this.glyph = glyph;
        this.strokePx = 1.5f * density;

        ground.setColor(Studio.GROUND);
        ground.setStyle(Paint.Style.FILL);

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(strokePx);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(Studio.alpha(Studio.INK_FAINT, GLYPH_ALPHA));
        line.setTextAlign(Paint.Align.LEFT);
        if (iconFont != null) line.setTypeface(iconFont);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        float w = b.width(), h = b.height();
        if (w <= 0 || h <= 0) return;

        // The wash sits a little above centre, where the eye rests when the tag is top-left and
        // the name sits below the hero. Wide enough to reach the edges without a visible rim.
        float cx = b.left + w * 0.5f, cy = b.top + h * 0.46f;
        float radius = Math.max(w * 0.6f, h);
        wash.setShader(new RadialGradient(cx, cy, radius,
                new int[]{ Studio.alpha(gradA, WASH_A), Studio.alpha(gradB, WASH_B), Studio.alpha(gradB, 0) },
                new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));

        if (glyph != null) {
            // "Large": about 60% of the hero's height, never wider than a third of it.
            float size = Math.min(h * 0.6f, w * 0.34f);
            line.setTextSize(size);
            line.getTextBounds(glyph, 0, glyph.length(), textBox);
            glyphX = cx - textBox.exactCenterX();
            glyphY = cy - textBox.exactCenterY();
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        canvas.drawRect(b, ground);
        if (wash.getShader() != null) canvas.drawRect(b, wash);
        if (glyph != null) canvas.drawText(glyph, glyphX, glyphY, line);
    }

    @Override public void setAlpha(int alpha) { /* fixed look */ }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { /* fixed look */ }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
