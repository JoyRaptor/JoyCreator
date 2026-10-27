package com.fadcam.ui.faditor.markers;

import android.util.AtomicFile;

import com.fadcam.FLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned sidecar; never changes project.json or destroys the legacy bookmarks.json. */
public final class MarkerWorkspaceStore {
    private static final String TAG = "MarkerWorkspaceStore";
    private static final String FILE_NAME = "timeline-marks.json";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private MarkerWorkspaceStore() { }

    public static File fileFor(File projectDir) { return new File(projectDir, FILE_NAME); }

    /** Undo-only payload; a read-only/future-format document must never become an empty v1 snapshot. */
    public static String toSnapshotJson(MarkerWorkspace.State state) {
        if (state == null || state.storageReadOnly) return null;
        try {
            if (state.originalJson != null) decode(state.originalJson);
            MarkerWorkspace.State snapshot = state.copy();
            snapshot.normalize(-1L);
            String json = encode(snapshot, state.originalJson);
            return json.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES ? json : null;
        } catch (Exception exception) {
            FLog.w(TAG, "Could not capture timeline marks for undo: " + exception.getMessage());
            return null;
        }
    }

    /**
     * Decode without writing. Canonical read-only state takes precedence over any history entry;
     * save() separately rechecks the live file before a caller applies this restored value.
     */
    public static MarkerWorkspace.State fromSnapshotJson(String json, MarkerWorkspace.State canonical) {
        if (json == null || canonical == null || canonical.storageReadOnly) return null;
        try {
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return null;
            return decode(json);
        } catch (Exception exception) {
            FLog.w(TAG, "Keeping canonical timeline marks for unreadable undo payload: "
                    + exception.getMessage());
            return null;
        }
    }

    /**
     * Migrates legacy times only if the new sidecar (including a recoverable backup) is absent.
     * A malformed/newer file yields a read-only empty state and remains untouched on save.
     */
    public static synchronized MarkerWorkspace.State load(File projectDir, List<Long> legacyBookmarks) {
        File file = fileFor(projectDir);
        if (!existsOrBackup(file)) {
            MarkerWorkspace.State state = new MarkerWorkspace.State();
            Set<Long> seen = new HashSet<>();
            if (legacyBookmarks != null) {
                for (Long timeMs : legacyBookmarks) {
                    if (timeMs != null && timeMs >= 0L && seen.add(timeMs)) {
                        state.points.add(new MarkerWorkspace.Point("Bookmark", timeMs, 0));
                    }
                }
            }
            state.normalize(-1L);
            return state;
        }
        try {
            return decode(read(new AtomicFile(file)));
        } catch (Exception exception) {
            FLog.w(TAG, "Keeping unreadable marker file intact: " + exception.getMessage());
            MarkerWorkspace.State state = new MarkerWorkspace.State();
            state.storageReadOnly = true;
            return state;
        }
    }

