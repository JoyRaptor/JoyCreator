package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where Joybot reads and writes a project.
 *
 * <p>With the editor off screen (the chat over it, or the lobby) this is plain
 * {@link ProjectStorage}: the editor saved before it paused and reloads when it resumes. With the
 * editor ON screen — a job that kept running after the chat closed — the file is the wrong place:
 * the editor's next autosave would overwrite Joybot's change, and anything the person edited since
 * the job started would be lost to Joybot's save. So then this reads the editor's open project and
 * hands the result back to the editor, which adopts it as one undo step.</p>
 *
 * <p>Every tool already loads, changes and saves through one {@code storage} field, so swapping
 * that field is the whole integration.</p>
 */
public class JoybotStorage extends ProjectStorage {

    private static final String TAG = "JoybotStorage";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long WAIT_MS = 5000;

    public JoybotStorage(@NonNull Context context) {
        super(context);
    }

    @Nullable
    @Override
    public FaditorProject load(@NonNull String projectId) {
        AIChatState.LiveEditor live = AIChatState.liveEditorFor(projectId);
        if (live != null) {
            String json = onMain(live::snapshotJson);
            if (json != null) {
                try {
                    FaditorProject p = fromJson(json);
                    if (p != null) return p;
                } catch (Exception e) {
                    FLog.w(TAG, "live snapshot unreadable; reading the file instead", e);
                }
            }
        }
        return super.load(projectId);
    }

    @Override
    public boolean save(@NonNull FaditorProject project) {
        AIChatState.LiveEditor live = AIChatState.liveEditorFor(project.getId());
        if (live != null) {
            final String json = toJson(project);
            final String label = AIChatState.liveWriteLabel;
            final String request = AIChatState.liveWriteRequest;
            Boolean ok = onMain(() -> live.adopt(json, label, request));
            if (Boolean.TRUE.equals(ok)) return true;
            FLog.w(TAG, "live editor did not adopt Joybot's change; writing the file");
        }
        return super.save(project);
    }

    /** True when writes for this project go to the editor on screen rather than the file. */
    public static boolean isLive(@Nullable String projectId) {
        return AIChatState.liveEditorFor(projectId) != null;
    }

    private interface Call<T> { T run(); }

    @Nullable
    private static <T> T onMain(@NonNull Call<T> call) {
        if (Looper.myLooper() == Looper.getMainLooper()) return call.run();
        AtomicReference<T> out = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(() -> {
            try { out.set(call.run()); } finally { done.set(true); latch.countDown(); }
        });
        try {
            latch.await(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return done.get() ? out.get() : null;
    }
}
