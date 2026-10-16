package com.fadcam.ui.library;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;

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

        Facts(long stamp, int layers, long durationMs) {
            this.stamp = stamp;
            this.layers = layers;
            this.durationMs = durationMs;
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
    static void read(@NonNull ProjectStorage storage, @NonNull List<ProjectStorage.ProjectSummary> projects) {
        for (ProjectStorage.ProjectSummary p : projects) {
            if (known(p) == null) KNOWN.put(p.id, readOne(storage, p));
        }
    }

    @NonNull
    private static Facts readOne(@NonNull ProjectStorage storage, @NonNull ProjectStorage.ProjectSummary p) {
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
                return new Facts(p.lastModified, layers, t.getTotalDurationMs());
            }
        } catch (Throwable e) {
            // A project too big or too broken to parse still has a name, a picture and an age.
            FLog.w(TAG, "no layers or running time for " + p.id, e);
        }
        // Filed as unknown, so it is not parsed again until the project is next saved.
        return new Facts(p.lastModified, -1, -1);
    }
}
