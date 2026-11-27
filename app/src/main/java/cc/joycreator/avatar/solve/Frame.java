package cc.joycreator.avatar.solve;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One evaluation of {@link CharacterSolver}: the body of the pilot's {@code AL.Solver.prototype.eval} (tools/avatarlab/al-solve.js),
 * its inner functions as methods, in the same order and with the same names, so a reader can hold the two side by side.
 */
final class Frame {
    private static final double DEG = Math.PI / 180;

    private final CharacterSolver sv;
    private final CharIndex ix;
    private final CharacterSolver.State st;
    private final Signals S;
    private final CharacterSolver.Opt opt;
    private final JSONObject set;
    private final boolean live, moving;
    private final double dt;
    private final SolveOut out = new SolveOut();
    private final List<Late> late = new ArrayList<>();
    private final Map<String, List<Item>> masks = new HashMap<>();
    private final Map<String, double[]> wm = new HashMap<>();
    private final Map<String, Item.Z> ZF = new HashMap<>();
    private final IdentityHashMap<JSONObject, Item.Z> zRec = new IdentityHashMap<>();
    private final List<Object[]> PZ = new ArrayList<>();          // [stance JSON, Double weight]
    private final Signals[] SM;
    private double sqz = 1, fy = 1, axis, windIn;
    private boolean flipped;
    private double[] invRoot;
    private int frame;

    private static final class Late { JSONObject n; Ctx c; int at; }

    /** The pilot's per-node context {@code c}. */
    static final class Ctx {
        double[] M, P, rootIn, rel, P0, reach;
        double a, foot;
        boolean mir, ghost, nc, picked, late, lift, back;
        List<JSONObject> clip;
        String tag = "", vs, wrap, under, limbSet, handKey;
        Wp warp;
        Map<String, Wp> warps;
        Ball ball;
        Double phase;
        Item.Z zo;
        Post post;
        JSONObject restLimb, dirKid;

        Ctx copy() {
            Ctx k = new Ctx();
            k.M = M; k.P = P; k.rootIn = rootIn; k.rel = rel; k.P0 = P0; k.reach = reach; k.a = a; k.foot = foot; k.mir = mir; k.ghost = ghost;
            k.nc = nc; k.picked = picked; k.late = late; k.lift = lift; k.back = back; k.clip = clip; k.tag = tag; k.vs = vs; k.wrap = wrap;
            k.under = under; k.limbSet = limbSet; k.handKey = handKey; k.warp = warp; k.warps = warps; k.ball = ball; k.phase = phase; k.zo = zo;
            k.post = post; k.restLimb = restLimb; k.dirKid = dirKid;
            return k;
        }
    }

    /** A warp by pairs of points: from P to Q, each with a strength K (null = all full); par = the depth slide left over. */
    static final class Wp {
        List<double[]> P, Q;
        double[] K, par;
        boolean flp, moved;
    }

    static final class Ball { JSONObject b; String id; Meshes.Lathe pr; }

    static final class Post { boolean on; Warps.Lattice lat; double[] M, I; }

    Frame(CharacterSolver sv, Signals S, CharacterSolver.Opt opt) {
        this.sv = sv;
        this.ix = sv.ix;
        this.st = sv.st;
        this.S = S;
        this.opt = opt;
        JSONObject s0 = sv.ch.optJSONObject("settings");
        this.set = s0 == null ? new JSONObject() : s0;
        this.live = opt.live;
        this.dt = Limbs.clamp(opt.dt > 0 ? opt.dt : 1 / 60.0, 0.001, 1 / 20.0);
        this.moving = live || opt.tween;
        this.SM = new Signals[] {S, S.mirrored()};
    }

    private static double clamp(double v, double a, double b) { return Limbs.clamp(v, a, b); }

    private static double smooth(double t) { return Limbs.smooth(t); }

    private static double hyp(double x, double y) { return Limbs.hyp(x, y); }

    private static double wrapA(double a) { while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI; return a; }

    private static String id(JSONObject n) { return n.optString("id", ""); }

    private static String type(JSONObject n) { return n.optString("type", ""); }

    private boolean show(JSONObject n) { return opt.show != null && Boolean.TRUE.equals(opt.show.get(id(n))); }

    private boolean hasImg(JSONObject n) { String img = J.s(n, "img"); return img != null && sv.pics.has(img); }

    SolveOut run() {
        /* every button is also a level that eases 0..1 */
        JSONArray trigs = sv.ch.optJSONArray("triggers");
        if (trigs != null) for (int i = 0; i < trigs.length(); i++) {
            JSONObject t = trigs.optJSONObject(i); if (t == null) continue;
            String tid = id(t); double w = S.trig(tid) ? 1 : 0; Double was = st.lvl.get(tid);
            st.lvl.put(tid, live && was != null ? was + (w - was) * Math.min(1, dt * 9) : w);
        }
        /* whole-character mirror */
        String facing = set.optString("facing", "");
        int F = "left".equals(facing) ? -1 : "right".equals(facing) ? 1 : 0, want = st.flipWant;
        if (live && J.t(set, "autoFlip") && F != 0) { double th = J.d(set, "flipAt", 0.4), x = S.turnX * F; if (x < -th) want = 1; else if (x > -th * 0.45) want = 0; }
        st.flipWant = want;
        int target = (want != 0 ? 1 : 0) ^ (S.flip ? 1 : 0);
        String fstyle = J.s(set, "flipStyle") != null && !J.s(set, "flipStyle").isEmpty() ? J.s(set, "flipStyle") : "smooth";
        double fdur = "turn".equals(fstyle) ? 0.16 : J.d(set, "flipTime", 0.14);
        if (!live || "snap".equals(fstyle) || fdur <= 0) st.flip = target; else st.flip = st.flip < target ? Math.min(target, st.flip + dt / fdur) : Math.max(target, st.flip - dt / fdur);
        double fx = Math.cos(st.flip * Math.PI);
        if ("smooth".equals(fstyle)) { double tuck = clamp(J.d(set, "flipTuck", 0.45), 0, 0.92); sqz = 1 - tuck * (1 - Math.pow(Math.abs(fx), 1.4)); fx = (st.flip > 0.5 ? -1 : 1) * sqz; fy = 1 + (1 - sqz) * 0.08; }
        else if (Math.abs(fx) < 0.03) fx = fx < 0 ? -0.03 : 0.03;
        axis = J.d(set, "axisX", sv.w / 2);
        double rs = S.rootScale != 0 ? S.rootScale : 1;
        JSONObject walkSet = J.o(set, "walk");
        double gy = walkSet != null && J.t(walkSet, "groundY") ? walkSet.optDouble("groundY") : sv.h;
        double[] TS = Mat.trs(S.rootX, S.rootY, 0, rs, rs, axis, gy), rootM = Mat.mul(TS, Mat.trs(0, 0, 0, fx, fy, axis, gy));
        out.rootM = rootM;
        flipped = out.flipped = st.flip > 0.5;
        invRoot = Mat.mul(TS, Mat.inv(rootM));
        frame = st.frame = st.frame + 1;
        windIn = S.wind * (flipped ? -1 : 1);
        JSONArray poses = sv.ch.optJSONArray("poses");
        if (poses != null) for (int i = 0; i < poses.length(); i++) {
            JSONObject p = poses.optJSONObject(i); if (p == null) continue;
            Double fw = opt.poses != null ? opt.poses.get(id(p)) : null;
            double w = fw != null ? fw : (S.pose.containsKey(id(p)) ? S.pose.get(id(p)) : 0);
            if (w > 0.0005 || w < -0.0005) PZ.add(new Object[] {p, w});
        }

        Ctx c0 = new Ctx();
        c0.M = rootM; c0.a = 1; c0.rootIn = rootM;
        walk(ix.root, c0);
        /* riders whose limb is drawn above them */
        if (!late.isEmpty()) {
            int added = 0;
            for (Late q : late) {
                int n0 = out.items.size();
                Ctx lc = q.c.copy(); lc.late = true;
                walk(q.n, lc);
                List<Item> made = new ArrayList<>(out.items.subList(n0, out.items.size()));
                out.items.subList(n0, out.items.size()).clear();
                if (!made.isEmpty()) { out.items.addAll(q.at + added, made); added += made.size(); }
            }
        }
        resolveMasks();
        orderUnder();
        orderStances();
        tones(rootM);
        return out;
    }

    private double windAcc(double x) { return windIn * 1500 * (0.6 + 0.4 * Math.sin(S.t * 1.9 + x * 0.013) + 0.25 * Math.sin(S.t * 4.7 + x * 0.031)); }

    private Ctx ctx(Ctx c, double[] Mx) {
        Ctx k = new Ctx();
        k.M = Mx; k.a = c.a; k.mir = c.mir; k.P = c.P; k.rootIn = c.rootIn; k.clip = c.clip; k.tag = c.tag; k.vs = c.vs; k.ghost = c.ghost;
        k.warp = c.warp; k.reach = null; k.ball = c.ball; k.wrap = c.wrap; k.rel = c.rel; k.under = c.under; k.phase = c.phase;
        k.warps = c.warps; k.P0 = c.P0; k.nc = c.nc; k.zo = c.zo; k.post = c.post;
        return k;
    }

    private Item.Z zrec(JSONObject j) { Item.Z z = zRec.get(j); if (z == null) { z = Item.Z.of(j); zRec.put(j, z); } return z; }

    private Item.Z zFront(Item.Z z) { Item.Z f = ZF.get(z.of); if (f == null) { f = new Item.Z("over", z.of, null, null); ZF.put(z.of, f); } return f; }

    private Item.Z zOf(JSONObject n) {
        for (int i = PZ.size() - 1; i >= 0; i--) {
            JSONObject p = (JSONObject) PZ.get(i)[0]; double w = (Double) PZ.get(i)[1];
            JSONObject d = J.o(J.o(p, "d"), id(n)), z = J.o(d, "z");
            if (z != null && w > J.d(z, "at", 0.5)) return zrec(z);
        }
        JSONObject zo = J.o(n, "zo");
        return zo == null ? null : zrec(zo);
    }

    private Signals sg(Ctx c) { return SM[(c.mir ? 1 : 0) ^ (flipped ? 1 : 0)]; }

    /* a round head */
    private double[] ballRot(Ctx c) {
        JSONObject b = c.ball.b; Signals s = sg(c);
        double r0 = c.rel != null ? c.rel[0] : s.turnX, r1 = c.rel != null ? c.rel[1] : s.turnY;
        double th = (r0 * J.d(b, "yaw", 50) + (J.t(b, "spin") ? s.spin * 180 : 0)) * DEG, ph = r1 * J.d(b, "pitch", 30) * DEG;
        return new double[] {Math.cos(th), Math.sin(th), Math.cos(ph), Math.sin(ph), th};
    }

    private double[] ballPt(JSONObject b, double[] r, Meshes.Lathe pr, double x, double y, double lift, boolean back) {
        if (pr != null) {
            int i0 = (int) clamp(Math.round(y - pr.y0), 0, pr.n - 1);
            double nxl = (x - pr.cx[i0]) / pr.R[i0], ext = Math.max(0, Math.abs(nxl) - 1), a = Math.asin(clamp(nxl, -1, 1));
            if (back) a = Math.PI - a;
            a += r[4];
            double zl = Math.cos(a), y2 = y + r[3] * zl * pr.Rmax * 0.5;
            int i1 = (int) clamp(Math.round(y2 - pr.y0), 0, pr.n - 1);
            return new double[] {pr.cx[i1] + pr.R[i1] * (1 + ext + lift) * Math.sin(a), y2, zl};
        }
        double bcx = J.z(b, "cx"), bcy = J.z(b, "cy"), brx = J.z(b, "rx"), bry = J.z(b, "ry");
        double nx = (x - bcx) / brx, ny = (y - bcy) / bry, r2 = nx * nx + ny * ny, qz = 1 - r2, nz = qz > 0 ? Math.sqrt(qz) * Math.sqrt(Math.sqrt(qz)) : 0, k = 1 + lift;
        if (back) nz = -nz;
        nx *= k; ny *= k; nz *= k;
        double x1 = nx * r[0] + nz * r[1], z1 = -nx * r[1] + nz * r[0], y1 = ny * r[2] + z1 * r[3];
        return new double[] {bcx + x1 * brx, bcy + y1 * bry, -ny * r[3] + z1 * r[2]};
    }

