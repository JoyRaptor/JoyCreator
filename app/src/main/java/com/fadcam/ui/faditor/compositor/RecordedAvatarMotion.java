package com.fadcam.ui.faditor.compositor;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;

import com.fadcam.ui.faditor.avatar.AvatarItemPuppet;
import com.fadcam.ui.faditor.avatar.AvatarParamTrack;
import com.fadcam.ui.faditor.avatar.AvatarRig;
import com.fadcam.ui.faditor.avatar.AvatarShutterSchedule;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

import java.util.HashMap;
import java.util.Map;

/** Shared admission, source fences, and placement opacity for recorded avatar blur. */
public final class RecordedAvatarMotion {
    private RecordedAvatarMotion() {}

    public static boolean canStageNativeVideo(@Nullable Format format) {
        return format != null && format.width > 0 && format.height > 0
                && format.rotationDegrees == 0 && format.pixelWidthHeightRatio == 1f
                && !ColorInfo.isTransferHdr(format.colorInfo);
    }

    public static boolean supports(SpriteOverlayItem item) {
        var take = item.getAvatarTrack();
        var controls = item.getRigidMotionShutter();
        return item.hasAvatarPerformance() && take != null && take.size() >= 2
                && take.timeMsAt(0) >= 0 && item.getStartMs() >= 0
                && item.getStartMs() <= Long.MAX_VALUE / 1000L
                && take.durationMs() <= Long.MAX_VALUE / 1000L - item.getStartMs()
                && !item.isHidden() && !item.isLinked() && !item.wantsGl()
                && item.getFadeInMs() == 0 && item.getFadeOutMs() == 0
                && controls.angle() > 0 && controls.mixPercent() > 0;
    }

    public static boolean supportsRig(@Nullable AvatarRig rig) {
        return rig != null && !rig.getParts().isEmpty()
                && rig.getParts().stream().noneMatch(part -> part.dangle);
    }

    /** A stable asset snapshot handed from the editor to the owner of the preview GL context. */
    public static final class Source {
        private final AvatarRig rig;
        private final AvatarParamTrack take;
        private final Map<String, SpriteSheet> sheets;
        private final float aspect;
        private final MotionBreaks takeBreaks;

        public Source(AvatarRig rig, AvatarParamTrack take, Map<String, SpriteSheet> sheets,
                      float aspect, long itemStartUs) {
            this.rig = AvatarRig.fromJson(rig.toJson());
            this.take = take.copy();
            var art = new HashMap<String, SpriteSheet>();
            sheets.forEach((id, sheet) -> art.put(id, SpriteSheet.fromJson(sheet.toJson())));
            this.sheets = art;
            this.aspect = aspect;
            var builder = new MotionBreaks.Builder();
            this.take.addShutterBreaks(builder, itemStartUs);
            takeBreaks = builder.build();
        }

        public float aspect() { return aspect; }

        public MotionBreaks takeBreaks() { return takeBreaks; }

        public PreviewReplay open(Context context, int decodeSize) {
            return new PreviewReplay(context, this, decodeSize);
        }
    }

    /** Owns the preview-side decoders and offscreen rig on the GL thread. */
    public static final class PreviewReplay {
        private final Map<String, SpriteSheetRenderer> renderers = new HashMap<>();
        private final AvatarItemPuppet puppet;
        private final AvatarParamTrack take;

        private PreviewReplay(Context context, Source source, int decodeSize) {
            take = source.take;
            puppet = new AvatarItemPuppet(context, source.rig,
                    id -> renderers.computeIfAbsent(id, key -> {
                        var sheet = source.sheets.get(key);
                        return sheet == null ? null : SpriteSheetRenderer.load(context, sheet, decodeSize);
                    }), source.sheets::get);
        }

        @Nullable public AvatarItemPuppet.Exposure begin(long firstUs, long lastUs) {
            return puppet.beginExposure(take, firstUs, lastUs);
        }

        public void release() {
            try {
                puppet.release();
            } finally {
                for (var renderer : renderers.values()) renderer.recycle();
                renderers.clear();
            }
        }
    }

    /**
     * The initial route has a single avatar over ordinary video. Adding other kinds of visual
     * layers requires proving their relative order on both rendering paths.
     */
    public static boolean ordinaryAvatarRun(Timeline timeline) {
        if (timeline.getClipCount() != 1 || timeline.hasTransitions()
                || !timeline.getWaveformOverlays().isEmpty()) return false;
        var base = timeline.getClip(0);
        if (base == null || base.isImageClip() || base.hasLoopExtension()
                || base.hasRemovedSpans() || base.getSpeedMultiplier() != 1f
                || com.fadcam.ui.faditor.model.TimeMap.of(timeline).cuts().count() != 0) return false;
        boolean found = false;
        for (var visual : LayerPreviewController.orderedVisualItems(timeline)) {
            var item = visual.item.getSprite();
            if (found || item == null || !item.isRigidMotionBlurEnabled() || !supports(item)) return false;
            found = true;
        }
        return found;
    }

    public static long itemEndUs(SpriteOverlayItem item) {
        long end = item.getEndMs(); // Visible at the entire final millisecond.
        return end >= Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : (end + 1L) * 1000L;
    }

    public static MotionBreaks fences(SpriteOverlayItem item) {
        var builder = new MotionBreaks.Builder();
        long itemStartUs = item.getStartMs() * 1000L;
        addPlacementFences(builder, item, itemStartUs);
        var take = item.getAvatarTrack();
        if (take != null) take.addShutterBreaks(builder, itemStartUs);
        return builder.build();
    }

    static MotionBreaks placementFences(SpriteOverlayItem item) {
        var builder = new MotionBreaks.Builder();
        addPlacementFences(builder, item, item.getStartMs() * 1000L);
        return builder.build();
    }

    private static void addPlacementFences(MotionBreaks.Builder builder, SpriteOverlayItem item,
                                            long startUs) {
        for (var track : item.getKeyframes().tracks()) builder.addSteps(track, startUs);
    }

    /** Copy just the live placement, avoiding a per-frame copy of the recorded performance. */
    static SpriteOverlayItem snapshotPlacement(SpriteOverlayItem item) {
        var copy = new SpriteOverlayItem(item.getId(), item.getSheetId());
        copy.setTimeRange(item.getStartMs(), item.getEndMs());
        copy.setCenter(item.getCenterX(), item.getCenterY());
        copy.setSizeFraction(item.getSizeFraction());
        copy.setRotationDeg(item.getRotationDeg());
        copy.setOpacity(item.getOpacity());
        copy.setFlipH(item.isFlipH());
        copy.setFlipV(item.isFlipV());
        copy.setRigidMotionShutter(item.getRigidMotionShutter());
        copy.getKeyframes().copyFrom(item.getKeyframes());
        return copy;
    }

    @Nullable
    public static MotionShutter.Schedule plan(SpriteOverlayItem item, long editorUs, long periodUs,
                                               long pieceStartUs, long pieceEndUs, MotionBreaks fences) {
        return AvatarShutterSchedule.plan(item.getRigidMotionShutter(), editorUs, periodUs,
                item.getStartMs() * 1000L, itemEndUs(item), pieceStartUs, pieceEndUs, fences);
    }

    public static float opacity(SpriteOverlayItem item, long editorUs) {
        double localMs = Math.max(0.0, editorUs / 1000.0 - item.getStartMs());
        float alpha = item.getKeyframes().valueAt(KeyframeSet.OPACITY, localMs, item.getOpacity());
        return Math.max(0f, Math.min(1f, alpha));
    }
}
