package cc.joycreator.textlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The TextLab room's picture: a look drawn live on its own GL thread. Every change is a small job queued for that
 * thread ({@link #run}), so the room never touches GL itself. The light is {@link #tilt} (set from the room's
 * light helper each frame); {@link #hold} freezes the look's clock (pause).
 */
public final class TlStage extends GLSurfaceView {

    private static final String TAG = "TextLab";

    /** Told on the main thread when the picture fails, with words for the person. */
    public interface Trouble {
        void failed(@NonNull String why);
    }

    private final ConcurrentLinkedQueue<Consumer<TextLabRenderer>> jobs = new ConcurrentLinkedQueue<>();
    private volatile double[] tilt = {0, 0};
    private volatile boolean held;
    private volatile String label = "";
    @Nullable private Trouble trouble;
    @Nullable private JSONObject shown;
    @Nullable private String shownWords;
    private TextLabRenderer r;

    public TlStage(@NonNull Context c) {
        super(c);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setRenderer(new Draw());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void setTrouble(@Nullable Trouble t) {
        trouble = t;
    }

    /** Queue a job for the GL thread (with the renderer). */
    public void run(@NonNull Consumer<TextLabRenderer> job) {
        jobs.add(job);
    }

    /** A new look (and words): drawn from the moment the letters have arrived. */
    public void show(@NonNull JSONObject look, @NonNull String words) {
        final JSONObject l = look;
        final String w = words;
        run(r -> {
            shown = l;
            shownWords = w;
            r.setLook(l);
            r.stage = l.optDouble("stage", 1);
            r.setText(w);
            r.still(LookPreview.START);
            liveStart = -1;
        });
    }

    /** The look again from the start (the letters arrive). */
    public void replay() {
        run(r -> {
            r.still(0);
            liveStart = -1;
        });
    }

    public void setTilt(double x, double y) {
        tilt = new double[]{x, y};
    }

    public void setHeld(boolean h) {
        held = h;
    }

    public boolean held() {
        return held;
    }

    /** A short line for the corner (frame time), refreshed twice a second. */
    @NonNull
    public String label() {
        return label;
    }

    // ---- GL thread -----------------------------------------------------------------------------------

    private double liveStart = -1, heldAt = -1;

    private final class Draw implements GLSurfaceView.Renderer {
        int sw, sh;
        double sumMs;
        int frames;
        long lastLabel;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            try {
                r = new TextLabRenderer(TextLabRenderer.Assets.load(getContext().getAssets()), getContext());
                r.setSize(1280, 720);
                // a new surface after a pause: draw what was showing again
                if (shown != null) show(shown, shownWords != null ? shownWords : "");
            } catch (Exception | Error e) {
                fail(e);
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            sw = width;
            sh = height;
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (r == null) {
                GLES30.glClearColor(0.043f, 0.043f, 0.047f, 1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                return;
            }
            try {
                for (Consumer<TextLabRenderer> j; (j = jobs.poll()) != null; ) j.accept(r);
                if (shown != null) {
                    r.tilt = tilt.clone();
                    double now = SystemClock.elapsedRealtimeNanos() / 1e9;
                    if (liveStart < 0) liveStart = now;
                    if (held) {
                        if (heldAt < 0) heldAt = now;
                        r.frame(1000 + (heldAt - liveStart));   // the clock stands still; the light still moves
                    } else {
                        if (heldAt >= 0) {
                            liveStart += now - heldAt;
                            heldAt = -1;
                        }
                        long a = SystemClock.elapsedRealtimeNanos();
                        r.frame(1000 + (now - liveStart));
                        sumMs += (SystemClock.elapsedRealtimeNanos() - a) / 1e6;
                        frames++;
                    }
                }
            } catch (RuntimeException e) {
                fail(e);
                return;
            }
            blit();
            long t = SystemClock.uptimeMillis();
            if (t - lastLabel > 500) {
                lastLabel = t;
                label = frames > 0 ? String.format(java.util.Locale.ROOT, "%.0f ms a frame", sumMs / frames) : "";
                sumMs = 0;
                frames = 0;
            }
        }

        void blit() {
            int pw = r.width(), ph = r.height();
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, sw, sh);
            GLES30.glClearColor(0.043f, 0.043f, 0.047f, 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            if (shown == null || pw <= 0 || ph <= 0) return;
            double k = Math.min((double) sw / pw, (double) sh / ph);
            int dw = (int) Math.round(pw * k), dh = (int) Math.round(ph * k);
            int x0 = (sw - dw) / 2, y0 = (sh - dh) / 2;
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, r.outputFramebuffer());
            GLES30.glBlitFramebuffer(0, 0, pw, ph, x0, y0, x0 + dw, y0 + dh, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }
    }

    private void fail(Throwable e) {
        Log.e(TAG, "TextLab room picture failed", e);
        r = null;
        Trouble t = trouble;
        String why = TlTrouble.words(e);
        if (t != null) post(() -> t.failed(why));
    }

    /** Hand a still of the picture as it is now to {@code done} (main thread). */
    public void still(@NonNull Consumer<Bitmap> done) {
        run(r -> {
            Bitmap b = r.readBitmap();
            post(() -> done.accept(b));
        });
    }
}
