package com.fadcam.ui.faditor.model;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
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
 * clipPath is not antialiased, which is why {@link CompositingSpec#maskFeather}
 * cannot ride on it: the soft path renders the ERASE region into an ALPHA_8
 * bitmap through a {@code BlurMaskFilter} and {@code DST_OUT}s it out of an
 * offscreen layer ({@link #beginMask}/{@link #endMask}).</p>
 *
 * <p><b>Why the blur happens in a Bitmap.</b> {@code BlurMaskFilter} is IGNORED
 * on a hardware-accelerated canvas — the preview's canvas is exactly that, so
 * blurring the mask path directly would soften the export and do nothing on
 * screen, i.e. the two renderers would disagree while both looked like they
 * worked. Drawing into a {@code Bitmap} is software by definition, so the same
 * blur happens in both paths. (This is the same trap recorded on
 * {@code Transform#blurPx} for GHOST; here it is avoidable because the thing
 * being blurred is a static mask, not every video frame.)</p>
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

    // clipCanvas (both overloads) was deleted when beginMask/endMask replaced it: a clip
    // cannot express a soft edge, and leaving a second way to apply a mask is how the two
    // renderers drift apart. Every caller went through it, so there is nothing left to keep.

    // ── Soft edges (feather) ──────────────────────────────────────────────

    /**
     * The region the mask stack ERASES, in pixel space — the exact complement of
     * {@link #buildVisiblePath}. Null when the spec has no masks. This is what gets
     * blurred: softening the thing being removed is what turns a cut edge into a
     * gradient, and it keeps the untouched interior bit-identical to the hard case.
     */
    @Nullable
    public static Path buildErasePath(@Nullable CompositingSpec spec, float w, float h) {
        Path visible = buildVisiblePath(spec, w, h);
        if (visible == null) return null;
        Path erase = new Path();
        erase.addRect(0, 0, w, h, Path.Direction.CW);
        erase.op(visible, Path.Op.DIFFERENCE);
        return erase;
    }

    /**
     * A live {@link #beginMask} bracket. Deliberately a type rather than the bare save
     * count it wraps: the feathered path opens TWO canvas states, an outer layer and an
     * inner one holding the caller's own transforms, and the erase pass has to happen
     * between them. With a bare int the first version of this code drew the soft edge
     * through the PiP's rotation — the mask is authored against the frame and must never
     * move with the item, which is the whole reason both renderers mask before rotating.
     */
    public static final class MaskScope {
        private final int outer;        // canvas state to return to when the bracket closes
        private final int content;      // state holding the caller's transforms, or -1
        /** What beginMask actually clipped with — the RESOLVED spec for a time-aware bracket. */
        @Nullable private CompositingSpec resolved;
        private float w, h, dx, dy;
        private MaskScope(int outer, int content) { this.outer = outer; this.content = content; }
    }

    /**
     * Open a masked drawing bracket. Draw, then pass the returned scope to {@link #endMask}.
     * Replaces {@link #clipCanvas}, which cannot express a soft edge.
     *
     * <p>Three paths, and only the third costs anything:</p>
     * <ul>
     *   <li>no masks → a plain {@code save()}; the caller's drawing is untouched;</li>
     *   <li>masks, {@code maskFeather == 0} → {@code save()} + {@code clipPath}, i.e.
     *       exactly what shipped, which is every existing project;</li>
     *   <li>masks + feather → {@code saveLayer}, so the caller's pixels land offscreen
     *       and {@link #endMask} can erase a soft edge out of them.</li>
     * </ul>
     *
     * @param dx offset of the mask's coordinate space inside the canvas — 0 for the export
     *           frame, the content rect's left for the preview
     * @param dy the same, vertically
     */
    /**
     * Resolve a spec's mask geometry for {@code timelineMs} — animated parameters and the object
     * link — and hand back what the geometry methods below should be given.
     *
     * <p><b>Both renderers are FORCED through this</b> by the {@code timelineMs} overloads of
     * {@link #beginMask} / {@link #endMask}: there is no way to obtain mask geometry at a time
     * without resolving it at that time, so preview and export cannot animate a mask differently
     * — the parity is structural rather than a rule someone has to remember. Same discipline as
     * {@code ChromaKey.GLSL_KEY_FN} being compiled by both shaders.</p>
     */
    @Nullable
    public static CompositingSpec resolve(@Nullable CompositingSpec spec,
                                          @Nullable com.fadcam.ui.faditor.keyframe.KeyframeSet objectKf,
                                          long timelineMs, float w, float h) {
        return MaskAnimator.resolve(spec, objectKf, timelineMs, w, h);
    }

    /**
     * Time-aware {@link #beginMask}: resolves animated / linked mask geometry at
     * {@code timelineMs} first. The returned scope carries the RESOLVED spec, so
     * {@link #endMask(Canvas, MaskScope)} softens exactly the shape that was clipped — passing
     * the authored spec to the close of the bracket would feather a different shape than the one
     * drawn, which reads as the soft edge sliding off the mask as it animates.
     */
    @NonNull
    public static MaskScope beginMask(@NonNull Canvas canvas, @Nullable CompositingSpec spec,
                                      @Nullable com.fadcam.ui.faditor.keyframe.KeyframeSet objectKf,
                                      long timelineMs,
                                      float w, float h, float dx, float dy) {
        CompositingSpec resolved = resolve(spec, objectKf, timelineMs, w, h);
        MaskScope scope = beginMask(canvas, resolved, w, h, dx, dy);
        scope.resolved = resolved;
        scope.w = w; scope.h = h; scope.dx = dx; scope.dy = dy;
        return scope;
    }

    /** Close a bracket opened by the time-aware {@link #beginMask}. */
    public static void endMask(@NonNull Canvas canvas, @NonNull MaskScope scope) {
        endMask(canvas, scope.resolved, scope.w, scope.h, scope.dx, scope.dy, scope);
    }

    @NonNull
    public static MaskScope beginMask(@NonNull Canvas canvas, @Nullable CompositingSpec spec,
                                      float w, float h, float dx, float dy) {
        if (spec == null || !spec.hasMasks() || w <= 0 || h <= 0) {
            return new MaskScope(canvas.save(), -1);
        }
        if (!spec.hasFeather()) {
            int save = canvas.save();
            Path visible = buildVisiblePath(spec, w, h);
            if (visible != null) {
                if (dx != 0f || dy != 0f) visible.offset(dx, dy);
                canvas.clipPath(visible);
            }
            return new MaskScope(save, -1);
        }
        // The layer must cover the mask's whole space, not just the drawn item: the erase
        // pass in endMask writes across all of it.
        int outer = canvas.saveLayer(dx, dy, dx + w, dy + h, null);
        int content = canvas.save();
        return new MaskScope(outer, content);
    }

    /** Close a {@link #beginMask} bracket, softening the edge if the spec asked for one. */
    public static void endMask(@NonNull Canvas canvas, @Nullable CompositingSpec spec,
                               float w, float h, float dx, float dy, @NonNull MaskScope scope) {
        if (scope.content >= 0) {
            // Drop the caller's transforms first: the erase bitmap is in mask space.
            canvas.restoreToCount(scope.content);
            Bitmap erase = (spec != null && w > 0 && h > 0) ? featherBitmap(spec, w, h) : null;
            if (erase != null) {
                Paint p = new Paint();
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                canvas.drawBitmap(erase, dx, dy, p);
            }
        }
        canvas.restoreToCount(scope.outer);
    }

    // One-entry cache. The mask is static geometry while the frames under it are not, so
    // without this the export would rebuild and re-blur the same bitmap on EVERY frame.
    // One entry is enough because a frame belongs to one clip: alternating specs would
    // thrash, which is why the signature is checked rather than assumed.
    private static final Object featherLock = new Object();
    @Nullable private static String featherKey;
    @Nullable private static Bitmap featherCache;

    @Nullable
    private static Bitmap featherBitmap(@NonNull CompositingSpec spec, float w, float h) {
        int bw = Math.max(1, Math.round(w));
        int bh = Math.max(1, Math.round(h));
        String key = signature(spec) + '|' + bw + 'x' + bh;
        synchronized (featherLock) {
            if (key.equals(featherKey) && featherCache != null && !featherCache.isRecycled()) {
                return featherCache;
            }
            Path erase = buildErasePath(spec, w, h);
            if (erase == null) return null;
            float radius = CompositingSpec.featherRadiusPx(spec.maskFeather, w, h);
            Bitmap bmp;
            try {
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
            } catch (OutOfMemoryError e) {
                return null;   // a soft edge is never worth taking the export down
            }
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // Software canvas — this is the whole reason the blur lives in a Bitmap.
            if (radius > 0f) {
                paint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
            }
            new Canvas(bmp).drawPath(erase, paint);
            featherKey = key;
            featherCache = bmp;
            return bmp;
        }
    }

    /** Everything that changes the erase bitmap, and nothing that does not. */
    @NonNull
    private static String signature(@NonNull CompositingSpec spec) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(spec.invertMasks ? 'I' : 'n').append(spec.maskFeather);
        for (CompositingSpec.MaskShape m : spec.masks) {
            sb.append(';').append(m.cx).append(',').append(m.cy).append(',')
              .append(m.w).append(',').append(m.h).append(',')
              .append(m.corner).append(',').append(m.rotationDeg)
              .append(m.subtract ? 's' : 'a');
        }
        return sb.toString();
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
