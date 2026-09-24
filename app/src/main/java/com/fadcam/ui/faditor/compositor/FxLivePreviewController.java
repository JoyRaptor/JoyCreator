package com.fadcam.ui.faditor.compositor;

import android.view.Surface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.TextBoxRenderer;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides when the master video should render through {@link FxPreviewTextureView} instead of the
 * ordinary {@code PlayerView}, and keeps that view fed.
 *
 * <p><b>Routing is per-PROJECT, not per-frame.</b> The obvious rule — route while a layer is live
 * at the playhead — would tear the decoder off its surface and back every time the user scrubbed
 * across a layer's edge, and a surface swap costs a visible black flash. So the decoder moves
 * once, when the project first contains an adjustment layer that renders anything, and stays
 * there. The GL view passes frames through untouched when no layer is live at the playhead, so
 * the two states are pixel-identical anyway; all the churn bought was flicker.</p>
 *
 * <p><b>It does not fight the PlayerView for the surface.</b> An earlier shape of this hid or
 * detached the PlayerView while routed; both make {@code PlayerView} re-grab the video output at
 * its own next attach, silently un-routing the preview. The arrangement that holds is to leave
 * PlayerView entirely alone and simply DRAW OVER it — {@link FxPreviewTextureView} is the next
 * sibling in {@code fx_below_group} and letterboxes to exactly the same rect, so the stale frame
 * underneath is never visible.</p>
 */
public final class FxLivePreviewController {

    private static final String TAG = "FxLivePreview";

    /** What this controller needs from the editor, so it needs nothing else from it. */
    public interface Host {
        /** The player rendering video right now — legacy or gapless. Null before preparation. */
        @Nullable ExoPlayer activeVideoPlayer();
        /** Hand video output back to the PlayerView's own surface. */
        void restoreVideoOutput();
        /** The clip under the playhead, whose colour grade the preview must show. */
        @Nullable com.fadcam.ui.faditor.model.Clip clipAtPlayhead();

        /**
         * The BASE-PICTURE ALPHA at {@code absoluteMs}: the playhead clip's opacity keyframe
         * envelope times its master fade-knob factor, clamped, exactly as
         * {@code OpacityExportShaderProgram} multiplies them.
         *
         * <p>Asked of the HOST rather than computed here so there is ONE authority for the
         * number: the host hands the identical value to {@code playerView} on the Canvas path
         * and to {@link FxPreviewTextureView#setPictureAlpha} through this. Two copies of the
         * arithmetic is how preview and export drifted apart the first time.</p>
         *
         * <p>Defaults to 1 - fully opaque, a no-op - for a host that has no fade concept.</p>
         */
        default float clipPictureAlphaAt(long absoluteMs) { return 1f; }

        /**
         * The playhead clip's SPINE CANVAS TRANSFORM at {@code absoluteMs}, resolved into
         * {@code out} (length {@code SpineTransform.POSE}). Return false for "fit-centred", which
         * is what every project without one is.
         *
         * <p>Asked of the HOST for the same reason {@link #clipPictureAlphaAt} is: the host owns
         * the absolute-to-clip-local time conversion (segment position divided by the clip's
         * speed), and a second copy of it here would be a second clock for the same keyframes.
         * The host resolves the pose through {@code Clip.spinePoseAt} — the one method the
         * exporter also calls — so all this controller does is carry the six numbers.</p>
         */
        default boolean spinePoseAt(long absoluteMs, @NonNull float[] out) { return false; }

        /**
         * The PiP layer, so its decoder can be composited INTO this chain rather than drawn over
         * the graded result. Null on a host that has no PiP support.
         */
        @Nullable default OverlayVideoPreviewView overlayVideoLayer() { return null; }

        /**
         * The decoded picture of the IMAGE master clip under the playhead, or null when the base
         * is a video (or the decode has not finished).
         *
         * <p>An image never enters the player, so {@code setVideoSurface} routing shows nothing
         * for it and the chain below was blind: an adjustment layer graded every video clip and
         * left the photo alone, while the export graded both. Supplying the bitmap here is what
         * makes the two agree.</p>
         *
         * <p>The returned bitmap crosses to the GL thread. Retire it through
         * {@link FxPreviewTextureView#stillTrash()}, never by recycling it directly.</p>
         */
        @Nullable default android.graphics.Bitmap baseStillAtPlayhead() { return null; }

        /**
         * True while the GL chain is drawing that bitmap, so the host can hide whatever View was
         * showing the image — otherwise the ungraded {@code ImageView} sits ON TOP of the graded
         * result and the fix is invisible.
         */
        default void onBaseStillRouted(boolean routed) { }

        /**
         * One IMAGE OVERLAY's placement for the composite, built by the overlay layer that
         * already positions its {@code ImageView} — see {@code TextOverlayLayer.fxPipFor}. Null
         * when the picture is not decoded yet, or on a host with no overlay layer.
         *
         * <p>Asked of the LAYER rather than computed here on purpose: the layer holds the
         * content rect, the decoded bitmap and the "finger is down on this one" state, and
         * deriving any of those a second time is how the drawn image and the effected image end
         * up in two different places.</p>
         */
        /**
         * Sprites the composite is drawing this frame, so the Canvas view can stop painting them.
         * Mirrors {@code onGlOwnedImages}; told every tick, including when empty, or a sprite that
         * left GL would stay invisible.
         */
        default void onGlOwnedSprites(@NonNull java.util.Set<String> ids) { }

        /**
         * The sprite's real pixels for the composite to sample — see
         * {@code SpriteOverlayView.rasterFor}. Null when its sheet is not loaded yet, which omits
         * the sprite for that frame rather than drawing a placeholder.
         */
        @Nullable default android.graphics.Bitmap spriteRasterFor(
                @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem sp,
                int frameW, int frameH) {
            return null;
        }

        @Nullable default FxPreviewTextureView.Pip imagePipFor(
                @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o, int frameW, int frameH) {
            return null;
        }

        /**
         * The PiP decoder-facing surfaces, by live slot, as the GL thread publishes them. Slot 0 is
         * the one that has always existed; the rest appear only after {@link #buildPlan} asks for
         * them, which it does only for a project that genuinely stacks overlay videos.
         */
        default void onGlOwnedImages(@NonNull java.util.Set<String> ids) { }

        /** Captions now composite in GL at real z — tell the host which bindings are GL-owned so its Canvas views can hide. */
        default void onCaptionGlOwned(@NonNull java.util.Set<String> ids) { }

        /**
         * The effect stack would not compile on this GPU, so the preview is ungraded. Say so —
         * see {@code FxPreviewTextureView.SurfaceListener#onFxShaderUnavailable} for why a log
         * line was not good enough. Already on the main thread, fired once per failing stack.
         */
        default void onFxShaderUnavailable(@NonNull String reason) { }

        /**
         * Full-frame raster of layer_image_overlay at video resolution, or null when nothing
         * visible. The layer stays in layout as an invisible hit-test surface (spec §4.3).
         */
        @Nullable default android.graphics.Bitmap layerImageOverlayFor(int frameW, int frameH, long playheadMs) {
            return null;
        }
        default void onLayerImageOverlayRouted(boolean routed) { }

        /**
         * True while the crop EDITOR has the clip's own overlay up: the user must see the FULL
         * frame there to drag a rectangle over it, so the chain suppresses the crop pass until
         * editing ends. The export never sees this flag — it only ever shapes the live view.
         */
        default boolean suppressPreviewCrop() { return false; }
    }

    /**
     * Resolve a clip's colour grade for the renderer, or null when it has none.
     *
     * <p><b>The matrices come from media3, not from arithmetic here.</b> {@code Brightness},
     * {@code Contrast} and {@code RgbAdjustment} are {@code RgbMatrix} implementations, so asking
     * them for their matrix gives the export's exact numbers — including any future change to how
     * media3 defines them. Building the same matrix by hand would be a copy that silently ages.</p>
     *
     * <p>The activation thresholds mirror {@code EffectStack.toEffects} exactly, because a stage
     * export SKIPS must not run here: an HSL round trip at neutral saturation is not a perfect
     * identity, and "the preview drifts slightly on every clip" is a horrible bug to chase.</p>
     *
     * <p><b>Image clips are NOT excluded.</b> They used to be, because the chain could not see
     * them anyway; but {@code ExportManager.assembleClipVideoEffects} gates the grade on
     * {@code !isTransitionItem} alone, not on {@code isVideo}, so a graded photo came out of the
     * export graded and out of the editor raw. Now that a bitmap can be the base, dropping the
     * exclusion is what makes those two the same picture.</p>
     */
    @Nullable
    static FxPreviewTextureView.Grade gradeOf(
            @Nullable com.fadcam.ui.faditor.model.Clip clip) {
        if (clip == null) return null;
        com.fadcam.ui.faditor.effects.EffectStack s = clip.getEffectStack();
        // MIGRATED STACKS PAINT NOTHING HERE. Once FxGradeMigration has folded the ten grading
        // floats into the clip's FxStack, the grade is rendered by the SPINE FX rung that
        // buildPlan emits — the preview mirror of ExportManager's spineFxLayer. isActive() alone
        // is not enough of a gate: it stays TRUE for a migrated stack that also carries a LUT
        // (EffectStack.isActive = hasLut() || (!fxMigrated && hasGrade())), and this method reads
        // the ten floats unconditionally, so such a clip would be graded twice — once here, once
        // by the spine rung. Nothing is lost by leaving early: this Grade record has no LUT field
        // at all (see FxPreviewTextureView.Grade), so the LUT was never previewed on this path.
        if (s == null || s.isFxMigrated() || !s.isActive()) return null;

        // Exposure then contrast, composed into one matrix — media3 runs them as two chained
        // effects, and chaining two matrix stages IS multiplying them.
        float[] matA = identity();
        boolean matAOn = false;
        if (Math.abs(s.getExposure()) > 0.001f) {
            matA = mul(new androidx.media3.effect.Brightness(s.getExposure())
                    .getMatrix(0L, false), matA);
            matAOn = true;
        }
        if (Math.abs(s.getContrast()) > 0.001f) {
            matA = mul(new androidx.media3.effect.Contrast(s.getContrast())
                    .getMatrix(0L, false), matA);
            matAOn = true;
        }

        boolean satOn = Math.abs(s.getSaturation() - 1f) > 0.001f;
        // HslAdjustment takes a PERCENTAGE and HslShaderProgram divides by 100, so what the
        // shader actually sees is the plain fractional delta. Getting this wrong is what once
        // made export ~100x less saturated than the preview.
        float satAdj = s.getSaturation() - 1f;

        float[] matB = identity();
        boolean matBOn = Math.abs(s.getTemperature()) > 0.001f || Math.abs(s.getTint()) > 0.001f;
        if (matBOn) {
            matB = new androidx.media3.effect.RgbAdjustment.Builder()
                    .setRedScale(1f + s.getTemperature() * 0.18f)
                    .setGreenScale(1f + s.getTint() * 0.08f)
                    .setBlueScale(1f - s.getTemperature() * 0.18f)
                    .build()
                    .getMatrix(0L, false);
        }

        boolean shaderOn = Math.abs(s.getHighlights()) > 0.001f
                || Math.abs(s.getShadows()) > 0.001f
                || Math.abs(s.getFade()) > 0.001f
                || s.getVignette() > 0.001f
                || s.getGrain() > 0.001f;

        return new FxPreviewTextureView.Grade(
                matA, matAOn, satOn, satAdj, matB, matBOn, shaderOn,
                s.getHighlights(), s.getShadows(), s.getFade(), s.getVignette(), s.getGrain());
    }

