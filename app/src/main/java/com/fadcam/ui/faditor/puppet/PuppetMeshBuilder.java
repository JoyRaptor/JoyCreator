package com.fadcam.ui.faditor.puppet;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transform.mesh.AlphaContour;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;
import com.fadcam.ui.faditor.transform.mesh.PuppetPoseRemap;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;
import com.fadcam.ui.faditor.transform.mesh.PuppetTopology;

/**
 * TURNS A PICTURE INTO A PUPPET — the one place a rig becomes something the renderer can draw.
 *
 * <p>SPEC_20260904_PUPPET_ARCHITECTURE, in JoyRaptor's own words: <i>"I upload a PNG, it sees a
 * blob of pixels in the shape of a person, puts TRIANGLES AROUND THE SHAPE in a mesh, and
 * anything transparent it cuts off on some threshold."</i> That is this file, joining three
 * pieces the engine lane already built and tested:
 *
 * <pre>
 *   alpha -> AlphaContour.trace  -> the outline of the opaque pixels
 *         -> AlphaContour.simplify -> fewer points, same shape
 *         -> PuppetTopology        -> triangles, plus the across-the-body weight table
 *         -> MeshWarpSpec          -> what the item stores and both renderers already draw
 * </pre>
 *
 * <p><b>The two stores stay apart.</b> {@link PuppetPin#restX} is where a pin was PLACED and is an
 * input to the topology; the spec's handle values are OFFSETS from there, and they are what
 * bends the picture. Re-placing a pin rebuilds; posing one does not. That split is why dragging
 * an arm does not re-triangulate the character sixty times a second.
 *
 * <p><b>Rebuilding preserves the pose.</b> A pin added to a rig that is already bent must not
 * straighten it, so {@link #rebuild} copies the old offsets across by index. Pins keep their
 * index when one is appended, and {@code PuppetRig.removePin} renumbers bones for the same
 * reason — so index is a safe identity here, which {@code PuppetRigTest} pins down.
 */
public final class PuppetMeshBuilder {

    private PuppetMeshBuilder() {}

    /**
     * Longest side the alpha is scanned at.
     *
     * <p>The contour is traced in UNIT space, so scanning a downsampled copy costs a little
     * precision on the outline and nothing else — and it is the difference between a 4000px PNG
     * taking a beat and taking a second. 256 is comfortably finer than the mesh that follows.
     */
    private static final int SCAN_MAX = 256;

    /** Below two pins there is nothing to solve: MLS needs two points to have a direction. */
    public static final int MIN_PINS = 2;

    /** How hard the outline is simplified, in unit space. */
    private static final float SIMPLIFY_EPS = 0.004f;

    /**
     * Interior seeding density, mapped from the rig's Mesh detail slider.
     *
     * <p><b>This is points ALONG ONE AXIS, not a total.</b> {@code PuppetTriangulator} documents
     * the parameter that way and calls 4-8 "a sensible range". The first version of this file
     * read it as a total and passed 12..160, so the top of the Mesh detail slider asked for a
     * 160x160 grid — tens of thousands of candidate points. The resulting mesh sailed past
     * {@link com.fadcam.ui.faditor.compositor.MeshStampGl#MAX_VERTS}, the stamp refused it in
     * silence, and the picture simply never bent. That was the whole of "nothing happens".
     */
    private static final int INTERIOR_MIN = 3;
    private static final int INTERIOR_MAX = 10;

    /**
     * Vertex ceiling this builder will hand the renderer.
     *
     * <p>Read from {@code MeshStampGl} rather than copied, because a builder that disagrees with
     * its renderer about the budget produces meshes that are silently dropped. The margin below
     * the hard cap is for the triangle count: ear clipping a polygon of V vertices yields roughly
     * 2V triangles, so indices run out before vertices do.
     */
    private static final int VERT_BUDGET = Math.min(
            com.fadcam.ui.faditor.compositor.MeshStampGl.MAX_VERTS,
            com.fadcam.ui.faditor.compositor.MeshStampGl.MAX_INDICES / 6);

