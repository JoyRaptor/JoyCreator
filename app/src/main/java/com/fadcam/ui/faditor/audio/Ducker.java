package com.fadcam.ui.faditor.audio;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.VolumeKeyframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Sidechain ducking — SPEC_AUDIO_UX_V1 row {@code C5.E}.
 *
 * <p><b>What it does.</b> Given the amplitude envelope of a KEY track (someone talking) it works
 * out when that voice is present and writes volume keyframes onto a TARGET track (the music) so
 * the music steps out of the way and comes back afterwards.</p>
 *
 * <p><b>Why keyframes and not a live effect.</b> Every other editor implements this as a hidden
 * processor with a threshold knob: it works until it does not, and when it grabs the wrong
 * moment there is nothing to take hold of. Here the output is ORDINARY KEYFRAMES on the ordinary
 * envelope — the same ones a finger can drag. The generator is a starting point the user edits,
 * not an authority. It also costs nothing at export: by the time the exporter runs this has left
 * no trace except the curve.</p>
 *
 * <p><b>Why the output stays sparse.</b> A keyframe per analysis frame would be technically
 * correct and practically useless — a hundred dots a second cannot be edited by hand. Each
 * region of speech yields at most FOUR keyframes (start of dip, floor, end of hold, back up),
 * and regions separated by less than {@link Params#bridgeGapMs} are merged rather than producing
 * a dip-and-recover between every sentence, which is heard as pumping.</p>
 *
 * <p><b>Units.</b> Values written are MULTIPLIERS over the clip's {@code volumeLevel}, per B1.Q —
 * 1.0 means "the level the user set", not "full scale". Ducking a clip already pulled to 50%
 * takes it to 50% x the duck amount, which is what "duck the music" means to someone who has
 * already decided how loud the music is.</p>
 */
public final class Ducker {

    private Ducker() {}

    /** Tuning for {@link #compute}. The defaults are the ones to reach for first. */
    public static final class Params {
        /**
         * Speech counts as present this far up from the key track's floor toward its peak.
         *
         * <p>Relative rather than absolute, because a quiet recording and a loud one should duck
         * the same way — an absolute threshold silently stops working the moment someone sits
         * further from the microphone. Measured from the FLOOR rather than from zero; see the
         * contrast note in {@link #compute}.</p>
         */
        public float thresholdFraction = 0.15f;

        /** How far down to duck, as a multiplier. 0.25 is about -12 dB. */
        public float duckMultiplier = 0.25f;

        /** Time to go down. Short, or the music is still loud over the first word. */
        public long attackMs = 150;

        /** Time to come back. Longer than attack — a fast recovery is the classic pumping sound. */
        public long releaseMs = 400;

        /** Stay at the floor this long after the voice stops, before releasing. */
        public long holdMs = 250;

        /**
         * Gaps shorter than this between two speech regions are BRIDGED into one.
         *
         * <p>This is the difference between ducking and pumping. Ordinary speech is full of gaps
         * of a few hundred ms; treating each as a chance to bring the music back up produces an
         * audible surge between every sentence.</p>
         */
        public long bridgeGapMs = 700;
    }

    /**
     * Work out the ducking curve for one target clip.
     *
     * @param keyEnvelope      amplitude envelope of the KEY (voice) track.
     * @param framesPerSecond  frame rate of that envelope.
     * @param keyStartMs       where the key clip starts on the TIMELINE.
     * @param targetStartMs    where the target clip starts on the TIMELINE.
     * @param targetDurationMs trimmed duration of the target clip.
     * @return keyframes in TARGET-CLIP-LOCAL ms, ascending, multipliers per B1.Q. Empty when
     *         there is nothing to duck — an empty result is a real answer, not a failure.
     */
    @NonNull
    public static List<VolumeKeyframe> compute(
            @NonNull int[] keyEnvelope, double framesPerSecond,
            long keyStartMs, long targetStartMs, long targetDurationMs,
            @NonNull Params p) {

        List<VolumeKeyframe> out = new ArrayList<>();
        if (keyEnvelope.length == 0 || framesPerSecond <= 0 || targetDurationMs <= 0) return out;
        // A duck multiplier of 1.0 means "do not duck". Returning NO keyframes rather than a
        // flat line of 1.0s leaves the clip's envelope genuinely untouched.
        if (p.duckMultiplier >= 1.0f) return out;

        int peak = 0;
        for (int v : keyEnvelope) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0) return out;                    // digital silence: nothing to duck under

        // The threshold is measured from the track's FLOOR up to its peak, not from zero.
        //
        // Measuring from zero looks equivalent and is not: a key track of constant room tone —
        // a live mic with nobody talking — has every frame above 15% of its own peak, so the
        // whole track reads as speech and the music ducks permanently. The harness's
        // silent-key negative control caught exactly that, on a fixture of unbroken room tone.
        //
        // Contrast is also the test for whether there is any speech at all. Real speech towers
        // over the room it was recorded in; a track whose loudest moment barely rises above its
        // quietest has nothing in it worth ducking under, and the honest answer is no keyframes.
        int floor = floorOf(keyEnvelope);
        final double range = peak - floor;
        if (range < peak * MIN_CONTRAST) return out;
        final double thresh = floor + range * p.thresholdFraction;
        final double msPerFrame = 1000.0 / framesPerSecond;

        // 1. Frames where the voice is present, collapsed into [startMs,endMs] regions on the
        //    TIMELINE (not yet clipped to the target).
        List<long[]> regions = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i < keyEnvelope.length; i++) {
            boolean loud = Math.abs(keyEnvelope[i]) > thresh;
            if (loud && runStart < 0) runStart = i;
            if (!loud && runStart >= 0) {
                regions.add(new long[]{
                        keyStartMs + Math.round(runStart * msPerFrame),
                        keyStartMs + Math.round(i * msPerFrame)});
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            regions.add(new long[]{
                    keyStartMs + Math.round(runStart * msPerFrame),
                    keyStartMs + Math.round(keyEnvelope.length * msPerFrame)});
        }
        if (regions.isEmpty()) return out;

        // 2. Bridge short gaps BEFORE generating keyframes, so a bridged pair yields one dip
        //    instead of two that overlap and fight each other.
        List<long[]> merged = new ArrayList<>();
        long[] cur = new long[]{regions.get(0)[0], regions.get(0)[1]};
        for (int i = 1; i < regions.size(); i++) {
            long[] r = regions.get(i);
            if (r[0] - cur[1] <= p.bridgeGapMs) {
                cur[1] = r[1];
            } else {
                merged.add(cur);
                cur = new long[]{r[0], r[1]};
            }
        }
        merged.add(cur);

        // 3. Four keyframes per region, converted to target-clip-local time. Regions falling
        //    entirely outside the target contribute nothing.
        final float duck = Math.max(0f, p.duckMultiplier);
        for (long[] r : merged) {
            long downStart = r[0] - p.attackMs;   // still at the user's level here
            long downEnd = r[0];                  // at the floor by the first word
            long upStart = r[1] + p.holdMs;       // hold, then start coming back
            long upEnd = upStart + p.releaseMs;

            if (upEnd < targetStartMs) continue;
            if (downStart > targetStartMs + targetDurationMs) continue;

            addLocal(out, downStart - targetStartMs, 1f, targetDurationMs);
            addLocal(out, downEnd - targetStartMs, duck, targetDurationMs);
            addLocal(out, upStart - targetStartMs, duck, targetDurationMs);
            addLocal(out, upEnd - targetStartMs, 1f, targetDurationMs);
        }

        // 4. Ascending, one keyframe per instant. A later write wins, which matters where two
        //    regions were close enough that a release and the next attack land on the same ms.
        out.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        List<VolumeKeyframe> dedup = new ArrayList<>();
        for (VolumeKeyframe kf : out) {
            if (!dedup.isEmpty() && dedup.get(dedup.size() - 1).timeMs == kf.timeMs) {
                dedup.set(dedup.size() - 1, kf);
            } else {
                dedup.add(kf);
            }
        }
        return dedup;
    }

    /**
     * Minimum peak-to-floor contrast for a key track to contain speech at all.
     *
     * <p>Below this the track is some steady thing — room tone, hum, a hiss — and ducking under
     * it would hold the music down from beginning to end. 10% is far under any real voice
     * (speech against room tone measures upwards of 90% on the harness fixtures) and far above
     * a track that is merely noisy.</p>
     */
    private static final double MIN_CONTRAST = 0.10;

    /**
     * The track's quiet floor: the 10th percentile of |amplitude|.
     *
     * <p>A percentile rather than the minimum, because one digitally-silent frame — a dropout, a
     * gap between takes — would otherwise put the floor at 0 and hand back the from-zero
     * behaviour this exists to avoid.</p>
     */
    private static int floorOf(@NonNull int[] env) {
        int[] sorted = new int[env.length];
        for (int i = 0; i < env.length; i++) sorted[i] = Math.abs(env[i]);
        java.util.Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, sorted.length / 10)];
    }

    /** Add one keyframe clamped into the clip, skipping those that fall past its end. */
    private static void addLocal(@NonNull List<VolumeKeyframe> out,
                                 long localMs, float value, long durationMs) {
        if (localMs < 0) {
            // The dip begins before the clip does, so the clip must START already ducked. Pin
            // the keyframe to 0 rather than dropping it — dropping it would leave the clip
            // playing at full level underneath a voice that is already talking.
            localMs = 0;
        }
        if (localMs > durationMs) return;
        out.add(new VolumeKeyframe(localMs, value));
    }
}
