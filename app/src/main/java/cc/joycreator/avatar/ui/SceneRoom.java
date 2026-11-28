package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.file.CharacterStore;
import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.perform.TakeRecorder;
import cc.joycreator.avatar.scene.Scene;
import cc.joycreator.avatar.scene.SceneClock;
import cc.joycreator.avatar.scene.SceneDraw;
import cc.joycreator.avatar.scene.SceneFile;
import cc.joycreator.avatar.scene.SceneGeometry;
import cc.joycreator.avatar.solve.Signals;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.ui.faditor.Studio;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scene in hand (the pilot's one room with the scene in hand): its picture on the stage, and its own cards for the first three stages.
 * With a scene open the room shows this instead of the single character: {@link SceneStage} draws the set, the things that stand in
 * it and every character; a finger takes a thing and, in Arrange, moves it (up the floor sends it back, smaller, behind what stands in
 * front); the panel is the scene's Set up, Arrange and Motion cards. The empty scene (no scene open) is the room as it always was.
 *
 * <p>Scenes are kept in the Joy Creator folder as {@code <Name>.scene.json} and saved a moment after every change.</p>
 */
public final class SceneRoom {

    /** What the scene needs of the room. */
    public interface Host {
        Context ctx();

        void toast(String text);

        /** The scene came or went: the room shows its own views or the scene's. */
        void sceneShown(boolean shown);

        /** The four stage tabs should show this one chosen. */
        void stageChosen(int idx);

        /** A scene was written to the folder (the list of scenes may have changed). */
        void saved();

        /** A character is taken in hand: the room's own performer (stage, inputs, panel) works on it, with this take. */
        void handChanged(CharacterDoc doc, Take take);

        /** The room's own recorder (the one the Record and Play buttons run), for the one in hand. */
        TakeRecorder handRecorder();
    }

    private final Host host;
    private final Context c;
    private final float dp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SceneStage gl;
    private final Touch touch;
    private final FrameLayout view;
    private final ScrollView cardScroll;
    private final LinearLayout card;
    private Scene scene;
    private String path;
    private SceneClock clock;
    private int stage = 3;
    private String sel;
    private final Map<String, CharacterDoc> docs = new HashMap<>();
    private final Map<String, double[]> boxes = new HashMap<>();       // each thing's box on the stage {x0, y0, x1, y1}, for picking
    private final Map<String, Bitmap> pictures = new HashMap<>();
    private final Runnable saveLater = this::saveNow;
    private double t;
    private double f = 0.2, cx, cy;                                    // glass pixels per stage unit and the stage's centre on the glass
    private float[] frameRect;                                         // px, or null when the frame is off
    private boolean dirtyCard;
    private boolean toldNoFolder;
    private final Map<String, Take> takes = new HashMap<>();             // each character's take in this scene, by cast item id
    private String hand;                                                 // the cast item the room's performer is working on
    private cc.joycreator.avatar.solve.Signals handSig;
    private final ScenePlayers players = new ScenePlayers(new ScenePlayers.Env() {
        @Override
        public Scene scene() {
            return scene;
        }

        @Override
        public Map<String, CharacterDoc> docs() {
            return docs;
        }

        @Override
        public Map<String, Take> takes() {
            return takes;
        }

        @Override
        public String hand() {
            return hand;
        }

        @Override
        public TakeRecorder handRecorder() {
            return host.handRecorder();
        }

        @Override
        public double[] boxOf(String id) {
            return boxes.get(id);
        }
    });

    public SceneRoom(Host host, SceneStage gl) {
        this.host = host;
        this.c = host.ctx();
        this.dp = c.getResources().getDisplayMetrics().density;
        this.gl = gl;
        view = new FrameLayout(c);
        touch = new Touch(c);
        view.addView(gl, new FrameLayout.LayoutParams(-1, -1));
        view.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        view.setVisibility(View.GONE);
        cardScroll = new ScrollView(c);
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(12), px(8), px(12), px(14));
        cardScroll.addView(card);
        cardScroll.setVisibility(View.GONE);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    public View stageView() {
        return view;
    }

    public View cardView() {
        return cardScroll;
    }

    public boolean isOpen() {
        return scene != null;
    }

    public Scene scene() {
        return scene;
    }

    public SceneClock clock() {
        return clock;
    }

    public int stage() {
        return stage;
    }

    public boolean alive() {
        return stage >= 2;
    }

    // ── opening, making, leaving ──
    /** A new scene on a set ({@code setKey}: meadow, room, night, studio, or "plain"), with a name, kept in the folder at once. */
    public void create(String setKey, String name) {
        Scene s = Scene.blank(Long.toString(System.currentTimeMillis(), 36), name, setKey);
        show(s, null, new HashMap<>(), new HashMap<>());
        scheduleSave(0);
    }

    /** Opens one scene of the folder by its path (read off the main thread). */
    public void open(String p) {
        JoyFolderHome.io().execute(() -> {
            SceneFile.Parsed r = SceneStore.read(c, p);
            main.post(() -> {
                if (r == null || !r.ok()) {
                    host.toast(c.getString(com.fadcam.R.string.avatar_sc_not_opened));
                    return;
                }
                Map<String, CharacterDoc> carried = new HashMap<>();
                for (Map.Entry<String, JSONObject> e : r.characters.entrySet()) {
                    CharacterDoc d = cc.joycreator.avatar.file.CharacterOpen.read(e.getValue().toString());
                    if (d != null) carried.put(e.getKey(), d);
                }
                show(r.scene, p, carried, r.takes);
            });
        });
    }

    private void show(Scene s, String p, Map<String, CharacterDoc> carried, Map<String, Take> tk) {
        close(false);
        takes.clear();
        takes.putAll(tk);
        hand = null;
        scene = s;
        path = p;
        clock = new SceneClock(s);
        sel = null;
        boxes.clear();
        docs.clear();
        docs.putAll(carried);
        for (org.json.JSONObject it : s.items().length() > 0 ? items() : new ArrayList<org.json.JSONObject>()) {
            if (it.has("made")) {
                String img = "set:" + it.optString("made");
                try {
                    it.put("img", img);
                } catch (org.json.JSONException ignored) {
                }
                if (!pictures.containsKey(img)) pictures.put(img, SetPaint.paint(it.optString("made")));
            }
        }
        gl.addPictures(pictures);
        loadCast();
        view.setVisibility(View.VISIBLE);
        cardScroll.setVisibility(View.VISIBLE);
        host.sceneShown(true);
        stage = 3;
        host.stageChosen(stage);
        rebuild();
    }

    private List<JSONObject> items() {
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < scene.items().length(); i++) l.add(scene.items().optJSONObject(i));
        return l;
    }

    /** Leave the scene (it is kept) and be back on the empty scene. */
    public void close(boolean tell) {
        if (scene == null) return;
        main.removeCallbacks(saveLater);
        saveNow();
        scene = null;
        path = null;
        view.setVisibility(View.GONE);
        cardScroll.setVisibility(View.GONE);
        if (tell) host.sceneShown(false);
    }

    // ── the cast: each character's file, found by its uid or as a built-in one ──
    private void loadCast() {
        final Scene s = scene;
        JoyFolderHome.io().execute(() -> {
            List<CharacterStore.Entry> mine = null;
            Map<String, CharacterDoc> got = new HashMap<>();
            for (JSONObject it : s.cast()) {
                String id = it.optString("id");
                if (docs.containsKey(id)) continue;
                CharacterDoc d = null;
                try {
                    String stock = it.optString("stock", "");
                    if (stock.equals("pip")) d = CharacterAssets.load(c, CharacterAssets.PIP);
                    else if (stock.equals("topps")) d = CharacterAssets.load(c, CharacterAssets.TOPPS);
                    else if (it.has("uid")) {
                        if (mine == null) mine = CharacterStore.list(c);
                        for (CharacterStore.Entry e : mine) {
                            CharacterDoc x = CharacterStore.read(c, e.path);
                            if (x != null && it.optString("uid").equals(x.json.optString("uid"))) {
                                d = x;
                                break;
                            }
                        }
                    }
                } catch (java.io.IOException | org.json.JSONException ignored) {
                }
                if (d != null) got.put(id, d);
            }
            main.post(() -> {
                if (scene != s) return;
                docs.putAll(got);
                pushCast();
            });
        });
    }

    private void pushCast() {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        for (Map.Entry<String, CharacterDoc> e : docs.entrySet()) m.put(e.getKey(), e.getValue().json);
        gl.setCast(m);
        if (hand == null && scene != null) for (JSONObject it : scene.cast()) if (docs.containsKey(it.optString("id"))) {
            setHand(it.optString("id"));
            break;
        }
        dirtyCard = true;
    }

    // ── keeping it ──
    private void scheduleSave(long ms) {
        main.removeCallbacks(saveLater);
        main.postDelayed(saveLater, ms == 0 ? 400 : ms);
    }

    private void saveNow() {
        final Scene s = scene;
        if (s == null) return;
        final String p = path;
        final Map<String, JSONObject> chars = new LinkedHashMap<>();
        for (Map.Entry<String, CharacterDoc> e : docs.entrySet()) chars.put(e.getKey(), e.getValue().json);
        final Map<String, Take> tk = new HashMap<>(takes);
        JoyFolderHome.io().execute(() -> {
            String np = SceneStore.save(c, s, tk, chars, null, p);
            boolean first = p == null && np != null;
            if (np != null && scene == s) path = np;
            if (first) main.post(host::saved);
            if (np == null && !toldNoFolder) {
                toldNoFolder = true;
                main.post(() -> host.toast(c.getString(com.fadcam.R.string.avatar_sc_not_kept)));
            }
        });
    }

    /** Called after every change to the scene's document. */
    private void changed() {
        scheduleSave(1400);
        dirtyCard = true;
    }

    // ── what the stage shows: called once a frame by SceneUi, which knows where the frame is ──
    /**
     * @param glassW the stage's width and height in px
     * @param frame the picture's rectangle in px when the frame is on and the scene is alive; null otherwise
     * @param area the room the picture has in px (the stage less its tools), for a scene held still
     */
    public void tick(double dt, int glassW, int glassH, float[] frame, float[] area, float[] picture) {
        if (scene == null) return;
        t += dt;
        if (alive()) clock.tick(players, hand, dt);
        double[] fr = scene.frame();
        boolean live = alive();
        frameRect = frame;
        if (live && frame != null) {
            f = (frame[2] - frame[0]) / fr[2];
            cx = (frame[0] + frame[2]) / 2;
            cy = (frame[1] + frame[3]) / 2;
        } else {
            f = Math.min((area[2] - area[0]) / (SceneGeometry.W + 150.0), (area[3] - area[1]) / (SceneGeometry.H + 110.0));
            cx = (area[0] + area[2]) / 2;
            cy = (area[1] + area[3]) / 2;
        }
        double[] V = SceneDraw.view(f, cx, cy, new SceneClock.Cam());
        List<SceneStage.Layer> out = new ArrayList<>();
        SceneDraw.Layers L = SceneDraw.layers(scene);
        for (JSONObject it : L.backs) pane(out, it, 0.22, V, true);
        Map<String, double[]> rest = gl.boxes();
        for (JSONObject it : L.stand) {
            if ("cast".equals(it.optString("kind"))) actor(out, it, V, rest);
            else obj(out, it, V);
        }
        for (JSONObject it : L.fronts) pane(out, it, -0.15, V, false);
        gl.frame(out, picture, (float) V[0], (float) V[1], (float) V[2], dt);
        if (dirtyCard && stage != 3) {
            dirtyCard = false;
            rebuild();
        }
        touch.v = V;
        touch.invalidate();
    }

    private void pane(List<SceneStage.Layer> out, JSONObject it, double dpar, double[] V, boolean back) {
        String img = it.optString("img", "");
        Bitmap b = pictures.get(img);
        if (b == null) return;
        SceneDraw.Quad q = SceneDraw.pane(it, b.getWidth(), b.getHeight());
        double par = it.has("par") ? it.optDouble("par") : dpar;
        double[] Vp = SceneDraw.view(f, cx, cy, SceneDraw.paneCam(new SceneClock.Cam(), par));
        SceneStage.Layer l = new SceneStage.Layer();
        l.img = img;
        l.idx = q.idx;
        l.uv = q.uv;
        l.v = new float[q.v.length];
        for (int i = 0; i < q.v.length; i += 2) {
            l.v[i] = (float) ((q.v[i] * Vp[0] + Vp[1] - V[1]) / V[0]);
            l.v[i + 1] = (float) ((q.v[i + 1] * Vp[0] + Vp[2] - V[2]) / V[0]);
        }
        boxes.put(it.optString("id"), q.box);
        out.add(l);
    }

    private void obj(List<SceneStage.Layer> out, JSONObject it, double[] V) {
        String img = it.optString("img", "");
        Bitmap b = pictures.get(img);
        if (b == null) return;
        SceneDraw.Quad q = SceneDraw.object(scene, it, b.getWidth(), b.getHeight(), t);
        SceneStage.Layer l = new SceneStage.Layer();
        l.img = img;
        l.v = q.v;
        l.uv = q.uv;
        l.idx = q.idx;
        l.tint = SceneDraw.haze(scene, it.optDouble("y"));
        boxes.put(it.optString("id"), q.box);
        out.add(l);
    }

    private void actor(List<SceneStage.Layer> out, JSONObject it, double[] V, Map<String, double[]> rest) {
        String id = it.optString("id");
        CharacterDoc d = docs.get(id);
        if (d == null) return;
        SceneStage.Layer l = new SceneStage.Layer();
        l.actor = id;
        l.live = alive();
        java.util.Map<String, Object> m = id.equals(hand) ? null : players.signalsFor(id);
        l.signals = id.equals(hand) && handSig != null ? handSig : m != null ? SignalMap.toSignals(m, t, 0) : new Signals();
        double[] box = rest.get(id);
        if (box != null) {
            SceneGeometry.Place p = SceneDraw.place(scene, it, box, d.json.optJSONObject("settings"));
            l.view = new float[]{(float) (V[0] * p.k), (float) (V[1] + V[0] * p.tx), (float) (V[2] + V[0] * p.ty)};
            l.tint = SceneDraw.haze(scene, it.optDouble("y"));
            boxes.put(id, new double[]{box[0] * p.k + p.tx, box[1] * p.k + p.ty, box[2] * p.k + p.tx, box[3] * p.k + p.ty});
        }
        out.add(l);
    }

    // ── the stages ──
    public void setStage(int i) {
        stage = i;
        if (stage != 1) touch.drag = null;
        rebuild();
    }

    // ── fingers ──
    private final class Touch extends View {
        double[] v;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String drag;
        private float x0, y0;
        private double ix, iy;
        private boolean moved;

        Touch(Context c) {
            super(c);
        }

        private double ux(float px) {
            return (px - v[1]) / v[0];
        }

        private double uy(float py) {
            return (py - v[2]) / v[0];
        }

        private String pick(float px, float py) {
            double wx = ux(px), wy = uy(py);
            String best = null;
            double by = -1e9;
            for (JSONObject it : items()) {
                String k = it.optString("kind");
                if (!(k.equals("cast") || k.equals("obj"))) continue;
                double[] b = boxes.get(it.optString("id"));
                if (b == null || wx < b[0] || wx > b[2] || wy < b[1] || wy > b[3]) continue;
                if (it.optDouble("y") > by) {
                    by = it.optDouble("y");
                    best = it.optString("id");
                }
            }
            return best;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (scene == null || v == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    String id = pick(e.getX(), e.getY());
                    if (id != null && !id.equals(sel)) {
                        select(id);
                        dirtyCard = true;
                    } else if (id == null && sel != null) {
                        sel = null;
                        dirtyCard = true;
                    }
                    drag = id != null && stage == 1 ? id : null;
                    moved = false;
                    if (drag != null) {
                        JSONObject it = scene.item(drag);
                        x0 = e.getX();
                        y0 = e.getY();
                        ix = it.optDouble("x");
                        iy = it.optDouble("y");
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (drag != null) {
                        JSONObject it = scene.item(drag);
                        if (it == null) return true;
                        if (!moved) {
                            if (Math.hypot(e.getX() - x0, e.getY() - y0) < 4 * dp) return true;
                            moved = true;
                            scene.snap("Move " + it.optString("name"));            // the drag is one undo step
                        }
                        try {
                            boolean stands = it.optString("kind").equals("cast") || it.optString("kind").equals("obj");
                            double nx = ix + (e.getX() - x0) / v[0], ny = iy + (e.getY() - y0) / v[0];
                            it.put("x", Math.round(stands ? SceneGeometry.clamp(nx, -500, SceneGeometry.W + 500) : nx));
                            it.put("y", Math.round(stands ? SceneGeometry.clamp(ny, scene.far() * SceneGeometry.H - 70, SceneGeometry.H * 1.14) : ny));
                        } catch (org.json.JSONException ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (drag != null && moved) changed();
                    drag = null;
                    return true;
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (scene == null || sel == null || v == null || stage == 3) return;
            double[] b = boxes.get(sel);
            if (b == null) return;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * dp);
            p.setColor(Studio.ARMED);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
            cv.drawRect((float) (b[0] * v[0] + v[1]), (float) (b[1] * v[0] + v[2]), (float) (b[2] * v[0] + v[1]), (float) (b[3] * v[0] + v[2]), p);
            JSONObject it = scene.item(sel);
            if (it != null && !it.optString("kind").equals("back")) {
                p.setPathEffect(null);
                p.setStyle(Paint.Style.FILL);
                float fx = (float) (it.optDouble("x") * v[0] + v[1]), fy = (float) (it.optDouble("y") * v[0] + v[2]);
                cv.drawCircle(fx, fy, 3.5f * dp, p);                    // where it stands
            }
        }
    }

    // ── the scene's cards ──
    private void rebuild() {
        card.removeAllViews();
        if (scene == null) return;
        new SceneCards(this, c).fill(card, stage);
    }

    void refresh() {
        rebuild();
    }

    String selected() {
        return sel;
    }

    void select(String id) {
        sel = id;
        JSONObject it = id == null ? null : scene.item(id);
        if (it != null && "cast".equals(it.optString("kind"))) setHand(id);
        rebuild();
    }

    /** The room's performer takes this character: Perform's controls (record, play, the joystick) work on it. */
    void setHand(String id) {
        if (id == null || id.equals(hand) || !docs.containsKey(id)) return;
        hand = id;
        host.handChanged(docs.get(id), takes.get(id));
        dirtyCard = true;
    }

    public String hand() {
        return hand;
    }

    /** Called by the room each frame with what the performer reads for the one in hand. */
    public void handSignals(cc.joycreator.avatar.solve.Signals s) {
        handSig = s;
    }

    /** A pass ended or a take was taken back or cleared: the one in hand's take is the scene's, kept with it. */
    public void takeKept(Take tk) {
        if (hand == null) return;
        if (tk == null || tk.n == 0) takes.remove(hand);
        else takes.put(hand, tk.copy());
        players.forget(hand);
        changed();
    }

    /** Play everyone from the start, together on the one clock. */
    public void playAll() {
        if (clock == null) return;
        host.handRecorder().stop();
        clock.forgetCamPass();
        clock.playAll(players);
    }

    public void stopAll() {
        if (clock != null) clock.stop(players, hand);
        host.handRecorder().stop();
    }

    public boolean playingAll() {
        return clock != null && clock.runPlay;
    }

    public boolean anyTake() {
        return !takes.isEmpty() || (host.handRecorder().take() != null && hand != null);
    }

    Map<String, CharacterDoc> docs() {
        return docs;
    }

    void edited() {
        changed();
        rebuild();
    }

    Map<String, Bitmap> pictures() {
        return pictures;
    }

    void setPicture(String id, Bitmap b) {
        pictures.put(id, b);
        gl.addPictures(pictures);
    }

    void castChanged() {
        loadCast();
    }

    String pathNow() {
        return path;
    }

    void deleteScene() {
        final String p = path;
        close(true);
        if (p != null) JoyFolderHome.io().execute(() -> SceneStore.delete(c, p));
    }

    /** What the strip shows of the scene: changes when who is in it, who is in hand or its name changes. */
    public String signature() {
        if (scene == null) return "";
        StringBuilder b = new StringBuilder(scene.name()).append('|').append(sel).append('|').append(path);
        for (JSONObject it : scene.cast()) b.append('|').append(it.optString("id")).append(it.optString("name"));
        return b.toString();
    }

    public void setTexture(Bitmap b) {
        gl.setTexture(b);
    }

    public void addCharacterSheet() {
        if (scene != null) new SceneCards(this, c).addCharacter(scene);
    }

    Host host() {
        return host;
    }

    /** A thing's box on the stage, for the cards. */
    double[] boxOf(String id) {
        return boxes.get(id);
    }

    RectF frameRect() {
        return frameRect == null ? null : new RectF(frameRect[0], frameRect[1], frameRect[2], frameRect[3]);
    }
}