    /**
     * Build a fresh spec for this rig, or null when the picture cannot carry one.
     *
     * <p>Null is a normal answer, not a failure: fewer than {@link #MIN_PINS} pins, or artwork
     * with no opaque region to trace (a fully transparent PNG, or a threshold set so high that
     * nothing survives it). The caller leaves the item unwarped, which is exactly right.
     */
    @Nullable
    public static MeshWarpSpec build(@Nullable Bitmap bmp, @Nullable PuppetRig rig) {
        if (bmp == null || bmp.isRecycled() || rig == null) return null;
        if (rig.pinCount() < MIN_PINS) return null;

        float[][] rings = outlines(bmp, rig.edgeThreshold, rig.edgeExpansion, SIMPLIFY_EPS);
        if (rings == null || rings.length == 0) return null;

        int interior = INTERIOR_MIN
                + Math.round(clamp01(rig.meshDetail) * (INTERIOR_MAX - INTERIOR_MIN));

        // EVERY AUTHORED KNOB, not just the positions. Softness, each pin's stiff patch and
        // each pin's mute all shape the weight table rather than the triangles, so they belong
        // here at build time — a slider that only reaches the model is a dead knob, which is
        // precisely what the engine lane found five of.
        int n = rig.pinCount();
        float[] pins = new float[n * 2];
        float[] stiffArea = new float[n];
        float[] stiffStrength = new float[n];
        // 1 is automatic — the density-adaptive falloff, untouched. PuppetPin.weight stores -1
        // for "auto", which is the value the model has always used and nothing has ever read.
        float[] weightScale = new float[n];
        boolean[] muted = new boolean[n];
        for (int i = 0; i < n; i++) {
            PuppetPin p = rig.pin(i);
            pins[i * 2] = clamp01(p.restX);
            pins[i * 2 + 1] = clamp01(p.restY);
            // Only a Stiff pin has a stiff patch. Sending a Free pin's defaults would starch
            // the whole character, which is the opposite of what Free means.
            boolean stiff = p.type == PuppetPin.Type.STIFF;
            stiffArea[i] = stiff ? clamp01(p.stiffArea) : 0f;
            stiffStrength[i] = stiff ? clamp01(p.stiffStrength) : 0f;
            muted[i] = p.muted;
            weightScale[i] = p.weightIsAuto() ? 1f : Math.max(0f, p.weight);
        }

        // BUILD SOMETHING THE RENDERER WILL ACTUALLY DRAW. A mesh over budget is not an error
        // anywhere — it is accepted by the topology, stored on the item, and then quietly
        // dropped at draw time. So the check belongs HERE, where there is still something to do
        // about it: step the density down and try again rather than hand over a mesh that will
        // be ignored. Three attempts is plenty; the first almost always fits.
        // BUILD SOMETHING THE RENDERER WILL ACTUALLY DRAW. A mesh over budget is not an error
        // anywhere -- the topology accepts it, the item stores it, and MeshStampGl then drops it
        // at draw time. So the whole check belongs here, where there is still something to do
        // about it. Two dials, in the order that matters: thin the OUTLINES first, because with
        // several islands they are most of the count, then the interior density.
        float eps = SIMPLIFY_EPS;
        try {
            for (int attempt = 0; attempt < 7; attempt++) {
                PuppetTopology topo = new PuppetTopology(
                        rings, interior, pins, rig.softness, stiffArea, stiffStrength, muted,
                        weightScale);
                if (topo.handleCount() != rig.pinCount() || topo.vertexCount() < 3) return null;
                if (topo.vertexCount() <= VERT_BUDGET
                        && topo.indexCount() <= com.fadcam.ui.faditor.compositor
                                .MeshStampGl.MAX_INDICES) {
                    return withDepth(new MeshWarpSpec(topo), rig);
                }
                // Coarsen and go again. Interior first while there is any left -- it costs the
                // least -- then the outlines, which is where the vertices actually are.
                if (interior > 2) {
                    interior = Math.max(2, interior / 2);
                } else {
                    eps *= 2f;
                    float[][] coarser = outlines(bmp, rig.edgeThreshold, rig.edgeExpansion, eps);
                    if (coarser == null || coarser.length == 0) break;
                    rings = coarser;
                }
            }
            return null;    // a picture this busy stays straight rather than silently broken
        } catch (Exception e) {
            // A picture that cannot be triangulated costs the RIG, never the overlay. The pins
            // stay, the drawer stays, and the user sees an un-bent picture rather than a crash.
            return null;
        }
    }

