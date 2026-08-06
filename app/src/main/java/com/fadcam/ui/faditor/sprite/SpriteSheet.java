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

    /** {@link #getKind()} — cells are sub-rects of ONE image, addressed by grid arithmetic. */
    public static final String KIND_GRID = "grid";
    /**
     * {@link #getKind()} — cells are N SEPARATE FILES, addressed by URI
     * (SPEC_IMAGE_SEQUENCE §0.3: <i>"a sheet whose cells are N FILES, not sub-rects of one
     * bitmap… this is the real structural difference and everything else follows from it"</i>).
     *
     * <p>Everything that is not addressing — the frame track, the resolver, presets, end
     * behaviour, the preview view, the export overlay, the timeline tape, the palette, the
     * gesture contract — is shared with grid sheets unchanged. That reuse is the entire reason
     * a sequence is modelled as a KIND of sheet instead of a new object family.</p>
     */
    public static final String KIND_SEQUENCE = "sequence";

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

    // ── Sequence backing (SPEC_IMAGE_SEQUENCE) ───────────────────────────
    // Additive and inert for every grid sheet that exists: kind defaults to "grid" and
    // frameUris stays empty, so nothing is written and nothing is read differently.

    @NonNull private String kind = KIND_GRID;

    /**
     * For {@link #KIND_SEQUENCE}: the ordered frame files, one per cell, project://-relative in
     * storage exactly like {@link #sheetUri}. Cell index == position in this list.
     *
     * <p><b>All N are real project media.</b> §8 requires every one to be registered with
     * ProjectIntegrity and ProjectConsolidator — a sequence breaks the instant one file is
     * renamed, and "Consolidate project" that copies only {@code sheetUri} would leave the other
     * 239 frames pointing at a folder the project no longer owns.</p>
     */
    @NonNull private final List<String> frameUris = new ArrayList<>();

    /**
     * What dragging this object's edge on the timeline MEANS (§2a). Stored on the SHEET rather
     * than the placed item because it is a property of the material — a rendered animation is a
     * film strip, a photo set is a slideshow — and because a sheet placed twice should not
     * retime differently in each spot for reasons the user cannot see.
     */
    @NonNull private SequenceTiming.ResizeMode resizeMode = SequenceTiming.ResizeMode.RELATIVE;

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

        /**
         * Per-frame WEIGHTS (SPEC_IMAGE_SEQUENCE §2), parallel to {@link #frames}.
         *
         * <p>EMPTY means "every frame weighs 1", which is precisely the behaviour presets had
         * before weights existed — so an unweighted preset resolves down the identical code path
         * and serialises byte-identically. See {@link SequenceTiming} for the arithmetic and for
         * why weights ride the preset instead of becoming a parallel model.</p>
         */
        @NonNull public final List<Integer> weights = new ArrayList<>();

        public Preset(@NonNull String id, @NonNull String name) {
            this.id = id;
            this.name = name;
        }

        /** True when any frame is held longer than one tick. */
        public boolean hasWeights() {
            for (Integer w : weights) {
                if (w != null && w != SequenceTiming.DEFAULT_WEIGHT) return true;
            }
            return false;
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

    // ── Sequence accessors ───────────────────────────────────────────────

    @NonNull public String getKind() { return kind; }
    public boolean isSequence() { return KIND_SEQUENCE.equals(kind); }
    @NonNull public List<String> getFrameUris() { return frameUris; }

    @NonNull public SequenceTiming.ResizeMode getResizeMode() { return resizeMode; }
    public void setResizeMode(@NonNull SequenceTiming.ResizeMode m) { this.resizeMode = m; }

    /** Turn this into a file-backed sequence over {@code uris} (order = cell order). */
    public void setSequenceFrames(@NonNull List<String> uris) {
        this.kind = KIND_SEQUENCE;
        this.frameUris.clear();
        this.frameUris.addAll(uris);
    }

    /**
     * The frame URI for {@code index}, or null when this is a grid sheet or the index is out of
     * range. Null is the MISSING affordance's input, never a crash — same S7 rule the grid path
     * follows for an unopenable sheetUri.
     */
    @Nullable
    public String frameUriAt(int index) {
        if (!isSequence() || index < 0 || index >= frameUris.size()) return null;
        return frameUris.get(index);
    }

    /**
     * The preset that IS this sequence — a sequence's timing lives in exactly one preset holding
     * every frame in order, which is what lets §7's AI treat the whole thing as one integer
     * array. Null for a grid sheet or a sequence whose preset has been deleted.
     */
    @Nullable
    public Preset sequencePreset() {
        if (!isSequence()) return null;
        for (Preset p : presets) if (SEQUENCE_PRESET_ID.equals(p.id)) return p;
        return presets.isEmpty() ? null : presets.get(0);
    }

    /**
     * Fixed id for the sequence preset. Fixed rather than a UUID so §7's tools, the dope sheet
     * and a re-import can all find it without threading an id through, and so a hand-written
     * sidecar can address it.
     */
    public static final String SEQUENCE_PRESET_ID = "sequence";

    /** Build (or rebuild) the sequence preset over the current frame list, keeping weights. */
    @NonNull
    public Preset ensureSequencePreset() {
        Preset p = sequencePreset();
        if (p == null) {
            p = new Preset(SEQUENCE_PRESET_ID, "Sequence");
            p.type = "once";
            presets.add(p);
        }
        List<Integer> kept = SequenceTiming.fit(p.weights, frameUris.size());
        p.frames.clear();
        for (int i = 0; i < frameUris.size(); i++) p.frames.add(i);
        p.weights.clear();
        p.weights.addAll(kept);
        return p;
    }

    /**
     * A full copy under a NEW id — the copy-on-write half of "editing one placement must not
     * retime the others" (user decision, 2026-08-06).
     *
     * <p>fps, resize mode, presets and weights are all sheet-scoped, so two placements of one
     * sheet share them. That is right for STORAGE — a 240-frame sequence should not duplicate
     * its frame list because it was dropped twice — and wrong for EDITING, where dragging one
     * object's edge would silently retime every other copy with no cue on their rows. Cloning at
     * the moment of a sheet-scoped edit keeps both: sharing until it would surprise you.</p>
     *
     * <p>Frame URIs are copied by reference (they are strings pointing at the same files), so
     * this is cheap; nothing on disk is duplicated.</p>
     */
    @NonNull
    public SpriteSheet copyAsNew(@NonNull String newName) {
        SpriteSheet c = new SpriteSheet(UUID.randomUUID().toString(), newName, sheetUri);
        c.kind = kind;
        c.frameUris.addAll(frameUris);
        c.resizeMode = resizeMode;
        c.cols = cols; c.rows = rows;
        c.marginX = marginX; c.marginY = marginY;
        c.spacingX = spacingX; c.spacingY = spacingY;
        c.order = order;
        c.fps = fps;
        c.bgKeyColor = bgKeyColor; c.keyTolerance = keyTolerance;
        c.pivotX = pivotX; c.pivotY = pivotY;
        for (Cell cell : cells) {
            Cell nc = new Cell(cell.index, cell.name);
            nc.tags.addAll(cell.tags);
            nc.enabled = cell.enabled;
            c.cells.add(nc);
        }
        for (Preset p : presets) {
            // Preset IDS are preserved, not regenerated: a placed item's frame-track key
            // references the preset by id, so a fresh id would leave the copy showing nothing.
            Preset np = new Preset(p.id, p.name);
            np.type = p.type;
            np.fps = p.fps;
            np.frames.addAll(p.frames);
            np.weights.addAll(p.weights);
            c.presets.add(np);
        }
        return c;
    }

    public int cellCount() { return isSequence() ? frameUris.size() : cols * rows; }

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
        // Omitted for grid sheets so every project written before sequences existed round-trips
        // byte-identically — the additive-schema rule the rest of this class already follows.
        if (isSequence()) {
            j.addProperty("kind", kind);
            JsonArray fu = new JsonArray();
            for (String u : frameUris) fu.add(u);
            j.add("frameUris", fu);
            if (resizeMode != SequenceTiming.ResizeMode.RELATIVE) {
                j.addProperty("resizeMode", resizeMode.name());
            }
        }
        j.addProperty("cols", cols);
        j.addProperty("rows", rows);
        if (marginX != 0) j.addProperty("marginX", marginX);
        if (marginY != 0) j.addProperty("marginY", marginY);
        if (spacingX != 0) j.addProperty("spacingX", spacingX);
        if (spacingY != 0) j.addProperty("spacingY", spacingY);
        if (!"row-major".equals(order)) j.addProperty("order", order);
        j.addProperty("fps", fps);
        if (bgKeyColor != 0) j.addProperty("bgKeyColor", bgKeyColor);
        // Written independently of bgKeyColor (review gate 2026-07-03): a UI that
        // disables the key while keeping the tolerance slider's value must not
        // silently lose it across a save/reload.
        if (keyTolerance != 0f) j.addProperty("keyTolerance", keyTolerance);
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
                // Written only when something is actually held: an all-1s array carries no
                // information, and omitting it keeps a pre-weights preset byte-identical.
                if (p.hasWeights()) {
                    JsonArray ws = new JsonArray();
                    for (int i = 0; i < p.frames.size(); i++) {
                        ws.add(SequenceTiming.weightAt(p.weights, i));
                    }
                    pj.add("weights", ws);
                }
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
        if (KIND_SEQUENCE.equals(j.has("kind") ? j.get("kind").getAsString() : KIND_GRID)) {
            s.kind = KIND_SEQUENCE;
            if (j.has("frameUris")) {
                JsonArray fu = j.getAsJsonArray("frameUris");
                for (int i = 0; i < fu.size(); i++) s.frameUris.add(fu.get(i).getAsString());
            }
            s.resizeMode = SequenceTiming.ResizeMode.fromName(
                    j.has("resizeMode") ? j.get("resizeMode").getAsString() : null);
        }
        if (j.has("cols") && j.has("rows")) s.setGrid(j.get("cols").getAsInt(), j.get("rows").getAsInt());
        s.setMargins(j.has("marginX") ? j.get("marginX").getAsInt() : 0,
                j.has("marginY") ? j.get("marginY").getAsInt() : 0);
        s.setSpacing(j.has("spacingX") ? j.get("spacingX").getAsInt() : 0,
                j.has("spacingY") ? j.get("spacingY").getAsInt() : 0);
        if (j.has("order")) s.order = j.get("order").getAsString();
        if (j.has("fps")) s.setFps(j.get("fps").getAsFloat());
        s.setBgKey(j.has("bgKeyColor") ? j.get("bgKeyColor").getAsInt() : 0,
                j.has("keyTolerance") ? j.get("keyTolerance").getAsFloat() : 0f);
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
                if (pj.has("weights")) {
                    JsonArray ws = pj.getAsJsonArray("weights");
                    for (int w = 0; w < ws.size(); w++) {
                        p.weights.add(SequenceTiming.clampWeight(ws.get(w).getAsInt()));
                    }
                    // Absent/short arrays are legal input (hand-edited JSON, an LLM that emitted
                    // fewer numbers than frames); normalise once here so no consumer downstream
                    // has to think about a ragged array.
                    List<Integer> fitted = SequenceTiming.fit(p.weights, p.frames.size());
                    p.weights.clear();
                    p.weights.addAll(fitted);
                }
                s.presets.add(p);
            }
        }
        return s;
    }
}
