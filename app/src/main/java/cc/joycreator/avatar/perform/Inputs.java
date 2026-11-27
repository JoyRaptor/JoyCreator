package cc.joycreator.avatar.perform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Where the performance comes from (the hands-on half of {@code tools/avatarlab/al-inputs.js}): the head pad, Hold to
 * talk, a mood, a blink, the buttons, the Breeze, and a recorded take all end up as ONE small record of signals per
 * frame, which the solver draws. Headless: the room's controls call the setters, a frame loop calls {@link #frame}.
 *
 * <p>Signal names are the take's own lanes (turnX, turnY, roll, mouth, vis, browL, browR, blinkL, blinkR, lidUp, lidLow,
 * lidTilt, wind, and {@code g:<button id>}), so whatever the solver reads from a take it reads from here.</p>
 */
public final class Inputs {
    /** A mood is three numbers for the lids and one for the brows: {upper lid, tilt, lower lid, brow}. */
    public static final Map<String, double[]> MOODS = new LinkedHashMap<>();

    static {
        MOODS.put("Neutral", new double[]{0, 0, 0, 0});
        MOODS.put("Relaxed", new double[]{0.42, 0, 0, 0});
        MOODS.put("Sleepy", new double[]{0.72, 0, 0.12, -0.3});
        MOODS.put("Angry", new double[]{0.3, 1, 0.18, -0.9});
        MOODS.put("Sad", new double[]{0.26, -1, 0, 0.35});
        MOODS.put("Cheery", new double[]{0, 0, 0.8, 0.45});
        MOODS.put("Wary", new double[]{0.4, 0.35, 0.45, -0.4});
    }

    /** Mouth shapes a babbling mouth moves between while Hold to talk is held. */
    private static final String[] BABBLE = {"Aa", "Oh", "Ee", "Aa", "W-Oo", "Aa", "M"};
    /** How long a mood or the Breeze stays "yours" (owned by the live hand) after it was last touched, in ms. */
    private static final long HOLD_MOOD_MS = 700, HOLD_WIND_MS = 600, BLINK_MS = 200;

    private static final List<String> BODY_NAMES = java.util.Arrays.asList("turnX", "turnY", "roll", "lookX", "lookY", "mouth", "vis",
            "browL", "browR", "blinkL", "blinkR", "lidUp", "lidLow", "lidTilt", "wind");

    public final TakeRecorder recorder = new TakeRecorder();
    private final List<String> buttonIds = new ArrayList<>();
    private final Map<String, Boolean> trig = new LinkedHashMap<>();
    private final Set<String> touched = new HashSet<>();     // buttons pressed in this pass are yours to the end of it
    private final Random rnd = new Random(7);

    private double turnX, turnY, roll, mouth, wind;
    private String vis = "Neutral", mood = "Neutral";
    private double[] moodNow = MOODS.get("Neutral");
    private boolean headHeld, talking, blinkHold;
    private long moodUntil, windUntil, blinkUntil;
    private double babbleAt, babbleHold;
    private int babbleI;

    /** The buttons this character has (ids), so their lanes exist in a take. */
    public void setButtons(List<String> ids) {
        buttonIds.clear();
        buttonIds.addAll(ids);
    }

    // ── the hands ─────────────────────────────────────────────────────────────────────────────────

    /** A finger on the head pad: -1..1 each way. */
    public void head(double x, double y) {
        turnX = Math.max(-1, Math.min(1, x));
        turnY = Math.max(-1, Math.min(1, y));
        headHeld = true;
    }

    public void releaseHead() {
        headHeld = false;
    }

    /** Hold to talk. */
    public void talk(boolean on) {
        talking = on;
        if (!on) {
            mouth = 0;
            vis = "Neutral";
        }
    }

    public void blinkHold(boolean on) {
        blinkHold = on;
    }

    /** A tap on Blink. */
    public void blinkNow(long nowMs) {
        blinkUntil = nowMs + BLINK_MS;
    }

    public void mood(String name, long nowMs) {
        double[] m = MOODS.get(name);
        if (m == null) return;
        mood = name;
        moodNow = m;
        moodUntil = nowMs + HOLD_MOOD_MS;
    }