    /** Something soft: it overshoots and settles instead of following exactly. */
    private double spring(String key, double v) {
        CharacterSolver.Spring be = st.bnc.get(key);
        if (be == null) { be = new CharacterSolver.Spring(); be.x = v; be.v = 0; be.f = frame; st.bnc.put(key, be); }
        if (frame - be.f > 3) { be.x = v; be.v = 0; }
        if (be.f != frame) { double bdt = Math.min(dt, 1 / 30.0); be.v += ((v - be.x) * 260 - be.v * 13) * bdt; be.x += be.v * bdt; be.f = frame; }
        return be.x;
    }

    private Double sigVal(JSONObject n, String sig, Signals s) {
        if (sig == null) return null;
        if ("always".equals(sig)) return 1.0;
        if ("blink".equals(sig)) return "R".equals(J.s(n, "side")) ? s.blinkR : s.blinkL;
        if (sig.startsWith("trig:")) { Double l = st.lvl.get(sig.substring(5)); return l == null ? 0.0 : l; }
        return s.num(sig);
    }

    private static double pinHold(JSONObject p, Map<String, Double> weightOf) {
        String home = J.s(p, "home");
        if (home == null || !home.startsWith("pose:")) return 1;
        Double w0 = weightOf.get(home);
        double w = Math.abs(w0 == null ? 0 : w0);
        return w > 1 ? 1 : w;
    }

    /* SHAPES: where each pin of a part is now */
    private Wp pinTargets(JSONObject n, Signals s, Ctx c) {
        Wp r = new Wp(); r.P = new ArrayList<>(); r.Q = new ArrayList<>();
        JSONObject mesh = J.o(n, "mesh"); JSONArray pins = J.a(mesh, "pins"), shp = J.a(n, "shapes");
        int ns = shp == null ? 0 : shp.length();
        Map<String, Double> forced = opt.shapes != null ? opt.shapes.get(id(n)) : null;
        double[] W = new double[ns];
        final Map<String, Double> wOf = new HashMap<>();
        for (int j = 0; j < ns; j++) {
            JSONObject sh = shp.optJSONObject(j); double w = 0;
            if (forced != null) { Double fv = forced.get(id(sh)); w = fv == null ? 0 : fv; }
            else { Double sv0 = sigVal(n, J.s(sh, "sig"), s); if (sv0 != null) { w = clamp(sv0 * J.d(sh, "amt", 1), -1.5, 1.5); if (J.t(sh, "bounce") && moving) w = spring(id(n) + ":s:" + id(sh) + c.tag, w); } }
            W[j] = w;
            if (!wOf.containsKey(id(sh))) wOf.put(id(sh), w);
        }
        int np = pins == null ? 0 : pins.length();
        r.K = new double[np];
        for (int i = 0; i < np; i++) {
            JSONObject p = pins.optJSONObject(i);
            double px = J.x(p), py = J.y(p); double[] q = {px, py};
            String role = J.s(p, "role");
            Signals.Hand hp = "handL".equals(role) ? s.hand.get("L") : "handR".equals(role) ? s.hand.get("R") : null;
            for (int j = 0; j < ns; j++) {
                JSONObject dd = J.o(shp.optJSONObject(j), "d"); JSONArray d = W[j] != 0 && dd != null ? dd.optJSONArray(id(p)) : null;
                if (d != null) { q[0] += d.optDouble(0, 0) * W[j]; q[1] += d.optDouble(1, 0) * W[j]; r.moved = true; }
            }
            double[] sp = s.pin.get(id(p));
            if (sp != null) { q = new double[] {sp[0], sp[1]}; r.moved = true; }
            else if (hp != null) { q = new double[] {hp.x, hp.y}; r.moved = true; }
            else if ("head".equals(role)) { q = new double[] {q[0] + s.turnX * J.d(mesh, "headMove", 10), q[1] + s.turnY * 7 - s.breath * 1.5}; r.moved = true; }
            r.P.add(new double[] {px, py}); r.Q.add(q); r.K[i] = J.s(p, "home") != null && !J.s(p, "home").isEmpty() ? pinHold(p, wOf) : 1;
        }
        return r;
    }

    private double[] centreOf(JSONObject n) {
        double[] pv = J.pt(n, "pivot"); if (pv != null) return pv;
        double[] b = ix.boundsOf(n);
        return b != null ? new double[] {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2} : new double[] {0, 0};
    }

    private double[] pivotOf(JSONObject n) {
        double[] pv = J.pt(n, "pivot"); if (pv != null) return pv;
        if ("art".equals(type(n)) && hasImg(n)) { String img = J.s(n, "img"); return new double[] {J.z(n, "x") + sv.pics.w(img) / 2.0, J.z(n, "y") + sv.pics.h(img) / 2.0}; }
        return new double[] {0, 0};
    }

    private double[] local(JSONObject n, Ctx c) {
        Signals s = sg(c);
        double tx = 0, ty = 0, rot = 0, sx = 1, sy = 1; boolean any = false;
        JSONArray links = J.a(n, "links");
        if (links != null) for (int i = 0; i < links.length(); i++) {
            JSONObject l = links.optJSONObject(i); if (l == null) continue;
            Double v0 = sigVal(n, J.s(l, "sig"), s); if (v0 == null) continue;
            any = true; double v = v0 + J.z(l, "bias");
            if (J.t(l, "bounce") && moving) v = spring(id(n) + ":" + i + c.tag, v);
            if (J.t(l, "osc")) v *= moving ? Math.sin(S.t * 6.2832 * l.optDouble("osc") + i * 1.3) : 0;
            String prop = J.s(l, "prop"); double amt = J.d(l, "amt", Double.NaN);
            if ("shake".equals(prop)) { tx += Math.sin(S.t * 67 + i * 2.1) * v * amt; ty += Math.cos(S.t * 83 + i) * v * amt * 0.7; continue; }
            if ("spin".equals(prop)) {
                String key = id(n) + ":" + i; CharacterSolver.Spin se = st.spin.get(key);
                if (se == null) { se = new CharacterSolver.Spin(); st.spin.put(key, se); }
                if (se.f != frame) { se.f = frame; if (moving) se.a += v * amt * 9 * DEG * dt; }
                rot += se.a; continue;
            }
            if ("x".equals(prop)) tx += v * amt; else if ("y".equals(prop)) ty += v * amt; else if ("rot".equals(prop)) rot += v * amt * DEG;
            else if ("sx".equals(prop)) sx *= 1 + v * amt; else if ("sy".equals(prop)) sy *= 1 + v * amt; else if ("s".equals(prop)) { sx *= 1 + v * amt; sy *= 1 + v * amt; }
        }
        Double nsc = J.num(n, "sc");
        if (nsc != null && nsc != 1) { sx *= nsc; sy *= nsc; any = true; }
        if (J.t(n, "rot")) { rot += n.optDouble("rot") * DEG; any = true; }
        if (J.t(n, "dx") || J.t(n, "dy")) { tx += J.z(n, "dx"); ty += J.z(n, "dy"); any = true; }
        for (Object[] pz : PZ) {
            JSONObject pd = J.o(J.o((JSONObject) pz[0], "d"), id(n)); if (pd == null) continue;
            double pw = (Double) pz[1], pr = J.z(pd, "rot") * DEG * pw;
            tx += J.z(pd, "x") * pw; ty += J.z(pd, "y") * pw; rot += pr;
            Double psc0 = J.num(pd, "sc");
            if (psc0 != null && psc0 != 1) { double psc = 1 + (psc0 - 1) * pw; sx *= psc; sy *= psc; }
            JSONObject orb = J.o(pd, "orbit");
            if (orb != null && J.t(orb, "deg")) {
                double po = orb.optDouble("deg") * DEG * pw; double[] pv1 = pivotOf(n);
                double vx1 = pv1[0] - J.x(orb), vy1 = pv1[1] - J.y(orb), cp = Math.cos(po), sp1 = Math.sin(po);
                tx += cp * vx1 - sp1 * vy1 - vx1; ty += sp1 * vx1 + cp * vy1 - vy1;
            }
            any = true;
        }
        if (J.t(n, "flip")) { sx = -sx; any = true; }
        if (moving && J.t(n, "depth") && c.P != null && !(c.ball != null && J.truthy(c.wrap))) { tx += n.optDouble("depth") * c.P[0]; ty += n.optDouble("depth") * c.P[1]; any = true; }
        JSONObject look = J.o(n, "look");
        if (look != null) { tx += s.lookX * J.z(look, "rx"); ty += s.lookY * J.z(look, "ry"); any = true; }
        if (s.mouth != null) {
            JSONObject hinge = J.o(n, "hinge"), squash = J.o(n, "squash");
            if (hinge != null) { rot += ("closed".equals(J.s(hinge, "rest")) ? s.mouth : 1 - s.mouth) * J.z(hinge, "angle") * DEG; any = true; }
            if (squash != null) { double open = "closed".equals(J.s(squash, "rest")) ? 1 - s.mouth : s.mouth, mn = J.z(squash, "min"); sy *= mn + (1 - mn) * open; any = true; }
        }
        JSONObject lid = J.o(n, "lid");
        if (lid != null) {
            double shut = J.t(set, "blinkLids") ? ("R".equals(J.s(n, "side")) ? s.blinkR : s.blinkL) : 0;
            if ("lower".equals(J.s(lid, "kind"))) { ty -= Math.max(s.lidLow, shut * 0.35) * J.z(lid, "travel"); rot -= s.lidTilt * J.z(lid, "tilt") * 0.25 * DEG * J.z(lid, "sign"); }
            else { ty += Math.max(s.lidUp, shut) * J.z(lid, "travel"); rot += s.lidTilt * J.z(lid, "tilt") * DEG * J.z(lid, "sign"); }
            any = true;
        }
        JSONObject swing = J.o(n, "swing");
        if (live && swing != null && "stiff".equals(J.s(swing, "kind")) && !J.isFalse(swing, "on")) { CharacterSolver.Stiff q = st.stiff.get(id(n)); if (q != null && q.f == frame) { rot += q.out * q.sgn; any = true; } }
        if (!any) return null;
        double[] p = pivotOf(n);
        if (moving && c.ball != null && "on".equals(c.wrap)) { double[] bq = ballPt(c.ball.b, ballRot(c), c.ball.pr, p[0], p[1], 0, J.t(n, "back")); p = new double[] {bq[0], bq[1]}; }
        return Mat.trs(tx, ty, rot, sx, sy, p[0], p[1]);
    }

    private double[] worldOf(String nid) {
        double[] had = wm.get(nid); if (had != null) return had;
        JSONObject n = ix.n(nid); if (n == null) return Mat.id();
        String att = J.s(n, "attach");
        String par = att != null && ix.n(att) != null ? att : (ix.p(nid) != null ? id(ix.p(nid)) : null);
        wm.put(nid, Mat.id());                             // guards a cycle
        double[] base = par != null ? worldOf(par) : Mat.id();
        JSONObject swing = J.o(n, "swing");
        if (live && swing != null && "stiff".equals(J.s(swing, "kind")) && !J.isFalse(swing, "on")) stepStiff(n, base);
        Ctx lc = new Ctx();
        double[] L = local(n, lc), r = L != null ? Mat.mul(base, L) : base;
        wm.put(nid, r);
        return r;
    }

