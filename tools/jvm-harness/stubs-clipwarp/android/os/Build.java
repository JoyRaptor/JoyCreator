package android.os;

/**
 * SPEC-ZB-scoped shadow of the shared harness {@code Build} stub (run-clipwarp.sh ONLY).
 * Identical plus {@code O_MR1}, which the {@code ProjectStorage} compile closure needs
 * ({@code SlideRenderActivity}). Same 0-SDK discipline as the original.
 */
public final class Build {

    private Build() {}

    public static final class VERSION {
        private VERSION() {}
        /** See the shared stub: 0, the most conservative tier. */
        public static final int SDK_INT = 0;
    }

    public static final class VERSION_CODES {
        private VERSION_CODES() {}
        public static final int O_MR1 = 27;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
    }
}
