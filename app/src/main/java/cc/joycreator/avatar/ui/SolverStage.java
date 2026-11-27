package cc.joycreator.avatar.ui;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.Log;

import cc.joycreator.avatar.draw.CharacterGl;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.solve.BitmapPics;
import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Signals;
import cc.joycreator.avatar.solve.SolveOut;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * The real character on glass: the pilot's solver ({@link CharacterSolver}) and its drawing ({@link CharacterGl}) on a GL
 * surface behind the stage's own overlay. Each frame the room hands over the signals (the take's lane names), the view
 * (zoom and pan) and whether things move (Motion and Perform) or hold still (building); the GL thread solves and draws.
 * Asks for 4x multisampling when the phone offers it, so outlines are as smooth as the pilot's.
 */
public final class SolverStage extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String TAG = "AvatarRoom";

    /** Tells the room the GPU drawing could not start, so it falls back to the stand-in drawing. */
    public interface Failure {
        void gpuFailed();
    }

    // handed over by the UI thread
    private volatile JSONObject pendingCharacter;
    private volatile boolean pendingNewPictures;
    private volatile Map<String, Object> signals = new HashMap<>();
    private volatile Map<String, String> force = new HashMap<>();
    private volatile boolean live;
    private volatile double clock, breath, dt = 1 / 60.0;
    private volatile float viewS = 1, viewX, viewY;
    private Failure failure;

    // owned by the GL thread
    private BitmapPics pics;
    private CharacterSolver solver;
    private CharacterGl gl;
    private int width, height;
    private boolean broken;

    public SolverStage(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new Chooser());
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setFailure(Failure f) {
        failure = f;
    }

    /**
     * A character, or the same one after an edit. {@code samePictures}: only the tree changed, so the decoded pictures (and
     * the textures made from them) are kept. The solver gets a copy of what it reads, so an edit in the room never races it.
     */
    public void setCharacter(CharacterDoc d, boolean samePictures) {
        pendingNewPictures = !samePictures;
        pendingCharacter = snapshot(d.json);
        requestRender();
    }

    /** Everything but the pictures is copied; the pictures are only read, once, to make the bitmaps. */
    private static JSONObject snapshot(JSONObject j) {
        JSONObject s = new JSONObject();
        try {
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = j.opt(k);
                if (k.equals("pictures") || k.equals("fine") || k.equals("take")) s.put(k, v);
                else if (v instanceof JSONObject) s.put(k, new JSONObject(v.toString()));
                else if (v instanceof org.json.JSONArray) s.put(k, new org.json.JSONArray(v.toString()));
                else s.put(k, v);
            }
        } catch (JSONException e) {
            Log.w(TAG, "snapshot failed", e);
        }
        return s;
    }

    /** One frame's worth of everything the drawing needs. */
    public void frame(Map<String, Object> sig, boolean moving, double seconds, double breathing, double dtSeconds, float scale, float ox, float oy) {
        signals = sig == null ? new HashMap<>() : sig;
        live = moving;
        clock = seconds;
        breath = breathing;
        dt = dtSeconds;
        viewS = scale;
        viewX = ox;
        viewY = oy;
        requestRender();
    }

    /** "Try this drawing": a drawing of a set shown whatever the signals say (set id to drawing id). */
    public void setForce(Map<String, String> f) {
        force = f == null ? new HashMap<>() : f;
        requestRender();
    }

    // ── the GL thread ─────────────────────────────────────────────────────────────────────────────

    @Override
    public void onSurfaceCreated(GL10 g, EGLConfig c) {
        if (gl != null) gl.release();
        gl = new CharacterGl();
        if (pics != null) gl.forget();
        broken = false;
    }

    @Override
    public void onSurfaceChanged(GL10 g, int w, int h) {
        width = w;
        height = h;
    }

    @Override
    public void onDrawFrame(GL10 g) {
        if (broken || gl == null) return;
        try {
            JSONObject next = pendingCharacter;
            if (next != null) {
                pendingCharacter = null;
                if (pics == null || pendingNewPictures) {
                    pics = new BitmapPics(next, null);
                    gl.forget();
                }
                solver = new CharacterSolver(next, pics);
            }
            if (solver == null) return;
            Signals s = SignalMap.toSignals(signals, clock, breath);
            CharacterSolver.Opt opt = new CharacterSolver.Opt();
            opt.live = live;
            opt.dt = dt;
            Map<String, String> f = force;
            if (!f.isEmpty()) opt.force = f;
            SolveOut out = solver.eval(s, opt);
            gl.draw(out, pics, new CharacterGl.View(viewS, viewX, viewY), null, 0, width, height);
        } catch (RuntimeException e) {
            Log.w(TAG, "the GPU drawing stopped", e);
            broken = true;
            Failure f = failure;
            if (f != null) post(f::gpuFailed);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        queueEvent(() -> {
            if (gl != null) gl.release();
            gl = null;
        });
    }

    /** 4x multisampling when the phone has it, else the plain configuration. */
    private static final class Chooser implements EGLConfigChooser {
        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[] withMsaa = {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 0, EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE};
            int[] plain = {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 0, EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE};
            EGLConfig[] out = new EGLConfig[1];
            int[] n = new int[1];
            if (egl.eglChooseConfig(display, withMsaa, out, 1, n) && n[0] > 0) return out[0];
            if (egl.eglChooseConfig(display, plain, out, 1, n) && n[0] > 0) return out[0];
            throw new IllegalArgumentException("no GL configuration");
        }
    }
}
