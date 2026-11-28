package com.fadcam.ui.faditor.motion.rest;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * STU-7 §K.3 rule 4: the picture at rest. When the playhead has rested for {@link #SETTLE_MS}
 * (paused, no finger down, nothing moving), the exact export frame is drawn in the background
 * and laid over the preview; anything that moves takes it away at once.
 *
 * <p>Every request carries a generation number: a late frame from an older request is
 * recycled, never shown. One worker thread, so two renders never compete for the codecs; a
 * newer request interrupts the one running, which cancels its export. A render that fails
 * leaves the live preview as it was, with no message (the person did not ask for anything).
 * The last frame is kept with its key, so coming back to rest on an unchanged frame shows it at
 * once. UI thread only, except the render itself.</p>
 */
public final class ExactFrameAtRest {

    /** How long everything must be still before the exact frame is drawn. */
    public static final long SETTLE_MS = 350;

    public interface Host {
        /** Paused, no finger down, no dialog or export running. */
        boolean atRest();
        /** What to draw for the resting playhead, or null when nothing there needs it. */
        @Nullable Job restJob();
    }

    /** One frame to draw: {@code key} names exactly what it shows (same key, same picture). */
    public record Job(@NonNull String key, @NonNull Callable<Bitmap> render) {}

    private static final String TAG = "ExactFrameAtRest";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "studio-rest-frame");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private final RestFrameView view;
    private final Host host;
    private final Runnable check = this::checkNow;
    private int generation;
    @Nullable private Future<?> running;
    @Nullable private String cachedKey;
    @Nullable private Bitmap cached;
    private boolean peeking;
    private boolean released;

    public ExactFrameAtRest(@NonNull RestFrameView view, @NonNull Host host) {
        this.view = view;
        this.host = host;
    }

    /**
     * Something moved or changed (play, scrub, seek, an edit, a preview draft, a finger): take
     * the frame away now and look again once things have been still for {@link #SETTLE_MS}.
     * Ignored while the icon is held: the peek owns the frame until it is let go.
     */
    public void poke() {
        if (released || peeking) return;
        hideNow();
        ui.removeCallbacks(check);
        ui.postDelayed(check, SETTLE_MS);
    }

    /** Take the frame away and stop looking (the editor is leaving the screen). */
    public void stop() {
        ui.removeCallbacks(check);
        peeking = false;
        hideNow();
    }

    /** §K.3 rule 2: hold the icon of an Off or Export only object: draw it now, blurred. */
    public void peek(@NonNull Job job) {
        if (released) return;
        ui.removeCallbacks(check);
        hideNow();
        peeking = true;
        start(job);
    }

    public void endPeek() {
        if (!peeking) return;
        peeking = false;
        poke();
    }

    public boolean isShowingFrame() { return view.isShowingFrame(); }

    public boolean isPeeking() { return peeking; }

    private void checkNow() {
        if (released || peeking || !host.atRest()) return;
        Job job = host.restJob();
        if (job != null) start(job);
    }

    private void hideNow() {
        generation++;
        if (running != null) {
            running.cancel(true);
            running = null;
        }
        view.clear();
    }

    private void start(@NonNull Job job) {
        if (job.key().equals(cachedKey) && cached != null && !cached.isRecycled()) {
            view.show(cached);
            return;
        }
        final int gen = ++generation;
        view.setBusy(true);
        try {
            running = worker.submit(() -> {
                Bitmap frame = null;
                try {
                    frame = job.render().call();
                } catch (Throwable t) {
                    FLog.w(TAG, "the frame at rest was not drawn", t);
                }
                final Bitmap result = frame;
                ui.post(() -> deliver(gen, job.key(), result));
            });
        } catch (java.util.concurrent.RejectedExecutionException gone) {
            view.setBusy(false);
        }
    }

    private void deliver(int gen, @NonNull String key, @Nullable Bitmap frame) {
        if (released || gen != generation) {
            if (frame != null && frame != cached) frame.recycle();
            return;
        }
        running = null;
        view.setBusy(false);
        if (frame == null) return;   // the live preview stays; never an error message
        Bitmap old = cached;
        cached = frame;
        cachedKey = key;
        view.show(frame);
        if (old != null && old != frame) old.recycle();
    }

    public void release() {
        if (released) return;
        stop();
        released = true;
        worker.shutdownNow();
        if (cached != null) cached.recycle();
        cached = null;
        cachedKey = null;
    }
}
