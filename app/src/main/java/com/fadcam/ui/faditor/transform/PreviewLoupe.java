package com.fadcam.ui.faditor.transform;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fadcam.ui.faditor.Studio;

/**
 * THE MAGNIFIER — the same helper the transform tool uses, pointed at a puppet pin.
 *
 * <p>JoyRaptor, 2026-09-15: <i>"we have built a viewing loupe to drag around transform points for
 * detail work. We need that exact same helper here!"</i> Quite right, and for a sharper reason
 * than on the transform surface: a transform handle sits on a corner you can see, while a pin
 * sits in the middle of the artwork with your fingertip parked on top of it.
 *
 * <p>Its whole job is the CHROME — where the circle goes, clipping to it, walking the preview
 * stack to draw the real picture magnified, the crosshair and the rim. What to draw ON TOP,
 * magnified, is the caller's business and arrives as {@link Decor}: the transform tool draws its
 * quad and handles, this surface draws pins and bones.
 *
 * <h3>The re-entrancy trap, and why the walk skips one view</h3>
 * <p>The loupe draws the container that the calling view is itself a child of. Without the skip
 * it would draw itself drawing itself; the guard here plus {@code skip} is what makes that safe,
 * and it is the same pairing {@code TransformOverlayView} arrived at.
 *
 * <p>It lives in {@code transform} rather than {@code puppet} because BOTH surfaces use it now
 * and the dependency has to point one way: the puppet package may lean on transform, never the
 * reverse. The placement rule, the 2.2x zoom and the rim colours came from
 * {@code TransformOverlayView}, which no longer has a second copy of them.
 */
public final class PreviewLoupe {

    /** Draws over the magnified picture, in the PICTURE's coordinates. */
    public interface Decor {
        /**
         * @param invZoom multiply every stroke width and radius by this so a line drawn inside
         *                the loupe is the same thickness on screen as one drawn outside it.
         */
        void draw(@NonNull Canvas c, float invZoom);
    }

    private static final float ZOOM = 2.2f;
    private static final float DIA_DP = 118f;
    private static final float MARGIN_DP = 10f;

    private final Path clip = new Path();
    private boolean drawingContent;

    /** True while the loupe is being drawn — the host's own draw must go inert. */
    public boolean isDrawingContent() { return drawingContent; }

    /**
     * Draw the loupe focused on {@code (fx, fy)} in {@code host}'s pixels.
     *
     * @param contentRoot the container whose children ARE the picture; null draws geometry only
     * @return false when there was nothing to draw
     */
    public boolean draw(@NonNull Canvas c, @NonNull View host, @Nullable ViewGroup contentRoot,
                        float fx, float fy, float density,
                        @NonNull Paint fill, @NonNull Paint stroke, @Nullable Decor decor) {
        return draw(c, host, contentRoot, fx, fy, density, fill, stroke, decor, null);
    }

    /**
     * As above, but never landing on {@code avoid} — a rect in {@code host}’s own pixels.
     *
     * <p>JoyRaptor, on device: <i>"loupe sometimes hides on top of magnifier."</i> The magnifier
     * and the puppet helper strip both park in the corner FURTHEST from the finger, so during a
     * drag they reason their way to the same corner and stack; whichever draws second wins and
     * the other is simply gone. Telling the loupe what is already taken is cheaper than inventing
     * a layout manager for two floating things that never otherwise meet.
     */
    public boolean draw(@NonNull Canvas c, @NonNull View host, @Nullable ViewGroup contentRoot,
                        float fx, float fy, float density,
                        @NonNull Paint fill, @NonNull Paint stroke, @Nullable Decor decor,
                        @Nullable android.graphics.RectF avoid) {
        if (drawingContent) return false;
        if (host.getWidth() <= 0 || host.getHeight() <= 0) return false;

        float dia = DIA_DP * density, r = dia / 2f, m = MARGIN_DP * density;

        // THE CORNER FURTHEST FROM THE FINGER. Anything nearer would be under the hand that is
        // doing the dragging, which is the one place a magnifier is useless.
        float bestX = m, bestY = m, bestD = -1f;
        float[][] cand = {{m, m}, {host.getWidth() - dia - m, m},
                {m, host.getHeight() - dia - m},
                {host.getWidth() - dia - m, host.getHeight() - dia - m}};
        // Two passes. The first ignores any corner something else is already sitting in; the
        // second drops that condition, because on a preview small enough that all four corners
        // are covered a magnifier which overlaps beats no magnifier at all.
        for (int pass = 0; pass < 2 && bestD < 0f; pass++) {
            for (float[] p : cand) {
                if (pass == 0 && avoid != null
                        && avoid.intersects(p[0], p[1], p[0] + dia, p[1] + dia)) {
                    continue;
                }
                float dd = (float) Math.hypot(p[0] + r - fx, p[1] + r - fy);
                if (dd > bestD) { bestD = dd; bestX = p[0]; bestY = p[1]; }
            }
        }

        int save = c.save();
        clip.reset();
        clip.addCircle(bestX + r, bestY + r, r, Path.Direction.CW);
        c.clipPath(clip);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xF0050508);
        c.drawCircle(bestX + r, bestY + r, r, fill);

