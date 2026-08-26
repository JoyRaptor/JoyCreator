package com.fadcam.ui.faditor.compositor;

import android.view.Surface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

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
        @Nullable default FxPreviewTextureView.Pip imagePipFor(
                @NonNull com.fadcam.ui.faditor.model.TextOverlayItem o, int frameW, int frameH) {
            return null;
        }

        /**
         * The image overlays the composite is drawing this tick, so the host can make their own
         * views transparent. Told EVERY tick — an item leaves the set the moment it stops being
         * visible or the chain stops running, and a stale set would leave a picture invisible.
         */
        default void onGlOwnedImages(@NonNull java.util.Set<String> ids) { }

        /**
         * The effect stack would not compile on this GPU, so the preview is ungraded. Say so —
         * see {@code FxPreviewTextureView.SurfaceListener#onFxShaderUnavailable} for why a log
         * line was not good enough. Already on the main thread, fired once per failing stack.
         */
        default void onFxShaderUnavailable(@NonNull String reason) { }

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
        if (s == null || !s.isActive()) return null;

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
                plainImagesBelowBlend(timeline, glImages);
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
        if (!anyRenders && g == null && !objectFx && !stacked && glImages.isEmpty()
                && maskedImages.isEmpty() && belowBlendImages.isEmpty() && !anyCrop) {
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
        int[] size = still != null
                ? new int[]{still.getWidth(), still.getHeight()}
                : videoSize();
        view.setVideoSize(size[0], size[1]);
        view.setVideoRotation(still != null ? 0 : rotationDegrees());
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
        // The BELOW-bucket plain images must enter the chain BEFORE the PiPs: the export
        // inserts its below-bucket overlay pass ahead of the PiP blend block
        // (ExportManager.assembleClipVideoEffects), so a Screen-blend PiP composites AGAINST
        // them. Left as sibling views — which sit underneath this GL surface — the blend had
        // nothing under it and JoyRaptor saw "Screen" work over the spine but not over the images
        // below (SPEC_20260825 §2.3). Above-bucket plain images stay appended after the walk,
        // where the export's final canvas pass paints them.
        java.util.Set<String> belowPlainImageIds = new java.util.HashSet<>();
        for (LayerPreviewController.VisualItem v
                : LayerPreviewController.partitionAroundVideo(timeline).get(0)) {
            com.fadcam.ui.faditor.model.TextOverlayItem o = v.item.getTextOverlay();
            if (o != null && o.isImage() && !o.wantsGlExport()) belowPlainImageIds.add(o.getId());
        }
        // Plain images that must composite in GL because a blending image above needs a GL
        // background — see plainImagesBelowBlend. This is the general fix for §3A.2: the
        // question is not "do I want GL for myself?" but "does something above me need me
        // in GL to composite against?". Images are handled here; text/sprites remain on
        // Canvas and are documented as a gap (no preview rasterizer, see report).
        java.util.Set<String> belowBlendIds = new java.util.HashSet<>();
        for (com.fadcam.ui.faditor.model.TextOverlayItem b : plainImagesBelowBlend(timeline, glImages)) {
            belowBlendIds.add(b.getId());
        }
        java.util.Set<String> owned = new java.util.HashSet<>();
        // Plain/masked image overlays whose lane sits ABOVE the PiP plane: appended after the
        // walk, where the export's final canvas pass paints them.
        List<com.fadcam.ui.faditor.model.TextOverlayItem> deferredImages = new ArrayList<>();
        for (LayerPreviewController.VisualItem v
                : LayerPreviewController.orderedCompositedItems(timeline)) {
            Clip vc = v.item.getClip();
            if (vc != null && vc.isOverlayClip()) {
                FxPreviewTextureView.Pip p = offer ? ov.fxPipFor(vc) : null;
                if (p != null) { rungs.add(FxPreviewTextureView.Rung.pip(p)); pipRungs++; }
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
                // A PLAIN image overlay (masked ones included) resolved to where its LANE
                // puts it. Below the PiP plane it rides HERE — beneath every PiP rung still
                // to come, exactly as the export's below-pass is; above it, after the walk.
                // Its view is hidden through onGlOwnedImages once it actually rides the chain.
                com.fadcam.ui.faditor.model.TextOverlayItem o = v.item.getTextOverlay();
                if (o != null && o.isImage() && !o.wantsGlExport()) {
                    if (belowPlainImageIds.contains(o.getId())
                            || belowBlendIds.contains(o.getId())
                            || hasActiveMask(o)) {
                        FxPreviewTextureView.Pip p = host.imagePipFor(o, size[0], size[1]);
                        if (p != null) {
                            rungs.add(FxPreviewTextureView.Rung.pip(p));
                            owned.add(o.getId());
                        }
                    } else {
                        deferredImages.add(o);
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
        for (com.fadcam.ui.faditor.model.TextOverlayItem o : glImages) {
            FxPreviewTextureView.Pip p = host.imagePipFor(o, size[0], size[1]);
            if (p == null) continue;   // not decoded yet: absent until ready, as a still PiP is
            rungs.add(FxPreviewTextureView.Rung.pip(p));
            glOwned.add(o.getId());
        }
        // Then the above-bucket plain/masked images, after the effected ones — the order the
        // export paints them in (ImageBlendGlEffects first, the canvas overlay pass last).
        for (com.fadcam.ui.faditor.model.TextOverlayItem o : deferredImages) {
            FxPreviewTextureView.Pip p = host.imagePipFor(o, size[0], size[1]);
            if (p == null) continue;
            rungs.add(FxPreviewTextureView.Rung.pip(p));
            glOwned.add(o.getId());
        }
        owned.addAll(glOwned);
        // Told every tick, INCLUDING when the set is empty — see Host#onGlOwnedImages.
        host.onGlOwnedImages(owned);

        int shape = (rungs.size() * 31 + pipRungs) * 31 + snapshot.size();
        if (offer) shape = ~shape;
        if (shape != lastPlanShape) {
            lastPlanShape = shape;
            FLog.d("FxMultiPip", "plan: rungs=" + rungs.size() + " pips=" + pipRungs
                    + " layers=" + snapshot.size() + " offer=" + offer);
        }
        return new FxPreviewTextureView.CompositePlan(snapshot, rungs);
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

    /** True when the item's CompositingSpec carries at least one mask shape. */
    private static boolean hasActiveMask(@NonNull com.fadcam.ui.faditor.model.TextOverlayItem o) {
        com.fadcam.ui.faditor.model.CompositingSpec cs = o.getCompositing();
        return cs != null && !cs.masks.isEmpty();
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
     * Every plain (NORMAL, unmasked, no-FX, no-key) IMAGE that sits below a blending
     * GL image in z. Those plain images are stranded on Canvas while the blend above
     * lives in GL, so the blend composites against video instead of the image below
     * (§3A.2). The fix is to promote them to GL at their lane z, exactly where export's
     * below-pass composites them. Text/sprites need the same promotion but have no
     * preview rasterizer — that gap is named explicitly in buildPlan and in the report,
     * not hidden by special-casing images only.
     */
    @NonNull
    private static List<com.fadcam.ui.faditor.model.TextOverlayItem> plainImagesBelowBlend(
            @NonNull Timeline timeline,
            @NonNull List<com.fadcam.ui.faditor.model.TextOverlayItem> glImages) {
        if (glImages.isEmpty()) return java.util.Collections.emptyList();
        // Highest z among blending images — any plain below this is below at least one blend.
        List<LayerPreviewController.VisualItem> ordered =
                LayerPreviewController.orderedVisualItems(timeline);
        java.util.Map<String, Integer> idxById = new java.util.HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            com.fadcam.ui.faditor.model.TextOverlayItem o =
                    ordered.get(i).item.getTextOverlay();
            if (o != null && o.isImage()) idxById.put(o.getId(), i);
        }
        // THE TRIGGER IS "WENT TO GL", NOT "BLENDS". Anything that leaves the Canvas path is
        // composited in a different pass, so a plain image left behind on Canvas lands at the
        // wrong depth relative to it — the reason for promoting is the SPLIT between passes,
        // and blending is only one of four ways to cause that split (see wantsGlExport).
        //
        // Filtering on wantsExportBlend() here is what broke z the moment masks started
        // routing to GL: a masked image on NORMAL blend went to GL, counted for nothing, and
        // the plain image beneath it stayed on Canvas. JoyRaptor, 2026-08-25, testing exactly that
        // pair on the Note 9: "the mask WORKED on normal, HOWEVER it moved its z-depth so that
        // even though it was on top the top img rendered AS IF it was under the second IMG …
        // when i added a mask to the second img it then properly ordered (because they both
        // were rendering the same)." Two masked images agreed because both were in GL; one of
        // each did not, because they were in different passes.
        //
        // glImages is already the GL-routed set, so every member is a reason to promote what
        // sits below it. This is what §3A.2 asked for — the general question, not the blend
        // special case.
        int maxBlendIdx = -1;
        for (com.fadcam.ui.faditor.model.TextOverlayItem g : glImages) {
            Integer idx = idxById.get(g.getId());
            if (idx != null && idx > maxBlendIdx) maxBlendIdx = idx;
        }
        if (maxBlendIdx <= 0) return java.util.Collections.emptyList();
        List<com.fadcam.ui.faditor.model.TextOverlayItem> out = new ArrayList<>();
        for (int i = 0; i < maxBlendIdx; i++) {
            com.fadcam.ui.faditor.model.TextOverlayItem o =
                    ordered.get(i).item.getTextOverlay();
            if (o == null || !o.isImage() || o.wantsGlExport()) continue;
            out.add(o);
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
        // Same reason, for image OVERLAYS: this chain is no longer drawing them, so their own
        // views have to come back. Cheap to repeat — the layer ignores an unchanged set.
        host.onGlOwnedImages(java.util.Collections.emptySet());
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
