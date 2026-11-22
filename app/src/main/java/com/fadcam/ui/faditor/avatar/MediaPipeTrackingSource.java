package com.fadcam.ui.faditor.avatar;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Size;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

/**
 * A2 (PLAN_AVATAR_STUDIO, spec D4): the REAL face tracker — the MediaPipe
 * FaceLandmarker source that drops in behind {@link TrackingSource} with no
 * other code aware, exactly where {@link SyntheticTrackingSource} sat. A
 * CameraX 320x240 FRONT feed pumps frames into FaceLandmarker (LIVE_STREAM,
 * CPU delegate — the Note 9 baseline) on its own executor; each result becomes
 * ONE {@link TrackingFrame} of PLAIN DRIVER PARAMS the bus already knows how to
 * smooth and republish.
 *
 * <p>WHY a self-owned {@link LifecycleRegistry}: CameraX binds to a
 * {@code LifecycleOwner}, but a mountable source is NOT an Activity — coupling
 * the camera to the studio's lifecycle would violate the mount/unmount contract
 * (the camera single-owner gotcha the bus enforces). So this source IS its own
 * owner: {@link #start} resumes it + binds, {@link #stop} destroys it + unbinds,
 * idempotently — the same shape as the synthetic source, camera machinery and
 * all confined here.</p>
 *
 * <p>Param mapping (spec D4): head pose comes from the FaceLandmarker
 * <em>facialTransformationMatrix</em> (not landmark geometry) — Euler angles
 * extracted from the 4x4, then {@code yaw = clamp(deg/45)},
 * {@code pitch = clamp(deg/30)}, {@code roll = clamp(deg/45)} with right/up
 * positive to match the studio sliders. Blendshapes pass through by their
 * MediaPipe category names (0..1), except {@code eyeBlinkLeft→blinkL} and
 * {@code eyeBlinkRight→blinkR} to match the rig's declared driver names.
 * {@code audioDb} is NaN — the mic stays with the A3 amplitude-viseme system,
 * so the life package idles correctly on quiet.</p>
 *
 * <p>Tracking loss = emit NOTHING (no frames, no calls): the bus's last
 * snapshot freezes and LifeSignals keeps breathing. On re-acquire after a gap
 * &gt; ~1s, {@link #onReacquireReset} fires (wired to {@code bus.requestReset})
 * so smoothing SNAPs to the new truth instead of gliding a full second of
 * stale-to-fresh interpolation. Thermal governor (spec §MINED): at
 * {@code THERMAL_STATUS_SEVERE}+ the analyzer throttles to ~10fps so the tracker
 * never becomes the reason the device throttles harder.</p>
 */
public final class MediaPipeTrackingSource implements TrackingSource, LifecycleOwner {

    /** Asset path for the FaceLandmarker model bundle (float16 v1). */
    public static final String MODEL_ASSET = "mediapipe/face_landmarker.task";

    /** Front feed size — small on purpose (Note 9 CPU): landmarks stay stable. */
    private static final int CAP_W = 320, CAP_H = 240;

    /** Slider-matched normalizers (spec D4): full deflection at these degrees. */
    private static final float YAW_DEG = 45f, PITCH_DEG = 30f, ROLL_DEG = 45f;

    /** Baseline analysis cap; thermal severe drops to {@link #FPS_THERMAL}. */
    private static final int FPS_NORMAL = 15, FPS_THERMAL = 10;

    /** Re-acquire snap threshold: a loss longer than this asks the bus to reset. */
    private static final double REACQUIRE_GAP_S = 1.0;

    @NonNull private final Context appContext;
    /** Fired (main thread) on re-acquire after a &gt;1s loss — wired to bus.requestReset. */
    @Nullable private final Runnable onReacquireReset;

    // ── FACE-1: the private preview, timing and the graphics-chip choice ──

    /** What the small preview shows: the picture (upright, mirrored like a mirror) and the outline. */
    public interface PreviewSink {
        /**
         * Analysis thread. {@code picture} is null when the picture is turned off (outline only);
         * {@code outline} is x,y pairs in 0..1 of the mirrored picture, NaN pairs between strokes,
         * or null when no face is found.
         */
        void onPreview(@Nullable Bitmap picture, @Nullable float[] outline, boolean found);
    }

