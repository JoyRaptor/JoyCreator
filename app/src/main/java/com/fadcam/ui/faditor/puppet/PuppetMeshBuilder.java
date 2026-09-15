package com.fadcam.ui.faditor.puppet;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transform.mesh.AlphaContour;
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

        float[] ring = outline(bmp, rig.edgeThreshold);
        if (ring == null || ring.length < 6) return null;

        int interior = INTERIOR_MIN
                + Math.round(clamp01(rig.meshDetail) * (INTERIOR_MAX - INTERIOR_MIN));

        float[] pins = new float[rig.pinCount() * 2];
        for (int i = 0; i < rig.pinCount(); i++) {
            PuppetPin p = rig.pin(i);
            pins[i * 2] = clamp01(p.restX);
            pins[i * 2 + 1] = clamp01(p.restY);
        }

        // BUILD SOMETHING THE RENDERER WILL ACTUALLY DRAW. A mesh over budget is not an error
        // anywhere — it is accepted by the topology, stored on the item, and then quietly
        // dropped at draw time. So the check belongs HERE, where there is still something to do
        // about it: step the density down and try again rather than hand over a mesh that will
        // be ignored. Three attempts is plenty; the first almost always fits.
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                PuppetTopology topo = new PuppetTopology(ring, interior, pins);
                if (topo.handleCount() != rig.pinCount() || topo.vertexCount() < 3) return null;
                if (topo.vertexCount() <= VERT_BUDGET
                        && topo.indexCount() <= com.fadcam.ui.faditor.compositor
                                .MeshStampGl.MAX_INDICES) {
                    return new MeshWarpSpec(topo);
                }
                if (interior <= 0) break;
                interior = Math.max(0, interior / 2);
            }
            // Contour only, no interior points at all — the last thing that can still bend. A
            // shape whose OUTLINE alone is over budget is one the simplifier should have thinned,
            // and returning null leaves the picture straight rather than silently broken.
            PuppetTopology bare = new PuppetTopology(ring, 0, pins);
            if (bare.handleCount() == rig.pinCount()
                    && bare.vertexCount() >= 3
                    && bare.vertexCount() <= VERT_BUDGET
                    && bare.indexCount() <= com.fadcam.ui.faditor.compositor
                            .MeshStampGl.MAX_INDICES) {
                return new MeshWarpSpec(bare);
            }
            return null;
        } catch (Exception e) {
            // A picture that cannot be triangulated costs the RIG, never the overlay. The pins
            // stay, the drawer stays, and the user sees an un-bent picture rather than a crash.
            return null;
        }
    }

    /**
     * Rebuild after the pins CHANGED, carrying the existing pose across.
     *
     * @param old the spec being replaced, or null on the first build
     */
    @Nullable
    public static MeshWarpSpec rebuild(@Nullable Bitmap bmp, @Nullable PuppetRig rig,
                                       @Nullable MeshWarpSpec old) {
        MeshWarpSpec fresh = build(bmp, rig);
        if (fresh == null || old == null) return fresh;

        float[] before = old.handles();
        float[] after = fresh.handles();
        if (before == null || after == null) return fresh;
        // Copy by index, up to whichever is shorter. Adding a pin appends, so every existing
        // pin keeps its offset; removing one has already renumbered the rig, and the pose that
        // belonged to the deleted pin is the one thing that should not survive.
        int n = Math.min(before.length, after.length);
        System.arraycopy(before, 0, after, 0, n);
        return fresh;
    }

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
        return spec.topology().handleCount() != rig.pinCount();
    }

    // ── the outline ──────────────────────────────────────────────────────

    /**
     * The traced, simplified outline of the opaque pixels, in unit space, or null.
     *
     * <p>Scanned on a downsampled copy — see {@link #SCAN_MAX}. A bitmap with no alpha channel at
     * all still traces: every pixel reads as opaque, so the ring is the picture's own rectangle,
     * which is the correct answer for a photo and lets a JPEG be bent like any other image.
     */
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
