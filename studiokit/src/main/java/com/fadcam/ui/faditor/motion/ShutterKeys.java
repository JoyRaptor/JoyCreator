package com.fadcam.ui.faditor.motion;

import java.util.Arrays;
import java.util.Objects;

/**
 * Keyframes for an object's Motion Blur (STU-7 spec §K: the card's ‹◆› on Shutter and on Mix).
 *
 * <p>Two independent tracks, as on every other card: <b>shutter</b> keys hold the exposure
 * window (angle and opening, keyed together, so the bar moves as one), <b>mix</b> keys hold Mix.
 * A track with no keys leaves that part at the object's fixed setting. Times are ABSOLUTE
 * timeline milliseconds, the same base the effect stacks key in. Between keys the value eases
 * in and out, the effect cards' default. Immutable: an edit returns a new value, so undo can
 * hold the before and after.</p>
 */
public final class ShutterKeys {

    public static final ShutterKeys EMPTY = new ShutterKeys(new long[0], new double[0],
            new double[0], new long[0], new double[0]);

    private final long[] shutterMs;
    private final double[] angle;
    private final double[] open;
    private final long[] mixMs;
    private final double[] mix;

    private ShutterKeys(long[] shutterMs, double[] angle, double[] open, long[] mixMs, double[] mix) {
        this.shutterMs = shutterMs;
        this.angle = angle;
        this.open = open;
        this.mixMs = mixMs;
        this.mix = mix;
    }

    public boolean isEmpty() { return shutterMs.length == 0 && mixMs.length == 0; }
    public boolean hasShutterKeys() { return shutterMs.length > 0; }
    public boolean hasMixKeys() { return mixMs.length > 0; }
    public int shutterCount() { return shutterMs.length; }
    public int mixCount() { return mixMs.length; }
    public long shutterTimeMs(int i) { return shutterMs[i]; }
    public double shutterAngle(int i) { return angle[i]; }
    public double shutterOpen(int i) { return open[i]; }
    public long mixTimeMs(int i) { return mixMs[i]; }
    public double mixValue(int i) { return mix[i]; }

    /** {@code base} as it is at {@code timelineMs}: keyed parts eased between keys. */
    public MotionShutter.Controls at(long timelineMs, MotionShutter.Controls base) {
        if (isEmpty()) return base;
        int a = base.angle();
        MotionShutter.Timing timing = base.timing();
        int phase = base.customPhase();
        if (shutterMs.length > 0) {
            double ang = sample(shutterMs, angle, timelineMs);
            double op = sample(shutterMs, open, timelineMs);
            a = (int) Math.round(ang);
            int o = (int) Math.round(op);
            timing = MotionShutter.Timing.CUSTOM;
            phase = o;
            for (MotionShutter.Timing t : new MotionShutter.Timing[]{MotionShutter.Timing.CENTERED,
                    MotionShutter.Timing.TRAILING, MotionShutter.Timing.LEADING}) {
                if (Math.abs(t.phase(a, 0) - o) < 0.5) { timing = t; phase = 0; break; }
            }
        }
        int m = mixMs.length > 0 ? (int) Math.round(sample(mixMs, mix, timelineMs)) : base.mixPercent();
        return new MotionShutter.Controls(a, timing, phase, m);
    }

    /**
     * Whether {@code base} with these keys blurs at any time: the gates that decide whether an
     * object takes the motion path at all ask this, not one moment's value.
     */
    public boolean everBlurs(MotionShutter.Controls base) {
        boolean angleOn = shutterMs.length == 0 ? base.angle() > 0 : max(angle) > 0;
        boolean mixOn = mixMs.length == 0 ? base.mixPercent() > 0 : max(mix) > 0;
        return angleOn && mixOn;
    }

    /** The widest shutter angle {@code base} with these keys ever reaches. */
    public int maxAngle(MotionShutter.Controls base) {
        return shutterMs.length == 0 ? base.angle() : (int) Math.ceil(max(angle));
    }

    private static double max(double[] v) {
        double m = Double.NEGATIVE_INFINITY;
        for (double x : v) m = Math.max(m, x);
        return m;
    }

    /** Key the shutter window of {@code c} at {@code ms} (replacing a key there). */
    public ShutterKeys withShutterKey(long ms, MotionShutter.Controls c) {
        int i = indexOf(shutterMs, ms);
        long[] t; double[] an; double[] op;
        if (i >= 0) {
            t = shutterMs.clone(); an = angle.clone(); op = open.clone();
        } else {
            i = -i - 1;
            t = insert(shutterMs, i, ms); an = insert(angle, i, 0); op = insert(open, i, 0);
        }
        an[i] = c.angle();
        op[i] = c.phase();
        return new ShutterKeys(t, an, op, mixMs, mix);
    }

    /** Key Mix of {@code c} at {@code ms} (replacing a key there). */
    public ShutterKeys withMixKey(long ms, MotionShutter.Controls c) {
        int i = indexOf(mixMs, ms);
        long[] t; double[] m;
        if (i >= 0) {
            t = mixMs.clone(); m = mix.clone();
        } else {
            i = -i - 1;
            t = insert(mixMs, i, ms); m = insert(mix, i, 0);
        }
        m[i] = c.mixPercent();
        return new ShutterKeys(shutterMs, angle, open, t, m);
    }

