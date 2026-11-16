package cc.joycreator.handoff;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One hand-off show (FX-1), running on its own GL thread from the room you leave to the room you
 * arrive in.
 *
 * <p>The show has to outlive the screens it plays over: the question is asked on one screen and the
 * item lands on another. So the engine belongs to the app, not to a screen. It keeps one GL context
 * and draws into whichever surface is currently lent to it ({@link #attach}); when the first screen
 * goes and the next one's surface arrives, the same show carries on in it, on the same clock. It
 * draws on its own thread, paced by Choreographer (never an Android animator: the owner's phone has
 * "remove animations" on, and Joybot always animates), so he keeps moving while the main thread is
 * busy opening the next room.</p>
 *
 * <p>Nothing here may ever hurt the real work: the file is saved and placed before the show starts,
 * and any failure (no GL, no memory, a room that never reports) just ends the show early.</p>
 */
public final class HandoffEngine {

    private static final String TAG = "Handoff";
    /** A room that has not reported in this long is not coming: end the show and get out of the way. */
    private static final float GIVE_UP_S = 9f;
    /** No screen to draw on for this long (the first one gone, the next not there): the same. */
    private static final float BLIND_S = 4f;

    /** Main thread. */
    public interface Listener {
        /** The item is slapped down: from now on the real room is what is on screen. */
        void onSlap();
        /** Nothing more to draw. {@code drew}: the show ran to its end (false: it was cut short). */
        void onDone(boolean drew);
        /** A first frame is on {@code surface}. */
        void onFirstFrame(@NonNull Surface surface);
    }

    @Nullable private static HandoffEngine live;

    /** The show in progress, if any. */
    @MainThread
    @Nullable
    public static HandoffEngine live() { return live; }

    private final Context app;
    private final int mode;
    private final float density;
    private final int a1, a2, b1, b2;
    private final HandoffMoves.Stage stage = new HandoffMoves.Stage();
    private final HandlerThread thread;
    private final Handler gl;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private volatile Listener listener;

    // GL thread only
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface window = EGL14.EGL_NO_SURFACE, parked = EGL14.EGL_NO_SURFACE;
    @Nullable private Surface drawingOn;
    private boolean firstFrameTold;
    @Nullable private HandoffSprites sprites;
    @Nullable private HandoffRenderer renderer;
    @Nullable private HandoffScript script;
    private final HandoffScript.Frame frame = new HandoffScript.Frame();
    private boolean running, over, slapTold;
    private long lastNs;
    private float sincePick, blind, afterglow;
    private boolean picked;
    private final int[] wh = new int[1];
    private int frames, slowGaps;
    private long gapNsMax, drawNsSum, drawNsMax;

    private HandoffEngine(@NonNull Context c, int mode, float density, int a1, int a2, int b1, int b2) {
        this.app = c.getApplicationContext();
        this.mode = mode;
        this.density = density;
        this.a1 = a1; this.a2 = a2; this.b1 = b1; this.b2 = b2;
        thread = new HandlerThread("handoff-gl");
        thread.start();
        gl = new Handler(thread.getLooper());
    }

    /**
     * Begin a show. {@code roomA}: the room we are leaving, pictured; {@code w, h}: the window in dp;
     * {@code btnX, btnY}: Joybot's button there, dp. Colours: the leaving and arriving rooms' gradients.
     */
    @MainThread
    @NonNull
    public static HandoffEngine start(@NonNull Context c, int mode, float density, float w, float h,
                                      float btnX, float btnY, @NonNull Bitmap roomA, int a1, int a2, int b1, int b2) {
        HandoffEngine old = live;
        if (old != null) old.abort();
        HandoffEngine e = new HandoffEngine(c, mode, density, a1, a2, b1, b2);
        e.stage.w = w; e.stage.h = h; e.stage.btnX = btnX; e.stage.btnY = btnY;
        e.stage.homeX = btnX; e.stage.homeY = btnY;
        live = e;
        e.gl.post(() -> e.setUp(roomA));
        return e;
    }

    @MainThread
    public void setListener(@Nullable Listener l) { listener = l; }

    /** Lend a surface to draw on (the newest one wins). */
    @MainThread
    public void attach(@NonNull Surface s) {
        gl.post(() -> bind(s));
    }

    /** Take a surface back: returns once the GL thread has let go of it (or 300 ms have passed). */
    @MainThread
    public void detach(@NonNull Surface s) {
        final Object lock = new Object();
        final boolean[] gone = {false};
        gl.post(() -> {
            if (drawingOn == s) unbind();
            synchronized (lock) { gone[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            long until = System.currentTimeMillis() + 300;
            while (!gone[0] && thread.isAlive() && System.currentTimeMillis() < until) {
                try { lock.wait(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    /** He flies out of his button and waits by the question; his seat is at {@code seatX, seatY} dp. */
    @MainThread
    public void ask(float seatX, float seatY) {
        gl.post(() -> {
            if (script != null || over) return;
            stage.hasSeat = true; stage.seatX = seatX; stage.seatY = seatY;
            script = new HandoffScript(mode, stage, true);
        });
    }

    /** The answer is in (or there was nothing to ask): into the move. */
    @MainThread
    public void go() {
        gl.post(() -> {
            if (over) return;
            if (script == null) script = new HandoffScript(mode, stage, false);
            else script.pick(frame.him);
            picked = true;
        });
    }

    /**
     * The next room is open. {@code roomB}: its picture; {@code item}: the new item's rectangle there,
     * dp (null: unknown, he lands mid-screen); {@code homeX, homeY}: Joybot's button there, dp.
     */
    @MainThread
    public void roomReady(@NonNull Bitmap roomB, @Nullable RectF item, float homeX, float homeY) {
        gl.post(() -> {
            HandoffRenderer r = renderer;
            HandoffScript s = script;
            if (over || r == null || s == null) { roomB.recycle(); return; }
            try {
                makeCurrent();
                r.setRoomB(roomB);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "next room's picture", e);
                finish(false);
                return;
            } finally {
                roomB.recycle();
            }
            if (item != null && item.width() > 4f && item.height() > 4f) {
                stage.itemL = item.left; stage.itemT = item.top; stage.itemR = item.right; stage.itemB = item.bottom;
            } else {
                stage.itemL = .2f * stage.w; stage.itemR = .8f * stage.w;
                stage.itemT = .72f * stage.h; stage.itemB = stage.itemT + 30f;
            }
            stage.homeX = homeX; stage.homeY = homeY;
            s.roomReady();
        });
    }

    /** A tap: to the end as soon as there is a room to end in. */
    @MainThread
    public void skip() {
        gl.post(() -> { if (script != null) script.skip(); });
    }

    /** End it now (a screen closed, something failed). */
    @MainThread
    public void abort() {
        gl.post(() -> finish(false));
    }

    // ── GL thread ───────────────────────────────────────────────────────────

    private void setUp(@NonNull Bitmap roomA) {
        try {
            HandoffSprites sp = HandoffSprites.load(app);
            if (sp == null) throw new IllegalStateException("Joybot's pictures could not be read");
            sprites = sp;
            stage.cell = sp.cellFor(64f);              // the chat's Joybot: a 64 dp body
            setupEgl();
            HandoffRenderer r = new HandoffRenderer(app, mode, sp, a1, a2, b1, b2);
            r.init();
            r.setRoomA(roomA);
            renderer = r;
            running = true;
            lastNs = 0L;
            Choreographer.getInstance().postFrameCallback(tick);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "show set-up failed", e);
            finish(false);
        }
    }

    private void bind(@NonNull Surface s) {
        if (over || display == EGL14.EGL_NO_DISPLAY || !s.isValid()) return;
        unbind();
        try {
            window = EGL14.eglCreateWindowSurface(display, config, s, new int[]{EGL14.EGL_NONE}, 0);
            if (window == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreateWindowSurface");
            drawingOn = s;
            firstFrameTold = false;
            blind = 0f;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not draw on the new screen", e);
            window = EGL14.EGL_NO_SURFACE;
            drawingOn = null;
        }
    }

    private void unbind() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, parked, parked, context);
            EGL14.eglDestroySurface(display, window);
        }
        window = EGL14.EGL_NO_SURFACE;
        drawingOn = null;
    }

    private void makeCurrent() {
        EGLSurface s = window != EGL14.EGL_NO_SURFACE ? window : parked;
        if (!EGL14.eglMakeCurrent(display, s, s, context)) throw new IllegalStateException("eglMakeCurrent");
    }

    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!running) return;
            float dt = lastNs == 0L ? 1f / 60f : Math.max(0f, Math.min(.1f, (now - lastNs) / 1e9f));
            if (lastNs != 0L) {
                long gap = now - lastNs;
                gapNsMax = Math.max(gapNsMax, gap);
                if (gap > 20_000_000L) slowGaps++;
            }
            lastNs = now;
            HandoffScript s = script;
            HandoffRenderer r = renderer;
            if (r == null) { finish(false); return; }
            try {
                boolean end = false;
                if (s != null) {
                    s.advance(dt);
                    s.frame(frame);
                    if (picked) sincePick += dt;
                    if (frame.slap && !slapTold) {
                        slapTold = true;
                        main.post(() -> { Listener l = listener; if (l != null) l.onSlap(); });
                    }
                    if (s.phase() == HandoffScript.Phase.DONE) {
                        afterglow += dt;
                        end = !r.busy() || afterglow > 1.6f;
                    } else if (picked && !s.slapped() && sincePick > GIVE_UP_S) {
                        Log.w(TAG, "the next room never reported: ending the show");
                        finish(false);
                        return;
                    }
                }
                if (window != EGL14.EGL_NO_SURFACE) {
                    if (!EGL14.eglMakeCurrent(display, window, window, context)) { unbind(); }
                    else {
                        long t0 = System.nanoTime();
                        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, wh, 0);
                        int w = wh[0];
                        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, wh, 0);
                        int h = wh[0];
                        if (w > 0 && h > 0) {
                            if (s != null) r.render(frame, stage, density, w, h, dt);
                            else {
                                GLES30.glClearColor(0, 0, 0, 0);
                                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                            }
                            EGL14.eglSwapBuffers(display, window);
                            long dn = System.nanoTime() - t0;
                            drawNsSum += dn;
                            drawNsMax = Math.max(drawNsMax, dn);
                            frames++;
                            if (!firstFrameTold && s != null) {
                                firstFrameTold = true;
                                final Surface on = drawingOn;
                                main.post(() -> { Listener l = listener; if (l != null && on != null) l.onFirstFrame(on); });
                            }
                        }
                    }
                } else if (picked) {
                    blind += dt;
                    if (blind > BLIND_S) { finish(false); return; }
                }
                if (end) { finish(true); return; }
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "show frame failed", e);
                finish(false);
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** GL thread: clear the screen we were drawing on, free everything, tell the main thread, stop the thread. */
    private void finish(boolean drew) {
        if (over) return;
        over = true;
        running = false;
        Choreographer.getInstance().removeFrameCallback(tick);
        if (frames > 0) {
            Log.i(TAG, "show " + frames + " frames, gaps over 20 ms " + slowGaps + ", worst gap " + gapNsMax / 100_000 / 10f
                    + " ms, draw+swap avg " + drawNsSum / frames / 100_000 / 10f + " ms, worst " + drawNsMax / 100_000 / 10f + " ms");
        }
        try {
            if (window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context)) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                GLES30.glClearColor(0, 0, 0, 0);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                EGL14.eglSwapBuffers(display, window);
            }
            HandoffRenderer r = renderer;
            renderer = null;
            if (r != null && display != EGL14.EGL_NO_DISPLAY) {
                makeCurrent();
                r.release();
            }
        } catch (RuntimeException ignored) {
            // the context goes anyway
        }
        teardownEgl();
        HandoffSprites sp = sprites;
        sprites = null;
        if (sp != null) sp.recycle();
        final boolean told = slapTold;
        main.post(() -> {
            if (live == HandoffEngine.this) live = null;
            Listener l = listener;
            listener = null;
            if (l != null) {
                if (!told) l.onSlap();          // the room must end up selected and usable however the show ended
                l.onDone(drew);
            }
        });
        thread.quitSafely();
    }

    // ── EGL (as the arrival's StageView and VisTextureView) ─────────────────

    private void setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new IllegalStateException("eglInitialize");
        int[] attr = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfg = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attr, 0, cfg, 0, 1, num, 0) || num[0] == 0) {
            throw new IllegalStateException("no ES3 RGBA8888 config");
        }
        config = cfg[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("no ES3 context");
        // a 1 x 1 surface to stand on while no screen is lent to us
        parked = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (parked == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreatePbufferSurface");
        if (!EGL14.eglMakeCurrent(display, parked, parked, context)) throw new IllegalStateException("eglMakeCurrent");
    }

    private void teardownEgl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (parked != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, parked);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            // No eglTerminate: the default display is shared with every other GL user in the app.
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        window = parked = EGL14.EGL_NO_SURFACE;
        drawingOn = null;
    }
}
