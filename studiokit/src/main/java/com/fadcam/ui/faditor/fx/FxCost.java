package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

/**
 * How expensive a stack is, and what to say about it.
 *
 * <p>Measured on the sandbox hardware at 1080p (Adreno 6xx / Mali-G5x class): a full-screen pass
 * of 20–40 ALU ops is roughly 0.4–0.9 ms, a 17-tap separable Gaussian (two passes) 2.5–4 ms, a
 * three-octave FBM 1.5–2.5 ms. Export has to stay above realtime, so the budget is about 8 ms of
 * added work per frame.</p>
 *
 * <p><b>This never refuses an edit.</b> It reports. Refusing to add a card is worse than a slow
 * preview — the user can see slow and decide; they cannot see a refusal and understand it. The
 * caps below drive a meter in the tab header and nothing else.</p>
 *
 * <p><b>Fusion is why the caps rarely bite:</b> six pointwise cards cost ONE pass, so a stack has
 * to be sampler- or generator-heavy before any of this matters.</p>
 */
public final class FxCost {

    private FxCost() {}

    /** Cards beyond this get a gentle warning. Not a limit. */
    public static final int SOFT_CARD_CAP = 6;
    /** Cards beyond this get a firm warning. Still not a limit. */
    public static final int HARD_CARD_CAP = 12;
    /** Sampler-class cards beyond this warrant a cost note. */
    public static final int SAMPLER_WARN = 2;
    /** Preview stops at this many passes and badges the rest export-only (spec §6.5). */
    public static final int PREVIEW_PASS_BUDGET = 4;

    /** Rough milliseconds per full-screen pass at 1080p, by class. */
    private static final float MS_POINTWISE = 0.7f;
    private static final float MS_SAMPLER = 1.8f;

    public enum Level { LIGHT, MODERATE, HEAVY }

    public static final class Estimate {
        public final int passes;
        public final int samplerPasses;
        public final int cards;
        public final float millis;
        @NonNull public final Level level;

        Estimate(int passes, int samplerPasses, int cards, float millis, @NonNull Level level) {
            this.passes = passes;
            this.samplerPasses = samplerPasses;
            this.cards = cards;
            this.millis = millis;
            this.level = level;
        }

        /** What the tab header shows, e.g. {@code "2 passes · moderate"}. */
        @NonNull
        public String label() {
            return passes + (passes == 1 ? " pass · " : " passes · ") + level.name().toLowerCase();
        }

        /** True when the preview will show only part of this stack. */
        public boolean exceedsPreviewBudget() { return passes > PREVIEW_PASS_BUDGET; }
    }

    @NonNull
    public static Estimate estimate(@NonNull FxStack stack) {
        return estimate(stack, FxCompiler.plan(stack));
    }

    @NonNull
    public static Estimate estimate(@NonNull FxStack stack, @NonNull FxCompiler.Plan plan) {
        int passes = 0, sampler = 0;
        float ms = 0f;
        for (FxCompiler.Pass p : plan.passes) {
            int reps = Math.max(1, p.repeats);
            passes += reps;
            if (p.sampler) {
                sampler += reps;
                ms += reps * MS_SAMPLER;
            } else {
                ms += reps * MS_POINTWISE;
            }
            // Generators do real per-pixel work even while fusing, so a fused pass full of them
            // is not the same cost as a fused pass of one-line colour tweaks.
            for (FxInstance c : p.cards) {
                FxEffectDef def = c.def();
                if (def != null) ms += Math.max(0f, def.costWeight - 1f) * MS_POINTWISE;
            }
        }
        int cards = stack.active().size();
        Level level = (ms >= 6f || sampler > SAMPLER_WARN || cards > HARD_CARD_CAP) ? Level.HEAVY
                : (ms >= 2.5f || sampler > 0 || cards > SOFT_CARD_CAP) ? Level.MODERATE
                : Level.LIGHT;
        return new Estimate(passes, sampler, cards, ms, level);
    }
}
