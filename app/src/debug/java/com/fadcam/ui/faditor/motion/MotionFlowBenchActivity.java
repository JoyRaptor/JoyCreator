package com.fadcam.ui.faditor.motion;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.opengl.GLSurfaceView;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

/** Synthetic footage diagnostics for the test phone. No Studio project or device data is edited. */
public final class MotionFlowBenchActivity extends Activity {
    static final String TAG = "MotionFlowBench";
    private final Handler main = new Handler(Looper.getMainLooper());
    private GLSurfaceView surface;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean timing = getIntent().getBooleanExtra("timing", false);
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(timing ? 3 : 2);
        surface.setRenderer(new MotionFlowBenchRenderer(getAssets(), timing, () -> main.post(() -> {
            temperature();
            finish();
        })));
        surface.setRenderMode(timing ? GLSurfaceView.RENDERMODE_CONTINUOUSLY
                : GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setContentView(surface);
        temperature();
        main.postDelayed(this::logTemperature, 30_000L);
    }

    private void logTemperature() {
        temperature();
        main.postDelayed(this::logTemperature, 30_000L);
    }

    private void temperature() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int temp = battery == null ? Integer.MIN_VALUE
                : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int thermal = Build.VERSION.SDK_INT >= 29 ? getSystemService(PowerManager.class).getCurrentThermalStatus() : -1;
        Log.i(TAG, "batteryC=" + (temp == Integer.MIN_VALUE ? "unavailable" : temp / 10f)
                + " thermalStatus=" + thermal);
    }

    @Override protected void onPause() {
        main.removeCallbacksAndMessages(null);
        if (surface != null) surface.onPause();
        super.onPause();
        finish();
    }
}
