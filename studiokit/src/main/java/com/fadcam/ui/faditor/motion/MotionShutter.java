package com.fadcam.ui.faditor.motion;

import java.util.Objects;

/** Converts project shutter controls to a weighted exposure on the editor microsecond clock. */
public final class MotionShutter {
    private MotionShutter() {}

    /** Pass e.g. 24_000/1_001, never an already rounded 23.976. */
    public record FrameRate(int numerator, int denominator) {
        public FrameRate {
            if (numerator <= 0 || denominator <= 0) {
                throw new IllegalArgumentException("Frame rate must be positive");
            }
        }

        public double periodUs() { return 1_000_000.0 * denominator / numerator; }
    }

    public enum Shape { BOX, SOFT }

    public enum Timing {
        CENTERED, TRAILING, LEADING, CUSTOM;

        public double phase(double angle, double customPhase) {
            return switch (this) {
                case CENTERED -> -angle / 2.0;
                case TRAILING -> -angle;
                case LEADING -> 0.0;
                case CUSTOM -> customPhase;
            };
        }
    }

    public enum Preset {
        NATURAL(180, Timing.CENTERED),
        CRISP(90, Timing.CENTERED),
        HEAVY(270, Timing.CENTERED),
        TRAILING(180, Timing.TRAILING);

        public final int angle;
        public final Timing timing;

        Preset(int angle, Timing timing) {
            this.angle = angle;
            this.timing = timing;
        }

        public double phase() { return timing.phase(angle, 0.0); }
    }

    /** STU-7 §K.2: how a blur's window is drawn. Smear is the camera's continuous streak. */
    public enum Look { SMEAR, COPIES, BOTH }

    /** A real shutter reaches at most two frames (last frame to next frame). */
    public static final int SHUTTER_REACH = 720;

    /** Long trails reach this far back: 32 frames (STU-7 spec §K.2). */
    public static final int MAX_TRAIL_ANGLE = 32 * 360;

    /**
     * Saved per-item shutter controls. Older items use the Natural preset.
     *
     * <p>STU-7 §K.2: past {@link #SHUTTER_REACH} the window is a <b>long trail</b> that ends at
     * this frame (timing is forced to trailing). {@code fadePercent}, {@code look} and
     * {@code copies} shape any window. Producers that cannot trail use {@link #schedule}, which
     * caps the window at two frames, so they degrade to the longest real shutter.</p>
     */
    public record Controls(int angle, Timing timing, int customPhase, int mixPercent,
                           int fadePercent, Look look, int copies) {
        public static final Controls NATURAL = fromPreset(Preset.NATURAL);

        public Controls {
            angle = Math.max(0, Math.min(MAX_TRAIL_ANGLE, angle));
            timing = Objects.requireNonNull(timing);
            customPhase = Math.max(-720, Math.min(720, customPhase));
            if (angle > SHUTTER_REACH) {
                timing = Timing.TRAILING;
                customPhase = 0;
            }
            mixPercent = Math.max(0, Math.min(100, mixPercent));
            fadePercent = Math.max(0, Math.min(100, fadePercent));
            look = Objects.requireNonNull(look);
            copies = Math.max(2, Math.min(7, copies));   // RigidShutterPlan.MAX_TRAIL_POSES
        }

        /** The camera-only controls every older caller knows. */
        public Controls(int angle, Timing timing, int customPhase, int mixPercent) {
            this(angle, timing, customPhase, mixPercent, 0, Look.SMEAR, 4);
        }

        public static Controls fromPreset(Preset preset) {
            return new Controls(preset.angle, preset.timing, 0, 100);
        }

        /** Longer than any camera shutter: built from real earlier poses. */
        public boolean isLongTrail() { return angle > SHUTTER_REACH; }

        /** Anything a camera would not do: a long trail, a fade, or copies. */
        public boolean isStylised() { return isLongTrail() || fadePercent > 0 || look != Look.SMEAR; }

        /** The same look with a new window. */
        public Controls withWindow(int newAngle, Timing newTiming, int newPhase) {
            return new Controls(newAngle, newTiming, newPhase, mixPercent, fadePercent, look, copies);
        }

        /** The same window with a new look. */
        public Controls withLook(int fade, Look newLook, int newCopies) {
            return new Controls(angle, timing, customPhase, mixPercent, fade, newLook, newCopies);
        }

        public Controls withMix(int mix) {
            return new Controls(angle, timing, customPhase, mix, fadePercent, look, copies);
        }

        /** Trail weight at {@code u} across the window (0 = oldest, 1 = newest). */
        public double fadeWeight(double u) {
            return 1.0 - fadePercent / 100.0 * (1.0 - Math.max(0, Math.min(1, u)));
        }

        /**
         * Times and weights of the key poses of a stylised window, oldest first, {@code poses} of
         * them evenly spread over the whole window (long trails included), weighted by Fade. The
         * trail pass smears between neighbours for Smear and draws them alone for Copies.
         */
        public Schedule trailPoses(long centerUs, long periodUs, int poses, MotionBreaks breaks) {
            return trailPoses(centerUs, periodUs, poses, breaks, MotionBreaks.none());
        }

        /** {@link #trailPoses} also fenced by changes local to the source (as {@link #schedule}). */
        public Schedule trailPoses(long centerUs, long periodUs, int poses, MotionBreaks breaks,
                                   MotionBreaks source) {
            if (periodUs <= 0 || mixPercent == 0 || angle == 0 || poses < 2) return center(centerUs);
            double openUs = centerUs + phase() * periodUs / 360.0;
            double closeUs = openUs + angle * (double) periodUs / 360.0;
            MotionBreaks.Window window = breaks.splitWindow(centerUs, openUs, closeUs);
            if (!window.hasSamples()) return center(centerUs);
            if (source != MotionBreaks.none()) {
                window = source.splitWindow(centerUs, window.openUs(), window.closeUs());
                if (!window.hasSamples()) return center(centerUs);
            }
            double span = window.closeUs() - window.openUs();
            long[] times = new long[poses];
            double[] weights = new double[poses];
            for (int i = 0; i < poses; i++) {
                double t = window.openUs() + span * i / (poses - 1);
                times[i] = Math.max(window.firstValidUs(), Math.min(window.lastValidUs(), Math.round(t)));
                weights[i] = fadeWeight((t - openUs) / (closeUs - openUs));
            }
            return new Schedule(times, weights);
        }

        public double phase() { return timing.phase(angle, customPhase); }

        public double openOffsetUs(long periodUs) { return phase() * periodUs / 360.0; }

        public double closeOffsetUs(long periodUs) { return (phase() + angle) * periodUs / 360.0; }

        /**
         * Reserve one of the available texture taps for the sharp pose when the mix is partial.
         * Mixing these weights with coverage in linear light preserves premultiplied edges.
         */
        public Schedule schedule(long centerUs, long periodUs, int taps,
                                 MotionBreaks breaks, MotionBreaks sourceBreaks) {
            if (periodUs <= 0 || mixPercent == 0 || angle == 0 || taps <= 1) return center(centerUs);
            var rate = new FrameRate(1_000_000, Math.toIntExact(periodUs));
            int exposureTaps = mixPercent == 100 ? taps : taps - 1;
            // A long trail is capped to the longest real shutter here; only the trail pass
            // draws past it (see trailPoses).
            int shutterAngle = Math.min(angle, SHUTTER_REACH);
            double shutterPhase = isLongTrail() ? -SHUTTER_REACH : phase();
            Schedule exposure = sample(centerUs, rate, shutterAngle, shutterPhase, Shape.BOX,
                    exposureTaps, breaks, sourceBreaks);
            if (mixPercent == 100 || exposure.timesUs().length == 1) return exposure;

            double mix = mixPercent / 100.0;
            int sharpIndex = exposure.timesUs().length;
            long[] times = java.util.Arrays.copyOf(exposure.timesUs(), sharpIndex + 1);
            double[] weights = java.util.Arrays.copyOf(exposure.weights(), sharpIndex + 1);
            for (int i = 0; i < sharpIndex; i++) weights[i] *= mix;
            times[sharpIndex] = centerUs;
            weights[sharpIndex] = 1.0 - mix;
            return new Schedule(times, weights);
        }
    }

