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
            // ONE SLOT PER PACKED SHAPE. __MASKN__ is substituted with this object's shape
            // count by pipFragment, so a single-mask object compiles the identical shader it
            // always did (arrays of one, no loop cost) while an 8-shape mask finally previews
            // as all eight — the export's Path.op fold has always applied every one of them.
            + "uniform vec4 uPipMaskGeo[__MASKN__];\n"
            + "uniform vec2 uPipMaskRot[__MASKN__];\n"
            + "uniform float uPipMaskCorner[__MASKN__];\n"
            + "uniform float uPipMaskFeather[__MASKN__];\n"
            + "uniform float uPipMaskOp[__MASKN__];\n"
            + "uniform float uPipMaskInvert;\n"
            + "uniform vec2 uPipTexel;\n"
            + "uniform float uMatteOn;\n"
            + "uniform sampler2D uMatteSampler;\n"
            + "uniform vec2 uMatteCentre;\n"
            + "uniform vec2 uMatteHalf;\n"
            + "uniform float uMatteCos;\n"
            + "uniform float uMatteSin;\n"
            + "uniform float uMatteAspect;\n"
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
            // The fold is MaskSdf.coverage's, shape for shape: seed with the first, then
            // union/difference/intersect as MaskFold ordered them. Constant loop bound (no
            // break, no dynamic index) so GLSL ES 1.00 accepts it on every driver.
            + "      float inside = 0.0;\n"
            + "      for (int i = 0; i < __MASKN__; i++) {\n"
            + "        float sd = fxShapeSd(vFxUv, frame, uPipMaskGeo[i],\n"
            + "                             uPipMaskRot[i], uPipMaskCorner[i]);\n"
            + "        float c = fxCoverageOf(sd, uPipMaskFeather[i]);\n"
            + "        if (i == 0) inside = c;\n"
            + "        else if (uPipMaskOp[i] > 1.5) inside = min(inside, c);\n"
            + "        else if (uPipMaskOp[i] > 0.5) inside = min(inside, 1.0 - c);\n"
            + "        else inside = max(inside, c);\n"
            + "      }\n"
            // HOLE BY DEFAULT — see fxMaskCover in MaskSdf.GLSL_MASK_FN, the single
            // statement of this policy shared by all three GL consumers.
            + "      cover = fxMaskCover(inside, uPipMaskInvert);\n"
            + "    }\n"
            + "    if (uMatteOn > 0.5) {\n"
            + "      vec2 mp = vFxUv - uMatteCentre;\n"
            + "      mp.x *= uMatteAspect;\n"
            + "      vec2 mr = vec2(mp.x * uMatteCos + mp.y * uMatteSin,\n"
            + "               -mp.x * uMatteSin + mp.y * uMatteCos);\n"
            + "      mr.x /= uMatteAspect;\n"
            + "      vec2 mq = mr / uMatteHalf;\n"
            + "      vec4 mt = vec4(0.0);\n"
            + "      if (abs(mq.x) <= 1.0 && abs(mq.y) <= 1.0) {\n"
            + "        vec2 muv = mq * 0.5 + 0.5;\n"
            + "        mt = texture2D(uMatteSampler, vec2(muv.x, 1.0 - muv.y));\n"
            + "      }\n"
            + "      vec3 mc = mt.rgb / max(mt.a, 0.001);\n"
            + "      float luma = dot(mc, vec3(0.299, 0.587, 0.114)) * mt.a;\n"
            + "      cover *= clamp(luma, 0.0, 1.0);\n"
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

    /**
     * SPEC E mesh-stamp composite: {@link #PIP_STILL_FRAGMENT} sampling a frame-sized stamp FBO
     * instead of a {@code GLUtils} bitmap. Derived textually so blend/mask/key/FX maths can never
     * drift; only the sampling differs: an FBO texture is bottom-up (like the OES picture and the
     * ping-pong targets, all sampled without a flip), while a bitmap upload is top-row-first
     * (sampled with {@code 1-y}). An unmeshed object never compiles this string.
     */
    private static final String PIP_MESH_FRAGMENT = PIP_STILL_FRAGMENT
            .replace("vec2 s = vec2(uv.x, 1.0 - uv.y);",
                     "vec2 s = uv;");

    /**
     * The CLIP CROP pass: keep only {@code uCropSrc}'s sub-rectangle of the staged frame and
     * fit-centre it into this target, transparent outside.
     *
     * <p><b>Fit-centred, not stretched, and that is not a style choice.</b> media3's
     * {@code Crop} is a MatrixTransformation whose {@code configure} returns a Size scaled by
     * the crop fractions — the exported frame IS the crop region at its own aspect, which the
     * trailing {@code Presentation(LAYOUT_SCALE_TO_FIT)} then letterboxes onto the canvas
     * (bytecode-verified against media3-effect 1.8.0, 2026-08-25). Reproducing the stretch
     * instead would distort exactly the crops whose aspect differs from the source — most of
     * them, given the preset table.</p>
     *
     * <p>{@code uCropSrc}/{@code uCropDst} are rects as x0,y0,w,h in bottom-up uv space,
     * precomputed per frame on the caller side so the shader stays one comparison and one mix.
     * Runs BEFORE the grade — the export chain orders rotate → crop → grade, and grading the
     * letterbox bars black-on-grade would tint them.</p>
     */
    private static final String CROP_FRAGMENT =
            "#version 100\n"
            + "precision mediump float;\n"
            + "varying vec2 vFxUv;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "uniform vec4 uCropSrc;\n"
            + "uniform vec4 uCropDst;\n"
            + "void main() {\n"
            + "  if (vFxUv.x < uCropDst.x || vFxUv.x > uCropDst.x + uCropDst.z\n"
            + "      || vFxUv.y < uCropDst.y || vFxUv.y > uCropDst.y + uCropDst.w) {\n"
            + "    gl_FragColor = vec4(0.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  float sx = clamp((vFxUv.x - uCropDst.x) / max(uCropDst.z, 0.0001), 0.0, 1.0);\n"
            + "  float sy = clamp((vFxUv.y - uCropDst.y) / max(uCropDst.w, 0.0001), 0.0, 1.0);\n"
            + "  gl_FragColor = texture2D(uTexSampler,\n"
            + "      vec2(uCropSrc.x + sx * uCropSrc.z, uCropSrc.y + sy * uCropSrc.w));\n"
            + "}\n";

    /**
     * THE CLIP FADE: scale the base picture's RGB by the clip's opacity envelope times its
     * master fade-knob factor. One full-frame pass, run ONLY when that product is below 1.
     *
     * <p>RGB ONLY, alpha untouched - the picture fades to BLACK, not to transparent. That is
     * what the exported file shows: {@code OpacityExportShaderProgram} scales RGB and alpha
     * together, the H.264 encoder then discards alpha, and the RGB that lands in the frame is
     * exactly {@code c.rgb * o} - these same numbers. Scaling alpha here instead would make the
     * darkening depend on what sits UNDER the picture in the composite and on how every later
     * pass treats the alpha channel, and the owner wants the clip to darken toward the black
     * backdrop rather than reveal the layers beneath it. It also leaves the letterbox bars
     * {@link #stageViewport} and {@link #drawCrop} clear to transparent exactly as transparent
     * as they were (rgb 0 x o is still 0, alpha 0 is untouched).</p>
     */
    private static final String PICTURE_ALPHA_FRAGMENT =
            "#version 100\n"
            + "precision mediump float;\n"
            + "varying vec2 vFxUv;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "uniform float uPictureAlpha;\n"
            + "void main() {\n"
            + "  vec4 c = texture2D(uTexSampler, vFxUv);\n"
            + "  gl_FragColor = vec4(c.rgb * uPictureAlpha, c.a);\n"
            + "}\n";

    /**
     * GL pilot: composite a full-frame rasterised Canvas layer (layer_image_overlay) over
     * the frame so far. Top-row-first upload flips v, so sample with 1.0 - y like the
     * still variant. Simple alpha-over; no blend/mask in this pilot — the Canvas already
     * baked per-item opacity/position into the bitmap. This is the convergence path that
     * would make promote rules deletable if cheap enough.
     */
    private static final String LAYER_FRAGMENT =
            "#version 100\n"
            + "precision mediump float;\n"
            + "varying vec2 vFxUv;\n"
            + "uniform sampler2D uBaseSampler;\n"
            + "uniform sampler2D uLayerSampler;\n"
            + "void main() {\n"
            + "  vec4 base = texture2D(uBaseSampler, vFxUv);\n"
            + "  vec4 layer = texture2D(uLayerSampler, vec2(vFxUv.x, 1.0 - vFxUv.y));\n"
            + "  gl_FragColor = vec4(mix(base.rgb, layer.rgb, layer.a), max(base.a, layer.a));\n"
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
     * SPEC E mesh stamp inputs — one immutable snapshot per frame per bent image, built on the
     * MAIN thread (see {@code FxLivePreviewController}) and read on the GL thread. All placement
     * normalized top-left 0..1 (model space); matrices are built inside {@link MeshStampGl} via
     * {@code MeshPlacement} — the single authority both renderers share. The spec copy is deep
     * (never the live model) so a drag racing the draw cannot tear a pose.
     */
    public static final class MeshInputs {
        @NonNull final com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec spec;
        final long localMs;
        final float cx, cy, wNorm, hNorm;
        final float pivOffX, pivOffY;
        final boolean applyPivot;
        final float rotDeg;
        final float presetScaleX, presetScaleY, presetDx, presetDy;
        final float alpha, reveal;
        @Nullable final float[] cornerPin8;
        /**
         * SPEC H mirror signs from {@code TextOverlayItem.mirrorSignX/Y} (+1/-1) — the model's
         * ONE shared definition, read once on the main thread. The stamp shader applies them;
         * (1,1) draws exactly what shipped.
         */
        final float mirrorX, mirrorY;
        MeshInputs(@NonNull com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec spec, long localMs,
                   float cx, float cy, float wNorm, float hNorm,
                   float pivOffX, float pivOffY, boolean applyPivot, float rotDeg,
                   float presetScaleX, float presetScaleY, float presetDx, float presetDy,
                   float alpha, float reveal, @Nullable float[] cornerPin8,
                   float mirrorX, float mirrorY) {
            this.spec = spec;
            this.localMs = localMs;
            this.cx = cx; this.cy = cy; this.wNorm = wNorm; this.hNorm = hNorm;
            this.pivOffX = pivOffX; this.pivOffY = pivOffY; this.applyPivot = applyPivot;
            this.rotDeg = rotDeg;
            this.presetScaleX = presetScaleX; this.presetScaleY = presetScaleY;
            this.presetDx = presetDx; this.presetDy = presetDy;
            this.alpha = alpha; this.reveal = reveal;
            this.cornerPin8 = cornerPin8 == null ? null : cornerPin8.clone();
            this.mirrorX = mirrorX < 0f ? -1f : 1f;
            this.mirrorY = mirrorY < 0f ? -1f : 1f;
        }
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
        /**
         * The packed mask, split into the per-shape uniform arrays {@link #drawPip} uploads.
         *
         * <p><b>Every shape, not just the first.</b> {@code MaskSdf.packShapes} has always
         * packed up to {@link MaskSdf#MAX_SHAPES}, and the export's Canvas fold applies all of
         * them; the composite uploaded {@code maskGeo[0..7]} — shape ZERO — so a two-rectangle
         * mask previewed as one rectangle and rendered as two. Split here, on the main thread,
         * because doing it per frame on the GL thread would allocate five arrays per PiP per
         * frame for a value that cannot change between syncs.</p>
         */
        final int maskShapes;
        @NonNull final float[] maskGeo4;      // cx, cy, w, h
        @NonNull final float[] maskRot2;      // cos, sin
        @NonNull final float[] maskCorner;
        @NonNull final float[] maskFeather;
        /** {@code MaskFold} ordinals as floats — the shader has no integer uniforms here. */
        @NonNull final float[] maskOpCodes;
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
        /**
         * CORNER PIN, as the INVERSE homography the fragment stage needs — null when this object
         * is not pinned, which is every PiP and every unpinned image.
         *
         * <p><b>Why an inverse map and not a warped quad.</b> This shader finds the picture by
         * inverse-mapping the fragment: subtract the centre, unrotate, divide by the half-extents.
         * The honest-looking alternative is real geometry — a two-triangle quad at the four pinned
         * corners with straight UVs — and it was rejected for three specific costs, not for taste.
         * (1) It needs PERSPECTIVE-CORRECT interpolation: a homography is not affine, so linearly
         * interpolated UVs across two triangles produce the classic diagonal seam, and fixing it
         * means solving the diagonal-intersection weights and smuggling them through
         * {@code gl_Position.w}. (2) The composite ping-pongs into a target that nothing else
         * writes, so a quad covering only the picture would leave every other pixel of the frame
         * holding the PREVIOUS frame's garbage — it would need a full-frame copy pass first, i.e.
         * a second draw per pinned image anyway. (3) It needs a per-object vertex buffer and a
         * second vertex shader, in the one place five other lanes are editing.</p>
         *
         * <p><b>And the inverse is not expensive.</b> The inverse of a 3x3 homography is another
         * 3x3 homography — {@code Matrix.invert} solves it ONCE on the main thread — so the
         * per-fragment cost is one mat3 multiply and one divide, against the existing two
         * multiplies. Nothing is iterated and nothing is solved per pixel.</p>
         *
         * <p>Nine floats, COLUMN-major, because {@code glUniformMatrix3fv} must be called with
         * {@code transpose = false} on GL ES 2.0 (ES 2.0 rejects a true transpose flag outright),
         * while {@code Matrix.getValues} hands back row-major. The transposition is done in
         * {@link #pinUniforms} rather than in the shader.</p>
         */
        @Nullable final float[] pinInv;
        /**
         * The remap from this object's PADDED box into its own unit rect: {@code d = uv * zw +
         * xy}, i.e. {@code {-exX, -exY, 1 + 2exX, 1 + 2exY}}.
         *
         * <p>A pulled corner lands OUTSIDE the picture's rectangle, so the quad the shader tests
         * against is grown by the largest excursion on every side — the same inflation
         * {@code TextOverlayLayer} performs on the {@code CornerPinImageView}'s bounds, and for
         * the identical reason: without it the pinned corner is simply clipped off. Growing it
         * symmetrically about the centre is what keeps the rotation pivot where it was.</p>
         */
        @Nullable final float[] pinPad;
        /** Track matte: luma of matte peer becomes this clip's alpha (B3). Reuses same math as export. */
        final boolean matteOn;
        @Nullable final String matteClipId;
        @Nullable final android.graphics.Bitmap matteStill;
        final float matteCx, matteCy, matteHalfW, matteHalfH, matteRotationDeg;
        /**
         * SPEC E mesh: stamp inputs snapshot (null = ordinary flat path). Built on the main thread
         * by {@code FxLivePreviewController}; read on the GL thread. The spec inside is a deep copy.
         */
        @Nullable final MeshInputs mesh;
        /**
         * SPEC E mesh composite flag: true for the synthetic identity Pip that composites a stamp
         * (frame-sized FBO, no flip). Picks {@link #PIP_MESH_FRAGMENT}; an unmeshed object never
         * sets it so its program string is character-for-character what shipped.
         */
        final boolean meshComposite;

        public Pip(float cx, float cy, float halfW, float halfH, float rotationDeg, float alpha,
                   @Nullable FxCompiler.Pass fused,
                   @NonNull List<FxUniforms.Value> fxUniforms, @NonNull String fxKey,
                   float timeSec, float blendMode, boolean maskOn, boolean maskInvert,
                   @NonNull float[] maskGeo,
                   @NonNull String clipId, @Nullable android.graphics.Bitmap still,
                   int liveSlot,
                   boolean extras, @NonNull float[] keyColor, @NonNull float[] keyParams,
                   float revealFrac) {
            this(cx, cy, halfW, halfH, rotationDeg, alpha, fused, fxUniforms, fxKey, timeSec, blendMode, maskOn, maskInvert, maskGeo, clipId, still, liveSlot, extras, keyColor, keyParams, revealFrac, false, null, null, 0f, 0f, 0f, 0f, 0f);
        }

        public Pip(float cx, float cy, float halfW, float halfH, float rotationDeg, float alpha,
                   @Nullable FxCompiler.Pass fused,
                   @NonNull List<FxUniforms.Value> fxUniforms, @NonNull String fxKey,
                   float timeSec, float blendMode, boolean maskOn, boolean maskInvert,
                   @NonNull float[] maskGeo,
                   @NonNull String clipId, @Nullable android.graphics.Bitmap still,
                   int liveSlot,
                   boolean extras, @NonNull float[] keyColor, @NonNull float[] keyParams,
                   float revealFrac,
                   boolean matteOn, @Nullable String matteClipId, @Nullable android.graphics.Bitmap matteStill,
                   float matteCx, float matteCy, float matteHalfW, float matteHalfH, float matteRotationDeg) {
            this(cx, cy, halfW, halfH, rotationDeg, alpha, fused, fxUniforms, fxKey, timeSec,
                    blendMode, maskOn, maskInvert, maskGeo, clipId, still, liveSlot, extras,
                    keyColor, keyParams, revealFrac, matteOn, matteClipId, matteStill,
                    matteCx, matteCy, matteHalfW, matteHalfH, matteRotationDeg,
                    new float[Math.max(1, maskGeo.length / MaskSdf.FLOATS_PER_SHAPE)],
                    null, null, null, false);
        }

        /**
         * The full constructor, with the per-shape boolean OPS the fold needs. The overload
         * above defaults them to UNION so the pre-existing signature keeps working; every path
         * that actually has a {@code CompositingSpec} comes through {@link #build}, which asks
         * {@code MaskSdf.packOps} for the real ones.
         */
        private Pip(float cx, float cy, float halfW, float halfH, float rotationDeg, float alpha,
                   @Nullable FxCompiler.Pass fused,
                   @NonNull List<FxUniforms.Value> fxUniforms, @NonNull String fxKey,
                   float timeSec, float blendMode, boolean maskOn, boolean maskInvert,
                   @NonNull float[] maskGeo,
                   @NonNull String clipId, @Nullable android.graphics.Bitmap still,
                   int liveSlot,
                   boolean extras, @NonNull float[] keyColor, @NonNull float[] keyParams,
                   float revealFrac,
                   boolean matteOn, @Nullable String matteClipId, @Nullable android.graphics.Bitmap matteStill,
                   float matteCx, float matteCy, float matteHalfW, float matteHalfH, float matteRotationDeg,
                   @NonNull float[] maskOpCodes,
                   @Nullable float[] pinInv, @Nullable float[] pinPad,
                   @Nullable MeshInputs mesh, boolean meshComposite) {
            this.pinInv = pinInv;
            this.pinPad = pinPad;
            int n = Math.max(1, Math.min(MaskSdf.MAX_SHAPES,
                    maskGeo.length / MaskSdf.FLOATS_PER_SHAPE));
            this.maskShapes = n;
            this.maskGeo4 = new float[n * 4];
            this.maskRot2 = new float[n * 2];
            this.maskCorner = new float[n];
            this.maskFeather = new float[n];
            this.maskOpCodes = new float[n];
            for (int i = 0; i < n; i++) {
                int o = i * MaskSdf.FLOATS_PER_SHAPE;
                maskGeo4[i * 4] = maskGeo[o];
                maskGeo4[i * 4 + 1] = maskGeo[o + 1];
                maskGeo4[i * 4 + 2] = maskGeo[o + 2];
                maskGeo4[i * 4 + 3] = maskGeo[o + 3];
                maskRot2[i * 2] = maskGeo[o + 4];
                maskRot2[i * 2 + 1] = maskGeo[o + 5];
                maskCorner[i] = maskGeo[o + 6];
                maskFeather[i] = maskGeo[o + 7];
                this.maskOpCodes[i] = i < maskOpCodes.length ? maskOpCodes[i] : 0f;
            }
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
            this.matteOn = matteOn;
            this.matteClipId = matteClipId;
            this.matteStill = matteStill;
            this.matteCx = matteCx;
            this.matteCy = matteCy;
            this.matteHalfW = matteHalfW;
            this.matteHalfH = matteHalfH;
            this.matteRotationDeg = matteRotationDeg;
            this.mesh = mesh;
            this.meshComposite = meshComposite;
        }

        /**
         * SPEC E: copy carrying a mesh stamp snapshot. The spec inside {@code mi} is already a
         * deep copy owned by this Pip; the GL thread never touches the live model.
         */
        @NonNull
        public Pip withMesh(@NonNull MeshInputs mi) {
            return new Pip(cx, cy, halfW, halfH, rotationDeg, alpha, fused, fxUniforms, fxKey, timeSec,
                    blendMode, maskOn, maskInvert, maskGeo, clipId, still, liveSlot, extras,
                    keyColor, keyParams, revealFrac, matteOn, matteClipId, matteStill,
                    matteCx, matteCy, matteHalfW, matteHalfH, matteRotationDeg,
                    maskOpCodes, pinInv, pinPad, mi, false);
        }

        /**
         * SPEC E: synthetic identity Pip compositing a frame-sized stamp. Same mask/blend/key/FX
         * as the source item, but centred full-frame with alpha/reveal baked already in the stamp
         * (1,1 here avoids doubling). Picks {@link #PIP_MESH_FRAGMENT} (FBO sampling, no flip).
         */
        @NonNull
        public Pip meshCompositePip() {
            return new Pip(0.5f, 0.5f, 0.5f, 0.5f, 0f, 1f, fused, fxUniforms, fxKey, timeSec,
                    blendMode, maskOn, maskInvert, maskGeo, clipId, still, liveSlot, extras,
                    keyColor, keyParams, 1f, false, null, null,
                    0f, 0f, 0f, 0f, 0f,
                    maskOpCodes, null, null, null, true);
        }

        /** Copy this Pip with a still-frame matte peer (budget-safe fallback). */
        @NonNull
        public Pip withMatte(@NonNull Pip mattePeer) {
            // Matte peer's own still may be null if it's live; fallback still is already in mattePeer.still if available, else null -> degrade to unmatted
            boolean on = mattePeer.still != null && !mattePeer.still.isRecycled() && mattePeer.matteClipId == null;
            // Actually mattePeer is a normal Pip for the matte clip; its still is the matte texture.
            // We treat matteOn true only when mattePeer has a bitmap.
            if (mattePeer.still == null || mattePeer.still.isRecycled()) {
                on = false;
            }
            return new Pip(cx, cy, halfW, halfH, rotationDeg, alpha, fused, fxUniforms, fxKey, timeSec, blendMode, maskOn, maskInvert, maskGeo, clipId, still, liveSlot, extras, keyColor, keyParams, revealFrac,
                    on, mattePeer.clipId, mattePeer.still, mattePeer.cx, mattePeer.cy, mattePeer.halfW, mattePeer.halfH, mattePeer.rotationDeg,
                    maskOpCodes, pinInv, pinPad, mesh, meshComposite);
        }

        /** Copy with matte disabled (dangling peer). */
        @NonNull
        public Pip withoutMatte() {
            if (!matteOn) return this;
            return new Pip(cx, cy, halfW, halfH, rotationDeg, alpha, fused, fxUniforms, fxKey, timeSec, blendMode, maskOn, maskInvert, maskGeo, clipId, still, liveSlot, extras, keyColor, keyParams, revealFrac,
                    false, null, null, 0f, 0f, 0f, 0f, 0f,
                    maskOpCodes, pinInv, pinPad, mesh, meshComposite);
        }

        /** True when this object must run the corner-pinned variant of the composite shader. */
        boolean pinned() { return pinInv != null && pinPad != null; }

        boolean rendersAnything() {
            // SPEC G: abs — a mirrored half-extent arrives negative and draws mirrored.
            return alpha > 0.004f && Math.abs(halfW) > 0f && Math.abs(halfH) > 0f;
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
            return ofImage(cx, cy, halfW, halfH, rot, alpha, stack, editorMs, spec, blendMode,
                    frameW, frameH, itemId, still, revealFrac, null);
        }

        /**
         * The same, CORNER-PINNED.
         *
         * <p>An image that carries FX, a chroma key or a blend mode leaves its
         * {@code CornerPinImageView} and is drawn here instead — and this chain had no pin
         * channel, so exactly those images previewed FLAT while {@code ImageOverlayDraw} exported
         * them PINNED. {@code cornerPin8} closes that: the offsets are
         * {@code TextOverlayItem.animatedCornerPin}'s, untouched, and everything the shader needs
         * is derived from them here so there is no second reading of what a pin means.</p>
         *
         * @param cornerPin8 packed corner offsets, or null / flat for an unpinned image — which
         *                   takes the byte-identical program and uniform set it always did.
         */
        @NonNull
        public static Pip ofImage(float cx, float cy, float halfW, float halfH, float rot,
                                  float alpha, @Nullable FxStack stack, long editorMs,
                                  @Nullable CompositingSpec spec, float blendMode,
                                  int frameW, int frameH, @NonNull String itemId,
                                  @Nullable android.graphics.Bitmap still, float revealFrac,
                                  @Nullable float[] cornerPin8) {
            float[][] pin = pinUniforms(cornerPin8, halfW < 0f, halfH < 0f);
            if (pin == null) {
                return build(cx, cy, halfW, halfH, rot, alpha, stack, editorMs, spec, blendMode,
                        frameW, frameH, itemId, still, /* liveSlot= */ 0,
                        /* extras= */ true, revealFrac, null, null);
            }
            // The BOX grows by the excursion; the CENTRE does not move, so the rotation pivot and
            // the mask (which is in frame space) are untouched. pin[1] is {-ex, +1+2ex}, so the
            // padded half-extent is the unpadded one times the same 1 + 2ex.
            return build(cx, cy, halfW * pin[1][2], halfH * pin[1][3], rot, alpha, stack, editorMs,
                    spec, blendMode, frameW, frameH, itemId, still, /* liveSlot= */ 0,
                    /* extras= */ true, revealFrac, pin[0], pin[1]);
        }

        /**
         * Resolve packed corner offsets into the two uniforms the pinned shader reads, or null
         * when there is no usable distortion.
         *
         * <p><b>The whole y-flip, in one place.</b> {@code CornerPin}'s offsets are +y DOWN, in
         * the item's own top-left-origin space — the space the Canvas preview and
         * {@code ImageOverlayDraw} both draw in. This shader works in a bottom-up uv (which is
         * also why the caller hands it {@code -rotation}). So the map the fragment needs is the
         * inverse homography CONJUGATED by the flip, {@code F . H^-1 . F} where
         * {@code F(x, y) = (x, 1 - y)} — flip into the item's space, undo the pin, flip back.
         * Doing the flip inside the matrix rather than around it in GLSL is what keeps the shader
         * to one multiply, and keeps the sign convention stated once instead of in two shaders.</p>
         *
         * <p>Built on the UNIT SQUARE, which is why no frame size, scale or rotation appears here:
         * an offset is a fraction of the item's own drawn size, so in the item's own 0..1 box it
         * IS the corner displacement. The same authored pin therefore means the same shape at any
         * resolution — the property {@code CornerPin}'s class note exists to protect.</p>
         *
         * @return {@code {inverse (9, column-major), pad remap (4)}}, or null when the offsets are
         *         flat, the destination quad is degenerate ({@code setPolyToPoly} refuses), or the
         *         solved matrix will not invert. Every one of those falls back to drawing the
         *         image UNPINNED, which is what {@code CornerPin.buildMatrix} already chooses for
         *         the Canvas paths — an item drawn flat for a frame is recoverable, an item drawn
         *         through garbage is not.
         *
         * <p><b>Composition order is mirror OUTSIDE pin</b> — {@code (M . H)^-1} — the order the
         * export draws ({@code ImageOverlayDraw} concats the mirror first and the pin second)
         * and the order {@code CornerPinImageView} assembles. The fragment coordinate the
         * shader feeds in already wears the mirror (it divides by the SIGNED half-extent), so
         * the inverse must unmirror first and unpin second; inverting the pin alone reads the
         * mirrored coordinate as an unmirrored one and shoves the picture sideways exactly
         * like the Canvas path did before it was fixed (JoyRaptor 2026-09-07). A flat pin still
         * returns null — the signed box alone draws a flat mirror with no matrix at all.
         */
        @Nullable
        private static float[][] pinUniforms(@Nullable float[] cornerPin8,
                                             boolean mirrorX, boolean mirrorY) {
            if (com.fadcam.ui.faditor.model.CornerPin.isFlat(cornerPin8)) return null;
            android.graphics.Matrix h = new android.graphics.Matrix();
            if (!com.fadcam.ui.faditor.model.CornerPin.buildMatrix(
                    h, 0f, 0f, 1f, 1f, cornerPin8)) {
                return null;
            }
            if (mirrorX || mirrorY) {
                android.graphics.Matrix total = new android.graphics.Matrix();
                total.setScale(mirrorX ? -1f : 1f, mirrorY ? -1f : 1f, 0.5f, 0.5f);
                // preConcat multiplies on the source side: total = mirror . pin.
                total.preConcat(h);
                h = total;
            }
            android.graphics.Matrix inv = new android.graphics.Matrix();
            if (!h.invert(inv)) return null;
            android.graphics.Matrix flip = new android.graphics.Matrix();
            flip.setValues(new float[]{1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f, 1f});
            android.graphics.Matrix m = new android.graphics.Matrix(flip);
            // preConcat applies the argument FIRST, so this builds flip . inv . flip.
            m.preConcat(inv);
            m.preConcat(flip);
            float[] v = new float[9];
            m.getValues(v);
            float[] column = {v[0], v[3], v[6], v[1], v[4], v[7], v[2], v[5], v[8]};
            float[] ex = com.fadcam.ui.faditor.model.CornerPin.excursionFraction(cornerPin8);
            float[] pad = {-ex[0], -ex[1], 1f + 2f * ex[0], 1f + 2f * ex[1]};
            return new float[][]{column, pad};
        }

        @NonNull
        private static Pip build(float cx, float cy, float halfW, float halfH, float rot,
                             float alpha,
                             @Nullable FxStack stack, long editorMs,
                             @Nullable CompositingSpec spec, float blendMode,
                             int frameW, int frameH,
                             @NonNull String clipId, @Nullable android.graphics.Bitmap still,
                             int liveSlot, boolean extras, float revealFrac) {
            return build(cx, cy, halfW, halfH, rot, alpha, stack, editorMs, spec, blendMode,
                    frameW, frameH, clipId, still, liveSlot, extras, revealFrac, null, null);
        }

        @NonNull
        private static Pip build(float cx, float cy, float halfW, float halfH, float rot,
                             float alpha,
                             @Nullable FxStack stack, long editorMs,
                             @Nullable CompositingSpec spec, float blendMode,
                             int frameW, int frameH,
                             @NonNull String clipId, @Nullable android.graphics.Bitmap still,
                             int liveSlot, boolean extras, float revealFrac,
                             @Nullable float[] pinInv, @Nullable float[] pinPad) {
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
            int[] ops = MaskSdf.packOps(spec);
            float[] opCodes = new float[ops.length];
            for (int i = 0; i < ops.length; i++) opCodes[i] = ops[i];
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
                    revealFrac,
                    /* matteOn= */ false, null, null, 0f, 0f, 0f, 0f, 0f,
                    opCodes, pinInv, pinPad, null, false);
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
        /**
         * The packed mask, split into the per-shape uniform arrays {@link #drawOneLayer}
         * uploads.
         *
         * <p><b>Every shape, not just the first.</b> This used to upload {@code geo[0..7]} —
         * shape ZERO — into a single-shape uniform block, so a two-rectangle mask on an
         * adjustment layer graded through one rectangle. The EXPORT reads the same shader
         * string from {@code FxGlSource} and was wrong in exactly the same way, which is why
         * both were fixed as one change. Split here, on the main thread, for the reason
         * {@link Pip#maskShapes} is: five arrays per layer per frame on the GL thread would be
         * churn for a value that cannot change between syncs.</p>
         */
        final int maskShapes;
        @NonNull final float[] maskGeo4;      // cx, cy, w, h
        @NonNull final float[] maskRot2;      // cos, sin
        @NonNull final float[] maskCorner;
        @NonNull final float[] maskFeather;
        /** {@code MaskFold} ordinals as floats — the shader has no integer uniforms here. */
        @NonNull final float[] maskOpCodes;
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
                      @NonNull float[] keyColor, @NonNull float[] keyParams, float blendMode,
                      @NonNull float[] maskOpCodes) {
            this.plan = plan;
            this.sourceKey = sourceKey;
            this.uniforms = uniforms;
            this.geo = geo;
            int n = Math.max(1, Math.min(MaskSdf.MAX_SHAPES,
                    geo.length / MaskSdf.FLOATS_PER_SHAPE));
            this.maskShapes = n;
            this.maskGeo4 = new float[n * 4];
            this.maskRot2 = new float[n * 2];
            this.maskCorner = new float[n];
            this.maskFeather = new float[n];
            this.maskOpCodes = new float[n];
            for (int i = 0; i < n; i++) {
                int o = i * MaskSdf.FLOATS_PER_SHAPE;
                maskGeo4[i * 4] = geo[o];
                maskGeo4[i * 4 + 1] = geo[o + 1];
                maskGeo4[i * 4 + 2] = geo[o + 2];
                maskGeo4[i * 4 + 3] = geo[o + 3];
                maskRot2[i * 2] = geo[o + 4];
                maskRot2[i * 2 + 1] = geo[o + 5];
                maskCorner[i] = geo[o + 6];
                maskFeather[i] = geo[o + 7];
                this.maskOpCodes[i] = i < maskOpCodes.length ? maskOpCodes[i] : 0f;
            }
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
            int[] ops = MaskSdf.packOps(cs);
            float[] opCodes = new float[ops.length];
            for (int i = 0; i < ops.length; i++) opCodes[i] = ops[i];
            return new Layer(plan, key.toString(), uniforms,
                    MaskSdf.packShapes(cs, videoW, videoH),
                    layer.opacityAt(editorMs),
                    cs != null && !cs.masks.isEmpty(),
                    cs != null && cs.invertMasks,
                    editorMs / 1000f,
                    com.fadcam.ui.faditor.model.ChromaKey.packColor(cs),
                    com.fadcam.ui.faditor.model.ChromaKey.packParams(cs),
                    com.fadcam.ui.faditor.model.BlendModes.modeCode(layer.getBlendMode()),
                    opCodes);
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
        /**
         * Index into {@link #layers} of the MASTER (spine) clip's own FxStack, or -1 when the
         * clip under the playhead has no active stack.
         *
         * <p><b>Not a {@link Rung}, deliberately.</b> The rung walk runs after the below-blend
         * raster and the caption quads, so a spine grade emitted as rung 0 would grade the
         * captions and the below-blend text as well. The export puts the spine stack
         * immediately after the clip's own crop/grade and BEFORE all of that
         * ({@code ExportManager}:3150 — "before the below-blend pass, the PiP loop, the
         * adjustment-layer inserts and the caption/text OverlayEffect"), so it gets its own slot
         * at the same point in {@link #drawFrame}. It still shares {@code layers}, so it is
         * compiled by the same {@code ensurePrograms} call as every other layer.</p>
         */
        final int spineLayerIndex;

        public CompositePlan(@NonNull List<Layer> layers, @NonNull List<Rung> rungs) {
            this(layers, rungs, -1);
        }

        public CompositePlan(@NonNull List<Layer> layers, @NonNull List<Rung> rungs,
                             int spineLayerIndex) {
            this.layers = layers;
            this.rungs = rungs;
            this.spineLayerIndex = spineLayerIndex;
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
     * The CLIP CROP under the playhead, as fractions {@code {left, top, right, bottom}} with a
     * TOP-LEFT origin — the exact array {@code Clip.effectiveCropFractions()} returns — or null
     * when the playhead's clip is uncropped (or crop display is suppressed, e.g. inside the
     * crop editor). Applied between staging and grading, which is the export chain's order.
     */
    @Nullable private volatile float[] clipCrop;

    /** @see #setPictureAlpha - 1f is "no fade", what every project without one carries. */
    private volatile float pictureAlpha = 1f;
    /**
     * The playhead clip's SPINE CANVAS TRANSFORM, resolved on the main thread, or null for the
     * plain fit-centre every project has today.
     *
     * <p>A resolved pose rather than the Clip, for the same reason every other snapshot in this
     * file is a snapshot: the GL thread must never walk the live model. Null is the no-op, and
     * {@code drawFrame} skips the whole pass on it - no shader, no framebuffer, no ping-pong
     * flip - so an unplaced project runs the identical pass list it ran before this existed.</p>
     */
    @Nullable private volatile float[] spinePose;
    /**
     * The BASE frame as a bitmap — an image master clip — or null to stage the decoder instead.
     *
     * <p>Not owned here. The publisher must never recycle a bitmap it has handed over; retire it
     * through {@link #stillTrash} exactly as the PiP stills do, or {@code texImage2D} reads freed
     * pixel memory and takes the process down at a native frame no catch block reaches.</p>
     */
    @Nullable private volatile android.graphics.Bitmap baseStill;
    private volatile int videoW = 0, videoH = 0;
    /**
     * The DECODED picture's size, when it differs from the composite frame.
     *
     * <p>{@link #videoW}/{@link #videoH} are the frame the whole chain runs in — the CANVAS,
     * as of the frame-space fix — and the decoder's picture is fit-centred into it by
     * {@link #drawStage}, exactly as the export's trailing {@code Presentation
     * (LAYOUT_SCALE_TO_FIT)} fits it onto the canvas. Zero means "same as the frame", which is
     * what every clip whose aspect equals the canvas reports.</p>
     */
    private volatile int sourceW = 0, sourceH = 0;
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
    private int stageProgram, presentProgram, gradeProgram, cropProgram, layerProgram;
    /** @see #PICTURE_ALPHA_FRAGMENT - compiled with the rest, used only while fading. */
    private int pictureAlphaProgram;
    /**
     * The spine canvas-transform pass. Its source is
     * {@code SpineTransform.fragmentShader("vFxUv")} - the SAME string the exporter compiles,
     * with only the varying name substituted - so this pass and
     * {@code SpineTransformExportEffect} cannot disagree about the geometry.
     */
    private int spineTransformProgram;
    /** @see #STAGE_STILL_FRAGMENT — the bitmap-base variant of {@link #stageProgram}. */
    private int stageStillProgram;
    /** GL-thread scratch for {@link #drawSpineTransform}'s eight uniform floats. */
    private final float[] spineUniforms =
            new float[com.fadcam.ui.faditor.model.SpineTransform.UNIFORMS];
    /** The 2D texture holding {@link #baseStill}, and which bitmap it currently holds. */
    private int baseStillTexId;
    @Nullable private android.graphics.Bitmap baseStillUploaded;
    /** GL pilot: full-frame raster of layer_image_overlay, uploaded as a 2D texture */
    @Nullable private volatile android.graphics.Bitmap layerOverlayBitmap;
    private int layerOverlayTexId;
    @Nullable private android.graphics.Bitmap layerOverlayUploaded;
    /** Text/sprite below-blend raster — composited BEFORE the blending image (static/fallback path) */
    @Nullable private volatile android.graphics.Bitmap belowBlendBitmap;
    private int belowBlendTexId;
    @Nullable private android.graphics.Bitmap belowBlendUploaded;
    /** Pose-animated below-blend overlays — per-item textured quads (spec §3), fed via Pip path */
    @Nullable private volatile java.util.List<Pip> belowBlendOverlays;
    /** Captions — third client of the texture cache (SPEC_20260829_CAPTIONS_GL §3.1), composited before the PiP walk so a blend above samples them */
    @Nullable private volatile java.util.List<Pip> captionOverlays;
    @NonNull private final java.util.Map<String, Integer> overlayTexIds = new java.util.HashMap<>();
    @NonNull private final java.util.Map<String, android.graphics.Bitmap> overlayUploaded = new java.util.HashMap<>();
    @NonNull private final java.util.Set<String> overlayKeysInFrame = new java.util.HashSet<>();
    /** Pilot measurement: frame time */
    private long pilotFrameCount = 0;
    private long pilotTotalFrameNs = 0;
    private long pilotMaxFrameNs = 0;
    private long pilotLastFrameNs = 0;
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
    /** Matte peers: same still machinery but keyed by recipient clip id + matte clip id. */
    @NonNull private final java.util.Map<String, Integer> matteTexIds = new java.util.HashMap<>();
    @NonNull private final java.util.Map<String, android.graphics.Bitmap> matteUploaded = new java.util.HashMap<>();
    @NonNull private final java.util.Set<String> matteKeysInFrame = new java.util.HashSet<>();
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
    /**
     * SPEC E shared mesh stamp (ONE frame-sized FBO reused across bent items — the §7 guard that
     * keeps VRAM at 8.29 MB total, not per object). One instance lives on THIS GL thread; the
     * export effect thread owns its own instances. Released with the context.
     */
    @NonNull private final MeshStampGl meshStamp = new MeshStampGl();

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
        /**
         * How many mask shapes these programs' uniform arrays actually hold — the layer's own
         * count, or 1 when the many-shape source blew the device's uniform budget and the
         * single-shape retry is what compiled. {@link #drawOneLayer} uploads this many.
         */
        int shapes = 1;
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
     * The composite FRAME and the decoded PICTURE inside it.
     *
     * <p><b>THE FRAME IS THE CANVAS, and that is the whole fix.</b> The chain used to run at the
     * decoded video's size and {@link #drawPresent} letterboxed the result onto the canvas-sized
     * surface. But every overlay this composite draws — image overlays, captions, PiPs, mask
     * geometry — arrives normalised against the CANVAS ({@code computeCanvasRect}). On a clip
     * whose aspect differs from the canvas those canvas fractions were being read as fractions
     * of the smaller letterboxed sub-rect, which rescaled the object on ONE axis and pulled it
     * toward the frame centre: add a mask to an image (which moves it from its {@code ImageView}
     * into this composite) and it changed size, with nothing about the image having changed.
     * JoyRaptor, 2026-09-01: "when I added the mask, it changed the apparent zoom".</p>
     *
     * <p>Making the frame the canvas removes the second definition of "the frame" rather than
     * compensating for it at each of the four call sites. The picture itself does not move: a
     * fit-centre into a canvas-aspect frame followed by a 1:1 present is exactly the fit-centre
     * into the surface that {@code drawPresent} was doing alone. When the clip's aspect already
     * equals the canvas the controller passes {@code frame == source} and every viewport, FBO
     * and uniform here is what it was.</p>
     */
    public void setCompositeFrame(int frameW, int frameH, int srcW, int srcH) {
        if (srcW > 0 && srcH > 0 && (srcW != sourceW || srcH != sourceH)) {
            sourceW = srcW;
            sourceH = srcH;
            requestFrame();
        }
        setVideoSize(frameW, frameH);
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
     * The crop the chain must apply to the base frame, or null for none.
     *
     * <p>Pushed EVERY tick by {@code FxLivePreviewController} from
     * {@code Clip.effectiveCropFractions()} — the same per-clip decision
     * {@code ExportManager} builds its {@code Crop} effect from, so scrubbing across clips
     * with different crops re-crops this chain exactly as the export cuts between them.
     * Fractions are copied on arrival: the caller's array is resolved once per sync and must
     * not alias GL-thread state.</p>
     */
    public void setClipCrop(@Nullable float[] ltrb) {
        boolean same = (ltrb == null && clipCrop == null)
                || (ltrb != null && clipCrop != null
                    && java.util.Arrays.equals(ltrb, clipCrop));
        if (same) return;
        clipCrop = ltrb == null ? null : java.util.Arrays.copyOf(ltrb, ltrb.length);
        requestFrame();
    }

    /**
     * The BASE-PICTURE ALPHA for the clip under the playhead: its opacity keyframe envelope
     * multiplied by its master fade-knob factor - the same product
     * {@code OpacityExportShaderProgram} writes, and the same one the Canvas path puts on
     * {@code playerView}/{@code imagePreview}.
     *
     * <p>APPLIED TO THE BASE PICTURE ONLY, by {@link #drawPictureAlpha}, which runs after the
     * clip's own crop, colour grade and spine FxStack and BEFORE everything that composites on
     * top of it - the below-blend raster, the PiPs, the adjustment layers, the image overlays
     * and the captions. Those are drawn over the already-darkened picture at their own full
     * brightness. That is the owner's ruling ("the opacity should fade the CLIP, not everything
     * above it") and it is the export's own slot for the same multiply: step 6 of
     * {@code ExportManager.assembleClipVideoEffects}' canonical order, after the grade and
     * before the PiP / adjustment / OverlayEffect block.</p>
     *
     * <p>FADE TO BLACK, NOT TO TRANSPARENT - see {@link #PICTURE_ALPHA_FRAGMENT}.</p>
     *
     * <p>NO-OP AT 1: {@code drawFrame} skips the pass entirely unless this is below 1, so an
     * unfaded clip runs the identical pass list, ping-pong slots and all, that it ran before
     * this existed - byte-identical output for one float compare per frame.</p>
     */
    public void setPictureAlpha(float alpha) {
        float a = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
        if (a == pictureAlpha) return;
        pictureAlpha = a;
        requestFrame();
    }

    /**
     * The playhead clip's spine canvas transform, already resolved to a pose by
     * {@code Clip.spinePoseAt} - or null / an identity pose for the plain fit-centre.
     *
     * <p>Pass the array by value; it is published to the GL thread and must not be mutated
     * afterwards. An identity pose is stored as null so the draw loop's skip is one null test.</p>
     */
    public void setSpinePose(@Nullable float[] pose) {
        float[] next = pose != null
                && !com.fadcam.ui.faditor.model.SpineTransform.isIdentity(pose)
                ? pose.clone() : null;
        float[] cur = spinePose;
        if (next == null && cur == null) return;
        if (next != null && cur != null && java.util.Arrays.equals(next, cur)) return;
        spinePose = next;
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
     * GL pilot: full-frame raster of layer_image_overlay. Null means nothing to composite
     * (no visible images at this playhead), so the pass is skipped. Bitmap handed here
     * must be retired via stillTrash(), never recycled directly — see baseStill.
     */
    public void setLayerOverlayBitmap(@Nullable android.graphics.Bitmap b) {
        if (b == layerOverlayBitmap) return;
        android.graphics.Bitmap old = layerOverlayBitmap;
        if (old != null && old != b && !old.isRecycled()) {
            stillTrash.offer(old);
        }
        layerOverlayBitmap = b;
        requestFrame();
    }

    /**
     * Text/sprite layers below a blending image, rasterised as a full-frame bitmap at
     * video resolution. Composited BEFORE the blending image so the blend
     * has something to composite against — without it the blend sampled video. Null =
     * nothing below to promote. Pose-animated content below a blend rides the
     * texture-quad path (see setBelowBlendOverlays) — one predicate, one place.
     */
    public void setBelowBlendBitmap(@Nullable android.graphics.Bitmap b) {
        if (b == belowBlendBitmap) return;
        android.graphics.Bitmap old = belowBlendBitmap;
        if (old != null && old != b && !old.isRecycled()) {
            stillTrash.offer(old);
        }
        belowBlendBitmap = b;
        requestFrame();
    }

    /**
     * Pose-animated text/sprite below a blending image — per-item textured quads (spec §3).
     * Each Pip carries its own bitmap at authored size (OverlayTextureCache, 1.5×) and is drawn
     * via the existing Pip path (cx,cy,halfW,halfH,rotationDeg,alpha) — no second transform pipeline.
     * Null or empty clears the quads.
     */
    public void setBelowBlendOverlays(@Nullable java.util.List<Pip> pips) {
        belowBlendOverlays = pips == null || pips.isEmpty() ? null : new java.util.ArrayList<>(pips);
        requestFrame();
    }

    /** Captions — raster per cue at authored size, quad per frame (SPEC_20260829_CAPTIONS_GL §3.1). */
    public void setCaptionOverlays(@Nullable java.util.List<Pip> pips) {
        captionOverlays = pips == null || pips.isEmpty() ? null : new java.util.ArrayList<>(pips);
        requestFrame();
    }

    /** Pilot measurement: reset frame-time stats */
    public void resetPilotStats() {
        pilotFrameCount = 0;
        pilotTotalFrameNs = 0;
        pilotMaxFrameNs = 0;
        pilotLastFrameNs = 0;
    }
    public long getPilotFrameCount() { return pilotFrameCount; }
    public long getPilotAvgFrameNs() { return pilotFrameCount == 0 ? 0 : pilotTotalFrameNs / pilotFrameCount; }
    public long getPilotMaxFrameNs() { return pilotMaxFrameNs; }
    public long getPilotLastFrameNs() { return pilotLastFrameNs; }
    public int getPilotLayerBitmapBytes() {
        android.graphics.Bitmap b = layerOverlayBitmap;
        return b == null || b.isRecycled() ? 0 : b.getByteCount();
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
            cropProgram = buildProgram(FxGlSource.VERTEX_SHADER, CROP_FRAGMENT);
            pictureAlphaProgram =
                    buildProgram(FxGlSource.VERTEX_SHADER, PICTURE_ALPHA_FRAGMENT);
            spineTransformProgram = buildProgram(FxGlSource.VERTEX_SHADER,
                    com.fadcam.ui.faditor.model.SpineTransform.fragmentShader("vFxUv"));
            layerProgram = buildProgram(FxGlSource.VERTEX_SHADER, LAYER_FRAGMENT);
            // The PiP programs are compiled lazily by pipProgramFor, because their source
            // depends on each object's effect stack. Any ids cached from a previous surface
            // belong to a destroyed context — clearing the maps is not optional.
            pipPrograms.clear();
            shapesCompiled.clear();
            stillTexIds.clear();
            stillUploaded.clear();
            // SPEC E mesh stamp belongs to the dead context too — drop its ids (deleting unknown
            // names is a defined no-op) so the next meshed frame rebuilds them lazily. No bend
            // still costs nothing: renderToStamp returns before creating anything when idle.
            try { meshStamp.release(); } catch (Exception ignored) { }
            // Same for the PiP inputs: whatever sits in these slots names objects in a context
            // that no longer exists, and ensurePipInputs decides "already built" from the count.
            java.util.Arrays.fill(pipTextures, null);
            java.util.Arrays.fill(pipSurfaces, null);
            java.util.Arrays.fill(pipHasFrame, false);
            // Same reasoning for the bitmap BASE: its texture id belonged to the dead context.
            baseStillTexId = 0;
            baseStillUploaded = null;
            layerOverlayTexId = 0;
            layerOverlayUploaded = null;
            belowBlendTexId = 0;
            belowBlendUploaded = null;
            overlayTexIds.clear();
            overlayUploaded.clear();
            overlayKeysInFrame.clear();
            resetPilotStats();

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
        long frameStartNs = System.nanoTime();
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

            // 1b — the CLIP CROP, BEFORE the grade — the export chain's order
            //     (rotate → crop → grade). Fit-centred with transparent bars, matching what
            //     media3's Crop plus the trailing SCALE_TO_FIT Presentation actually write:
            //     see CROP_FRAGMENT. A failed pass leaves the frame uncropped rather than
            //     half-cropped; the setter's null restores full frame on the next sync tick.
            float[] cropRect = clipCrop;
            if (!degraded && cropRect != null && cropRect.length == 4) {
                cur = drawCrop(cropRect, cur, vw, vh);
            }

            // 2 — the CLIP grade, before any composited item, exactly as the export chain
            //     orders them (ExportManager:2560 against the PiP block at :2714).
            Grade g = grade;
            if (!degraded && g != null && g.rendersAnything()) {
                drawGrade(g, cur, 1, vw, vh);
                cur = 1;
            }

            // 2a — the MASTER (spine) clip's OWN FxStack, over the whole frame, after the crop
            //      and the legacy grade and before anything is composited on top. That is the
            //      export's position for it (ExportManager:3150, after the canvas Presentation,
            //      before the below-blend pass / PiP loop / adjustment inserts / caption
            //      overlay), and the reason it is not simply rung 0 of the walk below.
            //
            //      COSTS NOTHING WHEN THERE IS NOTHING: spineLayerIndex is -1 unless
            //      FxLivePreviewController found an active stack on the playhead clip, and
            //      -1 skips the whole block — no pass, no ping-pong flip, no FBO.
            CompositePlan cp = plan;
            boolean layersReady = false;
            if (!degraded && cp != null) {
                layersReady = cp.layers.isEmpty() || ensurePrograms(cp.layers);
                if (layersReady && cp.spineLayerIndex >= 0
                        && cp.spineLayerIndex < cp.layers.size()
                        && cp.spineLayerIndex < compiled.size()) {
                    cur = drawOneLayer(cp.layers.get(cp.spineLayerIndex),
                            compiled.get(cp.spineLayerIndex), vw, vh, cur);
                }
            }

            // 2b0 — THE SPINE CANVAS TRANSFORM: where this clip's picture SITS on the canvas.
            //       After the crop, the grade and the spine FxStack (so an effect belongs to the
            //       clip and travels with it); before the opacity multiply and before ANYTHING
            //       that composites on top — the below-blend raster, the PiPs, the adjustment
            //       layers, the image overlays, the captions. Those keep their own canvas
            //       coordinates and deliberately do NOT move with the clip.
            //
            //       That is the export's own slot for SpineTransformExportEffect (after the
            //       canvas Presentation and the FxStack, before OpacityExportEffect), and the
            //       pass itself compiles the same shader and uploads the same uniforms from the
            //       same SpineTransform methods the exporter calls.
            //
            //       COSTS NOTHING WHEN THERE IS NOTHING: setSpinePose stores null for an
            //       identity pose, and null skips the whole block — no pass, no ping-pong flip,
            //       no clear.
            float[] sp = spinePose;
            if (!degraded && sp != null) cur = drawSpineTransform(sp, cur, vw, vh);

            // 2c — THE CLIP FADE / OPACITY, on the base picture and nothing else. After the
            //      crop, the grade and the spine FxStack; before the below-blend raster, the
            //      PiPs, the adjustment layers, the image overlays and the captions — the
            //      export's own slot for OpacityExportEffect. Skipped whole at alpha 1.
            float pa = pictureAlpha;
            if (!degraded && pa < 1f) cur = drawPictureAlpha(cur, vw, vh);

            // 2b — text/sprite below a blending image — two paths (one predicate, one place):
            // 2b1: full-frame raster for static/fallback content, composited BEFORE the blend
            // 2b2: per-item textured quads for pose-animated content (spec §3) — raster once at
            //      authored size (1.5×), quad per frame via Pip path (no second pipeline)
            android.graphics.Bitmap belowBmp = belowBlendBitmap;
            if (!degraded && belowBmp != null && !belowBmp.isRecycled() && layerProgram != 0) {
                int dst = cur == 0 ? 1 : 0;
                if (drawBelowBlend(belowBmp, cur, dst, vw, vh)) cur = dst;
            }
            overlayKeysInFrame.clear();
            java.util.List<Pip> overlays = belowBlendOverlays;
            if (!degraded && overlays != null && !overlays.isEmpty() && layerProgram != 0) {
                for (Pip p : overlays) {
                    cur = drawOverlayPip(p, cur, vw, vh);
                }
            }

            // 3 — the composited items, bottom→top, in the EXPORT's chain order: each PiP
            //     drawn over the frame so far, each adjustment layer grading what is beneath
            //     it. One ordering (LayerPreviewController.orderedCompositedItems) feeds both
            //     renderers, so a layer between two PiPs grades only the lower one here,
            //     exactly as in the file — and EVERY visible PiP is composited, the live one
            //     from its decoder surface and the rest from their cached stills, rather than
            //     the sibling-View stills that used to paint UNGRADED over this chain.
            stillKeysInFrame.clear();
            matteKeysInFrame.clear();
            // `cp` and `layersReady` were resolved at 2a above — ONE ensurePrograms call per
            // frame, over the one layer list that now also carries the spine stack.
            if (!degraded && cp != null) {
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
            // Captions LAST, over every image, PiP and adjustment layer — where the export draws
            // them (its final pass, after the text boxes). Drawn before the rungs they sat
            // BEHIND every image overlay in the preview while the file showed them in front
            // (JoyRaptor, 2026-09-24: "Preview, icon, and export need to ALWAYS AGREE").
            java.util.List<Pip> caps = captionOverlays;
            if (!degraded && caps != null && !caps.isEmpty() && layerProgram != 0) {
                for (Pip p : caps) {
                    cur = drawOverlayPip(p, cur, vw, vh);
                }
            }
            evictUnusedOverlays();
            evictUnusedStills();
            evictUnusedMattes();

            // 3b — GL pilot: layer_image_overlay as a full-frame texture at its real z.
            // This composite sits AFTER the PiP/adjustment walk (which is bottom→top in
            // orderedVisualItems order) — layer_image_overlay is above PiPs and waveform per
            // the XML stack, so compositing here puts it where the Canvas would have painted
            // over the GL surface unconditionally. With this, the promote rules for this
            // layer can eventually be deleted; for the pilot they stay.
            android.graphics.Bitmap layerBmp = layerOverlayBitmap;
            if (!degraded && layerBmp != null && !layerBmp.isRecycled() && layerProgram != 0) {
                int dst = cur == 0 ? 1 : 0;
                if (drawLayerOverlay(layerBmp, cur, dst, vw, vh)) cur = dst;
            }

            // 4 — present the finished frame, fit-centred, at view resolution.
            drawPresent(targets[cur][0]);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            // Pilot measurement: frame time
            long frameNs = System.nanoTime() - frameStartNs;
            pilotFrameCount++;
            pilotTotalFrameNs += frameNs;
            if (frameNs > pilotMaxFrameNs) pilotMaxFrameNs = frameNs;
            pilotLastFrameNs = frameNs;
            if ((pilotFrameCount % 60) == 0) {
                FLog.d("GLPilot", "frame " + pilotFrameCount + " last=" + (frameNs/1_000_000) + "ms avg=" + ((pilotTotalFrameNs/pilotFrameCount)/1_000_000) + "ms max=" + (pilotMaxFrameNs/1_000_000) + "ms layerBytes=" + getPilotLayerBitmapBytes());
            }
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
    /**
     * Point the stage at the sub-rectangle of the frame the decoded picture actually occupies,
     * clearing the letterbox to TRANSPARENT first.
     *
     * <p>Transparent, not black: the view is non-opaque and the canvas backdrop must show
     * through the bars, exactly as {@link #drawCrop}'s bars do and exactly as the exported
     * file's letterbox sits on the player's surface. With {@code sourceW/H} unset — or equal to
     * the frame, which is every clip at the canvas's own aspect — this is
     * {@code glViewport(0, 0, vw, vh)} with no clear, i.e. what the stage always did.</p>
     */
    private void stageViewport(int vw, int vh) {
        int sw = sourceW > 0 ? sourceW : vw;
        int sh = sourceH > 0 ? sourceH : vh;
        float s = Math.min((float) vw / sw, (float) vh / sh);
        int w = Math.max(1, Math.round(sw * s));
        int h = Math.max(1, Math.round(sh * s));
        if (w < vw || h < vh) {
            GLES20.glViewport(0, 0, vw, vh);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        GLES20.glViewport((vw - w) / 2, (vh - h) / 2, w, h);
    }

    /** The staged picture's rect inside the frame, normalised and bottom-up: {x0, y0, w, h}. */
    @NonNull
    private float[] pictureRect(int vw, int vh) {
        int sw = sourceW > 0 ? sourceW : vw;
        int sh = sourceH > 0 ? sourceH : vh;
        float s = Math.min((float) vw / sw, (float) vh / sh);
        float w = Math.min(1f, sw * s / vw);
        float h = Math.min(1f, sh * s / vh);
        return new float[]{(1f - w) / 2f, (1f - h) / 2f, w, h};
    }

    private void drawStage(int vw, int vh) {
        android.graphics.Bitmap b = baseStill;
        if (b != null && !b.isRecycled() && drawStageStill(b, vw, vh)) return;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[0][1]);
        stageViewport(vw, vh);
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
        stageViewport(vw, vh);
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

    /**
     * The clip-crop pass: {@code src} → the other slot, fit-centring the crop region and
     * clearing the rest to transparent. Returns the slot holding the result — or {@code src}
     * unchanged when the rect is degenerate, so a bad crop can never blank the preview.
     */
    private int drawCrop(@NonNull float[] c, int src, int vw, int vh) {
        float cw = c[2] - c[0];
        float ch = c[3] - c[1];
        if (cw <= 0.001f || ch <= 0.001f || cw > 1f || ch > 1f) return src;
        // Fit-centre the crop region into this target: media3's Crop emits frames AT THE
        // CROP'S OWN ASPECT (configure scales the Size by the crop fractions), and the
        // export's trailing SCALE_TO_FIT Presentation letterboxes that onto the canvas.
        // AGAINST THE PICTURE, not the frame. The crop fractions describe the DECODED picture,
        // which since the frame-space fix occupies only a sub-rectangle of the (canvas-shaped)
        // frame — see stageViewport. Reading them as frame fractions would crop the letterbox
        // bars along with the footage. With source == frame these two lines are the old ones.
        int sw = sourceW > 0 ? sourceW : vw;
        int sh = sourceH > 0 ? sourceH : vh;
        float[] pr = pictureRect(vw, vh);
        float contentAspect = (cw * sw) / (ch * sh);
        float frameAspect = (float) vw / (float) vh;
        float dw, dh;
        if (contentAspect >= frameAspect) {
            dw = 1f;
            dh = frameAspect / contentAspect;
        } else {
            dh = 1f;
            dw = contentAspect / frameAspect;
        }
        int dst = src == 0 ? 1 : 0;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
        // The bars must be TRANSPARENT, not stale pixels from whatever last used this FBO —
        // the view is non-opaque so the canvas backdrop shows through them, exactly as the
        // exported file's letterbox sits on the player's surface.
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(cropProgram);
        bindQuad(cropProgram);
        setSampler(cropProgram, "uTexSampler", targets[src][0], 0, true);
        // uv is bottom-up; the fractions are top-down, so the source window flips y.
        setFn(cropProgram, "uCropSrc",
                new float[]{pr[0] + c[0] * pr[2], pr[1] + (1f - c[3]) * pr[3],
                        cw * pr[2], ch * pr[3]}, 4);
        setFn(cropProgram, "uCropDst",
                new float[]{(1f - dw) / 2f, (1f - dh) / 2f, dw, dh}, 4);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return dst;
    }

    /** The clip colour grade: {@code src} → {@code dst}, one pass, media3's own math. */
    private void drawGrade(@NonNull Grade g, int src, int dst, int vw, int vh) {        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
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
     * THE CLIP FADE pass: {@code src} -> the other slot, RGB scaled by {@link #pictureAlpha}.
     * Returns the slot holding the result, or {@code src} untouched when there is nothing to do.
     *
     * <p>POSITION IS THE POINT. It runs after the clip's own crop, colour grade and spine
     * FxStack and BEFORE anything that composites on top - the below-blend raster, the PiPs,
     * the adjustment layers, the image overlays, the captions. That is exactly where the export
     * puts {@code OpacityExportEffect} (ExportManager.assembleClipVideoEffects, canonical order
     * step 6: after the grade, before the PiP/adjustment/OverlayEffect block), so a faded clip
     * with a caption on it renders here what it renders in the file: the picture scaled, the
     * caption over it at full strength. Staging the multiply earlier would have put it before
     * the grade, where a brightness lift would have partly undone it.</p>
     *
     * <p>NO-OP AT 1: the caller skips this entirely when the alpha is 1, so an unfaded clip
     * runs the identical pass list it ran before this existed - no extra draw, no extra FBO,
     * no ping-pong flip, and therefore byte-identical output. Its cost is one float compare.</p>
     */
    /**
     * THE SPINE CANVAS TRANSFORM pass: {@code src} -> the other slot, with the finished clip
     * picture MOVED, SCALED and ROTATED on the canvas. Returns the slot holding the result, or
     * {@code src} untouched when there is nothing to do or the pose cannot be drawn.
     *
     * <p>POSITION IS THE POINT, exactly as it is for {@link #drawPictureAlpha}. It runs after
     * the clip's own crop, colour grade and spine FxStack -- so an effect belongs to the clip and
     * travels with it -- and BEFORE the below-blend raster, the PiPs, the adjustment layers, the
     * image overlays and the captions, which draw over the canvas at their own coordinates and
     * therefore do NOT move with the clip. That is the export's own slot for
     * {@code SpineTransformExportEffect}: after the canvas Presentation and the FxStack, before
     * {@code OpacityExportEffect} and the composite block.</p>
     *
     * <p>NO MATHS LIVES HERE. The uniforms come from {@code SpineTransform.uniforms} and the
     * program was compiled from {@code SpineTransform.fragmentShader}; the exporter calls the
     * same two. If this pass and the file ever disagree it is because that one class is wrong for
     * both, which is the only kind of preview/export divergence this design can produce.</p>
     *
     * <p>A pose that cannot be drawn -- collapsed, non-finite, singular -- returns {@code src}
     * rather than a blank slot, so a bad keyframe stops the picture moving instead of erasing it.
     * Same rule {@link #drawCrop} follows for a degenerate rect.</p>
     */
    private int drawSpineTransform(@NonNull float[] pose, int src, int vw, int vh) {
        if (spineTransformProgram == 0) return src;
        // Reused, not allocated: this is a per-frame path and eight floats a frame is eight
        // floats a frame more garbage than the rest of this loop makes.
        float[] u = spineUniforms;
        if (!com.fadcam.ui.faditor.model.SpineTransform.uniforms(pose, vw, vh, u)) return src;
        int dst = src == 0 ? 1 : 0;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
        // Everything the transform vacates must be TRANSPARENT, not whatever last used this FBO:
        // the view is non-opaque and the editor's backdrop shows through, exactly as it does
        // through stageViewport's and drawCrop's letterbox bars -- and exactly as the exported
        // file's un-composited region reaches the encoder as black.
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(spineTransformProgram);
        bindQuad(spineTransformProgram);
        setSampler(spineTransformProgram, "uTexSampler", targets[src][0], 0, true);
        setF4(spineTransformProgram, "uSpineInvA", u[0], u[1], u[2], u[3]);
        setF2(spineTransformProgram, "uSpineInvB", u[4], u[5]);
        setF2(spineTransformProgram, "uSpineEdge", u[6], u[7]);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return dst;
    }

    private int drawPictureAlpha(int src, int vw, int vh) {
        if (pictureAlphaProgram == 0) return src;
        int dst = src == 0 ? 1 : 0;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(pictureAlphaProgram);
        bindQuad(pictureAlphaProgram);
        setSampler(pictureAlphaProgram, "uTexSampler", targets[src][0], 0, true);
        setF(pictureAlphaProgram, "uPictureAlpha", pictureAlpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return dst;
    }

    /**
     * GL pilot: composite a full-frame Canvas layer (layer_image_overlay) over the frame.
     * Uploads the bitmap on demand (top-row-first v-flip like stills) and alpha-overs it.
     * Returns false if upload failed and nothing was drawn.
     */
    private boolean drawLayerOverlay(@NonNull android.graphics.Bitmap b, int src, int dst, int vw, int vh) {
        if (layerOverlayTexId == 0) layerOverlayTexId = newStillTexture();
        if (layerOverlayUploaded != b) {
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerOverlayTexId);
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                layerOverlayUploaded = b;
            } catch (RuntimeException e) {
                FLog.w(TAG, "layer overlay upload failed", e);
                return false;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(layerProgram);
        bindQuad(layerProgram);
        setSampler(layerProgram, "uBaseSampler", targets[src][0], 0, true);
        setSampler(layerProgram, "uLayerSampler", layerOverlayTexId, 1, true);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return true;
    }

    private boolean drawBelowBlend(@NonNull android.graphics.Bitmap b, int src, int dst, int vw, int vh) {
        if (belowBlendTexId == 0) belowBlendTexId = newStillTexture();
        if (belowBlendUploaded != b) {
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, belowBlendTexId);
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                belowBlendUploaded = b;
            } catch (RuntimeException e) {
                FLog.w(TAG, "below-blend overlay upload failed", e);
                return false;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[dst][1]);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(layerProgram);
        bindQuad(layerProgram);
        setSampler(layerProgram, "uBaseSampler", targets[src][0], 0, true);
        setSampler(layerProgram, "uLayerSampler", belowBlendTexId, 1, true);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return true;
    }

    /** Pose-animated below-blend overlay: per-item textured quad via Pip path (spec §3). */
    private int drawOverlayPip(@NonNull Pip p, int src, int vw, int vh) {
        if (!p.rendersAnything()) return src;
        int dst = src == 0 ? 1 : 0;
        int tex = overlayTextureFor(p);
        if (tex == 0) return src;
        return drawPip(p, src, dst, vw, vh, tex) ? dst : src;
    }

    private int overlayTextureFor(@NonNull Pip p) {
        overlayKeysInFrame.add(p.clipId);
        android.graphics.Bitmap b = p.still;
        Integer have = overlayTexIds.get(p.clipId);
        if (b == null || b.isRecycled()) return have == null ? 0 : have;
        if (have != null && overlayUploaded.get(p.clipId) == b) return have;
        int id = have == null ? newStillTexture() : have;
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            FLog.d("OverlayTex", "overlay upload OK " + p.clipId + " tex=" + id + " " + b.getWidth() + "x" + b.getHeight());
        } catch (RuntimeException e) {
            FLog.w(TAG, "overlay upload failed for " + p.clipId, e);
            return have == null ? 0 : have;
        }
        overlayTexIds.put(p.clipId, id);
        overlayUploaded.put(p.clipId, b);
        return id;
    }

    private void evictUnusedOverlays() {
        java.util.Iterator<java.util.Map.Entry<String, Integer>> it = overlayTexIds.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Integer> e = it.next();
            if (overlayKeysInFrame.contains(e.getKey())) continue;
            try { GLES20.glDeleteTextures(1, new int[]{e.getValue()}, 0); } catch (Exception ignored) {}
            it.remove();
            overlayUploaded.remove(e.getKey());
        }
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
        // The SHAPE COUNT is part of the key: it sizes the mask uniform arrays, so it is part
        // of the shader text. One mask compiles the same program it always did.
        // The PIN is part of the key too, for the same reason: it rewrites the shader text.
        // An unpinned object's key is unchanged from "-", so its program is the one it has
        // always compiled.
        // SPEC E mesh composite ("m" first char) picks the stamp-sampling variant (FBO, no flip);
        // an unmeshed object's key is unchanged so its program is byte-for-byte what shipped.
        String key = (p.meshComposite ? "m" : (p.still == null ? "o" : "s")) + (p.extras ? "x" : "-")
                + (p.pinned() ? "p" : "-")
                + "m" + p.maskShapes + p.fxKey;
        Integer have = pipPrograms.get(key);
        if (have != null) return have;
        int prog;
        try {
            prog = buildProgram(FxGlSource.VERTEX_SHADER,
                    pipFragment(p.fused, p.still != null, p.extras, p.maskShapes,
                            p.pinned(), p.meshComposite));
            FLog.d("FxMultiPip", "pip program compiled key=" + key + " -> " + prog);
        } catch (Exception e) {
            // Fall back to the PLAIN composite, which is what the log claims happens. A 0 latch
            // drops this PiP from the chain rather than retrying a broken stack every frame —
            // the same "absent until fixed" behaviour a still that never decodes has.
            FLog.w(TAG, "PiP FX compile failed; compositing ungraded", e);
            int fallback = 0;
            try {
                fallback = buildProgram(FxGlSource.VERTEX_SHADER,
                        pipFragment(null, p.still != null, p.extras, p.maskShapes,
                                p.pinned(), p.meshComposite));
            } catch (Exception fatal) {
                FLog.e(TAG, "plain PiP composite failed too", fatal);
                // A many-shape mask is the one thing here that can outgrow a GPU's fragment
                // uniform budget, and losing the object entirely would be WORSE than the
                // shape-0-only preview this replaced. Retry at one shape; drawPip uploads
                // whatever count actually compiled, so the arrays and the shader agree.
                if (p.maskShapes > 1) {
                    try {
                        fallback = buildProgram(FxGlSource.VERTEX_SHADER,
                                pipFragment(null, p.still != null, p.extras, 1,
                                        p.pinned(), p.meshComposite));
                        if (fallback != 0) shapesCompiled.put(fallback, 1);
                    } catch (Exception ignored) {
                        FLog.e(TAG, "single-shape PiP composite failed too", ignored);
                    }
                }
                // LAST RESORT: drop the corner pin. A 0 latch removes the object from the
                // preview entirely, and an image drawn FLAT for a session is recoverable where a
                // vanished image is not — the same ranking CornerPin.buildMatrix already applies
                // when the solve refuses. Only reached if a driver rejects the pinned variant
                // outright, since every earlier attempt kept it.
                // SPEC E: a mesh composite never pins (stamp already pinned via H); still, if a
                // mesh program somehow refused, fall back to the flat still variant rather than
                // vanishing — same recoverable ranking (flat for a session beats gone).
                if (fallback == 0 && p.pinned()) {
                    try {
                        fallback = buildProgram(FxGlSource.VERTEX_SHADER,
                                pipFragment(null, p.still != null, p.extras, 1, false, false));
                        if (fallback != 0) shapesCompiled.put(fallback, 1);
                        FLog.e(TAG, "corner-pinned PiP composite refused; drawing unpinned");
                    } catch (Exception ignored) {
                        FLog.e(TAG, "unpinned PiP composite failed too", ignored);
                    }
                }
                if (fallback == 0 && p.meshComposite) {
                    try {
                        fallback = buildProgram(FxGlSource.VERTEX_SHADER,
                                pipFragment(null, p.still != null, p.extras, 1, false, false));
                        if (fallback != 0) shapesCompiled.put(fallback, 1);
                        FLog.e(TAG, "mesh-stamp composite refused; drawing flat still");
                    } catch (Exception ignored) {
                        FLog.e(TAG, "flat still composite failed too", ignored);
                    }
                }
            }
            prog = fallback;
        }
        pipPrograms.put(key, prog);
        return prog;
    }

    /**
     * How many mask shapes a compiled program's uniform arrays actually hold, when that is not
     * simply the {@link Pip}'s own count — see the single-shape retry in {@link #pipProgramFor}.
     */
    @NonNull private final java.util.Map<Integer, Integer> shapesCompiled =
            new java.util.HashMap<>();

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
    static String pipFragment(@Nullable FxCompiler.Pass fused, boolean still,
                                      boolean extras, int maskShapes, boolean pinned,
                                      boolean meshComposite) {
        // SPEC E: a stamp composite samples its frame-sized FBO without a flip; every other still
        // samples its bitmap upload with one. Textual derivation (see PIP_MESH_FRAGMENT) so the
        // blend/mask/key/FX below cannot drift between the two; an unmeshed object never takes
        // this branch so its source is character-for-character what shipped.
        String base = meshComposite ? PIP_MESH_FRAGMENT
                : (still ? PIP_STILL_FRAGMENT : PIP_FRAGMENT);
        if (fused == null && !extras) {
            return withMaskShapes(pinned ? withCornerPin(base) : base, maskShapes);
        }
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
                        //
                        // SPEC G: unmirrored first. A mirrored half-extent flips uv, so uv.x is the
                        // SOURCE edge, not the destination one the Canvas clip and the export keep.
                        // Reading it straight would wipe the mirror image of the wipe.
                        "    float rux = uPipHalf.x < 0.0 ? 1.0 - uv.x : uv.x;\n"
                        + "    if (rux > uPipReveal) src.a = 0.0;\n"
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
        String out = base
                .replace("void main() {\n", extrasDecls + decls + "void main() {\n")
                .replace("    vec4 src = texture2D(uPipTexture, s);\n",
                        "    vec4 src = texture2D(uPipTexture, s);\n" + extrasBody + apply);
        return withMaskShapes(pinned ? withCornerPin(out) : out, maskShapes);
    }

    /**
     * Size the mask uniform arrays and the fold loop to the object's ACTUAL shape count.
     *
     * <p>Clamped to {@link MaskSdf#MAX_SHAPES} — the packer drops shapes past it, so declaring
     * more slots than it can fill would upload garbage — and floored at 1, because a GLSL array
     * of length 0 does not exist and every object compiles the mask block whether it has a mask
     * or not (the {@code uPipMaskOn} branch is what turns it off).</p>
     */
    /**
     * Derive the CORNER-PINNED variant of an assembled composite shader.
     *
     * <p>Textual, exactly as {@link #PIP_STILL_FRAGMENT} is derived, and applied LAST so the
     * anchors it rewrites are the finished ones — in particular the wipe line, which only exists
     * once the extras have been spliced in. An unpinned object never calls this, so its source
     * string is character-for-character what it has always been.</p>
     *
     * <p><b>What the four rewrites do.</b> {@code q} is the fragment's position in the object's
     * own box, {@code -1..1}; the box has already been grown by the excursion (see
     * {@link Pip#pinPad}), so {@code pd} maps it back onto the item's UNPINNED unit rect — the
     * space {@code ImageOverlayDraw} draws in, and the space its reveal clip is expressed in.
     * {@code pq} is then the SOURCE texel: {@code uPinInv} carries the pinned quad back to the
     * straight rect. A {@code pq} outside {@code 0..1} is a fragment inside the padded box but
     * outside the pinned quad — the four regions a pin leaves empty — and returns with the frame
     * already written, which is the same "leave the base alone" the box test performs.</p>
     *
     * <p>The reveal's extra clauses are {@code ImageOverlayDraw}'s {@code clipRect}, which is
     * applied BEFORE the pin and therefore bounds the DESTINATION rect, not the source. They are
     * gated on {@code uPipReveal < 1.0} because with no wipe running there is nothing to clip and
     * the pinned excursions must survive.</p>
     */
    @NonNull
    private static String withCornerPin(@NonNull String fragment) {
        return fragment
                .replace("void main() {\n",
                        "uniform mat3 uPinInv;\n"
                        + "uniform vec4 uPipPad;\n"
                        + "void main() {\n")
                .replace("  vec2 q = r / uPipHalf;\n",
                        "  vec2 q = r / uPipHalf;\n"
                        + "  vec2 pd = (q * 0.5 + 0.5) * uPipPad.zw + uPipPad.xy;\n"
                        // SPEC G: the wipe reads the DESTINATION edge, unmirrored — pd is mirrored
                        // exactly when the half-extent is, and the pad remap is symmetric about
                        // 0.5, so 1.0 - pd.x is the unmirrored coordinate, no new uniform.
                        + "  float rpx = uPipHalf.x < 0.0 ? 1.0 - pd.x : pd.x;\n"
                        + "  vec3 ph = uPinInv * vec3(pd, 1.0);\n"
                        // Guarded at 1e-4, not 1e-6: this shader is mediump, whose smallest
                        // normal value is about 6e-5, so a 1e-6 guard would itself flush to zero
                        // and divide by it. A degenerate w only happens on the horizon line of a
                        // hard tilt, where the huge pq that results fails the 0..1 test anyway.
                        + "  float pz = ph.z;\n"
                        + "  if (abs(pz) < 0.0001) pz = 0.0001;\n"
                        + "  vec2 pq = ph.xy / pz;\n")
                .replace("    vec2 uv = q * 0.5 + 0.5;\n",
                        "    if (pq.x < 0.0 || pq.x > 1.0 || pq.y < 0.0 || pq.y > 1.0) return;\n"
                        + "    vec2 uv = pq;\n")
                .replace("    if (rux > uPipReveal) src.a = 0.0;\n",
                        "    if (uPipReveal < 1.0 && (rpx < 0.0 || rpx > uPipReveal\n"
                        + "        || pd.y < 0.0 || pd.y > 1.0)) src.a = 0.0;\n");
    }

    @NonNull
    private static String withMaskShapes(@NonNull String fragment, int maskShapes) {
        int n = Math.max(1, Math.min(MaskSdf.MAX_SHAPES, maskShapes));
        return fragment.replace("__MASKN__", Integer.toString(n));
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
        // Every non-texture uniform — geometry, mask (EVERY shape), pin, key, reveal, FX,
        // matte — through the ONE statement the export's GL image pass also uses (PipGl), so
        // the editor and the file cannot draw the same Pip differently.
        Integer compiled = shapesCompiled.get(program);
        PipGl.applyUniforms(program, p, vw, vh, compiled == null ? -1 : compiled);
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
        if (p.matteOn) {
            int matteTex = matteTextureFor(p);
            // Bind on unit 2; driver will ignore if location -1
            int matteLoc = GLES20.glGetUniformLocation(program, "uMatteSampler");
            if (matteLoc >= 0 && matteTex != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, matteTex);
                GLES20.glUniform1i(matteLoc, 2);
            }
        }
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
        // SPEC E mesh: bent images render via the shared stamp (frame-sized FBO) then composite
        // as identity; identity/false/degraded fall back to the flat path below (recoverable, never
        // vanishing — same ranking as the corner-pin fallback).
        if (p.mesh != null && p.mesh.spec != null) {
            return drawMeshRung(p, src, vw, vh);
        }
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
     * SPEC E mesh rung: stamp the bend into the shared frame-sized FBO, then composite as
     * identity (alpha/reveal already baked in the stamp, so 1,1 here avoids doubling; mask,
     * key, FX and blend ride the composite exactly as the flat path does, in frame space).
     * Identity/false/degraded stamp (0) falls back to the flat still path — a bent image drawn
     * flat for a frame is recoverable, a vanished image is not.
     */
    private int drawMeshRung(@NonNull Pip p, int src, int vw, int vh) {
        MeshInputs mi = p.mesh;
        android.graphics.Bitmap bmp = p.still;
        if (mi == null || mi.spec == null || bmp == null || bmp.isRecycled()) return src;
        meshStamp.configure(vw, vh);
        int stampTex = 0;
        try {
            stampTex = meshStamp.renderToStamp(bmp, mi.spec, mi.localMs,
                    mi.cx, mi.cy, mi.wNorm, mi.hNorm,
                    mi.pivOffX, mi.pivOffY, mi.applyPivot, mi.rotDeg,
                    mi.presetScaleX, mi.presetScaleY, mi.presetDx, mi.presetDy,
                    mi.alpha, mi.reveal, mi.cornerPin8,
                    mi.mirrorX, mi.mirrorY);
        } catch (Exception e) {
            FLog.w(TAG, "mesh stamp failed; drawing flat", e);
            stampTex = 0;
        }
        int dst = src == 0 ? 1 : 0;
        if (stampTex == 0) {
            int tex = stillTextureFor(p);
            if (tex == 0) return src;
            return drawPip(p, src, dst, vw, vh, tex) ? dst : src;
        }
        Pip comp = p.meshCompositePip();
        return drawPip(comp, src, dst, vw, vh, stampTex) ? dst : src;
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

    private int matteTextureFor(@NonNull Pip p) {
        if (!p.matteOn || p.matteClipId == null) return 0;
        String key = p.clipId + "#matte#" + p.matteClipId;
        matteKeysInFrame.add(key);
        android.graphics.Bitmap b = p.matteStill;
        Integer have = matteTexIds.get(key);
        if (b == null || b.isRecycled()) return have == null ? 0 : have;
        if (have != null && matteUploaded.get(key) == b) return have;
        int id = have == null ? newStillTexture() : have;
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            FLog.d("FxMatte", "matte upload OK " + key + " tex=" + id + " " + b.getWidth() + "x" + b.getHeight());
        } catch (RuntimeException e) {
            FLog.w(TAG, "matte upload failed for " + key, e);
            return have == null ? 0 : have;
        }
        matteTexIds.put(key, id);
        matteUploaded.put(key, b);
        return id;
    }

    private void evictUnusedMattes() {
        java.util.Iterator<java.util.Map.Entry<String, Integer>> it =
                matteTexIds.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Integer> e = it.next();
            if (matteKeysInFrame.contains(e.getKey())) continue;
            try { GLES20.glDeleteTextures(1, new int[]{e.getValue()}, 0); }
            catch (Exception ignored) { }
            it.remove();
            matteUploaded.remove(e.getKey());
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
            // EVERY packed shape, not shape 0. See Layer.maskShapes. ls.shapes is what actually
            // compiled — the single-shape retry can be below the layer's own count.
            int shapes = Math.min(layer.maskShapes, Math.max(1, ls.shapes));
            setF4v(step.program, "uMaskGeo", layer.maskGeo4, shapes);
            setF2v(step.program, "uMaskRot", layer.maskRot2, shapes);
            setF1v(step.program, "uMaskCorner", layer.maskCorner, shapes);
            setF1v(step.program, "uMaskFeather", layer.maskFeather, shapes);
            setF1v(step.program, "uMaskOp", layer.maskOpCodes, shapes);
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
        // The shape count SIZES THE SOURCE (FxGlSource.fragment), so it belongs in the key:
        // without it, adding a second mask shape would keep the one-slot program and the extra
        // shape would never be uploaded.
        for (Layer l : live) key.append(l.sourceKey).append('m').append(l.maskShapes).append('#');
        String want = key.toString();
        if (want.equals(compiledKey) && !compiled.isEmpty()) return true;
        // A stack that already failed to compile is not retried every frame — but a DIFFERENT
        // stack is. Latching on the key rather than on a boolean is what lets the user delete
        // the offending card and get their preview back, instead of having to reopen the editor.
        if (want.equals(failedKey)) return false;

        try {
            try {
                buildLayerPrograms(live, /* singleShape= */ false);
            } catch (RuntimeException first) {
                // A many-shape mask is the one thing here that can outgrow a GPU's fragment
                // uniform budget, and previewing UNGRADED would be worse than the shape-0-only
                // preview this replaced. Retry at one shape before giving up; drawOneLayer
                // uploads whatever count actually compiled, so arrays and shader agree. A stack
                // with no multi-shape mask cannot be over budget FOR THIS REASON, so it rethrows
                // rather than compiling the same source twice.
                boolean multi = false;
                for (Layer l : live) if (l.maskShapes > 1) multi = true;
                if (!multi) throw first;
                FLog.w(TAG, "FX shader compile failed at the full mask shape count;"
                        + " previewing the first shape only", first);
                buildLayerPrograms(live, /* singleShape= */ true);
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

    /**
     * Compile one program per RENDER for every layer in {@code live}, replacing whatever is
     * compiled now. {@code singleShape} clamps every layer's mask arrays to one slot — the
     * uniform-budget retry {@link #ensurePrograms} falls back to.
     *
     * <p>Throws on the first failure, having released what it had already built: a half-compiled
     * set would draw some layers and silently drop others.</p>
     */
    private void buildLayerPrograms(@NonNull List<Layer> live, boolean singleShape) {
        releasePrograms();
        try {
            for (Layer l : live) {
                LayerSteps ls = new LayerSteps();
                ls.shapes = singleShape ? 1 : l.maskShapes;
                for (int i = 0; i < l.plan.passes.size(); i++) {
                    FxCompiler.Pass pa = l.plan.passes.get(i);
                    boolean lastPass = i == l.plan.passes.size() - 1;
                    int renders = Math.max(1, pa.repeats);
                    for (int r = 0; r < renders; r++) {
                        boolean lastRender = lastPass && r == renders - 1;
                        int prog = buildProgram(FxGlSource.VERTEX_SHADER,
                                FxGlSource.fragment(pa, FxGlSource.KERNEL_HALF, lastRender,
                                        ls.shapes));
                        float dx = (renders > 1 && r == 1) ? 0f : 1f;
                        float dy = (renders > 1 && r == 1) ? 1f : 0f;
                        ls.steps.add(new Step(prog, i, dx, dy, lastRender));
                    }
                }
                compiled.add(ls);
            }
        } catch (RuntimeException e) {
            releasePrograms();
            throw e;
        }
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

    private void setF4(int program, @NonNull String name, float a, float b, float c, float d) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform4f(loc, a, b, c, d);
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

    /** Array uniform setters — {@link #setFn} only ever handled a single element. */
    private void setF1v(int program, @NonNull String name, @NonNull float[] v, int count) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform1fv(loc, Math.max(1, count), v, 0);
    }

    private void setF2v(int program, @NonNull String name, @NonNull float[] v, int count) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform2fv(loc, Math.max(1, count), v, 0);
    }

    private void setF4v(int program, @NonNull String name, @NonNull float[] v, int count) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform4fv(loc, Math.max(1, count), v, 0);
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
                shapesCompiled.clear();
                for (Integer id : stillTexIds.values()) {
                    try { GLES20.glDeleteTextures(1, new int[]{id}, 0); }
                    catch (Exception ignored) { }
                }
                stillTexIds.clear();
                stillUploaded.clear();
                for (Integer id : matteTexIds.values()) {
                    try { GLES20.glDeleteTextures(1, new int[]{id}, 0); }
                    catch (Exception ignored) { }
                }
                matteTexIds.clear();
                matteUploaded.clear();
                matteKeysInFrame.clear();
                if (baseStillTexId != 0) {
                    try { GLES20.glDeleteTextures(1, new int[]{baseStillTexId}, 0); }
                    catch (Exception ignored) { }
                    baseStillTexId = 0;
                }
                baseStillUploaded = null;
                if (belowBlendTexId != 0) {
                    try { GLES20.glDeleteTextures(1, new int[]{belowBlendTexId}, 0); }
                    catch (Exception ignored) { }
                    belowBlendTexId = 0;
                }
                belowBlendUploaded = null;
                for (java.util.Map.Entry<String, Integer> e : overlayTexIds.entrySet()) {
                    try { GLES20.glDeleteTextures(1, new int[]{e.getValue()}, 0); } catch (Exception ignored) {}
                }
                overlayTexIds.clear();
                overlayUploaded.clear();
                overlayKeysInFrame.clear();
                // SPEC E mesh stamp owns GL objects in this context — free them while current.
                try { meshStamp.release(); } catch (Exception ignored) { }
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
