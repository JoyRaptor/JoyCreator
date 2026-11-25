package com.fadcam.ui.faditor.motion.flow;

/** Proves requested timestamps can never silently masquerade as decoded timestamps. */
public final class ExactDecodedLumaTest {
    public static void main(String[] args) throws Exception {
        LumaImage image = new LumaImage(2, 1, new float[]{.25f, .75f});
        var exact = ExactDecodedLuma.adapter((requested, width, height) ->
                new ExactDecodedLuma.Frame(33_000, image));
        check(exact.decode(33_000, 2, 1) == image, "exact decoded frame is borrowed without a copy");
        reject(() -> exact.decode(34_000, 2, 1), "decoded PTS cannot be relabelled as the request");
        reject(() -> exact.decode(33_000, 1, 1), "decoded shape must match the cache key");
        var absent = ExactDecodedLuma.adapter((requested, width, height) -> null);
        reject(() -> absent.decode(33_000, 2, 1), "missing decoded output is not a black frame");
        System.out.println("ExactDecodedLumaTest: PASS");
    }

    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action, String message) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
