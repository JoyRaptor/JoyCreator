package com.fadcam.ui.library;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.FLog;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.studio.SourceName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Projects and drawings, read from the app's own working copies: the same two lists the lobby's
 * recents and its Joy Brush room read, so the Library and the front door always agree.
 *
 * <p>The working copies are the ones that open. A project's or drawing's copy in the Joy Creator
 * folder is the same piece of work, so {@link FolderReader} leaves those out (a project the app
 * does not have yet is the one exception it keeps). App-private files only: cheap, any thread but
 * the main one.</p>
 */
final class WorkReader {

    private static final String TAG = "LibraryWork";
    /** The name ProjectStorage gives a project nobody named: a marker in the data, not words on screen. */
    private static final String UNTITLED = "Untitled";
    /** A video link to its file's name ("" when it has none): a content:// one costs a query. */
    private static final Map<String, String> FILE_NAMES = new ConcurrentHashMap<>();

    private WorkReader() {}

    /** One card per project, with its layers and running time when {@link ProjectFacts} knows them. */
    @NonNull
    static List<LibraryItem> projects(@NonNull Context c, @NonNull List<ProjectStorage.ProjectSummary> summaries) {
        List<LibraryItem> out = new ArrayList<>(summaries.size());
        for (ProjectStorage.ProjectSummary p : summaries) {
            LibraryItem it = new LibraryItem("project:" + p.id, LibraryKind.PROJECTS, nameOf(c, p),
                    p.lastModified, -1, false);
            it.fileKind = FileKind.PROJECT;
            it.projectId = p.id;
            it.thumb = p.videoUri;
            ProjectFacts.Facts facts = ProjectFacts.known(p);
            if (facts != null) {
                it.layers = facts.layers;
                it.durationMs = facts.durationMs;
            }
            out.add(it);
        }
        return out;
    }

    /**
     * The classic Studio list's rule: a project never given a name is called after its first
     * video's file, so five projects cut from five videos read as five names, not five "Untitled".
     */
    @NonNull
    private static String nameOf(@NonNull Context c, @NonNull ProjectStorage.ProjectSummary p) {
        String n = p.name.trim();
        if ((!n.isEmpty() && !UNTITLED.equals(n)) || p.videoUri == null) return n;
        String file = FILE_NAMES.get(p.videoUri);
        if (file == null) {
            String read = SourceName.of(c, p.videoUri);
            file = read == null ? "" : read;
            FILE_NAMES.put(p.videoUri, file);
        }
        return file.isEmpty() ? n : file;
    }

    @NonNull
    static List<LibraryItem> drawings(@NonNull Context c) {
        List<LibraryItem> out = new ArrayList<>();
        try {
            for (cc.joycreator.joybrush.android.library.DrawingLibrary.Drawing d
                    : cc.joycreator.joybrush.android.library.DrawingLibrary.list(c)) {
                LibraryItem it = new LibraryItem("drawing:" + d.file, LibraryKind.DRAWINGS, d.name,
                        d.modified, -1, false);
                it.fileKind = FileKind.DRAWING;
                it.drawingFile = d.file;
                it.thumb = d.thumbPath;
                out.add(it);
            }
        } catch (Exception e) {
            FLog.w(TAG, "drawing list failed", e);
        }
        return out;
    }
}
