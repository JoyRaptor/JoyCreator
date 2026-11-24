package com.fadcam.ui.faditor.model.path;

import androidx.annotation.NonNull;

/**
 * What a path item looks like on a frame of a given size, worked out once, for every renderer
 * (STU-5 primitives step 3, hidden). The preview and the export both draw from this plan through
 * {@code overlay/PathDraw}, so they cannot disagree: same outline, same line width in pixels, same
 * colours, same rule for when there is a fill.
 *
 * <p>Pure Java, so the parity test can pin it on a PC ({@code tools/jvm-harness/StudioPathTest}).</p>
 */
public final class PathPlan {

    /** The outline in frame pixels, x,y interleaved. */
    @NonNull public final float[] points;
    public final boolean closed;
    /** Line width in frame pixels: the item's share of the frame height. 0 = no line. */
    public final float strokePx;
    public final int strokeColor;
    /** 0 = no fill. Only a closed path with a fill colour is filled. */
    public final int fillColor;

    private PathPlan(float[] points, boolean closed, float strokePx, int strokeColor, int fillColor) {
        this.points = points;
        this.closed = closed;
        this.strokePx = strokePx;
        this.strokeColor = strokeColor;
        this.fillColor = fillColor;
    }

    /**
     * The plan for {@code item} on a {@code frameW} x {@code frameH} frame. Canvas fractions become
     * pixels (x by the width, y by the height); the line width follows the HEIGHT, so a path looks
     * the same in a 720p preview and a 4K export.
     */
    @NonNull
    public static PathPlan of(@NonNull PathItem item, int frameW, int frameH) {
        float[] unit = item.getPath().polyline();
        float[] px = new float[unit.length];
        for (int i = 0; i + 1 < unit.length; i += 2) {
            px[i] = unit[i] * frameW;
            px[i + 1] = unit[i + 1] * frameH;
        }
        boolean closed = item.getPath().isClosed();
        int fill = closed ? item.getFillColor() : 0;
        return new PathPlan(px, closed, Math.max(0f, item.getStrokeWidth()) * frameH, item.getStrokeColor(), fill);
    }

    /** Nothing would show: no outline, or neither a line nor a fill. */
    public boolean isEmpty() {
        return points.length < 4 || (strokePx <= 0f && fillColor == 0);
    }
}
