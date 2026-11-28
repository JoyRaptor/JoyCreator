package cc.joycreator.avatar.face;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the face camera does to a performance (the pilot's face branch of {@code tools/avatarlab/al-inputs.js} and the face
 * lanes of {@code al-facepins.js}). Pure: the room hands it what {@code FaceCapture.sample()} saw each frame and where the head
 * pad is; it hands back the TARGETS the hands would otherwise give, which then ease exactly as the hands do
 * ({@code Inputs.live}), plus the face's own lanes for a take.
 *
 * <ul>
 * <li><b>The Face chip</b> (the owner, 11-22): the first tap drives always, the second only while Hold to talk is held, the
 *     third turns the camera off ({@link #next}).</li>
 * <li><b>The head</b> moves from wherever the head pad was when the face took over, by how far your head has moved since:
 *     no jump in, no jump out (the pilot's {@code cam.zero}). The face's turn is already measured from your neutral face and
 *     reaches the pad's ends at about 32 degrees (FaceMapper), so turning and nodding move the head as far as the pad does
 *     (the owner, 11-27: "When I look left to right, it doesn't look left or right").</li>
 * <li><b>The face's expression</b>: mouth, a mouth shape (open: "Aa", or "Ee" in a smile, else "Neutral"), each brow, the
 *     lids, each blink and the gaze. A mood button held a moment ago keeps the expression unless you are talking.</li>
 * <li><b>Lanes</b>: {@code pt.<mesh index>.x / .y} for face pins and {@code face.<name>} for expressions (cheekPuff, smile,
 *     jaw, mouthWide, pucker, wide, squint, browInner, frown, sneer), eased as the pilot eases them, back to rest when the face
 *     is not driving. They ride the take like any lane (owner "facepins"; the cheek puff's is "puff", the Puff button).</li>
 * </ul>
 */
public final class FaceDrive {

    /** The Face chip's three states. */
    public enum Mode { OFF, ALWAYS, TALK }

    /** What the face sets this frame. {@link #drives} false: only {@link #lanes} (easing back to rest) count. */
    public static final class Out {
        public boolean drives, head, expression;
        public double turnX, turnY, roll, mouth, browL, browR, lidUp, lidLow, lidTilt, lookX, lookY, blinkL, blinkR;
        public String vis = "Neutral";
        public final Map<String, Double> lanes = new LinkedHashMap<>();
    }

    /** The expressions any part can follow through Links and Shapes, as {@code face.<name>} (al-facepins.js EXPR). */
    public static final String[] EXPR = {"cheekPuff", "smile", "jaw", "mouthWide", "pucker", "wide", "squint", "browInner", "frown", "sneer"};
    public static final String POINT = "pt.", FACE = "face.";
    /** How fast the lanes follow (per second, as the pilot): the face's points, the expressions. */
    static final double EASE_POINTS = 30, EASE_EXPR = 20;

    private Mode mode = Mode.OFF;
    private boolean puff;                                     // the Puff button is held
    private org.json.JSONObject maskSet;                      // the head wearing the face mask (FACE-2), or null
    private boolean maskMirrored = true;
    private float[] maskRest;
    private double[] zero;                                    // {face turnX, turnY, roll, pad x, pad y, pad roll} when the face took over
    private final Map<String, Double> smPt = new HashMap<>(), smEx = new HashMap<>();

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode m) {
        mode = m == null ? Mode.OFF : m;
        if (mode == Mode.OFF) zero = null;
    }

    /** The Face chip: first tap always, second only while talking, third off. Returns the new mode. */
    public Mode next() {
        setMode(mode == Mode.OFF ? Mode.ALWAYS : mode == Mode.ALWAYS ? Mode.TALK : Mode.OFF);
        return mode;
    }

    /** The camera runs (in either driving mode). */
    public boolean cameraOn() {
        return mode != Mode.OFF;
    }

    public boolean drives(boolean talking) {
        return mode == Mode.ALWAYS || (mode == Mode.TALK && talking);
    }

    /** The Puff button (held): the cheek puff a part can follow, with or without the camera (the tracker never sees a puff). */
    public void puff(boolean on) {
        puff = on;
    }

    public boolean puffing() {
        return puff;
    }

    /** The face mask (FACE-2): the head set wearing it (null: none), Mirror on, and the neutral face's points (or null). */
    public void mask(org.json.JSONObject headSet, boolean mirrored, float[] restPoints) {
        maskSet = headSet != null && MaskDrive.points(headSet) != null ? headSet : null;
        maskMirrored = mirrored;
        maskRest = restPoints;
    }

    /** Re-centre (with the camera's new neutral): the head moves from where the pad is now. */
    public void recentre() {
        zero = null;
    }

    /**
     * One frame. {@code face}: what FaceCapture saw (its signal names; null or empty when no face). {@code pad*}: the head pad
     * now. {@code headHeld}: a finger on the head pad (it wins). {@code moodHeld}: a mood button touched a moment ago.
     */
    public Out apply(Map<String, Float> face, boolean talking, boolean headHeld, boolean moodHeld,
                     double padX, double padY, double padRoll, double dt) {
        Out o = new Out();
        boolean on = drives(talking);
        boolean seen = on && face != null && !face.isEmpty() && face.get("turnX") != null;
        if (!on) zero = null;
        if (seen) {
            double fx = g(face, "turnX"), fy = g(face, "turnY"), fr = g(face, "roll");
            if (zero == null) zero = new double[]{fx, fy, fr, padX, padY, padRoll};
            o.drives = true;
            if (!headHeld) {
                o.head = true;
                o.turnX = clamp(zero[3] + (fx - zero[0]), -1, 1);
                o.turnY = clamp(zero[4] + (fy - zero[1]), -1, 1);
                o.roll = clamp(zero[5] + (fr - zero[2]), -1, 1);
            }
            if (!moodHeld || talking) {
                o.expression = true;
                o.mouth = g(face, "mouth");
                double smile = (g(face, "smileL") + g(face, "smileR")) / 2;
                o.vis = o.mouth > 0.12 ? (smile > 0.5 ? "Ee" : "Aa") : "Neutral";
                o.browL = g(face, "browL");
                o.browR = g(face, "browR");
                o.lidUp = g(face, "lidUp");
                o.lidLow = g(face, "lidLow");
                o.lidTilt = g(face, "lidTilt");
                o.blinkL = g(face, "blinkL");
                o.blinkR = g(face, "blinkR");
            }
            o.lookX = g(face, "lookX");
            o.lookY = g(face, "lookY");
        }
        lanes(seen ? face : null, dt, o.lanes);
        return o;
    }

    /** The face's own lanes, eased: points toward where they are now (rest when no face), expressions likewise. */
    private void lanes(Map<String, Float> face, double dt, Map<String, Double> out) {
        double ap = 1 - Math.exp(-EASE_POINTS * Math.max(0, dt)), ae = 1 - Math.exp(-EASE_EXPR * Math.max(0, dt));
        Map<String, Double> want = new LinkedHashMap<>();
        if (face != null) {                                   // the points the camera watches, where they are now
            for (Map.Entry<String, Float> e : face.entrySet())
                if (e.getKey().startsWith(POINT) && e.getValue() != null && !e.getValue().isNaN()) want.put(e.getKey(), (double) e.getValue());
        } else for (String k : smPt.keySet()) want.put(k, 0.0);   // no face driving: every point eases back to rest
        Map<String, Double> next = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : want.entrySet()) {
            Double cur = smPt.get(e.getKey());
            next.put(e.getKey(), cur == null ? e.getValue() : cur + (e.getValue() - cur) * ap);
        }
        smPt.clear();                                          // (a point no longer watched, a face pin removed, lets go)
        smPt.putAll(next);
        out.putAll(smPt);
        if (maskSet != null) MaskDrive.drive(maskSet, smPt, maskMirrored, maskRest, out);   // FACE-2: the mask follows the points
        for (String n : EXPR) {
            double v = face == null ? 0 : expr(face, n);
            if (puff && n.equals("cheekPuff")) v = 1;           // (the Puff button)
            Double cur = smEx.get(n);
            double c = cur == null ? v : cur + (v - cur) * ae;
            smEx.put(n, c);
            out.put(FACE + n, c);
        }
    }

    /** One expression from the face's signals (al-facepins.js exprOf). */
    static double expr(Map<String, Float> f, String k) {
        switch (k) {
            case "wide": return Math.max(g(f, "wideL"), g(f, "wideR"));
            case "squint": return Math.max(g(f, "squintL"), g(f, "squintR"));
            case "frown": return (g(f, "frownL") + g(f, "frownR")) / 2;
            case "smile": return (g(f, "smileL") + g(f, "smileR")) / 2;
            default: return g(f, k);
        }
    }

    /** The lane's owner in a take: the face's lanes are the face pins' (the pilot's ownerOf). Null: not a face lane. */
    public static String ownerOf(String lane) {
        if (lane == null) return null;
        if (lane.equals(FACE + "cheekPuff")) return "puff";     // (its own owner: a pass with Puff held leaves the face's other lanes alone)
        if (lane.startsWith(POINT) || lane.startsWith(FACE) || lane.startsWith("mk.") || lane.startsWith("mp.")) return "facepins";
        if (lane.equals("shrugL") || lane.equals("shrugR") || lane.equals("shrug")) return "shoulders";
        return null;
    }

    private static double g(Map<String, Float> f, String k) {
        Float v = f.get(k);
        return v == null || v.isNaN() ? 0 : v;
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : v > b ? b : v;
    }
}
