package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared character / scene file's conversions: {@code /1} to {@code /2} (the owner's one-set-of-landmarks model),
 * the way back, the landmark blend, and the canonical text. A port of {@code tools/avatarlab/al-format.js}; the rules
 * are in {@link FormatCheck}. Spec: tasks/avatar/AVA_FORMAT.md.
 *
 * <p>Works on the loose JSON ({@link JSONObject}) on purpose: a reader keeps every field it does not know and writes it
 * back unchanged (format rule 2), which a typed model would lose.</p>
 */
public final class CharacterFile {
    public static final String CHARACTER = "joycreator.character/2";
    public static final String CHARACTER_OLD = "joycreator.character/1";
    public static final String SCENE = "joycreator.scene/1";
    /** Two landmark dots in the same drawing closer than this are the same landmark. */
    public static final double JOIN_PX = 4;
    /** One landmark given two places further apart than this in one drawing is split in two. */
    public static final double SPLIT_PX = 6;

    private CharacterFile() {}

    /** A document and the plain-words report of what was joined, split or dropped. */
    public static final class Upgraded {
        public final JSONObject doc;
        public final List<String> report;

        Upgraded(JSONObject d, List<String> r) {
            doc = d;
            report = r;
        }
    }

    /** One landmark's place in one angle: x, y and "" (here), "behind" or "off". */
    public static final class Place {
        public final double x, y;
        public final String state;

        Place(double x, double y, String state) {
            this.x = x;
            this.y = y;
            this.state = state;
        }
    }

    public static JSONObject parse(String text) throws JSONException {
        return new JSONObject(text);
    }

    // ── one set's pairs into one numbered set per piece ───────────────────────────────────────────

    private static final class Dot {
        final String angle;
        final double[] p;
        int mate = -1;

        Dot(String angle, double[] p) {
            this.angle = angle;
            this.p = p;
        }
    }

    private static final class Cls {
        final List<Dot> dots = new ArrayList<>();
    }

    private static final class Mean {
        double sx, sy;
        int n = 1;
        final double[] first;

        Mean(double[] p) {
            sx = p[0];
            sy = p[1];
            first = p;
        }
    }

