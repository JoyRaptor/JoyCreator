package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * A2 (PLAN_AVATAR_STUDIO): the pure per-frame stage between a
 * {@link TrackingSource} and {@link PuppetPoseResolver#resolve} — the ONE
 * place raw tracking becomes resolver-ready driver params. Order (each rule
 * has a reason):
 * <ol>
 *   <li>ONE-EURO SMOOTHING of every source param via
 *       {@link DriverParamSmoother} (the MINED "smooth ALL continuous
 *       inputs" rule — including {@code pinTarget.*} IK drivers).</li>
 *   <li>{@code jawOpen} AMPLITUDE FALLBACK: a face tracker that emits
 *       {@code jawOpen} wins (already smoothed above); otherwise, when the
 *       frame carries a mic level, {@link AudioLevelViseme} drives it. The
 *       viseme's own attack/release envelope IS its smoothing — it must not
 *       pass through One-Euro too (double-filtering adds lag).</li>
 *   <li>A3 v2 SPECTRAL TIER: when {@link TrackingFrame#visemeClassIndex} is
 *       not {@link TrackingFrame#NO_VISEME}, emit it as the {@code viseme}
 *       driver param (the {@link SpectralVisemeAnalyzer}'s own hysteresis IS
 *       its smoothing — same never-One-Euro-a-threshold-signal rule as
 *       jawOpen). This COEXISTS with, never replaces, the amplitude tier:
 *       jawOpen keeps driving mouth OPENING from dB exactly as above; the
 *       spectral class additionally drives mouth SHAPE. A rig with no
 *       "viseme"-driven pose domain simply ignores the extra param.</li>
 *   <li>{@link LifeSignals} MERGE, after smoothing: life params are
 *       deterministic micro-motion, and One-Euro would delay the blink
 *       envelopes it deliberately shapes (the smoother's own "never smooth a
 *       threshold signal" rule). Life names ({@code life_*}) are disjoint
 *       from tracker names, so merge = put.</li>
 * </ol>
 *
 * <p>PURE + DETERMINISTIC: no clocks, no threads; all state advances only via
 * {@link #process}, and the life package's randomness is the seeded LCG — so
 * a baked frame sequence replays to identical params (bake-to-param-track
 * doctrine). Thread-confinement is the caller's job: the bus runs this on the
 * source's thread only.</p>
 */
public class TrackingParamPipeline {

    /** Sources without a mic read as silence — the life gate can engage. */
    private static final float NO_MIC_DB = -60f;

    private final DriverParamSmoother smoother = new DriverParamSmoother();
    private final AudioLevelViseme viseme = new AudioLevelViseme();
    private final LifeSignals life;

    public TrackingParamPipeline(long lifeSeed) {
        this.life = new LifeSignals(lifeSeed);
    }

    /** Current idle-life blend weight (0 live … 1 idle) — for debug overlays. */
    public double lifeWeight() {
        return life.getWeight();
    }

    /** One frame in → resolver-ready params out. Caller-thread-confined. */
    @NonNull
    public Map<String, Float> process(@NonNull TrackingFrame frame) {
        float db = Float.isNaN(frame.audioDb) ? NO_MIC_DB : frame.audioDb;

        Map<String, Float> out =
                new HashMap<>(smoother.smooth(frame.params, frame.tSeconds));

        if (!out.containsKey(AudioLevelViseme.PARAM_JAW_OPEN)
                && !Float.isNaN(frame.audioDb)) {
            out.put(AudioLevelViseme.PARAM_JAW_OPEN, viseme.update(frame.tSeconds, db));
        }

        if (frame.visemeClassIndex != TrackingFrame.NO_VISEME) {
            out.put(SpectralVisemeAnalyzer.PARAM_VISEME, (float) frame.visemeClassIndex);
        }

        out.putAll(life.update(frame.tSeconds, db));
        return out;
    }

    /** Tracking lost / source swapped: snap to the next truth, never glide. */
    public void reset() {
        smoother.reset();
        viseme.reset();
        // LifeSignals keeps its schedule — idle motion continuing across a
        // source swap is correct (the puppet shouldn't "die" for a beat).
    }
}