    /**
     * Copy each pin's DEPTH onto the spec, mapping the drawer's 0..1 onto the engine's -1..+1.
     *
     * <p>One place where a slider becomes a z, so the mapping is argued about here rather than
     * rediscovered in three files. 0.5 is flat, which is what every rig made before depth existed
     * carries — so nothing already drawn moves.
     */
    @NonNull
    private static MeshWarpSpec withDepth(@NonNull MeshWarpSpec spec, @NonNull PuppetRig rig) {
        for (int i = 0; i < rig.pinCount(); i++) {
            spec.setHandleZ(i, (clamp01(rig.pin(i).depth) - 0.5f) * 2f);
        }
        return spec;
    }

    /**
     * Rebuild after the pins CHANGED, carrying the existing pose across.
     *
     * @param old the spec being replaced, or null on the first build
     */
    @Nullable
    public static MeshWarpSpec rebuild(@Nullable Bitmap bmp, @Nullable PuppetRig rig,
                                       @Nullable MeshWarpSpec old) {
        return rebuild(bmp, rig, old, -1);
    }

    /**
     * Rebuild after the pins CHANGED, carrying the existing pose AND its keyframes across.
     *
     * @param removedPin the index that was just deleted, or -1 when nothing was. It matters:
     *                   after a deletion every pin above it shifted down by one, so copying the
     *                   pose straight across would give each surviving pin its NEIGHBOUR's
     *                   offset — every limb subtly wrong, with nothing to point at.
     */
    @Nullable
    public static MeshWarpSpec rebuild(@Nullable Bitmap bmp, @Nullable PuppetRig rig,
                                       @Nullable MeshWarpSpec old, int removedPin) {
        MeshWarpSpec fresh = build(bmp, rig);
        if (fresh == null || rig == null) return fresh;
        if (old != null) carryPose(old, fresh, rig.pinCount(), removedPin);
        rig.meshSignature = signatureOf(rig);
        return fresh;
    }

    /**
     * Move the old pose onto the new topology — the STATIC handles and every KEYFRAME.
     *
     * <p>The old code copied the handle array and stopped there, which lost a whole animation the
     * moment a pin was added to a rig that had one. {@code MeshWarpSpec.retopologize} rewrites
     * every pose in the track through one remapper, which is what that method exists for.
     */
    private static void carryPose(@NonNull MeshWarpSpec old, @NonNull MeshWarpSpec fresh,
                                  int newPinCount, int removedPin) {
        int oldPins = old.topology() == null ? 0 : old.topology().handleCount();
        int[] newToOld = new int[Math.max(0, newPinCount)];
        for (int i = 0; i < newToOld.length; i++) {
            if (removedPin >= 0 && i >= removedPin) newToOld[i] = i + 1;   // everything shifted
            else newToOld[i] = i;
            if (newToOld[i] >= oldPins) newToOld[i] = -1;                  // a brand new pin
        }
        try {
            MeshPoseTrack.Remapper remap = PuppetPoseRemap.byPinIndex(newToOld, 2);
            // Static handles first, then the track, both through the SAME map so a bent rig and
            // an animated one survive a pin change identically.
            float[] before = old.handles();
            float[] after = fresh.handles();
            if (before != null && after != null) {
                float[] moved = remap.remap(before);
                if (moved != null && moved.length == after.length) {
                    System.arraycopy(moved, 0, after, 0, after.length);
                }
            }
            MeshPoseTrack track = old.track();
            if (track != null && !track.isEmpty()) {
                fresh.setTrack(track);
                fresh.retopologize(fresh.topology(), remap);
            }
        } catch (Exception ignored) {
            // A pose that cannot be carried costs the POSE, never the rig.
        }
    }

