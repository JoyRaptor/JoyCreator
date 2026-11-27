package cc.joycreator.avatar.face;

import java.util.Map;

/**
 * Walk with your face (the pilot's {@code tools/avatarlab/al-face-walk.js}; the owner, 11-22: "like Character Animator"). Three
 * tick boxes in a small drawer under Walking:
 * <ul>
 * <li><b>Left and right with your face</b>: your face to one side of where it rests walks the character that way, faster the
 *     further over, like the joystick; back to the middle and it stops where it is. Turning or tilting your head still turns
 *     ITS head: this is where your face SITS (the face camera's {@code leanX}), not where it points. A head tilted toward a
 *     shoulder holds the walk (a tilt swings the face sideways; it is not a walk), and so does a head turned well to a side.</li>
 * <li><b>Closer and further with your face</b>: come closer to the camera and it walks toward you; sit back and it steps away
 *     ({@code near}). Where your face rests, it stands at its place.</li>
 * <li><b>Up close</b>: no face needed; it walks up to the camera and stays; untick and it walks back.</li>
 * </ul>
 * The middle of each is a dead zone (the face is noisy at its edges). Whatever the face walks is the body's, so a take records
 * it like a finger's walk; while a take plays, the take walks it. The two depth boxes need a walker that walks in depth
 * ({@link Legs#depth}: the phone's Walker with its {@code DepthWalk}, made by the room while either box is on).
 */
public final class FaceWalk {
    public static final double DEAD_X = 0.18, DEAD_Z = 0.15;   // the middle of each that is not a walk
    public static final double SPEED = 0.8;                     // body heights a second, the face right over to one side
    public static final double CURVE = 1.6;                     // gentle near the middle, quick toward the edge
    public static final double TILT = 0.36;                     // the head tilted further than this (|roll|) holds the sideways walk
    public static final double TURN = 0.5;                      // or turned further than this (|turnX|): a turned head swings the face sideways
                                                                // (his take, 11-27: turning right walked Topps right; his leans turned 0.3 to 0.41)
    static final double LET_GO_S = 0.4;                         // stopped this long, the body is handed back

    /** What it needs of the walker (the phone's {@code Walker}, or a test's). */
    public interface Legs {
        boolean travels();

        boolean held();

        double tx();

        void setTx(double v);

        /** Walks toward and away from the camera (al-depth-walk.js). */
        default boolean depth() {
            return false;
        }

        /** {away, toward}: how far it may go (its own units; away is negative). */
        default double[] depthLimits() {
            return new double[]{0, 0};
        }

        default Double tz() {
            return null;
        }

        default void setTz(double v) {
        }

        /** Where its ground line is now, toward (+) or away (-) from its place. */
        default double base() {
            return 0;
        }
    }

    private boolean lr, deep, upClose;
    private double lx, nz, roll, turn, still;
    private boolean own, drove;

    public boolean leftRight() {
        return lr;
    }

    public void setLeftRight(boolean on) {
        lr = on;
    }

    public boolean closer() {
        return deep;
    }

    public void setCloser(boolean on) {
        deep = on;
    }

    public boolean upClose() {
        return upClose;
    }

    public void setUpClose(boolean on) {
        upClose = on;
    }

    /** The face walks the body now (a take records it as the body's). */
    public boolean owns() {
        return own;
    }

    /** The part of a signal past the dead zone, -1..1. */
    static double past(double v, double dz) {
        double a = Math.abs(v);
        return a <= dz ? 0 : Math.signum(v) * Math.min(1, (a - dz) / (1 - dz));
    }

    /**
     * One frame while the room performs. {@code face}: FaceCapture's signals, or null (no camera, or no face). {@code bodyH}: the
     * character's height (its own units); {@code limX}: how far it may walk either way from home. {@code replaying}: a take is
     * playing (it walks the body). Returns whether the face walked the body this frame.
     */
    public boolean step(double dt, Map<String, Float> face, Legs w, double bodyH, double limX, boolean replaying) {
        Float pres = face == null ? null : face.get("face");
        boolean seen = face != null && !face.isEmpty() && (pres == null || pres > 0.5f);
        double e = 1 - Math.exp(-8 * Math.max(0, dt));
        if (seen) {
            lx += (g(face, "leanX") - lx) * e;
            nz += (g(face, "near") - nz) * e;
        } else {
            lx = 0;
            nz = 0;
        }
        boolean drive = false;
        if (replaying) {                                       // the take walks it: hand the body back at once
            own = false;
            return false;
        }
        if (!w.held()) {
            /* left and right: a rate, like the joystick */
            if (lr && seen && w.travels()) {
                roll += (Math.abs(g(face, "roll")) - roll) * e;
                turn += (Math.abs(g(face, "turnX")) - turn) * e;
                double u = roll > TILT || turn > TURN ? 0 : past(lx, DEAD_X);
                if (u != 0) {
                    double v = w.tx() + Math.signum(u) * Math.pow(Math.abs(u), CURVE) * SPEED * bodyH * dt;
                    w.setTx(Math.max(-limX, Math.min(limX, v)));
                    drive = true;
                }
            }
            /* toward the camera and away: a place, set by how near the face is */
            if (w.depth()) {
                double[] zl = w.depthLimits();
                Double was = w.tz();
                int mine = 0;
                if (upClose) {
                    w.setTz(zl[1]);
                    mine = 1;
                } else if (deep && seen) {
                    double n = past(nz, DEAD_Z);
                    w.setTz(n > 0 ? n * zl[1] : -n * zl[0]);
                    mine = 1;
                } else if (!deep && drove) {                   // let go: back to its place (where the joystick left it, it stays)
                    w.setTz(0);
                    mine = 2;
                    if (Math.abs(w.base()) < 0.05) drove = false;
                }
                Double now = w.tz();
                if (mine > 0 && now != null && (Math.abs(now - w.base()) > 0.5 || !now.equals(was))) {
                    drive = true;
                    if (mine == 1) drove = true;
                }
            }
        }
        if (drive) {
            own = true;
            still = 0;
        } else if (own) {
            still += dt;
            if (still > LET_GO_S) own = false;
        }
        return drive;
    }

    private static double g(Map<String, Float> f, String k) {
        Float v = f.get(k);
        return v == null || v.isNaN() ? 0 : v;
    }
}
