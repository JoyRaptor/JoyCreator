package com.fadcam.ui.faditor.compositor;

import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionTimelineBreaks;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;
import com.fadcam.ui.faditor.sprite.SpriteFrameResolver;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

/** Rigid sprite eligibility and the timing of the currently visible cell. */
public final class RigidSpriteMotion {
    private RigidSpriteMotion() {}

    public static boolean supports(SpriteOverlayItem item) {
        return item.getKeyframes().isAnimated() && !item.isLinked()
                && !item.hasAvatarPerformance() && !item.wantsGl();
    }

    public static MotionBreaks fences(SpriteOverlayItem item, long startUs, long endUs) {
        var builder = new MotionBreaks.Builder().add(startUs).add(endUs);
        long itemStartUs = item.getStartMs() * 1000L;
        for (var track : item.getKeyframes().tracks()) builder.addSteps(track, itemStartUs);
        return builder.build();
    }

    public static int supportedCell(SpriteSheet sheet, SpriteOverlayItem item, long timeMs) {
        int cell = SpriteFrameResolver.resolveCellAt(sheet, item, timeMs);
        if (cell == SpriteFrameResolver.NO_CELL) return cell;
        var alignment = sheet.cellTransform(cell);
        // Aligned art can extend beyond the cell texture.
        return alignment == null || alignment.isIdentity() ? cell : SpriteFrameResolver.NO_CELL;
    }

    public static float opacity(SpriteOverlayItem item, long timeMs) {
        return Math.round(Math.max(0f, Math.min(1f, item.animatedOpacity(timeMs))) * 255) / 255f;
    }

    @Nullable
    public static RigidShutterPlan plan(SpriteOverlayItem item, SpriteSheet sheet,
                                        ItemMotionSampler sampler, MotionBreaks fences,
                                        long timeUs, long periodUs, int width, int height) {
        var controls = item.getRigidMotionShutter();
        var cellBreaks = new MotionBreaks.Builder();
        long lookBackUs = (long) Math.floor(Math.min(0.0, controls.openOffsetUs(periodUs)));
        long lookAheadUs = (long) Math.ceil(Math.max(0.0, controls.closeOffsetUs(periodUs)));
        MotionTimelineBreaks.addSprite(cellBreaks, sheet, item, timeUs + lookBackUs, timeUs + lookAheadUs);
        return RigidTextureShutterPass.plan(controls, sampler, timeUs, periodUs, fences,
                cellBreaks.build(), width, height);
    }
}
