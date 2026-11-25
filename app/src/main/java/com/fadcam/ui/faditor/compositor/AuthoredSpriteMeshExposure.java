package com.fadcam.ui.faditor.compositor;

import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.MotionTimelineBreaks;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;

import java.util.Arrays;

/** Shared preview/export planning for one isolated authored mesh holding a direct sprite cell. */
public final class AuthoredSpriteMeshExposure {
    /** Initial bounded quality shared by the preview and export routes. */
    public static final int RENDER_TAPS = 6;

    private final SpriteOverlayItem sprite;
    private final MeshWarpSpec mesh;
    private final MeshExposure exposure;

    private AuthoredSpriteMeshExposure(SpriteOverlayItem sprite, MeshWarpSpec mesh) {
        this.sprite = sprite;
        this.mesh = mesh;
        long startUs = sprite.getStartMs() * 1000L;
        long endUs = sprite.getEndMs() == Long.MAX_VALUE ? Long.MAX_VALUE
                : (sprite.getEndMs() + 1L) * 1000L;
        MotionBreaks.Builder fences = new MotionBreaks.Builder();
        for (var track : sprite.getKeyframes().tracks()) fences.addSteps(track, startUs);
        exposure = new MeshExposure(mesh, startUs, endUs, fences);
    }

    /**
     * The direct authored-mesh domain shared by ordinary and True Layer Order routing.
     * This is structural only: launch/project/item opt-in remains in {@link #prepare}.
     */
    public static boolean supportsDirectMesh(@Nullable SpriteOverlayItem item) {
        if (item == null || item.isHidden() || item.isLinked() || item.hasAvatarPerformance()
                || item.hasCornerPin() || item.getFadeInMs() != 0 || item.getFadeOutMs() != 0
                || !item.hasMesh()) return false;
        return item.getStartMs() >= 0 && item.getStartMs() < Long.MAX_VALUE / 1000L
                && (item.getEndMs() == Long.MAX_VALUE || (item.getEndMs() >= item.getStartMs()
                && item.getEndMs() < Long.MAX_VALUE / 1000L));
    }

    /** Rebuild when the item changes. Both switches must be enabled before any snapshot is taken. */
    @Nullable
    public static AuthoredSpriteMeshExposure prepare(boolean launchEnabled, boolean projectEnabled,
                                                     SpriteOverlayItem item) {
        if (!launchEnabled || !projectEnabled || !item.isRigidMotionBlurEnabled()
                || !supportsDirectMesh(item)) return null;
        SpriteOverlayItem snapshot = item.copyWithNewId(item.getId());
        return new AuthoredSpriteMeshExposure(snapshot, snapshot.getMesh());
    }

    /** The held source cell, ordered taps and fractional placement for one render. */
    public static final class Taps {
        private final int sourceCell;
        private final MeshWarpSpec mesh;
        private final MeshExposure exposure;
        private final MotionShutter.Schedule schedule;
        private final Placement[] placements;

        private Taps(int sourceCell, MeshWarpSpec mesh, MeshExposure exposure,
                     MotionShutter.Schedule schedule, Placement[] placements) {
            this.sourceCell = sourceCell;
            this.mesh = mesh;
            this.exposure = exposure;
            this.schedule = schedule;
            this.placements = placements;
        }

        public int sourceCell() { return sourceCell; }
        MeshWarpSpec mesh() { return mesh; }
        MeshExposure exposure() { return exposure; }
        MotionShutter.Schedule schedule() { return schedule; }
        Placement placementAt(long projectUs) {
            return placements[Arrays.binarySearch(schedule.timesUs(), projectUs)];
        }
    }

    record Placement(float cx, float cy, float height, float rotation, float alpha,
                     float mirrorX, float mirrorY) {}

