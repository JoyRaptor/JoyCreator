package com.fadcam.ui.library;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;
import com.fadcam.joyfolder.studio.StudioMirror;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.utils.TrashManager;

import java.util.Collections;
import java.util.Set;

/**
 * What can be done to a thing from its long-press sheet, and doing it. Each action is offered ONLY
 * where a helper the app already trusts makes it safe; anything else is left out rather than
 * half-built:
 *
 * <table>
 *   <tr><td>Rename</td><td>drawings ({@code DrawingLibrary.rename})</td></tr>
 *   <tr><td>Share</td><td>any single file: folder files, older recordings, a sheet's picture, a font</td></tr>
 *   <tr><td>Move to Trash</td><td>folder files no project uses ({@code StudioMirror.trashFiles}, the
 *       Space screen's path); projects with a folder copy (the Studio list's delete); drawings
 *       ({@code DrawingLibrary.delete}); recordings outside the folder ({@code TrashManager}, the
 *       classic Records path). All of these land in the one Trash and can be put back.</td></tr>
 * </table>
 *
 * Not offered, because nothing safe exists yet: renaming projects, folder files and recordings;
 * trashing sprite sheets, characters and fonts (their only deletes are permanent or in-editor).
 */
final class LibraryActions {

    interface Done {
        /** On the main thread: a sentence for the user, and whether the list should be read again. */
        void onDone(@NonNull String message, boolean changed);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LibraryActions() {}

    static boolean canRename(@NonNull LibraryItem it) {
        return it.kind == LibraryKind.DRAWINGS && it.drawingFile != null;
    }

    static boolean canShare(@NonNull LibraryItem it) {
        return it.uri != null && it.kind != LibraryKind.PROJECTS && it.fileKind != FileKind.PLUGIN;
    }

    static boolean canTrash(@NonNull LibraryItem it) {
        if (it.kind == LibraryKind.PROJECTS) return it.projectId != null || it.folderPath != null;
        if (it.kind == LibraryKind.DRAWINGS) return it.drawingFile != null;
        return it.folderPath != null || it.captureCategory != null;
    }

    static void share(@NonNull Context c, @NonNull LibraryItem it) {
        Uri uri = LibraryOpener.shareable(c, it.uri);
        if (uri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND).setType(LibraryOpener.mimeOf(it))
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(send, it.name));
    }

    static void rename(@NonNull Context c, @NonNull LibraryItem it, @NonNull String newName, @NonNull Done done) {
        final Context app = c.getApplicationContext();
        JoyFolderHome.io().execute(() -> {
            boolean ok = false;
            try {
                ok = cc.joycreator.joybrush.android.library.DrawingLibrary.rename(app, it.drawingFile, newName);
            } catch (Exception e) {
                FLog.w("LibraryActions", "rename failed", e);
            }
            final boolean renamed = ok;
            MAIN.post(() -> done.onDone(app.getString(renamed ? R.string.library_renamed : R.string.library_rename_failed), renamed));
        });
    }

    static void trash(@NonNull Context c, @NonNull LibraryItem it, @NonNull Done done) {
        final Context app = c.getApplicationContext();
        JoyFolderHome.io().execute(() -> {
            String msg;
            boolean changed = false;
            try {
                msg = trashNow(app, it);
                changed = msg == null;
            } catch (Exception e) {
                FLog.w("LibraryActions", "trash failed for " + it.key, e);
                msg = app.getString(R.string.library_trash_failed, String.valueOf(e.getMessage()));
            }
            final String m = msg == null ? app.getString(R.string.library_trashed) : msg;
            final boolean ch = changed;
            MAIN.post(() -> done.onDone(m, ch));
        });
    }

    /** On the folder thread. Null when it went to Trash, else the reason in words. */
    @Nullable
    private static String trashNow(@NonNull Context app, @NonNull LibraryItem it) throws Exception {
        if (it.kind == LibraryKind.DRAWINGS) {
            String why = cc.joycreator.joybrush.android.library.DrawingLibrary.delete(app, it.drawingFile);
            return why == null ? null : app.getString(R.string.library_trash_failed, why);
        }
        if (it.kind == LibraryKind.PROJECTS && !it.needsRestore) {
            // The Studio list's delete: the folder copy goes to Trash. Refused when there is no folder
            // copy to go there, because then the delete could not be undone.
            if (!JoyFolderHome.isReachable(app)) return app.getString(R.string.library_trash_no_folder);
            if (StudioMirror.folderPathOf(app, it.projectId) == null) return app.getString(R.string.library_trash_no_copy);
            new ProjectStorage(app).delete(it.projectId);
            return null;
        }
        if (it.folderPath != null) {
            if (!JoyFolderHome.isReachable(app)) return app.getString(R.string.library_trash_no_folder);
            SafJoyFs saf = JoyFolderHome.fsOf(JoyFolderHome.open(app));
            Uri doc = saf == null ? null : saf.documentUri(it.folderPath);
            if (doc != null && it.kind != LibraryKind.PROJECTS) {
                Set<String> used = StudioMirror.usedByAnyProject(app);
                if (used.contains(StudioMirror.usageKey(doc.toString()))) return app.getString(R.string.library_trash_in_use);
            }
            if (StudioMirror.trashFiles(app, Collections.singletonList(it.folderPath)) == 0) {
                return app.getString(R.string.library_trash_failed, it.name);
            }
            if (doc != null && (it.isVideo() || it.isImage())) {
                VideoIndexRepository.getInstance(app).removeFromIndex(doc.toString());
            }
            return null;
        }
        if (it.captureCategory != null && it.uri != null) {
            Uri u = Uri.parse(it.uri);
            String name = it.fileName != null ? it.fileName : it.name;
            if (!TrashManager.moveToTrash(app, u, name, "content".equals(u.getScheme()))) {
                return app.getString(R.string.library_trash_failed, name);
            }
            VideoIndexRepository.getInstance(app).removeFromIndex(it.uri);
            return null;
        }
        return app.getString(R.string.library_trash_failed, it.name);
    }
}
