package com.fadcam.ui.faditor.tools.fxswatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.fx.FxCompiler;
import com.fadcam.ui.faditor.fx.FxEffectDef;
import com.fadcam.ui.faditor.fx.FxGlSource;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.fx.FxUniforms;
import com.fadcam.vislab.studio.OffscreenGl;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Effect swatches (STU-6 stage 1): the picker's still of every effect, and each stack card's
 * small live picture.
 *
 * <ul>
 *   <li>{@link #still}: an effect at its default settings, alone over neutral grey (a
 *       generator) or over the {@link FxSampleFrame} scene (everything else). Drawn once with
 *       the real compiler and kept as a PNG in the app's cache, named by the effect's id and a
 *       hash of what draws it (its emitted shader source, its default values, the sample's
 *       version and the swatch size). An edited effect therefore remakes its own still, and an
 *       app update that changes nothing keeps them all.</li>
 *   <li>{@link #card}: one card at its current values, over the scene, drawn now. Ahead of any
 *       picker still that is still waiting: the person is moving that slider.</li>
 * </ul>
 *
 * <p>All drawing is on one GL thread of its own ({@link OffscreenGl}), never the preview's.
 * Callbacks come back on the main thread; null means it could not be drawn, and the tile shows
 * its name on a plain ground.</p>
 */
public final class FxSwatches {

    private static final String TAG = "FxSwatch";
    /** Bump when how a still is drawn changes in a way no hashed input shows. */
    private static final int RENDER_VERSION = 1;

    public static final int STILL_W = 256, STILL_H = 144;
    public static final int CARD_W = 128, CARD_H = 72;
    /** The moment a picker still shows: two seconds in, so moving effects are under way. */
    static final float STILL_TIME_SEC = 2f;

    private static FxSwatches instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File dir;
    private final LruCache<String, Bitmap> stills = new LruCache<String, Bitmap>(12 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount() / 1024;
        }
    };
    private final LruCache<String, Bitmap> cards = new LruCache<>(32);

    @Nullable private OffscreenGl gl;
    // GL thread only:
    @Nullable private FxSwatchRenderer stillRenderer, cardRenderer;
    @Nullable private Bitmap scene, grey, cardScene;

    // Timing for the log: one batch is every still asked for until none is waiting.
    private final AtomicInteger waiting = new AtomicInteger();
    private long batchStartMs;
    private int batchDrawn, batchFromDisk, batchFailed;

    private FxSwatches(@NonNull Context c) {
        app = c.getApplicationContext();
        dir = new File(app.getCacheDir(), "fx_swatches");
    }

    @NonNull
    public static synchronized FxSwatches get(@NonNull Context c) {
        if (instance == null) instance = new FxSwatches(c);
        return instance;
    }

    @NonNull
    private synchronized Handler glThread() {
        if (gl == null) gl = new OffscreenGl();
        return gl.handler();
    }

    // ── Picker stills ──────────────────────────────────────────────────────────────────────

    /** Main thread: the still if it is already in memory. */
    @Nullable
    public Bitmap stillIfReady(@NonNull FxEffectDef def) {
        return stills.get(def.id);
    }

    /** {@code def}'s still: at once if in memory, otherwise from the cache or drawn. */
    public void still(@NonNull FxEffectDef def, @NonNull Consumer<Bitmap> done) {
        Bitmap ready = stills.get(def.id);
        if (ready != null) {
            done.accept(ready);
            return;
        }
        if (waiting.getAndIncrement() == 0) {
            long asked = SystemClock.elapsedRealtime();
            glThread().post(() -> {
                batchStartMs = asked;
                batchDrawn = batchFromDisk = batchFailed = 0;
            });
        }
        glThread().post(() -> {
            Bitmap b = stillNow(def);
            if (b == null) batchFailed++;
            if (waiting.decrementAndGet() == 0) {
                Log.i(TAG, "picker stills: " + (batchDrawn + batchFromDisk + batchFailed) + " in "
                        + (SystemClock.elapsedRealtime() - batchStartMs) + " ms ("
                        + batchDrawn + " drawn, " + batchFromDisk + " from the cache, "
                        + batchFailed + " not drawn)");
            }
            main.post(() -> {
                if (b != null) stills.put(def.id, b);
                done.accept(b);
            });
        });
    }

    /** GL thread: from the PNG cache, or drawn and written there. */
    @Nullable
    private Bitmap stillNow(@NonNull FxEffectDef def) {
        try {
            FxStack one = new FxStack();
            one.add(def.id);
            String name = def.id + "." + key(def, one) + ".png";
            File f = new File(dir, name);
            if (f.exists()) {
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) {
                    batchFromDisk++;
                    return b;
                }
            }
            if (!ready()) return null;
            if (stillRenderer == null) stillRenderer = new FxSwatchRenderer();
            boolean generator = def.family == FxEffectDef.Family.GENERATE
                    || def.capability == FxEffectDef.Capability.GENERATOR;
            Bitmap input;
            if (generator) {
                if (grey == null) grey = FxSampleFrame.grey(STILL_W, STILL_H);
                input = grey;
            } else {
                if (scene == null) scene = FxSampleFrame.scene(STILL_W, STILL_H);
                input = scene;
            }
            long t0 = SystemClock.elapsedRealtime();
            Bitmap b = stillRenderer.render(one, input, STILL_TIME_SEC);
            if (b == null) return null;
            batchDrawn++;
            Log.d(TAG, def.id + " still drawn in " + (SystemClock.elapsedRealtime() - t0) + " ms");
            write(def.id, f, b);
            return b;
        } catch (RuntimeException e) {
            Log.w(TAG, "still not made for " + def.id, e);
            return null;
        }
    }

    /** Write the PNG beside nothing older: an effect's previous stills go. */
    private void write(@NonNull String id, @NonNull File f, @NonNull Bitmap b) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmp = new File(dir, f.getName() + ".part");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        } catch (Exception e) {
            Log.w(TAG, "still not cached for " + id, e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        File[] old = dir.listFiles((d, n) -> n.startsWith(id + ".") && !n.equals(f.getName()));
        if (old != null) {
            for (File o : old) {
                //noinspection ResultOfMethodCallIgnored
                o.delete();
            }
        }
    }

    /**
     * What a still depends on: the emitted shader source of each pass (the effect's body, and the
     * compiler's own prelude), the default values its uniforms carry, the sample picture's
     * version and the size and moment it is drawn at.
     */
    @NonNull
    private static String key(@NonNull FxEffectDef def, @NonNull FxStack one) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(RENDER_VERSION).append('|').append(FxSampleFrame.VERSION).append('|')
                .append(STILL_W).append('x').append(STILL_H).append('@').append(STILL_TIME_SEC)
                .append('|').append(def.family).append('|');
        for (FxCompiler.Pass pa : FxCompiler.plan(one).passes) {
            sb.append(pa.repeats).append(FxGlSource.fragment(pa, FxGlSource.KERNEL_HALF, false, 1));
            for (FxUniforms.Value v : FxUniforms.forPass(pa)) sb.append(v).append(';');
        }
        return hash(sb.toString());
    }

    // ── Card swatches ──────────────────────────────────────────────────────────────────────

    /** Main thread: a card picture already made for exactly this {@code key}. */
    @Nullable
    public Bitmap cardIfReady(@NonNull String key) {
        return cards.get(key);
    }

    /**
     * Draw {@code one} (a one-card stack, already resolved at the playhead) over the scene at
     * {@code timeSec}. {@code key} names the result for {@link #cardIfReady}.
     */
    public void card(@NonNull FxStack one, float timeSec, @NonNull String key,
                     @NonNull Consumer<Bitmap> done) {
        Handler h = glThread();
        // Ahead of waiting picker stills, but never ahead of the GL thread's own start-up.
        h.postAtFrontOfQueue(() -> {
            if (gl == null || !gl.ready()) {
                h.post(() -> cardNow(one, timeSec, key, done));
                return;
            }
            cardNow(one, timeSec, key, done);
        });
    }

    /** GL thread. */
    private void cardNow(@NonNull FxStack one, float timeSec, @NonNull String key,
                         @NonNull Consumer<Bitmap> done) {
        Bitmap b = null;
        if (ready()) {
            if (cardRenderer == null) cardRenderer = new FxSwatchRenderer();
            if (cardScene == null) cardScene = FxSampleFrame.scene(CARD_W, CARD_H);
            long t0 = SystemClock.elapsedRealtime();
            b = cardRenderer.render(one, cardScene, timeSec);
            Log.d(TAG, "card swatch drawn in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        }
        Bitmap r = b;
        main.post(() -> {
            if (r != null) cards.put(key, r);
            done.accept(r);
        });
    }

    /** GL thread: the context is up. */
    private boolean ready() {
        OffscreenGl g = gl;
        if (g != null && g.ready()) return true;
        Log.w(TAG, "swatches not drawn: the GL thread could not start");
        return false;
    }

    @NonNull
    static String hash(@NonNull String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
