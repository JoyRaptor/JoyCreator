package cc.joycreator.textlab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * The check screen's test of Studio's two ways of asking for a title's look (adb {@code --es studio <look>}), through
 * {@link StudioTextLab} exactly as Studio calls it: the preview while playing (the title box asks for the playhead's
 * moment on every tick) and the export (one blocking picture per video frame).
 * Logs how many pictures a second each gets, and how a picture's time splits between drawing and reading it back.
 */
final class StudioTextLabBench {

    private static final String TAG = "TextLab";

    private StudioTextLabBench() {}

    static void run(@NonNull Context c, @NonNull String lookId, @NonNull String lookJson, @NonNull String words,
                    double previewEm, double exportEm) {
        TitleLook look = TitleLook.of("asset:" + lookId, lookId, lookJson);
        StudioTextLab s = StudioTextLab.get(c);
        new Thread(() -> {
            // warm up (shaders compile) and let the GPU's clock settle
            s.renderBlocking(StudioTextLab.exportKey("bench"), look, words, 0, exportEm);
            for (int round = 0; round < 2; round++) {
                preview(s, look, words, previewEm, 8000);
                export(s, look, words, exportEm, 90);
            }
            Log.i(TAG, "studio bench done");
        }, "studio-bench").start();
    }

    private static void preview(StudioTextLab s, TitleLook look, String words, double em, long forMs) {
        Handler main = new Handler(Looper.getMainLooper());
        final long t0 = SystemClock.uptimeMillis();
        final int[] got = {0};
        final Object done = new Object();
        StudioTextLab.split(true);
        Runnable[] ask = new Runnable[1];
        ask[0] = () -> {
            long now = SystemClock.uptimeMillis();
            if (now - t0 > forMs) {
                synchronized (done) {
                    done.notifyAll();
                }
                return;
            }
            // playback: the title box asks for the playhead's moment on every tick (60 a second), whether or not the
            // last picture is in; the pictures come back on the main thread
            s.previewFrame(StudioTextLab.previewKey("bench"), look, words, now - t0, em, () -> got[0]++);
            main.postDelayed(ask[0], 16);
        };
        main.post(ask[0]);
        synchronized (done) {
            try {
                done.wait(forMs + 3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        double secs = (SystemClock.uptimeMillis() - t0) / 1000.0;
        Log.i(TAG, String.format(Locale.ROOT, "studio preview em %.0f: %.1f pictures a second; %s", em, got[0] / secs,
                StudioTextLab.split(false)));
        StudioTextLab.releasePreviewIfRunning();
    }

    private static void export(StudioTextLab s, TitleLook look, String words, double em, int frames) {
        StudioTextLab.split(true);
        long a = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < frames; i++) {
            StudioTextLab.Frame f = s.renderBlocking(StudioTextLab.exportKey("bench"), look, words, 1000 + i * 1000L / 30, em);
            if (f != null) f.bitmap.recycle();   // an export owns its pictures
        }
        double ms = (SystemClock.elapsedRealtimeNanos() - a) / 1e6 / frames;
        Log.i(TAG, String.format(Locale.ROOT, "studio export em %.0f: %.1f ms a video frame; %s", em, ms, StudioTextLab.split(false)));
    }
}
