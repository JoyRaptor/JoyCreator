package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;

import java.util.Map;

/**
 * A2 (PLAN_AVATAR_STUDIO): one raw sample from a {@link TrackingSource} —
 * everything a tracker knows at one instant, expressed as PLAIN DRIVER PARAMS.
 * The param map IS the tracking contract: head rotation lands on the names the
 * rig's pose domains declare ({@code yaw}/{@code pitch}/{@code roll},
 * normalized [-1..1]), limb IK targets land on
 * {@code pinTarget.<partId>.x|.y} (view-normalized 0..1, consumed by the
 * renderer's FABRIK hookup), face blendshapes land on their MediaPipe-style
 * names ({@code jawOpen}, {@code blinkL}…, 0..1). Because a frame is ONLY
 * params + a timestamp, the bake-to-parameter-track doctrine gets recording
 * for free — a baked track replays frames through the same pipeline.
 *
 * <p>{@code audioDb} feeds the {@link LifeSignals} idle gate and the
 * {@link AudioLevelViseme} amplitude tier; sources without a mic pass
 * {@link Float#NaN} (treated as silence — the life package stays available).</p>
 *
 * <p>A3 v2: {@code visemeClassIndex} carries the {@link SpectralVisemeAnalyzer}
 * class index ({@code -1} = no spectral tier available, e.g. no mic or the
 * amplitude-only fallback) OUT-OF-BAND from {@code params} — like
 * {@code audioDb}, it must NOT pass through {@link DriverParamSmoother}'s
 * One-Euro filter (the analyzer's own attack/release + hysteresis IS the
 * smoothing; a discrete class index is a threshold signal, not a continuous
 * one — see {@link TrackingParamPipeline}'s class doc, same rule as
 * {@code jawOpen}).</p>
 */
public final class TrackingFrame {

    /** Prefix + suffixes of the limb IK target convention. */
    public static final String PIN_TARGET_PREFIX = "pinTarget.";
    public static final String PIN_TARGET_X = ".x";
    public static final String PIN_TARGET_Y = ".y";

    /** {@link #visemeClassIndex} sentinel: no spectral classification this frame. */
    public static final int NO_VISEME = -1;

    /** Monotonic sample time in seconds (tracker clock, not wall time). */
    public final double tSeconds;
    /** Raw (unsmoothed) continuous driver params — see class doc for names. */
    @NonNull public final Map<String, Float> params;
    /** Input level for the life gate / amplitude visemes; NaN = no mic. */
    public final float audioDb;
    /** {@link SpectralVisemeAnalyzer} class index, or {@link #NO_VISEME}. */
    public final int visemeClassIndex;

    public TrackingFrame(double tSeconds, @NonNull Map<String, Float> params, float audioDb) {
        this(tSeconds, params, audioDb, NO_VISEME);
    }

    public TrackingFrame(double tSeconds, @NonNull Map<String, Float> params, float audioDb,
                          int visemeClassIndex) {
        this.tSeconds = tSeconds;
        this.params = params;
        this.audioDb = audioDb;
        this.visemeClassIndex = visemeClassIndex;
    }

    /** Compose a pin-target param name for a part. */
    @NonNull
    public static String pinTargetX(@NonNull String partId) {
        return PIN_TARGET_PREFIX + partId + PIN_TARGET_X;
    }

    @NonNull
    public static String pinTargetY(@NonNull String partId) {
        return PIN_TARGET_PREFIX + partId + PIN_TARGET_Y;
    }
}