    private boolean gated(JSONObject n, Ctx c) {
        if (show(n)) return false;
        Signals s = sg(c);
        if (J.t(n, "blink")) {
            double b = J.t(set, "blinkLids") ? 0 : ("R".equals(J.s(n, "side")) ? s.blinkR : s.blinkL);
            if ("open".equals(J.s(n, "blink")) ? b > 0.5 : b <= 0.5) return true;
        }
        String tg = J.s(n, "trig");
        if (tg != null && !tg.isEmpty() && !c.picked && !s.trig(tg)) return true;
        JSONArray ho = J.a(n, "hideOn");
        if (ho != null) for (int i = 0; i < ho.length(); i++) if (s.trig(ho.optString(i))) return true;
        return false;
    }

    private void draw(JSONObject n, double[] Mx, Ctx c) {
        String t = type(n);
        if ("art".equals(t)) emit(n, Mx, c);
        else if ("set".equals(t)) walkSet(n, Mx, c);
        else { List<JSONObject> kids = ix.kids(n); for (int i = kids.size() - 1; i >= 0; i--) walk(kids.get(i), ctx(c, Mx)); }
    }

    /* RIDES: carried by the end of a limb or a swinging part */
    private Ctx ride(JSONObject n, Ctx c, SolveOut.Tip tp) {
        JSONObject rd = J.o(n, "ride"), pk = J.o(n, "pick");
        double turn = J.d(rd, "turn", 1), base = tp.rest; double[] pv = J.pt(n, "pivot"); JSONObject kid = null;
        if ("set".equals(type(n)) && pk != null && "dir".equals(J.s(pk, "by"))) {
            String prevId = st.pick.get(id(n)); double bd = 1e9, pd = 1e9; JSONObject prev = null;
            for (JSONObject k : ix.kids(n)) {
                Double sel = J.num(k, "when") != null ? J.num(k, "when") : J.num(k, "dir");
                if (sel == null || J.t(k, "trig")) continue;
                double d = Math.abs(wrapA(tp.ang - Mat.wAng(c.M, sel * DEG)));
                if (d < bd) { bd = d; kid = k; }
                if (id(k).equals(prevId)) { pd = d; prev = k; }
            }
            if (live && prev != null && pd - bd < 0.14) kid = prev;
            if (kid != null) {
                Double dir = J.num(kid, "dir") != null ? J.num(kid, "dir") : J.num(n, "dir") != null ? J.num(n, "dir") : J.num(kid, "when");
                base = Mat.wAng(c.M, (dir == null ? 0 : dir) * DEG);
                double[] kp = J.pt(kid, "pivot"); if (kp != null) pv = kp;
            }
        }
        if (kid == null && J.num(n, "dir") != null) base = Mat.wAng(c.M, n.optDouble("dir") * DEG);
        double[] pw = pv != null ? Mat.ap(c.M, pv[0], pv[1]) : tp.restP;
        double th = wrapA(tp.ang - base) * turn;
        if (J.t(rd, "loose") && live) {
            double loose = rd.optDouble("loose");
            CharacterSolver.Spring re = st.ride.get(id(n));
            if (re == null) { re = new CharacterSolver.Spring(); re.x = th; re.v = 0; re.f = frame; st.ride.put(id(n), re); }
            if (frame - re.f > 3) { re.x = th; re.v = 0; }
            if (re.f != frame) { double rdt = Math.min(dt, 1 / 30.0), rkk = 420 - loose * 330, rcc = 2 * Math.sqrt(rkk) * (0.62 - loose * 0.34); re.v += (wrapA(th - re.x) * rkk - re.v * rcc) * rdt; re.x += re.v * rdt; re.f = frame; }
            th = re.x;
        }
        Ctx k = c.copy();
        k.M = Mat.mul(Mat.mul(Mat.trs(tp.p[0] - pw[0], tp.p[1] - pw[1], 0, 1, 1, 0, 0), Mat.trs(0, 0, th, 1, 1, pw[0], pw[1])), c.M);
        k.dirKid = kid; k.late = false;
        k.under = J.t(rd, "front") ? c.under : (tp.under != null ? tp.under : c.under);
        k.zo = tp.zo != null ? tp.zo : c.zo;
        return k;
    }

    /* STREAM: one drawing sent out as several copies */
    private void stream(JSONObject n, double[] Mx, Ctx c) {
        JSONObject em = J.o(n, "emit");
        int cnt = (int) clamp(Math.round(J.t(em, "n") ? em.optDouble("n") : 4), 1, 24);
        double life = Math.max(0.15, J.t(em, "life") ? em.optDouble("life") : 1.4); double[] pv = centreOf(n);
        double s0 = J.d(em, "s0", 1), s1 = J.d(em, "s1", 1), tt = Math.max(0, sg(c).t) / life, fo = Math.max(0.02, J.d(em, "fade", 0.35));
        List<Double> list = new ArrayList<>();
        for (int e = 0; e < cnt; e++) list.add(((tt + (double) e / cnt) % 1 + 1) % 1);
        list.sort((a, b) -> Double.compare(b, a));
        for (int e = 0; e < cnt; e++) {
            double ph = list.get(e), sc = s0 + (s1 - s0) * ph, env = Math.min(1, Math.min(ph / 0.1, (1 - ph) / fo)), wob = J.z(em, "sway") * Math.sin(ph * 9.4 + e * 2.4);
            Ctx k = c.copy(); k.a = c.a * env; k.phase = ph;
            draw(n, Mat.mul(Mx, Mat.trs(J.z(em, "dx") * ph + wob, J.z(em, "dy") * ph, J.z(em, "spin") * ph * DEG, sc, sc, pv[0], pv[1])), k);
        }
    }

    private void walk(JSONObject n, Ctx c) {
        if (J.t(n, "hidden") && !c.picked && !show(n)) return;
        if (gated(n, c)) return;
        JSONObject rd = J.o(n, "ride");
        if (rd != null && ix.n(J.s(rd, "of")) != null && !c.ghost) {
            SolveOut.Tip tp = out.tips.get(J.s(rd, "of"));
            if (tp == null && !c.late) { Late l = new Late(); l.n = n; l.c = c; l.at = out.items.size(); late.add(l); return; }
            if (tp != null) c = ride(n, c, tp);
        }
        double[] Mx = c.M;
        if (J.t(n, "noTone") && !c.nc) { c = c.copy(); c.nc = true; }
        String att = J.s(n, "attach");
        if (att != null && ix.n(att) != null) Mx = Mat.mul(c.rootIn, worldOf(att));
        JSONObject swing = J.o(n, "swing");
        if (live && swing != null && "stiff".equals(J.s(swing, "kind")) && !J.isFalse(swing, "on")) stepStiff(n, Mat.mul(invRoot, Mx));
        String nm = J.s(n, "name");
        if (c.warps != null && nm != null && !nm.isEmpty()) {
            String key = nm.toLowerCase().trim();
            if (c.warps.containsKey(key)) { Wp wk = c.warps.get(key); Ctx k = c.copy(); k.warp = wk; k.warps = null; k.P = wk != null && wk.par != null ? wk.par : (c.P0 != null ? c.P0 : c.P); c = k; }
        }
        Wp gp = null;
        JSONObject mesh = J.o(n, "mesh"); JSONArray mpins = J.a(mesh, "pins");
        if (!"art".equals(type(n)) && mpins != null && mpins.length() > 0) {
            gp = pinTargets(n, sg(c), c);
            if (gp.moved && !J.t(mesh, "last")) {
                Ctx k = c.copy();
                if (c.warp != null) {
                    Wp m = new Wp(); m.P = new ArrayList<>(c.warp.P); m.P.addAll(gp.P); m.Q = new ArrayList<>(c.warp.Q); m.Q.addAll(gp.Q);
                    double[] k0 = c.warp.K; m.K = new double[c.warp.P.size() + gp.K.length];
                    for (int i = 0; i < c.warp.P.size(); i++) m.K[i] = k0 != null ? k0[i] : 1;
                    System.arraycopy(gp.K, 0, m.K, c.warp.P.size(), gp.K.length);
                    k.warp = m;
                } else k.warp = gp;
                c = k;
            }
        }
        JSONObject ballJ = J.o(n, "ball");
        if (ballJ != null && !J.isFalse(ballJ, "on")) {
            Ball b = new Ball(); b.b = ballJ; b.id = J.s(ballJ, "shell") != null && !J.s(ballJ, "shell").isEmpty() ? J.s(ballJ, "shell") : id(n);
            b.pr = "lathe".equals(J.s(ballJ, "kind")) ? lathe(ballJ) : null;
            c = c.copy(); c.ball = b;
        }
        double[] out2 = null, an = null, dec = null, Mx0 = Mx;
        String wrap = J.s(n, "wrap");
        boolean wrapT = J.truthy(n.opt("wrap"));
        if (wrapT && !"out".equals(wrap) && !"art".equals(type(n)) && c.ball != null && moving) {
            double[] da = centreOf(n), br0 = ballRot(c); JSONObject b0 = c.ball.b; double l0 = J.z(n, "lift"); boolean bk = J.t(n, "back");
            double[] f0 = ballPt(b0, br0, c.ball.pr, da[0], da[1], l0, bk), f1 = ballPt(b0, br0, c.ball.pr, da[0] + 1, da[1], l0, bk), f2 = ballPt(b0, br0, c.ball.pr, da[0], da[1] + 1, l0, bk);
            double kp = J.d(n, "keep", 0.6), zmin = kp > 0 ? -0.04 : 0.12;
            if (f0[2] < zmin) return;
            double j00 = f1[0] - f0[0], j10 = f1[1] - f0[1], j01 = f2[0] - f0[0], j11 = f2[1] - f0[1];
            if (kp > 0) {
                double kx = hyp(j00, j10), ky = hyp(j01, j11); j00 = kx + (1 - kx) * kp; j11 = ky + (1 - ky) * kp; j10 = j01 = 0;
                Meshes.Lathe prr = c.ball.pr; double[] bn = prr != null ? ix.boundsOf(n) : null;
                if (bn != null) {
                    int ir = (int) clamp(Math.round(f0[1] - prr.y0), 0, prr.n - 1); double hw = (bn[2] - bn[0]) / 2, room = prr.R[ir] - Math.abs(f0[0] - prr.cx[ir]);
                    if (hw > 1 && j00 * hw > room + hw * 0.18) j00 = Math.max(0.46, (room + hw * 0.18) / hw);
                }
            }
            dec = new double[] {j00, j10, j01, j11, f0[0] - j00 * da[0] - j01 * da[1], f0[1] - j10 * da[0] - j11 * da[1]};
            c = c.copy(); c.wrap = null; c.a = c.a * smooth((f0[2] - zmin) / 0.14);
        } else if (wrapT && c.ball != null && moving) {
            if ("out".equals(wrap)) {
                an = centreOf(n); out2 = ballPt(c.ball.b, ballRot(c), c.ball.pr, an[0], an[1], J.z(n, "lift"), J.t(n, "back")); out2[0] -= an[0]; out2[1] -= an[1];
                Ctx k = c.copy(); k.wrap = null; k.under = out2[2] < -0.03 ? c.ball.id : c.under; c = k;
            } else { Ctx k = c.copy(); k.wrap = "on"; k.back = J.t(n, "back"); c = k; }
        }
        if (dec != null) Mx = Mat.mul(Mx, dec);
        if (out2 != null) Mx0 = Mx = Mat.mul(Mx, Mat.trs(out2[0], out2[1], 0, J.t(n, "flat") ? clamp(Math.abs(out2[2]) / (1 + J.z(n, "lift")), 0.2, 1) : 1, 1, an[0], an[1]));
        out.before.put(id(n), Mx);
        double[] L = local(n, c); if (L != null) Mx = Mat.mul(Mx, L);
        out.m.put(id(n), Mx);
        if (!"art".equals(type(n)) && mesh != null && J.t(mesh, "last")) {
            boolean bend = gp != null && gp.moved;
            Post post = new Post();
            if (bend) {
                double[] bb = ix.boundsOf(n);
                List<double[]> hold = holdOf(n, bb);
                List<double[]> P = new ArrayList<>(gp.P), Q = new ArrayList<>(gp.Q); P.addAll(hold); Q.addAll(hold);
                double[] K = new double[gp.K.length + hold.size()]; System.arraycopy(gp.K, 0, K, 0, gp.K.length); for (int i = gp.K.length; i < K.length; i++) K[i] = 1;
                Warps.Lattice lat = Warps.sheetLattice(bb, P, Q, K);
                if (lat != null) { post.on = true; post.lat = lat; post.M = Mx; post.I = Mat.inv(Mx); }
            }
            c = c.copy(); c.post = post;
        }
        Item.Z zo1 = (!PZ.isEmpty() || J.has(n, "zo")) ? zOf(n) : null;
        if (zo1 != null) { c = c.copy(); c.zo = zo1; }
        if (swing != null && "stiff".equals(J.s(swing, "kind")) && J.o(swing, "tip") != null && !c.ghost) {
            double[] pv0 = pivotOf(n), tip = J.pt(swing, "tip"); double ra = Math.atan2(tip[1] - pv0[1], tip[0] - pv0[0]);
            SolveOut.Tip t = new SolveOut.Tip(); t.p = Mat.ap(Mx, tip[0], tip[1]); t.ang = Mat.wAng(Mx, ra); t.restP = Mat.ap(Mx0, tip[0], tip[1]); t.rest = Mat.wAng(Mx0, ra); t.under = c.under;
            out.tips.put(id(n), t);
        }
        JSONArray clips = J.a(n, "clips");
        if (clips != null && clips.length() > 0) { Ctx k = c.copy(); k.clip = new ArrayList<>(c.clip == null ? new ArrayList<>() : c.clip); for (int i = 0; i < clips.length(); i++) k.clip.add(clips.optJSONObject(i)); c = k; }
        int mark = out.items.size();
        JSONObject emitJ = J.o(n, "emit");
        if (emitJ != null && !J.isFalse(emitJ, "on") && moving && !c.ghost) stream(n, Mx, c); else draw(n, Mx, c);
        if (out.items.size() > mark) { out.first.put(id(n), out.items.get(mark)); out.last.put(id(n), out.items.get(out.items.size() - 1)); }
        if (sv.maskIds.containsKey(id(n))) masks.put(id(n) + c.tag, new ArrayList<>(out.items.subList(mark, out.items.size())));
        if (J.t(n, "maskOnly") && !show(n)) out.items.subList(mark, out.items.size()).clear();
    }