        c.translate(bestX + r, bestY + r);
        c.scale(ZOOM, ZOOM);
        c.translate(-fx, -fy);

        drawContent(c, host, contentRoot);
        if (decor != null) decor.draw(c, 1f / ZOOM);

        c.restoreToCount(save);

        // Rim, then a crosshair with a gap at the middle so the thing being dragged stays visible.
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Studio.alpha(Studio.GUIDE, 0x47));
        stroke.setStrokeWidth(density);
        c.drawCircle(bestX + r, bestY + r, r - density, stroke);
        stroke.setColor(Studio.alpha(Studio.AUDIO, 0xD9));
        float a = 5f * density, b = 13f * density;
        c.drawLine(bestX + r, bestY + r - b, bestX + r, bestY + r - a, stroke);
        c.drawLine(bestX + r, bestY + r + a, bestX + r, bestY + r + b, stroke);
        c.drawLine(bestX + r - b, bestY + r, bestX + r - a, bestY + r, stroke);
        c.drawLine(bestX + r + a, bestY + r, bestX + r + b, bestY + r, stroke);
        return true;
    }

    /** Draw the preview stack, offset so it lands where the picture really is. */
    private void drawContent(@NonNull Canvas c, @NonNull View host, @Nullable ViewGroup root) {
        if (root == null || drawingContent) return;
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;

        float ox = 0f, oy = 0f;
        for (View v = host; v != null && v != root; ) {
            ox += v.getLeft();
            oy += v.getTop();
            ViewParent p = v.getParent();
            if (!(p instanceof View)) return;   // not under this root: refuse rather than guess
            v = (View) p;
            ox -= v.getScrollX();
            oy -= v.getScrollY();
        }

        drawingContent = true;
        int outer = c.save();
        try {
            c.translate(-ox, -oy);
            int n = root.getChildCount();
            for (int i = 0; i < n; i++) {
                View ch = root.getChildAt(i);
                if (ch == host) continue;                       // never draw ourselves
                if (ch.getVisibility() != View.VISIBLE) continue;
                if (ch.getWidth() <= 0 || ch.getHeight() <= 0) continue;
                if (ch.getAlpha() <= 0.01f) continue;
                int s = c.save();
                c.translate(ch.getLeft() + ch.getTranslationX(),
                        ch.getTop() + ch.getTranslationY());
                float sx = ch.getScaleX(), sy = ch.getScaleY();
                if (sx != 1f || sy != 1f) {
                    c.scale(sx, sy, ch.getPivotX(), ch.getPivotY());
                }
                float rot = ch.getRotation();
                if (rot != 0f) c.rotate(rot, ch.getPivotX(), ch.getPivotY());
                try {
                    ch.draw(c);
                } catch (Exception ignored) {
                    // A child that refuses to draw off-schedule costs its own layer, not the
                    // magnifier: a surface view, a texture mid-recreate. Better a partial
                    // picture than no loupe at all.
                }
                c.restoreToCount(s);
            }
        } finally {
            c.restoreToCount(outer);
            drawingContent = false;
        }
    }
}
