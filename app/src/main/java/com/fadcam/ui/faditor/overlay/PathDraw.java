package com.fadcam.ui.faditor.overlay;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.path.PathItem;
import com.fadcam.ui.faditor.model.path.PathPlan;

/**
 * THE one routine that draws a path item, for the live preview and the export alike (STU-5
 * primitives step 3, hidden: nothing calls it until {@link PathItem#ENABLED}). Everything that
 * decides the look is in {@link PathPlan}, which the parity test pins; this only turns that plan
 * into Canvas calls, the same calls on both surfaces: a fill first (closed paths only), then the
 * line with round ends and joins.
 */
public final class PathDraw {

    private PathDraw() {}

    // One Paint and Path per thread (the preview draws on the main thread, the export on its own):
    // ThreadLocal.withInitial needs Android 8, and the app starts at Android 7.
    private static final ThreadLocal<Paint> PAINT = new ThreadLocal<Paint>() {
        @Override protected Paint initialValue() { return new Paint(Paint.ANTI_ALIAS_FLAG); }
    };
    private static final ThreadLocal<Path> PATH = new ThreadLocal<Path>() {
        @Override protected Path initialValue() { return new Path(); }
    };

    /** Draw {@code item} onto a {@code frameW} x {@code frameH} canvas. */
    public static void draw(@NonNull Canvas canvas, @NonNull PathItem item, int frameW, int frameH) {
        PathPlan plan = PathPlan.of(item, frameW, frameH);
        if (plan.isEmpty()) return;
        Path path = PATH.get();
        path.rewind();
        float[] p = plan.points;
        path.moveTo(p[0], p[1]);
        for (int i = 2; i + 1 < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        if (plan.closed) path.close();
        Paint paint = PAINT.get();
        if (plan.fillColor != 0) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(plan.fillColor);
            canvas.drawPath(path, paint);
        }
        if (plan.strokePx > 0f) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(plan.strokePx);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(plan.strokeColor);
            canvas.drawPath(path, paint);
        }
    }
}
