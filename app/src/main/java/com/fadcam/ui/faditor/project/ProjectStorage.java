package com.fadcam.ui.faditor.project;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Handles JSON persistence for Faditor projects.
 *
 * <p>Projects are stored in app-internal storage at:
 * {@code files/faditor/projects/{projectId}/project.json}</p>
 *
 * <p>Uses Gson for serialization with custom adapters for {@link Uri},
 * {@link Clip}, and {@link FaditorProject} types.</p>
 */
public class ProjectStorage {

    private static final String TAG = "ProjectStorage";
    private static final String PROJECTS_DIR = "faditor/projects";
    private static final String PROJECT_FILE = "project.json";

    @NonNull
    private final File projectsRoot;

    @NonNull
    private final Gson gson;
    
    @NonNull
    private final Context context;

    // Serial executor for disk writes. The JSON is serialized on the caller's
    // thread (so it reads a consistent project snapshot with no concurrent-
    // modification risk), but the file I/O — the slow, variable-latency part —
    // runs here off the main thread. Serial ordering guarantees the newest save
    // wins and never interleaves with an older one.
    @NonNull
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    @Nullable
    private volatile Future<?> lastWrite;

    /**
     * Create a ProjectStorage instance.
     *
     * @param context application or activity context
     */
    public ProjectStorage(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.projectsRoot = new File(context.getFilesDir(), PROJECTS_DIR);
        if (!projectsRoot.exists()) {
            projectsRoot.mkdirs();
        }
        this.gson = createGson();
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Save a project to disk. Creates or overwrites the project file.
     *
     * @param project the project to save
     * @return true if successful, false otherwise
     */
    public boolean save(@NonNull FaditorProject project) {
        // Downgrade guard (PLAN §4.1(2)): never overwrite a project written by a newer
        // build — doing so would silently drop the data we couldn't parse. Refuse & log.
        if (project.isLoadedFromNewerVersion()) {
            FLog.w(TAG, "save() refused: project '" + project.getName()
                    + "' was made with a newer version and is read-only.");
            return false;
        }
        String json = gson.toJson(project);
        return writeProjectJson(project.getId(), json);
    }

    /**
     * Like {@link #save} but only the JSON serialization runs on the caller's
     * thread; the file write is queued on a background serial executor. Use this
     * on the hot per-edit path so trimming/importing/transitions don't block the
     * UI thread on disk I/O. Forced/critical saves (onPause/onDestroy) should use
     * the synchronous {@link #save} (optionally after {@link #flushPendingWrites}).
     */
    public void saveAsync(@NonNull FaditorProject project) {
        // Downgrade guard (PLAN §4.1(2)) — see save().
        if (project.isLoadedFromNewerVersion()) {
            FLog.w(TAG, "saveAsync() refused: project '" + project.getName()
                    + "' was made with a newer version and is read-only.");
            return;
        }
        final String id = project.getId();
        final String json = gson.toJson(project);   // serialize on caller thread (no CME)
        lastWrite = ioExecutor.submit(() -> writeProjectJson(id, json));
    }

    /** Block (briefly) until queued async writes have flushed — call before the activity dies. */
    public void flushPendingWrites() {
        Future<?> f = lastWrite;
        if (f == null) return;
        try {
            f.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            FLog.w(TAG, "flushPendingWrites: timed out or failed", e);
        }
    }

    /** Atomic write of a pre-serialized project JSON string. Safe to run off the main thread. */
    private boolean writeProjectJson(@NonNull String projectId, @NonNull String json) {
        File projectDir = getProjectDir(projectId);
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            FLog.e(TAG, "Failed to create project directory: " + projectDir);
            return false;
        }

        // Atomic-ish save: write to a temp file, then rotate the previous good
        // copy to .bak and move temp into place. A crash mid-write can no longer
        // corrupt project.json (which previously wiped a user's project).
        File file = new File(projectDir, PROJECT_FILE);
        File tmp = new File(projectDir, PROJECT_FILE + ".tmp");
        File bak = new File(projectDir, PROJECT_FILE + ".bak");

        try (FileWriter writer = new FileWriter(tmp)) {
            writer.write(json);
            writer.flush();
        } catch (IOException e) {
            FLog.e(TAG, "Failed to write temp project: " + projectId, e);
            tmp.delete();
            return false;
        }

        // Keep the previous good copy as a backup, then move temp into place.
        if (file.exists()) {
            bak.delete();
            if (!file.renameTo(bak)) {
                FLog.w(TAG, "Could not rotate previous project to .bak");
            }
        }
        if (!tmp.renameTo(file)) {
            FLog.e(TAG, "Failed to move temp project into place; restoring backup");
            if (bak.exists()) bak.renameTo(file); // restore previous good copy
            tmp.delete();
            return false;
        }
        return true;
    }

