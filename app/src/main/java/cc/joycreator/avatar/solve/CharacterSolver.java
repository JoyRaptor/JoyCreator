package cc.joycreator.avatar.solve;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE pose solver of Avatar Studio on the phone: signals in, a list of posed meshes out. The room's stage, recording, Studio's
 * preview and Studio's export all call this one, so they cannot disagree (the single-authority rule {@code PuppetPoseResolver}
 * keeps for the older room).
 *
 * <p>A port of the web pilot's {@code tools/avatarlab/al-solve.js}, kept line for line so the two can be compared: the pilot
 * writes golden files ({@code AL.golden}, tasks/avatar/golden/) and {@code tools/jvm-harness/CharacterSolverTest} checks every
 * vertex of this port against them within 0.5 px. A change to the behaviour is made in the pilot first, its golden files made
 * again, then here.</p>
 *
 * <p>Plain Java, no Android: the character is its JSON ({@code joycreator.character/2}, AVA_FORMAT.md), the pictures come
 * through {@link Pics}. One solver per character on stage; it keeps the springs, fades and swings between frames.</p>
 */
public final class CharacterSolver {

    /** How one frame is solved (the pilot's {@code opt}). */
    public static final class Opt {
        /** true = everything moves (Perform, Motion). false = the still pose for building: no fades, no springs. */
        public boolean live;
        public double dt = 1 / 60.0;
        /** moving without being live (a tween preview) */
        public boolean tween;
        /** show this drawing of a set whatever the signals say: { setId: kidId } */
        public Map<String, String> force;
        /** draw a node that is normally waiting for a blink or a button */
        public Map<String, Boolean> show;
        /** ghost the other drawings of this set */
        public String onion, onionKid;
        public double onionAlpha;
        public boolean onionOver;
        /** a forced blend between two looks of a set: { set, a, b, t } */
        public String morphSet, morphA, morphB;
        public double morphT;
        /** building: lift a chosen limb drawing out as drawn (default true) */
        public boolean lift = true;
        /** stance levels forced while one is being worked on; shape weights forced while one is posed; colour-set levels */
        public Map<String, Double> poses, tones;
        public Map<String, Map<String, Double>> shapes;
    }

    final JSONObject ch;
    final CharIndex ix;
    final Pics pics;
    final double w, h;

    /** what carries over between frames */
    State st = new State();
    final Map<String, Meshes.Grid> grids = new HashMap<>();
    final Map<String, Warps.Geo> geo = new HashMap<>();
    final Map<String, Meshes.Lathe> lathes = new HashMap<>();
    final Map<String, List<double[]>> holds = new HashMap<>();
    final Map<String, Boolean> maskIds = new HashMap<>();

    static final class State {
        final Map<String, String> pick = new LinkedHashMap<>();
        final Map<String, Fade> fade = new LinkedHashMap<>();
        final Map<String, Stiff> stiff = new LinkedHashMap<>();
        final Map<String, Chain> chain = new LinkedHashMap<>();
        final Map<String, Shape> shape = new LinkedHashMap<>();
        final Map<String, Double> lvl = new LinkedHashMap<>();
        final Map<String, Spring> bnc = new LinkedHashMap<>();
        final Map<String, Spin> spin = new LinkedHashMap<>();
        final Map<String, Spring> ride = new LinkedHashMap<>();
        final Map<String, Reach> rb = new LinkedHashMap<>();
        double flip;
        int flipWant, frame;
    }

    static final class Fade { String from; double t; boolean morph; }

    static final class Stiff { double th, om, vx, vy, out; double sgn = 1; Double x, y; int f = -9; }

    static final class Chain { int n; double[] p, o; int f; }

    static final class Shape { int n, f; double[] rel, was; }

    static final class Spring { double x, v; int f; }

    static final class Spin { double a; int f = -1; }

    static final class Reach { int m = -1; double t, x, y, fx, fy; int f; }

    public CharacterSolver(JSONObject character, Pics pics) {
        this.ch = character;
        this.pics = pics;
        this.w = character.optDouble("w", 512);
        this.h = character.optDouble("h", 512);
        JSONObject root = character.optJSONObject("root");
        this.ix = new CharIndex(root == null ? new JSONObject() : root, pics);
        ix.walk(ix.root, n -> {
            org.json.JSONArray cl = n.optJSONArray("clips");
            if (cl != null) for (int i = 0; i < cl.length(); i++) { JSONObject q = cl.optJSONObject(i); if (q != null && J.s(q, "by") != null) maskIds.put(J.s(q, "by"), true); }
        });
    }

    /** Forget springs and fades (a jump in time); keeps which way it faces and the frame count, as the pilot does. */
    public void reset() {
        State o = new State();
        o.flip = st.flip;
        o.frame = st.frame;
        st = o;
    }

    /** One frame. {@code S} is changed only where the pilot changes it ({@link Signals#anchor}, {@link Signals#tips}). */
    public SolveOut eval(Signals S, Opt opt) {
        return new Frame(this, S, opt == null ? new Opt() : opt).run();
    }

    /** Where one spot (x, y) of a part with pins is carried to with its shapes worn at these strengths ({shapeId: 0..1}). */
    public double[] warpAt(String nodeId, double x, double y, java.util.Map<String, Double> weights) {
        JSONObject n = ix.n(nodeId);
        if (n == null) return new double[] {x, y};
        return new Frame(this, new Signals(), new Opt()).warpAtPart(n, x, y, weights == null ? new java.util.HashMap<>() : weights);
    }

    /** Rest bounds [x0, y0, x1, y1] of a part, for the room (framing, the transform box). */
    public double[] boundsOf(String id) {
        JSONObject n = ix.n(id);
        return n == null ? null : ix.boundsOf(n);
    }
}
