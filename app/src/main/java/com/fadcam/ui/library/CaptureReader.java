package com.fadcam.ui.library;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;
import com.fadcam.ui.VideoItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Recordings, photos and exports that live OUTSIDE the Joy Creator folder: older in-app recordings,
 * a phone with no folder chosen, or a custom recording location elsewhere. Read from the same index
 * the classic Records screen and the lobby use ({@link VideoIndexRepository}), so nothing the old
 * screen shows goes missing here.
 *
 * <p>Anything that index found INSIDE the folder is left to {@link FolderReader}, which knows more
 * about it (who made it, whether it was shared in). Not on the main thread: a cold index scans.</p>
 */
final class CaptureReader {

    private CaptureReader() {}

    /** @param folderWasRead true when FolderReader listed the folder, so its files are skipped here */
    @NonNull
    static List<LibraryItem> read(@NonNull Context c, boolean folderWasRead) {
        List<LibraryItem> out = new ArrayList<>();
        List<VideoItem> vids;
        try {
            vids = VideoIndexRepository.getInstance(c).getVideos(SharedPreferencesManager.getInstance(c));
        } catch (Exception e) {
            FLog.w("LibraryCapture", "recordings index unreadable", e);
            return out;
        }
        if (vids == null) return out;
        Uri tree = folderWasRead ? JoyFolderHome.treeUri(c) : null;
        SafJoyFs folder = tree == null ? null : new SafJoyFs(c, tree);   // no I/O: only relOf is used
        for (VideoItem v : vids) {
            if (v == null || v.uri == null || v.isSkeleton || v.isTemporary) continue;
            if (folder != null && folder.relOf(v.uri) != null) continue;
            out.add(toItem(v));
        }
        return out;
    }

    @NonNull
    private static LibraryItem toItem(@NonNull VideoItem v) {
        boolean image = v.mediaType == VideoItem.MediaType.IMAGE;
        LibraryKind kind = v.category == VideoItem.Category.FADITOR ? LibraryKind.EXPORTS
                : image ? LibraryKind.PHOTOS : LibraryKind.RECORDINGS;
        String file = v.displayName == null ? "" : v.displayName;
        LibraryItem it = new LibraryItem("media:" + v.uri, kind, FileKind.baseOf(file),
                v.lastModified, v.size, false);
        it.fileKind = image ? FileKind.IMAGE : FileKind.VIDEO;
        it.fileName = file;
        it.uri = v.uri.toString();
        it.thumb = it.uri;
        it.captureCategory = v.category == null ? null : v.category.name();
        return it;
    }
}