    /**
     * Load a project from disk by ID.
     *
     * @param projectId the project ID
     * @return the loaded project, or null if not found or corrupt
     */
    @Nullable
    public FaditorProject load(@NonNull String projectId) {
        File file = new File(getProjectDir(projectId), PROJECT_FILE);
        File bak = new File(getProjectDir(projectId), PROJECT_FILE + ".bak");

        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                FaditorProject p = gson.fromJson(reader, FaditorProject.class);
                if (p != null && p.getTimeline() != null && !p.getTimeline().isEmpty()) {
                    return p;
                }
                FLog.w(TAG, "Main project file empty/invalid, trying backup: " + projectId);
            } catch (Exception e) {
                FLog.e(TAG, "Main project file corrupt, trying backup: " + projectId, e);
            }
        }

        // Fall back to the last good backup if the main file is missing/corrupt.
        if (bak.exists()) {
            try (FileReader reader = new FileReader(bak)) {
                FaditorProject p = gson.fromJson(reader, FaditorProject.class);
                if (p != null) {
                    FLog.i(TAG, "Recovered project from backup: " + projectId);
                    return p;
                }
            } catch (Exception e) {
                FLog.e(TAG, "Backup project also corrupt: " + projectId, e);
            }
        }

        FLog.d(TAG, "No loadable project found: " + projectId);
        return null;
    }

    /**
     * List all saved projects, sorted by last modified (newest first).
     *
     * @return list of project summaries (lightweight — only metadata, no full load)
     */
    @NonNull
    public List<ProjectSummary> listProjects() {
        List<ProjectSummary> result = new ArrayList<>();

        File[] dirs = projectsRoot.listFiles(File::isDirectory);
        if (dirs == null) return result;

        for (File dir : dirs) {
            File file = new File(dir, PROJECT_FILE);
            if (!file.exists()) continue;

            try (FileReader reader = new FileReader(file)) {
                JsonObject json = gson.fromJson(reader, JsonObject.class);
                if (json == null) continue;

                String id = json.has("id") ? json.get("id").getAsString() : dir.getName();
                String name = json.has("name") ? json.get("name").getAsString() : "Untitled";
                long lastModified = json.has("lastModified")
                        ? json.get("lastModified").getAsLong()
                        : file.lastModified();
                long createdAt = json.has("createdAt")
                        ? json.get("createdAt").getAsLong()
                        : lastModified;

                // Get video URI from first clip (for thumbnail in future)
                String videoUri = null;
                if (json.has("timeline")) {
                    JsonObject timeline = json.getAsJsonObject("timeline");
                    if (timeline.has("clips")) {
                        JsonArray clips = timeline.getAsJsonArray("clips");
                        if (clips.size() > 0) {
                            JsonObject firstClip = clips.get(0).getAsJsonObject();
                            if (firstClip.has("sourceUri")) {
                                // Resolve schema-v6 project://<relative> back to an absolute
                                // file:// path so the thumbnail extractor can read it.
                                videoUri = fromStorageUri(dir,
                                        firstClip.get("sourceUri").getAsString()).toString();
                            }
                        }
                    }
                }

                result.add(new ProjectSummary(id, name, createdAt, lastModified, videoUri));
            } catch (Exception e) {
                FLog.w(TAG, "Skipping corrupt project: " + dir.getName(), e);
            }
        }

        // Sort by lastModified descending
        Collections.sort(result, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return result;
    }

    /**
     * Delete a saved project.
     * Also cleans up any cached remuxed files for clips in this project.
     *
     * @param projectId the project ID to delete
     * @return true if deleted, false otherwise
     */
    public boolean delete(@NonNull String projectId) {
        // First, try to load the project to get clip info for cache cleanup
        FaditorProject project = null;
        try {
            project = load(projectId);
        } catch (Exception e) {
            FLog.w(TAG, "Could not load project for cache cleanup: " + projectId, e);
        }
        
        // Clean up cache files for all clips in this project
        if (project != null && project.getTimeline() != null) {
            Timeline timeline = project.getTimeline();
            
            // Clean cache for video clips
            for (int i = 0; i < timeline.getClipCount(); i++) {
                Clip clip = timeline.getClip(i);
                if (clip != null) {
                    android.net.Uri sourceUri = clip.getSourceUri();
                    if (sourceUri != null) {
                        // Extract filename from URI to use as cache prefix
                        String filename = extractFilename(sourceUri.toString());
                        if (filename != null) {
                            String baseName = filename.substring(0, Math.max(filename.lastIndexOf('.'), filename.length()));
                            com.fadcam.playback.FragmentedMp4Remuxer remuxer =
                                    new com.fadcam.playback.FragmentedMp4Remuxer(context);
                            int deleted = remuxer.deleteCacheForPrefix(baseName);
                            if (deleted > 0) {
                                FLog.d(TAG, "Cleared " + deleted + " cache files for clip: " + filename);
                            }
                        }
                    }
                }
            }
            
            // Clean cache for audio clips
            List<AudioClip> audioClips = timeline.getAudioClips();
            if (audioClips != null) {
                for (AudioClip audioClip : audioClips) {
                    if (audioClip != null && audioClip.getSourceUri() != null) {
                        String filename = extractFilename(audioClip.getSourceUri().toString());
                        if (filename != null) {
                            String baseName = filename.substring(0, Math.max(filename.lastIndexOf('.'), filename.length()));
                            com.fadcam.playback.FragmentedMp4Remuxer remuxer =
                                    new com.fadcam.playback.FragmentedMp4Remuxer(context);
                            int deleted = remuxer.deleteCacheForPrefix(baseName);
                            if (deleted > 0) {
                                FLog.d(TAG, "Cleared " + deleted + " cache files for audio: " + filename);
                            }
                        }
                    }
                }
            }
        }
        
        // Now delete the project directory
        File dir = getProjectDir(projectId);
        if (!dir.exists()) return false;

        // Delete all files in the directory
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        boolean deleted = dir.delete();
        if (deleted) {
            FLog.i(TAG, "Project deleted: " + projectId);
        }
        return deleted;
    }
    
    /**
     * Extract filename from a URI (file path or content URI).
     *
     * @param uri the URI string
     * @return the filename, or null if extraction failed
     */
    @Nullable
    private String extractFilename(@NonNull String uri) {
        try {
            if (uri.startsWith("file://")) {
                uri = uri.substring(7);  // Remove "file://" prefix
            } else if (uri.startsWith("content://")) {
                // For content URIs, try to extract the last path segment
                int lastSlash = uri.lastIndexOf('/');
                if (lastSlash >= 0) {
                    return uri.substring(lastSlash + 1);
                }
                return null;
            }
            
            int lastSlash = uri.lastIndexOf('/');
            if (lastSlash >= 0) {
                return uri.substring(lastSlash + 1);
            }
            return uri;
        } catch (Exception e) {
            FLog.w(TAG, "Could not extract filename from URI: " + uri, e);
            return null;
        }
    }

    /**
     * Check if a project exists on disk.
     */
    public boolean exists(@NonNull String projectId) {
        return new File(getProjectDir(projectId), PROJECT_FILE).exists();
    }

    // ── Serialization helpers (for snapshot capture) ─────────────────

    /**
     * Serialize a project to a JSON string.
     * Used by UndoManager's SnapshotRestorer to capture project state.
     *
     * @param project the project to serialize
     * @return JSON string representation
     */
    @NonNull
    public String toJson(@NonNull FaditorProject project) {
        return gson.toJson(project);
    }

    /**
     * Deserialize a project from a JSON string.
     * Used by UndoManager's SnapshotRestorer to restore project state.
     *
     * @param json the JSON string to parse
     * @return the deserialized project, or null if parsing fails
     */
    @Nullable
    public FaditorProject fromJson(@NonNull String json) {
        try {
            return gson.fromJson(json, FaditorProject.class);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to deserialize project from JSON snapshot", e);
            return null;
        }
    }

    // ── Undo history persistence ─────────────────────────────────────

    private static final String UNDO_HISTORY_FILE = "undo_history.json";

    /**
     * Save undo history alongside the project.
     * Each entry stores a description and a project JSON snapshot.
     *
     * @param projectId the project ID
     * @param descriptions list of action descriptions (oldest first)
     * @param snapshots    list of project JSON snapshots (oldest first)
     * @return true if saved successfully
     */
    public boolean saveUndoHistory(@NonNull String projectId,
                                   @NonNull List<String> descriptions,
                                   @NonNull List<String> snapshots) {
        String json = buildUndoHistoryJson(descriptions, snapshots);
        return writeUndoHistoryJson(projectId, json);
    }

    /**
     * Async variant of {@link #saveUndoHistory}: the (potentially large) JSON of
     * snapshot strings is assembled on the caller thread, but the disk write runs
     * on the background serial executor. Undo-history snapshots can total tens of
     * MB; writing them synchronously on the UI thread was a major stall.
     */
    public void saveUndoHistoryAsync(@NonNull String projectId,
                                     @NonNull List<String> descriptions,
                                     @NonNull List<String> snapshots) {
        final String json = buildUndoHistoryJson(descriptions, snapshots);
        lastWrite = ioExecutor.submit(() -> writeUndoHistoryJson(projectId, json));
    }

    @NonNull
    private String buildUndoHistoryJson(@NonNull List<String> descriptions,
                                        @NonNull List<String> snapshots) {
        JsonArray historyArray = new JsonArray();
        int count = Math.min(descriptions.size(), snapshots.size());
        for (int i = 0; i < count; i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("description", descriptions.get(i));
            entry.addProperty("snapshot", snapshots.get(i));
            historyArray.add(entry);
        }
        return gson.toJson(historyArray);
    }

    private boolean writeUndoHistoryJson(@NonNull String projectId, @NonNull String json) {
        File projectDir = getProjectDir(projectId);
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            FLog.e(TAG, "Failed to create project directory for undo history");
            return false;
        }
        File file = new File(projectDir, UNDO_HISTORY_FILE);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(json);
            writer.flush();
            return true;
        } catch (IOException e) {
            FLog.e(TAG, "Failed to save undo history: " + projectId, e);
            return false;
        }
    }

    /**
     * Load undo history for a project.
     *
     * @param projectId the project ID
     * @param outDescriptions output list populated with descriptions (oldest first)
     * @param outSnapshots    output list populated with snapshots (oldest first)
     * @return true if loaded successfully, false if no history or error
     */
    public boolean loadUndoHistory(@NonNull String projectId,
                                   @NonNull List<String> outDescriptions,
                                   @NonNull List<String> outSnapshots) {
        File file = new File(getProjectDir(projectId), UNDO_HISTORY_FILE);
        if (!file.exists()) {
            FLog.d(TAG, "No undo history found for: " + projectId);
            return false;
        }

        try (FileReader reader = new FileReader(file)) {
            JsonArray historyArray = gson.fromJson(reader, JsonArray.class);
            if (historyArray == null) return false;

            for (int i = 0; i < historyArray.size(); i++) {
                JsonObject entry = historyArray.get(i).getAsJsonObject();
                String desc = entry.has("description")
                        ? entry.get("description").getAsString() : "Unknown";
                String snap = entry.has("snapshot")
                        ? entry.get("snapshot").getAsString() : null;
                if (snap != null) {
                    outDescriptions.add(desc);
                    outSnapshots.add(snap);
                }
            }
            FLog.d(TAG, "Loaded " + outDescriptions.size()
                    + " undo history entries for: " + projectId);
            return !outDescriptions.isEmpty();
        } catch (Exception e) {
            FLog.e(TAG, "Failed to load undo history: " + projectId, e);
            return false;
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    @NonNull
    private File getProjectDir(@NonNull String projectId) {
        return new File(projectsRoot, projectId);
    }

    /**
     * The on-disk directory for a project (created on demand). Slide HTML and the
     * slide render cache live under here so they travel with the project.
     */
    @NonNull
    public File projectDir(@NonNull String projectId) {
        File dir = getProjectDir(projectId);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ── Relative asset paths (schema v6) ─────────────────────────────

    private static final String PROJECT_URI_PREFIX =
            com.fadcam.ui.faditor.model.FaditorProject.PROJECT_URI_SCHEME + "://";

    /**
     * Convert a runtime URI to its on-disk storage form. A {@code file://} URI that
     * points inside the project directory is rewritten to {@code project://<relative>}
     * so the project bundle is portable; everything else is stored verbatim.
     */
    @Nullable
    private String toStorageUri(@NonNull File projectDir, @Nullable String uriStr) {
        if (uriStr == null) return null;
        if (uriStr.startsWith("file://")) {
            String path = Uri.parse(uriStr).getPath();
            if (path != null) {
                String base = projectDir.getAbsolutePath();
                if (path.startsWith(base + File.separator)) {
                    String rel = path.substring(base.length() + 1).replace(File.separatorChar, '/');
                    return PROJECT_URI_PREFIX + rel;
                }
            }
        }
        return uriStr;
    }

    /**
     * Resolve a stored URI back to a runtime URI. {@code project://<relative>} is
     * resolved against the current project directory (absolute {@code file://});
     * everything else is parsed verbatim.
     */
    @NonNull
    private Uri fromStorageUri(@NonNull File projectDir, @NonNull String stored) {
        if (stored.startsWith(PROJECT_URI_PREFIX)) {
            String rel = stored.substring(PROJECT_URI_PREFIX.length());
            return Uri.fromFile(new File(projectDir, rel));
        }
        return Uri.parse(stored);
    }

    @NonNull
    private Gson createGson() {
        return new GsonBuilder()
                .registerTypeAdapter(Uri.class, new UriAdapter())
                .registerTypeAdapter(FaditorProject.class, new ProjectSerializer())
                .registerTypeAdapter(FaditorProject.class, new ProjectDeserializer())
                .setPrettyPrinting()
                .create();
    }

    // ── Gson adapters ────────────────────────────────────────────────

    /**
     * Serializes/deserializes Android Uri as a plain string.
     */
    private static class UriAdapter
            implements JsonSerializer<Uri>, JsonDeserializer<Uri> {

        @Override
        public JsonElement serialize(Uri src, Type typeOfSrc,
                                     JsonSerializationContext context) {
            return context.serialize(src.toString());
        }

        @Override
        public Uri deserialize(JsonElement json, Type typeOfT,
                               JsonDeserializationContext context) throws JsonParseException {
            return Uri.parse(json.getAsString());
        }
    }

    /** Parse a JSON words array (keys t/s/e/x) into a Transcript. */
    @NonNull
    private static com.fadcam.ui.faditor.transcript.Transcript parseWordsArray(
            @NonNull JsonArray wordsArr) {
        com.fadcam.ui.faditor.transcript.Transcript tr =
                new com.fadcam.ui.faditor.transcript.Transcript();
        for (int w = 0; w < wordsArr.size(); w++) {
            JsonObject wj = wordsArr.get(w).getAsJsonObject();
            tr.words.add(new com.fadcam.ui.faditor.transcript.TranscriptWord(
                    wj.get("t").getAsString(),
                    wj.get("s").getAsLong(),
                    wj.get("e").getAsLong(),
                    wj.has("x") && wj.get("x").getAsBoolean(),
                    wj.has("b") && wj.get("b").getAsBoolean()));
        }
        return tr;
    }

    private static void serializeGeneratedSource(JsonObject parentJson,
            com.fadcam.ui.faditor.model.GeneratedSource gs) {
        if (gs == null) return;
        JsonObject g = new JsonObject();
        g.addProperty("kind", gs.kind);
        g.addProperty("mode", gs.mode);
        g.addProperty("htmlUri", gs.htmlUri);
        g.addProperty("contentHash", gs.contentHash);
        if (gs.renderCacheUri != null) g.addProperty("renderCacheUri", gs.renderCacheUri);
        if (gs.renderSequenceDir != null) g.addProperty("renderSequenceDir", gs.renderSequenceDir);
        g.addProperty("authoredDurationMs", gs.authoredDurationMs);
        g.addProperty("width", gs.width);
        g.addProperty("height", gs.height);
        if (gs.styleHint != null) g.addProperty("styleHint", gs.styleHint);
        if (gs.sourceModel != null) g.addProperty("sourceModel", gs.sourceModel);
        parentJson.add("generatedSource", g);
    }

    @Nullable
    private static com.fadcam.ui.faditor.model.GeneratedSource deserializeGeneratedSource(
            JsonObject g) {
        if (g == null || !g.has("htmlUri") || !g.has("contentHash")) return null;
        com.fadcam.ui.faditor.model.GeneratedSource gs =
                new com.fadcam.ui.faditor.model.GeneratedSource();
        if (g.has("kind")) gs.kind = g.get("kind").getAsString();
        if (g.has("mode")) gs.mode = g.get("mode").getAsString();
        gs.htmlUri = g.get("htmlUri").getAsString();
        gs.contentHash = g.get("contentHash").getAsString();
        if (g.has("renderCacheUri")) gs.renderCacheUri = g.get("renderCacheUri").getAsString();
        if (g.has("renderSequenceDir")) gs.renderSequenceDir = g.get("renderSequenceDir").getAsString();
        if (g.has("authoredDurationMs")) gs.authoredDurationMs = g.get("authoredDurationMs").getAsLong();
        if (g.has("width")) gs.width = g.get("width").getAsInt();
        if (g.has("height")) gs.height = g.get("height").getAsInt();
        if (g.has("styleHint")) gs.styleHint = g.get("styleHint").getAsString();
        if (g.has("sourceModel")) gs.sourceModel = g.get("sourceModel").getAsString();
        return gs;
    }

    // ── Schema-v8 layer (Track/TimedItem) serialization (PLAN Part 2 + §4.2) ──

    /**
     * Predicate deciding v7-vs-v8 stamping (PLAN §4.1(1)). Returns true only when the
     * project uses a layer feature an old build cannot represent — i.e. anything BEYOND
     * the auto-migrated single TEXT layer + single AUDIO track with all-default metadata:
     * a non-"ripple" ripple mode, any extra layer/audio track, any non-default track
     * flag/name/zIndex, any TimedItem with a non-NORMAL blend, any TimedItem transform,
     * or any non-zero zHint. Otherwise the project is fully re-expressible as v7 flat
     * lists and is stamped 7 so old builds open it losslessly.
     */
    private static boolean usesLayerFeatures(@NonNull FaditorProject project) {
        Timeline tl = project.getTimeline();
        if (!"ripple".equals(tl.getRippleMode())) return true;
        // The migration produces at most ONE TEXT layer and ONE AUDIO track; more than
        // that means a real multi-track project.
        if (tl.getLayers().size() > 1) return true;
        if (tl.getAudioTracks().size() > 1) return true;
        for (com.fadcam.ui.faditor.layers.Track t : tl.getLayers()) {
            if (trackUsesFeatures(t)) return true;
        }
        for (com.fadcam.ui.faditor.layers.Track t : tl.getAudioTracks()) {
            if (trackUsesFeatures(t)) return true;
        }
        // Master track: only its per-item metadata (blend/transform/zHint) matters —
        // its item set is exactly the flat clips list.
        if (trackUsesFeatures(tl.getMasterTrack())) return true;
        return false;
    }

    /** True if this track carries any non-default metadata beyond the plain migration. */
    private static boolean trackUsesFeatures(@NonNull com.fadcam.ui.faditor.layers.Track t) {
        if (t.getZIndex() != 0 || t.isCollapsed() || t.isHidden()
                || t.isLocked() || t.isMuted()) {
            return true;
        }
        for (com.fadcam.ui.faditor.layers.TimedItem item : t.getItems()) {
            if (item.getBlendMode() != com.fadcam.ui.faditor.layers.BlendMode.NORMAL) return true;
            if (item.hasTransform()) return true;
            if (item.getZHint() != 0) return true;
        }
        return false;
    }

    @NonNull
    private static JsonObject serializeTrack(@NonNull com.fadcam.ui.faditor.layers.Track t) {
        JsonObject tj = new JsonObject();
        tj.addProperty("id", t.getId());
        tj.addProperty("kind", t.getKind().name());
        tj.addProperty("name", t.getName());
        tj.addProperty("zIndex", t.getZIndex());
        tj.addProperty("collapsed", t.isCollapsed());
        tj.addProperty("hidden", t.isHidden());
        tj.addProperty("locked", t.isLocked());
        tj.addProperty("muted", t.isMuted());
        JsonArray itemsArr = new JsonArray();
        for (com.fadcam.ui.faditor.layers.TimedItem item : t.getItems()) {
            itemsArr.add(serializeTimedItem(item));
        }
        tj.add("items", itemsArr);
        return tj;
    }

    @NonNull
    private static JsonObject serializeTimedItem(
            @NonNull com.fadcam.ui.faditor.layers.TimedItem item) {
        JsonObject ij = new JsonObject();
        ij.addProperty("id", item.getId());
        ij.addProperty("timelineStartMs", item.getTimelineStartMs());
        ij.addProperty("zHint", item.getZHint());
        ij.addProperty("blendMode", item.getBlendMode().name());
        // Payload discriminator — the payload itself lives in the flat lists (dual-write);
        // here we only record which flat object this item wraps, by id.
        ij.addProperty("payloadKind", item.payloadKind());
        String payloadId = null;
        if (item.getClip() != null) payloadId = item.getClip().getId();
        else if (item.getTextOverlay() != null) payloadId = item.getTextOverlay().getId();
        else if (item.getAudioClip() != null) payloadId = item.getAudioClip().getId();
        if (payloadId != null) ij.addProperty("payloadId", payloadId);
        // transform: reuse the EXACT overlay-keyframes JSON shape (see ~928-945:
        // { property: [ {t,v,e}, ... ] }).
        if (item.hasTransform()) {
            JsonObject tracksJson = new JsonObject();
            for (com.fadcam.ui.faditor.keyframe.KeyframeTrack tr : item.getTransform().tracks()) {
                if (tr.isEmpty()) continue;
                JsonArray kfArr = new JsonArray();
                for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
                    JsonObject kj = new JsonObject();
                    kj.addProperty("t", k.timeMs);
                    kj.addProperty("v", k.value);
                    kj.addProperty("e", k.easing.name());
                    kfArr.add(kj);
                }
                tracksJson.add(tr.property, kfArr);
            }
            ij.add("transform", tracksJson);
        }
        return ij;
    }

    private static void serializeEffectStack(JsonObject clipJson,
                                             com.fadcam.ui.faditor.effects.EffectStack stack) {
        if (stack == null || !stack.isActive()) return;
        JsonObject fx = new JsonObject();
        fx.addProperty("exposure", stack.getExposure());
        fx.addProperty("contrast", stack.getContrast());
        fx.addProperty("saturation", stack.getSaturation());
        fx.addProperty("temperature", stack.getTemperature());
        fx.addProperty("tint", stack.getTint());
        fx.addProperty("highlights", stack.getHighlights());
        fx.addProperty("shadows", stack.getShadows());
        fx.addProperty("fade", stack.getFade());
        fx.addProperty("vignette", stack.getVignette());
        fx.addProperty("grain", stack.getGrain());
        fx.addProperty("lutEnabled", stack.isLutEnabled());
        if (stack.getLutId() != null) fx.addProperty("lutId", stack.getLutId());
        clipJson.add("effectStack", fx);
    }

    private static void deserializeEffectStack(
            com.fadcam.ui.faditor.effects.EffectStack stack, JsonObject fx) {
        if (fx.has("exposure")) stack.setExposure(fx.get("exposure").getAsFloat());
        if (fx.has("contrast")) stack.setContrast(fx.get("contrast").getAsFloat());
        if (fx.has("saturation")) stack.setSaturation(fx.get("saturation").getAsFloat());
        if (fx.has("temperature")) stack.setTemperature(fx.get("temperature").getAsFloat());
        if (fx.has("tint")) stack.setTint(fx.get("tint").getAsFloat());
        if (fx.has("highlights")) stack.setHighlights(fx.get("highlights").getAsFloat());
        if (fx.has("shadows")) stack.setShadows(fx.get("shadows").getAsFloat());
        if (fx.has("fade")) stack.setFade(fx.get("fade").getAsFloat());
        if (fx.has("vignette")) stack.setVignette(fx.get("vignette").getAsFloat());
        if (fx.has("grain")) stack.setGrain(fx.get("grain").getAsFloat());
        if (fx.has("lutEnabled")) stack.setLutEnabled(fx.get("lutEnabled").getAsBoolean());
        if (fx.has("lutId")) stack.setLutId(fx.get("lutId").getAsString());
    }

    /**
     * Custom serializer for FaditorProject (flattens nested objects).
     */
    private class ProjectSerializer implements JsonSerializer<FaditorProject> {
        @Override
        public JsonElement serialize(FaditorProject src, Type typeOfSrc,
                                     JsonSerializationContext context) {
            File projectDir = getProjectDir(src.getId());
            JsonObject json = new JsonObject();
            // Dual-write schema stamp (PLAN §4.1(1)): stamp v8 ONLY when the project
            // genuinely uses a layer feature an old build can't represent; otherwise
            // stamp 7 so an old build can still open it losslessly. The v8 `layers`
            // block below is written either way (additive; old builds ignore it).
            int stampedVersion = usesLayerFeatures(src)
                    ? com.fadcam.ui.faditor.model.FaditorProject.SCHEMA_VERSION  // 8
                    : 7;
            json.addProperty("schemaVersion", stampedVersion);
            json.addProperty("id", src.getId());
            json.addProperty("name", src.getName());
            json.addProperty("createdAt", src.getCreatedAt());
            json.addProperty("lastModified", src.getLastModified());

            // Serialize timeline with clips
            JsonObject timelineJson = new JsonObject();
            JsonArray clipsArray = new JsonArray();
            for (Clip clip : src.getTimeline().getClips()) {
                JsonObject clipJson = new JsonObject();
                clipJson.addProperty("id", clip.getId());
                clipJson.addProperty("sourceUri", toStorageUri(projectDir, clip.getSourceUri().toString()));
                clipJson.addProperty("imageClip", clip.isImageClip());
                clipJson.addProperty("inPointMs", clip.getInPointMs());
                clipJson.addProperty("outPointMs", clip.getOutPointMs());
                clipJson.addProperty("sourceDurationMs", clip.getSourceDurationMs());
                clipJson.addProperty("speedMultiplier", clip.getSpeedMultiplier());
                clipJson.addProperty("audioMuted", clip.isAudioMuted());
                clipJson.addProperty("volumeLevel", clip.getVolumeLevel());
                clipJson.addProperty("rotationDegrees", clip.getRotationDegrees());
                clipJson.addProperty("flipHorizontal", clip.isFlipHorizontal());
                clipJson.addProperty("flipVertical", clip.isFlipVertical());
                clipJson.addProperty("cropPreset", clip.getCropPreset());
                clipJson.addProperty("cropLeft", clip.getCropLeft());
                clipJson.addProperty("cropTop", clip.getCropTop());
                clipJson.addProperty("cropRight", clip.getCropRight());
                clipJson.addProperty("cropBottom", clip.getCropBottom());
                if (!clip.getRemovedSpans().isEmpty()) {
                    JsonArray spans = new JsonArray();
                    for (long[] s : clip.getRemovedSpans()) {
                        JsonArray pair = new JsonArray();
                        pair.add(s[0]);
                        pair.add(s[1]);
                        spans.add(pair);
                    }
                    clipJson.add("removedSpans", spans);
                }
                // Transcript versions (word timings + struck state) so reopening
                // never re-transcribes and captions can be rebuilt for export.
                java.util.List<com.fadcam.ui.faditor.transcript.NamedTranscript> versions =
                        clip.getTranscripts();
                if (!versions.isEmpty()) {
                    JsonArray versionsArr = new JsonArray();
                    for (com.fadcam.ui.faditor.transcript.NamedTranscript nt : versions) {
                        JsonObject vj = new JsonObject();
                        vj.addProperty("id", nt.id);
                        vj.addProperty("label", nt.label);
                        vj.addProperty("engine", nt.engine);
                        JsonArray wordsArr = new JsonArray();
                        for (com.fadcam.ui.faditor.transcript.TranscriptWord w : nt.transcript.words) {
                            JsonObject wj = new JsonObject();
                            wj.addProperty("t", w.text);
                            wj.addProperty("s", w.startMs);
                            wj.addProperty("e", w.endMs);
                            if (w.struck) wj.addProperty("x", true);
                            if (w.forceLineBreakAfter) wj.addProperty("b", true);
                            wordsArr.add(wj);
                        }
                        vj.add("words", wordsArr);
                        versionsArr.add(vj);
                    }
                    clipJson.add("transcripts", versionsArr);
                    clipJson.addProperty("activeTranscript", clip.getActiveTranscriptIndex());
                }
                if (clip.getDisplayName() != null) {
                    clipJson.addProperty("displayName", clip.getDisplayName());
                }
                // Caption settings.
                clipJson.addProperty("captionsEnabled", clip.isCaptionsEnabled());
                clipJson.addProperty("captionStyleId", clip.getCaptionStyleId());
                clipJson.addProperty("captionCenterX", clip.getCaptionCenterX());
                clipJson.addProperty("captionCenterY", clip.getCaptionCenterY());
                clipJson.addProperty("captionSizeFraction", clip.getCaptionSizeFraction());
                // Audio ducking and punch-in zoom (schema v2)
                if (clip.getDuckAmount() > 0f) {
                    clipJson.addProperty("duckAmount", clip.getDuckAmount());
                }
                if (clip.getZoomLevel() > 1.0f) {
                    clipJson.addProperty("zoomLevel", clip.getZoomLevel());
                    clipJson.addProperty("zoomCenterX", clip.getZoomCenterX());
                    clipJson.addProperty("zoomCenterY", clip.getZoomCenterY());
                }
                if (clip.getLoopMode() != com.fadcam.ui.faditor.model.Clip.LOOP_MODE_OFF) {
                    clipJson.addProperty("loopMode", clip.getLoopMode());
                    clipJson.addProperty("loopBeforeMs", clip.getLoopBeforeMs());
                    clipJson.addProperty("loopAfterMs", clip.getLoopAfterMs());
                }
                if (clip.hasOpacityKeyframes()) {
                    JsonArray kfArr = new JsonArray();
                    for (com.fadcam.ui.faditor.model.Clip.OpacityKeyframe kf
                            : clip.getOpacityKeyframes()) {
                        JsonObject kfJson = new JsonObject();
                        kfJson.addProperty("t", kf.timeMs);
                        kfJson.addProperty("o", kf.opacity);
                        kfArr.add(kfJson);
                    }
                    clipJson.add("opacityKeyframes", kfArr);
                }
                if (clip.hasVolumeKeyframes()) {
                    JsonArray kfArr = new JsonArray();
                    for (com.fadcam.ui.faditor.model.Clip.VolumeKeyframe kf
                            : clip.getVolumeKeyframes()) {
                        JsonObject kfJson = new JsonObject();
                        kfJson.addProperty("t", kf.timeMs);
                        kfJson.addProperty("v", kf.volume);
                        kfArr.add(kfJson);
                    }
                    clipJson.add("volumeKeyframes", kfArr);
                }
                if (clip.hasCaptionStyleKeyframes()) {
                    JsonArray kfArr = new JsonArray();
                    for (com.fadcam.ui.faditor.model.Clip.CaptionStyleKeyframe kf
                            : clip.getCaptionStyleKeyframes()) {
                        JsonObject kfJson = new JsonObject();
                        kfJson.addProperty("t", kf.timeMs);
                        kfJson.addProperty("s", kf.styleId);
                        kfArr.add(kfJson);
                    }
                    clipJson.add("captionStyleKeyframes", kfArr);
                }
                serializeEffectStack(clipJson, clip.getEffectStack());
                serializeGeneratedSource(clipJson, clip.getGeneratedSource());
                clipsArray.add(clipJson);
            }
            timelineJson.add("clips", clipsArray);

            // Serialize audio clips
            JsonArray audioArray = new JsonArray();
            for (AudioClip ac : src.getTimeline().getAudioClips()) {
                JsonObject acJson = new JsonObject();
                acJson.addProperty("id", ac.getId());
                acJson.addProperty("sourceUri", toStorageUri(projectDir, ac.getSourceUri().toString()));
                acJson.addProperty("sourceDurationMs", ac.getSourceDurationMs());
                acJson.addProperty("inPointMs", ac.getInPointMs());
                acJson.addProperty("outPointMs", ac.getOutPointMs());
                acJson.addProperty("offsetMs", ac.getOffsetMs());
                acJson.addProperty("volumeLevel", ac.getVolumeLevel());
                acJson.addProperty("muted", ac.isMuted());
                acJson.addProperty("label", ac.getLabel());
                // Serialize waveform as int array
                int[] waveform = ac.getWaveform();
                if (waveform != null) {
                    JsonArray wfArray = new JsonArray();
                    for (int val : waveform) {
                        wfArray.add(val);
                    }
                    acJson.add("waveform", wfArray);
                }
                // Serialize volume automation keyframes (the blue envelope)
                if (ac.hasVolumeKeyframes()) {
                    JsonArray kfArr = new JsonArray();
                    for (AudioClip.VolumeKeyframe kf : ac.getVolumeKeyframes()) {
                        JsonObject kfJson = new JsonObject();
                        kfJson.addProperty("t", kf.timeMs);
                        kfJson.addProperty("v", kf.volume);
                        kfArr.add(kfJson);
                    }
                    acJson.add("volumeKeyframes", kfArr);
                }
                // Serialize transcripts + caption settings
                if (ac.hasTranscript()) {
                    JsonArray versionsArr = new JsonArray();
                    for (com.fadcam.ui.faditor.transcript.NamedTranscript nt : ac.getTranscripts()) {
                        JsonObject vj = new JsonObject();
                        vj.addProperty("id", nt.id);
                        vj.addProperty("label", nt.label);
                        vj.addProperty("engine", nt.engine);
                        JsonArray wordsArr = new JsonArray();
                        for (com.fadcam.ui.faditor.transcript.TranscriptWord w : nt.transcript.words) {
                            JsonObject wj = new JsonObject();
                            wj.addProperty("t", w.text);
                            wj.addProperty("s", w.startMs);
                            wj.addProperty("e", w.endMs);
                            if (w.struck) wj.addProperty("x", true);
                            if (w.forceLineBreakAfter) wj.addProperty("b", true);
                            wordsArr.add(wj);
                        }
                        vj.add("words", wordsArr);
                        versionsArr.add(vj);
                    }
                    acJson.add("transcripts", versionsArr);
                    acJson.addProperty("activeTranscript", ac.getActiveTranscriptIndex());
                }
                acJson.addProperty("captionsEnabled", ac.isCaptionsEnabled());
                acJson.addProperty("captionStyleId", ac.getCaptionStyleId());
                acJson.addProperty("captionCenterX", ac.getCaptionCenterX());
                acJson.addProperty("captionCenterY", ac.getCaptionCenterY());
                audioArray.add(acJson);
            }
            timelineJson.add("audioClips", audioArray);

            // Serialize text overlays
            JsonArray overlaysArray = new JsonArray();
            for (com.fadcam.ui.faditor.model.TextOverlayItem o
                    : src.getTimeline().getTextOverlays()) {
                JsonObject oJson = new JsonObject();
                oJson.addProperty("id", o.getId());
                oJson.addProperty("text", o.getText());
                oJson.addProperty("colorInt", o.getColorInt());
                oJson.addProperty("centerX", o.getCenterX());
                oJson.addProperty("centerY", o.getCenterY());
                oJson.addProperty("sizeFraction", o.getSizeFraction());
                oJson.addProperty("rotationDeg", o.getRotationDeg());
                if (o.getOpacity() != 1f) oJson.addProperty("opacity", o.getOpacity());
                if (!"default".equals(o.getFontFamily())) {
                    oJson.addProperty("fontFamily", o.getFontFamily());
                }
                if (o.getImageUri() != null) {
                    oJson.addProperty("imageUri", toStorageUri(projectDir, o.getImageUri()));
                }
                // Time range (only when not the default whole-timeline span).
                if (o.getStartMs() != 0) oJson.addProperty("startMs", o.getStartMs());
                if (o.getEndMs() != Long.MAX_VALUE) oJson.addProperty("endMs", o.getEndMs());
                if (o.getStrokeColorInt() != android.graphics.Color.TRANSPARENT) oJson.addProperty("strokeColorInt", o.getStrokeColorInt());
                if (o.getStrokeWidthPx() > 0f) oJson.addProperty("strokeWidthPx", o.getStrokeWidthPx());
                if (o.getShadowColorInt() != 0xCC000000) oJson.addProperty("shadowColorInt", o.getShadowColorInt());
                if (o.getShadowRadiusPx() > 0f) oJson.addProperty("shadowRadiusPx", o.getShadowRadiusPx());
                if (o.getGlowColorInt() != android.graphics.Color.TRANSPARENT) oJson.addProperty("glowColorInt", o.getGlowColorInt());
                if (o.getGlowRadiusPx() > 0f) oJson.addProperty("glowRadiusPx", o.getGlowRadiusPx());
                if (o.getBackgroundColorInt() != android.graphics.Color.TRANSPARENT) oJson.addProperty("backgroundColorInt", o.getBackgroundColorInt());
                // Keyframe tracks (animation), if any.
                if (!o.getKeyframes().isEmpty()) {
                    JsonObject tracksJson = new JsonObject();
                    for (com.fadcam.ui.faditor.keyframe.KeyframeTrack tr
                            : o.getKeyframes().tracks()) {
                        if (tr.isEmpty()) continue;
                        JsonArray kfArr = new JsonArray();
                        for (com.fadcam.ui.faditor.keyframe.Keyframe k : tr.keyframes) {
                            JsonObject kj = new JsonObject();
                            kj.addProperty("t", k.timeMs);
                            kj.addProperty("v", k.value);
                            kj.addProperty("e", k.easing.name());
                            kfArr.add(kj);
                        }
                        tracksJson.add(tr.property, kfArr);
                    }
                    oJson.add("keyframes", tracksJson);
                }
                overlaysArray.add(oJson);
            }
            timelineJson.add("textOverlays", overlaysArray);

            // Serialize waveform visualizer overlays (schema v7)
            if (!src.getTimeline().getWaveformOverlays().isEmpty()) {
                JsonArray wfArray = new JsonArray();
                for (com.fadcam.ui.faditor.model.WaveformOverlayInstance wo
                        : src.getTimeline().getWaveformOverlays()) {
                    JsonObject wj = new JsonObject();
                    wj.addProperty("id", wo.getId());
                    wj.addProperty("styleId", wo.getStyleId());
                    if (wo.getAudioSourceRef() != null) {
                        wj.addProperty("audioSourceRef", wo.getAudioSourceRef());
                    }
                    if (wo.getStartMs() != 0) wj.addProperty("startMs", wo.getStartMs());
                    if (wo.getEndMs() != Long.MAX_VALUE) wj.addProperty("endMs", wo.getEndMs());
                    wj.addProperty("centerX", wo.getCenterX());
                    wj.addProperty("centerY", wo.getCenterY());
                    wj.addProperty("widthFraction", wo.getWidthFraction());
                    wj.addProperty("heightFraction", wo.getHeightFraction());
                    if (wo.getRotationDeg() != 0f) wj.addProperty("rotationDeg", wo.getRotationDeg());
                    if (wo.getJustify() >= 0) wj.addProperty("justify", wo.getJustify());
                    if (wo.getDataMode() >= 0) wj.addProperty("dataMode", wo.getDataMode());
                    if (wo.isHorizontalMirror()) wj.addProperty("hMirror", true);
                    if (wo.getCenterMode() >= 0) wj.addProperty("centerMode", wo.getCenterMode());
                    if (wo.getRenderMode() != 0) wj.addProperty("renderMode", wo.getRenderMode());
                    if (Math.abs(wo.getRadialRingSize() - 0.35f) > 0.01f) wj.addProperty("radialRingSize", wo.getRadialRingSize());
                    if (wo.getFrequencyRangeLowHz() != 20) wj.addProperty("freqLowHz", wo.getFrequencyRangeLowHz());
                    if (wo.getFrequencyRangeHighHz() != 20000) wj.addProperty("freqHighHz", wo.getFrequencyRangeHighHz());
                    if (wo.getBandCountOverride() != 0) wj.addProperty("bandCount", wo.getBandCountOverride());
                    if (wo.getColorOverride() != null) wj.addProperty("colorOverride", wo.getColorOverride());
                    if (wo.getSensitivityOverride() > 0f) wj.addProperty("sensitivity", wo.getSensitivityOverride());
                    if (wo.getGradientStartOverride() != null && wo.getGradientEndOverride() != null) {
                        wj.addProperty("gradStart", wo.getGradientStartOverride());
                        wj.addProperty("gradEnd", wo.getGradientEndOverride());
                    }
                    wfArray.add(wj);
                }
                timelineJson.add("waveformOverlays", wfArray);
            }

            // Serialize transitions
            if (!src.getTimeline().getTransitions().isEmpty()) {
                JsonArray transArray = new JsonArray();
                for (com.fadcam.ui.faditor.model.Transition t
                        : src.getTimeline().getTransitions()) {
                    JsonObject tj = new JsonObject();
                    tj.addProperty("type", t.type.name());
                    tj.addProperty("durationMs", t.durationMs);
                    tj.addProperty("clipIndex", t.clipIndex);
                    if (t.fuzziness > 0) tj.addProperty("fuzziness", t.fuzziness);
                    if (t.type == com.fadcam.ui.faditor.model.Transition.Type.GL_SHADER) {
                        if (t.glTransitionId != null) tj.addProperty("glTransitionId", t.glTransitionId);
                        if (t.paramOverrides != null && !t.paramOverrides.isEmpty()) {
                            JsonObject params = new JsonObject();
                            for (java.util.Map.Entry<String, Float> e : t.paramOverrides.entrySet()) {
                                params.addProperty(e.getKey(), e.getValue());
                            }
                            tj.add("paramOverrides", params);
                        }
                    }
                    transArray.add(tj);
                }
                timelineJson.add("transitions", transArray);
            }

            // ── Schema-v8 additive layer block (PLAN Part 2 + §4.1) ──────
            // Written alongside the v7 flat lists (dual-write). Payloads are NOT
            // duplicated here — each layer item references its payload in the flat
            // lists by discriminator + id (§4.2). Old builds ignore this key.
            timelineJson.addProperty("rippleMode", src.getTimeline().getRippleMode());
            JsonObject layersBlock = new JsonObject();
            layersBlock.add("masterTrack", serializeTrack(src.getTimeline().getMasterTrack()));
            JsonArray layersArr = new JsonArray();
            for (com.fadcam.ui.faditor.layers.Track t : src.getTimeline().getLayers()) {
                layersArr.add(serializeTrack(t));
            }
            layersBlock.add("layers", layersArr);
            JsonArray audioTracksArr = new JsonArray();
            for (com.fadcam.ui.faditor.layers.Track t : src.getTimeline().getAudioTracks()) {
                audioTracksArr.add(serializeTrack(t));
            }
            layersBlock.add("audioTracks", audioTracksArr);
            timelineJson.add("layers", layersBlock);

            json.add("timeline", timelineJson);

            // Serialize canvas preset
            json.addProperty("canvasPreset", src.getCanvasPreset());

            // Serialize pinned asset directory (schema v3+)
            if (src.getPinnedAssetDir() != null) {
                json.addProperty("pinnedAssetDir", src.getPinnedAssetDir());
            }
            if (!src.getAssetDirHistory().isEmpty()) {
                JsonArray histArr = new JsonArray();
                for (String s : src.getAssetDirHistory()) histArr.add(s);
                json.add("assetDirHistory", histArr);
            }

            // Serialize export settings
            JsonObject exportJson = new JsonObject();
            exportJson.addProperty("resolution", src.getExportSettings().getResolution().name());
            exportJson.addProperty("quality", src.getExportSettings().getQuality().name());
            exportJson.addProperty("format", src.getExportSettings().getFormat().name());
            exportJson.addProperty("cleanAudio", src.getExportSettings().isCleanAudio());
            json.add("exportSettings", exportJson);

            return json;
        }
    }

    /**
     * Custom deserializer for FaditorProject (rebuilds from flat JSON).
     */
    private class ProjectDeserializer implements JsonDeserializer<FaditorProject> {
        @Override
        public FaditorProject deserialize(JsonElement json, Type typeOfT,
                                          JsonDeserializationContext context)
                throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            // Resolve project://<relative> asset paths against this project's dir.
            String projectIdForPaths = obj.has("id") ? obj.get("id").getAsString() : null;
            File projectDir = projectIdForPaths != null
                    ? getProjectDir(projectIdForPaths) : projectsRoot;

            // Restore schema version (defaults to 0 for very old projects)
            int schemaVersion = obj.has("schemaVersion")
                    ? obj.get("schemaVersion").getAsInt() : 0;

            String name = obj.has("name") ? obj.get("name").getAsString() : "Untitled";

            // Restore project with original ID and timestamps
            FaditorProject project;
            if (obj.has("id") && obj.has("createdAt")) {
                String id = obj.get("id").getAsString();
                long createdAt = obj.get("createdAt").getAsLong();
                long lastModified = obj.has("lastModified")
                        ? obj.get("lastModified").getAsLong() : createdAt;
                project = new FaditorProject(id, name, createdAt, lastModified);
            } else {
                project = new FaditorProject(name);
            }
            // Downgrade guard (PLAN §4.1(2)). Previously this UNCONDITIONALLY stamped
            // the running SCHEMA_VERSION onto every loaded project — so a project written
            // by a NEWER build, opened here, would be silently re-saved at our (older)
            // version, dropping the unknown newer data. Instead: keep the on-disk version,
            // and if it is newer than we understand, mark the project read-only so the
            // save path refuses to overwrite it.
            int runningVersion = com.fadcam.ui.faditor.model.FaditorProject.SCHEMA_VERSION;
            if (schemaVersion > runningVersion) {
                project.setSchemaVersion(schemaVersion);          // preserve the newer stamp
                project.setLoadedFromNewerVersion(true);
                FLog.w(TAG, "Project '" + name + "' was written by a NEWER build (schema v"
                        + schemaVersion + " > running v" + runningVersion
                        + "); opening read-only to avoid a lossy downgrade.");
            } else {
                // Same-or-older on-disk version: adopt the running version. The dual-write
                // serializer re-decides v7-vs-v8 on the next save via usesLayerFeatures().
                project.setSchemaVersion(runningVersion);
            }

            // Restore timeline clips
            if (obj.has("timeline")) {
                JsonObject timelineJson = obj.getAsJsonObject("timeline");
                if (timelineJson.has("clips")) {
                    JsonArray clips = timelineJson.getAsJsonArray("clips");
                    for (int i = 0; i < clips.size(); i++) {
                        JsonObject clipObj = clips.get(i).getAsJsonObject();
                        String clipId = clipObj.get("id").getAsString();
                        Uri sourceUri = fromStorageUri(projectDir, clipObj.get("sourceUri").getAsString());
                        long inPointMs = clipObj.get("inPointMs").getAsLong();
                        long outPointMs = clipObj.get("outPointMs").getAsLong();
                        long sourceDurationMs = clipObj.get("sourceDurationMs").getAsLong();
                        float speed = clipObj.has("speedMultiplier")
                                ? clipObj.get("speedMultiplier").getAsFloat() : 1.0f;
                        boolean audioMuted = clipObj.has("audioMuted")
                                && clipObj.get("audioMuted").getAsBoolean();
                        float volumeLevel = clipObj.has("volumeLevel")
                                ? clipObj.get("volumeLevel").getAsFloat() : 1.0f;
                        int rotationDeg = clipObj.has("rotationDegrees")
                                ? clipObj.get("rotationDegrees").getAsInt() : 0;
                        boolean flipH = clipObj.has("flipHorizontal")
                                && clipObj.get("flipHorizontal").getAsBoolean();
                        boolean flipV = clipObj.has("flipVertical")
                                && clipObj.get("flipVertical").getAsBoolean();
                        String crop = clipObj.has("cropPreset")
                                ? clipObj.get("cropPreset").getAsString() : "none";
                        float cropL = clipObj.has("cropLeft")
                                ? clipObj.get("cropLeft").getAsFloat() : 0f;
                        float cropT = clipObj.has("cropTop")
                                ? clipObj.get("cropTop").getAsFloat() : 0f;
                        float cropR = clipObj.has("cropRight")
                                ? clipObj.get("cropRight").getAsFloat() : 1f;
                        float cropB = clipObj.has("cropBottom")
                                ? clipObj.get("cropBottom").getAsFloat() : 1f;

                        Clip clip = new Clip(clipId, sourceUri,
                                inPointMs, outPointMs, sourceDurationMs,
                                speed, audioMuted, volumeLevel,
                                rotationDeg, flipH, flipV, crop,
                                cropL, cropT, cropR, cropB);
                        if (clipObj.has("imageClip")) {
                            clip.setImageClip(clipObj.get("imageClip").getAsBoolean());
                        }
                        if (clipObj.has("removedSpans")) {
                            JsonArray spans = clipObj.getAsJsonArray("removedSpans");
                            java.util.List<long[]> list = new java.util.ArrayList<>();
                            for (int s = 0; s < spans.size(); s++) {
                                JsonArray pair = spans.get(s).getAsJsonArray();
                                list.add(new long[]{pair.get(0).getAsLong(),
                                        pair.get(1).getAsLong()});
                            }
                            clip.setRemovedSpans(list);
                        }
                        // New format: list of named transcript versions.
                        if (clipObj.has("transcripts")) {
                            JsonArray versionsArr = clipObj.getAsJsonArray("transcripts");
                            for (int v = 0; v < versionsArr.size(); v++) {
                                JsonObject vj = versionsArr.get(v).getAsJsonObject();
                                com.fadcam.ui.faditor.transcript.Transcript tr =
                                        parseWordsArray(vj.getAsJsonArray("words"));
                                String id = vj.has("id") ? vj.get("id").getAsString()
                                        : java.util.UUID.randomUUID().toString();
                                String label = vj.has("label") ? vj.get("label").getAsString()
                                        : "Transcript";
                                String engine = vj.has("engine") ? vj.get("engine").getAsString()
                                        : "vosk";
                                clip.addTranscript(
                                        new com.fadcam.ui.faditor.transcript.NamedTranscript(
                                                id, label, engine, tr));
                            }
                            if (clipObj.has("activeTranscript")) {
                                clip.setActiveTranscriptIndex(
                                        clipObj.get("activeTranscript").getAsInt());
                            }
                        } else if (clipObj.has("transcript")) {
                            // Back-compat: a single un-named transcript.
                            com.fadcam.ui.faditor.transcript.Transcript tr =
                                    parseWordsArray(clipObj.getAsJsonArray("transcript"));
                            clip.addTranscript(
                                    new com.fadcam.ui.faditor.transcript.NamedTranscript(
                                            "Transcript", "vosk", tr));
                        }
                        if (clipObj.has("displayName")) {
                            clip.setDisplayName(clipObj.get("displayName").getAsString());
                        }
                        if (clipObj.has("captionsEnabled")) {
                            clip.setCaptionsEnabled(
                                    clipObj.get("captionsEnabled").getAsBoolean());
                        }
                        if (clipObj.has("captionStyleId")) {
                            clip.setCaptionStyleId(
                                    clipObj.get("captionStyleId").getAsString());
                        }
                        if (clipObj.has("captionCenterX") && clipObj.has("captionCenterY")) {
                            clip.setCaptionCenter(
                                    clipObj.get("captionCenterX").getAsFloat(),
                                    clipObj.get("captionCenterY").getAsFloat());
                        }
                        if (clipObj.has("captionSizeFraction")) {
                            clip.setCaptionSizeFraction(
                                    clipObj.get("captionSizeFraction").getAsFloat());
                        }
                        // Audio ducking and punch-in zoom (schema v2)
                        if (clipObj.has("duckAmount")) {
                            clip.setDuckAmount(clipObj.get("duckAmount").getAsFloat());
                        }
                        if (clipObj.has("zoomLevel")) {
                            clip.setZoomLevel(clipObj.get("zoomLevel").getAsFloat());
                            float zcx = clipObj.has("zoomCenterX")
                                    ? clipObj.get("zoomCenterX").getAsFloat() : 0.5f;
                            float zcy = clipObj.has("zoomCenterY")
                                    ? clipObj.get("zoomCenterY").getAsFloat() : 0.5f;
                            clip.setZoomCenter(zcx, zcy);
                        }
                        // Loop / ping-pong (schema v6+)
                        if (clipObj.has("loopMode")) {
                            clip.setLoopMode(clipObj.get("loopMode").getAsInt());
                            clip.setLoopBeforeMs(clipObj.has("loopBeforeMs")
                                    ? clipObj.get("loopBeforeMs").getAsLong() : 0);
                            clip.setLoopAfterMs(clipObj.has("loopAfterMs")
                                    ? clipObj.get("loopAfterMs").getAsLong() : 0);
                        }
                        if (clipObj.has("opacityKeyframes")) {
                            JsonArray kfArr = clipObj.getAsJsonArray("opacityKeyframes");
                            java.util.ArrayList<com.fadcam.ui.faditor.model.Clip.OpacityKeyframe> kfs =
                                    new java.util.ArrayList<>();
                            for (int k = 0; k < kfArr.size(); k++) {
                                JsonObject kf = kfArr.get(k).getAsJsonObject();
                                kfs.add(new com.fadcam.ui.faditor.model.Clip.OpacityKeyframe(
                                        kf.get("t").getAsLong(),
                                        kf.get("o").getAsFloat()));
                            }
                            clip.setOpacityKeyframes(kfs);
                        }
                        if (clipObj.has("volumeKeyframes")) {
                            JsonArray kfArr = clipObj.getAsJsonArray("volumeKeyframes");
                            java.util.ArrayList<com.fadcam.ui.faditor.model.Clip.VolumeKeyframe> kfs =
                                    new java.util.ArrayList<>();
                            for (int k = 0; k < kfArr.size(); k++) {
                                JsonObject kf = kfArr.get(k).getAsJsonObject();
                                kfs.add(new com.fadcam.ui.faditor.model.Clip.VolumeKeyframe(
                                        kf.get("t").getAsLong(),
                                        kf.get("v").getAsFloat()));
                            }
                            clip.setVolumeKeyframes(kfs);
                        }
                        if (clipObj.has("captionStyleKeyframes")) {
                            JsonArray kfArr = clipObj.getAsJsonArray("captionStyleKeyframes");
                            java.util.ArrayList<com.fadcam.ui.faditor.model.Clip.CaptionStyleKeyframe> kfs =
                                    new java.util.ArrayList<>();
                            for (int k = 0; k < kfArr.size(); k++) {
                                JsonObject kf = kfArr.get(k).getAsJsonObject();
                                kfs.add(new com.fadcam.ui.faditor.model.Clip.CaptionStyleKeyframe(
                                        kf.get("t").getAsLong(),
                                        kf.get("s").getAsString()));
                            }
                            clip.getCaptionStyleKeyframes().addAll(kfs);
                        }
                        if (clipObj.has("effectStack")) {
                            deserializeEffectStack(clip.getEffectStack(),
                                    clipObj.getAsJsonObject("effectStack"));
                        }
                        if (clipObj.has("generatedSource")) {
                            clip.setGeneratedSource(deserializeGeneratedSource(
                                    clipObj.getAsJsonObject("generatedSource")));
                        }
                        project.getTimeline().addClip(clip);
                    }
                }
            }

            // Restore audio clips
            if (obj.has("timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (tl.has("audioClips")) {
                    JsonArray audioArr = tl.getAsJsonArray("audioClips");
                    for (int i = 0; i < audioArr.size(); i++) {
                        JsonObject acObj = audioArr.get(i).getAsJsonObject();
                        Uri acUri = fromStorageUri(projectDir, acObj.get("sourceUri").getAsString());
                        long acDuration = acObj.get("sourceDurationMs").getAsLong();
                        AudioClip ac = new AudioClip(acUri, acDuration);
                        ac.setInPointMs(acObj.get("inPointMs").getAsLong());
                        ac.setOutPointMs(acObj.get("outPointMs").getAsLong());
                        if (acObj.has("offsetMs")) {
                            ac.setOffsetMs(acObj.get("offsetMs").getAsLong());
                        }
                        if (acObj.has("volumeLevel")) {
                            ac.setVolumeLevel(acObj.get("volumeLevel").getAsFloat());
                        }
                        if (acObj.has("muted")) {
                            ac.setMuted(acObj.get("muted").getAsBoolean());
                        }
                        if (acObj.has("label")) {
                            ac.setLabel(acObj.get("label").getAsString());
                        }
                        if (acObj.has("volumeKeyframes")) {
                            JsonArray kfArr = acObj.getAsJsonArray("volumeKeyframes");
                            List<AudioClip.VolumeKeyframe> kfs = new ArrayList<>();
                            for (int j = 0; j < kfArr.size(); j++) {
                                JsonObject kfObj = kfArr.get(j).getAsJsonObject();
                                kfs.add(new AudioClip.VolumeKeyframe(
                                        kfObj.get("t").getAsLong(),
                                        kfObj.get("v").getAsFloat()));
                            }
                            ac.setVolumeKeyframes(kfs);
                        }
                        if (acObj.has("waveform")) {
                            JsonArray wfArr = acObj.getAsJsonArray("waveform");
                            int[] waveform = new int[wfArr.size()];
                            for (int j = 0; j < wfArr.size(); j++) {
                                waveform[j] = wfArr.get(j).getAsInt();
                            }
                            ac.setWaveform(waveform);
                        }
                        // Restore transcripts + caption settings
                        if (acObj.has("transcripts")) {
                            JsonArray versionsArr = acObj.getAsJsonArray("transcripts");
                            for (int v = 0; v < versionsArr.size(); v++) {
                                JsonObject vj = versionsArr.get(v).getAsJsonObject();
                                com.fadcam.ui.faditor.transcript.Transcript tr =
                                        parseWordsArray(vj.getAsJsonArray("words"));
                                String id = vj.has("id") ? vj.get("id").getAsString()
                                        : java.util.UUID.randomUUID().toString();
                                String label = vj.has("label") ? vj.get("label").getAsString()
                                        : "Transcript";
                                String engine = vj.has("engine") ? vj.get("engine").getAsString()
                                        : "vosk";
                                ac.addTranscript(
                                        new com.fadcam.ui.faditor.transcript.NamedTranscript(
                                                id, label, engine, tr));
                            }
                            if (acObj.has("activeTranscript")) {
                                ac.setActiveTranscriptIndex(
                                        acObj.get("activeTranscript").getAsInt());
                            }
                        }
                        if (acObj.has("captionsEnabled")) {
                            ac.setCaptionsEnabled(
                                    acObj.get("captionsEnabled").getAsBoolean());
                        }
                        if (acObj.has("captionStyleId")) {
                            ac.setCaptionStyleId(
                                    acObj.get("captionStyleId").getAsString());
                        }
                        if (acObj.has("captionCenterX") && acObj.has("captionCenterY")) {
                            ac.setCaptionCenter(
                                    acObj.get("captionCenterX").getAsFloat(),
                                    acObj.get("captionCenterY").getAsFloat());
                        }
                        project.getTimeline().addAudioClip(ac);
                    }
                }
            }

            // Restore text overlays
            if (obj.has("timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (tl.has("textOverlays")) {
                    JsonArray ovArr = tl.getAsJsonArray("textOverlays");
                    for (int i = 0; i < ovArr.size(); i++) {
                        JsonObject oObj = ovArr.get(i).getAsJsonObject();
                        com.fadcam.ui.faditor.model.TextOverlayItem o =
                                new com.fadcam.ui.faditor.model.TextOverlayItem(
                                        oObj.get("id").getAsString(),
                                        oObj.get("text").getAsString(),
                                        oObj.get("colorInt").getAsInt(),
                                        oObj.get("centerX").getAsFloat(),
                                        oObj.get("centerY").getAsFloat(),
                                        oObj.get("sizeFraction").getAsFloat(),
                                        oObj.has("rotationDeg")
                                                ? oObj.get("rotationDeg").getAsFloat() : 0f);
                        if (oObj.has("fontFamily")) {
                            o.setFontFamily(oObj.get("fontFamily").getAsString());
                        }
                        if (oObj.has("imageUri")) {
                            o.setImageUri(fromStorageUri(projectDir,
                                    oObj.get("imageUri").getAsString()).toString());
                        }
                        long startMs = oObj.has("startMs") ? oObj.get("startMs").getAsLong() : 0;
                        long endMs = oObj.has("endMs")
                                ? oObj.get("endMs").getAsLong() : Long.MAX_VALUE;
                        o.setTimeRange(startMs, endMs);
                        if (oObj.has("strokeColorInt")) o.setStrokeColorInt(oObj.get("strokeColorInt").getAsInt());
                        if (oObj.has("strokeWidthPx")) o.setStrokeWidthPx(oObj.get("strokeWidthPx").getAsFloat());
                        if (oObj.has("shadowColorInt")) o.setShadowColorInt(oObj.get("shadowColorInt").getAsInt());
                        if (oObj.has("shadowRadiusPx")) o.setShadowRadiusPx(oObj.get("shadowRadiusPx").getAsFloat());
                        if (oObj.has("glowColorInt")) o.setGlowColorInt(oObj.get("glowColorInt").getAsInt());
                        if (oObj.has("glowRadiusPx")) o.setGlowRadiusPx(oObj.get("glowRadiusPx").getAsFloat());
                        if (oObj.has("backgroundColorInt")) o.setBackgroundColorInt(oObj.get("backgroundColorInt").getAsInt());
                        if (oObj.has("opacity")) o.setOpacity(oObj.get("opacity").getAsFloat());
                        if (oObj.has("keyframes")) {
                            JsonObject tracksJson = oObj.getAsJsonObject("keyframes");
                            for (java.util.Map.Entry<String, JsonElement> e
                                    : tracksJson.entrySet()) {
                                com.fadcam.ui.faditor.keyframe.KeyframeTrack tr =
                                        o.getKeyframes().getOrCreate(e.getKey());
                                JsonArray kfArr = e.getValue().getAsJsonArray();
                                for (int k = 0; k < kfArr.size(); k++) {
                                    JsonObject kj = kfArr.get(k).getAsJsonObject();
                                    tr.put(kj.get("t").getAsLong(), kj.get("v").getAsFloat(),
                                            com.fadcam.ui.faditor.keyframe.Easing.fromName(
                                                    kj.get("e").getAsString()));
                                }
                            }
                        }
                        project.getTimeline().addTextOverlay(o);
                    }
                }
            }

            // Restore waveform visualizer overlays (schema v7)
            if (obj.has("timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (tl.has("waveformOverlays")) {
                    JsonArray wfArr = tl.getAsJsonArray("waveformOverlays");
                    for (int i = 0; i < wfArr.size(); i++) {
                        JsonObject wj = wfArr.get(i).getAsJsonObject();
                        String id = wj.has("id") ? wj.get("id").getAsString()
                                : java.util.UUID.randomUUID().toString();
                        String styleId = wj.has("styleId") ? wj.get("styleId").getAsString() : "neon_bars";
                        com.fadcam.ui.faditor.model.WaveformOverlayInstance wo =
                                new com.fadcam.ui.faditor.model.WaveformOverlayInstance(id, styleId);
                        if (wj.has("audioSourceRef")) {
                            wo.setAudioSourceRef(wj.get("audioSourceRef").getAsString());
                        }
                        long startMs = wj.has("startMs") ? wj.get("startMs").getAsLong() : 0;
                        long endMs = wj.has("endMs") ? wj.get("endMs").getAsLong() : Long.MAX_VALUE;
                        wo.setTimeRange(startMs, endMs);
                        if (wj.has("centerX") && wj.has("centerY")) {
                            wo.setCenter(wj.get("centerX").getAsFloat(), wj.get("centerY").getAsFloat());
                        }
                        if (wj.has("widthFraction") && wj.has("heightFraction")) {
                            wo.setSize(wj.get("widthFraction").getAsFloat(),
                                    wj.get("heightFraction").getAsFloat());
                        }
                        if (wj.has("rotationDeg")) {
                            wo.setRotationDeg(wj.get("rotationDeg").getAsFloat());
                        }
                        if (wj.has("justify")) wo.setJustify(wj.get("justify").getAsInt());
                        if (wj.has("dataMode")) wo.setDataMode(wj.get("dataMode").getAsInt());
                        if (wj.has("hMirror")) wo.setHorizontalMirror(wj.get("hMirror").getAsBoolean());
                        if (wj.has("centerMode")) wo.setCenterMode(wj.get("centerMode").getAsInt());
                        if (wj.has("renderMode")) wo.setRenderMode(wj.get("renderMode").getAsInt());
                        if (wj.has("radialRingSize")) wo.setRadialRingSize(wj.get("radialRingSize").getAsFloat());
                        if (wj.has("freqLowHz")) wo.setFrequencyRangeLowHz(wj.get("freqLowHz").getAsInt());
                        if (wj.has("freqHighHz")) wo.setFrequencyRangeHighHz(wj.get("freqHighHz").getAsInt());
                        if (wj.has("bandCount")) wo.setBandCountOverride(wj.get("bandCount").getAsInt());
                        if (wj.has("colorOverride")) wo.setColorOverride(wj.get("colorOverride").getAsString());
                        if (wj.has("sensitivity")) wo.setSensitivityOverride(wj.get("sensitivity").getAsFloat());
                        if (wj.has("gradStart") && wj.has("gradEnd")) {
                            wo.setGradientOverride(wj.get("gradStart").getAsString(),
                                    wj.get("gradEnd").getAsString());
                        }
                        project.getTimeline().addWaveformOverlay(wo);
                    }
                }
            }

            // Restore transitions
            if (obj.has("timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (tl.has("transitions")) {
                    JsonArray transArr = tl.getAsJsonArray("transitions");
                    for (int i = 0; i < transArr.size(); i++) {
                        JsonObject tj = transArr.get(i).getAsJsonObject();
                        try {
                            com.fadcam.ui.faditor.model.Transition.Type type =
                                    com.fadcam.ui.faditor.model.Transition.Type.valueOf(
                                            tj.get("type").getAsString());
                            long dur = tj.has("durationMs") ? tj.get("durationMs").getAsLong() : 500;
                            int clipIdx = tj.has("clipIndex") ? tj.get("clipIndex").getAsInt() : 0;
                            float fuzz = tj.has("fuzziness") ? tj.get("fuzziness").getAsFloat() : 0f;
                            com.fadcam.ui.faditor.model.Transition transition =
                                    new com.fadcam.ui.faditor.model.Transition(type, dur, clipIdx, fuzz);
                            if (type == com.fadcam.ui.faditor.model.Transition.Type.GL_SHADER) {
                                transition.glTransitionId = tj.has("glTransitionId")
                                        ? tj.get("glTransitionId").getAsString() : "CrossZoom";
                                if (tj.has("paramOverrides")) {
                                    JsonObject params = tj.getAsJsonObject("paramOverrides");
                                    java.util.Map<String, Float> overrides = new java.util.HashMap<>();
                                    for (java.util.Map.Entry<String, JsonElement> e : params.entrySet()) {
                                        overrides.put(e.getKey(), e.getValue().getAsFloat());
                                    }
                                    transition.paramOverrides = overrides;
                                }
                            }
                            project.getTimeline().addTransition(transition);
                        } catch (Exception ignored) { }
                    }
                }
            }

            // Restore schema-v8 layer block (PLAN Part 2 + §4.2). Additive, .has()-guarded.
            // The Track model is a synchronized view rebuilt from the flat lists on access
            // (see Timeline), so the only field with a persistent home to restore here is
            // rippleMode. The masterTrack/layers/audioTracks arrays are the dual-write
            // mirror of the flat lists (payloads referenced by id) — they are round-tripped
            // by the flat-list restore above and need no separate reconstruction in M5.
            if (obj.has("timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (tl.has("rippleMode")) {
                    project.getTimeline().setRippleMode(tl.get("rippleMode").getAsString());
                }
            }

            // Restore canvas preset
            if (obj.has("canvasPreset")) {
                project.setCanvasPreset(obj.get("canvasPreset").getAsString());
            }

            // Restore pinned asset directory (schema v3+, safe defaults for older)
            if (obj.has("pinnedAssetDir")) {
                project.setPinnedAssetDir(obj.get("pinnedAssetDir").getAsString());
            }
            if (obj.has("assetDirHistory")) {
                for (JsonElement e : obj.getAsJsonArray("assetDirHistory")) {
                    String s = e.getAsString();
                    if (!project.getAssetDirHistory().contains(s)) {
                        project.getAssetDirHistory().add(s);
                    }
                }
            }

            // Restore export settings
            if (obj.has("exportSettings")) {
                JsonObject expObj = obj.getAsJsonObject("exportSettings");
                ExportSettings settings = project.getExportSettings();
                if (expObj.has("resolution")) {
                    try {
                        settings.setResolution(
                                ExportSettings.Resolution.valueOf(
                                        expObj.get("resolution").getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (expObj.has("quality")) {
                    try {
                        settings.setQuality(
                                ExportSettings.Quality.valueOf(
                                        expObj.get("quality").getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (expObj.has("format")) {
                    try {
                        settings.setFormat(
                                ExportSettings.Format.valueOf(
                                        expObj.get("format").getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (expObj.has("cleanAudio")) {
                    settings.setCleanAudio(expObj.get("cleanAudio").getAsBoolean());
                }
            }

            return project;
        }
    }

    // ── Project summary (lightweight) ────────────────────────────────

    /**
     * Lightweight summary of a saved project for list display.
     * Avoids loading full project data.
     */
    public static class ProjectSummary {

        @NonNull
        public final String id;

        @NonNull
        public final String name;

        public final long createdAt;

        public final long lastModified;

        /** First clip's video URI string (can be null). */
        @Nullable
        public final String videoUri;

        public ProjectSummary(@NonNull String id, @NonNull String name,
                              long createdAt, long lastModified,
                              @Nullable String videoUri) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.lastModified = lastModified;
            this.videoUri = videoUri;
        }
    }
}
