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
public final class Inputs implements Walker.Body {
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

    private static final List<String> BODY_NAMES = java.util.Arrays.asList("turnX", "turnY", "roll", "sway", "bounce", "lookX", "lookY", "mouth", "vis",
            "browL", "browR", "blinkL", "blinkR", "lidUp", "lidLow", "lidTilt", "wind");

    public final TakeRecorder recorder = new TakeRecorder();
    private final List<String> buttonIds = new ArrayList<>();
    private final Map<String, Boolean> trig = new LinkedHashMap<>();
    private final Set<String> touched = new HashSet<>();     // buttons pressed in this pass are yours to the end of it
    private final Random rnd = new Random(7);

    private double turnX, turnY, roll, mouth, wind;
    // life (the pilot's step): a character that is alive by itself: eased movement, idle sway, darting eyes, auto blinks, breathing
    private org.json.JSONObject settings;                                    // null until the room gives it: then the raw hands are used as they are
    private final Random life = new Random(11);
    private double t, nextBlink = 1.5, blinkT = -1, nextDart = 1, dartX, dartY, smTurnX, smTurnY, smRoll, smSway, smBounce, smMouth, smLookX, smLookY, smBrowL, smBrowR, smLidUp, smLidLow, smLidTilt, smWind;
    private String vis = "Neutral", mood = "Neutral";
    private double[] moodNow = MOODS.get("Neutral");
    private boolean headHeld, talking, blinkHold, micOn, faceOn;
    private Map<String, Float> face;
    // the face camera (the pilot's face branch): its chip's modes, the hand-over from the head pad, its own lanes
    private final cc.joycreator.avatar.face.FaceDrive faceDrive = new cc.joycreator.avatar.face.FaceDrive();
    private final Set<String> faceLanes = new java.util.LinkedHashSet<>();   // the face's lanes (pt.*, face.*) so far, for a take
    private boolean faceSeen;                                                 // the face drives and is seen this frame
    private double micOpen;
    private String micVis = "Neutral";
    private long moodUntil, windUntil, blinkUntil;
    // stance and colour-set levels: buttons ease them in and out (a spring for stances, a steady sweep for colour sets)
    private final List<String[]> stances = new ArrayList<>(), palettes = new ArrayList<>();   // {id, trig, time, bounce}
    private final Map<String, double[]> poseLevel = new LinkedHashMap<>();                    // id -> {level, velocity}
    private final Map<String, Double> toneLevel = new LinkedHashMap<>();
    private double leanX, leanY;                                                               // the joystick on a character that does not walk
    private boolean flipped;                                                                  // the character is turned round (walking the other way)
    private final Map<String, Double> poseBefore = new LinkedHashMap<>();                     // the stance levels as they were at the start of this frame
    private double babbleAt, babbleHold;
    private int babbleI;

    /** The buttons this character has (ids), so their lanes exist in a take. */
    // ── what the walker asks of the hands ──────────────────────────────────────────────────────────

    /** The joystick on a character that does not walk: it leans the way the knob is pushed (0, 0 stands it up). */
    public void lean(double x, double y) {
        leanX = x;
        leanY = y;
    }

    @Override
    public boolean flip() {
        return flipped;
    }

    @Override
    public void setFlip(boolean f) {
        flipped = f;
    }

    @Override
    public void reverseTurn() {
        turnX = -turnX;
    }

    @Override
    public boolean heldHead() {
        return headHeld;
    }

    @Override
    public double[] placed(String held) {
        return null;                       // a finger on a foot or hand: not wired yet
    }

    /** A stance's level as it stood at the start of this frame (the walker reads last frame's, as the pilot does). */
    @Override
    public double stanceLevel(String poseId) {
        Double v = poseBefore.get(poseId);
        return v == null ? 0 : v;
    }

    @Override
    public boolean trigOn(String trigId) {
        return isOn(trigId);
    }

    @Override
    public void setTrig(String trigId, boolean on) {
        setButton(trigId, on);
    }

