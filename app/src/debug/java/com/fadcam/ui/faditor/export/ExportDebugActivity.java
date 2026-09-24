package com.fadcam.ui.faditor.export;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * DEBUG BUILDS ONLY: start an export of a saved project from adb, exactly as the editor's
 * Export button does (a snapshot file handed to {@link ExportService}), so an agent can prove
 * an export fix on the device without JoyRaptor tapping through it again ("I am so sick and
 * tired of doing the same export testing", 2026-09-23). Extras:
 * {@code project_id} (required), {@code range_start_ms}/{@code range_end_ms} (optional range
 * export), {@code audio_only} (optional). Progress and results land in the export trace and
 * the usual notifications. The editor's slide pre-pass is not run here.
 */
public class ExportDebugActivity extends Activity {

    private static final String TAG = "ExportDebug";

    /** Extra "stay": remain visible (over the lock screen, screen on) bound to the export. */
    private android.content.ServiceConnection binding;
    private android.content.BroadcastReceiver doneReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            start(getIntent());
        } catch (Exception e) {
            FLog.e(TAG, "debug export failed to start", e);
        }
        if (!getIntent().getBooleanExtra("stay", false)) {
            finish();
            return;
        }
        // Experiment for the cpuset question: does a VISIBLE screen bound to the service keep
        // the :export process off Samsung's little-core-only group?
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        binding = ExportService.bindWhileVisible(this);
        FLog.i(TAG, "staying visible, bound=" + (binding != null));
        doneReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, Intent i) {
                finish();
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(ExportService.ACTION_EXPORT_COMPLETED);
        f.addAction(ExportService.ACTION_EXPORT_ERROR);
        f.addAction(ExportService.ACTION_EXPORT_CANCELLED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(doneReceiver, f, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(doneReceiver, f);
        }
    }

    @Override
    protected void onDestroy() {
        if (binding != null) {
            try { unbindService(binding); } catch (Exception ignored) { }
        }
        if (doneReceiver != null) {
            try { unregisterReceiver(doneReceiver); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    private void start(Intent in) throws Exception {
        String id = in.getStringExtra("project_id");
        if (id == null) {
            FLog.w(TAG, "no project_id extra");
            return;
        }
        ProjectStorage storage = new ProjectStorage(this);
        FaditorProject project = storage.load(id);
        if (project == null) {
            FLog.w(TAG, "no project " + id);
            return;
        }
        File dir = new File(getFilesDir(), "faditor");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File snap = new File(dir, "export_snapshot_" + System.currentTimeMillis() + ".json");
        try (FileOutputStream fos = new FileOutputStream(snap)) {
            fos.write(storage.toJson(project).getBytes(StandardCharsets.UTF_8));
        }
        Intent si = new Intent(this, ExportService.class);
        si.setAction(ExportService.ACTION_START_EXPORT);
        si.putExtra(ExportService.EXTRA_PROJECT_SNAPSHOT_PATH, snap.getAbsolutePath());
        si.putExtra(ExportService.EXTRA_AUDIO_ONLY, in.getBooleanExtra("audio_only", false));
        long a = in.getLongExtra("range_start_ms", -1L);
        long b = in.getLongExtra("range_end_ms", -1L);
        if (a >= 0 && b > a) {
            si.putExtra(ExportService.EXTRA_RANGE_START_MS, a);
            si.putExtra(ExportService.EXTRA_RANGE_END_MS, b);
        }
        si.putExtra("extra_loudness_target", getSharedPreferences("faditor_export",
                MODE_PRIVATE).getString("pending_loudness_target", "OFF"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(si);
        } else {
            startService(si);
        }
        FLog.i(TAG, "debug export started for " + id + (a >= 0 && b > a
                ? " range " + a + ".." + b : ""));
    }
}
