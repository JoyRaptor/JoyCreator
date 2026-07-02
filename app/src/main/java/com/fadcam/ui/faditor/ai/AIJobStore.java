package com.fadcam.ui.faditor.ai;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * Persistent store for AI job state, so long-running jobs (transcription,
 * silence detection, AI enhance) can be resumed after the process is killed.
 *
 * <p>Uses SharedPreferences to survive process death. The foreground
 * {@link AIJobService} checks this store on restart and resumes the
 * pending job if one exists.</p>
 */
public class AIJobStore {

    private static final String PREFS = "fadcam_ai_jobs";
    private static final String KEY_JOB_ACTIVE = "job_active";
    private static final String KEY_JOB_TOOL = "job_tool";
    private static final String KEY_JOB_PROJECT_ID = "job_project_id";
    private static final String KEY_JOB_ARGS = "job_args";
    private static final String KEY_JOB_PROGRESS = "job_progress";
    private static final String KEY_JOB_STARTED_AT = "job_started_at";
    private static final String KEY_JOB_TASK = "job_task";

    private final SharedPreferences prefs;

    public AIJobStore(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Save a pending job so it can be resumed after process death. */
    public void saveJob(@NonNull String tool, @NonNull String projectId,
                        @Nullable JSONObject args, @NonNull String taskDesc) {
        prefs.edit()
                .putBoolean(KEY_JOB_ACTIVE, true)
                .putString(KEY_JOB_TOOL, tool)
                .putString(KEY_JOB_PROJECT_ID, projectId)
                .putString(KEY_JOB_ARGS, args != null ? args.toString() : null)
                .putInt(KEY_JOB_PROGRESS, 0)
                .putLong(KEY_JOB_STARTED_AT, System.currentTimeMillis())
                .putString(KEY_JOB_TASK, taskDesc)
                .apply();
    }

    /** Update the progress of the current job. */
    public void updateProgress(int percent, @Nullable String taskDesc) {
        SharedPreferences.Editor ed = prefs.edit()
                .putInt(KEY_JOB_PROGRESS, percent);
        if (taskDesc != null) ed.putString(KEY_JOB_TASK, taskDesc);
        ed.apply();
    }

    /** Mark the job as complete (clears the active flag). */
    public void clearJob() {
        prefs.edit().clear().apply();
    }

    public boolean isJobActive() {
        return prefs.getBoolean(KEY_JOB_ACTIVE, false);
    }

    @Nullable
    public String getTool() {
        return prefs.getString(KEY_JOB_TOOL, null);
    }

    @Nullable
    public String getProjectId() {
        return prefs.getString(KEY_JOB_PROJECT_ID, null);
    }

    @Nullable
    public JSONObject getArgs() {
        String s = prefs.getString(KEY_JOB_ARGS, null);
        if (s == null || s.isEmpty()) return null;
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    public int getProgress() {
        return prefs.getInt(KEY_JOB_PROGRESS, 0);
    }

    public long getStartedAt() {
        return prefs.getLong(KEY_JOB_STARTED_AT, 0);
    }

    @Nullable
    public String getTaskDescription() {
        return prefs.getString(KEY_JOB_TASK, null);
    }

    /** Check if the stored job is stale (older than 30 minutes). */
    public boolean isStale() {
        long started = getStartedAt();
        if (started == 0) return true;
        return System.currentTimeMillis() - started > 30 * 60 * 1000L;
    }
}
