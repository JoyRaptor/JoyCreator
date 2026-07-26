package com.fadcam.ui.faditor.model;

/**
 * SPEC_TIMER_OBJECT: the ONE AUTHORITY that turns a {@link TimerSpec} plus a playhead
 * position into the string a timer overlay displays.
 *
 * <p>Preview and export both call this and nothing else, for the same reason
 * {@code LayerPreviewController.effectiveOverlayVolume} is shared: two implementations of
 * "what does this read right now" is two implementations that will disagree, and a
 * preview/export divergence in a COUNTDOWN is invisible until someone exports the finished
 * video and finds the numbers land differently.</p>
 *
 * <p><b>Android-free by construction</b> — primitives in, {@code String} out — so every rule
 * below is pinned by {@code tools/jvm-harness/TimerTextTest.java} without a device.</p>
 */
public final class TimerText {

    /** Export writes 30fps in every path (ExportManager setFrameRate(30)); FRAMES follows it. */
    public static final int DEFAULT_FPS = 30;

    private TimerText() { }

    /**
     * The displayed string for a timer overlay, or {@code null} when {@code spec} is null
     * (i.e. an ordinary text overlay — the caller keeps its authored text).
     *
     * @param timelineMs        playhead position on the project timeline
     * @param startMs           the overlay's trim-IN edge (tape start)
     * @param endMs             the overlay's trim-OUT edge; may be {@code Long.MAX_VALUE}
     *                          for an unbounded overlay, which is the model DEFAULT
     * @param projectDurationMs total project duration; also the fallback out-point
     * @param fps               frame rate for {@link TimerSpec.Precision#FRAMES}
     */
    public static String format(TimerSpec spec, long timelineMs, long startMs, long endMs,
            long projectDurationMs, int fps) {
        if (spec == null) return null;
        long value = valueMs(spec, timelineMs, startMs, endMs, projectDurationMs);
        return render(spec, value, fps);
    }

    /**
     * The raw millisecond quantity a timer is displaying, before formatting — split out
     * so the harness can assert the arithmetic separately from the digits.
     *
     * <p>RELATIVE is measured against the TAPE. The out-point needs care: a text overlay's
     * {@code endMs} DEFAULTS to {@code Long.MAX_VALUE}, so a freshly-added timer has no
     * meaningful span and {@code end - start} would overflow into nonsense. An end at or
     * past the project's own end is therefore treated as ending WITH the project, which is
     * both overflow-safe and the behaviour a user expects from an untrimmed overlay.</p>
     */
    public static long valueMs(TimerSpec spec, long timelineMs, long startMs, long endMs,
            long projectDurationMs) {
        if (spec == null) return 0L;
        if (spec.getBasis() == TimerSpec.Basis.ABSOLUTE) {
            if (spec.getDirection() == TimerSpec.Direction.COUNT_UP) {
                return Math.max(0L, timelineMs);
            }
            return Math.max(0L, projectDurationMs - timelineMs);
        }
        long start = Math.max(0L, startMs);
        long end = effectiveEndMs(start, endMs, projectDurationMs);
        long span = Math.max(0L, end - start);
        long elapsed = Math.max(0L, Math.min(span, timelineMs - start));
        return spec.getDirection() == TimerSpec.Direction.COUNT_UP ? elapsed : span - elapsed;
    }

    /**
     * Resolve a tape's out-point. Unbounded ({@code Long.MAX_VALUE}), negative, or simply
     * running past the end of the project all collapse to "ends with the project"; if the
     * project has no duration either, the span degenerates to zero rather than overflowing.
     */
    public static long effectiveEndMs(long startMs, long endMs, long projectDurationMs) {
        if (endMs < 0L) return projectDurationMs > 0L ? projectDurationMs : startMs;
        if (projectDurationMs > 0L && endMs > projectDurationMs) return projectDurationMs;
        if (endMs == Long.MAX_VALUE) return projectDurationMs > 0L ? projectDurationMs : startMs;
        return endMs;
    }

    /** Format a non-negative millisecond value per the spec's field selection. */
    public static String render(TimerSpec spec, long valueMs, int fps) {
        if (spec == null) return null;
        long v = Math.max(0L, valueMs);
        int rate = fps > 0 ? fps : DEFAULT_FPS;

        boolean h = spec.isShowHours();
        boolean m = spec.isShowMinutes();
        boolean s = spec.isShowSeconds();
        TimerSpec.Precision p = spec.getPrecision();
        // A spec with nothing selected can only arrive from hand-edited or newer-schema
        // JSON (the UI keeps at least one field on). Render seconds rather than "".
        if (!h && !m && !s && p == TimerSpec.Precision.NONE) {
            s = true;
        }

        // A countdown with no sub-second field must show the FULL value for the whole of
        // its first display tick and reach zero exactly at the out-point, so it rounds UP
        // to the coarsest visible unit: a 5s tape reads 5,4,3,2,1,0 — not 4 the instant
        // one millisecond has passed. Count-up floors for the mirror-image reason (it must
        // read 0, not 1, at the in-point). With FRAMES/MILLIS the sub-second digits are
        // visible, so both directions truncate and rounding would be wrong.
        if (p == TimerSpec.Precision.NONE
                && spec.getDirection() == TimerSpec.Direction.COUNT_DOWN) {
            long unit = s ? 1000L : m ? 60_000L : h ? 3_600_000L : 1000L;
            v = ceilTo(v, unit);
        }

        // PADDING: the LEADING field is unpadded, every following one is zero-padded
        // (2 digits, 3 for millis) — the YouTube/stopwatch convention: "0:05", "1:02:00".
        // It also gives the flagship case its natural look: a seconds-only countdown reads
        // "5 4 3 2 1", not "05 04 03". Padding the leading field instead would force "05".
        StringBuilder sb = new StringBuilder();
        long rest = v;
        boolean first = true;
        // The LARGEST enabled field absorbs everything above it, so hiding hours turns
        // 1h02m into "62:00" rather than silently dropping an hour.
        if (h) {
            long hours = rest / 3_600_000L;
            rest %= 3_600_000L;
            sb.append(hours);
            first = false;
        }
        if (m) {
            long mins = rest / 60_000L;
            rest %= 60_000L;
            append(sb, mins, first ? 1 : 2, first ? "" : ":");
            first = false;
        }
        if (s) {
            long secs = rest / 1000L;
            rest %= 1000L;
            append(sb, secs, first ? 1 : 2, first ? "" : ":");
            first = false;
        } else {
            rest %= 1000L;
        }
        if (p == TimerSpec.Precision.MILLIS) {
            append(sb, rest, 3, first ? "" : ".");
        } else if (p == TimerSpec.Precision.FRAMES) {
            long frames = rest * rate / 1000L;
            if (frames > rate - 1) frames = rate - 1; // never display a full second of frames
            append(sb, frames, 2, first ? "" : ".");
        }
        return sb.toString();
    }

    /** Round {@code v} up to the next whole {@code unit} (overflow-safe for sane inputs). */
    private static long ceilTo(long v, long unit) {
        if (unit <= 1L) return v;
        long r = v % unit;
        return r == 0L ? v : v + (unit - r);
    }

    private static void append(StringBuilder sb, long value, int width, String sep) {
        sb.append(sep);
        String digits = Long.toString(Math.max(0L, value));
        for (int i = digits.length(); i < width; i++) sb.append('0');
        sb.append(digits);
    }
}
