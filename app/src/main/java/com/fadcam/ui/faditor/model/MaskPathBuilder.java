package com.fadcam.ui.faditor.model;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE mask-geometry authority for {@link CompositingSpec#masks} — preview and
 * export both clip through {@link #buildVisiblePath}; no other code may turn
 * mask shapes into pixels (single-authority rule). Kept out of
 * {@link CompositingSpec} so the model class stays android-free for the JVM
 * harness.
 *
 * <p>Construction: hole = union(additive shapes) − union(subtractive shapes);
 * visible = fullRect − hole, or just the hole region when
 * {@code invertMasks} (window mode). Built with {@link Path#op} (API 19+) —
 * NOT canvas Region.Op clipping, which lost non-INTERSECT modes in API 26.
 * clipPath is not antialiased; acceptable for v1 (soft-edged masks are a
 * recorded follow-up: render the mask to an ALPHA_8 bitmap and DST_OUT it).</p>
 */
public final class MaskPathBuilder {

    private MaskPathBuilder() {}

    /**
     * The VISIBLE region of an item under {@code spec}'s masks, in pixel space
     * {@code (0,0)-(w,h)} (the video content rect / export frame the shapes'
     * canvas-normalized coords scale onto). Null when the spec has no masks —
     * callers skip the clip entirely.
     */
    @Nullable
    public static Path buildVisiblePath(@Nullable CompositingSpec spec, float w, float h) {
        if (spec == null || !spec.hasMasks() || w <= 0 || h <= 0) return null;
        Path add = new Path();
        Path sub = new Path();
        for (CompositingSpec.MaskShape m : spec.masks) {
            Path shape = shapePath(m, w, h);
            if (m.subtract) sub.op(shape, Path.Op.UNION);
            else add.op(shape, Path.Op.UNION);
        }
        add.op(sub, Path.Op.DIFFERENCE); // the effective hole/window region
        if (spec.invertMasks) return add;
        Path visible = new Path();
        visible.addRect(0, 0, w, h, Path.Direction.CW);
        visible.op(add, Path.Op.DIFFERENCE);
        return visible;
    }

    /** Clip {@code canvas} to the spec's visible region; no-op without masks.
     *  Callers bracket with save/restore. */
    public static void clipCanvas(@NonNull Canvas canvas,
                                  @Nullable CompositingSpec spec, float w, float h) {
        Path visible = buildVisiblePath(spec, w, h);
        if (visible != null) canvas.clipPath(visible);
    }

    /** Preview variant: the canvas-normalized shapes scale onto the video
     *  CONTENT RECT (which is offset inside the preview layer). */
    public static void clipCanvas(@NonNull Canvas canvas,
                                  @Nullable CompositingSpec spec, @NonNull RectF contentRect) {
        Path visible = buildVisiblePath(spec, contentRect.width(), contentRect.height());
        if (visible != null) {
            visible.offset(contentRect.left, contentRect.top);
            canvas.clipPath(visible);
        }
    }

    @NonNull
    private static Path shapePath(@NonNull CompositingSpec.MaskShape m, float w, float h) {
        float sw = m.w * w, sh = m.h * h;
        float cx = m.cx * w, cy = m.cy * h;
        RectF r = new RectF(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f);
        float radius = m.corner * Math.min(sw, sh) / 2f;
        Path p = new Path();
        p.addRoundRect(r, radius, radius, Path.Direction.CW);
        if (m.rotationDeg != 0f) {
            Matrix rot = new Matrix();
            rot.setRotate(m.rotationDeg, cx, cy);
            p.transform(rot);
        }
        return p;
    }
}
