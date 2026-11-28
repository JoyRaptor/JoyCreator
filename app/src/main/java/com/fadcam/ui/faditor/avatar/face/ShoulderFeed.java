package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * SHRUG-1: the shoulders between the body model and the screen (the pilot's {@code AL.pose.reading} and {@code feed}).
 * Each reading of the body (ten a second at most) goes through the {@link ShoulderStage}; every screen frame the shown
 * shrug glides to the last reading (the owner, 11-22: "a little bit jerky" when it stepped), and with Shoulders switched
 * off or the camera gone the last shrug eases away instead of freezing. Also how long the shoulders have been out of
 * the picture, for the room's hint.
 *
 * <p>Pure Java. One thread gives the readings (the camera's), one other draws; a reading replaces a whole map, so the
 * drawing thread never sees half of one.</p>
 */
public final class ShoulderFeed {

    /** The glide, per second: critically damped, about a quarter of a second to settle, no overshoot (the pilot's). */
    static final double GLIDE = 14;
    /** Switched off or the camera gone: the last shrug eases away over about this long (the pilot's). */
    static final double EASE_OFF_S = 0.3;

    private final ShoulderStage stage = new ShoulderStage();
    @Nullable private volatile Map<String, Float> out;     // the last reading's shrugs (a fresh map each time)
    private volatile boolean seen;
    private volatile double unseenS;
    private double lastReadT = Double.NaN;
    private final Map<String, double[]> follow = new HashMap<>();   // the drawing thread's own

    /**
     * One reading of the body: {@code xyv} as {@link ShoulderStage#drops} takes it (null: no body), {@code tall} the
     * upright picture's height over its width, {@code t} seconds. {@code mirror}: the face's Mirror setting off (like
     * someone facing you), as {@link FaceTuning#mirror}. {@code sensitivity}: 1 as built, 2 = half the movement.
     */
    public synchronized void reading(@Nullable float[] xyv, float tall, double t, boolean mirror, float sensitivity) {
        float[] d = ShoulderStage.drops(xyv, tall);
        if (d != null && (!Float.isFinite(d[0]) || !Float.isFinite(d[1]))) d = null;   // (not a number is no body: the smoothing would keep it)
        stage.setSensitivity(sensitivity);
        Map<String, Float> o = stage.process(d, t, mirror);
        double dt = Double.isNaN(lastReadT) ? 0 : Math.max(0, Math.min(1, t - lastReadT));
        lastReadT = t;
        if (d != null) {
            seen = true;
            unseenS = 0;
        } else {
            seen = false;
            unseenS += dt;
        }
        out = o;
    }

    /** Re-centre: the next still half-second with the shoulders in view is their rest. */
    public synchronized void requestNeutral() {
        stage.requestNeutral();
    }

    /** The last reading saw both shoulders and an ear. */
    public boolean seen() {
        return seen;
    }

    /** How long the readings have missed the shoulders, in seconds (0 while they are seen). */
    public double unseenSeconds() {
        return unseenS;
    }

    /**
     * The shrugs to draw now ({@link ShoulderStage#SHRUG_L}, {@code SHRUG_R}, {@code SHRUG}, glided, and {@code BODY}:
     * 0..1, how much the shoulders drive), or null before the first reading. {@code dt}: seconds since the last frame;
     * {@code on}: the camera reads the shoulders now (Shoulders ticked and the camera running).
     */
    @Nullable
    public Map<String, Float> sample(double dt, boolean on) {
        Map<String, Float> o = out;
        if (o == null) return null;
        if (!on && g(o, ShoulderStage.BODY) > 0) {              // switched off or the camera gone: ease the last shrug away
            double k = Math.exp(-Math.max(0, dt) / EASE_OFF_S);
            float b = (float) (g(o, ShoulderStage.BODY) * k);
            Map<String, Float> e = new HashMap<>();
            e.put(ShoulderStage.SHRUG_L, (float) (g(o, ShoulderStage.SHRUG_L) * k));
            e.put(ShoulderStage.SHRUG_R, (float) (g(o, ShoulderStage.SHRUG_R) * k));
            e.put(ShoulderStage.SHRUG, (float) (g(o, ShoulderStage.SHRUG) * k));
            e.put(ShoulderStage.BODY, b < 0.01f ? 0f : b);
            out = o = e;
        }
        Map<String, Float> m = new HashMap<>();
        for (String k : new String[]{ShoulderStage.SHRUG_L, ShoulderStage.SHRUG_R, ShoulderStage.SHRUG})
            m.put(k, (float) glide(k, g(o, k), dt));
        m.put(ShoulderStage.BODY, on ? g(o, ShoulderStage.BODY) : 0f);
        return m;
    }

    /** A critically damped follow toward {@code target} (0..1), in steps of at most a sixtieth of a second. */
    private double glide(@NonNull String k, double target, double dt) {
        double[] e = follow.get(k);
        if (e == null) {
            e = new double[]{target, 0};
            follow.put(k, e);
        }
        double left = Math.min(Math.max(0, dt), 0.25);
        while (left > 1e-6) {
            double h = Math.min(left, 1 / 60.0);
            e[1] += (GLIDE * GLIDE * (target - e[0]) - 2 * GLIDE * e[1]) * h;
            e[0] += e[1] * h;
            left -= h;
        }
        return Math.max(0, Math.min(1, e[0]));
    }

    private static float g(@NonNull Map<String, Float> m, @NonNull String k) {
        Float v = m.get(k);
        return v == null ? 0f : v;
    }
}
