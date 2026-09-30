package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

/**
 * What the LIVE PREVIEW can show, and what a card must therefore say about itself.
 *
 * <p><b>Decided in ONE place on purpose.</b> The picker badges each effect with what the user
 * will see before they add it and the panel header explains any gap. If those asked the question
 * separately they would eventually answer it differently, and the failure mode is the worst kind:
 * a UI that promises a live effect the renderer silently cannot produce.</p>
 *
 * <p><b>There is no device tier any more, and the history is worth keeping.</b> This class used
 * to grade phones A/B/C by {@code RenderEffect} (API 31) and AGSL (API 33), which made the
 * project's own Note 9 on API 29 "permanently tier C — export only". The API numbers were right
 * and the conclusion was wrong: that phone is an Adreno 630 running OpenGL ES 3.2, and
 * {@code FxPreviewTextureView} previews the entire chain on it by compiling the EXPORT's own
 * GLSL. Since the preview now runs the same GL the export requires, a device that cannot preview
 * cannot export either — so there is nothing left for a tier to describe.</p>
 *
 * <p>What remains is a genuine, device-independent limit: a SAMPLER effect on a single OBJECT.
 * That is about where the effect is attached, not what the phone can do.</p>
 */
public final class FxPreviewTier {

    private FxPreviewTier() {}

    /**
     * Can {@code def} be previewed live?
     *
     * <p>Always, and that is the point: the preview compiles {@link FxGlSource}, the identical
     * program the export compiles, so there is no subset it can fall short of.</p>
     */
    public static boolean canPreview(@NonNull FxEffectDef def) {
        return true;
    }

    /** Whether the editor routes the decoder through the GL preview renderer. It always does. */
    public static boolean usesGl() {
        return true;
    }

    /**
     * Can this effect be RENDERED ON EXPORT?
     *
     * <p>{@code AdjustmentLayerGlEffect} compiles one program per pass and ping-pongs through its
     * own FBOs, so a SAMPLER card gets the finished image it needs.</p>
     */
    public static boolean canExport(@NonNull FxEffectDef def) {
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
     * into {@code BlendModeGlEffect}'s shader, which composites the object in ONE pass and has no
     * finished image for a SAMPLER card to read neighbours from. Giving one answer for both would
     * badge a blur as fine and then quietly skip it on a PiP.</p>
     */
    public static boolean canExportOn(@NonNull FxEffectDef def, @NonNull Subject subject) {
        if (subject == Subject.LAYER) return canExport(def);
        return def.capability != FxEffectDef.Capability.SAMPLER;
    }

    /**
     * The picker badge for {@code def}, or {@code ""} when there is nothing to say.
     *
     * <p>Empty for every effect now. Kept as the one place that decides, so re-introducing a
     * limit means changing this rather than sprinkling strings through the panel.</p>
     */
    @NonNull
    public static String badge(@NonNull FxEffectDef def) {
        return "";
    }

    /**
     * What to say ON a card, or {@code ""} when it works everywhere.
     *
     * <p>Always empty now. C21: the old branch {@code !canExportOn(def, LAYER)} was dead on
     * arrival — {@code canExportOn} returns {@code canExport(def)} for a LAYER, and
     * {@code canExport} is unconditionally true (the adjustment-layer exporter compiles one
     * program per pass and ping-pongs its own FBOs), so the condition could never fire. Kept as
     * the one place that decides, so re-introducing a per-subject note means changing this rather
     * than sprinkling strings through the panel.</p>
     */
    @NonNull
    public static String cardNote(@NonNull FxEffectDef def, @NonNull Subject subject) {
        return "";
    }

    /**
     * One line for the FX panel header, or {@code ""} when the preview is faithful.
     *
     * <p>Empty: there is no longer a device-shaped gap to warn about. The remaining caveat — a
     * chroma-keyed PiP, which keeps its own live tier — depends on the PROJECT rather than the
     * phone, so the panel asks its host for that one instead of asking here.</p>
     */
    @NonNull
    public static String headerNote() {
        return "";
    }
}