    /** Returns null for a sharp frame, an unsupported cell, or a frame outside the render piece. */
    @Nullable
    public Taps plan(SpriteSheet sheet, long centerUs, long periodUs, int maxTaps,
                     long pieceStartUs, long pieceEndUs) {
        if (!sprite.getSheetId().equals(sheet.getId())) return null;
        var cell = MotionTimelineBreaks.directCellWindow(sheet, sprite, Math.floorDiv(centerUs, 1000L));
        return planHeldCell(cell, centerUs, periodUs, maxTaps, pieceStartUs, pieceEndUs);
    }

    /** Plan against the cell and hold interval already resolved with the caller's unplaced bitmap. */
    @Nullable
    Taps planHeldCell(@Nullable MotionTimelineBreaks.CellWindow cell, long centerUs, long periodUs,
                      int maxTaps, long pieceStartUs, long pieceEndUs) {
        if (cell == null || centerUs < pieceStartUs || centerUs >= pieceEndUs
                || centerUs < cell.startUs() || centerUs >= cell.endUs() || periodUs <= 0 || periodUs > Integer.MAX_VALUE
                || maxTaps < 2 || maxTaps > MeshExposureGlPolicy.MAX_TAPS) return null;
        MotionShutter.Controls controls = sprite.getRigidMotionShutter();
        if (controls.angle() == 0 || controls.mixPercent() == 0) return null;
        if (!sprite.isVisibleAt(Math.floorDiv(centerUs, 1000L))) return null;
        MotionBreaks frameFences = new MotionBreaks.Builder().add(pieceStartUs).add(pieceEndUs)
                .add(cell.startUs()).add(cell.endUs()).build();
        MotionShutter.Schedule schedule = exposure.schedule(controls, centerUs, periodUs, maxTaps, frameFences);
        if (schedule.timesUs().length < 2) return null;

        Placement center = placement(centerUs);
        // Ordinary preview does not build a mesh rung below this visibility cutoff.
        if (center == null || center.alpha() < 0.005f) return null;
        boolean moves = false;
        Placement[] placements = new Placement[schedule.timesUs().length];
        for (int i = 0; i < placements.length; i++) {
            Placement tap = placement(schedule.timesUs()[i]);
            if (tap == null) return null;
            placements[i] = tap;
            moves |= !center.equals(tap);
        }
        if (!moves) {
            moves = changesWithin(mesh.track(), centerUs, schedule.timesUs())
                    || changesWithin(mesh.groupZTrackOrNull(), centerUs, schedule.timesUs())
                    || changesWithin(mesh.handleZTrackOrNull(), centerUs, schedule.timesUs());
        }
        return moves ? new Taps(cell.cell(), mesh, exposure, schedule, placements) : null;
    }

    @Nullable private Placement placement(long timeUs) {
        double ms = timeUs / 1000.0;
        float[] pose = new float[4];
        sprite.motionPlacementAt(ms, pose);
        float opacity = sprite.getKeyframes().valueAt(KeyframeSet.OPACITY,
                Math.max(0.0, ms - sprite.getStartMs()), sprite.getOpacity());
        if (!Float.isFinite(opacity) || !Float.isFinite(pose[0]) || !Float.isFinite(pose[1])
                || !Float.isFinite(pose[2]) || !Float.isFinite(pose[3]) || !(pose[2] > 0f)) return null;
        return new Placement(pose[0], pose[1], pose[2], pose[3], Math.max(0f, Math.min(1f, opacity)),
                sprite.isFlipH() ? -1f : 1f, sprite.isFlipV() ? -1f : 1f);
    }

    private boolean changesWithin(@Nullable MeshPoseTrack track, long centerUs, long[] timesUs) {
        if (track == null || track.poses().size() < 2) return false;
        float[] center = new float[track.arity()];
        float[] tap = new float[center.length];
        track.valueAtMicros(exposure.localTimeUs(centerUs), center);
        for (long timeUs : timesUs) {
            track.valueAtMicros(exposure.localTimeUs(timeUs), tap);
            if (!Arrays.equals(center, tap)) return true;
        }
        return false;
    }
}