    private List<double[]> holdOf(JSONObject n, double[] b) {
        if (b == null) return new ArrayList<>();
        String key = id(n) + "|" + b[0] + "," + b[1] + "," + b[2] + "," + b[3];
        List<double[]> h = sv.holds.get(key);
        if (h == null) { if (sv.holds.size() > 200) sv.holds.clear(); h = Warps.sheetHold(b); sv.holds.put(key, h); }
        return h;
    }

    private Meshes.Lathe lathe(JSONObject b) {
        JSONObject n = ix.n(J.s(b, "shell"));
        if (n == null || !"art".equals(type(n)) || !hasImg(n)) return null;
        String img = J.s(n, "img");
        String key = img + "|" + sv.pics.ver(img) + "|" + J.z(n, "x") + "," + J.z(n, "y");
        if (sv.lathes.containsKey(key)) return sv.lathes.get(key);
        Meshes.Lathe L = Meshes.lathe(J.z(n, "x"), J.z(n, "y"), sv.pics.w(img), sv.pics.h(img), sv.pics.alpha(img));
        if (L != null) sv.lathes.put(key, L);
        return L;
    }

    private Ctx kidCtx(Ctx c, double[] Mx, double a) { Ctx k = ctx(c, Mx); k.a = a; k.picked = true; return k; }

    private double[] effOf(JSONObject q) {
        JSONObject qs = "mirror".equals(type(q)) ? ix.n(J.s(q, "of")) : null;
        if (qs != null) return new double[] {J.z(q, "rot") - J.z(qs, "rot"), J.d(q, "sc", 1) * J.d(qs, "sc", 1), J.z(q, "dx") - J.z(qs, "dx"), J.z(q, "dy") + J.z(qs, "dy")};
        return new double[] {J.z(q, "rot"), J.d(q, "sc", 1), J.z(q, "dx"), J.z(q, "dy")};
    }

    private void walkSet(JSONObject n, double[] Mx0, Ctx c) {
        Signals s = sg(c);
        JSONObject pk = J.o(n, "pick"); if (pk == null) pk = new JSONObject();
        final JSONObject pkF = pk;
        String by = J.s(pk, "by") == null ? "" : J.s(pk, "by");
        List<JSONObject> kids = ix.kids(n);
        if (kids.isEmpty()) return;
        double[] Mx = Mx0;
        String fid = opt.force != null ? opt.force.get(id(n)) : null;
        JSONObject forced = fid != null && ix.n(fid) != null && ix.p(fid) == n ? ix.n(fid) : null;
        JSONObject trigKid = null;
        for (JSONObject k : kids) { String tg = J.s(k, "trig"); if (tg != null && !tg.isEmpty() && s.trig(tg)) { trigKid = k; break; } }
        String prevId = st.pick.get(id(n));
        JSONObject prev = prevId != null && ix.p(prevId) == n ? ix.n(prevId) : null, cur = null;
        double[] T = null;
        double tx = 0, ty = 0;
        double[] mix = null;
        JSONObject restKid = null;
        boolean reachSet = "reach".equals(by);

        if ("turn".equals(by)) {
            double[] dl = J.t(pk, "dial") ? S.dial.get(J.s(pk, "dial")) : null;
            tx = dl != null ? dl[0] * ((((c.mir ? 1 : 0) ^ (flipped ? 1 : 0)) != 0) ? -1 : 1) : s.turnX;
            ty = dl != null ? dl[1] : s.turnY;
            Double gain = J.num(pk, "gain");
            if (gain != null && gain != 1) tx = clamp(tx * gain, -1, 1);
            JSONObject best = null; double bd = 1e9, pd = 1e9;
            for (JSONObject k : kids) { double[] at = J.xy(k, "at"); if (at == null) continue; double d = hyp(at[0] - tx, at[1] - ty); if (d < bd) { bd = d; best = k; } if (k == prev) pd = d; }
            cur = (live && prev != null && J.xy(prev, "at") != null && pd - bd < 0.07) ? prev : best;
            if (sqz < 0.999 && !J.t(pk, "dial")) {
                boolean lft = false, rgt = false;
                for (JSONObject q : kids) { double[] at = J.xy(q, "at"); if (at == null) continue; if (at[0] < -0.1) lft = true; if (at[0] > 0.1) rgt = true; }
                if (lft && rgt) {
                    double[] pv = J.pt(n, "pivot"), hb = pv != null ? null : ix.boundsOf(n);
                    double[] hp = pv != null ? pv : (hb != null ? new double[] {(hb[0] + hb[2]) / 2, hb[3]} : new double[] {axis, 0});
                    Mx = Mat.mul(Mx, Mat.trs(0, 0, 0, 1 / sqz, 1 / fy, hp[0], hp[1]));
                }
            }
            boolean anyTip = false;
            for (JSONObject q : kids) if (J.xy(q, "at") != null && (J.t(q, "rot") || J.t(q, "dx") || J.t(q, "dy") || (J.num(q, "sc") != null && J.num(q, "sc") != 1))) anyTip = true;
            if (moving && anyTip) {
                double gw = 0, gr = 0, gs = 0, gx = 0, gyy = 0;
                for (JSONObject q : kids) {
                    double[] at = J.xy(q, "at"); if (at == null) continue;
                    double[] ef = effOf(q); double dq = hyp(at[0] - tx, at[1] - ty), g = 1 / (dq * dq * dq + 1e-6);
                    gw += g; gr += g * ef[0]; gs += g * Math.log(Math.max(0.01, ef[1])); gx += g * ef[2]; gyy += g * ef[3];
                }
                if (gw != 0) mix = new double[] {gr / gw, Math.exp(gs / gw), gx / gw, gyy / gw};
            }
        } else if (reachSet) {
            String hk = J.s(pk, "hand");
            Signals.Hand hand = hk == null ? null : s.hand.get(hk);
            boolean foot = hk != null && !hk.isEmpty() && hk.charAt(0) == 'F';
            restKid = ix.n(J.s(pk, "rest"));
            if (restKid == null || J.o(restKid, "limb") == null) { restKid = null; for (JSONObject k : kids) if (J.o(k, "limb") != null) { restKid = k; break; } }
            double[] rAt = J.o(pk, "restAt") != null && moving ? J.pt(pk, "restAt") : (restKid != null ? J.pt(J.o(restKid, "limb"), "c") : null);
            double[] Tw = hand != null ? ((foot || hand.w) ? new double[] {hand.x, hand.y} : Mat.ap(c.rootIn, hand.x, hand.y)) : (rAt != null ? Mat.ap(Mx, rAt[0], rAt[1]) : null);
            if (hand != null && hand.k != null && hand.k < 1 && Tw != null && rAt != null) { double[] fr0 = Mat.ap(Mx, rAt[0], rAt[1]); Tw = new double[] {fr0[0] + (Tw[0] - fr0[0]) * hand.k, fr0[1] + (Tw[1] - fr0[1]) * hand.k}; }
            if (hand == null && !foot && restKid != null && live && s.walk != 0) {
                JSONObject wk = J.o(set, "walk"); Double asw = s.armSide != null ? s.armSide.get(hk) : null;
                double sa = (wk != null && J.num(wk, "armSwing") != null ? wk.optDouble("armSwing") : 18) * DEG * s.walk * (asw != null ? asw : ("L".equals(hk) ? 1 : -1));
                JSONObject lim = J.o(restKid, "limb"); double[] la = J.pt(lim, "a"), lc = rAt != null ? rAt : J.pt(lim, "c");
                double cs = Math.cos(sa), sn = Math.sin(sa);
                Tw = Mat.ap(Mx, la[0] + cs * (lc[0] - la[0]) - sn * (lc[1] - la[1]), la[1] + sn * (lc[0] - la[0]) + cs * (lc[1] - la[1]));
            }
            if (Tw != null) { double[] iv = Mat.inv(Mx), tl = Mat.ap(iv, Tw[0], Tw[1]); T = new double[] {tl[0], tl[1]}; }
            if (hk != null && !hk.isEmpty() && restKid != null && !c.ghost) { double[] la = J.pt(J.o(restKid, "limb"), "a"); S.anchor.put(hk, Mat.ap(Mx, la[0], la[1])); }
            if (live && T != null && !c.ghost) {
                String rk = id(n) + c.tag;
                CharacterSolver.Reach rq = st.rb.get(rk);
                if (rq == null) { rq = new CharacterSolver.Reach(); rq.m = -1; rq.t = 0; rq.x = T[0]; rq.y = T[1]; rq.f = frame; st.rb.put(rk, rq); }
                int md = hand != null ? ((hand.w || foot) ? 2 : 1) : 0;
                if (rq.m != md && rq.m >= 0 && frame - rq.f <= 2 && !foot) { rq.t = 1; rq.fx = rq.x; rq.fy = rq.y; }
                rq.m = md;
                if (rq.t > 0) { rq.t -= dt / 0.3; double rw = smooth(clamp(1 - rq.t, 0, 1)); T = new double[] {rq.fx + (T[0] - rq.fx) * rw, rq.fy + (T[1] - rq.fy) * rw}; }
                rq.x = T[0]; rq.y = T[1]; rq.f = frame;
            }
            if (T != null) {
                JSONObject bk = null; double bdist = 1e9, pdist = 1e9;
                for (JSONObject k : kids) { JSONObject lim = J.o(k, "limb"); if (lim == null) continue; double[] lc = J.pt(lim, "c"); double dd = hyp(lc[0] - T[0], lc[1] - T[1]); if (dd < bdist) { bdist = dd; bk = k; } if (k == prev) pdist = dd; }
                cur = (live && prev != null && J.o(prev, "limb") != null && !J.isFalse(pk, "swap") && pdist < bdist * 1.18 + 3) ? prev : bk;
                if (J.isFalse(pk, "swap") && restKid != null) cur = restKid;
            } else cur = restKid != null ? restKid : kids.get(0);
            JSONObject pw = J.o(pk, "walk");
            if (hand != null && hand.w && !foot && T != null && !(pw != null && J.t(pw, "any"))) {
                JSONObject wkid = pw != null && J.t(pw, "kid") ? ix.n(J.s(pw, "kid")) : null;
                if (wkid == null || ix.p(id(wkid)) != n || J.o(wkid, "limb") == null) wkid = restKid;
                if (wkid != null) cur = wkid;
            }
            if (J.t(pk, "loop") && moving && T != null) {
                List<JSONObject> lf = new ArrayList<>(); for (JSONObject q : kids) if (J.o(q, "limb") != null && !J.t(q, "trig")) lf.add(q);
                if (!lf.isEmpty()) cur = lf.get((int) (Math.floor(Math.max(0, s.t) * pk.optDouble("loop")) % lf.size()));
            }
        } else if ("cycle".equals(by)) {
            List<JSONObject> fr = new ArrayList<>(); for (JSONObject q : kids) if (!J.t(q, "trig")) fr.add(q);
            if (!fr.isEmpty() && c.phase != null && J.t(pk, "life")) cur = fr.get((int) Math.min(fr.size() - 1, Math.floor(c.phase * fr.size())));
            else if (!fr.isEmpty() && moving) cur = fr.get((int) (Math.floor(Math.max(0, s.t) * (J.t(pk, "fps") ? pk.optDouble("fps") : 12) + (c.phase != null ? c.phase : 0) * fr.size()) % fr.size()));
        } else if ("dir".equals(by)) {
            cur = c.dirKid;
        } else if ("viseme".equals(by)) {
            if (s.mouth != null) {
                List<String> want = new ArrayList<>(); want.add(s.viseme);
                String[] fb = VFALL.get(s.viseme); if (fb != null) for (String f : fb) want.add(f);
                if (s.mouth > 0.25) { want.add("Aa"); want.add("Oh"); want.add("Ee"); }
                want.add("Neutral"); want.add("M");
                for (int wi = 0; wi < want.size() && cur == null; wi++) for (JSONObject k : kids) if (want.get(wi) != null && want.get(wi).equals(J.s(k, "shape"))) { cur = k; break; }
            }
        }
        if (cur == null) { String rid = J.s(pk, "rest"); cur = ix.n(rid) != null && ix.p(rid) == n ? ix.n(rid) : null; }
        if (cur == null) for (JSONObject k : kids) if (!J.t(k, "trig")) { cur = k; break; }
        if (trigKid != null) cur = trigKid;
        if (forced != null) cur = forced;
        if (cur == null) cur = kids.get(0);
        if (forced != null && J.o(forced, "limb") != null && !live) T = J.pt(J.o(forced, "limb"), "c");
        boolean mo = opt.morphSet != null && opt.morphSet.equals(id(n)) && ix.n(opt.morphA) != null && ix.n(opt.morphB) != null;
        if (mo) cur = ix.n(opt.morphB);

        CharacterSolver.Fade fade = st.fade.get(id(n));
        boolean looks = !"turn".equals(by) && !"reach".equals(by) && !"cycle".equals(by) && Marks.has(n);
        double fms = J.num(pk, "fade") == null ? (looks ? 170 : 110) : pk.optDouble("fade");
        if (live && prev != null && prev != cur && fms > 0 && (looks ? Marks.bridgeFor(sv.w, n, prev, cur, "", true) != null : (("turn".equals(by) || "reach".equals(by)) && !J.t(pk, "loop")))) {
            fade = new CharacterSolver.Fade(); fade.from = id(prev); fade.t = 0; fade.morph = looks; st.fade.put(id(n), fade);
        }
        st.pick.put(id(n), id(cur)); out.picks.put(id(n), id(cur));
        if (fade != null) { fade.t += dt * 1000 / Math.max(1, fms); if (fade.t >= 1 || !live || fade.from.equals(id(cur)) || ix.n(fade.from) == null) { st.fade.remove(id(n)); fade = null; } }
        if (mo) { fade = new CharacterSolver.Fade(); fade.from = opt.morphA; fade.t = opt.morphT; fade.morph = true; }

        SetCtx sc = new SetCtx();
        sc.n = n; sc.pk = pkF; sc.by = by; sc.kids = kids; sc.cur = cur; sc.fade = fade; sc.T = T; sc.tx = tx; sc.ty = ty; sc.mix = mix; sc.restKid = restKid; sc.forced = forced; sc.Mx = Mx; sc.c = c; sc.s = s;
        if (opt.onion != null && opt.onion.equals(id(n)) && !opt.onionOver) ghosts(sc);
        if (fade != null) { one(sc, ix.n(fade.from), c.a, false); one(sc, cur, c.a * smooth(fade.t), false); } else one(sc, cur, c.a, false);
        if (opt.onion != null && opt.onion.equals(id(n)) && opt.onionOver) ghosts(sc);
    }

