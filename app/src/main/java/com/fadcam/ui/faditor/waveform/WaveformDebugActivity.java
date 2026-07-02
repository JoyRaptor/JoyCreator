package com.fadcam.ui.faditor.waveform;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.WaveformData;
import com.fadcam.ui.faditor.model.WaveformStyle;

import java.util.List;

/**
 * Phase 0 debug screen for the Waveform Visualizer: renders every built-in preset against
 * SYNTHETIC amplitude + spectrum data, animating over time, so the renderer can be verified
 * before any extraction/editor/export wiring. Launch via:
 * {@code adb shell am start -n com.fadcam.beta/com.fadcam.ui.faditor.waveform.WaveformDebugActivity}
 */
public class WaveformDebugActivity extends Activity {

    private static final int RENDER_W = 1000;
    private static final int RENDER_H = 260;
    private static final long FRAME_MS = 50; // ~20fps

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WaveformStyleRenderer renderer = new WaveformStyleRenderer();
    private final List<WaveformStyle> styles = new java.util.ArrayList<>();
    private WaveformData data;
    private float density;
    private long startWall;
    private ImageView[] views;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        data = synthData();
        styles.addAll(WaveformStyleIO.loadBuiltins(this));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101014);
        int pad = (int) (12 * density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        views = new ImageView[styles.size()];
        for (int i = 0; i < styles.size(); i++) {
            TextView label = new TextView(this);
            label.setText(styles.get(i).displayName + "  (" + styles.get(i).type + ")");
            label.setTextColor(Color.WHITE);
            label.setPadding(0, pad, 0, pad / 2);
            root.addView(label);

            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 130,
                            getResources().getDisplayMetrics()));
            iv.setLayoutParams(lp);
            iv.setBackgroundColor(0xFF000000);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            views[i] = iv;
            root.addView(iv);
        }

        setContentView(scroll);
        startWall = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(frameLoop);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(frameLoop);
    }

    private final Runnable frameLoop = new Runnable() {
        @Override
        public void run() {
            long atMs = (SystemClock.elapsedRealtime() - startWall) % data.durationMs;
            for (int i = 0; i < styles.size(); i++) {
                views[i].setImageBitmap(
                        renderer.render(data, styles.get(i), RENDER_W, RENDER_H, atMs, density));
            }
            handler.postDelayed(this, FRAME_MS);
        }
    };

    /** Synthetic amplitude + spectrum so the renderer can be verified without real audio. */
    @NonNull
    private WaveformData synthData() {
        int buckets = 400;
        long durationMs = 4000;
        int bands = 32;
        float[] amp = new float[buckets];
        float[][] spec = new float[buckets][bands];
        for (int i = 0; i < buckets; i++) {
            double base = 0.5 + 0.5 * Math.sin(i * 0.10);
            double slow = 0.5 + 0.5 * Math.sin(i * 0.013);
            amp[i] = (float) clamp01(base * slow);
            for (int b = 0; b < bands; b++) {
                double bandFall = 1.0 - (b / (double) bands) * 0.7; // bass louder than treble
                double wob = 0.5 + 0.5 * Math.sin(i * 0.05 + b * 0.6);
                spec[i][b] = (float) clamp01(bandFall * wob);
            }
        }
        return new WaveformData(amp, spec, durationMs);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