    @Nullable private volatile PreviewSink previewSink;
    private volatile boolean previewPicture = true;
    private volatile boolean useGpu = false;
    /** Analysis-thread ring: MediaPipe stamp -> {stamp, analysis start, camera capture (nanoTime), rotation}. */
    private final long[][] stampRing = new long[8][4];
    /** The camera's turn of the last frame (the points come back in the camera's own sideways frame). */
    private volatile int lastRotation = 0;
    private int stampAt = 0, previewTick = 0;
    @Nullable private volatile float[] lastOutline;
    /** Model time per frame, ms (smoothed), and which chip ran it. */
    private volatile float inferenceMs = Float.NaN;
    private volatile boolean runningOnGpu = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);

    /** Analysis + MediaPipe run here; the render thread pulls from the bus. */
    @Nullable private ExecutorService analysisExecutor;
    @Nullable private FaceLandmarker landmarker;
    @Nullable private ProcessCameraProvider cameraProvider;
    @Nullable private FrameListener listener;

    /** Reused frame buffer (avoids a per-frame allocation on the Note 9). */
    @Nullable private Bitmap frameBitmap;

    private volatile int targetFps = FPS_NORMAL;
    private long lastAnalyzedUptimeMs = 0;
    private long baseTimeMs = 0;
    private long lastStamp = 0;             // MediaPipe LIVE_STREAM monotonicity guard
    private double lastEmitSeconds = Double.NaN;

    @Nullable private PowerManager.OnThermalStatusChangedListener thermalListener;

    public MediaPipeTrackingSource(@NonNull Context context, @Nullable Runnable onReacquireReset) {
        this.appContext = context.getApplicationContext();
        this.onReacquireReset = onReacquireReset;
    }

    /** True when the model bundle is present — the activity gates the source swap on this. */
    public static boolean isModelPresent(@NonNull Context context) {
        try (InputStream in = context.getAssets().open(MODEL_ASSET)) {
            return in.read() != -1;
        } catch (IOException e) {
            return false;
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    /** FACE-1: show what the camera sees (null = no preview). {@code picture} false = outline only. */
    public void setPreviewSink(@Nullable PreviewSink sink, boolean picture) {
        previewSink = sink;
        previewPicture = picture;
    }

    public void setPreviewPicture(boolean picture) {
        previewPicture = picture;
    }

    /** FACE-1: run the model on the graphics chip (falls back to the processor if it will not start). */
    public void setUseGpu(boolean gpu) {
        useGpu = gpu;
    }

    public boolean isRunningOnGpu() { return runningOnGpu; }

    /** FACE-1: the model's own time per frame in ms (smoothed); NaN before the first. */
    public float inferenceMs() { return inferenceMs; }

    // ── Mount / unmount (mirrors SyntheticTrackingSource; camera-owning) ──────

    @Override
    public void start(@NonNull FrameListener frameListener) {
        // All camera + lifecycle mutation on the main thread (CameraX contract).
        main.post(() -> {
            stopInternal();
            this.listener = frameListener;
            this.analysisExecutor = Executors.newSingleThreadExecutor();
            this.baseTimeMs = SystemClock.elapsedRealtime();
            this.lastStamp = 0;
            this.lastEmitSeconds = Double.NaN;
            this.targetFps = FPS_NORMAL;

            if (!buildLandmarker()) {
                // Host-neutral wording: the studio falls back to synthetic, the
                // recorder bubble just shows a non-tracking (neutral) puppet.
                // FACE-1: plain words, never a technical error.
                Toast.makeText(appContext,
                        "The face camera could not start, so the character will stay still",
                        Toast.LENGTH_LONG).show();
                stopInternal();
                return;
            }
            registerThermal();
            lifecycle.setCurrentState(Lifecycle.State.RESUMED);
            bindCamera();
        });
    }

    @Override
    public void stop() {
        main.post(this::stopInternal);
    }

    private void stopInternal() {
        unregisterThermal();
        // Lifecycle DESTROYED unbinds every CameraX use-case bound to us.
        if (lifecycle.getCurrentState() != Lifecycle.State.INITIALIZED
                && lifecycle.getCurrentState() != Lifecycle.State.DESTROYED) {
            lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        if (landmarker != null) {
            landmarker.close();
            landmarker = null;
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            analysisExecutor = null;
        }
        if (frameBitmap != null) {
            frameBitmap.recycle();
            frameBitmap = null;
        }
        listener = null;
        lastOutline = null;
        PreviewSink sink = previewSink;
        if (sink != null) sink.onPreview(null, null, false); // the preview goes dark with the camera
    }

    private boolean buildLandmarker() {
        // FACE-1: the graphics chip when asked for, the processor if it will not start there.
        if (useGpu && buildLandmarker(Delegate.GPU)) {
            runningOnGpu = true;
            return true;
        }
        runningOnGpu = false;
        return buildLandmarker(Delegate.CPU);
    }

    private boolean buildLandmarker(@NonNull Delegate delegate) {
        try {
            BaseOptions base = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(delegate) // CPU = the Note 9 baseline; GPU only when asked
                    .build();
            FaceLandmarker.FaceLandmarkerOptions options =
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(base)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumFaces(1)
                            .setOutputFaceBlendshapes(true)
                            .setOutputFacialTransformationMatrixes(true)
                            .setResultListener((result, input) -> onResult(result))
                            .setErrorListener(e -> { /* transient — next frame retries */ })
                            .build();
            landmarker = FaceLandmarker.createFromOptions(appContext, options);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(appContext);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                if (listener == null) return; // stopped while the provider warmed up
                cameraProvider.unbindAll();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(CAP_W, CAP_H))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                ExecutorService exec = analysisExecutor;
                if (exec == null) return;
                analysis.setAnalyzer(exec, this::analyze);
                cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
            } catch (Exception e) {
                Toast.makeText(appContext,
                        "The front camera isn't free right now", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(appContext));
    }

    // ── Per-frame analysis (analysis executor) ────────────────────────────────

    private void analyze(@NonNull ImageProxy image) {
        try {
            // Thermal / baseline throttle: skip frames to hold the target rate so
            // the tracker never drives the device hotter (spec §MINED governor).
            long now = SystemClock.uptimeMillis();
            long minGap = 1000L / Math.max(1, targetFps);
            if (now - lastAnalyzedUptimeMs < minGap) return;
            lastAnalyzedUptimeMs = now;

            FaceLandmarker lm = landmarker;
            if (lm == null) return;

            Bitmap bmp = toBitmap(image);
            if (bmp == null) return;
            MPImage mp = new BitmapImageBuilder(bmp).build();

            // LIVE_STREAM requires strictly increasing timestamps; guard against
            // the rare equal/rewound clock read.
            long stamp = SystemClock.elapsedRealtime() - baseTimeMs;
            if (stamp <= lastStamp) stamp = lastStamp + 1;
            lastStamp = stamp;

            int rotation = image.getImageInfo().getRotationDegrees();
            ImageProcessingOptions ipo = ImageProcessingOptions.builder()
                    .setRotationDegrees(rotation)
                    .build();
            // FACE-1: remember when this picture was taken and when its analysis began, by stamp.
            long startNanos = System.nanoTime();
            long[] slot = stampRing[stampAt++ % stampRing.length];
            slot[0] = stamp;
            slot[1] = startNanos;
            slot[2] = captureNanos(image.getImageInfo().getTimestamp(), startNanos);
            slot[3] = rotation;
            lastRotation = rotation;
            publishPreview(bmp, rotation);
            lm.detectAsync(mp, ipo, stamp);
        } finally {
            image.close();
        }
    }

    /**
     * FACE-1: the camera's own timestamp in the {@link System#nanoTime} clock. Cameras stamp either
     * since boot (elapsedRealtime) or with the monotonic clock; whichever is plausibly close wins,
     * else the analysis start stands in (the delay then reads a little short, never invented).
     */
    private static long captureNanos(long sensorNanos, long nowNanos) {
        long sinceBoot = SystemClock.elapsedRealtimeNanos();
        long ageBoot = sinceBoot - sensorNanos;
        if (ageBoot >= 0 && ageBoot < 1_000_000_000L) return nowNanos - ageBoot;
        long ageMono = nowNanos - sensorNanos;
        if (ageMono >= 0 && ageMono < 1_000_000_000L) return sensorNanos;
        return nowNanos;
    }

    /**
     * FACE-1: every other analysed frame, a small upright copy of the picture, mirrored the way a
     * mirror shows you, for the preview. Never stored, never recorded; nothing at all when the
     * preview is off or set to outline only.
     */
    private void publishPreview(@NonNull Bitmap frame, int rotation) {
        PreviewSink sink = previewSink;
        if (sink == null || (previewTick++ & 1) != 0) return;
        Bitmap picture = null;
        if (previewPicture) {
            try {
                float scale = 128f / Math.max(frame.getWidth(), frame.getHeight());
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotation);
                m.postScale(-scale, scale); // mirrored: the preview behaves like a mirror
                picture = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), m, true);
            } catch (RuntimeException e) {
                picture = null;
            }
        }
        float[] o = lastOutline;
        sink.onPreview(picture, o, o != null);
    }

    /** RGBA_8888 ImageProxy → upright-agnostic Bitmap (rotation handled by MP). */
    @Nullable
    private Bitmap toBitmap(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        buffer.rewind();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int bufW = image.getWidth() + (pixelStride > 0 ? rowPadding / pixelStride : 0);
        if (frameBitmap == null || frameBitmap.getWidth() != bufW
                || frameBitmap.getHeight() != image.getHeight()) {
            if (frameBitmap != null) frameBitmap.recycle();
            frameBitmap = Bitmap.createBitmap(bufW, image.getHeight(), Bitmap.Config.ARGB_8888);
        }
        frameBitmap.copyPixelsFromBuffer(buffer);
        return frameBitmap;
    }

    // ── Result → TrackingFrame (MediaPipe callback thread) ────────────────────

    private void onResult(@NonNull FaceLandmarkerResult result) {
        FrameListener l = listener;
        if (l == null) return;

        long[] timing = timingFor(result.timestampMs());
        double tSeconds = result.timestampMs() / 1000.0;
        List<float[]> matrices = result.facialTransformationMatrixes().orElse(null);
        // FACE-1: loss is now a frame of its own (FACE_LOST): the face stage holds the last face a
        // moment, then eases it to rest while the idle life takes over, instead of freezing. The
        // gap is still tracked so a long loss asks for a smoothing SNAP on re-acquire.
        if (result.faceLandmarks().isEmpty() || matrices == null || matrices.isEmpty()) {
            lastOutline = null;
            l.onFrame(new TrackingFrame(tSeconds, new java.util.HashMap<>(), Float.NaN,
                    TrackingFrame.NO_VISEME, TrackingFrame.FACE_LOST, timing != null ? timing[2] : -1L));
            return;
        }

        boolean longGap = !Double.isNaN(lastEmitSeconds)
                && (tSeconds - lastEmitSeconds) > REACQUIRE_GAP_S;
        // First frame after mount (NaN) also snaps — no stale state to glide from,
        // but the reset is harmless and keeps the "fresh acquire = snap" rule.
        // Run SYNCHRONOUSLY (not posted): the flag must be set before this frame
        // is handed to the bus so the SNAP applies to THIS frame, not the next
        // one. requestReset only flips a volatile, so it is safe off the main
        // thread.
        if ((longGap || Double.isNaN(lastEmitSeconds)) && onReacquireReset != null) {
            onReacquireReset.run();
        }
        lastEmitSeconds = tSeconds;

        int rotation = timing != null ? (int) timing[3] : lastRotation;
        Map<String, Float> raw = resultToRaw(result, rotation);
        if (raw == null) return; // raced away between the guards — treat as loss
        lastOutline = outline(result, rotation);

        l.onFrame(new TrackingFrame(tSeconds, raw, Float.NaN, TrackingFrame.NO_VISEME,
                TrackingFrame.FACE_FOUND, timing != null ? timing[2] : -1L));
    }

    /** FACE-1: the analysis start and capture time recorded for a stamp; updates the model time. */
    @Nullable
    private long[] timingFor(long stamp) {
        for (long[] s : stampRing) {
            if (s[0] == stamp && s[1] != 0) {
                float ms = (System.nanoTime() - s[1]) / 1e6f;
                float prev = inferenceMs;
                inferenceMs = Float.isNaN(prev) ? ms : prev + (ms - prev) * 0.1f;
                return s;
            }
        }
        return null;
    }

    /**
     * FACE-1: the RAW face for the face stage: every expression value under MediaPipe's own name,
     * the head's three angles in degrees (before any neutral or sign), and where the face sits in
     * the upright picture (centre and width, 0..1) for lean. Null without a face. {@code rotation} is
     * the camera's turn: the points come back in its sideways frame and are turned upright here.
     */
    @Nullable
    static Map<String, Float> resultToRaw(@NonNull FaceLandmarkerResult result, int rotation) {
        List<float[]> matrices = result.facialTransformationMatrixes().orElse(null);
        if (result.faceLandmarks().isEmpty() || matrices == null || matrices.isEmpty()) return null;
        Map<String, Float> raw = new java.util.HashMap<>();
        float[] deg = headDegrees(matrices.get(0));
        if (deg != null) {
            raw.put(com.fadcam.ui.faditor.avatar.face.FaceCalibration.HEAD_YAW, deg[0]);
            // the old params read up as positive; the face stage reads down as positive (as the pilot)
            raw.put(com.fadcam.ui.faditor.avatar.face.FaceCalibration.HEAD_PITCH, -deg[1]);
            raw.put(com.fadcam.ui.faditor.avatar.face.FaceCalibration.HEAD_ROLL, deg[2]);
        }
        List<List<Category>> blends = result.faceBlendshapes().orElse(null);
        if (blends != null && !blends.isEmpty()) {
            for (Category c : blends.get(0)) {
                String name = c.categoryName();
                if (name != null && !name.isEmpty() && !"_neutral".equals(name)) raw.put(name, c.score());
            }
        }
        List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> pts =
                result.faceLandmarks().get(0);
        float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
        for (int i : FACE_OVAL) {
            if (i >= pts.size()) continue;
            float[] u = com.fadcam.ui.faditor.avatar.face.FaceFrame.upright(pts.get(i).x(), pts.get(i).y(), rotation);
            float x = u[0], y = u[1];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        if (maxX > minX) {
            raw.put(com.fadcam.ui.faditor.avatar.face.FaceCalibration.FACE_X, (minX + maxX) / 2f);
            raw.put(com.fadcam.ui.faditor.avatar.face.FaceCalibration.FACE_Y, (minY + maxY) / 2f);
            raw.put(com.fadcam.ui.faditor.avatar.face.FaceCalibration.FACE_SIZE, maxX - minX);
        }
        return raw;
    }

    // MediaPipe face mesh outlines (the canonical index loops): oval, both eyes, both brows, lips.
    static final int[] FACE_OVAL = {10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365,
            379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103,
            67, 109, 10};
    private static final int[][] OUTLINE = {
            FACE_OVAL,
            {33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246, 33},
            {263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466, 263},
            {46, 53, 52, 65, 55, 70, 63, 105, 66, 107},
            {276, 283, 282, 295, 285, 300, 293, 334, 296, 336},
            {61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185, 61},
            {78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191, 78},
    };

    /** FACE-1: the outline strokes in the mirrored preview's 0..1 space, NaN pairs between strokes. */
    @Nullable
    private static float[] outline(@NonNull FaceLandmarkerResult result, int rotation) {
        if (result.faceLandmarks().isEmpty()) return null;
        List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> pts =
                result.faceLandmarks().get(0);
        int n = 0;
        for (int[] s : OUTLINE) n += s.length + 1;
        float[] out = new float[n * 2];
        int k = 0;
        for (int[] s : OUTLINE) {
            for (int i : s) {
                boolean ok = i < pts.size();
                float[] u = ok ? com.fadcam.ui.faditor.avatar.face.FaceFrame.upright(pts.get(i).x(), pts.get(i).y(), rotation)
                        : null;
                out[k++] = ok ? 1f - u[0] : Float.NaN; // upright, then mirrored like the picture
                out[k++] = ok ? u[1] : Float.NaN;
            }
            out[k++] = Float.NaN;
            out[k++] = Float.NaN;
        }
        return out;
    }

    /**
     * Full FaceLandmarker result → plain driver params (head pose from the
     * transformation matrix + blendshape pass-through with the rig's
     * blinkL/blinkR renames). Null when the result carries no face/matrix.
     * STATIC + shared (extract, don't copy): the live stream above and the
     * point-at-video VIDEO-mode sweep both map frames through here, so the
     * two paths can never disagree on param names or axis conventions.
     */
    @Nullable
    static Map<String, Float> resultToParams(@NonNull FaceLandmarkerResult result) {
        List<float[]> matrices = result.facialTransformationMatrixes().orElse(null);
        if (result.faceLandmarks().isEmpty() || matrices == null || matrices.isEmpty()) {
            return null;
        }
        Map<String, Float> params = new java.util.HashMap<>();
        putHeadPose(params, matrices.get(0));

        List<List<Category>> blends = result.faceBlendshapes().orElse(null);
        if (blends != null && !blends.isEmpty()) {
            for (Category c : blends.get(0)) {
                String name = c.categoryName();
                if (name == null || name.isEmpty()) continue;
                switch (name) {
                    case "eyeBlinkLeft":  params.put("blinkL", c.score()); break;
                    case "eyeBlinkRight": params.put("blinkR", c.score()); break;
                    default:              params.put(name, c.score()); break;
                }
            }
        }
        return params;
    }

    /**
     * Euler angles from the 4x4 facial-transformation matrix (row-major
     * float[16], OpenCV Tait-Bryan extraction), normalized to the slider domain.
     * Sign convention targets right/up positive to match the studio yaw/pitch
     * sliders; the front-camera mirror axis is the one likely device-tuning knob
     * (flip {@link #MIRROR_YAW} if a right head-turn reads negative on-device).
     *
     * <p>STATIC + shared on purpose (bake doctrine: extract, don't copy): the
     * point-at-video sweep (VIDEO-mode FaceLandmarker over a clip → an
     * AvatarParamTrack) maps its per-frame matrices through this exact method,
     * so live tracking and offline sweeps can never drift apart on axis
     * conventions — one tuning knob serves both.</p>
     */
    static void putHeadPose(@NonNull Map<String, Float> params, @NonNull float[] m) {
        float[] d = headDegrees(m);
        if (d == null) return;
        params.put("yaw", clamp1(d[0] / YAW_DEG));
        params.put("pitch", clamp1(d[1] / PITCH_DEG));
        params.put("roll", clamp1(d[2] / ROLL_DEG));
    }

    /**
     * FACE-1: the head's {yaw, pitch, roll} in degrees from the matrix, with the old signs (right
     * and up positive as the old sliders read them; neither ever checked against a real face — the
     * face stage's direction check settles each phone). Shared by the old normalised params above.
     */
    @Nullable
    static float[] headDegrees(@NonNull float[] m) {
        if (m.length < 16) return null;
        // Row-major 3x3 rotation block: rIJ = m[I*4 + J].
        float r00 = m[0], r10 = m[4], r20 = m[8];
        float r21 = m[9], r22 = m[10];
        double sy = Math.sqrt(r00 * r00 + r10 * r10);
        double pitchRad, yawRad, rollRad;
        if (sy > 1e-6) {
            pitchRad = Math.atan2(r21, r22);
            yawRad = Math.atan2(-r20, sy);
            rollRad = Math.atan2(r10, r00);
        } else { // gimbal-lock fallback
            pitchRad = Math.atan2(-m[6], m[5]);
            yawRad = Math.atan2(-r20, sy);
            rollRad = 0;
        }
        float yawDeg = (float) Math.toDegrees(yawRad) * MIRROR_YAW;
        float pitchDeg = (float) Math.toDegrees(pitchRad) * SIGN_PITCH;
        float rollDeg = (float) Math.toDegrees(rollRad) * MIRROR_YAW;
        return new float[]{yawDeg, pitchDeg, rollDeg};
    }

    /** Front-camera mirror: head-right must read positive yaw (slider convention). */
    private static final float MIRROR_YAW = 1f;
    /** Up must read positive pitch; matrix pitch is nose-down positive → invert. */
    private static final float SIGN_PITCH = -1f;

    private static float clamp1(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    // ── Thermal governor ──────────────────────────────────────────────────────

    private void registerThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        thermalListener = status -> targetFps =
                status >= PowerManager.THERMAL_STATUS_SEVERE ? FPS_THERMAL : FPS_NORMAL;
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(appContext), thermalListener);
    }

    private void unregisterThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || thermalListener == null) return;
        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (pm != null) pm.removeThermalStatusListener(thermalListener);
        thermalListener = null;
    }
}
