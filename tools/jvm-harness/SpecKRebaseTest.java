import com.fadcam.ui.faditor.transform.TransformQuad;

/**
 * SPEC K follow-up (JoyRaptor 2026-09-07): "resize and it disappeared on lifting my
 * finger". A canvas-rect change (drawer resize, controls fade) landing mid-drag
 * left the frozen grab snapshot and the live finger speaking different frames; the
 * commit baked the gap into the project as a teleport on finger-up. The view now
 * rebases its frozen gesture state to the new rect (pure chrome, no model writes).
 *
 * <p>This test proves the rebase is exact: the same gesture, aimed at the same
 * NORMALIZED finger target, must store the same pin fractions whether or not the
 * rect moved mid-drag — pins live in normalized units, so the rect must not leak
 * into them.
 */
public class SpecKRebaseTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    // ── pose replica (absolute px, norm-stable pose mapped per rect) ──
    static final float EPS = 1e-5f;
    static boolean isFlat(float[] o) {
        for (float v : o) if (Math.abs(v) > EPS) return false;
        return true;
    }

    static class Pose {
        // Norm pose (rect-independent): centre, size as canvas fractions.
        float ncx, ncy, sizeFrac, aspect = 1.5f, th, pivx = 0.5f, pivy = 0.5f;
        float smx = 1f, smy = 1f;
        float[] off = new float[8];
        // Rect + derived px pose.
        float rl, rt, rw, rh, cx, cy, w, h;
        void layout(float l, float t, float cw, float ch) {
            rl = l; rt = t; rw = cw; rh = ch;
            cx = l + ncx * cw; cy = t + ncy * ch;
            h = sizeFrac * ch; w = h * aspect;
        }
    }

    static Pose copy(Pose p) {
        Pose q = new Pose();
        q.ncx = p.ncx; q.ncy = p.ncy; q.sizeFrac = p.sizeFrac; q.aspect = p.aspect;
        q.th = p.th; q.pivx = p.pivx; q.pivy = p.pivy;
        q.smx = p.smx; q.smy = p.smy; q.off = p.off.clone();
        q.rl = p.rl; q.rt = p.rt; q.rw = p.rw; q.rh = p.rh;
        q.cx = p.cx; q.cy = p.cy; q.w = p.w; q.h = p.h;
        return q;
    }

    static boolean isNeutral(Pose p) {
        // Production: centre pivot never folds, however pinned (SPEC K catapult fix).
        return p.pivx == 0.5f && p.pivy == 0.5f;
    }

    static float pivDx(Pose p) {
        if (isFlat(p.off)) return (p.pivx - 0.5f) * p.w;
        float[] ww = new float[4];
        TransformQuad.pivotWeights(p.pivx, p.pivy, ww);
        return ww[0] * (-p.w / 2f + p.off[0] * p.w) + ww[1] * (p.w / 2f + p.off[2] * p.w)
             + ww[2] * (p.w / 2f + p.off[4] * p.w) + ww[3] * (-p.w / 2f + p.off[6] * p.w);
    }

    static float pivDy(Pose p) {
        if (isFlat(p.off)) return (p.pivy - 0.5f) * p.h;
        float[] ww = new float[4];
        TransformQuad.pivotWeights(p.pivx, p.pivy, ww);
        return ww[0] * (-p.h / 2f + p.off[1] * p.h) + ww[1] * (-p.h / 2f + p.off[3] * p.h)
             + ww[2] * (p.h / 2f + p.off[5] * p.h) + ww[3] * (p.h / 2f + p.off[7] * p.h);
    }

    static float[] foldedCentre(Pose p) {
        if (isNeutral(p) || p.th == 0f) return new float[]{p.cx, p.cy};
        double rad = Math.toRadians(p.th);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        float dX = p.smx * pivDx(p), dY = p.smy * pivDy(p);
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

    static float[] recoverCentre(Pose p) {
        float[] cf = foldedCentre(p);
        if (isNeutral(p) || p.th == 0f) return new float[]{cf[0], cf[1]};
        double radU = Math.toRadians(-p.th);
        float uc = (float) Math.cos(radU), us = (float) Math.sin(radU);
        double radP = Math.toRadians(p.th);
        float pc = (float) Math.cos(radP), ps = (float) Math.sin(radP);
        float dX = p.smx * pivDx(p), dY = p.smy * pivDy(p);
        float pvx = cf[0] + pc * dX - ps * dY, pvy = cf[1] + ps * dX + pc * dY;
        float dx = cf[0] - pvx, dy = cf[1] - pvy;
        return new float[]{pvx + uc * dx - us * dy, pvy + us * dx + uc * dy};
    }

    static float[] writeQuad(Pose p, float[] quad8) {
        float[] qc = recoverCentre(p);
        float[] next = new float[8];
        boolean ok = TransformQuad.solvePinForQuad(quad8, qc[0], qc[1], p.w, p.h, p.th,
                p.smx, p.smy, p.pivx, p.pivy, next);
        return ok ? next : null;
    }

    static float dist(float[] a, float[] b) {
        float m = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    /** Canvas-px point for a normalized finger target. */
    static float[] px(Pose p, float nx, float ny) {
        return new float[]{p.rl + nx * p.rw, p.rt + ny * p.rh};
    }

    public static void main(String[] a) {
        // 1. rebasePoints unit contract.
        {
            float[] pts = {100f, 200f, 300f, 400f};
            check(!TransformQuad.rebasePoints(pts, 0f, 0f, 100f, 100f, 0f, 0f, 100f, 100f),
                    "identical rects: no-op");
            check(dist(pts, new float[]{100f, 200f, 300f, 400f}) == 0f, "no-op leaves points");
            check(TransformQuad.rebasePoints(pts, 0f, 0f, 100f, 100f, 50f, 0f, 100f, 100f),
                    "shift applies");
            check(dist(pts, new float[]{150f, 200f, 350f, 400f}) < 1e-4f, "shift moves all pairs");
            check(TransformQuad.rebasePoints(pts, 50f, 0f, 100f, 100f, 50f, 0f, 200f, 50f),
                    "scale applies");
            check(dist(pts, new float[]{250f, 100f, 650f, 200f}) < 1e-4f, "scale about origin");
            check(!TransformQuad.rebasePoints(pts, 0f, 0f, 0f, 100f, 0f, 0f, 100f, 100f),
                    "degenerate old rect refuses");
            float[] nan = {Float.NaN, 0f};
            check(!TransformQuad.rebasePoints(nan, 0f, 0f, 100f, 100f, 0f, 0f, 100f, 100f),
                    "NaN refuses");
        }
        // 2. Gesture continuity across a mid-drag rect change (the reported teleport).
        // Uniform canvas change (the common case: container grows, canvas scales + recentres)
        // rebases EXACTLY at any rotation: same norm finger target => same pins.
        {
            float oL = 90f, oT = 400f, oW = 900f, oH = 600f;
            float nL = 40f, nT = 290f, nW = 1000f, nH = 666.6667f; // uniform 1.111x + recenter
            java.util.Random r = new java.util.Random(20260907L);
            int bad = 0;
            float worst = 0f;
            for (int k = 0; k < 120; k++) {
                Pose pref = new Pose();
                pref.ncx = 0.35f + r.nextFloat() * 0.3f;
                pref.ncy = 0.35f + r.nextFloat() * 0.3f;
                pref.sizeFrac = 0.25f + r.nextFloat() * 0.2f;
                pref.th = r.nextFloat() * 720f - 360f;
                float[] pivs = {0f, 0.5f, 1f};
                pref.pivx = pivs[r.nextInt(3)]; pref.pivy = pivs[r.nextInt(3)];
                if (r.nextBoolean()) pref.smx = -1f;
                for (int i = 0; i < 8; i++) pref.off[i] = (r.nextFloat() - 0.5f) * 0.3f;
                pref.layout(oL, oT, oW, oH);
                int corner = r.nextInt(4);
                // Same NORM finger target in both runs.
                float fnx = 0.2f + r.nextFloat() * 0.6f;
                float fny = 0.2f + r.nextFloat() * 0.6f;
                // Reference: uninterrupted drag in the old rect.
                float[] q0 = readQuad(pref);
                float[] ft0 = px(pref, fnx, fny);
                float[] qref = q0.clone();
                float[] fac = new float[2];
                if (!TransformQuad.scaleCorner(qref, q0, corner, ft0[0], ft0[1], fac)) continue;
                float[] pinsRef = writeQuad(pref, qref);
                if (pinsRef == null) continue;
                // Rebasing run: rect moves first (snapshot goes stale), view rebases it.
                float[] q0s = q0.clone();
                boolean rebased = TransformQuad.rebasePoints(q0s, oL, oT, oW, oH, nL, nT, nW, nH);
                if (!rebased) { bad++; continue; }
                Pose pnew = copy(pref);
                pnew.layout(nL, nT, nW, nH);
                // The rebased snapshot must equal a fresh read in the new rect.
                float dSync = dist(q0s, readQuad(pnew));
                if (dSync > 0.5f) {
                    bad++;
                    if (bad < 4) System.out.println("    resync drift " + dSync + " k=" + k);
                    continue;
                }
                // Continue the drag to the same NORM target (fresh px in the new rect).
                float[] ft1 = px(pnew, fnx, fny);
                float[] qnew = q0s.clone();
                if (!TransformQuad.scaleCorner(qnew, q0s, corner, ft1[0], ft1[1], new float[2])) continue;
                float[] pinsNew = writeQuad(pnew, qnew);
                if (pinsNew == null) continue;
                float d = dist(pinsRef, pinsNew);
                worst = Math.max(worst, d);
                if (d > 0.002f) {
                    bad++;
                    if (bad < 6) System.out.println("    pin drift " + d + " k=" + k + " th=" + pref.th);
                }
            }
            check(bad == 0, "120 rebased scale-drags store the same pins (bad " + bad + ", worst " + worst + ")");
        }
        // 2b. Non-uniform canvas change cannot preserve rotated geometry exactly
        // (rotation does not commute with non-uniform scale), but the rebase must stay
        // bounded by the rect change itself — continuity, never teleport-scale garbage.
        // (In practice canvas fits keep aspect, so the uniform-exact path above is the
        // one that fires; this bounds the rare constraint-switching resize.)
        {
            float oL = 90f, oT = 400f, oW = 900f, oH = 600f;
            float nL = 90f, nT = 340f, nW = 900f, nH = 720f; // taller only
            java.util.Random r = new java.util.Random(20260909L);
            int bad = 0;
            float worstSync = 0f, worstPin = 0f;
            for (int k = 0; k < 120; k++) {
                Pose pref = new Pose();
                pref.ncx = 0.35f + r.nextFloat() * 0.3f;
                pref.ncy = 0.35f + r.nextFloat() * 0.3f;
                pref.sizeFrac = 0.25f + r.nextFloat() * 0.2f;
                pref.th = r.nextFloat() * 60f - 30f;
                float[] pivs = {0f, 0.5f, 1f};
                pref.pivx = pivs[r.nextInt(3)]; pref.pivy = pivs[r.nextInt(3)];
                if (r.nextBoolean()) pref.smx = -1f;
                for (int i = 0; i < 8; i++) pref.off[i] = (r.nextFloat() - 0.5f) * 0.3f;
                pref.layout(oL, oT, oW, oH);
                int corner = r.nextInt(4);
                float fnx = 0.2f + r.nextFloat() * 0.6f;
                float fny = 0.2f + r.nextFloat() * 0.6f;
                float[] q0 = readQuad(pref);
                float[] ft0 = px(pref, fnx, fny);
                float[] qref = q0.clone();
                if (!TransformQuad.scaleCorner(qref, q0, corner, ft0[0], ft0[1], new float[2])) continue;
                float[] pinsRef = writeQuad(pref, qref);
                if (pinsRef == null) continue;
                float[] q0s = q0.clone();
                TransformQuad.rebasePoints(q0s, oL, oT, oW, oH, nL, nT, nW, nH);
                Pose pnew = copy(pref);
                pnew.layout(nL, nT, nW, nH);
                float dSync = dist(q0s, readQuad(pnew));
                worstSync = Math.max(worstSync, dSync);
                if (dSync > 80f) { bad++; continue; }
                float[] ft1 = px(pnew, fnx, fny);
                float[] qnew = q0s.clone();
                if (!TransformQuad.scaleCorner(qnew, q0s, corner, ft1[0], ft1[1], new float[2])) continue;
                float[] pinsNew = writeQuad(pnew, qnew);
                if (pinsNew == null) continue;
                float d = dist(pinsRef, pinsNew);
                worstPin = Math.max(worstPin, d);
                if (d > 0.4f) { bad++; continue; }
            }
            check(bad == 0, "120 non-uniform rebases stay bounded (bad " + bad
                    + ", worst sync " + worstSync + "px, worst pin " + worstPin + ")");
        }
        // 3. Without the rebase (the old behavior), the same scenario corrupts.
        {
            Pose p = new Pose();
            p.ncx = 0.5f; p.ncy = 0.5f; p.sizeFrac = 0.3f; p.th = 5f;
            p.pivx = 0.5f; p.pivy = 0.0f;
            float oL = 90f, oT = 400f, oW = 900f, oH = 600f;
            float nL = 90f, nT = 340f, nW = 900f, nH = 720f;
            p.layout(oL, oT, oW, oH);
            float[] q0 = readQuad(p);
            // Rect moves; snapshot stays stale (old code); drag continues in the new frame.
            Pose pnew = copy(p);
            pnew.layout(nL, nT, nW, nH);
            float[] ft1 = px(pnew, 0.7f, 0.3f);
            float[] qbad = q0.clone();
            TransformQuad.scaleCorner(qbad, q0, 0, ft1[0], ft1[1], new float[2]);
            float[] pinsBad = writeQuad(pnew, qbad);
            // Reference: same norm target, no rect change.
            float[] ft0 = px(p, 0.7f, 0.3f);
            float[] qref = q0.clone();
            TransformQuad.scaleCorner(qref, q0, 0, ft0[0], ft0[1], new float[2]);
            float[] pinsRef = writeQuad(p, qref);
            float d = dist(pinsBad, pinsRef);
            check(d > 0.01f, "stale snapshot demonstrably corrupts (drift " + d + " — the old teleport)");
        }
        System.out.println(fails == 0 ? "ALL GREEN" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
