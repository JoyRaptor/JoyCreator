package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.avatar.LifeSignals;

import java.util.HashMap;
import java.util.Map;

/**
 * FACE-1: the stateful half of face capture, run once per tracker frame on the tracker's thread
 * (inside {@code TrackingParamPipeline}). Raw face in, face signals out:
 * <ol>
 *   <li><b>Neutral</b>: a requested (or, the very first time, automatic) capture of a still face
 *       becomes the calibration; until then the old one keeps working.</li>
 *   <li><b>Directions</b>: a running {@link FaceCalibration.DirectionCheck} gets each frame.</li>
 *   <li><b>Mapping</b> through {@link FaceMapper}; the eyes hold where they were looking while
 *       they blink; a sheet mouth's shape changes only when the new shape holds for two frames.</li>
 *   <li><b>Lost and found</b>: when the face goes, the last face holds for a moment, then eases to
 *       a relaxed rest while the idle life (blinks, eye darts) takes over; when it comes back the
 *       character crosses over to it instead of jumping. {@link FaceSignals#FACE} says how much the
 *       live face is driving.</li>
 * </ol>
 * Pure Java and deterministic (frame times only, no clocks), so it is harness-tested and a take
 * replays exactly. Requests from the screen (neutral, a direction check, new settings) are flags
 * read on the next frame, like the bus's reset.
 */
public final class FaceStage {

    /** The last face holds this long before easing away (a blink of tracking is not a loss). */
    static final double HOLD_S = 0.35;
    /** Easing to rest once the face is gone, and crossing back when it returns. */
    static final double EASE_OUT_S = 0.8, EASE_IN_S = 0.3;
    /** Frames of a still face that make a neutral (about half a second at 15 a second). */
    static final int NEUTRAL_FRAMES = 8;

    private volatile FaceCalibration cal;
    private volatile FaceTuning tuning;
    private volatile boolean neutralRequested;
    private volatile boolean autoNeutral = true;
    @Nullable private volatile String checkRequested;
    @Nullable private volatile FaceCalibration.DirectionCheck lastCheck;
    /** FACE-1 face pins: the mesh points whose movement is put out as {@link FaceSignals#pointX}/Y. */
    @NonNull private volatile int[] watched = new int[0];

    // ── tracker-thread state ──
    @Nullable private FaceCalibration.Capture capture;
    @Nullable private FaceCalibration.DirectionCheck check;
    private Map<String, Float> lastTracked = FaceSignals.rest();
    @NonNull private Map<String, Float> lastOut = FaceSignals.rest();
    @Nullable private Map<String, Float> crossFrom;
    private double crossK = 1;
    private double presence = 0;
    private double lastT = Double.NaN;
    private double lostSince = Double.NaN;
    private float gazeX, gazeY;
    private float vis = 0, visPending = 0;
    private int visPendingFrames = 0;
    private volatile boolean found;
    private volatile int captureCount;

    public FaceStage() {
        this(FaceCalibration.none(), new FaceTuning());
    }

    public FaceStage(@NonNull FaceCalibration cal, @NonNull FaceTuning tuning) {
        this.cal = cal;
        this.tuning = tuning;
    }

    // ── requests (any thread) ──

    /** One tap: the next half-second of a still face becomes neutral. */
    public void requestNeutral() { neutralRequested = true; }

    /** Off: never capture a neutral on its own (the first one is otherwise automatic). */
    public void setAutoNeutral(boolean on) { autoNeutral = on; }

    /** Start one direction prompt ({@link FaceCalibration.DirectionCheck#YAW} etc.). */
    public void startDirectionCheck(@NonNull String what) {
        lastCheck = null;
        checkRequested = what;
    }

    /** The prompt last answered (null while one is running or none was asked). */
    @Nullable
    public FaceCalibration.DirectionCheck lastDirectionCheck() { return lastCheck; }

    @NonNull public FaceCalibration calibration() { return cal; }
    public void setCalibration(@NonNull FaceCalibration c) { cal = c; }
    @NonNull public FaceTuning tuning() { return tuning; }
    public void setTuning(@NonNull FaceTuning t) { tuning = t.copy(); }

    /** Face pins: put out the movement of these face points (mesh indices); empty for none. */
    public void setWatchedPoints(@NonNull int[] points) { watched = points.clone(); }

    @NonNull public int[] watchedPoints() { return watched.clone(); }

    /** True while a face is being tracked. */
    public boolean isFound() { return found; }
    /** True while a neutral is being captured (requested, or the automatic first one). */
    public boolean isCapturingNeutral() { return neutralRequested || (autoNeutral && !cal.isNeutralSet()); }
    /** Frames gathered toward the neutral so far (out of {@link #NEUTRAL_FRAMES}). */
    public int neutralProgress() { return captureCount; }
    public boolean isCheckingDirection() { return check != null || checkRequested != null; }

