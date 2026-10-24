package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.playback.FragmentedMp4Remuxer;
import com.fadcam.ui.faditor.model.FaditorProject;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One composed Studio frame — everything the exported video would show at that moment — as a
 * bitmap, without the editor on screen (audit 2026-10-18: Joybot's look only saw the raw clip,
 * so "is the title readable?" had no answer).
 *
 * <p>It is the single-frame image export ({@link ExportManager#renderFrame}) minus the file:
 * the same compositor, so what Joybot sees is what the person will export. Blocks; call it on a
 * worker. Hand it a project COPY (JoybotStorage.load gives one): the render resyncs attached
 * visualizers on it and reads it from its own thread.</p>
 */
public final class ComposedFrame {

    private ComposedFrame() {}

    private static final String TAG = "ComposedFrame";
    /** One frame is a few seconds; past this something is stuck (a hot phone, a held codec). */
    private static final long RENDER_WAIT_MS = 45_000;

    /** Why the last {@link #render} came back empty, in the person's words. */
    @NonNull public static volatile String lastProblem = "";

    /**
     * The frame at {@code timelineMs} (clamped into the video), or null with {@link #lastProblem}.
     * Raw phone recordings are made seekable first (cached; a long one can take a while once),
     * and VisLab looks get their sound, as before any export.
     */
    @Nullable
    public static Bitmap render(@NonNull Context context, @NonNull FaditorProject project,
                                long timelineMs) {
        lastProblem = "";
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("ComposedFrame.render blocks; call it off the main thread");
        }
        Context app = context.getApplicationContext();
        long total = project.getTimeline().getTotalDurationMs();
        if (total <= 0) { lastProblem = "The video is empty."; return null; }
        long at = Math.max(0, Math.min(timelineMs, total - 1));
        warm(app, project);

        HandlerThread thread = new HandlerThread("joybot-frame");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        ExportManager em = new ExportManager(app, SharedPreferencesManager.getInstance(app));
        AtomicReference<Bitmap> got = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        h.post(() -> em.renderFrame(project, at, new ExportManager.FrameCallback() {
            @Override public void onFrame(@NonNull Bitmap frame) {
                got.set(frame);
                done.countDown();
            }

            @Override public void onError(@NonNull Exception error) {
                err.set(error);
                done.countDown();
            }
        }));
        try {
            if (!done.await(RENDER_WAIT_MS, TimeUnit.MILLISECONDS)) {
                h.post(em::cancel);
                lastProblem = "Drawing that frame took too long.";
                FLog.w(TAG, "render timed out at " + at + "ms");
            } else if (err.get() != null) {
                lastProblem = "Drawing that frame failed: " + err.get().getMessage();
                FLog.w(TAG, "render failed at " + at + "ms", err.get());
            }
        } catch (InterruptedException e) {
            h.post(em::cancel);
            Thread.currentThread().interrupt();
            lastProblem = "Stopped.";
        } finally {
            // Quit after anything posted above has run; a late frame lands in `got` unread.
            thread.quitSafely();
        }
        Bitmap b = got.get();
        if (b != null && !lastProblem.isEmpty()) { b.recycle(); return null; }
        return b;
    }

    /** What ExportService does before a frame export: seekable sources, VisLab sound. */
    private static void warm(@NonNull Context app, @NonNull FaditorProject project) {
        try {
            FragmentedMp4Remuxer remuxer = new FragmentedMp4Remuxer(app);
            for (File f : ExportService.collectSourcesNeedingRemux(app, project)) {
                if (remuxer.remuxSync(f) == null) FLog.w(TAG, "remux failed for " + f.getName());
            }
        } catch (Exception e) {
            FLog.w(TAG, "remux warm-up threw (a trimmed recording may not open)", e);
        }
        try {
            com.fadcam.vislab.studio.StudioVisExport.warm(app, project.getTimeline());
        } catch (RuntimeException e) {
            FLog.w(TAG, "VisLab warm-up threw (its looks may draw deaf)", e);
        }
    }
}
