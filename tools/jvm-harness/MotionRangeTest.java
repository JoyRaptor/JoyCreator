import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * Pins the motion range to the object's own span.
 *
 * Found in JoyRaptor's project 2026-08-19: a text box trimmed to end at 663891 still carried a motion
 * range ending at 671793 — 7.9 seconds past its own end. The motion range is stored in absolute
 * project time and is written by different controls from the ones that set the object's span, so
 * nothing kept them consistent.
 *
 * It matters because motionSpanMs is the denominator unitProgress divides by: a range hanging off
 * the end silently rescales the animation, so the entrance runs at the wrong speed and the exit
 * can sit in time that never plays.
 */
public class MotionRangeTest {
    static int pass = 0, fail = 0;

    /** TextOverlayItem has no no-arg constructor; this is the smallest real one. */
    static TextOverlayItem mk() {
        return new TextOverlayItem("t", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
    }

    static void check(String what, boolean ok) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL  " + what); }
    }

    static void eq(String what, long got, long expected) {
        check(what + " (got " + got + ", expected " + expected + ")", got == expected);
    }

    public static void main(String[] args) {
        // ── Trimming the object pulls the motion range in with it ────────────────────────
        TextOverlayItem a = mk();
        a.setTimeRange(646260, 675648);
        a.setMotionRange(646260, 675648);
        a.setTimeRange(646260, 663891);              // JoyRaptor's trim
        check("motion range survives a trim", a.hasMotionRange());
        eq("motion end clamped to the object's end", a.getMotionEndMs(), 663891);
        eq("motion start untouched", a.getMotionStartMs(), 646260);
        check("motion range now inside the span",
                a.getMotionStartMs() >= a.getStartMs() && a.getMotionEndMs() <= a.getEndMs());

        // ── Moving the object's start forward pushes the motion start with it ────────────
        TextOverlayItem b = mk();
        b.setTimeRange(1000, 9000);
        b.setMotionRange(2000, 8000);
        b.setTimeRange(5000, 9000);
        eq("motion start clamped up to the new start", b.getMotionStartMs(), 5000);
        eq("motion end left alone", b.getMotionEndMs(), 8000);

        // ── A range trimmed out of existence is CLEARED, not pinned to a sliver ──────────
        TextOverlayItem c = mk();
        c.setTimeRange(0, 10000);
        c.setMotionRange(8000, 9500);
        c.setTimeRange(0, 4000);                     // the whole motion range is now past the end
        check("degenerate motion range is cleared", !c.hasMotionRange());
        // Cleared means "use the full span" — the documented default — not a 1ms flash.
        eq("falls back to the object's start", c.motionRangeStartMs(), 0);
        eq("falls back to the object's end", c.motionRangeEndMs(4000), 4000);

        // ── Writing a motion range from outside the object is clamped on the way IN ──────
        TextOverlayItem d = mk();
        d.setTimeRange(1000, 5000);
        d.setMotionRange(0, 20000);                  // playhead parked outside the object
        eq("write clamped to span start", d.getMotionStartMs(), 1000);
        eq("write clamped to span end", d.getMotionEndMs(), 5000);

        // ── An open-ended object (endMs == MAX) does not clamp the end ───────────────────
        TextOverlayItem e = mk();
        e.setTimeRange(1000, 0);                     // degenerate end -> "runs to the end"
        check("open-ended object", e.getEndMs() == Long.MAX_VALUE);
        e.setMotionRange(2000, 30000);
        eq("open end leaves the motion end alone", e.getMotionEndMs(), 30000);

        // ── Objects that never touch the controls are unaffected ────────────────────────
        TextOverlayItem f = mk();
        f.setTimeRange(0, 5000);
        check("no motion range by default", !f.hasMotionRange());
        f.setTimeRange(0, 3000);
        check("still none after a trim", !f.hasMotionRange());

        // ── "None" keeps the timing, and stored timing is not the same as animating ──────
        // JoyRaptor, 2026-08-21: trying None is part of comparing options, not a decision to throw
        // away timings you plotted out. An earlier fix zeroed them here and that was silent
        // data loss. Keeping them is safe because the renderer gates on the preset NAME.
        TextOverlayItem g = mk();
        g.setTimeRange(0, 10000);
        g.setTextAnimPreset("RISE");
        g.setTextAnimZonePct(0.20f, 0.30f);
        check("a real preset with zones is active", g.isTextAnimActive());

        g.setTextAnimPreset("NONE");
        eq("None keeps the entrance timing", Math.round(g.getTextAnimInPct() * 1000), 200);
        eq("None keeps the exit timing", Math.round(g.getTextAnimOutPct() * 1000), 300);
        check("None still reports stored timing", g.hasTextAnim());
        check("None is NOT active", !g.isTextAnimActive());

        g.setTextAnimPreset("SCRAMBLE");
        eq("switching back keeps the entrance", Math.round(g.getTextAnimInPct() * 1000), 200);
        eq("switching back keeps the exit", Math.round(g.getTextAnimOutPct() * 1000), 300);
        check("active again without re-plotting", g.isTextAnimActive());

        // A box that never had timing is not "active" just because a preset name is set.
        TextOverlayItem h = mk();
        h.setTimeRange(0, 5000);
        h.setTextAnimPreset("RISE");
        check("preset with no zones is not active", !h.isTextAnimActive());

        System.out.println((fail == 0 ? "MOTIONRANGE OK" : "MOTIONRANGE FAILED")
                + " — " + pass + " passed, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }
}
