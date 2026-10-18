package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Joybot's hands on a VisLab look (joy.vis/1): reads one out in words and changes it from short
 * lines a model can write — {@code "Pads.speed = 0.8"}, {@code "finish.bloom = 1.2 + kick 0.3"}.
 * Pure Java over Gson (tested in tools/jvm-harness/VisLabEditTest); the knob list is VisLab's own
 * types.json, so a knob the room shows is a knob Joybot can turn. The room's LookValidator still
 * checks the result — this class only has to get names and shapes right and say what changed.
 */
public final class VisLabEdit {

    /** One knob as types.json declares it. Layer-level knobs (opacity, detail…) are made up here. */
    public static final class Knob {
        @NonNull public final String id, label;
        public final double min, max, def;
        /** Enum option names, or empty. */
        @NonNull public final List<String> options;
        public final boolean toggle;
        /** Where the value lives: "p" (the knob list) or "top" (a field on the layer itself). */
        final boolean top;
        /**
         * types.json "showFor": the knob only does something while these choice knobs hold one of
         * these options ({"path": [0, 5]}: Ribbon or Flight). Empty = always.
         */
        final Map<String, List<Integer>> showFor = new LinkedHashMap<>();

        Knob(@NonNull String id, @NonNull String label, double min, double max, double def,
             @NonNull List<String> options, boolean toggle, boolean top) {
            this.id = id;
            this.label = label;
            this.min = min;
            this.max = max;
            this.def = def;
            this.options = options;
            this.toggle = toggle;
            this.top = top;
        }

        boolean musical() {
            return options.isEmpty() && !toggle;
        }
    }

    /** A layer type (or the Finish / Echo pass). */
    public static final class Kind {
        @NonNull public final String id, label, family, about;
        @NonNull public final List<Knob> knobs;

        Kind(@NonNull String id, @NonNull String label, @NonNull String family, @NonNull String about,
             @NonNull List<Knob> knobs) {
            this.id = id;
            this.label = label;
            this.family = family;
            this.about = about;
            this.knobs = knobs;
        }
    }

    public final Map<String, Kind> types = new LinkedHashMap<>();
    @NonNull public final Kind finish, echo;
    /** Signal id → label ("kick" → "Kick"). */
    public final Map<String, String> signals = new LinkedHashMap<>();
    private final Map<String, String> familyLabels = new LinkedHashMap<>();

