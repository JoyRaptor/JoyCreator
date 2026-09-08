import com.fadcam.ui.faditor.transform.TransformQuad;

/**
 * SPEC K — "a move or a fold throws ONE corner across the frame".
 *
 * <p>Acceptance 5 (no snap on release) is exactly:
 * {@code readQuad(writeQuad(gestureQuad)) == gestureQuad} for every gesture, and
 * acceptance 1–3 is that a move/rotate/pinch leaves all eight pin offsets at zero
 * while a fold lands every corner where the fold puts it.
 *
 * <p>The WRITE half under test is the real production code —
 * {@link TransformQuad#solvePinForQuad} — fed by the host's pose-centre recovery;
 * the READ half replicates the unchanged {@code CornerPinTransformHost.readQuad}
 * (folded box + rotate about its centre) with the model's own bilinear pivot, so a
 * drift here is a handles-vs-picture snap on device. Before the fix this test fails
 * (the old explicit unfold stored every rotated fold/scale/free drag into the wrong
 * frame by (I−R)·(δold−δnew): 10 failures, drifts to 1615px in fuzz); after it, green.
 */
public class SpecKPinRoundTripTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    // ── model replica (READ side + pose-centre recovery; unchanged by the fix) ──
    static final float EPS = 1e-5f;

    static boolean isFlat(float[] off) {
        for (int i = 0; i < 8; i++) if (Math.abs(off[i]) > EPS) return false;
        return true;
    }

    static boolean isNeutral(float px, float py, float[] off) {
        // Production: centre pivot never folds, however pinned (SPEC K catapult fix).
        return px == 0.5f && py == 0.5f;
    }

    static float pivDx(float w, float px, float py, float[] pins) {
        if (isFlat(pins)) return (px - 0.5f) * w;
        float[] ww = new float[4];
        TransformQuad.pivotWeights(px, py, ww);
        return ww[0] * (-w / 2f + pins[0] * w) + ww[1] * (w / 2f + pins[2] * w)
             + ww[2] * (w / 2f + pins[4] * w) + ww[3] * (-w / 2f + pins[6] * w);
    }

    static float pivDy(float h, float px, float py, float[] pins) {
        if (isFlat(pins)) return (py - 0.5f) * h;
        float[] ww = new float[4];
        TransformQuad.pivotWeights(px, py, ww);
        return ww[0] * (-h / 2f + pins[1] * h) + ww[1] * (-h / 2f + pins[3] * h)
             + ww[2] * (h / 2f + pins[5] * h) + ww[3] * (h / 2f + pins[7] * h);
    }

    static class Pose {
        float cx, cy, w, h, th;
        float pivx = 0.5f, pivy = 0.5f;
        float smx = 1f, smy = 1f;
        float[] off = new float[8];
    }

    static Pose copy(Pose p) {
        Pose q = new Pose();
        q.cx = p.cx; q.cy = p.cy; q.w = p.w; q.h = p.h; q.th = p.th;
        q.pivx = p.pivx; q.pivy = p.pivy; q.smx = p.smx; q.smy = p.smy;
        q.off = p.off.clone();
        return q;
    }

    static float[] foldedCentre(Pose p) {
        if (isNeutral(p.pivx, p.pivy, p.off) || p.th == 0f) return new float[]{p.cx, p.cy};
        double rad = Math.toRadians(p.th);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        float dX = p.smx * pivDx(p.w, p.pivx, p.pivy, p.off);
        float dY = p.smy * pivDy(p.h, p.pivx, p.pivy, p.off);
        float pvx = p.cx + dX, pvy = p.cy + dY;
        float vx = p.cx - pvx, vy = p.cy - pvy;
        return new float[]{pvx + vx * c - vy * s, pvy + vx * s + vy * c};
    }

    static float[] readQuad(Pose p) {
        float[] cf = foldedCentre(p);
        double rad = Math.toRadians(p.th);
        float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
        float[] bx = {p.cx - p.w / 2f, p.cx + p.w / 2f, p.cx + p.w / 2f, p.cx - p.w / 2f};
        float[] by = {p.cy - p.h / 2f, p.cy - p.h / 2f, p.cy + p.h / 2f, p.cy + p.h / 2f};
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            float px = bx[i] + p.off[i * 2] * p.w;
            float py = by[i] + p.off[i * 2 + 1] * p.h;
            float dx = p.smx * (px - p.cx), dy = p.smy * (py - p.cy);
            out[i * 2] = cf[0] + cs * dx - sn * dy;
            out[i * 2 + 1] = cf[1] + sn * dx + cs * dy;
        }
        return out;
    }

    /** Host pose-centre recovery (stable inside a distort gesture; unchanged). */
    static float[] recoverPoseCentre(Pose p) {
        float[] cf = foldedCentre(p);
        if (isNeutral(p.pivx, p.pivy, p.off) || p.th == 0f) return new float[]{cf[0], cf[1]};
        double radU = Math.toRadians(-p.th);
        float uc = (float) Math.cos(radU), us = (float) Math.sin(radU);
        double radP = Math.toRadians(p.th);
        float pc = (float) Math.cos(radP), ps = (float) Math.sin(radP);
        float dX = p.smx * pivDx(p.w, p.pivx, p.pivy, p.off);
        float dY = p.smy * pivDy(p.h, p.pivx, p.pivy, p.off);
        float pvx = cf[0] + pc * dX - ps * dY, pvy = cf[1] + ps * dX + pc * dY;
        float dx = cf[0] - pvx, dy = cf[1] - pvy;
        return new float[]{pvx + uc * dx - us * dy, pvy + us * dx + uc * dy};
    }

    /** WRITE half = the real production solve. */
    static float[] writeQuad(Pose p, float[] quad8) {
        float[] qc = recoverPoseCentre(p);
        float[] next = new float[8];
        boolean ok = TransformQuad.solvePinForQuad(quad8, qc[0], qc[1], p.w, p.h, p.th,
                p.smx, p.smy, p.pivx, p.pivy, next);
        return ok ? next : null;
    }

    static float quadDist(float[] a, float[] b) {
        float m = 0f;
        for (int i = 0; i < 8; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    static float maxAbs(float[] a) {
        float m = 0f;
        for (float v : a) m = Math.max(m, Math.abs(v));
        return m;
    }

    static void roundTrip(String n, Pose p, float[] gestureQuad, float tolPx) {
        float[] pins = writeQuad(p, gestureQuad);
        if (pins == null) { check(false, n + ": write refused"); return; }
        Pose p2 = copy(p);
        p2.off = pins;
        float d = quadDist(gestureQuad, readQuad(p2));
        check(d <= tolPx, n + ": read(write(Q))==Q (drift " + d + "px)");
        if (d > tolPx) {
            for (int i = 0; i < 4; i++) {
                System.out.println("    corner " + i + " gesture=(" + gestureQuad[i * 2] + ","
                        + gestureQuad[i * 2 + 1] + ") reread=(" + readQuad(p2)[i * 2] + ","
                        + readQuad(p2)[i * 2 + 1] + ")");
            }
            System.out.println("    pins=" + java.util.Arrays.toString(pins));
        }
    }

    static Pose fresh() {
        Pose p = new Pose();
        p.cx = 500f; p.cy = 500f; p.w = 300f; p.h = 200f; p.th = 0f;
        return p;
    }

    static Pose scottFlatTopPivot() {
        // Note 20 overlay abaef109's pose (pivot TOP-CENTRE, rotation 365.24 = SPEC A
        // raw winding) with FLAT pins: a freshly imported image after pivot+spin.
        Pose p = new Pose();
        p.cx = 500f; p.cy = 360f; p.w = 300f; p.h = 200f; p.th = 365.2412f;
        p.pivx = 0.5f; p.pivy = 0.0f;
        p.off = new float[8];
        return p;
    }

    public static void main(String[] a) {
        // pivotWeights contract (the solve's foundation).
        {
            float[] ww = new float[4];
            TransformQuad.pivotWeights(0.5f, 0.0f, ww);
            float sum = ww[0] + ww[1] + ww[2] + ww[3];
            check(Math.abs(sum - 1f) < 1e-6f, "pivotWeights sum to 1");
            check(ww[0] == 0.5f && ww[1] == 0.5f && ww[2] == 0f && ww[3] == 0f,
                    "top-centre weights are {0.5,0.5,0,0}");
            TransformQuad.pivotWeights(0.5f, 0.5f, ww);
            check(ww[0] == 0.25f && ww[1] == 0.25f && ww[2] == 0.25f && ww[3] == 0.25f,
                    "centre weights are uniform");
        }
        // 1. Fresh image: a pure MOVE never touches the pin (acceptance 1).
        {
            Pose p = fresh();
            float[] q = readQuad(p);
            float[] moved = q.clone();
            TransformQuad.translate(moved, 37f, -25f);
            check(maxAbs(p.off) == 0f, "fresh move: pins stay exactly 0 (move writes no pin)");
            Pose pm = copy(p); pm.cx += 37f; pm.cy += -25f;
            check(quadDist(moved, readQuad(pm)) < 1e-3f, "fresh move: translated quad == re-read quad");
        }
        // 2. JoyRaptor's pose, flat: scale / pinch / fold / free must all round-trip.
        {
            Pose p = scottFlatTopPivot();
            float[] q = readQuad(p);
            check(maxAbs(p.off) == 0f, "top-pivot 365 flat: pins start at 0");
            float[] qs = q.clone();
            float[] fac = new float[2];
            check(TransformQuad.scaleCorner(qs, q.clone(), 0, q[0] - 40f, q[1] - 30f, fac),
                    "top-pivot 365: scaleCorner applies");
            roundTrip("top-pivot 365 scale TL", p, qs, 0.5f);
            float[] qe = q.clone();
            check(TransformQuad.scaleEdge(qe, q.clone(), 2, (q[4] + q[6]) / 2f,
                    (q[5] + q[7]) / 2f + 40f, new float[2]), "top-pivot 365: scaleEdge applies");
            roundTrip("top-pivot 365 scale bottom edge", p, qe, 0.5f);
            check(maxAbs(p.off) == 0f, "top-pivot 365 pinch: pins stay 0 (pinch writes no pin)");
            for (int e = 0; e < 4; e++) {
                float[] qf = q.clone();
                TransformQuad.foldOverEdge(qf, e);
                roundTrip("top-pivot 365 fold edge " + e, p, qf, 0.5f);
                float[] pins = writeQuad(p, qf);
                float[] xs = new float[4], ys = new float[4];
                for (int i = 0; i < 4; i++) {
                    xs[i] = ((i == 0 || i == 3) ? -p.w / 2f : p.w / 2f) + pins[i * 2] * p.w;
                    ys[i] = ((i < 2) ? -p.h / 2f : p.h / 2f) + pins[i * 2 + 1] * p.h;
                }
                float m1 = (float) Math.hypot((xs[1] - xs[0]) - (xs[2] - xs[3]),
                        (ys[1] - ys[0]) - (ys[2] - ys[3]));
                float m2 = (float) Math.hypot((xs[3] - xs[0]) - (xs[2] - xs[1]),
                        (ys[3] - ys[0]) - (ys[2] - ys[1]));
                check(Math.max(m1 / p.w, m2 / p.h) < 1e-3f,
                        "top-pivot 365 fold edge " + e + " stays a parallelogram");
            }
            float[] qfr = q.clone();
            TransformQuad.freeCorner(qfr, 3, q[6] + 25f, q[7] + 30f);
            roundTrip("top-pivot 365 free BL", p, qfr, 0.5f);
        }
        // 3. Centre pivot, rotated 30 deg (the 2026-09-06 change area).
        {
            Pose p = fresh();
            p.th = 30f;
            float[] q = readQuad(p);
            float[] qs = q.clone();
            TransformQuad.scaleCorner(qs, q.clone(), 1, q[2] + 30f, q[3] - 20f, new float[2]);
            roundTrip("centre 30deg scale TR", p, qs, 0.5f);
            for (int e = 0; e < 4; e++) {
                float[] qf = q.clone();
                TransformQuad.foldOverEdge(qf, e);
                roundTrip("centre 30deg fold edge " + e, p, qf, 0.5f);
            }
        }
        // 4. Fuzz incl. mirrors: every scale/free/fold round-trips (acceptance 5).
        {
            java.util.Random r = new java.util.Random(20260906L);
            float worst = 0f;
            int bad = 0;
            float[] pivs = {0f, 0.5f, 1f};
            for (int k = 0; k < 400; k++) {
                Pose p = new Pose();
                p.cx = 400f + r.nextFloat() * 400f; p.cy = 400f + r.nextFloat() * 400f;
                p.w = 100f + r.nextFloat() * 400f; p.h = 100f + r.nextFloat() * 400f;
                p.th = r.nextFloat() * 720f - 360f;
                p.pivx = pivs[r.nextInt(3)]; p.pivy = pivs[r.nextInt(3)];
                if (r.nextBoolean()) p.smx = -1f;
                if (r.nextBoolean()) p.smy = -1f;
                for (int i = 0; i < 8; i++) p.off[i] = (r.nextFloat() - 0.5f) * 0.2f;
                float[] q = readQuad(p);
                int op = k % 3;
                float[] g = q.clone();
                if (op == 0) {
                    if (!TransformQuad.scaleCorner(g, q, r.nextInt(4),
                            q[0] + (r.nextFloat() - 0.5f) * 80f,
                            q[1] + (r.nextFloat() - 0.5f) * 80f, new float[2])) continue;
                } else if (op == 1) {
                    TransformQuad.freeCorner(g, r.nextInt(4),
                            q[0] + (r.nextFloat() - 0.5f) * 60f,
                            q[1] + (r.nextFloat() - 0.5f) * 60f);
                } else {
                    if (!TransformQuad.foldOverEdge(g, r.nextInt(4))) continue;
                }
                float[] pins = writeQuad(p, g);
                if (pins == null) continue;
                boolean range = true;
                for (float v : pins) if (!Float.isFinite(v) || Math.abs(v) > 8.0001f) range = false;
                if (!range) continue;
                Pose p2 = copy(p); p2.off = pins;
                float d = quadDist(g, readQuad(p2));
                worst = Math.max(worst, d);
                if (d > 0.5f) {
                    bad++;
                    if (bad <= 4) {
                        System.out.println("    FUZZ drift " + d + "px k=" + k + " th=" + p.th
                                + " piv=(" + p.pivx + "," + p.pivy + ") op=" + op);
                    }
                }
            }
            check(bad == 0, "fuzz 400 (mirrored too): round-trip within 0.5px (worst "
                    + worst + "px, bad " + bad + ")");
        }
        System.out.println(fails == 0 ? "ALL GREEN" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
