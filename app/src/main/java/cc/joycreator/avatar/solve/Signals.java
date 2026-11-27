package cc.joycreator.avatar.solve;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What drives a character for one frame: the pilot's signals object ({@code AL.restSignals()} in al-solve.js, filled by
 * al-inputs.js and a take). Plain fields for the named ones; {@link #extra} for the rest (face pins {@code face.*},
 * {@code pt.*}, shrugs), which a part can follow by name.
 */
public final class Signals {
    /** A hand or foot being held, or planted on the ground: {@code w} = walking (its place is on the stage, not the body). */
    public static final class Hand {
        public double x, y;
        public boolean w;
        /** Part-way to where it is wanted (a stance eased in by its slider); null = all the way. */
        public Double k;

        public Hand(double x, double y) { this.x = x; this.y = y; }
    }

    public double turnX, turnY, roll, lookX, lookY, blinkL, blinkR, browL, browR;
    /** null = nobody is driving the mouth. */
    public Double mouth;
    public String viseme = "Neutral";
    public double lidUp, lidLow, lidTilt, wind, breath, sway, bounce, spin, walk, rootScale = 1, t;
    public boolean flip;
    /** L, R (hands) and FL, FR (feet): null = at rest. */
    public final Map<String, Hand> hand = new LinkedHashMap<>();
    public final Map<String, Double> foot = new LinkedHashMap<>();
    public double rootX, rootY;
    public final Map<String, double[]> dial = new LinkedHashMap<>();
    public final Map<String, Boolean> trig = new LinkedHashMap<>();
    public final Map<String, double[]> pin = new LinkedHashMap<>();
    public final Map<String, Double> pose = new LinkedHashMap<>();
    public final Map<String, Double> tone = new LinkedHashMap<>();
    /** Which way each hand swings against the legs (null = the usual side). */
    public Map<String, Double> armSide;
    public final Map<String, Double> extra = new LinkedHashMap<>();
    /** Written by the solver for the walker: where each limb grows from, where each hand is (stage space). */
    public final Map<String, double[]> anchor = new LinkedHashMap<>();
    public final Map<String, double[]> tips = new LinkedHashMap<>();
    /** True for the copy a mirrored drawing reads (left is right): see {@link #mirrored()}. */
    boolean mir;

    public Signals() {
        hand.put("L", null); hand.put("R", null); hand.put("FL", null); hand.put("FR", null);
        foot.put("FL", 0.0); foot.put("FR", 0.0);
    }

    public boolean trig(String id) { Boolean b = trig.get(id); return b != null && b; }

    /** A signal by name, as {@code s[name]} in the pilot: a number, or null when it is not a number or not there. */
    public Double num(String name) {
        switch (name) {
            case "turnX": return turnX; case "turnY": return turnY; case "roll": return roll;
            case "lookX": return lookX; case "lookY": return lookY; case "blinkL": return blinkL; case "blinkR": return blinkR;
            case "browL": return browL; case "browR": return browR; case "mouth": return mouth;
            case "lidUp": return lidUp; case "lidLow": return lidLow; case "lidTilt": return lidTilt; case "wind": return wind;
            case "breath": return breath; case "sway": return sway; case "bounce": return bounce; case "spin": return spin;
            case "walk": return walk; case "t": return t;
            case "rootScale": return mir ? null : rootScale;     // (not carried into the mirrored copy, as in the pilot)
            default: return extra.get(name);
        }
    }