    /** Fields every layer has besides its knobs (CompiledScene.Layer reads them). */
    private static final List<Knob> LAYER_KNOBS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Knob("on", "Shown", 0, 1, 1, Collections.<String>emptyList(), true, true),
            new Knob("opacity", "Opacity", 0, 1, 1, Collections.<String>emptyList(), false, true),
            new Knob("add", "Light blend", 0, 1, 1, Collections.<String>emptyList(), false, true),
            new Knob("res", "Detail", 0.25, 1, 1, Collections.<String>emptyList(), false, true),
            new Knob("echo", "Under the echo", 0, 1, 1, Collections.<String>emptyList(), true, true)));
    private static final Knob ECHO_ON =
            new Knob("on", "On", 0, 1, 1, Collections.<String>emptyList(), true, true);

    public VisLabEdit(@NonNull String typesJson) {
        JsonObject t = JsonParser.parseString(typesJson).getAsJsonObject();
        for (JsonElement e : arr(t, "signals")) {
            JsonObject s = e.getAsJsonObject();
            signals.put(str(s, "id"), str(s, "label"));
        }
        for (JsonElement e : arr(t, "families")) {
            JsonObject s = e.getAsJsonObject();
            familyLabels.put(str(s, "id"), str(s, "label"));
        }
        JsonObject ty = t.has("types") ? t.getAsJsonObject("types") : new JsonObject();
        for (Map.Entry<String, JsonElement> en : ty.entrySet()) {
            types.put(en.getKey(), kind(en.getKey(), en.getValue().getAsJsonObject()));
        }
        finish = kind("finish", t.has("post") ? t.getAsJsonObject("post") : new JsonObject());
        echo = kind("echo", t.has("echo") ? t.getAsJsonObject("echo") : new JsonObject());
    }

    @NonNull
    private Kind kind(@NonNull String id, @NonNull JsonObject o) {
        List<Knob> knobs = new ArrayList<>();
        for (JsonElement e : arr(o, "params")) {
            JsonObject p = e.getAsJsonObject();
            List<String> opts = new ArrayList<>();
            for (JsonElement x : arr(p, "enum")) opts.add(x.getAsString());
            boolean toggle = p.has("toggle") && p.get("toggle").getAsBoolean();
            double def = num(p, "def", 0);
            double min = opts.isEmpty() ? (toggle ? 0 : num(p, "min", 0)) : 0;
            double max = opts.isEmpty() ? (toggle ? 1 : num(p, "max", 1)) : opts.size() - 1;
            Knob k = new Knob(str(p, "id"), str(p, "label"), min, max, def, opts, toggle, false);
            JsonObject sf = obj(p, "showFor");
            if (sf != null) {
                for (Map.Entry<String, JsonElement> en : sf.entrySet()) {
                    List<Integer> ok = new ArrayList<>();
                    if (en.getValue().isJsonArray()) for (JsonElement x : en.getValue().getAsJsonArray()) ok.add(x.getAsInt());
                    k.showFor.put(en.getKey(), ok);
                }
            }
            knobs.add(k);
        }
        String fam = str(o, "family");
        return new Kind(id, str(o, "label").isEmpty() ? id : str(o, "label"),
                familyLabels.containsKey(fam) ? familyLabels.get(fam) : fam, str(o, "about"), knobs);
    }

    // ------------------------------------------------------------------ reading out

    /** One line per look for a list: name, id, family and what it is. */
    @NonNull
    public static String lookLine(@NonNull JsonObject look) {
        String d = str(look, "description");
        return "“" + str(look, "name") + "” (" + str(look, "id") + ")"
                + (str(look, "family").isEmpty() ? "" : " — " + str(look, "family"))
                + (d.isEmpty() ? "" : ": " + d);
    }

    /**
     * The look in words. With no {@code layerRef}: each layer and the knobs the look sets (the rest
     * are at their defaults), and the Finish/Echo knobs it sets. With one: every knob of that
     * layer (or "finish" / "echo") with its value and range.
     */
    @NonNull
    public String describe(@NonNull JsonObject look, @Nullable String layerRef) throws ActionError {
        StringBuilder sb = new StringBuilder();
        if (layerRef == null || layerRef.trim().isEmpty()) {
            sb.append(lookLine(look)).append('\n');
            // Printed the way vislab_change takes them, so a copied line is a valid change.
            sb.append("look.palette = ").append(colours(look.get("palette")))
                    .append("; look.background = ").append(look.has("background") && look.get("background").isJsonPrimitive()
                            ? look.get("background").getAsString() : "none").append('\n');
            JsonArray layers = layers(look);
            sb.append(layers.size()).append(" layers, back to front:\n");
            for (int i = 0; i < layers.size(); i++) {
                JsonObject L = layers.get(i).getAsJsonObject();
                Kind k = types.get(str(L, "type"));
                sb.append(i + 1).append(". ").append(layerTitle(L, k)).append(':');
                if (k != null) appendSet(sb, k.knobs, L, false);
                appendSet(sb, LAYER_KNOBS, L, true);
                sb.append('\n');
            }
            JsonObject post = obj(look, "post");
            sb.append("Finish:");
            if (post != null) appendSet(sb, finish.knobs, post, false);
            sb.append('\n');
            JsonObject ec = obj(look, "echo");
            sb.append("Echo: ").append(ec == null || !on(ec) ? "off" : "on");
            if (ec != null) appendSet(sb, echo.knobs, ec, false);
            sb.append("\nOnly knobs that differ from their defaults are listed; describe one layer (or finish /"
                    + " echo) to see all of its knobs and their ranges.");
            return sb.toString().replace(":; ", ": ");
        }
        Target t = target(look, layerRef);
        sb.append(t.title).append(" — every knob (id “label” = value, range):\n");
        int hidden = 0;
        for (Knob k : t.knobs) {
            if (whyIdle(k, t) != null) { hidden++; continue; }
            sb.append("  ").append(knobLine(k, t.holder)).append('\n');
        }
        if (hidden > 0) sb.append("(").append(hidden).append(" more knobs only do something with another ")
                .append(String.join(" / ", controllers(t))).append("; change that first to use them.)\n");
        if (t.kind != null && !t.kind.about.isEmpty()) sb.append(t.kind.about).append('\n');
        sb.append("Music: add “+ signal amount” to a number (amount = share of the knob's range, -2…2). Signals: ")
                .append(String.join(", ", signals.keySet())).append('.');
        return sb.toString();
    }

    /** The layer types: all of them in one line each, or one type's knobs. */
    @NonNull
    public String describeTypes(@Nullable String typeRef) throws ActionError {
        StringBuilder sb = new StringBuilder();
        if (typeRef == null || typeRef.trim().isEmpty()) {
            sb.append("Layer types (id “name”, family: what it is):\n");
            for (Kind k : types.values()) {
                sb.append(k.id).append(" “").append(k.label).append("”, ").append(k.family)
                        .append(": ").append(k.about).append('\n');
            }
            return sb.toString().trim();
        }
        Kind k = kindOf(typeRef);
        sb.append(k.id).append(" “").append(k.label).append("”: ").append(k.about).append("\nKnobs:\n");
        for (Knob n : k.knobs) sb.append("  ").append(knobLine(n, null)).append('\n');
        return sb.toString().trim();
    }

    @NonNull
    private String knobLine(@NonNull Knob k, @Nullable JsonObject holder) {
        StringBuilder sb = new StringBuilder(k.id).append(" “").append(k.label).append("”");
        if (holder != null) sb.append(" = ").append(valueWords(k, holder));
        else sb.append(" default ").append(plain(k, k.def));
        if (!k.options.isEmpty()) sb.append("  [").append(String.join(" | ", k.options)).append(']');
        else if (k.toggle) sb.append("  [on | off]");
        else sb.append("  [").append(fmt(k.min)).append(" … ").append(fmt(k.max)).append(']');
        return sb.toString();
    }

    private void appendSet(@NonNull StringBuilder sb, @NonNull List<Knob> knobs, @NonNull JsonObject holder,
                           boolean top) {
        JsonObject p = top ? holder : obj(holder, "p");
        if (p == null) return;
        for (Knob k : knobs) {
            if (!p.has(k.id) || (top && "type".equals(k.id))) continue;
            sb.append("; ").append(k.id).append(" = ").append(valueWords(k, holder));
        }
    }

    @NonNull
    private String valueWords(@NonNull Knob k, @NonNull JsonObject holder) {
        JsonElement v = raw(k, holder);
        if (v == null) return plain(k, k.def);
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            StringBuilder sb = new StringBuilder(plain(k, num(o, "v", k.def)));
            JsonObject mod = obj(o, "mod");
            if (mod != null) {
                for (Map.Entry<String, JsonElement> m : mod.entrySet()) {
                    sb.append(" + ").append(m.getKey()).append(' ').append(fmt(m.getValue().getAsDouble()));
                }
            }
            return sb.toString();
        }
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) return v.getAsBoolean() ? "on" : "off";
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) return plain(k, v.getAsDouble());
        return v.toString();
    }

    @NonNull
    private static String plain(@NonNull Knob k, double d) {
        if (!k.options.isEmpty()) {
            int i = (int) Math.round(d);
            return i >= 0 && i < k.options.size() ? k.options.get(i) : fmt(d);
        }
        if (k.toggle) return d >= 0.5 ? "on" : "off";
        return fmt(d);
    }

    // ------------------------------------------------------------------ changing

    /**
     * Applies one change line to {@code look} (in place) and says what changed. Lines:
     * <ul>
     *   <li>{@code <layer>.<knob> = <value>} — layer by number (1 = back), name, or type; or
     *       {@code finish} / {@code echo}. knob by id or label.</li>
     *   <li>value: a number, an option name, on / off; then optional {@code + <signal> <amount>}
     *       parts to make it move with the music, or {@code still} to stop it moving. An empty
     *       value with {@code + …} parts keeps the number and adds the music.</li>
     *   <li>{@code look.name|palette|background = …} and {@code <layer>.palette = #a, #b, …}.</li>
     * </ul>
     */
    @NonNull
    public String change(@NonNull JsonObject look, @NonNull String line) throws ActionError {
        // Models copy what describe() printed, so "=", ":" and "Palette #a #b…" all mean the same.
        String l = line.trim();
        int eq = l.indexOf('=');
        if (eq < 0) eq = l.indexOf(':');
        if (eq < 0) {
            java.util.regex.Matcher m = LOOK_FIELD_BARE.matcher(l);
            if (m.find()) eq = m.end(1);
        }
        if (eq < 0) throw new ActionError("“" + line + "” needs an = : like Pads.speed = 0.8");
        String left = l.substring(0, eq).trim(), right = l.substring(eq).replaceFirst("^[=:]", "").trim();
        int dot = left.lastIndexOf('.');
        if (dot < 0 && LOOK_FIELDS.matcher(norm(left)).matches()) {
            return lookField(look, lookFieldName(norm(left)), right);
        }
        if (dot < 0) return changeUnique(look, left, right);
        if (dot == 0 || dot == left.length() - 1) {
            throw new ActionError("“" + left + "” should be layer.knob, like Pads.speed or finish.bloom");
        }
        String where = left.substring(0, dot).trim(), what = left.substring(dot + 1).trim();
        String w = norm(where), n = norm(what);
        if ("look".equals(w)) return lookField(look, lookFieldName(n), right);
        if ("palette".equals(n) && !"finish".equals(w) && !"echo".equals(w)) {
            Target t = target(look, where);
            JsonArray pal = palette(right);
            t.holder.add("palette", pal);
            return t.title + " colours → " + colours(pal);
        }
        Target t = target(look, where);
        Knob k = knob(t, what);
        String before = valueWords(k, t.holder);
        set(t, k, right);
        String idle = whyIdle(k, t);
        return t.title + " › " + k.label + ": " + before + " → " + valueWords(k, t.holder)
                + (idle == null ? "" : " (no effect yet: " + idle + ")");
    }

    /**
     * Null when knob {@code k} does something with the target's current choices; otherwise why
     * not, in words ("it only works when Path is Ribbon, Flight or Rising").
     */
    @Nullable
    private String whyIdle(@NonNull Knob k, @NonNull Target t) {
        for (Map.Entry<String, List<Integer>> e : k.showFor.entrySet()) {
            Knob ctl = null;
            for (Knob c : t.knobs) if (c.id.equals(e.getKey())) ctl = c;
            if (ctl == null) continue;
            JsonElement v = raw(ctl, t.holder);
            int now = (int) Math.round(v == null ? ctl.def : v.isJsonObject() ? num(v.getAsJsonObject(), "v", ctl.def)
                    : v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : ctl.def);
            if (e.getValue().contains(now)) continue;
            List<String> names = new ArrayList<>();
            for (int i : e.getValue()) names.add(i >= 0 && i < ctl.options.size() ? ctl.options.get(i) : String.valueOf(i));
            return "it only works when " + ctl.label + " is " + String.join(", ", names);
        }
        return null;
    }

    /** The choice knobs other knobs depend on ("Path", "Behaviour"). */
    @NonNull
    private static List<String> controllers(@NonNull Target t) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (Knob k : t.knobs) ids.addAll(k.showFor.keySet());
        List<String> out = new ArrayList<>();
        for (Knob k : t.knobs) if (ids.contains(k.id)) out.add(k.label);
        return out;
    }

    private static final java.util.regex.Pattern LOOK_FIELDS =
            java.util.regex.Pattern.compile("name|palette|colou?rs|background");
    /** "Palette #a #b…" / "background #000000" with no = (as describe() prints them). */
    private static final java.util.regex.Pattern LOOK_FIELD_BARE =
            java.util.regex.Pattern.compile("(?i)^(?:look\\.)?(palette|colou?rs|background)\\s+(?=#)");

    @NonNull
    private static String lookFieldName(@NonNull String n) {
        return n.matches("colou?rs") ? "palette" : n;
    }

    /** "speed = 0.8" with no layer: fine when exactly one layer (or Finish / Echo) has that knob. */
    @NonNull
    private String changeUnique(@NonNull JsonObject look, @NonNull String knobRef, @NonNull String right) throws ActionError {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < layers(look).size(); i++) refs.add(String.valueOf(i + 1));
        refs.add("finish");
        refs.add("echo");
        Target hit = null;
        Knob hitKnob = null;
        List<String> where = new ArrayList<>();
        for (String r : refs) {
            Target t = target(look, r);
            try {
                Knob k = knob(t, knobRef);
                if (k.top) continue;   // every layer has opacity etc.: never a unique match
                hit = t;
                hitKnob = k;
                where.add(t.title);
            } catch (ActionError ignored) { }
        }
        if (where.size() != 1) {
            throw new ActionError("Say which layer: like Pads." + knobRef.trim() + " = …"
                    + (where.isEmpty() ? " (no layer has a knob “" + knobRef.trim() + "”)."
                    : " (" + String.join(", ", where) + " all have it)."));
        }
        String before = valueWords(hitKnob, hit.holder);
        set(hit, hitKnob, right);
        return hit.title + " › " + hitKnob.label + ": " + before + " → " + valueWords(hitKnob, hit.holder);
    }

    @NonNull
    private String lookField(@NonNull JsonObject look, @NonNull String field, @NonNull String v) throws ActionError {
        switch (field) {
            case "name": {
                String name = unquote(v);
                if (name.isEmpty()) throw new ActionError("look.name needs a name.");
                look.addProperty("name", name);
                return "Name → “" + name + "”";
            }
            case "palette": {
                JsonArray pal = palette(v);
                look.add("palette", pal);
                return "Colours → " + colours(pal);
            }
            case "background": {
                String c = unquote(v);
                if (norm(c).matches("none|clear|see ?through|transparent")) {
                    look.remove("background");
                    return "Background → see-through";
                }
                if (!isHex(c)) throw new ActionError("look.background must be a colour like #020208 (or none).");
                look.addProperty("background", c.toUpperCase(Locale.ROOT));
                return "Background → " + c.toUpperCase(Locale.ROOT);
            }
            default:
                throw new ActionError("A look has name, palette and background; “" + field + "” isn't one.");
        }
    }

    private void set(@NonNull Target t, @NonNull Knob k, @NonNull String right) throws ActionError {
        String[] parts = right.split("\\+");
        String base = parts[0].trim();
        boolean still = false;
        if (norm(base).endsWith("still")) {
            still = true;
            base = base.substring(0, base.length() - 5).trim();
        }
        JsonObject holder = k.top ? t.holder : ensure(t.holder, "p");
        JsonElement old = holder.get(k.id);
        double baseNum;
        if (base.isEmpty()) {
            baseNum = old == null ? k.def : old.isJsonObject() ? num(old.getAsJsonObject(), "v", k.def)
                    : old.isJsonPrimitive() && old.getAsJsonPrimitive().isBoolean() ? (old.getAsBoolean() ? 1 : 0)
                    : old.getAsDouble();
        } else {
            baseNum = parseValue(t, k, base);
        }
        // The music links: kept unless the line says "still" or brings its own.
        JsonObject mod = null;
        if (!still && old != null && old.isJsonObject() && obj(old.getAsJsonObject(), "mod") != null) {
            mod = obj(old.getAsJsonObject(), "mod").deepCopy();
        }
        for (int i = 1; i < parts.length; i++) {
            if (!k.musical()) {
                throw new ActionError(t.title + " › " + k.label + " is a choice, so it can't move with the music.");
            }
            String part = parts[i].trim();
            int sp = part.lastIndexOf(' ');
            if (sp <= 0) throw new ActionError("“+ " + part + "” should be + signal amount, like + kick 0.3");
            String sig = signal(part.substring(0, sp).trim());
            double amt;
            try {
                amt = Double.parseDouble(part.substring(sp + 1).trim());
            } catch (NumberFormatException e) {
                throw new ActionError("“+ " + part + "”: the amount should be a number from -2 to 2.");
            }
            if (mod == null) mod = new JsonObject();
            if (amt == 0) mod.remove(sig);
            else mod.addProperty(sig, Math.max(-2, Math.min(2, amt)));
        }
        if (k.toggle && k.top) {
            holder.addProperty(k.id, baseNum >= 0.5);
        } else if (mod != null && mod.size() > 0) {
            JsonObject o = new JsonObject();
            o.addProperty("v", round(baseNum));
            o.add("mod", mod);
            holder.add(k.id, o);
        } else {
            holder.addProperty(k.id, k.options.isEmpty() && !k.toggle ? round(baseNum) : (int) Math.round(baseNum));
        }
    }

    private double parseValue(@NonNull Target t, @NonNull Knob k, @NonNull String s) throws ActionError {
        String v = norm(unquote(s));
        if (k.toggle) {
            if (v.matches("on|yes|true|1|show|shown")) return 1;
            if (v.matches("off|no|false|0|hide|hidden")) return 0;
            throw new ActionError(t.title + " › " + k.label + " is on or off, not “" + s + "”.");
        }
        if (!k.options.isEmpty()) {
            for (int i = 0; i < k.options.size(); i++) if (norm(k.options.get(i)).equals(v)) return i;
            int hit = -1;
            for (int i = 0; i < k.options.size(); i++) {
                if (norm(k.options.get(i)).contains(v)) {
                    if (hit >= 0) { hit = -2; break; }
                    hit = i;
                }
            }
            if (hit >= 0) return hit;
            try {
                double d = Double.parseDouble(v);
                if (d == Math.rint(d) && d >= 0 && d < k.options.size()) return d;
            } catch (NumberFormatException ignored) { }
            throw new ActionError(t.title + " › " + k.label + " is one of: " + String.join(", ", k.options) + ".");
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new ActionError(t.title + " › " + k.label + " takes a number from " + fmt(k.min) + " to "
                    + fmt(k.max) + ", not “" + s + "”.");
        }
    }

    @NonNull
    private String signal(@NonNull String s) throws ActionError {
        String n = norm(s);
        for (Map.Entry<String, String> e : signals.entrySet()) {
            if (norm(e.getKey()).equals(n) || norm(e.getValue()).equals(n)) return e.getKey();
        }
        throw new ActionError("There's no music signal called “" + s + "”. Signals: "
                + String.join(", ", signals.keySet()) + ".");
    }

    // ------------------------------------------------------------------ layers

    /** Adds a layer of {@code typeRef} at {@code position} (1 = back; null = on top). */
    @NonNull
    public String addLayer(@NonNull JsonObject look, @NonNull String typeRef, @Nullable String name,
                           @Nullable Integer position) throws ActionError {
        Kind k = kindOf(typeRef);
        JsonArray layers = layers(look);
        JsonObject L = new JsonObject();
        L.addProperty("type", k.id);
        L.addProperty("name", name == null || name.trim().isEmpty() ? k.label : name.trim());
        L.add("p", new JsonObject());
        int at = position == null ? layers.size() : Math.max(0, Math.min(layers.size(), position - 1));
        insert(layers, at, L);
        return "Added " + layerTitle(L, k) + " as layer " + (at + 1);
    }

    @NonNull
    public String removeLayer(@NonNull JsonObject look, @NonNull String ref) throws ActionError {
        JsonArray layers = layers(look);
        int i = layerIndex(look, ref);
        JsonObject L = layers.get(i).getAsJsonObject();
        layers.remove(i);
        return "Removed " + layerTitle(L, types.get(str(L, "type")));
    }

    /** {@code "Pads to 1"} / {@code "Pads to front"} / {@code "Pads to back"}. */
    @NonNull
    public String moveLayer(@NonNull JsonObject look, @NonNull String line) throws ActionError {
        int to = norm(line).lastIndexOf(" to ");
        if (to < 0) throw new ActionError("“" + line + "” should be like: Pads to 1, or Pads to front");
        String ref = line.substring(0, to).trim(), where = norm(line.substring(to + 4));
        JsonArray layers = layers(look);
        int from = layerIndex(look, ref);
        int at;
        if (where.matches("front|top")) at = layers.size() - 1;
        else if (where.matches("back|bottom")) at = 0;
        else {
            try {
                at = Integer.parseInt(where) - 1;
            } catch (NumberFormatException e) {
                throw new ActionError("Move “" + ref + "” to a number (1 = back), front or back.");
            }
        }
        at = Math.max(0, Math.min(layers.size() - 1, at));
        JsonElement L = layers.remove(from);
        insert(layers, at, L);
        return "Moved " + layerTitle(L.getAsJsonObject(), types.get(str(L.getAsJsonObject(), "type")))
                + " to layer " + (at + 1);
    }

    private static void insert(@NonNull JsonArray a, int at, @NonNull JsonElement e) {
        List<JsonElement> all = new ArrayList<>();
        for (JsonElement x : a) all.add(x);
        all.add(at, e);
        while (a.size() > 0) a.remove(a.size() - 1);
        for (JsonElement x : all) a.add(x);
    }

    // ------------------------------------------------------------------ finding things

    private static final class Target {
        @NonNull final String title;
        @NonNull final JsonObject holder;
        @NonNull final List<Knob> knobs;
        @Nullable final Kind kind;

        Target(@NonNull String title, @NonNull JsonObject holder, @NonNull List<Knob> knobs, @Nullable Kind kind) {
            this.title = title;
            this.holder = holder;
            this.knobs = knobs;
            this.kind = kind;
        }
    }

    @NonNull
    private Target target(@NonNull JsonObject look, @NonNull String ref) throws ActionError {
        String r = norm(ref);
        if (r.matches("finish|post")) return new Target("Finish", ensure(look, "post"), finish.knobs, finish);
        if (r.equals("echo")) {
            List<Knob> k = new ArrayList<>();
            k.add(ECHO_ON);
            k.addAll(echo.knobs);
            return new Target("Echo", ensure(look, "echo"), k, echo);
        }
        int i = layerIndex(look, ref);
        JsonObject L = layers(look).get(i).getAsJsonObject();
        Kind k = types.get(str(L, "type"));
        List<Knob> knobs = new ArrayList<>(k == null ? Collections.<Knob>emptyList() : k.knobs);
        knobs.addAll(LAYER_KNOBS);
        return new Target(layerTitle(L, k), L, knobs, k);
    }

    private int layerIndex(@NonNull JsonObject look, @NonNull String ref) throws ActionError {
        JsonArray layers = layers(look);
        String r = norm(ref).replaceFirst("^layer ", "");
        try {
            int n = Integer.parseInt(r);
            if (n >= 1 && n <= layers.size()) return n - 1;
            throw new ActionError("This look has " + layers.size() + " layers; there's no layer " + n + ".");
        } catch (NumberFormatException ignored) { }
        List<Integer> byName = new ArrayList<>(), byType = new ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            JsonObject L = layers.get(i).getAsJsonObject();
            Kind k = types.get(str(L, "type"));
            if (norm(str(L, "name")).equals(r)) byName.add(i);
            else if (norm(str(L, "type")).equals(r) || (k != null && norm(k.label).equals(r))) byType.add(i);
        }
        List<Integer> hit = !byName.isEmpty() ? byName : byType;
        if (hit.size() == 1) return hit.get(0);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < layers.size(); i++) {
            JsonObject L = layers.get(i).getAsJsonObject();
            names.append(i == 0 ? "" : ", ").append(i + 1).append(" ").append(layerTitle(L, types.get(str(L, "type"))));
        }
        throw new ActionError((hit.isEmpty() ? "No layer is called “" + ref + "”." : "Several layers match “" + ref + "”;"
                + " use its number.") + " Layers: " + names + "; or finish / echo.");
    }

    @NonNull
    private Knob knob(@NonNull Target t, @NonNull String ref) throws ActionError {
        String r = norm(ref);
        for (Knob k : t.knobs) if (norm(k.id).equals(r)) return k;
        for (Knob k : t.knobs) if (norm(k.label).equals(r)) return k;
        List<String> ids = new ArrayList<>();
        for (Knob k : t.knobs) ids.add(k.id);
        throw new ActionError(t.title + " has no knob “" + ref + "”. Its knobs: " + String.join(", ", ids) + ".");
    }

    @NonNull
    private Kind kindOf(@NonNull String ref) throws ActionError {
        String r = norm(ref);
        for (Kind k : types.values()) if (norm(k.id).equals(r) || norm(k.label).equals(r)) return k;
        throw new ActionError("There's no layer type “" + ref + "”. Types: " + String.join(", ", types.keySet()) + ".");
    }

    @NonNull
    private static String layerTitle(@NonNull JsonObject L, @Nullable Kind k) {
        String name = str(L, "name"), type = k == null ? str(L, "type") : k.label;
        String hidden = L.has("on") && L.get("on").isJsonPrimitive() && !L.get("on").getAsBoolean() ? " (hidden)" : "";
        return (name.isEmpty() || name.equals(type) ? "“" + type + "”" : "“" + name + "” (" + type + ")") + hidden;
    }

    // ------------------------------------------------------------------ small helpers

    @Nullable
    private static JsonElement raw(@NonNull Knob k, @NonNull JsonObject holder) {
        if (k.top) return holder.get(k.id);
        JsonObject p = obj(holder, "p");
        return p == null ? null : p.get(k.id);
    }

    private static boolean on(@NonNull JsonObject o) {
        JsonElement e = o.get("on");
        return e == null || !e.isJsonPrimitive() || e.getAsBoolean();
    }

    @NonNull
    private static JsonArray layers(@NonNull JsonObject look) {
        JsonElement e = look.get("layers");
        if (e == null || !e.isJsonArray()) {
            JsonArray a = new JsonArray();
            look.add("layers", a);
            return a;
        }
        return e.getAsJsonArray();
    }

    @NonNull
    private static JsonObject ensure(@NonNull JsonObject o, @NonNull String key) {
        JsonObject c = obj(o, key);
        if (c == null) {
            c = new JsonObject();
            o.add(key, c);
        }
        return c;
    }

    @Nullable
    private static JsonObject obj(@NonNull JsonObject o, @NonNull String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    @NonNull
    private static Iterable<JsonElement> arr(@NonNull JsonObject o, @NonNull String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    @NonNull
    static String str(@NonNull JsonObject o, @NonNull String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static double num(@NonNull JsonObject o, @NonNull String key, double dflt) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return dflt;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
        return p.isNumber() ? p.getAsDouble() : dflt;
    }

    @NonNull
    private static JsonArray palette(@NonNull String s) throws ActionError {
        JsonArray a = new JsonArray();
        for (String c : unquote(s).replaceAll("[\\[\\]\"]", "").split("[,\\s]+")) {
            if (c.isEmpty()) continue;
            if (!isHex(c)) throw new ActionError("“" + c + "” isn't a colour; use #RRGGBB.");
            a.add((c.startsWith("#") ? c : "#" + c).toUpperCase(Locale.ROOT));
        }
        if (a.size() < 2) throw new ActionError("A palette needs at least two colours (VisLab looks use five).");
        return a;
    }

    @NonNull
    private static String colours(@Nullable JsonElement pal) {
        if (pal == null || !pal.isJsonArray()) return "(its own)";
        List<String> c = new ArrayList<>();
        for (JsonElement e : pal.getAsJsonArray()) c.add(e.getAsString());
        return String.join(" ", c);
    }

    static boolean isHex(@NonNull String s) {
        return s.trim().matches("#?([0-9a-fA-F]{6}|[0-9a-fA-F]{3})");
    }

    @NonNull
    private static String unquote(@NonNull String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("“") && t.endsWith("”"))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    @NonNull
    private static String norm(@NonNull String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    }

    private static double round(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    @NonNull
    static String fmt(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return String.valueOf((long) d);
        String s = String.format(Locale.US, "%.3f", d);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
