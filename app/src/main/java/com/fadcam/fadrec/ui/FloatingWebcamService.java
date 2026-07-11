package com.fadcam.fadrec.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.FLog;
import com.fadcam.R;

import java.util.Collections;

/**
 * Floating webcam overlay for FadRec screen recording.
 * Shows a draggable, resizable front/back camera preview window that stays
 * on top of other apps so it gets captured by the screen recording.
 *
 * Window interactions:
 *  - Drag anywhere on the preview to move.
 *  - Drag the bottom-right handle to resize (aspect ratio locked).
 *  - Tap once to show controls (switch camera / minimize / close).
 *  - Minimize collapses to a small bubble; tap bubble to restore.
 */
public class FloatingWebcamService extends Service {
    private static final String TAG = "FloatingWebcamService";
    private static final String CHANNEL_ID = "webcam_overlay_channel";
    private static final int NOTIFICATION_ID = 7341;

    public static final String ACTION_TOGGLE_MINIMIZE = "com.fadcam.fadrec.WEBCAM_TOGGLE_MINIMIZE";
    private static final String PREFS = "floating_webcam_prefs";

    /** True while the service is alive; lets the floating menu show overlay state. */
    public static volatile boolean isRunning = false;

    // Aspect ratio of the preview window (w:h). Portrait-ish card like phone selfie cams.
    private static final float ASPECT = 3f / 4f;
    private static final int MIN_WIDTH_DP = 90;
    private static final int MAX_WIDTH_DP = 360;

