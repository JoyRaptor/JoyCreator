package com.fadcam.ui.faditor.sprite;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transform.mesh.LatticeDeformer;
import com.fadcam.ui.faditor.transform.mesh.LatticeTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;

/**
 * Draw a sprite BENT, on a Canvas — the one place that happens, used by the preview and the export.
 *
 * <h3>Why a Canvas warp and not the GL stamp</h3>
 * <p>JoyRaptor, 2026-09-13: <i>"We already got still images to bend. And this sprite is just a
 * still image that swaps out for another still image. There's no reason why we can't have it
 * warp."</i>
 *
 * <p>He is right, and the earlier plan was over-built. An image bends in GL because an image
 * overlay is ALREADY a GL texture on both surfaces. A sprite is not: it draws on a Canvas in the
 * preview ({@code SpriteOverlayView}) and on a Canvas in the export
 * ({@code CompositeExportOverlay}). Promoting sprites onto the GL path to reach
 * {@code MeshStampGl} would have been a large change to reach a warp that {@code Canvas} already
 * offers — {@link Canvas#drawBitmapMesh} is exactly this operation.
 *
 * <p><b>This is not a second warp.</b> Where each point LANDS comes from
 * {@link LatticeDeformer#eval}, the same map the GL path's shader is built from and the same map
 * the handles are drawn from — the deformation has one authority and this does not add another.
 * What differs is only the rasteriser, which already differs between these surfaces for everything
 * else. And because BOTH the sprite preview and the sprite export call THIS method, the two cannot
 * disagree with each other either.
 *
 * <h3>Why it rasterises first</h3>
 * <p>The warp is applied to a bitmap of whatever the sprite is showing at that instant — a sheet
 * cell, a rig's composed parts, anything the caller draws into the offscreen. That is not an
 * implementation convenience, it is JoyRaptor's design constraint made structural:
 *
 * <blockquote>"The distortion should be on the sprite itself — NOT the cells living inside, which
 * are transient. If I'm animating squash and stretch, bend to the head, I want the mouth or facial
 * expressions to follow underneath."</blockquote>
 *
 * <p>Because the content is flattened BEFORE the warp, there is nowhere here for a per-cell warp to
 * exist. Swap the cell and the same bend applies; pose the rig and the same bend applies. The
 * mouth follows the head because they were one picture by the time the bend was applied.
 *
 * <h3>Cost</h3>
 * <p>Zero for a sprite with no bend: {@link #draw} returns false before allocating anything and the
 * caller takes the path it always did. For a bent sprite, one offscreen bitmap, reused across
 * frames while the size holds.
 */
public final class SpriteMeshDraw {

    /**
     * How the caller draws the sprite's CONTENT into the offscreen. Keeps the cell/rig/missing
     * branches where they already live instead of duplicating them here — this class owns the
     * bend, not the picture.
     */
    public interface Content {
        void draw(@NonNull Canvas canvas, @NonNull RectF into);
    }

    /**
     * Grid resolution. The lattice map is a smooth spline, so the visible quality is set by how
     * finely it is sampled rather than by the lattice's own side: a 3x3 lattice still describes a
     * curve, and 16 segments follow it without faceting. 17x17 = 289 vertices, which is nothing
     * against one bitmap draw.
     */
    private static final int SEGMENTS = 16;

    /** Never rasterise larger than this on an edge — a huge sprite must not allocate a huge bitmap. */
    private static final int MAX_EDGE_PX = 2048;

    private final LatticeDeformer deformer = new LatticeDeformer();
    private final float[] out2 = new float[2];
    private float[] pose = new float[0];
    private float[] verts = new float[0];

    @Nullable private Bitmap offscreen;
    @Nullable private Canvas offscreenCanvas;
    private final RectF offscreenRect = new RectF();

    /**
     * Draw {@code content} bent by {@code o}'s mesh into {@code dest}.
     *
     * @return true when the bend was drawn; false when there is nothing to bend and the caller
     *         must draw normally. False is the common case and costs nothing.
     */
    public boolean draw(@NonNull Canvas canvas, @NonNull SpriteOverlayItem o, long timelineMs,
                        @NonNull RectF dest, @NonNull Content content, @Nullable Paint paint) {
        if (!o.hasMesh()) return false;
        MeshWarpSpec spec = o.getMesh();
        if (spec == null) return false;
        MeshTopology topo = spec.topology();
        // Only a LATTICE exposes a continuous map to sample. A puppet solve has solved VERTICES
        // and no eval(), which its own doc is explicit about — so it is refused here rather than
        // approximated, and the sprite draws flat.
        if (!(topo instanceof LatticeTopology)) return false;
        int side = ((LatticeTopology) topo).side();
        if (side < 2) return false;

        int arity = topo.handleArity();
        if (pose.length != arity) pose = new float[arity];
        if (!spec.handlesAt(o.meshLocalTime(timelineMs), pose)) return false;
        if (LatticeDeformer.isIdentityPose(pose)) return false;

        float dw = dest.width(), dh = dest.height();
        if (!(dw > 1f) || !(dh > 1f)) return false;

        int bw = Math.max(1, Math.min(MAX_EDGE_PX, Math.round(dw)));
        int bh = Math.max(1, Math.min(MAX_EDGE_PX, Math.round(dh)));
        if (offscreen == null || offscreen.isRecycled()
                || offscreen.getWidth() != bw || offscreen.getHeight() != bh) {
            if (offscreen != null && !offscreen.isRecycled()) offscreen.recycle();
            try {
                offscreen = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                offscreen = null;
                return false;   // a bend that cannot allocate costs the bend, never the sprite
            }
            offscreenCanvas = new Canvas(offscreen);
        }
        if (offscreen == null || offscreenCanvas == null) return false;

        offscreen.eraseColor(0);
        offscreenRect.set(0f, 0f, bw, bh);
        content.draw(offscreenCanvas, offscreenRect);

        int need = (SEGMENTS + 1) * (SEGMENTS + 1) * 2;
        if (verts.length != need) verts = new float[need];
        int k = 0;
        for (int j = 0; j <= SEGMENTS; j++) {
            float v = j / (float) SEGMENTS;
            for (int i = 0; i <= SEGMENTS; i++) {
                float u = i / (float) SEGMENTS;
                // THE one deformation authority. eval returns where (u,v) lands, still in unit
                // space; mapping that onto dest is all this class does with it.
                deformer.eval(pose, side, u, v, out2);
                verts[k++] = dest.left + out2[0] * dw;
                verts[k++] = dest.top + out2[1] * dh;
            }
        }
        canvas.drawBitmapMesh(offscreen, SEGMENTS, SEGMENTS, verts, 0, null, 0, paint);
        return true;
    }

    /** Drop the offscreen — call when the owner is done drawing for good. */
    public void release() {
        if (offscreen != null && !offscreen.isRecycled()) offscreen.recycle();
        offscreen = null;
        offscreenCanvas = null;
    }
}
