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
        MaskFold.Fold fold = MaskFold.foldOps(spec);
        Path region = fold.sequential ? buildSequential(spec, fold, w, h)
                                      : buildTwoBucket(spec, w, h);
        if (spec.invertMasks) return region;
        Path visible = new Path();
        visible.addRect(0, 0, w, h, Path.Direction.CW);
        visible.op(region, Path.Op.DIFFERENCE);
        return visible;
    }

    /**
     * EXACTLY the code that shipped — union(add) − union(sub) — reached whenever no shape uses
     * INTERSECT. Untouched on purpose (spec §1.2): the gate is what guarantees BY CONSTRUCTION
     * that no existing project can take a different code path, which is what keeps the
     * feather-0 clip path byte-identical.
     */
    @NonNull
    private static Path buildTwoBucket(@NonNull CompositingSpec spec, float w, float h) {
        Path add = new Path();
        Path sub = new Path();
        for (CompositingSpec.MaskShape m : spec.masks) {
            Path shape = shapePath(m, w, h);
            if (m.isSubtract()) sub.op(shape, Path.Op.UNION);
            else add.op(shape, Path.Op.UNION);
        }
        add.op(sub, Path.Op.DIFFERENCE); // the effective hole/window region
        return add;
    }

    /**
     * The ordered fold, reached only when some shape INTERSECTS. Each shape folds onto the
     * accumulator in list order with its own op — the reading a boolean stack has everywhere
     * else, and the only one under which "intersect" means anything at all.
     *
     * <p>The FIRST shape always seeds the accumulator by union — {@link MaskFold#foldOps}
     * reports that in {@code ops[0]}, so it is pinned by the harness rather than buried here.</p>
     */
    @NonNull
    private static Path buildSequential(@NonNull CompositingSpec spec,
                                        @NonNull MaskFold.Fold fold, float w, float h) {
        Path acc = new Path();
        for (int i = 0; i < spec.masks.size(); i++) {
            acc.op(shapePath(spec.masks.get(i), w, h), pathOp(fold.ops[i]));
        }
        return acc;
    }

    /**
     * The op ordinals {@link MaskFold} speaks in, turned into the {@code Path.Op}s only this
     * file may use. This mapping is the ENTIRE reason the decision could be extracted: it is
     * the one line that needs android, and it holds no policy.
     */
    @NonNull
    private static Path.Op pathOp(int op) {
        switch (op) {
            case MaskFold.OP_DIFFERENCE: return Path.Op.DIFFERENCE;
            case MaskFold.OP_INTERSECT: return Path.Op.INTERSECT;
            case MaskFold.OP_UNION:
            default: return Path.Op.UNION;
        }
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

    /**
     * FOUR-entry LRU, not the one entry that shipped (spec §1.2, risk R4).
     *
     * <p>The mask is static geometry while the frames under it are not, so without a cache the
     * export would rebuild and re-blur the same bitmap on EVERY frame. One entry was enough
     * only while "a frame belongs to one clip" held. It stops holding the moment a second
     * masked object can exist — an adjustment layer with a mask over a masked PiP makes the
     * export alternate between two specs per frame, and a one-entry cache then MISSES every
     * single time, i.e. it degrades to exactly the no-cache cost it was added to avoid. This
     * has to land here, in M0, because M0 is the last point before anything can produce that
     * second masked object.</p>
     *
     * <p>Four, not more: each entry is a full-frame ALPHA_8 bitmap (~2 MB at 1080p), and four
     * covers the realistic worst case (a layer plus a few masked PiPs) without turning a cache
     * into a leak.</p>
     *
     * <p>Evicted bitmaps are deliberately NOT recycled. {@link #endMask} draws the returned
     * bitmap outside the lock, so recycling on eviction would be a use-after-free the moment
     * two threads composite at once; the previous one-entry code dropped its reference the same
     * way, and the GC has always been what actually frees these.</p>
     */
    private static final int FEATHER_CACHE_ENTRIES = 4;
    private static final Object featherLock = new Object();
    private static final java.util.LinkedHashMap<String, Bitmap> featherCache =
            new java.util.LinkedHashMap<String, Bitmap>(8, 0.75f, /* accessOrder= */ true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> eldest) {
                    return size() > FEATHER_CACHE_ENTRIES;
                }
            };

    @Nullable
    private static Bitmap featherBitmap(@NonNull CompositingSpec spec, float w, float h) {
        int bw = Math.max(1, Math.round(w));
        int bh = Math.max(1, Math.round(h));
        String key = MaskFold.signature(spec) + '|' + bw + 'x' + bh;
        synchronized (featherLock) {
            Bitmap hit = featherCache.get(key);   // access-ordered: a get is a touch
            if (hit != null && !hit.isRecycled()) return hit;
            if (hit != null) featherCache.remove(key);
            Bitmap bmp;
            try {
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
            } catch (OutOfMemoryError e) {
                return null;   // a soft edge is never worth taking the export down
            }

            if (spec.usesPerShapeFeather()) {
                if (!drawPerShapeFeather(spec, bmp, w, h)) return null;
                if (spec.invertMasks) {
                    // drawPerShapeFeather composes `inside` (the combined shape region).
                    // The erase pass needs the complement when the stack is a window:
                    // visible = inside, so erase = full - inside. The combined path
                    // gets this via buildErasePath; this path never read invertMasks
                    // at all, so a window-mode stack with per-shape feather exported
                    // inverted (erasing where it should keep). Complement here, once,
                    // so the fold above stays the single statement of the booleans.
                    Bitmap inner;
                    try {
                        inner = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
                    } catch (OutOfMemoryError e) {
                        return null;
                    }
                    new Canvas(inner).drawBitmap(bmp, 0f, 0f, null);
                    new Canvas(bmp).drawColor(0xFFFFFFFF);
                    Paint cut = new Paint();
                    cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                    new Canvas(bmp).drawBitmap(inner, 0f, 0f, cut);
                    inner.recycle();
                }
            } else {
                // EXACTLY the code that shipped: one combined path, one blur. Gated so every
                // project written before per-shape feather provably renders unchanged.
                float radius = CompositingSpec.featherRadiusPx(spec.maskFeather, w, h);
                Path erase = featherErasePath(spec, w, h, radius * 2f + 4f);
                if (erase == null) return null;
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                // Software canvas — this is the whole reason the blur lives in a Bitmap.
                if (radius > 0f) {
                    paint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
                }
                new Canvas(bmp).drawPath(erase, paint);
            }
            featherCache.put(key, bmp);
            return bmp;
        }
    }

    /**
     * {@link #buildErasePath} for the BLUR, which must not stop at the frame edge.
     *
     * <p>buildErasePath is bounded by the frame rect, and a Gaussian treats everything past a
     * path's edge as "not erased" — so wherever the erase region met the frame border, the blur
     * faded it to half strength and the picture leaked back in along that border. That is most
     * of the frame's edge for a window ("Show only inside the box"), and any edge a hole box
     * runs off. The hole is the shapes themselves, unclipped; the window's complement is taken
     * against a rect {@code pad} beyond the frame, past the blur's reach. Same geometry inside
     * the frame, so the hard clip path and {@link #buildVisiblePath} are untouched.</p>
     */
    @Nullable
    private static Path featherErasePath(@NonNull CompositingSpec spec, float w, float h,
                                         float pad) {
        if (!spec.hasMasks() || w <= 0 || h <= 0) return null;
        MaskFold.Fold fold = MaskFold.foldOps(spec);
        Path region = fold.sequential ? buildSequential(spec, fold, w, h)
                                      : buildTwoBucket(spec, w, h);
        if (!spec.invertMasks) return region;
        Path erase = new Path();
        erase.addRect(-pad, -pad, w + pad, h + pad, Path.Direction.CW);
        erase.op(region, Path.Op.DIFFERENCE);
        return erase;
    }

    /**
     * Compose the visible-region alpha ONE SHAPE AT A TIME, each blurred by its own feather.
     *
     * <p>This is the only way one hard edge and one soft edge can coexist in a single mask. The
     * shipped path unions the shapes into a single {@code Path} first and blurs the result, at
     * which point there is exactly one radius to give it — softening shape 2 necessarily
     * softened shape 1 (user, 2026-08-06).</p>
     *
     * <p><b>The booleans become ALPHA ops rather than Path ops:</b> union → SRC_OVER,
     * subtract → DST_OUT, intersect → DST_IN. A blurred shape carries a soft edge into the
     * composite, which a {@code Path.op} could not represent — a path has no partial coverage,
     * so a soft subtract was never expressible either. That is a capability gain, but it is
     * also why this must stay behind the gate: it is a genuinely different renderer.</p>
     *
     * <p><b>Draw ORDER preserves the shipped geometry.</b> When no shape intersects, the two
     * bucket reading is kept exactly — every additive shape first, then every subtractive one,
     * so a subtract still removes from the union of ALL adds rather than only from those before
     * it. Switching that to an ordered fold would move existing geometry the moment a user
     * touched a feather slider, which is the last thing this change may do.</p>
     *
     * <p>Composes {@code inside} (the combined shape region), NEVER the erase bitmap:
     * {@link #featherBitmap} complements it when {@code invertMasks} turns the stack into
     * a window. Reading invert here as well would be a second statement of the same fact.</p>
     *
     * @return false if the surface could not be prepared; the caller then falls back to no
     *         feather bitmap at all rather than to a wrong one
     */
    private static boolean drawPerShapeFeather(@NonNull CompositingSpec spec,
                                               @NonNull Bitmap bmp, float w, float h) {
        MaskFold.Fold fold = MaskFold.foldOps(spec);
        if (fold.ops.length != spec.masks.size()) return false;
        Canvas canvas = new Canvas(bmp);
        boolean ordered = fold.sequential;
        // Two passes when unordered (adds, then subtracts); one pass in list order when the
        // stack intersects and order is the meaning.
        for (int pass = 0; pass < (ordered ? 1 : 2); pass++) {
            for (int i = 0; i < spec.masks.size(); i++) {
                CompositingSpec.MaskShape m = spec.masks.get(i);
                int op = fold.ops[i];
                if (!ordered) {
                    boolean isSub = op == MaskFold.OP_DIFFERENCE;
                    if ((pass == 0) == isSub) continue;   // pass 0 = adds, pass 1 = subtracts
                }
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                float radius = CompositingSpec.featherRadiusPx(spec.featherOf(m), w, h);
                if (radius > 0f) {
                    paint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
                }
                paint.setXfermode(new android.graphics.PorterDuffXfermode(alphaMode(op)));
                canvas.drawPath(shapePath(m, w, h), paint);
            }
        }
        return true;
    }

    /** {@link MaskFold}'s op ordinals as ALPHA composition modes. @see #drawPerShapeFeather */
    @NonNull
    private static android.graphics.PorterDuff.Mode alphaMode(int op) {
        switch (op) {
            case MaskFold.OP_DIFFERENCE: return android.graphics.PorterDuff.Mode.DST_OUT;
            case MaskFold.OP_INTERSECT:  return android.graphics.PorterDuff.Mode.DST_IN;
            case MaskFold.OP_UNION:
            default:                     return android.graphics.PorterDuff.Mode.SRC_OVER;
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
