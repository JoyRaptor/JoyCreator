import com.fadcam.ui.faditor.motion.PreviewMotionClock;

/** Presentation cadence and decoder-to-texture pairing for the live shutter. */
public final class PreviewMotionClockTest {
    public static void main(String[] args) {
        boolean releaseClock = args.length > 0 && args[0].equals("release-clock");
        PreviewMotionClock clock = new PreviewMotionClock();
        long generation = clock.reset();
        callback(clock, generation, 2_000_000_000L, 100_000L, releaseClock);
        expect(clock.latch(2_000_000_000L), 100_000L, 0, "first texture");
        callback(clock, generation, 2_046_000_000L, 116_667L, releaseClock);
        callback(clock, generation, 2_065_000_000L, 133_334L, releaseClock);
        expect(clock.latch(2_065_000_000L), 133_334L, 16_667L,
                "use presentation cadence after a skipped texture");
        if (clock.latch(2_046_000_000L) != null) throw new AssertionError("retired texture");
        if (clock.latch(2_075_000_000L) != null) throw new AssertionError("unmatched texture");
        expect(clock.latch(2_065_000_000L), 133_334L, 16_667L, "paused redraw");
        callback(clock, generation, 2_079_000_000L, 150_000L, releaseClock);
        callback(clock, generation, 2_079_000_000L, 150_000L, releaseClock);
        expect(clock.latch(2_079_000_000L), 150_000L, 16_666L, "duplicate delivery");

        long seekGeneration = clock.reset();
        callback(clock, generation, 2_086_000_000L, 170_000L, releaseClock);
        if (clock.latch(2_086_000_000L) != null) throw new AssertionError("stale listener");
        if (clock.latch(2_079_000_000L) != null) throw new AssertionError("stale texture");
        callback(clock, seekGeneration, 2_100_000_000L, 3_450_000L, releaseClock);
        expect(clock.latch(2_100_000_000L), 3_450_000L, 0L, "first texture after seek");
        for (int i = 0; i < 35; i++) {
            callback(clock, seekGeneration, 2_110_000_000L + i, 3_466_667L + i * 16_667L, releaseClock);
        }
        if (clock.latch(2_110_000_000L) != null) throw new AssertionError("bounded queue");
        expect(clock.latch(2_110_000_034L), 4_033_345L, 16_667L, "newest retained texture");
        System.out.println("PASS PreviewMotionClockTest");
    }

    private static void callback(PreviewMotionClock clock, long generation, long releaseNs,
                                 long editorUs, boolean releaseClock) {
        long time = releaseClock && editorUs > 100_000L
                ? 100_000L + (releaseNs - 2_000_000_000L) / 1000 : editorUs;
        clock.onFrame(generation, releaseNs, time);
    }

    private static void expect(PreviewMotionClock.Frame actual, long pts, long interval, String label) {
        if (actual == null || actual.editorTimeUs() != pts || actual.periodUs() != interval) {
            throw new AssertionError(label + ": " + actual + ", expected " + pts + ", " + interval);
        }
    }
}
