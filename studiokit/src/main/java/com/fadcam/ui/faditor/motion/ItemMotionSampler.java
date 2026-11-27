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

        /**
         * Write the affine entries of an identity-initialized column-major matrix. It
         * transforms a centred y-up quad into the same frame as this top-left pixel mapping.
         */
        public boolean writeClipTransform(float[] matrix, int width, int height) {
            if (width <= 0 || height <= 0) return false;
            matrix[0] = (float) (xx / width);
            matrix[1] = (float) (-yx / height);
            matrix[4] = (float) (-xy / width);
            matrix[5] = (float) (yy / height);
            matrix[12] = (float) ((xx + xy + 2 * tx) / width - 1);
            matrix[13] = (float) (1 - (yx + yy + 2 * ty) / height);
            return Float.isFinite(matrix[0]) && Float.isFinite(matrix[1])
                    && Float.isFinite(matrix[4]) && Float.isFinite(matrix[5])
                    && Float.isFinite(matrix[12]) && Float.isFinite(matrix[13]);
        }
    }
}
