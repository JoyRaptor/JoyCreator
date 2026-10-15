package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.AIJobService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Joybot's work, kept alive after the chat closes.
 *
 * <p>Owner, 2026-10-14: closing the chat while a long transcription ran stopped it. The chat used
 * its own worker, shut down with the screen. Jobs now run here, on one app-wide worker, under the
 * foreground notification ({@link AIJobService}) so Android keeps the process alive; their results
 * go to the chat if it is open, or wait for it ("While you were away…") if not. Writes made after
 * the chat closed land in the editor's open project through {@link JoybotStorage}.</p>
 */
public final class JoybotJobs {

    private JoybotJobs() {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "joybot-jobs");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A finished job the chat has not shown yet. */
    public static final class Done {
        @NonNull public final String projectId;
        @NonNull public final Idea idea;
        @NonNull public final DirectRunner.Result result;

        Done(@NonNull String projectId, @NonNull Idea idea, @NonNull DirectRunner.Result result) {
            this.projectId = projectId;
            this.idea = idea;
            this.result = result;
        }
    }

    public interface Listener {
        /** Progress for the status line (main thread). */
        void onProgress(@NonNull String task, int percent);
        /** The job finished (main thread). Return false if the screen can't show it now. */
        boolean onDone(@NonNull Done done);
    }

    @Nullable private static Listener listener;
    private static final List<Done> waiting = new ArrayList<>();
    private static volatile boolean running;
    private static volatile boolean cancelled;

    /** The chat attaches while it is open; null when it closes. */
    public static synchronized void attach(@Nullable Listener l) {
        listener = l;
    }

    public static boolean isRunning() { return running; }

    /** Stop between steps (a step already under way finishes). */
    public static void cancel() { cancelled = true; }

    /** Jobs that finished while the chat was closed, for this project; removed once taken. */
    @NonNull
    public static synchronized List<Done> takeWaiting(@Nullable String projectId) {
        List<Done> out = new ArrayList<>();
        for (int i = waiting.size() - 1; i >= 0; i--) {
            if (waiting.get(i).projectId.equals(projectId)) out.add(0, waiting.remove(i));
        }
        return out;
    }

    /** Run an idea's steps (see {@link DirectRunner}) where closing the chat can't stop it. */
    public static void run(@NonNull Context context, @NonNull String projectId, @NonNull Idea idea) {
        final Context app = context.getApplicationContext();
        cancelled = false;
        running = true;
        AIJobService.start(app, "Joybot: " + idea.label);
        WORKER.execute(() -> {
            // Writes that reach the editor while it is on screen are one undo step, named like this.
            AIChatState.liveWriteLabel = "Joybot: " + idea.undoLabel;
            AIChatState.liveWriteRequest = UUID.randomUUID().toString();
            DirectRunner.Result r;
            try {
                r = DirectRunner.run(app, projectId, idea, (task, percent) -> {
                    AIJobService.update(app, "Joybot: " + task, percent);
                    MAIN.post(() -> {
                        Listener l = listener;
                        if (l != null) l.onProgress(task, percent);
                    });
                }, () -> cancelled);
            } catch (Throwable t) {
                FLog.e("JoybotJobs", "job failed: " + idea.label, t);
                r = new DirectRunner.Result();
                r.error = "Something went wrong: " + t.getMessage();
            }
            running = false;
            AIJobService.stop(app);
            final Done done = new Done(projectId, idea, r);
            MAIN.post(() -> deliver(done));
        });
    }

    private static synchronized void deliver(@NonNull Done done) {
        Listener l = listener;
        if (l != null && l.onDone(done)) return;
        waiting.add(done);
    }
}