    /** The pilot's pairs, {@code bridges = [{a, b, part?, pts:[{a:[x,y], b:[x,y]}], meet}]}, as one numbered set per piece. */
    public static JSONObject bridgesToMarks(JSONObject set, List<String> report) throws JSONException {
        JSONArray bs = Js.arr(Js.get(set, "bridges"));
        Map<String, List<JSONObject>> byPart = new LinkedHashMap<>();
        Map<String, JSONArray> meets = new LinkedHashMap<>();
        for (int bi = 0; bs != null && bi < bs.length(); bi++) {
            JSONObject br = Js.obj(Js.at(bs, bi));
            if (br == null || !Js.truthy(Js.get(br, "a")) || !Js.truthy(Js.get(br, "b"))) {
                report.add("A landmark pair with no angles was dropped.");
                continue;
            }
            String pk = br.optString("part", "");
            if (!byPart.containsKey(pk)) byPart.put(pk, new ArrayList<>());
            byPart.get(pk).add(br);
            Object meet = Js.get(br, "meet");
            if (meet != null && Js.isNum(meet) && Math.abs(Js.dbl(meet) - 0.5) > 1e-6) {
                if (!meets.containsKey(pk)) meets.put(pk, new JSONArray());
                meets.get(pk).put(new JSONObject().put("a", br.getString("a")).put("b", br.getString("b")).put("at", Js.dbl(meet)));
            }
        }
        JSONObject marks = new JSONObject();
        for (Map.Entry<String, List<JSONObject>> en : byPart.entrySet()) {
            String pk = en.getKey();
            String piece = pk.isEmpty() ? "the whole drawing" : pk;
            List<Dot> dots = new ArrayList<>();
            List<Integer> par = new ArrayList<>();
            for (JSONObject br : en.getValue()) {
                JSONArray pts = Js.arr(Js.get(br, "pts"));
                for (int qi = 0; pts != null && qi < pts.length(); qi++) {
                    JSONObject q = Js.obj(Js.at(pts, qi));
                    Object pa = q == null ? null : Js.get(q, "a"), pb = q == null ? null : Js.get(q, "b");
                    if (!Js.isXy(pa) || !Js.isXy(pb)) {
                        report.add("A landmark with a missing place was dropped (" + piece + ").");
                        continue;
                    }
                    int i1 = addDot(dots, par, br.getString("a"), (JSONArray) pa);
                    int i2 = addDot(dots, par, br.getString("b"), (JSONArray) pb);
                    dots.get(i1).mate = i2;
                    dots.get(i2).mate = i1;
                    join(par, i1, i2);
                }
            }
            for (int i = 0; i < dots.size(); i++)
                for (int j = i + 1; j < dots.size(); j++)
                    if (dots.get(i).angle.equals(dots.get(j).angle) && Js.dist(dots.get(i).p, dots.get(j).p) < JOIN_PX) join(par, i, j);

            // each class is one numbered landmark, numbered in the order it was first marked
            List<Cls> classes = new ArrayList<>();
            Map<Integer, Integer> seen = new LinkedHashMap<>();
            for (int i = 0; i < dots.size(); i++) {
                int r = find(par, i);
                Integer idx = seen.get(r);
                if (idx == null) {
                    idx = classes.size();
                    seen.put(r, idx);
                    classes.add(new Cls());
                }
                classes.get(idx).dots.add(dots.get(i));
            }
            // a class with two places far apart in one angle is split: the first keeps the number, each disagreeing
            // place starts a landmark of its own, with the place in the other drawing it was paired with
            List<Map<String, double[]>> out = new ArrayList<>();
            for (Cls cl : classes) {
                Map<String, Mean> main = new LinkedHashMap<>();
                List<Dot> extra = new ArrayList<>();
                for (Dot d : cl.dots) {
                    Mean m = main.get(d.angle);
                    if (m == null) {
                        main.put(d.angle, new Mean(d.p));
                        continue;
                    }
                    if (Js.dist(m.first, d.p) <= SPLIT_PX) {
                        m.sx += d.p[0];
                        m.sy += d.p[1];
                        m.n++;
                        continue;
                    }
                    extra.add(d);
                }
                Map<String, double[]> places = new LinkedHashMap<>();
                for (Map.Entry<String, Mean> me : main.entrySet())
                    places.put(me.getKey(), new double[]{Js.round(me.getValue().sx / me.getValue().n), Js.round(me.getValue().sy / me.getValue().n)});
                out.add(places);
                if (!extra.isEmpty()) {
                    report.add("One landmark of " + (pk.isEmpty() ? "the whole drawing" : pk)
                            + " had two different places in one drawing; the second became a landmark of its own.");
                    for (Dot d : extra) {
                        Map<String, double[]> pl = new LinkedHashMap<>();
                        pl.put(d.angle, new double[]{Js.round(d.p[0]), Js.round(d.p[1])});
                        Dot m = d.mate >= 0 ? dots.get(d.mate) : null;
                        if (m != null && !m.angle.equals(d.angle)) pl.put(m.angle, new double[]{Js.round(m.p[0]), Js.round(m.p[1])});
                        out.add(pl);
                    }
                }
            }
            // every angle that has any landmark of this piece gets a full row: its own places, and for the rest "not
            // here", parked at the average of where the point is in the drawings that have it
            Map<String, Boolean> angles = new LinkedHashMap<>();
            for (Map<String, double[]> pl : out) for (String a : pl.keySet()) angles.put(a, true);
            JSONArray ptsOut = new JSONArray();
            for (int i = 0; i < out.size(); i++) ptsOut.put(new JSONObject().put("n", i + 1));
            JSONObject at = new JSONObject();
            for (String a : angles.keySet()) {
                JSONArray row = new JSONArray();
                for (Map<String, double[]> pl : out) {
                    double[] own = pl.get(a);
                    if (own != null) {
                        row.put(new JSONArray().put(own[0]).put(own[1]));
                        continue;
                    }
                    double sx = 0, sy = 0;
                    for (double[] v : pl.values()) {
                        sx += v[0];
                        sy += v[1];
                    }
                    row.put(new JSONArray().put(Js.round(sx / pl.size())).put(Js.round(sy / pl.size())).put("off"));
                }
                at.put(a, row);
            }
            int joined = 0;
            for (JSONObject br : en.getValue()) {
                JSONArray pts = Js.arr(Js.get(br, "pts"));
                joined += pts == null ? 0 : pts.length();
            }
            if (joined != ptsOut.length())
                report.add((pk.isEmpty() ? "The whole drawing" : pk) + ": " + joined + " landmark pairs became " + ptsOut.length() + " numbered landmarks.");
            JSONObject mk = new JSONObject().put("pts", ptsOut).put("at", at);
            if (meets.containsKey(pk)) mk.put("meets", meets.get(pk));
            marks.put(pk, mk);
        }
        return marks;
    }

