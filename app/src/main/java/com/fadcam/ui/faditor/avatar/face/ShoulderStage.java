package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * SHRUG-1 (the owner, 11-22: "Is there a way for us to get the shoulders animated for shoulder shrugs?"): the
 * shoulders from the camera's body tracker (MediaPipe Pose, 33 points) to a shrug for each of the character's sides.
 *
 * <p>What is measured: how far each shoulder sits below the middle of the ears, in shoulder widths, against this
 * person's own rest. A shrug brings the shoulder up toward the ears. The ears' middle barely moves when the head
 * tilts or nods; dividing by the shoulders' width cancels coming closer or going further; leaning moves ears and
 * shoulders together. So only the shoulders themselves make a shrug.</p>
 *
 * <p>Like the face ({@link FaceStage}): the first still half-second with the shoulders in view becomes the rest
 * (and {@link #requestNeutral} takes it again); a lost body holds a moment, then eases to rest; a returning one eases
 * in. Mirror as the face: by default a reflection, the person's right shoulder lifting the character's left.
 * Pure Java; the web pilot's {@code al-pose.js} is the same, line for line.</p>
 */
public final class ShoulderStage {

    /** The signals. The character's own sides, as the face's. */
    public static final String SHRUG_L = "shrugL", SHRUG_R = "shrugR", SHRUG = "shrug", BODY = "body";
    /** The take's lane for them. */
    public static final String LANE = "shoulders";

    /** MediaPipe Pose's points (the person's own sides): ears and shoulders. */
    public static final int EAR_L = 7, EAR_R = 8, SHOULDER_L = 11, SHOULDER_R = 12, COUNT = 33;
    /** A point counts as seen at this visibility. */
    static final float SEEN = 0.5f;
    /** A full shrug: the shoulder this many shoulder widths nearer the ears than at rest (the owner's reached 0.13 to 0.19). */
    static final float SPAN = 0.16f;
    /** Breathing, the tracker's jitter and drift: less than this is no shrug (his test drifted up to 0.07, 11-22). */
    static final float DEAD = 0.04f;
    static final double HOLD_S = 0.35, EASE_OUT_S = 0.8, EASE_IN_S = 0.3;
    /** Smoothing toward the tracker's reading, per second (about 70 ms behind). */
    static final double RATE = 14;
    /** The rest: this many readings in a row that stay within {@link #STILL} of the first. */
    static final int NEUTRAL_FRAMES = 5;
    static final float STILL = 0.03f;

    private float restL = Float.NaN, restR = Float.NaN;
    private boolean wantNeutral = true;
    private int capN;
    private float cap0L, cap0R, capSumL, capSumR;
    private float smL, smR, lastL, lastR;
    private double lastT = Double.NaN, lostSince = Double.NaN, presence;
    private boolean tracking;

    /**
     * How far each shoulder sits below the ears' middle, in shoulder widths: {the picture's left shoulder (the
     * person's right), the picture's right}. {@code xyv} is x, y, visibility per point (0..1 of the picture), {@code
     * tall} the picture's height over its width. Null when a shoulder, or both ears, are not seen.
     */
    @Nullable
    public static float[] drops(@Nullable float[] xyv, float tall) {
        if (xyv == null || xyv.length < COUNT * 3) return null;
        if (vis(xyv, SHOULDER_L) < SEEN || vis(xyv, SHOULDER_R) < SEEN) return null;
        boolean el = vis(xyv, EAR_L) >= SEEN, er = vis(xyv, EAR_R) >= SEEN;
        if (!el && !er) return null;
        float earY = el && er ? (y(xyv, EAR_L, tall) + y(xyv, EAR_R, tall)) / 2f : y(xyv, el ? EAR_L : EAR_R, tall);
        float dx = x(xyv, SHOULDER_L) - x(xyv, SHOULDER_R), dy = y(xyv, SHOULDER_L, tall) - y(xyv, SHOULDER_R, tall);
        float w = (float) Math.sqrt(dx * dx + dy * dy);
        if (w < 1e-4f) return null;
        // the picture's left is the person's right shoulder (an unmirrored front camera)
        return new float[]{(y(xyv, SHOULDER_R, tall) - earY) / w, (y(xyv, SHOULDER_L, tall) - earY) / w};
    }

    private static float vis(float[] p, int i) { return p[i * 3 + 2]; }
    private static float x(float[] p, int i) { return p[i * 3]; }
    private static float y(float[] p, int i, float tall) { return p[i * 3 + 1] * tall; }

    /** The next still half-second with the shoulders in view becomes the rest. */
    public void requestNeutral() { wantNeutral = true; capN = 0; }

    public boolean hasNeutral() { return !Float.isNaN(restL); }

    /**
     * One reading. {@code drops} from {@link #drops}, or null when the body (or a shoulder) is not seen.
     * {@code mirror}: the Mirror setting off (like someone facing you). Returns the shrug signals (a new map).
     */
    @NonNull
    public Map<String, Float> process(@Nullable float[] drops, double t, boolean mirror) {
        double dt = Double.isNaN(lastT) ? 0 : Math.max(0, Math.min(0.5, t - lastT));
        lastT = t;
        float trackL, trackR;
        if (drops != null) {
            if (wantNeutral) capture(drops);
            if (!hasNeutral()) return out(0f, 0f, 0f);
            if (!tracking) { smL = drops[0]; smR = drops[1]; tracking = true; lostSince = Double.NaN; }
            float k = (float) (1 - Math.exp(-RATE * dt));
            smL += (drops[0] - smL) * k;
            smR += (drops[1] - smR) * k;
            float pl = lift(restL - smL), pr = lift(restR - smR);
            // the character's left: the person's right (the picture's left) like a reflection, their left with Mirror off
            lastL = mirror ? pr : pl;
            lastR = mirror ? pl : pr;
            presence = Math.min(1, presence + dt / EASE_IN_S);
            trackL = lastL; trackR = lastR;
        } else {
            if (tracking) { tracking = false; lostSince = t; }
            capN = 0;
            if (!Double.isNaN(lostSince) && t - lostSince >= HOLD_S) presence = Math.max(0, presence - dt / EASE_OUT_S);
            trackL = lastL; trackR = lastR;
        }
        float p = smooth((float) presence);
        return out(trackL * p, trackR * p, p);
    }

    private void capture(float[] d) {
        if (capN > 0 && (Math.abs(d[0] - cap0L) > STILL || Math.abs(d[1] - cap0R) > STILL)) capN = 0;   // moved: start again
        if (capN == 0) { cap0L = d[0]; cap0R = d[1]; capSumL = 0; capSumR = 0; }
        capSumL += d[0]; capSumR += d[1]; capN++;
        if (capN >= NEUTRAL_FRAMES) {
            restL = capSumL / capN; restR = capSumR / capN;
            wantNeutral = false; capN = 0;
        }
    }

    /** Shoulder widths nearer the ears than at rest, to 0..1. */
    static float lift(float nearer) {
        float v = (nearer - DEAD) / (SPAN - DEAD);
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float smooth(float k) { return k * k * (3f - 2f * k); }

    @NonNull
    private static Map<String, Float> out(float l, float r, float body) {
        Map<String, Float> m = new HashMap<>();
        m.put(SHRUG_L, l);
        m.put(SHRUG_R, r);
        m.put(SHRUG, (l + r) / 2f);
        m.put(BODY, body);
        return m;
    }
}
