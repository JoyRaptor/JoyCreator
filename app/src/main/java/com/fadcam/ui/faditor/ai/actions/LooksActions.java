package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.fx.FxEffectDef;
import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxParam;
import com.fadcam.ui.faditor.fx.FxPresetStore;
import com.fadcam.ui.faditor.fx.FxPreviewTier;
import com.fadcam.ui.faditor.fx.FxRegistry;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.BlendModes;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The looks family (gap F2): the Effects drawer's colour grade, the Blend mode chip, the Chroma key
 * tab and the saved looks. Joybot cannot touch pixels, so each action writes the same model fields
 * the drawer writes, and nothing else.
 *
 * <p>Ranges are the drawers' own sliders: FxRegistry's color_grade and film cards (the grade's
 * slider min..max, which is also what the clamp enforces) and the chroma tab's bars.</p>
 */
final class LooksActions {
    private LooksActions() { }

    private static final String FAMILY = "looks";
    private static final String GRADE = "color_grade";
    private static final String FILM = "film";

    /** Sliders of the Color Grade card. */
    private static final String[] COLOUR = {"exposure", "contrast", "saturation", "temperature", "tint"};
    /** Sliders of the Film Look card (the custom-shader half of the old Filters sheet). */
    private static final String[] FILM_PARAMS = {"highlights", "shadows", "fade", "vignette", "grain"};

    static final List<String> MODES = Arrays.asList(BlendModes.ALL);

    private static final String TARGET_HELP =
            "Which clip or layer: its id or its name. \"all\" = every clip on the main track";
    private static final String LAYER_HELP =
            "Which floating video, picture or adjustment layer: its id or its name";