    public ShutterKeys withoutShutterKey(long ms) {
        int i = indexOf(shutterMs, ms);
        if (i < 0) return this;
        return new ShutterKeys(remove(shutterMs, i), remove(angle, i), remove(open, i), mixMs, mix);
    }

    public ShutterKeys withoutMixKey(long ms) {
        int i = indexOf(mixMs, ms);
        if (i < 0) return this;
        return new ShutterKeys(shutterMs, angle, open, remove(mixMs, i), remove(mix, i));
    }

    public boolean hasShutterKeyAt(long ms) { return indexOf(shutterMs, ms) >= 0; }
    public boolean hasMixKeyAt(long ms) { return indexOf(mixMs, ms) >= 0; }

    /** The nearest shutter (or mix) key strictly before / after {@code ms}, or -1. */
    public static long previous(long[] times, long ms) {
        long best = -1;
        for (long t : times) if (t < ms) best = t;
        return best;
    }

    public static long next(long[] times, long ms) {
        for (long t : times) if (t > ms) return t;
        return -1;
    }

    public long[] shutterTimes() { return shutterMs.clone(); }
    public long[] mixTimes() { return mixMs.clone(); }

    /** Every key moved by {@code deltaMs}, for when the object itself moves on the timeline. */
    public ShutterKeys shifted(long deltaMs) {
        if (isEmpty() || deltaMs == 0) return this;
        long[] s = shutterMs.clone();
        long[] m = mixMs.clone();
        for (int i = 0; i < s.length; i++) s[i] += deltaMs;
        for (int i = 0; i < m.length; i++) m[i] += deltaMs;
        return new ShutterKeys(s, angle, open, m, mix);
    }

    /** Rebuild from saved arrays (any order; a duplicate time keeps the last). */
    public static ShutterKeys of(long[] shutterMs, double[] angle, double[] open,
                                 long[] mixMs, double[] mix) {
        ShutterKeys k = EMPTY;
        for (int i = 0; i < shutterMs.length; i++) {
            k = k.withShutterKeyRaw(shutterMs[i], angle[i], open[i]);
        }
        for (int i = 0; i < mixMs.length; i++) {
            k = k.withMixKey(mixMs[i], new MotionShutter.Controls(0, MotionShutter.Timing.CENTERED,
                    0, (int) Math.round(mix[i])));
        }
        return k;
    }

    private ShutterKeys withShutterKeyRaw(long ms, double ang, double op) {
        int i = indexOf(shutterMs, ms);
        long[] t; double[] an; double[] o;
        if (i >= 0) { t = shutterMs.clone(); an = angle.clone(); o = open.clone(); }
        else {
            i = -i - 1;
            t = insert(shutterMs, i, ms); an = insert(angle, i, 0); o = insert(open, i, 0);
        }
        an[i] = Math.max(0, Math.min(720, ang));
        o[i] = Math.max(-720, Math.min(720, op));
        return new ShutterKeys(t, an, o, mixMs, mix);
    }

    private static double sample(long[] t, double[] v, long ms) {
        if (ms <= t[0]) return v[0];
        int n = t.length;
        if (ms >= t[n - 1]) return v[n - 1];
        int i = 0;
        while (t[i + 1] <= ms) i++;
        double u = (ms - t[i]) / (double) (t[i + 1] - t[i]);
        double e = u * u * (3 - 2 * u);   // ease in and out
        return v[i] + (v[i + 1] - v[i]) * e;
    }

    private static int indexOf(long[] t, long ms) { return Arrays.binarySearch(t, ms); }

    private static long[] insert(long[] a, int i, long v) {
        long[] b = new long[a.length + 1];
        System.arraycopy(a, 0, b, 0, i);
        b[i] = v;
        System.arraycopy(a, i, b, i + 1, a.length - i);
        return b;
    }

    private static double[] insert(double[] a, int i, double v) {
        double[] b = new double[a.length + 1];
        System.arraycopy(a, 0, b, 0, i);
        b[i] = v;
        System.arraycopy(a, i, b, i + 1, a.length - i);
        return b;
    }

    private static long[] remove(long[] a, int i) {
        long[] b = new long[a.length - 1];
        System.arraycopy(a, 0, b, 0, i);
        System.arraycopy(a, i + 1, b, i, a.length - i - 1);
        return b;
    }

    private static double[] remove(double[] a, int i) {
        double[] b = new double[a.length - 1];
        System.arraycopy(a, 0, b, 0, i);
        System.arraycopy(a, i + 1, b, i, a.length - i - 1);
        return b;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ShutterKeys k)) return false;
        return Arrays.equals(shutterMs, k.shutterMs) && Arrays.equals(angle, k.angle)
                && Arrays.equals(open, k.open) && Arrays.equals(mixMs, k.mixMs)
                && Arrays.equals(mix, k.mix);
    }

    @Override public int hashCode() {
        return Objects.hash(Arrays.hashCode(shutterMs), Arrays.hashCode(angle),
                Arrays.hashCode(open), Arrays.hashCode(mixMs), Arrays.hashCode(mix));
    }
}
