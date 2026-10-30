package com.fadcam.vislab.room;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;

/**
 * What lies behind the look when the editor shrinks it (owner, 10-27): Studio's own workspace art
 * (the dark filmstrip doodles behind Studio's canvas), and over it a soft glow of the look's own
 * colours spilling out around the picture and down behind the see-through drawer, like a
 * television's ambient light.
 *
 * <p>The glow is a tiny copy of the live picture stretched wide, so it is soft on every Android
 * version without a blur, and costs one small read of the picture about twelve times a second,
 * only while the editor is open.</p>
 */
final class RoomBackdrop extends View {

    /** The glow's copy of the picture: this many pixels across (its height follows the picture). */
    private static final int GLOW_W = 12;
    /** About seven reads a second: the glow drifts with the look, it does not need every frame. */
    private static final long GLOW_EVERY_MS = 150;
    /** How strong the glow is at its brightest, near the picture: subtle (owner, 10-28). */
    private static final float GLOW_ALPHA = 0.3f;
    /**
     * Studio's canvas slot on a phone is about 1.19 times as tall as it is wide, and Studio fills
     * it with the art; drawing the art at the scale that gives makes the room's doodles the same
     * size as Studio's. Below that the art repeats.
     */
    private static final float STUDIO_SLOT_ASPECT = 1.19f;

    @Nullable private final Bitmap art;
    private final Matrix artMatrix = new Matrix();
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    @Nullable private BitmapShader artShader;
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix glowMatrix = new Matrix();
    private final Matrix maskMatrix = new Matrix();
    private final RectF picture = new RectF();
    private final RectF glowRect = new RectF();
    @Nullable private TextureView source;
    @Nullable private Bitmap glow;
    @Nullable private RadialGradient mask;
    private boolean glowing;
    private float strength;   // 0..1, eased in and out with the editor

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean more = glowing || strength > 0f;
            strength = glowing ? Math.min(1f, strength + 0.2f) : Math.max(0f, strength - 0.25f);
            capture();
            invalidate();
            if (more) postDelayed(this, GLOW_EVERY_MS);
        }
    };

    RoomBackdrop(@NonNull Context c) {
        super(c);
        // RGB_565, as Studio loads it: an opaque JPEG, half the memory.
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        art = BitmapFactory.decodeResource(getResources(), com.fadcam.R.drawable.studio_backdrop_graffiti, o);
    }

    /** The live picture the glow is taken from. */
    void setGlowSource(@Nullable TextureView v) {
        source = v;
    }

    /** Where the picture sits now, in this view's coordinates (it moves while the drawer slides). */
    void setPicture(float left, float top, float right, float bottom) {
        picture.set(left, top, right, bottom);
        invalidate();
    }

    /** Glow while the editor is open; fade it out when it closes. */
    void setGlowing(boolean on) {
        if (glowing == on) return;
        glowing = on;
        removeCallbacks(tick);
        post(tick);
    }

    private void capture() {
        TextureView v = source;
        if (v == null || !v.isAvailable() || !glowing) return;
        int gw = GLOW_W;
        int gh = Math.max(1, Math.round(GLOW_W * v.getHeight() / (float) Math.max(1, v.getWidth())));
        if (glow == null || glow.getWidth() != gw || glow.getHeight() != gh) {
            glow = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
        }
        v.getBitmap(glow);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(Studio.GROUND);
        Bitmap a = art;
        if (a != null && a.getWidth() > 0 && a.getHeight() > 0) {
            // Studio's scale (its slot filled), centred across, repeating down the screen.
            float s = Math.max(w / (float) a.getWidth(), w * STUDIO_SLOT_ASPECT / a.getHeight());
            if (artShader == null) {
                artShader = new BitmapShader(a, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                artPaint.setShader(artShader);
            }
            artMatrix.setScale(s, s);
            artMatrix.postTranslate((w - a.getWidth() * s) / 2f, 0f);
            artShader.setLocalMatrix(artMatrix);
            canvas.drawRect(0, 0, w, h, artPaint);
        }
        Bitmap g = glow;
        if (g == null || strength <= 0f || picture.isEmpty()) return;
        // The glow: the picture's own colours, stretched well past its edges and down behind the
        // drawer, fading to nothing toward the screen's edges (an elliptical falloff).
        float cx = picture.centerX(), cy = picture.centerY();
        float rx = Math.max(picture.width() * 0.9f, w * 0.75f);
        float ry = Math.max(picture.height() * 0.95f, h * 0.55f);
        glowRect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        glowMatrix.setScale(glowRect.width() / g.getWidth(), glowRect.height() / g.getHeight());
        glowMatrix.postTranslate(glowRect.left, glowRect.top);
        BitmapShader pic = new BitmapShader(g, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        pic.setLocalMatrix(glowMatrix);
        if (mask == null) {
            mask = new RadialGradient(0f, 0f, 1f, new int[]{0xFF000000, 0x99000000, 0x00000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        }
        maskMatrix.setScale(rx, ry);
        maskMatrix.postTranslate(cx, cy);
        mask.setLocalMatrix(maskMatrix);
        glowPaint.setShader(new ComposeShader(pic, mask, PorterDuff.Mode.DST_IN));
        glowPaint.setAlpha(Math.round(255 * GLOW_ALPHA * strength));
        canvas.drawRect(glowRect, glowPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
