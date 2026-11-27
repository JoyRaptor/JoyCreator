package com.fadcam.ui.faditor.avatar;

import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;

import java.util.Map;

public final class RecordedAvatarReplayTest {
    public static void main(String[] args) {
        var rig = new AvatarRig("rig", "Test");
        rig.getParts().add(new AvatarRig.Part("hand", "sheet"));
        var domain = new AvatarRig.PoseDomain("arms");
        domain.cols = 2;
        for (int i = 0; i < 2; i++) {
            var cell = new AvatarRig.Cell();
            cell.col = i;
            var pose = new AvatarRig.PartPose("hand");
            pose.cellIndex = i;
            pose.x = i;
            cell.poses.add(pose);
            domain.cells.add(cell);
        }
        rig.getDomains().add(domain);

        var track = new AvatarParamTrack();
        for (int i = 0; i < 600; i++) {
            track.add(i * 10, Map.of("yaw", i == 248 ? 0f : i < 248 ? -1f : 1f));
        }
        var dense = new RecordedAvatarReplay(rig, track);
        for (long t = 0; t < 2_500_000; t += 16_667) {
            require(dense.prepare(t, t), "regular playback stays prepared");
            dense.sample(t);
        }
        var skipped = new RecordedAvatarReplay(rig, track);
        long first = 2_475_500;
        long last = 2_501_250;
        require(!skipped.prepare(first, last), "cold seek obeys its per-call replay budget");
        int attempts = 1;
        while (!skipped.prepare(first, last)) {
            require(++attempts < 6, "cold seek resumes from saved checkpoints");
        }
        var exposureTap = skipped.sample(last);
        var denseTap = dense.sample(last);
        var otherCell = Boolean.getBoolean("avatar.wrongPresentationClock")
                ? presentationHistory(rig, track).fade("hand") : denseTap.replay().fade("hand");
        require(exposureTap.replay().fade("hand").equals(otherCell),
                "presentation cadence cannot move the recorded cell swap");
        require(otherCell.startUs() == 2_490_000, "the first recorded swap owns the fade clock");
        require(exposureTap.pose().get("hand").cellIndex == 1, "new cell after the swap");
        var earlier = skipped.sample(first);
        require(Math.abs(earlier.pose().get("hand").x - 0.275f) < 0.00001f,
                "sub-millisecond tap interpolates the hand pose");
        require(earlier.pose().get("hand").cellIndex == 0, "earlier tap retains the old cell");
        require(skipped.sample(last).replay().fade("hand").equals(otherCell),
                "rewind and repeated taps reproduce the same fade");
        require(!skipped.prepare(last, first), "reject reversed bounds");
        track.add(6_000, Map.of("yaw", -1f));
        require(!skipped.matches(track), "editing the recorded take invalidates its checkpoints");

        var controls = new MotionShutter.Controls(180, MotionShutter.Timing.LEADING, 0, 50);
        long center = 100_000;
        var plan = AvatarShutterSchedule.plan(controls, center, 30_000, 80_000, 200_000,
                90_000, 108_000, new MotionBreaks.Builder().add(105_000).build());
        require(plan != null, "leading partial mix is a real exposure");
        if (Boolean.getBoolean("avatar.wrongTapOrder")) {
            long[] times = plan.timesUs();
            long sharp = times[0];
            System.arraycopy(times, 1, times, 0, times.length - 1);
            times[times.length - 1] = sharp;
        }
        require(plan.timesUs()[0] == center && plan.weights()[0] == 0.5,
                "sharp mix tap is reinserted in chronological order with its weight");
        require(plan.timesUs()[plan.timesUs().length - 1] < 105_000,
                "no tap crosses the avatar's discontinuity inside the piece");
        double weight = 0;
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < plan.timesUs().length; i++) {
            require(plan.timesUs()[i] > previous, "ordered distinct taps");
            previous = plan.timesUs()[i];
            weight += plan.weights()[i];
        }
        require(Math.abs(weight - 1) < 0.000001, "exposure keeps all its weight");
        require(AvatarShutterSchedule.plan(controls, center, 30_000, 80_000, 200_000,
                90_000, center, MotionBreaks.none()) == null, "piece end is exclusive");
        require(AvatarShutterSchedule.plan(controls, center, 30_000, 80_000, center + 1,
                90_000, 108_000, MotionBreaks.none()) == null, "one valid instant stays sharp");

        var mouth = new AvatarParamTrack();
        String viseme = SpectralVisemeAnalyzer.PARAM_VISEME;
        mouth.add(0, Map.of());
        mouth.add(10, Map.of(viseme, 1f));
        mouth.add(20, Map.of(viseme, 2f));
        mouth.add(30, Map.of());
        var mouthFences = new MotionBreaks.Builder();
        mouth.addShutterBreaks(mouthFences, 100_000L);
        var mouthBreaks = mouthFences.build();
        require(mouthBreaks.splitWindow(100_000, 90_000, 140_000).lastValidUs() == 100_000,
                "first one-sided viseme starts after the exact key");
        require(mouthBreaks.splitWindow(110_000, 90_000, 140_000).lastValidUs() == 119_999,
                "changing visemes cannot bleed across their recorded boundary");
        require(mouthBreaks.splitWindow(120_000, 90_000, 140_000).lastValidUs() == 129_999,
                "missing right-side viseme is held until that exact key");
        System.out.println("RecordedAvatarReplayTest PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static AvatarReplayState presentationHistory(AvatarRig rig, AvatarParamTrack track) {
        var replay = new AvatarReplayState();
        var discrete = new PuppetPoseResolver.DiscreteState();
        for (long displayedUs : new long[]{2_470_000L, 2_500_000L}) {
            var pose = PuppetPoseResolver.resolve(rig, track.sampleAtMicros(displayedUs), discrete);
            pose.forEach((id, part) -> replay.observe(id, part.cellIndex, part.swapped, displayedUs));
        }
        return replay;
    }
}
