package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Avatar Studio A1 (PLAN_AVATAR_STUDIO): the puppet RIG model — parts referencing
 * sprite sheets, parented into a hierarchy with per-part anchors, plus POSE DOMAINS
 * (the Moho/CTA-style extreme matrices: a 2-D yaw×pitch grid for the head, 1-D
 * angle strips for limbs) whose cells snapshot per-part pose deltas, sprite-cell
 * choices, z-order, flips, and warp PINS (the user's Adobe-Ch pin-warp design).
 *
 * <p>LLM-legible, versioned, sidecar-exportable JSON — the AI-rigging contract.
 * Same sparse-write / tolerant-read discipline as {@code SpriteSheet}. Evaluation
 * lives EXCLUSIVELY in {@link PuppetPoseResolver} (single-authority rule).</p>
 */
public class AvatarRig {

    public static final int RIG_SCHEMA_VERSION = 1;

    @NonNull private final String id;
    @NonNull private String name;

    @NonNull private final List<Part> parts = new ArrayList<>();
    @NonNull private final List<PoseDomain> domains = new ArrayList<>();
    /** visemeClass ("A","E","I","O","U","M","rest"...) → mouth-part sprite cellIndex. */
    @NonNull private final java.util.Map<String, Integer> visemeMap = new java.util.LinkedHashMap<>();

    public AvatarRig(@NonNull String id, @NonNull String name) {
        this.id = id;
        this.name = name;
    }

    public static AvatarRig create(@NonNull String name) {
        return new AvatarRig(UUID.randomUUID().toString(), name);
    }

    // ── Nested types ──────────────────────────────────────────────────────

    /** One rig node: art source + hierarchy + anchor. Canonical biped part ids
     *  ("head","body","armL","armR","handL","handR","mouth") are conventions the
     *  AI template uses — free-form ids are equally valid. */
    public static class Part {
        @NonNull public final String id;
        /** Sprite sheet this part draws from (project-level SpriteSheet id). */
        @NonNull public String sheetId;
        /** Parent part id, or null = root. Transform composes parent∘child. */
        @Nullable public String parentId;
        /** Anchor within the part's cell, 0..1; null → the sheet's own pivot. */
        @Nullable public Float anchorX, anchorY;
        /** 0..1: how much of the parent's ROTATION/translation this part inherits. */
        public float followWeight = 1f;
        /** Base z-order (pose cells may override per-cell). */
        public int z = 0;
        /** Optional "dangle" physics tag (hair/ears/tail) — cheap verlet, A6. */
        public boolean dangle = false;
        /**
         * A6 pin-warp: where the pin chain sits ON THE ARTWORK — the REST chain in
         * cell space ({@code [x,y]} 0..1, top→bottom monotonic in y per the
         * {@link PinWarpStrip} authoring convention). Empty = rigid part (no warp).
         * Per-cell {@link PartPose#pins} are the POSED positions the resolver
         * blends; this is the fixed art-space reference they deform against.
         */
        @NonNull public final List<float[]> restPins = new ArrayList<>();

        public Part(@NonNull String id, @NonNull String sheetId) {
            this.id = id;
            this.sheetId = sheetId;
        }
    }

    /**
     * A pose domain: the extreme matrix for one part-group. 2-D (head: yaw×pitch)
     * or 1-D (limb strip: rows==1). Cells are addressed col-major-free — by (col,
     * row) with normalized driver coordinates spanning [-1..1] per axis.
     */
    public static class PoseDomain {
        @NonNull public final String id;         // "head", "armL", "body", "legs", "misc"
        /** Driver parameter names this domain listens to (resolver maps tracking → these). */
        @NonNull public String driverX = "yaw";
        @Nullable public String driverY = null;  // null = 1-D strip
        public int cols = 3;
        public int rows = 1;
        @NonNull public final List<Cell> cells = new ArrayList<>();

        public PoseDomain(@NonNull String id) { this.id = id; }

        @Nullable
        public Cell cellAt(int col, int row) {
            for (Cell c : cells) if (c.col == col && c.row == row) return c;
            return null;
        }
    }

    /** One extreme: per-part pose states pinned at a grid coordinate. */
    public static class Cell {
        public int col, row;
        @NonNull public final List<PartPose> poses = new ArrayList<>();

        @Nullable
        public PartPose poseFor(@NonNull String partId) {
            for (PartPose p : poses) if (p.partId.equals(partId)) return p;
            return null;
        }
    }

    /**
     * A part's state within one cell. CONTINUOUS fields (x/y/scale/rot) blend
     * between cells; DISCRETE fields (cellIndex/z/flip) snap at thresholds with
     * hysteresis + pin-snap crossfade (resolver's job). Pins are ITEM-normalized
     * (0..1 of the part's cell rect) warp control points (shoulder/elbow/wrist —
     * pin COUNT and meaning are per-part conventions; the warp renderer consumes
     * them positionally).
     */
    public static class PartPose {
        @NonNull public final String partId;
        public float x = 0f, y = 0f;        // canvas-normalized offset from base
        public float scale = 1f;
        public float rotationDeg = 0f;
        public int cellIndex = 0;            // sprite cell shown at this extreme
        public int z = 0;
        public boolean flipH = false, flipV = false;
        @NonNull public final List<float[]> pins = new ArrayList<>(); // each {x,y} 0..1

        public PartPose(@NonNull String partId) { this.partId = partId; }
    }

    // ── Accessors ────────────────────────────────────────────────────────

    @NonNull public String getId() { return id; }
    @NonNull public String getName() { return name; }
    public void setName(@NonNull String n) { this.name = n; }
    @NonNull public List<Part> getParts() { return parts; }
    @NonNull public List<PoseDomain> getDomains() { return domains; }
    @NonNull public java.util.Map<String, Integer> getVisemeMap() { return visemeMap; }

    @Nullable
    public Part partById(@Nullable String partId) {
        if (partId == null) return null;
        for (Part p : parts) if (p.id.equals(partId)) return p;
        return null;
    }

    // ── JSON (sidecar format == project-embedded format) ─────────────────

    @NonNull
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("rigSchemaVersion", RIG_SCHEMA_VERSION);
        j.addProperty("id", id);
        j.addProperty("name", name);
        JsonArray pArr = new JsonArray();
        for (Part p : parts) {
            JsonObject pj = new JsonObject();
            pj.addProperty("id", p.id);
            pj.addProperty("sheetId", p.sheetId);
            if (p.parentId != null) pj.addProperty("parentId", p.parentId);
            if (p.anchorX != null) pj.addProperty("anchorX", p.anchorX);
            if (p.anchorY != null) pj.addProperty("anchorY", p.anchorY);
            if (p.followWeight != 1f) pj.addProperty("followWeight", p.followWeight);
            if (p.z != 0) pj.addProperty("z", p.z);
            if (p.dangle) pj.addProperty("dangle", true);
            if (!p.restPins.isEmpty()) {
                // Same [x,y]-pair shape as PartPose pins.
                JsonArray pins = new JsonArray();
                for (float[] pin : p.restPins) {
                    JsonArray one = new JsonArray();
                    one.add(pin[0]);
                    one.add(pin[1]);
                    pins.add(one);
                }
                pj.add("restPins", pins);
            }
            pArr.add(pj);
        }
        j.add("parts", pArr);
        JsonArray dArr = new JsonArray();
        for (PoseDomain d : domains) {
            JsonObject dj = new JsonObject();
            dj.addProperty("id", d.id);
            dj.addProperty("driverX", d.driverX);
            if (d.driverY != null) dj.addProperty("driverY", d.driverY);
            dj.addProperty("cols", d.cols);
            dj.addProperty("rows", d.rows);
            JsonArray cArr = new JsonArray();
            for (Cell c : d.cells) {
                JsonObject cj = new JsonObject();
                cj.addProperty("col", c.col);
                cj.addProperty("row", c.row);
                JsonArray ppArr = new JsonArray();
                for (PartPose pp : c.poses) {
                    JsonObject ppj = new JsonObject();
                    ppj.addProperty("partId", pp.partId);
                    if (pp.x != 0f) ppj.addProperty("x", pp.x);
                    if (pp.y != 0f) ppj.addProperty("y", pp.y);
                    if (pp.scale != 1f) ppj.addProperty("scale", pp.scale);
                    if (pp.rotationDeg != 0f) ppj.addProperty("rot", pp.rotationDeg);
                    if (pp.cellIndex != 0) ppj.addProperty("cell", pp.cellIndex);
                    if (pp.z != 0) ppj.addProperty("z", pp.z);
                    if (pp.flipH) ppj.addProperty("flipH", true);
                    if (pp.flipV) ppj.addProperty("flipV", true);
                    if (!pp.pins.isEmpty()) {
                        JsonArray pins = new JsonArray();
                        for (float[] pin : pp.pins) {
                            JsonArray one = new JsonArray();
                            one.add(pin[0]);
                            one.add(pin[1]);
                            pins.add(one);
                        }
                        ppj.add("pins", pins);
                    }
                    ppArr.add(ppj);
                }
                cj.add("poses", ppArr);
                cArr.add(cj);
            }
            dj.add("cells", cArr);
            dArr.add(dj);
        }
        j.add("domains", dArr);
        if (!visemeMap.isEmpty()) {
            JsonObject vj = new JsonObject();
            for (java.util.Map.Entry<String, Integer> e : visemeMap.entrySet()) {
                vj.addProperty(e.getKey(), e.getValue());
            }
            j.add("visemeMap", vj);
        }
        return j;
    }

    @NonNull
    public static AvatarRig fromJson(@NonNull JsonObject j) {
        AvatarRig rig = new AvatarRig(
                j.has("id") ? j.get("id").getAsString() : UUID.randomUUID().toString(),
                j.has("name") ? j.get("name").getAsString() : "Avatar");
        if (j.has("parts")) {
            JsonArray pArr = j.getAsJsonArray("parts");
            for (int i = 0; i < pArr.size(); i++) {
                JsonObject pj = pArr.get(i).getAsJsonObject();
                // Tolerant read: a malformed element (AI-emitted JSON) drops
                // ITSELF, never the whole rig (2026-07-05 review-gate fix).
                if (!pj.has("id")) continue;
                Part p = new Part(pj.get("id").getAsString(),
                        pj.has("sheetId") ? pj.get("sheetId").getAsString() : "");
                if (pj.has("parentId")) p.parentId = pj.get("parentId").getAsString();
                if (pj.has("anchorX")) p.anchorX = pj.get("anchorX").getAsFloat();
                if (pj.has("anchorY")) p.anchorY = pj.get("anchorY").getAsFloat();
                if (pj.has("followWeight")) p.followWeight = pj.get("followWeight").getAsFloat();
                if (pj.has("z")) p.z = pj.get("z").getAsInt();
                if (pj.has("dangle")) p.dangle = pj.get("dangle").getAsBoolean();
                if (pj.has("restPins") && pj.get("restPins").isJsonArray()) {
                    JsonArray pins = pj.getAsJsonArray("restPins");
                    for (int q = 0; q < pins.size(); q++) {
                        // Tolerant read: each pin must be a [x,y] pair; skip anything else.
                        if (!pins.get(q).isJsonArray()) continue;
                        JsonArray one = pins.get(q).getAsJsonArray();
                        if (one.size() < 2) continue;
                        p.restPins.add(new float[]{
                                one.get(0).getAsFloat(), one.get(1).getAsFloat()});
                    }
                }
                rig.parts.add(p);
            }
        }
        if (j.has("domains")) {
            JsonArray dArr = j.getAsJsonArray("domains");
            for (int i = 0; i < dArr.size(); i++) {
                JsonObject dj = dArr.get(i).getAsJsonObject();
                if (!dj.has("id")) continue; // tolerant read — skip, don't crash
                PoseDomain d = new PoseDomain(dj.get("id").getAsString());
                if (dj.has("driverX")) d.driverX = dj.get("driverX").getAsString();
                if (dj.has("driverY")) d.driverY = dj.get("driverY").getAsString();
                if (dj.has("cols")) d.cols = dj.get("cols").getAsInt();
                if (dj.has("rows")) d.rows = dj.get("rows").getAsInt();
                if (dj.has("cells")) {
                    JsonArray cArr = dj.getAsJsonArray("cells");
                    for (int c = 0; c < cArr.size(); c++) {
                        JsonObject cj = cArr.get(c).getAsJsonObject();
                        Cell cell = new Cell();
                        cell.col = cj.has("col") ? cj.get("col").getAsInt() : 0;
                        cell.row = cj.has("row") ? cj.get("row").getAsInt() : 0;
                        if (cj.has("poses")) {
                            JsonArray ppArr = cj.getAsJsonArray("poses");
                            for (int k = 0; k < ppArr.size(); k++) {
                                JsonObject ppj = ppArr.get(k).getAsJsonObject();
                                if (!ppj.has("partId")) continue; // tolerant read
                                PartPose pp = new PartPose(ppj.get("partId").getAsString());
                                if (ppj.has("x")) pp.x = ppj.get("x").getAsFloat();
                                if (ppj.has("y")) pp.y = ppj.get("y").getAsFloat();
                                if (ppj.has("scale")) pp.scale = ppj.get("scale").getAsFloat();
                                if (ppj.has("rot")) pp.rotationDeg = ppj.get("rot").getAsFloat();
                                if (ppj.has("cell")) pp.cellIndex = ppj.get("cell").getAsInt();
                                if (ppj.has("z")) pp.z = ppj.get("z").getAsInt();
                                if (ppj.has("flipH")) pp.flipH = ppj.get("flipH").getAsBoolean();
                                if (ppj.has("flipV")) pp.flipV = ppj.get("flipV").getAsBoolean();
                                if (ppj.has("pins")) {
                                    JsonArray pins = ppj.getAsJsonArray("pins");
                                    for (int q = 0; q < pins.size(); q++) {
                                        // Pin must be a [x,y] numeric pair; skip anything else.
                                        if (!pins.get(q).isJsonArray()) continue;
                                        JsonArray one = pins.get(q).getAsJsonArray();
                                        if (one.size() < 2) continue;
                                        pp.pins.add(new float[]{
                                                one.get(0).getAsFloat(), one.get(1).getAsFloat()});
                                    }
                                }
                                cell.poses.add(pp);
                            }
                        }
                        d.cells.add(cell);
                    }
                }
                rig.domains.add(d);
            }
        }
        if (j.has("visemeMap")) {
            JsonObject vj = j.getAsJsonObject("visemeMap");
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : vj.entrySet()) {
                rig.visemeMap.put(e.getKey(), e.getValue().getAsInt());
            }
        }
        return rig;
    }
}
