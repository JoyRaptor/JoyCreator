package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Type;
import com.fadcam.vislab.room.VisLabSession;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VisLab, the music visualiser room, as actions (app-wide host). The looks live in
 * {@link VisLabSession} — the same ones the room shows — so a change here is on screen the moment
 * the room is in front. Reading and the change lines are {@link VisLabEdit}. Every change a request
 * makes shares one VisLab undo step (the request token is the session's undo group), as in the Studio.
 */
final class VisLabActions {
    private VisLabActions() { }

    private static final String FAMILY = "vislab";
    @Nullable private static volatile VisLabEdit EDIT;

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("vislab_looks", "List the VisLab looks")
                .help("Lists VisLab's music visualiser looks (name, id, style, what it is) and says which is on"
                        + " screen and whether brand colours are locked. Call it before picking a look by name.")
                .wing("APP", FAMILY).effect(Effect.READ)
                .where("VisLab › the looks strip")
                .build(), VisLabActions::looks);

        r.add(ActionSpec.of("vislab_describe", "Read a VisLab look")
                .help("Reads a look in words. With no layer: its colours, its layers back to front with the"
                        + " knobs it sets, and its Finish and Echo. With layer (a number, name or type; or"
                        + " finish / echo): every knob of that layer with its value and range. Read before"
                        + " changing. look defaults to the one on screen; raw=true gives the look's JSON"
                        + " (for vislab_apply_look).")
                .wing("APP", FAMILY).effect(Effect.READ)
                .where("VisLab › tap a layer")
                .text("look", false, "The look's name or id (default: the one on screen)")
                .text("layer", false, "A layer number (1 = back), name or type; or finish / echo")
                .flag("raw", false, "Give the look's JSON instead of words")
                .build(), VisLabActions::describe);

        r.add(ActionSpec.of("vislab_layer_types", "List VisLab layer types")
                .help("The kinds of layer a look can have (spectrum bars, particles, lasers, tunnels…), or one"
                        + " type's knobs and their ranges. Use before adding a layer.")
                .wing("APP", FAMILY).effect(Effect.READ)
                .where("VisLab › Add layer")
                .text("type", false, "A type id or name to see its knobs")
                .build(), VisLabActions::layerTypes);

        r.add(ActionSpec.of("vislab_pick_look", "Show a VisLab look")
                .help("Puts a look on screen, as tapping it in the strip does. look: its name or id.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("VisLab › the looks strip")
                .text("look", true, "The look's name or id")
                .build(), VisLabActions::pickLook);

        r.add(ActionSpec.of("vislab_change", "Change a VisLab look")
                .help("Changes a look. Put everything one request needs into one call where you can (a whole request is one undo either way)."
                        + " changes: lines \"<layer>.<knob> = <value>\" — layer by number (1 = back), name or"
                        + " type, or finish / echo; knob by id or label (vislab_describe shows them). value: a"
                        + " number, an option name, or on/off; add \"+ <signal> <amount>\" to make it move with"
                        + " the music (amount = share of the knob's range, -2…2; signals: bass, low, mid, high,"
                        + " air, level, voice, bright, kick, snare, hat, beat, beatSine, bar, energy, slow, wave),"
                        + " or end with \"still\" to stop it moving. \"look.palette = #a #b #c #d #e\","
                        + " \"look.background = #000000\", \"look.name = …\", \"<layer>.palette = …\" too. Every"
                        + " layer also has on (shown), opacity, add (light blend 0…1), res (detail) and echo."
                        + " add_layers: \"<type> as <name> at <position>\" (as/at optional; default on top)."
                        + " remove_layers: layer numbers or names. move_layers: \"<layer> to <n|front|back>\"."
                        + " Order: remove, add, move, then changes. as_new: keep the original and save the result"
                        + " as a new look with this name. look defaults to the one on screen.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("VisLab › tap a layer › its knobs")
                .param("changes", Type.STRING_LIST, false, "Lines like \"Pads.speed = 0.8\" or \"finish.bloom = 1.2 + kick 0.3\"")
                .param("add_layers", Type.STRING_LIST, false, "Lines like \"lasers as Beams at 2\"")
                .param("remove_layers", Type.STRING_LIST, false, "Layer numbers or names")
                .param("move_layers", Type.STRING_LIST, false, "Lines like \"Beams to front\"")
                .text("look", false, "The look's name or id (default: the one on screen)")
                .text("as_new", false, "Save as a new look with this name instead of changing this one")
                .build(), VisLabActions::change);

        r.add(ActionSpec.of("vislab_apply_look", "Put a whole VisLab look on screen")
                .help("Shows a complete look written as joy.vis/1 JSON (start from vislab_describe raw=true)."
                        + " Same id replaces that look; a new id adds one. Prefer vislab_change for edits —"
                        + " this is for building a look from scratch. VisLab fixes what it can and says so. Never set a layer's image: only the person picks pictures.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("VisLab › Import look")
                .text("look_json", true, "The whole look, joy.vis/1 JSON")
                .build(), VisLabActions::applyLook);

        r.add(ActionSpec.of("vislab_brand", "Lock VisLab to brand colours")
                .help("Puts every look in these five colours (a brand or channel's colours), or off to let each"
                        + " look use its own.")
                .wing("APP", FAMILY).effect(Effect.EDIT)
                .where("VisLab › Brand")
                .param("colors", Type.STRING_LIST, false, "Five colours like #FF2BD6, darkest to brightest")
                .flag("off", false, "true: each look uses its own colours again")
                .build(), VisLabActions::brand);
    }

    // ------------------------------------------------------------------ handlers

    @NonNull
    private static ActionResult looks(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = session(ctx);
        List<String> ids = s.lookIds();
        JsonObject now = current(s);
        String onScreen = now == null ? "" : VisLabEdit.str(now, "id");
        StringBuilder sb = new StringBuilder(ids.size() + " looks:\n");
        for (String id : ids) {
            JsonObject l = parse(s.lookJson(id));
            if (l == null) continue;
            sb.append(id.equals(onScreen) ? "★ " : "• ").append(VisLabEdit.lookLine(l)).append('\n');
        }
        sb.append(onScreen.isEmpty() ? "" : "★ = on screen. ");
        String[] b = s.brand();
        sb.append(b == null ? "Brand colours: off." : "Brand colours locked: " + String.join(" ", b) + ".");
        if (!s.isRoomOpen()) sb.append(" The VisLab room isn't open; changes show when it is (open_room vislab).");
        return ActionResult.unchanged(sb.toString());
    }

    @NonNull
    private static ActionResult describe(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = session(ctx);
        JsonObject look = look(s, a.str("look"));
        if (Boolean.TRUE.equals(a.bool("raw"))) return ActionResult.unchanged(look.toString());
        return ActionResult.unchanged(edit(s).describe(look, a.str("layer")));
    }

    @NonNull
    private static ActionResult layerTypes(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        return ActionResult.unchanged(edit(session(ctx)).describeTypes(a.str("type")));
    }

    @NonNull
    private static ActionResult pickLook(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = session(ctx);
        JsonObject look = look(s, a.str("look"));
        VisLabSession.ApplyResult r = s.pickLook(VisLabEdit.str(look, "id"), label(), group());
        return result(s, r, "Showing “" + VisLabEdit.str(look, "name") + "”.");
    }

    @NonNull
    private static ActionResult change(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = session(ctx);
        VisLabEdit e = edit(s);
        JsonObject look = look(s, a.str("look")).deepCopy();
        List<String> done = new ArrayList<>();
        for (String ref : a.list("remove_layers")) done.add(e.removeLayer(look, ref));
        for (String line : a.list("add_layers")) done.add(addLayer(e, look, line));
        for (String line : a.list("move_layers")) done.add(e.moveLayer(look, line));
        for (String line : a.list("changes")) done.add(e.change(look, line));
        String asNew = a.str("as_new");
        if (asNew != null && !asNew.trim().isEmpty()) {
            look.addProperty("name", asNew.trim());
            look.addProperty("id", freshId(s, asNew));
            done.add("Saved as a new look, “" + asNew.trim() + "”");
        }
        if (done.isEmpty()) throw new ActionError("Nothing to change: give changes, add_layers, remove_layers or move_layers.");
        VisLabSession.ApplyResult r = s.apply(look.toString(), label(), group());
        return result(s, r, String.join("\n", done));
    }

    /** "lasers as Beams at 2" → type, name, position (as/at optional). */
    @NonNull
    private static String addLayer(@NonNull VisLabEdit e, @NonNull JsonObject look, @NonNull String line) throws ActionError {
        String rest = line.trim();
        Integer at = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\s+at\\s+(\\d+|front|top|back|bottom)\\s*$").matcher(rest);
        if (m.find()) {
            String w = m.group(1).toLowerCase(Locale.ROOT);
            at = w.matches("back|bottom") ? 1 : w.matches("front|top") ? null : Integer.valueOf(w);
            rest = rest.substring(0, m.start()).trim();
        }
        String name = null;
        int as = rest.toLowerCase(Locale.ROOT).indexOf(" as ");
        if (as > 0) {
            name = rest.substring(as + 4).trim().replaceAll("^[\"“]|[\"”]$", "");
            rest = rest.substring(0, as).trim();
        }
        return e.addLayer(look, rest, name, at);
    }

    @NonNull
    private static ActionResult applyLook(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = session(ctx);
        JsonObject look = parse(a.str("look_json"));
        if (look == null) throw new ActionError("look_json must be a whole look as JSON.");
        String said = keepPickedPictures(s, look) ? "Put the look on screen. Its picture layers show the Joy emblem:"
                + " only the person can pick a picture (Use my picture on the layer)." : "Put the look on screen.";
        VisLabSession.ApplyResult r = s.apply(look.toString(), label(), group());
        return result(s, r, said);
    }

    /**
     * A picture layer's "image" is a file the person picked (content://…). Joybot never invents
     * one: any image that isn't already in one of the looks becomes the emblem (empty). True when
     * one was dropped.
     */
    private static boolean keepPickedPictures(@NonNull VisLabSession s, @NonNull JsonObject look) {
        java.util.Set<String> picked = new java.util.HashSet<>();
        for (String id : s.lookIds()) {
            JsonObject l = parse(s.lookJson(id));
            if (l != null) picked.addAll(images(l));
        }
        boolean dropped = false;
        com.google.gson.JsonElement le = look.get("layers");
        if (le == null || !le.isJsonArray()) return false;
        for (com.google.gson.JsonElement e : le.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            String img = VisLabEdit.str(e.getAsJsonObject(), "image");
            if (!img.isEmpty() && !picked.contains(img)) {
                e.getAsJsonObject().remove("image");
                dropped = true;
            }
        }
        return dropped;
    }

    @NonNull
    private static List<String> images(@NonNull JsonObject look) {
        List<String> out = new ArrayList<>();
        com.google.gson.JsonElement le = look.get("layers");
        if (le == null || !le.isJsonArray()) return out;
        for (com.google.gson.JsonElement e : le.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            String img = VisLabEdit.str(e.getAsJsonObject(), "image");
            if (!img.isEmpty()) out.add(img);
        }
        return out;
    }

    @NonNull
    private static ActionResult brand(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        VisLabSession s = session(ctx);
        if (Boolean.TRUE.equals(a.bool("off"))) {
            s.setBrand(null, label(), group());
            return ActionResult.changed("Brand colours off: each look uses its own.", new ArrayList<>());
        }
        List<String> c = a.list("colors");
        if (c.size() < 5) throw new ActionError("Brand colours need five colours (darkest to brightest), or off=true.");
        String[] five = new String[5];
        for (int i = 0; i < 5; i++) {
            String h = c.get(i).trim();
            if (!VisLabEdit.isHex(h)) throw new ActionError("“" + h + "” isn't a colour; use #RRGGBB.");
            five[i] = (h.startsWith("#") ? h : "#" + h).toUpperCase(Locale.ROOT);
        }
        s.setBrand(five, label(), group());
        return ActionResult.changed("Every look now uses " + String.join(" ", five) + ".", new ArrayList<>());
    }

    // ------------------------------------------------------------------ helpers

    @NonNull
    private static VisLabSession session(@NonNull AppCtx ctx) throws ActionError {
        try {
            return VisLabSession.get(ctx.app);
        } catch (IllegalStateException e) {
            throw new ActionError("VisLab didn't answer; try again in a moment.");
        }
    }

    @NonNull
    private static VisLabEdit edit(@NonNull VisLabSession s) {
        VisLabEdit e = EDIT;
        if (e == null) EDIT = e = new VisLabEdit(s.typesJson());
        return e;
    }

    @Nullable
    private static JsonObject current(@NonNull VisLabSession s) {
        return parse(s.currentLookJson());
    }

    /** The look by id or name (exact, then containing); null ref = the one on screen. */
    @NonNull
    private static JsonObject look(@NonNull VisLabSession s, @Nullable String ref) throws ActionError {
        if (ref == null || ref.trim().isEmpty()) {
            JsonObject c = current(s);
            if (c == null) throw new ActionError("VisLab has no looks.");
            return c;
        }
        String r = ref.trim().toLowerCase(Locale.ROOT);
        List<String> ids = s.lookIds(), names = s.lookNames();
        int hit = -1;
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i).equalsIgnoreCase(r) || names.get(i).toLowerCase(Locale.ROOT).equals(r)) { hit = i; break; }
        }
        if (hit < 0) {
            for (int i = 0; i < ids.size(); i++) {
                if (names.get(i).toLowerCase(Locale.ROOT).contains(r)) {
                    if (hit >= 0) throw new ActionError("Several looks match “" + ref + "”; call vislab_looks and use its id.");
                    hit = i;
                }
            }
        }
        if (hit < 0) throw new ActionError("No look is called “" + ref + "”. Looks: " + String.join(", ", names) + ".");
        JsonObject l = parse(s.lookJson(ids.get(hit)));
        if (l == null) throw new ActionError("That look couldn't be read.");
        return l;
    }

    @NonNull
    private static String freshId(@NonNull VisLabSession s, @NonNull String name) {
        String base = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "my-look";
        List<String> ids = s.lookIds();
        String id = base;
        for (int n = 2; ids.contains(id); n++) id = base + "-" + n;
        return id;
    }

    @Nullable
    private static JsonObject parse(@Nullable String json) {
        if (json == null) return null;
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The undo step's name: the person's words, as the Studio's steps are named. */
    @NonNull
    private static String label() {
        String l = AIChatState.liveWriteLabel;
        return l == null || l.isEmpty() ? "Joybot" : l;
    }

    /** The request this change belongs to: changes in one request merge into one VisLab undo step. */
    @Nullable
    private static String group() {
        String g = AIChatState.liveWriteRequest;
        return g == null || g.isEmpty() ? null : "joybot:" + g;
    }

    @NonNull
    private static ActionResult result(@NonNull VisLabSession s, @NonNull VisLabSession.ApplyResult r,
                                       @NonNull String said) throws ActionError {
        if (!r.ok) throw new ActionError(r.warnings.isEmpty() ? "VisLab couldn't show that." : String.join(" ", r.warnings));
        StringBuilder sb = new StringBuilder(said);
        if (!r.warnings.isEmpty()) sb.append("\nVisLab fixed: ").append(String.join(" ", r.warnings));
        if (!s.isRoomOpen()) sb.append("\nThe VisLab room isn't open, so it shows next time it is (open_room vislab).");
        return ActionResult.changed(sb.toString(), new ArrayList<>());
    }
}
