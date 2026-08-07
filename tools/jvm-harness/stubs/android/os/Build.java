package android.os;

/**
 * Harness stub for {@code android.os.Build}, following the {@code SystemClock} precedent
 * already in this directory.
 *
 * <p>{@code FxPreviewTier} is otherwise android-free: its whole decision table is
 * {@code of(int sdkInt)}, a pure function, and that is what the harness actually tests. Only the
 * {@code current()} convenience reads {@code SDK_INT}, and a class that could not LOAD because of
 * one field reference would have forced the tier table out of the {@code fx} package and away
 * from the code that owns it.</p>
 *
 * <p><b>0 deliberately.</b> A harness that silently reported a modern SDK would let a test pass
 * for the wrong reason; 0 maps to {@code EXPORT_ONLY}, the most conservative answer, so anything
 * calling {@code current()} off-device gets the tier that promises least. Every tier assertion
 * in the harness calls {@code of(int)} explicitly instead.</p>
 */
public final class Build {

    private Build() {}

    public static final class VERSION {
        private VERSION() {}
        /** See the class note: 0, so {@code current()} is the most conservative tier. */
        public static final int SDK_INT = 0;
    }

    public static final class VERSION_CODES {
        private VERSION_CODES() {}
        public static final int S = 31;
        public static final int TIRAMISU = 33;
    }
}