    public String moodName() {
        return mood;
    }

    /** The Breeze slider: -1 (left) to 1 (right). */
    public void breeze(double v, long nowMs) {
        wind = Math.abs(v) < 0.06 ? 0 : Math.max(-1, Math.min(1, v));
        windUntil = nowMs + HOLD_WIND_MS;
    }

    public void toggleButton(String id) {
        setButton(id, !isOn(id));
    }

    public void setButton(String id, boolean on) {
        if (isOn(id) == on) return;
        trig.put(id, on);
        if (recorder.recording()) touched.add("g:" + id);
    }

    public boolean isOn(String id) {
        Boolean b = trig.get(id);
        return b != null && b;
    }

    /** Head to the middle, no buttons, no wind, neutral mood. */
    public void letGoOfEverything() {
        turnX = turnY = roll = mouth = wind = 0;
        vis = "Neutral";
        mood = "Neutral";
        moodNow = MOODS.get("Neutral");
        headHeld = talking = blinkHold = false;
        trig.clear();
    }

    // ── a pass ────────────────────────────────────────────────────────────────────────────────────

    public void record() {
        touched.clear();
        recorder.record();
    }

    // ── one frame ─────────────────────────────────────────────────────────────────────────────────

    /** Who is being driven by a live hand right now. */
    public Set<String> owners(long nowMs) {
        Set<String> o = new HashSet<>();
        if (headHeld) o.add("head");
        if (talking) o.add("mouth");
        if (nowMs < moodUntil) o.add("mood");
        if (blinkHold || nowMs < blinkUntil) o.add("blink");
        if (nowMs < windUntil) o.add("wind");
        o.addAll(touched);
        return o;
    }

    /** Every signal a take may hold: the body's own, one lane per button, and whatever the take already has. */
    public List<String> channels() {
        List<String> n = new ArrayList<>(BODY_NAMES);
        for (String id : buttonIds) n.add("g:" + id);
        Take t = recorder.take();
        if (t != null) for (String k : t.ch.keySet()) if (!n.contains(k)) n.add(k);
        return n;
    }

    /** What the live hands say now (before a take is laid under them). */
    public Map<String, Object> live(long nowMs, double dt) {
        if (talking) babble(dt);
        boolean blink = blinkHold || nowMs < blinkUntil;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turnX", turnX);
        m.put("turnY", turnY);
        m.put("roll", roll);
        m.put("lookX", 0.0);
        m.put("lookY", 0.0);
        m.put("mouth", mouth);
        m.put("vis", vis);
        m.put("browL", moodNow[3]);
        m.put("browR", moodNow[3]);
        m.put("blinkL", blink ? 1.0 : 0.0);
        m.put("blinkR", blink ? 1.0 : 0.0);
        m.put("lidUp", moodNow[0]);
        m.put("lidTilt", moodNow[1]);
        m.put("lidLow", moodNow[2]);
        m.put("wind", wind);
        for (String id : buttonIds) m.put("g:" + id, isOn(id) ? 1.0 : 0.0);
        return m;
    }

    /**
     * One frame: the live hands, with the take under them. Returns the signals the solver draws. While recording only
     * what is being driven is written; the rest of the old take plays under it.
     */
    public Map<String, Object> frame(double dt, long nowMs) {
        Map<String, Object> live = live(nowMs, dt);
        Map<String, Object> played = recorder.step(dt, live, channels(), owners(nowMs));
        Map<String, Object> out = new LinkedHashMap<>(live);
        out.putAll(played);
        return out;
    }

    /** The mouth moves while Hold to talk is held: open and shut, changing shape. */
    private void babble(double dt) {
        babbleAt -= dt;
        if (babbleAt <= 0) {
            babbleAt = 0.09 + rnd.nextDouble() * 0.12;
            babbleHold = 0.35 + rnd.nextDouble() * 0.65;
            babbleI = (babbleI + 1 + rnd.nextInt(3)) % BABBLE.length;
        }
        mouth += (babbleHold - mouth) * Math.min(1, dt * 18);
        vis = mouth < 0.12 ? "Neutral" : BABBLE[babbleI];
    }
}
