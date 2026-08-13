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
import com.fadcam.ui.faditor.model.StyleSpan;
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
        // Concurrent-instance guard (Stage 1 P0 fix) — see FaditorProject#diskLastModifiedAtLastSync.
        mergeTrackFlagsIfStale(project);
        // Stage 1 P0 fix: every call site only ever calls save()/saveAsync() right after
        // a real edit (the existing convention throughout this codebase), but most edits
        // — including every M6 track-flags toggle — mutate Timeline directly and never
        // call FaditorProject#touch(). Without this, lastModified stays frozen at
        // load/creation time for the whole session, which breaks the freshness signal
        // mergeTrackFlagsIfStale (and the "recent projects" sort) depend on. Bumping it
        // here, once, right before every write, guarantees it always reflects "the
        // moment this save happened" regardless of what the caller touched.
        project.touch();
        String json = gson.toJson(project);
        boolean ok = writeProjectJson(project.getId(), json);
        if (ok) project.setDiskLastModifiedAtLastSync(project.getLastModified());
        return ok;
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
        // Concurrent-instance guard (Stage 1 P0 fix) — see FaditorProject#diskLastModifiedAtLastSync.
        // Checked/merged on the CALLER thread (cheap — peeks one field, and only reads
        // the rest of the file when that field actually indicates staleness) so a
        // stale instance's queued write already carries the merged flags.
        mergeTrackFlagsIfStale(project);
        project.touch(); // Stage 1 P0 fix — see the comment in save() above.
        final String id = project.getId();
        final long syncedLastModified = project.getLastModified();
        final String json = gson.toJson(project);   // serialize on caller thread (no CME)
        lastWrite = ioExecutor.submit(() -> {
            if (writeProjectJson(id, json)) {
                project.setDiskLastModifiedAtLastSync(syncedLastModified);
            }
        });
    }

    /**
     * Concurrent-instance guard (Stage 1 P0 fix: "shows unlocked, acts locked").
     *
     * <p>{@code FaditorEditorActivity} has no {@code launchMode} restriction, so two
     * instances can hold separate in-memory copies of the SAME project id (e.g. the
     * same recent project opened twice from different entry points, one left
     * backgrounded — {@code onPause()} unconditionally saves). Each edits its own
     * copy; whichever instance's save runs LAST would normally win the file
     * unconditionally — including a stale copy from BEFORE the other instance's
     * edits, silently reverting them. This is how a lock/unlock toggle done in one
     * instance could be undone by another, days-old, backgrounded instance finally
     * pausing/finishing (the user sees the icon they just set, but the file — and
     * the next reload — reflects the older instance's flags).</p>
     *
     * <p>Rather than refuse the save outright (which would leave a legitimately-
     * still-in-use "stale" instance permanently unable to save with no recovery),
     * this merges forward JUST the {@code trackFlags} side-table — the exact data
     * class that desyncs — from the fresher on-disk copy into {@code project}'s
     * {@link Timeline} before serializing. Every other field in {@code project}
     * (clips, overlays, audio, the user's actual in-progress edit) is untouched.</p>
     *
     * <p><b>Per-track, baseline-aware merge</b> (see {@link
     * Timeline#trackFlagsChangedSinceLoad}): for each track id that appears in
     * EITHER this instance's current flags or the fresher on-disk copy's flags,
     * take {@code theirs} (disk) UNLESS this instance's flags for that exact id
     * have changed since ITS OWN load — i.e. this instance genuinely edited that
     * track this session, in which case this instance's value wins (a same-track
     * conflict — both instances touched the identical id — still resolves to
     * whichever save physically lands last, same as today's baseline). Every track
     * NEITHER instance touched, or only the OTHER instance touched, correctly picks
     * up the fresher copy's value — including a revert to default (an id present
     * in {@code mine} but absent from {@code theirs} is cleared, not just left
     * alone), which an earlier gap-fill-only version of this guard got wrong: it
     * could not tell "the other instance reverted this to default" apart from
     * "this instance's own brand-new edit hasn't reached disk yet" just from map
     * presence, and ended up discarding a same-session edit made moments earlier
     * on an UNRELATED track id purely because that id happened to already have an
     * incidental entry. Comparing against the per-Timeline load-time baseline
     * removes that ambiguity without needing a full vector-clock.</p>
     */
    private void mergeTrackFlagsIfStale(@NonNull FaditorProject project) {
        long syncedAt = project.getDiskLastModifiedAtLastSync();
        if (syncedAt < 0) return; // never synced yet (new project) — nothing to merge against
        Long diskLastModified = peekDiskLastModified(project.getId());
        if (diskLastModified == null || diskLastModified <= syncedAt) return; // not stale
        FaditorProject onDisk = load(project.getId());
        if (onDisk == null) return; // inconclusive — leave project untouched
        Timeline mine = project.getTimeline();
        Timeline theirs = onDisk.getTimeline();
        java.util.Set<String> allIds = new java.util.LinkedHashSet<>();
        allIds.addAll(mine.getAllTrackFlags().keySet());
        allIds.addAll(theirs.getAllTrackFlags().keySet());
        int merged = 0;
        for (String trackId : allIds) {
            if (mine.trackFlagsChangedSinceLoad(trackId)) continue; // this session's own edit wins
            com.fadcam.ui.faditor.layers.TrackFlags theirValue = theirs.getAllTrackFlags().get(trackId);
            mine.setTrackFlags(trackId, theirValue != null ? theirValue.copy() : null);
            merged++;
        }
        if (merged > 0) {
            FLog.w(TAG, "mergeTrackFlagsIfStale: project '" + project.getName()
                    + "' has a newer copy on disk (saved by another instance) — pulled forward "
                    + merged + " track-flag entry/entries this instance hadn't itself edited.");
        }
    }

    /**
     * Cheaply read just the {@code lastModified} field from the current on-disk
     * project.json, without deserializing the whole project. Returns {@code null}
     * if the file doesn't exist or can't be parsed (treated as "not stale" by the
     * caller — this guard only ever acts on POSITIVE evidence of a newer file,
     * never on an inconclusive read, so it can't touch data on an ambiguous read).
     */
    @Nullable
    private Long peekDiskLastModified(@NonNull String projectId) {
        File file = new File(getProjectDir(projectId), PROJECT_FILE);
        if (!file.exists()) return null;
        try (FileReader reader = new FileReader(file)) {
            JsonObject json = gson.fromJson(reader, JsonObject.class);
            if (json != null && hasValue(json, "lastModified")) {
                return json.get("lastModified").getAsLong();
            }
        } catch (Exception e) {
            FLog.w(TAG, "peekDiskLastModified: failed to read " + projectId, e);
        }
        return null;
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
                    // Concurrent-instance guard (Stage 1 P0 fix): this copy is now known
                    // to match the file exactly — record its lastModified as the sync
                    // point so a LATER save from this same in-memory copy can detect if
                    // some OTHER instance has since saved something newer.
                    p.setDiskLastModifiedAtLastSync(p.getLastModified());
                    // One-time transcript-duplicate migration (backup-first; see method doc).
                    dedupTranscriptsWithBackup(p, file);
                    // Then collapse per-clip forks onto one shared transcript per id, so an
                    // edit made from any clip is an edit everywhere (backup-first too).
                    shareTranscriptsWithBackup(p, file);
                    healDanglingAnchors(p);
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
                    // Same sync-point bookkeeping as the main-file path above.
                    p.setDiskLastModifiedAtLastSync(p.getLastModified());
                    // Same duplicate migration as the main-file path (backs up the
                    // .bak we actually loaded from).
                    dedupTranscriptsWithBackup(p, bak);
                    healDanglingAnchors(p);
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
     * Whether a {@code project.json.bak} exists for this project — i.e. whether the
     * "open the last backup instead" choice can be offered when {@link #load} reports
     * skipped items (load-failure SHAPE fix).
     */
    public boolean hasBackup(@NonNull String projectId) {
        return new File(getProjectDir(projectId), PROJECT_FILE + ".bak").exists();
    }

    /**
     * Load ONLY the {@code project.json.bak} backup, ignoring the (possibly
     * partly-malformed) main file. Used when the user, told that {@link #load} had to
     * skip some items, explicitly chooses "open the last backup instead". Returns
     * {@code null} if there is no readable backup. The same per-item tolerance and
     * transcript migrations that {@link #load} applies to the main file apply here, so
     * the backup itself is served as-good-as-possible rather than all-or-nothing.
     */
    @Nullable
    /**
     * Re-home rider anchors that point at clips this project does not contain — the same shape as
     * {@code pruneOrphanedTrackFlags}, for {@code hostClipId}. See
     * {@link com.fadcam.ui.faditor.model.Timeline#healDanglingHostAnchors()} for why a dangling
     * anchor is not cosmetic: such a rider is read as an orphan and therefore never ripples again.
     *
     * <p>Called from the LOAD paths only, deliberately NOT from the shared deserializer, which also
     * runs on every undo/redo snapshot restore. A restore is not the moment to repair anything: the
     * state being restored is the truth, and an orphan that is still awaiting §4A's re-anchor-or-
     * delete answer would have that question silently answered for it.</p>
     */
    private void healDanglingAnchors(@NonNull FaditorProject p) {
        if (p.getTimeline() == null) return;
        List<String> healed = p.getTimeline().healDanglingHostAnchors();
        if (!healed.isEmpty()) {
            FLog.w(TAG, "Re-homed " + healed.size()
                    + " dangling host anchor(s) on load: " + healed);
        }
    }

    public FaditorProject loadBackupOnly(@NonNull String projectId) {
        File bak = new File(getProjectDir(projectId), PROJECT_FILE + ".bak");
        if (!bak.exists()) {
            FLog.w(TAG, "loadBackupOnly: no backup for " + projectId);
            return null;
        }
        try (FileReader reader = new FileReader(bak)) {
            FaditorProject p = gson.fromJson(reader, FaditorProject.class);
            if (p != null && p.getTimeline() != null) {
                p.setDiskLastModifiedAtLastSync(p.getLastModified());
                dedupTranscriptsWithBackup(p, bak);
                shareTranscriptsWithBackup(p, bak);
                healDanglingAnchors(p);
                FLog.i(TAG, "loadBackupOnly: opened backup for " + projectId
                        + " (skips=" + p.getLoadSkips().size() + ")");
                return p;
            }
        } catch (Exception e) {
            FLog.e(TAG, "loadBackupOnly: backup unreadable for " + projectId, e);
        }
        return null;
    }

    /**
     * One-time (idempotent) migration: strip accumulated duplicate transcript
     * versions from a freshly loaded project — see {@link
     * com.fadcam.ui.faditor.transcript.TranscriptDedup} for the exact keep/remove
     * rule (live version untouched by identity, user-edited versions never
     * removed).
     *
     * <p><b>Backup-first, verified:</b> nothing is mutated until a timestamped
     * byte-copy of the exact file we loaded from exists under
     * {@code <projectDir>/backups/project-yyyyMMdd-HHmmss.json} and is non-empty
     * and size-identical to the source. If the backup can't be verified the
     * project is returned exactly as parsed (no dedup).</p>
     *
     * <p><b>Undo safety:</b> this runs inside {@link #load} — before the editor
     * creates its UndoManager or loads undo history, so no in-session snapshot
     * can predate it. Persisted undo snapshots from OLD sessions may still
     * contain the duplicates, but every snapshot restore goes through
     * {@link #fromJson}, which applies the same (memory-only) dedup — so an
     * undo can never resurrect the duplicates.</p>
     *
     * <p>After a verified backup the deduped JSON is written straight back via
     * {@link #writeProjectJson} — deliberately NOT {@link #save}: no
     * {@code touch()} (lastModified stays as loaded, so the recent-projects
     * order and the concurrent-instance staleness signal are unaffected) and no
     * re-entrant merge machinery. Downgrade guard respected: projects written
     * by a newer app version are never touched.</p>
     */
    /**
     * One-time (idempotent) migration: collapse per-clip transcript FORKS onto one shared
     * instance per id, reconstructing legacy partitions on the way — see {@link
     * com.fadcam.ui.faditor.transcript.TranscriptSharing} for the merge rule and the two
     * fork shapes it has to tell apart.
     *
     * <p>Backup-first and verified, exactly like {@link #dedupTranscriptsWithBackup}:
     * nothing is mutated until a byte-copy of the file we loaded exists and is
     * size-identical. This one earns that caution — an earlier first-wins version of this
     * migration would have replaced a 1112-word transcript with a 1110-word one on the
     * reporter's live project.</p>
     */
    private void shareTranscriptsWithBackup(@NonNull FaditorProject p, @NonNull File sourceFile) {
        try {
            if (p.isLoadedFromNewerVersion()) return;
            if (com.fadcam.ui.faditor.transcript.TranscriptSharing.countForks(p) <= 0) return;

            // Sharing is an IN-MEMORY collapse, and each clip still serialises its own copy
            // (the file-level dedup is a schema change, deliberately deferred). So forks
            // reappear on every load and this runs every time. Only the RECONSTRUCTION of
            // legacy partitions actually changes the data and is worth a backup + rewrite;
            // a pure re-collapse must not, or opening a project would write a fresh
            // multi-megabyte backup each time. Measured: two 5.3MB backups from a single app
            // launch before this check existed.
            com.fadcam.ui.faditor.transcript.TranscriptSharing.Result dry =
                    com.fadcam.ui.faditor.transcript.TranscriptSharing.shareProject(p);
            if (dry.recovered <= 0) {
                FLog.d(TAG, "transcriptSharing: " + dry + " (in-memory only, nothing to persist)");
                return;
            }

            File backupsDir = new File(sourceFile.getParentFile(), "backups");
            if (!backupsDir.exists() && !backupsDir.mkdirs()) {
                FLog.w(TAG, "transcriptSharing: cannot create backups dir — skipping");
                return;
            }
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            File backup = new File(backupsDir, "project-preshare-" + ts + ".json");
            try (java.io.FileInputStream in = new java.io.FileInputStream(sourceFile);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(backup)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
                out.getFD().sync();
            }
            if (!backup.exists() || backup.length() <= 0
                    || backup.length() != sourceFile.length()) {
                FLog.w(TAG, "transcriptSharing: backup verification FAILED — skipping");
                backup.delete();
                return;
            }

            // Already applied above (idempotent); persist that result.
            com.fadcam.ui.faditor.transcript.TranscriptSharing.Result r = dry;
            String json = gson.toJson(p);
            writeProjectJson(p.getId(), json);
            FLog.i(TAG, "transcriptSharing: " + r + " for project " + p.getId()
                    + "; backup=" + backup.getName());
        } catch (Exception e) {
            FLog.w(TAG, "transcriptSharing failed (project left as loaded)", e);
        }
    }

    private void dedupTranscriptsWithBackup(@NonNull FaditorProject p, @NonNull File sourceFile) {
        try {
            if (p.isLoadedFromNewerVersion()) return;
            int removable = com.fadcam.ui.faditor.transcript.TranscriptDedup.countRemovable(p);
            if (removable <= 0) return;

            // 1) Timestamped backup of the exact bytes we loaded, verified.
            File backupsDir = new File(sourceFile.getParentFile(), "backups");
            if (!backupsDir.exists() && !backupsDir.mkdirs()) {
                FLog.w(TAG, "transcriptDedup: cannot create backups dir — skipping dedup");
                return;
            }
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            File backup = new File(backupsDir, "project-" + ts + ".json");
            try (java.io.FileInputStream in = new java.io.FileInputStream(sourceFile);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(backup)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
                out.getFD().sync();
            }
            if (!backup.exists() || backup.length() <= 0
                    || backup.length() != sourceFile.length()) {
                FLog.w(TAG, "transcriptDedup: backup verification FAILED ("
                        + backup + ") — skipping dedup");
                backup.delete();
                return;
            }

            // 2) Mutate the in-memory model (live/edited versions untouched).
            long beforeBytes = sourceFile.length();
            int removed = com.fadcam.ui.faditor.transcript.TranscriptDedup.dedupProject(p);

            // 3) Persist the slimmed project immediately so the shrink is real
            //    even if the user makes no edit this session.
            String json = gson.toJson(p);
            boolean ok = writeProjectJson(p.getId(), json);
            File after = new File(getProjectDir(p.getId()), PROJECT_FILE);
            FLog.i(TAG, "transcriptDedup: removed " + removed
                    + " duplicate transcript version(s); backup=" + backup.getName()
                    + "; bytes " + beforeBytes + " -> "
                    + (ok ? after.length() : beforeBytes + " (rewrite failed; memory-only)"));
        } catch (Exception e) {
            // Never let the migration break project loading.
            FLog.e(TAG, "transcriptDedup: failed — project loaded un-deduped", e);
        }
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
                String name = hasValue(json, "name") ? json.get("name").getAsString() : "Untitled";
                long lastModified = hasValue(json, "lastModified")
                        ? json.get("lastModified").getAsLong()
                        : file.lastModified();
                long createdAt = json.has("createdAt")
                        ? json.get("createdAt").getAsLong()
                        : lastModified;

                // Get video URI from first clip (for thumbnail in future)
                String videoUri = null;
                if (hasValue(json, "timeline")) {
                    JsonObject timeline = json.getAsJsonObject("timeline");
                    if (hasValue(timeline, "clips")) {
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
            FaditorProject p = gson.fromJson(json, FaditorProject.class);
            // Memory-only duplicate-transcript strip on EVERY snapshot restore:
            // persisted undo snapshots from before the dedup migration still
            // contain the stacked duplicates, and without this an undo would
            // resurrect them. Same conservative rule as the load migration
            // (live version kept by identity, edited versions never removed),
            // so the restored state the user sees is unchanged.
            if (p != null && !p.isLoadedFromNewerVersion()) {
                int removed = com.fadcam.ui.faditor.transcript.TranscriptDedup.dedupProject(p);
                if (removed > 0) {
                    FLog.i(TAG, "fromJson: stripped " + removed
                            + " duplicate transcript version(s) from snapshot");
                }
                // Re-share transcripts across clips, exactly as load() does. Deserialization
                // builds a FRESH NamedTranscript per clip, so a restored snapshot arrives as
                // per-clip forks rather than the one shared instance a clip cut from the same
                // source should hold. Without this, the v12 pool stops "paying" the moment a
                // snapshot is restored (the writer keys on instance identity), so the very
                // next save silently rewrites the project in the old duplicated shape and the
                // undo snapshots grow back with it — measured on the Note 9: 31,945 -> 37,684
                // bytes after a single cross-session undo. Restoring the sharing here keeps
                // the restore semantically identical (TranscriptSharing collapses forks of the
                // SAME id and unions legacy partitions) while keeping the file pooled.
                com.fadcam.ui.faditor.transcript.TranscriptSharing.Result shared =
                        com.fadcam.ui.faditor.transcript.TranscriptSharing.shareProject(p);
                if (shared.changedAnything()) {
                    FLog.d(TAG, "fromJson: re-shared transcripts in snapshot — " + shared);
                }
            }
            return p;
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
        return saveUndoHistory(projectId, descriptions, snapshots, Collections.emptyList());
    }

    /** @param aiFlags parallel to the other lists; marks steps the AI assistant made. */
    public boolean saveUndoHistory(@NonNull String projectId,
                                   @NonNull List<String> descriptions,
                                   @NonNull List<String> snapshots,
                                   @NonNull List<Boolean> aiFlags) {
        return writeUndoHistoryStreaming(projectId, descriptions, snapshots, aiFlags);
    }

    /**
     * Async variant of {@link #saveUndoHistory}: the disk write runs on the
     * background serial executor. Only the list references are copied on the
     * caller thread (snapshot strings are immutable), so the caller pays
     * near-zero cost.
     */
    public void saveUndoHistoryAsync(@NonNull String projectId,
                                     @NonNull List<String> descriptions,
                                     @NonNull List<String> snapshots) {
        saveUndoHistoryAsync(projectId, descriptions, snapshots, Collections.emptyList());
    }

    /** @param aiFlags parallel to the other lists; marks steps the AI assistant made. */
    public void saveUndoHistoryAsync(@NonNull String projectId,
                                     @NonNull List<String> descriptions,
                                     @NonNull List<String> snapshots,
                                     @NonNull List<Boolean> aiFlags) {
        final List<String> descCopy = new ArrayList<>(descriptions);
        final List<String> snapCopy = new ArrayList<>(snapshots);
        final List<Boolean> aiCopy = new ArrayList<>(aiFlags);
        lastWrite = ioExecutor.submit(
                () -> writeUndoHistoryStreaming(projectId, descCopy, snapCopy, aiCopy));
    }

    /**
     * Stream the undo history straight to disk with JsonWriter. The history can
     * total tens (formerly hundreds) of MB of snapshot strings; materializing it
     * as one concatenated String first (the old approach) needed a contiguous
     * allocation of the full serialized size and repeatedly OOM-crashed the app
     * on large projects.
     */
    private boolean writeUndoHistoryStreaming(@NonNull String projectId,
                                              @NonNull List<String> descriptions,
                                              @NonNull List<String> snapshots,
                                              @NonNull List<Boolean> aiFlags) {
        File projectDir = getProjectDir(projectId);
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            FLog.e(TAG, "Failed to create project directory for undo history");
            return false;
        }
        File file = new File(projectDir, UNDO_HISTORY_FILE);
        int count = Math.min(descriptions.size(), snapshots.size());
        try (com.google.gson.stream.JsonWriter writer = new com.google.gson.stream.JsonWriter(
                new java.io.BufferedWriter(new FileWriter(file), 64 * 1024))) {
            writer.beginArray();
            for (int i = 0; i < count; i++) {
                writer.beginObject();
                writer.name("description").value(descriptions.get(i));
                writer.name("snapshot").value(snapshots.get(i));
                // Written only when true: absent means user-authored, which is exactly what
                // every pre-existing sidecar contains.
                if (i < aiFlags.size() && Boolean.TRUE.equals(aiFlags.get(i))) {
                    writer.name("ai").value(true);
                }
                writer.endObject();
            }
            writer.endArray();
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
        return loadUndoHistory(projectId, outDescriptions, outSnapshots, new ArrayList<>());
    }

    /** @param outAiFlags populated parallel to the others; absent "ai" reads as false. */
    public boolean loadUndoHistory(@NonNull String projectId,
                                   @NonNull List<String> outDescriptions,
                                   @NonNull List<String> outSnapshots,
                                   @NonNull List<Boolean> outAiFlags) {
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
                String desc = hasValue(entry, "description")
                        ? entry.get("description").getAsString() : "Unknown";
                String snap = hasValue(entry, "snapshot")
                        ? entry.get("snapshot").getAsString() : null;
                if (snap != null) {
                    outDescriptions.add(desc);
                    outSnapshots.add(snap);
                    outAiFlags.add(hasValue(entry, "ai") && entry.get("ai").getAsBoolean());
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
                    hasValue(wj, "x") && wj.get("x").getAsBoolean(),
                    hasValue(wj, "b") && wj.get("b").getAsBoolean()));
        }
        return tr;
    }

    /**
     * SPEC_TIMER_OBJECT: written ONLY for a timer overlay, so every project that has no
     * timer keeps byte-identical JSON (same discipline as {@code overlayAudioEnabled} and
     * the objHidden/objLocked pair). Enum names are stored, not ordinals — reordering an
     * enum must never silently reinterpret existing projects.
     */
    private static void serializeTimerSpec(JsonObject parentJson,
            com.fadcam.ui.faditor.model.TimerSpec spec) {
        if (spec == null) return;
        JsonObject t = new JsonObject();
        t.addProperty("direction", spec.getDirection().name());
        t.addProperty("basis", spec.getBasis().name());
        t.addProperty("showHours", spec.isShowHours());
        t.addProperty("showMinutes", spec.isShowMinutes());
        t.addProperty("showSeconds", spec.isShowSeconds());
        t.addProperty("precision", spec.getPrecision().name());
        parentJson.add("timer", t);
    }

    /** Inverse of {@link #serializeTimerSpec}; unknown enum names degrade to defaults. */
    @Nullable
    private static com.fadcam.ui.faditor.model.TimerSpec deserializeTimerSpec(JsonObject o) {
        if (o == null || !hasValue(o, "timer") || !o.get("timer").isJsonObject()) return null;
        JsonObject t = o.getAsJsonObject("timer");
        com.fadcam.ui.faditor.model.TimerSpec spec =
                new com.fadcam.ui.faditor.model.TimerSpec();
        if (hasValue(t, "direction")) {
            try {
                spec.setDirection(com.fadcam.ui.faditor.model.TimerSpec.Direction
                        .valueOf(t.get("direction").getAsString()));
            } catch (IllegalArgumentException ignored) { }
        }
        if (hasValue(t, "basis")) {
            try {
                spec.setBasis(com.fadcam.ui.faditor.model.TimerSpec.Basis
                        .valueOf(t.get("basis").getAsString()));
            } catch (IllegalArgumentException ignored) { }
        }
        if (hasValue(t, "precision")) {
            try {
                spec.setPrecision(com.fadcam.ui.faditor.model.TimerSpec.Precision
                        .valueOf(t.get("precision").getAsString()));
            } catch (IllegalArgumentException ignored) { }
        }
        if (hasValue(t, "showHours")) spec.setShowHours(t.get("showHours").getAsBoolean());
        if (hasValue(t, "showMinutes")) spec.setShowMinutes(t.get("showMinutes").getAsBoolean());
        if (hasValue(t, "showSeconds")) spec.setShowSeconds(t.get("showSeconds").getAsBoolean());
        return spec;
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
        if (gs.freezeStartMs != 0) g.addProperty("freezeStartMs", gs.freezeStartMs);
        if (gs.freezeEndMs != 0) g.addProperty("freezeEndMs", gs.freezeEndMs);
        if (gs.renderStateHash != null) g.addProperty("renderStateHash", gs.renderStateHash);
        parentJson.add("generatedSource", g);
    }

    @Nullable
    private static com.fadcam.ui.faditor.model.GeneratedSource deserializeGeneratedSource(
            JsonObject g) {
        if (g == null || !hasValue(g, "htmlUri") || !hasValue(g, "contentHash")) return null;
        com.fadcam.ui.faditor.model.GeneratedSource gs =
                new com.fadcam.ui.faditor.model.GeneratedSource();
        if (hasValue(g, "kind")) gs.kind = g.get("kind").getAsString();
        if (hasValue(g, "mode")) gs.mode = g.get("mode").getAsString();
        gs.htmlUri = g.get("htmlUri").getAsString();
        gs.contentHash = g.get("contentHash").getAsString();
        if (hasValue(g, "renderCacheUri")) gs.renderCacheUri = g.get("renderCacheUri").getAsString();
        if (hasValue(g, "renderSequenceDir")) gs.renderSequenceDir = g.get("renderSequenceDir").getAsString();
        if (hasValue(g, "authoredDurationMs")) gs.authoredDurationMs = g.get("authoredDurationMs").getAsLong();
        if (hasValue(g, "width")) gs.width = g.get("width").getAsInt();
        if (hasValue(g, "height")) gs.height = g.get("height").getAsInt();
        if (hasValue(g, "styleHint")) gs.styleHint = g.get("styleHint").getAsString();
        if (hasValue(g, "sourceModel")) gs.sourceModel = g.get("sourceModel").getAsString();
        if (hasValue(g, "freezeStartMs")) gs.freezeStartMs = g.get("freezeStartMs").getAsLong();
        if (hasValue(g, "freezeEndMs")) gs.freezeEndMs = g.get("freezeEndMs").getAsLong();
        if (hasValue(g, "renderStateHash")) gs.renderStateHash = g.get("renderStateHash").getAsString();
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
     *
     * <p>M10 addition: any {@link com.fadcam.ui.faditor.layers.LayerTrackDef} (a
     * user-created track — see {@code Timeline#createLayerTrack}) is itself a real
     * layer feature even before anything is dragged into it (an old build has no way
     * to represent "an empty second text track exists"), so it is checked directly
     * rather than only via the resulting track COUNT (a still-empty user-created
     * track does not change {@code getLayers().size()} beyond 1 unless the default
     * bucket is also non-empty — the direct check below covers that gap).</p>
     */
    /**
     * True when ANY clip — master track or overlay — carries a compositing spec that would
     * serialize a v13-only key. Both clip lists are walked because a mask can live on either;
     * checking only the overlays is the shape of bug that leaves a master-track mask unstamped.
     *
     * @see CompositingSpec#needsSchema13()
     */
    private static boolean usesMultiShapeMaskFeatures(@NonNull FaditorProject project) {
        Timeline tl = project.getTimeline();
        for (Clip c : tl.getClips()) {
            com.fadcam.ui.faditor.model.CompositingSpec s = c.getCompositing();
            if (s != null && s.needsSchema13()) return true;
        }
        for (Clip c : tl.getOverlayClips()) {
            com.fadcam.ui.faditor.model.CompositingSpec s = c.getCompositing();
            if (s != null && s.needsSchema13()) return true;
        }
        return false;
    }

    private static boolean usesLayerFeatures(@NonNull FaditorProject project) {
        Timeline tl = project.getTimeline();
        if (!"ripple".equals(tl.getRippleMode())) return true;
        if (!tl.getExtraLayerTracks().isEmpty()) return true;
        // M-COMP-2: a floating overlay-video (PiP) clip is a layer feature an old
        // build cannot represent — stamp v8.
        if (!tl.getOverlayClips().isEmpty()) return true;
        // PHASE-P P1: a renamed DEFAULT track (TrackFlags.customName) is a layer
        // feature an old build can't represent — stamp v8.
        for (com.fadcam.ui.faditor.layers.TrackFlags f : tl.getAllTrackFlags().values()) {
            if (f.customName != null && !f.customName.isEmpty()) return true;
        }
        // The migration produces at most ONE TEXT layer and ONE AUDIO track; more than
        // that means a real multi-track project (belt-and-suspenders with the check
        // above — also catches any non-default layerId that lacks a def, defensively).
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

    /**
     * Restore one serialized track's flags (collapsed/hidden/locked/muted/zIndex) into
     * {@link Timeline}'s persistent trackFlags side-table (M6; PLAN Part 7 M6 scope 3).
     * {@code .has()}-guarded per field so older-shaped entries degrade to defaults.
     * A no-op (and never creates an entry) when the track carries only default flags,
     * matching {@link Timeline#pruneDefaultTrackFlags()} so re-saving stays minimal.
     */
    private static void restoreTrackFlags(@NonNull Timeline timeline, @NonNull JsonObject tj) {
        if (!tj.has("id")) return;
        String id = tj.get("id").getAsString();
        boolean collapsed = hasValue(tj, "collapsed") && tj.get("collapsed").getAsBoolean();
        boolean hidden = hasValue(tj, "hidden") && tj.get("hidden").getAsBoolean();
        boolean locked = hasValue(tj, "locked") && tj.get("locked").getAsBoolean();
        boolean muted = hasValue(tj, "muted") && tj.get("muted").getAsBoolean();
        int zIndex = hasValue(tj, "zIndex") ? tj.get("zIndex").getAsInt() : 0;
        if (!collapsed && !hidden && !locked && !muted && zIndex == 0) return;
        com.fadcam.ui.faditor.layers.TrackFlags flags = timeline.getOrCreateTrackFlags(id);
        flags.collapsed = collapsed;
        flags.hidden = hidden;
        flags.locked = locked;
        flags.muted = muted;
        flags.zIndex = zIndex;
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

    /**
     * Serialize ONE {@link Clip} to its project.json object. Extracted VERBATIM from
     * the master-clips loop (M-COMP-2 refactor) so master clips and floating
     * {@code overlayClips} share a single serializer — field order and every
     * conditional are unchanged, keeping master-clip JSON byte-identical. The
     * overlay-only fields at the end are guarded on {@code layerId != null}, which
     * is never true for a master clip.
     */
    @NonNull
    private JsonObject serializeClipObject(@NonNull File projectDir, @NonNull Clip clip) {
        return serializeClipObject(projectDir, clip, null);
    }

    /**
     * @param pool when non-null, transcript versions are interned into it and the clip carries
     *             {@code transcriptRefs} instead of a full inline copy (see
     *             {@link com.fadcam.ui.faditor.transcript.TranscriptPoolCodec}). Null keeps the
     *             historical inline shape byte-for-byte.
     */
    @NonNull
    private JsonObject serializeClipObject(
            @NonNull File projectDir, @NonNull Clip clip,
            @Nullable com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.Pool pool) {
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
        // §4.5 per-object eye/lock — write-if-true keeps master-clip JSON byte-identical.
        if (clip.isHiddenObject()) clipJson.addProperty("objHidden", true);
        if (clip.isLockedObject()) clipJson.addProperty("objLocked", true);
        // Written beside objLocked because Clip is HAND-serialized here, not reflected by gson.
        // Adding the field to the model was not enough: the toggle worked, the badge appeared,
        // and the flag evaporated on save — the object went back to catching taps with no badge
        // and no explanation. Omit-at-default, so an untouched project is byte-identical.
        if (clip.isPassThrough()) clipJson.addProperty("objPassThrough", true);
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
                if (pool != null) {
                    versionsArr.add(pool.intern(nt));
                } else {
                    versionsArr.add(
                            com.fadcam.ui.faditor.transcript.TranscriptPoolCodec
                                    .serializeVersion(nt));
                }
            }
            clipJson.add(pool != null
                            ? com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.REFS_KEY
                            : com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.INLINE_KEY,
                    versionsArr);
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
        // Caption text animation (SPEC_TEXT_ANIMATION). Written SPARSELY — omitted entirely at
        // the defaults — so every project made before this feature stays byte-identical and no
        // schema bump is needed. Same idiom as GeneratedSource.freezeStartMs/freezeEndMs.
        if (!"NONE".equals(clip.getCaptionAnimPreset())) {
            clipJson.addProperty("captionAnimPreset", clip.getCaptionAnimPreset());
        }
        if (!"WORD".equals(clip.getCaptionAnimGranularity())) {
            clipJson.addProperty("captionAnimGranularity", clip.getCaptionAnimGranularity());
        }
        if (clip.getCaptionAnimInPct() != 0f) {
            clipJson.addProperty("captionAnimInPct", clip.getCaptionAnimInPct());
        }
        if (clip.getCaptionAnimOutPct() != 0f) {
            clipJson.addProperty("captionAnimOutPct", clip.getCaptionAnimOutPct());
        }
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
        // Pitch compensation (review fix 2026-07-05: the quickwin batch added the
        // field + UI + export wiring but never persisted it, so unchecking silently
        // reverted on reload). Default true → written only when non-default, keeping
        // every pre-existing project's JSON byte-identical.
        if (!clip.isPitchCompensationEnabled()) {
            clipJson.addProperty("pitchCompensation", false);
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
        // Per-item compositing spec (masks/chroma-key/matte) — omitted when
        // absent, so pre-existing JSON stays byte-identical.
        com.fadcam.ui.faditor.model.CompositingSpec comp = clip.getCompositing();
        if (comp != null && !comp.isEmpty()) {
            clipJson.add("compositing", comp.toJson());
        }
        // Per-object FX (M7). Additive: written only when the clip actually has effects, so
        // every clip authored before them stays byte-identical.
        if (clip.hasActiveFx() || (clip.getFx() != null && !clip.getFx().isEmpty())) {
            clipJson.add("fx", clip.getFx().toJson());
        }
        // ── Floating overlay-video fields (M-COMP-2).
        //
        // ⚠ These used to be gated ENTIRELY on `layerId != null`, which was safe only while a
        // clip could never move between the spine and a layer. M12 makes that move a one-tap
        // action, and the gate then becomes silent PERMANENT data loss: promote a keyframed PiP
        // to the spine and the very next autosave drops its whole transform envelope, its blend
        // mode and its audio opt-in — with no undo able to bring them back after the save.
        // Found by adversarial review 2026-08-03.
        //
        // So only `layerId`/`overlayStartMs` — which genuinely mean nothing on the spine — stay
        // gated. The rest are written whenever they hold a NON-DEFAULT value, which keeps every
        // pre-existing project byte-identical (a master clip that never was a PiP has all
        // defaults and still writes nothing) while making the round trip lossless.
        if (clip.getLayerId() != null) {
            clipJson.addProperty("layerId", clip.getLayerId());
            clipJson.addProperty("overlayStartMs", clip.getOverlayStartMs());
        }
        {
            // SPEC_PIP_AUDIO: opt-in PiP audio. Written ONLY when true so every existing
            // project's JSON (and its export) stays byte-identical; absent => false.
            if (clip.isOverlayAudioEnabled()) {
                clipJson.addProperty("overlayAudioEnabled", true);
            }
            if (!"NORMAL".equals(clip.getOverlayBlendMode())) {
                clipJson.addProperty("overlayBlendMode", clip.getOverlayBlendMode());
            }
            // Same { property: [ {t,v,e}, ... ] } shape as overlay/sprite keyframes — and now
            // literally the same code as the mask keyframes on CompositingSpec, so the two
            // cannot drift apart (KeyframeCodec).
            JsonObject tracksJson = com.fadcam.ui.faditor.keyframe.KeyframeCodec
                    .toJson(clip.getOverlayTransform());
            if (tracksJson != null) clipJson.add("overlayTransform", tracksJson);
        }
        // Dual-stream pair link (spec §3) — applies to master clips too, so it lives
        // outside the overlay block. Omitted when null so unlinked clips stay byte-identical.
        if (clip.getLinkedClipId() != null) {
            clipJson.addProperty("linkedClipId", clip.getLinkedClipId());
        }
        return clipJson;
    }

    /**
     * Deserialize ONE clip object from project.json. Extracted VERBATIM from the
     * master-clips loop (M-COMP-2 refactor) so master clips and floating
     * {@code overlayClips} share a single deserializer — every {@code .has()}
     * guard and default is unchanged. The overlay-only fields at the end are
     * absent on master clips, so restoring them is a no-op there.
     */
    @NonNull
    /**
     * Optional-field guard: the key is present AND carries an actual value.
     *
     * <p>{@code JsonObject.has(k)} is true for an EXPLICIT {@code "k": null}, and every typed
     * accessor ({@code getAsString}, {@code getAsFloat}, …) throws on {@link com.google.gson
     * .JsonNull}. So the ubiquitous {@code if (o.has(k)) x.set(o.get(k).getAsFloat())} idiom is
     * one JSON literal away from an exception — and in the clip/audio/text deserializers that
     * exception escapes {@code deserialize()} entirely, so {@code load()} logs "file corrupt"
     * and silently serves {@code project.json.bak} instead. That is not a hypothetical: it was
     * reproduced on device for {@code layerId} (audit 1.3, commit 7a09eb6) — a fixture opened
     * under the title of its own older backup, with no error shown.
     *
     * <p>Swapping {@code o.has(k)} for {@code hasValue(o, k)} is a one-token change per site
     * that keeps every existing default and branch exactly as it was: an explicit null now
     * takes the same path as an absent key, which is the semantics the callers already assume.
     * Preferred over an {@code optString/optFloat} family precisely because it does not
     * restructure working code — the diff stays reviewable line by line.
     */
    private static boolean hasValue(@NonNull JsonObject o, @NonNull String key) {
        return o.has(key) && !o.get(key).isJsonNull();
    }

    private Clip deserializeClipObject(@NonNull File projectDir, @NonNull JsonObject clipObj) {
        String clipId = clipObj.get("id").getAsString();
        Uri sourceUri = fromStorageUri(projectDir, clipObj.get("sourceUri").getAsString());
        long inPointMs = clipObj.get("inPointMs").getAsLong();
        long outPointMs = clipObj.get("outPointMs").getAsLong();
        long sourceDurationMs = clipObj.get("sourceDurationMs").getAsLong();
        float speed = hasValue(clipObj, "speedMultiplier")
                ? clipObj.get("speedMultiplier").getAsFloat() : 1.0f;
        boolean audioMuted = hasValue(clipObj, "audioMuted")
                && clipObj.get("audioMuted").getAsBoolean();
        float volumeLevel = hasValue(clipObj, "volumeLevel")
                ? clipObj.get("volumeLevel").getAsFloat() : 1.0f;
        int rotationDeg = hasValue(clipObj, "rotationDegrees")
                ? clipObj.get("rotationDegrees").getAsInt() : 0;
        boolean flipH = hasValue(clipObj, "flipHorizontal")
                && clipObj.get("flipHorizontal").getAsBoolean();
        boolean flipV = hasValue(clipObj, "flipVertical")
                && clipObj.get("flipVertical").getAsBoolean();
        String crop = hasValue(clipObj, "cropPreset")
                ? clipObj.get("cropPreset").getAsString() : "none";
        float cropL = hasValue(clipObj, "cropLeft")
                ? clipObj.get("cropLeft").getAsFloat() : 0f;
        float cropT = hasValue(clipObj, "cropTop")
                ? clipObj.get("cropTop").getAsFloat() : 0f;
        float cropR = hasValue(clipObj, "cropRight")
                ? clipObj.get("cropRight").getAsFloat() : 1f;
        float cropB = hasValue(clipObj, "cropBottom")
                ? clipObj.get("cropBottom").getAsFloat() : 1f;

        Clip clip = new Clip(clipId, sourceUri,
                inPointMs, outPointMs, sourceDurationMs,
                speed, audioMuted, volumeLevel,
                rotationDeg, flipH, flipV, crop,
                cropL, cropT, cropR, cropB);
        if (hasValue(clipObj, "imageClip")) {
            clip.setImageClip(clipObj.get("imageClip").getAsBoolean());
        }
        if (hasValue(clipObj, "removedSpans")) {
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
        if (hasValue(clipObj, "transcripts")) {
            JsonArray versionsArr = clipObj.getAsJsonArray("transcripts");
            for (int v = 0; v < versionsArr.size(); v++) {
                JsonObject vj = versionsArr.get(v).getAsJsonObject();
                com.fadcam.ui.faditor.transcript.Transcript tr =
                        parseWordsArray(vj.getAsJsonArray("words"));
                String id = vj.has("id") ? vj.get("id").getAsString()
                        : java.util.UUID.randomUUID().toString();
                String label = hasValue(vj, "label") ? vj.get("label").getAsString()
                        : "Transcript";
                String engine = hasValue(vj, "engine") ? vj.get("engine").getAsString()
                        : "vosk";
                clip.addTranscript(
                        new com.fadcam.ui.faditor.transcript.NamedTranscript(
                                id, label, engine, tr));
            }
            if (hasValue(clipObj, "activeTranscript")) {
                clip.setActiveTranscriptIndex(
                        clipObj.get("activeTranscript").getAsInt());
            }
        } else if (hasValue(clipObj, "transcript")) {
            // Back-compat: a single un-named transcript.
            com.fadcam.ui.faditor.transcript.Transcript tr =
                    parseWordsArray(clipObj.getAsJsonArray("transcript"));
            clip.addTranscript(
                    new com.fadcam.ui.faditor.transcript.NamedTranscript(
                            "Transcript", "vosk", tr));
        }
        if (hasValue(clipObj, "displayName")) {
            clip.setDisplayName(clipObj.get("displayName").getAsString());
        }
        if (hasValue(clipObj, "captionsEnabled")) {
            clip.setCaptionsEnabled(
                    clipObj.get("captionsEnabled").getAsBoolean());
        }
        if (hasValue(clipObj, "captionStyleId")) {
            clip.setCaptionStyleId(
                    clipObj.get("captionStyleId").getAsString());
        }
        if (hasValue(clipObj, "captionCenterX") && hasValue(clipObj, "captionCenterY")) {
            clip.setCaptionCenter(
                    clipObj.get("captionCenterX").getAsFloat(),
                    clipObj.get("captionCenterY").getAsFloat());
        }
        if (hasValue(clipObj, "captionSizeFraction")) {
            clip.setCaptionSizeFraction(
                    clipObj.get("captionSizeFraction").getAsFloat());
        }
        // Caption text animation. Guarded reads, so a project written before the feature keeps
        // the model defaults ("NONE"/"WORD"/0/0) — which are the off state, so it renders
        // exactly as it always did.
        if (hasValue(clipObj, "captionAnimPreset")) {
            clip.setCaptionAnimPreset(clipObj.get("captionAnimPreset").getAsString());
        }
        if (hasValue(clipObj, "captionAnimGranularity")) {
            clip.setCaptionAnimGranularity(clipObj.get("captionAnimGranularity").getAsString());
        }
        // Both zones through the one setter, which clamps each to [0, 0.5].
        //
        // The older keys "captionAnimInMs"/"captionAnimOutMs" are deliberately READ AND IGNORED.
        // They held a source-ms duration; the model is now a fraction of each caption LINE, and
        // there is no honest conversion — the ms value would have to be divided by the length of
        // a line that varies phrase to phrase, so any migration would pick one phrase's length
        // and be wrong for every other. Nothing is lost: those keys only ever existed in the
        // 2026-07-29 sandbox build and were never shipped to a real project, so the worst case is
        // that a sandbox clip's animation returns to its default "off" and is set again in one
        // tap. Documented here rather than silently dropped so the absence is a decision.
        if (hasValue(clipObj, "captionAnimInPct") || hasValue(clipObj, "captionAnimOutPct")) {
            clip.setCaptionAnimZones(
                    hasValue(clipObj, "captionAnimInPct")
                            ? clipObj.get("captionAnimInPct").getAsFloat() : 0f,
                    hasValue(clipObj, "captionAnimOutPct")
                            ? clipObj.get("captionAnimOutPct").getAsFloat() : 0f);
        }
        // Audio ducking and punch-in zoom (schema v2)
        if (hasValue(clipObj, "duckAmount")) {
            clip.setDuckAmount(clipObj.get("duckAmount").getAsFloat());
        }
        if (hasValue(clipObj, "zoomLevel")) {
            clip.setZoomLevel(clipObj.get("zoomLevel").getAsFloat());
            float zcx = hasValue(clipObj, "zoomCenterX")
                    ? clipObj.get("zoomCenterX").getAsFloat() : 0.5f;
            float zcy = hasValue(clipObj, "zoomCenterY")
                    ? clipObj.get("zoomCenterY").getAsFloat() : 0.5f;
            clip.setZoomCenter(zcx, zcy);
        }
        // Loop / ping-pong (schema v6+)
        if (hasValue(clipObj, "loopMode")) {
            clip.setLoopMode(clipObj.get("loopMode").getAsInt());
            clip.setLoopBeforeMs(hasValue(clipObj, "loopBeforeMs")
                    ? clipObj.get("loopBeforeMs").getAsLong() : 0);
            clip.setLoopAfterMs(hasValue(clipObj, "loopAfterMs")
                    ? clipObj.get("loopAfterMs").getAsLong() : 0);
        }
        if (hasValue(clipObj, "pitchCompensation")) {
            clip.setPitchCompensationEnabled(clipObj.get("pitchCompensation").getAsBoolean());
        }
        if (hasValue(clipObj, "opacityKeyframes")) {
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
        if (hasValue(clipObj, "volumeKeyframes")) {
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
        if (hasValue(clipObj, "captionStyleKeyframes")) {
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
        if (hasValue(clipObj, "effectStack")) {
            deserializeEffectStack(clip.getEffectStack(),
                    clipObj.getAsJsonObject("effectStack"));
        }
        if (hasValue(clipObj, "generatedSource")) {
            clip.setGeneratedSource(deserializeGeneratedSource(
                    clipObj.getAsJsonObject("generatedSource")));
            // Slides are freely stretchable (the animation time-remaps to the
            // clip window), so their trim ceiling is a constant, not the
            // authored length. Widen legacy slides saved before stretch landed.
            if (clip.getGeneratedSource() != null
                    && !clip.getGeneratedSource().isOverlay()
                    && clip.getSourceDurationMs()
                            < com.fadcam.ui.faditor.slides.SlideRenderer.SLIDE_MAX_DURATION_MS) {
                clip.setSourceDurationMs(
                        com.fadcam.ui.faditor.slides.SlideRenderer.SLIDE_MAX_DURATION_MS);
            }
        }
        // Per-item compositing spec — restored for masters AND overlays (the
        // serializer writes it at clip level, outside the layerId block).
        if (hasValue(clipObj, "fx")) {
            try {
                clip.setFx(com.fadcam.ui.faditor.fx.FxStack.fromJson(
                        clipObj.getAsJsonObject("fx")));
            } catch (Exception ignored) {
                // Tolerant read: a malformed stack costs the EFFECTS, never the clip.
            }
        }
        if (hasValue(clipObj, "compositing")) {
            clip.setCompositing(com.fadcam.ui.faditor.model.CompositingSpec
                    .fromJson(clipObj.getAsJsonObject("compositing")));
        }
        // ── Floating overlay-video fields (M-COMP-2) — absent on master clips. ──
        if (hasValue(clipObj, "overlayAudioEnabled")) {
            clip.setOverlayAudioEnabled(clipObj.get("overlayAudioEnabled").getAsBoolean());
        }
        // Audit 1.3: `.has()` alone is not an optional-field guard — an EXPLICIT
        // `"layerId": null` satisfies it and then JsonNull.getAsString() throws. Here that
        // exception escapes deserialize() entirely and load() catches it as "file corrupt",
        // silently falling back to project.json.bak — i.e. the user loses whatever the last
        // save contained, with no error. `null` means "no layer" (a master clip), which is
        // exactly what skipping this block gives. Same idiom already used for linkedClipId
        // below and customStyleJson.
        if (hasValue(clipObj, "layerId")) {
            clip.setLayerId(clipObj.get("layerId").getAsString());
            if (hasValue(clipObj, "overlayStartMs")) {
                clip.setOverlayStartMs(clipObj.get("overlayStartMs").getAsLong());
            }
        }
        // ⚠ blend mode and the transform envelope are read OUTSIDE the layerId gate, mirroring
        // the writer. Gating the READ as well would make the widened writer pointless: a clip
        // promoted to the spine would persist its transform and then silently fail to load it
        // back, which is the same data loss one save later. (Adversarial review 2026-08-03.)
        {
            if (hasValue(clipObj, "overlayBlendMode")) {
                clip.setOverlayBlendMode(clipObj.get("overlayBlendMode").getAsString());
            }
            if (hasValue(clipObj, "overlayTransform")) {
                com.fadcam.ui.faditor.keyframe.KeyframeSet ks =
                        com.fadcam.ui.faditor.keyframe.KeyframeCodec.fromJson(
                                clipObj.getAsJsonObject("overlayTransform"));
                if (ks != null) clip.setOverlayTransform(ks);
            }
        }
        // Dual-stream pair link (spec §3) — master or overlay clip; absent = unlinked.
        if (hasValue(clipObj, "linkedClipId")) {
            clip.setLinkedClipId(clipObj.get("linkedClipId").getAsString());
        }
        // §4.5 per-object eye/lock (tolerant: absent = false).
        if (hasValue(clipObj, "objHidden")) clip.setHiddenObject(clipObj.get("objHidden").getAsBoolean());
        if (hasValue(clipObj, "objLocked")) clip.setLockedObject(clipObj.get("objLocked").getAsBoolean());
        if (hasValue(clipObj, "objPassThrough")) {
            clip.setPassThrough(clipObj.get("objPassThrough").getAsBoolean());
        }
        return clip;
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
        if (stack.getLutIntensity() < 0.999f) {
            fx.addProperty("lutIntensity", stack.getLutIntensity());
        }
        clipJson.add("effectStack", fx);
    }

    private static void deserializeEffectStack(
            com.fadcam.ui.faditor.effects.EffectStack stack, JsonObject fx) {
        if (hasValue(fx, "exposure")) stack.setExposure(fx.get("exposure").getAsFloat());
        if (hasValue(fx, "contrast")) stack.setContrast(fx.get("contrast").getAsFloat());
        if (hasValue(fx, "saturation")) stack.setSaturation(fx.get("saturation").getAsFloat());
        if (hasValue(fx, "temperature")) stack.setTemperature(fx.get("temperature").getAsFloat());
        if (hasValue(fx, "tint")) stack.setTint(fx.get("tint").getAsFloat());
        if (hasValue(fx, "highlights")) stack.setHighlights(fx.get("highlights").getAsFloat());
        if (hasValue(fx, "shadows")) stack.setShadows(fx.get("shadows").getAsFloat());
        if (hasValue(fx, "fade")) stack.setFade(fx.get("fade").getAsFloat());
        if (hasValue(fx, "vignette")) stack.setVignette(fx.get("vignette").getAsFloat());
        if (hasValue(fx, "grain")) stack.setGrain(fx.get("grain").getAsFloat());
        if (hasValue(fx, "lutEnabled")) stack.setLutEnabled(fx.get("lutEnabled").getAsBoolean());
        if (hasValue(fx, "lutId")) stack.setLutId(fx.get("lutId").getAsString());
        if (hasValue(fx, "lutIntensity")) stack.setLutIntensity(fx.get("lutIntensity").getAsFloat());
    }

    /**
     * Custom serializer for FaditorProject (flattens nested objects).
     */
    /**
     * Whether pooling transcripts would actually save anything for {@code src}: true when some
     * {@link com.fadcam.ui.faditor.transcript.NamedTranscript} INSTANCE is carried by more than
     * one owner (clip, overlay clip or audio clip). That is precisely the duplication the pool
     * removes.
     *
     * <p>Deliberately identity-based. Two forks that share an id are not a saving — they get
     * separate pool entries — and a project with one transcript on one clip would gain nothing
     * but a schema bump locking older builds out of a file they read perfectly well. Same
     * "stamp only what the project genuinely needs" discipline as the rest of this serializer.</p>
     */
    private static boolean poolingWouldPay(@Nullable FaditorProject src) {
        if (src == null || src.getTimeline() == null) return false;
        java.util.Set<com.fadcam.ui.faditor.transcript.NamedTranscript> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        com.fadcam.ui.faditor.model.Timeline tl = src.getTimeline();
        for (Clip c : tl.getClips()) {
            if (c != null && anyTranscriptRepeat(c.getTranscripts(), seen)) return true;
        }
        for (Clip c : tl.getOverlayClips()) {
            if (c != null && anyTranscriptRepeat(c.getTranscripts(), seen)) return true;
        }
        for (AudioClip a : tl.getAudioClips()) {
            if (a != null && anyTranscriptRepeat(a.getTranscripts(), seen)) return true;
        }
        return false;
    }

    /** True as soon as one of {@code list}'s instances has already been seen. */
    private static boolean anyTranscriptRepeat(
            @Nullable java.util.List<com.fadcam.ui.faditor.transcript.NamedTranscript> list,
            @NonNull java.util.Set<com.fadcam.ui.faditor.transcript.NamedTranscript> seen) {
        if (list == null) return false;
        for (com.fadcam.ui.faditor.transcript.NamedTranscript nt : list) {
            if (nt != null && !seen.add(nt)) return true;
        }
        return false;
    }

    private class ProjectSerializer implements JsonSerializer<FaditorProject> {
        @Override
        public JsonElement serialize(FaditorProject src, Type typeOfSrc,
                                     JsonSerializationContext context) {
            File projectDir = getProjectDir(src.getId());
            JsonObject json = new JsonObject();
            // Dual-write schema stamp (PLAN §4.1(1)): stamp only the version the
            // project GENUINELY needs, so older builds keep opening sprite-free /
            // layer-free projects losslessly. v10 = avatar rigs, v9 = sprites
            // (PLAN_SPRITE_ANIMATION S1), v8 = layer features, else 7. All newer blocks
            // are written additively either way (old builds ignore unknown fields).
            // Each is a LITERAL, not SCHEMA_VERSION: it names the version that first
            // understood the feature, so a later unrelated bump must not drag it along
            // (that would lock old builds out of files they can read perfectly well).
            boolean usesRigs = !src.getAvatarRigs().isEmpty();
            boolean usesSprites = !src.getTimeline().getSpriteOverlays().isEmpty()
                    || !src.getSpriteSheets().isEmpty();
            int stampedVersion = usesRigs ? 10
                    : usesSprites ? 9
                    : usesLayerFeatures(src) ? 8 : 7;
            // Audit 1.2: a lane KIND an older build cannot represent is its own floor on
            // the stamp. usesLayerFeatures() above already returns true for any
            // LayerTrackDef, but v8 is not enough — a v10 build's downgrade guard fires
            // only on on-disk > running, so v8 sails straight past it, TrackKind.fromName
            // coerces LAYER to VIDEO, and the next autosave writes that coercion back,
            // permanently changing the lane's band position and therefore its paint order.
            // Raising the stamp is what makes that build refuse the file instead.
            for (com.fadcam.ui.faditor.layers.LayerTrackDef def
                    : src.getTimeline().getExtraLayerTracks()) {
                stampedVersion = Math.max(stampedVersion, def.getKind().minSchemaVersion());
            }
            // v12 — transcript pool. The ONE non-additive block here: a pooled file has no
            // per-clip "transcripts" at all, so an older build would read the project with
            // its transcripts missing and autosave that back. Raising the stamp is what makes
            // that build refuse the file instead. Pooling is therefore switched on only when
            // it actually pays (some transcript instance is on more than one owner) — an
            // un-duplicated project keeps the inline shape AND its old stamp, so it stays
            // openable by older builds and its JSON stays byte-identical.
            // v13 — multi-shape masks (SPEC_ADJUSTMENT_LAYERS_FX M0). Non-additive for the two
            // reasons CompositingSpec.needsSchema13 documents: an old build reads an INTERSECT
            // shape as plain additive and reads no explicit slot at all, then autosaves that
            // back — turning an intersection into a union and renumbering keyframe tracks onto
            // the wrong shapes. Raising the stamp is what makes that build refuse the file.
            // A spec that writes neither key leaves the stamp alone, so every add/subtract-only
            // project keeps its old version AND its byte-identical JSON.
            if (usesMultiShapeMaskFeatures(src)) {
                stampedVersion = Math.max(stampedVersion, 13);
            }
            // v13 — adjustment layers (M3). NON-NEGOTIABLE, and the reason is TrackKind's:
            // an old build maps the unknown ADJUSTMENT kind to VIDEO, then autosaves that
            // coercion back, permanently changing which lane paints over which — with no error
            // and no way back. It already happened once for LAYER. Raising the stamp is what
            // makes that build refuse the file instead of quietly damaging it.
            if (src.getTimeline().usesAdjustmentLayers()) {
                stampedVersion = Math.max(stampedVersion, 13);
            }
            com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.Pool transcriptPool =
                    poolingWouldPay(src)
                            ? new com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.Pool()
                            : null;
            if (transcriptPool != null) {
                stampedVersion = Math.max(stampedVersion,
                        com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.MIN_SCHEMA_VERSION);
            }
            json.addProperty("schemaVersion", stampedVersion);
            json.addProperty("id", src.getId());
            json.addProperty("name", src.getName());
            json.addProperty("createdAt", src.getCreatedAt());
            json.addProperty("lastModified", src.getLastModified());

            // Serialize timeline with clips
            JsonObject timelineJson = new JsonObject();
            JsonArray clipsArray = new JsonArray();
            for (Clip clip : src.getTimeline().getClips()) {
                clipsArray.add(serializeClipObject(projectDir, clip, transcriptPool));
            }
            timelineJson.add("clips", clipsArray);

            // Floating overlay-video (PiP) clips — M-COMP-2. Additive: the array is
            // only written when non-empty, so every pre-existing project's JSON is
            // byte-identical. Same clip serializer as the master list (one authority).
            if (!src.getTimeline().getOverlayClips().isEmpty()) {
                JsonArray overlayClipsArray = new JsonArray();
                for (Clip oc : src.getTimeline().getOverlayClips()) {
                    overlayClipsArray.add(serializeClipObject(projectDir, oc, transcriptPool));
                }
                timelineJson.add("overlayClips", overlayClipsArray);
            }

            // Adjustment layers — SPEC_ADJUSTMENT_LAYERS_FX M3. Additive in the same way, and
            // SELF-SERIALIZING (AdjustmentLayer.toJson) rather than going through a
            // serializer-side helper: the class that knows what a field means writes it.
            if (src.getTimeline().usesAdjustmentLayers()) {
                JsonArray adjustArray = new JsonArray();
                for (com.fadcam.ui.faditor.model.AdjustmentLayer al
                        : src.getTimeline().getAdjustmentLayers()) {
                    adjustArray.add(al.toJson());
                }
                timelineJson.add("adjustmentLayers", adjustArray);
            }

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
                // M10 track-membership: which audio layer track this clip belongs to.
                // Omitted (→ null on load) for the default/auto-migrated "audio" track,
                // so an old-shaped or pre-M10 project's clips are indistinguishable from
                // one explicitly on the default track (Timeline#getAudioTracks()).
                if (ac.getLayerId() != null) acJson.addProperty("layerId", ac.getLayerId());
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
                        if (transcriptPool != null) {
                            versionsArr.add(transcriptPool.intern(nt));
                        } else {
                            versionsArr.add(
                                    com.fadcam.ui.faditor.transcript.TranscriptPoolCodec
                                            .serializeVersion(nt));
                        }
                    }
                    acJson.add(transcriptPool != null
                                    ? com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.REFS_KEY
                                    : com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.INLINE_KEY,
                            versionsArr);
                    acJson.addProperty("activeTranscript", ac.getActiveTranscriptIndex());
                }
                acJson.addProperty("captionsEnabled", ac.isCaptionsEnabled());
                acJson.addProperty("captionStyleId", ac.getCaptionStyleId());
                acJson.addProperty("captionCenterX", ac.getCaptionCenterX());
                acJson.addProperty("captionCenterY", ac.getCaptionCenterY());
                // Audit 2.2: the clip serializer has written this since captions landed;
                // the audio one never did, so an audio caption's size survived into the
                // EXPORT (which reads the model) but reverted to 0.060 on the next load.
                acJson.addProperty("captionSizeFraction", ac.getCaptionSizeFraction());
                // §4.5 per-object lock (write-if-true; audio's eye = its existing mute).
                if (ac.isLocked()) acJson.addProperty("objLocked", true);
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
                // Per-object FX (M7). Additive: written only when the overlay has effects.
                if (o.getFx() != null && !o.getFx().isEmpty()) {
                    oJson.add("fx", o.getFx().toJson());
                }
                if (o.getOpacity() != 1f) oJson.addProperty("opacity", o.getOpacity());
                if (!"default".equals(o.getFontFamily())) {
                    oJson.addProperty("fontFamily", o.getFontFamily());
                }
                // Entrance/exit animation (SPEC_TEXT_ANIMATION, text-box half). Sparse, like
                // the clip's caption zones: an overlay that was never animated writes nothing
                // and stays byte-identical to how every existing project already serialises it.
                // Rider attachment (§4A). Sparse for the same reason as the animation block: an
                // unanchored overlay — i.e. every overlay in every project written before this —
                // writes nothing and round-trips byte-identically.
                if (o.getHostClipId() != null) {
                    oJson.addProperty("hostClipId", o.getHostClipId());
                    if (o.getHostOffsetMs() != 0L) {
                        oJson.addProperty("hostOffsetMs", o.getHostOffsetMs());
                    }
                }
                if (!"NONE".equals(o.getTextAnimPreset())) {
                    oJson.addProperty("textAnimPreset", o.getTextAnimPreset());
                }
                if (!"BLOCK".equals(o.getTextAnimGranularity())) {
                    oJson.addProperty("textAnimGranularity", o.getTextAnimGranularity());
                }
                if (o.getTextAnimInPct() != 0f) {
                    oJson.addProperty("textAnimInPct", o.getTextAnimInPct());
                }
                if (o.getTextAnimOutPct() != 0f) {
                    oJson.addProperty("textAnimOutPct", o.getTextAnimOutPct());
                }
                if (o.getImageUri() != null) {
                    oJson.addProperty("imageUri", toStorageUri(projectDir, o.getImageUri()));
                }
                // M10 track-membership: which TEXT/STICKER layer track this item belongs
                // to. Omitted (→ null on load) for the default/auto-migrated "text" track,
                // so a pre-M10 project's overlays are indistinguishable from ones
                // explicitly on the default track (Timeline#getLayers()).
                if (o.getLayerId() != null) oJson.addProperty("layerId", o.getLayerId());
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
                // Motion-range window — sparse like the rest: written only when set, so a project
                // that never opens the motion-range controls round-trips byte-identically.
                if (o.hasMotionRange()) {
                    oJson.addProperty("motionStartMs", o.getMotionStartMs());
                    oJson.addProperty("motionEndMs", o.getMotionEndMs());
                }
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
                serializeGeneratedSource(oJson, o.getGeneratedSource());
                // §4.5 per-object eye/lock (write-if-true — pre-§4.5 JSON unchanged).
                if (o.isHidden()) oJson.addProperty("objHidden", true);
                if (o.isLocked()) oJson.addProperty("objLocked", true);
                // Image-overlay drawer state (M-IMG-1). All sparse defaults:
                // passThrough/scaleLinked false/true and blend "NORMAL" write nothing,
                // so every pre-existing project stays byte-identical.
                if (o.isPassThrough()) oJson.addProperty("passThrough", true);
                if (!o.isScaleLinked()) oJson.addProperty("scaleLinked", false);
                if (o.getScaleX() != 1f) oJson.addProperty("scaleX", o.getScaleX());
                if (o.getScaleY() != 1f) oJson.addProperty("scaleY", o.getScaleY());
                if (!"NORMAL".equals(o.getOverlayBlendMode())) {
                    oJson.addProperty("overlayBlendMode", o.getOverlayBlendMode());
                }
                com.fadcam.ui.faditor.model.CompositingSpec oComp = o.getCompositing();
                if (oComp != null && !oComp.isEmpty()) {
                    oJson.add("compositing", oComp.toJson());
                }
                serializeTimerSpec(oJson, o.getTimerSpec());
                // W5-2 rich text spans (§3.8). Sparse: an overlay with no per-selection
                // formatting writes nothing, so every pre-W5-2 project round-trips
                // byte-identically.
                if (o.hasStyleSpans()) {
                    JsonArray spansArr = new JsonArray();
                    for (StyleSpan s : o.getStyleSpans()) {
                        JsonObject sj = new JsonObject();
                        StyleSpan.toJson(s, sj);
                        spansArr.add(sj);
                    }
                    oJson.add("styleSpans", spansArr);
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
                    if (wo.getBarWidthOverrideDp() > 0f) wj.addProperty("barWidthDp", wo.getBarWidthOverrideDp());
                    if (wo.getBarGapOverrideDp() > 0f) wj.addProperty("barGapDp", wo.getBarGapOverrideDp());
                    if (wo.getColorOverride() != null) wj.addProperty("colorOverride", wo.getColorOverride());
                    if (wo.getSensitivityOverride() > 0f) wj.addProperty("sensitivity", wo.getSensitivityOverride());
                    if (wo.getGradientStartOverride() != null && wo.getGradientEndOverride() != null) {
                        wj.addProperty("gradStart", wo.getGradientStartOverride());
                        wj.addProperty("gradEnd", wo.getGradientEndOverride());
                    }
                    // SPEC_VIZ_ENGINE §4 (Layers UI lane): inline custom layer stack (absent = null,
                    // so every pre-Layers-UI project round-trips byte-identical).
                    if (wo.getCustomStyleJson() != null) {
                        wj.addProperty("customStyleJson", wo.getCustomStyleJson());
                    }
                    // G5 attach/detach (tolerant, absent = detached — every pre-G5 project)
                    if (wo.getAttachedClipId() != null) {
                        wj.addProperty("attachedClipId", wo.getAttachedClipId());
                        wj.addProperty("attachOffsetMs", wo.getAttachOffsetMs());
                        if (wo.getAttachDurationMs() != Long.MAX_VALUE) {
                            wj.addProperty("attachDurationMs", wo.getAttachDurationMs());
                        }
                        if (wo.isStratified()) wj.addProperty("stratified", true);
                        // §4.5 per-object eye/lock (write-if-true).
                        if (wo.isHidden()) wj.addProperty("objHidden", true);
                        if (wo.isLocked()) wj.addProperty("objLocked", true);
                    }
                    wfArray.add(wj);
                }
                timelineJson.add("waveformOverlays", wfArray);
            }

            // G9 link groups (tolerant: absent = unlinked, every pre-G9 project). PERSISTED
            // ad-hoc groups only — the transient G5-preset groups live in a separate Timeline
            // list this writer never touches (PLAN_G9_LINK_ENGINE.md §4.1).
            if (!src.getTimeline().getLinkGroups().isEmpty()) {
                JsonArray lgArray = new JsonArray();
                for (com.fadcam.ui.faditor.layers.LinkGroup g : src.getTimeline().getLinkGroups()) {
                    JsonObject gj = new JsonObject();
                    gj.addProperty("id", g.id);
                    JsonArray props = new JsonArray();
                    for (com.fadcam.ui.faditor.layers.LinkedProperty p : g.properties) {
                        props.add(p.name());
                    }
                    gj.add("properties", props);
                    JsonArray members = new JsonArray();
                    for (com.fadcam.ui.faditor.layers.LinkMember m : g.members) {
                        JsonObject mj = new JsonObject();
                        mj.addProperty("kind", m.kind);
                        mj.addProperty("id", m.id);
                        if (m.isHost) mj.addProperty("host", true);
                        if (m.hostOffsetMs != com.fadcam.ui.faditor.layers.LinkMember.UNSET) {
                            mj.addProperty("hostOffsetMs", m.hostOffsetMs);
                        }
                        members.add(mj);
                    }
                    gj.add("members", members);
                    lgArray.add(gj);
                }
                timelineJson.add("linkGroups", lgArray);
            }

            // Serialize placed sprite overlays (schema v9, PLAN_SPRITE_ANIMATION S1).
            // Sparse-write like every other overlay family; frame track entries are
            // {t, c} (direct cell) or {t, p} (preset ref); keyframes reuse the exact
            // text-overlay tracks shape.
            if (!src.getTimeline().getSpriteOverlays().isEmpty()) {
                JsonArray spArray = new JsonArray();
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so
                        : src.getTimeline().getSpriteOverlays()) {
                    JsonObject sj = new JsonObject();
                    sj.addProperty("id", so.getId());
                    sj.addProperty("sheetId", so.getSheetId());
                    sj.addProperty("centerX", so.getCenterX());
                    sj.addProperty("centerY", so.getCenterY());
                    sj.addProperty("sizeFraction", so.getSizeFraction());
                    if (so.getRotationDeg() != 0f) sj.addProperty("rotationDeg", so.getRotationDeg());
                    if (so.getOpacity() != 1f) sj.addProperty("opacity", so.getOpacity());
                    if (so.isFlipH()) sj.addProperty("flipH", true);
                    if (so.isFlipV()) sj.addProperty("flipV", true);
                    if (so.getStartMs() != 0) sj.addProperty("startMs", so.getStartMs());
                    if (so.getEndMs() != Long.MAX_VALUE) sj.addProperty("endMs", so.getEndMs());
                    if (so.getLayerId() != null) sj.addProperty("layerId", so.getLayerId());
                    if (!"hold".equals(so.getEndBehavior())) sj.addProperty("endBehavior", so.getEndBehavior());
                    // SPEC_IMAGE_SEQUENCE §6: the INTENT to continue. endMs is always a
                    // resolved concrete number, so only the intent needs persisting —
                    // and only when set, keeping every existing project byte-identical.
                    if (so.isContinuesUntilBlocked()) sj.addProperty("continues", true);
                    // §2a ABSOLUTE left-trim: which frame the run starts on. Omitted at 0,
                    // which is every sequence that has never been left-trimmed.
                    if (so.getSequenceStartFrame() > 0) {
                        sj.addProperty("startFrame", so.getSequenceStartFrame());
                    }
                    // Avatar performance (bake-to-keyframes): additive, sparse.
                    // The track self-serializes (schemaVersion + tolerant read).
                    if (so.getAvatarRigId() != null) {
                        sj.addProperty("avatarRigId", so.getAvatarRigId());
                    }
                    if (so.getAvatarTrack() != null && !so.getAvatarTrack().isEmpty()) {
                        sj.add("avatarTrack", so.getAvatarTrack().toJson());
                    }
                    if (!so.getFrameTrack().isEmpty()) {
                        JsonArray ftArr = new JsonArray();
                        for (com.fadcam.ui.faditor.sprite.FrameTrack.Key k
                                : so.getFrameTrack().keys()) {
                            JsonObject kj = new JsonObject();
                            kj.addProperty("t", k.timeMs);
                            if (k.presetId != null) kj.addProperty("p", k.presetId);
                            else kj.addProperty("c", k.cellIndex);
                            ftArr.add(kj);
                        }
                        sj.add("frameTrack", ftArr);
                    }
                    if (!so.getKeyframes().isEmpty()) {
                        JsonObject tracksJson = new JsonObject();
                        for (com.fadcam.ui.faditor.keyframe.KeyframeTrack tr
                                : so.getKeyframes().tracks()) {
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
                        sj.add("keyframes", tracksJson);
                    }
                    // §4.5 per-object eye/lock (write-if-true).
                    if (so.isHidden()) sj.addProperty("objHidden", true);
                    if (so.isLocked()) sj.addProperty("objLocked", true);
                    spArray.add(sj);
                }
                timelineJson.add("spriteOverlays", spArray);
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
            // M10: the persistent list of user-created track DEFINITIONS (id/kind/name),
            // independent of whether they currently hold any items — this is what lets a
            // freshly-created EMPTY layer track survive a save/reload (Timeline#createLayerTrack).
            // Redundant with (but more explicit/robust than) re-deriving membership from the
            // "layers"/"audioTracks" arrays above, which only round-trip a track that
            // {@code getLayers()}/{@code getAudioTracks()} actually produced.
            JsonArray trackDefsArr = new JsonArray();
            for (com.fadcam.ui.faditor.layers.LayerTrackDef def : src.getTimeline().getExtraLayerTracks()) {
                JsonObject dj = new JsonObject();
                dj.addProperty("id", def.getId());
                dj.addProperty("kind", def.getKind().name());
                dj.addProperty("name", def.getName());
                trackDefsArr.add(dj);
            }
            layersBlock.add("trackDefs", trackDefsArr);
            // PHASE-P P1 (additive): user renames of the DEFAULT "text"/"audio"/"sprite"
            // tracks live in the TrackFlags side-table (customName) — those tracks have
            // no LayerTrackDef to carry a name. Serialized as {trackId: name}; absent
            // for every project that never renamed a default track.
            JsonObject trackNamesObj = new JsonObject();
            for (java.util.Map.Entry<String, com.fadcam.ui.faditor.layers.TrackFlags> e
                    : src.getTimeline().getAllTrackFlags().entrySet()) {
                if (e.getValue().customName != null && !e.getValue().customName.isEmpty()) {
                    trackNamesObj.addProperty(e.getKey(), e.getValue().customName);
                }
            }
            if (trackNamesObj.size() > 0) {
                layersBlock.add("trackNames", trackNamesObj);
            }
            timelineJson.add("layers", layersBlock);

            json.add("timeline", timelineJson);

            // The transcript pool the clip/audio serializers above interned into. Attached
            // after the timeline because that is when it is complete; the reader looks it up
            // by key, so position is irrelevant.
            if (transcriptPool != null && !transcriptPool.isEmpty()) {
                json.add(com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.POOL_KEY,
                        transcriptPool.toJson());
                if (transcriptPool.forkCount() > 0) {
                    // Two instances shared one id — an unmerged fork (TranscriptSharing). Not
                    // an error here (each got its own entry, so nothing is lost), but worth a
                    // line: it means the sharing migration has not run on this project yet.
                    FLog.d(TAG, "transcriptPool: " + transcriptPool.size() + " entries, "
                            + transcriptPool.forkCount() + " of them unmerged forks");
                }
            }

            // Serialize sprite-sheet definitions (schema v9, project level). The sheet's
            // own toJson emits the in-memory URI; convert to project://-relative here so
            // the sheet image travels with the project bundle (imageUri convention).
            if (!src.getSpriteSheets().isEmpty()) {
                JsonArray sheetsArr = new JsonArray();
                for (com.fadcam.ui.faditor.sprite.SpriteSheet sheet : src.getSpriteSheets()) {
                    JsonObject shJson = sheet.toJson();
                    shJson.addProperty("sheetUri", toStorageUri(projectDir, sheet.getSheetUri()));
                    // An image SEQUENCE's cells are N separate files, and every one of them is
                    // project media in exactly the way sheetUri is. Relativising only sheetUri
                    // would make "make project self-contained" copy one frame of a 240-frame
                    // sequence and leave the rest pointing outside the bundle.
                    if (sheet.isSequence()) {
                        JsonArray fu = new JsonArray();
                        for (String u : sheet.getFrameUris()) fu.add(toStorageUri(projectDir, u));
                        shJson.add("frameUris", fu);
                    }
                    sheetsArr.add(shJson);
                }
                json.add("spriteSheets", sheetsArr);
            }

            // Serialize avatar rigs (schema v10, project level; self-serializing model).
            if (!src.getAvatarRigs().isEmpty()) {
                JsonArray rigsArr = new JsonArray();
                for (com.fadcam.ui.faditor.avatar.AvatarRig rig : src.getAvatarRigs()) {
                    rigsArr.add(rig.toJson());
                }
                json.add("avatarRigs", rigsArr);
            }

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
            // Expand a v12 transcript pool back into the inline per-clip shape BEFORE anything
            // below reads the tree, so the clip/audio deserializers keep seeing exactly the one
            // input shape they have always seen. A non-pooled file is untouched by this.
            int danglingRefs = com.fadcam.ui.faditor.transcript.TranscriptPoolCodec.expand(obj);
            if (danglingRefs > 0) {
                FLog.w(TAG, "transcriptPool: " + danglingRefs
                        + " ref(s) named a missing pool entry; those versions were dropped");
            }
            // Resolve project://<relative> asset paths against this project's dir.
            String projectIdForPaths = obj.has("id") ? obj.get("id").getAsString() : null;
            File projectDir = projectIdForPaths != null
                    ? getProjectDir(projectIdForPaths) : projectsRoot;

            // Restore schema version (defaults to 0 for very old projects)
            int schemaVersion = hasValue(obj, "schemaVersion")
                    ? obj.get("schemaVersion").getAsInt() : 0;

            String name = hasValue(obj, "name") ? obj.get("name").getAsString() : "Untitled";

            // Restore project with original ID and timestamps
            FaditorProject project;
            if (obj.has("id") && obj.has("createdAt")) {
                String id = obj.get("id").getAsString();
                long createdAt = obj.get("createdAt").getAsLong();
                long lastModified = hasValue(obj, "lastModified")
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
            if (hasValue(obj, "timeline")) {
                JsonObject timelineJson = obj.getAsJsonObject("timeline");
                if (hasValue(timelineJson, "clips")) {
                    JsonArray clips = timelineJson.getAsJsonArray("clips");
                    for (int i = 0; i < clips.size(); i++) {
                        // Per-item fault tolerance (load-failure SHAPE fix): a single
                        // malformed clip must not abort the whole load and drop the user
                        // to a silent .bak. Skip it, record it, keep the rest.
                        try {
                            JsonObject clipObj = clips.get(i).getAsJsonObject();
                            project.getTimeline().addClip(
                                    deserializeClipObject(projectDir, clipObj));
                        } catch (Exception ex) {
                            FLog.e(TAG, "Skipping malformed clip #" + i, ex);
                            project.addLoadSkip("Video clip #" + (i + 1));
                        }
                    }
                }
                // Floating overlay-video (PiP) clips — M-COMP-2. Absent on every
                // pre-existing project; same clip deserializer as the master list.
                if (hasValue(timelineJson, "overlayClips")) {
                    JsonArray overlayArr = timelineJson.getAsJsonArray("overlayClips");
                    for (int i = 0; i < overlayArr.size(); i++) {
                        try {
                            JsonObject clipObj = overlayArr.get(i).getAsJsonObject();
                            Clip oc = deserializeClipObject(projectDir, clipObj);
                            if (oc.getLayerId() == null) {
                                // Tolerant-read (A1 fromJson lesson): an overlay clip whose
                                // layerId was lost lands on the default PiP layer instead of
                                // silently vanishing into neither list.
                                oc.setLayerId("video");
                            }
                            project.getTimeline().addOverlayClip(oc);
                        } catch (Exception ex) {
                            FLog.e(TAG, "Skipping malformed overlay (PiP) clip #" + i, ex);
                            project.addLoadSkip("Picture-in-picture clip #" + (i + 1));
                        }
                    }
                }
                // Adjustment layers — M3. Absent on every project written before them, and a
                // malformed one is skipped per-item rather than taking the load down.
                if (hasValue(timelineJson, "adjustmentLayers")) {
                    JsonArray adjArr = timelineJson.getAsJsonArray("adjustmentLayers");
                    for (int i = 0; i < adjArr.size(); i++) {
                        try {
                            com.fadcam.ui.faditor.model.AdjustmentLayer al =
                                    com.fadcam.ui.faditor.model.AdjustmentLayer.fromJson(
                                            adjArr.get(i).getAsJsonObject());
                            if (al == null) continue;
                            if (al.getLayerId().isEmpty()) {
                                // Same tolerant read as the overlay above: a layer whose lane
                                // was lost lands somewhere real rather than in neither list.
                                al.setLayerId("adjustment");
                            }
                            project.getTimeline().addAdjustmentLayer(al);
                        } catch (Exception ex) {
                            FLog.e(TAG, "Skipping malformed adjustment layer #" + i, ex);
                            project.addLoadSkip("Adjustment layer #" + (i + 1));
                        }
                    }
                }
            }

            // Restore audio clips
            if (hasValue(obj, "timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (hasValue(tl, "audioClips")) {
                    JsonArray audioArr = tl.getAsJsonArray("audioClips");
                    for (int i = 0; i < audioArr.size(); i++) {
                      // Per-item fault tolerance (load-failure SHAPE fix).
                      try {
                        JsonObject acObj = audioArr.get(i).getAsJsonObject();
                        Uri acUri = fromStorageUri(projectDir, acObj.get("sourceUri").getAsString());
                        long acDuration = acObj.get("sourceDurationMs").getAsLong();
                        AudioClip ac = new AudioClip(acUri, acDuration);
                        ac.setInPointMs(acObj.get("inPointMs").getAsLong());
                        ac.setOutPointMs(acObj.get("outPointMs").getAsLong());
                        if (hasValue(acObj, "offsetMs")) {
                            ac.setOffsetMs(acObj.get("offsetMs").getAsLong());
                        }
                        if (hasValue(acObj, "layerId") && !acObj.get("layerId").isJsonNull()) {
                            ac.setLayerId(acObj.get("layerId").getAsString()); // audit 1.3
                        }
                        if (hasValue(acObj, "volumeLevel")) {
                            ac.setVolumeLevel(acObj.get("volumeLevel").getAsFloat());
                        }
                        if (hasValue(acObj, "muted")) {
                            ac.setMuted(acObj.get("muted").getAsBoolean());
                        }
                        if (hasValue(acObj, "label")) {
                            ac.setLabel(acObj.get("label").getAsString());
                        }
                        if (hasValue(acObj, "volumeKeyframes")) {
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
                        if (hasValue(acObj, "waveform")) {
                            JsonArray wfArr = acObj.getAsJsonArray("waveform");
                            int[] waveform = new int[wfArr.size()];
                            for (int j = 0; j < wfArr.size(); j++) {
                                waveform[j] = wfArr.get(j).getAsInt();
                            }
                            ac.setWaveform(waveform);
                        }
                        // Restore transcripts + caption settings
                        if (hasValue(acObj, "transcripts")) {
                            JsonArray versionsArr = acObj.getAsJsonArray("transcripts");
                            for (int v = 0; v < versionsArr.size(); v++) {
                                JsonObject vj = versionsArr.get(v).getAsJsonObject();
                                com.fadcam.ui.faditor.transcript.Transcript tr =
                                        parseWordsArray(vj.getAsJsonArray("words"));
                                String id = vj.has("id") ? vj.get("id").getAsString()
                                        : java.util.UUID.randomUUID().toString();
                                String label = hasValue(vj, "label") ? vj.get("label").getAsString()
                                        : "Transcript";
                                String engine = hasValue(vj, "engine") ? vj.get("engine").getAsString()
                                        : "vosk";
                                ac.addTranscript(
                                        new com.fadcam.ui.faditor.transcript.NamedTranscript(
                                                id, label, engine, tr));
                            }
                            if (hasValue(acObj, "activeTranscript")) {
                                ac.setActiveTranscriptIndex(
                                        acObj.get("activeTranscript").getAsInt());
                            }
                        }
                        if (hasValue(acObj, "captionsEnabled")) {
                            ac.setCaptionsEnabled(
                                    acObj.get("captionsEnabled").getAsBoolean());
                        }
                        if (hasValue(acObj, "captionStyleId")) {
                            ac.setCaptionStyleId(
                                    acObj.get("captionStyleId").getAsString());
                        }
                        if (hasValue(acObj, "captionCenterX") && hasValue(acObj, "captionCenterY")) {
                            ac.setCaptionCenter(
                                    acObj.get("captionCenterX").getAsFloat(),
                                    acObj.get("captionCenterY").getAsFloat());
                        }
                        // Audit 2.2. Tolerant like every other read: absent (every project
                        // written before this) keeps AudioClip's 0.060f default, which is
                        // exactly the value those projects were being reset to anyway.
                        if (hasValue(acObj, "captionSizeFraction")) {
                            ac.setCaptionSizeFraction(
                                    acObj.get("captionSizeFraction").getAsFloat());
                        }
                        // §4.5 per-object lock (tolerant: absent = false).
                        if (hasValue(acObj, "objLocked")) ac.setLocked(acObj.get("objLocked").getAsBoolean());
                        project.getTimeline().addAudioClip(ac, false);
                      } catch (Exception ex) {
                        FLog.e(TAG, "Skipping malformed audio clip #" + i, ex);
                        project.addLoadSkip("Audio track #" + (i + 1));
                      }
                    }
                }
            }

            // Restore text overlays
            if (hasValue(obj, "timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (hasValue(tl, "textOverlays")) {
                    JsonArray ovArr = tl.getAsJsonArray("textOverlays");
                    for (int i = 0; i < ovArr.size(); i++) {
                      // Per-item fault tolerance (load-failure SHAPE fix). This is the
                      // exact site the repro hits: a text overlay with sizeFraction:null
                      // used to throw here (getAsFloat), abort the whole load, and drop
                      // the user to a silent .bak. Now it skips this one overlay only.
                      try {
                        JsonObject oObj = ovArr.get(i).getAsJsonObject();
                        com.fadcam.ui.faditor.model.TextOverlayItem o =
                                new com.fadcam.ui.faditor.model.TextOverlayItem(
                                        oObj.get("id").getAsString(),
                                        oObj.get("text").getAsString(),
                                        oObj.get("colorInt").getAsInt(),
                                        oObj.get("centerX").getAsFloat(),
                                        oObj.get("centerY").getAsFloat(),
                                        oObj.get("sizeFraction").getAsFloat(),
                                        hasValue(oObj, "rotationDeg")
                                                ? oObj.get("rotationDeg").getAsFloat() : 0f);
                        if (hasValue(oObj, "fontFamily")) {
                            o.setFontFamily(oObj.get("fontFamily").getAsString());
                        }
                        if (hasValue(oObj, "fx")) {
                            try {
                                o.setFx(com.fadcam.ui.faditor.fx.FxStack.fromJson(
                                        oObj.getAsJsonObject("fx")));
                            } catch (Exception ignored) {
                                // Tolerant read: a malformed stack costs the EFFECTS, not the
                                // overlay -- losing someone's caption over a bad effect would
                                // be a wildly disproportionate failure.
                            }
                        }
                        if (hasValue(oObj, "imageUri")) {
                            o.setImageUri(fromStorageUri(projectDir,
                                    oObj.get("imageUri").getAsString()).toString());
                        }
                        if (hasValue(oObj, "layerId") && !oObj.get("layerId").isJsonNull()) {
                            o.setLayerId(oObj.get("layerId").getAsString()); // audit 1.3
                        }
                        long startMs = hasValue(oObj, "startMs") ? oObj.get("startMs").getAsLong() : 0;
                        long endMs = hasValue(oObj, "endMs")
                                ? oObj.get("endMs").getAsLong() : Long.MAX_VALUE;
                        o.setTimeRange(startMs, endMs);
                        // Entrance/exit animation. Guarded reads with the model's own defaults,
                        // so a project written before this existed loads as NONE/BLOCK/0/0 — the
                        // off state — rather than needing a migration.
                        // Rider attachment. A DANGLING host id (its clip was deleted by a build
                        // that did not know about anchors) is read as-is and resolved at use time
                        // rather than dropped here — the orphan decision belongs to §4A's prompt,
                        // and silently detaching on load would answer it without asking.
                        if (hasValue(oObj, "hostClipId")) {
                            o.setHostAnchor(oObj.get("hostClipId").getAsString(),
                                    hasValue(oObj, "hostOffsetMs")
                                            ? oObj.get("hostOffsetMs").getAsLong() : 0L);
                        }
                        if (hasValue(oObj, "textAnimPreset")) {
                            o.setTextAnimPreset(oObj.get("textAnimPreset").getAsString());
                        }
                        if (hasValue(oObj, "textAnimGranularity")) {
                            o.setTextAnimGranularity(oObj.get("textAnimGranularity").getAsString());
                        }
                        if (hasValue(oObj, "textAnimInPct") || hasValue(oObj, "textAnimOutPct")) {
                            o.setTextAnimZonePct(
                                    hasValue(oObj, "textAnimInPct")
                                            ? oObj.get("textAnimInPct").getAsFloat() : 0f,
                                    hasValue(oObj, "textAnimOutPct")
                                            ? oObj.get("textAnimOutPct").getAsFloat() : 0f);
                        }
                        // Motion-range window (SPEC_TEXT_DRAWER follow-up, 2026-08-08) — the
                        // [startMs, endMs) window the entrance/exit zones evaluate against. Both
                        // keys must be present; a lone one would leave the model half-set, and
                        // hasMotionRange() already guards every consumer.
                        if (hasValue(oObj, "motionStartMs") && hasValue(oObj, "motionEndMs")) {
                            o.setMotionRange(oObj.get("motionStartMs").getAsLong(),
                                    oObj.get("motionEndMs").getAsLong());
                        }
                        if (hasValue(oObj, "strokeColorInt")) o.setStrokeColorInt(oObj.get("strokeColorInt").getAsInt());
                        if (hasValue(oObj, "strokeWidthPx")) o.setStrokeWidthPx(oObj.get("strokeWidthPx").getAsFloat());
                        if (hasValue(oObj, "shadowColorInt")) o.setShadowColorInt(oObj.get("shadowColorInt").getAsInt());
                        if (hasValue(oObj, "shadowRadiusPx")) o.setShadowRadiusPx(oObj.get("shadowRadiusPx").getAsFloat());
                        if (hasValue(oObj, "glowColorInt")) o.setGlowColorInt(oObj.get("glowColorInt").getAsInt());
                        if (hasValue(oObj, "glowRadiusPx")) o.setGlowRadiusPx(oObj.get("glowRadiusPx").getAsFloat());
                        if (hasValue(oObj, "backgroundColorInt")) o.setBackgroundColorInt(oObj.get("backgroundColorInt").getAsInt());
                        if (hasValue(oObj, "opacity")) o.setOpacity(oObj.get("opacity").getAsFloat());
                        // Image-overlay drawer state (M-IMG-1). Tolerant defaults mirror the
                        // model so a project written before these keys load exactly as it
                        // always did.
                        if (hasValue(oObj, "passThrough")) o.setPassThrough(oObj.get("passThrough").getAsBoolean());
                        if (hasValue(oObj, "scaleLinked")) o.setScaleLinked(oObj.get("scaleLinked").getAsBoolean());
                        if (hasValue(oObj, "scaleX")) o.setScaleX(oObj.get("scaleX").getAsFloat());
                        if (hasValue(oObj, "scaleY")) o.setScaleY(oObj.get("scaleY").getAsFloat());
                        if (hasValue(oObj, "overlayBlendMode")) o.setOverlayBlendMode(oObj.get("overlayBlendMode").getAsString());
                        if (hasValue(oObj, "compositing")) {
                            try {
                                o.setCompositing(com.fadcam.ui.faditor.model.CompositingSpec.fromJson(
                                        oObj.getAsJsonObject("compositing")));
                            } catch (Exception ignored) {
                                // Tolerant: lose the masks, keep the overlay.
                            }
                        }
                        if (hasValue(oObj, "keyframes")) {
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
                        if (hasValue(oObj, "generatedSource")) {
                            o.setGeneratedSource(deserializeGeneratedSource(
                                    oObj.getAsJsonObject("generatedSource")));
                        }
                        // §4.5 per-object eye/lock (tolerant: absent = false).
                        if (hasValue(oObj, "objHidden")) o.setHidden(oObj.get("objHidden").getAsBoolean());
                        if (hasValue(oObj, "objLocked")) o.setLocked(oObj.get("objLocked").getAsBoolean());
                        o.setTimerSpec(deserializeTimerSpec(oObj)); // absent = ordinary text
                        // W5-2 rich text spans (§3.8). Tolerant per-span read: a malformed
                        // span costs only that span's formatting, and out-of-range spans are
                        // re-clamped by the resolver's normalize at use time.
                        if (hasValue(oObj, "styleSpans")) {
                            try {
                                JsonArray spansArr = oObj.getAsJsonArray("styleSpans");
                                java.util.List<StyleSpan> spans = new java.util.ArrayList<>();
                                for (int s = 0; s < spansArr.size(); s++) {
                                    spans.add(StyleSpan.fromJson(
                                            spansArr.get(s).getAsJsonObject()));
                                }
                                o.setStyleSpans(spans);
                            } catch (Exception ignored) {
                                // Tolerant: lose the formatting, keep the overlay + text.
                            }
                        }
                        project.getTimeline().addTextOverlay(o);
                      } catch (Exception ex) {
                        FLog.e(TAG, "Skipping malformed text overlay #" + i, ex);
                        project.addLoadSkip("Text overlay #" + (i + 1));
                      }
                    }
                }
            }

            // Restore waveform visualizer overlays (schema v7)
            if (hasValue(obj, "timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (hasValue(tl, "waveformOverlays")) {
                    JsonArray wfArr = tl.getAsJsonArray("waveformOverlays");
                    for (int i = 0; i < wfArr.size(); i++) {
                      // Per-item fault tolerance (load-failure SHAPE fix).
                      try {
                        JsonObject wj = wfArr.get(i).getAsJsonObject();
                        String id = wj.has("id") ? wj.get("id").getAsString()
                                : java.util.UUID.randomUUID().toString();
                        String styleId = hasValue(wj, "styleId") ? wj.get("styleId").getAsString() : "neon_bars";
                        com.fadcam.ui.faditor.model.WaveformOverlayInstance wo =
                                new com.fadcam.ui.faditor.model.WaveformOverlayInstance(id, styleId);
                        if (hasValue(wj, "audioSourceRef")) {
                            wo.setAudioSourceRef(wj.get("audioSourceRef").getAsString());
                        }
                        long startMs = hasValue(wj, "startMs") ? wj.get("startMs").getAsLong() : 0;
                        long endMs = hasValue(wj, "endMs") ? wj.get("endMs").getAsLong() : Long.MAX_VALUE;
                        wo.setTimeRange(startMs, endMs);
                        if (hasValue(wj, "centerX") && hasValue(wj, "centerY")) {
                            wo.setCenter(wj.get("centerX").getAsFloat(), wj.get("centerY").getAsFloat());
                        }
                        if (hasValue(wj, "widthFraction") && hasValue(wj, "heightFraction")) {
                            wo.setSize(wj.get("widthFraction").getAsFloat(),
                                    wj.get("heightFraction").getAsFloat());
                        }
                        if (hasValue(wj, "rotationDeg")) {
                            wo.setRotationDeg(wj.get("rotationDeg").getAsFloat());
                        }
                        if (hasValue(wj, "justify")) wo.setJustify(wj.get("justify").getAsInt());
                        if (hasValue(wj, "dataMode")) wo.setDataMode(wj.get("dataMode").getAsInt());
                        if (hasValue(wj, "hMirror")) wo.setHorizontalMirror(wj.get("hMirror").getAsBoolean());
                        if (hasValue(wj, "centerMode")) wo.setCenterMode(wj.get("centerMode").getAsInt());
                        if (hasValue(wj, "renderMode")) wo.setRenderMode(wj.get("renderMode").getAsInt());
                        if (hasValue(wj, "radialRingSize")) wo.setRadialRingSize(wj.get("radialRingSize").getAsFloat());
                        if (hasValue(wj, "freqLowHz")) wo.setFrequencyRangeLowHz(wj.get("freqLowHz").getAsInt());
                        if (hasValue(wj, "freqHighHz")) wo.setFrequencyRangeHighHz(wj.get("freqHighHz").getAsInt());
                        if (hasValue(wj, "bandCount")) wo.setBandCountOverride(wj.get("bandCount").getAsInt());
                        if (hasValue(wj, "barWidthDp")) wo.setBarWidthOverrideDp(wj.get("barWidthDp").getAsFloat());
                        if (hasValue(wj, "barGapDp")) wo.setBarGapOverrideDp(wj.get("barGapDp").getAsFloat());
                        if (hasValue(wj, "colorOverride")) wo.setColorOverride(wj.get("colorOverride").getAsString());
                        if (hasValue(wj, "sensitivity")) wo.setSensitivityOverride(wj.get("sensitivity").getAsFloat());
                        if (hasValue(wj, "gradStart") && hasValue(wj, "gradEnd")) {
                            wo.setGradientOverride(wj.get("gradStart").getAsString(),
                                    wj.get("gradEnd").getAsString());
                        }
                        // SPEC_VIZ_ENGINE §4 (Layers UI lane): tolerant read, absent = null.
                        if (hasValue(wj, "customStyleJson") && !wj.get("customStyleJson").isJsonNull()) {
                            wo.setCustomStyleJson(wj.get("customStyleJson").getAsString());
                        }
                        // G5 attach/detach (tolerant, absent = detached)
                        if (hasValue(wj, "attachedClipId")) {
                            wo.setAttachedClipId(wj.get("attachedClipId").getAsString());
                            if (hasValue(wj, "attachOffsetMs")) {
                                wo.setAttachOffsetMs(wj.get("attachOffsetMs").getAsLong());
                            }
                            if (hasValue(wj, "attachDurationMs")) {
                                wo.setAttachDurationMs(wj.get("attachDurationMs").getAsLong());
                            }
                            if (hasValue(wj, "stratified")) {
                                wo.setStratified(wj.get("stratified").getAsBoolean());
                            }
                        }
                        // §4.5 per-object eye/lock (tolerant: absent = false).
                        if (hasValue(wj, "objHidden")) wo.setHidden(wj.get("objHidden").getAsBoolean());
                        if (hasValue(wj, "objLocked")) wo.setLocked(wj.get("objLocked").getAsBoolean());
                        project.getTimeline().addWaveformOverlay(wo);
                      } catch (Exception ex) {
                        FLog.e(TAG, "Skipping malformed waveform overlay #" + i, ex);
                        project.addLoadSkip("Audio visualizer #" + (i + 1));
                      }
                    }
                    // Attached windows re-derive from their hosts' CURRENT spans on load.
                    project.getTimeline().resyncAttachedVisualizers();
                }

                // G9 link groups (tolerant: absent = unlinked). Members that don't resolve to a
                // live payload are dropped; a group left <2 dissolves — logged in prune, never a
                // hard failure (PLAN_G9_LINK_ENGINE.md §2).
                if (hasValue(tl, "linkGroups")) {
                    JsonArray lgArr = tl.getAsJsonArray("linkGroups");
                    for (int i = 0; i < lgArr.size(); i++) {
                        try {
                            JsonObject gj = lgArr.get(i).getAsJsonObject();
                            String gid = gj.has("id") ? gj.get("id").getAsString()
                                    : java.util.UUID.randomUUID().toString();
                            com.fadcam.ui.faditor.layers.LinkGroup g =
                                    new com.fadcam.ui.faditor.layers.LinkGroup(gid);
                            if (hasValue(gj, "properties")) {
                                for (com.google.gson.JsonElement pe : gj.getAsJsonArray("properties")) {
                                    try {
                                        g.properties.add(com.fadcam.ui.faditor.layers
                                                .LinkedProperty.valueOf(pe.getAsString()));
                                    } catch (IllegalArgumentException ignored) {
                                        // Unknown future axis — drop it, keep the group.
                                    }
                                }
                            }
                            if (hasValue(gj, "members")) {
                                for (com.google.gson.JsonElement me : gj.getAsJsonArray("members")) {
                                    JsonObject mj = me.getAsJsonObject();
                                    if (!hasValue(mj, "kind") || !mj.has("id")) continue;
                                    com.fadcam.ui.faditor.layers.LinkMember m =
                                            new com.fadcam.ui.faditor.layers.LinkMember(
                                                    mj.get("kind").getAsString(),
                                                    mj.get("id").getAsString(),
                                                    hasValue(mj, "host") && mj.get("host").getAsBoolean());
                                    if (hasValue(mj, "hostOffsetMs")) {
                                        m.hostOffsetMs = mj.get("hostOffsetMs").getAsLong();
                                    }
                                    g.members.add(m);
                                }
                            }
                            if (g.members.size() >= 2) project.getTimeline().addLinkGroup(g);
                        } catch (Exception e) {
                            android.util.Log.w("ProjectStorage",
                                    "dropping unreadable linkGroup[" + i + "]: " + e.getMessage());
                        }
                    }
                }
                // One write-point pass + rebuild the transient G5-preset view (plan §3/§4.1).
                project.getTimeline().resyncLinkGroups();
                project.getTimeline().synthesizeG5PresetLinkGroups();
            }

            // Restore sprite-sheet definitions (schema v9, project level). Tolerant:
            // absent on every pre-v9 project. sheetUri comes back project://-relative
            // and is resolved to an absolute URI here (imageUri convention).
            if (hasValue(obj, "spriteSheets")) {
                JsonArray sheetsArr = obj.getAsJsonArray("spriteSheets");
                for (int i = 0; i < sheetsArr.size(); i++) {
                    try {
                        com.fadcam.ui.faditor.sprite.SpriteSheet sheet =
                                com.fadcam.ui.faditor.sprite.SpriteSheet.fromJson(
                                        sheetsArr.get(i).getAsJsonObject());
                        if (!sheet.getSheetUri().isEmpty()) {
                            sheet.setSheetUri(fromStorageUri(projectDir,
                                    sheet.getSheetUri()).toString());
                        }
                        if (sheet.isSequence()) {
                            java.util.List<String> abs = new java.util.ArrayList<>();
                            for (String u : sheet.getFrameUris()) {
                                abs.add(u == null || u.isEmpty() ? ""
                                        : fromStorageUri(projectDir, u).toString());
                            }
                            sheet.setSequenceFrames(abs);
                        }
                        project.getSpriteSheets().add(sheet);
                    } catch (Exception ignored) { }
                }
            }

            // Restore avatar rigs (schema v10, project level). Tolerant: absent pre-v10.
            if (hasValue(obj, "avatarRigs")) {
                JsonArray rigsArr = obj.getAsJsonArray("avatarRigs");
                for (int i = 0; i < rigsArr.size(); i++) {
                    try {
                        project.getAvatarRigs().add(
                                com.fadcam.ui.faditor.avatar.AvatarRig.fromJson(
                                        rigsArr.get(i).getAsJsonObject()));
                    } catch (Exception ignored) { }
                }
            }

            // Restore placed sprite overlays (schema v9, timeline level).
            if (hasValue(obj, "timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (hasValue(tl, "spriteOverlays")) {
                    JsonArray spArr = tl.getAsJsonArray("spriteOverlays");
                    for (int i = 0; i < spArr.size(); i++) {
                        try {
                            JsonObject sj = spArr.get(i).getAsJsonObject();
                            com.fadcam.ui.faditor.sprite.SpriteOverlayItem so =
                                    new com.fadcam.ui.faditor.sprite.SpriteOverlayItem(
                                            sj.get("id").getAsString(),
                                            sj.get("sheetId").getAsString());
                            if (hasValue(sj, "centerX") && hasValue(sj, "centerY")) {
                                so.setCenter(sj.get("centerX").getAsFloat(),
                                        sj.get("centerY").getAsFloat());
                            }
                            if (hasValue(sj, "sizeFraction")) so.setSizeFraction(sj.get("sizeFraction").getAsFloat());
                            if (hasValue(sj, "rotationDeg")) so.setRotationDeg(sj.get("rotationDeg").getAsFloat());
                            if (hasValue(sj, "opacity")) so.setOpacity(sj.get("opacity").getAsFloat());
                            if (hasValue(sj, "flipH")) so.setFlipH(sj.get("flipH").getAsBoolean());
                            if (hasValue(sj, "flipV")) so.setFlipV(sj.get("flipV").getAsBoolean());
                            long sStart = hasValue(sj, "startMs") ? sj.get("startMs").getAsLong() : 0;
                            long sEnd = hasValue(sj, "endMs") ? sj.get("endMs").getAsLong() : Long.MAX_VALUE;
                            so.setTimeRange(sStart, sEnd);
                            // Audit 1.3. Unlike the three above, this one sits inside a
                            // `catch (Exception ignored)`, so an explicit null does not
                            // break the load — it silently drops THIS SPRITE and moves on.
                            if (hasValue(sj, "layerId") && !sj.get("layerId").isJsonNull()) {
                                so.setLayerId(sj.get("layerId").getAsString());
                            }
                            if (hasValue(sj, "endBehavior")) so.setEndBehavior(sj.get("endBehavior").getAsString());
                            so.setContinuesUntilBlocked(
                                    sj.has("continues") && sj.get("continues").getAsBoolean());
                            if (sj.has("startFrame")) {
                                so.setSequenceStartFrame(sj.get("startFrame").getAsInt());
                            }
                            if (hasValue(sj, "avatarRigId")) {
                                so.setAvatarRigId(sj.get("avatarRigId").getAsString());
                            }
                            if (hasValue(sj, "avatarTrack") && sj.get("avatarTrack").isJsonObject()) {
                                com.fadcam.ui.faditor.avatar.AvatarParamTrack t =
                                        com.fadcam.ui.faditor.avatar.AvatarParamTrack
                                                .fromJson(sj.getAsJsonObject("avatarTrack"));
                                if (!t.isEmpty()) so.setAvatarTrack(t);
                            }
                            if (hasValue(sj, "frameTrack")) {
                                JsonArray ftArr = sj.getAsJsonArray("frameTrack");
                                for (int k = 0; k < ftArr.size(); k++) {
                                    JsonObject kj = ftArr.get(k).getAsJsonObject();
                                    long t = kj.get("t").getAsLong();
                                    if (hasValue(kj, "p")) {
                                        so.getFrameTrack().put(
                                                com.fadcam.ui.faditor.sprite.FrameTrack.Key
                                                        .ofPreset(t, kj.get("p").getAsString()));
                                    } else if (hasValue(kj, "c")) {
                                        so.getFrameTrack().put(
                                                com.fadcam.ui.faditor.sprite.FrameTrack.Key
                                                        .ofCell(t, kj.get("c").getAsInt()));
                                    }
                                }
                            }
                            if (hasValue(sj, "keyframes")) {
                                JsonObject tracksJson = sj.getAsJsonObject("keyframes");
                                for (java.util.Map.Entry<String, JsonElement> e
                                        : tracksJson.entrySet()) {
                                    com.fadcam.ui.faditor.keyframe.KeyframeTrack tr =
                                            so.getKeyframes().getOrCreate(e.getKey());
                                    JsonArray kfArr = e.getValue().getAsJsonArray();
                                    for (int k = 0; k < kfArr.size(); k++) {
                                        JsonObject kj = kfArr.get(k).getAsJsonObject();
                                        tr.put(kj.get("t").getAsLong(), kj.get("v").getAsFloat(),
                                                com.fadcam.ui.faditor.keyframe.Easing.fromName(
                                                        kj.get("e").getAsString()));
                                    }
                                }
                            }
                            // §4.5 per-object eye/lock (tolerant: absent = false).
                            if (hasValue(sj, "objHidden")) so.setHidden(sj.get("objHidden").getAsBoolean());
                            if (hasValue(sj, "objLocked")) so.setLocked(sj.get("objLocked").getAsBoolean());
                            project.getTimeline().addSpriteOverlay(so);
                        } catch (Exception ignored) { }
                    }
                }
            }

            // Restore transitions
            if (hasValue(obj, "timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (hasValue(tl, "transitions")) {
                    JsonArray transArr = tl.getAsJsonArray("transitions");
                    for (int i = 0; i < transArr.size(); i++) {
                        JsonObject tj = transArr.get(i).getAsJsonObject();
                        try {
                            com.fadcam.ui.faditor.model.Transition.Type type =
                                    com.fadcam.ui.faditor.model.Transition.Type.valueOf(
                                            tj.get("type").getAsString());
                            long dur = hasValue(tj, "durationMs") ? tj.get("durationMs").getAsLong() : 500;
                            int clipIdx = hasValue(tj, "clipIndex") ? tj.get("clipIndex").getAsInt() : 0;
                            float fuzz = hasValue(tj, "fuzziness") ? tj.get("fuzziness").getAsFloat() : 0f;
                            com.fadcam.ui.faditor.model.Transition transition =
                                    new com.fadcam.ui.faditor.model.Transition(type, dur, clipIdx, fuzz);
                            if (type == com.fadcam.ui.faditor.model.Transition.Type.GL_SHADER) {
                                transition.glTransitionId = hasValue(tj, "glTransitionId")
                                        ? tj.get("glTransitionId").getAsString() : "CrossZoom";
                                if (hasValue(tj, "paramOverrides")) {
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

            // Restore schema-v8 layer block (PLAN Part 2 + §4.2, extended M6). Additive,
            // .has()-guarded. The Track model itself is a synchronized view rebuilt from
            // the flat lists on access (see Timeline) — the masterTrack/layers/audioTracks
            // arrays are otherwise the dual-write mirror of the flat lists (payloads
            // referenced by id) and need no separate reconstruction. BUT per-track flags
            // (collapsed/hidden/locked/muted/zIndex) have no home on the flat lists, so M6
            // restores them here into Timeline's persistent trackFlags side-table
            // (Timeline.getOrCreateTrackFlags) keyed by each track's serialized id — the
            // same "master"/"text"/"audio" ids the view builders assign.
            if (hasValue(obj, "timeline")) {
                JsonObject tl = obj.getAsJsonObject("timeline");
                if (hasValue(tl, "rippleMode")) {
                    project.getTimeline().setRippleMode(tl.get("rippleMode").getAsString());
                }
                if (hasValue(tl, "layers")) {
                    JsonObject layersBlock = tl.getAsJsonObject("layers");
                    // M10: restore user-created track DEFINITIONS first — Timeline.getLayers()/
                    // getAudioTracks() need these present so a still-EMPTY user-created track
                    // (no items yet) still shows up as a track after reload, not just tracks
                    // that happen to have items on the flat lists.
                    if (hasValue(layersBlock, "trackDefs")) {
                        for (JsonElement e : layersBlock.getAsJsonArray("trackDefs")) {
                            JsonObject dj = e.getAsJsonObject();
                            if (!dj.has("id") || !hasValue(dj, "kind")) continue;
                            com.fadcam.ui.faditor.layers.TrackKind kind =
                                    com.fadcam.ui.faditor.layers.TrackKind.fromName(dj.get("kind").getAsString());
                            String defName = hasValue(dj, "name") ? dj.get("name").getAsString() : "Layer";
                            project.getTimeline().restoreLayerTrackDef(
                                    new com.fadcam.ui.faditor.layers.LayerTrackDef(
                                            dj.get("id").getAsString(), kind, defName));
                        }
                    }
                    if (hasValue(layersBlock, "masterTrack")) {
                        restoreTrackFlags(project.getTimeline(),
                                layersBlock.getAsJsonObject("masterTrack"));
                    }
                    if (hasValue(layersBlock, "layers")) {
                        for (JsonElement e : layersBlock.getAsJsonArray("layers")) {
                            restoreTrackFlags(project.getTimeline(), e.getAsJsonObject());
                        }
                    }
                    if (hasValue(layersBlock, "audioTracks")) {
                        for (JsonElement e : layersBlock.getAsJsonArray("audioTracks")) {
                            restoreTrackFlags(project.getTimeline(), e.getAsJsonObject());
                        }
                    }
                    // PHASE-P P1: restore default-track renames into the flags side-table.
                    if (hasValue(layersBlock, "trackNames")) {
                        JsonObject namesObj = layersBlock.getAsJsonObject("trackNames");
                        for (java.util.Map.Entry<String, JsonElement> e : namesObj.entrySet()) {
                            String nm = e.getValue().getAsString();
                            if (nm != null && !nm.isEmpty()) {
                                project.getTimeline()
                                        .getOrCreateTrackFlags(e.getKey()).customName = nm;
                            }
                        }
                    }
                }
                // Stage 1 P0 fix follow-up: drop any restored trackFlags entry whose id
                // provably matches no current track (see Timeline#pruneOrphanedTrackFlags's
                // doc — conservative, only removes ids that cannot possibly resolve).
                // Run once here, after every flat list AND the flags themselves are fully
                // populated, so the check has everything it needs. Logged, not silent.
                List<String> droppedFlagIds = project.getTimeline().pruneOrphanedTrackFlags();
                if (!droppedFlagIds.isEmpty()) {
                    FLog.w(TAG, "Dropped " + droppedFlagIds.size()
                            + " orphaned trackFlags entry/entries on load (no matching track): "
                            + droppedFlagIds);
                }
            }
            // Stage 1 P0 fix: capture the concurrent-instance merge guard's baseline
            // now that trackFlags is in its final post-migration/post-prune state.
            // Applies uniformly everywhere this deserializer runs (a plain load, a
            // backup recovery, or an undo/redo snapshot restore) — each produces a
            // brand-new Timeline, and whichever JSON populated THIS ONE is correctly
            // "the state further edits get compared against" for that Timeline's
            // lifetime, matching ProjectStorage#mergeTrackFlagsIfStale's needs.
            project.getTimeline().snapshotBaselineTrackFlags();

            // Restore canvas preset
            if (hasValue(obj, "canvasPreset")) {
                project.setCanvasPreset(obj.get("canvasPreset").getAsString());
            }

            // Restore pinned asset directory (schema v3+, safe defaults for older)
            if (hasValue(obj, "pinnedAssetDir")) {
                project.setPinnedAssetDir(obj.get("pinnedAssetDir").getAsString());
            }
            if (hasValue(obj, "assetDirHistory")) {
                for (JsonElement e : obj.getAsJsonArray("assetDirHistory")) {
                    String s = e.getAsString();
                    if (!project.getAssetDirHistory().contains(s)) {
                        project.getAssetDirHistory().add(s);
                    }
                }
            }

            // Restore export settings
            if (hasValue(obj, "exportSettings")) {
                JsonObject expObj = obj.getAsJsonObject("exportSettings");
                ExportSettings settings = project.getExportSettings();
                if (hasValue(expObj, "resolution")) {
                    try {
                        settings.setResolution(
                                ExportSettings.Resolution.valueOf(
                                        expObj.get("resolution").getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (hasValue(expObj, "quality")) {
                    try {
                        settings.setQuality(
                                ExportSettings.Quality.valueOf(
                                        expObj.get("quality").getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (hasValue(expObj, "format")) {
                    try {
                        settings.setFormat(
                                ExportSettings.Format.valueOf(
                                        expObj.get("format").getAsString()));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (hasValue(expObj, "cleanAudio")) {
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
