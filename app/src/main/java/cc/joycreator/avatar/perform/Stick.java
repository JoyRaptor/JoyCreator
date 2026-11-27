package cc.joycreator.avatar.perform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The joystick (a port of al-stick.js and al-stick-moves.js): a rate stick that walks the body, and a quick tap or flick on its
 * pad that is a gesture instead. It drives exactly what dragging the body drives (the {@link Walker}), so recording and passes
 * take it with no change. A character that does not walk leans the way the knob is pushed.
 */
public final class Stick {
    public static final double SPEED = 1.0;      // body heights a second at the rim
    public static final double CURVE = 1.7;      // how much gentler the middle is than the rim
    public static final long TAP_MS = 230;
    public static final double TAP_MOVE = 0.2;   // pad radii
    public static final long SWIPE_MS = 360;
    public static final double SWIPE_DIST = 0.45;
    public static final long DOUBLE_MS = 280;    // a first tap waits this long for a second (only when Two taps has an act)

    /** The six slots: key, then what the sheet calls it. */
    public static final String[][] SLOTS = {{"tap", "Tap"}, {"dtap", "Two taps"}, {"up", "Swipe up"}, {"down", "Swipe down"}, {"left", "Swipe left"}, {"right", "Swipe right"}};

    /** Does a thing a moment later (the room's handler). */
    public interface Later {
        void after(long ms, Runnable r);

        /** Cancel what {@link #after} has not yet run for this tag. */
        default void cancel(Object tag) {
        }
    }

    /** One act on offer: its key, its name and what it does. */
    public static final class Act {
        public final String key, name, title;

        Act(String key, String name, String title) {
            this.key = key;
            this.name = name;
            this.title = title;
        }
    }

    private final JSONObject ch;
    private final Inputs inputs;
    private final Walker walker;
    private final Later later;
    private double bodyW = 100, bodyH = 100;
    private boolean on, walks;
    private double kx, ky;
    private double[] v = {0, 0};
    private boolean tapWaiting;
    private String flash;
    private long flashUntil;

    public Stick(JSONObject character, Inputs inputs, Walker walker, Later later) {
        this.ch = character;
        this.inputs = inputs;
        this.walker = walker;
        this.later = later;
    }

    /** How big the character is (character pixels): a body height a second at the rim is measured on this. */
    public void setBodySize(double w, double h) {
        bodyW = Math.max(20, w);
        bodyH = Math.max(20, h);
    }

    // ── the steer ──────────────────────────────────────────────────────────────────────────────────

    public boolean pressed() {
        return on;
    }

    public double knobX() {
        return kx;
    }

    public double knobY() {
        return ky;
    }

    /** A thumb went down. */
    public void press() {
        on = true;
        walks = walker != null && walker.travels();
        if (walks) {
            walker.grab(0, 0);
            v = new double[]{0, 0};
        }
    }

    /** The knob is at x, y (-1..1 each way). */
    public void knob(double x, double y) {
        kx = clamp(x, -1, 1);
        ky = clamp(y, -1, 1);
    }

    /** Let go: the body stops where it is (a walker), or stands up straight (a lean). */
    public void release() {
        if (!on) return;
        on = false;
        kx = ky = 0;
        if (walks) walker.release();
        else inputs.lean(0, 0);
    }

    /** Once a frame while a thumb is on the pad: push the body on at the speed the knob asks for. */
    public void step(double dt) {
        if (!on) return;
        double gx = Math.signum(kx) * Math.pow(Math.abs(kx), CURVE), gy = Math.signum(ky) * Math.pow(Math.abs(ky), CURVE);
        if (!walks) {
            inputs.lean(clamp(kx, -1, 1), clamp(ky, -1, 1));
            return;
        }
        double[] g0 = walker.grabbed();
        if (g0 == null) {
            walker.grab(0, 0);
            g0 = walker.grabbed();
        }
        double sp = SPEED * bodyH * dt, lo = -bodyW * 0.7, hi = bodyW * 0.7;
        v[0] = clamp(v[0] + gx * sp, lo - g0[2], hi - g0[2]);                                         // stays within reach of the picture
        v[1] = clamp(v[1] + gy * sp, walker.baseY - bodyH - g0[3], walker.baseY + bodyH * 0.5 - g0[3]);  // up toward a jump, down toward a crouch
        walker.drag(v[0], v[1]);
    }

