import com.fadcam.ui.faditor.model.AudioCrossfade;

/**
 * B2.E — the cross-fade pill's model, off device.
 *
 * <p>Written because this class had ZERO test coverage while being BUILT and shipped: it is the
 * one piece of the pill a finger drags directly, and every one of its operations is geometry
 * that fails at a boundary rather than in the middle. A pill dragged to time zero, resized
 * through its own opposite edge, or asked for its progress one millisecond outside itself are
 * all things a real hand does within seconds of meeting it.</p>
 *
 * <p>The invariant that matters most: a resize can never invert the pill. Whatever the finger
 * does, start &lt; end and the duration never drops below {@code MIN_DURATION_MS} — because an
 * inverted or zero-width pill renders as nothing while still existing, which is the worst of
 * both (invisible and in the way).</p>
 */
public class CrossfadeTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static AudioCrossfade xf(long a, long b) {
        return new AudioCrossfade("lane-lower", a, b);
    }

    /** start < end and duration >= MIN, always. */
    static boolean sane(AudioCrossfade x) {
        return x.getStartMs() >= 0
                && x.getEndMs() > x.getStartMs()
                && x.getDurationMs() >= AudioCrossfade.MIN_DURATION_MS;
    }

    public static void main(String[] args) {
        final long MIN = AudioCrossfade.MIN_DURATION_MS;

        // ── 1. Construction ─────────────────────────────────────────────────────────
        AudioCrossfade x = xf(1000, 2000);
        check(x.getStartMs() == 1000 && x.getEndMs() == 2000, "constructed span is kept");
        check(x.getDurationMs() == 1000, "duration is end - start");
        check(sane(x), "a normal pill is sane");

        // A degenerate request must be widened, not accepted.
        AudioCrossfade z = xf(1000, 1000);
        check(sane(z), "a zero-width request is widened to the minimum (" + z.getDurationMs() + ")");
        AudioCrossfade inv = xf(2000, 1000);
        check(sane(inv), "an INVERTED request is repaired, not stored inverted ("
                + inv.getStartMs() + ".." + inv.getEndMs() + ")");

        // ── 2. moveTo preserves duration — the drag-the-middle gesture ──────────────
        AudioCrossfade m = xf(1000, 2000);
        m.moveTo(5000);
        check(m.getStartMs() == 5000 && m.getDurationMs() == 1000,
                "moveTo slides without resizing");
        m.moveTo(-9999);
        check(m.getStartMs() == 0, "dragged before zero, it stops AT zero");
        check(m.getDurationMs() == 1000,
                "and keeps its duration there (" + m.getDurationMs() + ")");
        check(sane(m), "still sane at the origin");

        // ── 3. setEdge — resize, and the inversion attempt ──────────────────────────
        AudioCrossfade e = xf(1000, 2000);
        e.setEdge(true, 1500);
        check(e.getStartMs() == 1500 && e.getEndMs() == 2000, "left edge resizes");
        e.setEdge(false, 2500);
        check(e.getEndMs() == 2500, "right edge resizes");

        // Drag the LEFT edge past the right edge. It must stop MIN short, not invert.
        AudioCrossfade li = xf(1000, 2000);
        li.setEdge(true, 999999);
        check(sane(li), "left edge dragged PAST the right does not invert");
        check(li.getDurationMs() == MIN,
                "it stops exactly MIN_DURATION_MS short (" + li.getDurationMs() + ")");

        // And the mirror: right edge dragged before the left.
        AudioCrossfade ri = xf(1000, 2000);
        ri.setEdge(false, -999999);
        check(sane(ri), "right edge dragged BEFORE the left does not invert");
        check(ri.getDurationMs() == MIN,
                "it also stops MIN short (" + ri.getDurationMs() + ")");

        // Left edge dragged negative clamps to zero rather than going negative.
        AudioCrossfade ln = xf(1000, 2000);
        ln.setEdge(true, -500);
        check(ln.getStartMs() == 0, "left edge cannot go before zero");
        check(sane(ln), "still sane after clamping to zero");

        // ── 4. contains / progressAt at the boundaries ─────────────────────────────
        AudioCrossfade c = xf(1000, 2000);
        check(!c.contains(999), "one ms before the pill is outside");
        check(c.contains(1000), "the start edge is inside");
        check(!c.contains(2001), "one ms past the end is outside");

        float p0 = c.progressAt(1000), pMid = c.progressAt(1500), p1 = c.progressAt(2000);
        System.out.println("      progress: start=" + p0 + " mid=" + pMid + " end=" + p1);
        check(Math.abs(p0) < 1e-4f, "progress at the start edge is 0");
        check(Math.abs(pMid - 0.5f) < 1e-3f, "progress at the midpoint is 0.5");
        check(Math.abs(p1 - 1f) < 1e-4f, "progress at the end edge is 1");

        // Outside the pill, progress must CLAMP rather than run off. An unclamped value here
        // drives a gain multiplier, so a negative or >1 result is audible, not cosmetic.
        float before = c.progressAt(0), after = c.progressAt(999999);
        System.out.println("      outside: before=" + before + " after=" + after);
        check(before >= 0f && before <= 1f, "progress before the pill stays in 0..1 (" + before + ")");
        check(after >= 0f && after <= 1f, "progress after the pill stays in 0..1 (" + after + ")");

        // A minimum-width pill must not divide by zero.
        AudioCrossfade tiny = xf(1000, 1000 + MIN);
        float tp = tiny.progressAt(1000 + MIN / 2);
        check(!Float.isNaN(tp) && !Float.isInfinite(tp),
                "a minimum-width pill yields a finite progress (" + tp + ")");

        // ── 5. Copy is independent ─────────────────────────────────────────────────
        AudioCrossfade src = xf(1000, 2000);
        src.setColorRgb(0x123456);
        src.setToLaneAbove(false);
        AudioCrossfade cp = new AudioCrossfade(src);
        check(cp.getStartMs() == 1000 && cp.getEndMs() == 2000, "copy keeps the span");
        check(cp.getColorRgb() == 0x123456, "copy keeps the colour");
        check(!cp.isToLaneAbove(), "copy keeps the direction");
        cp.moveTo(9000);
        check(src.getStartMs() == 1000,
                "moving the COPY does not move the original (aliasing guard)");

        // flipDirection is its own inverse.
        AudioCrossfade f = xf(1000, 2000);
        boolean was = f.isToLaneAbove();
        f.flipDirection();
        check(f.isToLaneAbove() != was, "flipDirection flips");
        f.flipDirection();
        check(f.isToLaneAbove() == was, "and flipping twice returns to the start");

        // ── NEGATIVE CONTROLS ──────────────────────────────────────────────────────
        // The clamps above are only meaningful if the un-clamped values would have been out
        // of range. Assert the raw arithmetic the class is protecting against.
        check(1000L - 999999L < 0, "NEGCTRL: the un-clamped left drag really is negative");
        check((0 - 1000) / 1000.0f < 0f,
                "NEGCTRL: un-clamped progress before the pill really is negative");
        check((999999 - 1000) / 1000.0f > 1f,
                "NEGCTRL: un-clamped progress after the pill really exceeds 1");
        check(MIN > 0, "NEGCTRL: MIN_DURATION_MS is a real floor, not zero");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