    @NonNull
    private static float[] identity() {
        float[] m = new float[16];
        android.opengl.Matrix.setIdentityM(m, 0);
        return m;
    }

    /** {@code out = lhs * rhs} — apply {@code rhs} first, then {@code lhs}. */
    @NonNull
    private static float[] mul(@NonNull float[] lhs, @NonNull float[] rhs) {
        float[] out = new float[16];
        android.opengl.Matrix.multiplyMM(out, 0, lhs, 0, rhs, 0);
        return out;
    }

    @NonNull private final FxPreviewTextureView view;
    @NonNull private final Host host;
    /**
     * Main-thread scratch for one resolved spine pose. {@code setSpinePose} clones what it keeps,
     * so this array is never published to the GL thread — see its own note.
     */
    private final float[] spinePoseScratch =
            new float[com.fadcam.ui.faditor.model.SpineTransform.POSE];

    /** The decoder-facing surface, once the GL thread has published one. */
    @Nullable private Surface inputSurface;
    /**
     * The PiP decoder-facing surfaces, by live slot, as the GL thread publishes them. Slot 0 is
     * the one that has always existed; the rest appear only after {@link #buildPlan} asks for
     * them, which it does only for a project that genuinely stacks overlay videos.
     */
    private final Surface[] pipSurfaces = new Surface[FxPreviewTextureView.MAX_LIVE_PIPS];
    /** True while the decoder is rendering into {@link #view} rather than the PlayerView. */
    private boolean routed;
    /**
     * The player {@link #routed} describes. A decoder error releases the player and a later clip
     * builds a NEW one wired to the PlayerView; without this the stale flag would match, routing
     * would early-return, and the preview would render nothing at all — the identical
     * self-invalidating check {@link OverlayVideoPreviewView} needs for the keyed tier.
     */
    @Nullable private ExoPlayer routedPlayer;

    public FxLivePreviewController(@NonNull FxPreviewTextureView view, @NonNull Host host) {
        this.view = view;
        this.host = host;
        view.setSurfaceListener(new FxPreviewTextureView.SurfaceListener() {
            @Override public void onFxInputSurfaceReady(@NonNull Surface s) {
                inputSurface = s;
                // The surface arrives a frame or so after the view is laid out, which is usually
                // AFTER the first sync asked to route. Re-running the decision here is what makes
                // the deferred attach actually happen.
                route();
                // And ASK FOR A DRAW, which a video base never needed: the decoder's first frame
                // kicks one by itself, but an image base has no decoder callback at all, so the
                // draw requested by the sync that ran before this surface existed was dropped on
                // the floor and the chain would sit blank until the next playhead tick — which,
                // paused on a photo, never comes.
                view.requestFrame();
            }
            @Override public void onFxInputSurfaceLost() {
                inputSurface = null;
                java.util.Arrays.fill(pipSurfaces, null);
                OverlayVideoPreviewView ov = host.overlayVideoLayer();
                if (ov != null) ov.setFxCompositeSurface(null);
                if (routed) {
                    routed = false;
                    routedPlayer = null;
                    host.restoreVideoOutput();
                }
            }
            @Override public void onFxPipSurfaceReady(@NonNull Surface s, int slot) {
                if (slot >= 0 && slot < pipSurfaces.length) pipSurfaces[slot] = s;
            }
            @Override public void onFxShaderUnavailable(@NonNull String reason) {
                host.onFxShaderUnavailable(reason);
            }
        });
    }

    /**
     * Bring the live preview into line with {@code timeline} at {@code playheadMs}. Called from
     * the editor's existing playhead tick, on the main thread.
     */
    public void sync(@Nullable Timeline timeline, long playheadMs) {
        if (timeline == null) { stop(); return; }

        // Either reason is enough to take the decoder: a layer that renders something, or a clip
        // that carries a grade. Both are things the export will do and the user must therefore
        // see; the routing itself does not care which.
        List<AdjustmentLayer> all = timeline.getAdjustmentLayers();
        boolean anyRenders = false;
        if (all != null) {
            for (AdjustmentLayer l : all) {
                if (l.rendersAnything()) { anyRenders = true; break; }
            }
        }
        FxPreviewTextureView.Grade g = gradeOf(host.clipAtPlayhead());
        // A project whose ONLY effects are on an object still has to route, or those effects
        // render nowhere in the editor — which is the state that made "I don't see anything
        // working in a video layer" true.
        boolean objectFx = false;
        for (Clip c : timeline.getOverlayClips()) {
            if (c.getFx() != null && !c.getFx().active().isEmpty()) { objectFx = true; break; }
        }
        // A MASTER (spine) clip's OWN FxStack routes too. This loop walked getOverlayClips()
        // only, so a spine clip's stack reached no renderer at all in the editor while
        // ExportManager (:3150) has been emitting it as an AdjustmentLayerGlEffect — the exact
        // gap that made a migrated colour grade vanish from the preview and survive the render.
        // Asked of the PLAYHEAD clip, matching the legacy grade above, so routing behaves for a
        // migrated project exactly as it did for the same project before migration.
        // hasActiveFx() is `fx != null && !fx.active().isEmpty()`, the export's own gate: an
        // empty or absent stack — every clip in every project written before the Effects tab —
        // does not flip this and costs nothing.
        if (!objectFx) {
            Clip spine = host.clipAtPlayhead();
            if (spine != null && spine.hasActiveFx()) objectFx = true;
        }
        // A project that STACKS overlay videos routes too, even with no grade and no effect
        // anywhere. This chain is the only tier that can show more than one live PiP — the
        // sibling View path has one TextureView, so it plays the top clip and reduces the rest
        // to 400ms stills while the export composites all of them. Routing here is what lets
        // OverlayVideoPreviewView give those clips real decoders, and it costs a project with
        // nothing else live only a passthrough draw.
        boolean stacked = stacksOverlayVideos(timeline);
        // An IMAGE OVERLAY carrying effects, a chroma key or a blend mode routes too. Its picture
        // leaves the Canvas path on export for exactly those three reasons
        // ({@code TextOverlayItem.wantsGlExport}), and this chain is the editor's only equivalent
        // — without it the drawer's three "export only" notes stay true.
        // Walked ONCE per tick and carried into buildPlan. orderedVisualItems() rebuilds every
        // lane view from the flat lists on each call (getLayers is not cached), and this method
        // runs on every playhead tick — asking twice is the same doubled cost partitionAroundVideo
        // already warns against, on the same per-frame path.
        List<com.fadcam.ui.faditor.model.TextOverlayItem> glImages = glImageOverlays(timeline);
        // With hasExportMask() now in wantsGlExport() (3A.1), masked images are already in
        // glImages. This second list is kept for the pre-3A.1 path where masks were NOT in the
        // gate and were stranded on Canvas; it is now empty for new code but the routing
        // check stays so a build against an old TextOverlayItem still routes.
        List<com.fadcam.ui.faditor.model.TextOverlayItem> maskedImages =
                maskedImageOverlays(timeline);
        // IMAGES that must join GL not for their own sake but because a BLEND ABOVE needs to
        // composite against them. A plain NORMAL image below a SCREEN image is stranded on
        // Canvas while the blend above lives in GL — the blend then composites against the
        // video instead of the image below (spec §3A.2). This promotes every plain image
        // whose z is below any blending GL image's z, regardless of its own blend/mask/fx.
        // Text and sprites need the same but have no preview rasterizer (see buildPlan's
        // gap note); this general solution is image-only, the gap is named explicitly.
        List<com.fadcam.ui.faditor.model.TextOverlayItem> belowBlendImages =
                LayerPreviewController.plainImagesBelowBlend(timeline, glImages);
        // TEXT/SPRITE below a blending/masked GL IMAGE — static only, see plainTextsBelowBlend.
        // Those layers are stranded on Canvas while the blend above lives
        // in GL, so the blend samples video. Promoting static text/sprite to a GL texture at their real z closes
        // the gap; animated content would need per-frame raster and blows the 16.6ms budget
        // (~17ms measured on Note 9 for full-frame text raster every frame), so it stays on
        // Canvas and is documented as a gap.
        java.util.List<TextOverlayItem> belowTexts =
                LayerPreviewController.plainTextsBelowBlend(timeline, glImages);
        java.util.List<SpriteOverlayItem> belowSprites =
                LayerPreviewController.plainSpritesBelowBlend(timeline, glImages);
        boolean anyBelowTextSprite = !belowTexts.isEmpty() || !belowSprites.isEmpty();
        // GL pilot: layer_image_overlay — if this project has any IMAGE-track items, the
        // pilot must route so the rasterised layer can composite at its real z. Per-project
        // like crop: an empty section still passes through.
        boolean anyLayerImage = !LayerPreviewController.visibleImageItems(timeline).isEmpty();
        // Track matte: any overlay clip whose CompositingSpec names a matte peer needs the GL
        // chain to composite it — otherwise the peer vanishes and the recipient renders unmatted.
        boolean anyMatte = false;
        for (Clip c : timeline.getOverlayClips()) {
            com.fadcam.ui.faditor.model.CompositingSpec cs = c.getCompositing();
            if (cs != null && cs.mattePeerId != null) { anyMatte = true; break; }
        }
        // A project that CROPS any master clip routes too — and stays routed. Crop is a
        // per-CLIP property, so deciding per tick would tear the decoder off its surface at
        // every cropped/uncropped seam; per-project, an uncropped clip just passes through
        // untouched. The crop itself is applied below from the PLAYHEAD clip.
        boolean anyCrop = false;
        for (Clip c : timeline.getClips()) {
            if (!c.isImageClip() && c.effectiveCropFractions() != null) {
                anyCrop = true;
                break;
            }
        }
        // A project that PLACES any master clip on the canvas routes too, and stays routed —
        // exactly the rule crop follows, and for exactly the same reason. The transform is a
        // per-CLIP property, so deciding per tick would tear the decoder off its surface at
        // every placed/unplaced seam and cost a black flash; an unplaced clip just passes
        // through untouched. Without this the GL chain would never be asked for at all on an
        // otherwise-plain project and the transform would render in the file but nowhere in the
        // editor — the precise gap that made a spine FxStack invisible in the preview while the
        // exporter had been honouring it for weeks.
        boolean anySpineTransform = false;
        for (Clip c : timeline.getClips()) {
            if (c.hasSpineTransform()) { anySpineTransform = true; break; }
        }
        if (!anyRenders && g == null && !objectFx && !stacked && glImages.isEmpty()
                && maskedImages.isEmpty() && belowBlendImages.isEmpty() && !anyBelowTextSprite && !anyCrop
                && !anySpineTransform
                && !anyLayerImage && !anyMatte) {
            stop();
            return;
        }

        if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
        view.setGrade(g);

        // THE CLIP CROP, from the PLAYHEAD clip — never the selection (getSelectedClip()
        // falls back to clip 0 when nothing is selected and silently crops by the wrong
        // clip). effectiveCropFractions() is the SAME model authority ExportManager builds
        // its media3 Crop effect from (Clip.effectiveCropRectNdc), so scrubbing across clips
        // with different crops re-crops this chain exactly as the export cuts between them.
        // Image master clips are excluded because the export gates crop on isVideo.
        Clip playheadClip = host.clipAtPlayhead();
        float[] cropFractions =
                playheadClip != null && !playheadClip.isImageClip() && !host.suppressPreviewCrop()
                        ? playheadClip.effectiveCropFractions()
                        : null;
        view.setClipCrop(cropFractions);

        // THE CLIP FADE. Once an adjustment layer (or any other reason above) routes the
        // picture through this chain, playerView is held hidden underneath and the alpha the
        // editor writes on it lands on nothing - "it's going over nothing. It's not darkening
        // the clip." The GL chain has to carry it, and it carries it on the BASE PICTURE ONLY
        // (setPictureAlpha): after that clip's own crop, grade and spine FxStack, and before
        // the PiPs, the adjustment layers, the image overlays and the captions - the owner's
        // ruling, and the exact slot ExportManager gives OpacityExportEffect.
        view.setPictureAlpha(host.clipPictureAlphaAt(playheadMs));

        // THE SPINE CANVAS TRANSFORM, from the PLAYHEAD clip — never the selection, same trap
        // the crop above names. The host resolves it through Clip.spinePoseAt, which is the ONE
        // method SpineTransformExportEffect also reads, so scrubbing across clips with different
        // placements re-places this chain exactly as the export cuts between them. Null (the
        // identity) is the no-op the renderer skips whole.
        if (host.spinePoseAt(playheadMs, spinePoseScratch)) {
            view.setSpinePose(spinePoseScratch);
        } else {
            view.setSpinePose(null);
        }

        // An IMAGE master clip's pixels come from a bitmap, not the decoder. The chain then runs
        // at the PICTURE's size and with no rotation — matching export, whose rotate/flip stage is
        // gated on isVideo — and the host hides the ImageView that would otherwise cover the
        // graded result with the raw photo.
        android.graphics.Bitmap still = host.baseStillAtPlayhead();
        if (still != null && still.isRecycled()) still = null;
        view.setBaseStill(still);
        setBaseStillRouted(still != null);

        // Resolve on THIS thread — the GL thread must never walk the live model. See
        // FxPreviewTextureView.Layer for why a snapshot rather than the layer itself.
        int[] src = still != null
                ? new int[]{still.getWidth(), still.getHeight()}
                : videoSize();
        // THE COMPOSITE FRAME IS THE CANVAS. Everything below normalises against it — mask
        // geometry, image-overlay quads, caption quads, the below-blend raster — and every one
        // of those was authored against computeCanvasRect(). See compositeFrame().
        int[] size = compositeFrame(src[0], src[1]);
        view.setCompositeFrame(size[0], size[1], src[0], src[1]);
        OverlayVideoPreviewView ovFrame = host.overlayVideoLayer();
        if (ovFrame != null) ovFrame.setCompositeFrameSize(size[0], size[1]);
        view.setVideoRotation(still != null ? 0 : rotationDegrees());
        // Text/sprite below-blend raster — two paths:
        // 1) full-frame bitmap for static (and pixel-changing fallback) content, cached by CONTENT signature (spec §2)
        // 2) per-item textured quads for pose-animated content via OverlayTextureCache (spec §3)
        android.graphics.Bitmap belowBlend = null;
        java.util.List<FxPreviewTextureView.Pip> belowBlendOverlays = null;
        if (anyBelowTextSprite) {
            belowBlend = buildBelowBlendBitmap(timeline, belowTexts, belowSprites,
                    size[0], size[1], playheadMs);
            belowBlendOverlays = buildBelowBlendOverlays(timeline, belowTexts, belowSprites,
                    size[0], size[1], playheadMs);
        }
        view.setBelowBlendBitmap(belowBlend);
        view.setBelowBlendOverlays(belowBlendOverlays);
        // Captions — third client of the texture cache (SPEC_20260829_CAPTIONS_GL §3)
        java.util.List<FxPreviewTextureView.Pip> captionPips = buildCaptionOverlays(timeline, size[0], size[1], playheadMs);
        view.setCaptionOverlays(captionPips);
        // Tell host which bindings are GL-owned so Canvas views can hide (avoid double draw)
        java.util.Set<String> captionOwned = new java.util.HashSet<>();
        if (captionPips != null) {
            for (FxPreviewTextureView.Pip p : captionPips) captionOwned.add(p.clipId);
        }
        host.onCaptionGlOwned(captionOwned);
        // GL pilot: layer_image_overlay rasterised to a full-frame bitmap at video
        // resolution, composited at its real z inside the GL pass. The View stays in
        // layout as an invisible hit-test surface (alpha 0, still VISIBLE so it receives
        // touch — spec §4.3). Bitmap is provided on demand, not per frame, and handed
        // via stillTrash discipline so GL never touches a recycled bitmap (trap 6.4).
        android.graphics.Bitmap layerOverlay = host.layerImageOverlayFor(size[0], size[1], playheadMs);
        if (layerOverlay != null && layerOverlay.isRecycled()) layerOverlay = null;
        view.setLayerOverlayBitmap(layerOverlay);
        boolean layerRouted = layerOverlay != null;
        if (layerRouted != layerOverlayRouted) {
            layerOverlayRouted = layerRouted;
            host.onLayerImageOverlayRouted(layerRouted);
        }
        view.setCompositePlan(buildPlan(timeline, playheadMs, size, stacked, glImages));
        // Routed even while a still is the base: routing is per-PROJECT (see the class note), and
        // dropping it here would make every image→video crossing pay for a surface swap.
        route();
    }

