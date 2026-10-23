package com.fadcam.ui.about;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.services.UpdateCheckService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ONE UPDATE CHECK, off the main thread, answered on it.
 *
 * <p>The checking itself is not here and must never be copied here: it is
 * {@link UpdateCheckService#checkForUpdate}, the very call the classic About screen, the capture
 * header's badge and the automatic check all make, against the same release feed. This class only
 * runs it in the background and hands the answer back to the page, dropping it if the page has
 * gone ({@link #cancel}).</p>
 */
final class AboutUpdateCheck {

    interface Listener {
        /** The feed answered. Check {@code hasStable} / {@code hasBeta} for what it offered. */
        void onAnswer(@NonNull UpdateCheckService.UpdateCheckResult result);

        /** The feed could not be read. */
        void onFailed();
    }

    private static final String TAG = "AboutUpdateCheck";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Bumped on every run and on cancel: an answer from an older run is ignored. */
    private int generation;
    private boolean running;

    boolean isRunning() {
        return running;
    }

    void run(@NonNull String currentVersion, @NonNull Listener listener) {
        if (running) return;
        running = true;
        final int gen = ++generation;
        IO.execute(() -> {
            UpdateCheckService.UpdateCheckResult r = null;
            try {
                r = UpdateCheckService.checkForUpdate(currentVersion);
            } catch (RuntimeException e) {
                FLog.w(TAG, "update check failed", e);
            }
            final UpdateCheckService.UpdateCheckResult answer = r;
            main.post(() -> deliver(gen, answer, listener));
        });
    }

    private void deliver(int gen, @Nullable UpdateCheckService.UpdateCheckResult r, @NonNull Listener l) {
        if (gen != generation) return;
        running = false;
        if (r == null || r.errorOccurred) l.onFailed();
        else l.onAnswer(r);
    }

    /** The page is going: forget any answer still on its way. */
    void cancel() {
        generation++;
        running = false;
    }
}