    // ── a gesture, or a steer ──────────────────────────────────────────────────────────────────────

    /** A touch's own record: when it began, where (pad units -1..1), where it is, how far it strayed. */
    public static final class Touch {
        public long t0;
        public double x0, y0, x, y, far;
        public double tx, ty;                          // where the body was when the thumb went down

        public void move(double nx, double ny) {
            x = nx;
            y = ny;
            far = Math.max(far, Math.hypot(x - x0, y - y0));
        }
    }

    public Touch begin(long nowMs, double padX, double padY) {
        Touch t = new Touch();
        t.t0 = nowMs;
        t.x0 = t.x = padX;
        t.y0 = t.y = padY;
        if (walker != null) {
            t.tx = walker.tx;
            t.ty = walker.ty;
        }
        return t;
    }

    /** A quick touch is a tap, a quick flick a swipe; anything held is the joystick (null). */
    public static String gestureOf(Touch g, long nowMs) {
        long dt = nowMs - g.t0;
        double dx = g.x - g.x0, dy = g.y - g.y0, d = Math.hypot(dx, dy);
        if (dt < TAP_MS && g.far < TAP_MOVE) return "tap";
        if (dt < SWIPE_MS && d >= SWIPE_DIST) return Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
        return null;
    }

    /** The thumb lifted: a gesture is done (the little the body was nudged is taken back), a steer just stops. Returns what was done, or null. */
    public String lift(Touch g, long nowMs) {
        String kind = g == null ? null : gestureOf(g, nowMs);
        release();
        if (kind == null) return null;
        if (walker != null && walker.travels()) {
            walker.tx = g.tx;
            walker.ty = g.ty;
        }
        return gesture(kind);
    }

    // ── the acts this character can do ─────────────────────────────────────────────────────────────

    private boolean travels() {
        return walker != null && walker.travels();
    }

