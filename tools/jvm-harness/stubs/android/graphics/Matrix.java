package android.graphics;

/**
 * Harness stub: just enough of android.graphics.Matrix for the Studio model to compile
 * (CornerPin.buildMatrix, TextOverlayItem.cornerPinMatrix). It records nothing: suites that
 * use this stub do not test the corner-pin homography (that lives in on-device renderers).
 */
public class Matrix {
    public Matrix() {}

    public void reset() {}

    public boolean setPolyToPoly(float[] src, int srcIndex, float[] dst, int dstIndex, int pointCount) {
        return true;
    }

    public void mapPoints(float[] pts) {}

    public boolean invert(Matrix inverse) { return true; }
}