    /**
     * Signals as a mirrored drawing sees them: left is right. The pilot's {@code sigsFull(true)}: the named signals turned
     * over, face pins as they are, shrugs swapped sides; anything else is not carried.
     */
    public Signals mirrored() {
        Signals o = new Signals();
        o.mir = true;
        o.turnX = -turnX; o.turnY = turnY; o.roll = -roll; o.lookX = -lookX; o.lookY = lookY;
        o.blinkL = blinkR; o.blinkR = blinkL; o.browL = browR; o.browR = browL;
        o.mouth = mouth; o.viseme = viseme; o.lidUp = lidUp; o.lidLow = lidLow; o.lidTilt = lidTilt;
        o.wind = -wind; o.breath = breath; o.sway = -sway; o.bounce = bounce;
        o.hand.clear(); o.hand.putAll(hand); o.foot.clear(); o.foot.putAll(foot);
        o.spin = -spin; o.walk = -walk; o.armSide = armSide; o.trig.putAll(trig); o.pin.putAll(pin); o.t = t;
        for (Map.Entry<String, Double> e : extra.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("pt.") || k.startsWith("face.")) o.extra.put(k, e.getValue());
        }
        Double sl = extra.get("shrugL"), sr = extra.get("shrugR"), sh = extra.get("shrug");
        if (sr != null) o.extra.put("shrugL", sr);
        if (sl != null) o.extra.put("shrugR", sl);
        if (sh != null) o.extra.put("shrug", sh);
        return o;
    }

    /** Signals as the pilot writes them (a take's frame, a golden case). Unknown numeric keys go to {@link #extra}. */
    public static Signals fromJson(JSONObject j) {
        Signals s = new Signals();
        for (Iterator<String> it = j.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = j.opt(k);
            switch (k) {
                case "mouth": s.mouth = v instanceof Number ? ((Number) v).doubleValue() : null; break;
                case "viseme": s.viseme = v instanceof String ? (String) v : "Neutral"; break;
                case "flip": s.flip = J.truthy(v); break;
                case "hand": {
                    JSONObject h = j.optJSONObject(k);
                    if (h != null) for (Iterator<String> hi = h.keys(); hi.hasNext(); ) {
                        String hk = hi.next();
                        JSONObject q = h.optJSONObject(hk);
                        if (q == null) { s.hand.put(hk, null); continue; }
                        Hand hh = new Hand(q.optDouble("x", 0), q.optDouble("y", 0));
                        hh.w = J.t(q, "w");
                        hh.k = J.num(q, "k");
                        s.hand.put(hk, hh);
                    }
                    break;
                }
                case "foot": numMap(j.optJSONObject(k), s.foot); break;
                case "root": { JSONObject r = j.optJSONObject(k); if (r != null) { s.rootX = r.optDouble("x", 0); s.rootY = r.optDouble("y", 0); } break; }
                case "dial": xyMap(j.optJSONObject(k), s.dial); break;
                case "pin": {
                    JSONObject p = j.optJSONObject(k);
                    if (p != null) for (Iterator<String> pi = p.keys(); pi.hasNext(); ) {
                        String pk = pi.next();
                        JSONObject q = p.optJSONObject(pk);
                        if (q != null) s.pin.put(pk, new double[] {q.optDouble("x", 0), q.optDouble("y", 0)});
                    }
                    break;
                }
                case "trig": {
                    JSONObject tr = j.optJSONObject(k);
                    if (tr != null) for (Iterator<String> ti = tr.keys(); ti.hasNext(); ) { String tk = ti.next(); s.trig.put(tk, J.truthy(tr.opt(tk))); }
                    break;
                }
                case "pose": numMap(j.optJSONObject(k), s.pose); break;
                case "tone": numMap(j.optJSONObject(k), s.tone); break;
                case "armSide": { JSONObject a = j.optJSONObject(k); if (a != null) { s.armSide = new LinkedHashMap<>(); numMap(a, s.armSide); } break; }
                case "anchor": case "tips": break;
                default:
                    if (v instanceof Number) {
                        double d = ((Number) v).doubleValue();
                        switch (k) {
                            case "turnX": s.turnX = d; break; case "turnY": s.turnY = d; break; case "roll": s.roll = d; break;
                            case "lookX": s.lookX = d; break; case "lookY": s.lookY = d; break; case "blinkL": s.blinkL = d; break;
                            case "blinkR": s.blinkR = d; break; case "browL": s.browL = d; break; case "browR": s.browR = d; break;
                            case "lidUp": s.lidUp = d; break; case "lidLow": s.lidLow = d; break; case "lidTilt": s.lidTilt = d; break;
                            case "wind": s.wind = d; break; case "breath": s.breath = d; break; case "sway": s.sway = d; break;
                            case "bounce": s.bounce = d; break; case "spin": s.spin = d; break; case "walk": s.walk = d; break;
                            case "rootScale": s.rootScale = d; break; case "t": s.t = d; break;
                            default: s.extra.put(k, d);
                        }
                    }
            }
        }
        return s;
    }

    private static void numMap(JSONObject o, Map<String, Double> to) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof Number) to.put(k, ((Number) v).doubleValue());
        }
    }

    private static void xyMap(JSONObject o, Map<String, double[]> to) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONArray a = o.optJSONArray(k);
            if (a != null && a.length() >= 2) to.put(k, new double[] {a.optDouble(0, 0), a.optDouble(1, 0)});
        }
    }
}
