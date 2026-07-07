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
 * Foreground service that runs video export in the background so it survives
 * Activity destruction (app minimised or closed).
 *
 * <p>The Activity starts this service, passes the project via a static bridge,
 * and binds to observe real-time progress. If the Activity is destroyed, the
 * service continues exporting and updates the system notification.</p>
 */
public class ExportService extends Service {

    private static final String TAG = "ExportService";
    private static final String CHANNEL_ID = "faditor_export_channel";
    private static final int NOTIFICATION_ID = 3001;

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

    // ── Static bridge for passing project data ───────────────────────
    @Nullable
    private static FaditorProject pendingProject;

    /**
     * Set the project to export. Must be called before starting the service.
     */
    public static void setPendingProject(@Nullable FaditorProject project) {
        pendingProject = project;
    }

    // ── Listener for Activity binding ────────────────────────────────

    /**
     * Callback interface for UI updates. Called on the main thread.
     */
    public interface ExportServiceListener {
        void onExportStarted(@NonNull String outputPath);
        void onExportProgress(float progress);
        void onExportCompleted(@NonNull String outputPath,
                               @NonNull androidx.media3.transformer.ExportResult result);
        void onExportError(@NonNull Exception error);
    }

    // ── Instance fields ──────────────────────────────────────────────

    private final IBinder binder = new ExportBinder();
    @Nullable
    private ExportServiceListener serviceListener;
    @Nullable
    private ExportManager exportManager;
    @Nullable
    private NotificationManager notificationManager;
    private boolean isExporting = false;
    private long exportStartTimeMs;
    /** Single-thread executor used to warm the fMP4 remux cache off the main thread. */
    @Nullable
    private ExecutorService remuxExecutor;

    // ── Binder ───────────────────────────────────────────────────────

    public class ExportBinder extends Binder {
        public ExportService getService() {
            return ExportService.this;
        }
    }

    public void setServiceListener(@Nullable ExportServiceListener listener) {
        this.serviceListener = listener;
    }

    public boolean isExporting() {
        return isExporting;
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
            startExportInternal();
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
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

    private void startExportInternal() {
        FaditorProject project = pendingProject;
        pendingProject = null; // consume

        if (project == null) {
            FLog.e(TAG, "No pending project — cannot export");
            stopSelf();
            return;
        }

        // EDIT-SAFETY: the activity hands us its LIVE project by reference, and the
        // editor stays reachable while we run (notification tap, minimize-to-background),
        // so any edit made mid-export would mutate the Timeline this export is reading.
        // Deep-snapshot through the project serializer — the same round-trip every
        // app-restart export already survives — so this export is immune to concurrent
        // edits. On any snapshot failure, fall back to the live reference (old behavior).
        try {
            com.fadcam.ui.faditor.project.ProjectStorage storage =
                    new com.fadcam.ui.faditor.project.ProjectStorage(this);
            FaditorProject snapshot = storage.fromJson(storage.toJson(project));
            if (snapshot != null) {
                project = snapshot;
                FLog.d(TAG, "Export project snapshotted — concurrent edits cannot affect this export");
            } else {
                FLog.w(TAG, "Export snapshot deserialize returned null — exporting live reference");
            }
        } catch (Exception e) {
            FLog.w(TAG, "Export snapshot failed — exporting live reference", e);
        }

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
                if (serviceListener != null) {
                    serviceListener.onExportStarted(outputPath);
                }
                // Broadcast to UI (FaditorMiniFragment listens via LocalBroadcastManager)
                Intent broadcast = new Intent(ACTION_EXPORT_STARTED);
                broadcast.putExtra(EXTRA_OUTPUT_PATH, outputPath);
                FLog.d(TAG, "Broadcasting ACTION_EXPORT_STARTED");
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ExportService.this)
                        .sendBroadcast(broadcast);
                FLog.d(TAG, "ACTION_EXPORT_STARTED broadcast sent");
            }

            @Override
            public void onExportProgress(float progress) {
                int percent = (int) (progress * 100);
                updateNotification(percent);
                if (serviceListener != null) {
                    serviceListener.onExportProgress(progress);
                }
                // Broadcast to UI
                Intent broadcast = new Intent(ACTION_EXPORT_PROGRESS);
                broadcast.putExtra(EXTRA_PROGRESS, progress);
                FLog.d(TAG, "Broadcasting ACTION_EXPORT_PROGRESS: " + percent + "%");
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ExportService.this)
                        .sendBroadcast(broadcast);
            }

            @Override
            public void onExportCompleted(@NonNull String outputPath,
                                          @NonNull androidx.media3.transformer.ExportResult result) {
                FLog.d(TAG, "Export completed: " + outputPath);
                isExporting = false;
                showCompletionNotification();
                if (serviceListener != null) {
                    serviceListener.onExportCompleted(outputPath, result);
                }
                // Broadcast to UI
                Intent broadcast = new Intent(ACTION_EXPORT_COMPLETED);
                broadcast.putExtra(EXTRA_OUTPUT_PATH, outputPath);
                FLog.d(TAG, "Broadcasting ACTION_EXPORT_COMPLETED");
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ExportService.this)
                        .sendBroadcast(broadcast);
                FLog.d(TAG, "ACTION_EXPORT_COMPLETED broadcast sent");
                
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }

            @Override
            public void onExportError(@NonNull Exception error) {
                FLog.e(TAG, "Export failed", error);
                isExporting = false;
                showErrorNotification(error.getMessage());
                if (serviceListener != null) {
                    serviceListener.onExportError(error);
                }
                // Broadcast to UI
                Intent broadcast = new Intent(ACTION_EXPORT_ERROR);
                broadcast.putExtra(EXTRA_ERROR_MESSAGE, error.getMessage());
                FLog.d(TAG, "Broadcasting ACTION_EXPORT_ERROR: " + error.getMessage());
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ExportService.this)
                        .sendBroadcast(broadcast);
                FLog.d(TAG, "ACTION_EXPORT_ERROR broadcast sent");
                
                stopForeground(STOP_FOREGROUND_DETACH);
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
            exportManager.export(exportProject);
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
                    exportManager.export(exportProject);
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
            
            // Broadcast cancellation to UI
            Intent broadcast = new Intent(ACTION_EXPORT_CANCELLED);
            FLog.d(TAG, "Broadcasting ACTION_EXPORT_CANCELLED");
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(broadcast);
            FLog.d(TAG, "ACTION_EXPORT_CANCELLED broadcast sent");
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

    private void showCompletionNotification() {
        // Tap to open editor
        Intent openIntent = new Intent(this, FaditorEditorActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export_video)
                .setContentTitle(getString(R.string.faditor_export_complete_title))
                .setContentText(getString(R.string.faditor_export_complete_summary))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build();

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
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
            notificationManager.notify(NOTIFICATION_ID, notification);
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
