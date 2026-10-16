package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.text.FontLibrary;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;
import com.fadcam.ui.faditor.transcript.CaptionStyle;
import com.fadcam.ui.faditor.transcript.CaptionStyleStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The caption look and placement family (gap F2): the caption drawer's Style, Fit and Motion rows,
 * and each caption track's position, size, box width and alignment.
 *
 * <p>Look edits follow the drawer exactly ({@code FaditorEditorActivity.tweakCaptionStyle}, ~L23459):
 * each caption track gets its OWN working style, saved as {@code customdraft_<clipId>_b<track>}, so a
 * change to one track never restyles another, and the shared presets and saved styles are never
 * edited. Picking a style is the style chip ({@code applyCaptionStyle}, ~L23664): it sets the track's
 * style id with no copy. Motion belongs to the clip, as in the drawer; size and position belong to the
 * track, as on the preview.</p>
 *
 * <p>The one write outside the project: working styles live in the app-wide {@link CaptionStyleStore}
 * (SharedPreferences), exactly as the drawer writes them. A track can only point at a style that is
 * in that store, so this write is required, not optional.</p>
 */
final class CaptionActions {
    private CaptionActions() { }

    private static final String FAMILY = "captions";
    private static final String CLIP_HELP =
            "Which clip: its id, its name, or \"all\" for every clip that has captions";
    private static final String DRAFT_PREFIX = "customdraft_";
    /** Studio.alpha(Studio.GROUND, 0xCC): the drawer's default colour for a new box. */
    private static final int DEFAULT_BOX_COLOUR = 0xCC000000;

    static final List<String> ANIM = Arrays.asList("POP", "ZOOM", "BOUNCE");
    static final List<String> FIT = Arrays.asList("OFF", "UNIFORM", "PER_CUE");
    static final List<String> GROUPING = Arrays.asList("KARAOKE", "SLIDE");
    /** The drawer's one-tap position control cycles Top, Middle, Bottom (~L22830). */
    static final List<String> POSITION = Arrays.asList("TOP", "MIDDLE", "BOTTOM");
    private static final float[] POSITION_Y = {0.15f, 0.5f, 0.85f};
    /** Grow: anchor 0 = centre, 1 = top pinned (grows down), 2 = bottom pinned (grows up) (~L22464). */
    static final List<String> GROW = Arrays.asList("CENTER", "DOWN", "UP");
    /** Align: justify 0 = centre, 1 = left, 2 = right (~L22518). */
    static final List<String> ALIGN = Arrays.asList("CENTER", "LEFT", "RIGHT");
    private static final List<String> PRESETS = names(CaptionAnimator.Preset.values());
    private static final List<String> GRANULARITIES = names(CaptionAnimator.Granularity.values());

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("list_caption_styles", "List the caption styles")
                .help("The caption styles a person can pick: the built-in ones and any they saved. Call before"
                        + " set_caption_look when they name a style you don't see here. Give clip to see which"
                        + " style each caption track on that clip wears now.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where("Studio › tap a caption › the style chips along the bottom")
                .id("clip", "clip", false, CLIP_HELP)
                .build(), CaptionActions::listStyles);

