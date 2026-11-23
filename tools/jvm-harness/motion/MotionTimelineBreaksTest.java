import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TimeMap;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.Transition;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.MotionTimelineBreaks;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

public final class MotionTimelineBreaksTest {
    private static final boolean WRONG_NO_FENCES = Boolean.getBoolean("motion.wrongNoFences");

    public static void main(String[] args) {
        equal(1_234_567, TimeMap.chunkToEditorUs(234_567, 1_000), "export piece retains microseconds");
        equal(1_234, TimeMap.chunkToEditorMs(234_567, 1_000), "legacy export piece retains milliseconds");
        var timeline = new Timeline();
        var first = clip(900);
        first.setRemovedSpans(java.util.List.of(new long[]{300, 400}));
        timeline.addClip(first);
        timeline.addClip(clip(800));
        timeline.addTransition(new Transition(Transition.Type.CROSS_DISSOLVE, 120, 0));

        var collected = new MotionBreaks.Builder();
        if (!WRONG_NO_FENCES) MotionTimelineBreaks.addTimeline(collected, TimeMap.of(timeline));
        var breaks = collected.build();
        equal(299_999, around(breaks, 295_000).lastValidUs(), "cut start");
        equal(400_000, around(breaks, 402_000).firstValidUs(), "cut end");
        equal(779_999, around(breaks, 775_000).lastValidUs(), "outgoing transition edge");
        equal(900_000, around(breaks, 900_000).firstValidUs(), "clip seam");
        equal(1_020_000, around(breaks, 1_020_000).firstValidUs(), "incoming transition edge");

        var loop = clip(100);
        loop.setLoopMode(Clip.LOOP_MODE_NORMAL);
        loop.setLoopBeforeMs(100);
        loop.setLoopAfterMs(200);
        var loops = new MotionBreaks.Builder();
        MotionTimelineBreaks.addClip(loops, loop, 500, 500_000, 900_000);
        equal(700_000, around(loops.build(), 701_000).firstValidUs(), "normal loop restarts base");

        loop.setLoopMode(Clip.LOOP_MODE_PING_PONG);
        var pingPong = new MotionBreaks.Builder();
        MotionTimelineBreaks.addClip(pingPong, loop, 500, 500_000, 900_000);
        equal(795_000, around(pingPong.build(), 805_000).firstValidUs(), "matching ping-pong turn is continuous");

        var sheet = new SpriteSheet("test", "test", "test");
        sheet.setGrid(2, 1);
        var sprite = new SpriteOverlayItem("drawings", "test");
        sprite.setTimeRange(1_000, 2_000);
        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(80, 1));
        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(90, 90));
        var spriteChanges = new MotionBreaks.Builder();
        MotionTimelineBreaks.addSprite(spriteChanges, sheet, sprite, 1_065_000, 1_105_000);
        var spriteBreaks = spriteChanges.build();
        equal(1_079_999, around(spriteBreaks, 1_075_000).lastValidUs(), "sprite swap");
        equal(1_089_999, around(spriteBreaks, 1_085_000).lastValidUs(), "sprite becomes empty");

        var poseFences = new MotionBreaks.Builder().add(1_077_000).build();
        var cellFences = Boolean.getBoolean("motion.wrongNoSpriteExposureFence")
                ? MotionBreaks.none() : spriteBreaks;
        var fps = new MotionShutter.FrameRate(30, 1);
        var left = MotionShutter.sample(1_079_999, fps, 180, -90,
                MotionShutter.Shape.BOX, 6, poseFences, cellFences);
        for (long sample : left.timesUs()) {
            if (sample < 1_077_000 || sample >= 1_080_000) {
                throw new AssertionError("sprite exposure intersects pose and cell fences");
            }
        }
        var right = MotionShutter.sample(1_080_000, fps, 180, -90,
                MotionShutter.Shape.BOX, 6, poseFences, cellFences);
        for (long sample : right.timesUs()) {
            if (sample < 1_080_000 || sample >= 1_090_000) {
                throw new AssertionError("cell swap time belongs to the new drawing");
            }
        }

        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(32, 0));
        var previewLeft = MotionTimelineBreaks.directCellWindow(sheet, sprite, 1_079);
        var previewRight = MotionTimelineBreaks.directCellWindow(sheet, sprite, 1_080);
        long leftEnd = Boolean.getBoolean("motion.wrongPreviewCellWindow")
                ? sprite.getEndMs() * 1000L : previewLeft.endUs();
        equal(1_080_000, leftEnd, "preview cell holds only until the authored swap");
        equal(1_000_000, previewLeft.startUs(), "identical cell key does not break exposure");
        equal(0, previewLeft.cell(), "previous drawing");
        equal(1_080_000, previewRight.startUs(), "next drawing owns the exact swap");
        equal(1_090_000, previewRight.endUs(), "preview fences empty cells");
        equal(1, previewRight.cell(), "next drawing");
        if (MotionTimelineBreaks.directCellWindow(sheet, sprite, 1_090) != null) {
            throw new AssertionError("empty cell never becomes a texture donor");
        }

        System.out.println("MotionTimelineBreaksTest: transition, cut, repeat, ping-pong and sprite assertions passed");
    }

    private static Clip clip(long duration) {
        var clip = new Clip(null, 600_000);
        clip.setInPointMs(0);
        clip.setOutPointMs(duration);
        return clip;
    }

    private static MotionBreaks.Window around(MotionBreaks breaks, long timeUs) {
        return breaks.splitWindow(timeUs, timeUs - 10_000, timeUs + 10_000);
    }

    private static void equal(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