    /** AtomicFile retains the preceding complete document if serialization/write/commit fails. */
    public static synchronized boolean save(File projectDir, MarkerWorkspace.State state) {
        if (state == null || state.storageReadOnly) return false;
        File file = fileFor(projectDir);
        FileOutputStream stream = null;
        AtomicFile atomic = new AtomicFile(file);
        try {
            if (!projectDir.isDirectory() && !projectDir.mkdirs()) return false;
            String source = state.originalJson;
            if (existsOrBackup(file)) {
                // Validate the current file too: another/newer build may have written since load.
                source = read(atomic);
                decode(source);
            }
            MarkerWorkspace.State snapshot = state.copy();
            snapshot.normalize(-1L);
            String json = encode(snapshot, source);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) return false;
            stream = atomic.startWrite();
            stream.write(bytes);
            stream.getFD().sync();
            atomic.finishWrite(stream);
            stream = null;
            // AtomicFile reports some rename/close failures only to logcat. Confirm that its
            // authoritative file contains the complete new document before reporting success.
            if (!json.equals(read(atomic))) throw new IOException("Marker commit did not complete");
            state.originalJson = json;
            return true;
        } catch (Exception exception) {
            if (stream != null) atomic.failWrite(stream);
            FLog.w(TAG, "Could not save timeline marks: " + exception.getMessage());
            return false;
        }
    }

    private static boolean existsOrBackup(File file) {
        return file.exists() || new File(file.getPath() + ".bak").exists();
    }

    private static String read(AtomicFile atomic) throws IOException {
        try (FileInputStream stream = atomic.openRead();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("Marker file too large");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // Package-visible codec seams let local JVM tests exercise schema compatibility without Android IO.
    static MarkerWorkspace.State decode(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (readLong(root, "schemaVersion") != SCHEMA_VERSION) {
            throw new JSONException("Unsupported marker schema");
        }
        JSONArray points = root.getJSONArray("points");
        JSONArray regions = root.getJSONArray("regions");
        MarkerWorkspace.State state = new MarkerWorkspace.State();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < points.length(); i++) {
            JSONObject entry = points.getJSONObject(i);
            MarkerWorkspace.Point point = new MarkerWorkspace.Point();
            point.id = readId(entry, ids);
            point.name = entry.getString("name");
            point.timeMs = readLong(entry, "timeMs");
            point.color = readColor(entry);
            state.points.add(point);
        }
        for (int i = 0; i < regions.length(); i++) {
            JSONObject entry = regions.getJSONObject(i);
            MarkerWorkspace.Region region = new MarkerWorkspace.Region();
            region.id = readId(entry, ids);
            region.name = entry.getString("name");
            region.inMs = readLong(entry, "inMs");
            region.outMs = readLong(entry, "outMs");
            region.color = readColor(entry);
            region.exportEnabled = entry.getBoolean("exportEnabled");
            state.regions.add(region);
        }
        state.activeRegionId = root.isNull("activeRegionId") ? null : root.getString("activeRegionId");
        state.loopEnabled = root.getBoolean("loopEnabled");
        state.originalJson = json;
        state.normalize(-1L);
        return state;
    }

    /** Preserve unknown v1 fields at root and per-ID so additive metadata survives older builds. */
    static String encode(MarkerWorkspace.State state, String source) throws JSONException {
        JSONObject root = source == null ? new JSONObject() : new JSONObject(source);
        Map<String, JSONObject> oldPoints = entriesById(root.optJSONArray("points"));
        Map<String, JSONObject> oldRegions = entriesById(root.optJSONArray("regions"));
        // Undo can resurrect a deleted ID. Its additive metadata lives in the snapshot even
        // though the current sidecar no longer contains that row. Preserve it on restoration.
        if (state.originalJson != null) {
            JSONObject historical = new JSONObject(state.originalJson);
            Map<String, JSONObject> historicalPoints = entriesById(historical.optJSONArray("points"));
            Map<String, JSONObject> historicalRegions = entriesById(historical.optJSONArray("regions"));
            for (Map.Entry<String, JSONObject> entry : historicalPoints.entrySet()) {
                if (!oldPoints.containsKey(entry.getKey())) oldPoints.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, JSONObject> entry : historicalRegions.entrySet()) {
                if (!oldRegions.containsKey(entry.getKey())) oldRegions.put(entry.getKey(), entry.getValue());
            }
            java.util.Iterator<String> keys = historical.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!root.has(key)) root.put(key, historical.get(key));
            }
        }
        JSONArray points = new JSONArray();
        for (MarkerWorkspace.Point point : state.sortedPoints()) {
            JSONObject entry = oldPoints.get(point.id);
            if (entry == null) entry = new JSONObject();
            entry.put("id", point.id);
            entry.put("name", point.name);
            entry.put("timeMs", point.timeMs);
            entry.put("color", point.color);
            points.put(entry);
        }
        JSONArray regions = new JSONArray();
        for (MarkerWorkspace.Region region : state.sortedRegions()) {
            JSONObject entry = oldRegions.get(region.id);
            if (entry == null) entry = new JSONObject();
            entry.put("id", region.id);
            entry.put("name", region.name);
            entry.put("inMs", region.inMs);
            entry.put("outMs", region.outMs);
            entry.put("color", region.color);
            entry.put("exportEnabled", region.exportEnabled);
            regions.put(entry);
        }
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("points", points);
        root.put("regions", regions);
        root.put("activeRegionId", state.activeRegionId == null ? JSONObject.NULL : state.activeRegionId);
        root.put("loopEnabled", state.loopEnabled);
        return root.toString();
    }

    private static Map<String, JSONObject> entriesById(JSONArray array) throws JSONException {
        Map<String, JSONObject> result = new HashMap<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                result.put(entry.getString("id"), entry);
            }
        }
        return result;
    }

    private static String readId(JSONObject entry, Set<String> ids) throws JSONException {
        String id = entry.getString("id");
        if (id.trim().isEmpty() || !ids.add(id)) throw new JSONException("Missing/duplicate marker ID");
        return id;
    }

    private static int readColor(JSONObject entry) throws JSONException {
        long color = readLong(entry, "color");
        if (color < 0L || color > 3L) throw new JSONException("Unknown marker palette index");
        return (int) color;
    }

    private static long readLong(JSONObject entry, String key) throws JSONException {
        Object value = entry.get(key);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long)) throw new JSONException("Expected integer: " + key);
        return ((Number) value).longValue();
    }
}