    private static final Map<String, String[]> VFALL = new HashMap<>();
    static {
        VFALL.put("S", new String[] {"Ee", "D"}); VFALL.put("D", new String[] {"S", "Ee"}); VFALL.put("F", new String[] {"M"}); VFALL.put("L", new String[] {"D", "Ee"});
        VFALL.put("R", new String[] {"W-Oo", "Oh"}); VFALL.put("W-Oo", new String[] {"Oh"}); VFALL.put("Oh", new String[] {"W-Oo"}); VFALL.put("Ee", new String[] {"S"});
        VFALL.put("Uh", new String[] {"Aa", "Oh"}); VFALL.put("Surprised", new String[] {"Oh"}); VFALL.put("Smile", new String[] {"Ee"});
    }

    /** What walkSet's inner function {@code one} closes over. */
    private static final class SetCtx {
        JSONObject n, pk, cur, restKid, forced;
        String by;
        List<JSONObject> kids;
        CharacterSolver.Fade fade;
        double[] T, mix, Mx;
        double tx, ty;
        Ctx c;
        Signals s;
    }

    private void ghosts(SetCtx sc) {
        for (int gi = sc.kids.size() - 1; gi >= 0; gi--) {
            JSONObject k = sc.kids.get(gi);
            if (k != sc.cur && !J.t(k, "trig") && (opt.onionKid == null || opt.onionKid.equals(id(k)))) one(sc, k, sc.c.a * (opt.onionAlpha != 0 ? opt.onionAlpha : 0.2), true);
        }
    }

    private double[] pvA(JSONObject n, JSONObject q) {
        double[] p = J.pt(q, "pivot"); if (p != null) return p;
        p = J.pt(n, "pivot"); if (p != null) return p;
        double[] qb = ix.boundsOf(q);
        return qb != null ? new double[] {(qb[0] + qb[2]) / 2, (qb[1] + qb[3]) / 2} : new double[] {0, 0};
    }

