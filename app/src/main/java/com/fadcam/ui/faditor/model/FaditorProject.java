package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Top-level project wrapper for the Faditor editor.
 * Contains a {@link Timeline} and metadata.
 *
 * <p>For Phase 1 (MVP) a project is created on-the-fly from a video pick.
 * Phase 4 adds persistence via JSON serialization.</p>
 */
public class FaditorProject {

    /** Current project JSON schema version. Increment when the format changes. */
    public static final int SCHEMA_VERSION = 7;

    /**
     * URI scheme used in saved project JSON for assets that live inside the project
     * directory, stored as a path relative to the project root (e.g.
     * {@code project://assets/<uuid>.png}). Resolved to an absolute {@code file://}
     * URI at load time. This makes a project a self-contained, movable bundle
     * (project.json + assets/) that survives reinstall and travels to another device,
     * since absolute {@code content://}/{@code file://} URIs break off-device.
     */
    public static final String PROJECT_URI_SCHEME = "project";

    @NonNull
    private final String id;

    @NonNull
    private String name;

    private final long createdAt;

    private long lastModified;

    @NonNull
    private final Timeline timeline;

    @NonNull
    private final ExportSettings exportSettings;

    /** Schema version of this project's JSON. Set on creation, checked on load. */
    private int schemaVersion = SCHEMA_VERSION;

    /** Canvas aspect ratio preset (project-level). "original" = no change. */
    @NonNull
    private String canvasPreset = "original";

    /**
     * Pinned asset directory tree URI (SAF content:// URI as string).
     * Null when no directory is pinned. Saved with the project so AI and
     * the asset browser can enumerate files in this directory.
     */
    @Nullable
    private String pinnedAssetDir;

    /**
     * History of previously pinned asset directories (SAF tree URI strings).
     * Users can navigate back/forward through these. The current
     * {@link #pinnedAssetDir} is always the last entry when non-null.
     */
    @NonNull
    private final List<String> assetDirHistory = new ArrayList<>();

    /**
     * Create a new empty project.
     *
     * @param name display name for the project
     */
    public FaditorProject(@NonNull String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.createdAt = System.currentTimeMillis();
        this.lastModified = this.createdAt;
        this.timeline = new Timeline();
        this.exportSettings = new ExportSettings();
    }

    /**
     * Restore a project from persisted data.
     * Used by {@code ProjectStorage} deserialization.
     *
     * @param id           the original project ID
     * @param name         display name
     * @param createdAt    original creation timestamp
     * @param lastModified last modification timestamp
     */
    public FaditorProject(@NonNull String id, @NonNull String name,
                          long createdAt, long lastModified) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.lastModified = lastModified;
        this.timeline = new Timeline();
        this.exportSettings = new ExportSettings();
    }

    // ── Getters ──────────────────────────────────────────────────────

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastModified() {
        return lastModified;
    }

    @NonNull
    public Timeline getTimeline() {
        return timeline;
    }

    @NonNull
    public ExportSettings getExportSettings() {
        return exportSettings;
    }

    @NonNull
    public String getCanvasPreset() {
        return canvasPreset;
    }

    @Nullable
    public String getPinnedAssetDir() {
        return pinnedAssetDir;
    }

    @NonNull
    public List<String> getAssetDirHistory() {
        return assetDirHistory;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int version) {
        this.schemaVersion = version;
    }

    // ── Setters ──────────────────────────────────────────────────────

    public void setCanvasPreset(@NonNull String canvasPreset) {
        this.canvasPreset = canvasPreset;
        touch();
    }

    /**
     * Set the pinned asset directory. Adds the previous value to history
     * (if different). Pass null to unpin.
     */
    public void setPinnedAssetDir(@Nullable String dir) {
        if (dir == null) {
            this.pinnedAssetDir = null;
            touch();
            return;
        }
        // Avoid duplicate consecutive entries
        if (!dir.equals(this.pinnedAssetDir)) {
            if (this.pinnedAssetDir != null && !assetDirHistory.contains(this.pinnedAssetDir)) {
                assetDirHistory.add(this.pinnedAssetDir);
            }
            this.pinnedAssetDir = dir;
            // Ensure current dir is also last in history for navigation
            assetDirHistory.remove(dir);
            assetDirHistory.add(dir);
        }
        touch();
    }

    public void removeAssetDirFromHistory(@NonNull String dir) {
        while (assetDirHistory.remove(dir)) { }
        if (dir.equals(this.pinnedAssetDir)) {
            this.pinnedAssetDir = assetDirHistory.isEmpty() ? null : assetDirHistory.get(assetDirHistory.size() - 1);
        }
        touch();
    }

    public void setName(@NonNull String name) {
        this.name = name;
        touch();
    }

    /**
     * Update the lastModified timestamp. Call after any edit operation.
     */
    public void touch() {
        this.lastModified = System.currentTimeMillis();
    }

    @NonNull
    @Override
    public String toString() {
        return "FaditorProject{id=" + id
                + ", name=" + name
                + ", clips=" + timeline.getClipCount()
                + "}";
    }
}
