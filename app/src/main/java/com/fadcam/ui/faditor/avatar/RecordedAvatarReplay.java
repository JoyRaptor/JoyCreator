package com.fadcam.ui.faditor.avatar;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reconstructs avatar shutter poses from the recorded clock, independent of presented frames. */
final class RecordedAvatarReplay {
    private static final int STRIDE = 32;
    private static final int MAX_STEPS_PER_PREPARE = 64;
    private static final int MAX_CHECKPOINTS = 96;

    private final AvatarRig rig;
    private final AvatarParamTrack track;
    private final int sampleCount;
    // Keys count the recorded samples already applied. The empty state is implicit.
    private final Map<Integer, State> checkpoints = new LinkedHashMap<>(16, 0.75f, true);
    private State cursor = new State();
    private int cursorIndex;

    record Tap(Map<String, Float> params, Map<String, PuppetPoseResolver.PartState> pose,
               AvatarReplayState replay) {}

    private record State(PuppetPoseResolver.DiscreteState discrete, AvatarReplayState replay) {
        private State() { this(new PuppetPoseResolver.DiscreteState(), new AvatarReplayState()); }
        private State copy() { return new State(discrete.copy(), replay.copy()); }

        private void observe(Map<String, PuppetPoseResolver.PartState> pose, long timeUs) {
            pose.forEach((id, part) -> replay.observe(id, part.cellIndex, part.swapped, timeUs));
        }
    }

    RecordedAvatarReplay(AvatarRig rig, AvatarParamTrack track) {
        this.rig = rig;
        this.track = track;
        this.sampleCount = track.size();
    }

    boolean matches(AvatarParamTrack candidate) {
        return candidate == track && candidate.size() == sampleCount;
    }

    /**
     * Build only fixed, reusable checkpoints. A cold seek can defer its blur while this catches
     * up. Resume from the last completed checkpoint on the next render attempt.
     */
    boolean prepare(long firstUs, long lastUs) {
        if (!matches(track) || sampleCount == 0 || firstUs < 0 || lastUs < firstUs
                || track.timeMsAt(0) < 0 || track.durationMs() > Long.MAX_VALUE / 1000L) return false;
        int first = aligned(sampleCountBefore(firstUs));
        int last = aligned(sampleCountBefore(lastUs));
        // A valid shutter should not span more than one call's history budget.
        if (last - first > MAX_STEPS_PER_PREPARE) return false;
        int budget = MAX_STEPS_PER_PREPARE;
        for (int target = first; ; target += STRIDE) {
            if (target != 0 && checkpoints.get(target) == null) {
                int from = floorCheckpoint(target);
                int end = from + Math.min(budget, target - from);
                advance(from, end);
                if (end != target) return false;
                budget -= end - from;
            }
            if (target == last) return true;
        }
    }

    Tap sample(long timeUs) {
        int before = sampleCountBefore(timeUs);
        int checkpoint = aligned(before);
        if (!matches(track) || (checkpoint != 0 && !checkpoints.containsKey(checkpoint))) {
            throw new IllegalStateException("Avatar tap was not prepared");
        }
        if (cursorIndex < checkpoint || cursorIndex > before) {
            cursor = checkpoint == 0 ? new State() : checkpoints.get(checkpoint).copy();
            cursorIndex = checkpoint;
        }
        while (cursorIndex < before) applyRecordedSample(cursor, cursorIndex++);
        // The cursor advances only through samples actually recorded in the take.
        State state = cursor.copy();
        Map<String, Float> params = track.sampleAtMicros(timeUs);
        var pose = PuppetPoseResolver.resolve(rig, params, state.discrete());
        state.observe(pose, timeUs);
        return new Tap(params, pose, state.replay());
    }

    private void advance(int from, int end) {
        if (from == end) return;
        State state = from == 0 ? new State() : checkpoints.get(from).copy();
        for (int i = from; i < end; i++) {
            applyRecordedSample(state, i);
            int applied = i + 1;
            if (applied % STRIDE != 0) continue;
            checkpoints.put(applied, state.copy());
            if (checkpoints.size() > MAX_CHECKPOINTS) {
                var oldest = checkpoints.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    private void applyRecordedSample(State state, int index) {
        var pose = PuppetPoseResolver.resolve(rig, track.sampleAtIndex(index), state.discrete());
        state.observe(pose, track.timeMsAt(index) * 1000L);
    }

    private int floorCheckpoint(int limit) {
        int result = 0;
        for (int saved : checkpoints.keySet()) {
            if (saved <= limit && saved > result) result = saved;
        }
        return result;
    }

    private static int aligned(int count) { return count - count % STRIDE; }

    private int sampleCountBefore(long timeUs) {
        int lo = 0;
        int hi = sampleCount;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (track.timeMsAt(mid) * 1000L < timeUs) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
