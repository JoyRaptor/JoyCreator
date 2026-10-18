package com.fadcam.vislab.gl;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.audio.Signals;
import com.fadcam.vislab.model.CompiledScene;
import com.fadcam.vislab.model.KnobSet;
import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.model.VisType;
import com.fadcam.vislab.model.VisTypes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The VisLab engine, Android host (OpenGL ES 3.0). Structural twin of the web pilot's
 * {@code VisEngine} (tools/vislab-web/vislab-engine.js): same shaders, same pass order, same
 * fixed 1/60 s simulation tick, same knob packing. Keep them in step.
 *
 * <p>Frame order:
 * <ol>
 *   <li>sound into the Audio uniform block and the spectrum / wave / history textures</li>
 *   <li>living layers (particles, slime, reaction-diffusion) step on the 60 Hz grid</li>
 *   <li>ECHO: last frame warped and faded becomes this frame's canvas (when the look has it)</li>
 *   <li>layers draw in order, all with ONE, ONE_MINUS_SRC_ALPHA (premultiplied)</li>
 *   <li>bloom</li>
 *   <li>FINISH: lens, glitch, film, colour, framing, background, into the output</li>
 * </ol></p>
 *
 * <p>Single-threaded: every call except the setters must come from the GL thread that called
 * {@link #init()}. Setters are safe from any thread; they take effect on the next frame.</p>
 */
public final class VisRenderer {

    private static final String TAG = "VisRenderer";
    private static final int NB = Features.NB, NW = Features.NW, NH = Features.NH;

    /** Supplies pictures for image layers (logo, album art, a Joy Brush drawing). */
    public interface ImageSource {
        /** @param ref the layer's {@code image} value, or null for the default logo */
        @Nullable Bitmap bitmap(@Nullable String ref);
    }

    private final ShaderLib lib;
    private final VisTypes types;

    // ---- settings (any thread) ----
    private volatile VisScene pendingScene;
    private volatile String[] brand;
    private volatile boolean transparent;
    private volatile int alphaMode = -1;     // -1 auto, 0 additive, 1 screen
    private volatile int forceFrame = -1;    // -1 = the look's own edges
    private volatile float renderScale = 1f;
    private volatile boolean profile;
    @Nullable private volatile com.fadcam.vislab.words.WordFeed words;
    private volatile double wordsTime = Double.NaN;
    @Nullable private GpuTimer gpu;
    private volatile ImageSource images;
    private volatile boolean resetRequested;

    // ---- GL state ----
    GlTarget.Format hdrFormat = GlTarget.RGBA8;
    GlTarget.Format stateFormat = null;
    private int vao, ubo;
    private int texSpec, texWave, texHist, texBlack;
    private final FloatBuffer uboBuf = buf(32);
    private final FloatBuffer specBuf = buf(NB * 4), waveBuf = buf(NW * 4), histBuf = buf(NB * NH);
    private final Map<String, GlProgram> programs = new HashMap<>();
    private final Map<String, String> brokenPrograms = new HashMap<>();
    private GlTarget main, half, echoA, echoB;
    private final Bloom bloom = new Bloom();
    private boolean echoFresh = true;
    private int w, h, outW, outH, outFbo;
    private float pxScene;

    // ---- per look ----
    private VisScene scene;
    private String[] compiledBrand;
    private CompiledScene compiled;
    private final IdentityHashMap<Object, LivingLayers.State> living = new IdentityHashMap<>();
    private final IdentityHashMap<Object, String> brokenLayers = new IdentityHashMap<>();
    private final IdentityHashMap<Object, int[]> imageTex = new IdentityHashMap<>();   // {tex, w, h}
    private final IdentityHashMap<Object, Object> imageRef = new IdentityHashMap<>();
    private final Signals signals = new Signals();
    private double time;
    private long lastTick = Long.MIN_VALUE;
    private float lastMs;
    @Nullable private String lastError;
    private boolean initialised;

    public VisRenderer(@NonNull ShaderLib lib, @NonNull VisTypes types) {
        this.lib = lib;
        this.types = types;
    }

    // ================================================================== settings

    public void setScene(@Nullable VisScene s) {
        pendingScene = s;
    }

    /** Re-colour every look with these five stops (brand lock); null = each look's own. */
    public void setBrandPalette(@Nullable String[] stops) {
        brand = stops;
    }

    /** See-through output (over video or the screen) even when the look has a background. */
    public void setTransparent(boolean t) {
        transparent = t;
    }

    /** -1 auto (screen when see-through), 0 light adds, 1 screen-like. */
    public void setAlphaMode(int m) {
        alphaMode = m;
    }

    /** -1 the look's own edges, 0 bleed, 1 soft box, 2 circle, 3 oval. */
    public void setForceFrame(int f) {
        forceFrame = f;
    }

    /** Internal resolution as a share of the output (0.45..1). */
    public void setRenderScale(float s) {
        renderScale = Math.max(0.3f, Math.min(1f, s));
    }

    public float renderScale() {
        return renderScale;
    }

    public void setImageSource(@Nullable ImageSource src) {
        images = src;
    }

    /** Forget all memory (particles, trails, echo) so the next frame starts clean (a seek). */
    public void reset() {
        resetRequested = true;
    }

    @Nullable
    public String lastError() {
        return lastError;
    }

    /** Where words layers get their words (a transcript, the demo lyrics); null = no words. */
    public void setWords(@Nullable com.fadcam.vislab.words.WordFeed feed) {
        words = feed;
    }

    /**
     * The song's own clock, seconds, so words land when they are sung (the demo song's playhead,
     * Studio's clip time). NaN = use the look's clock.
     */
    public void setWordsTime(double seconds) {
        wordsTime = seconds;
    }

    /** Time each stage on the GPU (debug builds); read with {@link #gpuReport()}. */
    public void setProfiling(boolean on) {
        profile = on;
    }

    /** GPU ms per stage, averaged since the last call ("" when off or unsupported). GL thread. */
    @NonNull
    public String gpuReport() {
        return gpu != null ? gpu.report() : "";
    }

    /** CPU time of the last frame, ms (GPU time is not visible from here). */
    public float lastFrameMs() {
        return lastMs;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    // ================================================================== lifecycle

    /** Call once on the GL thread with an ES 3 context current. */
    public void init() {
        String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        if (ext == null) ext = "";
        String ver = GLES30.glGetString(GLES30.GL_VERSION);
        boolean es32 = ver != null && ver.contains("OpenGL ES 3.2");
        boolean f32 = es32 || ext.contains("GL_EXT_color_buffer_float");
        boolean f16 = f32 || ext.contains("GL_EXT_color_buffer_half_float");
        hdrFormat = f16 ? GlTarget.RGBA16F : GlTarget.RGBA8;
        stateFormat = f32 ? GlTarget.RGBA32F : (f16 ? GlTarget.RGBA16F : null);
        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        GLES30.glGenBuffers(1, ids, 0);
        ubo = ids[0];
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, ubo);
        GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, 32 * 4, null, GLES30.GL_DYNAMIC_DRAW);
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, 0, ubo);
        texSpec = dataTex(NB, 1, GLES30.GL_RGBA16F, GLES30.GL_RGBA);
        texWave = dataTex(NW, 1, GLES30.GL_RGBA16F, GLES30.GL_RGBA);
        texHist = dataTex(NB, NH, GLES30.GL_R16F, GLES30.GL_RED);
        texBlack = dataTex(1, 1, GLES30.GL_RGBA8, GLES30.GL_RGBA);
        gpu = profile ? new GpuTimer("living", "layers", "bloom", "finish") : null;
        initialised = true;
        Log.i(TAG, "init " + ver + " hdr=" + hdrFormat.name + " state=" + (stateFormat == null ? "none" : stateFormat.name));
    }

    /** Free everything this renderer made. GL thread, context current. */
    public void release() {
        freeLook();
        for (GlProgram p : programs.values()) p.delete();
        programs.clear();
        brokenPrograms.clear();
        GlTarget.free(main);
        GlTarget.free(half);
        GlTarget.free(echoA);
        GlTarget.free(echoB);
        main = half = echoA = echoB = null;
        bloom.free();
        if (gpu != null) gpu.release();
        gpu = null;
        com.fadcam.vislab.words.WordFeed wf = words;
        if (wf != null) wf.release();
        if (initialised) {
            GLES30.glDeleteTextures(4, new int[]{texSpec, texWave, texHist, texBlack}, 0);
            GLES30.glDeleteBuffers(1, new int[]{ubo}, 0);
            GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
        }
        initialised = false;
        w = h = 0;
    }

    /** The GL context was lost (surface destroyed): forget ids without deleting them. */
    public void contextLost() {
        programs.clear();
        brokenPrograms.clear();
        living.clear();
        imageTex.clear();
        imageRef.clear();
        main = half = echoA = echoB = null;
        bloom.free();
        gpu = null;
        com.fadcam.vislab.words.WordFeed wf = words;
        if (wf != null) wf.contextLost();
        initialised = false;
        w = h = 0;
    }

    private void freeLook() {
        for (LivingLayers.State s : living.values()) s.free();
        living.clear();
        for (int[] t : imageTex.values()) GLES30.glDeleteTextures(1, new int[]{t[0]}, 0);
        imageTex.clear();
        imageRef.clear();
        brokenLayers.clear();
        echoFresh = true;
    }

    // ================================================================== frame

    /**
     * Draw one frame.
     * @param f        what the music is doing at this moment
     * @param timeSec  the look's clock: seconds since the look started (live) or clip time (Studio)
     * @param dtSec    seconds since the previous frame (0 when paused)
     * @param fbo      framebuffer to finish into (0 = the window)
     */
    public void render(@NonNull Features f, double timeSec, double dtSec, int fbo, int outWidth, int outHeight) {
        if (!initialised) return;
        long t0 = SystemClock.elapsedRealtimeNanos();
        lastError = null;
        outFbo = fbo;
        outW = outWidth;
        outH = outHeight;
        refreshLook();
        if (compiled == null) {
            bindTarget(null);
            GLES30.glClearColor(0, 0, 0, transparent ? 0 : 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        int nw = Math.max(16, Math.round(outW * renderScale));
        int nh = Math.max(16, Math.round(outH * renderScale));
        ensureTargets(nw, nh);
        double dt = Math.max(0, Math.min(0.25, dtSec));
        time = timeSec;
        signals.set(f, time);
        uploadTextures(f);
        GLES30.glBindVertexArray(vao);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD);
        for (CompiledScene.Layer L : compiled.layers) L.eval(signals);

        // 2. living layers on the absolute 60 Hz grid
        if (gpu != null) gpu.begin(0);
        long tick = (long) Math.floor(time * 60);
        int ticks;
        if (lastTick == Long.MIN_VALUE || tick < lastTick) ticks = 1;
        else ticks = (int) Math.min(6, tick - lastTick);
        lastTick = tick;
        for (int k = 0; k < ticks; k++) {
            float punch = k == 0 ? f.punch : 0f;
            uploadAudio(f, LivingLayers.TICK, punch);
            for (CompiledScene.Layer L : compiled.layers) stepGuarded(L, punch);
        }

        uploadAudio(f, (float) dt, f.punch);
        pxScene = 2f / Math.min(w, h);

        // 3 + 4. echo and layers
        if (gpu != null) gpu.begin(1);
        GlTarget canvas;
        if (compiled.echo != null) {
            if (echoA == null || echoA.w != w || echoA.h != h) {
                GlTarget.free(echoA);
                GlTarget.free(echoB);
                echoA = GlTarget.create(w, h, hdrFormat, 1, true);
                echoB = GlTarget.create(w, h, hdrFormat, 1, true);
                echoFresh = true;
            }
            if (echoFresh) {
                clear(echoA);
                clear(echoB);
                echoFresh = false;
            }
            GlTarget prev = echoA, cur = echoB;
            clear(cur);
            KnobSet e = compiled.echo;
            e.eval(signals);
            bindTarget(cur);
            GLES30.glDisable(GLES30.GL_BLEND);
            GlProgram p = program("fullscreen.vert", "echo.frag");
            int unit = useParams(p, e.params, compiled.palette, pxScene, 0f, 1f, 1f);
            tex(p, "uPrev", prev.tex(), unit);
            drawFullscreen();
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            boolean above = false;
            for (CompiledScene.Layer L : compiled.layers) {
                if (L.underEcho) drawGuarded(L, cur);
                else above = true;
            }
            echoA = cur;
            echoB = prev;
            if (above) {
                clear(main);
                blit(cur.tex(), main, 1f);
                for (CompiledScene.Layer L : compiled.layers) if (!L.underEcho) drawGuarded(L, main);
                canvas = main;
            } else {
                canvas = cur;
            }
        } else {
            clear(main);
            for (CompiledScene.Layer L : compiled.layers) drawGuarded(L, main);
            canvas = main;
        }

        // 5. bloom
        KnobSet post = compiled.post;
        post.eval(signals);
        VisType pt = types.post;
        float bloomAmt = post.value(pt, "bloom", 0.8f);
        boolean bloomOn = bloomAmt > 0.001f;
        if (gpu != null) gpu.begin(2);
        if (bloomOn) bloom.run(this, canvas, post.value(pt, "bloomThreshold", 0.6f), post.value(pt, "bloomRadius", 0.75f));

        // 6. finish
        if (gpu != null) gpu.begin(3);
        bindTarget(null);
        GLES30.glDisable(GLES30.GL_BLEND);
        float[] P = finishParams;
        System.arraycopy(post.params, 0, P, 0, 48);
        // The upsample chain sums every level: average it so Bloom means the same at any size.
        if (bloomOn) P[1] = bloomAmt * 1.6f / bloom.used;
        float[] bg = compiled.background;
        boolean see = transparent || bg == null;
        P[28] = bg != null ? bg[0] : 0f;
        P[29] = bg != null ? bg[1] : 0f;
        P[30] = bg != null ? bg[2] : 0f;
        P[31] = see ? 0f : 1f;
        // See-through output handed to a compositor we do not control (an Android window,
        // a browser) must be screen alpha: light with zero coverage is clamped to black there.
        P[32] = alphaMode >= 0 ? alphaMode : (see ? 1f : 0f);
        if (forceFrame >= 0) P[24] = forceFrame;
        GlProgram cp = program("fullscreen.vert", "composite.frag");
        int unit = useParams(cp, P, compiled.palette, pxScene, 0f, 1f, 1f);
        tex(cp, "uScene", canvas.tex(), unit++);
        tex(cp, "uBloom", bloomOn ? bloom.result() : texBlack, unit);
        drawFullscreen();
        if (gpu != null) gpu.frameDone();
        lastMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f;
    }

    private final float[] finishParams = new float[48];

    private void refreshLook() {
        VisScene want = pendingScene;
        String[] b = brand;
        if (want != scene) {
            scene = want;
            freeLook();
            compiled = null;
            lastTick = Long.MIN_VALUE;
        }
        if (resetRequested) {
            resetRequested = false;
            freeLook();
            lastTick = Long.MIN_VALUE;
        }
        if (scene == null) return;
        if (compiled == null || compiled.edits != scene.edits() || b != compiledBrand) {
            try {
                CompiledScene before = compiled;
                compiled = new CompiledScene(scene, types, b);
                compiled.carryFrom(before);
                compiledBrand = b;
                // Layers that were removed must not keep GPU memory.
                java.util.Set<Object> alive = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                for (CompiledScene.Layer L : compiled.layers) alive.add(L.source);
                java.util.Iterator<Map.Entry<Object, LivingLayers.State>> it = living.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Object, LivingLayers.State> en = it.next();
                    if (!alive.contains(en.getKey())) {
                        en.getValue().free();
                        it.remove();
                    }
                }
                if (compiled.echo == null) echoFresh = true;
            } catch (RuntimeException e) {
                lastError = "look: " + e.getMessage();
                Log.w(TAG, lastError, e);
                compiled = null;
            }
        }
    }

    private void ensureTargets(int nw, int nh) {
        if (nw == w && nh == h && main != null) return;
        w = nw;
        h = nh;
        GlTarget.free(main);
        GlTarget.free(half);
        GlTarget.free(echoA);
        GlTarget.free(echoB);
        echoA = echoB = null;
        main = GlTarget.create(w, h, hdrFormat, 1, true);
        half = GlTarget.create(Math.max(1, w >> 1), Math.max(1, h >> 1), hdrFormat, 1, true);
        bloom.ensure(w, h, hdrFormat);
        echoFresh = true;
    }

    // ================================================================== layers

    private void stepGuarded(@NonNull CompiledScene.Layer L, float punch) {
        if (brokenLayers.containsKey(L.source)) return;
        String draw = L.type.draw;
        if (!VisType.DRAW_PARTICLES.equals(draw) && !VisType.DRAW_SLIME.equals(draw) && !VisType.DRAW_RD.equals(draw)) return;
        if (stateFormat == null) {
            broken(L, "this phone cannot simulate " + L.type.label);
            return;
        }
        try {
            LivingLayers.State st = living.get(L.source);
            if (VisType.DRAW_PARTICLES.equals(draw)) {
                if (st == null) living.put(L.source, st = new LivingLayers.Particles());
                ((LivingLayers.Particles) st).step(this, L, punch);
            } else if (VisType.DRAW_SLIME.equals(draw)) {
                if (st == null) living.put(L.source, st = new LivingLayers.Slime());
                ((LivingLayers.Slime) st).step(this, L);
            } else {
                if (st == null) living.put(L.source, st = new LivingLayers.Rd());
                ((LivingLayers.Rd) st).step(this, L, punch);
            }
        } catch (RuntimeException e) {
            broken(L, e.getMessage());
        }
    }

    private void drawGuarded(@NonNull CompiledScene.Layer L, @NonNull GlTarget target) {
        if (brokenLayers.containsKey(L.source)) return;
        try {
            drawLayer(L, target);
        } catch (RuntimeException e) {
            broken(L, e.getMessage());
        }
    }

    /** One broken layer is reported once and skipped; every other layer keeps playing. */
    private void broken(@NonNull CompiledScene.Layer L, @Nullable String why) {
        brokenLayers.put(L.source, why);
        lastError = L.type.label + ": " + why;
        Log.w(TAG, "layer off: " + lastError);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void drawLayer(@NonNull CompiledScene.Layer L, @NonNull GlTarget target) {
        VisType t = L.type;
        float opacity = L.opacity();
        if (opacity <= 0.001f) return;
        String draw = t.draw;
        boolean fullscreenLike = VisType.DRAW_FULLSCREEN.equals(draw) || VisType.DRAW_IMAGE.equals(draw)
                || VisType.DRAW_SLIME.equals(draw) || VisType.DRAW_RD.equals(draw) || VisType.DRAW_WORDS.equals(draw);
        GlTarget dst = target;
        float px = pxScene;
        // Moved / sized / turned needs nothing here: the layer is made in that space (useDrawn).
        // Only softness needs the layer on its own first, at half size (cheaper; the softening
        // hides it).
        boolean soft = L.soft() > 0.001f;
        if (soft || (fullscreenLike && L.res < 0.99f)) {
            dst = half;
            px = pxScene * 2f;
            clear(dst);
        }
        bindTarget(dst);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        float drawOpacity = dst == target ? opacity : 1f;
        if (VisType.DRAW_FULLSCREEN.equals(draw)) {
            GlProgram p = program("fullscreen.vert", t.frag);
            useDrawn(p, L, px, drawOpacity);
            drawFullscreen();
        } else if (VisType.DRAW_IMAGE.equals(draw)) {
            int[] img = imageTexture(L);
            GlProgram p = program("fullscreen.vert", t.frag);
            int unit = useDrawn(p, L, px, drawOpacity);
            tex(p, "uImage", img[0], unit);
            GLES30.glUniform1f(p.u("uImageAspect"), img[2] > 0 ? (float) img[1] / img[2] : 1f);
            drawFullscreen();
        } else if (VisType.DRAW_WORDS.equals(draw)) {
            com.fadcam.vislab.words.WordFeed feed = words;
            if (feed == null) return;
            double now = Double.isNaN(wordsTime) ? time : wordsTime;
            // Words = a path, a behaviour and a letter style (see words.frag). Keep each word in
            // view as long as its behaviour needs: smoke and sparkle linger after the word, a
            // burst's rocket climbs before it.
            int path = Math.round(L.knobs.value(t, "path", 0f));
            int beh = Math.round(L.knobs.value(t, "behaviour", 0f));
            double back = 4.0;
            double lead = L.knobs.value(t, "lead", 3f);
            if (beh == 3) back = Math.max(back, 3.2 / Math.max(L.knobs.value(t, "smoke", 1f), 0.2f));
            if (beh == 4 || beh == 5) back = Math.max(back, 3.0);
            if (path == 1) back = Math.max(back, 14.0);   // the spiral fills with the lines already sung
            if (beh == 4) lead = Math.max(lead, 0.8);
            if (beh == 5) lead = Math.max(lead, 1.3);
            // A ribbon carrying the words away shows the ones already sung, far off: keep those.
            if (path == 0 && L.knobs.value(t, "travel", 0f) > 0.5f) {
                back = Math.max(lead, back);
                lead = 1.5;
            }
            feed.update(now, lead, back);
            // One small program per scene and letter style: all of them in one ran at 5 fps.
            String defs = "#define WPATH " + path + "\n#define BEHAVIOUR " + beh + "\n#define LETTER_STYLE "
                    + Math.round(L.knobs.value(t, "letters", 0f)) + "\n";
            GlProgram p = program("fullscreen.vert", t.frag, defs);
            int unit = useDrawn(p, L, px, drawOpacity);
            tex(p, "uWords", feed.texture(), unit);
            GLES30.glUniform4fv(p.u("uWord"), com.fadcam.vislab.words.WordFeed.SLOTS, feed.u, 0);
            GLES30.glUniform4fv(p.u("uWordB"), com.fadcam.vislab.words.WordFeed.SLOTS, feed.ub, 0);
            GLES30.glUniform1f(p.u("uWordCount"), feed.count);
            GLES30.glUniform1f(p.u("uFlowNow"), feed.flowNow);
            drawFullscreen();
        } else if (VisType.DRAW_INSTANCED.equals(draw)) {
            GlProgram p = program(t.vert, t.frag);
            useDrawn(p, L, px, drawOpacity);
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, t.verts, instances(L));
        } else if (VisType.DRAW_PARTICLES.equals(draw)) {
            LivingLayers.State st = living.get(L.source);
            if (!(st instanceof LivingLayers.Particles) || ((LivingLayers.Particles) st).cur == null) return;
            LivingLayers.Particles ps = (LivingLayers.Particles) st;
            GlProgram p = program(t.vert, t.frag);
            int unit = useDrawn(p, L, px, drawOpacity);
            tex(p, "uState0", ps.cur.texs[0], unit++);
            tex(p, "uState1", ps.cur.texs[1], unit);
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, ps.n);
        } else if (VisType.DRAW_SLIME.equals(draw)) {
            LivingLayers.State st = living.get(L.source);
            if (!(st instanceof LivingLayers.Slime) || ((LivingLayers.Slime) st).trailA == null) return;
            GlProgram p = program("fullscreen.vert", t.frag);
            int unit = useDrawn(p, L, px, drawOpacity);
            tex(p, "uTrail", ((LivingLayers.Slime) st).trailA.tex(), unit);
            drawFullscreen();
        } else if (VisType.DRAW_RD.equals(draw)) {
            LivingLayers.State st = living.get(L.source);
            if (!(st instanceof LivingLayers.Rd) || ((LivingLayers.Rd) st).a == null) return;
            GlProgram p = program("fullscreen.vert", t.frag);
            int unit = useDrawn(p, L, px, drawOpacity);
            tex(p, "uState", ((LivingLayers.Rd) st).a.tex(), unit);
            drawFullscreen();
        }
        if (soft) soften(dst, target, L.soft(), opacity);
        else if (dst != target) blit(dst.tex(), target, opacity);
    }

    /** Lay a layer drawn on its own onto {@code target}, softened (0 sharp .. 1 very soft). */
    private void soften(@NonNull GlTarget src, @NonNull GlTarget target, float soft, float opacity) {
        bindTarget(target);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GlProgram p = program("fullscreen.vert", "soften.frag");
        int unit = useBare(p);
        tex(p, "uSrc", src.tex(), unit);
        GLES30.glUniform1f(p.u("uOpacity"), opacity);
        GLES30.glUniform1f(p.u("uSoft"), soft);
        drawFullscreen();
    }

    private int instances(@NonNull CompiledScene.Layer L) {
        long n = 1;
        for (Object x : L.type.instances) {
            if (x instanceof Integer) n *= (Integer) x;
            else n *= Math.max(1, (int) Math.floor(L.knobs.value(L.type, (String) x, 1f)));
        }
        return (int) Math.min(n, 4_000_000L);
    }

    @NonNull
    private int[] imageTexture(@NonNull CompiledScene.Layer L) {
        int[] t = imageTex.get(L.source);
        Object ref = L.image == null ? "" : L.image;
        if (t != null && ref.equals(imageRef.get(L.source))) return t;
        if (t != null) GLES30.glDeleteTextures(1, new int[]{t[0]}, 0);
        ImageSource src = images;
        Bitmap bmp = src != null ? src.bitmap(L.image) : null;
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0]);
        int bw = 1, bh = 1;
        if (bmp != null && !bmp.isRecycled()) {
            // GLUtils uploads Android's premultiplied bitmap as-is: premultiplied, as the shader expects.
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
            bw = bmp.getWidth();
            bh = bmp.getHeight();
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        } else {
            ByteBuffer clear = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, clear);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        t = new int[]{ids[0], bw, bh};
        imageTex.put(L.source, t);
        imageRef.put(L.source, ref);
        return t;
    }

    // ================================================================== GL helpers (package)

    @NonNull
    GlProgram program(@NonNull String vert, @NonNull String frag) {
        return program(vert, frag, "");
    }

    /** {@code defines}: lines put ahead of the fragment shader to build a smaller variant of it. */
    GlProgram program(@NonNull String vert, @NonNull String frag, @NonNull String defines) {
        String key = vert + "|" + frag + (defines.isEmpty() ? "" : "|" + defines);
        GlProgram p = programs.get(key);
        if (p != null) return p;
        String bad = brokenPrograms.get(key);
        if (bad != null) throw new IllegalStateException(bad);
        try {
            p = GlProgram.link(key, lib.assemble(vert), lib.assemble(frag, defines));
        } catch (RuntimeException e) {
            brokenPrograms.put(key, String.valueOf(e.getMessage()));
            throw e;
        }
        programs.put(key, p);
        return p;
    }

    void bindTarget(@Nullable GlTarget t) {
        if (t != null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fb);
            GLES30.glViewport(0, 0, t.w, t.h);
        } else {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo);
            GLES30.glViewport(0, 0, outW, outH);
        }
    }

    void clear(@NonNull GlTarget t) {
        bindTarget(t);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
    }

    void drawFullscreen() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    void tex(@NonNull GlProgram p, @NonNull String name, int texId, int unit) {
        int l = p.u(name);
        if (l < 0) return;
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);
        GLES30.glUniform1i(l, unit);
    }

    /** Use a program with only the sound textures bound. Returns the next free texture unit. */
    int useBare(@NonNull GlProgram p) {
        GLES30.glUseProgram(p.id);
        tex(p, "uSpec", texSpec, 0);
        tex(p, "uWave", texWave, 1);
        tex(p, "uHist", texHist, 2);
        return 3;
    }

    /** Use a program for a layer: its knobs, palette, seed, light/paint and opacity. */
    int useLayer(@NonNull GlProgram p, @NonNull CompiledScene.Layer L, float px, float opacity, @Nullable float[] pal) {
        // A layer's own colours, unless the brand lock is on: then everything is in the brand.
        float[] own = L.palette != null && compiledBrand == null ? L.palette : null;
        return useParams(p, L.knobs.params, pal != null ? pal : (own != null ? own : compiled.palette),
                px, L.seed, L.add, opacity);
    }

    /**
     * A layer being drawn on screen: its knobs, plus where it sits (moved / sized / turned). The
     * layer is MADE in that space (common.glsl placeIn / placeOut), not drawn and then moved like
     * a card. Never for a living layer's simulation step: that runs in the layer's own space.
     */
    private int useDrawn(@NonNull GlProgram p, @NonNull CompiledScene.Layer L, float px, float opacity) {
        float size = L.size();
        int unit = useLayer(p, L, px / size, opacity, null);
        int l = p.u("uView");
        if (l >= 0) GLES30.glUniform4f(l, L.x(), L.y(), (float) (Math.log(size) / Math.log(2)), (float) Math.toRadians(L.turn()));
        return unit;
    }

    int useParams(@NonNull GlProgram p, @NonNull float[] params, @NonNull float[] pal, float px, float seed, float add, float opacity) {
        int unit = useBare(p);
        int l;
        if ((l = p.u("uP")) >= 0) GLES30.glUniform4fv(l, 12, params, 0);
        if ((l = p.u("uPal[0]")) >= 0) GLES30.glUniform3fv(l, 5, pal, 0);
        if ((l = p.u("uPx")) >= 0) GLES30.glUniform1f(l, px);
        if ((l = p.u("uSeed")) >= 0) GLES30.glUniform1f(l, seed);
        if ((l = p.u("uAdd")) >= 0) GLES30.glUniform1f(l, add);
        if ((l = p.u("uOpacity")) >= 0) GLES30.glUniform1f(l, opacity);
        return unit;
    }

    private void blit(int texId, @NonNull GlTarget target, float opacity) {
        bindTarget(target);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GlProgram p = program("fullscreen.vert", "copy.frag");
        int unit = useBare(p);
        tex(p, "uSrc", texId, unit);
        GLES30.glUniform1f(p.u("uOpacity"), opacity);
        drawFullscreen();
    }

    // ================================================================== sound

    private void uploadAudio(@NonNull Features f, float dt, float punch) {
        FloatBuffer u = uboBuf;
        u.clear();
        u.put(f.bass).put(f.low).put(f.mid).put(f.high);
        u.put(f.air).put(f.level).put(f.voice).put(f.bright);
        u.put(f.kick).put(f.snare).put(f.hat).put(f.beat);
        u.put((float) time).put(dt).put(f.beatPhase).put(f.barPhase);
        u.put(f.bassAcc).put(f.levelAcc).put(f.energy).put(punch);
        float m = Math.max(1, Math.min(w, h));
        u.put(w).put(h).put(w / m).put(h / m);
        for (int i = 0; i < 8; i++) u.put(f.shockAge(i));
        u.flip();
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, ubo);
        GLES30.glBufferSubData(GLES30.GL_UNIFORM_BUFFER, 0, 32 * 4, u);
    }

    private void uploadTextures(@NonNull Features f) {
        FloatBuffer s = specBuf;
        s.clear();
        for (int i = 0; i < NB; i++) s.put(f.spec[i]).put(f.peak[i]).put(f.raw[i]).put(0f);
        s.flip();
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texSpec);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, NB, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, s);
        FloatBuffer wv = waveBuf;
        wv.clear();
        for (int i = 0; i < NW; i++) wv.put(f.waveL[i]).put(f.waveR[i]).put(0f).put(0f);
        wv.flip();
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texWave);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, NW, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, wv);
        FloatBuffer hb = histBuf;
        hb.clear();
        hb.put(f.hist);
        hb.flip();
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texHist);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, NB, NH, GLES30.GL_RED, GLES30.GL_FLOAT, hb);
    }

    private static int dataTex(int w, int h, int internal, int format) {
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0]);
        int type = internal == GLES30.GL_RGBA8 ? GLES30.GL_UNSIGNED_BYTE : GLES30.GL_FLOAT;
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return ids[0];
    }

    @NonNull
    private static FloatBuffer buf(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
