package cc.joycreator.avatar.ui;

import cc.joycreator.avatar.solve.Signals;

import java.util.Map;

/** The room's per-frame signals (the take's lane names) as the solver reads them ({@link Signals}). */
final class SignalMap {
    private SignalMap() {}

    private static double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    static Signals toSignals(Map<String, Object> m, double t, double breath) {
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
        }
        return s;
    }
}
