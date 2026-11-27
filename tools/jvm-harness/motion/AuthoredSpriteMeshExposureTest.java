package com.fadcam.ui.faditor.compositor;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.MotionTimelineBreaks;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;

import java.util.Arrays;

public final class AuthoredSpriteMeshExposureTest {
    public static void main(String[] args) {
        SpriteSheet sheet = new SpriteSheet("sheet", "test", "test");
        sheet.setGrid(2, 1);
        SpriteOverlayItem sprite = new SpriteOverlayItem("sprite", "sheet");
        sprite.setTimeRange(1000, 1200);
        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(80, 1));
        MeshWarpSpec mesh = MeshWarpSpec.lattice(2);
        float[] bent = mesh.handles().clone();
        bent[0] += .2f;
        mesh.ensureTrack().put(0, mesh.handles(), Easing.LINEAR.name());
        mesh.track().put(200, bent, Easing.LINEAR.name());
        sprite.setMesh(mesh);
        require(AuthoredSpriteMeshExposure.prepare(true, true, sprite) == null,
                "newly created item is sharp");
        sprite.setRigidMotionBlurEnabled(true);
        require(AuthoredSpriteMeshExposure.supportsDirectMesh(sprite),
                "direct authored mesh is structurally supported");
        require(AuthoredSpriteMeshExposure.prepare(false, true, sprite) == null,
                "launch gate blocks opted-in item");
        require(AuthoredSpriteMeshExposure.prepare(true, false, sprite) == null,
                "project gate blocks opted-in item");
        AuthoredSpriteMeshExposure motion = AuthoredSpriteMeshExposure.prepare(true, true, sprite);
        require(motion != null, "eligible authored mesh prepares");
        SpriteOverlayItem faded = sprite.copyWithNewId("faded");
        faded.setFadeInMs(1);
        require(!AuthoredSpriteMeshExposure.supportsDirectMesh(faded),
                "fade stays outside the direct mesh domain");
        SpriteOverlayItem pinned = sprite.copyWithNewId("pinned");
        pinned.setCornerPin(new float[]{.1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});
        require(!AuthoredSpriteMeshExposure.supportsDirectMesh(pinned),
                "corner pin stays outside the direct mesh domain");
        SpriteOverlayItem avatar = sprite.copyWithNewId("avatar");
        var performance = new com.fadcam.ui.faditor.avatar.AvatarParamTrack();
        performance.add(0L, java.util.Map.of());
        avatar.setAvatarRigId("rig");
        avatar.setAvatarTrack(performance);
        require(!AuthoredSpriteMeshExposure.supportsDirectMesh(avatar),
                "stateful avatar stays outside the direct mesh domain");
        SpriteSheet wrongSheet = new SpriteSheet("other-sheet", "test", "test");
        wrongSheet.setGrid(2, 1);
        require(motion.plan(wrongSheet, 1_079_999L, 40_000L, 8, 1_076_000L, 1_200_000L) == null,
                "matching cell indices from a different sheet cannot donate");
        var before = motion.plan(sheet, 1_079_999L, 40_000L, 8, 1_076_000L, 1_200_000L);
        require(before != null && before.sourceCell() == 0, "last instant of holding cell zero");
        long[] beforeTimes = args.length == 1 && "--unfenced-cell".equals(args[0])
                ? MotionShutter.Controls.NATURAL.schedule(1_079_999L, 40_000L, 8,
                        new MotionBreaks.Builder().add(1_076_000L).build(), MotionBreaks.none()).timesUs()
                : before.schedule().timesUs();
        for (long timestamp : beforeTimes) {
            require(timestamp >= 1_076_000L && timestamp < 1_080_000L,
                    "an authored sprite tap must stay inside its held cell");
        }
        var after = motion.plan(sheet, 1_080_000L, 40_000L, 8, 1_076_000L, 1_200_000L);
        require(after != null && after.sourceCell() == 1, "cell one owns the exact change");
        require(after.schedule().timesUs()[0] >= 1_080_000L, "new cell cannot use older cell");
        var previousCell = MotionTimelineBreaks.directCellWindow(sheet, sprite, 1079);
        var matchingCell = MotionTimelineBreaks.directCellWindow(sheet, sprite, 1080);
        require(motion.planHeldCell(previousCell, 1_080_000L, 40_000L, 8, 1_076_000L, 1_200_000L) == null,
                "stale preview atlas must not blur across the decoded cell change");
        var preview = motion.planHeldCell(matchingCell, 1_080_000L, 40_000L, 8, 1_076_000L, 1_200_000L);
        require(preview != null && preview.sourceCell() == after.sourceCell()
                && Arrays.equals(preview.schedule().timesUs(), after.schedule().timesUs())
                && Arrays.equals(preview.schedule().weights(), after.schedule().weights()),
                "matching preview and export frames share the held cell and exposure weights");

