package com.fadcam.ui.faditor.motion;

import java.util.Objects;

/**
 * The geometry of the Motion Blur card's shutter strip (STU-7 spec §K.1), with no Android in it.
 *
 * <p>The strip shows time around the playhead in shutter degrees: 0 is this frame, -360 the last
 * frame, +360 the next. The exposure window is {@code [phase, phase + angle]}. Dragging an end
 * moves that end, dragging the middle slides the window, and the result is turned back into
 * {@link MotionShutter.Controls}, naming the timing (centred / behind / ahead) whenever the
 * window lands on one, so a hand-placed 180° centred shutter is the Natural preset again.</p>
 *
 * <p>The view spans whole frames each side ({@link #viewFramesFor}): one frame each side for
 * every ordinary shutter, more when the window reaches further, so the window always fits.</p>
 */
public final class ShutterStripModel {

    /** Angles the ends snap to (§3): the film-camera stops, and the two-frame limit. */
    public static final int[] ANGLE_STOPS = {45, 90, 180, 270, 360, 720};

    /** The furthest the window may reach either side of the playhead today (two frames). */
    public static final int REACH = 720;

    /** Snap distance in degrees, per frame of view, so it feels the same at every zoom. */
    public static final double SNAP_PER_FRAME = 8.0;

    private ShutterStripModel() {}

    /** Whole frames shown each side of the playhead: at least 1, enough to hold the window. */
    public static int viewFramesFor(MotionShutter.Controls c) {
        double open = c.phase();
        double close = open + c.angle();
        double far = Math.max(Math.abs(open), Math.abs(close));
        return Math.max(1, (int) Math.ceil(far / 360.0 - 1e-9));
    }

    /**
     * The strip's view: whole frames shown before and after the playhead. A long trail ends at
     * this frame, so its view has nothing after the playhead (the mock's "8 frames back ...
     * this frame"); every camera shutter is shown the same number of frames each side.
     */
    public record View(int back, int ahead) {
        public double fraction(double deg) {
            return (deg + 360.0 * back) / (360.0 * (back + ahead));
        }
        public double degrees(double fraction) {
            return fraction * 360.0 * (back + ahead) - 360.0 * back;
        }
        /** The larger side, for snap distances. */
        public int frames() { return Math.max(back, ahead); }
    }

    public static View viewFor(MotionShutter.Controls c) {
        if (c.isLongTrail()) return new View((int) Math.ceil(c.angle() / 360.0 - 1e-9), 0);
        int n = viewFramesFor(c);
        return new View(n, n);
    }

    /** Degrees to a position in [0, 1] across a view of {@code frames} each side. */
    public static double fraction(double deg, int frames) {
        double span = 360.0 * frames;
        return (deg + span) / (2 * span);
    }

    /** A position in [0, 1] back to degrees. */
    public static double degrees(double fraction, int frames) {
        double span = 360.0 * frames;
        return fraction * 2 * span - span;
    }

    /** Which part of the strip a touch grabs. */
    public enum Grab { OPEN, CLOSE, MIDDLE }

    /**
     * What a touch at {@code deg} grabs: the nearer end when within {@code handleDeg} of it,
     * else the middle when inside the window, else the nearer end (so a tap beside a narrow
     * window still takes hold of it).
     */
    public static Grab grab(MotionShutter.Controls c, double deg, double handleDeg) {
        double open = c.phase();
        double close = open + c.angle();
        double dOpen = Math.abs(deg - open);
        double dClose = Math.abs(deg - close);
        if (Math.min(dOpen, dClose) <= handleDeg) return dOpen <= dClose ? Grab.OPEN : Grab.CLOSE;
        if (deg > open && deg < close) return Grab.MIDDLE;
        return dOpen <= dClose ? Grab.OPEN : Grab.CLOSE;
    }

