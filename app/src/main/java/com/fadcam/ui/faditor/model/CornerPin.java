package com.fadcam.ui.faditor.model;

import android.graphics.Matrix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE single authority for an overlay's CORNER PIN — four corner offsets, the track names that
 * animate them, the JSON keys that persist them, and the one {@link Matrix} both renderers build
 * from them.
 *
 * <p><b>Why a homography and not more View properties.</b> An image overlay is positioned in the
 * preview with {@code setRotation}/{@code setScaleX}/{@code setScaleY}, and those are AFFINE —
 * there is no {@code setSkew} and no corner pin anywhere in the View API, so parallel edges stay
 * parallel forever. {@code android.graphics.Matrix} is a full 3x3 WITH the perspective row, and
 * {@link Matrix#setPolyToPoly} with four point pairs solves for the exact homography that carries
 * one quadrilateral onto another. That is the whole mechanism: the offsets below describe a
 * destination quad, {@code setPolyToPoly} produces the matrix, and both the preview and the export
 * concat that matrix immediately around the bitmap draw.</p>
 *
 * <p><b>The offsets are FRACTIONS OF THE ITEM'S OWN UNTRANSFORMED SIZE</b>, not pixels and not
 * canvas fractions. {@code TL.dx = 0.1f} means "pull the top-left corner right by one tenth of the
 * picture's drawn width". That is the only unit that survives the three things which change under
 * it — the item's {@code sizeFraction}, the preview's content rect, and the export's output
 * resolution — so one authored pin looks the same in a 480p preview and a 4K export. A pixel
 * offset would not, and a canvas fraction would change shape whenever the item was resized.</p>
 *
 * <p><b>Zero is undistorted, and zero is the default.</b> {@link #isFlat} lets every render path
 * skip the matrix entirely, so an overlay that has never been pinned — i.e. every overlay in every
 * project written before this — takes the exact code path it always did.</p>
 */
public final class CornerPin {

    private CornerPin() {}

    /** Corner indices, in the order {@link Matrix#setPolyToPoly} is fed them (clockwise from TL). */
    public static final int TL = 0;
    public static final int TR = 1;
    public static final int BR = 2;
    public static final int BL = 3;

    /** Axis indices into a corner's (dx, dy) pair. */
    public static final int DX = 0;
    public static final int DY = 1;

    /** Corner names, index-aligned with {@link #TL}..{@link #BL}. */
    public static final String[] CORNER_NAMES = {"TL", "TR", "BR", "BL"};

    /** Number of floats in a packed offset array: four corners times (dx, dy). */
    public static final int SIZE = 8;

    /**
     * How far one corner may be dragged, in units of the item's own size.
     *
     * <p>Eight full extents: a corner can be pulled eight picture-sizes out, which is
     * hard perspective work (a page curl, a billboard planted in a scene) without ever
     * meeting a wall in normal use — After Effects places no wall here either, and an
     * artist mid-drag must never discover one. It is still a LIMIT, not an invitation:
     * a stray keyframe cannot demand a quad a thousand widths across and hand the
     * matrix solver a degenerate problem, the excursion inset the preview lays out
     * stays bounded (eight extents a side), and the GL preview's mediump pin inverse
     * keeps sub-pixel precision this side of it. Same reasoning as
     * {@code KeyframeSet.POS_ABS}.
     *
     * <p>Flips and folds never spend a unit of this: the commit bake clears affine
     * content (mirror flags, rotation, size) with a zero residual, so mirroring is
     * unlimited by construction, whatever the cap says.
     */
    public static final float MAX_OFFSET = 8f;

    /** Below this, an offset is not a distortion — it is float noise. See {@link #isFlat}. */
    public static final float EPSILON = 1e-5f;

    /**
     * The keyframe track animating one corner component.
     *
     * <p>Namespaced with a dot exactly like {@code MaskAnimator}'s per-slot tracks
     * ({@code mask3.cx}), and for the same two reasons: these live in the SAME {@code KeyframeSet}
     * as the item's {@code x}/{@code y}/{@code scale}/{@code rotation}, so an unprefixed
     * {@code "dx"} would be one careless copy-paste from being read as something else; and
     * {@code KeyframeCodec} / {@code ProjectStorage} round-trip ANY track name, so easing, timeline
     * glyphs and the shared undo snapshot come free with no serializer change at all.</p>
     */
    @NonNull
    public static String trackFor(int corner, int axis) {
        int c = corner < 0 || corner > BL ? TL : corner;
        return "pin" + CORNER_NAMES[c] + (axis == DY ? ".dy" : ".dx");
    }

    /** All eight track names, in packed-array order. */
    @NonNull
    public static String[] tracks() {
        String[] out = new String[SIZE];
        for (int c = 0; c < 4; c++) {
            out[c * 2 + DX] = trackFor(c, DX);
            out[c * 2 + DY] = trackFor(c, DY);
        }
        return out;
    }

    /** True when {@code property} is one of the eight {@link #tracks()}. */
    public static boolean isPinTrack(@Nullable String property) {
        if (property == null || !property.startsWith("pin")) return false;
        for (String t : tracks()) if (t.equals(property)) return true;
        return false;
    }

    /**
     * The JSON key one component persists under. Flat rather than dotted (a dot is legal in JSON
     * but reads as nesting), and sparse on write — see {@code ProjectStorage}.
     */
    @NonNull
    public static String jsonKeyFor(int corner, int axis) {
        int c = corner < 0 || corner > BL ? TL : corner;
        return "pin" + CORNER_NAMES[c] + (axis == DY ? "dy" : "dx");
    }

    /** Clamp one offset into {@link #MAX_OFFSET}, mapping NaN to "undistorted". */
    public static float clamp(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, v));
    }

    /** True when every offset is zero (to {@link #EPSILON}) — i.e. no distortion at all. */
    public static boolean isFlat(@Nullable float[] off8) {
        if (off8 == null || off8.length < SIZE) return true;
        for (int i = 0; i < SIZE; i++) {
            if (Math.abs(off8[i]) > EPSILON) return false;
        }
        return true;
    }

    /**
     * The largest excursion any corner makes beyond the item's own rectangle, as a fraction of
     * that rectangle's width / height.
     *
     * <p>The preview needs it: a pinned corner is drawn OUTSIDE the item's laid-out bounds, and a
     * child View's drawing is clipped by its parent. {@code TextOverlayLayer} inflates the image
     * view by this much on every side (the same "excursion margin" trick {@code TextBoxView} uses
     * for glow and shadow) so the pulled corner has somewhere to land.</p>
     *
     * @return {@code {padFractionX, padFractionY}}, never negative.
     */
    @NonNull
    public static float[] excursionFraction(@Nullable float[] off8) {
        float px = 0f, py = 0f;
        if (off8 != null && off8.length >= SIZE) {
            for (int c = 0; c < 4; c++) {
                px = Math.max(px, Math.abs(off8[c * 2 + DX]));
                py = Math.max(py, Math.abs(off8[c * 2 + DY]));
            }
        }
        return new float[]{px, py};
    }

    /**
     * Build the corner-pin homography for a rectangle.
     *
     * <p><b>Coordinate space.</b> {@code left/top/w/h} is the item's UNTRANSFORMED drawn rect, in
     * whatever pixel space the caller is about to draw in — the export hands it the frame-space
     * rect it computed from {@code sizeFraction * outH}, the preview hands it the view-local rect
     * of its own {@code ImageView}. The matrix maps that rect's four corners onto the same four
     * corners displaced by {@code offset * (w or h)}, so it is expressed entirely in the caller's
     * space and needs no knowledge of frame size, scale or rotation.</p>
     *
     * <p><b>Where it goes in the chain: INNERMOST.</b> The caller applies it immediately around
     * the bitmap draw, INSIDE the existing translate / rotate / scale. That order — pin, then
     * scale, then rotate, then translate — is the only one that matches what the control means to
     * a user: the corners are pulled on the PICTURE, and the pinned picture is then rotated and
     * scaled as a rigid whole. The other order (rotate the rect, then pin its axis-aligned
     * corners) would make a corner drag do something different at every rotation angle, which is
     * the same class of bug as a mask that rotates with the object it is masking. It is also the
     * order the preview gets for free: a View's rotation and scale are applied by the parent
     * ABOVE whatever {@code onDraw} puts on the canvas, so concat-ing inside {@code onDraw} is
     * necessarily inside them, and the export mirrors that deliberately.</p>
     *
     * @param out   receives the matrix; untouched and left {@code identity} when there is no pin
     * @param off8  packed offsets, or null
     * @return true if {@code out} holds a real (non-identity) transform the caller must concat
     */
    public static boolean buildMatrix(@NonNull Matrix out, float left, float top,
                                      float w, float h, @Nullable float[] off8) {
        out.reset();
        if (isFlat(off8) || w <= 0f || h <= 0f) return false;
        float right = left + w;
        float bottom = top + h;
        float[] src = {
                left, top,
                right, top,
                right, bottom,
                left, bottom,
        };
        float[] dst = new float[8];
        for (int i = 0; i < SIZE; i++) {
            // Even indices are x (scaled by width), odd are y (scaled by height) — the corners
            // are laid out (x,y) pairs in the same order as CORNER_NAMES.
            dst[i] = src[i] + clamp(off8[i]) * ((i & 1) == 0 ? w : h);
        }
        // FOUR point pairs = an exact homography. setPolyToPoly returns false for a degenerate
        // destination (three collinear corners, or a self-crossing quad a user could reach by
        // dragging one corner across the far edge). A failed solve leaves `out` in an undefined
        // state, so it is reset and refused: an item drawn UNPINNED for one frame is recoverable,
        // an item drawn through garbage is not.
        if (!out.setPolyToPoly(src, 0, dst, 0, 4)) {
            out.reset();
            return false;
        }
        return true;
    }
}