        r.add(ActionSpec.of("set_caption_look", "Change how captions look")
                .help("Change how a clip's captions look and where they sit: colours, font, words per caption,"
                        + " fitting, outline, shadow, the highlight animation, the box behind the words, size,"
                        + " position, box width, alignment, and the motion as they arrive. Send only what should"
                        + " change. Changes go to every caption track on the clip, or to one track when track is"
                        + " given. Each track keeps its own look (its own copy is changed, so presets and saved"
                        + " styles stay as they were). A track named with track is shown even if it was hidden."
                        + " Motion is for the whole clip. The clip must already have captions (toggle_captions"
                        + " turns them on). Colours #RRGGBB or #AARRGGBB.")
                .wing("STUDIO", FAMILY)
                .where("Studio › tap a caption › Style")
                .id("clip", "clip", true, CLIP_HELP)
                .number("track", false, "Which caption track: 1, 2 or 3 in the order they are listed."
                        + " Leave out for every track that is showing.", 1.0, 3.0)
                .text("style", false, "A style to start from: an id or name from list_caption_styles (e.g."
                        + " \"boxed\"). The other changes build on it.")
                .color("color", false, "Colour of the words not spoken yet")
                .color("highlightColor", false, "Colour of the word being spoken")
                .text("font", false, "Font name or key from the caption font list, e.g. Serif, Mono, Rounded,"
                        + " or an imported font's name")
                .number("wordsPerCaption", false, "How many words one caption holds before it breaks to the"
                        + " next", 1.0, 60.0)
                .number("maxLines", false, "Most lines one caption may take when fitting; 0 for no limit",
                        0.0, 10.0)
                .choice("fit", false, "Shrink captions to fit the box: OFF, UNIFORM (one size for all) or"
                        + " PER_CUE (each caption its own size)", FIT)
                .flag("fitTruncate", false, "When fitting, cut text that still overflows with an ellipsis"
                        + " (on), or let the column run taller (off)")
                .number("fitFloor", false, "Smallest a caption shrinks to, as a fraction of its set size"
                        + " (0.45 is the default)", 0.15, 1.0)
                .choice("grouping", false, "KARAOKE: a few words at a time, as spoken. SLIDE: one box holds"
                        + " the whole timed passage, wrapped by word", GROUPING)
                .flag("outline", false, "Outline round the words")
                .color("outlineColor", false, "Outline colour (turns the outline on)")
                .flag("shadow", false, "Soft shadow under the words")
                .choice("highlightAnimation", false, "How the word being spoken moves", ANIM)
                .flag("box", false, "A coloured box behind the words")
                .color("boxColor", false, "Colour and opacity of the box (turns the box on)")
                .number("boxCorners", false, "Corner roundness of the box: 0 square, 0.5 fully round (0.35 is"
                        + " the default)", 0.0, 0.5)
                .number("size", false, "Letter size as a fraction of the frame height (0.06 is typical)",
                        0.02, 0.6)
                .choice("position", false, "Where the captions sit on screen: TOP, MIDDLE or BOTTOM", POSITION)
                .number("centerX", false, "Exact horizontal centre, 0 left edge to 1 right edge", 0.0, 1.0)
                .number("centerY", false, "Exact vertical centre, 0 top edge to 1 bottom edge", 0.0, 1.0)
                .number("boxWidth", false, "Width of the caption box as a fraction of the frame width", 0.3, 1.0)
                .choice("grow", false, "Which way the box grows when text gets longer: CENTER (both ways),"
                        + " DOWN (from the top) or UP (from the bottom)", GROW)
                .choice("align", false, "Alignment of the lines inside the box: CENTER, LEFT or RIGHT", ALIGN)
                .choice("motion", false, "How the captions arrive and leave (the Motion picker)", PRESETS)
                .choice("motionBy", false, "What animates as one unit: LETTER, WORD, SENTENCE or BLOCK",
                        GRANULARITIES)
                .number("motionIn", false, "Arrival, percent of the caption's time", 0.0, 50.0)
                .number("motionOut", false, "Leaving, percent of the caption's time", 0.0, 50.0)
                .build(), CaptionActions::setLook);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult listStyles(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        CaptionStyleStore.ensureInit(ctx.app);
        StringBuilder builtIn = new StringBuilder();
        StringBuilder ids = new StringBuilder();
        for (CaptionStyle p : CaptionStyle.presets()) {
            builtIn.append(builtIn.length() > 0 ? ", " : "").append(p.label);
            ids.append(ids.length() > 0 ? ", " : "").append(p.id);
        }
        List<CaptionStyle> mine = CaptionStyleStore.listed();
        StringBuilder own = new StringBuilder();
        for (CaptionStyle m : mine) {
            own.append(own.length() > 0 ? ", " : "").append(m.label);
            ids.append(", ").append(m.id).append(" (").append(m.label).append(')');
        }
        String sum = "Built-in caption styles: " + builtIn + "."
                + (mine.isEmpty() ? " No saved styles of your own yet." : " Your own: " + own + ".");
        String detail = "Style ids: " + ids + ".";
        if (a.has("clip")) {
            StringBuilder wear = new StringBuilder();
            for (Clip c : clips(ctx, a.str("clip"))) {
                List<Clip.CaptionBinding> bs = c.getCaptionBindings();
                for (int i = 0; i < bs.size(); i++) {
                    Clip.CaptionBinding b = bs.get(i);
                    wear.append(wear.length() > 0 ? "; " : "").append(label(c)).append(" track ")
                            .append(i + 1).append(b.enabled ? "" : " (hidden)").append(" wears ")
                            .append(styleLabel(b.styleId));
                }
            }
            detail += " Now: " + wear + ".";
        }
        return ActionResult.unchanged(sum).withDetail(detail);
    }

