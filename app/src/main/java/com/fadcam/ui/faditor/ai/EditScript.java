package com.fadcam.ui.faditor.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * A validated list of edit operations that can be applied to a Faditor project.
 *
 * <p>This is the bridge between an AI agent (or CLI, or script) and the project
 * model. Instead of letting an AI directly mutate project state, the AI emits
 * an EditScript as JSON. FadCam validates each operation, applies it to the
 * live {@link com.fadcam.ui.faditor.model.FaditorProject}, and saves.</p>
 *
 * <p>Design principles:
 * <ul>
 *   <li>Every operation is reversible (the undo system captures a snapshot before applying).</li>
 *   <li>Every operation is validated before any are applied (atomic-ish batch).</li>
 *   <li>The schema is documented and versioned so external tools can target it.</li>
 *   <li>Unknown operation types are rejected, not silently ignored.</li>
 * </ul></p>
 */
public class EditScript {

    /** Schema version of the edit-script format itself. */
    public static final int SCRIPT_VERSION = 1;

    private final int version;
    private final String description;
    private final List<EditOp> operations;

    public EditScript(int version, @Nullable String description,
                      @NonNull List<EditOp> operations) {
        this.version = version;
        this.description = description != null ? description : "";
        this.operations = operations;
    }

    public int getVersion() { return version; }
    @NonNull public String getDescription() { return description; }
    @NonNull public List<EditOp> getOperations() { return operations; }

    /** Parse an edit-script JSON string into an EditScript, or throw on invalid JSON. */
    @NonNull
    public static EditScript fromJson(@NonNull String json) throws EditScriptException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int ver = root.has("version") ? root.get("version").getAsInt() : 1;
            String desc = root.has("description") ? root.get("description").getAsString() : "";
            JsonArray opsArr = root.has("operations")
                    ? root.getAsJsonArray("operations") : new JsonArray();

            List<EditOp> ops = new ArrayList<>();
            for (JsonElement el : opsArr) {
                JsonObject opObj = el.getAsJsonObject();
                String type = opObj.has("type") ? opObj.get("type").getAsString() : "";
                ops.add(EditOp.fromJsonObject(type, opObj));
            }
            return new EditScript(ver, desc, ops);
        } catch (EditScriptException e) {
            throw e;
        } catch (Exception e) {
            throw new EditScriptException("Failed to parse edit script JSON: " + e.getMessage(), e);
        }
    }

    /** Serialize this script back to a JSON string. */
    @NonNull
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        if (!description.isEmpty()) root.addProperty("description", description);
        JsonArray arr = new JsonArray();
        for (EditOp op : operations) arr.add(op.toJsonObject());
        root.add("operations", arr);
        return root.toString();
    }

    // ── Operation types ─────────────────────────────────────────────

    public enum OpType {
        REMOVE_SPAN,
        ADD_TEXT_OVERLAY,
        REMOVE_TEXT_OVERLAY,
        SET_OVERLAY_RANGE,
        SET_OVERLAY_POSITION,
        SET_OVERLAY_TEXT,
        SET_CLIP_SPEED,
        SET_CLIP_VOLUME,
        SET_CLIP_MUTED,
        SET_CAPTIONS_ENABLED,
        SET_CAPTION_STYLE,
        SET_CANVAS_PRESET,
        SET_EXPORT_SETTING,
        MOVE_KEYFRAME,
        ADD_KEYFRAME,
        ADD_OPACITY_KEYFRAME,
        CLEAR_KEYFRAMES,
        ADD_GENERATED_SLIDE,
        REGENERATE_SLIDE,
        SPLIT_CLIP_AT_TIME,
        REORDER_CLIPS,
        INSERT_BROLL_CUTAWAY,
        ADD_VISUALIZER,
    }

    /** A single validated edit operation. */
    public static class EditOp {
        public final OpType type;
        public final JsonObject params;

        public EditOp(@NonNull OpType type, @NonNull JsonObject params) {
            this.type = type;
            this.params = params;
        }

        @NonNull
        public JsonObject toJsonObject() {
            JsonObject o = new JsonObject();
            o.addProperty("type", type.name());
            for (java.util.Map.Entry<String, JsonElement> e : params.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
            return o;
        }

        @NonNull
        public static EditOp fromJsonObject(@NonNull String typeName,
                                            @NonNull JsonObject obj)
                throws EditScriptException {
            OpType type;
            try {
                type = OpType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                throw new EditScriptException("Unknown edit operation type: " + typeName);
            }
            JsonObject params = new JsonObject();
            for (java.util.Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (!"type".equals(e.getKey())) params.add(e.getKey(), e.getValue());
            }
            return new EditOp(type, params);
        }
    }

    /** Exception thrown when an edit script is malformed or fails validation. */
    public static class EditScriptException extends Exception {
        public EditScriptException(@NonNull String message) { super(message); }
        public EditScriptException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }
}
