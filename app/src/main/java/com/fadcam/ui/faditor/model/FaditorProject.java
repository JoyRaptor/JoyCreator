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

    /**
     * Current project JSON schema version. Increment when the format changes.
     * <p>v8 adds the additive layer/Track model (PLAN Part 2). It is written with
     * DUAL-WRITE (PLAN §4.1): the v7 flat lists are still written byte-compatibly,
     * and {@code schemaVersion} is only stamped 8 when the project genuinely uses a
     * layer feature an old build can't represent (see {@code ProjectStorage.usesLayerFeatures}).</p>
     * <p>v9 adds sprite sheets + placed sprite overlays (PLAN_SPRITE_ANIMATION S1),
     * same dual-write discipline: additive blocks always written, {@code schemaVersion}
     * stamped 9 only when the project actually contains sprite data.</p>
     * <p>v10 adds avatar rigs (PLAN_AVATAR_STUDIO A1), stamped 10 only when rigs exist.</p>
     * <p>v11 covers the NEUTRAL {@code TrackKind.LAYER} lane (SPEC_NEUTRAL_SUBSTRATE),
     * stamped 11 only when the project actually holds a LAYER-kind {@code LayerTrackDef}.
     * The spec declared this storage-free on the strength of {@code TrackKind.fromName}'s
     * VIDEO fallback; it is not. Without a stamp the downgrade guard below never fires for
     * such a project, so a v10 build coerces {@code kind=LAYER} to {@code VIDEO} and
     * re-serializes it that way — permanently moving the lane's band position, i.e. its
     * paint order (audit 1.2; reproduced in {@code tasks/schema_layer_stamp.py}). Which
     * kinds force which version lives on {@code TrackKind.minSchemaVersion()}.</p>
     */
    public static final int SCHEMA_VERSION = 11;

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

    /** Sprite-sheet definitions owned by this project (schema v9). Never null. */
    @NonNull
    private final java.util.List<com.fadcam.ui.faditor.sprite.SpriteSheet> spriteSheets =
            new java.util.ArrayList<>();

    /** Avatar rigs owned by this project (schema v10, PLAN_AVATAR_STUDIO A1). Never null. */
    @NonNull
    private final java.util.List<com.fadcam.ui.faditor.avatar.AvatarRig> avatarRigs =
            new java.util.ArrayList<>();

    /** Schema version of this project's JSON. Set on creation, checked on load. */
    private int schemaVersion = SCHEMA_VERSION;

    /**
     * Downgrade guard (PLAN §4.1(2)). Set true when this project was loaded from a
     * file whose on-disk {@code schemaVersion} is NEWER than this build's
     * {@link #SCHEMA_VERSION}. When true, the save path refuses to overwrite the file
     * (which would silently drop the unknown newer data). Not persisted — it is a
     * per-load runtime marker.
     */
    private transient boolean loadedFromNewerVersion = false;

    /**
     * Concurrent-instance guard (Stage 1 P0 fix, TrackFlags desync). Tracks the
     * on-disk {@code lastModified} value this in-memory copy last confirmed matches
     * the file (set on load, and again after every successful save). {@code
     * FaditorEditorActivity} has no {@code launchMode} restriction, so nothing stops
     * two instances for the SAME project id existing at once (e.g. opening the same
     * recent project twice, or from two different entry points). Each instance keeps
     * its OWN in-memory {@code Timeline}/{@code TrackFlags}; if instance A edits and
     * saves, then instance B (backgrounded, still holding its now-stale copy from
     * BEFORE A's edit) is later paused/finished, B's unconditional {@code onPause()}
     * save would blindly overwrite A's newer file with its stale copy — silently
     * reverting whatever A changed (e.g. a lock/unlock toggle) without any visible
     * error. {@code ProjectStorage.save/saveAsync} compares the file's CURRENT
     * {@code lastModified} against this field immediately before writing: if the file
     * is newer than what this copy last confirmed, someone else has saved since, so
     * this save is refused rather than clobbering (mirrors the {@link
     * #loadedFromNewerVersion} guard's shape). Not persisted — a per-load runtime
     * marker like that guard.
     */
    private transient long diskLastModifiedAtLastSync = -1L;

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
     * Timeline bookmarks (absolute timeline ms), sorted ascending. KineMaster-class
     * playhead lane (JoyRaptor 2026-07-19): droppable ruler markers.
     *
     * <p>NOTE (persistence): the manual {@code ProjectSerializer}/{@code
     * ProjectDeserializer} in the held {@code ProjectStorage} does not yet write this
     * field, so it does NOT round-trip through project.json. Persistence is handled by
     * the sidecar {@code BookmarkStore} (&lt;projectDir&gt;/bookmarks.json) until this
     * can be folded into the project.json path. See
     * tasks/PLAYHEAD_KINEMASTER_20260719.md.</p>
     */
    @NonNull
    private final List<Long> bookmarksMs = new ArrayList<>();

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

    /** Sprite-sheet definitions (schema v9) — live list, mutate directly. */
    @NonNull
    public java.util.List<com.fadcam.ui.faditor.sprite.SpriteSheet> getSpriteSheets() {
        return spriteSheets;
    }

    /** Sheet lookup by id, or null. */
    @androidx.annotation.Nullable
    public com.fadcam.ui.faditor.sprite.SpriteSheet spriteSheetById(@androidx.annotation.Nullable String id) {
        if (id == null) return null;
        for (com.fadcam.ui.faditor.sprite.SpriteSheet s : spriteSheets) {
            if (s.getId().equals(id)) return s;
        }
        return null;
    }

    /** Avatar rigs (schema v10) — live list, mutate directly. */
    @NonNull
    public java.util.List<com.fadcam.ui.faditor.avatar.AvatarRig> getAvatarRigs() {
        return avatarRigs;
    }

    /** Rig lookup by id, or null. */
    @androidx.annotation.Nullable
    public com.fadcam.ui.faditor.avatar.AvatarRig avatarRigById(@androidx.annotation.Nullable String id) {
        if (id == null) return null;
        for (com.fadcam.ui.faditor.avatar.AvatarRig r : avatarRigs) {
            if (r.getId().equals(id)) return r;
        }
        return null;
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

    /** Timeline bookmarks (absolute timeline ms), sorted ascending — live list. */
    @NonNull
    public List<Long> getBookmarksMs() {
        return bookmarksMs;
    }

    /** Replace all bookmarks with {@code marks} (copied + sorted). */
    public void setBookmarksMs(@Nullable List<Long> marks) {
        bookmarksMs.clear();
        if (marks != null) bookmarksMs.addAll(marks);
        java.util.Collections.sort(bookmarksMs);
        touch();
    }

    /** Add a bookmark at {@code ms} (ignored if one already sits within {@code tolMs}). */
    public void addBookmark(long ms, long tolMs) {
        for (long b : bookmarksMs) {
            if (Math.abs(b - ms) <= tolMs) return;
        }
        bookmarksMs.add(ms);
        java.util.Collections.sort(bookmarksMs);
        touch();
    }

    /** Remove the nearest bookmark within {@code tolMs} of {@code ms}; true if one went. */
    public boolean removeBookmarkNear(long ms, long tolMs) {
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < bookmarksMs.size(); i++) {
            long d = Math.abs(bookmarksMs.get(i) - ms);
            if (d <= tolMs && d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        if (best >= 0) {
            bookmarksMs.remove(best);
            touch();
            return true;
        }
        return false;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int version) {
        this.schemaVersion = version;
    }

    /**
     * Whether this project was loaded from a file written by a NEWER build (on-disk
     * schemaVersion &gt; this build's {@link #SCHEMA_VERSION}). When true the project
     * is read-only: {@code ProjectStorage.save/saveAsync} refuse to overwrite it to
     * avoid a lossy downgrade (PLAN §4.1(2)).
     */
    public boolean isLoadedFromNewerVersion() {
        return loadedFromNewerVersion;
    }

    public void setLoadedFromNewerVersion(boolean value) {
        this.loadedFromNewerVersion = value;
    }

    /**
     * The on-disk {@code lastModified} value this copy last confirmed matches the
     * file (see {@link #diskLastModifiedAtLastSync}'s doc), or {@code -1} if never
     * synced (e.g. a brand-new project that has not yet been saved once).
     */
    public long getDiskLastModifiedAtLastSync() {
        return diskLastModifiedAtLastSync;
    }

    /** Record that this copy is now known to match the file as of {@code diskLastModified}. */
    public void setDiskLastModifiedAtLastSync(long diskLastModified) {
        this.diskLastModifiedAtLastSync = diskLastModified;
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