    private WindowManager windowManager;
    private View overlayView;
    private View webcamCard;
    private View webcamBubble;
    private View webcamControls;
    private View resizeHandle;
    private TextureView previewView;
    private WindowManager.LayoutParams layoutParams;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());
    private boolean useFrontCamera = true;
    private boolean isMinimized = false;
    private Size previewSize;
    private int sensorOrientation = 90;
    private String[] backCameraIds = new String[0];
    private int backCameraIndex = 0;
    // Manual orientation overrides (captured by the screen recording, so they correct a
    // sideways/flipped webcam by hand — and serve as a fallback to the auto-rotation logic).
    private int userRotation = 0;        // 0 / 90 / 180 / 270
    private boolean userMirrorH = false;
    private boolean userMirrorV = false;
    private static final long HANDLE_HIDE_DELAY_MS = 1500;
    private static final long CONTROLS_HIDE_DELAY_MS = 5000;
    // Very slow, subtle fade-out (4× the 160ms baseline) so the handle doesn't draw the eye as it leaves.
    private final Runnable hideHandleRunnable = () -> fadeView(resizeHandle, false, 640);
    private final Runnable hideControlsRunnable = () -> fadeView(webcamControls, false, 700);

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        startInForeground();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        enumerateBackCameras();
        cameraThread = new HandlerThread("WebcamCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        createOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE_MINIMIZE.equals(intent.getAction())) {
            setMinimized(!isMinimized);
        }
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.webcam_overlay_notification_title),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.webcam_overlay_notification_title))
                .setSmallIcon(R.drawable.ic_video_placeholder)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // -------------------- Overlay window --------------------

    private void createOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.floating_webcam, null);
        webcamCard = overlayView.findViewById(R.id.webcamCard);
        webcamBubble = overlayView.findViewById(R.id.webcamBubble);
        webcamControls = overlayView.findViewById(R.id.webcamControls);
        resizeHandle = overlayView.findViewById(R.id.webcamResizeHandle);
        previewView = overlayView.findViewById(R.id.webcamPreview);

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int width = prefs.getInt("width", dp(160));
        int height = prefs.getInt("height", (int) (width / ASPECT));
        useFrontCamera = prefs.getBoolean("front", true);
        userRotation = prefs.getInt("userRotation", 0);
        userMirrorH = prefs.getBoolean("mirrorH", false);
        userMirrorV = prefs.getBoolean("mirrorV", false);

        layoutParams = new WindowManager.LayoutParams(
                width, height,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = prefs.getInt("x", dp(16));
        layoutParams.y = prefs.getInt("y", dp(80));

        windowManager.addView(overlayView, layoutParams);

        setupTouchHandling();
        setupControls();
        applyRoundness();

        // Resize handle stays out of the way (invisible-UI ethos): hidden until the window is moved,
        // with a brief reveal on launch so it's discoverable.
        resizeHandle.setVisibility(View.GONE);
        resizeHandle.post(() -> { flashResizeHandle(); scheduleHideResizeHandle(); });

        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int w, int h) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int w, int h) {
                configureTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) { }
        });
    }

    private void setupTouchHandling() {
        // Drag to move (whole window); tap toggles control bar or restores from bubble.
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float touchX, touchY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downTime = System.currentTimeMillis();
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        layoutParams.x = initialX + (int) (event.getRawX() - touchX);
                        layoutParams.y = initialY + (int) (event.getRawY() - touchY);
                        windowManager.updateViewLayout(overlayView, layoutParams);
                        flashResizeHandle(); // reveal the resize affordance while repositioning
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dx = Math.abs(event.getRawX() - touchX);
                        float dy = Math.abs(event.getRawY() - touchY);
                        if (System.currentTimeMillis() - downTime < 250 && dx < 10 && dy < 10) {
                            if (isMinimized) {
                                setMinimized(false);
                            } else {
                                toggleControlBar();
                            }
                        } else {
                            savePosition();
                            scheduleHideResizeHandle(); // settle, then fade the handle away
                        }
                        return true;
                }
                return false;
            }
        });

        // Drag handle to resize. Near-diagonal drags keep the current aspect
        // ratio; clearly horizontal/vertical drags resize freely so the window
        // can be made wide-and-flat or tall (up to full screen size).
        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialWidth, initialHeight;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialWidth = layoutParams.width;
                        initialHeight = layoutParams.height;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        float growW = dx / initialWidth;
                        float growH = dy / initialHeight;
                        int newWidth, newHeight;
                        if (Math.abs(growW - growH) < 0.15f) {
                            // Near-diagonal: snap to current aspect ratio
                            float grow = (growW + growH) / 2f;
                            newWidth = (int) (initialWidth * (1 + grow));
                            newHeight = (int) (initialHeight * (1 + grow));
                        } else {
                            newWidth = initialWidth + (int) dx;
                            newHeight = initialHeight + (int) dy;
                        }
                        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                        layoutParams.width = Math.max(dp(MIN_WIDTH_DP),
                                Math.min(dm.widthPixels, newWidth));
                        layoutParams.height = Math.max(dp(MIN_WIDTH_DP),
                                Math.min(dm.heightPixels, newHeight));
                        windowManager.updateViewLayout(overlayView, layoutParams);
                        applyRoundness();
                        flashResizeHandle();
                        return true;
                    case MotionEvent.ACTION_UP:
                        savePosition();
                        scheduleHideResizeHandle();
                        return true;
                }
                return false;
            }
        });
    }

    /** Applies corner roundness: 0% = square, 100% = circle/oval (radius = half the short side). */
    private void applyRoundness() {
        if (webcamCard instanceof androidx.cardview.widget.CardView) {
            int pct = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("roundness", 20);
            int shortSide = Math.min(layoutParams.width, layoutParams.height);
            ((androidx.cardview.widget.CardView) webcamCard)
                    .setRadius(pct / 100f * shortSide / 2f);
        }
    }

    private void setupControls() {
        android.widget.SeekBar roundness = overlayView.findViewById(R.id.webcamRoundnessSeek);
        roundness.setProgress(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("roundness", 20));
        roundness.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putInt("roundness", progress).apply();
                    applyRoundness();
                    scheduleHideControls();
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) { }
        });

        overlayView.findViewById(R.id.btnWebcamClose).setOnClickListener(v -> stopSelf());
        overlayView.findViewById(R.id.btnWebcamMinimize).setOnClickListener(v -> setMinimized(true));
        overlayView.findViewById(R.id.btnWebcamSwitch).setOnClickListener(v -> {
            useFrontCamera = !useFrontCamera;
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("front", useFrontCamera).apply();
            closeCamera();
            openCamera();
            updateLensButton();
            scheduleHideControls();
        });
        overlayView.findViewById(R.id.btnWebcamLens).setOnClickListener(v -> {
            if (backCameraIds.length > 1) {
                backCameraIndex = (backCameraIndex + 1) % backCameraIds.length;
                closeCamera();
                openCamera();
                updateLensButton();
            }
            scheduleHideControls();
        });
        overlayView.findViewById(R.id.btnWebcamAvatar).setOnClickListener(v -> {
            cycleAvatar();
            scheduleHideControls();
        });
        updateAvatarButton();
        overlayView.findViewById(R.id.btnWebcamRotate).setOnClickListener(v -> {
            userRotation = (userRotation + 90) % 360;
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt("userRotation", userRotation).apply();
            configureTransform();
            scheduleHideControls();
        });
        overlayView.findViewById(R.id.btnWebcamMirrorH).setOnClickListener(v -> {
            userMirrorH = !userMirrorH;
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("mirrorH", userMirrorH).apply();
            updateOrientationButtons();
            configureTransform();
            scheduleHideControls();
        });
        overlayView.findViewById(R.id.btnWebcamMirrorV).setOnClickListener(v -> {
            userMirrorV = !userMirrorV;
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("mirrorV", userMirrorV).apply();
            updateOrientationButtons();
            configureTransform();
            scheduleHideControls();
        });
        updateLensButton();
        updateOrientationButtons();
        // Note: no click listener on webcamBubble — the root touch handler
        // owns drag + tap-to-restore so the bubble stays draggable.
    }

    /** Shows the lens-cycle button (with the active lens number) when the
     *  device has multiple back cameras and the back camera is active. */
    private void updateLensButton() {
        android.widget.TextView lens = overlayView.findViewById(R.id.btnWebcamLens);
        if (!useFrontCamera && backCameraIds.length > 1) {
            lens.setVisibility(View.VISIBLE);
            lens.setText(String.valueOf(backCameraIndex + 1));
        } else {
            lens.setVisibility(View.GONE);
        }
    }

    /**
     * A4 avatar selector (JoyRaptor spec 2026-07-11: sits between the webcam cluster
     * and Rotate): tap cycles webcam → each library avatar → webcam. Selection
     * persists in prefs; the puppet actually RENDERING into this bubble (camera
     * feeding only the tracker) is the next A4 slice — the selector is honest
     * about that in its toast until then. Cycle-not-dialog because this UI is a
     * service overlay (no activity to host a Material dialog).
     */
    private void cycleAvatar() {
        java.util.List<com.fadcam.ui.faditor.avatar.AvatarLibrary.Entry> entries =
                com.fadcam.ui.faditor.avatar.AvatarLibrary.list(this);
        if (entries.isEmpty()) {
            android.widget.Toast.makeText(this,
                    "No saved avatars — Avatar Studio → Library ⇪ to add one",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cur = prefs.getString("avatarEntryDir", null);
        int idx = -1; // -1 = webcam (no avatar)
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).dir.getName().equals(cur)) { idx = i; break; }
        }
        int next = idx + 1;
        if (next >= entries.size()) {
            prefs.edit().remove("avatarEntryDir").apply();
            android.widget.Toast.makeText(this, "Avatar off — webcam shows",
                    android.widget.Toast.LENGTH_SHORT).show();
        } else {
            com.fadcam.ui.faditor.avatar.AvatarLibrary.Entry e = entries.get(next);
            prefs.edit().putString("avatarEntryDir", e.dir.getName()).apply();
            android.widget.Toast.makeText(this,
                    "Avatar: " + e.rig.getName() + " (rendering lands next build)",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        updateAvatarButton();
    }

    /** Green tint while an avatar is selected — same state cue as the mirrors. */
    private void updateAvatarButton() {
        android.widget.TextView b = overlayView.findViewById(R.id.btnWebcamAvatar);
        if (b == null) return;
        boolean on = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("avatarEntryDir", null) != null;
        b.setTextColor(on ? 0xFF4CAF50 : 0xFFFFFFFF);
    }

    /** Tints the mirror toggles green when active so their state is obvious. */
    private void updateOrientationButtons() {
        android.widget.TextView mh = overlayView.findViewById(R.id.btnWebcamMirrorH);
        android.widget.TextView mv = overlayView.findViewById(R.id.btnWebcamMirrorV);
        if (mh != null) mh.setTextColor(userMirrorH ? 0xFF4CAF50 : 0xFFFFFFFF);
        if (mv != null) mv.setTextColor(userMirrorV ? 0xFF4CAF50 : 0xFFFFFFFF);
    }

    private void enumerateBackCameras() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            java.util.List<String> backs = new java.util.ArrayList<>();
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backs.add(id);
                }
            }
            backCameraIds = backs.toArray(new String[0]);
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Failed to enumerate cameras", e);
        }
    }

    /**
     * Center-crops the camera frame into the window instead of stretching it,
     * so faces keep their real proportions regardless of window shape.
     */
    private void configureTransform() {
        if (previewSize == null || previewView == null) return;
        int vw = previewView.getWidth();
        int vh = previewView.getHeight();
        if (vw == 0 || vh == 0) return;
        boolean swap = sensorOrientation == 90 || sensorOrientation == 270;
        float frameW = swap ? previewSize.getHeight() : previewSize.getWidth();
        float frameH = swap ? previewSize.getWidth() : previewSize.getHeight();
        float scale = Math.max(vw / frameW, vh / frameH);
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setScale(frameW * scale / vw, frameH * scale / vh, vw / 2f, vh / 2f);
        // Manual orientation overrides, applied about the window centre.
        if (userRotation != 0) {
            matrix.postRotate(userRotation, vw / 2f, vh / 2f);
            if (userRotation == 90 || userRotation == 270) {
                // A 90/270 turn leaves the (fixed-aspect) window unfilled; over-scale to cover.
                float cover = Math.max(vw / (float) vh, vh / (float) vw);
                matrix.postScale(cover, cover, vw / 2f, vh / 2f);
            }
        }
        if (userMirrorH) matrix.postScale(-1f, 1f, vw / 2f, vh / 2f);
        if (userMirrorV) matrix.postScale(1f, -1f, vw / 2f, vh / 2f);
        previewView.setTransform(matrix);
    }

    private void toggleControlBar() {
        boolean show = webcamControls.getVisibility() != View.VISIBLE;
        fadeView(webcamControls, show);
        if (show) {
            // Sit above the live preview so the menu is never blocked by the camera texture.
            webcamControls.bringToFront();
            webcamControls.setElevation(dp(8));
            scheduleHideControls(); // auto-dismiss if left untouched (forgotten-on-record safety net)
        } else {
            mainHandler.removeCallbacks(hideControlsRunnable);
        }
    }

    /** Reset/start the quiet-period timer that slowly fades the controls away if untouched. */
    private void scheduleHideControls() {
        if (webcamControls.getVisibility() != View.VISIBLE) return;
        mainHandler.removeCallbacks(hideControlsRunnable);
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS);
    }

    private void fadeView(View v, boolean show) {
        fadeView(v, show, 160);
    }

    /** Cross-fade a view in/out over {@code durationMs} (no hard single-frame pop). */
    private void fadeView(View v, boolean show, long durationMs) {
        if (v == null) return;
        v.animate().cancel();
        if (show) {
            v.setAlpha(0f);
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(durationMs).start();
        } else {
            v.animate().alpha(0f).setDuration(durationMs)
                    .withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    /**
     * Reveals the resize handle and schedules it to fade out after a short delay, so it only appears
     * while you're moving the window and then gets out of the way — keeping the recorded overlay clean
     * (the "invisible UI" ethos). Fade-in is gentle (~320ms); fade-out is very slow + subtle (~640ms)
     * so it doesn't draw the eye as it leaves.
     */
    private void flashResizeHandle() {
        if (resizeHandle == null || isMinimized) return;
        mainHandler.removeCallbacks(hideHandleRunnable);
        if (resizeHandle.getVisibility() != View.VISIBLE || resizeHandle.getAlpha() < 1f) {
            fadeView(resizeHandle, true, 320);
        }
    }

    /** Fade the handle out after the move/resize gesture settles. */
    private void scheduleHideResizeHandle() {
        if (resizeHandle == null) return;
        mainHandler.removeCallbacks(hideHandleRunnable);
        mainHandler.postDelayed(hideHandleRunnable, HANDLE_HIDE_DELAY_MS);
    }

    private void setMinimized(boolean minimize) {
        isMinimized = minimize;
        mainHandler.removeCallbacks(hideHandleRunnable);
        mainHandler.removeCallbacks(hideControlsRunnable);
        if (minimize) {
            closeCamera();
            webcamCard.setVisibility(View.GONE);
            // Outgoing card is instant (the window resizes too, so fading it would clip); the
            // incoming view fades in for a soft swap.
            fadeView(webcamBubble, true);
            layoutParams.width = dp(56);
            layoutParams.height = dp(56);
        } else {
            webcamBubble.setVisibility(View.GONE);
            fadeView(webcamCard, true);
            resizeHandle.setVisibility(View.GONE); // stays hidden until the window is moved again
            SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int width = p.getInt("width", dp(160));
            layoutParams.width = width;
            layoutParams.height = p.getInt("height", (int) (width / ASPECT));
            if (previewView.isAvailable()) {
                openCamera();
            }
        }
        windowManager.updateViewLayout(overlayView, layoutParams);
        if (!minimize) {
            applyRoundness();
        }
    }

    private void savePosition() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("x", layoutParams.x)
                .putInt("y", layoutParams.y);
        if (!isMinimized) {
            editor.putInt("width", layoutParams.width)
                    .putInt("height", layoutParams.height);
        }
        editor.apply();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // -------------------- Camera --------------------

    private void openCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            FLog.e(TAG, "Camera permission not granted; closing webcam overlay");
            stopSelf();
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId;
            if (!useFrontCamera && backCameraIds.length > 0) {
                cameraId = backCameraIds[backCameraIndex % backCameraIds.length];
            } else {
                cameraId = findCamera(manager,
                        useFrontCamera ? CameraCharacteristics.LENS_FACING_FRONT
                                : CameraCharacteristics.LENS_FACING_BACK);
            }
            if (cameraId == null) {
                FLog.e(TAG, "No matching camera found");
                return;
            }
            Integer orientation = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation != null ? orientation : 90;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    FLog.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            FLog.e(TAG, "Failed to open camera", e);
        }
    }

    @Nullable
    private String findCamera(CameraManager manager, int facing) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer lensFacing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == facing) {
                return id;
            }
        }
        return null;
    }

    private void startPreview() {
        if (cameraDevice == null || !previewView.isAvailable()) return;
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            previewSize = choosePreviewSize();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            mainHandler.post(this::configureTransform);
            Surface surface = new Surface(texture);

            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                            } catch (CameraAccessException e) {
                                FLog.e(TAG, "Failed to start preview", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            FLog.e(TAG, "Capture session configuration failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Failed to create preview session", e);
        }
    }

    private Size choosePreviewSize() {
        // Modest size keeps GPU/battery cost low; the window is small on screen.
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            StreamConfigurationMap map = manager.getCameraCharacteristics(cameraDevice.getId())
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size best = null;
                for (Size size : map.getOutputSizes(SurfaceTexture.class)) {
                    if (size.getWidth() <= 1280 && (best == null || size.getWidth() > best.getWidth())) {
                        best = size;
                    }
                }
                if (best != null) return best;
            }
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Failed to query preview sizes", e);
        }
        return new Size(960, 720);
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        mainHandler.removeCallbacks(hideHandleRunnable);
        mainHandler.removeCallbacks(hideControlsRunnable);
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
        }
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
    }
}