    // ── the frame (tracker thread) ──

    /**
     * One frame. {@code raw} is the tracker's raw face (blendshapes + head degrees), or null when
     * this frame found no face. Returns the face signals (a new map).
     */
    @NonNull
    public Map<String, Float> process(@Nullable Map<String, Float> raw, double t) {
        return process(raw, null, t);
    }

    /**
     * One frame with the face's points too ({@code xyz}: x, y, z per point, y in width units; null if the
     * tracker gives none). The points are kept with the neutral and the watched ones put out.
     */
    @NonNull
    public Map<String, Float> process(@Nullable Map<String, Float> raw, @Nullable float[] xyz, double t) {
        float[] pts = raw != null && xyz != null ? FacePoints.normalize(xyz) : null;
        float[] gaps = FacePoints.eyeGaps(pts);
        if (gaps != null) {                    // the lids and the face's size, measured on the points (kept with the neutral)
            raw = new HashMap<>(raw);
            raw.put(FacePoints.GAP_LEFT, gaps[0]);
            raw.put(FacePoints.GAP_RIGHT, gaps[1]);
            float sp = FacePoints.spread(xyz);
            if (!Float.isNaN(sp)) raw.put(FaceCalibration.FACE_SPREAD, sp);
        }
        double dt = Double.isNaN(lastT) ? 0 : Math.max(0, Math.min(0.5, t - lastT));
        lastT = t;
        FaceCalibration c = cal;
        FaceTuning tun = tuning;

        Map<String, Float> target;
        if (raw != null) {
            found = true;
            calibrate(raw, pts, c);
            c = cal; // a neutral may have just landed
            if (autoNeutral && !c.isNeutralSet()) {
                // The very first half-second: wait at rest for the neutral instead of showing the
                // uncalibrated face and then jumping when the neutral lands; it eases in after.
                if (Double.isNaN(lostSince)) lostSince = t;
                Map<String, Float> wait = FaceSignals.rest();
                lastOut = wait;
                return new HashMap<>(wait);
            }
            if (!Double.isNaN(lostSince)) {             // the face is back: cross over from what shows
                crossFrom = new HashMap<>(lastOut);
                crossK = 0;
                lostSince = Double.NaN;
            }
            Map<String, Float> m = FaceMapper.map(raw, c, tun);
            putPoints(m, pts, c, tun);
            holdGazeWhileBlinking(m);
            steadyShape(m);
            lastTracked = m;
            presence = Math.min(1, presence + (dt > 0 ? dt / EASE_IN_S : 0));
            if (presence == 0 && dt == 0) presence = 1;   // the very first frame: no ramp from nothing
            target = blend(FaceSignals.rest(), m, (float) presence);
            if (crossFrom != null) {
                crossK = Math.min(1, crossK + dt / EASE_IN_S);
                target = blend(crossFrom, target, smooth((float) crossK));
                if (crossK >= 1) crossFrom = null;
            }
        } else {
            found = false;
            if (capture != null) capture.restart();
            captureCount = 0;
            if (Double.isNaN(lostSince)) {
                lostSince = t;
                crossFrom = null;
            }
            if (t - lostSince >= HOLD_S) presence = Math.max(0, presence - (dt > 0 ? dt / EASE_OUT_S : 0));
            target = blend(FaceSignals.rest(), lastTracked, smooth((float) presence));
            target.put(FaceSignals.VIS, presence > 0.5 ? lastTracked.get(FaceSignals.VIS) : 0f);
        }
        target.put(FaceSignals.FACE, (float) presence);
        lastOut = target;
        return new HashMap<>(target);
    }

    /**
     * After the idle life has run: while the face is not driving, its blinks and eye darts show
     * (scaled by how far the face has let go), so a lost face never leaves a dead stare.
     */
    public void mergeLife(@NonNull Map<String, Float> out, @NonNull Map<String, Float> life) {
        Float p = out.get(FaceSignals.FACE);
        float w = 1f - (p == null ? 0f : p);
        if (w <= 0f) return;
        maxInto(out, FaceSignals.BLINK_L, get(life, LifeSignals.PARAM_BLINK_L) * w);
        maxInto(out, FaceSignals.BLINK_R, get(life, LifeSignals.PARAM_BLINK_R) * w);
        out.put(FaceSignals.LOOK_X, FaceMapper.clamp(get(out, FaceSignals.LOOK_X)
                + get(life, LifeSignals.PARAM_EYE_X) * w * 0.5f, -1f, 1f));
        out.put(FaceSignals.LOOK_Y, FaceMapper.clamp(get(out, FaceSignals.LOOK_Y)
                + get(life, LifeSignals.PARAM_EYE_Y) * w * 0.5f, -1f, 1f));
    }