    private void one(SetCtx sc, JSONObject kid, double a, boolean ghost) {
        JSONObject n = sc.n, pk = sc.pk; Ctx c = sc.c; Signals s = sc.s; String by = sc.by;
        Ctx k = kidCtx(c, sc.Mx, a);                         // (the extras below are set on it straight away, as kidCtx(c, m2, a, extra) does)
        k.ghost = ghost || c.ghost;
        if ("reach".equals(by)) {
            JSONObject kl = J.o(kid, "limb");
            k.reach = (ghost && kl != null) ? J.pt(kl, "c") : sc.T;
            k.lift = !live && sc.forced == kid && opt.lift;
            k.limbSet = id(n); k.handKey = J.s(pk, "hand");
            k.restLimb = sc.restKid != null ? J.o(sc.restKid, "limb") : null;
            Double fv = J.s(pk, "hand") != null ? s.foot.get(J.s(pk, "hand")) : null; k.foot = fv == null ? 0 : fv;
        }
        CharacterSolver.Fade fade = sc.fade;
        if (fade != null && fade.morph && !ghost) {
            JSONObject oth = kid == sc.cur ? ix.n(fade.from) : sc.cur; double me = smooth(fade.t), mw = kid == sc.cur ? 1 - me : me;
            List<Marks.Pull> mq = oth != null && oth != kid ? Marks.bridgeFor(sv.w, n, kid, oth, "", true) : null;
            if (mq != null) { Wp wp = new Wp(); wp.P = new ArrayList<>(); wp.Q = new ArrayList<>(); for (Marks.Pull q : mq) { wp.P.add(q.from); wp.Q.add(new double[] {q.from[0] + (q.to[0] - q.from[0]) * mw, q.from[1] + (q.to[1] - q.from[1]) * mw}); } k.warp = wp; }
        }
        if ("turn".equals(by)) k.vs = id(n);
        double[] kat = J.xy(kid, "at");
        double dpx = J.d(pk, "depth", 14);
        boolean hasP = false;
        if ("turn".equals(by) && kat != null) { k.P = new double[] {(sc.tx - kat[0]) * dpx, (sc.ty - kat[1]) * dpx}; k.rel = new double[] {sc.tx - kat[0], sc.ty - kat[1]}; hasP = true; }
        double[] m2 = sc.Mx; JSONObject src = kid; Double ax = J.num(n, "axisX");
        boolean hasWarps = false;
        if ("turn".equals(by) && moving && !ghost && kat != null && Marks.has(n)) {
            /* how far toward each neighbour: the turn shared out, decided once for the whole head */
            final Map<String, Double> share = new HashMap<>();
            List<JSONObject> cand = new ArrayList<>();
            JSONObject ww = J.o(n, "warpWith");
            for (JSONObject o : sc.kids) {
                if (o == kid || J.xy(o, "at") == null) continue;
                boolean ok = Marks.bridgeFor(sv.w, n, kid, o, "", false) != null;
                if (!ok && ww != null) for (Iterator<String> it = ww.keys(); it.hasNext(); ) if (Marks.bridgeFor(sv.w, n, kid, o, it.next(), false) != null) { ok = true; break; }
                if (ok) cand.add(o);
            }
            double rx = sc.tx - kat[0], ry = sc.ty - kat[1];
            List<JSONObject> left = new ArrayList<>(cand);
            while (!left.isEmpty()) {
                int bi = -1; double bl = 1e-6;
                for (int i = 0; i < left.size(); i++) { double[] oa = J.xy(left.get(i), "at"); double ddx = oa[0] - kat[0], ddy = oa[1] - kat[1], dn = Math.sqrt(ddx * ddx + ddy * ddy); if (dn == 0) dn = 1; double l = (rx * ddx + ry * ddy) / dn; if (l > bl) { bl = l; bi = i; } }
                if (bi < 0) break;
                JSONObject o = left.remove(bi); double[] oa = J.xy(o, "at");
                double ddx = oa[0] - kat[0], ddy = oa[1] - kat[1], d2 = ddx * ddx + ddy * ddy; if (d2 == 0) d2 = 1;
                double tt = (rx * ddx + ry * ddy) / d2;
                share.put(id(o), tt); rx -= tt * ddx; ry -= tt * ddy;
            }
            final double[] katF = kat; final JSONObject kidF = kid; final Double axF = ax;
            java.util.function.Function<String, Wp> warpOf = part -> {
                List<double[]> BP = new ArrayList<>(), BQ = new ArrayList<>(); double qx = sc.tx - katF[0], qy = sc.ty - katF[1]; List<double[]> toward = new ArrayList<>();
                for (JSONObject o : sc.kids) {
                    if (o == kidF || J.xy(o, "at") == null) continue;
                    List<Marks.Pull> br = Marks.bridgeFor(sv.w, n, kidF, o, part, false); if (br == null) continue;
                    double[] oa = J.xy(o, "at"); double ddx = oa[0] - katF[0], ddy = oa[1] - katF[1], d2 = ddx * ddx + ddy * ddy; if (d2 == 0) d2 = 1;
                    Double t0 = share.get(id(o)); double tt = t0 == null ? 0 : t0, w = smooth(clamp(tt / 0.5, 0, 1));
                    if (tt > 0) toward.add(new double[] {tt, ddx, ddy, d2});
                    if (w < 0.002) continue;
                    for (Marks.Pull q : br) {
                        double fxp = q.from[0], txp = q.from[0] + (q.to[0] - q.from[0]) * w;
                        if ("mirror".equals(type(kidF))) { double axm = axF == null ? sv.w / 2 : axF; fxp = 2 * axm - fxp; txp = 2 * axm - txp; }
                        BP.add(new double[] {fxp, q.from[1]}); BQ.add(new double[] {txp, q.from[1] + (q.to[1] - q.from[1]) * w});
                    }
                }
                for (double[] q : toward) { qx -= q[0] * q[1]; qy -= q[0] * q[2]; }
                if (BP.isEmpty()) return null;
                Wp wp = new Wp(); wp.P = BP; wp.Q = BQ; wp.par = new double[] {qx * dpx, qy * dpx};
                return wp;
            };
            k.P0 = k.P;
            Wp whole = warpOf.apply("");
            if (whole != null) { k.warp = whole; k.P = whole.par; }
            if (ww != null) {
                Map<String, Wp> ws = new LinkedHashMap<>(); int wn = 0;
                for (Iterator<String> it = ww.keys(); it.hasNext(); ) {
                    String wkey = it.next(); String tg = J.s(ww, wkey);
                    tg = "own".equals(tg) ? wkey : ("own".equals(J.s(ww, tg == null ? "" : tg)) ? tg : null);
                    ws.put(wkey, tg != null ? warpOf.apply(tg) : whole); wn++;
                }
                if (wn > 0) { k.warps = ws; hasWarps = true; }
            }
        }
        /* this angle's share of the blended tip and size */
        double er = 0, es = 1, ex0 = 0, ey0 = 0;
        if (sc.mix != null && !ghost && kat != null) { double[] ek = effOf(kid); er = sc.mix[0] - ek[0]; es = sc.mix[1] / ek[1]; ex0 = sc.mix[2] - ek[2]; ey0 = sc.mix[3] - ek[3]; }
        if (!"mirror".equals(type(kid)) && (Math.abs(er) > 0.005 || Math.abs(es - 1) > 0.0005 || Math.abs(ex0) > 0.01 || Math.abs(ey0) > 0.01)) { double[] pk1 = pvA(n, kid); m2 = Mat.mul(sc.Mx, Mat.trs(ex0, ey0, er * DEG, es, es, pk1[0], pk1[1])); }
        if ("mirror".equals(type(kid))) {
            src = ix.n(J.s(kid, "of")); if (src == null) return;
            double axv;
            if (ax == null) { double[] b = ix.boundsOf(src); axv = b != null ? (b[0] + b[2]) / 2 : sv.w / 2; } else axv = ax;
            double mr = J.z(kid, "rot") + er, ms = J.d(kid, "sc", 1) * es;
            double[] mB = sc.Mx;
            double mdx = J.z(kid, "dx") + ex0, mdy = J.z(kid, "dy") + ey0;
            if (Math.abs(mr) > 0.005 || Math.abs(ms - 1) > 0.0005 || mdx != 0 || mdy != 0) { double[] pk2 = pvA(n, src); mB = Mat.mul(sc.Mx, Mat.trs(mdx, mdy, mr * DEG, ms, ms, 2 * axv - pk2[0], pk2[1])); }
            m2 = Mat.mul(mB, Mat.flipX(axv));
            k.mir = !c.mir; k.tag = c.tag + "m";
            if (hasP && k.P != null) {
                k.P = new double[] {-k.P[0], k.P[1]}; k.rel = new double[] {-k.rel[0], k.rel[1]};
                if (k.P0 != null) k.P0 = new double[] {-k.P0[0], k.P0[1]};
                if (hasWarps) for (Wp fw : k.warps.values()) if (fw != null && fw.par != null && !fw.flp) { fw.flp = true; fw.par = new double[] {-fw.par[0], fw.par[1]}; }
            }
            out.m.put(id(kid), m2);
        }
        k.M = m2;
        walk(src, k);
    }

    private void emit(JSONObject n, double[] Mx, Ctx c) {
        if (!hasImg(n)) return;
        String img = J.s(n, "img");
        Signals s = sg(c);
        JSONObject limb = J.o(n, "limb"), swing = J.o(n, "swing"), breathe = J.o(n, "breathe"), mesh = J.o(n, "mesh");
        JSONArray mpins = J.a(mesh, "pins");
        boolean limbOn = limb != null && c.reach != null && !J.isFalse(limb, "bend");
        boolean bendOn = swing != null && !"stiff".equals(J.s(swing, "kind")) && live && !J.isFalse(swing, "on");
        boolean brOn = breathe != null && !J.isFalse(breathe, "on") && s.breath != 0;
        boolean meshOn = mpins != null && mpins.length() > 0;
        boolean wrapOn = moving && c.ball != null && "on".equals(c.wrap);
        byte[] behind = null;
        int dense = (meshOn || wrapOn || limbOn) ? 2 : (limb != null || (swing != null && !"stiff".equals(J.s(swing, "kind"))) || breathe != null || c.warp != null || c.post != null) ? 1 : 0;
        Meshes.Grid g = gridOf(n, img, dense);
        JSONObject zs = (J.o(n, "zSplit") != null && limb != null && !c.lift && !c.ghost && c.zo == null) ? J.o(n, "zSplit") : null;
        Item.Z zt = (c.zo != null && "under".equals(c.zo.how) && c.zo.tip != null && limb != null && !c.lift && !c.ghost) ? c.zo : null;
        if (zs != null) g = Meshes.splitGrid(g, limb, J.d(zs, "at", 0.5), "tip".equals(J.s(zs, "behind")));
        else if (zt != null) g = Meshes.splitGrid(g, limb, zt.tip, false);
        float[] v = g.v;
        if (wrapOn) {
            JSONObject bb = c.ball.b; double[] br4 = ballRot(c); double lf = J.z(n, "lift"); boolean bk = J.t(n, "back");
            float[] wv = new float[v.length];
            for (int i = 0; i < v.length; i += 2) { double[] bq = ballPt(bb, br4, c.ball.pr, v[i], v[i + 1], lf, bk); wv[i] = (float) bq[0]; wv[i + 1] = (float) bq[1]; if (bq[2] < 0.08) { if (behind == null) behind = new byte[v.length / 2]; behind[i >> 1] = 1; } }
            v = wv;
        }
        if (c.warp != null) v = Warps.warpMLS(v, c.warp.P, c.warp.Q, null, c.warp.K);
        if (limbOn && "hose".equals(J.s(limb, "style"))) {
            Limbs.Hose hk = Limbs.solveHose(limb, c.reach[0], c.reach[1], s.t);
            if (hk != null) { v = Limbs.warpHose(v, limb, hk); double[] la = J.pt(limb, "a"); out.pins.put(id(n), new double[][] {Mat.ap(Mx, la[0], la[1]), Mat.ap(Mx, hk.mid[0], hk.mid[1]), Mat.ap(Mx, hk.T[0], hk.T[1])}); tipAt(n, Mx, c, hk.T[0], hk.T[1], hk.ang, zs, zt); }
        } else if (limbOn) {
            Limbs.Bend k = Limbs.solveLimb(limb, c.reach[0], c.reach[1]);
            if (k != null) {
                float[] vr = v; v = Limbs.warpLimb(v, limb, k);
                if (J.o(limb, "foot") != null) Limbs.warpFoot(vr, v, limb, k, c.foot);
                double[] la = J.pt(limb, "a");
                out.pins.put(id(n), new double[][] {Mat.ap(Mx, la[0], la[1]), Mat.ap(Mx, k.e[0], k.e[1]), Mat.ap(Mx, k.w[0], k.w[1])});
                tipAt(n, Mx, c, k.w[0], k.w[1], Math.atan2(k.w[1] - k.e[1], k.w[0] - k.e[0]), zs, zt);
            }
        } else if (limb != null && c.reach != null && J.isFalse(limb, "bend")) {
            double[] la = J.pt(limb, "a"), lb = J.pt(limb, "b"), lc = J.pt(limb, "c");
            double r = Math.atan2(c.reach[1] - la[1], c.reach[0] - la[0]) - Math.atan2(lc[1] - la[1], lc[0] - la[0]);
            Mx = Mat.mul(Mx, Mat.trs(0, 0, r, 1, 1, la[0], la[1]));
            tipAt(n, Mx, c, lc[0], lc[1], Math.atan2(lc[1] - lb[1], lc[0] - lb[0]), zs, zt);
        }
        if (meshOn) {
            Wp pt = pinTargets(n, s, c);
            if (pt.moved) v = Warps.warpMLS(v, pt.P, pt.Q, geoFor(n, img), pt.K);
            if (!J.t(mesh, "quiet")) { double[][] mp = new double[pt.Q.size()][]; for (int i = 0; i < mp.length; i++) mp[i] = Mat.ap(Mx, pt.Q.get(i)[0], pt.Q.get(i)[1]); out.pins.put(id(n), mp); }
        }
        if (brOn) {
            double kk = J.d(breathe, "amt", 1) * 0.05 * s.breath, br = J.z(breathe, "r"), r2 = br * br, bcx = J.z(breathe, "cx"), bcy = J.z(breathe, "cy");
            float[] o2 = v == g.v ? v.clone() : v;
            for (int i = 0; i < o2.length; i += 2) { double dx = o2[i] - bcx, dy = o2[i + 1] - bcy, d2 = dx * dx + dy * dy; if (d2 < r2) { double f = 1 - d2 / r2; f *= f * kk; o2[i] = (float) (o2[i] + dx * f); o2[i + 1] = (float) (o2[i + 1] + dy * f * 0.6); } }
            v = o2;
        }
        if (bendOn) v = bendy(n, v == g.v ? v.clone() : v, Mx, c, s);
        float[] w = new float[v.length];
        for (int i = 0; i < v.length; i += 2) { w[i] = (float) (Mx[0] * v[i] + Mx[2] * v[i + 1] + Mx[4]); w[i + 1] = (float) (Mx[1] * v[i] + Mx[3] * v[i + 1] + Mx[5]); }
        if (c.post != null && c.post.on) Warps.bendBySheet(w, c.post.lat, c.post.I, c.post.M);
        Item item = new Item();
        item.id = id(n); item.img = img; item.v = w; item.uv = g.uv; item.idx = g.idx; item.a = c.a * J.d(n, "opacity", 1);
        item.under = c.lift ? null : (J.s(n, "zUnder") != null ? J.s(n, "zUnder") : c.under);
        item.ghost = c.ghost; item.nx = g.nx; item.ny = g.ny; item.clip = c.clip; item.tag = c.tag; item.nc = c.nc;
        if (c.zo != null && !c.lift && !c.ghost) { item.under = null; item.zo = c.zo; }
        if (behind != null && zs == null && zt == null) {
            List<Integer> ki = new ArrayList<>(); int[] gi = g.idx;
            for (int i = 0; i < gi.length; i += 3) if (behind[gi[i]] == 0 && behind[gi[i + 1]] == 0 && behind[gi[i + 2]] == 0) { ki.add(gi[i]); ki.add(gi[i + 1]); ki.add(gi[i + 2]); }
            if (ki.isEmpty()) return;
            item.idx = new int[ki.size()]; for (int i = 0; i < ki.size(); i++) item.idx[i] = ki.get(i);
        }
        if (zs != null) {
            Item back = item.copy();
            back.idx = g.back; back.under = J.s(zs, "under") != null ? J.s(zs, "under") : J.s(n, "zUnder"); item.idx = g.front; item.under = null;
            if (back.idx.length > 0) out.items.add(back);
            if (item.idx.length == 0) return;
        }
        if (zt != null) { Item zb = item.copy(); zb.idx = g.back; item.idx = g.front; item.zo = zFront(zt); if (zb.idx.length > 0) out.items.add(zb); if (item.idx.length == 0) return; }
        out.items.add(item);
    }

