package cc.joycreator.avatar.solve;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner's landmark model (11-23; tasks/avatar/AVA_FORMAT.md B2.1) as the solver reads it: each piece of a set has ONE
 * numbered set of landmarks, every drawing places the same points, each here, behind (still steers) or off (not here).
 * {@link #bridgeFor} is the pilot's {@code AL.bridgeFor}: as the head turns from A toward B, where each point A shares with B
 * travels to. The editing side of the same model is the pilot's {@code AL.marks} (al-format.js).
 */
final class Marks {
    private Marks() {}

    /** One landmark seen from drawing A: where A has it, and where it is when B takes over. */
    static final class Pull {
        final double[] from, to;

        Pull(double[] from, double[] to) { this.from = from; this.to = to; }
    }

    /** "Not here" (or no place at all): takes no part. */
    static boolean off(JSONArray q) { return q == null || "off".equals(q.optString(2, "")); }

    static boolean has(JSONObject set) {
        JSONObject m = J.o(set, "marks");
        if (m == null) return false;
        for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
            JSONObject p = m.optJSONObject(it.next());
            JSONArray pts = p == null ? null : p.optJSONArray("pts");
            if (pts != null && pts.length() > 0) return true;
        }
        return false;
    }

    /** Where drawings a and b meet on the way between them, as a share of the way from a to b (0.5 when never set). */
    static double meet(JSONObject mk, String a, String b) {
        JSONArray ms = mk.optJSONArray("meets");
        if (ms != null) for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            if (m == null) continue;
            String ma = m.optString("a"), mb = m.optString("b");
            if (ma.equals(a) && mb.equals(b)) return m.optDouble("at", 0.5);
            if (ma.equals(b) && mb.equals(a)) return 1 - m.optDouble("at", 0.5);
        }
        return 0.5;
    }

    static List<Pull> bridgeFor(double chW, JSONObject set, JSONObject A, JSONObject B, String part, boolean full) {
        JSONObject all = J.o(set, "marks"), mk = all == null ? null : all.optJSONObject(part == null ? "" : part);
        JSONArray pts = mk == null ? null : mk.optJSONArray("pts");
        if (pts == null || pts.length() == 0) return null;
        JSONObject at = mk.optJSONObject("at");
        if (at == null) return null;
        double ax = J.d(set, "axisX", chW / 2);
        String a = CharIndex.id(A), b = CharIndex.id(B);
        JSONArray ra = at.optJSONArray(a), rb = at.optJSONArray(b);
        boolean flip = false;
        boolean aMir = "mirror".equals(A.optString("type")), bMir = "mirror".equals(B.optString("type"));
        if ((ra == null || rb == null) && (aMir || bMir)) {          // the far side of the face: the mirror image of what was matched by hand
            String a2 = aMir ? J.s(A, "of") : (centred(A) ? a : null), b2 = bMir ? J.s(B, "of") : (centred(B) ? b : null);
            if (a2 != null && b2 != null && at.optJSONArray(a2) != null && at.optJSONArray(b2) != null) {
                ra = at.optJSONArray(a2); rb = at.optJSONArray(b2); a = a2; b = b2; flip = true;
            }
        }
        if (ra == null || rb == null) return null;
        double m = full ? 1 : meet(mk, a, b);
        List<Pull> out = new ArrayList<>();
        for (int i = 0; i < pts.length(); i++) {
            JSONArray qa = ra.optJSONArray(i), qb = rb.optJSONArray(i);
            if (off(qa) || off(qb)) continue;                      // "not here" in either drawing: no pull between them
            double qa0 = qa.optDouble(0, 0), qa1 = qa.optDouble(1, 0), qb0 = qb.optDouble(0, 0), qb1 = qb.optDouble(1, 0);
            double fx = qa0, tx = qa0 + (qb0 - qa0) * m;
            if (flip) { fx = 2 * ax - fx; tx = 2 * ax - tx; }
            out.add(new Pull(new double[] {fx, qa1}, new double[] {tx, qa1 + (qb1 - qa1) * m}));
        }
        return out.isEmpty() ? null : out;
    }

    private static boolean centred(JSONObject k) {
        double[] at = J.xy(k, "at");
        return at != null && Math.abs(at[0]) < 0.05;
    }
}
