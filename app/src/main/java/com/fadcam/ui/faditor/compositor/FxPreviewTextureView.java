package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.fx.FxCompiler;
import com.fadcam.ui.faditor.fx.FxGlSource;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.fx.FxUniforms;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.MaskSdf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The LIVE adjustment-layer preview: decoder frames through the SAME effect chain the export
 * runs, on the phone's GPU, at any API level this app ships to.
 *
 * <p><b>Why this exists.</b> {@code AdjustmentPreviewController} previews through
 * {@code RenderEffect}, which is API 31, and AGSL {@code RuntimeShader}, which is API 33. That
 * was read as a hardware floor and it is not one — it is a floor on that one API. This project's
 * {@code minSdk} is 24 and its own sandbox device is a Note 9 on API 29 with an Adreno 630 doing
 * OpenGL ES 3.2, which runs these shaders without complaint. Two features in this same package
 * already proved it on that exact phone: {@link ChromaKeyTextureView} runs a live per-pixel key
 * on decoder frames, and {@code GlTransitionPreviewView} blends two live decoders. This is the
 * third, and the one that makes the editor honest — if it renders on export, it shows here.</p>
 *
 * <p><b>It cannot drift from the export.</b> Every fragment program is
 * {@link FxGlSource#fragment}, the identical text {@code AdjustmentLayerGlEffect} compiles, with
 * the identical {@link FxGlSource#KERNEL_HALF}. The pass loop below is the same loop: one program
 * per render, ping-pong FBOs between them, only the LAST render composites the grade back over
 * its own input by mask coverage and layer opacity. Nothing here re-states what an effect means;
 * if it did, this class would eventually lie, and a preview that lies is worse than none.</p>
 *
 * <p><b>The chain runs at VIDEO resolution, not view resolution</b>, and that is not an
 * efficiency note — it is correctness. Sampler effects offset by {@code uTexel}, so a blur of
 * radius 20 run over a 540-px-wide preview covers twice the picture it covers in a 1080-px
 * export. Rendering the chain video-sized and blitting the finished frame down to the view is the
 * only arrangement where the slider means the same thing in both places.</p>
 *
 * <p><b>Uniform lookups cannot throw here.</b> Drivers strip uniforms no code path reads, and the
 * export path had to guard every setter because media3's {@code GlProgram} resolves names against
 * the linked program and NPEs on a missing one. Raw {@code glGetUniformLocation} returns -1
 * instead, and {@code glUniform*} on -1 is a defined no-op — so the whole class of crash that the
 * first export A/B found does not exist on this path.</p>
 *
 * <p><b>Cost is opt-in.</b> Nothing constructs this view until a project actually has a live
 * adjustment layer with active effects. A project that never used one never allocates an EGL
 * context and renders through the ordinary {@code PlayerView} exactly as before.</p>
 */
public class FxPreviewTextureView extends TextureView
        implements TextureView.SurfaceTextureListener, SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "FxPreviewGl";

    /**
     * Decoder frames arrive on an external-OES texture, but every effect body the compiler emits
     * reads a plain {@code sampler2D uTexSampler} — because that is what media3 hands the export.
     * Rather than teach the compiler a second sampler type, one staging pass copies OES into an
     * ordinary 2D texture and the entire chain downstream is then byte-identical to export's.
     * The decoder's transform matrix (rotation, crop, and on some devices a vertical flip) is
     * applied HERE, once, so no effect body ever has to know about it.
     */
    private static final String STAGE_FRAGMENT =
            "#version 100\n"
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vFxUv;\n"
            + "uniform samplerExternalOES uOesTexture;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "uniform float uRotation;\n"
            + "void main() {\n"
            // Rotate FIRST, into the decoder's own frame, then let uTexMatrix crop in that
            // space — the matrix describes the OES image as decoded, so cropping before
            // rotating would crop along the wrong axis.
            + "  vec2 s = vFxUv;\n"
            + "  if (uRotation > 269.0) s = vec2(1.0 - vFxUv.y, vFxUv.x);\n"
            + "  else if (uRotation > 179.0) s = vec2(1.0 - vFxUv.x, 1.0 - vFxUv.y);\n"
            + "  else if (uRotation > 89.0) s = vec2(vFxUv.y, 1.0 - vFxUv.x);\n"
            + "  vec2 uv = (uTexMatrix * vec4(s, 0.0, 1.0)).xy;\n"
            + "  gl_FragColor = texture2D(uOesTexture, uv);\n"
            + "}\n";

    /**
     * Composite one PiP over the frame, in the MASTER frame's normalised space.
     *
     * <p>This is {@code OverlayVideoPreviewView.applyTransform}'s maths, moved into a shader.
     * There it is a chain of View properties — scale, rotation, alpha, translation on a box
     * fit-sized into the video content rect. Here the same numbers place a quad, because a PiP
     * has to BE in this chain for an adjustment layer above it to grade it, which is what the
     * export does ({@code BlendModeGlEffect} at ExportManager:2723, adjustment layers at :2789).
     * A PiP left as a sibling View can only ever be drawn over the graded result.</p>
     *
     * <p><b>Rotation is aspect-corrected.</b> The View rotates in SCREEN space; normalised uv is
     * not square, so rotating it directly would shear the PiP. Multiplying x by the aspect before
     * the rotation and dividing after is what keeps a rotated PiP rectangular.</p>
     */
    private static final String PIP_FRAGMENT =
            "#version 100\n"
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vFxUv;\n"
            + "uniform sampler2D uTexSampler;\n"       // the frame so far
            + "uniform samplerExternalOES uPipTexture;\n"
            + "uniform mat4 uPipTexMatrix;\n"
            + "uniform vec2 uPipCentre;\n"
            + "uniform vec2 uPipHalf;\n"
            + "uniform float uPipCos;\n"
            + "uniform float uPipSin;\n"
            + "uniform float uPipAspect;\n"
            + "uniform float uPipAlpha;\n"
            + "uniform float uPipRotation;\n"
            + "void main() {\n"
            + "  vec4 base = texture2D(uTexSampler, vFxUv);\n"
            + "  vec2 p = vFxUv - uPipCentre;\n"
            + "  p.x *= uPipAspect;\n"
            + "  vec2 r = vec2(p.x * uPipCos + p.y * uPipSin,\n"
            + "               -p.x * uPipSin + p.y * uPipCos);\n"
            + "  r.x /= uPipAspect;\n"
            + "  vec2 q = r / uPipHalf;\n"              // -1..1 inside the PiP box
            + "  gl_FragColor = base;\n"
            + "  if (abs(q.x) <= 1.0 && abs(q.y) <= 1.0) {\n"
            + "    vec2 uv = q * 0.5 + 0.5;\n"
            + "    vec2 s = (uPipTexMatrix * vec4(uv, 0.0, 1.0)).xy;\n"
            + "    vec4 src = texture2D(uPipTexture, s);\n"
            + "    gl_FragColor = vec4(mix(base.rgb, src.rgb, uPipAlpha * src.a), base.a);\n"
            + "  }\n"
            + "}\n";

    /** Full-frame quad in clip space — the same bounds media3 feeds {@code aFramePosition}. */
    private static final float[] QUAD = {
            -1f, -1f, 0f, 1f,
             1f, -1f, 0f, 1f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 0f, 1f,
    };

    public interface SurfaceListener {
        /** Fires on the MAIN thread once a decoder-facing surface exists. */
        void onFxInputSurfaceReady(@NonNull Surface surface);
        /** Fires on the MAIN thread when the surface goes away and the player must let go. */
        void onFxInputSurfaceLost();
        /** The PiP decoder's surface, published alongside the master's. */
        default void onFxPipSurfaceReady(@NonNull Surface surface) { }
    }

    /**
     * One picture-in-picture clip, resolved to where it sits on the master frame.
     *
     * <p>Built on the main thread from the same {@code KeyframeSet} evaluation
     * {@code OverlayVideoPreviewView} uses to position its View, so the GL composite and the
     * gesture layer cannot disagree about where the PiP is.</p>
     */
    public static final class Pip {
        final float cx, cy, halfW, halfH, rotationDeg, alpha;
        /**
         * The object's OWN effect stack, fused into one pass — or null when it has none.
         *
         * <p>Per-object FX were read by nothing in this package until now: only
         * {@code BlendModeGlEffect} (export) and {@code TextFxGlEffect} consumed them, so an
         * effect put on a PiP rendered on export and was invisible in the editor. "I don't see
         * anything that's working in a video layer" was exactly right.</p>
         *
         * <p>ONE FUSED PASS, matching export. {@code BlendModeGlEffect} splices the first
         * non-sampler pass into its compositing shader and skips the rest; previewing more than
         * export can render would be a new lie in the other direction.</p>
         */
        @Nullable final FxCompiler.Pass fused;
        @NonNull final List<FxUniforms.Value> fxUniforms;
        @NonNull final String fxKey;

        public Pip(float cx, float cy, float halfW, float halfH, float rotationDeg, float alpha,
                   @Nullable FxCompiler.Pass fused,
                   @NonNull List<FxUniforms.Value> fxUniforms, @NonNull String fxKey) {
            this.cx = cx;
            this.cy = cy;
            this.halfW = halfW;
            this.halfH = halfH;
            this.rotationDeg = rotationDeg;
            this.alpha = alpha;
            this.fused = fused;
            this.fxUniforms = fxUniforms;
            this.fxKey = fxKey;
        }

        boolean rendersAnything() {
            return alpha > 0.004f && halfW > 0f && halfH > 0f;
        }

        /** Resolve an object's stack at {@code editorMs} into the fused pass and its uniforms. */
        @NonNull
        public static Pip of(float cx, float cy, float halfW, float halfH, float rot, float alpha,
                             @Nullable FxStack stack, long editorMs) {
            FxCompiler.Pass fused = null;
            List<FxUniforms.Value> vals = java.util.Collections.emptyList();
            String key = "";
            if (stack != null && !stack.active().isEmpty()) {
                FxStack resolved = stack.resolveAt(editorMs);
                for (FxCompiler.Pass p : FxCompiler.plan(resolved).passes) {
                    if (p.sampler) continue;
                    fused = p;
                    vals = FxUniforms.forPass(p);
                    key = FxUniforms.sourceKey(p, FxGlSource.KERNEL_HALF);
                    break;
                }
            }
            return new Pip(cx, cy, halfW, halfH, rot, alpha, fused, vals, key);
        }
    }

    // ── Immutable per-frame state, built on the MAIN thread ──────────────────────────────────

    /**
     * One adjustment layer, resolved to everything the GL thread needs and nothing it would have
     * to reach into the model for.
     *
     * <p><b>Why a snapshot rather than the layer.</b> The GL thread draws while the main thread
     * is dragging a slider, and {@code FxStack.resolveAt} walks live keyframe lists. Handing the
     * renderer the model would be a data race whose symptom is an occasional garbage frame — the
     * kind that gets blamed on the shader for a week. Resolving on the main thread and publishing
     * one immutable object is the same discipline {@link ChromaKeyTextureView} uses when it packs
     * the key to a float array before crossing threads.</p>
     */
    public static final class Layer {
        @NonNull final FxCompiler.Plan plan;
        @NonNull final String sourceKey;
        /** Parallel to {@code plan.passes}: the card uniforms for each, resolved at this time. */
        @NonNull final List<List<FxUniforms.Value>> uniforms;
        @NonNull final float[] geo;
        final float opacity;
        final boolean hasMask;
        final boolean invertMask;
        final float timeSec;

        private Layer(@NonNull FxCompiler.Plan plan, @NonNull String sourceKey,
                      @NonNull List<List<FxUniforms.Value>> uniforms, @NonNull float[] geo,
                      float opacity, boolean hasMask, boolean invertMask, float timeSec) {
            this.plan = plan;
            this.sourceKey = sourceKey;
            this.uniforms = uniforms;
            this.geo = geo;
            this.opacity = opacity;
            this.hasMask = hasMask;
            this.invertMask = invertMask;
            this.timeSec = timeSec;
        }

        /**
         * Resolve {@code layer} at {@code editorMs}, or null when it contributes nothing.
         *
         * <p>The gates are the export's gates in the export's order — {@code activeAt} then
         * {@code rendersAnything} then a non-empty plan — so a layer that draws nothing on export
         * draws nothing here, rather than the two disagreeing about what "off" means.</p>
         */
        @Nullable
        public static Layer of(@NonNull AdjustmentLayer layer, long editorMs,
                               int videoW, int videoH) {
            if (!layer.activeAt(editorMs) || !layer.rendersAnything()) return null;
            FxStack resolved = layer.getFx().resolveAt(editorMs);
            FxCompiler.Plan plan = FxCompiler.plan(resolved);
            if (plan.passes.isEmpty()) return null;

            StringBuilder key = new StringBuilder();
            List<List<FxUniforms.Value>> uniforms = new ArrayList<>(plan.passes.size());
            for (FxCompiler.Pass pa : plan.passes) {
                key.append(FxUniforms.sourceKey(pa, FxGlSource.KERNEL_HALF)).append('/');
                uniforms.add(FxUniforms.forPass(pa));
            }
            CompositingSpec cs = layer.getCompositing();
            return new Layer(plan, key.toString(), uniforms,
                    MaskSdf.packShapes(cs, videoW, videoH),
                    layer.opacityAt(editorMs),
                    cs != null && !cs.masks.isEmpty(),
                    cs != null && cs.invertMasks,
                    editorMs / 1000f);
        }
    }

    /**
     * The clip colour grade, resolved to what the shader needs.
     *
     * <p>Built on the main thread from {@link com.fadcam.ui.faditor.effects.EffectStack}, with the
     * matrix stages taken from media3's OWN {@code Brightness} / {@code Contrast} /
     * {@code RgbAdjustment} rather than recomputed — see
     * {@link com.fadcam.ui.faditor.effects.ColorGradeGlSource}. The {@code On} flags reproduce
     * {@code toEffects}' activation thresholds so the preview skips exactly what export skips.</p>
     */
    public static final class Grade {
        @NonNull final float[] matA;
        @NonNull final float[] matB;
        final boolean matAOn, matBOn, satOn, shaderOn;
        final float satAdj, highlights, shadows, fade, vignette, grain;

        public Grade(@NonNull float[] matA, boolean matAOn, boolean satOn, float satAdj,
                     @NonNull float[] matB, boolean matBOn, boolean shaderOn, float highlights,
                     float shadows, float fade, float vignette, float grain) {
            this.matA = matA;
            this.matAOn = matAOn;
            this.satOn = satOn;
            this.satAdj = satAdj;
            this.matB = matB;
            this.matBOn = matBOn;
            this.shaderOn = shaderOn;
            this.highlights = highlights;
            this.shadows = shadows;
            this.fade = fade;
            this.vignette = vignette;
            this.grain = grain;
        }

        boolean rendersAnything() {
            return matAOn || matBOn || satOn || shaderOn;
        }
    }

    // ── Main-thread state ────────────────────────────────────────────────────────────────────

    @Nullable private SurfaceListener surfaceListener;
    @Nullable private HandlerThread glThread;
    @Nullable private Handler glHandler;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());

    /** Published for the GL thread. Immutable objects, so a plain volatile handoff is enough. */
    @NonNull private volatile List<Layer> layers = java.util.Collections.emptyList();
    /** The clip grade, or null when the clip under the playhead has none. */
    @Nullable private volatile Grade grade;
    /** The PiP to composite, or null when there is none (or its own tier owns it). */
    @Nullable private volatile Pip pip;
    private volatile int videoW = 0, videoH = 0;
    /** @see #setVideoRotation */
    private volatile int rotation = 0;

    // ── GL-thread state. Touched ONLY on glHandler. ──────────────────────────────────────────

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int oesTexId;
    @Nullable private SurfaceTexture inputTexture;
    @Nullable private Surface inputSurface;
    private final float[] texMatrix = new float[16];
    /** Second decoder input: the PiP. Allocated with the master's, used only when one exists. */
    private int pipTexId;
    @Nullable private SurfaceTexture pipTexture;
    @Nullable private volatile Surface pipSurface;
    private final float[] pipTexMatrix = new float[16];
    /** Set on the GL thread when a PiP frame has actually arrived; until then, do not draw it. */
    private boolean pipHasFrame;
    private FloatBuffer quadBuf;
    private volatile int surfaceW, surfaceH;

    /** Staging (OES→2D) and presentation (2D→screen) programs. Built once, never rebuilt. */
    private int stageProgram, presentProgram, gradeProgram, pipProgram;
    /** The object stack {@link #pipProgram} was compiled for. */
    @NonNull private String pipFxKey = " ";
    /** Compiled effect steps, keyed by the concatenated source keys of every live layer. */
    @Nullable private String compiledKey;
    /** The one stack whose compile failed, so it is not retried per frame. See ensurePrograms. */
    @Nullable private String failedKey;
    @NonNull private final List<LayerSteps> compiled = new ArrayList<>();
    /** Video-sized RGBA targets: one holds the base, two ping-pong. Reused every frame. */
    private final int[][] targets = new int[3][];
    private int targetW, targetH;
    /** Latched after a compile failure so a broken stack is attempted once, not every frame. */
    private boolean degraded;

    /** One RENDER: a program, which pass it came from, and its separable-kernel axis. */
    private static final class Step {
        final int program;
        final int passIndex;
        final float dirX, dirY;
        final boolean composite;

        Step(int program, int passIndex, float dirX, float dirY, boolean composite) {
            this.program = program;
            this.passIndex = passIndex;
            this.dirX = dirX;
            this.dirY = dirY;
            this.composite = composite;
        }
    }

    /** The compiled steps for one adjustment layer, in order. */
    private static final class LayerSteps {
        @NonNull final List<Step> steps = new ArrayList<>();
    }

    public FxPreviewTextureView(Context context) {
        super(context);
        init();
    }

    public FxPreviewTextureView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Not opaque: the video is letterboxed inside this view and the area around it must show
        // the canvas backdrop underneath rather than a black bar this view invented.
        setOpaque(false);
        setSurfaceTextureListener(this);
        quadBuf = toBuffer(QUAD);
    }

    public void setSurfaceListener(@Nullable SurfaceListener l) {
        this.surfaceListener = l;
    }

    /**
     * The decoded video's dimensions. The effect chain runs at this size so a radius in pixels
     * means the same thing here as on export — see the class note.
     */
    public void setVideoSize(int w, int h) {
        if (w <= 0 || h <= 0 || (w == videoW && h == videoH)) return;
        videoW = w;
        videoH = h;
        requestFrame();
    }

    /**
     * The decoder's unapplied rotation, in degrees.
     *
     * <p><b>This is not optional and it is easy to miss.</b> When ExoPlayer renders into a
     * {@code TextureView} it applies rotation as a VIEW transform; handed a bare {@code Surface}
     * it does not, and {@code SurfaceTexture}'s transform matrix carries the crop but not the
     * display rotation. So a portrait clip arrives as a landscape texture and, without this,
     * gets squeezed into a sliver — which is exactly what the first device run showed.</p>
     */
    public void setVideoRotation(int degrees) {
        int norm = ((degrees % 360) + 360) % 360;
        if (norm == rotation) return;
        rotation = norm;
        requestFrame();
    }

    /**
     * Publish the layers to render, innermost first — the same order the export chain appends
     * them, so each layer grades the result of the one beneath it.
     *
     * <p>Safe from the main thread at any time; push every tick rather than on change. Resolving
     * is cheap, and gating it on "did anything change" is how a keyframed parameter quietly stops
     * animating.</p>
     */
    public void setLayers(@NonNull List<Layer> next) {
        layers = next;
        requestFrame();
    }

    /**
     * The colour grade for the clip under the playhead, or null for none.
     *
     * <p>Runs BEFORE the adjustment layers, which is where the export chain puts it
     * (ExportManager:2560 against :2789) — a layer grades what the clip grade already produced,
     * not the raw decode.</p>
     */
    public void setGrade(@Nullable Grade g) {
        grade = g;
        requestFrame();
    }

    /**
     * The PiP to composite over the master frame, or null for none.
     *
     * <p>Composited AFTER the clip grade and BEFORE the adjustment layers, which is the export
     * chain's order — so a layer grades the PiP along with the video beneath it, and the clip's
     * own grade does not leak onto the PiP.</p>
     */
    public void setPip(@Nullable Pip p) {
        pip = p;
        requestFrame();
    }

    /** True once the PiP decoder surface exists, so a caller knows routing can proceed. */
    public boolean hasPipSurface() {
        return pipSurface != null;
    }

    /** Redraw with current state even if no new decoder frame arrived (a paused scrub). */
    public void requestFrame() {
        Handler h = glHandler;
        if (h != null) h.post(this::drawFrame);
    }

    // ── TextureView lifecycle ────────────────────────────────────────────────────────────────

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
        surfaceW = w;
        surfaceH = h;
        HandlerThread t = new HandlerThread("fx-preview-gl");
        t.start();
        glThread = t;
        glHandler = new Handler(t.getLooper());
        glHandler.post(() -> setupGl(st));
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
        surfaceW = w;
        surfaceH = h;
        requestFrame();
    }

    /**
     * Returning true hands the {@link SurfaceTexture} back to the framework to release, so the
     * EGL window surface built on it MUST already be gone — see the same note on
     * {@link ChromaKeyTextureView}, where releasing out of order is a driver use-after-free that
     * crashes at a stack naming neither this class nor GL.
     */
    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
        HandlerThread t = glThread;
        releaseGl();
        if (t != null) {
            try {
                t.join(500);
                if (t.isAlive()) {
                    FLog.w(TAG, "GL thread did not stop; keeping the SurfaceTexture");
                    return false;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) { /* we drive our own draws */ }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        requestFrame();
    }

    // ── GL thread ────────────────────────────────────────────────────────────────────────────

    private void setupGl(@NonNull SurfaceTexture output) {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1);
            int[] cfgAttr = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] numCfg = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, numCfg, 0)
                    || numCfg[0] == 0) {
                throw new RuntimeException("no RGBA8888 ES2 EGL config");
            }
            eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], output,
                    new int[]{EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

            stageProgram = buildProgram(FxGlSource.VERTEX_SHADER, STAGE_FRAGMENT);
            presentProgram = buildProgram(FxGlSource.VERTEX_SHADER, FxGlSource.PASSTHROUGH_FRAGMENT);
            gradeProgram = buildProgram(FxGlSource.VERTEX_SHADER,
                    com.fadcam.ui.faditor.effects.ColorGradeGlSource.PREVIEW_FRAGMENT);
            // The PiP program is compiled lazily by pipProgramFor, because its source depends on
            // the object's effect stack.
            pipProgram = 0;
            pipFxKey = " ";

            oesTexId = newOesTexture();

            inputTexture = new SurfaceTexture(oesTexId);
            inputTexture.setOnFrameAvailableListener(this);
            inputSurface = new Surface(inputTexture);

            // The PiP's input, made alongside the master's. Costs one texture and one
            // SurfaceTexture on a project that never uses a PiP; building it lazily instead
            // would mean creating GL objects from whichever thread noticed, which is the kind
            // of cross-thread GL that fails intermittently rather than loudly.
            pipTexId = newOesTexture();
            pipTexture = new SurfaceTexture(pipTexId);
            pipTexture.setOnFrameAvailableListener(st -> {
                pipHasFrame = true;
                requestFrame();
            });
            pipSurface = new Surface(pipTexture);

            final Surface ready = inputSurface;
            final Surface pipReady = pipSurface;
            main.post(() -> {
                SurfaceListener l = surfaceListener;
                if (l != null) {
                    l.onFxInputSurfaceReady(ready);
                    l.onFxPipSurfaceReady(pipReady);
                }
            });
        } catch (Exception e) {
            FLog.e(TAG, "GL setup failed; the live FX preview is unavailable", e);
            releaseGl();
        }
    }

    /** An external-OES texture with the clamped, linear sampling every decoder input wants. */
    private int newOesTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int id = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return id;
    }

    private void drawFrame() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || inputTexture == null) return;
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            inputTexture.updateTexImage();
            inputTexture.getTransformMatrix(texMatrix);

            int vw = videoW > 0 ? videoW : surfaceW;
            int vh = videoH > 0 ? videoH : surfaceH;
            if (vw <= 0 || vh <= 0) return;

            List<Layer> live = layers;
            ensureTargets(vw, vh);
            if (targets[0] == null) return;   // FBO allocation failed; it already logged

            // 1 — OES into an ordinary 2D texture, decoder transform applied once.
            drawStage(vw, vh);
            int cur = 0;

            // 2 — the CLIP grade, before any layer, exactly as the export chain orders them.
            Grade g = grade;
            if (!degraded && g != null && g.rendersAnything()) {
                drawGrade(g, cur, 1, vw, vh);
                cur = 1;
            }

            // 3 — the PiP, over the graded clip and UNDER the layers. Same order as export, and
            //     the whole reason it is composited here rather than left as a sibling View.
            Pip pp = pip;
            if (!degraded && pp != null && pp.rendersAnything() && pipHasFrame
                    && pipTexture != null) {
                pipTexture.updateTexImage();
                pipTexture.getTransformMatrix(pipTexMatrix);
                int dst = cur == 0 ? 1 : 0;
                drawPip(pp, cur, dst, vw, vh);
                cur = dst;
            }

            // 4 — every live layer, in z order, each grading the result of the one beneath.
            if (!degraded && !live.isEmpty() && ensurePrograms(live)) {
                cur = drawLayers(live, vw, vh, cur);
            }

            // 5 — present the finished frame, fit-centred, at view resolution.
            drawPresent(targets[cur][0]);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        } catch (Exception e) {
            // A lost preview must never take the editor down, and it cannot affect the export.
            if (!degraded) {
                FLog.w(TAG, "preview draw failed; showing ungraded", e);
                degraded = true;
            }
        }
    }

    /** OES → {@code targets[0]}, the "base" the first layer grades. */
    private void drawStage(int vw, int vh) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[0][1]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(stageProgram);
        bindQuad(stageProgram);
        GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(stageProgram, "uTexMatrix"), 1, false, texMatrix, 0);
        setF(stageProgram, "uRotation", rotation);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(stageProgram, "uOesTexture"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** The clip colour grade: {@code src} → {@code dst}, one pass, media3's own math. */
    private void drawGrade(@NonNull Grade g, int src, int dst, int vw, int vh) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(gradeProgram);
        bindQuad(gradeProgram);
        setSampler(gradeProgram, "uTexSampler", targets[src][0], 0, true);
        GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(gradeProgram, "uGradeMatA"), 1, false, g.matA, 0);
        GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(gradeProgram, "uGradeMatB"), 1, false, g.matB, 0);
        setF(gradeProgram, "uGradeMatAOn", g.matAOn ? 1f : 0f);
        setF(gradeProgram, "uGradeMatBOn", g.matBOn ? 1f : 0f);
        setF(gradeProgram, "uSatOn", g.satOn ? 1f : 0f);
        setF(gradeProgram, "uSatAdj", g.satAdj);
        setF(gradeProgram, "uShaderOn", g.shaderOn ? 1f : 0f);
        setF(gradeProgram, "uHighlights", g.highlights);
        setF(gradeProgram, "uShadows", g.shadows);
        setF(gradeProgram, "uFade", g.fade);
        setF(gradeProgram, "uVignette", g.vignette);
        setF(gradeProgram, "uGrain", g.grain);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * The PiP composite program for {@code p}'s effect stack, compiled on demand.
     *
     * <p>Keyed on the stack's source, so an unchanged stack reuses its program and a slider drag
     * that only moves uniform VALUES never recompiles.</p>
     */
    private int pipProgramFor(@NonNull Pip p) {
        if (p.fxKey.equals(pipFxKey) && pipProgram != 0) return pipProgram;
        try {
            int prog = buildProgram(FxGlSource.VERTEX_SHADER, pipFragment(p.fused));
            if (pipProgram != 0) GLES20.glDeleteProgram(pipProgram);
            pipProgram = prog;
            pipFxKey = p.fxKey;
        } catch (Exception e) {
            FLog.w(TAG, "PiP FX compile failed; compositing ungraded", e);
            pipFxKey = p.fxKey;   // do not retry this stack every frame
        }
        return pipProgram;
    }

    /**
     * Splice the object's fused FX pass into the composite shader.
     *
     * <p>Deliberately the SAME transformation {@code BlendModeGlEffect.fragmentFor} performs:
     * take the compiler's output, discard its {@code main()} (this shader has one, and here the
     * subject is one object's colour rather than a whole frame), keep its declarations minus the
     * ones already present, and fold each card over the object's colour. Doing it differently
     * here is how the editor and the render start disagreeing about what a PiP looks like.</p>
     */
    @NonNull
    private static String pipFragment(@Nullable FxCompiler.Pass fused) {
        if (fused == null) return PIP_FRAGMENT;
        String emitted = FxCompiler.emitGlsl(fused, FxGlSource.KERNEL_HALF);
        int mainAt = emitted.indexOf("void main()");
        if (mainAt < 0) return PIP_FRAGMENT;
        String decls = emitted.substring(0, mainAt)
                .replace("uniform sampler2D uTexSampler;\n", "")
                .replace("varying vec2 vFxUv;\n", "")
                .replace("precision mediump float;\n", "")
                .replace("precision highp float;\n", "");
        StringBuilder fold = new StringBuilder();
        for (com.fadcam.ui.faditor.fx.FxInstance card : fused.cards) {
            fold.append("    fxc = fxBlendOver(fxc, fx").append(card.slot)
                    .append("(uv, fxc), ")
                    .append(FxCompiler.foldOpacityName(card)).append(", ")
                    .append(FxCompiler.foldBlendName(card)).append(");\n");
        }
        String apply = "    vec4 fxc = src;\n" + fold + "    src = fxc;\n";
        return PIP_FRAGMENT
                .replace("uniform float uPipRotation;\n", "uniform float uPipRotation;\n" + decls)
                .replace("    vec4 src = texture2D(uPipTexture, s);\n",
                        "    vec4 src = texture2D(uPipTexture, s);\n" + apply);
    }

    /** Composite the PiP: {@code src} → {@code dst}, one pass. */
    private void drawPip(@NonNull Pip p, int src, int dst, int vw, int vh) {
        int pipProgram = pipProgramFor(p);
        if (pipProgram == 0) return;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(pipProgram);
        bindQuad(pipProgram);
        // The object's own effects, and the frame constants their bodies may read.
        setF2(pipProgram, "uTexel", 1f / vw, 1f / vh);
        setF(pipProgram, "uAspect", (float) vw / (float) vh);
        setF(pipProgram, "uTime", 0f);
        setF2(pipProgram, "uDir", 1f, 0f);
        for (FxUniforms.Value v : p.fxUniforms) {
            setFn(pipProgram, v.name, v.data, v.components());
        }
        setSampler(pipProgram, "uTexSampler", targets[src][0], 0, true);
        GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(pipProgram, "uPipTexMatrix"), 1, false,
                pipTexMatrix, 0);
        int loc = GLES20.glGetUniformLocation(pipProgram, "uPipTexture");
        if (loc >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, pipTexId);
            GLES20.glUniform1i(loc, 1);
        }
        double rad = Math.toRadians(p.rotationDeg);
        setF2(pipProgram, "uPipCentre", p.cx, p.cy);
        setF2(pipProgram, "uPipHalf", p.halfW, p.halfH);
        setF(pipProgram, "uPipCos", (float) Math.cos(rad));
        setF(pipProgram, "uPipSin", (float) Math.sin(rad));
        setF(pipProgram, "uPipAspect", (float) vw / (float) vh);
        setF(pipProgram, "uPipAlpha", p.alpha);
        setF(pipProgram, "uPipRotation", p.rotationDeg);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * Run every layer's compiled steps. Returns the index of the target holding the result.
     *
     * <p><b>Three targets, and the reason is the composite.</b> The last render of each layer
     * reads its own INPUT through {@code uBaseSampler} at the same time as the previous pass's
     * output through {@code uTexSampler}, so the layer's base must survive until that layer is
     * finished. One slot holds the base and the other two ping-pong; when the layer completes,
     * its output becomes the next layer's base and the old base is free again.</p>
     */
    private int drawLayers(@NonNull List<Layer> live, int vw, int vh, int from) {
        int base = from;
        for (int li = 0; li < compiled.size() && li < live.size(); li++) {
            Layer layer = live.get(li);
            List<Step> steps = compiled.get(li).steps;
            if (steps.isEmpty()) continue;
            int src = base;
            int dst = -1;
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                // Any slot that is neither the layer's base nor the current source is free.
                dst = freeTargetOther(base, src);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
                GLES20.glViewport(0, 0, vw, vh);
                GLES20.glUseProgram(step.program);
                bindQuad(step.program);

                setSampler(step.program, "uTexSampler", targets[src][0], 0, false);
                // The layer's own INPUT, on unit 1 so it cannot collide with the pass input.
                setSampler(step.program, "uBaseSampler", targets[base][0], 1, false);
                setF2(step.program, "uTexel", 1f / vw, 1f / vh);
                setF(step.program, "uAspect", (float) vw / (float) vh);
                setF(step.program, "uTime", layer.timeSec);
                setF2(step.program, "uDir", step.dirX, step.dirY);
                for (FxUniforms.Value v : layer.uniforms.get(step.passIndex)) {
                    setFn(step.program, v.name, v.data, v.components());
                }
                setF(step.program, "uLayerOpacity", layer.opacity);
                setF(step.program, "uMaskCount", layer.hasMask ? 1f : 0f);
                float[] g = layer.geo;
                setFn(step.program, "uMaskGeo", new float[]{g[0], g[1], g[2], g[3]}, 4);
                setF2(step.program, "uMaskRot", g[4], g[5]);
                setF(step.program, "uMaskCorner", g[6]);
                setF(step.program, "uMaskFeather", g[7]);
                setF(step.program, "uMaskInvert", layer.invertMask ? 1f : 0f);

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                src = dst;
            }
            if (dst >= 0) base = dst;
        }
        return base;
    }

    /** The one slot that is neither {@code a} nor {@code b}. */
    private static int freeTargetOther(int a, int b) {
        for (int i = 0; i < 3; i++) {
            if (i != a && i != b) return i;
        }
        return 0;
    }

    /**
     * Blit the finished video-resolution frame into the view, letterboxed to preserve aspect —
     * matching the {@code PlayerView} {@code resize_mode="fit"} this view stands in front of, so
     * routing the decoder here does not move the picture on screen.
     */
    private void drawPresent(int tex) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int vw = videoW > 0 ? videoW : surfaceW;
        int vh = videoH > 0 ? videoH : surfaceH;
        if (vw <= 0 || vh <= 0 || surfaceW <= 0 || surfaceH <= 0) return;
        float scale = Math.min((float) surfaceW / vw, (float) surfaceH / vh);
        int w = Math.max(1, Math.round(vw * scale));
        int h = Math.max(1, Math.round(vh * scale));
        GLES20.glViewport((surfaceW - w) / 2, (surfaceH - h) / 2, w, h);
        GLES20.glUseProgram(presentProgram);
        bindQuad(presentProgram);
        setSampler(presentProgram, "uTexSampler", tex, 0, true);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ── Program management ───────────────────────────────────────────────────────────────────

    /**
     * Compile one program per RENDER for the live layer set, reusing them while the stack's
     * source is unchanged.
     *
     * <p>The structure is the export's: a pass declaring two renders (a separable kernel) gets
     * two steps sharing one program with {@code uDir} flipped, horizontal then vertical, which is
     * the order the kernel weights assume. Only the very last render of a layer composites.</p>
     *
     * @return false when nothing is compiled and the frame should pass through ungraded.
     */
    private boolean ensurePrograms(@NonNull List<Layer> live) {
        StringBuilder key = new StringBuilder();
        for (Layer l : live) key.append(l.sourceKey).append('#');
        String want = key.toString();
        if (want.equals(compiledKey) && !compiled.isEmpty()) return true;
        // A stack that already failed to compile is not retried every frame — but a DIFFERENT
        // stack is. Latching on the key rather than on a boolean is what lets the user delete
        // the offending card and get their preview back, instead of having to reopen the editor.
        if (want.equals(failedKey)) return false;

        releasePrograms();
        try {
            for (Layer l : live) {
                LayerSteps ls = new LayerSteps();
                for (int i = 0; i < l.plan.passes.size(); i++) {
                    FxCompiler.Pass pa = l.plan.passes.get(i);
                    boolean lastPass = i == l.plan.passes.size() - 1;
                    int renders = Math.max(1, pa.repeats);
                    for (int r = 0; r < renders; r++) {
                        boolean lastRender = lastPass && r == renders - 1;
                        int prog = buildProgram(FxGlSource.VERTEX_SHADER,
                                FxGlSource.fragment(pa, FxGlSource.KERNEL_HALF, lastRender));
                        float dx = (renders > 1 && r == 1) ? 0f : 1f;
                        float dy = (renders > 1 && r == 1) ? 1f : 0f;
                        ls.steps.add(new Step(prog, i, dx, dy, lastRender));
                    }
                }
                compiled.add(ls);
            }
        } catch (Exception e) {
            FLog.w(TAG, "FX shader compile failed; previewing ungraded", e);
            releasePrograms();
            failedKey = want;
            return false;
        }
        compiledKey = want;
        return true;
    }

    private void releasePrograms() {
        for (LayerSteps ls : compiled) {
            for (Step s : ls.steps) {
                try { GLES20.glDeleteProgram(s.program); } catch (Exception ignored) { }
            }
        }
        compiled.clear();
        compiledKey = null;
    }

    /** (Re)allocate the three video-sized ping-pong targets when the video size changes. */
    private void ensureTargets(int w, int h) {
        if (targets[0] != null && targetW == w && targetH == h) return;
        releaseTargets();
        for (int i = 0; i < 3; i++) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            // CLAMP matters: the compiler's fxClamp keeps sampling in range, but a blur reading
            // past the edge on a REPEAT texture would wrap the opposite side of the picture in.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            int[] f = new int[1];
            GLES20.glGenFramebuffers(1, f, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, t[0], 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                FLog.w(TAG, "FX target FBO incomplete: " + status);
                releaseTargets();
                return;
            }
            targets[i] = new int[]{t[0], f[0]};
        }
        targetW = w;
        targetH = h;
    }

    private void releaseTargets() {
        for (int i = 0; i < 3; i++) {
            if (targets[i] == null) continue;
            try {
                GLES20.glDeleteTextures(1, new int[]{targets[i][0]}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{targets[i][1]}, 0);
            } catch (Exception ignored) { }
            targets[i] = null;
        }
        targetW = targetH = 0;
    }

    // ── Small GL helpers ─────────────────────────────────────────────────────────────────────

    /**
     * Bind the full-frame quad to {@code aFramePosition}.
     *
     * <p>Not optional and not obvious: without an attribute bound, the draw produces nothing at
     * all — the export path's equivalent omission threw "call setBuffer before bind" on the very
     * first frame.</p>
     */
    private void bindQuad(int program) {
        int loc = GLES20.glGetAttribLocation(program, "aFramePosition");
        if (loc < 0) return;
        quadBuf.position(0);
        GLES20.glVertexAttribPointer(loc, 4, GLES20.GL_FLOAT, false, 0, quadBuf);
        GLES20.glEnableVertexAttribArray(loc);
    }

    /**
     * Bind a sampler, tolerating one the driver stripped.
     *
     * <p>{@code glGetUniformLocation} returns -1 for a uniform no code path reads, and every
     * {@code glUniform*} on -1 is a defined no-op — so unlike the export path, a pass that does
     * not read {@code uBaseSampler} needs no special case. {@code required} exists only so the
     * presentation blit, which genuinely cannot work without its sampler, says so once.</p>
     */
    private void setSampler(int program, @NonNull String name, int tex, int unit,
                            boolean required) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc < 0) {
            if (required) noteMissing(name);
            return;
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(loc, unit);
    }

    private void setF(int program, @NonNull String name, float v) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform1f(loc, v);
    }

    private void setF2(int program, @NonNull String name, float a, float b) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform2f(loc, a, b);
    }

    private void setFn(int program, @NonNull String name, @NonNull float[] v, int components) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc < 0) return;
        switch (components) {
            case 2: GLES20.glUniform2f(loc, v[0], v[1]); break;
            case 3: GLES20.glUniform3f(loc, v[0], v[1], v[2]); break;
            case 4: GLES20.glUniform4f(loc, v[0], v[1], v[2], v[3]); break;
            default: GLES20.glUniform1f(loc, v[0]);
        }
    }

    private static final java.util.Set<String> MISSING =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static void noteMissing(@NonNull String name) {
        if (MISSING.add(name)) FLog.w(TAG, "required uniform absent from program: " + name);
    }

    private int buildProgram(@NonNull String vertex, @NonNull String fragment) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("FX program link failed: " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private int compileShader(int type, @NonNull String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            // Named loudly with the source: a silent compile failure looks exactly like "the
            // effect does nothing", which is the bug this whole tier exists to make impossible.
            throw new RuntimeException("FX shader compile failed: " + log + "\n--- source ---\n"
                    + src);
        }
        return s;
    }

    /**
     * Tear down on the GL thread, then let the caller join. Ordering matters: the player must be
     * told the surface is gone BEFORE it is released, or media3 writes into a dead Surface.
     */
    private void releaseGl() {
        Handler h = glHandler;
        HandlerThread t = glThread;
        glHandler = null;
        glThread = null;
        main.post(() -> {
            SurfaceListener l = surfaceListener;
            if (l != null) l.onFxInputSurfaceLost();
        });
        if (h != null) {
            h.post(() -> {
                releasePrograms();
                releaseTargets();
                if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
                if (inputTexture != null) { inputTexture.release(); inputTexture = null; }
                Surface ps = pipSurface;
                pipSurface = null;
                if (ps != null) ps.release();
                if (pipTexture != null) { pipTexture.release(); pipTexture = null; }
                pipHasFrame = false;
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext);
                    }
                    EGL14.eglTerminate(eglDisplay);
                }
                eglSurface = EGL14.EGL_NO_SURFACE;
                eglContext = EGL14.EGL_NO_CONTEXT;
                eglDisplay = EGL14.EGL_NO_DISPLAY;
                if (t != null) t.quitSafely();
            });
        } else if (t != null) {
            t.quitSafely();
        }
    }

    @NonNull
    private static FloatBuffer toBuffer(@NonNull float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data);
        b.position(0);
        return b;
    }
}
