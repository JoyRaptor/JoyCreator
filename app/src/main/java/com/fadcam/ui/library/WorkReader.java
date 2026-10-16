package com.fadcam.ui.library;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.FLog;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.util.ArrayList;
import java.util.List;

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

    private WorkReader() {}

    @NonNull
    static List<LibraryItem> projects(@NonNull List<ProjectStorage.ProjectSummary> summaries) {
        List<LibraryItem> out = new ArrayList<>(summaries.size());
        for (ProjectStorage.ProjectSummary p : summaries) {
            LibraryItem it = new LibraryItem("project:" + p.id, LibraryKind.PROJECTS, p.name,
                    p.lastModified, -1, false);
            it.fileKind = FileKind.PROJECT;
            it.projectId = p.id;
            it.thumb = p.videoUri;
            out.add(it);
        }
        return out;
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
