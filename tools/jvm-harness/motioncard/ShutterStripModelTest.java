import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.MotionShutter.Controls;
import com.fadcam.ui.faditor.motion.MotionShutter.Preset;
import com.fadcam.ui.faditor.motion.MotionShutter.Timing;
import com.fadcam.ui.faditor.motion.ShutterStripModel;
import com.fadcam.ui.faditor.motion.ShutterStripModel.Grab;

/** STU-7 §K.1: the Motion Blur card's shutter strip geometry. Run by run-motioncard.sh. */
public final class ShutterStripModelTest {

    private static int checks;

    public static void main(String[] args) {
        viewSpan();
        mapping();
        presetsRoundTrip();
        dragEnds();
        slideKeepsAngle();
        snapping();
        reachLimit();
        grabbing();
        System.out.println("ShutterStripModelTest: " + checks + " checks passed");
    }

    private static void viewSpan() {
        eq(1, ShutterStripModel.viewFramesFor(Controls.NATURAL), "natural fits one frame each side");
        eq(1, ShutterStripModel.viewFramesFor(new Controls(720, Timing.CENTERED, 0, 100)),
                "720 centred is exactly last..next");
        eq(2, ShutterStripModel.viewFramesFor(new Controls(720, Timing.TRAILING, 0, 100)),
                "720 behind reaches two frames back");
        eq(1, ShutterStripModel.viewFramesFor(new Controls(0, Timing.CENTERED, 0, 100)),
                "a closed shutter still shows one frame");
    }

    private static void mapping() {
        near(0.5, ShutterStripModel.fraction(0, 1), "playhead is the middle");
        near(0.0, ShutterStripModel.fraction(-360, 1), "last frame is the left edge");
        near(1.0, ShutterStripModel.fraction(360, 1), "next frame is the right edge");
        near(0.25, ShutterStripModel.fraction(-360, 2), "last frame is a quarter in at two frames");
        for (double f = 0; f <= 1.0; f += 0.125) {
            near(f, ShutterStripModel.fraction(ShutterStripModel.degrees(f, 3), 3), "round trip " + f);
        }
    }

    private static void presetsRoundTrip() {
        for (Preset p : Preset.values()) {
            Controls c = Controls.fromPreset(p);
            eq(p, ShutterStripModel.presetOf(c), "preset " + p + " is recognised");
            Controls again = ShutterStripModel.fromWindow(c.phase(), c.angle(), 100, 1);
            eq(c, again, "preset " + p + " survives the window round trip");
        }
        eq(null, ShutterStripModel.presetOf(new Controls(200, Timing.CUSTOM, -50, 100)),
                "a hand-set shutter is no preset");
        Controls mixed = new Controls(90, Timing.LEADING, 0, 40);
        eq(40, ShutterStripModel.withPreset(mixed, Preset.HEAVY).mixPercent(), "a preset keeps Mix");
    }

    private static void dragEnds() {
        Controls c = Controls.NATURAL; // -90 .. +90
        Controls wider = ShutterStripModel.dragOpen(c, -180, 1);
        eq(270, wider.angle(), "pulling the open end back to -180 makes 270");
        near(90, wider.phase() + wider.angle(), "the close end stayed");
        Controls shorter = ShutterStripModel.dragClose(c, 0, 1);
        eq(90, shorter.angle(), "pulling the close end to the playhead makes 90");
        eq(Timing.TRAILING, shorter.timing(), "a window ending at the playhead is named behind");
        Controls crossed = ShutterStripModel.dragOpen(c, 300, 1);
        eq(0, crossed.angle(), "the open end cannot pass the close end");
    }

    private static void slideKeepsAngle() {
        Controls c = Controls.NATURAL;
        Controls ahead = ShutterStripModel.slide(c, 90, 1);
        eq(180, ahead.angle(), "sliding keeps the angle");
        eq(Timing.LEADING, ahead.timing(), "slid to open at the playhead it is named ahead");
        Controls odd = ShutterStripModel.slide(c, 40, 1);
        eq(Timing.CUSTOM, odd.timing(), "between named places it is custom");
        eq(-50, odd.customPhase(), "custom keeps the exact opening");
        near(-50, odd.phase(), "and the engine reads that opening");
    }

    private static void snapping() {
        eq(180, ShutterStripModel.snapAngle(185, 1), "185 snaps to 180");
        eq(200, ShutterStripModel.snapAngle(200, 1), "200 stays");
        eq(360, ShutterStripModel.snapAngle(350, 2), "the snap widens when zoomed out");
        Controls c = ShutterStripModel.fromWindow(-87, 180, 100, 1);
        eq(Timing.CENTERED, c.timing(), "3 degrees off centre snaps to centred");
        eq(Preset.NATURAL, ShutterStripModel.presetOf(c), "and is the Natural preset again");
    }

    private static void reachLimit() {
        Controls far = ShutterStripModel.dragOpen(Controls.NATURAL, -5000, 4);
        check(far.phase() >= -ShutterStripModel.REACH, "the open end stops at two frames back");
        check(far.angle() <= ShutterStripModel.REACH, "the angle stops at 720");
        Controls slid = ShutterStripModel.slide(new Controls(360, Timing.CENTERED, 0, 100), -5000, 2);
        near(-720, slid.phase(), "a slide stops at the reach limit");
        Controls slidOut = ShutterStripModel.slide(new Controls(360, Timing.CENTERED, 0, 100), 5000, 2);
        near(720, slidOut.phase() + slidOut.angle(), "and on the other side");
    }

    private static void grabbing() {
        Controls c = Controls.NATURAL; // -90 .. +90
        eq(Grab.OPEN, ShutterStripModel.grab(c, -95, 20), "near the open end grabs it");
        eq(Grab.CLOSE, ShutterStripModel.grab(c, 85, 20), "near the close end grabs it");
        eq(Grab.MIDDLE, ShutterStripModel.grab(c, 0, 20), "inside grabs the middle");
        eq(Grab.CLOSE, ShutterStripModel.grab(c, 300, 20), "outside grabs the nearer end");
        eq(Grab.CLOSE, ShutterStripModel.grab(c, 120, 20),
                "just outside the handle reach still grabs the end, never slides the window");
        eq(Grab.OPEN, ShutterStripModel.grab(c, -120, 20), "on both sides");
        Controls tiny = new Controls(10, Timing.CENTERED, 0, 100);
        check(ShutterStripModel.grab(tiny, 0, 20) != Grab.MIDDLE,
                "a narrow window is grabbed by an end, so it can always be widened");
    }

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": expected " + want + " got " + got);
        }
    }

    private static void near(double want, double got, String what) {
        checks++;
        if (Math.abs(want - got) > 1e-6) throw new AssertionError(what + ": expected " + want + " got " + got);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
