package cc.joycreator.avatar.scene;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * How a scene is laid out for drawing (the pilot's {@code al-scene-draw.js}: layers, paneQuad, objQuad, the view). Pure geometry: the
 * GL surface only draws what this hands it. Back to front: backdrops, then everything that stands (sorted by where its feet are:
 * higher on the floor = further back = earlier), then foregrounds. Far things take a little of the set's colour (haze).
 */
public final class SceneDraw {
    private SceneDraw() {
    }

    /** One picture to draw: posed vertices (x, y pairs, stage units), texture places, triangles, and its box. */
    public static final class Quad {
        public float[] v, uv;
        public int[] idx;
        public double[] box;
    }

    /** The scene's things in drawing order. */
    public static final class Layers {
        public final List<JSONObject> backs = new ArrayList<>(), stand = new ArrayList<>(), fronts = new ArrayList<>();
    }

    public static Layers layers(Scene s) {
        Layers l = new Layers();
        JSONArray a = s.items();
        final List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) continue;
            String k = it.optString("kind");
            if ("back".equals(k)) l.backs.add(it);
            else if ("front".equals(k)) l.fronts.add(it);
            else l.stand.add(it);
            idx.add(i);
        }
        final JSONArray items = a;
        java.util.Collections.sort(l.stand, (p, q) -> {
            int c = Double.compare(p.optDouble("y"), q.optDouble("y"));
            return c != 0 ? c : Integer.compare(indexOf(items, p), indexOf(items, q));
        });
        return l;
    }

    private static int indexOf(JSONArray a, JSONObject o) {
        for (int i = 0; i < a.length(); i++) if (a.opt(i) == o) return i;
        return -1;
    }

    /** The view that puts stage units on the glass: stage px = unit * s + (ox, oy). {@code f} is the glass pixels per unit. */
    public static double[] view(double f, double cx, double cy, SceneClock.Cam cam) {
        double s = f * cam.z;
        return new double[]{s, cx - cam.x * s, cy - cam.y * s};
    }

    /** The camera a backdrop or foreground sees: it slides less (far) or more (near) than the stage, which is what makes a close-up feel deep. */
    public static SceneClock.Cam paneCam(SceneClock.Cam cam, double par) {
        return new SceneClock.Cam(SceneGeometry.W / 2.0 + (cam.x - SceneGeometry.W / 2.0) * (1 - par), SceneGeometry.H / 2.0 + (cam.y - SceneGeometry.H / 2.0) * (1 - par), 1 + (cam.z - 1) * (1 - par));
    }

    /** A backdrop or foreground covers the stage whatever its own shape, with a little spare so the camera never finds its edge. */
    public static Quad pane(JSONObject it, int imgW, int imgH) {
        double k = Math.max(SceneGeometry.W / (double) imgW, SceneGeometry.H / (double) imgH) * 1.06 * sizeOf(it), w = imgW * k, h = imgH * k;
        double x0 = SceneGeometry.W / 2.0 + it.optDouble("x", 0) - w / 2, y0 = SceneGeometry.H / 2.0 + it.optDouble("y", 0) - h / 2;
        Quad q = new Quad();
        q.v = new float[]{(float) x0, (float) y0, (float) (x0 + w), (float) y0, (float) x0, (float) (y0 + h), (float) (x0 + w), (float) (y0 + h)};
        boolean flip = it.optBoolean("flip");
        q.uv = flip ? new float[]{1, 0, 0, 0, 1, 1, 0, 1} : new float[]{0, 0, 1, 0, 0, 1, 1, 1};
        q.idx = new int[]{0, 1, 2, 1, 3, 2};
        q.box = new double[]{x0, y0, x0 + w, y0 + h};
        return q;
    }

    private static double sizeOf(JSONObject it) {
        double s = it.optDouble("s", 1);
        return s == 0 || Double.isNaN(s) ? 1 : s;
    }

    /** An object stands on its bottom edge at (x, y); in the wind its top leans and its foot stays put. */
    public static Quad object(Scene sc, JSONObject it, int imgW, int imgH, double t) {
        double k = sizeOf(it) * sc.depth(it.optDouble("y")), w = imgW * k, h = imgH * k, x = it.optDouble("x"), y = it.optDouble("y");
        double sway = it.optDouble("sway", 0), wind = sc.doc.has("wind") ? sc.doc.optDouble("wind") : 0.5;
        int n = sway > 0 ? 6 : 1;
        float[] v = new float[(n + 1) * 4], uv = new float[(n + 1) * 4];
        int[] idx = new int[n * 6];
        double sw = sway > 0 ? (Math.sin(t * 1.6 + x * 0.013) * 0.6 + Math.sin(t * 2.9 + x * 0.021) * 0.3) * sway * wind * 0.14 * h : 0;
        boolean flip = it.optBoolean("flip");
        for (int r = 0; r <= n; r++) {
            double f = (double) r / n, up = 1 - f, dx = sw * up * up, yy = y - h * up;
            int o = r * 4;
            v[o] = (float) (x - w / 2 + dx);
            v[o + 1] = (float) yy;
            v[o + 2] = (float) (x + w / 2 + dx);
            v[o + 3] = (float) yy;
            uv[o] = flip ? 1 : 0;
            uv[o + 1] = (float) f;
            uv[o + 2] = flip ? 0 : 1;
            uv[o + 3] = (float) f;
        }
        for (int r = 0; r < n; r++) {
            int a = r * 2, q = r * 6;
            idx[q] = a;
            idx[q + 1] = a + 1;
            idx[q + 2] = a + 2;
            idx[q + 3] = a + 1;
            idx[q + 4] = a + 3;
            idx[q + 5] = a + 2;
        }
        Quad q = new Quad();
        q.v = v;
        q.uv = uv;
        q.idx = idx;
        q.box = new double[]{x - w / 2, y - h, x + w / 2, y};
        return q;
    }

    /** What far things take of the air: {r, g, b, amount} or null (nothing). Higher on the floor = further = more. */
    public static float[] haze(Scene sc, double y) {
        JSONObject made = sc.made();
        int i = made == null ? -1 : Scene.setIndex(made.optString("made"));
        if (i < 0) return null;
        double hk = sc.doc.has("haze") ? sc.doc.optDouble("haze") : 0.5, far = SceneGeometry.clamp(1 - (y / SceneGeometry.H - sc.far()) / (sc.near() - sc.far()), 0, 1) * hk * 0.22;
        if (far <= 0.01) return null;
        double[] c = Scene.HAZE[i];
        return new float[]{(float) c[0], (float) c[1], (float) c[2], (float) far};
    }

    /** A character's drawing space on the stage for the item. {@code box} = its rest box, {@code settings} its file's settings or null. */
    public static SceneGeometry.Place place(Scene sc, JSONObject it, double[] box, JSONObject settings) {
        double axis = settings != null && settings.has("axisX") ? settings.optDouble("axisX") : Double.NaN;
        JSONObject walk = settings == null ? null : settings.optJSONObject("walk");
        double ground = walk != null && walk.has("groundY") ? walk.optDouble("groundY") : Double.NaN;
        return SceneGeometry.place(it.optDouble("x"), it.optDouble("y"), it.optDouble("s", 1), sc.near(), sc.far(), sc.shrink(), box, axis, ground);
    }
}
