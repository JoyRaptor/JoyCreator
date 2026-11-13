package cc.joycreator.textlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.studio.OffscreenGl;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Studio titles wearing TextLab looks (TEXT-1): the twin of VisLab's {@code StudioVis}. TextLab draws on a GL thread
 * of its own (never Studio's or media3's), one renderer per title for the preview and a separate one per title for an
 * export (fire and particles carry state from frame to frame, so the two must never share).
 *
 * <p>The look's clock is the title's own time. Stepping forward a little (playback, export) runs the look on as it
 * would live; a jump (a seek, scrubbing back, the first frame) draws that moment exactly ({@link TextLabRenderer#still}),
 * so a picture never depends on how the editor got there.</p>
 */
public final class StudioTextLab {

    private static final String TAG = "TextLab";
    private static final long IDLE_MS = 20_000;
    /** No side of a title's picture over this, px. */
    public static final int CAP = 2048;

    /** A drawn moment: the see-through picture (premultiplied) and how to place it. */
    public static final class Frame {
        @NonNull public final Bitmap bitmap;
        /** Pixels to the em the picture was drawn at: scale it by (wanted em / this) to place it. */
        public final double em;

        Frame(@NonNull Bitmap bitmap, double em) {
            this.bitmap = bitmap;
            this.em = em;
        }
    }

    private static final class Request {
        final TitleLook look;
        final String words;
        final long localMs;
        final double em;

        Request(TitleLook look, String words, long localMs, double em) {
            this.look = look;
            this.words = words;
            this.localMs = localMs;
            this.em = em;
        }

        boolean same(Request o) {
            return o != null && o.localMs == localMs && Math.abs(o.em - em) < 0.5 && o.words.equals(words)
                    && o.look.signature().equals(look.signature());
        }
    }

    private static final class Item {
        TextLabRenderer r;
        String lookSig;
        double base = Double.NaN;   // the renderer's clock = base + the title's seconds
        double lastT = Double.NaN;
        long usedAt;
    }

    private static StudioTextLab instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private OffscreenGl gl;
    private TextLabRenderer.Assets assets;
    private final Map<String, Item> items = new HashMap<>();   // GL thread
    private final Map<String, Request> pending = new HashMap<>(), asked = new HashMap<>();
    private final Map<String, Frame> latest = new HashMap<>();
    private boolean sweeping;

    private StudioTextLab(@NonNull Context c) {
        app = c.getApplicationContext();
    }

    @NonNull
    public static synchronized StudioTextLab get(@NonNull Context c) {
        if (instance == null) instance = new StudioTextLab(c);
        return instance;
    }

    private synchronized Handler glThread() {
        if (gl == null) gl = new OffscreenGl();
        return gl.handler();
    }

    @NonNull
    public static String previewKey(@NonNull String titleId) {
        return "preview:" + titleId;
    }

    @NonNull
    public static String exportKey(@NonNull String titleId) {
        return "export:" + titleId;
    }

    /**
     * Export: the title's look at {@code localMs} (its own time), drawn at {@code em} px to the em. Blocks up to 5 s
     * (the first frame compiles the look's shaders). Returns a new picture the caller owns, or null when it cannot be
     * drawn here. Never call on the main thread.
     */
    @Nullable
    public Frame renderBlocking(@NonNull String key, @NonNull TitleLook look, @NonNull String words, long localMs,
                                double em) {
        final Frame[] out = new Frame[1];
        CountDownLatch done = new CountDownLatch(1);
        Request r = new Request(look.copy(), words, localMs, em);
        glThread().post(() -> {
            try {
                out[0] = draw(key, r);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(5, TimeUnit.SECONDS)) Log.w(TAG, "title look timed out for " + key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    /**
     * Preview: the newest finished picture for {@code key} (null before the first) and a request for this moment;
     * only the newest request per title is kept, and {@code onReady} runs on the main thread when it is in. The
     * picture belongs to this class: draw it, never keep or recycle it.
     */
    @Nullable
    public Frame previewFrame(@NonNull String key, @NonNull TitleLook look, @NonNull String words, long localMs,
                              double em, @Nullable Runnable onReady) {
        Request want = new Request(look.copy(), words, localMs, em);
        boolean queue;
        synchronized (pending) {
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
            glThread().post(() -> {
                Request r;
                synchronized (pending) {
                    r = pending.remove(key);
                }
                if (r == null) return;
                Frame f = draw(key, r);
                if (f == null) return;
                Frame old;
                synchronized (latest) {
                    old = latest.put(key, f);
                }
                main.post(() -> {
                    if (old != null && old.bitmap != f.bitmap) old.bitmap.recycle();
                    if (onReady != null) onReady.run();
                });
            });
        }
        synchronized (latest) {
            return latest.get(key);
        }
    }

    /** The editor closed: let the preview's renderers and pictures go (an export keeps its own). */
    public static void releasePreviewIfRunning() {
        StudioTextLab s;
        synchronized (StudioTextLab.class) {
            s = instance;
        }
        if (s == null || s.gl == null) return;
        s.gl.handler().post(() -> {
            Iterator<Map.Entry<String, Item>> i = s.items.entrySet().iterator();
            while (i.hasNext()) {
                Map.Entry<String, Item> e = i.next();
                if (!e.getKey().startsWith("preview:")) continue;
                e.getValue().r.release();
                i.remove();
            }
        });
        synchronized (s.pending) {
            s.pending.keySet().removeIf(k -> k.startsWith("preview:"));
            s.asked.keySet().removeIf(k -> k.startsWith("preview:"));
        }
        synchronized (s.latest) {
            for (Iterator<Map.Entry<String, Frame>> i = s.latest.entrySet().iterator(); i.hasNext(); ) {
                Map.Entry<String, Frame> e = i.next();
                if (e.getKey().startsWith("preview:")) {
                    Bitmap b = e.getValue().bitmap;
                    s.main.post(b::recycle);
                    i.remove();
                }
            }
        }
    }

    /** An export finished: its renderers go now (not after the idle sweep). */
    public void releaseExport() {
        if (gl == null) return;
        gl.handler().post(() -> {
            Iterator<Map.Entry<String, Item>> i = items.entrySet().iterator();
            while (i.hasNext()) {
                Map.Entry<String, Item> e = i.next();
                if (!e.getKey().startsWith("export:")) continue;
                e.getValue().r.release();
                i.remove();
            }
        });
    }

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
                it.r.release();
                i.remove();
            }
            if (!items.isEmpty()) sweepLater();
        }, IDLE_MS);
    }

    /**
     * Set the renderer up for the title's moment: the person's knobs, the light (never a sensor) and a pinned
     * progress (frontGone with frontAuto 0, or shatter with shatterAuto 0). The look itself is set when it changes.
     */
    static void apply(@NonNull TitleLook t, @NonNull TextLabRenderer r, long localMs) {
        for (Map.Entry<String, com.google.gson.JsonElement> k : t.knobs.entrySet()) {
            com.google.gson.JsonElement v = k.getValue();
            if (!v.isJsonPrimitive()) continue;
            r.setKnob(k.getKey(), v.getAsJsonPrimitive().isNumber() ? (Object) v.getAsDouble() : v.getAsString());
        }
        r.tilt = t.tiltAt(localMs);
        if (t.progressPinned()) {
            double p = t.keys.valueAt(TitleLook.PROGRESS, localMs, 0f);
            if (t.feature("FRONT")) {
                r.setKnob("frontAuto", 0.0);
                r.setKnob("frontGone", p);
            } else if (t.feature("SHATTER")) {
                r.setKnob("shatterAuto", 0.0);
                r.setKnob("shatter", p);
            }
        }
    }

    // ---- the check screen's split of a picture's time (StudioTextLabBench) --------------------------

    private static volatile boolean splitOn;
    private static final long[] splitSums = new long[3];
    private static int splitN;
    private static String splitSize = "";

    /** On: time each picture's processor work, GPU wait and read-back. Off: the averages since it went on. */
    @NonNull
    static synchronized String split(boolean on) {
        String s = splitN == 0 ? "no pictures" : String.format(java.util.Locale.ROOT,
                "%s, processor %.1f ms, GPU wait %.1f ms, read back %.1f ms (%d pictures)", splitSize,
                splitSums[0] / 1e6 / splitN, splitSums[1] / 1e6 / splitN, splitSums[2] / 1e6 / splitN, splitN);
        java.util.Arrays.fill(splitSums, 0);
        splitN = 0;
        splitOn = on;
        return s;
    }

    // ---- GL thread ------------------------------------------------------------------------------

    @Nullable
    private Frame draw(@NonNull String key, @NonNull Request q) {
        if (!gl.ready()) return null;
        long c0 = SystemClock.elapsedRealtimeNanos();
        try {
            if (assets == null) assets = TextLabRenderer.Assets.load(app.getAssets());
            Item it = items.get(key);
            if (it == null) {
                it = new Item();
                it.r = new TextLabRenderer(assets, app);
                it.r.output = TextLabRenderer.CLEAR;
                items.put(key, it);
            }
            it.usedAt = SystemClock.uptimeMillis();
            sweepLater();
            TextLabRenderer r = it.r;
            String sig = q.look.lookSignature();
            boolean jump = false;
            if (!sig.equals(it.lookSig)) {
                r.setLook(new org.json.JSONObject(q.look.look.toString()));
                it.lookSig = sig;
                jump = true;
            }
            r.setText(q.words.isEmpty() ? q.look.sample() : q.words);
            r.setEm(q.em, CAP);
            apply(q.look, r, q.localMs);
            double t = q.localMs / 1000.0;
            // a jump (or the first frame, or a new look): that moment exactly; a step forward: on, as live
            if (jump || Double.isNaN(it.lastT) || t < it.lastT || t - it.lastT > 0.25) {
                r.still(t);
                it.base = 1000 - t;
            } else {
                r.frame(it.base + t);
            }
            it.lastT = t;
            if (!splitOn) return new Frame(r.readBitmap(), r.em());
            long c1 = SystemClock.elapsedRealtimeNanos();
            android.opengl.GLES30.glFinish();
            long c2 = SystemClock.elapsedRealtimeNanos();
            Frame f = new Frame(r.readBitmap(), r.em());
            long c3 = SystemClock.elapsedRealtimeNanos();
            synchronized (StudioTextLab.class) {
                splitSums[0] += c1 - c0;
                splitSums[1] += c2 - c1;
                splitSums[2] += c3 - c2;
                splitN++;
                splitSize = f.bitmap.getWidth() + "x" + f.bitmap.getHeight();
            }
            return f;
        } catch (Exception | Error e) {
            Log.w(TAG, "title look not drawn for " + key, e);
            return null;
        }
    }
}