    private static int addDot(List<Dot> dots, List<Integer> par, String angle, JSONArray p) {
        dots.add(new Dot(angle, new double[]{Js.dbl(Js.at(p, 0)), Js.dbl(Js.at(p, 1))}));
        par.add(par.size());
        return dots.size() - 1;
    }

    private static int find(List<Integer> par, int i) {
        while (par.get(i) != i) {
            par.set(i, par.get(par.get(i)));
            i = par.get(i);
        }
        return i;
    }

    private static void join(List<Integer> par, int i, int j) {
        i = find(par, i);
        j = find(par, j);
        if (i != j) {
            if (i < j) par.set(j, i);
            else par.set(i, j);
        }
    }

    /** The other way: every two angles that both place a landmark (neither "not here") become a pair. */
    public static JSONArray marksToBridges(JSONObject set) throws JSONException {
        JSONArray out = new JSONArray();
        JSONObject marks = Js.obj(Js.get(set, "marks"));
        if (marks == null) return out;
        for (String pk : Js.keys(marks)) {
            JSONObject mk = Js.obj(marks.opt(pk));
            if (mk == null) continue;
            JSONObject at = Js.obj(Js.get(mk, "at"));
            JSONArray pts = Js.arr(Js.get(mk, "pts"));
            if (at == null || pts == null) continue;
            List<String> ids = Js.keys(at);
            Map<String, Double> meetOf = new LinkedHashMap<>();
            JSONArray ms = Js.arr(Js.get(mk, "meets"));
            for (int i = 0; ms != null && i < ms.length(); i++) {
                JSONObject m = Js.obj(Js.at(ms, i));
                if (m == null) continue;
                meetOf.put(m.optString("a") + "|" + m.optString("b"), m.optDouble("at"));
                meetOf.put(m.optString("b") + "|" + m.optString("a"), 1 - m.optDouble("at"));
            }
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++) {
                    String A = ids.get(i), B = ids.get(j);
                    JSONArray ra = Js.arr(at.opt(A)), rb = Js.arr(at.opt(B));
                    JSONArray ps = new JSONArray();
                    for (int k = 0; k < pts.length(); k++) {
                        JSONArray pa = Js.arr(Js.at(ra, k)), pb = Js.arr(Js.at(rb, k));
                        if (pa == null || pb == null || "off".equals(Js.at(pa, 2)) || "off".equals(Js.at(pb, 2))) continue;
                        ps.put(new JSONObject()
                                .put("a", new JSONArray().put(Js.at(pa, 0)).put(Js.at(pa, 1)))
                                .put("b", new JSONArray().put(Js.at(pb, 0)).put(Js.at(pb, 1))));
                    }
                    if (ps.length() == 0) continue;
                    Double meet = meetOf.get(A + "|" + B);
                    JSONObject br = new JSONObject().put("a", A).put("b", B).put("pts", ps).put("meet", meet != null ? meet : 0.5);
                    if (!pk.isEmpty()) br.put("part", pk);
                    out.put(br);
                }
        }
        return out;
    }

    /**
     * Where each landmark of one piece is, for a turn whose angles weigh {@code w} (weights need not sum to 1). Each
     * point blends between ITS OWN places in the angles that have it; "not here" takes no part; "behind" counts in
     * full. An entry is null when no angle in the blend has that point.
     */
    public static List<double[]> blend(JSONObject set, String pk, Map<String, Double> w, Double axisX) {
        List<double[]> out = new ArrayList<>();
        JSONObject marks = Js.obj(Js.get(set, "marks"));
        JSONObject mk = marks == null ? null : Js.obj(marks.opt(pk == null ? "" : pk));
        JSONArray pts = mk == null ? null : Js.arr(Js.get(mk, "pts"));
        if (pts == null) return out;
        Map<String, JSONObject> kids = new LinkedHashMap<>();
        JSONArray ks = Js.arr(Js.get(set, "kids"));
        for (int i = 0; ks != null && i < ks.length(); i++) {
            JSONObject k = Js.obj(Js.at(ks, i));
            if (k != null) kids.put(k.optString("id"), k);
        }
        for (int i = 0; i < pts.length(); i++) {
            double sx = 0, sy = 0, sw = 0;
            for (Map.Entry<String, Double> we : w.entrySet()) {
                Place q = place(set, mk, we.getKey(), i, axisX, kids);
                if (q == null || "off".equals(q.state) || !(we.getValue() > 0)) continue;
                sx += q.x * we.getValue();
                sy += q.y * we.getValue();
                sw += we.getValue();
            }
            out.add(sw > 0 ? new double[]{sx / sw, sy / sw} : null);
        }
        return out;
    }

    /** One landmark's place in one angle, reading a mirrored angle from its source. */
    public static Place place(JSONObject set, JSONObject mk, String angleId, int i, Double axisX, Map<String, JSONObject> kids) {
        JSONObject at = Js.obj(Js.get(mk, "at"));
        JSONArray row = at == null ? null : Js.arr(at.opt(angleId));
        if (row != null) return placeOf(Js.arr(Js.at(row, i)), null);
        JSONObject k = kids == null ? null : kids.get(angleId);
        if (k == null || !"mirror".equals(k.optString("type"))) return null;
        JSONArray src = at == null ? null : Js.arr(at.opt(k.optString("of")));
        if (src == null) return null;
        Object own = Js.get(set, "axisX");
        Double ax = Js.isNum(own) ? Js.dbl(own) : axisX;
        return placeOf(Js.arr(Js.at(src, i)), ax == null ? 0.0 : ax);
    }

    private static Place placeOf(JSONArray q, Double mirrorAbout) {
        if (q == null) return null;
        double x = Js.dbl(Js.at(q, 0)), y = Js.dbl(Js.at(q, 1));
        Object st = Js.at(q, 2);
        return new Place(mirrorAbout == null ? x : 2 * mirrorAbout - x, y, st instanceof String ? (String) st : "");
    }

    // ── the file, /1 to /2 and back ───────────────────────────────────────────────────────────────

    public static Upgraded upgrade(JSONObject doc) throws JSONException {
        JSONObject o = Js.copy(doc);
        List<String> report = new ArrayList<>();
        String f = o.optString("format", "");
        if (CHARACTER.equals(f)) return new Upgraded(o, report);
        if (!f.isEmpty() && !CHARACTER_OLD.equals(f)) {
            report.add("Not a character file this reads (" + f + ").");
            return new Upgraded(o, report);
        }
        final int[] nb = {0};
        final JSONException[] err = {null};
        walk(Js.obj(Js.get(o, "root")), n -> {
            if (!"set".equals(n.optString("type")) || Js.arr(Js.get(n, "bridges")) == null) return;
            try {
                if (Js.arr(Js.get(n, "bridges")).length() > 0) {
                    n.put("marks", bridgesToMarks(n, report));
                    nb[0]++;
                }
                n.remove("bridges");
            } catch (JSONException e) {
                err[0] = e;
            }
        });
        if (err[0] != null) throw err[0];
        if (nb[0] > 0)
            report.add(nb[0] + (nb[0] == 1 ? " part" : " parts") + " had landmarks matched pair by pair; each piece now has one numbered set.");
        o.put("format", CHARACTER);
        return new Upgraded(o, report);
    }

    public static JSONObject downgrade(JSONObject doc) throws JSONException {
        JSONObject o = Js.copy(doc);
        final JSONException[] err = {null};
        walk(Js.obj(Js.get(o, "root")), n -> {
            if (!"set".equals(n.optString("type")) || Js.get(n, "marks") == null) return;
            try {
                JSONArray bs = marksToBridges(n);
                if (bs.length() > 0) n.put("bridges", bs);
                n.remove("marks");
            } catch (JSONException e) {
                err[0] = e;
            }
        });
        if (err[0] != null) throw err[0];
        o.put("format", CHARACTER_OLD);
        return o;
    }

    /** A scene's characters ride inside it at the current version too. */
    public static Upgraded upgradeScene(JSONObject doc) throws JSONException {
        JSONObject o = Js.copy(doc);
        List<String> report = new ArrayList<>();
        JSONObject chars = Js.obj(Js.get(o, "characters"));
        if (chars != null)
            for (String id : Js.keys(chars)) {
                JSONObject c = Js.obj(chars.opt(id));
                if (c == null) continue;
                Upgraded r = upgrade(c);
                chars.put(id, r.doc);
                for (String s : r.report) report.add(id + ": " + s);
            }
        return new Upgraded(o, report);
    }

    /** The document as text with its keys in order: two files that mean the same give the same text. */
    public static String canonical(JSONObject o) {
        return Js.canonical(o);
    }

    interface Visit {
        void at(JSONObject n);
    }

    static void walk(JSONObject n, Visit fn) {
        if (n == null) return;
        fn.at(n);
        JSONArray kids = Js.arr(Js.get(n, "kids"));
        for (int i = 0; kids != null && i < kids.length(); i++) walk(Js.obj(Js.at(kids, i)), fn);
    }
}
