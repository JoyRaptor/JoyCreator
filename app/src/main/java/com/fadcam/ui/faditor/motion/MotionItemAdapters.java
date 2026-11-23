package com.fadcam.ui.faditor.motion;

import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.PipPlacement;
import com.fadcam.ui.faditor.model.SpineTransform;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.transform.TransformQuad;

/** Samples Studio's existing rigid placement rules without changing normal rendering. */
public final class MotionItemAdapters {
    private MotionItemAdapters() {}

    /**
     * Supply the renderer's unscaled texture-box width and height per unit size fraction.
     * A text raster has its own measured box. A rigid image uses canvas height and image aspect.
     */
    public static ItemMotionSampler textOrImage(TextOverlayItem item, int frameWidth, int frameHeight,
                                                float boxWidthAtUnit, float boxHeightAtUnit) {
        return timeUs -> {
            double ms = timeUs / 1000.0;
            float[] pose = new float[4];
            item.motionPlacementAt(ms, pose);
            float width = boxWidthAtUnit * pose[2] * item.motionScaleXAt(ms);
            float height = boxHeightAtUnit * pose[2] * item.motionScaleYAt(ms);
            float mirrorX = item.mirrorSignX();
            float mirrorY = item.mirrorSignY();
            return quad(pose, frameWidth, frameHeight, width, height, mirrorX, mirrorY,
                    mirrorX * item.pivotOffsetFromCentreX(width),
                    mirrorY * item.pivotOffsetFromCentreY(height));
        };
    }

    /** Sample the position and rotation of an upright title raster at a fixed font size. */
    public static ItemMotionSampler title(TextOverlayItem item, int layoutW, int layoutH,
                                          int frameW, int frameH, int textureW, int textureH) {
        double xScale = frameW / (double) layoutW;
        double yScale = frameH / (double) layoutH;
        return timeUs -> {
            float[] pose = new float[4];
            item.motionPlacementAt(timeUs / 1000.0, pose);
            var layout = quad(pose, layoutW, layoutH, textureW, textureH, 1, 1, 0, 0);
            return new ItemMotionSampler.Affine(layout.xx() * xScale, layout.xy() * xScale,
                    layout.yx() * yScale, layout.yy() * yScale, layout.tx() * xScale,
                    layout.ty() * yScale);
        };
    }

    /** Per-cell static art corrections are baked into the selected cell texture by the producer. */
    public static ItemMotionSampler sprite(SpriteOverlayItem item, int frameWidth, int frameHeight,
                                           float cellAspect) {
        return timeUs -> {
            float[] pose = new float[4];
            item.motionPlacementAt(timeUs / 1000.0, pose);
            float height = frameHeight * pose[2];
            return quad(pose, frameWidth, frameHeight, height * cellAspect, height,
                    item.isFlipH() ? -1 : 1, item.isFlipV() ? -1 : 1, 0, 0);
        };
    }

    public static ItemMotionSampler pip(Clip clip, int frameWidth, int frameHeight,
                                        int sourceWidth, int sourceHeight, float defaultX,
                                        float defaultY, float defaultScale) {
        return timeUs -> {
            KeyframeSet transform = clip.getOverlayTransform();
            double ms = timeUs / 1000.0;
            float x = transform == null ? defaultX : transform.valueAt(KeyframeSet.X, ms, defaultX);
            float y = transform == null ? defaultY : transform.valueAt(KeyframeSet.Y, ms, defaultY);
            float scale = transform == null ? defaultScale : transform.valueAt(KeyframeSet.SCALE, ms, defaultScale);
            float rotation = transform == null ? 0f : transform.valueAt(KeyframeSet.ROTATION, ms, 0f);
            float[] size = PipPlacement.fitSize(frameWidth, frameHeight, sourceWidth,
                    sourceHeight, Math.max(0.01f, scale));
            float[] matrix = PipPlacement.quadMatrix(frameWidth, frameHeight,
                    x * frameWidth, y * frameHeight, size[0], size[1], rotation);
            return fromThreePoints(
                    PipPlacement.cornerPixel(matrix, frameWidth, frameHeight, -1, 1),
                    PipPlacement.cornerPixel(matrix, frameWidth, frameHeight, 1, 1),
                    PipPlacement.cornerPixel(matrix, frameWidth, frameHeight, -1, -1));
        };
    }

    /** The piece adapter supplies the clip's position on the editor clock, including its trim. */
    public static ItemMotionSampler spine(Clip clip, long clipStartUs, int frameWidth, int frameHeight) {
        return timeUs -> {
            float[] pose = new float[SpineTransform.POSE];
            float[] matrix = new float[9];
            clip.spinePoseAt((timeUs - clipStartUs) / 1000.0, pose);
            if (!SpineTransform.forward(pose, frameWidth / (float) frameHeight, matrix)) {
                return new ItemMotionSampler.Affine(frameWidth, 0, 0, frameHeight, 0, 0);
            }
            return fromThreePoints(spineCorner(matrix, -1, 1, frameWidth, frameHeight),
                    spineCorner(matrix, 1, 1, frameWidth, frameHeight),
                    spineCorner(matrix, -1, -1, frameWidth, frameHeight));
        };
    }

    private static ItemMotionSampler.Affine quad(float[] pose, int frameWidth, int frameHeight,
                                                 float width, float height, float mirrorX, float mirrorY,
                                                 float pivotX, float pivotY) {
        float[] corners = new float[8];
        TransformQuad.renderQuad(pose[0] * frameWidth, pose[1] * frameHeight, width, height,
                null, pose[3], mirrorX, mirrorY, pivotX, pivotY, corners);
        return ItemMotionSampler.Affine.fromQuad(corners);
    }

    private static float[] spineCorner(float[] matrix, float x, float y, int width, int height) {
        float[] p = new float[2];
        SpineTransform.apply(matrix, x, y, p);
        p[0] = (p[0] + 1) * width / 2;
        p[1] = (1 - p[1]) * height / 2;
        return p;
    }

    private static ItemMotionSampler.Affine fromThreePoints(float[] tl, float[] tr, float[] bl) {
        return new ItemMotionSampler.Affine(tr[0] - tl[0], bl[0] - tl[0],
                tr[1] - tl[1], bl[1] - tl[1], tl[0], tl[1]);
    }
}
