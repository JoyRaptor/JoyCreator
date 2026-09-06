package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.effects.EffectStack;

/**
 * ONE-WAY, ONE-TIME conversion of the legacy per-clip {@link EffectStack} grade into
 * {@link FxInstance} cards on the same object's {@link FxStack} — run at PROJECT LOAD, in
 * {@code ProjectStorage}, so nothing in the editor ever sees two live grading systems again.
 *
 * <h3>Why this exists</h3>
 * <p>{@code Clip} carried BOTH systems: a fixed struct of ten grading floats plus a LUT
 * ({@code EffectStack}, rendered by {@code ColorGradeShaderProgram} / {@code ColorGradeGlSource})
 * and the stackable, reorderable, keyframable {@code FxStack} that {@code AdjustmentLayer},
 * {@code Clip} and {@code TextOverlayItem} all share. Two things writing the same pixels is the
 * bug. This moves the ten floats onto the universal system and marks the old struct consumed.</p>
 *
 * <h3>The look must not change — how that is guaranteed</h3>
 * <ul>
 *   <li>The GLSL in {@code FxRegistry.BODY_COLOR_GRADE} and {@code BODY_FILM} is a port of the
 *       maths the old system ran, stage for stage, clamp for clamp, threshold for threshold —
 *       see those two constants' notes for the per-stage evidence.</li>
 *   <li>The parameter RANGES and DEFAULTS declared for {@code color_grade} and {@code film} are
 *       {@code EffectStack}'s own setter clamps, field for field, so {@code FxParam.clamp()}
 *       cannot move a migrated value: every value that could be stored in an {@code EffectStack}
 *       is already inside the corresponding {@code FxParam}'s rails.</li>
 *   <li>Cards are inserted at the BOTTOM of the stack, not appended. Chain position is
 *       application order in both renderers, and {@code ExportManager.assembleClipVideoEffects}
 *       adds {@code EffectStack.toEffects} BEFORE the FX layer — so a clip that already had FX
 *       cards must keep its grade underneath them.</li>
 *   <li>Two cards, not one and not ten. {@code color_grade} is the matrix/HSL half (one pass, and
 *       nobody reorders "contrast before saturation"); {@code film} is the five parameters the
 *       old custom shader ran as a single UNCLAMPED block, which is why they cannot be split.</li>
 * </ul>
 *
 * <h3>THE LUT IS NOT MIGRATED</h3>
 * <p>Deliberately, and it is not a shortcut. A LUT is a 3D texture lookup: {@code LutManager}
 * bakes a {@code .cube} into an {@code N x N*N} bitmap and hands it to media3's
 * {@code SingleColorLut}. The FX system has no way to express that today — {@code FxParam.Kind}
 * has no texture/asset kind (every kind packs to floats), {@code FxCompiler} emits exactly one
 * input sampler per pass ({@code uTexSampler} / {@code inputShader}) and has no mechanism for
 * binding a second one, and {@code FxEffectDef.Capability.SAMPLER} means "reads NEIGHBOUR pixels
 * of the pass input", not "binds another texture". Declaring a {@code lut} effect POINTWISE with
 * a float "id" parameter would be a slider wired to nothing. So the LUT stays on the old path:
 * {@code EffectStack.isActive()} still returns true for it and {@code toEffects} still emits it.
 * Closing that gap needs a new {@code FxParam.Kind} plus a sampler-binding path through
 * {@code FxCompiler}, {@code FxGlSource} and {@code AdjustmentLayerGlEffect} — a separate change.</p>
 *
 * <p>Unlike the rest of {@code fx/}, this class is NOT Android-free: it names {@link EffectStack},
 * which pulls in media3. That is unavoidable — it is the bridge between the two systems — and it
 * is why the maths itself lives in {@code FxRegistry} (which stays harness-reachable) rather than
 * here.</p>
 */
public final class FxGradeMigration {

    /** Registry id of the matrix/HSL half. Wire value — see {@code FxRegistry}'s class note. */
    public static final String EFFECT_COLOR_GRADE = "color_grade";
    /** Registry id of the old custom-shader half. */
    public static final String EFFECT_FILM = "film";

    /** {@code EffectStack.toEffects}' own activation threshold, reproduced so nothing drifts. */
    private static final float EPS = 0.001f;