    /** The character's stances and colour sets (each with its button), so their levels follow the buttons. */
    public void setStances(org.json.JSONObject doc) {
        stances.clear();
        palettes.clear();
        org.json.JSONArray ps = doc.optJSONArray("poses"), cs = doc.optJSONArray("palettes");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            org.json.JSONObject p = ps.optJSONObject(i);
            if (p != null) stances.add(new String[]{p.optString("id"), p.optString("trig"), String.valueOf(p.optDouble("time", 0.6)), String.valueOf(p.optBoolean("bounce", false))});
        }
        for (int i = 0; cs != null && i < cs.length(); i++) {
            org.json.JSONObject p = cs.optJSONObject(i);
            if (p != null) palettes.add(new String[]{p.optString("id"), p.optString("trig"), String.valueOf(p.optDouble("time", 0.9)), "false"});
        }
    }

    /** Stance levels ease in and out on a spring that starts gently and settles without a bump (or a small one when it is bouncy). */
    private void easeLevels(double dt, Map<String, Object> out) {
        for (Map.Entry<String, double[]> e : poseLevel.entrySet()) poseBefore.put(e.getKey(), e.getValue()[0]);
        for (String[] p : stances) {
            boolean on = !p[1].isEmpty() && out.get("g:" + p[1]) instanceof Number && ((Number) out.get("g:" + p[1])).doubleValue() > 0.5;
            double[] st = poseLevel.get(p[0]);
            if (st == null) {
                st = new double[2];
                poseLevel.put(p[0], st);
            }
            double tgt = on ? 1 : 0, x = st[0], v = st[1], om = 5.4 / Math.max(0.08, Double.parseDouble(p[2])), z = Boolean.parseBoolean(p[3]) ? 0.6 : 1, h = dt / 3;
            for (int i = 0; i < 3; i++) {
                v += (om * om * (tgt - x) - 2 * z * om * v) * h;
                x += v * h;
            }
            if (Math.abs(tgt - x) < 0.0006 && Math.abs(v) < 0.004) {
                x = tgt;
                v = 0;
            }
            st[0] = x;
            st[1] = v;
            out.put("pose:" + p[0], x);
        }
        for (String[] p : palettes) {
            boolean on = !p[1].isEmpty() && out.get("g:" + p[1]) instanceof Number && ((Number) out.get("g:" + p[1])).doubleValue() > 0.5;
            double tgt = on ? 1 : 0, x = toneLevel.containsKey(p[0]) ? toneLevel.get(p[0]) : 0, step = dt / Math.max(0.05, Double.parseDouble(p[2]));
            x = x < tgt ? Math.min(tgt, x + step) : Math.max(tgt, x - step);
            toneLevel.put(p[0], x);
            out.put("tone:" + p[0], x);
        }
    }

    /** The character's settings (blinkAuto, blinkEvery, breathe, breathRate, idle, eyesLead); with them the character is alive by itself. */
    public void setSettings(org.json.JSONObject s) {
        settings = s == null ? new org.json.JSONObject() : s;
    }

    /** How deep the chest is now (-1..1), from the clock and the character's breathing rate; 0 when it does not breathe. */
    public double breath() {
        if (settings == null || !settings.optBoolean("breathe", true)) return 0;
        return Math.sin(t * 2 * Math.PI * settings.optDouble("breathRate", 0.28));
    }

    /** The clock the life runs on, in seconds. */
    public double time() {
        return t;
    }

    private static double ease(double cur, double to, double rate, double dt) {
        return cur + (to - cur) * (1 - Math.exp(-rate * dt));
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : v > b ? b : v;
    }

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

    /** The microphone is listening: the mouth follows the voice (its openness 0..1 and mouth shape) and is yours while it is on. */
    public void mic(boolean on) {
        micOn = on;
        if (!on) {
            micOpen = 0;
            micVis = "Neutral";
        }
    }

    public boolean micOn() {
        return micOn;
    }

    /**
     * The face camera is on: head, eyes, brows and mouth are the face's while it drives (straight away when it starts; the
     * Face chip's second tap, {@link #faceDrive()}{@code .setMode(TALK)}, only while Hold to talk is held).
     */
    public void face(boolean on) {
        faceOn = on;
        if (!on) face = null;
        if (on && faceDrive.mode() == cc.joycreator.avatar.face.FaceDrive.Mode.OFF) faceDrive.setMode(cc.joycreator.avatar.face.FaceDrive.Mode.ALWAYS);
        if (!on) faceDrive.setMode(cc.joycreator.avatar.face.FaceDrive.Mode.OFF);
    }

    public boolean faceOn() {
        return faceOn;
    }

    /** The Face chip's modes and the hand-over from the head pad (the head moves from where the pad was: no jump in or out). */
    public cc.joycreator.avatar.face.FaceDrive faceDrive() {
        return faceDrive;
    }

    /** What the face camera saw this frame, as it came (null: none), for walking with your face. */
    public Map<String, Float> faceNow() {
        return faceOn ? face : null;
    }

    /** What the face camera saw this frame (the take's own lane names; null or empty when no face is seen). */
    public void faceSaw(Map<String, Float> signals) {
        face = signals;
    }

    /** What the microphone heard this frame. */
    public void micHeard(double open, String shape) {
        micOpen = Math.max(0, Math.min(1, open));
        micVis = shape == null ? "Neutral" : shape;
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
        if (talking || micOn) o.add("mouth");
        if (faceSeen) {                                    // (the pilot's owned(): only while the face drives and is seen)
            o.add("head");
            o.add("mouth");
            o.add("mood");
            o.add("blink");
            o.add("facepins");
        }
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
        for (String k : faceLanes) if (!n.contains(k)) n.add(k);
        Take t = recorder.take();
        if (t != null) for (String k : t.ch.keySet()) if (!n.contains(k)) n.add(k);
        return n;
    }

    /** What the live hands say now (before a take is laid under them). */
    public Map<String, Object> live(long nowMs, double dt) {
        // the face camera first (the pilot's face branch): it gives the targets the hands would, which then ease as theirs do
        cc.joycreator.avatar.face.FaceDrive.Out fo = faceDrive.apply(faceOn ? face : null, talking, headHeld, nowMs < moodUntil, turnX, turnY, roll, dt);
        boolean faceDrives = faceOn && faceDrive.drives(talking), faceEx = fo.expression;
        faceSeen = fo.drives;
        if (talking && !fo.drives) babble(dt);               // (a face seen while talking: the mouth is yours, not a babble)
        t += dt;
        boolean blink = blinkHold || nowMs < blinkUntil;
        double lookX = fo.drives ? fo.lookX : 0, lookY = fo.drives ? fo.lookY : 0, sway = 0, bounce = 0;
        double tx = fo.head ? fo.turnX : turnX, ty = fo.head ? fo.turnY : turnY, ro = fo.head ? fo.roll : roll;
        double mo = faceEx ? fo.mouth : mouth;
        if (micOn && !talking) mo = faceEx ? Math.max(mo, micOpen) : micOpen;
        double bl = faceEx ? fo.browL : moodNow[3], bR = faceEx ? fo.browR : moodNow[3];
        double lu = faceEx ? fo.lidUp : moodNow[0], ll = faceEx ? fo.lidLow : moodNow[2], lt = faceEx ? fo.lidTilt : moodNow[1], wi = wind;
        if (settings != null) {
            // idle life: small movements even when nobody is touching it; eased so nothing snaps (resting while the face drives)
            double idle = settings.optBoolean("idle", true) && !faceDrives ? 1 : 0;
            tx += idle * 0.035 * Math.sin(t * 0.43);
            ty += idle * 0.03 * Math.sin(t * 0.31 + 2);
            sway += idle * 0.07 * Math.sin(t * 0.52);
            smTurnX = ease(smTurnX, clamp(tx, -1, 1), 11, dt);
            smTurnY = ease(smTurnY, clamp(ty, -1, 1), 11, dt);
            smRoll = ease(smRoll, ro, 10, dt);
            smSway = ease(smSway, sway, 8, dt);
            smBounce = ease(smBounce, bounce, 12, dt);
            smMouth = ease(smMouth, mo, mo > smMouth ? 38 : 22, dt);
            // eyes go first, the head follows, plus small darts
            nextDart -= dt;
            if (nextDart <= 0) {
                nextDart = 1.6 + life.nextDouble() * 2.6;
                dartX = (life.nextDouble() - 0.5) * 0.5;
                dartY = (life.nextDouble() - 0.5) * 0.3;
            }
            boolean lead = settings.optBoolean("eyesLead", true);
            if (!fo.drives) {                                // (a face seen: the eyes look where yours do)
                lookX = (lead ? clamp((tx - smTurnX) * 2.6, -1, 1) : 0) + idle * dartX;
                lookY = (lead ? clamp((ty - smTurnY) * 2.6, -1, 1) : 0) + idle * dartY;
            }
            smLookX = ease(smLookX, clamp(lookX, -1, 1), 26, dt);
            smLookY = ease(smLookY, clamp(lookY, -1, 1), 26, dt);
            double lift = mo > 0.75 ? 0.25 : 0;
            smBrowL = ease(smBrowL, bl + lift, 14, dt);
            smBrowR = ease(smBrowR, bR + lift, 14, dt);
            smLidUp = ease(smLidUp, lu, 12, dt);
            smLidLow = ease(smLidLow, ll, 12, dt);
            smLidTilt = ease(smLidTilt, lt, 10, dt);
            smWind = ease(smWind, wi, 3, dt);
            // blinks: uneven on purpose
            if (blinkT >= 0) {
                blinkT += dt;
                if (blinkT >= 0.11) blinkT = -1;
                else blink = true;
            } else if (settings.optBoolean("blinkAuto", true) && !faceEx) {     // (your own blinks, while the face gives them)
                nextBlink -= dt;
                if (nextBlink <= 0) {
                    blinkT = 0;
                    double every = settings.optDouble("blinkEvery", 3.6);
                    nextBlink = life.nextDouble() < 0.18 ? 0.24 : every * (0.45 + life.nextDouble() * 1.1);
                }
            }
            tx = smTurnX;
            ty = smTurnY;
            ro = smRoll;
            mo = smMouth < 0.015 ? 0 : smMouth;
            lookX = smLookX;
            lookY = smLookY;
            sway = smSway + leanX;
            bounce = smBounce + leanY;
            bl = smBrowL;
            bR = smBrowR;
            lu = smLidUp;
            ll = smLidLow;
            lt = smLidTilt;
            wi = smWind;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turnX", tx);
        m.put("turnY", ty);
        m.put("roll", ro);
        m.put("flip", flipped ? 1.0 : 0.0);
        m.put("sway", sway);
        m.put("bounce", bounce);
        m.put("lookX", lookX);
        m.put("lookY", lookY);
        boolean followMic = micOn && !talking;
        m.put("mouth", mo);                                 // (without the character's settings these are the hands as they are)
        m.put("vis", followMic ? micVis : faceEx ? fo.vis : vis);
        m.put("browL", bl);
        m.put("browR", bR);
        m.put("blinkL", faceEx ? fo.blinkL : blink ? 1.0 : 0.0);
        m.put("blinkR", faceEx ? fo.blinkR : blink ? 1.0 : 0.0);
        m.put("lidUp", lu);
        m.put("lidTilt", lt);
        m.put("lidLow", ll);
        m.put("wind", wi);
        for (String id : buttonIds) m.put("g:" + id, isOn(id) ? 1.0 : 0.0);
        m.putAll(fo.lanes);                                  // the face's own lanes: points pt.<i>.x/y, expressions face.<name>
        faceLanes.addAll(fo.lanes.keySet());
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
        easeLevels(dt, out);
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