        sprite.getKeyframes().getOrCreate(KeyframeSet.X).put(80, 0f, Easing.LINEAR);
        sprite.getKeyframes().getOrCreate(KeyframeSet.X).put(81, 1f, Easing.LINEAR);
        var snapshot = sprite.copyWithNewId(sprite.getId());
        require(AuthoredSpriteMeshExposure.prepareSnapshot(false, snapshot) == null,
                "a master-off snapshot still samples a sharp pose");
        long sharpUs = args.length == 1 && "--sharp-at-ui-time".equals(args[0]) ? 1_081_000L : 1_080_999L;
        var sharp = AuthoredSpriteMeshExposure.sharpPoseAt(snapshot, sharpUs);
        require(sharp != null && sharp.localMs() == 80L && sharp.cx() == 0f,
                "sharp mesh uses the decoder frame's pose even when the UI tick has advanced");
        sprite.setRigidMotionShutter(new MotionShutter.Controls(180, MotionShutter.Timing.LEADING, 0, 50));
        var fractional = AuthoredSpriteMeshExposure.prepare(true, true, sprite)
                .plan(sheet, 1_080_000L, 1000L, 5, 1_076_000L, 1_200_000L);
        require(fractional != null && fractional.schedule().timesUs()[0] == 1_080_000L,
                "partial Mix retains the ordered sharp centre");
        long firstMotionTap = fractional.schedule().timesUs()[1];
        float fractionalX = fractional.placementAt(firstMotionTap).cx();
        require(fractionalX > 0f && fractionalX < 1f, "placement uses fractional time");
        require(after.placementAt(after.schedule().timesUs()[0]).cx() != fractionalX,
                "changing the live item cannot change an existing render snapshot");

        var staticMesh = MeshWarpSpec.lattice(2);
        staticMesh.handles()[0] = .2f;
        SpriteOverlayItem still = new SpriteOverlayItem("still", "sheet");
        still.setTimeRange(1000, 1200);
        still.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
        still.setRigidMotionBlurEnabled(true);
        still.setMesh(staticMesh);
        require(AuthoredSpriteMeshExposure.prepare(true, true, still)
                .plan(sheet, 1_080_000L, 40_000L, 8, 1_000_000L, 1_200_000L) == null,
                "a still posed mesh needs no extra render");
        var opacity = sprite.getKeyframes().getOrCreate(KeyframeSet.OPACITY);
        opacity.put(0, 0f, Easing.LINEAR);
        opacity.put(119, 1f, Easing.LINEAR);
        var opacityTaps = AuthoredSpriteMeshExposure.prepare(true, true, sprite)
                .plan(sheet, 1_080_000L, 1000L, 5, 1_076_000L, 1_200_000L);
        require(opacityTaps.placementAt(opacityTaps.schedule().timesUs()[1]).alpha()
                        > opacityTaps.placementAt(1_080_000L).alpha(),
                "opacity uses fractional time");
        System.out.println("Authored sprite mesh exposure checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
