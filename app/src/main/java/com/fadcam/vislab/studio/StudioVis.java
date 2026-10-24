package com.fadcam.vislab.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.VisLab;
import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.gl.VisRenderer;
import com.fadcam.vislab.model.VisScene;

import java.util.HashMap;
import java.util.Iterator;
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
 * <p>Keys: the preview uses {@link #previewKey}, the export {@link #exportKey}, so an export
 * running after the editor closed keeps its own renderers. An item not drawn for
 * {@link #IDLE_MS} lets go of its renderer (an ended export, a deleted item); its last preview
 * picture stays until {@link #releasePreview}.</p>
 */
public final class StudioVis {

    private static final String TAG = "VisStudio";
    /** An item not drawn for this long lets go of its renderer and sound (GL thread). */
    private static final long IDLE_MS = 20_000;
    @Nullable private static StudioVis instance;

    private final Context app;
    private final OffscreenGl gl = new OffscreenGl();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Item> items = new HashMap<>();   // GL thread only
    private final Map<String, Bitmap> latest = new HashMap<>(); // guarded by itself
    private final Map<String, Request> pending = new HashMap<>(); // guarded by itself
    /** The last moment the preview asked for, per item (guarded by {@link #pending}). */
    private final Map<String, Request> asked = new HashMap<>();
    private boolean sweeping;   // GL thread

    private static final class Item {
        final VisRenderer renderer;
        String lookJson;
        @Nullable Uri uri;
        @Nullable SoundClock sound;
        double lastTime = Double.NaN;
        long usedAt;
        /** The clip's transcript words for the look's Words layers (S5), and their key. */
        @Nullable com.fadcam.vislab.words.WordFeed feed;
        long wordsKey;

        void release() {
            renderer.release();
            if (feed != null) feed.release();   // safe twice: the renderer may hold it too
        }

        Item(@NonNull VisRenderer r) {
            renderer = r;
        }
    }

    private static final class Request {
        final String lookJson;
        @Nullable final Uri uri;
        final long sourceMs, timelineMs;
        final int w, h;
        @NonNull final VisControls ctl;
        @Nullable final StudioWords words;

        Request(String lookJson, @Nullable Uri uri, long sourceMs, long timelineMs, int w, int h,
                @NonNull VisControls ctl, @Nullable StudioWords words) {
            this.words = words;
            this.lookJson = lookJson;
            this.uri = uri;
            this.sourceMs = sourceMs;
            this.timelineMs = timelineMs;
            this.w = w;
            this.h = h;
            this.ctl = ctl;
        }

        boolean same(@NonNull Request o) {
            return sourceMs == o.sourceMs && timelineMs == o.timelineMs && w == o.w && h == o.h
                    && ctl.same(o.ctl) && wordsKey(words) == wordsKey(o.words)
                    && (uri == null ? o.uri == null : uri.equals(o.uri))
                    && lookJson.equals(o.lookJson);
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

    /** The key for an item's picture in the editor's preview. */
    @NonNull
    public static String previewKey(@NonNull String itemId) {
        return "preview:" + itemId;
    }

    /** The key for an item's picture in an export (its own renderer: the preview may be live). */
    @NonNull
    public static String exportKey(@NonNull String itemId) {
        return "export:" + itemId;
    }

    /**
     * Export: the look for item {@code key} at this moment, {@code w} x {@code h}, with the item's
     * VisLab values {@code ctl} (React, Bright, Colour, Size, Speed) and its clip's words. Blocks (up to 2 s). Returns a new
     * Bitmap, or null when VisLab cannot draw here (no ES 3, a broken look). Never call on the
     * main thread while the main thread waits for you.
     */
    @Nullable
    public Bitmap renderBlocking(@NonNull String key, @NonNull String lookJson, @Nullable Uri uri,
                                 long sourceMs, long timelineMs, int w, int h, @NonNull VisControls ctl,
                                 @Nullable StudioWords words) {
        if (w <= 0 || h <= 0) return null;
        final Bitmap[] out = new Bitmap[1];
        CountDownLatch done = new CountDownLatch(1);
        Request r = new Request(lookJson, uri, sourceMs, timelineMs, w, h, ctl, words);
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
                               long sourceMs, long timelineMs, int w, int h, @NonNull VisControls ctl,
                               @Nullable StudioWords words,
                               @Nullable Runnable onReady) {
        Request want = new Request(lookJson, uri, sourceMs, timelineMs, w, h, ctl, words);
        boolean queue;
        synchronized (pending) {
            // The same moment again (a redraw while paused, or the invalidate onReady asked
            // for): what is there, or on its way, is already that moment. No render loop.
            Request last = asked.get(key);
            boolean have;
            synchronized (latest) {
                have = latest.containsKey(key);
            }
            if (last != null && last.same(want) && (have || pending.containsKey(key))) {
                synchronized (latest) {
                    return latest.get(key);
                }
            }
            asked.put(key, want);
            queue = !pending.containsKey(key);
            pending.put(key, want);
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

    /** {@link #releasePreview} if VisLab has drawn in Studio at all (never starts its GL thread). */
    public static void releasePreviewIfRunning() {
        StudioVis s;
        synchronized (StudioVis.class) {
            s = instance;
        }
        if (s != null) s.releasePreview();
    }

    /** The editor closed: forget the preview's renderers and pictures (an export keeps its own). */
    public void releasePreview() {
        gl.handler().post(() -> {
            Iterator<Map.Entry<String, Item>> i = items.entrySet().iterator();
            while (i.hasNext()) {
                Map.Entry<String, Item> e = i.next();
                if (!e.getKey().startsWith("preview:")) continue;
                e.getValue().release();
                i.remove();
            }
        });
        synchronized (pending) {
            pending.keySet().removeIf(k -> k.startsWith("preview:"));
            asked.keySet().removeIf(k -> k.startsWith("preview:"));
        }
        synchronized (latest) {
            latest.keySet().removeIf(k -> k.startsWith("preview:"));
        }
    }

    private static long wordsKey(@Nullable StudioWords w) {
        return w == null ? 0 : w.key;
    }

    // GL thread: let go of items nobody has drawn for a while.
    private void sweepLater() {
        if (sweeping) return;
        sweeping = true;
        gl.handler().postDelayed(() -> {
            sweeping = false;
            long now = SystemClock.uptimeMillis();
            Iterator<Map.Entry<String, Item>> i = items.entrySet().iterator();
            while (i.hasNext()) {
                Item it = i.next().getValue();
                if (now - it.usedAt < IDLE_MS) continue;
                it.release();
                i.remove();
            }
            if (!items.isEmpty()) sweepLater();
        }, IDLE_MS);
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
                vr.setWords(null);         // until the clip's transcript gives words (below)
                // A Words layer's own typed words and any layer's font (typed words drew nothing
                // in Studio before: no maker was set).
                vr.setWordsMaker(font -> new com.fadcam.vislab.words.WordFeed(app,
                        com.fadcam.vislab.words.VisFonts.typeface(app, font)));
                vr.setImageSource(new com.fadcam.vislab.room.Pictures(app));   // pictures as in the room
                vr.setSpriteSource(new com.fadcam.vislab.room.Sprites(app));    // and sprite sheets
                it = new Item(vr);
                items.put(key, it);
            }
            it.usedAt = SystemClock.uptimeMillis();
            sweepLater();
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
            it.renderer.setReactivity(r.ctl.react);
            it.renderer.setLookControls(r.ctl.bright, r.ctl.hue, r.ctl.size, r.ctl.speed);
            // The clip's transcript, timed on the clip's own clock (S5); rebuilt only on a change.
            long wk = wordsKey(r.words);
            if (wk != it.wordsKey) {
                it.wordsKey = wk;
                if (r.words == null) {
                    it.renderer.setWords(null);
                } else {
                    if (it.feed == null) it.feed = new com.fadcam.vislab.words.WordFeed(app);
                    it.feed.setWords(r.words.words, 0);
                    it.renderer.setWords(it.feed);
                }
            }
            it.renderer.setWordsTime(r.sourceMs / 1000.0);
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
