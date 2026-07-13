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
    
    public static final String EXTRA_OUTPUT_PATH = "output_path";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";
    /** Path of the serialized project snapshot the Activity wrote for this export job. */
    public static final String EXTRA_PROJECT_SNAPSHOT_PATH = "project_snapshot_path";
    /** Boolean: export only the composed audio mix to an {@code .m4a} (no video track). */
    public static final String EXTRA_AUDIO_ONLY = "audio_only";

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
    private long exportStartTimeMs;
    /** Single-thread executor used to warm the fMP4 remux cache off the main thread. */
    @Nullable
    private ExecutorService remuxExecutor;

    /** Package-scoped global broadcast — crosses the process boundary, never leaves the app. */
    private void sendExportBroadcast(@NonNull Intent broadcast) {
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
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
            startExportInternal(intent.getStringExtra(EXTRA_PROJECT_SNAPSHOT_PATH),
                    intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false));
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
        FLog.d(TAG, "Service destroyed");
    }

    // ── Export execution ─────────────────────────────────────────────

    private void startExportInternal(@Nullable String snapshotPath, boolean audioOnly) {
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
            stopSelf();
            return;
        }
        FLog.d(TAG, "Export snapshot loaded from " + snapshotPath
                + " — concurrent edits cannot affect this export");

        if (isExporting) {
            FLog.w(TAG, "Export already in progress");
            return;
        }

        isExporting = true;
        exportStartTimeMs = System.currentTimeMillis();

        // Show initial foreground notification
        startForeground(NOTIFICATION_ID, buildProgressNotification(0, true));

        // Create ExportManager and run
        SharedPreferencesManager prefsManager = SharedPreferencesManager.getInstance(this);
        exportManager = new ExportManager(this, prefsManager);
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
                int percent = (int) (progress * 100);
                updateNotification(percent);
                Intent broadcast = new Intent(ACTION_EXPORT_PROGRESS);
                broadcast.putExtra(EXTRA_PROGRESS, progress);
                sendExportBroadcast(broadcast);
            }

            @Override
            public void onExportCompleted(@NonNull String outputPath,
                                          @NonNull androidx.media3.transformer.ExportResult result) {
                FLog.d(TAG, "Export completed: " + outputPath);
                isExporting = false;
                // Remove the ongoing 3001 (it doubles as the isRunning() truth) and re-post
                // the completion under its own id.
                stopForeground(STOP_FOREGROUND_REMOVE);
                showCompletionNotification(audioOnly);
                Intent broadcast = new Intent(ACTION_EXPORT_COMPLETED);
                broadcast.putExtra(EXTRA_OUTPUT_PATH, outputPath);
                broadcast.putExtra(EXTRA_AUDIO_ONLY, audioOnly);
                sendExportBroadcast(broadcast);
                stopSelf();
            }

            @Override
            public void onExportError(@NonNull Exception error) {
                FLog.e(TAG, "Export failed", error);
                isExporting = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                showErrorNotification(error.getMessage());
                Intent broadcast = new Intent(ACTION_EXPORT_ERROR);
                broadcast.putExtra(EXTRA_ERROR_MESSAGE, error.getMessage());
                sendExportBroadcast(broadcast);
                stopSelf();
            }
        });

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
        if (needsRemux.isEmpty() && needsReverse.isEmpty()) {
            // Common case: nothing to warm — behave exactly as before.
            if (audioOnly) {
                exportManager.exportAudioOnly(exportProject);
            } else {
                exportManager.export(exportProject);
            }
            return;
        }

        FLog.i(TAG, "Warming caches before export: " + needsRemux.size()
                + " fMP4 remux(es), " + needsReverse.size() + " reverse bake(s)");
        final FragmentedMp4Remuxer remuxer = new FragmentedMp4Remuxer(this);
        final ReversedSegmentCache reversedCache = new ReversedSegmentCache(this);
        if (remuxExecutor == null) {
            remuxExecutor = Executors.newSingleThreadExecutor();
        }
        remuxExecutor.execute(() -> {
            for (File f : needsRemux) {
                try {
                    File out = remuxer.remuxSync(f);
                    if (out == null) {
                        FLog.w(TAG, "Remux failed for " + f.getName()
                                + " — export may fail for this trimmed source");
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Remux threw for " + f.getName(), e);
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
            // Hand back to the main thread to start the export (ExportManager
            // runs its Transformer on the main thread, as today).
            new Handler(Looper.getMainLooper()).post(() -> {
                if (exportManager != null) {
                    if (audioOnly) {
                        exportManager.exportAudioOnly(exportProject);
                    } else {
                        exportManager.export(exportProject);
                    }
                }
            });
        });
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
        for (Clip clip : project.getTimeline().getClips()) {
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
     * Cancel the running export.
     */
    public void cancelExport() {
        if (exportManager != null && exportManager.isExporting()) {
            exportManager.cancel();
            isExporting = false;
            FLog.d(TAG, "Export cancelled via service");
            sendExportBroadcast(new Intent(ACTION_EXPORT_CANCELLED));
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ── Notification helpers ─────────────────────────────────────────

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
        String text = indeterminate
                ? getString(R.string.faditor_exporting)
                : getString(R.string.faditor_exporting_percent, percent);

        // ETA calculation
        if (!indeterminate && percent > 5) {
            long elapsed = System.currentTimeMillis() - exportStartTimeMs;
            float progress = percent / 100f;
            long totalEstimated = (long) (elapsed / progress);
            long remainingMs = totalEstimated - elapsed;
            text += " • " + formatEta(remainingMs);
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

    private void showCompletionNotification(boolean audioOnly) {
        // Tap to open editor
        Intent openIntent = new Intent(this, FaditorEditorActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export_video)
                .setContentTitle(getString(R.string.faditor_export_complete_title))
                .setContentText(getString(audioOnly
                        ? R.string.faditor_export_complete_summary_audio
                        : R.string.faditor_export_complete_summary))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build();

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID_DONE, notification);
        }
    }

    private void showErrorNotification(@Nullable String errorMsg) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export_video)
                .setContentTitle(getString(R.string.faditor_export_notif_error_title))
                .setContentText(errorMsg != null ? errorMsg
                        : getString(R.string.faditor_export_error, "Unknown error"))
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
