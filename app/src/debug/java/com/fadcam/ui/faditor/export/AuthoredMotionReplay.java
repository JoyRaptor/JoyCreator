package com.fadcam.ui.faditor.export;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

/** Sprite placements corresponding to the independent CPU motion-reference scenes. */
public final class AuthoredMotionReplay {
    public static final int WIDTH = 96;
    public static final int HEIGHT = 80;
    public static final int ART_WIDTH = 20;
    public static final int ART_HEIGHT = 8;
    public static final int CENTER_MS = 120;
    private static final int HALF_EXPOSURE_MS = 10;

    private AuthoredMotionReplay() {}

    public static SpriteOverlayItem sprite(String scene, String sheetId, boolean blur) {
        var sprite = SpriteOverlayItem.create(sheetId);
        sprite.setCenter(0.5f, 0.5f);
        sprite.setTimeRange(0, 1_000);
        sprite.setSizeFraction((float) ART_HEIGHT / HEIGHT);
        sprite.setRigidMotionBlurEnabled(blur);
        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
        switch (scene) {
            case "slide-slow" -> slide(sprite, 2);
            case "slide-fast" -> slide(sprite, 19);
            case "zoom" -> keyExposure(sprite, KeyframeSet.SCALE,
                    (float) ART_HEIGHT / HEIGHT, (float) (3 * ART_HEIGHT) / HEIGHT);
            case "spin" -> keyExposure(sprite, KeyframeSet.ROTATION, -90f, 90f);
            case "teleport" -> {
                var x = sprite.getKeyframes().getOrCreate(KeyframeSet.X);
                x.put(0, 18f / WIDTH, Easing.HOLD);
                x.put(CENTER_MS, 68f / WIDTH, Easing.LINEAR);
            }
            case "still" -> { }
            default -> throw new IllegalArgumentException("Unknown authored motion reference: " + scene);
        }
        return sprite;
    }

    private static void slide(SpriteOverlayItem sprite, float distance) {
        keyExposure(sprite, KeyframeSet.X, 0.5f - distance / WIDTH, 0.5f + distance / WIDTH);
    }

    private static void keyExposure(SpriteOverlayItem sprite, String property, float open, float close) {
        var track = sprite.getKeyframes().getOrCreate(property);
        track.put(CENTER_MS - HALF_EXPOSURE_MS, open, Easing.LINEAR);
        track.put(CENTER_MS + HALF_EXPOSURE_MS, close, Easing.LINEAR);
    }
}