    /**
     * Move the opening edge to {@code deg}; the closing edge stays. Pulled further back than the
     * last frame, the window becomes a long trail ending at this frame (STU-7 §K.2), up to
     * {@link MotionShutter#MAX_TRAIL_ANGLE}, in whole quarter frames.
     */
    public static MotionShutter.Controls dragOpen(MotionShutter.Controls c, double deg, int frames) {
        if (deg < -REACH - 1e-9) {
            int angle = (int) Math.round(Math.min(MotionShutter.MAX_TRAIL_ANGLE, -deg) / 90.0) * 90;
            if (angle > REACH) return c.withWindow(angle, MotionShutter.Timing.TRAILING, 0);
        }
        double close = c.isLongTrail() ? 0 : c.phase() + c.angle();
        double open = clamp(deg, Math.max(-REACH, close - REACH), close);
        int angle = snapAngle((int) Math.round(close - open), frames);
        return fromWindow(c, close - angle, angle, frames);
    }

    /** Move the closing edge to {@code deg}; the opening edge stays. */
    public static MotionShutter.Controls dragClose(MotionShutter.Controls c, double deg, int frames) {
        if (c.isLongTrail()) return c;   // a long trail always ends at this frame
        double open = c.phase();
        double close = clamp(deg, open, Math.min(REACH, open + REACH));
        int angle = snapAngle((int) Math.round(close - open), frames);
        return fromWindow(c, open, angle, frames);
    }

    /** Slide the whole window by {@code deltaDeg}, keeping its angle and the reach limit. */
    public static MotionShutter.Controls slide(MotionShutter.Controls start, double deltaDeg,
                                               int frames) {
        if (start.isLongTrail()) return start;   // it only grows backwards from this frame
        int angle = start.angle();
        double open = clamp(start.phase() + deltaDeg, -REACH, REACH - angle);
        return fromWindow(start, open, angle, frames);
    }

    /**
     * A window back to controls. The opening edge snaps to the centred, behind and ahead
     * positions when it is within the snap distance of one, and the timing is named after it.
     */
    public static MotionShutter.Controls fromWindow(double open, int angle, int mix, int frames) {
        return fromWindow(new MotionShutter.Controls(180, MotionShutter.Timing.CENTERED, 0, mix),
                open, angle, frames);
    }

    /** {@link #fromWindow(double, int, int, int)} keeping {@code like}'s Mix, Fade and Look. */
    public static MotionShutter.Controls fromWindow(MotionShutter.Controls like, double open,
                                                    int angle, int frames) {
        double tol = SNAP_PER_FRAME * Math.max(1, frames);
        MotionShutter.Timing[] named = {
                MotionShutter.Timing.CENTERED, MotionShutter.Timing.TRAILING,
                MotionShutter.Timing.LEADING};
        for (MotionShutter.Timing t : named) {
            if (Math.abs(open - t.phase(angle, 0)) <= tol) return like.withWindow(angle, t, 0);
        }
        return like.withWindow(angle, MotionShutter.Timing.CUSTOM, (int) Math.round(open));
    }

    /** The angle, pulled onto a stop when within the snap distance of one. */
    public static int snapAngle(int angle, int frames) {
        double tol = SNAP_PER_FRAME * Math.max(1, frames);
        for (int stop : ANGLE_STOPS) if (Math.abs(angle - stop) <= tol) return stop;
        return Math.max(0, Math.min(REACH, angle));
    }

    /** The preset these controls are exactly, or null for a hand-set shutter. */
    public static MotionShutter.Preset presetOf(MotionShutter.Controls c) {
        for (MotionShutter.Preset p : MotionShutter.Preset.values()) {
            if (p.angle == c.angle() && p.timing == c.timing()) return p;
        }
        return null;
    }

    /** {@code c} set to {@code preset}, keeping its Mix. */
    public static MotionShutter.Controls withPreset(MotionShutter.Controls c,
                                                    MotionShutter.Preset preset) {
        Objects.requireNonNull(preset);
        return c.withWindow(preset.angle, preset.timing, 0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
