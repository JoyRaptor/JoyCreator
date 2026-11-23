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
 * export), {@code audio_only} (optional), {@code keep_awake} (default true: stay open, dimmed,
 * holding the screen on until the export ends - the editor's "Keep screen on" box; with the
 * screen off Samsung moves the export to the little cores and it runs ~4x slower). Progress
 * and results land in the export trace and the usual notifications. The editor's slide
 * pre-pass is not run here.
 */
public class ExportDebugActivity extends Activity {

    private static final String TAG = "ExportDebug";

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long startedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean started = false;
        try {
            started = start(getIntent());
        } catch (Exception e) {
            FLog.e(TAG, "debug export failed to start", e);
        }
        if (!started || !getIntent().getBooleanExtra("keep_awake", true)) {
            finish();
            return;
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.02f;
        getWindow().setAttributes(lp);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("Debug export running - screen held on until it ends");
        tv.setTextColor(0xFF888888);
        tv.setBackgroundColor(0xFF000000);
        tv.setGravity(android.view.Gravity.CENTER);
        setContentView(tv);
        startedAt = System.currentTimeMillis();
        handler.postDelayed(this::checkDone, 15_000L);
    }

    /** Close once the export's ongoing notification is gone (done, failed or cancelled). */
    private void checkDone() {
        if (isFinishing()) return;
        if (!ExportService.isRunning(this) && System.currentTimeMillis() - startedAt > 15_000L) {
            FLog.i(TAG, "export no longer running - releasing the screen");
            finish();
            return;
        }
        handler.postDelayed(this::checkDone, 5_000L);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private boolean start(Intent in) throws Exception {
        if (in.getBooleanExtra("motion_spine_fixture", false)) {
            boolean enabled = in.getBooleanExtra("motion_enabled", true);
            boolean animated = in.getBooleanExtra("motion_animated", true);
            boolean transparentArt = in.getBooleanExtra("motion_transparent_art", false);
            FaditorProject fixture = MotionRouteFixture.createSpine(this, enabled, animated, transparentArt);
            in.putExtra("project_id", fixture.getId());
            FLog.i(TAG, "C2 spine fixture enabled=" + enabled + " animated=" + animated
                    + " art=" + transparentArt);
        }
        if (in.getBooleanExtra("motion_picture_fixture", false)) {
            boolean enabled = in.getBooleanExtra("motion_enabled", true);
            FaditorProject fixture = MotionRouteFixture.createPicture(this, enabled);
            in.putExtra("project_id", fixture.getId());
            FLog.i(TAG, "C2 picture fixture enabled=" + enabled);
        }
        if (in.getBooleanExtra("motion_sprite_fixture", false)) {
            boolean enabled = in.getBooleanExtra("motion_enabled", true);
            FaditorProject fixture = MotionRouteFixture.createSprite(this, enabled);
            in.putExtra("project_id", fixture.getId());
            FLog.i(TAG, "C2 sprite fixture enabled=" + enabled);
        }
        if (in.getBooleanExtra("motion_title_fixture", false)) {
            boolean enabled = in.getBooleanExtra("motion_enabled", true);
            FaditorProject fixture = MotionRouteFixture.createTitle(this, enabled);
            in.putExtra("project_id", fixture.getId());
            FLog.i(TAG, "C2 title fixture enabled=" + enabled);
        }
        if (in.getBooleanExtra("motion_pip_fixture", false)) {
            boolean enabled = in.getBooleanExtra("motion_enabled", true);
            FaditorProject fixture = MotionRouteFixture.createPip(this, enabled);
            in.putExtra("project_id", fixture.getId());
            FLog.i(TAG, "C2 pip fixture enabled=" + enabled);
        }
        if (in.getBooleanExtra("motion_route_fixture", false)) {
            FaditorProject fixture = MotionRouteFixture.create(this);
            in.putExtra("project_id", fixture.getId());
            FLog.i(TAG, "C1 route fixture project_id=" + fixture.getId());
            if (in.getBooleanExtra("open_preview", false)) {
                Intent preview = new Intent(this, com.fadcam.ui.faditor.FaditorEditorActivity.class);
                preview.putExtra(com.fadcam.ui.faditor.FaditorEditorActivity.EXTRA_PROJECT_ID, fixture.getId());
                startActivity(preview);
                return false;
            }
        }
        String id = in.getStringExtra("project_id");
        if (id == null) {
            FLog.w(TAG, "no project_id extra");
            return false;
        }
        ProjectStorage storage = new ProjectStorage(this);
        FaditorProject project = storage.load(id);
        if (project == null) {
            FLog.w(TAG, "no project " + id);
            return false;
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
        return true;
    }
}
