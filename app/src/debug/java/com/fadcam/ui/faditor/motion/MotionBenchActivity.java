package com.fadcam.ui.faditor.motion;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLSurfaceView;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.fadcam.ui.faditor.compositor.FxPreviewTextureView;

import java.util.Arrays;
import java.util.Locale;

/** C1 debug diagnostic using the unmodified Studio preview view and a separate calibration pass. */
public final class MotionBenchActivity extends Activity {
    static final String TAG = "MotionBench";
    private static final int WIDTH = 1280, HEIGHT = 720;
    private static final long DURATION_MS = 60_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final long[] intervals = new long[7_200];
    private int intervalCount;
    private long lastLatchNs;
    private long startedAtMs;
    private MediaPlayer player;
    private GLSurfaceView calibration;
    private boolean recording;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout layout = new FrameLayout(this);
        var preview = new FxPreviewTextureView(this) {
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                super.onSurfaceTextureUpdated(surfaceTexture);
                recordLatch();
            }
        };
        preview.setSurfaceListener(new FxPreviewTextureView.SurfaceListener() {
            @Override public void onFxInputSurfaceReady(Surface surface) {
                play(preview, surface);
            }

            @Override public void onFxInputSurfaceLost() { releasePlayer(); }
        });
        layout.addView(preview, new FrameLayout.LayoutParams(WIDTH, HEIGHT, Gravity.CENTER));
        setContentView(layout);
    }

    private void play(FxPreviewTextureView preview, Surface surface) {
        releasePlayer();
        preview.setCompositeFrame(WIDTH, HEIGHT, WIDTH, HEIGHT);
        player = new MediaPlayer();
        try (var asset = getAssets().openFd("demo/card1.mp4")) {
            player.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            player.setSurface(surface);
            player.setLooping(true);
            player.setVolume(0f, 0f);
            player.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "Benchmark source playback error " + what + "/" + extra);
                finish();
                return true;
            });
            player.setOnPreparedListener(p -> {
                preview.setCompositeFrame(WIDTH, HEIGHT, p.getVideoWidth(), p.getVideoHeight());
                p.start();
                recording = true;
                startedAtMs = SystemClock.elapsedRealtime();
                temperature();
                main.postDelayed(this::temperature, 30_000);
                main.postDelayed(this::finishBaseline, DURATION_MS);
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Cannot play the bundled benchmark clip", e);
            finish();
        }
    }

    private void recordLatch() {
        if (!recording) return;
        long now = System.nanoTime();
        if (lastLatchNs != 0 && intervalCount < intervals.length) {
            intervals[intervalCount++] = now - lastLatchNs;
        }
        lastLatchNs = now;
    }

    private void finishBaseline() {
        recording = false;
        long elapsed = SystemClock.elapsedRealtime() - startedAtMs;
        Arrays.sort(intervals, 0, intervalCount);
        Log.i(TAG, String.format(Locale.ROOT,
                "Baseline 1280x720 compositor, bundled decoder clip, TextureView latch: wall=%dms intervals=%d p50=%.3fms p95=%.3fms p99=%.3fms",
                elapsed, intervalCount, percentile(.50), percentile(.95), percentile(.99)));
        temperature();
        releasePlayer();

        calibration = new GLSurfaceView(this);
        calibration.setEGLContextClientVersion(3);
        calibration.setRenderer(new MotionGatherCalibration(() -> main.post(this::finish)));
        setContentView(calibration);
    }

    private double percentile(double p) {
        if (intervalCount == 0) return Double.NaN;
        int at = Math.min(intervalCount - 1, (int) Math.ceil(p * intervalCount) - 1);
        return intervals[at] / 1e6;
    }

    private void temperature() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int batteryTenthsC = battery == null ? Integer.MIN_VALUE
                : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int thermal = android.os.Build.VERSION.SDK_INT >= 29
                ? getSystemService(PowerManager.class).getCurrentThermalStatus() : -1;
        Log.i(TAG, String.format(Locale.ROOT, "Temperature t=%dms battery=%sC thermalStatus=%d",
                SystemClock.elapsedRealtime() - startedAtMs,
                batteryTenthsC == Integer.MIN_VALUE ? "unavailable" : Float.toString(batteryTenthsC / 10f),
                thermal));
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (calibration != null) calibration.onPause();
        if (!isFinishing()) finish();
    }

    @Override protected void onDestroy() {
        recording = false;
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }
}
