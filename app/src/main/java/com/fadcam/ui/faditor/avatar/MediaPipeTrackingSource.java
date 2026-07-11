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
                Toast.makeText(appContext,
                        "Face model failed to load — avatar won't track",
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
    }

    private boolean buildLandmarker() {
        try {
            BaseOptions base = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(Delegate.CPU) // Note 9 baseline — no GPU delegate
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
                        "Camera unavailable for face tracking", Toast.LENGTH_SHORT).show();
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

            ImageProcessingOptions ipo = ImageProcessingOptions.builder()
                    .setRotationDegrees(image.getImageInfo().getRotationDegrees())
                    .build();
            lm.detectAsync(mp, ipo, stamp);
        } finally {
            image.close();
        }
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

        List<float[]> matrices = result.facialTransformationMatrixes().orElse(null);
        // Loss: no face → emit NOTHING (bus freezes, life breathes). Track the
        // gap so a long loss asks for a smoothing SNAP on re-acquire.
        if (result.faceLandmarks().isEmpty() || matrices == null || matrices.isEmpty()) {
            return;
        }

        double tSeconds = result.timestampMs() / 1000.0;
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

        l.onFrame(new TrackingFrame(tSeconds, params, Float.NaN));
    }

    /**
     * Euler angles from the 4x4 facial-transformation matrix (row-major
     * float[16], OpenCV Tait-Bryan extraction), normalized to the slider domain.
     * Sign convention targets right/up positive to match the studio yaw/pitch
     * sliders; the front-camera mirror axis is the one likely device-tuning knob
     * (flip {@link #MIRROR_YAW} if a right head-turn reads negative on-device).
     */
    private void putHeadPose(@NonNull Map<String, Float> params, @NonNull float[] m) {
        if (m.length < 16) return;
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
        params.put("yaw", clamp1(yawDeg / YAW_DEG));
        params.put("pitch", clamp1(pitchDeg / PITCH_DEG));
        params.put("roll", clamp1(rollDeg / ROLL_DEG));
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