    /* where this limb ends and which way it points there: hands and held things ride on it */
    private void tipAt(JSONObject n, double[] Mx, Ctx c, double x, double y, double ang, JSONObject zs, Item.Z zt) {
        if (c.ghost) return;
        JSONObject limb = J.o(n, "limb"), rl = c.restLimb != null ? c.restLimb : limb;
        boolean hose = "hose".equals(J.s(limb, "style"));
        double[] ra = J.pt(rl, "a"), rb = J.pt(rl, "b"), rc = J.pt(rl, "c");
        SolveOut.Tip t = new SolveOut.Tip();
        t.p = Mat.ap(Mx, x, y); t.ang = Mat.wAng(Mx, ang); t.restP = Mat.ap(Mx, rc[0], rc[1]);
        t.rest = Mat.wAng(Mx, hose ? Math.atan2(rc[1] - ra[1], rc[0] - ra[0]) : Math.atan2(rc[1] - rb[1], rc[0] - rb[0]));
        t.under = c.lift ? null : zs != null ? ("tip".equals(J.s(zs, "behind")) ? (J.s(zs, "under") != null ? J.s(zs, "under") : J.s(n, "zUnder")) : null) : (J.s(n, "zUnder") != null ? J.s(n, "zUnder") : c.under);
        if (c.zo != null && !c.lift) { t.under = null; t.zo = zt != null ? zFront(zt) : c.zo; }
        out.tips.put(id(n), t);
        if (c.limbSet != null) out.tips.put(c.limbSet, t);
        if (c.handKey != null) S.tips.put(c.handKey, t.p);
    }

    private Meshes.Grid gridOf(JSONObject n, String img, int dense) {
        String key = id(n) + "|" + sv.pics.ver(img) + "|" + J.z(n, "x") + "," + J.z(n, "y") + "|" + dense;
        Meshes.Grid g = sv.grids.get(key);
        if (g != null) return g;
        g = Meshes.grid(J.z(n, "x"), J.z(n, "y"), sv.pics.w(img), sv.pics.h(img), dense);
        if (sv.grids.size() > 900) sv.grids.clear();
        sv.grids.put(key, g);
        return g;
    }

    private Warps.Geo geoFor(JSONObject n, String img) {
        JSONArray pins = J.a(J.o(n, "mesh"), "pins");
        StringBuilder key = new StringBuilder().append(sv.pics.ver(img)).append('|').append(J.z(n, "x")).append(',').append(J.z(n, "y")).append('|');
        List<double[]> P = new ArrayList<>();
        for (int i = 0; i < pins.length(); i++) {
            JSONObject p = pins.optJSONObject(i);
            key.append(id(p)).append(':').append(Math.round(J.x(p))).append(',').append(Math.round(J.y(p))).append(';');
            P.add(new double[] {J.x(p), J.y(p)});
        }
        Warps.Geo g = sv.geo.get(id(n));
        if (g != null && key.toString().equals(g.key)) return g;
        g = Warps.geoFields(J.z(n, "x"), J.z(n, "y"), sv.pics.w(img), sv.pics.h(img), sv.pics.alpha(img), P);
        g.key = key.toString();
        sv.geo.put(id(n), g);
        return g;
    }

    /* STIFF: a chain link, an earring, a charm */
    private CharacterSolver.Stiff stepStiff(JSONObject n, double[] base) {
        CharacterSolver.Stiff q = st.stiff.get(id(n));
        if (q == null) { q = new CharacterSolver.Stiff(); st.stiff.put(id(n), q); }
        if (q.f == frame) return q;
        JSONObject sw = J.o(n, "swing"); double[] p = pivotOf(n), tip = J.pt(sw, "tip");
        double[] r = Mat.ap(base, p[0], p[1]), tp = Mat.ap(base, tip[0], tip[1]);
        double dx = tp[0] - r[0], dy = tp[1] - r[1], len = hyp(dx, dy); if (len == 0) len = 1;
        if (q.x == null || frame - q.f > 2) { q.x = r[0]; q.y = r[1]; q.vx = q.vy = 0; q.th = q.om = 0; }
        q.f = frame; q.sgn = (base[0] * base[3] - base[1] * base[2]) < 0 ? -1 : 1;
        double vx = (r[0] - q.x) / dt, vy = (r[1] - q.y) / dt, ax = (vx - q.vx) / dt, ay = (vy - q.vy) / dt; q.x = r[0]; q.y = r[1]; q.vx = vx; q.vy = vy;
        double rest = Math.atan2(dy, dx), want = 0;
        if (J.t(sw, "hang")) { want = Math.atan2(tip[1] - p[1], (tip[0] - p[0]) * q.sgn) - rest; while (want > Math.PI) want -= 2 * Math.PI; while (want < -Math.PI) want += 2 * Math.PI; }
        double k = 18 + J.d(sw, "stiff", 0.3) * 260, cd = 0.5 + J.d(sw, "damp", 0.25) * 16, follow = J.d(sw, "follow", 1), lim = J.d(sw, "limit", 80) * DEG, sens = J.d(sw, "wind", 0.4), h = dt / 2;
        for (int i = 0; i < 2; i++) {
            double a = rest + q.th, nx = -Math.sin(a), ny = Math.cos(a);
            double acc = -k * (q.th - want) - cd * q.om - clamp((ax * nx + ay * ny) / len, -600, 600) * follow + windAcc(r[0]) * sens * nx / len;
            q.om += acc * h; q.th += q.om * h;
            if (q.th - want > lim) { q.th = want + lim; q.om = -q.om * 0.5; } else if (q.th - want < -lim) { q.th = want - lim; q.om = -q.om * 0.5; }
        }
        q.out = q.th + J.z(sw, "wag") * Math.sin(S.t * 3.4 + r[0] * 0.02);
        return q;
    }