    public record Schedule(long[] timesUs, double[] weights) {}

    public static Schedule sample(long centerUs, FrameRate frameRate, double angle, double phase,
                                  Shape shape, int count, MotionBreaks breaks) {
        return sample(centerUs, frameRate, angle, phase, shape, count, breaks, MotionBreaks.none());
    }

    /** Intersect common continuity fences with source changes local to this exposure. */
    public static Schedule sample(long centerUs, FrameRate frameRate, double angle, double phase,
                                  Shape shape, int count, MotionBreaks breaks, MotionBreaks source) {
        Objects.requireNonNull(frameRate);
        Objects.requireNonNull(shape);
        Objects.requireNonNull(breaks);
        if (!Double.isFinite(angle) || !Double.isFinite(phase) || count < 1) {
            throw new IllegalArgumentException("Invalid shutter controls");
        }
        angle = Math.max(0.0, Math.min(720.0, angle));
        if (angle == 0.0 || count == 1) return center(centerUs);

        double durationUs = angle * frameRate.periodUs() / 360.0;
        double openUs = centerUs + phase * frameRate.periodUs() / 360.0;
        MotionBreaks.Window window = breaks.splitWindow(centerUs, openUs, openUs + durationUs);
        if (!window.hasSamples()) return center(centerUs);
        if (source != MotionBreaks.none()) {
            window = source.splitWindow(centerUs, window.openUs(), window.closeUs());
            if (!window.hasSamples()) return center(centerUs);
        }

        long[] times = new long[count];
        double[] weights = new double[count];
        double spacing = (window.closeUs() - window.openUs()) / count;
        double total = 0.0;
        for (int i = 0; i < count; i++) {
            double time = window.openUs() + (i + 0.5) * spacing;
            times[i] = Math.max(window.firstValidUs(),
                    Math.min(window.lastValidUs(), Math.round(time)));
            double u = (time - openUs) / durationUs;
            weights[i] = shape == Shape.BOX ? 1.0 : Math.max(0.0, 4.0 * u * (1.0 - u));
            total += weights[i];
        }
        if (total == 0.0) return center(centerUs);
        for (int i = 0; i < count; i++) weights[i] /= total;
        return new Schedule(times, weights);
    }

    private static Schedule center(long centerUs) {
        return new Schedule(new long[]{centerUs}, new double[]{1.0});
    }
}