    /** What a reference may point at. One bit each, so a caller says exactly what it accepts. */
    private static final int MASTER = 1, OVERLAY = 2, PICTURE = 4, ADJUST = 8, VISUALIZER = 16;

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("set_color_grade", "Change the colour grade")
                .help("The colour grade of a clip or an adjustment layer: exposure, contrast, saturation,"
                        + " temperature, tint, highlights, shadows, fade, vignette and grain. Send only what"
                        + " should change; each value is set, not added to. \"all\" grades every clip on the"
                        + " main track.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap a clip or adjustment layer › Effects")
                .id("target", "any", true, TARGET_HELP)
                .number("exposure", false, "Brightness: -1 darker … 1 brighter; 0 is unchanged", -1.0, 1.0)
                .number("contrast", false, "Contrast: -1 flatter … 1 punchier; 0 is unchanged", -1.0, 1.0)
                .number("saturation", false, "Colour strength: 0 grey … 1 unchanged … 2 very vivid", 0.0, 2.0)
                .number("temperature", false, "Temperature: -1 … 1; 0 is unchanged", -1.0, 1.0)
                .number("tint", false, "Tint: -1 … 1; 0 is unchanged", -1.0, 1.0)
                .number("highlights", false, "Bright parts: -1 … 1; 0 is unchanged", -1.0, 1.0)
                .number("shadows", false, "Dark parts: -1 … 1; 0 is unchanged", -1.0, 1.0)
                .number("fade", false, "Faded, washed-out look: -1 … 1; 0 is unchanged", -1.0, 1.0)
                .number("vignette", false, "Darker edges of the frame: 0 none … 1 strong", 0.0, 1.0)
                .number("grain", false, "Film grain: 0 none … 1 strong", 0.0, 1.0)
                .build(), LooksActions::setGrade);

        r.add(ActionSpec.of("set_blend_mode", "Change the blend mode")
                .help("How a floating video, a picture, a visualizer or an adjustment layer mixes with what is under it."
                        + " Normal is the plain layer; the others are the standard blend modes (Multiply,"
                        + " Screen, Overlay, Add, and so on).")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap a floating video, picture or adjustment layer (hold a visualizer) › Blend mode")
                .id("layer", "layer", true, LAYER_HELP)
                .choice("mode", true, "The blend mode", MODES)
                .build(), LooksActions::setBlend);

        r.add(ActionSpec.of("set_chroma_key", "Key out a colour")
                .help("Chroma key: makes one colour (usually green) see-through on a floating video, a picture"
                        + " or an adjustment layer. Send on=true to turn it on. Tolerance is how close a colour"
                        + " must be to the key colour; softness is the soft edge of the cut; spill chokes"
                        + " (negative) or spreads (positive) it.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap a floating video or picture › Chroma key")
                .id("layer", "layer", true, LAYER_HELP)
                .flag("on", false, "Turn the key on (true) or off (false)")
                .color("color", false, "Key colour, e.g. #00FF00 for green")
                .number("tolerance", false, "How close a colour must be to the key colour: 0 … 1", 0.0, 1.0)
                .number("softness", false, "Soft edge of the cut: 0 … 1", 0.0, 1.0)
                .number("spill", false, "Choke (negative) or spread (positive) the cut: -1 … 1; 0 is none",
                        -1.0, 1.0)
                .build(), LooksActions::setKey);

        r.add(ActionSpec.of("apply_look", "Put a saved look on")
                .help("Apply a saved look (its effects, in order) to a clip or an adjustment layer. It REPLACES"
                        + " the effects that thing has now; one Undo puts them back. Leave name out to list the"
                        + " saved looks, which changes nothing.")
                .wing("STUDIO", FAMILY).effect(Effect.DESTRUCTIVE)
                .where("Studio › tap a clip or adjustment layer › Effects (saved looks)")
                .text("name", false, "The look's name, as listed")
                .id("target", "any", false, TARGET_HELP)
                .build(), LooksActions::applyLook);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult setGrade(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        List<String> given = new ArrayList<>();
        for (String p : COLOUR) if (a.has(p)) given.add(p);
        for (String p : FILM_PARAMS) if (a.has(p)) given.add(p);
        if (given.isEmpty()) {
            throw new ActionError("Say which grade setting to change: " + join(allGradeNames()) + ".");
        }
        if (FxRegistry.get(GRADE) == null || FxRegistry.get(FILM) == null) {
            throw new ActionError("The colour grade effects are missing from this build.");
        }
        String ref = a.str("target");
        if (ref == null) throw new ActionError("Which clip or layer should be graded? Give its id or name.");
        List<Object> targets = gradeTargets(ctx, ref);

        List<String> said = new ArrayList<>();
        for (String p : given) {
            FxParam fp = FxRegistry.get(effectFor(p)).param(p);
            said.add((fp != null ? fp.label : p) + " " + String.format(Locale.US, "%.2f", a.num(p)));
        }

        for (Object t : targets) {
            FxStack stack = effectsOf(t);
            if (stack == null) continue;
            for (String p : given) {
                String effect = effectFor(p);
                FxInstance card = cardFor(stack, effect);       // as FxPanel's add-card picker does
                FxParam fp = FxRegistry.get(effect).param(p);
                if (fp != null) card.set(fp, a.num(p).floatValue());   // as FxPanel's slider: fx.set(param, v)
            }
        }
        String what = targets.size() == 1 ? words(targets.get(0))
                : "all " + targets.size() + " clips on the main track";
        return ActionResult.changed("Changed the colour grade of " + what + " (" + join(said) + ").",
                idsOf(targets));
    }

    @NonNull
    private static ActionResult setBlend(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        String ref = a.str("layer");
        if (ref == null) throw new ActionError("Which floating video, picture, visualizer or layer? Give its id or name.");
        Object t = resolve(ctx, ref, OVERLAY | PICTURE | ADJUST | VISUALIZER, "layer");
        String mode = a.choice("mode", MODES);
        if (mode == null) throw new ActionError("Which blend mode? Choose one of: " + join(MODES) + ".");
        String before = blendOf(t);
        if (mode.equals(before)) {
            return ActionResult.unchanged("The blend mode of " + words(t) + " is already " + pretty(mode) + ".");
        }
        setBlendOf(t, mode);   // as the blend picker's pick: one string on the item, no other side effect
        String note = t instanceof Clip
                ? " The preview keeps drawing a floating video normally; the blend shows in the exported video."
                : "";
        return ActionResult.changed("The blend mode of " + words(t) + " is now " + pretty(mode) + "." + note,
                Collections.singletonList(idOf(t)));
    }

    @NonNull
    private static ActionResult setKey(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        if (!a.has("on") && !a.has("color") && !a.has("tolerance") && !a.has("softness") && !a.has("spill")) {
            throw new ActionError("Say what to change on the chroma key: on, color, tolerance, softness or spill.");
        }
        String ref = a.str("layer");
        if (ref == null) throw new ActionError("Which floating video, picture or layer? Give its id or name.");
        Object t = resolve(ctx, ref, OVERLAY | PICTURE | ADJUST, "layer");
        CompositingSpec spec = specOf(t);   // the drawer's own spec, or a fresh one (as showPipDrawer does)
        if (a.has("on")) spec.keyEnabled = a.bool("on");
        Integer rgb = a.color("color");
        if (rgb != null) spec.keyColor = rgb & 0x00FFFFFF;
        if (a.has("tolerance")) spec.keyTolerance = a.num("tolerance").floatValue();
        if (a.has("softness")) spec.keyFuzziness = a.num("softness").floatValue();
        if (a.has("spill")) spec.keyOffset = a.num("spill").floatValue();
        storeSpec(t, spec);
        return ActionResult.changed("The chroma key on " + words(t) + " is "
                + (spec.keyEnabled ? "on" : "off") + ".", Collections.singletonList(idOf(t)));
    }

    @NonNull
    private static ActionResult applyLook(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        List<String> names = FxPresetStore.listNames(ctx.app);
        String want = a.str("name");
        if (want == null) {
            return ActionResult.unchanged(names.isEmpty()
                    ? "There are no saved looks yet."
                    : "Saved looks: " + join(names) + ". Name one and a clip or layer to put it on.");
        }
        String look = pickLook(names, want);
        String ref = a.str("target");
        if (ref == null) {
            throw new ActionError("Put \"" + look + "\" on which clip or layer? Give its id or name, or \"all\""
                    + " for every clip on the main track.");
        }
        List<Object> targets = gradeTargets(ctx, ref);
        int left = 0;
        for (Object t : targets) {
            FxStack stack = effectsOf(t);
            if (stack == null) continue;
            // FxPresetStore.load replaces the chain in place, exactly as FxPanel.applyPreset does.
            if (!FxPresetStore.load(ctx.app, look, stack)) {
                throw new ActionError("The look \"" + look + "\" could not be read.");
            }
            // FxPanel.applyPreset drops what the subject cannot render: a floating video is OBJECT,
            // a clip or adjustment layer is LAYER (the defaults of the drawers that build those panels).
            FxPreviewTier.Subject subject = t instanceof Clip && ((Clip) t).isOverlayClip()
                    ? FxPreviewTier.Subject.OBJECT : FxPreviewTier.Subject.LAYER;
            for (int i = stack.size() - 1; i >= 0; i--) {
                FxEffectDef d = stack.cards().get(i).def();
                if (d != null && !FxPreviewTier.canExportOn(d, subject)) {
                    stack.remove(i);
                    left++;
                }
            }
        }
        String what = targets.size() == 1 ? words(targets.get(0))
                : "all " + targets.size() + " clips on the main track";
        String sum = "Put the look \"" + look + "\" on " + what + ". Its effects replace the ones it had.";
        if (left > 0) {
            sum += " " + left + " effect" + (left == 1 ? " was" : "s were")
                    + " left out because they can't run on a floating video.";
        }
        return ActionResult.changed(sum, idsOf(targets));
    }

    // ── which thing the person means ─────────────────────────────────────────

    /** "all" = every clip on the main track; otherwise one clip or layer. */
    @NonNull
    private static List<Object> gradeTargets(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        if ("all".equalsIgnoreCase(ref.trim())) {
            List<Object> masters = pool(ctx, MASTER);
            if (masters.isEmpty()) throw new ActionError("This project has no clips on the main track.");
            return masters;
        }
        List<Object> one = new ArrayList<>();
        one.add(resolve(ctx, ref, MASTER | OVERLAY | ADJUST, "clip or layer"));
        return one;
    }

    @NonNull
    private static List<Object> pool(@NonNull StudioCtx ctx, int kinds) {
        Timeline tl = ctx.project.getTimeline();
        List<Object> out = new ArrayList<>();
        if ((kinds & MASTER) != 0) out.addAll(tl.getClips());
        if ((kinds & OVERLAY) != 0) out.addAll(tl.getOverlayClips());
        if ((kinds & PICTURE) != 0) {
            for (TextOverlayItem t : tl.getTextOverlays()) if (t.isImage()) out.add(t);
        }
        if ((kinds & ADJUST) != 0) out.addAll(tl.getAdjustmentLayers());
        if ((kinds & VISUALIZER) != 0) out.addAll(tl.getWaveformOverlays());
        return out;
    }

    /** By id first, then by name (exact, then ignoring case); several names is an error that lists them. */
    @NonNull
    private static Object resolve(@NonNull StudioCtx ctx, @NonNull String ref, int kinds,
                                  @NonNull String what) throws ActionError {
        String r = ref.trim();
        List<Object> all = pool(ctx, kinds);
        for (Object o : all) if (r.equals(idOf(o))) return o;
        String want = norm(r);
        List<Object> hit = new ArrayList<>();
        for (Object o : all) if (!want.isEmpty() && want.equals(norm(nameOf(o)))) hit.add(o);
        if (hit.size() == 1) return hit.get(0);
        if (hit.size() > 1) {
            throw new ActionError(hit.size() + " layers are called \"" + r + "\": " + listed(hit)
                    + ". Ask which one, or use its id.");
        }
        throw new ActionError("No " + what + " called \"" + r + "\". The choices are: " + listed(all) + ".");
    }

    @NonNull
    private static String pickLook(@NonNull List<String> names, @NonNull String want) throws ActionError {
        String w = want.trim();
        for (String n : names) if (n.equals(w)) return n;
        for (String n : names) if (n.equalsIgnoreCase(w)) return n;
        if (names.isEmpty()) throw new ActionError("There are no saved looks yet, so there is no \"" + w + "\".");
        throw new ActionError("No look called \"" + w + "\". The saved looks are: " + join(names) + ".");
    }

    // ── one place per model field the drawers write ──────────────────────────

    /** The effect stack a grade or look lives on: a clip's own (created on demand), or a layer's. */
    @Nullable
    private static FxStack effectsOf(@NonNull Object o) {
        if (o instanceof Clip) return ((Clip) o).getOrCreateFx();
        if (o instanceof AdjustmentLayer) return ((AdjustmentLayer) o).getFx();
        return null;
    }

    /** The first card of that effect, or a new one at the bottom (FxPanel's picker: stack.add). */
    @NonNull
    private static FxInstance cardFor(@NonNull FxStack stack, @NonNull String effect) {
        for (FxInstance c : stack.cards()) if (effect.equals(c.effectId)) return c;
        return stack.add(effect);
    }

    @NonNull
    private static String effectFor(@NonNull String param) {
        for (String c : COLOUR) if (c.equals(param)) return GRADE;
        return FILM;
    }

    @NonNull
    private static List<String> allGradeNames() {
        List<String> out = new ArrayList<>(Arrays.asList(COLOUR));
        out.addAll(Arrays.asList(FILM_PARAMS));
        return out;
    }

    @NonNull
    private static String blendOf(@NonNull Object o) {
        if (o instanceof Clip) return ((Clip) o).getOverlayBlendMode();   // FaditorEditorActivity blend dialog
        if (o instanceof TextOverlayItem) return ((TextOverlayItem) o).getOverlayBlendMode();
        if (o instanceof WaveformOverlayInstance) return ((WaveformOverlayInstance) o).getOverlayBlendMode();
        return ((AdjustmentLayer) o).getBlendMode();
    }

    private static void setBlendOf(@NonNull Object o, @NonNull String mode) {
        if (o instanceof Clip) ((Clip) o).setOverlayBlendMode(mode);
        else if (o instanceof TextOverlayItem) ((TextOverlayItem) o).setOverlayBlendMode(mode);
        else if (o instanceof WaveformOverlayInstance) ((WaveformOverlayInstance) o).setOverlayBlendMode(mode);
        else ((AdjustmentLayer) o).setBlendMode(mode);
    }

    @NonNull
    private static CompositingSpec specOf(@NonNull Object o) {
        CompositingSpec s = null;
        if (o instanceof Clip) s = ((Clip) o).getCompositing();
        else if (o instanceof TextOverlayItem) s = ((TextOverlayItem) o).getCompositing();
        else if (o instanceof AdjustmentLayer) s = ((AdjustmentLayer) o).getCompositing();
        return s != null ? s : new CompositingSpec();
    }

    /** Written back the way the drawers do it: an empty spec is stored as none. */
    private static void storeSpec(@NonNull Object o, @NonNull CompositingSpec s) {
        if (o instanceof Clip) ((Clip) o).setCompositing(s.isEmpty() ? null : s);
        else if (o instanceof TextOverlayItem) ((TextOverlayItem) o).setCompositing(s.isEmpty() ? null : s);
        else ((AdjustmentLayer) o).setCompositing(s);
    }

    // ── naming ───────────────────────────────────────────────────────────────

    @NonNull
    private static String idOf(@NonNull Object o) {
        if (o instanceof Clip) return ((Clip) o).getId();
        if (o instanceof AdjustmentLayer) return ((AdjustmentLayer) o).getId();
        if (o instanceof WaveformOverlayInstance) return ((WaveformOverlayInstance) o).getId();
        return ((TextOverlayItem) o).getId();
    }

    @NonNull
    private static String nameOf(@NonNull Object o) {
        String s;
        if (o instanceof Clip) s = ((Clip) o).getLabel();
        else if (o instanceof AdjustmentLayer) s = ((AdjustmentLayer) o).getName();
        else if (o instanceof WaveformOverlayInstance) s = ((WaveformOverlayInstance) o).getVisLook();
        else s = ((TextOverlayItem) o).getText();
        return s == null ? "" : s.replace('\n', ' ').trim();
    }

    @NonNull
    private static String kindOf(@NonNull Object o) {
        if (o instanceof Clip) return ((Clip) o).isOverlayClip() ? "floating video" : "clip";
        if (o instanceof TextOverlayItem) return "picture";
        if (o instanceof WaveformOverlayInstance) return "visualizer";
        return "adjustment layer";
    }

    /** Person words for a summary: "the floating video "Intro"". No ids. */
    @NonNull
    private static String words(@NonNull Object o) {
        String name = nameOf(o);
        return "the " + kindOf(o) + (name.isEmpty() ? "" : " \"" + shorten(name) + "\"");
    }

    /** One line per choice with its id, for the error that asks which one. */
    @NonNull
    private static String listed(@NonNull List<Object> os) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Object o : os) {
            if (n++ == 12) { sb.append(", … ").append(os.size() - 12).append(" more"); break; }
            String name = nameOf(o);
            if (sb.length() > 0) sb.append(", ");
            sb.append(kindOf(o)).append(name.isEmpty() ? "" : " \"" + shorten(name) + "\"")
                    .append(" (id ").append(idOf(o)).append(')');
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    @NonNull
    private static List<String> idsOf(@NonNull List<Object> os) {
        List<String> out = new ArrayList<>();
        for (Object o : os) out.add(idOf(o));
        return out;
    }

    @NonNull
    private static String pretty(@NonNull String mode) {
        String s = mode.replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @NonNull
    private static String norm(@Nullable String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String shorten(@NonNull String s) {
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    @NonNull
    private static String join(@NonNull List<String> l) {
        StringBuilder sb = new StringBuilder();   // String.join is API 26; minSdk is 24
        for (String s : l) sb.append(sb.length() > 0 ? ", " : "").append(s);
        return sb.toString();
    }
}