    /** @see Host#onBaseStillRouted — told once per transition, not once per tick. */
    private void setBaseStillRouted(boolean routed) {
        if (routed == baseStillRouted) return;
        baseStillRouted = routed;
        host.onBaseStillRouted(routed);
    }

    /** @see #setBaseStillRouted */
    private boolean baseStillRouted;
    private boolean layerOverlayRouted;
    // Below-blend text/sprite raster cache — content-signature based (spec §2: stop re-rastering static)
    @Nullable private Bitmap cachedBelowBitmap;
    private int cachedBelowW = -1, cachedBelowH = -1;
    @Nullable private String cachedBelowSignature = null;
    // Instrumentation for spec §5.3: how many times the full-frame raster actually ran
    private int belowBlendRasterCount = 0;
    // Per-item texture cache for animated pose (spec §3) — raster once at authored size, quad per frame
    @NonNull private final OverlayTextureCache overlayTextureCache = new OverlayTextureCache();
    // Captions — third client of the texture cache (SPEC_20260829_CAPTIONS_GL §3.1)
    @NonNull private final CaptionTextureCache captionTextureCache = new CaptionTextureCache();
    // Instrumentation for captions (§5.4)
    private int captionRasterCount = 0;

    /**
     * Build the z-ordered composite plan: every live adjustment layer (resolved to its immutable
     * snapshot, the list program compilation keys on) interleaved with every PiP the overlay
     * layer can supply — in the ONE order both renderers read, {@code LayerPreviewController
     * .orderedCompositedItems}, the same list the export's effect chain is assembled from. So an
     * adjustment layer grades the same set of PiPs here as in the file, and a layer between two
     * PiPs grades only the lower one.
     *
     * <p>The surface offer stays "every tick rather than once": the PiP layer creates its player
     * lazily (only when a PiP clip is actually on screen) and releases it when the list empties,
     * so the moment routing becomes possible is not knowable from here.</p>
     */
    @NonNull
    private FxPreviewTextureView.CompositePlan buildPlan(@NonNull Timeline timeline,
            long playheadMs, @NonNull int[] size, boolean stacked,
            @NonNull List<com.fadcam.ui.faditor.model.TextOverlayItem> glImages) {
        List<AdjustmentLayer> live =
                LayerPreviewController.visibleAdjustmentLayers(timeline, playheadMs);
        List<FxPreviewTextureView.Layer> snapshot = new ArrayList<>(live.size());
        java.util.IdentityHashMap<AdjustmentLayer, Integer> layerIndex =
                new java.util.IdentityHashMap<>();
        // ── THE SPINE CLIP'S OWN FX ──────────────────────────────────────────────────────────
        // The preview half of ExportManager:3150. That call wraps the clip's FxStack in a
        // throwaway AdjustmentLayer (spineFxLayer) and hands it to AdjustmentLayerGlEffect,
        // relying on the layer's DEFAULTS collapsing the layer machinery to "just run this
        // stack": no masks and no chroma key make MaskSdf coverage 1, a null transform makes
        // opacityAt() 1, and NORMAL blend makes the final line `out = graded`. The same
        // defaults do the same thing in FxPreviewTextureView.drawOneLayer, which is why this
        // reuses Layer.of rather than growing a second shader host for the spine.
        //
        // SAME FIELDS AS spineFxLayer: name + fx, everything else left at construction default.
        // SAME TIME BASE: the export passes editorTimeOffsetFor(...), whose whole job is to put
        // AdjustmentLayerGlEffect on EDITOR timeline ms; playheadMs already IS editor timeline
        // ms here (it is what visibleAdjustmentLayers and every other Layer.of call below are
        // resolved against), so keyframes and card time resolve identically in both.
        //
        // NO-OP PROOF: gated on hasActiveFx() — `fx != null && !fx.active().isEmpty()`, the
        // export's own gate. A clip with an empty or absent stack adds nothing to `snapshot`,
        // leaves spineLayerIndex at -1, and the plan it produces is element-for-element the
        // plan it produced before this block existed. Layer.of would refuse it anyway
        // (rendersAnything() is `!hidden && !fx.active().isEmpty()`), so the gate is belt and
        // braces, not the only guard.
        int spineLayerIndex = -1;
        Clip spineClip = host.clipAtPlayhead();
        if (spineClip != null && spineClip.hasActiveFx()) {
            AdjustmentLayer synthetic = new AdjustmentLayer();
            synthetic.setName("clip:" + spineClip.getId());
            synthetic.setFx(spineClip.getOrCreateFx());
            FxPreviewTextureView.Layer sl =
                    FxPreviewTextureView.Layer.of(synthetic, playheadMs, size[0], size[1]);
            if (sl != null) {
                // Index 0 — claimed BEFORE the live-layer walk, so the walk's own
                // snapshot.size() indices shift up by one without any arithmetic here.
                spineLayerIndex = snapshot.size();
                snapshot.add(sl);
            }
        }
        for (AdjustmentLayer l : live) {
            FxPreviewTextureView.Layer s =
                    FxPreviewTextureView.Layer.of(l, playheadMs, size[0], size[1]);
            if (s != null) {
                layerIndex.put(l, snapshot.size());
                snapshot.add(s);
            }
        }
        OverlayVideoPreviewView ov = host.overlayVideoLayer();
        // Ask for the extra composite inputs only once the project actually stacks. The request
        // only ever grows, and the GL side builds each input lazily, so a one-PiP project never
        // allocates the second OES texture at all.
        if (stacked) view.requestPipInputs(FxPreviewTextureView.MAX_LIVE_PIPS);
        boolean offer = ov != null && pipSurfaces[0] != null;
        if (offer) {
            ov.setFxCompositeSurface(pipSurfaces[0]);
            ov.setFxExtraSurfaces(stacked ? pipSurfaces : null);
            ov.setFxStillTrash(view.stillTrash());
        }
        List<FxPreviewTextureView.Rung> rungs = new ArrayList<>();
        int pipRungs = 0;
        // Plain images that must composite in GL because a blending image above needs a GL
        // background — see LayerPreviewController.plainImagesBelowBlend. This is the general
        // fix for §3A.2: the question is not "do I want GL for myself?" but "does something
        // ABOVE me need me in GL to composite against?". Images are handled here; STATIC
        // text/sprites below a blend are rasterised to the belowBlend bitmap and composited
        // BEFORE the blend so the blend has something to sample — animated text/sprite would
        // need per-frame raster (~17ms on Note 9, over the 16.6ms budget) and remain on Canvas
        // as a documented gap (see buildBelowBlendBitmap).
        //
        // THIS IS THE ONLY REASON A PLAIN IMAGE JOINS THE CHAIN. glImages empty — every
        // project that has never picked a blend, an effect, a key or a mask on an image —
        // makes plainImagesBelowBlend return the empty list on its first line, so the walk
        // below emits exactly the rungs it emitted before and the composite is unchanged.
        java.util.Set<String> belowBlendIds = new java.util.HashSet<>();
        for (com.fadcam.ui.faditor.model.TextOverlayItem b
                : LayerPreviewController.plainImagesBelowBlend(timeline, glImages)) {
            belowBlendIds.add(b.getId());
        }
        // Track matte: peers are hidden from normal rendering while they serve as mattes
        // (LayerPreviewController.servingMatteClipIds), but their luma is needed for the
        // recipient's alpha. Same model field and same luma math as export (BlendModeGlEffect).
        // Still-frame fallback per FEEDBACK_20260702 §B3: master + overlay + matte =3 decoders
        // exceeds Note 9's ~2, so a STILL-frame matte in preview is the correct fallback.
        java.util.Set<String> servingMatteIds = LayerPreviewController.servingMatteClipIds(timeline);
        java.util.Map<String, Clip> visibleById = new java.util.HashMap<>();
        for (Clip c : LayerPreviewController.visibleOverlayVideoClips(timeline)) visibleById.put(c.getId(), c);
        java.util.Set<String> owned = new java.util.HashSet<>();
        // orderedVisualItems, NOT orderedCompositedItems — the bug JoyRaptor hit.
        //
        // orderedCompositedItems keeps ONLY PiP clips and adjustment layers
        // (LayerPreviewController:130-138: `if (isPip || isAdjustment) out.add(v)`). An image
        // overlay is neither, so the image branch below — the one written to promote a plain
        // image into the chain — was NEVER REACHED: the walk it lived in could not contain an
        // image overlay by construction. That is why a NORMAL image under a blended one was
        // "omitted from the GL state" even though the promotion rule was already written.
        //
        // This list is a SUPERSET in the SAME order (orderedCompositedItems is a filter of it),
        // so every PiP and every adjustment rung lands in exactly the position it landed in
        // before. Non-image items fall through the branches below untouched: a master clip has
        // no adjustment and no text overlay, a text overlay fails isImage(), a sprite has no
        // text overlay at all. Nothing is added that was not added before EXCEPT a member of
        // belowBlendIds, which is empty unless an image is already GL-routed.
        for (LayerPreviewController.VisualItem v
                : LayerPreviewController.orderedVisualItems(timeline)) {
            Clip vc = v.item.getClip();
            if (vc != null && vc.isOverlayClip()) {
                if (servingMatteIds.contains(vc.getId())) {
                    // This clip's pixels exist only as another clip's luma matte — don't also
                    // render it as a PiP of its own (LayerPreviewController.renderableOverlayVideoClips).
                    continue;
                }
                FxPreviewTextureView.Pip p = offer ? ov.fxPipFor(vc) : null;
                if (p != null) {
                    com.fadcam.ui.faditor.model.CompositingSpec cs = vc.getCompositing();
                    if (cs != null && cs.mattePeerId != null) {
                        Clip peer = visibleById.get(cs.mattePeerId);
                        if (peer != null) {
                            FxPreviewTextureView.Pip mattePip = ov != null ? ov.mattePipFor(peer) : null;
                            if (mattePip != null) {
                                p = p.withMatte(mattePip);
                            } else {
                                FLog.d("FxMatte", "matte peer " + peer.getId() + " not ready for recipient " + vc.getId() + " — unmatted");
                            }
                        } else {
                            FLog.d("FxMatte", "dangling matte peerId " + cs.mattePeerId + " for " + vc.getId() + " — unmatted");
                        }
                    }
                    rungs.add(FxPreviewTextureView.Rung.pip(p)); pipRungs++;
                }
                else if (!loggedNullPip) {
                    // ONCE, not per frame: a PiP with no geometry yet is the normal state for
                    // the first frames after a clip appears, so this fired 60 times a second
                    // while telling you nothing new.
                    loggedNullPip = true;
                    FLog.d("FxMultiPip", "buildPlan: pip " + vc.getId() + " -> null");
                }
                continue;
            }
            AdjustmentLayer al = v.item.getAdjustment();
            if (al == null) {
                // A PLAIN image overlay that something ABOVE it needs to blend against,
                // resolved to where its LANE puts it — beneath every rung still to come,
                // which is where the blended image will be appended after this walk. Its
                // ImageView is hidden through onGlOwnedImages once it rides the chain, so
                // the raw picture does not sit on top of the composited one.
                //
                // ONE condition, deliberately: belowBlendIds. A plain image that nothing
                // above it needs stays on Canvas exactly as it always has — promoting it
                // would move it from "painted over the GL surface" to "painted inside it",
                // a z change nobody asked for. Time is NOT filtered here: fxPipFor already
                // returns null for an item that is not visible at the playhead, so an image
                // whose range does not reach this frame contributes no rung.
                com.fadcam.ui.faditor.model.TextOverlayItem o = v.item.getTextOverlay();
                if (o != null && o.isImage() && !o.wantsGlExport()
                        && belowBlendIds.contains(o.getId())) {
                    FxPreviewTextureView.Pip p = host.imagePipFor(o, size[0], size[1]);
                    if (p != null) {
                        rungs.add(FxPreviewTextureView.Rung.pip(p));
                        owned.add(o.getId());
                    }
                }
                continue;
            }
            Integer idx = layerIndex.get(al);
            if (idx != null) rungs.add(FxPreviewTextureView.Rung.layer(idx));
        }
        // LOG THE PLAN ONLY WHEN ITS SHAPE CHANGES. buildPlan runs once per rendered frame, so
        // this was concatenating a string and writing to logcat 60 times a second — FLog.d takes
        // an already-built String, so the concatenation happens at the call site whether or not
        // anything is listening. The diagnostic value is in the transitions ("we went from 2
        // rungs to 4"), which is exactly what this still prints.
        // ── IMAGE OVERLAYS, on top of everything this walk just built ────────────────────────
        // ABOVE the adjustment layers, and that is not a shortcut — it is where the EXPORT puts
        // them. ExportManager appends every ImageBlendGlEffect after the PiP block, then inserts
        // the adjustment layers at indices INSIDE that block, so the images end up last; chain
        // position is paint order, so a blended or effected image composites over the graded
        // frame. ImageBlendGlEffect states the same z caveat from the other side, and it is why
        // going through GL is opt-in rather than the path every image takes.
        java.util.Set<String> glOwned = new java.util.HashSet<>();
        java.util.Set<String> glSprites = new java.util.HashSet<>();
        // ONE WALK, IN LANE ORDER — images and sprites interleaved, not one type after the other.
        //
        // These were two loops: every GL image, then every GL sprite. That is TYPE order, and it
        // disagreed with the export, which emits both at true lane z (ZA lane, finding 2). The
        // visible cost was a blend above a bent sprite sampling the sprite in the FILE and the
        // video on SCREEN — the same class of preview/export split as the blue-rect bug, still
        // live. Membership is by id because glImages is asked of the PROJECT rather than of the
        // playhead (see its doc); the per-frame "is it visible" answer stays where it was, in
        // imagePipFor/spritePip returning null.
        java.util.Set<String> glImageIds = new java.util.HashSet<>();
        for (com.fadcam.ui.faditor.model.TextOverlayItem o : glImages) glImageIds.add(o.getId());
        for (LayerPreviewController.VisualItem v
                : LayerPreviewController.orderedVisualItems(timeline)) {
            com.fadcam.ui.faditor.model.TextOverlayItem o = v.item.getTextOverlay();
            if (o != null && glImageIds.contains(o.getId())) {
                FxPreviewTextureView.Pip p = host.imagePipFor(o, size[0], size[1]);
                if (p == null) continue;   // not decoded yet: absent until ready, as a still PiP is
                // SPEC E mesh: enrich the flat Pip with a stamp snapshot (deep spec copy +
                // unfolded animated placement). wantsGlExport already routes bent images here via
                // hasMesh; the GL thread then stamps via the shared MeshStampGl and composites as
                // identity, so blend/mask/key/FX/adjustment ride unchanged. Null keeps the flat
                // path — byte-identical for every project without a bend.
                FxPreviewTextureView.Pip mp = withMeshInputs(o, p, playheadMs, size, timeline);
                if (mp != null) p = mp;
                rungs.add(FxPreviewTextureView.Rung.pip(p));
                glOwned.add(o.getId());
                continue;
            }
            // SPRITES THAT BELONG TO THE COMPOSITE. A sprite painted by its Canvas view sits OVER
            // this surface, so a blend above it samples the video instead of the sprite, a mask
            // cannot cut it and an adjustment layer cannot grade it. Drawn here it is INSIDE the
            // composite and all three work.
            com.fadcam.ui.faditor.sprite.SpriteOverlayItem sp = v.item.getSprite();
            if (sp == null || !sp.wantsGl() || !sp.isVisibleAt(playheadMs)) continue;
            FxPreviewTextureView.Pip p = spritePip(sp, playheadMs, size);
            if (p == null) continue;   // not rasterised yet: absent until ready, as an image is
            rungs.add(FxPreviewTextureView.Rung.pip(p));
            glSprites.add(sp.getId());
        }
        host.onGlOwnedSprites(glSprites);

        // NO "deferred" pass for the remaining plain images. They stay on Canvas, drawn by
        // their own ImageView over this surface — which is where the export paints them too
        // (its final CompositeExportOverlay pass runs after every ImageBlendGlEffect).
        owned.addAll(glOwned);
        // Told every tick, INCLUDING when the set is empty — see Host#onGlOwnedImages.
        host.onGlOwnedImages(owned);

        int shape = ((rungs.size() * 31 + pipRungs) * 31 + snapshot.size()) * 31
                + spineLayerIndex;
        if (offer) shape = ~shape;
        if (shape != lastPlanShape) {
            lastPlanShape = shape;
            FLog.d("FxMultiPip", "plan: rungs=" + rungs.size() + " pips=" + pipRungs
                    + " layers=" + snapshot.size() + " offer=" + offer);
        }
        return new FxPreviewTextureView.CompositePlan(snapshot, rungs, spineLayerIndex);
    }

