package cc.joycreator.textlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;
import com.fadcam.joyfolder.SheetName;
import com.fadcam.joyfolder.android.JoyFolderHome;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * TextLab's quick export (owner, 2026-11-12): the look on the screen, with its words, straight out as a
 * picture, a video or a moving sheet, at a chosen shape and size, into the Joy Creator folder's exports (where
 * Studio's go). Each export runs on a GL thread of its own with its own renderer, let go when it is done, so the
 * screen's picture and the look previews are never disturbed.
 *
 * <p>The frame: the look is fitted to the chosen shape by widening its margins ({@link LookFrame}), so the
 * look's own stage fills a wide, square or tall frame and the words sit in the middle.</p>
 */
public final class LookExport {

    private static final String TAG = "TextLab";
    /** A picture is the look's moment with the letters in and the look in its stride (as the previews). */
    static final double PICTURE_AT = LookPreview.START;

    public enum Kind { PICTURE, VIDEO, SHEET }

    /** What to make. */
    public static final class Job {
        @NonNull public final JSONObject look;
        @NonNull public final String words;
        @NonNull public final Kind kind;
        /** The frame (a picture or video) or one cell of the sheet, px. */
        public final int w, h;
        /** See-through, no stage (a picture or a sheet; a video always has its stage). */
        public final boolean clear;
        /** A video's length. */
        public final double seconds;

        public Job(@NonNull JSONObject look, @NonNull String words, @NonNull Kind kind, int w, int h, boolean clear,
                   double seconds) {
            this.look = look;
            this.words = words;
            this.kind = kind;
            this.w = w;
            this.h = h;
            this.clear = clear && kind != Kind.VIDEO;
            this.seconds = seconds;
        }
    }

    /** Called on the main thread. */
    public interface Listener {
        void progress(float done);

        /** Where it went (a path in the folder, for the person), or why not; both null when it was stopped. */
        void done(@Nullable String where, @Nullable String problem);
    }

    /** Thrown inside the export when the person stopped it. */
    static final class Stopped extends RuntimeException {
        Stopped() {
            super("stopped");
        }
    }

    static final int FPS = 30;

    private final Context app;
    private final Job job;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("textlab-export");
    private volatile boolean stop;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface idle = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private TextLabRenderer r;

    private LookExport(@NonNull Context c, @NonNull Job job, @NonNull Listener l) {
        app = c.getApplicationContext();
        this.job = job;
        listener = l;
    }

    /** Start an export; {@link #stop()} ends it early (nothing is saved). */
    @NonNull
    public static LookExport start(@NonNull Context c, @NonNull Job job, @NonNull Listener l) {
        LookExport e = new LookExport(c, job, l);
        e.thread.start();
        new Handler(e.thread.getLooper()).post(e::run);
        return e;
    }

    public void stop() {
        stop = true;
    }

    /** The file's name: the words, then the look ("HELLO - Dark Steel.mp4"). */
    @NonNull
    static String baseName(@NonNull String words, @NonNull JSONObject look) {
        String w = ReadableNames.sanitize(words.replace('\n', ' ')).trim();
        String l = ReadableNames.sanitize(look.optString("name", look.optString("id", ""))).trim();
        if (w.length() > 40) w = w.substring(0, 40).trim();
        String b = w.isEmpty() ? l : l.isEmpty() ? w : w + " - " + l;
        return b.isEmpty() ? "Text look" : b;
    }

    // ---- the export thread ----------------------------------------------------------------------

