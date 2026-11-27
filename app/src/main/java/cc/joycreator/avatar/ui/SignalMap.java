package cc.joycreator.avatar.ui;

import cc.joycreator.avatar.solve.Signals;

import java.util.Map;

/** The room's per-frame signals (the take's lane names) as the solver reads them ({@link Signals}). */
public final class SignalMap {
    /** The face camera's lanes a part can follow by name (AVA_FORMAT B4): pt.*, face.*, mk.*, mp.*, shrugL / shrugR / shrug. */
    private static final java.util.regex.Pattern FACE_LANE = java.util.regex.Pattern.compile("pt\\.|face\\.|mk\\.|mp\\.|shrug(L|R)?$");

    private SignalMap() {}

    private static double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    public static Signals toSignals(Map<String, Object> m, double t, double breath) {
        Signals s = new Signals();
        s.t = t;
        s.breath = breath;
        s.turnX = num(m, "turnX");
        s.turnY = num(m, "turnY");
        s.roll = num(m, "roll");
        s.lookX = num(m, "lookX");
        s.lookY = num(m, "lookY");
        s.sway = num(m, "sway");
        s.bounce = num(m, "bounce");
        s.browL = num(m, "browL");
        s.browR = num(m, "browR");
        s.blinkL = num(m, "blinkL");
        s.blinkR = num(m, "blinkR");
        s.lidUp = num(m, "lidUp");
        s.lidLow = num(m, "lidLow");
        s.lidTilt = num(m, "lidTilt");
        s.wind = num(m, "wind");
        s.flip = num(m, "flip") > 0.5;
        s.mouth = m.containsKey("mouth") ? num(m, "mouth") : null;
        Object v = m.get("vis");
        s.viseme = v instanceof String ? (String) v : "Neutral";
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("g:") && e.getValue() instanceof Number && ((Number) e.getValue()).doubleValue() > 0.5) s.trig.put(k.substring(2), true);
            else if (k.startsWith("pose:") && e.getValue() instanceof Number) s.pose.put(k.substring(5), ((Number) e.getValue()).doubleValue());
            else if (k.startsWith("tone:") && e.getValue() instanceof Number) s.tone.put(k.substring(5), ((Number) e.getValue()).doubleValue());
            else if (FACE_LANE.matcher(k).lookingAt() && e.getValue() instanceof Number) s.extra.put(k, ((Number) e.getValue()).doubleValue());   // face pins, expressions, the mask, shrugs
        }
        return s;
    }
}
