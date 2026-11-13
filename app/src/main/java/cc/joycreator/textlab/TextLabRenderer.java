package cc.joycreator.textlab;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.opengl.GLES30;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.gl.ShaderLib;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * TextLab's look engine on the phone: the twin of {@code TL.Engine} in
 * {@code tools/textlab-web/tl-engine.js}, pass for pass, reading the same shader, knob and look files
 * from {@code assets/textlab}. One frame is a fixed row of passes; a look only chooses which shader
 * variant, which knobs, which sphere, which particles and how letters arrive.
 *
 * <pre>
 *   backdrop  the stage behind the words                 -> back
 *   compose   every letter's distance picture into the FIELD (nearest letter wins)
 *   soften    optional: blur the distances (melt, round) and wobble them (liquid)
 *   fire      optional: fixed 1/60 s ticks of the heat picture
 *   shade     the letters, lit                           -> scene (linear, premultiplied, HDR)
 *   fire draw flame and smoke over them                  -> scene
 *   particles embers, glints, drops                      -> scene
 *   bloom     light above the threshold, spread
 *   final     over the backdrop, wet floor, film curve   -> screen
 *   fxaa      smooth the steps where two lit faces meet  -> out (what is shown and saved)
 * </pre>
 *
 * <p>GL thread only. The light position is an input ({@link #tilt}): an export never reads a sensor.</p>
 */
public final class TextLabRenderer {

    private static final String[] NO_DEFINES = {};

    // ---- assets -------------------------------------------------------------------------------

    /** The shader, knob and tube files, read once (safe off the GL thread). */
    public static final class Assets {
        final ShaderLib shaders;
        final JSONObject parts;
        final JSONObject tubefont;

        Assets(ShaderLib shaders, JSONObject parts, JSONObject tubefont) {
            this.shaders = shaders;
            this.parts = parts;
            this.tubefont = tubefont;
        }

        @NonNull
        public static Assets load(@NonNull AssetManager am) throws IOException, JSONException {
            Map<String, String> src = new HashMap<>();
            String[] names = am.list("textlab/shaders");
            if (names != null) for (String n : names) src.put(n, ShaderLib.readAll(am.open("textlab/shaders/" + n)));
            return new Assets(ShaderLib.of(src),
                    new JSONObject(ShaderLib.readAll(am.open("textlab/parts.json"))),
                    new JSONObject(ShaderLib.readAll(am.open("textlab/tubefont.json"))));
        }

        /** A look file by its id ({@code looks/<id>.text.json}). */
        @NonNull
        public static JSONObject look(@NonNull AssetManager am, @NonNull String id) throws IOException, JSONException {
            return new JSONObject(ShaderLib.readAll(am.open("textlab/looks/" + id + ".text.json")));
        }
    }

    // ---- state --------------------------------------------------------------------------------

    private final TlGl glw;
    private final TlGlyphs glyphs;
    private final JSONObject parts;
    private final JSONObject knobs, particleKnobs;

    @Nullable private JSONObject look;
    private final Map<String, Object> over = new HashMap<>();
    private String text = "TEXT";
    /** The stage behind the words (the look's own unless set). */
    public double stage = 1;
    /** Where the light is, -1..1 each way. An input: the caller decides (a sensor live, a fixed value in export). */
    public double[] tilt = {0, 0};
    /** What the finished picture is for. */
    public static final int FOR_SCREEN = 0, LINEAR_CLEAR = 1, CLEAR = 2;
    /**
     * {@link #FOR_SCREEN}: over the look's own stage, film finish, smoothed edges (the look book's picture).
     * {@link #CLEAR}: the words alone, see-through, with the look's finish, 8-bit premultiplied: what Studio lays
     * over video. {@link #LINEAR_CLEAR}: the words alone as linear light (half-float), for a GL compositor that
     * applies the finish itself. Both clear ones skip the edge smoothing (it would fill in the see-through).
     */
    public int output = FOR_SCREEN;
    /** false: no stage behind the words (black), for checks. The clear outputs never have one. */
    public boolean hasBack = true;
    /** false: no edge smoothing on the screen picture, for checks. */
    public boolean fxaa = true;
    private int maxW = 1280, maxH = 720;
    private double emPx;
    private int emCap = 2048;

    private TlText.Layout lay;
    private double em;
    private int W, H;
    private double[] fieldU;
    private final TlGl.Target sphere, black;
    private TlGl.Target field, fa, fb, scene, back, screen, out, outHdr;
    /**
     * The 32-bit layers of the speed work (T13, 2026-11-13, twin of tl-engine.js): where each pixel's ray meets the
     * letter (the march pass), and the wobble's offset worked out once. Null when this GPU cannot draw into 32-bit
     * floats: then every look draws the old way (the same picture, only slower).
     */
    @Nullable private TlGl.Target march, wobT;
    /** Where this frame's shape work left the field (a still runs the fire on it). */
    private TlGl.Target shapedField;
    private TlGl.Target[] heat, bloom;
    private int emit;
    private int photo;
    private double photoAspect;

    private boolean dirty = true, sphereDirty = true, resetFire = true, emitDirty;
    private int rivetCount;
    private double simTime, simDebt;
    @Nullable private Double clock0;
    private double last;
    private double lookTime;

    public TextLabRenderer(@NonNull Assets assets, @NonNull android.content.Context context) {
        TlTrouble.check();   // a phone that cannot draw the looks says so in words, before anything is built
        glw = new TlGl(assets.shaders);
        parts = assets.parts;
        knobs = parts.optJSONObject("knobs");
        particleKnobs = parts.optJSONObject("particleKnobs");
        glyphs = new TlGlyphs(glw, new TlFonts(context));
        glyphs.tubefont = assets.tubefont;
        sphere = glw.target(512, 512, true, false);
        black = glw.target(1, 1, false, false);
        glw.bind(black);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
    }

    public void setLook(@NonNull JSONObject l) {
        look = l;
        over.clear();
        dirty = true;
        stage = l.optDouble("stage", 1);
        replay();
    }

    public void setText(@NonNull String t) {
        if (!t.equals(text)) {
            text = t;
            dirty = true;
        }
    }

    /** The largest picture, px; the field keeps its own shape inside it. */
    public void setSize(int w, int h) {
        if (w != maxW || h != maxH) {
            maxW = w;
            maxH = h;
            dirty = true;
        }
    }

    /**
     * Draw at a fixed size: {@code px} pixels to the em (a Studio title's letter size), the picture as large as the
     * words and the look's margins need, no side over {@code cap}. {@code px <= 0} returns to {@link #setSize}.
     */
    public void setEm(double px, int cap) {
        if (Math.abs(px - emPx) > 0.25 || cap != emCap) {
            emPx = px;
            emCap = cap;
            dirty = true;
        }
    }

    /** The em the last picture was drawn at, px (a title places the picture by it). */
    public double em() {
        return em;
    }

    /** The field the words sit in, em: width, height. */
    @NonNull
    public double[] fieldEm() {
        return lay == null ? new double[]{0, 0} : new double[]{lay.fieldW, lay.fieldH};
    }

    public void setKnob(@NonNull String name, @NonNull Object value) {
        if (value.equals(over.get(name))) return;   // set every frame by Studio: only a change counts
        over.put(name, value);
        sphereDirty = true;
        // rivets sit at a distance from the outline
        if (name.startsWith("rivet") || name.equals("weight") || name.equals("stroke")) emitDirty = true;
    }

    public void replay() {
        clock0 = null;
        resetFire = true;
    }

    /** The person's own picture behind the words (stage 11). */
    public void setPhoto(@NonNull Bitmap bmp) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + 5);
        photo = TlGl.bitmapTexture(bmp, photo);
        photoAspect = (double) bmp.getWidth() / bmp.getHeight();
    }

    public int width() {
        return W;
    }

    public int height() {
        return H;
    }

    public double lookTime() {
        return lookTime;
    }

    /** The layout's numbers, to set beside the look book's when a still does not match. */
    @NonNull
    public String layoutReport() {
        if (lay == null) return "no layout";
        StringBuilder s = new StringBuilder(String.format(java.util.Locale.ROOT, "field %.6f x %.6f em, cap %.6f, em %.4f px;",
                lay.fieldW, lay.fieldH, lay.cap, em));
        TlText.Font font = TlText.Font.of(look.optJSONObject("font"));
        for (TlText.Letter l : lay.letters) {
            TlGlyphs.Metrics m = glyphs.measure(font, l.ch);
            s.append(String.format(java.util.Locale.ROOT, " [%s %.5f %.5f %.4f %.4f e%d c%d | w%.3f a%.3f d%.3f l%.3f r%.3f]", l.ch, l.x, l.y,
                    l.piece.cellW, l.piece.cellH, l.piece.points.edge.length / 4, l.piece.points.corner.length / 4,
                    m.width, m.ascent, m.descent, m.left, m.right));
        }
        return s.toString();
    }

    /** The finished picture (RGBA8, W x H, rows bottom to top), ready to show or read back. */
    public int outputFramebuffer() {
        if (output == LINEAR_CLEAR && outHdr != null) return outHdr.fbo();
        return out != null ? out.fbo() : 0;
    }

    private double[] photoFit() {
        double fieldA = (double) W / H, pic = photoAspect > 0 ? photoAspect : fieldA;
        return fieldA > pic ? new double[]{1, pic / fieldA} : new double[]{fieldA / pic, 1};
    }

    // ---- knobs ----------------------------------------------------------------------------------

    @NonNull
    private Object knobValue(@NonNull String name) {
        Object o = over.get(name);
        if (o != null) return o;
        JSONObject p = look != null ? look.optJSONObject("p") : null;
        if (p != null && p.has(name)) return p.opt(name);
        JSONObject k = knobs.optJSONObject(name);
        if (k == null) return 0.0;
        return k.has("c") ? k.opt("c") : k.opt("v");
    }

    /** A knob's value now (the person's, else the look's, else its default). */
    public double knob(@NonNull String name) {
        return TlValues.num(knobValue(name), 0);
    }

    private interface Getter {
        Object get(String name, JSONObject k);
    }

    /** Knob values gathered into uniforms: { uBevel: [0.04], uExtrude: [0.3, -0.3], uFillA: [r, g, b] }. */
    @NonNull
    private static Map<String, Object> uniformsFrom(@NonNull JSONObject schema, @NonNull Getter get) {
        Map<String, Object> map = new HashMap<>();
        Map<String, double[]> nums = new HashMap<>();
        for (Iterator<String> it = schema.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject k = schema.optJSONObject(name);
            if (k == null) continue;
            String u = k.optString("u", "");
            if (u.isEmpty()) continue;
            Object v = get.get(name, k);
            if (k.has("c")) {
                map.put(u, TlValues.colour(String.valueOf(v)));
            } else {
                int i = k.optInt("i", 0);
                double[] a = nums.get(u);
                if (a == null || a.length <= i) {
                    double[] b = new double[i + 1];
                    if (a != null) System.arraycopy(a, 0, b, 0, a.length);
                    a = b;
                    nums.put(u, a);
                }
                a[i] = TlValues.num(v, 0);
            }
        }
        map.putAll(nums);
        return map;
    }

    /** {@code arr} grown or cut to {@code n} numbers, the new ones 0. */
    private static double[] sized(@Nullable Object arr, int n) {
        double[] out = new double[n];
        if (arr instanceof double[]) {
            double[] a = (double[]) arr;
            System.arraycopy(a, 0, out, 0, Math.min(n, a.length));
        }
        return out;
    }

    @NonNull
    private List<String> features() {
        List<String> out = new ArrayList<>();
        JSONArray f = look != null ? look.optJSONArray("features") : null;
        if (f != null) for (int i = 0; i < f.length(); i++) out.add(f.optString(i));
        return out;
    }

    // ---- build and the sphere ---------------------------------------------------------------

    private void freeTargets() {
        TlGl.Target[] single = {field, fa, fb, scene, back, screen, out, outHdr, march, wobT};
        for (TlGl.Target t : single) glw.free(t);
        if (heat != null) for (TlGl.Target t : heat) glw.free(t);
        if (bloom != null) for (TlGl.Target t : bloom) glw.free(t);
    }

    private void build() {
        TlText.Font font = TlText.Font.of(look.optJSONObject("font"));
        double[] margin = {0.7, 0.7, 0.7, 0.7};
        JSONArray m = look.optJSONArray("margin");
        if (m != null && m.length() == 4) for (int i = 0; i < 4; i++) margin[i] = m.optDouble(i);
        lay = glyphs.layout(text, font, margin);
        em = Math.min(maxW / lay.fieldW, maxH / lay.fieldH);
        // a title in Studio asks for its letter size; the picture is then as wide as the words need (capped)
        if (emPx > 0) em = Math.min(emPx, Math.min(emCap / lay.fieldW, emCap / lay.fieldH));
        W = Math.max(8, (int) Math.round(lay.fieldW * em));
        H = Math.max(8, (int) Math.round(lay.fieldH * em));
        freeTargets();
        field = glw.target(W, H, true, true);
        fa = glw.target(W, H, true, false);
        fb = glw.target(W, H, true, false);
        scene = glw.target(W, H, true, false);
        back = glw.target(W, H, true, false);
        screen = glw.target(W, H, false, false);
        out = glw.target(W, H, false, false);
        try {
            march = glw.floatTarget(W, H);
            wobT = glw.floatTarget(W, H);
        } catch (RuntimeException e) {
            glw.free(march);
            march = null;
            wobT = null;
            android.util.Log.w("TextLab", "no 32-bit float targets here: looks draw the old way", e);
        }
        outHdr = null;   // made when a linear clear picture is first asked for
        // the fire's heat picture has a fixed fineness (640 across at most): the same flame, and the
        // same cost, whether the picture is a phone preview or a 4K still
        double hw = Math.min(W / 2.0, 640), hh = hw * H / W;
        heat = new TlGl.Target[]{glw.target(hw, hh, true, false), glw.target(hw, hh, true, false)};
        bloom = new TlGl.Target[5];
        for (int i = 1; i <= 5; i++) bloom[i - 1] = glw.target(W >> i, H >> i, true, false);
        buildEmitter();
        fieldU = new double[]{lay.fieldW, lay.fieldH, em, TlGlyphs.SPREAD};
        dirty = false;
        sphereDirty = true;
        resetFire = true;
    }

    /** A particle system that takes its points from the ordered list ({@code from} 4: the rivets). */
    private static boolean fromList(@NonNull JSONObject ps) {
        JSONObject p = ps.optJSONObject("p");
        Object f = p != null ? p.opt("from") : null;
        return f instanceof Number && ((Number) f).doubleValue() == 4;
    }

    /**
     * The emitter's points, and the RIVETS (only for a look that asks for them, with {@code from} 4): a line a
     * fixed distance in from the outline (by default the middle of the raised frame), one at every corner, the
     * rest evenly spaced between. Rebuilt when the frame, the weight or the rivet sliders change.
     */
    private void buildEmitter() {
        boolean wants = false;
        JSONArray ps = look.optJSONArray("particles");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject o = ps.optJSONObject(i);
            if (o != null && fromList(o)) wants = true;
        }
        List<List<double[]>> rivets = null;
        if (wants) {
            double level = knob("rivetInset") - knob("weight") - knob("stroke"), gap = Math.max(knob("rivetGap"), 0.08);
            rivets = new ArrayList<>();
            for (TlText.Letter l : lay.letters) {
                rivets.add(l.piece.pic != null ? TlRivets.of(l.piece.pic, level, gap) : new ArrayList<>());
            }
        }
        TlGlyphs.Emit e = TlGlyphs.emitterPoints(lay.letters, rivets);
        emit = glw.pointTexture(TlGlyphs.Emit.W, TlGlyphs.Emit.H, e.data, emit);
        rivetCount = e.rivets;
        emitDirty = false;
    }

    private static List<Object> list(@Nullable JSONArray a) {
        List<Object> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.opt(i));
        return out;
    }

    private static JSONArray arr(Object... xs) {
        return new JSONArray(java.util.Arrays.asList(xs));
    }

    private static Object or(@NonNull JSONObject s, @NonNull String key, @NonNull Object dflt) {
        return s.has(key) ? s.opt(key) : dflt;
    }

    private void bakeSphere() {
        JSONObject s = look.optJSONObject("sphere");
        if (s == null) s = new JSONObject();
        List<Object> ramp = list(s.optJSONArray("ramp"));
        if (ramp.isEmpty()) {
            ramp.add(arr(0, "#101010"));
            ramp.add(arr(1, "#f0f0f0"));
        }
        if (ramp.size() > 8) ramp = ramp.subList(0, 8);
        while (ramp.size() < 8) {
            JSONArray lastR = (JSONArray) ramp.get(ramp.size() - 1);
            ramp.add(arr(1.0001 + ramp.size() * 1e-4, lastR.opt(1)));
        }
        List<Object> lamps = list(s.optJSONArray("lamps"));
        if (lamps.size() > 4) lamps = lamps.subList(0, 4);
        while (lamps.size() < 4) lamps.add(arr(0, 0, 1, 0, "#000000"));
        // softbox: [x, y, half width, half height, turn, softness, strength, colour, brighter middle]
        List<Object> boxes = list(s.optJSONArray("boxes"));
        if (boxes.size() > 4) boxes = boxes.subList(0, 4);
        while (boxes.size() < 4) boxes.add(arr(0, 0, 0.1, 0.1, 0, 0.05, 0, "#000000", 0));

        List<Object> rampCol = new ArrayList<>(), rampPos = new ArrayList<>();
        for (Object r : ramp) {
            rampPos.add(((JSONArray) r).opt(0));
            rampCol.add(((JSONArray) r).opt(1));
        }
        List<Object> lamp = new ArrayList<>(), lampCol = new ArrayList<>();
        for (Object o : lamps) {
            JSONArray l = (JSONArray) o;
            for (int i = 0; i < 4; i++) lamp.add(l.opt(i));
            lampCol.add(l.opt(4));
        }
        List<Object> box = new ArrayList<>(), boxB = new ArrayList<>(), boxCol = new ArrayList<>();
        for (Object o : boxes) {
            JSONArray b = (JSONArray) o;
            for (int i = 0; i < 4; i++) box.add(b.opt(i));
            boxB.add(b.opt(4));
            boxB.add(b.opt(5));
            boxB.add(b.opt(6));
            Object mid = b.opt(8);
            boxB.add(mid instanceof Number && ((Number) mid).doubleValue() != 0 ? mid : 0);
            boxCol.add(b.opt(7));
        }
        TlProgram p = glw.program("quad.vert", "matcap.frag", Collections.emptyList()).use();
        p.set("uRamp", rampCol).set("uRampPos", rampPos);
        p.set("uJag", or(s, "jag", new double[]{0.5, 0, 6, 0.08}));
        p.set("uLamp", lamp).set("uLampCol", lampCol);
        p.set("uBox", box).set("uBoxB", boxB).set("uBoxCol", boxCol);
        p.set("uUneven", or(s, "uneven", 0));
        p.set("uStrips", or(s, "strips", new double[]{0, 6, 0.55, 0.2})).set("uRimCol", or(s, "rim", "#000000"));
        p.set("uIrid", or(s, "irid", new double[]{0, 1.5})).set("uGain", or(s, "gain", 1));
        GLES30.glDisable(GLES30.GL_BLEND);
        glw.pass(sphere);
        glw.mips(sphere);
        sphereDirty = false;
    }

    // ---- motion ---------------------------------------------------------------------------------

    private static double clamp01(double x) {
        return Math.min(1, Math.max(0, x));
    }

    private static double ease(@Nullable String name, double k) {
        if (name == null) name = "out";
        switch (name) {
            case "linear": return k;
            case "inout": return k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
            case "back": return 1 + 2.70158 * Math.pow(k - 1, 3) + 1.70158 * Math.pow(k - 1, 2);
            case "bounce": {
                double n = 7.5625, d = 2.75;
                if (k < 1 / d) return n * k * k;
                if (k < 2 / d) { k -= 1.5 / d; return n * k * k + 0.75; }
                if (k < 2.5 / d) { k -= 2.25 / d; return n * k * k + 0.9375; }
                k -= 2.625 / d;
                return n * k * k + 0.984375;
            }
            case "spring": return 1 - Math.exp(-6 * k) * Math.cos(11 * k);
            case "out":
            default: return 1 - Math.pow(1 - k, 3);
        }
    }

    private static final class Pose {
        double[] move, scale;
        double turn, reveal, weight;
    }

    /** Where letter i is at look-time t: how it arrives, then how it idles. */
    @NonNull
    private Pose pose(int i, int n, double t) {
        JSONObject m = look.optJSONObject("motion");
        if (m == null) m = new JSONObject();
        JSONObject a = m.optJSONObject("in"), idle = m.optJSONObject("idle");
        if (a == null) a = new JSONObject();
        if (idle == null) idle = new JSONObject();
        double each = a.optDouble("each", 0.06), time = a.optDouble("time", 0.4);
        String from = a.optString("from", "");
        double start = ("end".equals(from) ? n - 1 - i : "middle".equals(from) ? Math.abs(i - (n - 1) / 2.0) : i) * each;
        double lin = time > 0 ? clamp01((t - start) / time) : 1;
        String easeName = a.has("ease") ? a.optString("ease") : null;
        double k = ease(easeName, lin), backK = 1 - k;
        JSONArray rv = a.optJSONArray("reveal");
        double rv0 = rv != null ? rv.optDouble(0) : 0, rv1 = rv != null ? rv.optDouble(1) : time;
        double reveal = rv1 > 0 ? clamp01((t - start - rv0) / rv1) : 1;
        double ph = t * idle.optDouble("speed", 1) * 6.2832 - i * idle.optDouble("phase", 0.6);
        double settled = clamp01((t - start - time) / 0.4);
        double sc = 1 + (a.optDouble("scale", 1) - 1) * backK + idle.optDouble("pulse", 0) * Math.sin(ph) * settled;
        Pose p = new Pose();
        p.move = new double[]{a.optDouble("x", 0) * backK, a.optDouble("y", 0) * backK + idle.optDouble("bob", 0) * Math.sin(ph) * settled};
        p.scale = new double[]{sc, sc};
        p.turn = a.optDouble("turn", 0) * backK + idle.optDouble("sway", 0) * Math.sin(ph * 0.7 + 1.0) * settled;
        p.reveal = reveal;
        p.weight = idle.optDouble("breathe", 0) * Math.sin(ph * 0.5) * settled;
        return p;
    }

    // ---- shatter --------------------------------------------------------------------------------

    /** How far the word has broken, 0 whole .. 1 gone: the slider, or by itself. */
    private double brokenNow(double t) {
        double auto = knob("shatterAuto"), base = knob("shatter");
        if (auto <= 0) return base;
        double p = (t * auto * 0.11) % 1;
        return clamp01(base + (p < 0.18 ? 0 : p < 0.55 ? (p - 0.18) / 0.37 : p < 0.65 ? 1 : 1 - (p - 0.65) / 0.35));
    }

    private static final class Shards {
        int n;
        double[][] seeds;
        double[][] r;
        double start;
    }

    @NonNull
    private Shards shards(@NonNull TlText.Letter l) {
        int n = (int) Math.round(Math.min(8, Math.max(3, knob("shatterPieces"))));
        if (l.shards instanceof Shards && ((Shards) l.shards).n == n) return (Shards) l.shards;
        final long[] seed = {(long) (l.index + 1) * 7919 + n * 31L + l.order * 101L};
        java.util.function.DoubleSupplier rnd = () -> {
            seed[0] = (seed[0] * 16807) % 2147483647;
            return seed[0] / 2147483647.0;
        };
        for (int i = 0; i < 5; i++) rnd.getAsDouble();
        double S = TlGlyphs.SPREAD, w = l.piece.cellW, h = l.piece.cellH;
        Shards sh = new Shards();
        sh.n = n;
        sh.seeds = new double[8][];
        sh.r = new double[8][];
        for (int k = 0; k < 8; k++) {
            if (k < n) {
                double sx = S + rnd.getAsDouble() * Math.max(w - 2 * S, 0.01);
                double sy = S + rnd.getAsDouble() * Math.max(h - 2 * S, 0.01);
                sh.seeds[k] = new double[]{sx, sy};
            } else {
                sh.seeds[k] = new double[]{99, 99};
            }
            double r0 = rnd.getAsDouble(), r1 = rnd.getAsDouble(), r2 = rnd.getAsDouble();
            sh.r[k] = new double[]{r0, r1, r2};
        }
        sh.start = (l.index / (double) Math.max(l.count - 1, 1)) * 0.55;
        l.shards = sh;
        return sh;
    }

    /** One shard's flight: move x, y, turn, fade. */
    private double[] flight(@NonNull Shards sh, @NonNull TlText.Letter l, int k, double part) {
        double w = l.piece.cellW, h = l.piece.cellH;
        double tau = clamp01((part - sh.r[k][0] * 0.35) / 0.65);
        double ox = sh.seeds[k][0] - w / 2, oy = sh.seeds[k][1] - h / 2, ol = Math.hypot(ox, oy);
        if (ol == 0) ol = 1;
        double burst = knob("shatterBurst") * (0.5 + sh.r[k][2]) * (1 - (1 - tau) * (1 - tau));
        return new double[]{(ox / ol) * burst, (oy / ol) * burst - knob("shatterFall") * tau * tau,
                (sh.r[k][1] - 0.5) * knob("shatterSpin") * 6.2832 * tau, 1 - clamp01((tau - 0.72) / 0.28)};
    }

    // ---- the frame ------------------------------------------------------------------------------

    private boolean softens() {
        return knob("soften") * em > 0.25 || knob("wobble") > 0 || knob("chip") > 0 || knob("grow") > 0
                || (knob("drip") > 0 || knob("splash") > 0);
    }

    private boolean hasFire() {
        Object f = look.opt("fire");
        return f != null && f != JSONObject.NULL && !Boolean.FALSE.equals(f);
    }

    /** One frame at clock {@code now} (seconds). */
    public void frame(double now) {
        if (look == null) return;
        if (dirty) build();
        if (emitDirty && lay != null) buildEmitter();
        if (sphereDirty) bakeSphere();
        if (clock0 == null) {
            clock0 = now;
            last = now;
        }
        double t = now - clock0, dt = Math.min(Math.max(now - last, 0), 0.1);
        last = now;
        Map<String, Object> U = uniformsFrom(knobs, (name, k) -> knobValue(name));
        U.put("uTime", new double[]{t});
        U.put("uField", fieldU);
        U.put("uTilt", tilt.clone());
        U.put("uHasBack", new double[]{output == FOR_SCREEN && hasBack ? 1 : 0});
        U.put("uOut", new double[]{output});
        List<String> defines = features();
        boolean neon = defines.contains("TUBE");
        U.put("uTubePaint", new double[]{lay.tubes ? 1 : neon ? 2 : 0});
        double[] wob = sized(U.get("uWobble"), 4);
        wob[3] = 0;
        U.put("uWobble", wob);
        double[] bc = sized(U.get("uBloomCfg"), 4);
        bc[2] = 0;
        bc[3] = 0;
        U.put("uBloomCfg", bc);
        List<String> none = Collections.emptyList();

        // backdrop
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        glw.program("quad.vert", "backdrop.frag", none).use().setAll(U).set("uScene", stage)
                .set("uPhotoFit", photoFit()).tex("uPhoto", photo != 0 ? photo : black.tex(), 5);
        glw.pass(back);
        if (defines.contains("FOG")) glw.mips(back);   // a steamed pane needs the world behind it soft as well as sharp

        // compose
        TlProgram gp = glw.program("glyph.vert", "glyph.frag", none).use().setAll(U);
        glw.bind(field);
        GLES30.glClearColor((float) -TlGlyphs.SPREAD, 0, 0, 0);
        GLES30.glClearDepthf(0);
        GLES30.glDepthMask(true);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_GREATER);
        double weight = knob("weight");
        double broken = defines.contains("SHATTER") ? brokenNow(t) : 0;
        for (TlText.Letter l : lay.letters) {
            Pose ps = pose(l.index, l.count, t);
            gp.set("uCell", new double[]{l.x, l.y, l.piece.cellW, l.piece.cellH}).set("uPivot", new double[]{l.pivotX, l.pivotY})
                    .set("uMove", ps.move).set("uScale", ps.scale).set("uTurn", ps.turn).set("uLine", new double[]{l.baseline, l.cap})
                    .set("uSdfScale", (ps.scale[0] + ps.scale[1]) / 2).set("uWeight", weight + ps.weight)
                    .set("uLetter", new double[]{ps.reveal, (l.index + 0.5) / l.count})
                    .set("uTubePiece", new double[]{l.piece.paint ? 1 : neon ? 2 : 0, l.order})
                    .set("uPenArc", l.arc != null ? l.arc : new double[]{0, 1}).set("uShard", -1).tex("uSdf", l.piece.tex, 0);
            // SHATTER: a breaking letter is drawn as its shards, each a rigid piece with its own flight
            Shards sh = broken > 0 ? shards(l) : null;
            double part = sh != null ? clamp01((broken - sh.start) / 0.45) : 0;
            if (part <= 0) {
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6);
                continue;
            }
            double[] seeds = new double[16];
            for (int k = 0; k < 8; k++) {
                seeds[k * 2] = sh.seeds[k][0];
                seeds[k * 2 + 1] = sh.seeds[k][1];
            }
            gp.set("uSeeds", seeds);
            for (int k = 0; k < sh.n; k++) {
                double[] fm = flight(sh, l, k, part);
                gp.set("uShard", k).set("uPivot", new double[]{l.x + sh.seeds[k][0], l.y + sh.seeds[k][1]})
                        .set("uMove", new double[]{ps.move[0] + fm[0], ps.move[1] + fm[1]}).set("uTurn", ps.turn + fm[2])
                        .set("uLetter", new double[]{ps.reveal * fm[3], (l.index + 0.5) / l.count});
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6);
            }
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);

        // soften
        TlGl.Target fieldNow = field;
        double soft = knob("soften") * em, wobble = knob("wobble");
        if (softens()) {
            // Each pass builds only the pieces it uses (the Note 9 runs a smaller program faster, T13): the blur passes
            // are blur alone; the shape work holds only the pieces switched on. Twin of tl-engine.js.
            List<String> parts = new ArrayList<>();
            parts.add("SOFTEN_PARTS");
            if (wobble > 0) parts.add("SOFTEN_WOBBLE");
            if (knob("chip") > 0 || knob("grow") > 0) parts.add("SOFTEN_CHIPS");
            if (knob("drip") > 0) parts.add("SOFTEN_DRIP");
            if (knob("splash") > 0) parts.add("SOFTEN_SPLASH");
            List<String> blurDefs = Collections.singletonList("SOFTEN_BLUR_ONLY");
            double[] shape = {wobble, knob("wobbleScale"), knob("wobbleSpeed"), 1};
            double[] zero4 = {0, 0, 0, 0};
            if ((knob("drip") > 0 || knob("splash") > 0) && soft > 0.25) {
                // drips are new shape, so they go in first and the rounding then rounds them with the letter: a chain
                // of small passes, each reading the one before (same order, same sums as one pass)
                List<String> pieces = new ArrayList<>();
                for (String p : new String[]{"SOFTEN_CHIPS", "SOFTEN_DRIP", "SOFTEN_SPLASH"}) if (parts.contains(p)) pieces.add(p);
                boolean wobbleRead = parts.contains("SOFTEN_WOBBLE") && pieces.size() > 1 && wobT != null;
                if (wobbleRead) {
                    glw.program("quad.vert", "soften.frag", java.util.Arrays.asList("SOFTEN_PARTS", "SOFTEN_WOBBLE", "SOFTEN_WOBBLE_ONLY"))
                            .use().setAll(U).set("uWobble", shape);
                    glw.pass(wobT);
                }
                List<String> wobDefs = new ArrayList<>();
                wobDefs.add("SOFTEN_PARTS");
                if (parts.contains("SOFTEN_WOBBLE")) wobDefs.add(wobbleRead ? "SOFTEN_WOBBLE_READ" : "SOFTEN_WOBBLE");
                TlGl.Target src = field, dst = fa;
                for (int i = 0; i < pieces.size(); i++) {
                    List<String> st = new ArrayList<>(wobDefs);
                    st.add(pieces.get(i));
                    st.add("SOFTEN_SHAPE_ONLY");
                    if (i > 0) st.add("SOFTEN_C_FROM_PREV");
                    TlProgram p = glw.program("quad.vert", "soften.frag", st).use().setAll(U)
                            .set("uRadius", 0).set("uWobble", shape).set("uStep", new double[]{1.0 / W, 0}).tex("uSrc", field.tex(), 0);
                    if (i > 0) p.tex("uPrev", src.tex(), 1);
                    if (wobbleRead) p.tex("uWob", wobT.tex(), 2);
                    glw.pass(dst);
                    src = dst;
                    dst = dst == fa ? fb : fa;
                }
                // then the rounding: across into the other target, and up back into this one
                TlProgram blur = glw.program("quad.vert", "soften.frag", blurDefs);
                blur.use().setAll(U).set("uRadius", soft).set("uWobble", zero4).set("uStep", new double[]{1.0 / W, 0}).tex("uSrc", src.tex(), 0);
                glw.pass(dst);
                blur.use().setAll(U).set("uRadius", soft).set("uWobble", zero4).set("uStep", new double[]{0, 1.0 / H}).tex("uSrc", dst.tex(), 0);
                glw.pass(src);
                fieldNow = src;
            } else {
                TlProgram first = glw.program("quad.vert", "soften.frag", parts.size() > 1 ? parts : blurDefs);
                first.use().setAll(U).set("uRadius", soft).set("uWobble", shape).set("uStep", new double[]{1.0 / W, 0}).tex("uSrc", field.tex(), 0);
                glw.pass(fa);
                glw.program("quad.vert", "soften.frag", blurDefs).use().setAll(U).set("uRadius", soft).set("uWobble", zero4)
                        .set("uStep", new double[]{0, 1.0 / H}).tex("uSrc", fa.tex(), 0);
                glw.pass(fb);
                fieldNow = fb;
            }
        }
        shapedField = fieldNow;   // (still() runs the fire on the settled field from here)

        // fire
        TlGl.Target heatNow = black;
        boolean fire = hasFire();
        if (fire) {
            if (resetFire) {
                for (TlGl.Target h : heat) {
                    glw.bind(h);
                    GLES30.glClearColor(0, 0, 0, 1);
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                }
                simTime = 0;
                simDebt = 0;
                resetFire = false;
            }
            simDebt = Math.min(simDebt + dt, 4 / 60.0);
            TlProgram fp = glw.program("quad.vert", "fire_step.frag", none).use().setAll(U).set("uDt", 1 / 60.0);
            while (simDebt >= 1 / 60.0) {
                fireTick(fp, fieldNow);
                simDebt -= 1 / 60.0;
            }
            heatNow = heat[0];
        }

        // shade
        // A look whose letters have depth seen at a slant marches each pixel's ray into the letter; that march runs as
        // its own pass first (two smaller programs run much faster than one big one on the Note 9, T13), and the
        // painting pass reads its answer. Looks that draw their own way skip it; flat ones build without the march.
        boolean slant = knob("persp") != 0 || knob("parallax") != 0 || knob("extrudeX") != 0 || knob("extrudeY") != 0;
        boolean ownWay = defines.contains("TUBE") || defines.contains("CELLS") || defines.contains("INK")
                || defines.contains("FOG") || defines.contains("FLAG");
        boolean split = slant && !ownWay && march != null;
        if (split) {
            List<String> md = new ArrayList<>(defines);
            md.add("MARCH_PASS");
            glw.program("quad.vert", "shade.frag", md).use().setAll(U).tex("uFieldTex", fieldNow.tex(), 0);
            glw.pass(march);
        }
        List<String> shadeDefs = new ArrayList<>(defines);
        if (split) shadeDefs.add("MARCH_READ");
        else if (!slant && !ownWay) shadeDefs.add("FLAT_FACE");
        // a relief built for its one kind only where that measured faster on the Note 9 (cut stones and rock; ripples
        // measured much slower, so not those)
        long kind = Math.round(knob("detailKind"));
        if (defines.contains("DETAIL") && (kind == 9 || kind == 10)) shadeDefs.add("DETAIL_KIND " + kind);
        TlProgram sh = glw.program("quad.vert", "shade.frag", shadeDefs).use().setAll(U);
        sh.tex("uFieldTex", fieldNow.tex(), 0).tex("uMatcap", sphere.tex(), 1).tex("uBack", back.tex(), 2).tex("uHeat", heatNow.tex(), 3);
        if (split) sh.tex("uMarch", march.tex(), 6);
        glw.pass(scene);

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        if (fire) {
            glw.program("quad.vert", "fire_draw.frag", none).use().setAll(U).tex("uHeat", heatNow.tex(), 0).tex("uFieldTex", fieldNow.tex(), 1);
            glw.pass(scene);
        }
        JSONArray particles = look.optJSONArray("particles");
        for (int pi = 0; particles != null && pi < particles.length(); pi++) {
            JSONObject ps = particles.optJSONObject(pi);
            if (ps == null) continue;
            final JSONObject pp0 = ps.optJSONObject("p");
            Map<String, Object> PU = uniformsFrom(particleKnobs,
                    (name, k) -> pp0 != null && pp0.has(name) ? pp0.opt(name) : k.has("c") ? k.opt("c") : k.opt("v"));
            TlProgram pp = glw.program("particle.vert", "particle.frag", none).use().setAll(U).setAll(PU);
            pp.tex("uEmit", emit, 0).tex("uFieldTex", fieldNow.tex(), 1).tex("uMatcap", sphere.tex(), 2);
            glw.bind(scene);
            double count = ps.optDouble("count", 200);
            if (count == 0 || Double.isNaN(count)) count = 200;
            // an ordered list (the rivets) draws exactly its points
            int many = fromList(ps) ? rivetCount : (int) Math.round(count);
            if (many > 0) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6 * many);
        }
        GLES30.glDisable(GLES30.GL_BLEND);

        // bloom: down the chain, then back up adding
        TlProgram down = glw.program("quad.vert", "bloom_down.frag", none).use();
        TlGl.Target src = scene;
        for (int i = 0; i < bloom.length; i++) {
            down.set("uHalf", new double[]{0.5 / src.w, 0.5 / src.h})
                    .set("uBloomCfg", new double[]{knob("bloomThreshold"), knob("bloomKnee"), i == 0 ? 1 : 0, 0})
                    .tex("uSrc", src.tex(), 0);
            glw.pass(bloom[i]);
            src = bloom[i];
        }
        TlProgram up = glw.program("quad.vert", "bloom_up.frag", none).use();
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        for (int i = bloom.length - 1; i > 0; i--) {
            up.set("uHalf", new double[]{0.5 / bloom[i].w, 0.5 / bloom[i].h}).tex("uSrc", bloom[i].tex(), 0);
            glw.pass(bloom[i - 1]);
        }
        GLES30.glDisable(GLES30.GL_BLEND);

        // final
        TlProgram fin = glw.program("quad.vert", "final.frag", none).use().setAll(U);
        fin.tex("uScene", scene.tex(), 0).tex("uBloom", bloom[0].tex(), 1).tex("uBack", back.tex(), 2)
                .tex("uHeat", heatNow.tex(), 3).tex("uBloomWide", bloom[3].tex(), 4);
        if (output == LINEAR_CLEAR && outHdr == null) outHdr = glw.target(W, H, true, false);
        boolean smooth = output == FOR_SCREEN && fxaa;
        glw.pass(output == LINEAR_CLEAR ? outHdr : smooth ? screen : out);
        if (smooth) {
            // smooth the steps left where two lit faces meet
            glw.program("quad.vert", "fxaa.frag", none).use().set("uPx", new double[]{1.0 / W, 1.0 / H}).tex("uSrc", screen.tex(), 0);
            glw.pass(out);
        }
        lookTime = t;
    }

    private void fireTick(@NonNull TlProgram fp, @NonNull TlGl.Target fieldNow) {
        simTime += 1 / 60.0;
        fp.set("uTime", simTime).tex("uPrev", heat[0].tex(), 0).tex("uFieldTex", fieldNow.tex(), 1);
        glw.pass(heat[1]);
        TlGl.Target x = heat[0];
        heat[0] = heat[1];
        heat[1] = x;
    }

    /**
     * Draw the look as it stands {@code seconds} after it starts (stills and checks): the fire is run
     * forward to that moment so the picture does not depend on how long the screen was open.
     */
    public void still(double seconds) {
        replay();
        int steps = (int) Math.min(Math.round(seconds * 60), 600);
        frame(1000);
        if (hasFire()) {
            TlProgram fp = glw.program("quad.vert", "fire_step.frag", Collections.emptyList());
            TlGl.Target f = shapedField != null ? shapedField : field;   // wherever the frame's shape work left the field
            // the field must be the settled one: draw the end pose first
            clock0 = 1000 - seconds;
            frame(1000);
            GLES30.glDisable(GLES30.GL_BLEND);
            fp.use();
            for (int i = 0; i < steps; i++) fireTick(fp, f);
        }
        clock0 = 1000 - seconds;
        last = 1000;
        simDebt = 0;
        frame(1000);
    }

    /** The finished picture as a Bitmap, the right way up (call after a frame). */
    @NonNull
    public Bitmap readBitmap() {
        ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, out.fbo());
        GLES30.glReadPixels(0, 0, W, H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        byte[] raw = new byte[W * H * 4];
        px.get(raw);
        if (output == CLEAR) {
            // see-through: the bytes are already premultiplied RGBA, which is what an ARGB_8888 Bitmap holds
            ByteBuffer flipped = ByteBuffer.allocateDirect(W * H * 4);
            for (int y = 0; y < H; y++) flipped.put(raw, (H - 1 - y) * W * 4, W * 4);
            flipped.position(0);
            Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            b.setPremultiplied(true);
            b.copyPixelsFromBuffer(flipped);
            return b;
        }
        int[] argb = new int[W * H];
        for (int y = 0; y < H; y++) {
            int src = (H - 1 - y) * W * 4;
            for (int x = 0; x < W; x++, src += 4) {
                // the look book's canvas has no alpha: the picture is opaque
                argb[y * W + x] = 0xff000000 | ((raw[src] & 255) << 16) | ((raw[src + 1] & 255) << 8) | (raw[src + 2] & 255);
            }
        }
        return Bitmap.createBitmap(argb, W, H, Bitmap.Config.ARGB_8888);
    }

    /** The linear clear picture as floats, RGBA, rows bottom to top (after a frame with {@link #LINEAR_CLEAR}). */
    @NonNull
    public float[] readLinear() {
        java.nio.FloatBuffer px = ByteBuffer.allocateDirect(W * H * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outHdr.fbo());
        GLES30.glReadPixels(0, 0, W, H, GLES30.GL_RGBA, GLES30.GL_FLOAT, px);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        float[] f = new float[W * H * 4];
        px.get(f);
        return f;
    }

    public void release() {
        freeTargets();
        glw.free(sphere);
        glw.free(black);
        TlGl.deleteTexture(emit);
        TlGl.deleteTexture(photo);
        glyphs.release();
        glw.release();
    }
}
