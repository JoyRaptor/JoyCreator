package com.fadcam.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * ONE FRAME OF THE FIRST-RUN SLIDESHOW.
 *
 * <p>A dark base with a single soft glow of one room's colour sitting off-centre. This is
 * what the mockup specifies, and the first build of this screen got it badly wrong by
 * painting a full-saturation corner-to-corner gradient instead — which turned the top of
 * the first screen a new user ever sees into a block of neon.
 *
 * <p>The difference is the whole character of the screen. A saturated field says "look at
 * the colour"; a dark field with a glow in it says "look at the words", and lets the colour
 * do what it is actually for here, which is to change underneath a line of text so the two
 * read as one statement. The glow tops out around 34% alpha over a near-black base, so even
 * at its brightest it stays a lighting effect rather than a surface.
 *
 * <p>From the mockup's CSS, per slide:
 * <pre>
 *   radial-gradient(64% 52% at 34% 30%, rgba(255,0,140,.34), transparent 72%),
 *   linear-gradient(160deg, #17171f, #101016)
 * </pre>
 */
public final class GlowSlide extends Drawable {

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int baseTop, baseBottom, glowColor;
    private final float atX, atY;

    /**
     * @param glowColor the room's colour, alpha included — the mockup uses .30–.34
     * @param baseTop   top of the dark backing
     * @param baseBottom bottom of the dark backing
     * @param atX       glow centre, as a fraction of width
     * @param atY       glow centre, as a fraction of height
     */
    public GlowSlide(int glowColor, int baseTop, int baseBottom, float atX, float atY) {
        this.glowColor = glowColor;
        this.baseTop = baseTop;
        this.baseBottom = baseBottom;
        this.atX = atX;
        this.atY = atY;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        if (b.width() <= 0 || b.height() <= 0) return;
        base.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                baseTop, baseBottom, Shader.TileMode.CLAMP));
        // Radius from the WIDTH, matching the CSS's 64%-of-width sizing. Deriving it from
        // the diagonal instead would make the glow grow and shrink with the aspect ratio of
        // whatever phone this lands on, so the same slide would look like a different
        // lighting setup on a tall device than on a short one.
        float r = b.width() * 0.64f;
        glow.setShader(new RadialGradient(
                b.left + b.width() * atX, b.top + b.height() * atY, r,
                new int[]{glowColor, glowColor & 0x00FFFFFF},
                new float[]{0f, 0.72f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        if (getBounds().width() <= 0) return;
        c.drawRect(getBounds(), base);
        c.drawRect(getBounds(), glow);
    }

    @Override public void setAlpha(int a) { base.setAlpha(a); glow.setAlpha(a); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