    private JSONObject stanceOf(String trig) {
        JSONArray ps = ch.optJSONArray("poses");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && trig.equals(p.optString("trig"))) return p;
        }
        return null;
    }

    private boolean hasLids() {
        return hasRole(ch.optJSONObject("root"), "lidU") || hasRole(ch.optJSONObject("root"), "lidL");
    }

    private static boolean hasRole(JSONObject n, String role) {
        if (n == null) return false;
        if (role.equals(n.optString("role"))) return true;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) if (hasRole(k.optJSONObject(i), role)) return true;
        return false;
    }

    /** Every act on offer: only what this character can really do. */
    public List<Act> acts() {
        List<Act> l = new ArrayList<>();
        l.add(new Act("none", "Nothing", "This gesture does nothing"));
        if (travels()) {
            l.add(new Act("jump", "Jump", "Up past where the feet let go, and down again"));
            l.add(new Act("dash-l", "Dash left", "Off to the left as fast as its legs go"));
            l.add(new Act("dash-r", "Dash right", "Off to the right as fast as its legs go"));
            l.add(new Act("home", "Back to the middle", "Walk back to where it started"));
        }
        l.add(new Act("flip", "Turn round", "Face the other way"));
        JSONArray ts = ch.optJSONArray("triggers");
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            l.add(new Act("trig:" + t.optString("id"), t.optString("name", t.optString("id")), (stanceOf(t.optString("id")) != null ? "The stance “" : "Its button “") + t.optString("name") + "”, on and off"));
        }
        l.add(new Act("blink", "Blink", "A blink"));
        if (hasLids()) l.add(new Act("mood", "Next mood", "Its eyelids and brows step to the next mood"));
        return l;
    }

    /** The gestures as they are now: this character's own, or the ones it starts with. An act it cannot do falls back, never a dead one. */
    public Map<String, String> map() {
        JSONObject settings = ch.optJSONObject("settings");
        JSONObject own = settings == null ? null : settings.optJSONObject("stick");
        Map<String, Boolean> keys = new LinkedHashMap<>();
        for (Act a : acts()) keys.put(a.key, true);
        JSONArray ps = ch.optJSONArray("poses");
        String stance = null;
        for (int i = 0; ps != null && i < ps.length() && stance == null; i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && !p.optString("trig").isEmpty()) stance = p.optString("trig");
        }
        boolean tr = travels();
        Map<String, String> def = new LinkedHashMap<>();
        def.put("tap", stance != null ? "trig:" + stance : "blink");
        def.put("dtap", "flip");
        def.put("up", tr ? "jump" : "blink");
        def.put("down", "none");
        def.put("left", tr ? "dash-l" : "none");
        def.put("right", tr ? "dash-r" : "none");
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] s : SLOTS) {
            String k = own == null ? null : own.optString(s[0], null);
            out.put(s[0], k != null && keys.containsKey(k) ? k : keys.containsKey(def.get(s[0])) ? def.get(s[0]) : "none");
        }
        return out;
    }

    // ── doing it: what a finger would do ───────────────────────────────────────────────────────────

    /** What a gesture just did, for the pad to say for a moment (null when nothing is showing). */
    public String flash(long nowMs) {
        return flash != null && nowMs < flashUntil ? flash : null;
    }

    private void say(String text) {
        flash = text;
        flashUntil = System.currentTimeMillis() + 900;
    }

    /** Runs an act now. Returns what it did, or null. */
    public String run(String key) {
        Act a = null;
        for (Act q : acts()) if (q.key.equals(key)) a = q;
        if (a == null || key.equals("none")) return null;
        Walker w = walker;
        if (key.equals("jump")) {
            double up = bodyH * 0.35;
            if (w.grounded()) {                                       // pulled up past where the feet let go, then let go: it falls by itself
                w.grab(0, 0);
                w.drag(0, -up);
                later.after(230, () -> {
                    if (w.held) w.release();
                });
            } else {                                                   // floating: up and back
                final double y0 = w.ty;
                w.ty = y0 - bodyH * 0.35;
                later.after(300, () -> w.ty = y0);
            }
        } else if (key.equals("dash-l") || key.equals("dash-r")) {
            w.tx = clamp(w.tx + (key.equals("dash-r") ? 1 : -1) * bodyW * 0.6, -bodyW * 0.7, bodyW * 0.7);
        } else if (key.equals("home")) {
            w.home();
        } else if (key.equals("flip")) {
            inputs.setFlip(!inputs.flip());
        } else if (key.equals("blink")) {
            inputs.blinkNow(System.currentTimeMillis());
        } else if (key.equals("mood")) {
            List<String> ms = new ArrayList<>(Inputs.MOODS.keySet());
            int i = ms.indexOf(inputs.moodName());
            inputs.mood(ms.get((i + 1) % ms.size()), System.currentTimeMillis());
        } else if (key.startsWith("trig:")) {
            inputs.toggleButton(key.substring(5));
        }
        String name = key.equals("mood") ? inputs.moodName() : key.startsWith("trig:") ? a.name + (inputs.isOn(key.substring(5)) ? " on" : " off") : a.name;
        say(name);
        return name;
    }

    /** A gesture came off the pad. A tap waits a moment for a second one, but only when Two taps has something to do. */
    public String gesture(String kind) {
        Map<String, String> m = map();
        if (!kind.equals("tap")) return run(m.get(kind));
        if (tapWaiting) {
            tapWaiting = false;
            later.cancel(this);
            return run(m.get("dtap"));
        }
        if (m.get("dtap").equals("none")) return run(m.get("tap"));
        tapWaiting = true;
        later.after(DOUBLE_MS, () -> {
            if (!tapWaiting) return;
            tapWaiting = false;
            run(m.get("tap"));
        });
        return null;
    }

    /** Fill a slot in the character's settings (the room wraps this in one undo step). */
    public static void setSlot(JSONObject character, String slot, String key) throws org.json.JSONException {
        JSONObject st = character.optJSONObject("settings");
        if (st == null) character.put("settings", st = new JSONObject());
        JSONObject s = st.optJSONObject("stick");
        if (s == null) st.put("stick", s = new JSONObject());
        s.put(slot, key);
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : v > b ? b : v;
    }
}