    /**
     * One sprite as a GL quad — texture, pose, corner pin and bend.
     *
     * <p>Everything here comes from the sprite's OWN model authorities
     * ({@code animatedCentre/Size/Rotation/Opacity}, {@code animatedCornerPin}, {@code getMesh}),
     * the same ones the Canvas view and the export read, so the three cannot disagree about where
     * the sprite is. Aspect comes from the rasterised bitmap — the exact pixels the stamp will
     * sample — which is the same rule the image path states for itself.
     *
     * <p>Returns null when there is nothing to draw yet. Absent-until-ready is how a still PiP
     * behaves too; a half-rasterised sprite is not worth a frame of wrong picture.
     */
    @Nullable
    private FxPreviewTextureView.Pip spritePip(
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem sp,
            long playheadMs, @NonNull int[] size) {
        try {
            if (size[0] <= 0 || size[1] <= 0) return null;
            // The REAL cells, from the view that owns the sheet — NOT
            // OverlayTextureCache.rasterizeSprite, which is a placeholder that fills a solid blue
            // rectangle. Routing GL sprites through that placeholder was this lane's regression
            // on 2026-09-13: a bent sprite previewed as a blue box while the export rasterised
            // real cells (caught by the ZA lane, finding 1).
            android.graphics.Bitmap tex = host.spriteRasterFor(sp, size[0], size[1]);
            if (tex == null || tex.isRecycled() || tex.getHeight() <= 0) return null;
            float alpha = sp.animatedOpacity(playheadMs);
            if (alpha < 0.005f) return null;

            float hNorm = sp.animatedSizeFraction(playheadMs);
            float aspect = tex.getWidth() / (float) tex.getHeight();
            float frameAspect = size[0] / (float) size[1];
            if (!(hNorm > 0f) || !(aspect > 0f) || !(frameAspect > 0f)) return null;
            float wNorm = hNorm * aspect / frameAspect;

            float cx = sp.animatedCenterX(playheadMs);
            float cy = sp.animatedCenterY(playheadMs);
            float rot = sp.animatedRotation(playheadMs);

            float[] pins = null;
            if (sp.hasCornerPin()) {
                pins = new float[com.fadcam.ui.faditor.model.CornerPin.SIZE];
                sp.animatedCornerPin(playheadMs, pins);
            }
            FxPreviewTextureView.Pip p = FxPreviewTextureView.Pip.ofImage(
                    cx, cy, wNorm / 2f, hNorm / 2f, rot, alpha,
                    null, playheadMs, null, 0f, size[0], size[1], sp.getId(), tex, 1f, pins);
            if (!sp.hasMesh()) return p;

            sp.installMeshCurve();
            com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec src = sp.getMesh();
            if (src == null || !src.hasWarp()) return p;
            FxPreviewTextureView.MeshInputs mi = new FxPreviewTextureView.MeshInputs(
                    src.copy(), sp.meshLocalTime(playheadMs), cx, cy, wNorm, hNorm,
                    // No stored rotation pivot on a sprite, so the fold is about the centre.
                    0f, 0f, false, rot,
                    // No caption-style preset on a sprite: identity scale, no offset, full reveal.
                    1f, 1f, 0f, 0f,
                    alpha, 1f, pins,
                    sp.isFlipH() ? -1f : 1f, sp.isFlipV() ? -1f : 1f);
            FxPreviewTextureView.Pip mp = p.withMesh(mi);
            return mp != null ? mp : p;
        } catch (Exception ignored) {
            return null;   // never let a sprite cost the frame
        }
    }

