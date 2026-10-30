package com.fadcam.ui.library;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.studio.StudioMirror;
import com.fadcam.ui.ImageViewerActivity;
import com.fadcam.ui.VideoPlayerActivity;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.avatar.AvatarStudioActivity;
import com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity;

import java.io.File;
import java.util.Locale;

/**
 * Where tapping a thing takes you. Every route is one the app already uses (the lobby's openers and
 * the classic Records screen's viewers); nothing here invents a new door.
 *
 * <ul>
 *   <li>Project: the Studio editor (a folder-only project is brought back into the app first,
 *       by the same restore a reinstall uses).</li>
 *   <li>Drawing: Joy Brush. Sprite sheet: Sprite Lab, on that sheet. Character: Avatar Studio.</li>
 *   <li>VisLab look: VisLab, on that look (before 10-28 it went to whichever app opens JSON).</li>
 *   <li>Recording, export, clip: the app's player. Photo, image: the app's viewer.</li>
 *   <li>Sound, document, anything else that is a file: whichever app on the phone opens it.</li>
 * </ul>
 */
final class LibraryOpener {

    private LibraryOpener() {}

    /** @return false when nothing opens it here, so the caller can show its details instead */
    static boolean open(@NonNull Activity a, @NonNull LibraryItem it) {
        try {
            switch (it.kind) {
                case PROJECTS:
                    if (it.needsRestore) restoreThenOpen(a, it.projectId);
                    else openProject(a, it.projectId);
                    return true;
                case DRAWINGS:
                    a.startActivity(cc.joycreator.joybrush.android.library.DrawingLibrary.openIntent(a, it.drawingFile));
                    return true;
                case SPRITE_SHEETS:
                    if (it.projectId == null) return openWithOtherApp(a, it);
                    a.startActivity(new Intent(a, SpriteSheetEditorActivity.class)
                            .putExtra(SpriteSheetEditorActivity.EXTRA_PROJECT_ID, it.projectId)
                            .putExtra(SpriteSheetEditorActivity.EXTRA_SHEET_ID, it.partId));
                    return true;
                case AVATARS:
                    return openAvatar(a, it);
                default:
                    if (it.fileKind == FileKind.FONT || it.fileKind == FileKind.PLUGIN) return false;
                    FLog.i("LibraryOpener", "open " + it.key + " kind=" + it.kind + " fileKind=" + it.fileKind
                            + " path=" + it.folderPath);
                    // VisLab's own looks only: a .grade.json or .caption.json is a LOOK too, and not VisLab's.
                    if (it.folderPath != null && it.folderPath.toLowerCase(java.util.Locale.ROOT).endsWith(".vis.json")) {
                        a.startActivity(new Intent(a, com.fadcam.vislab.room.VisLabActivity.class)
                                .putExtra(com.fadcam.vislab.room.VisLabActivity.EXTRA_LOOK_FILE, it.folderPath));
                        return true;
                    }
                    if (it.isVideo() || it.isImage()) return openMedia(a, it);
                    return openWithOtherApp(a, it);
            }
        } catch (Exception e) {
            FLog.w("LibraryOpener", "open failed for " + it.key, e);
            Toast.makeText(a, R.string.library_open_failed, Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    private static void openProject(@NonNull Context c, @NonNull String projectId) {
        c.startActivity(new Intent(c, FaditorEditorActivity.class)
                .putExtra(FaditorEditorActivity.EXTRA_PROJECT_ID, projectId));
    }

    /** A project only the folder has: StudioMirror's reinstall restore brings it in, then it opens. */
    private static void restoreThenOpen(@NonNull Activity a, @NonNull String projectId) {
        Toast.makeText(a, R.string.library_restoring, Toast.LENGTH_SHORT).show();
        final Context app = a.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        JoyFolderHome.io().execute(() -> {
            boolean ok;
            try {
                StudioMirror.restoreMissing(app, null);
                ok = new com.fadcam.ui.faditor.project.ProjectStorage(app).exists(projectId);
            } catch (Exception e) {
                FLog.w("LibraryOpener", "restore failed for " + projectId, e);
                ok = false;
            }
            final boolean done = ok;
            main.post(() -> {
                if (a.isFinishing()) return;
                if (done) openProject(a, projectId);
                else Toast.makeText(a, R.string.library_restore_failed, Toast.LENGTH_LONG).show();
            });
        });
    }

    private static boolean openAvatar(@NonNull Activity a, @NonNull LibraryItem it) {
        Intent i = new Intent(a, AvatarStudioActivity.class);
        if (it.avatarDir != null) {
            i.putExtra(AvatarStudioActivity.EXTRA_LIBRARY_DIR, it.avatarDir);
        } else if (it.projectId != null) {
            i.putExtra(AvatarStudioActivity.EXTRA_PROJECT_ID, it.projectId);
            i.putExtra(AvatarStudioActivity.EXTRA_RIG_ID, it.partId);
        } else {
            return openWithOtherApp(a, it);   // a loose .avatar.json in the folder
        }
        a.startActivity(i);
        return true;
    }

    /**
     * The classic Records screen's own viewers, given exactly what it gives them: the file's own
     * link, file:// included (an explicit Intent to our own screen never leaves the app, so the
     * FileProvider detour that sharing needs would only change what the player sees).
     */
    private static boolean openMedia(@NonNull Activity a, @NonNull LibraryItem it) {
        if (it.uri == null || it.uri.isEmpty()) return false;
        Uri uri = Uri.parse(it.uri);
        Intent i = new Intent(a, it.isImage() ? ImageViewerActivity.class : VideoPlayerActivity.class);
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (it.captureCategory != null) i.putExtra("item_category", it.captureCategory);
        i.putExtra("item_display_name", it.fileName != null ? it.fileName : it.name);
        a.startActivity(i);
        return true;
    }

    private static boolean openWithOtherApp(@NonNull Activity a, @NonNull LibraryItem it) {
        Uri uri = shareable(a, it.uri);
        if (uri == null) return false;
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(it))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, R.string.library_no_app, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /**
     * A link another screen or app may read: content:// as it is, file:// through the app's
     * FileProvider (a file:// link in an Intent throws on Android 7 and later).
     */
    @Nullable
    static Uri shareable(@NonNull Context c, @Nullable String uri) {
        if (uri == null || uri.isEmpty()) return null;
        Uri u = Uri.parse(uri);
        if (!"file".equals(u.getScheme()) || u.getPath() == null) return u;
        try {
            return FileProvider.getUriForFile(c, c.getPackageName() + ".provider", new File(u.getPath()));
        } catch (IllegalArgumentException e) {
            FLog.w("LibraryOpener", "no shareable link for " + uri, e);
            return null;
        }
    }

    @NonNull
    static String mimeOf(@NonNull LibraryItem it) {
        String name = it.fileName != null ? it.fileName : it.name;
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "*/*";
    }
}
