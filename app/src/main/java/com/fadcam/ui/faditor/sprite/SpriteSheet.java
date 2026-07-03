package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A sprite-sheet DEFINITION (PLAN_SPRITE_ANIMATION §Data model): one source image
 * sliced into a named grid of cells, plus reusable animation presets. Stored at
 * project level ({@code FaditorProject.spriteSheets[]}) and round-trippable as a
 * standalone sidecar {@code <name>.sprite.json} next to the PNG — the sidecar is
 * the sharing format, the plugin contract, and what lets the AI "find the sheet
 * and the JSON in the project."
 *
 * <p>Cell numbers are static (index = position in reading order); names/tags are
 * user aliases. The sheet never stores decoded/cache paths — {@link #sheetUri} is
 * the ORIGINAL source only (storage-relative conversion happens in ProjectStorage,
 * exactly like {@code TextOverlayItem.imageUri}).</p>
 */
public class SpriteSheet {

    /** Sidecar/project-JSON schema for this object family (independent of the app's project schema). */
    public static final int SPRITE_SCHEMA_VERSION = 1;

    @NonNull private final String id;
    @NonNull private String name;
    /** Original source image URI (project://-relative in storage; never a cache path). */
    @NonNull private String sheetUri;

    // ── Grid geometry (pixels are source-image pixels) ──────────────────
    private int cols = 3;
    private int rows = 3;
    private int marginX = 0;
    private int marginY = 0;
    private int spacingX = 0;
    private int spacingY = 0;
    /** Reading order; only "row-major" is implemented (constant for forward compat). */
    @NonNull private String order = "row-major";

    /** Default playback cadence for presets that don't override it. */
    private float fps = 8f;

    /** Optional background color-to-alpha key (applied ONCE at decode). 0 = none. */
    private int bgKeyColor = 0;
    private float keyTolerance = 0f;

    /** Pivot within a cell, normalized 0..1 (default center). */
    private float pivotX = 0.5f;
    private float pivotY = 0.5f;

    @NonNull private final List<Cell> cells = new ArrayList<>();
    @NonNull private final List<Preset> presets = new ArrayList<>();

    public SpriteSheet(@NonNull String id, @NonNull String name, @NonNull String sheetUri) {
        this.id = id;
        this.name = name;
        this.sheetUri = sheetUri;
    }

    public static SpriteSheet create(@NonNull String name, @NonNull String sheetUri) {
        return new SpriteSheet(UUID.randomUUID().toString(), name, sheetUri);
    }

    // ── Nested value types ───────────────────────────────────────────────

    /** One grid cell: a stable index plus user-editable alias metadata. */
    public static class Cell {
        public final int index;
        @NonNull public String name;
        @NonNull public final List<String> tags = new ArrayList<>();
        public boolean enabled = true;

        public Cell(int index, @NonNull String name) {
            this.index = index;
            this.name = name;
        }
    }

    /** A reusable frame sequence (fast-follow A authors these; the model ships now). */
    public static class Preset {
        @NonNull public final String id;
        @NonNull public String name;
        /** "loop" | "pingpong" | "once" */
        @NonNull public String type = "loop";
        /** 0 = inherit the sheet's default fps. */
        public float fps = 0f;
        @NonNull public final List<Integer> frames = new ArrayList<>();

        public Preset(@NonNull String id, @NonNull String name) {
            this.id = id;
            this.name = name;
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────

    @NonNull public String getId() { return id; }
    @NonNull public String getName() { return name; }
    public void setName(@NonNull String name) { this.name = name; }
    @NonNull public String getSheetUri() { return sheetUri; }
    public void setSheetUri(@NonNull String sheetUri) { this.sheetUri = sheetUri; }
    public int getCols() { return cols; }
    public int getRows() { return rows; }
    public void setGrid(int cols, int rows) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
    }
    public int getMarginX() { return marginX; }
    public int getMarginY() { return marginY; }
    public int getSpacingX() { return spacingX; }
    public int getSpacingY() { return spacingY; }
    public void setMargins(int marginX, int marginY) {
        this.marginX = Math.max(0, marginX);
        this.marginY = Math.max(0, marginY);
    }
    public void setSpacing(int spacingX, int spacingY) {
        this.spacingX = Math.max(0, spacingX);
        this.spacingY = Math.max(0, spacingY);
    }
    @NonNull public String getOrder() { return order; }
    public float getFps() { return fps; }
    public void setFps(float fps) { this.fps = Math.max(0.1f, fps); }
    public int getBgKeyColor() { return bgKeyColor; }
    public float getKeyTolerance() { return keyTolerance; }
    public void setBgKey(int color, float tolerance) {
        this.bgKeyColor = color;
        this.keyTolerance = Math.max(0f, tolerance);
    }
    public float getPivotX() { return pivotX; }
    public float getPivotY() { return pivotY; }
    public void setPivot(float x, float y) {
        this.pivotX = clamp01(x);
        this.pivotY = clamp01(y);
    }
    @NonNull public List<Cell> getCells() { return cells; }
    @NonNull public List<Preset> getPresets() { return presets; }

    public int cellCount() { return cols * rows; }

    @Nullable
    public Cell cellAt(int index) {
        for (Cell c : cells) if (c.index == index) return c;
        return null;
    }

    @Nullable
    public Preset presetById(@Nullable String presetId) {
        if (presetId == null) return null;
        for (Preset p : presets) if (p.id.equals(presetId)) return p;
        return null;
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    // ── JSON (sidecar format == the project-embedded format) ────────────
    // Sparse-write / tolerant-read, matching ProjectStorage house style. The
    // sheetUri handed in/out here is whatever the caller uses — ProjectStorage
    // converts to/from project://-relative around these calls.

    @NonNull
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("spriteSchemaVersion", SPRITE_SCHEMA_VERSION);
        j.addProperty("id", id);
        j.addProperty("name", name);
        j.addProperty("sheetUri", sheetUri);
        j.addProperty("cols", cols);
        j.addProperty("rows", rows);
        if (marginX != 0) j.addProperty("marginX", marginX);
        if (marginY != 0) j.addProperty("marginY", marginY);
        if (spacingX != 0) j.addProperty("spacingX", spacingX);
        if (spacingY != 0) j.addProperty("spacingY", spacingY);
        if (!"row-major".equals(order)) j.addProperty("order", order);
        j.addProperty("fps", fps);
        if (bgKeyColor != 0) {
            j.addProperty("bgKeyColor", bgKeyColor);
            j.addProperty("keyTolerance", keyTolerance);
        }
        if (pivotX != 0.5f) j.addProperty("pivotX", pivotX);
        if (pivotY != 0.5f) j.addProperty("pivotY", pivotY);
        if (!cells.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Cell c : cells) {
                JsonObject cj = new JsonObject();
                cj.addProperty("index", c.index);
                cj.addProperty("name", c.name);
                if (!c.tags.isEmpty()) {
                    JsonArray tags = new JsonArray();
                    for (String t : c.tags) tags.add(t);
                    cj.add("tags", tags);
                }
                if (!c.enabled) cj.addProperty("enabled", false);
                arr.add(cj);
            }
            j.add("cells", arr);
        }
        if (!presets.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Preset p : presets) {
                JsonObject pj = new JsonObject();
                pj.addProperty("id", p.id);
                pj.addProperty("name", p.name);
                pj.addProperty("type", p.type);
                if (p.fps > 0f) pj.addProperty("fps", p.fps);
                JsonArray frames = new JsonArray();
                for (Integer f : p.frames) frames.add(f);
                pj.add("frames", frames);
                arr.add(pj);
            }
            j.add("presets", arr);
        }
        return j;
    }

    @NonNull
    public static SpriteSheet fromJson(@NonNull JsonObject j) {
        SpriteSheet s = new SpriteSheet(
                j.has("id") ? j.get("id").getAsString() : UUID.randomUUID().toString(),
                j.has("name") ? j.get("name").getAsString() : "Sprite",
                j.has("sheetUri") ? j.get("sheetUri").getAsString() : "");
        if (j.has("cols") && j.has("rows")) s.setGrid(j.get("cols").getAsInt(), j.get("rows").getAsInt());
        s.setMargins(j.has("marginX") ? j.get("marginX").getAsInt() : 0,
                j.has("marginY") ? j.get("marginY").getAsInt() : 0);
        s.setSpacing(j.has("spacingX") ? j.get("spacingX").getAsInt() : 0,
                j.has("spacingY") ? j.get("spacingY").getAsInt() : 0);
        if (j.has("order")) s.order = j.get("order").getAsString();
        if (j.has("fps")) s.setFps(j.get("fps").getAsFloat());
        if (j.has("bgKeyColor")) {
            s.setBgKey(j.get("bgKeyColor").getAsInt(),
                    j.has("keyTolerance") ? j.get("keyTolerance").getAsFloat() : 0f);
        }
        s.setPivot(j.has("pivotX") ? j.get("pivotX").getAsFloat() : 0.5f,
                j.has("pivotY") ? j.get("pivotY").getAsFloat() : 0.5f);
        if (j.has("cells")) {
            JsonArray arr = j.getAsJsonArray("cells");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject cj = arr.get(i).getAsJsonObject();
                Cell c = new Cell(cj.get("index").getAsInt(),
                        cj.has("name") ? cj.get("name").getAsString() : "");
                if (cj.has("tags")) {
                    JsonArray tags = cj.getAsJsonArray("tags");
                    for (int t = 0; t < tags.size(); t++) c.tags.add(tags.get(t).getAsString());
                }
                if (cj.has("enabled")) c.enabled = cj.get("enabled").getAsBoolean();
                s.cells.add(c);
            }
        }
        if (j.has("presets")) {
            JsonArray arr = j.getAsJsonArray("presets");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject pj = arr.get(i).getAsJsonObject();
                Preset p = new Preset(
                        pj.has("id") ? pj.get("id").getAsString() : UUID.randomUUID().toString(),
                        pj.has("name") ? pj.get("name").getAsString() : "Preset");
                if (pj.has("type")) p.type = pj.get("type").getAsString();
                if (pj.has("fps")) p.fps = pj.get("fps").getAsFloat();
                if (pj.has("frames")) {
                    JsonArray frames = pj.getAsJsonArray("frames");
                    for (int f = 0; f < frames.size(); f++) p.frames.add(frames.get(f).getAsInt());
                }
                s.presets.add(p);
            }
        }
        return s;
    }
}
