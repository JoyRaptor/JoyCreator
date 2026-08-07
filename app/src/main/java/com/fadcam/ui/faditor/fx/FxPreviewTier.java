package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

/**
 * What the LIVE PREVIEW can actually show on this device (SPEC_ADJUSTMENT_LAYERS_FX M5).
 *
 * <p><b>Decided in ONE place on purpose.</b> The picker badges each effect with what the user
 * will see before they add it, the preview controller decides what to attempt, and the panel
 * header explains any gap. If those three asked the question separately they would eventually
 * answer it differently, and the failure mode is the worst kind: a UI that promises a live
 * effect the renderer silently cannot produce.</p>
 *
 * <p>The floor is real: {@code minSdk} is 24, {@code RenderEffect} arrives at 31 and AGSL at 33.
 * Roughly three in four phones in use are tier A. Export is correct on ALL of them — this only
 * ever describes the preview.</p>
 */
public final class FxPreviewTier {

    private FxPreviewTier() {}

    public enum Tier {
        /** SDK ≥ 33: the full AGSL chain. What the effect looks like here is what exports. */
        FULL,
        /** SDK 31–32: RenderEffect exists but AGSL does not. Blur only, and marked approximate. */
        PARTIAL,
        /** SDK < 31: no RenderEffect at all. The layer badges "applies on export". */
        EXPORT_ONLY
    }

    /** The tier for the running device. */
    @NonNull
    public static Tier current() {
        return of(android.os.Build.VERSION.SDK_INT);
    }

    /** Testable form — the harness cannot move {@code Build.VERSION}. */
    @NonNull
    public static Tier of(int sdkInt) {
        if (sdkInt >= 33) return Tier.FULL;
        if (sdkInt >= 31) return Tier.PARTIAL;
        return Tier.EXPORT_ONLY;
    }

    /**
     * Can {@code def} be previewed live at {@code tier}?
     *
     * <p>PARTIAL is deliberately narrow. {@code RenderEffect.createBlurEffect} is Skia's blur and
     * this project ships its OWN Gaussian precisely because the two differ visibly at the same
     * radius — so on tier B a blur is shown as an APPROXIMATION and everything else is skipped
     * rather than faked. A colour effect could be half-approximated with a ColorMatrix, but a
     * "nearly right" grade is exactly the thing nobody notices is wrong until export.</p>
     */
    public static boolean canPreview(@NonNull FxEffectDef def, @NonNull Tier tier) {
        switch (tier) {
            case FULL:
                return true;
            case PARTIAL:
                return def.family == FxEffectDef.Family.BLUR;
            case EXPORT_ONLY:
            default:
                return false;
        }
    }

    /** @see #canPreview(FxEffectDef, Tier) */
    public static boolean canPreview(@NonNull FxEffectDef def) {
        return canPreview(def, current());
    }

    /**
     * The badge for an effect in the picker, or {@code ""} when there is nothing to say.
     *
     * <p>Empty for the common case on purpose: a badge on every single entry is noise, and noise
     * is what stops the one that matters from being read.</p>
     */
    @NonNull
    public static String badge(@NonNull FxEffectDef def, @NonNull Tier tier) {
        if (tier == Tier.FULL) return "";
        if (!canPreview(def, tier)) return "export only";
        return "approx";
    }

    /** One line for the FX panel header, or {@code ""} when the preview is fully faithful. */
    @NonNull
    public static String headerNote(@NonNull Tier tier) {
        switch (tier) {
            case PARTIAL:
                return "This phone previews blur only, and approximately. "
                        + "Export applies every effect exactly.";
            case EXPORT_ONLY:
                return "This phone can't preview effects live. "
                        + "They are applied in full when you export.";
            case FULL:
            default:
                return "";
        }
    }
}
