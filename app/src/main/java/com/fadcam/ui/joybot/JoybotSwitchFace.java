package com.fadcam.ui.joybot;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.LruCache;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;

/**
 * Joybot inside the switch's porthole: the two registered frames ({@code joybot_neutral},
 * {@code joybot_smile}) drawn as ALPHA_8 masks in one tint.
 *
 * <h3>Why masks, pre-shrunk</h3>
 * The art is a 360x564 white silhouette with the features cut out of it. In a 23dp porthole he
 * is about 45px tall, an 8x shrink, and a GPU drawing an 8x shrink with plain bilinear sampling
 * skips most of the source pixels: his ring eyes break up and shimmer as he moves. So each frame
 * is halved step by step to the exact size once (every halving is a true 2x2 average) and kept
 * in a small shared cache: a settings page of twenty switches holds two ~3KB masks, not forty
 * full-size bitmaps. An ALPHA_8 mask also takes its colour straight from the paint, so tinting
 * him from dim grey to cyan allocates nothing per frame.
 *
 * <h3>Why a View of its own</h3>
 * The idle bob and the smile dip move this view's translationY only, which the compositor
 * applies without re-recording anything. The tint and the face mix redraw it, but those change
 * only during a tap's 220ms.
 */
final class JoybotSwitchFace extends View {

    // Joybot's BODY box on the shared canvas, as fractions (JoybotView's numbers). The smile's
    // sparks sit above it.
    static final float BODY_TOP = 0.2074f, BODY_BOT = 0.9663f;
    static final float BODY_L = 0.0417f, BODY_R = 0.9611f;
    /**
     * How much of the porthole his body fills. 0.74 keeps the smile's sparks inside the disc
     * once he has dipped, and inside it through an idle bob, which never rises past the
     * un-dipped height.
     */
    static final float FILL = 0.74f;

    private static final LruCache<String, Bitmap> MASKS = new LruCache<>(24);

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    @Nullable private Bitmap neutral, smile;
    private float offX, offY;
    private int tint;
    private float mix;          // 0 = neutral face, 1 = smile
    private float fade = 1f;    // disabled / reduced-motion press dim

    JoybotSwitchFace(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The porthole diameter in px; rebuilds the masks for that size. */
    void setDiameter(int d) {
        if (d <= 0) { neutral = smile = null; return; }
        Resources r = getResources();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        o.inScaled = false;
        BitmapFactory.decodeResource(r, R.drawable.joybot_neutral, o);
        if (o.outHeight <= 0) return;
        int bodyPx = Math.max(1, Math.round(d * FILL));
        int canvasH = Math.max(1, Math.round(bodyPx / (BODY_BOT - BODY_TOP)));
        neutral = mask(r, R.drawable.joybot_neutral, canvasH);
        smile = mask(r, R.drawable.joybot_smile, canvasH);
        if (neutral == null) return;
        float s = neutral.getHeight() / (float) o.outHeight;
        float bodyW = o.outWidth * (BODY_R - BODY_L) * s;
        float bodyH = o.outHeight * (BODY_BOT - BODY_TOP) * s;
        // Whole pixels: a mask drawn at a fractional offset is resampled, and soft.
        offX = Math.round((d - bodyW) / 2f - o.outWidth * BODY_L * s);
        offY = Math.round((d - bodyH) / 2f - o.outHeight * BODY_TOP * s);
        invalidate();
    }

    /** The height of his body in px, for motion measured in "his height" like JoybotView's. */
    float bodyHeight(int d) { return d * FILL; }

    void setLook(int tint, float mix, float fade) {
        if (tint == this.tint && mix == this.mix && fade == this.fade) return;
        this.tint = tint;
        this.mix = mix;
        this.fade = fade;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (neutral == null || smile == null) return;
        int a = (tint >>> 24);
        paint.setColor(tint);
        if (mix < 1f) {
            paint.setAlpha(Math.round(a * (1f - mix) * fade));
            canvas.drawBitmap(neutral, offX, offY, paint);
        }
        if (mix > 0f) {
            paint.setAlpha(Math.round(a * mix * fade));
            canvas.drawBitmap(smile, offX, offY, paint);
        }
    }

    @Nullable
    private static Bitmap mask(Resources r, int res, int targetH) {
        String key = res + "@" + targetH;
        Bitmap hit = MASKS.get(key);
        if (hit != null) return hit;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        Bitmap b;
        try {
            b = BitmapFactory.decodeResource(r, res, o);
        } catch (OutOfMemoryError e) {
            return null;
        }
        if (b == null) return null;
        while (b.getHeight() / 2 >= targetH && b.getWidth() / 2 > 0) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (half != b) b.recycle();
            b = half;
        }
        int w = Math.max(1, Math.round(b.getWidth() * targetH / (float) b.getHeight()));
        Bitmap exact = Bitmap.createScaledBitmap(b, w, targetH, true);
        if (exact != b) b.recycle();
        Bitmap alpha = exact.extractAlpha();
        if (alpha != exact) exact.recycle();
        MASKS.put(key, alpha);
        return alpha;
    }
}
