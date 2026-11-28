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
    private volatile Signals signals = new Signals();
    private volatile java.util.List<String> lastOrder = new java.util.ArrayList<>();
    private volatile Map<String, double[]> lastAnchor = new HashMap<>(), lastTips = new HashMap<>();
    private volatile Map<String, String> force = new HashMap<>();
    private volatile Map<String, Double> poses = new HashMap<>();
    private volatile Map<String, Map<String, Double>> shapes = new HashMap<>();
    private volatile Map<String, double[]> lastM = new HashMap<>();
    private final Object solverLock = new Object();
    private volatile boolean live;
    private volatile double clock, breath, dt = 1 / 60.0;
    private volatile float viewS = 1, viewX, viewY;
    private Failure failure;
    // the frame's texture behind the character (scene slice): a null picture rectangle means the plain surface as always
    private final cc.joycreator.avatar.draw.BackdropGl backdrop = new cc.joycreator.avatar.draw.BackdropGl();
    private volatile float[] backdropPic;

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

    /** Video Studio's canvas texture behind the picture (pic = its rectangle in pixels), or none: {@code pic == null}. */
    public void setBackdrop(android.graphics.Bitmap texture, float[] pic) {
        if (texture != null) backdrop.setTexture(texture);
        backdropPic = pic;
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

    /** The ids of what was drawn on the last frame, back to front, no ghosts: the picture's own draw order. */
    public java.util.List<String> drawOrder() {
        return lastOrder;
    }

    /** Where each limb grows from and where each hand is, from the last frame solved (what the walker needs). */
    public Map<String, double[]> anchors() {
        return lastAnchor;
    }

    public Map<String, double[]> tips() {
        return lastTips;
    }

    /** One frame's worth of everything the drawing needs. */
    public void frame(Signals sig, boolean moving, double dtSeconds, float scale, float ox, float oy) {
        signals = sig == null ? new Signals() : sig;
        live = moving;
        dt = dtSeconds;
        viewS = scale;
        viewX = ox;
        viewY = oy;
        requestRender();
    }

    /** A stance forced to a level while it is being worked on (stance id to 0..1); empty lets the buttons decide. */
    public void setPoses(Map<String, Double> p) {
        poses = p == null ? new HashMap<>() : p;
        requestRender();
    }

    /** Shapes worn at a given strength whatever the signals say ({part id: {shape id: 0..1}}): what reshaping shows. */
    public void setShapes(Map<String, Map<String, Double>> s) {
        shapes = s == null ? new HashMap<>() : s;
        requestRender();
    }

    /** A part's matrix as drawn on the last frame (part space to stage space: x' = a x + c y + e, y' = b x + d y + f), or null. */
    public double[] matrix(String id) {
        return lastM.get(id);
    }

    /** Where one spot of a part with pins goes with its shapes worn at these strengths (the solver's warpAt). */
    public double[] warpAt(String nodeId, double x, double y, Map<String, Double> weights) {
        synchronized (solverLock) {
            CharacterSolver s = solver;
            return s == null ? new double[]{x, y} : s.warpAt(nodeId, x, y, weights);
        }
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
        backdrop.release();
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
                synchronized (solverLock) {
                    solver = new CharacterSolver(next, pics);
                }
            }
            if (solver == null) return;
            Signals s = signals;
            CharacterSolver.Opt opt = new CharacterSolver.Opt();
            opt.live = live;
            opt.dt = dt;
            Map<String, String> f = force;
            if (!f.isEmpty()) opt.force = f;
            Map<String, Double> ps = poses;
            if (!ps.isEmpty()) opt.poses = ps;
            Map<String, Map<String, Double>> sh = shapes;
            if (!sh.isEmpty()) opt.shapes = sh;
            SolveOut out;
            synchronized (solverLock) {
                out = solver.eval(s, opt);
            }
            lastM = new HashMap<>(out.m);
            java.util.List<String> ord = new java.util.ArrayList<>();
            for (cc.joycreator.avatar.solve.Item it : out.items) if (!it.ghost) ord.add(it.id);
            lastOrder = ord;
            lastAnchor = new HashMap<>(s.anchor);
            lastTips = new HashMap<>(s.tips);
            float[] bp = backdropPic;
            if (bp != null) backdrop.draw(width, height, bp);
            gl.keep = bp != null;
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
            backdrop.release();
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