    /**
     * Everything about a rig that changes what the mesh looks like.
     *
     * <p>Pin count alone is not enough, and that is the whole point: softness, a stiff patch and
     * a mute all change the WEIGHT TABLE without changing a single triangle, so a rig compared by
     * count goes on bending the old way while the slider insists otherwise.
     */
    /**
     * Push every pin's depth onto the spec WITHOUT rebuilding anything.
     *
     * <p>Depth changes which triangles draw in front of which. It moves no vertex, changes no
     * weight and alters no outline — so re-tracing and re-triangulating the artwork to apply it
     * is pure waste, and it was waste on a scale that mattered: the depth control sends a touch
     * sample every few milliseconds, and each one was tracing the PNG and ear-clipping hundreds
     * of contour points. The picture would have crawled under a finger.
     *
     * <p>Depth is therefore NOT part of {@link #signatureOf} either — a rig whose only change is
     * depth does not need a new mesh, and saying otherwise made every caller rebuild for nothing,
     * including the drawer's own slider.
     */
    public static void applyDepth(@Nullable MeshWarpSpec spec, @Nullable PuppetRig rig) {
        if (spec == null || rig == null || spec.topology() == null) return;
        int n = Math.min(rig.pinCount(), spec.topology().handleCount());
        for (int i = 0; i < n; i++) {
            spec.setHandleZ(i, (clamp01(rig.pin(i).depth) - 0.5f) * 2f);
        }
    }

    public static long signatureOf(@Nullable PuppetRig rig) {
        if (rig == null) return 0L;
        long h = 1469598103934665603L;
        h = mix(h, rig.pinCount());
        h = mix(h, Math.round(rig.softness * 1000f));
        h = mix(h, Math.round(rig.meshDetail * 1000f));
        h = mix(h, Math.round(rig.edgeThreshold * 1000f));
        h = mix(h, Math.round(rig.edgeExpansion * 1000f));
        for (int i = 0; i < rig.pinCount(); i++) {
            PuppetPin p = rig.pin(i);
            h = mix(h, p.type.ordinal());
            h = mix(h, p.muted ? 1 : 0);
            // The override is part of what the mesh IS, so a change to one must invalidate the
            // cached topology — otherwise the slider moves and nothing bends any differently.
            h = mix(h, Math.round((p.weightIsAuto() ? 1f : p.weight) * 1000f));
            h = mix(h, Math.round(p.restX * 4096f));
            h = mix(h, Math.round(p.restY * 4096f));
            if (p.type == PuppetPin.Type.STIFF) {
                h = mix(h, Math.round(p.stiffArea * 1000f));
                h = mix(h, Math.round(p.stiffStrength * 1000f));
            }
        }
        return h;
    }

    private static long mix(long h, int v) { return (h ^ v) * 1099511628211L; }

    /**
     * True when the spec no longer matches the rig and must be rebuilt before it is drawn.
     *
     * <p>Cheap enough to call on every change, which is the point: the alternative is a dirty
     * flag that someone forgets to set, and a mesh silently drawn against the wrong pin count is
     * a picture that bends around handles that are not there.
     */
    public static boolean needsRebuild(@Nullable MeshWarpSpec spec, @Nullable PuppetRig rig) {
        if (rig == null || rig.pinCount() < MIN_PINS) return false;
        if (spec == null || spec.topology() == null) return true;
        if (!(spec.topology() instanceof PuppetTopology)) return true;
        if (spec.topology().handleCount() != rig.pinCount()) return true;
        // THE KNOBS TOO. Softness, a stiff patch and a mute change the weight table and not one
        // triangle, so a count-only comparison reports "nothing to do" for exactly the edits a
        // user is most likely to be watching for.
        return rig.meshSignature != signatureOf(rig);
    }

    // ── the outline ──────────────────────────────────────────────────────

