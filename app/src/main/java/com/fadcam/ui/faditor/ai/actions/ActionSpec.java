package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One thing a person can do in Joy Creator, declared once (gap F2 — see
 * tasks/joybot/ACTION_REGISTRY_SPEC_20261015.md). Joybot's native tool definitions, the JSON-in-text
 * fallback, argument checking, help and recipes all read it, so they can never disagree.
 *
 * <p>Pure Java (tested off device in IdeaEngineTest): no Android, no org.json — JSON is written by
 * hand ({@link #schemaJson()}).</p>
 */
public final class ActionSpec {

    /** What running it does to the person's work — what Joybot must ask before doing. */
    public enum Effect {
        /** Only reads. */ READ,
        /** Changes the project; one Undo puts it back. */ EDIT,
        /** Removes or reorders a lot: propose first unless asked for exactly that. */ DESTRUCTIVE,
        /** Spends money: price first, then consent. */ PAID,
        /** Opens another screen. */ NAVIGATE
    }

    /** A parameter's kind. */
    public enum Type { STRING, INT, NUMBER, BOOL, ENUM, ID, TIME_MS, COLOR, STRING_LIST, ID_LIST, OBJECT }

    /** One parameter. Build with {@link #param}. */
    public static final class Param {
        @NonNull public final String name;
        @NonNull public final Type type;
        public final boolean required;
        @NonNull public final String help;
        /** ENUM: the allowed values. */
        @NonNull public final List<String> values;
        /** ID / ID_LIST: which kinds of thing ("text", "image", "clip", "any"…). */
        @NonNull public final String idKind;
        @Nullable public final Double min, max;

        Param(@NonNull String name, @NonNull Type type, boolean required, @NonNull String help,
              @NonNull List<String> values, @NonNull String idKind, @Nullable Double min, @Nullable Double max) {
            this.name = name; this.type = type; this.required = required; this.help = help;
            this.values = values; this.idKind = idKind; this.min = min; this.max = max;
        }
    }

    @NonNull public final String tool;
    @NonNull public final String title;
    @NonNull public final String help;
    @NonNull public final String wing;
    @NonNull public final String family;
    @NonNull public final Effect effect;
    public final boolean offline;
    /** The path through the real UI ("Studio › tap a text › Style › Font"), for help and show-me-how. */
    @NonNull public final String where;
    @NonNull public final List<Param> params;
    /** Free-form arguments (a legacy tool not yet described param by param). */
    public final boolean openArgs;

    private ActionSpec(Builder b) {
        tool = b.tool; title = b.title; help = b.help; wing = b.wing; family = b.family;
        effect = b.effect; offline = b.offline; where = b.where; openArgs = b.openArgs;
        params = Collections.unmodifiableList(new ArrayList<>(b.params));
    }

    @NonNull
    public static Builder of(@NonNull String tool, @NonNull String title) {
        if (!tool.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("bad tool name: " + tool);
        return new Builder(tool, title);
    }

    public static final class Builder {
        final String tool, title;
        String help = "", wing = "STUDIO", family = "", where = "";
        Effect effect = Effect.EDIT;
        boolean offline = true, openArgs;
        final List<Param> params = new ArrayList<>();

        Builder(String tool, String title) { this.tool = tool; this.title = title; }

        public Builder help(@NonNull String h) { help = h; return this; }
        public Builder wing(@NonNull String w, @NonNull String fam) { wing = w; family = fam; return this; }
        public Builder effect(@NonNull Effect e) { effect = e; return this; }
        public Builder online() { offline = false; return this; }
        public Builder where(@NonNull String w) { where = w; return this; }
        public Builder openArgs() { openArgs = true; return this; }

        public Builder param(@NonNull String name, @NonNull Type type, boolean required, @NonNull String help) {
            params.add(new Param(name, type, required, help, Collections.emptyList(), "any", null, null));
            return this;
        }

        public Builder id(@NonNull String name, @NonNull String kind, boolean required, @NonNull String help) {
            params.add(new Param(name, Type.ID, required, help, Collections.emptyList(), kind, null, null));
            return this;
        }

        public Builder choice(@NonNull String name, boolean required, @NonNull String help, @NonNull List<String> values) {
            params.add(new Param(name, Type.ENUM, required, help, new ArrayList<>(values), "any", null, null));
            return this;
        }

        public Builder number(@NonNull String name, boolean required, @NonNull String help,
                              @Nullable Double min, @Nullable Double max) {
            params.add(new Param(name, Type.NUMBER, required, help, Collections.emptyList(), "any", min, max));
            return this;
        }

        /** A time on the timeline in milliseconds, with the UI's own limits. */
        public Builder time(@NonNull String name, boolean required, @NonNull String help,
                            @Nullable Double min, @Nullable Double max) {
            params.add(new Param(name, Type.TIME_MS, required, help, Collections.emptyList(), "any", min, max));
            return this;
        }

        public Builder color(@NonNull String name, boolean required, @NonNull String help) {
            return param(name, Type.COLOR, required, help);
        }

        public Builder flag(@NonNull String name, boolean required, @NonNull String help) {
            return param(name, Type.BOOL, required, help);
        }

        public Builder text(@NonNull String name, boolean required, @NonNull String help) {
            return param(name, Type.STRING, required, help);
        }

        @NonNull public ActionSpec build() { return new ActionSpec(this); }
    }

    @Nullable
    public Param param(@NonNull String name) {
        for (Param p : params) if (p.name.equals(name)) return p;
        return null;
    }

    // ── what the model is shown ──────────────────────────────────────────────

    /** The description sent with the native tool: help + where it lives in the app. */
    @NonNull
    public String description() {
        String d = help.isEmpty() ? title : help;
        if (!where.isEmpty()) d += " (In the app: " + where + ".)";
        if (effect == Effect.DESTRUCTIVE) d += " Big change: propose it first unless they asked for exactly this.";
        if (effect == Effect.PAID) d += " Costs money: say the price and wait for a yes.";
        return d.length() > 1000 ? d.substring(0, 999) + "…" : d;
    }

    /** The JSON Schema of the arguments, as JSON text. */
    @NonNull
    public String schemaJson() {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        boolean first = true;
        for (Param p : params) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(p.name)).append(':').append(paramSchema(p));
        }
        sb.append('}');
        List<String> req = new ArrayList<>();
        for (Param p : params) if (p.required) req.add(p.name);
        if (!req.isEmpty()) {
            sb.append(",\"required\":[");
            for (int i = 0; i < req.size(); i++) sb.append(i > 0 ? "," : "").append(quote(req.get(i)));
            sb.append(']');
        }
        sb.append(",\"additionalProperties\":").append(openArgs ? "true" : "false").append('}');
        return sb.toString();
    }

    @NonNull
    private static String paramSchema(@NonNull Param p) {
        String desc = p.help;
        StringBuilder sb = new StringBuilder("{");
        switch (p.type) {
            case INT: case TIME_MS: sb.append("\"type\":\"integer\""); break;
            case NUMBER: sb.append("\"type\":\"number\""); break;
            case BOOL: sb.append("\"type\":\"boolean\""); break;
            case ENUM:
                sb.append("\"type\":\"string\",\"enum\":[");
                for (int i = 0; i < p.values.size(); i++) sb.append(i > 0 ? "," : "").append(quote(p.values.get(i)));
                sb.append(']');
                break;
            case STRING_LIST: case ID_LIST: sb.append("\"type\":\"array\",\"items\":{\"type\":\"string\"}"); break;
            case OBJECT: sb.append("\"type\":\"object\""); break;
            default: sb.append("\"type\":\"string\"");
        }
        if (p.type == Type.TIME_MS) desc += " (milliseconds on the timeline)";
        if (p.type == Type.COLOR) desc += " (#RRGGBB or #AARRGGBB)";
        if (p.type == Type.ID || p.type == Type.ID_LIST) {
            desc += " (" + ("any".equals(p.idKind) ? "an item" : "a " + p.idKind) + " id or its name)";
        }
        if (p.min != null) sb.append(",\"minimum\":").append(num(p.min));
        if (p.max != null) sb.append(",\"maximum\":").append(num(p.max));
        if (!desc.isEmpty()) sb.append(",\"description\":").append(quote(desc));
        return sb.append('}').toString();
    }

    /** The numbered-text form for models without tool calling (same shape as getToolDescriptions). */
    @NonNull
    public String docsEntry(@NonNull String number) {
        StringBuilder sb = new StringBuilder().append(number).append(". ").append(tool)
                .append(" — ").append(description().replace('\n', ' ')).append("\n   args: {");
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            if (i > 0) sb.append(", ");
            sb.append(quote(p.name)).append(':').append(example(p));
        }
        return sb.append("}\n").toString();
    }

    @NonNull
    private static String example(@NonNull Param p) {
        switch (p.type) {
            case INT: case TIME_MS: return "1000";
            case NUMBER: return p.min != null ? num(p.min) : "1.0";
            case BOOL: return "true";
            case ENUM: return quote(joinBar(p.values));
            case COLOR: return "\"#FFD700\"";
            case STRING_LIST: case ID_LIST: return "[\"...\"]";
            case OBJECT: return "{...}";
            default: return "\"...\"";
        }
    }

    @NonNull
    private static String joinBar(@NonNull List<String> vs) {
        StringBuilder sb = new StringBuilder();   // String.join is API 26; minSdk is 24
        for (String v : vs) sb.append(sb.length() > 0 ? "|" : "").append(v);
        return sb.toString();
    }

    // ── checking what the model sent ────────────────────────────────────────

    /**
     * Problems with {@code args}, in words the model can act on; empty when they're fine. Numbers
     * sent as strings ("2000") and booleans as "true" are accepted — models do that.
     */
    @NonNull
    public List<String> check(@NonNull Map<String, Object> args) {
        List<String> out = new ArrayList<>();
        for (Param p : params) {
            Object v = args.get(p.name);
            if (v == null || (v instanceof String && ((String) v).trim().isEmpty())) {
                if (p.required) out.add(p.name + " is required: " + p.help);
                continue;
            }
            String bad = badValue(p, v);
            if (bad != null) out.add(bad);
        }
        if (!openArgs) {
            for (String k : args.keySet()) {
                if (param(k) == null) out.add("unknown argument \"" + k + "\" (this tool takes: " + names() + ")");
            }
        }
        return out;
    }

    @Nullable
    private static String badValue(@NonNull Param p, @NonNull Object v) {
        switch (p.type) {
            case INT: case TIME_MS: {
                Double d = asNumber(v);
                if (d == null || d != Math.rint(d)) return p.name + " must be a whole number, not " + show(v);
                return range(p, d);
            }
            case NUMBER: {
                Double d = asNumber(v);
                if (d == null) return p.name + " must be a number, not " + show(v);
                return range(p, d);
            }
            case BOOL:
                if (v instanceof Boolean || "true".equalsIgnoreCase(String.valueOf(v).trim())
                        || "false".equalsIgnoreCase(String.valueOf(v).trim())) return null;
                return p.name + " must be true or false";
            case ENUM: {
                String s = String.valueOf(v);
                for (String ok : p.values) if (ok.equalsIgnoreCase(s)) return null;
                return p.name + " must be one of " + p.values + ", not " + show(v);
            }
            case COLOR:
                return String.valueOf(v).trim().matches("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})") ? null
                        : p.name + " must be a colour like #FFD700, not " + show(v);
            case STRING_LIST: case ID_LIST:
                // A lone string is a one-item list (Args.list reads it that way) — models do that.
                return v instanceof List || v instanceof String ? null : p.name + " must be a list";
            default:
                return null;
        }
    }

    @Nullable
    private static String range(@NonNull Param p, double d) {
        if (p.min != null && d < p.min) return p.name + " must be at least " + num(p.min);
        if (p.max != null && d > p.max) return p.name + " must be at most " + num(p.max);
        return null;
    }

    @Nullable
    public static Double asNumber(@Nullable Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble(((String) v).trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    @NonNull
    private String names() {
        StringBuilder sb = new StringBuilder();
        for (Param p : params) sb.append(sb.length() > 0 ? ", " : "").append(p.name);
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    @NonNull
    private static String show(@NonNull Object v) {
        String s = String.valueOf(v);
        return "\"" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "\"";
    }

    @NonNull
    private static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.US, "%s", d);
    }

    @NonNull
    static String quote(@NonNull String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** A map of the spec's fields, for listings (help pages, the registry dump). */
    @NonNull
    public Map<String, String> summary() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tool", tool); m.put("title", title); m.put("wing", wing); m.put("family", family);
        m.put("effect", effect.name()); m.put("where", where);
        return m;
    }
}