    private FxGradeMigration() {}

    /**
     * Convert {@code stack}'s grading half into cards on {@code fx}, once.
     *
     * <p><b>Idempotent by a PERSISTED flag, not by inspection.</b> {@code EffectStack.fxMigrated}
     * is written into the project file, so load → save → load takes the early return on the
     * second load and cannot stack a second copy. Inspecting the stack for an existing
     * {@code color_grade} card would have been wrong in the other direction: a user is entitled
     * to add a second Color Grade card by hand, and that must not block a migration or be
     * mistaken for one.</p>
     *
     * <p><b>A DEFAULT stack is left completely alone</b> — no cards, no flag — so a project that
     * never used the Filters sheet re-saves byte for byte (both {@code effectStack} and
     * {@code fx} stay omitted by {@code ProjectStorage}'s existing gates).</p>
     *
     * @return true when cards were added, i.e. when the caller's model actually changed.
     */
    public static boolean migrate(@Nullable EffectStack stack, @Nullable FxStack fx) {
        if (stack == null || fx == null) return false;
        if (stack.isFxMigrated()) return false;
        if (!stack.hasGrade()) return false;

        // Bottom-up, in the old chain's order: matrix/HSL half first, custom-shader half second.
        // insertAtBottom() puts each at the given index, so the pair ends up [color_grade, film,
        // ...whatever the clip already had].
        int at = 0;
        if (hasColorGrade(stack)) {
            FxInstance c = fx.add(EFFECT_COLOR_GRADE);
            FxEffectDef def = c.def();
            if (def != null) {
                setIf(c, def, "exposure", stack.getExposure());
                setIf(c, def, "contrast", stack.getContrast());
                setIf(c, def, "saturation", stack.getSaturation());
                setIf(c, def, "temperature", stack.getTemperature());
                setIf(c, def, "tint", stack.getTint());
            }
            fx.move(fx.size() - 1, at++);
        }
        if (hasFilm(stack)) {
            FxInstance c = fx.add(EFFECT_FILM);
            FxEffectDef def = c.def();
            if (def != null) {
                setIf(c, def, "highlights", stack.getHighlights());
                setIf(c, def, "shadows", stack.getShadows());
                setIf(c, def, "fade", stack.getFade());
                setIf(c, def, "vignette", stack.getVignette());
                setIf(c, def, "grain", stack.getGrain());
            }
            fx.move(fx.size() - 1, at);
        }

        // CONSUMED. The floats stay readable (see EffectStack.fxMigrated) but stop painting.
        stack.setFxMigrated(true);
        return true;
    }

    /** Whether the matrix/HSL half would do anything — {@code toEffects}' four gates, OR'd. */
    private static boolean hasColorGrade(@NonNull EffectStack s) {
        return Math.abs(s.getExposure()) > EPS
                || Math.abs(s.getContrast()) > EPS
                || Math.abs(s.getSaturation() - 1f) > EPS
                || Math.abs(s.getTemperature()) > EPS
                || Math.abs(s.getTint()) > EPS;
    }

    /** Whether the custom-shader half would do anything — {@code hasCustomShaderAdjustments}. */
    private static boolean hasFilm(@NonNull EffectStack s) {
        return Math.abs(s.getHighlights()) > EPS
                || Math.abs(s.getShadows()) > EPS
                || Math.abs(s.getFade()) > EPS
                || s.getVignette() > EPS
                || s.getGrain() > EPS;
    }

    /**
     * Store {@code v} on the card only when it differs from the descriptor's default.
     *
     * <p>An unset parameter reads back as that default ({@link FxInstance#get}), so writing one
     * would change nothing in the picture — but it WOULD be persisted, and it would make a
     * migrated card's JSON differ from a hand-built card holding the same values. Cheaper to keep
     * the file honest about what the user actually dialled.</p>
     */
    private static void setIf(@NonNull FxInstance card, @NonNull FxEffectDef def,
                              @NonNull String param, float v) {
        FxParam p = def.param(param);
        if (p == null) return;                       // registry lost the param — skip, never crash
        if (Math.abs(v - p.defaultScalar()) <= 0f) return;
        card.set(p, v);
    }
}