    /**
     * The traced, simplified outline of the opaque pixels, in unit space, or null.
     *
     * <p>Scanned on a downsampled copy — see {@link #SCAN_MAX}. A bitmap with no alpha channel at
     * all still traces: every pixel reads as opaque, so the ring is the picture's own rectangle,
     * which is the correct answer for a photo and lets a JPEG be bent like any other image.
     */
    /**
     * Every opaque piece, simplified and grown — the geometry the topology is built from.
     *
     * <p>{@code eps} is the simplification tolerance and the caller RAISES IT when the mesh comes
     * out over budget. That is the whole reason it is a parameter: with ten islands the CONTOURS
     * dominate the vertex count, so reducing interior density alone cannot save a mesh that is
     * five times too big. Measured on JoyRaptor's dinosaur: 3,548 vertices against a limit of
     * 625, with interior already at its minimum.
     */
    @Nullable
    private static float[][] outlines(@NonNull Bitmap src, float thresholdUnit, float expandUnit,
                                      float eps) {
        float[] one = outline(src, thresholdUnit);      // kept for the single-piece fast path
        float[][] all = lastTraceAll;
        lastTraceAll = null;
        if (all == null || all.length == 0) {
            if (one == null) return null;
            all = new float[][]{one};
        }

        // SIMPLIFY EVERY RING. The single-piece path did this and the multi-piece one did not,
        // so a detached-limb character went to the triangulator at full traced resolution --
        // every staircase pixel of every outline, on every island. That alone put it past the
        // renderer's budget before a single interior point was added.
        float[][] thin = new float[all.length][];
        for (int i = 0; i < all.length; i++) {
            float[] r = all[i];
            try {
                float[] t = AlphaContour.simplify(r, eps);
                if (t != null && t.length >= 6) r = t;
            } catch (Exception ignored) { }
            thin[i] = r;
        }
        all = thin;

        if (expandUnit <= 0f) return all;
        // GROW EACH PIECE by the authored amount, separately. Expanding a merged outline would
        // close the gap between two limbs and fuse them into one blob — which is exactly the
        // thing the detached-limbs work exists to avoid.
        float[][] grown = new float[all.length][];
        for (int i = 0; i < all.length; i++) {
            float[] g = null;
            try { g = AlphaContour.expand(all[i], expandUnit); } catch (Exception ignored) { }
            grown[i] = (g != null && g.length >= 6) ? g : all[i];
        }
        return grown;
    }

    /** Set by {@link #outline} on its way through, so the trace is walked once and not twice. */
    @Nullable private static float[][] lastTraceAll;

