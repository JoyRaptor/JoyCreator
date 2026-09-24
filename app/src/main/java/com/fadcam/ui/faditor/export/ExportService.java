package com.fadcam.ui.faditor.export;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.playback.FragmentedMp4Remuxer;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that runs video export OUT OF PROCESS (manifest
 * {@code android:process=":export"}) so it survives not just Activity
 * destruction but a full editor-process crash/kill — and, symmetrically, an
 * exporter OOM/codec crash can never take the editor down. The exporter also
 * gets its OWN heap, which is the real fix for big-project exports dying on
 * memory pressure next to the preview player.
 *
 * <p>The Activity serializes an edit-immune project snapshot to a file and
 * passes its path in the start intent ({@link #EXTRA_PROJECT_SNAPSHOT_PATH});
 * progress/completion flow back as package-scoped global broadcasts (the
 * pre-OOP LocalBroadcastManager and bound-Binder channels are in-process-only
 * and are gone). Cancel = start intent with {@link #ACTION_CANCEL_EXPORT}.</p>
 */
public class ExportService extends Service {

    private static final String TAG = "ExportService";
    private static final String CHANNEL_ID = "faditor_export_channel";
    /** Ongoing foreground-progress notification. Its presence == an export is running
     *  ({@link #isRunning}); completion/error re-post under {@link #NOTIFICATION_ID_DONE}. */
    private static final int NOTIFICATION_ID = 3001;
    private static final int NOTIFICATION_ID_DONE = 3002;

    /** Action to start an export. */
    public static final String ACTION_START_EXPORT = "com.fadcam.EXPORT_START";
    /** Action to cancel an export. */
    public static final String ACTION_CANCEL_EXPORT = "com.fadcam.EXPORT_CANCEL";
    
    // ── Broadcast actions for UI updates ──────────────────────────────
    public static final String ACTION_EXPORT_STARTED = "com.fadcam.EXPORT_STARTED";
    public static final String ACTION_EXPORT_PROGRESS = "com.fadcam.EXPORT_PROGRESS";
    public static final String ACTION_EXPORT_COMPLETED = "com.fadcam.EXPORT_COMPLETED";
    public static final String ACTION_EXPORT_ERROR = "com.fadcam.EXPORT_ERROR";
    public static final String ACTION_EXPORT_CANCELLED = "com.fadcam.EXPORT_CANCELLED";
    /** FIX-6: the muxer finished; the finalize phase (loudness/SAF) is running. */
    public static final String ACTION_EXPORT_FINALIZING = "com.fadcam.EXPORT_FINALIZING";
    /**
     * An export was asked for while another runs: it waits and starts on its own when the
     * running one ends (JoyRaptor, 2026-09-24: pressing export mid-export should "Queue
     * export"). Carries {@link #EXTRA_QUEUE_SIZE}. Cancel stops the running export AND the queue.
     */
    public static final String ACTION_EXPORT_QUEUED = "com.fadcam.EXPORT_QUEUED";
    public static final String EXTRA_QUEUE_SIZE = "queue_size";

    public static final String EXTRA_OUTPUT_PATH = "output_path";
    public static final String EXTRA_PROGRESS = "progress";
    /** Chunked export phase label ("Part 2 of 6", "Sound", "Joining"). */
    public static final String EXTRA_PHASE = "export_phase";
    /** FIX-6: approximate composition item (see ExportListener), -1 unknown. */
    public static final String EXTRA_PROGRESS_ITEM = "progress_item";
    public static final String EXTRA_PROGRESS_ITEMS = "progress_items";
    /** FIX-6: exact staging bytes written, -1 unknown. */
    public static final String EXTRA_PROGRESS_BYTES = "progress_bytes";
    /** FIX-6: pace-measured remaining ms, -1 unknown. */
    public static final String EXTRA_PROGRESS_ETA_MS = "progress_eta_ms";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";
    public static final String EXTRA_ERROR_CLASS = "error_class";
    /** Path of the serialized project snapshot the Activity wrote for this export job. */
    public static final String EXTRA_PROJECT_SNAPSHOT_PATH = "project_snapshot_path";
    /** Boolean: export only the composed audio mix to an {@code .m4a} (no video track). */
    public static final String EXTRA_AUDIO_ONLY = "audio_only";
    /** SPEC_C: boolean — export a single frame as an image instead of a video. */
    public static final String EXTRA_SINGLE_FRAME = "single_frame";
    /** SPEC_C: long — the frame's editor-timeline time in ms (with EXTRA_SINGLE_FRAME). */
    public static final String EXTRA_FRAME_TIME_MS = "frame_time_ms";
    /** SPEC_C: boolean — the frame format, true=JPG false=PNG (default). */
    public static final String EXTRA_FRAME_JPEG = "frame_jpeg";
    /**
     * RANGE EXPORT (2026-09-24): longs, editor-timeline ms. When both are present (end > start)
     * the video export covers only [start, end); see ExportManager#exportRange.
     */
    public static final String EXTRA_RANGE_START_MS = "range_start_ms";
    public static final String EXTRA_RANGE_END_MS = "range_end_ms";

    /**
     * Cross-process "is an export running?" truth: the ongoing foreground-progress
     * notification (id {@link #NOTIFICATION_ID}) exists exactly while an export runs
     * (completion/error re-post under a different id). Works from any process — the
     * editor can't share memory with the {@code :export} process.
     */
    public static boolean isRunning(@NonNull Context context) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            for (android.service.notification.StatusBarNotification sbn
                    : nm.getActiveNotifications()) {
                if (sbn.getId() == NOTIFICATION_ID) return true;
            }
        } catch (Exception e) {
            FLog.w(TAG, "isRunning notification check failed", e);
        }
        return false;
    }

    // ── Instance fields ──────────────────────────────────────────────

    @Nullable
    private ExportManager exportManager;
    @Nullable
    private NotificationManager notificationManager;
    private boolean isExporting = false;

    /** An export waiting for the running one; its snapshot is already read (edit-immune). */
    private static final class QueuedExport {
        final FaditorProject project;
        final boolean audioOnly;
        @Nullable final String loudnessTargetName;
        @Nullable final Long frameTimeMs;
        final boolean frameJpeg;
        @Nullable final long[] range;

        QueuedExport(@NonNull FaditorProject project, boolean audioOnly,
                     @Nullable String loudnessTargetName, @Nullable Long frameTimeMs,
                     boolean frameJpeg, @Nullable long[] range) {
            this.project = project;
            this.audioOnly = audioOnly;
            this.loudnessTargetName = loudnessTargetName;
            this.frameTimeMs = frameTimeMs;
            this.frameJpeg = frameJpeg;
            this.range = range;
        }
    }

    private final java.util.ArrayDeque<QueuedExport> queue = new java.util.ArrayDeque<>();
    private long exportStartTimeMs;

    /**
     * Set the moment an export completes, fails or is cancelled. Progress and phase callbacks
     * can still arrive after that (a queued poll, the chunk driver's adapter); each of them
     * re-posted the "running" notification (id {@link #NOTIFICATION_ID}), and {@link #isRunning}
     * reads that notification — so a failed export (2026-09-23, sound pass ENOENT) left a zombie
     * "88%" that told the editor an export was still going and hid the failure from the user.
     */
    private volatile boolean terminal = false;

    /**
     * Keeps the CPU running while an export is in flight. A foreground service keeps the
     * PROCESS alive but does not stop the phone suspending: measured on JoyRaptor's Note 20
     * (2026-09-23), a busy process with the screen off was asleep 30-45% of the wall clock and
     * ran at half speed, back to 100% the instant the screen woke. Every long export that
     * "flew, then crawled" crawled from the moment the screen timed out (5 min on that phone).
     * Released in onDestroy, which every terminal path (done, error, cancel) reaches through
     * stopSelf; the timeout is only a backstop so a wedged export can never drain the battery.
     */
    @Nullable private android.os.PowerManager.WakeLock exportWakeLock;
    private static final long EXPORT_WAKELOCK_MAX_MS = 8L * 60L * 60L * 1000L;

    private void acquireExportWakeLock() {
        try {
            if (exportWakeLock == null) {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                exportWakeLock = pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK, "JoyCreator:export");
                exportWakeLock.setReferenceCounted(false);
            }
            exportWakeLock.acquire(EXPORT_WAKELOCK_MAX_MS);
            FLog.d(TAG, "Export wake lock held");
        } catch (Exception e) {
            FLog.w(TAG, "Export wake lock unavailable — export will slow if the screen turns off", e);
        }
    }

    private void releaseExportWakeLock() {
        try {
            if (exportWakeLock != null && exportWakeLock.isHeld()) {
                exportWakeLock.release();
                FLog.d(TAG, "Export wake lock released");
            }
        } catch (Exception ignored) { }
    }
    /**
     * FIX-2: phase label overriding the notification text while set (e.g. the cache-warm
     * phase, which remuxes GB-scale files for minutes). Cleared when the real export starts.
     */
    @Nullable
    private String progressTextOverride = null;
    /** Single-thread executor used to warm the fMP4 remux cache off the main thread. */
    @Nullable
    private ExecutorService remuxExecutor;

    /** Package-scoped global broadcast — crosses the process boundary, never leaves the app. */
    private void sendExportBroadcast(@NonNull Intent broadcast) {
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    /**
     * FIX-7: terminal-result ledger for the editor's onResume. Broadcasts are missed
     * whenever the editor is dead; the :export process is usually dead by the time the
     * user returns, so the result must outlive both in shared prefs. The editor shows
     * it once (consumed marker) and clears it.
     */
    private void recordTerminalResult(@NonNull String status, @Nullable String outputPath,
                                      @Nullable String errorMessage,
                                      @Nullable String errorClass) {
        try {
            getSharedPreferences("faditor_export", MODE_PRIVATE).edit()
                    .putString("last_export_status", status)
                    .putString("last_export_path", outputPath)
                    .putString("last_export_error", errorMessage)
                    .putString("last_export_error_class", errorClass)
                    .putLong("last_export_time", System.currentTimeMillis())
                    .putLong("last_export_consumed", 0L)
                    .apply();
        } catch (Exception e) {
            FLog.w(TAG, "recordTerminalResult failed", e);
        }
    }

    /** FIX-7: mark the ledger consumed (called by the editor after showing it live). */
    public static void markTerminalResultConsumed(@NonNull Context context, long resultTime) {
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences("faditor_export", MODE_PRIVATE);
            if (prefs.getLong("last_export_time", 0L) == resultTime) {
                prefs.edit().putLong("last_export_consumed", resultTime).apply();
            }
        } catch (Exception ignored) {}
    }

    // ── Service lifecycle ────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        FLog.d(TAG, "Service created");
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_CANCEL_EXPORT.equals(action)) {
            cancelExport();
            return START_NOT_STICKY;
        }

        if (ACTION_START_EXPORT.equals(action)) {
            String loudName = intent.getStringExtra("extra_loudness_target");
            if (loudName == null) {
                loudName = getSharedPreferences("faditor_export", MODE_PRIVATE).getString("pending_loudness_target", "OFF");
            }
            boolean singleFrame = intent.getBooleanExtra(EXTRA_SINGLE_FRAME, false);
            long rangeStart = intent.getLongExtra(EXTRA_RANGE_START_MS, -1L);
            long rangeEnd = intent.getLongExtra(EXTRA_RANGE_END_MS, -1L);
            long[] range = rangeStart >= 0 && rangeEnd > rangeStart
                    ? new long[]{rangeStart, rangeEnd} : null;
            startExportInternal(intent.getStringExtra(EXTRA_PROJECT_SNAPSHOT_PATH),
                    intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false), loudName,
                    singleFrame ? intent.getLongExtra(EXTRA_FRAME_TIME_MS, -1L) : null,
                    intent.getBooleanExtra(EXTRA_FRAME_JPEG, false), range);
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // No binding: the service runs in its own process and all state flows via
        // package-scoped broadcasts + the foreground notification (see class doc).
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (exportManager != null && exportManager.isExporting()) {
            exportManager.cancel();
        }
        if (remuxExecutor != null) {
            remuxExecutor.shutdownNow();
            remuxExecutor = null;
        }
        releaseExportWakeLock();
        FLog.d(TAG, "Service destroyed");
    }

    // ── Export execution ─────────────────────────────────────────────

    private void startExportInternal(@Nullable String snapshotPath, boolean audioOnly) {
        startExportInternal(snapshotPath, audioOnly, null, null, false, null);
    }

    private void startExportInternal(@Nullable String snapshotPath, boolean audioOnly, @Nullable String loudnessTargetName) {
        startExportInternal(snapshotPath, audioOnly, loudnessTargetName, null, false, null);
    }

    private void startExportInternal(@Nullable String snapshotPath, boolean audioOnly,
                                     @Nullable String loudnessTargetName,
                                     @Nullable Long frameTimeMs, boolean frameJpeg,
                                     @Nullable long[] range) {
        // EDIT-SAFETY + OOP handoff in one move: the Activity serialized the project to a
        // file at export-tap time (an edit-immune deep snapshot — the same round-trip every
        // app-restart export already survives) and passed the path here. Reading it is the
        // ONLY way project data enters this process — there is no live reference to mutate.
        FaditorProject project = null;
        if (snapshotPath != null) {
            File snapshotFile = new File(snapshotPath);
            try {
                byte[] bytes = new byte[(int) snapshotFile.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(snapshotFile)) {
                    int off = 0, n;
                    while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) {
                        off += n;
                    }
                }
                com.fadcam.ui.faditor.project.ProjectStorage storage =
                        new com.fadcam.ui.faditor.project.ProjectStorage(this);
                project = storage.fromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                FLog.e(TAG, "Failed to read export snapshot " + snapshotPath, e);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                snapshotFile.delete();
            }
        }

        if (project == null) {
            FLog.e(TAG, "No export snapshot (path=" + snapshotPath + ") — cannot export");
            Intent broadcast = new Intent(ACTION_EXPORT_ERROR);
            broadcast.putExtra(EXTRA_ERROR_MESSAGE, "Export could not read the project snapshot");
            sendExportBroadcast(broadcast);
            // Never stop the service under an export that is still running (onDestroy
            // cancels it): only a bad SECOND request arrives while one runs.
            if (!isExporting && queue.isEmpty()) stopSelf();
            return;
        }
        FLog.d(TAG, "Export snapshot loaded from " + snapshotPath
                + " — concurrent edits cannot affect this export");

        if (isExporting) {
            // Was: "already in progress" and the request (its snapshot already deleted above)
            // silently vanished. Now it waits its turn.
            queue.add(new QueuedExport(project, audioOnly, loudnessTargetName, frameTimeMs,
                    frameJpeg, range));
            FLog.i(TAG, "Export queued behind the running one (" + queue.size() + " waiting)");
            Intent queued = new Intent(ACTION_EXPORT_QUEUED);
            queued.putExtra(EXTRA_QUEUE_SIZE, queue.size());
            sendExportBroadcast(queued);
            return;
        }
        startExportForProject(project, audioOnly, loudnessTargetName, frameTimeMs, frameJpeg,
                range);
    }

    /** Start {@code project} now (nothing else is running). */
    private void startExportForProject(@NonNull FaditorProject project, boolean audioOnly,
                                       @Nullable String loudnessTargetName,
                                       @Nullable Long frameTimeMs, boolean frameJpeg,
                                       @Nullable long[] range) {
        isExporting = true;
        exportStartTimeMs = System.currentTimeMillis();

        terminal = false;
        // Show initial foreground notification
        startForeground(NOTIFICATION_ID, buildProgressNotification(0, true));
        acquireExportWakeLock();

        // Create ExportManager and run
        SharedPreferencesManager prefsManager = SharedPreferencesManager.getInstance(this);
        exportManager = new ExportManager(this, prefsManager);
        // C4: apply pending loudness target from dialog (via intent or prefs)
        String targetName = loudnessTargetName;
        if (targetName == null) {
            targetName = getSharedPreferences("faditor_export", MODE_PRIVATE).getString("pending_loudness_target", "OFF");
        }
        if (targetName != null) {
            try {
                ExportManager.LoudnessTarget t = ExportManager.LoudnessTarget.valueOf(targetName);
                exportManager.setPendingLoudnessTarget(t);
                FLog.d(TAG, "C4 loudness target: " + t + " (" + t.lufs + " LUFS)");
            } catch (Exception e) {
                FLog.w(TAG, "Unknown loudness target: " + targetName);
            }
        }
        exportManager.setExportListener(new ExportManager.ExportListener() {
            @Override
            public void onExportStarted(@NonNull String outputPath) {
                FLog.d(TAG, "Export started → " + outputPath);
                Intent broadcast = new Intent(ACTION_EXPORT_STARTED);
                broadcast.putExtra(EXTRA_OUTPUT_PATH, outputPath);
                sendExportBroadcast(broadcast);
            }

            @Override
            public void onExportProgress(float progress) {
                if (terminal) return;
                // Old coarse path (kept for interface compat): the poller always calls
                // the detailed variant below, which carries this same broadcast.
                onExportProgressDetailed(progress, -1, -1, -1L, -1L);
            }

            @Override
            public void onExportProgressDetailed(float progress, int itemIndex,
                                                 int itemCount, long bytesWritten,
                                                 long etaRemainingMs) {
                if (terminal) return;
                int percent = (int) (progress * 100);
                lastNotifiedPercent = percent;
                lastNotifiedProgress = progress;
                updateNotificationDetailed(percent, itemIndex, itemCount,
                        bytesWritten, etaRemainingMs);
                Intent broadcast = new Intent(ACTION_EXPORT_PROGRESS);
                broadcast.putExtra(EXTRA_PROGRESS, progress);
                if (currentPhase != null) broadcast.putExtra(EXTRA_PHASE, currentPhase);
                broadcast.putExtra(EXTRA_PROGRESS_ITEM, itemIndex);
                broadcast.putExtra(EXTRA_PROGRESS_ITEMS, itemCount);
                broadcast.putExtra(EXTRA_PROGRESS_BYTES, bytesWritten);
                broadcast.putExtra(EXTRA_PROGRESS_ETA_MS, etaRemainingMs);
                sendExportBroadcast(broadcast);
            }

            @Override
            public void onChunkPhase(@NonNull String phase) {
                if (terminal) return;
                currentPhase = phase;
                // Refresh the notification line with the phase; the next progress poll
                // overwrites with full detail anyway.
                oneShotText = getString(R.string.faditor_exporting_percent,
                        lastNotifiedPercent) + " • " + phase;
                notificationManager.notify(NOTIFICATION_ID,
                        buildProgressNotification(lastNotifiedPercent, false));
                oneShotText = null;
                Intent broadcast = new Intent(ACTION_EXPORT_PROGRESS);
                broadcast.putExtra(EXTRA_PROGRESS, lastNotifiedProgress);
                broadcast.putExtra(EXTRA_PHASE, phase);
                sendExportBroadcast(broadcast);
            }

            @Override
            public void onExportFinalizing() {
                if (terminal) return;
                // FIX-6: the muxer is done; loudness/SAF still run. The notification must
                // say so instead of sitting at a stuck percent or vanishing.
                progressTextOverride = getString(R.string.faditor_export_finalizing);
                notificationManager.notify(NOTIFICATION_ID,
                        buildProgressNotification(100, false));
                sendExportBroadcast(new Intent(ACTION_EXPORT_FINALIZING));
            }

            @Override
            public void onExportCompleted(@NonNull String outputPath,
                                          @NonNull androidx.media3.transformer.ExportResult result) {
                terminal = true;
                FLog.d(TAG, "Export completed: " + outputPath);
                isExporting = false;
                recordTerminalResult("completed", outputPath, null, null);
                // FIX-1: post-export prune (30 days, 8 GB cap). The file just written is
                // minutes fresh so the age rule can never take it; failures skip this so
                // their helpers survive a retry.
                try {
                    new FragmentedMp4Remuxer(ExportService.this)
                            .pruneStaleRemuxedFiles(30, 8L * 1024 * 1024 * 1024);
                    new PreTrimCache(ExportService.this)
                            .prunePreTrims(14, 6L * 1024 * 1024 * 1024);
                } catch (Exception e) {
                    FLog.w(TAG, "Post-export cache prune failed", e);
                }
                // Remove the ongoing 3001 (it doubles as the isRunning() truth) and re-post
                // the completion under its own id.
                stopForeground(STOP_FOREGROUND_REMOVE);
                if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
                showCompletionNotification(audioOnly, outputPath);
                Intent broadcast = new Intent(ACTION_EXPORT_COMPLETED);
                broadcast.putExtra(EXTRA_OUTPUT_PATH, outputPath);
                broadcast.putExtra(EXTRA_AUDIO_ONLY, audioOnly);
                sendExportBroadcast(broadcast);
                finishOrRunNext();
            }

            @Override
            public void onExportError(@NonNull Exception error) {
                terminal = true;
                FLog.e(TAG, "Export failed", error);
                isExporting = false;
                recordTerminalResult("error", null, error.getMessage(),
                        error.getClass().getName());
                stopForeground(STOP_FOREGROUND_REMOVE);
                if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
                showErrorNotification(error.getMessage());
                Intent broadcast = new Intent(ACTION_EXPORT_ERROR);
                broadcast.putExtra(EXTRA_ERROR_MESSAGE, error.getMessage());
                broadcast.putExtra(EXTRA_ERROR_CLASS, error.getClass().getName());
                sendExportBroadcast(broadcast);
                finishOrRunNext();
            }
        });

        // Durable trace opens BEFORE warming so the remux/probe lines survive logcat
        // rotation on multi-hour exports (2026-09-22: a 2h run's PROBE lines were gone).
        try {
            exportManager.openTrace(audioOnly ? "audio" : (frameTimeMs != null ? "frame" : "video"));
        } catch (Exception ignored) {}

        // ── Warm caches off the main thread BEFORE exporting ──
        // (1) fMP4 remux cache: raw FadCam recordings (file:// fragmented MP4s) are not seekable to
        //     a non-zero start, so ExportManager's ClippingConfiguration fails on a TRIMMED clip
        //     with "Illegal clipping: not seekable to start". Remuxing (faststart) produces a
        //     seekable copy that ExportManager points at via resolveSeekableSourceUri().
        // (2) L2 reversed-segment cache: a PING_PONG clip's reverse leg must play the SAME baked
        //     reversed file the preview used, or export would silently fall back to forward-tail
        //     while preview showed true reverse. Bake any missing reverse segment here (off-main)
        //     so export never disagrees with a preview that showed true reverse.
        // Both are skipped entirely for the common case (no fMP4, no ping-pong) → export immediately.
        final FaditorProject exportProject = project;
        final List<File> needsRemux = collectSourcesNeedingRemux(exportProject);
        final List<Clip> needsReverse = collectClipsNeedingReverse(exportProject);
        // 2026-09-22 pre-trim: every non-image file:// window from a BIG source gets its
        // own small flat file, so no item boundary ever seeks deep into a GB file on a hot
        // phone. Lookup-only downstream; a missing/failed trim silently falls back.
        // Collected BEFORE the early return: the 12:17 run proved a cached remux skips
        // the whole warm phase, which silently skipped the probe AND the pre-trims too.
        final List<Clip> needsPreTrim = collectWindowsNeedingPreTrim(exportProject);
        if (!audioOnly && frameTimeMs == null
                && needsRemux.isEmpty() && needsReverse.isEmpty() && needsPreTrim.isEmpty()) {
            // Common case: nothing to warm — behave exactly as before. (Video exports
            // always warm: the decode probe is cheap insurance even with zero bakes.)
            dispatchExport(exportProject, audioOnly, frameTimeMs, frameJpeg, range);
            return;
        }

        FLog.i(TAG, "Warming caches before export: " + needsRemux.size()
                + " fMP4 remux(es), " + needsReverse.size() + " reverse bake(s), "
                + needsPreTrim.size() + " pre-trim(s)");
        progressTextOverride = getString(R.string.faditor_export_preparing);
        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(0, true));
        final FragmentedMp4Remuxer remuxer = new FragmentedMp4Remuxer(this);
        final ReversedSegmentCache reversedCache = new ReversedSegmentCache(this);
        final PreTrimCache preTrimmer = new PreTrimCache(this);
        if (remuxExecutor == null) {
            remuxExecutor = Executors.newSingleThreadExecutor();
        }
        remuxExecutor.execute(() -> {
            // FIX-2 (2026-09-21): verify AFTER warming — a remux that fails (or whose
            // output vanishes, cf. the 11:32 ENOENT) must fail FAST here with a retryable
            // message, never 28 minutes into the export. One synchronous retry; the
            // thread is already background.
            final List<String> unpreparable = new ArrayList<>();
            for (File f : needsRemux) {
                try {
                    File out = remuxer.remuxSync(f);
                    if (out == null || !remuxer.hasRemuxedVersion(f)) {
                        FLog.w(TAG, "Remux missing for " + f.getName() + " — retrying once");
                        out = remuxer.remuxSync(f);
                    }
                    if (out == null || !remuxer.hasRemuxedVersion(f)) {
                        FLog.e(TAG, "Remux unrecoverable for " + f.getName());
                        unpreparable.add(f.getName());
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Remux threw for " + f.getName(), e);
                    unpreparable.add(f.getName());
                }
            }
            // Reverse bakes AFTER remuxing, so a raw-fMP4 ping-pong source reverses from its
            // now-cached seekable copy (accurate fast-seek), mirroring the preview input choice.
            for (Clip c : needsReverse) {
                try {
                    File input = resolveReverseInputFile(c, remuxer);
                    File out = reversedCache.bakeSync(c.getSourceUri(), input,
                            c.getInPointMs(), c.getOutPointMs());
                    if (out == null) {
                        FLog.w(TAG, "Reverse bake unavailable for a ping-pong clip — export will "
                                + "forward-tail that leg (matches an un-baked preview)");
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Reverse bake threw for a ping-pong clip", e);
                }
            }
            // 2026-09-22 pre-trim bakes AFTER remuxing (bakes cut FROM the flat remux
            // when one applies — fast indexed seek for ffmpeg's own -ss). Failures are
            // non-fatal by design: the export falls back to the resolved URI.
            // Single-frame exports skip bakes AND probe: one frame needs neither.
            final boolean fullProbe = frameTimeMs == null;
            for (Clip c : needsPreTrim) {
                if (!fullProbe) break;
                try {
                    File input = resolvePreTrimInputFile(c, remuxer);
                    if (input == null) continue;
                    File out = preTrimmer.bakeSync(input, c.getInPointMs(), c.getOutPointMs());
                    if (out == null) {
                        FLog.w(TAG, "Pre-trim unavailable for a clip window — export will "
                                + "seek the source file for that item (today's behavior)");
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Pre-trim threw for a clip window", e);
                }
            }
            // FIX-3: cold-baseline decode probe on the same background thread, AFTER
            // the remuxes it reads are in place. Minutes here save a 74-minute stall.
            ExportManager.WindowProbeFailure probeFailure = null;
            try {
                if (fullProbe && exportManager != null) {
                    probeFailure = exportManager.probeClipWindows(exportProject);
                }
            } catch (Exception e) {
                FLog.w(TAG, "Probe threw (proceeding to export anyway)", e);
            }
            final ExportManager.WindowProbeFailure probeFailed = probeFailure;
            // Hand back to the main thread to start the export (ExportManager
            // runs its Transformer on the main thread, as today) — or fail fast when
            // a source could not be prepared (FIX-2: never a 28-minute delayed ENOENT).
            final List<String> failed = new ArrayList<>(unpreparable);
            new Handler(Looper.getMainLooper()).post(() -> {
                progressTextOverride = null;
                if (probeFailed != null) {
                    isExporting = false;
                    String srcName = probeFailed.uri.substring(
                            probeFailed.uri.lastIndexOf('/') + 1);
                    long srcSec = probeFailed.inMs / 1000;
                    String msg = "clip " + (probeFailed.clipIndex + 1) + " could not be read"
                            + " at " + (srcSec / 60) + ":" + String.format("%02d", srcSec % 60)
                            + " in " + srcName + " (" + probeFailed.reason + ")."
                            + " Let the phone cool down, keep it plugged in and retry —"
                            + " or trim around that spot. Your project is safe";
                    FLog.e(TAG, "Export pre-flight probe failed: " + msg
                            + " codec=" + probeFailed.codecName
                            + " costMs=" + probeFailed.costMs);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
                    showErrorNotification(msg);
                    Intent broadcast = new Intent(ACTION_EXPORT_ERROR);
                    broadcast.putExtra(EXTRA_ERROR_MESSAGE, msg);
                    broadcast.putExtra(EXTRA_ERROR_CLASS,
                            ExportManager.WindowProbeFailure.class.getName());
                    sendExportBroadcast(broadcast);
                    finishOrRunNext();
                    return;
                }
                if (!failed.isEmpty()) {
                    isExporting = false;
                    String msg = "helper file for " + failed.get(0)
                            + " could not be prepared — free up space and retry,"
                            + " your project is safe";
                    FLog.e(TAG, "Export preparation failed: " + msg);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
                    showErrorNotification(msg);
                    Intent broadcast = new Intent(ACTION_EXPORT_ERROR);
                    broadcast.putExtra(EXTRA_ERROR_MESSAGE, msg);
                    broadcast.putExtra(EXTRA_ERROR_CLASS,
                            java.io.IOException.class.getName());
                    sendExportBroadcast(broadcast);
                    finishOrRunNext();
                    return;
                }
                if (exportManager != null) {
                    dispatchExport(exportProject, audioOnly, frameTimeMs, frameJpeg, range);
                }
            });
        });
    }

    /**
     * SPEC_C: the ONE dispatch point for all three export kinds. A frame job carries a
     * time; a null time keeps the audio-only / video branches exactly as before.
     */
    private void dispatchExport(@NonNull FaditorProject project, boolean audioOnly,
                                @Nullable Long frameTimeMs, boolean frameJpeg,
                                @Nullable long[] range) {
        if (range != null && frameTimeMs == null && !audioOnly) {
            exportManager.exportRange(project, range[0], range[1]);
        } else if (frameTimeMs != null) {
            exportManager.exportSingleFrame(project, frameTimeMs, frameJpeg,
                    exportManager.getExportListener());
        } else if (audioOnly) {
            exportManager.exportAudioOnly(project);
        } else {
            exportManager.export(project);
        }
    }

    /**
     * Collect PING_PONG loop clips whose baked-reversed segment is missing (and bakeable). Empty
     * for the common case. Each returned clip needs an off-main reverse bake before export so the
     * reverse leg matches preview.
     */
    @NonNull
    private List<Clip> collectClipsNeedingReverse(@NonNull FaditorProject project) {
        List<Clip> result = new ArrayList<>();
        if (project.getTimeline() == null) return result;
        ReversedSegmentCache cache = new ReversedSegmentCache(this);
        for (Clip clip : project.getTimeline().getClips()) {
            if (clip.isImageClip()) continue;
            if (clip.getLoopMode() != Clip.LOOP_MODE_PING_PONG || !clip.hasLoopExtension()) continue;
            long in = clip.getInPointMs();
            long out = clip.getOutPointMs();
            if (!ReversedSegmentCache.canBake(in, out)) continue; // guard: too long → forward-tail
            if (!cache.isCached(clip.getSourceUri(), in, out)) result.add(clip);
        }
        return result;
    }

    /**
     * 2026-09-22: windows worth pre-trimming — non-image {@code file://} clips from BIG
     * sources (small files seek fine; the 100 MB gate keeps ordinary projects trim-free)
     * whose baked window is missing. Lookup hits are skipped here AND downstream.
     */
    @NonNull
    private List<Clip> collectWindowsNeedingPreTrim(@NonNull FaditorProject project) {
        List<Clip> result = new ArrayList<>();
        if (project.getTimeline() == null) return result;
        FragmentedMp4Remuxer remuxer = new FragmentedMp4Remuxer(this);
        PreTrimCache cache = new PreTrimCache(this);
        for (Clip clip : project.getTimeline().getClips()) {
            if (clip.isImageClip()) continue;
            Uri uri = clip.getSourceUri();
            if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) continue;
            long in = clip.getInPointMs();
            long out = clip.getOutPointMs();
            if (out <= in) continue;
            try {
                File input = resolvePreTrimInputFile(clip, remuxer);
                if (input == null || input.length() < 100L * 1024 * 1024) continue;
                if (!cache.hasPreTrim(input, in, out)) result.add(clip);
            } catch (Exception e) {
                FLog.w(TAG, "collectWindowsNeedingPreTrim: skip " + uri, e);
            }
        }
        return result;
    }

    /**
     * The on-disk file to cut a pre-trim from — the remuxed copy when one is in force
     * (flat index → fast seek), else the raw file. Mirrors resolveReverseInputFile's
     * choice so the bake reads what the export would read. Null if unresolvable.
     */
    @Nullable
    private File resolvePreTrimInputFile(@NonNull Clip clip,
                                         @NonNull FragmentedMp4Remuxer remuxer) {
        Uri uri = clip.getSourceUri();
        if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) return null;
        File raw = new File(uri.getPath());
        if (!raw.exists()) return null;
        try {
            if (remuxer.needsRemux(raw) && remuxer.hasRemuxedVersion(raw)) {
                File remuxed = remuxer.getRemuxedFile(raw);
                if (remuxed != null && remuxed.exists()) return remuxed;
            }
        } catch (Exception e) {
            FLog.w(TAG, "resolvePreTrimInputFile: falling back to raw for " + uri, e);
        }
        return raw;
    }

    /**
     * The on-disk file to feed the reverse bake for {@code clip} — a remuxed/faststart copy when
     * the source is a fragmented MP4 (accurate fast-seek), otherwise the raw file. Null if no local
     * file resolves (e.g. a content:// source that isn't a plain file path).
     */
    @Nullable
    private File resolveReverseInputFile(@NonNull Clip clip, @NonNull FragmentedMp4Remuxer remuxer) {
        Uri uri = clip.getSourceUri();
        if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) return null;
        File raw = new File(uri.getPath());
        if (!raw.exists()) return null;
        try {
            if (remuxer.needsRemux(raw) && remuxer.hasRemuxedVersion(raw)) {
                File remuxed = remuxer.getRemuxedFile(raw);
                if (remuxed != null && remuxed.exists()) return remuxed;
            }
        } catch (Exception e) {
            FLog.w(TAG, "resolveReverseInputFile: falling back to raw for " + uri, e);
        }
        return raw;
    }

    /**
     * Collect the unique {@code file://} video sources in the project that are
     * fragmented MP4s needing a remux and don't already have a cached remuxed
     * copy. Image clips are skipped. Returns an empty list for the common case
     * (normal/imported projects), in which export proceeds with no background work.
     */
    @NonNull
    private List<File> collectSourcesNeedingRemux(@NonNull FaditorProject project) {
        List<File> result = new ArrayList<>();
        if (project.getTimeline() == null) return result;
        FragmentedMp4Remuxer remuxer = new FragmentedMp4Remuxer(this);
        Set<String> seen = new LinkedHashSet<>();
        // PiP clips too: their picture and their audio open the source at the clip's in-point,
        // and a raw fragmented recording cannot seek there ("Illegal clipping: not seekable to
        // start" - Note 9, ZA_CONTROL, a PiP sliced from a recording, 2026-09-24).
        List<Clip> clips = new ArrayList<>(project.getTimeline().getClips());
        clips.addAll(project.getTimeline().getOverlayClips());
        for (Clip clip : clips) {
            if (clip.isImageClip()) continue;
            Uri uri = clip.getSourceUri();
            if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) {
                continue;
            }
            if (!seen.add(uri.getPath())) continue; // dedupe
            try {
                File f = new File(uri.getPath());
                if (remuxer.needsRemux(f) && !remuxer.hasRemuxedVersion(f)) {
                    result.add(f);
                }
            } catch (Exception e) {
                FLog.w(TAG, "collectSourcesNeedingRemux: skip " + uri, e);
            }
        }
        return result;
    }

    /**
     * The running export has ended (done or failed): start the next queued one, or stop.
     * Posted, so the finished export's callbacks unwind before the next one takes the fields.
     */
    private void finishOrRunNext() {
        final QueuedExport next = queue.poll();
        if (next == null) {
            stopSelf();
            return;
        }
        FLog.i(TAG, "Starting the next queued export (" + queue.size() + " still waiting)");
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isExporting) {   // something else started meanwhile: keep it waiting
                queue.addFirst(next);
                return;
            }
            startExportForProject(next.project, next.audioOnly, next.loudnessTargetName,
                    next.frameTimeMs, next.frameJpeg, next.range);
        });
    }

    /**
     * Cancel the running export, and everything queued behind it — cancel means "give me my
     * phone back", not "skip to the next one".
     */
    public void cancelExport() {
        if (!queue.isEmpty()) {
            FLog.i(TAG, "Cancel also drops " + queue.size() + " queued export(s)");
            queue.clear();
        }
        terminal = true;
        if (exportManager != null && exportManager.isExporting()) {
            exportManager.cancel();
            isExporting = false;
            FLog.d(TAG, "Export cancelled via service");
            sendExportBroadcast(new Intent(ACTION_EXPORT_CANCELLED));
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
                if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    // ── Notification helpers ─────────────────────────────────────────

    /** FIX-7: terminal (done/failed) notices. The old channel is IMPORTANCE_LOW and
     *  its importance is frozen on devices that already created it, so failures posted
     *  there are easy to miss (the 28-minute silent run). A fresh channel id starts at
     *  DEFAULT — visible but not intrusive. */
    private static final String ALERTS_CHANNEL_ID = "faditor_export_alerts";

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.faditor_export_notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.faditor_export_notif_channel_desc));
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                NotificationChannel alerts = new NotificationChannel(
                        ALERTS_CHANNEL_ID,
                        getString(R.string.faditor_export_notif_channel),
                        NotificationManager.IMPORTANCE_DEFAULT);
                alerts.setDescription(getString(R.string.faditor_export_notif_channel_desc));
                alerts.setShowBadge(true);
                notificationManager.createNotificationChannel(alerts);
            }
        }
    }

    @NonNull
    private Notification buildProgressNotification(int percent, boolean indeterminate) {
        // Cancel action
        Intent cancelIntent = new Intent(this, ExportService.class);
        cancelIntent.setAction(ACTION_CANCEL_EXPORT);
        PendingIntent cancelPi = PendingIntent.getService(this, 0, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Tap to open editor
        Intent openIntent = new Intent(this, FaditorEditorActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = getString(R.string.faditor_export_notif_title);
        String text;
        if (oneShotText != null) {
            text = oneShotText;
        } else if (progressTextOverride != null) {
            text = progressTextOverride;
        } else {
            text = indeterminate
                    ? getString(R.string.faditor_exporting)
                    : getString(R.string.faditor_exporting_percent, percent);
        }

        // ETA calculation — pace-measured when the manager supplies one (FIX-6),
        // else the old elapsed/progress estimate.
        if (!indeterminate && percent > 0 && oneShotText == null) {
            long remainingMs = detailEtaMs >= 0 ? detailEtaMs : elapsedEtaMs(percent);
            if (remainingMs >= 0) {
                text += " • " + formatEta(remainingMs);
            }
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export_video)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(openPi)
                .addAction(0, getString(R.string.faditor_cancel), cancelPi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void updateNotification(int percent) {
        Notification notification = buildProgressNotification(percent, false);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    /** Chunked-export phase label + last notified progress (for phase refreshes). */
    @Nullable
    private String currentPhase = null;
    private int lastNotifiedPercent = 0;
    private float lastNotifiedProgress = 0f;

    /** FIX-6 detail snapshot feeding the notification text (see buildProgressNotification). */
    private long detailEtaMs = -1L;
    private long detailBytes = -1L;
    private int detailItem = -1;
    private int detailItems = -1;

    private long elapsedEtaMs(int percent) {
        try {
            long elapsed = System.currentTimeMillis() - exportStartTimeMs;
            float progress = percent / 100f;
            if (progress <= 0) return -1L;
            return (long) (elapsed / progress) - elapsed;
        } catch (Exception e) {
            return -1L;
        }
    }

    /** FIX-6: one-shot full notification line, composed by updateNotificationDetailed. */
    @Nullable
    private String oneShotText = null;

    private void updateNotificationDetailed(int percent, int itemIndex, int itemCount,
                                            long bytesWritten, long etaRemainingMs) {
        detailEtaMs = etaRemainingMs;
        detailBytes = bytesWritten;
        detailItem = itemIndex;
        detailItems = itemCount;
        // Full line: percent + phase + exact MB + pace ETA. The thing a stuck bar never says.
        StringBuilder line = new StringBuilder(
                getString(R.string.faditor_exporting_percent, percent));
        line.append(" • ").append(currentPhase != null ? currentPhase
                : getString(R.string.faditor_export_compositing));
        if (itemIndex >= 0 && itemCount > 0) {
            line.append(" (≈").append(itemIndex + 1).append('/').append(itemCount).append(')');
        }
        if (bytesWritten >= 0) {
            line.append(" • ").append(bytesWritten / (1024 * 1024)).append(" MB");
        }
        if (etaRemainingMs >= 0) {
            line.append(" • ").append(formatEta(etaRemainingMs));
        }
        oneShotText = line.toString();
        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(percent, false));
        oneShotText = null;
    }

    private void showCompletionNotification(boolean audioOnly, @Nullable String outputPath) {
        // Tap to open editor
        Intent openIntent = new Intent(this, FaditorEditorActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // FIX-7: terminal notices go to the DEFAULT-importance alerts channel (the old
        // channel is frozen at LOW on this device) and name the finished file.
        String doneText = getString(audioOnly
                ? R.string.faditor_export_complete_summary_audio
                : R.string.faditor_export_complete_summary);
        if (outputPath != null) {
            String name = new File(outputPath).getName();
            doneText = name + " • " + doneText;
        }
        Notification notification = new NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export_video)
                .setContentTitle(getString(R.string.faditor_export_complete_title))
                .setContentText(doneText)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build();

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID_DONE, notification);
        }
    }

    private void showErrorNotification(@Nullable String errorMsg) {
        Notification notification = new NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export_video)
                .setContentTitle(getString(R.string.faditor_export_notif_error_title))
                .setContentText(errorMsg != null ? errorMsg
                        : getString(R.string.faditor_export_error, "Unknown error"))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(errorMsg != null
                        ? errorMsg
                        : getString(R.string.faditor_export_error, "Unknown error")))
                .setOngoing(false)
                .setAutoCancel(true)
                .build();

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID_DONE, notification);
        }
    }

    @NonNull
    private String formatEta(long remainingMs) {
        long seconds = remainingMs / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
    }
}
