package com.fadcam.ui.faditor.compositor;

import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionItemAdapters;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;

/** The isolated picture source and placement rules shared by preview and export. */
public final class RigidPictureMotion {
    private final TextOverlayItem item;
    private final MotionBreaks fences;
    private final long pieceStartUs;
    private final long pieceEndUs;
    private final int width;
    private final int height;
    private final ItemMotionSampler sampler;

    /** The caller supplies an item owned by its renderer or a detached preview snapshot. */
    public RigidPictureMotion(TextOverlayItem item, float aspect, int width, int height,
                              long pieceStartUs, long pieceEndUs) {
        this.item = item;
        this.width = width;
        this.height = height;
        this.pieceStartUs = pieceStartUs;
        this.pieceEndUs = pieceEndUs;
        sampler = MotionItemAdapters.textOrImage(item, width, height, height * aspect, height);
        fences = itemFences(item, pieceStartUs, pieceEndUs);
    }

    public static boolean supports(TextOverlayItem item) {
        return item.isImage() && item.isAnimated() && !item.isLinked() && !item.hasCornerPin()
                && item.isRotationPivotNeutral(null) && !item.wantsGlExport()
                && CaptionAnimator.parsePreset(item.getTextAnimPreset()) == CaptionAnimator.Preset.NONE;
    }

    public static MotionBreaks itemFences(TextOverlayItem item, long pieceStartUs, long pieceEndUs) {
        long startUs = item.getStartMs() * 1000L;
        MotionBreaks.Builder builder = new MotionBreaks.Builder()
                .add(pieceStartUs).add(pieceEndUs).add(startUs);
        if (item.getEndMs() != Long.MAX_VALUE) {
            // Visibility includes the last whole millisecond.
            builder.add((item.getEndMs() + 1L) * 1000L);
        }
        for (var track : item.getKeyframes().tracks()) builder.addSteps(track, startUs);
        return builder.build();
    }

    @Nullable
    RigidTextureShutterPass.Exposure exposure(long timeUs, long periodUs, int targetWidth, int targetHeight) {
        return width == targetWidth && height == targetHeight ? exposure(timeUs, periodUs) : null;
    }

    @Nullable
    public RigidTextureShutterPass.Exposure exposure(long timeUs, long periodUs) {
        if (periodUs <= 0 || timeUs < pieceStartUs || timeUs >= pieceEndUs) return null;
        var plan = RigidTextureShutterPass.plan(item.getRigidMotionShutter(), sampler,
                timeUs, periodUs, fences, width, height);
        if (plan == null) return null;
        return new RigidTextureShutterPass.Exposure(plan, Math.max(0f, Math.min(1f, item.animatedOpacity(timeUs / 1000L))));
    }
}
