package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.util.Log;

import cc.joycreator.avatar.draw.BackdropGl;
import cc.joycreator.avatar.draw.CharacterGl;
import cc.joycreator.avatar.solve.BitmapPics;
import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Item;
import cc.joycreator.avatar.solve.Signals;
import cc.joycreator.avatar.solve.SolveOut;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A scene on glass (the pilot's {@code scene.draw}): Video Studio's canvas texture, the plain stage where the picture is, then the
 * scene's layers back to front: backdrops and objects as pictures, each character solved by its own engine and drawn by the same
 * drawing as when it is alone ({@link CharacterGl}). {@link cc.joycreator.avatar.scene.SceneDraw} decides what goes where; this
 * only draws it. Everything handed over is a snapshot, so an edit in the room never races the GL thread.
 */
public final class SceneStage extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String TAG = "AvatarRoom";

    /** One thing to draw: a picture (quad) or a character. */
    public static final class Layer {
        public String img;
        public float[] v, uv;
        public int[] idx;
        /** Haze: {r, g, b, amount} or null. */
        public float[] tint;
        /** A character: its id, the view of its own drawing space on the glass {scale, ox, oy}, the signals and whether it moves. */
        public String actor;
        public float[] view;
        public Signals signals;
        public boolean live;
    }

    public interface Failure {
        void gpuFailed();
    }

    private static final class Actor {
        final BitmapPics pics;
        final CharacterSolver solver;
        double[] box;

        Actor(JSONObject json) {
            pics = new BitmapPics(json, null);
            solver = new CharacterSolver(json, pics);
        }
    }

    private final Object lock = new Object();
    private volatile List<Layer> layers = new ArrayList<>();
    private volatile float[] pic;
    private volatile float viewS = 1, viewX, viewY;                     // the stage's view for pictures (stage units to glass pixels)
    private volatile double dt = 1 / 60.0;
    private Map<String, JSONObject> pendingCast;
    private Map<String, Bitmap> pendingPics;
    private volatile Map<String, double[]> boxes = new HashMap<>();
    private volatile Failure failure;

    // owned by the GL thread
    private CharacterGl gl;
    private final BackdropGl backdrop = new BackdropGl();
    private BitmapPics scenePics;
    private final Map<String, Actor> actors = new HashMap<>();
    private int width, height;
    private boolean broken;

    public SceneStage(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new SolverStage.Chooser());
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setFailure(Failure f) {
        failure = f;
    }

    /** The characters in the scene, by cast item id (their whole files). A new map replaces the old one. */
    public void setCast(Map<String, JSONObject> docs) {
        synchronized (lock) {
            pendingCast = docs;
        }
        requestRender();
    }

    /** The scene's pictures (sets, objects), by id. Added to what is there. */
    public void addPictures(Map<String, Bitmap> pics) {
        synchronized (lock) {
            if (pendingPics == null) pendingPics = new HashMap<>();
            pendingPics.putAll(pics);
        }
        requestRender();
    }

    public void setTexture(Bitmap t) {
        backdrop.setTexture(t);
    }

    /** The rest box of each character once it has been solved: {x0, y0, x1, y1} in its own drawing space. */
    public Map<String, double[]> boxes() {
        return boxes;
    }

    /** One frame: what to draw, the picture's rectangle on the glass (null = no frame), the view for pictures. */
    public void frame(List<Layer> l, float[] picture, float scale, float ox, float oy, double dtSeconds) {
        layers = l;
        pic = picture;
        viewS = scale;
        viewX = ox;
        viewY = oy;
        dt = dtSeconds;
        requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 g, EGLConfig c) {
        if (gl != null) gl.release();
        gl = new CharacterGl();
        backdrop.release();
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
            Map<String, JSONObject> cast;
            Map<String, Bitmap> pics;
            synchronized (lock) {
                cast = pendingCast;
                pendingCast = null;
                pics = pendingPics;
                pendingPics = null;
            }
            if (pics != null) {
                if (scenePics == null) scenePics = new BitmapPics(new JSONObject(), null);
                for (Map.Entry<String, Bitmap> e : pics.entrySet()) scenePics.replace(e.getKey(), e.getValue());
            }
            if (cast != null) {
                actors.keySet().retainAll(cast.keySet());
                for (Map.Entry<String, JSONObject> e : cast.entrySet()) if (!actors.containsKey(e.getKey())) actors.put(e.getKey(), new Actor(e.getValue()));
                gl.forget();
            }
            float[] bp = pic;
            if (bp != null) backdrop.draw(width, height, bp);
            else {
                android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0);
                android.opengl.GLES20.glViewport(0, 0, width, height);
                android.opengl.GLES20.glClearColor(0, 0, 0, 0);
                android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT);
            }
            gl.keep = true;
            Map<String, double[]> nb = new HashMap<>(boxes);
            for (Layer l : layers) {
                final float[] tint = l.tint;
                CharacterGl.Tint tf = tint == null ? null : it -> it.ghost ? null : tint;
                if (l.actor != null) {
                    Actor a = actors.get(l.actor);
                    if (a == null) continue;
                    CharacterSolver.Opt opt = new CharacterSolver.Opt();
                    opt.live = l.live;
                    opt.dt = dt;
                    SolveOut out = a.solver.eval(l.signals == null ? new Signals() : l.signals, opt);
                    if (a.box == null) {
                        a.box = boxOf(out);
                        if (a.box != null) nb.put(l.actor, a.box);
                    }
                    if (l.view != null) gl.draw(out, a.pics, new CharacterGl.View(l.view[0], l.view[1], l.view[2]), tf, 0, width, height);
                } else if (l.img != null && scenePics != null && scenePics.has(l.img)) {
                    SolveOut out = new SolveOut();
                    Item it = new Item();
                    it.id = "q:" + l.img;
                    it.img = l.img;
                    it.v = l.v;
                    it.uv = l.uv;
                    it.idx = l.idx;
                    it.a = 1;
                    out.items.add(it);
                    gl.draw(out, scenePics, new CharacterGl.View(viewS, viewX, viewY), tf, 0, width, height);
                }
            }
            if (nb.size() != boxes.size()) boxes = nb;
        } catch (RuntimeException e) {
            Log.w(TAG, "the scene's GPU drawing stopped", e);
            broken = true;
            Failure f = failure;
            if (f != null) post(f::gpuFailed);
        }
    }

    private static double[] boxOf(SolveOut out) {
        double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
        for (Item it : out.items) {
            if (it.ghost || it.v == null) continue;
            for (int i = 0; i + 1 < it.v.length; i += 2) {
                x0 = Math.min(x0, it.v[i]);
                x1 = Math.max(x1, it.v[i]);
                y0 = Math.min(y0, it.v[i + 1]);
                y1 = Math.max(y1, it.v[i + 1]);
            }
        }
        return x0 > x1 ? null : new double[]{x0, y0, x1, y1};
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
}