    private void run() {
        File tmp = null;
        try {
            setupEgl();
            r = new TextLabRenderer(TextLabRenderer.Assets.load(app.getAssets()), app);
            r.output = job.clear ? TextLabRenderer.CLEAR : TextLabRenderer.FOR_SCREEN;
            fit(job.w, job.h);
            String base = baseName(job.words, job.look);
            File dir = new File(app.getCacheDir(), "textlab/export");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String name;
            long t0 = System.nanoTime();
            switch (job.kind) {
                case VIDEO:
                    name = base + ".mp4";
                    tmp = new File(dir, "export.mp4");
                    video(tmp);
                    break;
                case SHEET:
                    name = SheetName.of(base, LookPreview.COLS, LookPreview.ROWS);
                    tmp = new File(dir, "export.sprite.png");
                    writePng(sheet(), tmp);
                    break;
                default:
                    name = base + ".png";
                    tmp = new File(dir, "export.png");
                    writePng(picture(), tmp);
            }
            Log.i(TAG, "export " + job.kind + " " + job.w + "x" + job.h + " made in "
                    + (System.nanoTime() - t0) / 1_000_000 + " ms");
            String where = place(tmp, name);
            main.post(() -> listener.done(where, null));
        } catch (Stopped s) {
            main.post(() -> listener.done(null, null));
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "export too big", e);
            main.post(() -> listener.done(null, "That size is too big for this phone. Try a smaller one."));
        } catch (Throwable e) {
            Log.w(TAG, "export failed", e);
            String why = e.getMessage() != null && e.getMessage().startsWith("This phone") ? e.getMessage()
                    : "Something went wrong making it. Try a smaller size.";
            main.post(() -> listener.done(null, why));
        } finally {
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            release();
            thread.quitSafely();
        }
    }

    private void progress(float f) {
        main.post(() -> listener.progress(f));
    }

    private void checkStop() {
        if (stop) throw new Stopped();
    }

    /** Lay the look out once, then widen its margins to the frame's shape. */
    private void fit(int w, int h) throws Exception {
        r.setLook(job.look);
        r.setText(job.words);
        r.setSize(w, h);
        r.frame(1000);
        r.setLook(LookFrame.framed(job.look, r.fieldEm(), (double) w / h, r.knob("floorLine")));
        r.setSize(w, h);
    }

    @NonNull
    private Bitmap picture() {
        r.still(PICTURE_AT);
        progress(0.7f);
        return exact(r.readBitmap(), job.w, job.h);
    }

    /** The look's two seconds as a 5 x 4 sheet, from the same moment as the previews, at 10 frames a second. */
    @NonNull
    private Bitmap sheet() {
        int cw = job.w, ch = job.h, n = LookPreview.COLS * LookPreview.ROWS;
        Bitmap sheet = Bitmap.createBitmap(LookPreview.COLS * cw, LookPreview.ROWS * ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        r.still(PICTURE_AT);
        int ticksPerFrame = 60 / SheetName.FPS, tick = 0;
        for (int i = 0; i < n; i++) {
            checkStop();
            if (i > 0) {
                for (int k = 0; k < ticksPerFrame; k++) r.frame(1000 + ++tick / 60.0);
            }
            Bitmap f = r.readBitmap();
            int x = (i % LookPreview.COLS) * cw, y = (i / LookPreview.COLS) * ch;
            c.drawBitmap(f, null, new Rect(x, y, x + cw, y + ch), p);
            f.recycle();
            progress((i + 1f) / n);
        }
        return sheet;
    }

    /** From the start, so the letters arrive, for the chosen length. */
    private void video(@NonNull File out) throws Exception {
        String no = LookVideo.refuses(job.w, job.h, FPS);
        if (no != null) throw new IllegalStateException(no);
        int count = Math.max(1, (int) Math.round(job.seconds * FPS));
        LookVideo.encode(display, config, context, idle, job.w, job.h, FPS, count, out, (i, w, h) -> {
            if (i == 0) r.still(0);
            else r.frame(1000 + i / (double) FPS);
            LookVideo.blit(r.outputFramebuffer(), r.width(), r.height(), w, h);
        }, () -> stop, this::progress);
    }

    /** The picture at exactly w x h (the fitted look can be a pixel off from rounding). */
    @NonNull
    private static Bitmap exact(@NonNull Bitmap b, int w, int h) {
        if (b.getWidth() == w && b.getHeight() == h) return b;
        Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(o).drawBitmap(b, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        b.recycle();
        return o;
    }

    private static void writePng(@NonNull Bitmap b, @NonNull File f) throws Exception {
        try (FileOutputStream os = new FileOutputStream(f)) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new IllegalStateException("PNG not written");
        } finally {
            b.recycle();
        }
    }

    /** Into the folder's exports (beside Studio's), on the folder's own thread; the app's own folder without one. */
    @NonNull
    private String place(@NonNull File tmp, @NonNull String name) throws Exception {
        return JoyFolderHome.io().submit(() -> {
            JoyFolder jf = JoyFolderHome.open(app);
            if (jf == null) {
                File dir = new File(app.getExternalFilesDir(null), "TextLab exports");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, name);
                for (int n = 2; f.exists(); n++) f = new File(dir, numbered(name, n));
                try (InputStream in = new FileInputStream(tmp); FileOutputStream os = new FileOutputStream(f)) {
                    byte[] buf = new byte[256 * 1024];
                    int k;
                    while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
                }
                Log.w(TAG, "Joy Creator folder unreachable; export kept in " + f);
                return "the app's own folder (the Joy Creator folder could not be reached)";
            }
            String dir = jf.prepareDir(Placement.What.EXPORT, null, null, null);
            String rel;
            try (InputStream in = new FileInputStream(tmp)) {
                rel = jf.addFile(dir, name, in, "export");
            }
            jf.saveIndex();
            Log.i(TAG, "exported " + rel);
            return rel;
        }).get();
    }

    /** "a.mp4" → "a 2.mp4"; "a.5x4.sprite.png" → "a 2.5x4.sprite.png". */
    @NonNull
    private static String numbered(@NonNull String name, int n) {
        int dot = name.indexOf('.');
        return dot < 0 ? name + " " + n : name.substring(0, dot) + " " + n + name.substring(dot);
    }

    private void setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new IllegalStateException("eglInitialize");
        int[] attr = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT | EGL14.EGL_WINDOW_BIT,
                LookVideo.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfg = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attr, 0, cfg, 0, 1, num, 0) || num[0] == 0) {
            throw new IllegalStateException("no ES3 config for exports");
        }
        config = cfg[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("no ES3 context");
        idle = EGL14.eglCreatePbufferSurface(display, config, new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(display, idle, idle, context)) throw new IllegalStateException("eglMakeCurrent");
    }

    private void release() {
        try {
            if (r != null) r.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "export renderer not released cleanly", e);
        }
        r = null;
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (idle != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, idle);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            // the display is shared with every other GL user in the app: never terminated here
        }
    }
}
