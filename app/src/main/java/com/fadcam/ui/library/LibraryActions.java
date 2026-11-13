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
import com.fadcam.ui.RecordsMediaActions;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.utils.TrashManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * What can be done to a thing from its long-press sheet, and doing it. Each action is offered ONLY
 * where a helper the app already trusts makes it safe; anything else is left out rather than
 * half-built:
 *
 * <table>
 *   <tr><td>Rename</td><td>drawings ({@code DrawingLibrary.rename}); Studio projects (the editor's own
 *       rename: load, set the name, save, which also updates the folder copy)</td></tr>
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
        if (it.kind == LibraryKind.PROJECTS) return it.projectId != null;
        if (LibraryGlance.isTextLabLook(it)) return true;   // a TextLab look: its moving preview follows the name
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

    /** A TextLab look: export it as a picture, video or moving sheet, as the room's export button does. */
    static boolean canExportLook(@NonNull LibraryItem it) {
        return LibraryGlance.isTextLabLook(it);
    }

    static void exportLook(@NonNull android.app.Activity a, @NonNull LibraryItem it) {
        JoyFolderHome.io().execute(() -> {
            org.json.JSONObject look = null;
            try {
                com.fadcam.joyfolder.JoyFolder jf = JoyFolderHome.open(a);
                if (jf != null) look = new org.json.JSONObject(new String(jf.readAll(it.folderPath), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                FLog.w("LibraryActions", "look not read for export: " + it.folderPath, e);
            }
            final org.json.JSONObject l = look;
            MAIN.post(() -> {
                if (l == null || a.isFinishing()) return;
                cc.joycreator.textlab.LookExport.ask(a, l, l.optString("sample", it.name));
            });
        });
    }

    /** Recordings and other videos only, the same files the classic Records could save. */
    static boolean canSaveToGallery(@NonNull LibraryItem it) {
        return it.uri != null && it.isVideo();
    }

    static boolean canOpenInStudio(@NonNull LibraryItem it) {
        return it.uri != null && it.isVideo();
    }

    /** Queues a copy into Downloads/FadCam through the classic Records' own service; says so at once. */
    static void saveToGallery(@NonNull Context c, @NonNull LibraryItem it, @NonNull Done done) {
        com.fadcam.service.RecordsDeletionRequestItem q = RecordsMediaActions.requestItem(
                Uri.parse(it.uri), it.fileName != null ? it.fileName : it.name, it.size);
        if (q == null) { done.onDone(c.getString(R.string.library_save_gallery_failed), false); return; }
        RecordsMediaActions.saveToGallery(c, Collections.singletonList(q), false);
        done.onDone(c.getString(R.string.library_save_gallery_queued), false);
    }

    /**
     * Batch save: every video among {@code its} whose file is really there, queued as one session.
     * Says how many at once, and (SHELL-11, 11-13) how many it left out and why: a chosen item that
     * is not a video, or a stale entry whose file is gone, used to vanish from the count unexplained.
     * The files are looked at on the folder thread.
     */
    static void saveAllToGallery(@NonNull Context c, @NonNull List<LibraryItem> its, @NonNull Done done) {
        final Context app = c.getApplicationContext();
        final List<LibraryItem> chosen = new ArrayList<>(its);
        JoyFolderHome.io().execute(() -> {
            List<com.fadcam.service.RecordsDeletionRequestItem> q = new ArrayList<>();
            int notVideo = 0, missing = 0;
            for (LibraryItem it : chosen) {
                if (!canSaveToGallery(it)) { notVideo++; continue; }
                Uri u = Uri.parse(it.uri);
                if (!fileIsThere(app, u)) { missing++; continue; }
                com.fadcam.service.RecordsDeletionRequestItem r = RecordsMediaActions.requestItem(
                        u, it.fileName != null ? it.fileName : it.name, it.size);
                if (r != null) q.add(r); else missing++;
            }
            final String m;
            if (q.isEmpty()) {
                m = missing > 0
                        ? app.getResources().getQuantityString(R.plurals.library_batch_save_none_there, missing, missing)
                        : app.getString(R.string.library_save_gallery_failed);
            } else {
                String said = app.getResources().getQuantityString(R.plurals.library_batch_save_queued, q.size(), q.size());
                if (missing > 0) said += " " + app.getResources().getQuantityString(R.plurals.library_batch_save_skipped_missing, missing, missing);
                if (notVideo > 0) said += " " + app.getResources().getQuantityString(R.plurals.library_batch_save_skipped_not_video, notVideo, notVideo);
                m = said;
            }
            final List<com.fadcam.service.RecordsDeletionRequestItem> go = q;
            MAIN.post(() -> {
                if (!go.isEmpty()) RecordsMediaActions.saveToGallery(app, go, false);
                done.onDone(m, false);
            });
        });
    }

    /** True when the file behind {@code u} can be opened for reading now. Folder thread. */
    static boolean fileIsThere(@NonNull Context c, @NonNull Uri u) {
        if ("file".equals(u.getScheme()) || u.getScheme() == null) {
            String p = u.getPath();
            return p != null && new java.io.File(p).isFile();
        }
        try (android.os.ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(u, "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Batch Move to Trash: the same per-item path as {@link #trash}, one after another on the folder
     * thread, ONE message at the end. Items that cannot be trashed are skipped and counted.
     */
    static void trashAll(@NonNull Context c, @NonNull List<LibraryItem> its, @NonNull Done done) {
        final Context app = c.getApplicationContext();
        JoyFolderHome.io().execute(() -> {
            int moved = 0, failed = 0;
            String why = null;
            for (LibraryItem it : its) {
                if (!canTrash(it)) { failed++; continue; }
                try {
                    String msg = trashNow(app, it);
                    if (msg == null) moved++;
                    else { failed++; if (why == null) why = msg; }
                } catch (Exception e) {
                    FLog.w("LibraryActions", "trash failed for " + it.key, e);
                    failed++;
                    if (why == null) why = app.getString(R.string.library_trash_failed, String.valueOf(e.getMessage()));
                }
            }
            final String m;
            if (failed == 0) m = app.getResources().getQuantityString(R.plurals.library_batch_trashed, moved, moved);
            else if (moved == 0) m = why != null ? why : app.getString(R.string.library_trash_failed, "");
            else m = app.getString(R.string.library_batch_trashed_some, moved, failed, why != null ? why : "");
            final boolean ch = moved > 0;
            MAIN.post(() -> done.onDone(m, ch));
        });
    }

    /** The Studio editor with this recording as its clip, exactly as the classic Records opened it. */
    static void openInStudio(@NonNull Context c, @NonNull LibraryItem it) {
        RecordsMediaActions.openInStudio(c, Collections.singletonList(Uri.parse(it.uri)));
    }

    /** Details of a video: the classic Records' own info sheet (size, codec, fps, bitrate, place). */
    static boolean canShowDetails(@NonNull LibraryItem it) {
        return it.uri != null && it.isVideo();
    }

    static void showDetails(@NonNull Context c, @NonNull LibraryItem it) {
        if (!(c instanceof androidx.fragment.app.FragmentActivity)) return;
        try {
            com.fadcam.ui.VideoItem v = new com.fadcam.ui.VideoItem(Uri.parse(it.uri),
                    it.fileName != null ? it.fileName : it.name, it.size, it.modified);
            com.fadcam.ui.VideoInfoBottomSheet.newInstance(v).show(
                    ((androidx.fragment.app.FragmentActivity) c).getSupportFragmentManager(), "video_info_bottom_sheet");
        } catch (Exception e) {
            FLog.w("LibraryActions", "details failed", e);
            android.widget.Toast.makeText(c, R.string.records_error_video_info, android.widget.Toast.LENGTH_SHORT).show();
        }
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
                if (it.kind == LibraryKind.PROJECTS) {
                    // The same three steps as the editor's title rename; save() mirrors the folder copy.
                    ProjectStorage ps = new ProjectStorage(app);
                    com.fadcam.ui.faditor.model.FaditorProject p = ps.load(it.projectId);
                    if (p != null) { p.setName(newName); ok = ps.save(p); }
                } else if (LibraryGlance.isTextLabLook(it)) {
                    ok = cc.joycreator.textlab.TlLookFiles.renamePath(app, it.folderPath, newName).ok();
                } else {
                    ok = cc.joycreator.joybrush.android.library.DrawingLibrary.rename(app, it.drawingFile, newName);
                }
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
            // a TextLab look goes to Trash with its moving preview (one item to the person)
            java.util.List<String> paths = LibraryGlance.isTextLabLook(it)
                    ? cc.joycreator.textlab.TlLookFiles.withSheet(it.folderPath) : Collections.singletonList(it.folderPath);
            if (StudioMirror.trashFiles(app, paths) == 0) {
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
