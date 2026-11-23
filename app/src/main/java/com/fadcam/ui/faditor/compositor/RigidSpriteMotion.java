package com.fadcam.ui.faditor.compositor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionTimelineBreaks;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;
import com.fadcam.ui.faditor.sprite.SpriteFrameResolver;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

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

    public record CellWindow(int cell, long startUs, long endUs) {}

    /** Exact holding interval for authored cells. Preset sequences use their regular route. */
    @Nullable
    public static CellWindow directCellWindow(SpriteSheet sheet, SpriteOverlayItem item, long timeMs) {
        int cell = supportedCell(sheet, item, timeMs);
        if (cell == SpriteFrameResolver.NO_CELL) return null;
        long itemStart = item.getStartMs();
        long start = itemStart;
        long end = item.getEndMs();
        int previous = SpriteFrameResolver.resolveCellAt(sheet, item, start);
        for (var key : item.getFrameTrack().keys()) {
            if (key.presetId != null) return null;
            long edge = itemStart + key.timeMs;
            if (edge >= end) break;
            int next = SpriteFrameResolver.resolveCellAt(sheet, item, edge);
            if (next == previous) continue;
            if (edge > timeMs) {
                end = edge;
                break;
            }
            start = edge;
            previous = next;
        }
        return new CellWindow(cell, start * 1000L,
                end == Long.MAX_VALUE ? end : end * 1000L);
    }

    /** The unplaced source cell used by both shutter draws. The caller owns this bitmap. */
    @Nullable
    public static Bitmap rasterCell(SpriteSheetRenderer renderer, int cell, Paint paint) {
        if (renderer.isCellMissing(cell)) return null;
        var bounds = renderer.cellRectBitmap(cell);
        if (bounds.width() <= 0 || bounds.height() <= 0) return null;
        float scale = Math.min(1f, 2048f / Math.max(bounds.width(), bounds.height()));
        int width = Math.max(1, Math.round(bounds.width() * scale));
        int height = Math.max(1, Math.round(bounds.height() * scale));
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            paint.setAlpha(255);
            renderer.drawCell(new Canvas(bitmap), cell, new RectF(0f, 0f, width, height), paint);
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError error) {
            if (bitmap != null) bitmap.recycle();
            com.fadcam.FLog.w("RigidSpriteMotion", "Source cell unavailable", error);
            return null;
        }
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
