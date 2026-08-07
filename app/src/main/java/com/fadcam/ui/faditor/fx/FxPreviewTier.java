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
 * <p><b>The API floor is real; the DEVICE floor was not.</b> {@code RenderEffect} arrives at 31
 * and AGSL at 33, so {@link Tier} below is an accurate description of what that ONE backend can
 * do. It was read for a while as "phones under Android 12 cannot preview effects", which is a
 * different and false claim: {@code minSdk} here is 24 and the project's own sandbox device is a
 * Note 9 on API 29 whose Adreno 630 runs OpenGL ES 3.2. {@code FxPreviewTextureView} previews the
 * full chain there by running the export's own GLSL, the same way this app's live chroma key and
 * live transitions already did on that phone. {@link #backend} is what the UI should ask;
 * {@link Tier} answers only "what could RenderEffect manage here".</p>
 */
public final class FxPreviewTier {

    private FxPreviewTier() {}

    /**
     * Which renderer draws the live preview.
     *
     * <p><b>GL is used everywhere, deliberately.</b> It is not a fallback for old phones. It
     * compiles {@link FxGlSource}, the byte-identical source the export compiles, at the same
     * kernel width, through the same multi-pass ping-pong — so "what you see is what exports" is
     * true by construction rather than by two implementations being carefully kept in step. The
     * AGSL path is a TRANSLATION of those bodies, and translations drift. Flip {@link #USE_GL} to
     * put new phones back on {@code RenderEffect}; nothing else needs to change.</p>
     */
    public enum Backend {
        /** {@code FxPreviewTextureView}: the export's shaders, on the decoder, at any API ≥ 24. */
        GL,
        /** {@code AdjustmentPreviewController}: {@code RenderEffect} over the view subtree. */
        RENDER_EFFECT,
        /** Nothing can preview — no longer reachable, kept so the UI copy has somewhere to go. */
        NONE
    }

    /** @see Backend */
    private static final boolean USE_GL = true;

    /** The renderer that will actually draw the preview on this device. */
    @NonNull
    public static Backend backend() {
        if (USE_GL) return Backend.GL;
        Tier t = current();
        return t == Tier.EXPORT_ONLY ? Backend.NONE : Backend.RENDER_EFFECT;
    }

    /** Whether the editor should route the decoder through the GL preview renderer. */
    public static boolean usesGl() {
        return backend() == Backend.GL;
    }

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

    /**
     * Can {@code def} be previewed live on THIS device, by whichever backend will draw it?
     *
     * <p>This is the question the UI actually has. Under {@link Backend#GL} the answer is yes for
     * everything, because the preview runs the export's own program — there is no subset.</p>
     */
    public static boolean canPreview(@NonNull FxEffectDef def) {
        switch (backend()) {
            case GL: return true;
            case NONE: return false;
            default: return canPreview(def, current());
        }
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

    /**
     * Can this effect be RENDERED ON EXPORT yet?
     *
     * <p>Separate from the preview tiers and nothing to do with the device.
     * {@code AdjustmentLayerGlEffect} renders a single FUSED pass today; a SAMPLER card needs a
     * finished image to read neighbours from, which means ping-pong FBOs that are not built.
     * Such a card is skipped at render time with a log — correct, since a wrong blur that looks
     * plausible is worse than an absent one, but invisible unless the UI says so.</p>
     *
     * <p>This is the single place that knows, so the card badge and any future warning cannot
     * disagree with what the renderer actually does.</p>
     */
    public static boolean canExport(@NonNull FxEffectDef def) {
        // On an ADJUSTMENT LAYER every capability renders: AdjustmentLayerGlEffect compiles one
        // program per pass and ping-pongs through its own FBOs, so a SAMPLER card gets the
        // finished image it needs.
        return true;
    }

    /** What the stack is attached to. The same effect is not equally supported on both. */
    public enum Subject {
        /** An adjustment layer: multi-pass, every capability. */
        LAYER,
        /** One object (a PiP): its FX are spliced into the compositing shader, single pass. */
        OBJECT
    }

    /**
     * Can {@code def} render on {@code subject}?
     *
     * <p>An adjustment layer owns its own FBOs, so a blur works. A PER-OBJECT stack is spliced
     * into {@code BlendModeGlEffect}'s shader, which composites the object in ONE pass and has
     * no finished image for a SAMPLER card to read neighbours from. Giving one answer for both
     * would badge a blur as fine and then quietly skip it on a PiP.</p>
     */
    public static boolean canExportOn(@NonNull FxEffectDef def, @NonNull Subject subject) {
        if (subject == Subject.LAYER) return canExport(def);
        return def.capability != FxEffectDef.Capability.SAMPLER;
    }

    /** The picker badge for THIS device, by whichever backend will draw the preview. */
    @NonNull
    public static String badge(@NonNull FxEffectDef def) {
        switch (backend()) {
            case GL: return "";
            case NONE: return "export only";
            default: return badge(def, current());
        }
    }

    /** The card note for THIS device. @see #cardNote(FxEffectDef, Tier, Subject) */
    @NonNull
    public static String cardNote(@NonNull FxEffectDef def, @NonNull Subject subject) {
        if (!canExportOn(def, subject)) {
            return subject == Subject.OBJECT
                    ? "needs an adjustment layer" : "multi-pass — not rendered yet";
        }
        switch (backend()) {
            case GL: return "";
            case NONE: return "export only";
            default: return cardNote(def, current(), subject);
        }
    }

    /**
     * One line for the FX panel header on THIS device, or {@code ""} when the preview is
     * faithful.
     *
     * <p>Under GL the honest remaining gap is not the effects — it is REACH. The preview grades
     * the video plane; the export chain also carries any PiP composited beneath the layer. For a
     * layer over plain footage, which is the ordinary case, there is nothing to say, so it says
     * nothing rather than spending the user's attention on a caveat that does not apply to
     * them.</p>
     */
    @NonNull
    public static String headerNote() {
        switch (backend()) {
            case GL: return "";
            case NONE: return headerNote(Tier.EXPORT_ONLY);
            default: return headerNote(current());
        }
    }

    /**
     * What to say ON a card about where it will and will not appear, or {@code ""} when it
     * works everywhere.
     */
    @NonNull
    public static String cardNote(@NonNull FxEffectDef def, @NonNull Tier tier) {
        return cardNote(def, tier, Subject.LAYER);
    }

    /** @see #canExportOn */
    @NonNull
    public static String cardNote(@NonNull FxEffectDef def, @NonNull Tier tier,
                                  @NonNull Subject subject) {
        if (!canExportOn(def, subject)) {
            return subject == Subject.OBJECT
                    ? "needs an adjustment layer" : "multi-pass — not rendered yet";
        }
        if (!canPreview(def, tier)) return "export only";
        if (tier == Tier.PARTIAL) return "preview approximate";
        return "";
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
