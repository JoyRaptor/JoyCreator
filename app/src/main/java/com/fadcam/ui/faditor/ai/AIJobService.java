package com.fadcam.ui.faditor.ai;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.FLog;
import com.fadcam.R;

/**
 * Foreground service that keeps the AI assistant alive while it runs
 * long tasks (transcription, silence detection, multi-step edit jobs).
 *
 * <p>Holds a partial wake lock so the CPU stays active even when the
 * screen is off, and shows a persistent notification so the user knows
 * the AI is working. When the job finishes, a notification is posted.</p>
 */
public class AIJobService extends Service {

    private static final String TAG = "AIJobService";
    private static final String CHANNEL_ID = "fadcam_ai_channel";
    private static final int NOTIFICATION_ID = 4001;

    private PowerManager.WakeLock wakeLock;
    private String currentTask = "Working...";

    public static final String ACTION_START = "com.fadcam.AI_JOB_START";
    public static final String ACTION_UPDATE = "com.fadcam.AI_JOB_UPDATE";
    public static final String ACTION_STOP = "com.fadcam.AI_JOB_STOP";
    public static final String EXTRA_TASK = "task_description";
    public static final String EXTRA_PROGRESS = "progress";

    public static void start(Context context, @NonNull String task) {
        Intent intent = new Intent(context, AIJobService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TASK, task);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void update(Context context, @NonNull String task, int progress) {
        Intent intent = new Intent(context, AIJobService.class);
        intent.setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_TASK, task);
        intent.putExtra(EXTRA_PROGRESS, progress);
        context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AIJobService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public class AIJobBinder extends Binder {
        public AIJobService getService() { return AIJobService.this; }
    }

    private final IBinder binder = new AIJobBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        AIJobStore jobStore = new AIJobStore(this);

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            currentTask = intent.getStringExtra(EXTRA_TASK);
            if (currentTask == null) currentTask = "AI working...";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(currentTask, 0),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(currentTask, 0));
            }
            FLog.i(TAG, "AI job service started: " + currentTask);
        } else if (intent != null && ACTION_UPDATE.equals(intent.getAction())) {
            String task = intent.getStringExtra(EXTRA_TASK);
            if (task != null) currentTask = task;
            int progress = intent.getIntExtra(EXTRA_PROGRESS, -1);
            updateTask(currentTask, progress);
            jobStore.updateProgress(progress, currentTask);
        } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            jobStore.clearJob();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        } else if (intent == null) {
            // Service restarted by the system after process death (START_STICKY).
            // Check if there's a pending job to resume.
            if (jobStore.isJobActive() && !jobStore.isStale()) {
                currentTask = jobStore.getTaskDescription() != null
                        ? jobStore.getTaskDescription() : "Resuming AI job...";
                int savedProgress = jobStore.getProgress();
                FLog.i(TAG, "AI job service restarted by system — resuming: " + currentTask
                        + " (" + savedProgress + "%)");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, buildNotification(currentTask, savedProgress),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                    startForeground(NOTIFICATION_ID, buildNotification(currentTask, savedProgress));
                }
                // The actual job resumption is handled by the ChatAssistantActivity
                // when it's recreated. The service just keeps the notification alive
                // and holds the wake lock so the process doesn't get killed again.
            } else {
                // No pending job or it's stale — stop the service.
                jobStore.clearJob();
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    public void updateTask(@NonNull String task, int progress) {
        currentTask = task;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(task, progress));
    }

    public void finishJob(@NonNull String summary) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_notification)
                    .setContentTitle("AI Assistant")
                    .setContentText(summary)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
            nm.notify(NOTIFICATION_ID + 1, b.build());
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
        FLog.i(TAG, "AI job service destroyed");
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FadCam::AIJob");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(30 * 60 * 1000L); // 30 min max for long Whisper jobs
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AI Assistant", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("AI assistant background tasks");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private android.app.Notification buildNotification(@NonNull String task, int progress) {
        Intent intent = new Intent(this, com.fadcam.ui.faditor.ai.ChatAssistantActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle("AI Assistant")
                .setContentText(task)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (progress > 0) {
            b.setProgress(100, progress, progress <= 0);
        }
        return b.build();
    }
}
