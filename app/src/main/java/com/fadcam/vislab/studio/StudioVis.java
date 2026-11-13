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
import java.util.function.BooleanSupplier;

/**
 * VisLab looks inside Studio (VISLAB_SPEC section 7, handoff tasks/VISLAB_HANDOFF_20261018_STUDIO.md):
 * one way to turn "this look, this item, this moment" into a picture, used by BOTH Studio's
 * preview and its export, so the video comes out as it was seen.
 *
 * <ul>
 *   <li>{@link #renderBlocking}: export. Waits for the picture; returns a NEW Bitmap the caller
 *       draws and recycles (Studio's export loop does exactly that with its own visualisers), and
 *       throws {@link LookFailed} rather than let a slow frame become some other picture.</li>
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
    /**
     * How long an export waits for one frame. Long on purpose: the first frame of a look compiles
     * shaders and can open a video on this same thread, and a slow frame must never be taken for
     * a failed one. Only a thread that has truly stuck gets here.
     */
    private static final long EXPORT_WAIT_MS = 60_000;
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
    /** The last {@link #draw} threw on a look that had been drawing (GL thread). */
    private boolean brokeDrawing;
    /** Per key, the look VisLab could not draw at all (never drew; no ES 3). Guarded by itself. */
    private final Map<String, String> cannotDraw = new HashMap<>();
    private static final int COST_FRAMES = 120;
    private int costFrames;    // GL thread, with the two below
    private long costRenderNs, costAllocNs;

    /** A look that was drawing could not give this frame: the export must stop, not carry on. */
    public static final class LookFailed extends RuntimeException {
        LookFailed(@NonNull String what) {
            super(what);
        }
    }

    private static final class Item {
        final VisRenderer renderer;
        String lookJson;
        /** The look this item last drew, so a frame that throws tells broken from breaking. */
        @Nullable String drewLook;
        @Nullable Uri uri;
        @Nullable SoundClock sound;
        double lastTime = Double.NaN;
        long usedAt;
        /** The clip's transcript words for the look's Words layers (S5), and their key. */
        @Nullable com.fadcam.vislab.words.WordFeed feed;
        long wordsKey;
        /** Its picture source, told each frame which Studio items are shown (R9). */
        @Nullable StudioPictures pictures;

        void release() {
            renderer.release();
            if (feed != null) feed.release();   // safe twice: the renderer may hold it too
            if (pictures != null) pictures.release();   // video decoders, sprite frames (R9, R12)
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

    /**
     * True once VisLab has found it cannot draw this look for {@code key} at all (unreadable, or no
     * ES 3). The preview then shows the classic style, as the export does, rather than nothing.
     */
    public boolean cannotDraw(@NonNull String key, @NonNull String lookJson) {
        synchronized (cannotDraw) {
            return lookJson.equals(cannotDraw.get(key));
        }
    }

    private void markCannotDraw(@NonNull String key, @NonNull String lookJson, boolean cannot) {
        synchronized (cannotDraw) {
            if (cannot) cannotDraw.put(key, lookJson);
            else cannotDraw.remove(key);
        }
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
     * VisLab values {@code ctl} (React, Bright, Colour, Size, Speed) and its clip's words. Blocks
     * until the picture is in, however slow. Returns a new Bitmap, or null when VisLab cannot draw
     * this look at all (no ES 3, a look that never drew). Never call on the main thread while the
     * main thread waits for you.
     *
     * @param cancelled polled while waiting: a cancelled export stops waiting at once
     * @throws LookFailed the frame stuck, the look broke part way, or the export was cancelled:
     *                    there is no right picture for this frame
     */
    @Nullable
    public Bitmap renderBlocking(@NonNull String key, @NonNull String lookJson, @Nullable Uri uri,
                                 long sourceMs, long timelineMs, int w, int h, @NonNull VisControls ctl,
                                 @Nullable StudioWords words, @Nullable BooleanSupplier cancelled) {
        if (w <= 0 || h <= 0) return null;
        LookTicket<Bitmap> ticket = new LookTicket<>(Bitmap::recycle);
        Request r = new Request(lookJson, uri, sourceMs, timelineMs, w, h, ctl, words);
        gl.handler().post(() -> {
            if (!ticket.begin()) return;   // the export gave up on this frame
            Bitmap b = draw(key, r);
            ticket.finish(b, brokeDrawing);
        });
        LookTicket.Outcome o = ticket.await(EXPORT_WAIT_MS, cancelled);
        if (o != LookTicket.Outcome.READY) {
            throw new LookFailed("VisLab look " + o + " for " + key + " at " + timelineMs + " ms");
        }
        return ticket.take();
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
                if (b == null) {
                    // Say so: the view asks again and draws the classic style, not a blank.
                    if (cannotDraw(key, r.lookJson) && onReady != null) main.post(onReady);
                    return;
                }
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

    // GL thread: every COST_FRAMES frames, where the time went (the GPU-handoff job needs this).
    private void noteCost(@NonNull String key, @NonNull Request r, long renderNs, long allocNs) {
        costRenderNs += renderNs;
        costAllocNs += allocNs;
        if (++costFrames < COST_FRAMES) return;
        double f = 1e6 * costFrames;
        com.fadcam.FLog.d(TAG, String.format(java.util.Locale.ROOT,
                "cost %s %dx%d over %d frames, ms each: submit %.2f, gpu wait %.2f, read %.2f, "
                        + "flip %.2f, alloc %.2f, into bitmap %.2f", key, r.w, r.h, costFrames,
                costRenderNs / f, gl.finishNs / f, gl.readNs / f, gl.flipNs / f, costAllocNs / f, gl.copyNs / f));
        costFrames = 0;
        costRenderNs = costAllocNs = 0;
        gl.finishNs = gl.readNs = gl.flipNs = gl.copyNs = 0;
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
        brokeDrawing = false;
        if (!gl.ready()) {
            markCannotDraw(key, r.lookJson, true);
            return null;
        }
        Item it = items.get(key);
        try {
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
                // Pictures and videos as in the room, plus the Studio items a picture layer shows (R9).
                StudioPictures pics = new StudioPictures(app);
                vr.setImageSource(pics);
                vr.setSpriteSource(new com.fadcam.vislab.room.Sprites(app));    // and sprite sheets
                it = new Item(vr);
                it.pictures = pics;
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
                it.renderer.setHitCounter(it.sound);
            }
            Features f = it.sound != null ? it.sound.at(r.sourceMs / 1000.0) : new Features();
            double t = r.timelineMs / 1000.0;
            double dt = Double.isNaN(it.lastTime) ? 1.0 / 30 : Math.max(0, Math.min(0.1, t - it.lastTime));
            it.lastTime = t;
            int fbo = gl.framebuffer(r.w, r.h);
            it.renderer.setReactivity(r.ctl.react);
            it.renderer.setLookControls(r.ctl.bright, r.ctl.hue, r.ctl.size, r.ctl.speed);
            it.renderer.setLayerFollow(r.ctl.follow);   // layers following a Studio object (R8)
            // Picture layers showing a Studio picture, title, sprite or video (R9).
            if (it.pictures != null) it.pictures.setShow(r.ctl.show);
            it.renderer.setLayerImages(r.ctl.show != null ? r.ctl.show.pairs : null);
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
            long t0 = System.nanoTime();
            it.renderer.render(f, t, dt, fbo, r.w, r.h);
            long t1 = System.nanoTime();
            Bitmap out = Bitmap.createBitmap(r.w, r.h, Bitmap.Config.ARGB_8888);
            long t2 = System.nanoTime();
            gl.readInto(out);
            it.drewLook = r.lookJson;
            markCannotDraw(key, r.lookJson, false);
            noteCost(key, r, t1 - t0, t2 - t1);
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "VisLab look failed to draw for " + key, e);
            brokeDrawing = it != null && r.lookJson.equals(it.drewLook);
            if (!brokeDrawing) markCannotDraw(key, r.lookJson, true);   // never drew: classic, as export
            return null;
        }
    }
}
