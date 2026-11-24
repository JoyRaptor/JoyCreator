package com.fadcam.ui.faditor.model.path;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * A path on the Studio timeline (STU-5 primitives, step 2: the plumbing). HIDDEN: nothing in the
 * editor makes, draws or exports one yet ({@link #ENABLED} is false and no screen reads it); the
 * screens wait for the owner's picks on {@code tasks/design/UI-Path-options.html}. What is here is
 * the part he will never see: the item, and its place in the project file, so the screens can be
 * built on it without changing the file again.
 *
 * <p>Self-serializing, like {@code AdjustmentLayer}: the class that knows what a field means
 * writes it. A project with no paths writes no {@code pathItems} key, so every existing project
 * saves byte for byte as before.</p>
 */
public final class PathItem {

    /** The hidden switch. Flip only when the add, edit, preview AND export pieces are all in. */
    public static final boolean ENABLED = false;

    @NonNull private String id;
    @NonNull private String layerId = "shapes";
    private long startMs;
    private long endMs;
    @NonNull private StudioPath path;
    /** Line width as a share of the frame's height, so it looks the same at every export size. */
    private float strokeWidth = 0.01f;
    private int strokeColor = 0xFFF4F4F5;
    /** 0 = no fill. Only a closed path is ever filled. */
    private int fillColor = 0;
    @Nullable private String name;

    public PathItem(@NonNull StudioPath path, long startMs, long endMs) {
        this.id = UUID.randomUUID().toString();
        this.path = path;
        this.startMs = Math.max(0L, startMs);
        this.endMs = Math.max(this.startMs, endMs);
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getLayerId() { return layerId; }
    public void setLayerId(@NonNull String l) { layerId = l; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public void setSpan(long start, long end) { startMs = Math.max(0L, start); endMs = Math.max(startMs, end); }
    @NonNull public StudioPath getPath() { return path; }
    public void setPath(@NonNull StudioPath p) { path = p; }
    public float getStrokeWidth() { return strokeWidth; }
    public void setStrokeWidth(float w) { strokeWidth = Math.max(0f, w); }
    public int getStrokeColor() { return strokeColor; }
    public void setStrokeColor(int c) { strokeColor = c; }
    public int getFillColor() { return fillColor; }
    public void setFillColor(int c) { fillColor = c; }
    @Nullable public String getName() { return name; }
    public void setName(@Nullable String n) { name = (n == null || n.trim().isEmpty()) ? null : n.trim(); }

    /** On screen at editor time {@code ms} (half-open, like every other item). */
    public boolean activeAt(long ms) { return ms >= startMs && ms < endMs; }

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("layerId", layerId);
        o.addProperty("startMs", startMs);
        o.addProperty("endMs", endMs);
        JsonArray p = new JsonArray();
        for (float v : path.toArray()) p.add(v);
        o.add("path", p);
        o.addProperty("strokeWidth", strokeWidth);
        o.addProperty("strokeColor", strokeColor);
        if (fillColor != 0) o.addProperty("fillColor", fillColor);
        if (name != null) o.addProperty("name", name);
        return o;
    }

    /** Null when the object is not a path item at all; a missing field takes its default. */
    @Nullable
    public static PathItem fromJson(@Nullable JsonObject o) {
        if (o == null || !o.has("path") || !o.get("path").isJsonArray()) return null;
        JsonArray p = o.getAsJsonArray("path");
        float[] a = new float[p.size()];
        for (int i = 0; i < a.length; i++) a[i] = p.get(i).getAsFloat();
        long start = o.has("startMs") ? o.get("startMs").getAsLong() : 0L;
        long end = o.has("endMs") ? o.get("endMs").getAsLong() : start;
        PathItem it = new PathItem(StudioPath.fromArray(a), start, end);
        if (o.has("id")) it.id = o.get("id").getAsString();
        if (o.has("layerId")) it.layerId = o.get("layerId").getAsString();
        if (o.has("strokeWidth")) it.strokeWidth = o.get("strokeWidth").getAsFloat();
        if (o.has("strokeColor")) it.strokeColor = o.get("strokeColor").getAsInt();
        if (o.has("fillColor")) it.fillColor = o.get("fillColor").getAsInt();
        if (o.has("name")) it.setName(o.get("name").getAsString());
        return it;
    }
}
