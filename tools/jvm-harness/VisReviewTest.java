import com.fadcam.vislab.audio.HitCounts;
import com.fadcam.vislab.room.VideoHeads;
import com.fadcam.vislab.studio.EarShelf;
import com.fadcam.vislab.studio.LookTicket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The 2026-11-01 VisLab review, findings V1, V3, V4's beat index and V5, against the pure parts
 * of each fix (the review's own checks, tasks/joybrush/reviews/CODEX_REVIEW_WAVE2_20261101.md).
 */
public class VisReviewTest {

    static int fails, passes;

    public static void main(String[] args) throws Exception {
        v1SlowFrames();
        v3Decoders();
        v4BeatIndex();
        v5Clocks();
        System.out.println("VisReviewTest: " + (fails == 0 ? "PASS" : "FAILURES: " + fails) + " (passed " + passes + ")");
        if (fails > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ V1

    static void v1SlowFrames() throws Exception {
        // A frame that takes longer than the old 2 s limit is simply waited for.
        LookTicket<String> slow = new LookTicket<>(s -> check("V1 not discarded", false));
        new Thread(() -> {
            nap(300);
            slow.finish("picture", false);
        }).start();
        LookTicket.Outcome o = slow.await(5_000, () -> false);
        check("V1 a slow frame arrives", o == LookTicket.Outcome.READY && "picture".equals(slow.take()));

        // Too slow: the wait ends in a failure, and the late picture is thrown away.
        AtomicInteger discarded = new AtomicInteger();
        LookTicket<String> late = new LookTicket<>(s -> discarded.incrementAndGet());
        CountDownLatch drawing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread gl = new Thread(() -> {
            if (!late.begin()) return;
            drawing.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
            late.finish("late", false);
        });
        gl.start();
        drawing.await();
        o = late.await(50, () -> false);
        check("V1 a stuck frame times out instead of returning nothing", o == LookTicket.Outcome.TIMED_OUT);
        release.countDown();
        gl.join();
        check("V1 the late picture is discarded, not left behind", discarded.get() == 1 && late.take() == null);

        // Frames nobody waits for any more are never drawn: no queue behind a stuck thread.
        int drawn = 0;
        LookTicket<String> queued = new LookTicket<>(s -> { });
        queued.await(1, () -> false);
        if (queued.begin()) drawn++;
        check("V1 an abandoned frame is skipped, not drawn", drawn == 0);

        // A cancelled export stops waiting at once.
        AtomicBoolean cancel = new AtomicBoolean();
        LookTicket<String> stuck = new LookTicket<>(s -> { });
        new Thread(() -> {
            nap(120);
            cancel.set(true);
        }).start();
        long t0 = System.nanoTime();
        o = stuck.await(60_000, cancel::get);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        check("V1 cancel ends a 60 s wait in " + ms + " ms", o == LookTicket.Outcome.CANCELLED && ms < 1_000);

        // A look that breaks part way is a failure; one that cannot draw at all is a null.
        LookTicket<String> broke = new LookTicket<>(s -> { });
        broke.finish(null, true);
        check("V1 a look breaking part way fails", broke.await(100, null) == LookTicket.Outcome.FAILED);
        LookTicket<String> cannot = new LookTicket<>(s -> { });
        cannot.finish(null, false);
        check("V1 a look that cannot draw answers null (the classic style stands in)",
                cannot.await(100, null) == LookTicket.Outcome.READY && cannot.take() == null);
    }

    // ------------------------------------------------------------------ V3

    /** A pretend decoder: counts how far it walks, and refuses to walk backward. */
    static final class FakeReader {
        long at = -1;
        boolean closed;

        void read(long us) {
            if (closed) check("V3 a closed reader was used", false);
            if (us < at) check("V3 a reader went backward", false);
            at = us;
        }
    }

    static final class Fakes implements VideoHeads.Readers<FakeReader> {
        int opened, open, maxOpen;

        @Override public FakeReader open() {
            opened++;
            open++;
            maxOpen = Math.max(maxOpen, open);
            return new FakeReader();
        }

        @Override public void close(FakeReader r) {
            r.closed = true;
            open--;
        }
    }

    /** One rendered frame: each layer asks for its moment of a video {@code lengthUs} long. */
    static void frame(VideoHeads<FakeReader> h, long us, long[] speeds, long lengthUs) {
        for (long s : speeds) {
            long want = lengthUs > 0 ? (us * s) % lengthUs : us * s;
            h.readerFor(want).read(want);
        }
    }

    static void v3Decoders() {
        long step = 33_333;
        Fakes f = new Fakes();
        VideoHeads<FakeReader> h = new VideoHeads<>(f);
        for (int i = 0; i < 20_000; i++) frame(h, i * step, new long[]{1, 2}, 0);
        check("V3 1x + 2x: " + f.opened + " decoders over 20000 frames (was 10000 + per frame)", f.opened <= 2);

        for (int frames : new int[]{5_000, 40_000}) {
            f = new Fakes();
            h = new VideoHeads<>(f);
            for (int i = 0; i < frames; i++) frame(h, i * step, new long[]{1, 2}, 0);
            check("V3 construction does not grow with frames (" + frames + " frames: " + f.opened + ")", f.opened <= 2);
        }

        f = new Fakes();
        h = new VideoHeads<>(f);
        for (int i = 0; i < 9_000; i++) frame(h, i * step, new long[]{1, 1, 1}, 0);
        check("V3 layers at one moment share one decoder (" + f.opened + ")", f.opened == 1);

        // Different offsets: two layers of the same speed, 3 s apart.
        f = new Fakes();
        h = new VideoHeads<>(f);
        for (int i = 0; i < 9_000; i++) {
            long us = i * step;
            h.readerFor(us).read(us);
            h.readerFor(us + 3_000_000).read(us + 3_000_000);
        }
        check("V3 same speed, 3 s apart: two decoders (" + f.opened + ")", f.opened <= 2);

        // Loops: a 2 s video at 1x and 2x. Each pass of a loop costs one decode, no more; and
        // decoders do not pile up.
        f = new Fakes();
        h = new VideoHeads<>(f);
        int frames = 9_000;   // 300 s: 150 passes at 1x, 300 at 2x
        for (int i = 0; i < frames; i++) frame(h, i * step, new long[]{1, 2}, 2_000_000);
        check("V3 loops: about one decode per pass (" + f.opened + " for 450 passes)", f.opened <= 460);
        check("V3 loops: decoders stay few (at most " + f.maxOpen + " open)", f.maxOpen <= 5);

        // One looping layer lets the old pass go.
        f = new Fakes();
        h = new VideoHeads<>(f);
        for (int i = 0; i < 3_000; i++) frame(h, i * step, new long[]{1}, 2_000_000);
        check("V3 one looping layer holds at most 2 decoders (" + f.maxOpen + ")", f.maxOpen <= 2);
        check("V3 the old pass is let go (" + f.open + " open)", f.open == 1);
        h.closeAll();
        check("V3 closeAll closes them", f.open == 0);

        // Past the cap: bounded, and never a backward read.
        f = new Fakes();
        h = new VideoHeads<>(f);
        for (int i = 0; i < 2_000; i++) frame(h, i * step, new long[]{1, 2, 3, 4, 5, 6, 7}, 0);
        check("V3 seven speeds of one video: never more than 5 open (" + f.maxOpen + ")", f.maxOpen <= 5);

        // A reader that failed is replaced.
        f = new Fakes();
        h = new VideoHeads<>(f);
        FakeReader bad = h.readerFor(0);
        h.drop(bad);
        check("V3 a failed reader is closed", bad.closed && f.open == 0);
        check("V3 ... and replaced", h.readerFor(0) != bad);
    }

    // ------------------------------------------------------------------ V4's beat index

    static void v4BeatIndex() {
        int rate = 22050, fft = 2048;
        double step = 1.0 / 60.0, seconds = 30;
        int frames = (int) (seconds * rate);
        float[] pcm = kickTrack(frames, rate, 120);
        long t0 = System.nanoTime();
        HitCounts h = HitCounts.scan(i -> pcm[i], frames, rate, fft, step, () -> false);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("  (30 s of sound heard in " + ms + " ms on this PC)");

        long kicks = h.kicksAt(seconds), beats = h.beatsAt(seconds);
        check("V4 index: 60 kicks of a 30 s 120 bpm track are found (" + kicks + ")", kicks >= 55 && kicks <= 62);
        check("V4 index: beats are counted (" + beats + ")", beats >= 45 && beats <= 75);
        boolean rising = true;
        long last = 0;
        for (double t = 0; t < seconds; t += 0.25) {
            long k = h.kicksAt(t);
            if (k < last) rising = false;
            last = k;
        }
        check("V4 index: counts only ever rise with time", rising);
        check("V4 index: nothing has landed at the start", h.kicksAt(0) == 0 && h.beatsAt(0) == 0);

        HitCounts again = HitCounts.scan(i -> pcm[i], frames, rate, fft, step, () -> false);
        boolean same = true;
        java.util.Random r = new java.util.Random(4);
        for (int i = 0; i < 5000; i++) {
            double t = r.nextDouble() * seconds;
            if (h.kicksAt(t) != again.kicksAt(t) || h.beatsAt(t) != again.beatsAt(t)) same = false;
        }
        check("V4 index: any moment gives one answer, every time", same);
        check("V4 index: stops when told", HitCounts.scan(i -> 0f, frames, rate, fft, step, () -> true) == null);
    }

    /** Low thumps at {@code bpm}: 60 Hz, 80 ms, over nothing. */
    static float[] kickTrack(int frames, int rate, int bpm) {
        float[] pcm = new float[frames];
        int every = rate * 60 / bpm, len = rate * 80 / 1000;
        for (int k = 0; k + len < frames; k += every) {
            for (int i = 0; i < len; i++) {
                float env = (float) Math.exp(-6.0 * i / len);
                pcm[k + i] = env * (float) Math.sin(2 * Math.PI * 60 * i / rate);
            }
        }
        return pcm;
    }

    // ------------------------------------------------------------------ V5

    /** The cost of a SoundClock: 133 analysis steps when new or thrown back, 2 going forward. */
    static final class FakeClock {
        long at = -1;
        int[] steps;

        FakeClock(int[] steps) {
            this.steps = steps;
        }

        EarShelf.Heard hear(long ms) {
            if (at < 0 || ms < at || ms - at > 500) steps[0] += 133;
            else steps[0] += 2;
            at = ms;
            return new EarShelf.Heard(new float[6], true);
        }
    }

    /** Analysis steps per frame for {@code effects} effects, keys made by {@code key}. */
    static int perFrame(int effects, java.util.function.IntFunction<String> key, int behind) {
        int[] steps = {0};
        EarShelf<FakeClock> shelf = new EarShelf<>(8);
        int frames = 300;
        for (int f = 0; f < frames; f++) {
            for (int e = 0; e < effects; e++) {
                // Further down the chain an effect can still be on an earlier frame.
                long ms = Math.max(0, f - (e % (behind + 1))) * 33L + 10_000;
                shelf.heard(key.apply(e), ms, () -> new FakeClock(steps), c -> c.hear(ms));
            }
        }
        return steps[0] / frames;
    }

    static void v5Clocks() {
        int nine = perFrame(9, e -> "export:layer" + e, 0);
        int eight = perFrame(8, e -> "export:layer" + e, 0);
        check("V5 confirmed: nine owners cost " + nine + " steps a frame, eight " + eight, nine > 1000 && eight < 20);

        for (int effects : new int[]{1, 8, 9, 30}) {
            int cost = perFrame(effects, e -> "export:part1", 0);
            check("V5 " + effects + " effects in one walk: " + cost + " steps a frame", cost <= 3);
        }
        int inFlight = perFrame(9, e -> "export:part1", 3);
        check("V5 an effect a few frames behind costs nothing extra (" + inFlight + ")", inFlight <= 3);

        // The preview and an export side by side never share a clock.
        int[] steps = {0};
        EarShelf<FakeClock> shelf = new EarShelf<>(8);
        List<FakeClock> made = new ArrayList<>();
        for (int f = 0; f < 300; f++) {
            long preview = 60_000L + f * 16, export = f * 33L;
            shelf.heard("preview|a", preview, () -> {
                FakeClock c = new FakeClock(steps);
                made.add(c);
                return c;
            }, c -> c.hear(preview));
            for (int e = 0; e < 9; e++) {
                shelf.heard("export:1|a", export, () -> {
                    FakeClock c = new FakeClock(steps);
                    made.add(c);
                    return c;
                }, c -> c.hear(export));
            }
        }
        check("V5 preview + export: two clocks (" + made.size() + "), " + steps[0] / 300 + " steps a frame",
                made.size() == 2 && steps[0] / 300 <= 6);

        // A deaf clock is not remembered: the sound can arrive while the playhead rests.
        int[] asks = {0};
        EarShelf<FakeClock> quiet = new EarShelf<>(8);
        for (int i = 0; i < 3; i++) {
            quiet.heard("preview|a", 5000, () -> new FakeClock(new int[1]), c -> {
                asks[0]++;
                return new EarShelf.Heard(new float[6], false);
            });
        }
        check("V5 silence before the sound copy exists is asked again", asks[0] == 3);
        // Nearby frames at 60 fps are different moments.
        int[] heard = {0};
        EarShelf<FakeClock> fine = new EarShelf<>(8);
        for (long ms : new long[]{1000, 1016, 1033, 1050}) {
            fine.heard("p|a", ms, () -> new FakeClock(new int[1]), c -> {
                heard[0]++;
                return new EarShelf.Heard(new float[6], true);
            });
        }
        check("V5 60 fps frames each hear their own moment", heard[0] == 4);
    }

    static void nap(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    static void check(String what, boolean ok) {
        if (ok) passes++;
        else fails++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
