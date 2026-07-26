package com.fadcam.ui.faditor.model;

/**
 * SPEC_TIMER_OBJECT: the recipe that turns an ordinary text overlay into a live
 * clock/timer. A {@code TextOverlayItem} carrying a non-null spec renders a computed
 * time string instead of its authored {@code text}; everything else about it — font,
 * colour, stroke, shadow, glow, background, position, scale, rotation, opacity,
 * keyframes — is untouched, so a timer inherits the caption styling for free.
 *
 * <p><b>Deliberately Android-free</b> (plain enums + primitives, no imports) so the whole
 * timer rule set compiles and runs off-device in {@code tools/jvm-harness} alongside
 * {@link TimerText}. That is the same trick {@code TransitionIndex} uses, and it is what
 * makes the awkward cases (unbounded tape, countdown rounding, frame carry) testable
 * without a phone.</p>
 */
public final class TimerSpec {

    /** Which way the number moves. */
    public enum Direction { COUNT_DOWN, COUNT_UP }

    /**
     * What the number is measured against.
     *
     * <p>{@link #RELATIVE} = the TAPE: the overlay's own trimmed span on the timeline.
     * Trim a timer in at 5s and out at 10s and a countdown reads 5 at the in-point and
     * 0 at the out-point, wherever on the timeline that span happens to sit.</p>
     *
     * <p>{@link #ABSOLUTE} = the PROJECT: count-up shows the playhead's own timeline
     * position, count-down shows the project's remaining time.</p>
     */
    public enum Basis { RELATIVE, ABSOLUTE }

    /** Sub-second resolution appended after seconds. */
    public enum Precision { NONE, FRAMES, MILLIS }

    private Direction direction = Direction.COUNT_DOWN;
    private Basis basis = Basis.RELATIVE;
    private boolean showHours = false;
    private boolean showMinutes = true;
    private boolean showSeconds = true;
    private Precision precision = Precision.NONE;

    public TimerSpec() { }

    public TimerSpec(Direction direction, Basis basis,
            boolean showHours, boolean showMinutes, boolean showSeconds, Precision precision) {
        this.direction = direction == null ? Direction.COUNT_DOWN : direction;
        this.basis = basis == null ? Basis.RELATIVE : basis;
        this.showHours = showHours;
        this.showMinutes = showMinutes;
        this.showSeconds = showSeconds;
        this.precision = precision == null ? Precision.NONE : precision;
    }

    /** Deep copy — a spec is mutable, so duplicating an item must not share one. */
    public TimerSpec copy() {
        return new TimerSpec(direction, basis, showHours, showMinutes, showSeconds, precision);
    }

    public Direction getDirection() { return direction; }
    public void setDirection(Direction d) { if (d != null) this.direction = d; }

    public Basis getBasis() { return basis; }
    public void setBasis(Basis b) { if (b != null) this.basis = b; }

    public boolean isShowHours() { return showHours; }
    public void setShowHours(boolean v) { this.showHours = v; }

    public boolean isShowMinutes() { return showMinutes; }
    public void setShowMinutes(boolean v) { this.showMinutes = v; }

    public boolean isShowSeconds() { return showSeconds; }
    public void setShowSeconds(boolean v) { this.showSeconds = v; }

    public Precision getPrecision() { return precision; }
    public void setPrecision(Precision p) { if (p != null) this.precision = p; }

    /**
     * True when at least one field would be printed. The UI must not be able to reach
     * this state (the field toggles refuse to clear the last one), but a hand-edited or
     * future-schema project can, and {@link TimerText} falls back rather than rendering
     * an empty overlay that looks like a bug.
     */
    public boolean hasAnyField() {
        return showHours || showMinutes || showSeconds || precision != Precision.NONE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TimerSpec)) return false;
        TimerSpec t = (TimerSpec) o;
        return showHours == t.showHours && showMinutes == t.showMinutes
                && showSeconds == t.showSeconds && direction == t.direction
                && basis == t.basis && precision == t.precision;
    }

    @Override
    public int hashCode() {
        int r = direction.hashCode();
        r = 31 * r + basis.hashCode();
        r = 31 * r + precision.hashCode();
        r = 31 * r + (showHours ? 1 : 0);
        r = 31 * r + (showMinutes ? 1 : 0);
        r = 31 * r + (showSeconds ? 1 : 0);
        return r;
    }

    @Override
    public String toString() {
        return "TimerSpec{" + direction + "," + basis + ",H=" + showHours + ",M=" + showMinutes
                + ",S=" + showSeconds + "," + precision + "}";
    }
}
