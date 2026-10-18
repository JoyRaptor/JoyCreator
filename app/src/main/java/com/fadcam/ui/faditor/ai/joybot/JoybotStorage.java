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
            if (json == null) {
                // The editor owns the project and didn't answer in time: its file may be older than
                // what's on screen, so don't read it (audit 2026-10-18 J7). The tool reports a miss.
                FLog.w(TAG, "live editor busy; not reading a possibly older file");
                return null;
            }
            try {
                FaditorProject p = fromJson(json);
                if (p != null) return p;
            } catch (Exception e) {
                FLog.w(TAG, "live snapshot unreadable; reading the file instead", e);
            }
        }
        return super.load(projectId);
    }

    /**
     * Every write Joybot makes, live or to the file. "Did this tool change the project?" is asked
     * of THIS, not of a before/after diff of the file: the screen Joybot was opened from can save
     * its own copy while a read-only tool runs, and a diff then showed a false "Changed: … Undo"
     * card after nothing but a look (Note 9, 2026-10-15 evening, twice).
     */
    private static final java.util.concurrent.atomic.AtomicLong WRITES = new java.util.concurrent.atomic.AtomicLong();

    public static long writes() { return WRITES.get(); }

    /** Saves that did NOT happen; a tool that hit one must not report success (audit J6). */
    private static final java.util.concurrent.atomic.AtomicLong FAILS = new java.util.concurrent.atomic.AtomicLong();

    public static long saveFailures() { return FAILS.get(); }

    /**
     * Counts as a write only once it really saved. While the editor is on screen it owns the
     * project: a change it doesn't take is a failed save, never a file written behind its back
     * (its next autosave would undo it, or it would undo the person's newer edits, audit J7).
     */
    @Override
    public boolean save(@NonNull FaditorProject project) {
        AIChatState.LiveEditor live = AIChatState.liveEditorFor(project.getId());
        boolean ok;
        if (live != null) {
            final String json = toJson(project);
            final String label = AIChatState.liveWriteLabel;
            final String request = AIChatState.liveWriteRequest;
            ok = Boolean.TRUE.equals(onMain(() -> live.adopt(json, label, request)));
            if (!ok && AIChatState.liveEditorFor(project.getId()) == null) {
                ok = super.save(project);   // the editor closed meanwhile: the file is the owner again
            } else if (!ok) {
                FLog.w(TAG, "live editor did not take Joybot's change (busy or refused)");
            }
        } else {
            ok = super.save(project);
        }
        if (ok) WRITES.incrementAndGet();
        else FAILS.incrementAndGet();
        return ok;
    }

    /** True when writes for this project go to the editor on screen rather than the file. */
    public static boolean isLive(@Nullable String projectId) {
        return AIChatState.liveEditorFor(projectId) != null;
    }

    private interface Call<T> { T run(); }

    /**
     * Runs {@code call} on the main thread and waits. If it hasn't STARTED within WAIT_MS it is
     * withdrawn — it will never run late (a late adopt replaced newer edits, audit J7) — and null
     * comes back. Once started it is waited for: it is short, and its result is real.
     */
    @Nullable
    private static <T> T onMain(@NonNull Call<T> call) {
        if (Looper.myLooper() == Looper.getMainLooper()) return call.run();
        AtomicReference<T> out = new AtomicReference<>();
        // 0 = waiting, 1 = running, 2 = withdrawn
        java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(() -> {
            if (!state.compareAndSet(0, 1)) return;
            try { out.set(call.run()); } finally { latch.countDown(); }
        });
        try {
            if (latch.await(WAIT_MS, TimeUnit.MILLISECONDS)) return out.get();
            if (state.compareAndSet(0, 2)) return null;   // never started: withdrawn for good
            latch.await();                                  // started: let it finish
            return out.get();
        } catch (InterruptedException ie) {
            state.compareAndSet(0, 2);
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
