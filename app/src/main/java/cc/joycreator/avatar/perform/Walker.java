package cc.joycreator.avatar.perform;

import cc.joycreator.avatar.solve.Signals;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walking (the pilot's {@code al-walk.js}): drag the body and the feet look after themselves.
 *
 * <p>There is no walk-cycle animation. Each foot is PLANTED at a spot on the stage and stays there (so nothing slides) until
 * the body has moved far enough that the foot is left behind; then that foot lifts, swings to a spot ahead of the body and
 * plants again. The legs are ordinary bending limbs reaching from the hips to the feet. Arms swing against the legs, the body
 * bobs and leans, and the tail follows because it already swings from whatever it is fixed to.</p>
 *
 * <p>ANY NUMBER OF LEGS: every limb that walks takes part, each with a step timing (0..1). LIMBS THAT MAY BECOME FEET: an arm
 * told "you may walk" plants whenever the ground is within its reach, so a character tipping onto all fours walks on its
 * hands with no other switch. GROUND is a switch: on, there is a ground line (pull the body up and it leaves it, push it down
 * and it goes onto all fours); off, it is always on the ground wherever it is. Everything here is arithmetic on a few numbers
 * per frame; the solver gives it where the hips and hands are ({@code anchor}, {@code tips}) from the frame before.</p>
 *
 * <p>Toward and away from the camera ({@code al-depth-walk.js}): {@link #z} (a {@link DepthWalk}) and {@link #tz} (ported 11-27,
 * the face capture scout). Not ported yet: the scene's side limits; and a
 * walking hand's palm height is the limb's own {@code walk.palm} (the pilot guesses it from the picture when it is absent).</p>
 */
public final class Walker {

    /** What the walker asks of the room's hands (the pilot's {@code inp}). */
    public interface Body {
        boolean flip();

        void setFlip(boolean flip);

        /** The head pad's turn is reversed when the character turns round while nobody holds the head. */
        void reverseTurn();

        boolean heldHead();

        /** A finger on a foot or hand ("footL", "footR", "handL", "handR"): where it was put, or null. */
        double[] placed(String held);

        double stanceLevel(String poseId);

        boolean trigOn(String trigId);

        void setTrig(String trigId, boolean on);
    }

    private static final double SAME = 0.13;      // timings this close step in the same wave

    private static final class Limb {
        String key, held, id, side;
        double[] hip, foot;
        double[][] bar;                         // heel, toe
        double len, stretch, phase, palm;
        boolean autoPhase, lift, arm;
        Limb mate;
    }

    private static final class Step {
        double t, dur, fx, fy, h;
        double[] fix;
        boolean land, hop;
    }

    private static final class Foot {
        double[] pos, hip, home, hang;
        double roll;
        boolean down, planted, user, placed, reach;
        Step step;
    }

    private final JSONObject ch;
    private final Body body;
    private final Map<String, JSONObject> byId = new HashMap<>();
    private final Map<String, JSONObject> parent = new HashMap<>();
    private List<JSONObject> order = new ArrayList<>();
    private List<Limb> all = new ArrayList<>(), base = new ArrayList<>();
    private Limb legL, legR;

    // state
    public double x, y, tx, ty, baseY, vx, vy, vz, fallV, sq, idle, swing, lift, lastPh, low, mv, fw;
    public boolean held, air, led;
    /** Walking toward the camera: how big it is drawn for where its ground line is, and how far it may go (null: no depth). */
    public DepthWalk z;
    /** Where its ground line should walk to, toward (+) or away (-) from its place; null: nowhere. */
    public Double tz;
    private Boolean free;
    private final Map<String, Foot> feet = new LinkedHashMap<>();
    private double[] g0;                           // where the body was grabbed, and where it was: {gx, gy, tx, ty}

    public Walker(JSONObject character, Body body) {
        this.ch = character;
        this.body = body;
        index();
        limbs();
    }

    /** Call after the tree changes (a pin moved, a stance made): the limbs are found again. */
    public void refresh() {
        byId.clear();
        parent.clear();
        order = new ArrayList<>();
        index();
        limbs();
    }

    public void reset() {
        x = y = tx = ty = 0;
        baseY = 0;
        vx = vy = vz = fallV = 0;
        held = false;
        tz = null;
        feet.clear();
        sq = idle = swing = lift = lastPh = low = mv = fw = 0;
        air = false;
        free = null;
        led = false;
        g0 = null;
    }

    private void index() {
        JSONObject root = ch.optJSONObject("root");
        if (root != null) walk(root, null);
    }

    private void walk(JSONObject n, JSONObject par) {
        order.add(n);
        byId.put(n.optString("id"), n);
        if (par != null) parent.put(n.optString("id"), par);
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject c = k.optJSONObject(i);
            if (c != null) walk(c, n);
        }
    }

    // ── the limbs that walk ───────────────────────────────────────────────────────────────────────

    private static boolean isFootKey(String key) {
        return key != null && !key.isEmpty() && key.charAt(0) == 'F';
    }

    private static double hyp(double a, double b) {
        return Math.sqrt(a * a + b * b);
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : v > b ? b : v;
    }

    private static double smooth(double t) {
        t = clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double pdist(double a, double b) {
        double d = Math.abs(a - b) % 1;
        return d > 0.5 ? 1 - d : d;
    }

    private static double pt(JSONObject o, String which, String axis) {
        JSONObject p = o == null ? null : o.optJSONObject(which);
        return p == null ? 0 : p.optDouble(axis, 0);
    }

    /** Is this set a limb that takes part in walking? (A leg always; anything else once it has been told it may.) */
    private static boolean walksOn(JSONObject set) {
        if (set == null || !"set".equals(set.optString("type"))) return false;
        JSONObject pick = set.optJSONObject("pick");
        if (pick == null || !"reach".equals(pick.optString("by")) || pick.optString("hand", "").isEmpty()) return false;
        JSONObject w = pick.optJSONObject("walk");
        if (w != null) return w.optBoolean("on", true);
        String role = set.optString("role");
        return role.equals("legL") || role.equals("legR");
    }

    private void limbs() {
        all = new ArrayList<>();
        base = new ArrayList<>();
        legL = legR = null;
        Limb L = null, R = null;
        Map<String, Boolean> used = new HashMap<>();
        for (JSONObject s : order) {
            if (!walksOn(s)) continue;
            JSONObject pick = s.optJSONObject("pick");
            String key = pick.optString("hand");
            if (used.containsKey(key)) continue;
            JSONObject k = byId.get(pick.optString("rest", ""));
            if (k == null || k.optJSONObject("limb") == null) {
                k = null;
                JSONArray kids = s.optJSONArray("kids");
                for (int i = 0; kids != null && i < kids.length(); i++) {
                    JSONObject q = kids.optJSONObject(i);
                    if (q != null && q.optJSONObject("limb") != null) {
                        k = q;
                        break;
                    }
                }
            }
            if (k == null) continue;
            JSONObject wk = pick.optJSONObject("walk");
            if (!isFootKey(key) && wk != null && wk.has("kid")) {                  // the drawing this arm walks on
                JSONObject wkd = byId.get(wk.optString("kid"));
                if (wkd != null && wkd.optJSONObject("limb") != null && parent.get(wkd.optString("id")) == s) k = wkd;
            }
            JSONObject l = k.optJSONObject("limb");
            boolean isFoot = isFootKey(key);
            String role = s.optString("role");
            Limb q = new Limb();
            q.side = role.endsWith("L") ? "L" : role.endsWith("R") ? "R" : "";
            q.key = key;
            q.held = isFoot ? "foot" + key.substring(1) : "hand" + key;
            q.id = s.optString("id");
            q.hip = new double[]{pt(l, "a", "x"), pt(l, "a", "y")};
            q.foot = new double[]{pt(l, "c", "x"), pt(l, "c", "y")};
            JSONObject bar = isFoot ? l.optJSONObject("foot") : null;
            q.bar = bar == null ? null : new double[][]{{pt(bar, "heel", "x"), pt(bar, "heel", "y")}, {pt(bar, "toe", "x"), pt(bar, "toe", "y")}};
            q.len = hyp(pt(l, "b", "x") - pt(l, "a", "x"), pt(l, "b", "y") - pt(l, "a", "y")) + hyp(pt(l, "c", "x") - pt(l, "b", "x"), pt(l, "c", "y") - pt(l, "b", "y"));
            q.stretch = l.has("stretch") ? l.optDouble("stretch") : 0.12;
            boolean hasPhase = wk != null && wk.has("phase") && !wk.isNull("phase");
            q.phase = hasPhase ? wk.optDouble("phase") : (q.side.equals("R") ? 0.5 : 0) + (isFoot ? 0 : 0.25);
            q.autoPhase = !hasPhase && !isFoot;
            q.lift = wk != null && wk.has("lift") ? wk.optBoolean("lift") : !isFoot;
            q.arm = !isFoot;
            q.palm = wk != null && wk.has("palm") ? wk.optDouble("palm") : 0;
            used.put(key, true);
            all.add(q);
            if (role.equals("legL") && L == null) L = q;
            if (role.equals("legR") && R == null) R = q;
        }
        for (Limb q : all) if (!q.lift) base.add(q);
        // which leg is on the same side as each walking arm: the one whose hip is drawn nearest its shoulder
        for (Limb q : all) {
            if (!q.arm || base.isEmpty()) continue;
            Limb m = base.get(0);
            for (Limb b : base) if (Math.abs(b.hip[0] - q.hip[0]) < Math.abs(m.hip[0] - q.hip[0])) m = b;
            q.mate = m;
            if (q.autoPhase) q.phase = (m.phase + 0.25) % 1;
        }
        if (base.size() >= 2) {
            legL = L != null ? L : base.get(0);
            legR = R != null ? R : (base.size() > (base.get(0) == L ? 1 : 0) ? base.get(base.get(0) == L ? 1 : 0) : base.get(1));
        }
    }

    private JSONObject settings() {
        JSONObject s = ch.optJSONObject("settings");
        return s == null ? new JSONObject() : s;
    }

    private JSONObject cfg() {
        JSONObject w = settings().optJSONObject("walk");
        return w != null && w.optBoolean("on", true) && legL != null ? w : null;
    }

    /** No legs, but it travels: a hovering robot, a ghost, a fish, a balloon. */
    private JSONObject hover() {
        JSONObject hv = settings().optJSONObject("hover");
        return hv != null && hv.optBoolean("on", true) && legL == null ? hv : null;
    }

    /** Does dragging the body take the character somewhere? */
    public boolean travels() {
        return cfg() != null || hover() != null;
    }

    /** Is there a ground line right now? (Off = it walks wherever it is.) */
    public boolean grounded() {
        JSONObject c = cfg();
        if (c == null) return false;
        return free == null ? !"plane".equals(c.optString("ground", "line")) : !free;
    }

    public void setGround(boolean on) {
        free = !on;
        if (on) {
            baseY = y;
            ty = y;
            fallV = 0;
        }
        air = false;
    }

    public void grab(double cx, double cy) {
        held = true;
        g0 = new double[]{cx, cy, tx, ty};
        fallV = 0;
    }

    public void drag(double cx, double cy) {
        if (g0 == null) return;
        tx = g0[2] + (cx - g0[0]);
        ty = g0[3] + (cy - g0[1]);
    }

    /** Where the body was taken, and where it was: {gx, gy, tx, ty}; null when nothing holds it. */
    public double[] grabbed() {
        return g0;
    }

    public void release() {
        held = false;
        g0 = null;
    }

    public void home() {
        tx = 0;
        ty = 0;
        if (z != null && baseY != 0) tz = 0.0;                     // walking in depth, home is walked back to (al-depth-walk.js)
        else baseY = 0;
        held = false;
    }

    private JSONObject stance() {
        JSONObject w = settings().optJSONObject("walk");
        String id = w == null ? null : w.optString("fours", null);
        if (id == null || id.isEmpty()) return null;
        JSONArray ps = ch.optJSONArray("poses");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    // ── one frame ─────────────────────────────────────────────────────────────────────────────────

    /**
     * One frame: writes where the character stands, where its feet are, how they roll and how its arms swing into {@code S}.
     * {@code anchor} and {@code tips} are what the solver wrote on the frame before (where each limb grows from, where each
     * hand is, stage space).
     */
    public void step(double dt, Signals S, Map<String, double[]> anchor, Map<String, double[]> tips) {
        final JSONObject set = settings();
        final JSONObject cfg = cfg();
        S.rootScale = 1;
        S.walk = 0;
        if (legL == null) {
            JSONObject hv = hover();
            S.hand.put("FL", null);
            S.hand.put("FR", null);
            S.foot.put("FL", 0.0);
            S.foot.put("FR", 0.0);
            if (hv == null) {
                S.rootX = S.rootY = 0;
                return;
            }
            double hk = 1 - Math.exp(-(hv.has("quick") ? hv.optDouble("quick") : 9) * dt), ox = x, oy = y;
            x += (tx - x) * hk;
            y += (ty - y) * hk;
            vx += ((x - ox) / dt - vx) * (1 - Math.exp(-12 * dt));
            vy += ((y - oy) / dt - vy) * (1 - Math.exp(-12 * dt));
            S.rootX = x;
            S.rootY = y;
            S.sway += clamp(vx / 520, -1, 1) * (hv.has("lean") ? hv.optDouble("lean") : 1);
            S.bounce += clamp(vy / 620, -1, 1) * 0.8;
            return;
        }
        for (Limb q : all) if (!q.arm) S.foot.put(q.key, 0.0);
        final double axis = set.has("axisX") && !set.isNull("axisX") ? set.optDouble("axisX") : ch.optDouble("w", 512) / 2;
        boolean flip = body.flip();
        if (cfg == null) {                                // not a walker: feet simply stay where they were drawn while the body leans
            x = y = tx = ty = 0;
            S.rootX = S.rootY = 0;
            for (Limb q : all) {
                if (q.arm) continue;
                S.hand.put(q.key, new Signals.Hand(flip ? 2 * axis - q.foot[0] : q.foot[0], q.foot[1]));
                double[] u = body.placed(q.held);
                if (u != null) S.hand.put(q.key, new Signals.Hand(u[0], u[1]));
            }
            return;
        }
        final boolean line = grounded(), deep = "plane".equals(cfg.optString("ground", "line"));
        final double gy = cfg.optDouble("groundY", 0) != 0 ? cfg.optDouble("groundY") : ch.optDouble("h", 512);
        final double stride = Math.max(8, cfg.optDouble("stride", 0) != 0 ? cfg.optDouble("stride") : 60);
        final double stepH = cfg.has("stepH") ? cfg.optDouble("stepH") : 14;
        final double rollAmt = cfg.has("roll") ? cfg.optDouble("roll") : 1, style = cfg.has("style") ? cfg.optDouble("style") : -0.4, heel = Math.max(0, -style), tip = Math.max(0, style);
        double legLen = 0, toeLen = 1e9, slack = 1e9;
        for (Limb q : base) {
            legLen += q.len / base.size();
            toeLen = Math.min(toeLen, q.bar != null ? hyp(q.bar[1][0] - q.foot[0], q.bar[1][1] - q.foot[1]) : 0);
            slack = Math.min(slack, q.len * (1 + q.stretch) - (gy - q.hip[1]));
        }
        final double crouch = legLen * 0.45, fourAt = cfg.has("fourAt") ? cfg.optDouble("fourAt") : 0.82;
        slack = Math.max(3, slack) + toeLen * 0.55 * Math.min(1, rollAmt);
        final double leave = slack + legLen * (cfg.has("jumpAt") ? cfg.optDouble("jumpAt") : 0.7);
        JSONObject fp = stance();
        String fpTrig = fp == null ? "" : fp.optString("trig", "");
        double lv4 = fp != null ? clamp(body.stanceLevel(fp.optString("id")), 0, 1) : 0;
        boolean on4 = fp != null && !fpTrig.isEmpty() && body.trigOn(fpTrig);

        // 1. the body goes where the finger takes it, no faster than these legs can carry it
        double px = x, py = y, k = 1 - Math.exp(-(held || led ? 24 : 7) * dt), dx = (tx - x) * k, cap = stride * 11 * dt, dl = Math.abs(dx);
        if (dl > cap) dx *= cap / dl;
        x += dx;
        if (!line) {                                       // no ground line: up and down the stage is walking too
            double dy = (ty - y) * k, dly = Math.abs(dy), capY = stride * 8 * dt;
            if (dly > capY) dy *= capY / dly;
            y += dy;
            air = false;
            fallV = 0;
            baseY = y;
        } else {
            double want = ty - baseY;                      // where the finger wants the hips, measured from the ground line (up is negative)
            // pushed well down, and kept there a moment: onto all fours instead of a deeper squat. Pulled well up: back onto two legs.
            if (fp != null && !fpTrig.isEmpty() && cfg.optBoolean("autoFours", true) && held && !air) {
                if (!on4) {
                    low = want > crouch * fourAt ? low + dt : 0;
                    if (low > 0.32) {
                        body.setTrig(fpTrig, true);
                        on4 = true;
                        low = 0;
                    }
                } else if (-want > legLen * 0.22) {
                    body.setTrig(fpTrig, false);
                    on4 = false;
                }
            }
            if (want > crouch) {
                want = crouch;
                ty = baseY + crouch;
            }
            if (!air && held && -want > leave && lv4 < 0.3) air = true;
            if (air) {
                if (held || !cfg.optBoolean("fall", true)) {
                    y += (baseY + want - y) * k;
                    fallV = 0;
                    if (-want < slack * 0.7) air = false;
                } else {
                    fallV += 2600 * dt;
                    y += fallV * dt;
                    if (y >= baseY - slack * 0.5) {
                        sq = clamp(fallV / 700, 0.2, 1.2);
                        y = Math.min(y, baseY);
                        fallV = 0;
                        air = false;
                        ty = baseY;
                    } else ty = y;
                }
            } else {
                double goal = held ? Math.max(want, -slack) : 0;           // on the ground it only rises as far as its legs and toes allow
                if (lv4 > 0) {                                             // on all fours it does not squat: the hips sit where the stance puts them
                    if (goal > 0) goal *= 1 - lv4;
                    JSONObject fours = fp.optJSONObject("fours");
                    goal += (fours != null ? fours.optDouble("drop", 0) : 0) * lv4 + Math.sin(Math.PI * lv4) * legLen * 0.05;
                }
                y += (baseY + goal - y) * k;
                fallV = 0;
                if (!held) ty = baseY;
            }
        }
        vx += ((x - px) / dt - vx) * (1 - Math.exp(-14 * dt));
        vy += ((y - py) / dt - vy) * (1 - Math.exp(-14 * dt));
        // 1b. toward the camera and away (DepthWalk gives z: how big it is drawn for where its ground line is, and how far it may
        //     go). The ground line itself walks there, no faster than these legs go, and the body, its target and a finger's hold
        //     go with it, so nothing crouches or jumps.
        double dz = 0;
        if (z != null && tz != null) {
            double tzc = clamp(tz, z.lim[0], z.lim[1]), capZ = stride * 4 * dt;
            dz = (tzc - baseY) * (1 - Math.exp(-5 * dt));
            if (Math.abs(dz) > capZ) dz *= capZ / Math.abs(dz);
            if (Math.abs(tzc - baseY) < 0.05) dz = tzc - baseY;
            baseY += dz;
            y += dz;
            ty += dz;
            if (g0 != null) g0[3] += dz;
        }
        vz += (dz / dt - vz) * (1 - Math.exp(-14 * dt));
        double speed = line ? hyp(vx, vz) : hyp(vx, vy);
        final boolean going = speed > stride * 0.35;
        idle = going || held ? 0 : idle + dt;

        // 2. face the way it is going
        String facing = set.optString("facing", "");
        if (cfg.optBoolean("face", true) && (facing.equals("left") || facing.equals("right")) && Math.abs(vx) > stride * 0.8) {
            boolean nf = (vx > 0) != facing.equals("right");
            if (nf != body.flip() && !body.heldHead()) body.reverseTurn();
            body.setFlip(nf);
            flip = nf;
        }
        final boolean flipF = flip;

        // 3. further up the stage is further away (a floor seen from above only)
        final double sc = (deep ? clamp(1 + ((line ? baseY : y) / ch.optDouble("h", 512)) * (cfg.optDouble("depth", 0)) * 2, 0.35, 2.2) : 1) * (z != null ? z.s(baseY) : 1);
        final boolean airF = line && air;
        double up = line ? -(y - baseY) : 0, tiptoe = airF ? 0 : clamp((up - (slack - toeLen * 0.55)) / Math.max(1, toeLen * 0.55), 0, 1);

        // 4. the feet: where each one belongs, and whether the ground is within its reach
        double dirx = speed > 1 ? vx / speed : 0, diry = speed > 1 ? (line ? vz : vy) / speed : 0;
        double reachAhead = stride * (0.5 + heel * 0.12 - tip * 0.1) * clamp(speed / (stride * 2.2), 0, 1), bodyLift = 0, yBase = line ? baseY : y;
        Map<String, double[]> anch = anchor == null ? new HashMap<>() : anchor, tp = tips == null ? new HashMap<>() : tips;
        final double face = (facing.equals("left") ? -1 : facing.equals("right") ? 1 : 0) * (flipF ? -1 : 1);
        for (Limb L : all) {
            Foot f = feet.get(L.key);
            if (f == null) {
                f = new Foot();
                f.down = !L.lift;
                feet.put(L.key, f);
            }
            double[] a = anch.get(L.key);
            f.hip = (L.lift && a != null) ? new double[]{a[0], a[1]} : new double[]{axis + (mx(flipF, axis, L.hip[0]) - axis) * sc + x, gy + (L.hip[1] - gy) * sc + y};
            if (L.arm) {
                // a hand that walks stands under its shoulder, a little ahead; the far-side hand a little higher, as the far-side foot is
                double palm = L.palm * sc, far = L.mate != null ? Math.max(0, gy - L.mate.foot[1]) * sc : 0;
                f.home = new double[]{f.hip[0] + (Math.abs(vx) > 24 ? (vx > 0 ? 1 : -1) : face) * L.len * 0.1 * sc, gy + yBase - palm - far};
            } else f.home = new double[]{axis + (mx(flipF, axis, L.foot[0]) - axis) * sc + x, gy + (L.foot[1] - gy) * sc + yBase};
            if (L.lift) {
                double hgt = f.home[1] - f.hip[1], maxr = L.len * (1 + L.stretch) * sc;
                boolean can = !airF && hgt > 2 && hgt < maxr * (f.down ? 1.12 : 0.99) && !(L.arm && body.placed(L.held) != null);
                if (can && !f.down) {                       // the ground has come within reach: reach down and plant
                    double[] t0 = tp.get(L.key);
                    f.down = true;
                    f.planted = true;
                    f.user = false;
                    f.pos = t0 != null ? new double[]{t0[0], t0[1]} : new double[]{f.hip[0], f.hip[1] + hgt * 0.6};
                    Step st = new Step();
                    st.dur = 0.26;
                    st.fx = f.pos[0];
                    st.fy = f.pos[1];
                    st.h = L.len * 0.08;
                    st.land = true;
                    f.step = st;
                } else if (!can && f.down) {                // lifted out of reach (or taken by a finger): let go
                    f.down = false;
                    f.step = null;
                    f.pos = null;
                    f.planted = false;
                }
                // on its way down onto all fours the hands go first: they reach for the ground before it is within reach
                f.reach = L.arm && !f.down && on4 && !airF && body.placed(L.held) == null;
            }
            if (f.down && f.pos == null) {
                f.pos = new double[]{f.home[0], f.home[1]};
                f.planted = true;
            }
        }
        List<Limb> swinging = new ArrayList<>();
        final List<Object[]> cands = new ArrayList<>();                 // {Limb, Foot, off, need}
        for (Limb L : all) {
            Foot f = feet.get(L.key);
            double[] user = !L.arm ? body.placed(L.held) : null;
            double want = 0;
            if (!f.down) {
                if (L.arm) continue;                        // an arm again: the hand does whatever hands do
                // a leg that has let go of the ground hangs from its hip, a little bent
                double e0 = 1 - Math.exp(-12 * dt), hxp = f.hip[0] + (mx(flipF, axis, L.foot[0]) - mx(flipF, axis, L.hip[0])) * 0.9 * sc, hyp0 = f.hip[1] + (L.foot[1] - L.hip[1]) * 0.9 * sc;
                if (f.hang == null) f.hang = new double[]{hxp, hyp0};
                f.hang[0] += (hxp - f.hang[0]) * e0;
                f.hang[1] += (hyp0 - f.hang[1]) * e0;
                settle(f, -0.3, 10, rollAmt, dt);
                continue;
            }
            f.hang = null;
            double toeDir = L.bar != null ? ((mx(flipF, axis, L.bar[1][0]) - mx(flipF, axis, L.bar[0][0])) >= 0 ? 1 : -1) : 0;
            double fwd = Math.abs(vx) > 24 ? (vx > 0 ? 1 : -1) : (toeDir != 0 ? toeDir : face);
            if (user != null) {
                f.pos = new double[]{user[0], user[1]};
                f.step = null;
                f.user = true;
                f.planted = true;
                settle(f, 0, 12, rollAmt, dt);
                continue;
            }
            if (f.user) {
                f.user = false;
                f.placed = true;
                if (line && !airF) {
                    Step st = new Step();
                    st.dur = 0.1;
                    st.fx = f.pos[0];
                    st.fy = f.pos[1];
                    st.fix = new double[]{f.pos[0], f.home[1]};
                    st.h = 0;
                    f.step = st;
                }
            }
            if (airF) {
                // off the ground. Going up the legs fold under it. The moment it starts to come down they swing forward and reach for the landing.
                double rvx = mx(flipF, axis, L.foot[0]) - mx(flipF, axis, L.hip[0]), rvy = L.foot[1] - L.hip[1];
                boolean hang = "hang".equals(cfg.optString("air", "tuck"));
                double sp = (L.phase % 1) < 0.5 ? 1 : -1;
                double fall = hang ? 0 : Math.max(smooth(vy / 150), (!held && cfg.optBoolean("fall", true)) ? 0.6 + 0.4 * smooth(vy / 320) : 0), rise = hang ? 0 : smooth(-vy / 420) * (1 - fall);
                double hx = rvx * 0.92 - clamp(vx * 0.05, -L.len * 0.35, L.len * 0.35), hy = rvy * (hang ? 0.98 : 0.86);
                double ux = rvx * 0.5 - fwd * L.len * 0.16 + sp * L.len * 0.06, uy = rvy * 0.5;
                double lx = rvx * 0.9 + fwd * L.len * (0.36 + sp * 0.08) + clamp(vx * 0.05, -L.len * 0.3, L.len * 0.3), ly = rvy * 0.97;
                double ttx = mix(mix(hx, ux, rise), lx, fall), tty = mix(mix(hy, uy, rise), ly, fall), e = 1 - Math.exp(-(15 + 9 * fall) * dt);
                f.pos[0] += (f.hip[0] + ttx * sc - f.pos[0]) * e;
                f.pos[1] += (f.hip[1] + tty * sc - f.pos[1]) * e;
                if (f.pos[1] > f.home[1]) f.pos[1] = f.home[1];                // never through the floor
                f.planted = false;
                f.step = null;
                f.placed = false;
                want = mix(mix(-0.35, -0.75, rise), 0.4, fall);
                settle(f, want, 12, rollAmt, dt);
                continue;
            }
            if (!f.planted) {                                                  // landing
                f.planted = true;
                Step st = new Step();
                st.dur = 0.08;
                st.fx = f.pos[0];
                st.fy = f.pos[1];
                st.h = 0;
                f.step = st;
            }
            if (f.step != null) {
                Step st = f.step;
                st.t += dt / st.dur;
                double[] to = st.fix != null ? st.fix : (st.land ? f.home : new double[]{f.home[0] + dirx * reachAhead * (st.hop ? 0.6 : 1), f.home[1] + diry * reachAhead});
                double tt = clamp(st.t, 0, 1), e2 = smooth(tt);
                f.pos[0] = st.fx + (to[0] - st.fx) * e2;
                f.pos[1] = st.fy + (to[1] - st.fy) * e2 - st.h * Math.sin(Math.PI * tt);
                if (st.hop) bodyLift = Math.max(bodyLift, stepH * 2.4 * Math.sin(Math.PI * tt));
                else if (!st.land) lift = Math.max(lift, Math.sin(Math.PI * tt) * (L.arm ? 0.6 : 1));
                // the foot through the air: toe trailing as it leaves, level in the middle, then heel first (or toe first, on tiptoe) as it comes down
                want = st.h != 0 && L.bar != null ? (tt < 0.45 ? mix(-0.7, -0.15, tt / 0.45) : mix(-0.15, 0.55 * heel - 0.5 * tip, (tt - 0.45) / 0.55)) : 0;
                f.roll += (want * rollAmt - f.roll) * (1 - Math.exp(-26 * dt));
                if (st.t >= 1) {
                    f.pos[0] = to[0];
                    f.pos[1] = to[1];
                    f.step = null;
                    f.placed = false;
                    lastPh = L.phase;
                } else swinging.add(L);
                continue;
            }
            double off = hyp(f.home[0] - f.pos[0], f.home[1] - f.pos[1]);
            // standing on it: flat; as the body passes over and leaves it behind the heel peels up; reaching up, it goes onto its toes
            double behind = (x - (f.pos[0] - (f.home[0] - x))) * (dirx);
            want = L.bar != null ? Math.min(Math.min(-0.42 * tip, -0.6 * tiptoe), going ? -0.62 * smooth((behind - stride * 0.12) / (stride * 0.45)) : 0) : 0;
            settle(f, want, 16, rollAmt, dt);
            cands.add(new Object[]{L, f, off, going ? stride * 0.5 : (idle > 0.3 && !f.placed ? stride * 0.16 : 1e9)});
        }
        if ("hop".equals(cfg.optString("gait", "walk"))) {
            // a hop: every foot leaves and lands together
            double avg = 0, least = 1e9;
            for (Object[] q : cands) {
                avg += (Double) q[2] / cands.size();
                least = Math.min(least, (Double) q[3]);
            }
            if (swinging.isEmpty() && !cands.isEmpty() && avg > (going ? stride * 0.45 : least)) {
                double hd = clamp(0.5 * stride / Math.max(speed, 1) + 0.12, 0.2, 0.34);
                for (Object[] q : cands) {
                    Foot ff = (Foot) q[1];
                    Step st = new Step();
                    st.dur = hd;
                    st.fx = ff.pos[0];
                    st.fy = ff.pos[1];
                    st.h = stepH * 2;
                    st.hop = true;
                    ff.step = st;
                }
            }
        } else {
            // whose turn: the next timing after the foot that stepped last; then, among equals, whoever is furthest behind
            final double lastPhF = lastPh;
            List<Object[]> ready = new ArrayList<>();
            for (Object[] q : cands) if ((Double) q[2] > (Double) q[3]) ready.add(q);
            ready.sort((a, b) -> {
                double ka = ((((((Limb) a[0]).phase - lastPhF) % 1) + 1) % 1), kb = ((((((Limb) b[0]).phase - lastPhF) % 1) + 1) % 1);
                if (ka == 0) ka = 1;
                if (kb == 0) kb = 1;
                int c = Double.compare(ka, kb);
                return c != 0 ? c : Double.compare((Double) b[2], (Double) a[2]);
            });
            for (Object[] q : ready) {
                Limb ql = (Limb) q[0];
                Foot qf = (Foot) q[1];
                // it may go if everything in the air belongs to its own wave, or the one in the air is nearly down and this one is badly behind (a hurry)
                boolean ok = true;
                for (Limb o : swinging) {
                    Foot fo = feet.get(o.key);
                    if (!(pdist(o.phase, ql.phase) <= SAME || (fo.step != null && fo.step.t > 0.55 && (Double) q[2] > stride * 0.95))) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                Step st = new Step();
                st.dur = clamp(0.55 * stride / Math.max(speed, 1), 0.1, 0.3);
                st.fx = qf.pos[0];
                st.fy = qf.pos[1];
                st.h = stepH * (ql.arm ? 0.75 : 1);
                qf.step = st;
                swinging.add(ql);
            }
        }

        // 5. out. A rolled foot turns about its heel (toe up) or its toe (heel up), so the ankle rises and the leg has further to reach.
        lift *= Math.exp(-9 * dt);
        sq *= Math.exp(-9 * dt);
        mv = mv + ((going && !airF ? 1 : 0) - mv) * (1 - Math.exp(-5 * dt));
        double bob = cfg.has("bob") ? cfg.optDouble("bob") : 0.8;
        // the body rides a wave with every step: low as the weight comes down onto a foot, high as the other leg passes it
        S.rootX = x;
        S.rootY = y - bodyLift - bob * (lift - 0.42 * mv) * legLen * 0.11 * (airF ? 0 : 1);
        S.rootScale = sc;
        for (Limb L : all) {
            Foot f = feet.get(L.key);
            if (L.arm) {                                                       // a walking hand is planted on the stage, like a foot
                if (f.down && f.pos != null) {
                    Signals.Hand h = new Signals.Hand(f.pos[0], f.pos[1]);
                    h.w = true;
                    S.hand.put(L.key, h);
                } else if (f.reach) {
                    Signals.Hand h = new Signals.Hand(f.home[0], f.home[1]);
                    h.w = true;
                    S.hand.put(L.key, h);
                }
                continue;
            }
            if (!f.down) {
                if (f.hang != null) S.hand.put(L.key, new Signals.Hand(f.hang[0], f.hang[1]));
                S.foot.put(L.key, L.bar != null ? f.roll : 0);
                continue;
            }
            double ax = f.pos[0], ay = f.pos[1];
            if (L.bar != null && f.roll != 0) {
                double[] pv = f.roll > 0 ? L.bar[0] : L.bar[1];
                double vx0 = (mx(flipF, axis, L.foot[0]) - mx(flipF, axis, pv[0])) * sc, vy0 = (L.foot[1] - pv[1]) * sc;
                double td = (mx(flipF, axis, L.bar[1][0]) - mx(flipF, axis, L.bar[0][0])) >= 0 ? 1 : -1, an = -f.roll * td, cs = Math.cos(an), sn = Math.sin(an);
                ax += cs * vx0 - sn * vy0 - vx0;
                ay += sn * vx0 + cs * vy0 - vy0;
            }
            S.hand.put(L.key, new Signals.Hand(ax, ay));
            S.foot.put(L.key, L.bar != null ? f.roll : 0);
        }
        Foot fl = feet.get(legL.key), fr = feet.get(legR.key);
        double sw = fl.pos != null && fr.pos != null ? clamp(((fl.pos[0] - fl.home[0]) - (fr.pos[0] - fr.home[0])) / stride, -1, 1) * (airF ? 0.3 : 1) : 0;
        swing += (sw - swing) * (1 - Math.exp(-14 * dt));
        S.walk = swing;
        // contra-body: an arm swings against the leg on ITS side (found by where it is drawn, since layer names cannot be trusted for sides)
        S.armSide = new LinkedHashMap<>();
        for (Limb q : all) if (q.arm && q.mate != null) S.armSide.put(q.key, q.mate == legL ? 1.0 : -1.0);
        S.sway += clamp(vx / (stride * 9), -1, 1) * (cfg.has("lean") ? cfg.optDouble("lean") : 0.5) * 1.6 * (1 - lv4 * 0.6);
        // coming toward the camera it turns to face it: its front drawing, if it has one (z.front, the head's turn to that drawing)
        double fz = z != null && z.front != null ? clamp(vz / (stride * 1.2), 0, 1) : 0;
        fw += (fz - fw) * (1 - Math.exp(-6 * dt));
        if (fw > 0.005 && z != null && z.front != null && !body.heldHead()) {
            S.turnX += (z.front - S.turnX) * fw;
            S.turnY += (0 - S.turnY) * fw * 0.5;
        }
        S.bounce += -bob * lift * 0.9 + sq * 1.4 + (airF ? -0.4 * smooth(-vy / 420) : 0);
    }

    private static double mx(boolean flip, double axis, double x) {
        return flip ? 2 * axis - x : x;
    }

    private static void settle(Foot f, double want, double rate, double rollAmt, double dt) {
        f.roll += (want * rollAmt - f.roll) * (1 - Math.exp(-rate * dt));
    }

    /** The limbs that walk, for the room (does this character walk at all?). */
    public boolean canWalk() {
        return cfg() != null;
    }
}