    // ── pieces ──

    /** Face pins: each watched point's movement from neutral, in the head's own frame, screen-sided. */
    private void putPoints(@NonNull Map<String, Float> m, @Nullable float[] pts, @NonNull FaceCalibration c,
                           @NonNull FaceTuning tun) {
        int[] w = watched;
        if (w.length == 0) return;
        int side = (tun.mirror ? -1 : 1) * c.signLean();
        float[] off = FacePoints.offsets(c.restPoints(), pts, w, side);
        for (int k = 0; k < w.length; k++) {
            m.put(FaceSignals.pointX(w[k]), off != null ? off[k * 2] : 0f);
            m.put(FaceSignals.pointY(w[k]), off != null ? off[k * 2 + 1] : 0f);
        }
    }

    private void calibrate(@NonNull Map<String, Float> raw, @Nullable float[] pts, @NonNull FaceCalibration c) {
        String ask = checkRequested;
        if (ask != null) {
            checkRequested = null;
            check = new FaceCalibration.DirectionCheck(ask, c);
        }
        if (check != null && check.add(raw)) {
            cal = check.apply();
            lastCheck = check;
            check = null;
            return;
        }
        if (check != null) return; // no neutral while someone is turning their head on purpose
        // a neutral saved before the lids were measured is taken again by itself (its direction answers kept)
        boolean noLids = (raw.containsKey(FacePoints.GAP_LEFT) && c.rest(FacePoints.GAP_LEFT) <= 0f)
                || (raw.containsKey(FaceCalibration.FACE_SPREAD) && c.rest(FaceCalibration.FACE_SPREAD) <= 0f);
        boolean want = neutralRequested || (autoNeutral && (!c.isNeutralSet() || noLids));
        if (!want) {
            capture = null;
            captureCount = 0;
            return;
        }
        if (capture == null) capture = new FaceCalibration.Capture(c, NEUTRAL_FRAMES);
        boolean done = capture.add(raw, pts);
        captureCount = capture.count();
        if (done) {
            cal = capture.result();
            capture = null;
            captureCount = 0;
            neutralRequested = false;
        }
    }

    /** Closed eyes have no gaze: keep where they were looking until they open. */
    private void holdGazeWhileBlinking(@NonNull Map<String, Float> m) {
        float shut = Math.max(get(m, FaceSignals.BLINK_L), get(m, FaceSignals.BLINK_R));
        if (shut > 0.55f) {
            m.put(FaceSignals.LOOK_X, gazeX);
            m.put(FaceSignals.LOOK_Y, gazeY);
        } else {
            gazeX = get(m, FaceSignals.LOOK_X);
            gazeY = get(m, FaceSignals.LOOK_Y);
        }
    }

    /** A sheet mouth changes shape only when the new shape is there two frames running. */
    private void steadyShape(@NonNull Map<String, Float> m) {
        float v = get(m, FaceSignals.VIS);
        if (v == vis) {
            visPendingFrames = 0;
        } else if (v == visPending) {
            if (++visPendingFrames >= 2) {
                vis = v;
                visPendingFrames = 0;
            }
        } else {
            visPending = v;
            visPendingFrames = 1;
        }
        m.put(FaceSignals.VIS, vis);
    }

    @NonNull
    private static Map<String, Float> blend(@NonNull Map<String, Float> a, @NonNull Map<String, Float> b, float k) {
        Map<String, Float> o = new HashMap<>();
        for (String n : FaceSignals.ALL) put(o, n, a, b, k);
        for (String n : a.keySet()) if (FaceSignals.isPoint(n)) put(o, n, a, b, k);  // face pins rest at 0
        for (String n : b.keySet()) if (FaceSignals.isPoint(n)) put(o, n, a, b, k);
        return o;
    }

    private static void put(Map<String, Float> o, String n, Map<String, Float> a, Map<String, Float> b, float k) {
        float av = get(a, n), bv = get(b, n);
        o.put(n, FaceSignals.isStep(n) ? (k >= 0.5f ? bv : av) : av + (bv - av) * k);
    }

    private static float smooth(float k) {
        return k * k * (3f - 2f * k);
    }

    private static void maxInto(Map<String, Float> m, String k, float v) {
        m.put(k, Math.max(get(m, k), v));
    }

    private static float get(@NonNull Map<String, Float> m, @NonNull String k) {
        Float v = m.get(k);
        return v == null || v.isNaN() ? 0f : v;
    }
}
