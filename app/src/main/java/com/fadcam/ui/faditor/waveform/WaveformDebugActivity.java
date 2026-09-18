package com.fadcam.ui.faditor.waveform;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.WaveformData;
import com.fadcam.ui.faditor.model.WaveformStyle;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Phase 0 debug screen for the Waveform Visualizer: renders every built-in preset against
 * SYNTHETIC amplitude + spectrum data, animating over time, so the renderer can be verified
 * before any extraction/editor/export wiring. Launch via:
 * {@code adb shell am start -n com.fadcam.beta/com.fadcam.ui.faditor.waveform.WaveformDebugActivity}
 *
 * <p>Phase 3: also doubles as an isolated SAF import/export host for custom
 * {@link WaveformStyle} JSON files (each preset row gets an Export button; a top-level
 * Import button appends an imported style as a new preview row), via
 * {@link WaveformStyleIO#write} / {@link WaveformStyleIO#read}.</p>
 */
public class WaveformDebugActivity extends AppCompatActivity {

    private static final String TAG = "WaveformDebugActivity";
    private static final int RENDER_W = 1000;
    private static final int RENDER_H = 260;
    private static final long FRAME_MS = 50; // ~20fps

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WaveformStyleRenderer renderer = new WaveformStyleRenderer();
    private final List<WaveformStyle> styles = new java.util.ArrayList<>();
    private WaveformData data;
    private float density;
    private long startWall;
    private final List<ImageView> views = new java.util.ArrayList<>();
    @Nullable private LinearLayout root;

    /** Index into {@link #styles} awaiting the CreateDocument result; -1 when idle. */
    private int pendingExportIndex = -1;

    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                int idx = pendingExportIndex;
                pendingExportIndex = -1;
                if (uri == null || idx < 0 || idx >= styles.size()) return;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out != null && WaveformStyleIO.write(out, styles.get(idx))) {
                        Toast.makeText(this, "Exported " + styles.get(idx).displayName, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Style export failed", e);
                    Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                WaveformStyle imported = null;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in != null) imported = WaveformStyleIO.read(in);
                } catch (Exception e) {
                    FLog.w(TAG, "Style import failed", e);
                }
                if (imported == null || imported.type == null) {
                    Toast.makeText(this, "Import failed: invalid style JSON", Toast.LENGTH_SHORT).show();
                    return;
                }
                addStyleRow(imported);
                Toast.makeText(this, "Imported " + imported.displayName, Toast.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        data = synthData();
        styles.addAll(WaveformStyleIO.loadBuiltins(this));

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D10);
        int pad = (int) (12 * density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        Button importBtn = new Button(this);
        importBtn.setText("Import style JSON");
        importBtn.setOnClickListener(v -> importLauncher.launch(
                new String[]{"application/json", "text/plain", "*/*"}));
        root.addView(importBtn);

        for (WaveformStyle style : styles) {
            addStyleRow(style);
        }

        setContentView(scroll);
        startWall = SystemClock.elapsedRealtime();
    }

    /**
     * Appends a label + Export button + preview row for {@code style} to the layout. Used both
     * for the built-in presets loaded at {@code onCreate} and for a freshly SAF-imported style
     * (which is NOT persisted anywhere — it's just added to this debug session's preview list).
     */
    private void addStyleRow(@NonNull WaveformStyle style) {
        if (root == null) return;
        int pad = (int) (12 * density);
        int idx = styles.indexOf(style);
        if (idx < 0) {
            styles.add(style);
            idx = styles.size() - 1;
        }

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(style.displayName + "  (" + style.type + ")");
        label.setTextColor(Color.WHITE);
        label.setPadding(0, pad, 0, pad / 2);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(label);

        int exportIdx = idx;
        Button exportBtn = new Button(this);
        exportBtn.setText("Export");
        exportBtn.setOnClickListener(v -> {
            pendingExportIndex = exportIdx;
            String fileName = (style.id == null || style.id.isEmpty() ? "visualizer" : style.id)
                    + WaveformStyleIO.USER_STYLE_SUFFIX;
            exportLauncher.launch(fileName);
        });
        headerRow.addView(exportBtn);
        root.addView(headerRow);

        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 130,
                        getResources().getDisplayMetrics()));
        iv.setLayoutParams(lp);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        views.add(iv);
        root.addView(iv);
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
            int n = Math.min(styles.size(), views.size());
            for (int i = 0; i < n; i++) {
                views.get(i).setImageBitmap(
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