    @Nullable
    private static float[] outline(@NonNull Bitmap src, float thresholdUnit) {
        Bitmap scan = null;
        boolean scaled = false;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            int longest = Math.max(w, h);
            if (longest > SCAN_MAX) {
                float k = SCAN_MAX / (float) longest;
                int sw = Math.max(2, Math.round(w * k));
                int sh = Math.max(2, Math.round(h * k));
                scan = Bitmap.createScaledBitmap(src, sw, sh, true);
                scaled = scan != src;
            } else {
                scan = src;
            }

            int sw = scan.getWidth(), sh = scan.getHeight();
            int[] px = new int[sw * sh];
            scan.getPixels(px, 0, sw, 0, 0, sw, sh);
            // Reuse the same array for alpha rather than allocating a second one the size of the
            // picture — this runs on the UI thread when a pin lands.
            for (int i = 0; i < px.length; i++) px[i] = (px[i] >>> 24) & 0xFF;

            int threshold = Math.max(1, Math.min(254, Math.round(clamp01(thresholdUnit) * 255f)));
            // EVERY PIECE, not just the biggest. A character drawn as detached limbs is one
            // puppet; traceAll is what the engine lane built for exactly that, and stashing the
            // result here means the alpha is scanned once rather than once per question.
            lastTraceAll = AlphaContour.traceAll(px, sw, sh, threshold, AlphaContour.MIN_REGION_PX);
            if (lastTraceAll != null && lastTraceAll.length == 0) lastTraceAll = null;
            float[] ring = AlphaContour.trace(px, sw, sh, threshold);
            if (ring == null) {
                // Nothing survived the threshold. Try the engine's own default once before
                // giving up: a picture with a soft edge and a high threshold is a setting
                // problem, not an unriggable picture.
                ring = AlphaContour.trace(px, sw, sh, AlphaContour.DEFAULT_THRESHOLD);
            }
            if (ring == null) return null;
            float[] simple = AlphaContour.simplify(ring, SIMPLIFY_EPS);
            return (simple != null && simple.length >= 6) ? simple : ring;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (scaled && scan != null && scan != src && !scan.isRecycled()) scan.recycle();
        }
    }

    /**
     * How many SEPARATE opaque pieces the artwork is in.
     *
     * <p>{@code AlphaContour.trace} keeps the largest connected region and drops the rest — its
     * own doc says so under "Multiple blobs". That is a perfectly reasonable engine rule and a
     * terrible user experience if nobody mentions it: a character drawn as detached limbs gets
     * triangles around one limb, and every pin on the others moves a dot and no pixels.
     *
     * <p>So the UI counts the pieces and says so. Same 4-connectivity and the same threshold the
     * tracer uses, on the same downsampled copy, so the number reported is the number that
     * matters rather than a different opinion about what "connected" means.
     *
     * @return 0 when nothing is opaque, otherwise the number of connected regions
     */
    public static int countIslands(@Nullable Bitmap src, float thresholdUnit) {
        if (src == null || src.isRecycled()) return 0;
        Bitmap scan = null;
        boolean scaled = false;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return 0;
            int longest = Math.max(w, h);
            if (longest > SCAN_MAX) {
                float k = SCAN_MAX / (float) longest;
                scan = Bitmap.createScaledBitmap(src, Math.max(2, Math.round(w * k)),
                        Math.max(2, Math.round(h * k)), true);
                scaled = scan != src;
            } else {
                scan = src;
            }
            int sw = scan.getWidth(), sh = scan.getHeight();
            int[] px = new int[sw * sh];
            scan.getPixels(px, 0, sw, 0, 0, sw, sh);
            int threshold = Math.max(1, Math.min(254, Math.round(clamp01(thresholdUnit) * 255f)));

            boolean[] seen = new boolean[sw * sh];
            int[] stack = new int[sw * sh];
            int islands = 0;
            for (int start = 0; start < px.length; start++) {
                if (seen[start]) continue;
                seen[start] = true;
                if (((px[start] >>> 24) & 0xFF) < threshold) continue;
                islands++;
                // A SPECK IS NOT A LIMB. Regions smaller than this are anti-aliasing crumbs and
                // stray dots; counting them would report "17 pieces" for a clean two-piece
                // character and the warning would be noise.
                int size = 0;
                int sp = 0;
                stack[sp++] = start;
                while (sp > 0) {
                    int i = stack[--sp];
                    size++;
                    int x = i % sw, y = i / sw;
                    if (x > 0) sp = push(px, seen, stack, sp, i - 1, threshold);
                    if (x < sw - 1) sp = push(px, seen, stack, sp, i + 1, threshold);
                    if (y > 0) sp = push(px, seen, stack, sp, i - sw, threshold);
                    if (y < sh - 1) sp = push(px, seen, stack, sp, i + sw, threshold);
                }
                if (size < MIN_ISLAND_PX) islands--;
            }
            return Math.max(0, islands);
        } catch (Exception | OutOfMemoryError e) {
            return 0;      // a count we could not take is not a warning worth showing
        } finally {
            if (scaled && scan != null && scan != src && !scan.isRecycled()) scan.recycle();
        }
    }

    /** Pixels below which a connected region is a speck, not a piece of the character. */
    private static final int MIN_ISLAND_PX = 24;

    private static int push(int[] px, boolean[] seen, int[] stack, int sp, int i, int threshold) {
        if (seen[i]) return sp;
        seen[i] = true;
        if (((px[i] >>> 24) & 0xFF) < threshold) return sp;
        stack[sp++] = i;
        return sp;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