    @NonNull
    private static ActionResult setLook(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        // Working styles live in the app-wide store; without init, put() and find() do nothing.
        CaptionStyleStore.ensureInit(ctx.app);

        String style = a.has("style") ? styleId(a.str("style")) : null;
        String[] font = a.has("font") ? font(a.str("font")) : null;
        List<String> said = describe(a, font, style);
        if (said.isEmpty()) {
            throw new ActionError("Say what to change: a colour, the font, the size, the position, the box, the"
                    + " motion...");
        }

        List<Clip> clips = clips(ctx, a.str("clip"));
        int only = a.has("track") ? a.whole("track").intValue() : 0;

        // Plan every clip first, so a problem with one changes nothing.
        List<Clip> work = new ArrayList<>();
        List<List<Integer>> plan = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Clip c : clips) {
            List<Clip.CaptionBinding> bs = c.getCaptionBindings();
            List<Integer> tracks = new ArrayList<>();
            if (only > 0) {
                if (only > bs.size()) {
                    if (clips.size() == 1) {
                        throw new ActionError(label(c) + " has " + bs.size() + " caption track"
                                + (bs.size() == 1 ? "" : "s") + ", so there is no track " + only + ".");
                    }
                    skipped.add(label(c));
                    continue;
                }
                tracks.add(only - 1);
            } else {
                for (int i = 0; i < bs.size(); i++) if (bs.get(i).enabled) tracks.add(i);
                if (tracks.isEmpty()) {
                    if (clips.size() == 1) {
                        throw new ActionError("Every caption track on " + label(c) + " is hidden. Name the track"
                                + " with track to change it, or turn captions on first.");
                    }
                    skipped.add(label(c));
                    continue;
                }
            }
            work.add(c);
            plan.add(tracks);
        }
        if (work.isEmpty()) throw new ActionError("None of those clips has a caption track to change.");

        // Mirrors the drawer's apply path (tweakCaptionStyle + applyCaptionStyle, ~L23459 / ~L23664).
        int changedTracks = 0, shown = 0;
        List<String> ids = new ArrayList<>();
        for (int k = 0; k < work.size(); k++) {
            Clip c = work.get(k);
            List<Clip.CaptionBinding> bs = c.getCaptionBindings();
            for (int i : plan.get(k)) {
                Clip.CaptionBinding b = bs.get(i);
                if (!b.enabled) shown++;
                // The drawer always shows a track it styles (applyCaptionStyle sets enabled = true).
                b.enabled = true;
                if (style != null) b.styleId = style;
                if (hasLookEdit(a)) {
                    String draftId = DRAFT_PREFIX + c.getId() + "_b" + i;
                    CaptionStyle base = CaptionStyle.byId(b.styleId);
                    CaptionStyle working = base.id.equals(draftId) ? base : base.copyAs(draftId, base.label);
                    editLook(working, a, font);
                    CaptionStyleStore.put(working);
                    b.styleId = draftId;
                }
                placeTrack(b, a);
                changedTracks++;
            }
            // Motion is per clip in the drawer (captionAnimTarget, ~L24043), so it is set once here.
            if (hasMotion(a)) setMotion(c, a);
            c.syncLegacyFromBindings();
            ids.add(c.getId());
        }