    /**
     * Every image overlay whose picture belongs to the shader rather than to a Canvas, in the one
     * bottom→top visual order, at any time in the project.
     *
     * <p>The predicate is {@code wantsGlExport()} and nothing else. That is the SAME single
     * authority {@code ExportManager} emits its {@code ImageBlendGlEffect}s from and that
     * {@code CompositeExportOverlay.filterTextOverlays} drops its canvas draws by — its own doc
     * says the two must be exactly complementary or the image is drawn twice or not at all. The
     * preview now has the same pair of decisions to keep in step (composite it, hide its view),
     * so it asks the same question rather than inventing a third opinion about what "needs a
     * shader" means.</p>
     *
     * <p>Asked of the PROJECT rather than of the playhead, because routing is per-project (see
     * the class note). The per-frame visibility gate lives in {@code fxPipFor}.</p>
     */
    @NonNull
    private static List<com.fadcam.ui.faditor.model.TextOverlayItem> glImageOverlays(
            @NonNull Timeline timeline) {
        List<com.fadcam.ui.faditor.model.TextOverlayItem> out = new ArrayList<>();
        for (LayerPreviewController.VisualItem v
                : LayerPreviewController.orderedVisualItems(timeline)) {
            com.fadcam.ui.faditor.model.TextOverlayItem o = v.item.getTextOverlay();
            if (o != null && o.wantsGlExport()) out.add(o);
        }
        return out;
    }

    /**
     * SPEC E mesh enrichment: snapshot a bent image's warp + unfolded animated placement for the
     * shared stamp. Returns null to keep the flat path (no bend, non-image, undecoded, degenerate
     * placement) — every pre-mesh frame takes that branch.
     *
     * <p>Numbers mirror the flat host ({@code TextOverlayLayer.fxPipFor}) and {@code ImageOverlayDraw}
     * via the SAME model authorities (animatedCentre/Size/Scale/Rotation/Opacity, preset via
     * {@code CaptionAnimator}, pivot via {@code pivotOffsetFromCentre}, pins via
     * {@code animatedCornerPin}); matrices themselves are built once inside {@code MeshStampGl} via
     * {@code MeshPlacement}, so preview and export cannot transcribe them differently. Aspect comes
     * from the Pip's own still bitmap — the exact pixels the stamp will sample — so the flat and
     * meshed paths can never disagree about the photo's shape. Animated (never live): unarmed items
     * track the finger anyway (animated==static with no keyframes); armed live-move shows the
     * keyframed pose until release — documented, test-only until the Bend tool lands.
     */
    @Nullable
    private static FxPreviewTextureView.Pip withMeshInputs(
            @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o,
            @NonNull FxPreviewTextureView.Pip p,
            long playheadMs, @NonNull int[] size, @NonNull Timeline timeline) {
        try {
            if (!o.isImage() || !o.hasMesh()) return null;
            android.graphics.Bitmap still = p.still;
            if (still == null || still.isRecycled() || still.getHeight() <= 0) return null;
            if (size[0] <= 0 || size[1] <= 0) return null;
            o.installMeshCurve();
            com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec src = o.getMesh();
            if (src == null || !src.hasWarp()) return null;
            com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec copy = src.copy();
            long localMs = o.meshLocalTime(playheadMs);
            float cx = o.animatedCenterX(playheadMs);
            float cy = o.animatedCenterY(playheadMs);
            float sizeFrac = o.animatedSizeFraction(playheadMs);
            float sx = o.animatedScaleX(playheadMs);
            float sy = o.animatedScaleY(playheadMs);
            float rot = o.animatedRotation(playheadMs);
            float opacity = o.animatedOpacity(playheadMs);
            if (sizeFrac <= 0f || sx <= 0f || sy <= 0f) return null;
            float imageAspect = still.getWidth() / (float) still.getHeight();
            float frameAspect = size[0] / (float) size[1];
            if (!(imageAspect > 0f) || !(frameAspect > 0f)) return null;
            float wNorm = sizeFrac * imageAspect * sx / frameAspect;
            float hNorm = sizeFrac * sy;
            if (!(wNorm > 0f) || !(hNorm > 0f)) return null;
            float[] pins = null;
            if (o.hasCornerPin()) {
                pins = new float[com.fadcam.ui.faditor.model.CornerPin.SIZE];
                o.animatedCornerPin(playheadMs, pins);
            }
            float pivOffX = o.mirrorSignX() * o.pivotOffsetFromCentreX(wNorm, hNorm, pins);
            float pivOffY = o.mirrorSignY() * o.pivotOffsetFromCentreY(wNorm, hNorm, pins);
            boolean applyPivot = !o.isRotationPivotNeutral(pins);
            com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform preset =
                    com.fadcam.ui.faditor.transcript.CaptionAnimator.textBoxTransformAt(
                            com.fadcam.ui.faditor.transcript.CaptionAnimator.parsePreset(
                                    o.getTextAnimPreset()),
                            playheadMs, o.motionRangeStartMs(),
                            o.motionSpanMs(timeline.getTotalDurationMs()),
                            o.getTextAnimInPct(), o.getTextAnimOutPct(),
                            sizeFrac * size[1]);
            float dxNorm = preset.dx / (float) size[0];
            float dyNorm = preset.dy / (float) size[1];
            float alpha = Math.max(0f, Math.min(1f, opacity * preset.alpha));
            float reveal = Math.max(0f, Math.min(1f, preset.revealFrac));
            // SPEC H mirror — the model's ONE shared definition (TextOverlayItem.mirrorSignX/Y),
            // read here on the main thread; the stamp shader applies it. Same values the export
            // passes, so the two surfaces cannot disagree.
            float mirrorX = o.mirrorSignX();
            float mirrorY = o.mirrorSignY();
            FxPreviewTextureView.MeshInputs mi = new FxPreviewTextureView.MeshInputs(
                    copy, localMs, cx, cy, wNorm, hNorm,
                    pivOffX, pivOffY, applyPivot, rot,
                    preset.scaleX, preset.scaleY, dxNorm, dyNorm,
                    alpha, reveal, pins,
                    mirrorX, mirrorY);
            return p.withMesh(mi);
        } catch (Exception ignored) {
            return null; // never let a bend cost the picture — flat path below
        }
    }

