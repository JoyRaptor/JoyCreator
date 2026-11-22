package com.fadcam.ui.faditor.avatar.face;

/**
 * FACE-1: the camera's sideways picture to the upright one. A phone's front camera delivers its
 * picture turned (90° or 270° on a phone held upright); the face model is told the turn and reads
 * the face upright, but it hands its 478 points back in the camera's own sideways frame. Every
 * point that is drawn or measured (the preview's outline, where the face sits for lean) goes
 * through here first, turned exactly as the preview's picture is turned (clockwise by the camera's
 * rotation, as {@code Matrix.postRotate} turns it). Found by the owner on the Note 9, 11-22: the
 * outline sat a quarter turn off his face.
 */
public final class FaceFrame {

    private FaceFrame() {}

    /** A 0..1 point of the camera's picture, in the picture turned clockwise by {@code rotation}. */
    public static float[] upright(float x, float y, int rotation) {
        switch (((rotation % 360) + 360) % 360) {
            case 90:  return new float[]{1f - y, x};
            case 180: return new float[]{1f - x, 1f - y};
            case 270: return new float[]{y, 1f - x};
            default:  return new float[]{x, y};
        }
    }
}