        String what = work.size() == 1 ? label(work.get(0)) : work.size() + " clips";
        String sum = "Changed the captions on " + what + (said.isEmpty() ? "" : " (" + join(said) + ")") + ".";
        StringBuilder det = new StringBuilder(changedTracks + " caption track(s) changed.");
        if (shown > 0) det.append(' ').append(shown).append(" hidden track(s) shown again.");
        if (!skipped.isEmpty()) det.append(" Skipped (no track to change): ").append(join(skipped)).append('.');
        return ActionResult.changed(sum, ids).withDetail(det.toString());
    }

    // ── what a look edit writes ──────────────────────────────────────────────

    /** Same fields and order as the drawer's controls; a colour turns its box/outline on, as there. */
    private static void editLook(@NonNull CaptionStyle s, @NonNull Args a, @Nullable String[] font) {
        if (font != null) s.fontKey = font[0];                                    // font picker, ~L21811
        if (a.has("color")) s.baseColor = a.color("color");                       // Text colour, ~L21855
        if (a.has("highlightColor")) s.activeColor = a.color("highlightColor");   // Highlight, ~L21856
        if (a.has("highlightAnimation")) {                                        // anim chips, ~L21838
            s.anim = CaptionStyle.Anim.valueOf(a.choice("highlightAnimation", ANIM));
        }
        if (a.has("wordsPerCaption")) s.maxWords = a.whole("wordsPerCaption").intValue();  // ~L22689
        if (a.has("maxLines")) s.fitMaxLines = a.whole("maxLines").intValue();             // ~L22767
        if (a.has("fit")) s.fitMode = CaptionStyle.FitMode.valueOf(a.choice("fit", FIT)); // ~L22339
        if (a.has("fitTruncate")) s.fitTruncate = a.bool("fitTruncate").booleanValue();   // ~L22360
        if (a.has("fitFloor")) s.fitMinScale = snapFloor(a.num("fitFloor"));               // ~L22406, 0.05 steps
        if (a.has("grouping")) {                                                            // ~L22426
            s.slideGroup = "SLIDE".equals(a.choice("grouping", GROUPING));
        }
        // Outline and box: a colour turns them on; an explicit on/off flag wins over that.
        if (a.has("outlineColor")) {                                              // ~L21859
            s.outlineColor = a.color("outlineColor");
            if (!a.has("outline")) s.outline = true;
        }
        if (a.has("boxColor")) {                                                  // ~L21858, ~L23413
            s.pillColor = a.color("boxColor");
            if (!a.has("box")) s.pill = true;
        }
        if (a.has("boxCorners")) {                                                // ~L23402
            s.pillCornerScale = CaptionStyle.clampPillCorner(a.num("boxCorners").floatValue());
            if (!a.has("box")) s.pill = true;
        }
        if (a.has("outline")) s.outline = a.bool("outline").booleanValue();
        if (a.has("shadow")) s.shadow = a.bool("shadow").booleanValue();         // ~L21860
        if (a.has("box")) s.pill = a.bool("box").booleanValue();                  // ~L21858
        if (s.pill && s.pillColor == 0) s.pillColor = DEFAULT_BOX_COLOUR;         // ~L23372
    }

    /** Binding-level placement, as the size slider, position control, drag and chips write it. */
    private static void placeTrack(@NonNull Clip.CaptionBinding b, @NonNull Args a) {
        if (a.has("size")) {                                                      // applyCaptionSize, ~L23900
            b.sizeFraction = clamp(a.num("size").floatValue(), 0.02f, 0.6f);
        }
        if (a.has("position")) {                                                  // makeCaptionPositionToggle, ~L22830
            b.centerX = 0.5f;
            b.centerY = POSITION_Y[POSITION.indexOf(a.choice("position", POSITION))];
        }
        if (a.has("centerX")) b.centerX = clamp(a.num("centerX").floatValue(), 0f, 1f);  // drag, ~L40284
        if (a.has("centerY")) b.centerY = clamp(a.num("centerY").floatValue(), 0f, 1f);
        if (a.has("boxWidth")) {                                                  // edge drag, ~L40317
            b.boxWidthFraction = clamp(a.num("boxWidth").floatValue(), 0.3f, 1f);
        }
        if (a.has("grow")) b.anchor = GROW.indexOf(a.choice("grow", GROW));       // ~L22483
        if (a.has("align")) b.justify = ALIGN.indexOf(a.choice("align", ALIGN));  // ~L22518
    }

    /** Motion per clip: applyCaptionAnimPreset / applyCaptionAnimGranularity (~L24270, ~L24308). */
    private static void setMotion(@NonNull Clip c, @NonNull Args a) {
        float in = c.getCaptionAnimInPct();
        float out = c.getCaptionAnimOutPct();
        if (a.has("motion")) {
            CaptionAnimator.Preset p = CaptionAnimator.parsePreset(a.choice("motion", PRESETS));
            c.setCaptionAnimPreset(p.name());
            // Choosing a motion with no room for it seeds half the entrance, as the picker does.
            if (p != CaptionAnimator.Preset.NONE && in <= 0f && out <= 0f
                    && !a.has("motionIn") && !a.has("motionOut")) {
                in = CaptionAnimator.MAX_ZONE_PCT / 2f;
            }
        }
        if (a.has("motionBy")) {
            c.setCaptionAnimGranularity(CaptionAnimator.parseGranularity(a.choice("motionBy", GRANULARITIES)).name());
        }
        if (a.has("motionIn")) in = a.num("motionIn").floatValue() / 100f;   // range control, ~L24082
        if (a.has("motionOut")) out = a.num("motionOut").floatValue() / 100f;
        if (a.has("motion") || a.has("motionIn") || a.has("motionOut")) c.setCaptionAnimZones(in, out);
    }

    private static boolean hasLookEdit(@NonNull Args a) {
        String[] keys = {"font", "color", "highlightColor", "highlightAnimation", "wordsPerCaption", "maxLines",
                "fit", "fitTruncate", "fitFloor", "grouping", "outline", "outlineColor", "shadow", "box",
                "boxColor", "boxCorners"};
        for (String k : keys) if (a.has(k)) return true;
        return false;
    }

    private static boolean hasMotion(@NonNull Args a) {
        return a.has("motion") || a.has("motionBy") || a.has("motionIn") || a.has("motionOut");
    }

    /** Words for the person about what changed. */
    @NonNull
    private static List<String> describe(@NonNull Args a, @Nullable String[] font, @Nullable String style) {
        List<String> s = new ArrayList<>();
        if (style != null) s.add("style " + styleLabel(style));
        if (font != null) s.add("font " + font[1]);
        if (a.has("color")) s.add("word colour");
        if (a.has("highlightColor")) s.add("highlight colour");
        if (a.has("highlightAnimation")) s.add("highlight " + a.choice("highlightAnimation", ANIM).toLowerCase(Locale.ROOT));
        if (a.has("wordsPerCaption")) s.add(a.whole("wordsPerCaption") + " words a caption");
        if (a.has("maxLines") || a.has("fit") || a.has("fitTruncate") || a.has("fitFloor")) s.add("fitting");
        if (a.has("grouping")) s.add(a.choice("grouping", GROUPING).equals("SLIDE") ? "slide grouping" : "karaoke grouping");
        if (a.has("outline") || a.has("outlineColor")) s.add("outline");
        if (a.has("shadow")) s.add(a.bool("shadow").booleanValue() ? "shadow on" : "shadow off");
        if (a.has("box") || a.has("boxColor") || a.has("boxCorners")) s.add("box");
        if (a.has("size")) s.add("size");
        if (a.has("position") || a.has("centerX") || a.has("centerY")) s.add("position");
        if (a.has("boxWidth")) s.add("box width");
        if (a.has("grow")) s.add("grow");
        if (a.has("align")) s.add("alignment");
        if (a.has("motion")) {
            s.add("motion " + CaptionAnimator.presetLabel(CaptionAnimator.parsePreset(a.choice("motion", PRESETS))));
        }
        if (a.has("motionBy")) s.add("motion by " + a.choice("motionBy", GRANULARITIES).toLowerCase(Locale.ROOT));
        if (a.has("motionIn") || a.has("motionOut")) s.add("motion timing");
        return s;
    }

    // ── id, name and font resolvers (private, as the spec asks) ─────────────

    /** Clips to change: "all" (every clip with captions), an id, or a name. Plain words on failure. */
    @NonNull
    private static List<Clip> clips(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        List<Clip> all = ctx.project.getTimeline().getClips();
        String r = ref.trim();
        if ("all".equalsIgnoreCase(r)) {
            List<Clip> withCaps = new ArrayList<>();
            for (Clip c : all) if (!c.getCaptionBindings().isEmpty()) withCaps.add(c);
            if (withCaps.isEmpty()) throw new ActionError("No clip has captions yet. Turn captions on for a clip first.");
            return withCaps;
        }
        for (Clip c : all) {
            if (r.equals(c.getId())) return one(captioned(c));
        }
        String want = r.toLowerCase(Locale.ROOT);
        List<Clip> exact = new ArrayList<>(), part = new ArrayList<>();
        for (Clip c : all) {
            String n = c.getDisplayName() == null ? "" : c.getDisplayName().trim().toLowerCase(Locale.ROOT);
            if (n.isEmpty()) continue;
            if (n.equals(want)) exact.add(c);
            else if (!want.isEmpty() && n.contains(want)) part.add(c);
        }
        List<Clip> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return one(captioned(hit.get(0)));
        if (hit.isEmpty()) {
            throw new ActionError("No clip is called \"" + r + "\". The clips are: " + listClips(all) + ".");
        }
        throw new ActionError(hit.size() + " clips match \"" + r + "\": " + listClips(hit)
                + ". Ask which one, or use its id.");
    }

    @NonNull
    private static Clip captioned(@NonNull Clip c) throws ActionError {
        if (c.getCaptionBindings().isEmpty()) {
            throw new ActionError(label(c) + " has no captions yet. Turn captions on for it first.");
        }
        return c;
    }

    /** A saved or built-in style id, or its name; plain words listing the choices otherwise. */
    @NonNull
    private static String styleId(@NonNull String name) throws ActionError {
        String want = name.trim();
        for (CaptionStyle p : CaptionStyle.presets()) {
            if (p.id.equals(want) || p.label.equalsIgnoreCase(want)) return p.id;
        }
        for (CaptionStyle m : CaptionStyleStore.listed()) {
            if (m.id.equals(want) || m.label.equalsIgnoreCase(want)) return m.id;
        }
        StringBuilder sb = new StringBuilder();
        for (CaptionStyle p : CaptionStyle.presets()) sb.append(sb.length() > 0 ? ", " : "").append(p.label);
        for (CaptionStyle m : CaptionStyleStore.listed()) sb.append(", ").append(m.label);
        throw new ActionError("There's no caption style called \"" + want + "\". Styles: " + sb + ".");
    }

    @NonNull
    private static String styleLabel(@NonNull String id) {
        for (CaptionStyle p : CaptionStyle.presets()) if (p.id.equals(id)) return p.label;
        for (CaptionStyle m : CaptionStyleStore.listed()) if (m.id.equals(id)) return m.label;
        return id;
    }

    /**
     * {key, label} for a caption font, in the drawer's own order (captionFontChoices, ~L37113): the
     * caption's six, then the text tool's fonts it does not already have, then imports.
     */
    @NonNull
    private static String[] font(@NonNull String name) throws ActionError {
        String n = name.trim();
        List<String[]> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] c : CaptionStyle.fontChoices()) {
            if (!c[0].startsWith("file:") && seen.add(c[0])) all.add(c);
        }
        for (String[] c : FontLibrary.TEXT_FONTS) if (seen.add(c[0])) all.add(c);
        for (String[] f : FontLibrary.imported()) if (seen.add(f[0])) all.add(new String[]{f[0], f[1]});
        for (String[] f : all) if (f[0].equals(n)) return f;
        for (String[] f : all) if (f[1].equalsIgnoreCase(n)) return f;
        String want = n.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (String[] f : all) {
            if (f[1].toLowerCase(Locale.ROOT).contains(want) || f[0].replace('_', ' ').equals(want)) return f;
        }
        StringBuilder sb = new StringBuilder();
        for (String[] f : all) sb.append(sb.length() > 0 ? ", " : "").append(f[1]);
        throw new ActionError("There's no font called \"" + n + "\". Fonts: " + sb + ".");
    }

    // ── small helpers ────────────────────────────────────────────────────────

    @NonNull
    private static List<Clip> one(@NonNull Clip c) {
        List<Clip> l = new ArrayList<>();
        l.add(c);
        return l;
    }

    /** A clip's name in words for the person; a clip that was never renamed has none. */
    @NonNull
    private static String label(@NonNull Clip c) {
        String n = c.getDisplayName() == null ? "" : c.getDisplayName().trim();
        return n.isEmpty() ? "a clip" : "\"" + n + "\"";
    }

    /** "\"Beach\" (id a1b2)" for each, so the model can ask which one. */
    @NonNull
    private static String listClips(@NonNull List<Clip> cs) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Clip c : cs) {
            if (n++ == 12) { sb.append(", … ").append(cs.size() - 12).append(" more"); break; }
            String name = c.getDisplayName() == null || c.getDisplayName().trim().isEmpty()
                    ? "unnamed clip" : "\"" + c.getDisplayName().trim() + "\"";
            sb.append(sb.length() > 0 ? ", " : "").append(name).append(" (id ").append(c.getId()).append(')');
        }
        return sb.toString();
    }

    /** The fit floor slider moves in 0.05 steps from 0.15 (~L22406); snap to the same grid. */
    private static float snapFloor(@NonNull Double v) {
        long steps = Math.round((v - 0.15) / 0.05);
        return (float) Math.max(0.15, Math.min(1.0, 0.15 + steps * 0.05));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @NonNull
    private static <E extends Enum<E>> List<String> names(@NonNull E[] values) {
        List<String> out = new ArrayList<>();
        for (E e : values) out.add(e.name());
        return out;
    }

    @NonNull
    private static String join(@NonNull List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? ", " : "").append(s);
        return sb.toString();
    }
}
