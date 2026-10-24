package com.fadcam.vislab.gl;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

/**
 * A sprite sheet made ready for the GPU: each frame a Sprite layer plays, drawn once into an even
 * grid with a clear gutter round every frame (so a neighbour never bleeds in). The host builds it
 * with Sprite Lab's own drawing, so the slicing, the background key and each frame's alignment
 * match Sprite Lab by construction.
 *
 * <p>{@link #order} is the play order as grid slots, a held frame repeated as often as its weight;
 * the grid holds each frame only once.</p>
 */
public final class SpriteAtlas {

    @NonNull public final Bitmap bitmap;
    public final int cols, rows;
    /** One frame's box and its picture inside it, in pixels (the gutter is the difference). */
    public final int cellW, cellH, gutter;
    @NonNull public final int[] order;
    public final float fps;
    /** The clip asks to play back and forth (a Sprite Lab "pingpong" preset). */
    public final boolean pingpong;

    public SpriteAtlas(@NonNull Bitmap bitmap, int cols, int rows, int cellW, int cellH, int gutter,
                       @NonNull int[] order, float fps, boolean pingpong) {
        this.bitmap = bitmap;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.cellW = Math.max(1, cellW);
        this.cellH = Math.max(1, cellH);
        this.gutter = Math.max(0, gutter);
        this.order = order.length > 0 ? order : new int[]{0};
        this.fps = fps > 0 ? fps : 8f;
        this.pingpong = pingpong;
    }

    /** Width / height of one frame. */
    public float aspect() {
        return (float) cellW / cellH;
    }

    /**
     * Grid slot {@code slot}'s picture as {x, y, w, h} in 0..1 of the whole bitmap, y from the
     * top (GLUtils uploads the bitmap's first row first).
     */
    public void rect(int slot, @NonNull float[] out) {
        int s = Math.max(0, Math.min(cols * rows - 1, slot));
        int col = s % cols, row = s / cols;
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        int stepX = cellW + 2 * gutter, stepY = cellH + 2 * gutter;
        out[0] = (col * stepX + gutter) / bw;
        out[1] = (row * stepY + gutter) / bh;
        out[2] = cellW / bw;
        out[3] = cellH / bh;
    }
}
