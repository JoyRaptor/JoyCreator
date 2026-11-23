package com.fadcam.ui.faditor.avatar.face;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.ui.faditor.avatar.MediaPipeTrackingSource;
import com.fadcam.ui.faditor.avatar.TrackingDriverBus;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * FACE-1: THE seam. Everything a screen needs to let a face drive a character, in one object:
 * the front camera and the face model (one owner at a time, through {@link TrackingDriverBus}),
 * the neutral and the direction check, the person's settings (saved between visits), the smooth
 * fill-in for the screen, a small private preview and plain numbers for how well it is going.
 *
 * <p>How a room uses it (see the FACE-1 card for the full contract):</p>
 * <pre>
 *   onCreate:   face = new FaceCapture(this);
 *   onResume:   if (face.hasCameraPermission()) face.start(); else ask for CAMERA, then start()
 *   each frame: Map&lt;String,Float&gt; s = face.sample(System.nanoTime());   // FaceSignals names
 *   onPause:    face.stop();                                              // the camera is let go
 *   buttons:    face.setNeutral(); face.checkDirection(FaceCalibration.DirectionCheck.YAW); ...
 *   recording:  FacePass pass = new FacePass(take, lanes); pass.add(ms, s); take = pass.finish();
 * </pre>
 * Main thread for everything except {@link #sample}, which any one drawing thread may call.
 */
public final class FaceCapture {

    /** What the room should say, in its own words. */
    public enum State { OFF, NEEDS_PERMISSION, STARTING, LOOKING, TRACKING }

    /** A snapshot of how it is going, for a status line or a bench. */
    public static final class Status {
        public State state = State.OFF;
        /** 0..1, how much the live face drives right now (eases down when the face is lost). */
        public float presence;
        public boolean capturingNeutral, checkingDirection, onGraphicsChip;
        /** Frames of the neutral gathered so far, out of {@link #neutralNeeded}. */
        public int neutralProgress, neutralNeeded;
        /** Faces a second, the model's time per face and camera-to-character delay (ms; NaN unknown). */
        public float rate, modelMs = Float.NaN, delayMs = Float.NaN;
    }

    private static final String PREFS = "face_capture", KEY_CAL = "calibration", KEY_TUNING = "tuning",
            KEY_GPU = "graphicsChip";

    @NonNull private final Context app;
    @NonNull private final TrackingDriverBus bus = new TrackingDriverBus();
    @NonNull private final FaceFollower follower = new FaceFollower();
    @NonNull private final SharedPreferences prefs;
    @Nullable private MediaPipeTrackingSource source;
    @Nullable private MediaPipeTrackingSource.PreviewSink previewSink;
    private boolean previewPicture = true;
    private boolean useGpu;

    public FaceCapture(@NonNull Context context) {
        this.app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        FaceStage st = bus.face();
        st.setCalibration(FaceCalibration.fromJson(readJson(KEY_CAL)));
        st.setTuning(FaceTuning.fromJson(readJson(KEY_TUNING)));
        useGpu = prefs.getBoolean(KEY_GPU, false);
    }

    public boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Opens the front camera and starts reading the face. Needs the CAMERA permission first. */
    @MainThread
    public void start() {
        if (source != null || !hasCameraPermission()) return;
        MediaPipeTrackingSource s = new MediaPipeTrackingSource(app, bus::requestReset);
        s.setUseGpu(useGpu);
        s.setPreviewSink(previewSink, previewPicture);
        source = s;
        follower.reset();
        bus.start(s, 20261122L);
    }

    /** Lets the camera go (call from onPause: the camera is never held by a room that is not shown). */
    @MainThread
    public void stop() {
        if (source == null) return;
        bus.stop();
        source = null;
        save();
    }

    public boolean isRunning() { return source != null; }

    /**
     * The face to draw now ({@link FaceSignals} names, plus the idle life's {@code life_*}),
     * smoothly filled in between the tracker's frames. The rest face before the first frame.
     */
    @NonNull
    public Map<String, Float> sample(long nowNanos) {
        Map<String, Float> latest = bus.latest();
        long at = bus.latestNanos();
        if (latest == null || at == Long.MIN_VALUE) return FaceSignals.rest();
        return follower.sample(latest, at, nowNanos);
    }

    /** The newest face exactly as the pipeline made it (no fill-in), or null before the first. */
    @Nullable
    public Map<String, Float> latest() { return bus.latest(); }

    // ── one tap and the guided check ──

    /**
     * Face pins: put out the movement of these face points (mesh indices; {@link FacePoints#NAMED} lists
     * the useful ones) as {@link FaceSignals#pointX} / {@link FaceSignals#pointY} in {@link #sample}:
     * each point's movement from the neutral face, in the head's own frame, in face-size units.
     */
    public void watchPoints(@NonNull int[] meshIndices) { bus.face().setWatchedPoints(meshIndices); }

    /** The neutral face's points (normalised; for drawing the face map), or null before a neutral. */
    @Nullable public float[] restPoints() { return bus.face().calibration().restPoints(); }

    /** One tap: the next half-second of a still face becomes neutral (kept for next time). */
    public void setNeutral() { bus.face().requestNeutral(); }

    /** Start one direction prompt; poll {@link #lastDirectionCheck()} for the answer. */
    public void checkDirection(@NonNull String what) { bus.face().startDirectionCheck(what); }

    @Nullable
    public FaceCalibration.DirectionCheck lastDirectionCheck() { return bus.face().lastDirectionCheck(); }

    /** Forget the neutral and the directions (the first still face becomes neutral again). */
    public void resetCalibration() {
        bus.face().setCalibration(FaceCalibration.none());
        save();
    }

    @NonNull public FaceCalibration calibration() { return bus.face().calibration(); }

    // ── the person's settings ──

    @NonNull public FaceTuning tuning() { return bus.face().tuning().copy(); }

    public void setTuning(@NonNull FaceTuning t) {
        bus.face().setTuning(t);
        save();
    }

    /** The model on the graphics chip (takes effect at the next {@link #start}). */
    public void setUseGpu(boolean gpu) {
        useGpu = gpu;
        prefs.edit().putBoolean(KEY_GPU, gpu).apply();
    }

    public boolean usesGpu() { return useGpu; }

    // ── preview ──

    /**
     * Show what the camera sees in a small preview (null to stop). {@code picture} false shows only
     * the outline of the face it found, never the picture. Nothing is ever stored or recorded.
     */
    public void setPreview(@Nullable MediaPipeTrackingSource.PreviewSink sink, boolean picture) {
        previewSink = sink;
        previewPicture = picture;
        MediaPipeTrackingSource s = source;
        if (s != null) s.setPreviewSink(sink, picture);
    }

    public void setPreviewPicture(boolean picture) {
        previewPicture = picture;
        MediaPipeTrackingSource s = source;
        if (s != null) s.setPreviewPicture(picture);
    }

    public boolean previewPicture() { return previewPicture; }

    // ── how it is going ──

    @NonNull
    public Status status() {
        Status st = new Status();
        FaceStage f = bus.face();
        MediaPipeTrackingSource s = source;
        Map<String, Float> latest = bus.latest();
        if (s == null) st.state = hasCameraPermission() ? State.OFF : State.NEEDS_PERMISSION;
        else if (latest == null) st.state = State.STARTING;
        else st.state = f.isFound() ? State.TRACKING : State.LOOKING;
        Float p = latest != null ? latest.get(FaceSignals.FACE) : null;
        st.presence = p == null ? 0f : p;
        st.capturingNeutral = s != null && f.isCapturingNeutral();
        st.neutralProgress = f.neutralProgress();
        st.neutralNeeded = FaceStage.NEUTRAL_FRAMES;
        st.checkingDirection = f.isCheckingDirection();
        st.rate = bus.rate();
        st.delayMs = bus.delayMs();
        st.modelMs = s != null ? s.inferenceMs() : Float.NaN;
        st.onGraphicsChip = s != null && s.isRunningOnGpu();
        return st;
    }

    /** The bus underneath, for hosts that already speak it (Studio's Record, the webcam bubble). */
    @NonNull public TrackingDriverBus bus() { return bus; }

    // ── saving (small hand-editable JSON in the app's own settings) ──

    private void save() {
        prefs.edit()
                .putString(KEY_CAL, bus.face().calibration().toJson().toString())
                .putString(KEY_TUNING, bus.face().tuning().toJson().toString())
                .apply();
    }

    @Nullable
    private JsonObject readJson(@NonNull String key) {
        String s = prefs.getString(key, null);
        if (s == null) return null;
        try {
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
