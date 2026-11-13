package com.fadcam.ui.library;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.util.HashSet;
import java.util.Set;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A project's layer count and running time: the two numbers the lobby's hero shows under the last
 * project ("6 LAYERS · 0:47"), so a project reads the same on the front door and in the Library.
 *
 * <p>Knowing them means parsing the whole project, which can run to megabytes. So they are read in
 * the Library's slow pass, never before its first paint, and kept for as long as the project stays
 * as it is: each answer is filed under the project's last save time. Parsing goes through
 * {@link ProjectStorage#fromJson}, which only reads; {@code load} also heals and migrates the file
 * on disk, and a list has no business writing to a project.</p>
 */
final class ProjectFacts {

    private static final String TAG = "LibraryFacts";

    /** What one save of a project holds; -1 where it could not be read. */
    static final class Facts {
        final long stamp;
        final int layers;
        final long durationMs;
        /**
         * What the project takes up (SHELL-26, 11-13): its own working copy in the app plus every distinct file
         * its clips and sounds use, wherever they live. A file two projects share counts in both, so these do
         * not add up to the phone's total (Space has that). -1 when unknown.
         */
        final long bytes;

        Facts(long stamp, int layers, long durationMs, long bytes) {
            this.stamp = stamp;
            this.layers = layers;
            this.durationMs = durationMs;
            this.bytes = bytes;
        }
    }

    /** Project id to the facts of its last save read. Lives as long as the app does. */
    private static final Map<String, Facts> KNOWN = new ConcurrentHashMap<>();

    private ProjectFacts() {}

    /** The facts of this exact save, when already read; else null. Cheap, any thread. */
    @Nullable
    static Facts known(@NonNull ProjectStorage.ProjectSummary p) {
        Facts f = KNOWN.get(p.id);
        return f != null && f.stamp == p.lastModified ? f : null;
    }

    /** Read every project whose current save is not known yet. Slow: the Library's reading thread only. */
    static void read(@NonNull Context c, @NonNull ProjectStorage storage,
                     @NonNull List<ProjectStorage.ProjectSummary> projects) {
        for (ProjectStorage.ProjectSummary p : projects) {
            if (known(p) == null) KNOWN.put(p.id, readOne(c, storage, p));
        }
    }

    @NonNull
    private static Facts readOne(@NonNull Context c, @NonNull ProjectStorage storage,
                                 @NonNull ProjectStorage.ProjectSummary p) {
        long own = sizeOf(storage.projectDir(p.id));
        try {
            File json = new File(storage.projectDir(p.id), PartsReader.PROJECT_JSON);
            FaditorProject project = json.isFile() ? storage.fromJson(PartsReader.readText(json)) : null;
            Timeline t = project == null ? null : project.getTimeline();
            if (t != null) {
                // "Layers" as the lobby counts them: everything stacked ON the main track. The
                // track's own clips are the movie, not a layer of it.
                int layers = t.getOverlayClips().size() + t.getTextOverlays().size()
                        + t.getSpriteOverlays().size() + t.getAudioClips().size()
                        + t.getAdjustmentLayers().size() + t.getWaveformOverlays().size();
                Set<String> media = new HashSet<>();
                for (com.fadcam.ui.faditor.model.Clip k : t.getClips()) add(media, k.getSourceUri());
                for (com.fadcam.ui.faditor.model.Clip k : t.getOverlayClips()) add(media, k.getSourceUri());
                for (com.fadcam.ui.faditor.model.AudioClip k : t.getAudioClips()) add(media, k.getSourceUri());
                long bytes = own;
                for (String u : media) bytes += Math.max(0L, sizeOf(c, Uri.parse(u)));
                return new Facts(p.lastModified, layers, t.getTotalDurationMs(), bytes);
            }
        } catch (Throwable e) {
            // A project too big or too broken to parse still has a name, a picture and an age.
            FLog.w(TAG, "no layers or running time for " + p.id, e);
        }
        // Filed as unknown, so it is not parsed again until the project is next saved.
        return new Facts(p.lastModified, -1, -1, own);
    }

    private static void add(@NonNull Set<String> into, @Nullable Uri u) {
        if (u != null) into.add(u.toString());
    }

    /** A folder's files, all the way down; 0 when it is not there. */
    private static long sizeOf(@Nullable File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return f.length();
        long n = 0L;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += sizeOf(k);
        return n;
    }

    /** One file's size by its link: a path's length, or what its provider says; -1 when gone or unknown. */
    private static long sizeOf(@NonNull Context c, @NonNull Uri u) {
        String scheme = u.getScheme();
        if (scheme == null || "file".equals(scheme)) {
            String path = u.getPath();
            File f = path == null ? null : new File(path);
            return f != null && f.isFile() ? f.length() : -1L;
        }
        if (!"content".equals(scheme)) return -1L;
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {
            // a link whose permission lapsed or whose file is gone: not counted
        }
        return -1L;
    }
}