    /**
     * Every IMAGE overlay whose CompositingSpec carries masks but whose export path stays
     * Canvas ({@code wantsGlExport()} false — see {@link #glImageOverlays} for why THAT gate
     * must not grow to include masks).
     */
    @NonNull
    private static List<com.fadcam.ui.faditor.model.TextOverlayItem> maskedImageOverlays(
            @NonNull Timeline timeline) {
        List<com.fadcam.ui.faditor.model.TextOverlayItem> out = new ArrayList<>();
        for (LayerPreviewController.VisualItem v
                : LayerPreviewController.orderedVisualItems(timeline)) {
            com.fadcam.ui.faditor.model.TextOverlayItem o = v.item.getTextOverlay();
            if (o == null || !o.isImage() || o.wantsGlExport()) continue;
            com.fadcam.ui.faditor.model.CompositingSpec cs = o.getCompositing();
            if (cs != null && !cs.masks.isEmpty()) out.add(o);
        }
        return out;
    }

    /**
     * Whether the project has two overlay VIDEOS whose time ranges overlap — the one condition
     * that needs more than one live PiP.
     *
     * <p>Asked of the PROJECT, not of the playhead, because routing is per-project (see the
     * class note): deciding it per frame would swap the decoder's surface every time the user
     * scrubbed across the edge of a stack, and a surface swap costs a visible black flash.</p>
     *
     * <p>Images and hidden clips are excluded — neither can ever own a decoder — but nothing
     * else is filtered. Being slightly over-eager here only engages a chain that is a
     * passthrough when nothing is live; being under-eager would leave the stack un-playable.</p>
     */
    /** For spec §5.3 instrumentation: number of full-frame rasters since creation. */
    public int getBelowBlendRasterCount() { return belowBlendRasterCount; }

