package com.fadcam.ui.faditor.motion;

/** Maps a rigid item's unit texture quad into top-left-origin canvas pixels. */
@FunctionalInterface
public interface ItemMotionSampler {
    Affine at(long editorTimeUs);

    record Affine(double xx, double xy, double yx, double yy, double tx, double ty) {
        public static Affine fromQuad(float[] q) {
            return new Affine(q[2] - q[0], q[6] - q[0],
                    q[3] - q[1], q[7] - q[1], q[0], q[1]);
        }

        public double x(double u, double v) { return xx * u + xy * v + tx; }
        public double y(double u, double v) { return yx * u + yy * v + ty; }
    }
}
