package com.fadcam.vislab.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One of the person's own parts: a layer saved out of a look, to drop into any other look (owner,
 * 10-18: "a library people can browse and shop and tweak and bend"). A plain file beside the
 * looks, {@code Supplies/Looks/<Name>.vispart.json}:
 * {@code {"format": "joy.vis.part/1", "name": "Fire Aurora", "from": "Northern Lights", "layer": {...}}}.
 * The layer is exactly a look's layer (its colours included), so it looks the same anywhere.
 */
public final class VisPart {

    public static final String FORMAT = "joy.vis.part/1";
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @NonNull public final String name;
    /** The look it was saved from ("" when unknown). */
    @NonNull public final String from;
    @NonNull public final JsonObject layer;

    public VisPart(@NonNull String name, @NonNull String from, @NonNull JsonObject layer) {
        this.name = name;
        this.from = from;
        this.layer = layer;
    }

    /** A part file's text, or null when it is not a part. */
    @Nullable
    public static VisPart parse(@NonNull String text, @NonNull String fallbackName) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            JsonElement l = o.get("layer");
            if (l == null || !l.isJsonObject() || !l.getAsJsonObject().has("type")) return null;
            return new VisPart(str(o, "name", fallbackName), str(o, "from", ""), l.getAsJsonObject());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @NonNull
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("name", name);
        o.addProperty("from", from);
        o.add("layer", layer);
        return PRETTY.toJson(o) + "\n";
    }

    @NonNull
    private static String str(@NonNull JsonObject o, @NonNull String k, @NonNull String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && !e.getAsString().trim().isEmpty() ? e.getAsString() : def;
    }
}
