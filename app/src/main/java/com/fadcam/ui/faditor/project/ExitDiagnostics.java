package com.fadcam.ui.faditor.project;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Why did this app die last time?
 *
 * <p><b>The debt this pays off.</b> {@code LONGFILE_FEEDBACK_20260716.md} #1 records an ANR on the
 * 45-minute lecture project with the process at <b>1.7GB PSS / 1.8GB RSS</b>, and the OS trace
 * sitting at {@code /data/system/procexitstore/anr_…gz} — readable by the app itself, no root
 * needed. Root cause has been "owed" since 2026-07-16 for one reason: <b>nobody was there with a
 * cable when it happened.</b> The trace ages out, the session ends, and the next report starts from
 * zero. That is not a hard problem, it is an absent instrument.</p>
 *
 * <p>So this reads the OS's own post-mortem record at startup and writes any ANR/crash trace into
 * the app's files directory, where it survives and can be pulled or shared later. The memory
 * figures alone usually settle the argument: an ANR at 1.7GB is memory pressure, an ANR at 200MB is
 * a lock or a slow main-thread call, and those two want completely different fixes.</p>
 *
 * <p><b>API 30+.</b> {@code getHistoricalProcessExitReasons} does not exist before then, so on the
 * Note 9 sandbox (Android 10) this is a deliberate no-op — which is exactly why it must not be
 * *tested* only on the sandbox. It matters on the phone the ANR actually happens on.</p>
 */
public final class ExitDiagnostics {

    private ExitDiagnostics() {}

    private static final String TAG = "ExitDiag";
    private static final int MAX_REPORTED = 5;

    /** Cheap and idempotent-ish; safe to call on every editor open. */
    public static void report(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            FLog.i(TAG, "exit history needs API 30+, running " + Build.VERSION.SDK_INT + " — skipped");
            return;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ApplicationExitInfo> infos =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, MAX_REPORTED);
            if (infos == null || infos.isEmpty()) {
                FLog.i(TAG, "no recorded exits");
                return;
            }
            File outDir = new File(context.getFilesDir(), "exitlogs");
            for (ApplicationExitInfo info : infos) {
                // PSS/RSS are the whole point: they separate "died of memory" from "died of a
                // stalled main thread", which are different bugs with different fixes.
                FLog.i(TAG, "exit reason=" + reasonName(info.getReason())
                        + " status=" + info.getStatus()
                        + " importance=" + info.getImportance()
                        + " pss=" + (info.getPss() / 1024) + "MB"
                        + " rss=" + (info.getRss() / 1024) + "MB"
                        + " at=" + info.getTimestamp()
                        + " desc=" + info.getDescription());
                if (info.getReason() == ApplicationExitInfo.REASON_ANR
                        || info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    saveTrace(info, outDir);
                }
            }
        } catch (Throwable t) {
            // Diagnostics must never be the thing that breaks the app they diagnose.
            FLog.w(TAG, "exit history unavailable: " + t);
        }
    }

    /**
     * Copy the OS trace out of the exit record into our own files dir. The record is evicted as
     * newer exits push it off the list, so reading it "later" is not an option — later is exactly
     * when it is gone.
     */
    private static void saveTrace(@NonNull ApplicationExitInfo info, @NonNull File outDir) {
        File dest = new File(outDir, "anr_" + info.getTimestamp() + ".txt");
        if (dest.exists()) return;                        // already captured on a previous launch
        if (!outDir.exists() && !outDir.mkdirs()) return;
        try (InputStream in = info.getTraceInputStream()) {
            if (in == null) return;
            try (OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            FLog.i(TAG, "saved ANR trace -> " + dest.getAbsolutePath() + " (" + dest.length() + "B)");
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            FLog.w(TAG, "could not save trace: " + t);
        }
    }

    @NonNull
    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "reason(" + reason + ")";
        }
    }

    /** Absolute paths of saved traces, newest first — for a future "share diagnostics" action. */
    @NonNull
    public static File[] savedTraces(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), "exitlogs");
        File[] f = dir.listFiles();
        if (f == null) return new File[0];
        java.util.Arrays.sort(f, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return f;
    }

    @Nullable
    public static File newestTrace(@NonNull Context context) {
        File[] f = savedTraces(context);
        return f.length > 0 ? f[0] : null;
    }
}