    /** Build the content signature for the below-blend bitmap cache (spec §2). */
    @NonNull
    private static String buildBelowBlendSignature(
            @NonNull java.util.List<TextOverlayItem> visTexts,
            @NonNull java.util.List<SpriteOverlayItem> visSprites,
            int videoW, int videoH, long playheadMs, long totalDurationMs) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(videoW).append('x').append(videoH).append('|');
        sb.append(totalDurationMs).append('|');
        // Sort by id for deterministic signature
        java.util.List<TextOverlayItem> sortedTexts = new java.util.ArrayList<>(visTexts);
        sortedTexts.sort(java.util.Comparator.comparing(TextOverlayItem::getId));
        for (TextOverlayItem tto : sortedTexts) {
            sb.append(tto.getId()).append(':');
            String shown = TextBoxRenderer.textAt(tto, playheadMs, totalDurationMs);
            sb.append(shown).append(':');
            sb.append(tto.animatedOpacity(playheadMs)).append(',');
            sb.append(tto.animatedSizeFraction(playheadMs)).append(',');
            sb.append(tto.animatedScaleX(playheadMs)).append(',');
            sb.append(tto.animatedScaleY(playheadMs)).append(',');
            sb.append(tto.animatedCenterX(playheadMs)).append(',');
            sb.append(tto.animatedCenterY(playheadMs)).append(',');
            sb.append(tto.animatedRotation(playheadMs)).append('|');
            sb.append(tto.getColorInt()).append(',');
            sb.append(tto.getStrokeColorInt()).append(',');
            sb.append(tto.animatedStrokeWidthPx(playheadMs)).append(',');
            sb.append(tto.getShadowColorInt()).append(',');
            sb.append(tto.animatedShadowRadiusPx(playheadMs)).append(',');
            sb.append(tto.animatedShadowAngleDeg(playheadMs)).append(',');
            sb.append(tto.animatedShadowDistancePx(playheadMs)).append(',');
            sb.append(tto.getGlowColorInt()).append(',');
            sb.append(tto.animatedGlowRadiusPx(playheadMs)).append(',');
            sb.append(tto.getBackgroundColorInt()).append(',');
            sb.append(tto.getFontFamily()).append(',');
            sb.append(tto.isBold()).append(',').append(tto.isItalic()).append(',').append(tto.isUnderline()).append(',');
            sb.append(tto.getTextAlign()).append(',').append(tto.getTextCase()).append('|');
            if (tto.hasStyleSpans() && tto.getStyleSpans() != null) sb.append(tto.getStyleSpans().hashCode());
            sb.append(';');
        }
        java.util.List<SpriteOverlayItem> sortedSprites = new java.util.ArrayList<>(visSprites);
        sortedSprites.sort(java.util.Comparator.comparing(SpriteOverlayItem::getId));
        for (SpriteOverlayItem sso : sortedSprites) {
            sb.append(sso.getId()).append(':');
            sb.append(sso.animatedOpacity(playheadMs)).append(',');
            sb.append(sso.animatedCenterX(playheadMs)).append(',');
            sb.append(sso.animatedCenterY(playheadMs)).append(',');
            sb.append(sso.animatedSizeFraction(playheadMs)).append(',');
            sb.append(sso.animatedRotation(playheadMs)).append(',');
            sb.append(sso.isFlipH()).append(',').append(sso.isFlipV()).append(',');
            sb.append(sso.getSheetId()).append(';');
        }
        return sb.toString();
    }

    /**
     * Build per-item textured quads for pose-animated text/sprite below a blend (spec §3).
     * Raster each item's glyphs once at authored size (OverlayTextureCache, supersample 1.5×),
     * then per frame feed quad via existing Pip path (cx,cy,halfW,halfH,rotationDeg,alpha).
     * Returns null when nothing needs the texture path. One predicate, one place —
     * {@link OverlayTextureCache#canUseTexture(TextOverlayItem)} / sprite variant.
     */
    @Nullable
    private java.util.List<FxPreviewTextureView.Pip> buildBelowBlendOverlays(
            @NonNull Timeline timeline,
            @NonNull java.util.List<TextOverlayItem> belowTexts,
            @NonNull java.util.List<SpriteOverlayItem> belowSprites,
            int videoW, int videoH, long playheadMs) {
        if (videoW <= 0 || videoH <= 0) return null;
        java.util.Set<String> belowTextIds = new java.util.HashSet<>();
        for (TextOverlayItem tt : belowTexts) belowTextIds.add(tt.getId());
        java.util.Set<String> belowSpriteIds = new java.util.HashSet<>();
        for (SpriteOverlayItem ss : belowSprites) belowSpriteIds.add(ss.getId());
        java.util.List<LayerPreviewController.VisualItem> ordered = LayerPreviewController.orderedVisualItems(timeline);
        java.util.List<FxPreviewTextureView.Pip> out = new java.util.ArrayList<>();
        long totalDur = timeline.getTotalDurationMs();
        for (LayerPreviewController.VisualItem v : ordered) {
            TextOverlayItem tto = v.item.getTextOverlay();
            if (tto != null && belowTextIds.contains(tto.getId()) && tto.isVisibleAt(playheadMs)) {
                if (!OverlayTextureCache.canUseTexture(tto)) continue;
                float alpha = tto.animatedOpacity(playheadMs);
                if (alpha < 0.005f) continue;
                android.graphics.Bitmap tex = overlayTextureCache.getOrCreate(tto, playheadMs, totalDur, videoW, videoH);
                if (tex == null || tex.isRecycled()) continue;
                // Quad geometry: raster at authored size * supersample, but halfW/H based on non-supersampled authored size scaled by animated factor
                float authored = tto.getSizeFraction();
                float animated = tto.animatedSizeFraction(playheadMs);
                float scale = authored > 0.001f ? animated / authored : 1f;
                float fontPxAuth = Math.max(1f, authored * videoH);
                String shown = TextBoxRenderer.textAt(tto, playheadMs, totalDur);
                float[] szAuth = new float[2];
                TextBoxRenderer.measure(tto, shown, fontPxAuth, szAuth);
                float boxW = szAuth[0] * scale;
                float boxH = szAuth[1] * scale;
                float halfW = (boxW / videoW) / 2f;
                float halfH = (boxH / videoH) / 2f;
                float cx = tto.animatedCenterX(playheadMs);
                // Top-down model -> bottom-up Pip, exactly as pipForModel does for images.
                float cy = 1f - tto.animatedCenterY(playheadMs);
                float rot = -tto.animatedRotation(playheadMs);
                // Use Pip's still path — texture keyed by item id, uploaded in FxPreviewTextureView's overlay map
                // Pip extras false, no FX, blend 0, no mask
                FxPreviewTextureView.Pip pip = FxPreviewTextureView.Pip.ofImage(
                        cx, cy, halfW, halfH, rot, alpha,
                        null, playheadMs, null, 0f, videoW, videoH, tto.getId(), tex, 1f);
                out.add(pip);
            }
            SpriteOverlayItem sso = v.item.getSprite();
            if (sso != null && belowSpriteIds.contains(sso.getId()) && sso.isVisibleAt(playheadMs)) {
                // A sprite the composite already owns must not ALSO be emitted here — it would
                // be drawn twice, the second time without its warp (ZA lane, finding 3).
                if (sso.wantsGl()) continue;
                if (!OverlayTextureCache.canUseTexture(sso)) continue;
                float alpha = sso.animatedOpacity(playheadMs);
                if (alpha < 0.005f) continue;
                // THE REAL CELLS, exactly as the GL path above takes them. This branch still went
                // through OverlayTextureCache, whose rasterizeSprite fills a solid blue rectangle
                // — so a PLAIN sprite sitting below a blend previewed as a blue box while the
                // export drew the artwork. Same bug the ZA lane caught on the GL path, left behind
                // on this one because only the GL path was rerouted.
                android.graphics.Bitmap tex = host.spriteRasterFor(sso, videoW, videoH);
                if (tex == null || tex.isRecycled() || tex.getHeight() <= 0) continue;
                float authored = sso.getSizeFraction();
                float animated = sso.animatedSizeFraction(playheadMs);
                float scale = authored > 0.001f ? animated / authored : 1f;
                float hAuth = authored * videoH;
                // The cell's OWN aspect, not a square. The placeholder was square because a blue
                // rectangle has no aspect to respect; real pixels do, and a square box would
                // stretch every non-square sprite on this path.
                float wAuth = hAuth * (tex.getWidth() / (float) tex.getHeight());
                float boxW = wAuth * scale;
                float boxH = hAuth * scale;
                float halfW = (boxW / videoW) / 2f;
                float halfH = (boxH / videoH) / 2f;
                float cx = sso.animatedCenterX(playheadMs);
                // Top-down model -> bottom-up Pip, exactly as pipForModel does for images.
                float cy = 1f - sso.animatedCenterY(playheadMs);
                float rot = -sso.animatedRotation(playheadMs);
                FxPreviewTextureView.Pip pip = FxPreviewTextureView.Pip.ofImage(
                        cx, cy, halfW, halfH, rot, alpha,
                        null, playheadMs, null, 0f, videoW, videoH, sso.getId(), tex, 1f);
                out.add(pip);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Build a full-frame bitmap containing text/sprite overlays that sit below a blending GL image.
     * Static and pixel-changing fallback content is rasterised here at video resolution; pose-animated
     * content that rides the texture path is handled by {@link #buildBelowBlendOverlays} and is NOT
     * included here (one predicate, one place).
     */
    @Nullable
    private android.graphics.Bitmap buildBelowBlendBitmap(
            @NonNull Timeline timeline,
            @NonNull java.util.List<TextOverlayItem> belowTexts,
            @NonNull java.util.List<SpriteOverlayItem> belowSprites,
            int videoW, int videoH, long playheadMs) {
        if (videoW <= 0 || videoH <= 0) return null;
        java.util.Set<String> belowTextIds = new java.util.HashSet<>();
        for (TextOverlayItem tt : belowTexts) belowTextIds.add(tt.getId());
        java.util.Set<String> belowSpriteIds = new java.util.HashSet<>();
        for (SpriteOverlayItem ss : belowSprites) belowSpriteIds.add(ss.getId());
        java.util.List<LayerPreviewController.VisualItem> ordered = LayerPreviewController.orderedVisualItems(timeline);
        java.util.Set<String> visibleIds = new java.util.HashSet<>();
        java.util.List<TextOverlayItem> visTexts = new java.util.ArrayList<>();
        java.util.List<SpriteOverlayItem> visSprites = new java.util.ArrayList<>();
        for (LayerPreviewController.VisualItem v : ordered) {
            TextOverlayItem tto = v.item.getTextOverlay();
            if (tto != null && belowTextIds.contains(tto.getId()) && tto.isVisibleAt(playheadMs)) {
                // One predicate, one place — pose-animated that rides texture path stays out of the full-frame bitmap
                if (OverlayTextureCache.canUseTexture(tto)) continue;
                visTexts.add(tto);
                visibleIds.add(tto.getId());
            }
            SpriteOverlayItem sso = v.item.getSprite();
            if (sso != null && belowSpriteIds.contains(sso.getId()) && sso.isVisibleAt(playheadMs)) {
                if (OverlayTextureCache.canUseTexture(sso)) continue;
                visSprites.add(sso);
                visibleIds.add(sso.getId());
            }
        }
        if (visTexts.isEmpty() && visSprites.isEmpty()) return null;
        String sig = buildBelowBlendSignature(visTexts, visSprites, videoW, videoH, playheadMs, timeline.getTotalDurationMs());
        if (cachedBelowBitmap != null && !cachedBelowBitmap.isRecycled()
                && cachedBelowW == videoW && cachedBelowH == videoH
                && sig.equals(cachedBelowSignature)) {
            return cachedBelowBitmap;
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(videoW, videoH, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        for (LayerPreviewController.VisualItem v : ordered) {
            TextOverlayItem tto = v.item.getTextOverlay();
            if (tto != null && belowTextIds.contains(tto.getId()) && tto.isVisibleAt(playheadMs)) {
                if (OverlayTextureCache.canUseTexture(tto)) continue;
                float alpha = tto.animatedOpacity(playheadMs);
                if (alpha < 0.005f) continue;
                String shown = TextBoxRenderer.textAt(tto, playheadMs, timeline.getTotalDurationMs());
                float sizeFrac = tto.animatedSizeFraction(playheadMs);
                float fontPx = Math.max(1f, sizeFrac * videoH);
                float[] sz = new float[2];
                TextBoxRenderer.measure(tto, shown, fontPx, sz);
                float cx = tto.animatedCenterX(playheadMs) * videoW;
                float cy = tto.animatedCenterY(playheadMs) * videoH;
                float rot = tto.animatedRotation(playheadMs);
                canvas.save();
                canvas.rotate(rot, cx, cy);
                TextBoxRenderer.draw(canvas, tto, shown,
                        cx - sz[0] / 2f, cy - sz[1] / 2f, fontPx, playheadMs,
                        timeline.getTotalDurationMs(), true, alpha);
                canvas.restore();
            }
            SpriteOverlayItem sso = v.item.getSprite();
            if (sso != null && belowSpriteIds.contains(sso.getId()) && sso.isVisibleAt(playheadMs)) {
                if (OverlayTextureCache.canUseTexture(sso)) continue;
                float alpha = sso.animatedOpacity(playheadMs);
                if (alpha < 0.005f) continue;
                float cx = sso.animatedCenterX(playheadMs) * videoW;
                float cy = sso.animatedCenterY(playheadMs) * videoH;
                float h = sso.animatedSizeFraction(playheadMs) * videoH;
                float w = h;
                android.graphics.Paint p2 = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                p2.setColor(android.graphics.Color.argb(Math.round(alpha * 255), 120, 180, 220));
                p2.setStyle(android.graphics.Paint.Style.FILL);
                canvas.save();
                canvas.rotate(sso.animatedRotation(playheadMs), cx, cy);
                if (sso.isFlipH() || sso.isFlipV()) {
                    canvas.scale(sso.isFlipH() ? -1f : 1f, sso.isFlipV() ? -1f : 1f, cx, cy);
                }
                canvas.drawRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, p2);
                canvas.restore();
            }
        }
        long cost = android.os.SystemClock.elapsedRealtime() - t0;
        belowBlendRasterCount++;
        cachedBelowBitmap = bmp;
        cachedBelowW = videoW;
        cachedBelowH = videoH;
        cachedBelowSignature = sig;
        FLog.d("GLBelowBlend", "rasterised below-blend " + videoW + "x" + videoH
                + " texts=" + visTexts.size() + " sprites=" + visSprites.size()
                + " cost=" + cost + "ms bytes=" + bmp.getByteCount()
                + " sigHash=" + sig.hashCode() + " rasterCount=" + belowBlendRasterCount);
        return bmp;
    }

    /** Captions at real z — each enabled binding is its own GL layer (SPEC_20260829_CAPTIONS_GL §3.1). */
    @Nullable
    private java.util.List<FxPreviewTextureView.Pip> buildCaptionOverlays(
            @NonNull Timeline timeline, int videoW, int videoH, long playheadMs) {
        if (videoW <= 0 || videoH <= 0) return null;
        Clip clip = clipAt(timeline, playheadMs);
        if (clip == null) return null;
        if (!CaptionTextureCache.canUseTexture(clip)) return null; // whole clip's captions fallback to Canvas
        java.util.List<Clip.CaptionBinding> bindings = clip.getCaptionBindings();
        if (bindings.isEmpty()) return null;
        // Find clip start to derive sourceMs
        long clipStart = clipStartMs(timeline, clip);
        long localMs = Math.max(0, playheadMs - clipStart);
        long clipLocalMs = (long)(localMs * clip.getSpeedMultiplier());
        long sourceMs = clip.getInPointMs() + clipLocalMs;
        java.util.List<FxPreviewTextureView.Pip> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < bindings.size() && i < Clip.MAX_CAPTION_BINDINGS; i++) {
            Clip.CaptionBinding b = bindings.get(i);
            if (!b.enabled) continue;
            String key = clip.getId() + "#" + i;
            if (seen.contains(key)) continue;
            seen.add(key);
            // Style keyframes: first binding's style animates via Clip.captionStyleKeyframes (export parity)
            Clip.CaptionBinding effective = b;
            if (i == 0 && clip.hasCaptionStyleKeyframes()) {
                String kfStyle = clip.captionStyleAtClipMs(clipLocalMs);
                if (kfStyle != null && !kfStyle.equals(b.styleId)) {
                    effective = b.copy();
                    effective.styleId = kfStyle;
                }
            }
            android.graphics.Bitmap tex = captionTextureCache.getOrCreate(clip, i, effective, sourceMs, videoW, videoH);
            if (tex == null || tex.isRecycled()) continue;
            // Quad geometry: tight bitmap -> halfW/H in NDC, placed at binding center
            float halfW = (tex.getWidth() / CaptionTextureCache.SUPERSAMPLE / videoW) / 2f;
            float halfH = (tex.getHeight() / CaptionTextureCache.SUPERSAMPLE / videoH) / 2f;
            float cx = effective.centerX;
            // centerY is TOP-DOWN (the caption drag handle and the export's renderer both read
            // it that way); the Pip shader's y is BOTTOM-UP, as image overlays already account
            // for (TextOverlayLayer.pipForModel passes 1 - cy). Passed raw, a caption placed near
            // the bottom previewed near the top and vice versa (JoyRaptor, 2026-09-24).
            float cy = 1f - effective.centerY;
            // Alpha 1 — captions have no per-clip opacity envelope yet
            FxPreviewTextureView.Pip pip = FxPreviewTextureView.Pip.ofImage(
                    cx, cy, halfW, halfH, 0f, 1f,
                    null, playheadMs, null, 0f, videoW, videoH, key, tex, 1f);
            out.add(pip);
        }
        // Audio captions: stay on Canvas fallback for this pass (documented — same "cheap first" sequencing as visualizer)
        // They would need their own cache key (AudioClip id + binding) and sourceMs mapping; visual parity already correct on Canvas.
        if (out.isEmpty()) return null;
        // Sort by z (binding order = track order bottom->top already, but ensure binding 0 below binding 1)
        // out is already in binding order (0 bottom, 2 top) which matches track z ascending for default.
        // For custom z, we would sort by trackFlags zIndex — not yet; keep binding order.
        captionRasterCount += out.size();
        if ((captionRasterCount % 30) == 0 || out.size() > 1) {
            FLog.d("CaptionTex", "caption overlays " + out.size() + " at " + playheadMs + "ms rasterCount=" + captionRasterCount);
        }
        return out;
    }

    @Nullable
    private static Clip clipAt(@NonNull Timeline tl, long playheadMs) {
        long cursor = 0;
        for (Clip c : tl.getClips()) {
            long dur = c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs();
            if (playheadMs >= cursor && playheadMs < cursor + dur) return c;
            cursor += dur;
        }
        return null;
    }

    private static long clipStartMs(@NonNull Timeline tl, @NonNull Clip target) {
        long cursor = 0;
        for (Clip c : tl.getClips()) {
            if (c == target) return cursor;
            cursor += c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs();
        }
        return 0;
    }

        private static boolean stacksOverlayVideos(@NonNull Timeline timeline) {
        if (FxPreviewTextureView.MAX_LIVE_PIPS < 2) return false;
        List<Clip> all = timeline.getOverlayClips();
        for (int i = 0; i < all.size(); i++) {
            Clip a = all.get(i);
            if (a.isImageClip() || a.isHiddenObject()) continue;
            long aStart = a.getOverlayStartMs();
            long aEnd = aStart + Math.max(0, a.getTrimmedDurationMs());
            for (int j = i + 1; j < all.size(); j++) {
                Clip b = all.get(j);
                if (b.isImageClip() || b.isHiddenObject()) continue;
                long bStart = b.getOverlayStartMs();
                long bEnd = bStart + Math.max(0, b.getTrimmedDurationMs());
                if (aStart < bEnd && bStart < aEnd) return true;
            }
        }
        return false;
    }

    /** @see #buildPlan — a cheap fingerprint of the last plan's shape, for change-only logging. */
    private int lastPlanShape = Integer.MIN_VALUE;
    /** @see #buildPlan — a null PiP is normal on the first frames; say it once, not per frame. */
    private boolean loggedNullPip;

    /**
     * The decoded picture's dimensions, rotation applied.
     *
     * <p>The chain runs at this size, so getting it wrong does not merely look wrong — it changes
     * what a blur radius means relative to the export. Falls back to 1080p rather than to the
     * view's size, because the view is letterboxed and its height would make the aspect wrong.</p>
     */
    /**
     * Last size the decoder actually reported, held across item transitions.
     *
     * <p>THE 1920x1080 FALLBACK IS THE SEAM SQUASH. {@code getVideoSize()} returns 0x0 for a
     * few frames while the decoder re-initialises for a new media item, and falling back to a
     * hard-coded LANDSCAPE 16:9 renders a portrait frame at the wrong aspect for exactly that
     * window: a 9:16 source fitted to 16:9 keeps 0.32 of its height — JoyRaptor, 2026-08-25, "the
     * clip squashes to a third of the height and then centered ... before it pops in at the
     * correct size."
     *
     * <p>It got dramatically worse on the Note 9 in the same session, which is the tell: B1
     * gave same-source slices distinct mediaIds so they would fire item transitions, and a cut
     * recording is nothing BUT same-source slices. Every seam became a transition, so every
     * seam became a squash. The glitch is older than B1; B1 only made it fire constantly.
     *
     * <p>A remembered size is right where a constant is wrong: the previous item's dimensions
     * are almost always the next item's too (same source), and when they genuinely differ the
     * real value lands a frame or two later anyway. The constant stays only as a cold-start
     * default, before any frame has ever been decoded.</p>
     */
    @Nullable private int[] lastReportedVideoSize;

    @NonNull
    private int[] videoSize() {
        ExoPlayer p = host.activeVideoPlayer();
        if (p == null) return lastReportedVideoSize != null
                ? lastReportedVideoSize : new int[]{1920, 1080};
        VideoSize vs = p.getVideoSize();
        if (vs.width <= 0 || vs.height <= 0) {
            return lastReportedVideoSize != null
                    ? lastReportedVideoSize : new int[]{1920, 1080};
        }
        // The UPRIGHT size: the renderer stages the frame already rotated, so everything
        // downstream — the FBOs, uTexel, uAspect, the fit rect — is in display orientation.
        boolean swap = vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270;
        int[] upright = swap ? new int[]{vs.height, vs.width} : new int[]{vs.width, vs.height};
        lastReportedVideoSize = upright;
        return upright;
    }

    /**
     * The composite frame: the decoded picture's pixels, boxed out to the CANVAS's aspect.
     *
     * <p><b>Why the canvas and not the video.</b> Every piece of geometry this controller hands
     * the renderer is a fraction of {@code computeCanvasRect()} — an image overlay's centre and
     * half-extents ({@code TextOverlayLayer.fxPipFor}), a PiP's ({@code
     * OverlayVideoPreviewView.pipFor}), a caption binding's centre, a mask shape's cx/cy/w/h.
     * Running the chain at the DECODED size made the renderer read those canvas fractions as
     * fractions of a letterboxed sub-rectangle of the canvas, so on any clip whose aspect is
     * not the canvas's, an object silently changed size on one axis and drifted toward the
     * frame's centre the moment it moved from its own View into this composite. Adding a mask
     * is exactly such a move ({@code TextOverlayItem.hasExportMask()} is part of {@code
     * wantsGlExport()}), which is why a mask "changed the apparent zoom" of an image.</p>
     *
     * <p><b>Native pixels, then capped.</b> The box CONTAINS the picture at 1:1 so nothing is
     * resampled in the common case, but a 16:9 clip on a 9:16 canvas would otherwise want a
     * 1920x3413 pair of FBOs (~52MB). The cap holds the frame's long side to the picture's own,
     * which costs a downscale on exactly the clips that were already being letterboxed on
     * screen anyway and keeps the allocation where it has always been.</p>
     *
     * <p><b>Identity when the aspects match</b> — which is every clip in an "original"-preset
     * project, i.e. most of them. Same frame, same FBOs, same uniforms, same viewport, so the
     * case where this bug is invisible today cannot regress.</p>
     */
    @NonNull
    private int[] compositeFrame(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return new int[]{vw, vh};
        float a = canvasAspect();
        if (a <= 0f) return new int[]{vw, vh};
        float v = (float) vw / (float) vh;
        // A hair of tolerance: the canvas rect is measured in whole view pixels, so an exact
        // 9:16 canvas reports 0.5625 only to within a pixel. Below this the box would differ
        // from the picture by less than one pixel and re-staging it would cost a resample for
        // nothing.
        if (Math.abs(v - a) <= 0.002f * Math.max(1f, a)) return new int[]{vw, vh};
        int cw, ch;
        if (v > a) {
            cw = vw;
            ch = Math.max(2, Math.round(vw / a));
        } else {
            ch = vh;
            cw = Math.max(2, Math.round(vh * a));
        }
        // THE FRAME MUST NEVER BE SIZED BY A TINY SOURCE.
        //
        // For an IMAGE master clip the caller passes the STILL's pixel size, and the gap spacer
        // this app generates for empty timeline regions is a 16x16 PNG
        // (FaditorEditorActivity.ensureBlackSpacerUri / ExportManager ~1770). Capping the boxed
        // frame at the source's long side therefore collapsed the ENTIRE composite to 9x16 px
        // over any gap clip, and that 9x16 buffer was then blown up ~120x onto a 1080-wide
        // preview. Everything drawn in the composite — adjustment layers, masks, image overlays,
        // captions — turned to mush. JoyRaptor, 2026-09-02: "I made an adjustment layer to put a
        // solid colour on it, then masked it into a shape, and it's COMPLETELY blurry... on an
        // adjustment layer there's no zoom, it's just a solid shape." His mask is 0.18 x 0.04 of
        // the frame, i.e. 1.6 x 0.6 pixels at 9x16.
        //
        // The composite is what the user SEES, so its resolution floor is the canvas's own
        // on-screen size, not whatever the base picture happens to be. Real footage still governs
        // when it is larger (never upscale a source past its own detail), and a ceiling keeps a
        // 4K source from allocating an absurd FBO for a phone preview.
        int canvasLong = canvasLongSidePx();
        int targetLong = Math.max(Math.max(vw, vh), canvasLong);
        if (targetLong > MAX_COMPOSITE_EDGE) targetLong = MAX_COMPOSITE_EDGE;
        int longSide = Math.max(cw, ch);
        if (longSide != targetLong && longSide > 0) {
            // Derive from the aspect at the target size rather than scaling the boxed pair, so
            // rounding at tiny sizes cannot drift the aspect (16x28 scaled up lands off-canvas).
            if (a >= 1f) { cw = targetLong; ch = Math.max(2, Math.round(targetLong / a)); }
            else { ch = targetLong; cw = Math.max(2, Math.round(targetLong * a)); }
        }
        return new int[]{cw, ch};
    }

    /**
     * The OUTPUT CANVAS's aspect, or -1 when the host has none.
     *
     * <p>Read off the overlay-video layer's callback rather than plumbed through a new {@link
     * Host} method, because that callback IS {@code computeCanvasRect()} and it already carries
     * the fallback this needs: when the project's canvas aspect cannot be resolved the activity
     * hands back the VIDEO CONTENT rect instead, whose aspect is the video's — so this returns
     * the video's aspect, {@link #compositeFrame} returns identity, and the renderer behaves
     * exactly as it did before. One authority for "where is the canvas", not two.</p>
     */
    /** Ceiling on the composite's long edge — a phone preview never needs more. */
    private static final int MAX_COMPOSITE_EDGE = 2160;

    /**
     * The canvas's longer edge in DEVICE PIXELS, or 0 when the host has none. This is the
     * resolution the composite is actually displayed at, and therefore the floor its own
     * resolution must not fall below. See {@link #compositeFrame}.
     */
    private int canvasLongSidePx() {
        OverlayVideoPreviewView ov = host.overlayVideoLayer();
        android.graphics.RectF r = ov == null ? null : ov.canvasRect();
        if (r == null || r.width() <= 0f || r.height() <= 0f) return 0;
        return Math.round(Math.max(r.width(), r.height()));
    }

    private float canvasAspect() {
        OverlayVideoPreviewView ov = host.overlayVideoLayer();
        android.graphics.RectF r = ov == null ? null : ov.canvasRect();
        if (r == null || r.width() <= 0f || r.height() <= 0f) return -1f;
        return r.width() / r.height();
    }

    /** @see FxPreviewTextureView#setVideoRotation */
    private int rotationDegrees() {
        ExoPlayer p = host.activeVideoPlayer();
        return p == null ? 0 : p.getVideoSize().unappliedRotationDegrees;
    }

    /** Attach the decoder to the GL view, if it is not already there. */
    private void route() {
        Surface s = inputSurface;
        if (s == null) return;
        ExoPlayer p = host.activeVideoPlayer();
        if (p == null) return;
        if (routed && p == routedPlayer) return;
        try {
            p.setVideoSurface(s);
            routed = true;
            routedPlayer = p;
            FLog.i(TAG, "live FX preview engaged");
        } catch (RuntimeException e) {
            FLog.w(TAG, "could not route video to the FX preview: " + e);
        }
    }

    /**
     * Give the video back and hide the GL view. Idempotent — the common case is a project with no
     * adjustment layers at all, where this runs on every tick and must cost nothing.
     */
    public void stop() {
        if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
        // BEFORE the routed early-return: a project with an image clip and no live FX never
        // routes, and leaving the ImageView hidden there would blank the picture entirely.
        setBaseStillRouted(false);
        view.setBaseStill(null);
        if (layerOverlayRouted) {
            layerOverlayRouted = false;
            host.onLayerImageOverlayRouted(false);
        }
        view.setLayerOverlayBitmap(null);
        view.setBelowBlendBitmap(null);
        view.setBelowBlendOverlays(null);
        view.setCaptionOverlays(null);
        cachedBelowBitmap = null;
        cachedBelowSignature = null;
        overlayTextureCache.clear();
        captionTextureCache.clear();
        // Same reason, for image OVERLAYS: this chain is no longer drawing them, so their own
        // views have to come back. Cheap to repeat — the layer ignores an unchanged set.
        host.onGlOwnedImages(java.util.Collections.emptySet());
        host.onCaptionGlOwned(java.util.Collections.emptySet());
        if (!routed) return;
        routed = false;
        routedPlayer = null;
        view.setCompositePlan(null);
        view.setGrade(null);
        OverlayVideoPreviewView ov = host.overlayVideoLayer();
        if (ov != null) ov.setFxCompositeSurface(null);   // give the PiP its own surface back
        host.restoreVideoOutput();
        FLog.i(TAG, "live FX preview released");
    }
}