    /* BENDY: a tail, a braid, a feather, a lock of hair */
    private float[] bendy(JSONObject n, float[] v, double[] Mx, Ctx c, Signals s) {
        JSONObject sw = J.o(n, "swing"); double[] p = pivotOf(n), tip = J.pt(sw, "tip");
        int N = (int) clamp(Math.round(J.t(sw, "segs") ? sw.optDouble("segs") : 4), 1, 10);
        double[] Mi = Mat.mul(invRoot, Mx);
        String key = id(n) + c.tag, nameKey = c.vs != null ? c.vs + ":" + n.optString("role", "") + ":" + n.optString("name", "") : null;
        double[] r = Mat.ap(Mi, p[0], p[1]), tp = Mat.ap(Mi, tip[0], tip[1]);
        double dx = tp[0] - r[0], dy = tp[1] - r[1], len = hyp(dx, dy); if (len == 0) len = 1;
        double L = len / N, ux = dx / len, uy = dy / len;
        CharacterSolver.Chain q = st.chain.get(key);
        if (q == null || q.n != N || frame - q.f > 2 || hyp(q.p[0] - r[0], q.p[1] - r[1]) > len * 2.5) {
            CharacterSolver.Shape sh = nameKey != null ? st.shape.get(nameKey) : null;
            boolean okS = sh != null && sh.n == N && frame - sh.f <= 3;
            double b0 = Math.atan2(uy, ux), ca = r[0], cb = r[1], oa = r[0], ob = r[1];
            q = new CharacterSolver.Chain(); q.n = N; q.p = new double[(N + 1) * 2]; q.o = new double[(N + 1) * 2];
            q.p[0] = r[0]; q.p[1] = r[1]; q.o[0] = r[0]; q.o[1] = r[1];
            for (int i = 0; i < N; i++) {
                double an = b0 + (okS ? sh.rel[i] : 0), aw = b0 + (okS ? sh.was[i] : 0);
                ca += Math.cos(an) * L; cb += Math.sin(an) * L; oa += Math.cos(aw) * L; ob += Math.sin(aw) * L;
                q.p[(i + 1) * 2] = ca; q.p[(i + 1) * 2 + 1] = cb; q.o[(i + 1) * 2] = oa; q.o[(i + 1) * 2 + 1] = ob;
            }
            st.chain.put(key, q);
        }
        q.f = frame;
        double stiff = 0.012 + J.d(sw, "stiff", 0.35) * 0.22, damp = 0.015 + J.d(sw, "damp", 0.3) * 0.2, follow = J.d(sw, "follow", 1), sens = J.d(sw, "wind", 1), flut = J.z(sw, "flutter"), hang = J.t(sw, "hang") ? 0.5 : 0;
        double sgn = (Mi[0] * Mi[3] - Mi[1] * Mi[2]) < 0 ? -1 : 1, hl = hyp(tip[0] - p[0], tip[1] - p[1]); if (hl == 0) hl = 1;
        double hx = (tip[0] - p[0]) / hl * sgn, hy = (tip[1] - p[1]) / hl;
        double mx = r[0] - q.p[0], my = r[1] - q.p[1], keep = 1 - clamp(follow, 0, 1), fling = Math.max(0, follow - 1);
        double jump = hyp(mx, my), capJ = L * 0.45, carry = jump > capJ ? 1 - capJ / jump : 0, kp2 = keep + (1 - keep) * carry, ko2 = keep + fling * (1 - carry) + (1 - keep) * carry;
        for (int i = 1; i <= N; i++) { q.p[i * 2] += mx * kp2; q.p[i * 2 + 1] += my * kp2; q.o[i * 2] += mx * ko2; q.o[i * 2 + 1] += my * ko2; }
        q.p[0] = r[0]; q.p[1] = r[1];
        double h = dt / 2, vcap = L * 0.5, lim = J.d(sw, "limit", 80) * DEG, b00 = Math.atan2(uy, ux);
        for (int k2 = 0; k2 < 2; k2++) {
            for (int i = 1; i <= N; i++) {
                int j = i * 2; double vx = (q.p[j] - q.o[j]) * (1 - damp), vy = (q.p[j + 1] - q.o[j + 1]) * (1 - damp); q.o[j] = q.p[j]; q.o[j + 1] = q.p[j + 1];
                double vl = hyp(vx, vy); if (vl > vcap) { vx *= vcap / vl; vy *= vcap / vl; }
                double w = (windAcc(q.p[j]) * ((double) i / N) + flut * windIn * 1100 * Math.sin(S.t * 21 + i * 1.7 + r[0])) * sens;
                q.p[j] += vx + w * h * h; q.p[j + 1] += vy;
            }
            for (int it = 0; it < 2; it++) for (int i = 1; i <= N; i++) {
                int jj = i * 2; double px = q.p[jj - 2], py = q.p[jj - 1], dxp = ux, dyp = uy;
                if (i > 1) { dxp = px - q.p[jj - 4]; dyp = py - q.p[jj - 3]; double dl = hyp(dxp, dyp); if (dl == 0) dl = 1; dxp /= dl; dyp /= dl; }
                if (hang != 0) { dxp = dxp * (1 - hang) + hx * hang; dyp = dyp * (1 - hang) + hy * hang; double hl2 = hyp(dxp, dyp); if (hl2 == 0) hl2 = 1; dxp /= hl2; dyp /= hl2; }
                if (windIn != 0 && sens != 0) {
                    double along = (double) i / N, gust = 0.65 + 0.35 * Math.sin(S.t * 1.7 + r[0] * 0.02), wv = Math.abs(windIn) * sens * along;
                    double ripple = 0.26 * Math.sin(S.t * 5.4 - i * 0.95 + r[0] * 0.03) + flut * 0.3 * Math.sin(S.t * 23 - i * 1.9 + r[1] * 0.05);
                    double lx = dxp + windIn * sens * 0.6 * gust * along - dyp * ripple * wv, ly = dyp + dxp * ripple * wv, ll = hyp(lx, ly); if (ll == 0) ll = 1;
                    dxp = lx / ll; dyp = ly / ll;
                }
                q.p[jj] += (px + dxp * L - q.p[jj]) * stiff; q.p[jj + 1] += (py + dyp * L - q.p[jj + 1]) * stiff;
                double ex = q.p[jj] - px, ey = q.p[jj + 1] - py, el = hyp(ex, ey); if (el == 0) el = 1;
                q.p[jj] = px + ex / el * L; q.p[jj + 1] = py + ey / el * L;
                double dv = wrapA(Math.atan2(q.p[jj + 1] - py, q.p[jj] - px) - b00), mxa = lim * (0.3 + 0.7 * i / N);
                if (dv > mxa || dv < -mxa) { double ca2 = b00 + clamp(dv, -mxa, mxa); q.p[jj] = px + Math.cos(ca2) * L; q.p[jj + 1] = py + Math.sin(ca2) * L; q.o[jj] += (q.p[jj] - q.o[jj]) * 0.7; q.o[jj + 1] += (q.p[jj + 1] - q.o[jj + 1]) * 0.7; }
            }
        }
        if (nameKey != null) {
            double bb0 = Math.atan2(uy, ux); CharacterSolver.Shape old = st.shape.get(nameKey); double[] rel = new double[N];
            for (int i = 0; i < N; i++) rel[i] = wrapA(Math.atan2(q.p[i * 2 + 3] - q.p[i * 2 + 1], q.p[i * 2 + 2] - q.p[i * 2]) - bb0);
            CharacterSolver.Shape ns = new CharacterSolver.Shape(); ns.n = N; ns.f = frame; ns.rel = rel;
            ns.was = old != null && old.n == N && old.f == frame - 1 ? old.rel : (old != null && old.f == frame ? old.was : rel);
            st.shape.put(nameKey, ns);
        }
        /* skin the drawing along the chain */
        double[] inv = Mat.inv(Mi); double ax0 = tip[0] - p[0], ay0 = tip[1] - p[1], al = hyp(ax0, ay0); if (al == 0) al = 1;
        double axx = ax0 / al, axy = ay0 / al, restA = Math.atan2(axy, axx), Ll = al / N;
        double[][] pl = new double[N + 1][]; double[] ang = new double[N];
        for (int i = 0; i <= N; i++) pl[i] = Mat.ap(inv, q.p[i * 2], q.p[i * 2 + 1]);
        double wag = J.z(sw, "wag") * Math.sin(s.t * Math.PI * J.d(sw, "wagRate", 2.2));
        for (int i = 0; i < N; i++) ang[i] = Math.atan2(pl[i + 1][1] - pl[i][1], pl[i + 1][0] - pl[i][0]) - restA + wag * Math.pow((i + 1) / (double) N, 1.4);
        for (int i = 0; i < v.length; i += 2) {
            double x = v[i], y = v[i + 1], t = ((x - p[0]) * axx + (y - p[1]) * axy) / al, f = t * N - 0.5; int i0 = (int) Math.floor(f); double wt = smooth(f - i0);
            double[] a0 = seg(clamp(i0, 0, N - 1), x, y, p, axx, axy, Ll, pl, ang), a1 = seg(clamp(i0 + 1, 0, N - 1), x, y, p, axx, axy, Ll, pl, ang);
            double nxp = a0[0] + (a1[0] - a0[0]) * wt, nyp = a0[1] + (a1[1] - a0[1]) * wt, grip = smooth(clamp(t * N * 2, 0, 1));
            v[i] = (float) (x + (nxp - x) * grip); v[i + 1] = (float) (y + (nyp - y) * grip);
        }
        double[][] chainPins = new double[N + 1][]; for (int i = 0; i <= N; i++) chainPins[i] = Mat.ap(Mx, pl[i][0], pl[i][1]);
        out.pins.put(id(n), chainPins);
        if (!c.ghost) {
            SolveOut.Tip t = new SolveOut.Tip(); t.p = Mat.ap(Mx, pl[N][0], pl[N][1]); t.ang = Mat.wAng(Mx, Math.atan2(pl[N][1] - pl[N - 1][1], pl[N][0] - pl[N - 1][0]));
            t.restP = Mat.ap(Mx, tip[0], tip[1]); t.rest = Mat.wAng(Mx, restA);
            out.tips.put(id(n), t);
        }
        return v;
    }

    private static double[] seg(double i0, double x, double y, double[] p, double axx, double axy, double Ll, double[][] pl, double[] ang) {
        int i = (int) i0;
        double rx = x - (p[0] + axx * Ll * i), ry = y - (p[1] + axy * Ll * i), cs = Math.cos(ang[i]), sn = Math.sin(ang[i]);
        return new double[] {pl[i][0] + cs * rx - sn * ry, pl[i][1] + sn * rx + cs * ry};
    }

    /* masks: "show only inside X" and "hidden where X is" */
    private void resolveMasks() {
        for (Item it : out.items) {
            if (it.clip == null) continue;
            List<Item> inside = null; List<List<Item>> cuts = new ArrayList<>(), more = new ArrayList<>(); StringBuilder key = new StringBuilder();
            for (JSONObject q : it.clip) {
                if (q == null) continue;
                String by = J.s(q, "by"); boolean cut = J.t(q, "cut");
                List<Item> m = masks.get(by + it.tag); if (m == null) m = masks.get(by);
                if (m == null || m.isEmpty()) { if (!cut) it.dead = true; continue; }
                key.append(cut ? '-' : '+').append(by);
                if (cut) cuts.add(m); else if (inside == null) inside = m; else if (m != inside) more.add(m);
            }
            if (inside != null || !cuts.isEmpty()) { Item.Mask mk = new Item.Mask(); mk.key = key + it.tag; mk.inside = inside; mk.cuts = cuts; mk.more = more; it.mask = mk; } else it.mask = null;
        }
        out.items.removeIf(it -> it.dead);
    }

    /* drawings that belong UNDER another part (an arm that passes behind the body) */
    private void orderUnder() {
        List<Item> moved = new ArrayList<>();
        for (Item it : out.items) if (it.under != null && out.first.get(it.under) != null && out.first.get(it.under) != it) moved.add(it);
        if (moved.isEmpty()) return;
        List<Item> keep = new ArrayList<>(); for (Item it : out.items) if (!moved.contains(it)) keep.add(it);
        for (Item it : moved) { int at = keep.indexOf(out.first.get(it.under)); if (at < 0) keep.add(0, it); else keep.add(at, it); }
        out.items.clear(); out.items.addAll(keep);
    }

    /* a stance's own order: these go last, so they have the final say */
    private void orderStances() {
        List<Item> zm = new ArrayList<>(); for (Item it : out.items) if (it.zo != null) zm.add(it);
        if (zm.isEmpty()) return;
        final List<Item> rest = new ArrayList<>(); for (Item it : out.items) if (it.zo == null) rest.add(it);
        final int[] nb = {0};
        List<Item> pend = zm; int zg = 0;
        while (!pend.isEmpty() && zg++ < 12) {
            List<Item> wait = new ArrayList<>(); for (Item it : pend) if (!put(it, false, rest, nb)) wait.add(it);
            if (wait.size() == pend.size()) {
                List<Item> fr = new ArrayList<>(); for (Item it : wait) if ("front".equals(it.zo.how)) fr.add(it);
                for (Item it : (fr.isEmpty() ? wait : fr)) put(it, true, rest, nb);
                if (fr.isEmpty()) wait = new ArrayList<>(); else { List<Item> w2 = new ArrayList<>(); for (Item it : wait) if (!"front".equals(it.zo.how)) w2.add(it); wait = w2; }
            }
            pend = wait;
        }
        rest.addAll(pend);
        out.items.clear(); out.items.addAll(rest);
    }

    private boolean put(Item it, boolean force, List<Item> rest, int[] nb) {
        Item.Z z = it.zo; Item tf = z.of != null ? out.first.get(z.of) : null, tl = z.of != null ? out.last.get(z.of) : null; int at;
        if ("back".equals(z.how)) { rest.add(nb[0]++, it); return true; }
        if ("front".equals(z.how) && !force) return false;
        if ("under".equals(z.how) && tf != null) {
            if ((at = rest.indexOf(tf)) >= 0) { rest.add(at, it); return true; }
            if (!force && tf.zo != null && tf.zo != z) return false;
        } else if ("over".equals(z.how) && tl != null) {
            if ((at = rest.indexOf(tl)) >= 0) { at++; while (at < rest.size() && rest.get(at).zo == z) at++; rest.add(at, it); return true; }
            if (!force && tl.zo != null && tl.zo != z) return false;
        }
        rest.add(it); return true;
    }

    /* COLOUR SETS in play: the drawing step recolours with them; the wave crosses the character's own box */
    private void tones(double[] rootM) {
        JSONArray pal = sv.ch.optJSONArray("palettes");
        if (pal == null) return;
        List<double[]> stops = new ArrayList<>();
        for (int i = 0; i < pal.length(); i++) {
            JSONObject p = pal.optJSONObject(i); if (p == null) continue;
            Double fw = opt.tones != null ? opt.tones.get(id(p)) : null;
            double w = fw != null ? fw : (S.tone.containsKey(id(p)) ? S.tone.get(id(p)) : 0);
            if (!(w > 0.002)) continue;
            JSONArray ss = p.optJSONArray("stops");
            if (ss != null) for (int j = 0; j < ss.length(); j++) {
                JSONObject q = ss.optJSONObject(j); if (q == null || stops.size() >= 8) continue;
                stops.add(new double[] {J.z(q, "from"), J.z(q, "to"), J.d(q, "sat", Double.NaN), J.d(q, "val", Double.NaN), J.d(q, "width", Double.NaN), w, J.d(p, "soft", Double.NaN), J.d(p, "blotch", Double.NaN)});
            }
        }
        if (stops.isEmpty()) return;
        double[] k0 = Mat.ap(rootM, 0, 0), k1 = Mat.ap(rootM, sv.w, sv.h);
        out.toneStops = stops;
        out.toneBox = new double[] {Math.min(k0[0], k1[0]), Math.min(k0[1], k1[1]), Math.max(k0[0], k1[0]), Math.max(k0[1], k1[1])};
        out.toneDir = J.d(set, "toneDir", 25);
    }
}
