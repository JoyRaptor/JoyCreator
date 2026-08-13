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
     * How many overlay clips may be LIVE (their own decoder) in the composite at once.
     *
     * <p>This is a hardware limit dressed as a constant. Phones expose a small fixed number of
     * concurrent AVC/HEVC decoder instances — commonly 2–4 — and going past it fails at
     * codec-init rather than degrading, so the cap has to be conservative and the overflow has
     * to have somewhere to go. The Part-10 probe measured the Note 9 comfortably running THREE
     * simultaneous 1080p HEVC decoders; the master owns one, so two overlays is exactly that
     * budget and not a frame more. Every visible overlay beyond the cap keeps the cached-still
     * path that has always been there, which is a worse picture but never a black PiP.</p>
     *
     * <p>Raising it is this one number plus a device probe — the input array, the pool and the
     * plan all size themselves from here.</p>
     */
    public static final int MAX_LIVE_PIPS = 2;

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
     * {@link #STAGE_FRAGMENT} with the base swapped from the decoder's OES texture to an uploaded
     * 2D bitmap — how an IMAGE master clip enters the chain.
     *
     * <p>An image never reaches the player, so {@code setVideoSurface} routing cannot show it and
     * the whole chain below was blind to it: an adjustment layer graded the video clips and left
     * the photo untouched, while the EXPORT graded both (image clips get the same
     * {@code videoEffects} list — {@code ExportManager.assembleClipVideoEffects}). This is the
     * sampler that closes that gap.</p>
     *
     * <p>Derived textually, for the reason {@link #PIP_STILL_FRAGMENT} is: the rotation branch and
     * everything downstream must stay one authority. Only the sampling differs — a {@code GLUtils}
     * upload is top-row-first where the OES decoder texture is not, so the still variant flips v
     * itself instead of running a decoder transform matrix. The caller passes rotation 0 for a
     * bitmap base (see {@code FxLivePreviewController}), which is also what export does: its
     * rotate/flip stage is gated on {@code isVideo}.</p>
     */
    private static final String STAGE_STILL_FRAGMENT = STAGE_FRAGMENT
            .replace("#extension GL_OES_EGL_image_external : require\n", "")
            .replace("uniform samplerExternalOES uOesTexture;", "uniform sampler2D uOesTexture;")
            .replace("vec2 uv = (uTexMatrix * vec4(s, 0.0, 1.0)).xy;",
                     "vec2 uv = vec2(s.x, 1.0 - s.y);");

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
            + "uniform float uPipBlend;\n"
            + "uniform float uPipMaskOn;\n"
            + "uniform vec4 uPipMaskGeo;\n"
            + "uniform vec2 uPipMaskRot;\n"
            + "uniform float uPipMaskCorner;\n"
            + "uniform float uPipMaskFeather;\n"
            + "uniform float uPipMaskInvert;\n"
            + "uniform vec2 uPipTexel;\n"
            + com.fadcam.ui.faditor.model.BlendModes.glslBlendFnWithModeParam()
            + com.fadcam.ui.faditor.model.MaskSdf.GLSL_MASK_FN
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
            // BLEND MODE and MASK, as the export composites them. Without these, adding an
            // adjustment layer silently REMOVED a PiP blend mode and mask from the preview:
            // the sibling View that used to draw them had handed its pixels to this chain,
            // and this chain did not know about either. One lie fixed with another.
            + "    float cover = 1.0;\n"
            + "    if (uPipMaskOn > 0.5) {\n"
            + "      vec2 frame = vec2(1.0) / uPipTexel;\n"
            + "      float sd = fxShapeSd(vFxUv, frame, uPipMaskGeo, uPipMaskRot,\n"
            + "                           uPipMaskCorner);\n"
            + "      float inside = fxCoverageOf(sd, uPipMaskFeather);\n"
            + "      cover = uPipMaskInvert > 0.5 ? 1.0 - inside : inside;\n"
            + "    }\n"
            + "    vec3 blended = blendPix(base.rgb, src.rgb, uPipBlend);\n"
            + "    float amt = clamp(uPipAlpha * src.a * cover, 0.0, 1.0);\n"
            + "    gl_FragColor = vec4(mix(base.rgb, blended, amt), base.a);\n"
            + "  }\n"
            + "}\n";

    /**
     * {@link #PIP_FRAGMENT} with the PiP source swapped from the decoder's OES texture to an
     * uploaded 2D still frame — how every visible PiP BELOW the live one is composited.
     * Derived textually so the blend/mask maths can never drift between the two variants; only
     * the sampling differs: a {@code GLUtils} upload is top-row-first, so the still variant
     * flips v itself rather than running a decoder transform matrix.
     */
    private static final String PIP_STILL_FRAGMENT = PIP_FRAGMENT
            .replace("#extension GL_OES_EGL_image_external : require\n", "")
            .replace("uniform samplerExternalOES uPipTexture;", "uniform sampler2D uPipTexture;")
            .replace("vec2 s = (uPipTexMatrix * vec4(uv, 0.0, 1.0)).xy;",
                     "vec2 s = vec2(uv.x, 1.0 - uv.y);");

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
        /**
         * A PiP decoder's surface. Slot 0 is published alongside the master's; the rest only
         * once someone asks for them ({@link #requestPipInputs}), so a project that never
         * stacks two overlay videos never allocates the second input at all.
         */
        default void onFxPipSurfaceReady(@NonNull Surface surface, int slot) { }

        /**
         * The effect stack could not be compiled or linked on THIS device, so the frame is
         * passing through ungraded. Fires on the MAIN thread, ONCE per failing stack.
         *
         * <p>This exists because the alternative was a log line. A gradient card declares around
         * 35 uniform vectors; GL ES 2.0's guaranteed floor is 16, and while the Note 9 links it
         * happily, a weaker driver can refuse. The user's experience of that was an effect that
         * simply did nothing — no error, no clue, and nothing to distinguish it from an effect
         * they had misconfigured. A message naming the device's actual limit is the difference
         * between "this app is broken" and "this effect is too big for this phone".</p>
         */
        default void onFxShaderUnavailable(@NonNull String reason) { }
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
        /** Playhead seconds, for time-driven object effects. */
        final float timeSec;
        /** Blend mode index and mask packing, so the composite matches the export's. */
        final float blendMode;
        final boolean maskOn, maskInvert;
        @NonNull final float[] maskGeo;
        /** The clip this PiP draws — keys the GL-side still-texture cache. */
        @NonNull final String clipId;
        /**
         * The frame to composite when this PiP is NOT the live decoder's clip: a cached still,
         * uploaded to a plain 2D texture on the GL thread. Null marks the live PiP, whose
         * pixels arrive on the OES surface.
         *
         * <p>The reference crosses threads safely ONLY because the stills cache never recycles
         * a bitmap while this chain might be uploading it — retired bitmaps are freed on the GL
         * thread itself ({@link #stillTrash}). Never publish a bitmap another owner recycles.</p>
         */
        @Nullable final android.graphics.Bitmap still;
        /**
         * Which live decoder input this PiP's pixels arrive on, when {@link #still} is null.
         * Slot 0 is the one input that has always existed; higher slots exist only while a
         * project genuinely stacks overlay videos. Meaningless for a still-backed PiP.
         */
        final int liveSlot;
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
        /**
         * Whether this object's composite shader carries the two EXTRAS an image overlay needs
         * and a PiP does not: the chroma key, and the entrance preset's wipe reveal.
         *
         * <p>A flag rather than "always compile them in", because the shader text is the program
         * cache key: leaving it off reproduces the PiP composite BYTE FOR BYTE, so nothing about
         * an existing PiP — including its uniform-vector budget, which the gradient card already
         * pushes near GL ES 2.0's guaranteed floor — changes because images arrived.</p>
         */
        final boolean extras;
        /** Packed by {@code ChromaKey}, the same authority the export effect reads. */
        @NonNull final float[] keyColor;
        @NonNull final float[] keyParams;
        /**
         * MASK_WIPE's reveal, 0..1 across the object's own box — 1 means "all of it". The Canvas
         * renderers express this as a {@code clipRect} in the item's local space, which is what
         * {@code q} already is here, so the shader needs one comparison rather than a geometry
         * change (narrowing the box would STRETCH the picture instead of uncovering it).
         */
        final float revealFrac;

        public Pip(float cx, float cy, float halfW, float halfH, float rotationDeg, float alpha,
                   @Nullable FxCompiler.Pass fused,
                   @NonNull List<FxUniforms.Value> fxUniforms, @NonNull String fxKey,
                   float timeSec, float blendMode, boolean maskOn, boolean maskInvert,
                   @NonNull float[] maskGeo,
                   @NonNull String clipId, @Nullable android.graphics.Bitmap still,
                   int liveSlot,
                   boolean extras, @NonNull float[] keyColor, @NonNull float[] keyParams,
                   float revealFrac) {
            this.extras = extras;
            this.keyColor = keyColor;
            this.keyParams = keyParams;
            this.revealFrac = revealFrac;
            this.cx = cx;
            this.cy = cy;
            this.halfW = halfW;
            this.halfH = halfH;
            this.rotationDeg = rotationDeg;
            this.alpha = alpha;
            this.fused = fused;
            this.fxUniforms = fxUniforms;
            this.fxKey = fxKey;
            this.timeSec = timeSec;
            this.blendMode = blendMode;
            this.maskOn = maskOn;
            this.maskInvert = maskInvert;
            this.maskGeo = maskGeo;
            this.clipId = clipId;
            this.still = still;
            this.liveSlot = Math.max(0, Math.min(MAX_LIVE_PIPS - 1, liveSlot));
        }

        boolean rendersAnything() {
            return alpha > 0.004f && halfW > 0f && halfH > 0f;
        }

        /**
         * Resolve an object's stack at {@code editorMs} into the fused pass and its uniforms,
         * with the composite EXTRAS off — the PiP shape, unchanged.
         *
         * <p>A PiP's key is deliberately not packed here. The ACTIVE keyed PiP owns its own live
         * tier ({@code ChromaKeyTextureView}) and {@code fxPipFor} returns null for it, so keying
         * it here as well would key it twice; a keyed PiP in a LOWER tier is unkeyed in this
         * preview today, and turning that on is a separate change that would need its own device
         * proof. See {@link #ofImage} for the overlay-image path, where the key is the whole
         * point.</p>
         */
        @NonNull
        public static Pip of(float cx, float cy, float halfW, float halfH, float rot, float alpha,
                             @Nullable FxStack stack, long editorMs,
                             @Nullable CompositingSpec spec, float blendMode,
                             int frameW, int frameH,
                             @NonNull String clipId, @Nullable android.graphics.Bitmap still,
                             int liveSlot) {
            return build(cx, cy, halfW, halfH, rot, alpha, stack, editorMs, spec, blendMode,
                    frameW, frameH, clipId, still, liveSlot,
                    /* extras= */ false, /* revealFrac= */ 1f);
        }

        /**
         * The same resolution for an IMAGE OVERLAY, which needs the extras: its chroma key and
         * its entrance preset's wipe.
         *
         * <p>{@code frameW}/{@code frameH} are the MASTER FRAME's, because that is the space
         * {@code ImageOverlayDraw} masks in — it opens {@code MaskPathBuilder.beginMask} with the
         * output frame's size — and the shader evaluates the mask in frame space too.</p>
         */
        @NonNull
        public static Pip ofImage(float cx, float cy, float halfW, float halfH, float rot,
                                  float alpha, @Nullable FxStack stack, long editorMs,
                                  @Nullable CompositingSpec spec, float blendMode,
                                  int frameW, int frameH, @NonNull String itemId,
                                  @Nullable android.graphics.Bitmap still, float revealFrac) {
            return build(cx, cy, halfW, halfH, rot, alpha, stack, editorMs, spec, blendMode,
                    frameW, frameH, itemId, still, /* liveSlot= */ 0,
                    /* extras= */ true, revealFrac);
        }

        @NonNull
        private static Pip build(float cx, float cy, float halfW, float halfH, float rot,
                             float alpha,
                             @Nullable FxStack stack, long editorMs,
                             @Nullable CompositingSpec spec, float blendMode,
                             int frameW, int frameH,
                             @NonNull String clipId, @Nullable android.graphics.Bitmap still,
                             int liveSlot, boolean extras, float revealFrac) {
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
            boolean maskOn = spec != null && !spec.masks.isEmpty();
            return new Pip(cx, cy, halfW, halfH, rot, alpha, fused, vals, key,
                    editorMs / 1000f, blendMode, maskOn,
                    spec != null && spec.invertMasks,
                    MaskSdf.packShapes(spec, frameW, frameH), clipId, still, liveSlot,
                    extras,
                    // Packed by the shared authority even when the extras are off, so the field
                    // is never null and drawPip needs no second branch. With extras off the
                    // shader has no key uniforms at all and these are simply never uploaded.
                    com.fadcam.ui.faditor.model.ChromaKey.packColor(extras ? spec : null),
                    com.fadcam.ui.faditor.model.ChromaKey.packParams(extras ? spec : null),
                    revealFrac);
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
        /** Packed by {@code ChromaKey} — the same authority the export effect reads. */
        @NonNull final float[] keyColor;
        @NonNull final float[] keyParams;
        /** A {@code BlendModes.modeCode} value — how the grade combines with the original. */
        final float blendMode;

        private Layer(@NonNull FxCompiler.Plan plan, @NonNull String sourceKey,
                      @NonNull List<List<FxUniforms.Value>> uniforms, @NonNull float[] geo,
                      float opacity, boolean hasMask, boolean invertMask, float timeSec,
                      @NonNull float[] keyColor, @NonNull float[] keyParams, float blendMode) {
            this.plan = plan;
            this.sourceKey = sourceKey;
            this.uniforms = uniforms;
            this.geo = geo;
            this.opacity = opacity;
            this.hasMask = hasMask;
            this.invertMask = invertMask;
            this.timeSec = timeSec;
            this.keyColor = keyColor;
            this.keyParams = keyParams;
            this.blendMode = blendMode;
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
                    editorMs / 1000f,
                    com.fadcam.ui.faditor.model.ChromaKey.packColor(cs),
                    com.fadcam.ui.faditor.model.ChromaKey.packParams(cs),
                    com.fadcam.ui.faditor.model.BlendModes.modeCode(layer.getBlendMode()));
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

    /**
     * One rung of the z-ordered composite walk: a PiP to draw over the frame so far, or an
     * adjustment layer to grade it. The walk IS the export's chain order — both renderers read
     * {@code LayerPreviewController.orderedCompositedItems} — so an adjustment layer grades
     * exactly the PiPs beneath it here, and a layer sitting between two PiPs grades only the
     * lower one, precisely as the exported file composites it.
     */
    public static final class Rung {
        @Nullable final Pip pip;
        /** Index into {@link CompositePlan#layers} when this rung is an adjustment layer. */
        final int layerIndex;

        private Rung(@Nullable Pip pip, int layerIndex) {
            this.pip = pip;
            this.layerIndex = layerIndex;
        }

        @NonNull public static Rung pip(@NonNull Pip p) { return new Rung(p, -1); }
        @NonNull public static Rung layer(int layerIndex) { return new Rung(null, layerIndex); }
    }

    /**
     * The immutable per-tick composite description: the live adjustment layers (bottom→top —
     * the list program compilation is keyed on) plus the z-ordered rung walk that interleaves
     * them with the PiPs. ONE holder published by a single volatile write, so the GL thread
     * never assembles a frame from a half-updated pair of lists.
     */
    public static final class CompositePlan {
        @NonNull final List<Layer> layers;
        @NonNull final List<Rung> rungs;

        public CompositePlan(@NonNull List<Layer> layers, @NonNull List<Rung> rungs) {
            this.layers = layers;
            this.rungs = rungs;
        }
    }

    // ── Main-thread state ────────────────────────────────────────────────────────────────────

    @Nullable private SurfaceListener surfaceListener;
    @Nullable private HandlerThread glThread;
    @Nullable private Handler glHandler;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());

    /**
     * The z-ordered composite plan — every visible PiP and adjustment layer, interleaved.
     * Immutable, so a plain volatile handoff to the GL thread is enough; null when the chain
     * composites nothing (the staged video, plus any grade, still renders — the passthrough a
     * plain project sees).
     */
    @Nullable private volatile CompositePlan plan;
    /** The clip grade, or null when the clip under the playhead has none. */
    @Nullable private volatile Grade grade;
    /**
     * The BASE frame as a bitmap — an image master clip — or null to stage the decoder instead.
     *
     * <p>Not owned here. The publisher must never recycle a bitmap it has handed over; retire it
     * through {@link #stillTrash} exactly as the PiP stills do, or {@code texImage2D} reads freed
     * pixel memory and takes the process down at a native frame no catch block reaches.</p>
     */
    @Nullable private volatile android.graphics.Bitmap baseStill;
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
    /**
     * The PiP decoder inputs. Slot 0 is allocated with the master's, exactly as it always was;
     * slots above it are built lazily on the GL thread when {@link #requestPipInputs} asks, so a
     * project with one overlay video allocates precisely what it allocated before.
     */
    private final int[] pipTexIds = new int[MAX_LIVE_PIPS];
    private final SurfaceTexture[] pipTextures = new SurfaceTexture[MAX_LIVE_PIPS];
    private final Surface[] pipSurfaces = new Surface[MAX_LIVE_PIPS];
    private final float[][] pipTexMatrix = new float[MAX_LIVE_PIPS][16];
    /** Set on the GL thread when a PiP frame has actually arrived; until then, do not draw it. */
    private final boolean[] pipHasFrame = new boolean[MAX_LIVE_PIPS];
    /** How many inputs the main thread has asked for; the GL thread builds up to this. */
    private volatile int wantPipInputs = 1;
    /** How many inputs exist and have been published — read from any thread. */
    private volatile int livePipInputs;
    private FloatBuffer quadBuf;
    private volatile int surfaceW, surfaceH;

    /** Staging (OES→2D) and presentation (2D→screen) programs. Built once, never rebuilt. */
    private int stageProgram, presentProgram, gradeProgram;
    /** @see #STAGE_STILL_FRAGMENT — the bitmap-base variant of {@link #stageProgram}. */
    private int stageStillProgram;
    /** The 2D texture holding {@link #baseStill}, and which bitmap it currently holds. */
    private int baseStillTexId;
    @Nullable private android.graphics.Bitmap baseStillUploaded;
    /**
     * PiP composite programs, keyed by effect-stack source AND texture variant (live OES vs
     * uploaded still) — two PiPs carrying different object stacks must not recompile each
     * other's program every frame. A 0 value latches a stack whose even plain composite
     * failed, so it is not retried per frame.
     */
    @NonNull private final java.util.Map<String, Integer> pipPrograms = new java.util.HashMap<>();
    /** Uploaded still frames by clip id — one GL texture per visible non-live PiP. A texture
     *  outlives a re-decode so the PiP keeps showing its last frame instead of blinking out. */
    @NonNull private final java.util.Map<String, Integer> stillTexIds = new java.util.HashMap<>();
    /** The bitmap each {@link #stillTexIds} entry currently holds, for upload-change detection. */
    @NonNull private final java.util.Map<String, android.graphics.Bitmap> stillUploaded =
            new java.util.HashMap<>();
    /** Clip ids the frame being drawn referenced; still textures for the rest are freed. */
    @NonNull private final java.util.Set<String> stillKeysInFrame = new java.util.HashSet<>();
    /**
     * Stills the cache retired while this chain might be mid-upload on them. Recycled HERE, on
     * the GL thread — the only thread that touches their pixels — because freeing a bitmap
     * during {@code texImage2D} is a native crash no catch block reaches.
     */
    @NonNull private final java.util.concurrent.ConcurrentLinkedQueue<android.graphics.Bitmap>
            stillTrash = new java.util.concurrent.ConcurrentLinkedQueue<>();
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
     * Publish the composite plan for the next frames — PiPs and adjustment layers interleaved
     * in z, the export's chain order. Null clears the walk: the staged video (and any grade)
     * still renders, which is the passthrough a plain project sees.
     *
     * <p>Safe from the main thread at any time; push every tick rather than on change. Resolving
     * is cheap, and gating it on "did anything change" is how a keyframed parameter quietly stops
     * animating.</p>
     */
    public void setCompositePlan(@Nullable CompositePlan next) {
        plan = next;
        requestFrame();
    }

    /**
     * The GL-thread trash for retired still bitmaps. The stills cache defers recycling here
     * while the composite may be uploading those bitmaps — see the field note.
     */
    @NonNull
    public java.util.concurrent.ConcurrentLinkedQueue<android.graphics.Bitmap> stillTrash() {
        return stillTrash;
    }

    /** True once the PiP decoder surface exists, so a caller knows routing can proceed. */
    public boolean hasPipSurface() {
        return livePipInputs > 0;
    }

    /**
     * Ask for {@code count} live PiP inputs (clamped to {@link #MAX_LIVE_PIPS}).
     *
     * <p>Only ever grows within a surface's lifetime: an input is an OES texture plus a
     * {@code SurfaceTexture}, and tearing one down while a decoder might still be writing to it
     * is the class of bug that shows up as an occasional native crash on someone else's phone.
     * Growth is one-way and cheap; the decoders on the other end are what actually cost, and
     * those {@link OverlayVideoPreviewView} releases the moment a stack stops being stacked.</p>
     */
    public void requestPipInputs(int count) {
        int want = Math.max(1, Math.min(MAX_LIVE_PIPS, count));
        if (want <= wantPipInputs) return;
        wantPipInputs = want;
        requestFrame();   // the inputs are built on the GL thread, inside the next draw
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
     * Stage {@code b} as the base frame instead of the decoder, or null to go back to the decoder.
     *
     * <p>This is what lets an IMAGE master clip be graded: there is no decoder callback behind a
     * still, so nothing would ever trigger a draw and the screen would simply not update. The
     * redraws come from the editor's playhead tick instead — {@code setCompositePlan} already
     * fires {@link #requestFrame} every tick and the flag there coalesces the burst — so this
     * setter only has to keep the reference fresh, not run a loop of its own.</p>
     *
     * <p>Ownership: see {@link #baseStill}. Hand over bitmaps you will retire through
     * {@link #stillTrash}, never ones you recycle yourself.</p>
     */
    public void setBaseStill(@Nullable android.graphics.Bitmap b) {
        if (b == baseStill) return;
        baseStill = b;
        requestFrame();
    }

    /**
     * Redraw with current state even if no new decoder frame arrived (a paused scrub).
     *
     * <p>COALESCED. {@code sync()} pushes size, rotation, grade, layers and the PiP every tick,
     * and each setter used to post its own draw — four or five complete video-resolution chain
     * renders per frame, on top of the one {@code onFrameAvailable} posts. The flag collapses a
     * burst into the single draw the caller actually wanted.</p>
     */
    public void requestFrame() {
        Handler h = glHandler;
        if (h == null) return;
        if (!drawPending.compareAndSet(false, true)) return;
        h.post(() -> {
            drawPending.set(false);
            drawFrame();
        });
    }

    private final java.util.concurrent.atomic.AtomicBoolean drawPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

            // EVERY LATCH RESETS HERE. degraded/failedKey/compiledKey used to survive
            // surface recreation, so one transient exception -- a driver hiccup, a
            // target FBO that failed once because the video size was still 0 -- left the
            // preview showing raw ungraded video for the rest of the session, and
            // rotating the device did not clear it. Worse, while degraded the PiP
            // composite is skipped too while its own View is held at alpha 0, so the PiP
            // vanished outright rather than merely losing its grade.
            degraded = false;
            failedKey = null;
            compiledKey = null;
            stageProgram = buildProgram(FxGlSource.VERTEX_SHADER, STAGE_FRAGMENT);
            stageStillProgram = buildProgram(FxGlSource.VERTEX_SHADER, STAGE_STILL_FRAGMENT);
            presentProgram = buildProgram(FxGlSource.VERTEX_SHADER, FxGlSource.PASSTHROUGH_FRAGMENT);
            gradeProgram = buildProgram(FxGlSource.VERTEX_SHADER,
                    com.fadcam.ui.faditor.effects.ColorGradeGlSource.PREVIEW_FRAGMENT);
            // The PiP programs are compiled lazily by pipProgramFor, because their source
            // depends on each object's effect stack. Any ids cached from a previous surface
            // belong to a destroyed context — clearing the maps is not optional.
            pipPrograms.clear();
            stillTexIds.clear();
            stillUploaded.clear();
            // Same for the PiP inputs: whatever sits in these slots names objects in a context
            // that no longer exists, and ensurePipInputs decides "already built" from the count.
            java.util.Arrays.fill(pipTextures, null);
            java.util.Arrays.fill(pipSurfaces, null);
            java.util.Arrays.fill(pipHasFrame, false);
            // Same reasoning for the bitmap BASE: its texture id belonged to the dead context.
            baseStillTexId = 0;
            baseStillUploaded = null;

            oesTexId = newOesTexture();

            inputTexture = new SurfaceTexture(oesTexId);
            inputTexture.setOnFrameAvailableListener(this);
            inputSurface = new Surface(inputTexture);

            // The PiP's first input, made alongside the master's. Costs one texture and one
            // SurfaceTexture on a project that never uses a PiP; building it lazily instead
            // would mean creating GL objects from whichever thread noticed, which is the kind
            // of cross-thread GL that fails intermittently rather than loudly. The inputs ABOVE
            // it are built lazily — but still on this thread, from ensurePipInputs.
            livePipInputs = 0;
            ensurePipInputs();

            final Surface ready = inputSurface;
            main.post(() -> {
                SurfaceListener l = surfaceListener;
                if (l != null) l.onFxInputSurfaceReady(ready);
            });
        } catch (Exception e) {
            FLog.e(TAG, "GL setup failed; the live FX preview is unavailable", e);
            releaseGl();
        }
    }

    /**
     * Build any PiP inputs that have been asked for and do not exist yet, and publish each new
     * surface to the main thread. GL thread only — it is called from {@code setupGl} and from
     * the top of {@code drawFrame}, which are the two places the context is guaranteed current.
     *
     * <p>Each slot is published separately and the decoder side treats an unpublished slot as
     * "not live yet", so the window between asking for a second input and getting one shows the
     * cached still rather than a hole.</p>
     */
    private void ensurePipInputs() {
        int want = Math.min(MAX_LIVE_PIPS, Math.max(1, wantPipInputs));
        for (int slot = livePipInputs; slot < want; slot++) {
            final int s = slot;
            pipTexIds[s] = newOesTexture();
            SurfaceTexture st = new SurfaceTexture(pipTexIds[s]);
            st.setOnFrameAvailableListener(t -> {
                pipHasFrame[s] = true;
                requestFrame();
            });
            pipTextures[s] = st;
            final Surface surface = new Surface(st);
            pipSurfaces[s] = surface;
            livePipInputs = s + 1;
            main.post(() -> {
                SurfaceListener l = surfaceListener;
                if (l != null) l.onFxPipSurfaceReady(surface, s);
            });
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
            // Retired stills are freed HERE, before any upload can touch them — see stillTrash.
            for (android.graphics.Bitmap b; (b = stillTrash.poll()) != null; ) b.recycle();
            // A newly requested PiP input is built here, on the one thread that may make GL
            // objects for this context.
            if (livePipInputs < wantPipInputs) ensurePipInputs();
            inputTexture.updateTexImage();
            inputTexture.getTransformMatrix(texMatrix);

            int vw = videoW > 0 ? videoW : surfaceW;
            int vh = videoH > 0 ? videoH : surfaceH;
            if (vw <= 0 || vh <= 0) return;

            ensureTargets(vw, vh);
            if (targets[0] == null) return;   // FBO allocation failed; it already logged

            // 1 — OES into an ordinary 2D texture, decoder transform applied once.
            drawStage(vw, vh);
            int cur = 0;

            // 2 — the CLIP grade, before any composited item, exactly as the export chain
            //     orders them (ExportManager:2560 against the PiP block at :2714).
            Grade g = grade;
            if (!degraded && g != null && g.rendersAnything()) {
                drawGrade(g, cur, 1, vw, vh);
                cur = 1;
            }

            // 3 — the composited items, bottom→top, in the EXPORT's chain order: each PiP
            //     drawn over the frame so far, each adjustment layer grading what is beneath
            //     it. One ordering (LayerPreviewController.orderedCompositedItems) feeds both
            //     renderers, so a layer between two PiPs grades only the lower one here,
            //     exactly as in the file — and EVERY visible PiP is composited, the live one
            //     from its decoder surface and the rest from their cached stills, rather than
            //     the sibling-View stills that used to paint UNGRADED over this chain.
            stillKeysInFrame.clear();
            CompositePlan cp = plan;
            if (!degraded && cp != null) {
                boolean layersReady = cp.layers.isEmpty() || ensurePrograms(cp.layers);
                for (int ri = 0; ri < cp.rungs.size(); ri++) {
                    Rung r = cp.rungs.get(ri);
                    if (r.pip != null) {
                        cur = drawPipRung(r.pip, cur, vw, vh);
                    } else if (layersReady && r.layerIndex >= 0
                            && r.layerIndex < cp.layers.size() && r.layerIndex < compiled.size()) {
                        cur = drawOneLayer(cp.layers.get(r.layerIndex),
                                compiled.get(r.layerIndex), vw, vh, cur);
                    }
                }
            }
            evictUnusedStills();

            // 4 — present the finished frame, fit-centred, at view resolution.
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

    /**
     * OES (or an uploaded bitmap) → {@code targets[0]}, the "base" the first layer grades.
     *
     * <p>The bitmap branch is what puts an IMAGE master clip into the chain at all. It falls back
     * to the decoder when the upload fails rather than skipping the stage: a stale {@code
     * targets[0]} would show the previous clip's frame under this clip's grade, which reads as a
     * far stranger bug than an ungraded photo.</p>
     */
    private void drawStage(int vw, int vh) {
        android.graphics.Bitmap b = baseStill;
        if (b != null && !b.isRecycled() && drawStageStill(b, vw, vh)) return;
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

    /**
     * Bitmap → {@code targets[0]}. Returns false when the pixels could not be uploaded, so the
     * caller can stage the decoder instead.
     *
     * <p>Uploads only when the bitmap CHANGED, keyed on identity: this runs on every tick a still
     * is the base, and re-uploading a full-resolution photo sixty times a second for a picture
     * that cannot move is exactly the "drastic performance cost" this project refuses to add.</p>
     *
     * <p>Rotation is passed through as usual (the controller sends 0 for a still), and no decoder
     * transform matrix exists on this path — {@link #STAGE_STILL_FRAGMENT} strips its uniform, so
     * the location is -1 and the setter is a defined no-op.</p>
     */
    private boolean drawStageStill(@NonNull android.graphics.Bitmap b, int vw, int vh) {
        if (stageStillProgram == 0) return false;
        if (baseStillTexId == 0) baseStillTexId = newStillTexture();
        if (baseStillUploaded != b) {
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseStillTexId);
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                baseStillUploaded = b;
            } catch (RuntimeException e) {
                FLog.w(TAG, "image base upload failed", e);
                return false;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[0][1]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(stageStillProgram);
        bindQuad(stageStillProgram);
        setF(stageStillProgram, "uRotation", rotation);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseStillTexId);
        GLES20.glUniform1i(
                GLES20.glGetUniformLocation(stageStillProgram, "uOesTexture"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return true;
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
     * The PiP composite program for {@code p}'s effect stack and texture variant, compiled on
     * demand.
     *
     * <p>Keyed on the stack's source plus the variant (live OES vs uploaded still), so an
     * unchanged stack reuses its program, a slider drag that only moves uniform VALUES never
     * recompiles, and two PiPs carrying different stacks no longer evict each other's program
     * on every frame.</p>
     */
    private int pipProgramFor(@NonNull Pip p) {
        String key = (p.still == null ? "o" : "s") + (p.extras ? "x" : "-") + p.fxKey;
        Integer have = pipPrograms.get(key);
        if (have != null) return have;
        int prog;
        try {
            prog = buildProgram(FxGlSource.VERTEX_SHADER,
                    pipFragment(p.fused, p.still != null, p.extras));
            FLog.d("FxMultiPip", "pip program compiled key=" + key + " -> " + prog);
        } catch (Exception e) {
            // Fall back to the PLAIN composite, which is what the log claims happens. A 0 latch
            // drops this PiP from the chain rather than retrying a broken stack every frame —
            // the same "absent until fixed" behaviour a still that never decodes has.
            FLog.w(TAG, "PiP FX compile failed; compositing ungraded", e);
            int fallback = 0;
            try {
                fallback = buildProgram(FxGlSource.VERTEX_SHADER,
                        pipFragment(null, p.still != null, p.extras));
            } catch (Exception fatal) {
                FLog.e(TAG, "plain PiP composite failed too", fatal);
            }
            prog = fallback;
        }
        pipPrograms.put(key, prog);
        return prog;
    }

    /**
     * Splice the object's fused FX pass into the composite shader.
     *
     * <p>Deliberately the SAME transformation {@code BlendModeGlEffect.fragmentFor} performs:
     * take the compiler's output, discard its {@code main()} (this shader has one, and here the
     * subject is one object's colour rather than a whole frame), keep its declarations minus the
     * ones already present, and fold each card over the object's colour. Doing it differently
     * here is how the editor and the render start disagreeing about what a PiP looks like.</p>
     *
     * <p>{@code extras} additionally splices the chroma key and the wipe reveal — see
     * {@link Pip#extras}. With it false and no stack, this returns the base string untouched, so
     * every PiP program compiled before images arrived is still compiled from the same source.</p>
     */
    @NonNull
    private static String pipFragment(@Nullable FxCompiler.Pass fused, boolean still,
                                      boolean extras) {
        String base = still ? PIP_STILL_FRAGMENT : PIP_FRAGMENT;
        if (fused == null && !extras) return base;
        // The extras (key + wipe) go in FIRST, so that once the FX fold is spliced onto the same
        // anchor line the key ends up ABOVE it — the key must measure distance from a colour in
        // the SOURCE image, exactly as ImageBlendGlEffect orders them. Grading first would stop a
        // green screen being green and the key would silently miss.
        String extrasDecls = "";
        String extrasBody = "";
        if (extras) {
            extrasDecls = "uniform vec3 uPipKeyColor;\n"
                    + "uniform vec4 uPipKeyParams;\n"
                    + "uniform float uPipReveal;\n"
                    + com.fadcam.ui.faditor.model.ChromaKey.GLSL_KEY_FN;
            extrasBody =
                    // MASK_WIPE's reveal, in the object's own local space — the same rect the
                    // Canvas renderers clip to, expressed as the one comparison that space makes
                    // free. Its uniform is 1.0 whenever no preset is wiping, so this costs a
                    // compare and nothing else.
                    "    if (uv.x > uPipReveal) src.a = 0.0;\n"
                    // Un-premultiplied, because that is what the key is defined on (ChromaKey's
                    // class note: a premultiplied semi-transparent green is darker than the green
                    // it is, so it survives a key that should have eaten it).
                    + "    src.a = fadKeyAlpha(src.rgb / max(src.a, 0.001), src.a,\n"
                    + "                        uPipKeyColor, uPipKeyParams);\n";
        }
        // The FX half is OPTIONAL from here on: an image may carry only a key. Both halves land
        // through the one pair of replaces at the bottom, so there is a single description of
        // where each block goes rather than a copy per combination.
        String decls = "";
        String apply = "";
        String emitted = fused == null ? "" : FxCompiler.emitGlsl(fused, FxGlSource.KERNEL_HALF);
        int mainAt = emitted.indexOf("void main()");
        if (fused != null && mainAt >= 0) {
            decls = emitted.substring(0, mainAt)
                    .replace("uniform sampler2D uTexSampler;\n", "")
                    .replace("varying vec2 vFxUv;\n", "")
                    .replace("precision mediump float;\n", "")
                    .replace("precision highp float;\n", "")
                    // blendPix IS ALREADY IN PIP_FRAGMENT, and GLSL ES 1.00 rejects a second body
                    // outright: "'blendPix' : function already has a body". The whole PiP stack
                    // then failed to compile and fell back to the plain composite, which is
                    // precisely the "inverse still doesn't work on a PiP" report. Both sides pull
                    // the equations from BlendModes, so removing the emitted copy by that exact
                    // string keeps ONE authority and cannot drift out of sync with what was
                    // spliced in.
                    .replace(com.fadcam.ui.faditor.model.BlendModes.glslBlendFnWithModeParam(),
                            "");
            StringBuilder fold = new StringBuilder();
            for (com.fadcam.ui.faditor.fx.FxInstance card : fused.cards) {
                fold.append("    fxc = fxBlendOver(fxc, fx").append(card.slot)
                        .append("(uv, fxc), ")
                        .append(FxCompiler.foldOpacityName(card)).append(", ")
                        .append(FxCompiler.foldBlendName(card)).append(");\n");
            }
            apply = "    vec4 fxc = src;\n" + fold + "    src = fxc;\n";
        }
        // SPLICED IMMEDIATELY BEFORE main(), not up among the uniforms. The emitted decls
        // include fxBlendOver, which CALLS blendPix, and GLSL ES 1.00 requires a declaration
        // before its use — placing them higher put the caller above the callee and traded
        // "function already has a body" for "no matching overloaded function". Everything
        // spliced here is global scope, so uniforms are equally happy this far down.
        return base
                .replace("void main() {\n", extrasDecls + decls + "void main() {\n")
                .replace("    vec4 src = texture2D(uPipTexture, s);\n",
                        "    vec4 src = texture2D(uPipTexture, s);\n" + extrasBody + apply);
    }

    /**
     * Composite the PiP: {@code src} → {@code dst}, one pass. {@code stillTexId} is 0 when the
     * pixels arrive on the live decoder's OES surface, or the uploaded 2D texture for a cached
     * still. Returns false when nothing was drawn, so the caller keeps the frame where it was.
     */
    private boolean drawPip(@NonNull Pip p, int src, int dst, int vw, int vh, int stillTexId) {
        int program = pipProgramFor(p);
        if (program == 0) return false;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(program);
        bindQuad(program);
        // The object's own effects, and the frame constants their bodies may read.
        setF2(program, "uTexel", 1f / vw, 1f / vh);
        setF(program, "uAspect", (float) vw / (float) vh);
        setF(program, "uTime", p.timeSec);
        setF(program, "uPipBlend", p.blendMode);
        setF(program, "uPipMaskOn", p.maskOn ? 1f : 0f);
        setF(program, "uPipMaskInvert", p.maskInvert ? 1f : 0f);
        setFn(program, "uPipMaskGeo", new float[]{p.maskGeo[0], p.maskGeo[1],
                p.maskGeo[2], p.maskGeo[3]}, 4);
        setF2(program, "uPipMaskRot", p.maskGeo[4], p.maskGeo[5]);
        setF(program, "uPipMaskCorner", p.maskGeo[6]);
        setF(program, "uPipMaskFeather", p.maskGeo[7]);
        setF2(program, "uPipTexel", 1f / vw, 1f / vh);
        setF2(program, "uDir", 1f, 0f);
        if (p.extras) {
            // Only on the variant that declared them. setFn tolerates a missing location, but
            // asking for one the shader never had would still be a lie about what this draws.
            setFn(program, "uPipKeyColor", p.keyColor, 3);
            setFn(program, "uPipKeyParams", p.keyParams, 4);
            setF(program, "uPipReveal", p.revealFrac);
        }
        for (FxUniforms.Value v : p.fxUniforms) {
            setFn(program, v.name, v.data, v.components());
        }
        setSampler(program, "uTexSampler", targets[src][0], 0, true);
        int loc = GLES20.glGetUniformLocation(program, "uPipTexture");
        if (loc >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            if (stillTexId != 0) {
                // Uploaded still frame (plain 2D). The shader variant samples it with v flipped.
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stillTexId);
            } else {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, pipTexIds[p.liveSlot]);
            }
            GLES20.glUniform1i(loc, 1);
        }
        if (stillTexId == 0) {
            // The decoder's transform matrix exists only on the OES path; the still variant
            // strips it (location -1, which GLES ignores).
            GLES20.glUniformMatrix4fv(
                    GLES20.glGetUniformLocation(program, "uPipTexMatrix"), 1, false,
                    pipTexMatrix[p.liveSlot], 0);
        }
        double rad = Math.toRadians(p.rotationDeg);
        setF2(program, "uPipCentre", p.cx, p.cy);
        setF2(program, "uPipHalf", p.halfW, p.halfH);
        setF(program, "uPipCos", (float) Math.cos(rad));
        setF(program, "uPipSin", (float) Math.sin(rad));
        setF(program, "uPipAspect", (float) vw / (float) vh);
        setF(program, "uPipAlpha", p.alpha);
        setF(program, "uPipRotation", p.rotationDeg);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return true;
    }

    /**
     * Composite one PiP rung over the frame in {@code src}; returns the slot now holding the
     * frame. A PiP whose pixels are not there yet (live decoder yet to produce a frame, still
     * yet to decode) contributes nothing — the slot stays {@code src}, matching the "absent
     * until ready" behaviour the sibling stills have always had.
     */
    private int drawPipRung(@NonNull Pip p, int src, int vw, int vh) {
        if (!p.rendersAnything()) return src;
        int dst = src == 0 ? 1 : 0;
        if (p.still == null) {
            // This PiP's own live decoder, on its own OES surface. A slot that has not been
            // built or has not produced a frame contributes nothing rather than sampling
            // whatever another PiP last left in a neighbouring texture.
            int slot = p.liveSlot;
            SurfaceTexture st = slot < livePipInputs ? pipTextures[slot] : null;
            if (st == null || !pipHasFrame[slot]) return src;
            st.updateTexImage();
            st.getTransformMatrix(pipTexMatrix[slot]);
            return drawPip(p, src, dst, vw, vh, 0) ? dst : src;
        }
        int tex = stillTextureFor(p);
        if (tex == 0) return src;
        return drawPip(p, src, dst, vw, vh, tex) ? dst : src;
    }

    /**
     * The GL texture holding {@code p}'s still frame, uploading only when the bitmap changed.
     * Keyed by clip id so an unchanged still is uploaded once per decode, not once per frame;
     * a texture outlives a re-decode so the PiP keeps showing its last frame instead of blinking.
     */
    private int stillTextureFor(@NonNull Pip p) {
        stillKeysInFrame.add(p.clipId);
        android.graphics.Bitmap b = p.still;
        Integer have = stillTexIds.get(p.clipId);
        if (b == null || b.isRecycled()) return have == null ? 0 : have;
        if (have != null && stillUploaded.get(p.clipId) == b) return have;
        int id = have == null ? newStillTexture() : have;
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            FLog.d("FxMultiPip", "still upload OK " + p.clipId + " tex=" + id
                    + " " + b.getWidth() + "x" + b.getHeight());
        } catch (RuntimeException e) {
            // A bitmap the stills cache recycled underneath us must not take the preview down;
            // keep whatever frame this clip last uploaded. (A native mid-upload crash is avoided
            // by the stillTrash deferral — see OverlayVideoPreviewView.recycleStill.)
            FLog.w(TAG, "still upload failed for " + p.clipId, e);
            return have == null ? 0 : have;
        }
        stillTexIds.put(p.clipId, id);
        stillUploaded.put(p.clipId, b);
        return id;
    }

    /** A plain 2D texture with the clamped, linear sampling the still frames want. */
    private int newStillTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return ids[0];
    }

    /**
     * Free still textures no visible PiP referenced this frame — bounded memory, and a clip
     * that comes back simply re-uploads from its (still cached) bitmap.
     */
    private void evictUnusedStills() {
        java.util.Iterator<java.util.Map.Entry<String, Integer>> it =
                stillTexIds.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Integer> e = it.next();
            if (stillKeysInFrame.contains(e.getKey())) continue;
            try { GLES20.glDeleteTextures(1, new int[]{e.getValue()}, 0); }
            catch (Exception ignored) { }
            it.remove();
            stillUploaded.remove(e.getKey());
        }
    }

    /**
     * Run ONE layer's compiled steps over the frame at {@code from}; returns the slot holding
     * the result.
     *
     * <p><b>Three targets, and the reason is the composite.</b> The last render of each layer
     * reads its own INPUT through {@code uBaseSampler} at the same time as the previous pass's
     * output through {@code uTexSampler}, so the layer's base must survive until that layer is
     * finished. One slot holds the base and the other two ping-pong; when the layer completes,
     * its output becomes the next rung's base and the old base is free again.</p>
     *
     * <p>The interleaved composite walk calls this once per adjustment-layer rung, so a layer
     * sitting between two PiPs grades only the frame beneath it — the export's ordering.</p>
     */
    private int drawOneLayer(@NonNull Layer layer, @NonNull LayerSteps ls, int vw, int vh,
                             int from) {
        List<Step> steps = ls.steps;
        if (steps.isEmpty()) return from;
        int base = from;
        int src = from;
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
            setFn(step.program, "uKeyColor", layer.keyColor, 3);
            setFn(step.program, "uKeyParams", layer.keyParams, 4);
            setF(step.program, "uBlendMode", layer.blendMode);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            src = dst;
        }
        return dst >= 0 ? dst : base;
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
            FLog.w(TAG, "FX shader compile failed; previewing ungraded"
                    + " (max fragment uniform vectors on this device: "
                    + maxFragmentUniformVectors() + ")", e);
            releasePrograms();
            failedKey = want;
            // Told, not just logged. The latch above means this fires once per failing stack, so
            // the user gets one message rather than one per frame, and a different stack reports
            // again — which is what makes deleting the offending card feel like it worked.
            final SurfaceListener l = surfaceListener;
            if (l != null) {
                // Deliberately does NOT claim the cause. A link failure is the LIKELY reason (the
                // uniform ceiling), but this catch also covers a genuine shader bug, and telling
                // the user their phone is too weak when the app miscompiled would be worse than
                // saying nothing. The limit is included because it is the number a bug report
                // needs; the log carries the exception.
                final String reason = "This effect couldn't run on this device's GPU"
                        + " (it allows " + maxFragmentUniformVectors()
                        + " uniform slots per shader). Previewing without it."; // TODO(strings)
                post(() -> l.onFxShaderUnavailable(reason));
            }
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

    /** Cached: one GL query, on the GL thread, the first time anything asks. -1 until then. */
    private int maxFragUniformVecs = -1;

    /**
     * {@code GL_MAX_FRAGMENT_UNIFORM_VECTORS} for this device — the ceiling a shader's uniforms
     * have to fit under, and the one number that explains a link failure.
     *
     * <p>ES 2.0 GUARANTEES only 16. A gradient card declares around 35, which the Note 9 links
     * without complaint; a weaker driver need not. Reading the real figure means a failure report
     * says which side of that line the device actually falls on instead of leaving it a guess.</p>
     */
    private int maxFragmentUniformVectors() {
        if (maxFragUniformVecs < 0) {
            int[] v = new int[1];
            try {
                GLES20.glGetIntegerv(GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS, v, 0);
            } catch (RuntimeException ignored) { }
            // 0 means the query failed or there is no context — report the guaranteed floor
            // rather than a zero that reads as "this device supports no uniforms at all".
            maxFragUniformVecs = v[0] > 0 ? v[0] : 16;
        }
        return maxFragUniformVecs;
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
        // SYNCHRONOUS, and we are already on the main thread. Posting it meant the callback
        // could not run until onSurfaceTextureDestroyed's join(500) returned -- by which time
        // the GL thread had already released the Surface. The comment promised the opposite
        // ordering to what the code did.
        SurfaceListener sl = surfaceListener;
        if (sl != null) sl.onFxInputSurfaceLost();
        if (h != null) {
            h.post(() -> {
                releasePrograms();
                releaseTargets();
                // The cached PiP programs and uploaded still textures belong to this context —
                // free them while it is still current. Retired still bitmaps are recycled here
                // too: it is the GL thread, the only thread that ever touches their pixels.
                for (Integer prog : pipPrograms.values()) {
                    try { GLES20.glDeleteProgram(prog); } catch (Exception ignored) { }
                }
                pipPrograms.clear();
                for (Integer id : stillTexIds.values()) {
                    try { GLES20.glDeleteTextures(1, new int[]{id}, 0); }
                    catch (Exception ignored) { }
                }
                stillTexIds.clear();
                stillUploaded.clear();
                if (baseStillTexId != 0) {
                    try { GLES20.glDeleteTextures(1, new int[]{baseStillTexId}, 0); }
                    catch (Exception ignored) { }
                    baseStillTexId = 0;
                }
                baseStillUploaded = null;
                for (android.graphics.Bitmap b; (b = stillTrash.poll()) != null; ) b.recycle();
                if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
                if (inputTexture != null) { inputTexture.release(); inputTexture = null; }
                int built = livePipInputs;
                livePipInputs = 0;
                for (int i = 0; i < built; i++) {
                    Surface ps = pipSurfaces[i];
                    pipSurfaces[i] = null;
                    if (ps != null) ps.release();
                    if (pipTextures[i] != null) { pipTextures[i].release(); pipTextures[i] = null; }
                    pipHasFrame[i] = false;
                }
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
