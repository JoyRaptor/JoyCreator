package com.fadcam.vislab.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.VisLab;
import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.gl.VisRenderer;
import com.fadcam.vislab.model.VisScene;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * VisLab looks inside Studio (VISLAB_SPEC section 7, handoff tasks/VISLAB_HANDOFF_20261018_STUDIO.md):
 * one way to turn "this look, this item, this moment" into a picture, used by BOTH Studio's
 * preview and its export, so the video comes out as it was seen.
 *
 * <ul>
 *   <li>{@link #renderBlocking}: export. Waits for the picture; returns a NEW Bitmap the caller
 *       draws and recycles (Studio's export loop does exactly that with its own visualisers).</li>
 *   <li>{@link #previewFrame}: preview. Never waits: returns the newest finished picture for
 *       that item (or null), asks for the requested moment, and calls {@code onReady} on the main
 *       thread when it is there (invalidate the view).</li>
 * </ul>
 * Each item (a {@code key}) keeps its own {@link VisRenderer} (particles and trails have memory)
 * and {@link SoundClock}. The look draws see-through with soft edges, so it sits on the video
 * like Studio's own visualisers.
 *
 * <p>Status 10-18: built and compiled, NOT yet wired into Studio's preview / export or tested
 * on the phone; the wiring is S3 in the handoff.</p>
 */
public final class StudioVis {

    private static final String TAG = "VisStudio";
    @Nullable private static StudioVis instance;

    private final Context app;
    private final OffscreenGl gl = new OffscreenGl();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Item> items = new HashMap<>();   // GL thread only
    private final Map<String, Bitmap> latest = new HashMap<>(); // guarded by itself
    private final Map<String, Request> pending = new HashMap<>(); // guarded by itself

    private static final class Item {
        final VisRenderer renderer;
        String lookJson;
        @Nullable Uri uri;
        @Nullable SoundClock sound;
        double lastTime = Double.NaN;

        Item(@NonNull VisRenderer r) {
            renderer = r;
        }
    }

    private static final class Request {
        final String lookJson;
        @Nullable final Uri uri;
        final long sourceMs, timelineMs;
        final int w, h;

        Request(String lookJson, @Nullable Uri uri, long sourceMs, long timelineMs, int w, int h) {
            this.lookJson = lookJson;
            this.uri = uri;
            this.sourceMs = sourceMs;
            this.timelineMs = timelineMs;
            this.w = w;
            this.h = h;
        }
    }

    private StudioVis(@NonNull Context c) {
        app = c.getApplicationContext();
    }

    @NonNull
    public static synchronized StudioVis get(@NonNull Context c) {
        if (instance == null) instance = new StudioVis(c);
        return instance;
    }

    /**
     * Export: the look for item {@code key} at this moment, {@code w} x {@code h}. Blocks (up to
     * 2 s). Returns a new Bitmap, or null when VisLab cannot draw here (no ES 3, a broken look).
     * Never call on the main thread while the main thread waits for you.
     */
    @Nullable
    public Bitmap renderBlocking(@NonNull String key, @NonNull String lookJson, @Nullable Uri uri,
                                 long sourceMs, long timelineMs, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        final Bitmap[] out = new Bitmap[1];
        CountDownLatch done = new CountDownLatch(1);
        Request r = new Request(lookJson, uri, sourceMs, timelineMs, w, h);
        gl.handler().post(() -> {
            try {
                out[0] = draw(key, r);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(2, TimeUnit.SECONDS)) Log.w(TAG, "render timed out for " + key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    /**
     * Preview: the newest finished picture for {@code key} (null before the first), and a request
     * for this moment. Only the newest request per item is kept. {@code onReady} runs on the main
     * thread when the new picture is in. The returned Bitmap belongs to StudioVis: draw it, do
     * not recycle or keep it.
     */
    @Nullable
    public Bitmap previewFrame(@NonNull String key, @NonNull String lookJson, @Nullable Uri uri,
                               long sourceMs, long timelineMs, int w, int h, @Nullable Runnable onReady) {
        boolean queue;
        synchronized (pending) {
            queue = !pending.containsKey(key);
            pending.put(key, new Request(lookJson, uri, sourceMs, timelineMs, w, h));
        }
        if (queue) {
            gl.handler().post(() -> {
                Request r;
                synchronized (pending) {
                    r = pending.remove(key);
                }
                if (r == null) return;
                Bitmap b = draw(key, r);
                if (b == null) return;
                Bitmap old;
                synchronized (latest) {
                    old = latest.put(key, b);
                }
                main.post(() -> {
                    // The old picture may still be on screen until this frame's draw: recycle after.
                    if (old != null && old != b) old.recycle();
                    if (onReady != null) onReady.run();
                });
            });
        }
        synchronized (latest) {
            return latest.get(key);
        }
    }

    /** Forget every item's renderer and picture (the editor closed). */
    public void releaseAll() {
        gl.handler().post(() -> {
            for (Item it : items.values()) it.renderer.release();
            items.clear();
        });
        synchronized (latest) {
            latest.clear();
        }
    }

    // ------------------------------------------------------------------ GL thread

    @Nullable
    private Bitmap draw(@NonNull String key, @NonNull Request r) {
        if (!gl.ready()) return null;
        try {
            Item it = items.get(key);
            if (it == null) {
                VisRenderer vr = VisLab.get(app).newRenderer();
                vr.init();
                vr.setTransparent(true);   // sits on the video, like Studio's own visualisers
                vr.setForceFrame(1);       // soft edges inside the item's box, never a hard seam
                vr.setWords(null);         // S5: the transcript's words
                it = new Item(vr);
                items.put(key, it);
            }
            if (!r.lookJson.equals(it.lookJson)) {
                it.renderer.setScene(VisScene.parse(r.lookJson));
                it.lookJson = r.lookJson;
            }
            if (it.sound == null || (r.uri != null && !r.uri.equals(it.uri))) {
                it.uri = r.uri;
                it.sound = r.uri != null ? new SoundClock(app, r.uri) : null;
            }
            Features f = it.sound != null ? it.sound.at(r.sourceMs / 1000.0) : new Features();
            double t = r.timelineMs / 1000.0;
            double dt = Double.isNaN(it.lastTime) ? 1.0 / 30 : Math.max(0, Math.min(0.1, t - it.lastTime));
            it.lastTime = t;
            int fbo = gl.framebuffer(r.w, r.h);
            it.renderer.render(f, t, dt, fbo, r.w, r.h);
            Bitmap out = Bitmap.createBitmap(r.w, r.h, Bitmap.Config.ARGB_8888);
            gl.readInto(out);
            return out;
        } catch (RuntimeException e) {
            Log.w(TAG, "VisLab look failed to draw for " + key, e);
            return null;
        }
    }
}
