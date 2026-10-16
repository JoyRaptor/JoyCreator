package com.fadcam.ui.trash;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;
import com.fadcam.joyfolder.studio.JoyTrashBridge;
import com.fadcam.model.TrashItem;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Pictures for things in the Joy Creator folder's Trash. Read-only; on the folder thread.
 *
 * <ul>
 *   <li>A picture or a clip: the file itself, in Trash ({@link JoyTrashBridge#previewUri}, the link
 *       the classic Trash already draws from; where the folder's storage does not name documents by
 *       path, the folder is asked once).</li>
 *   <li>A project: its cover, which is its first clip, as the Library and the lobby show it. The
 *       project file in Trash is read only as far as that clip; its media stays in the folder when
 *       the project is deleted, so the frame still draws.</li>
 *   <li>Anything else: nothing, and the card shows the kind's glyph.</li>
 * </ul>
 */
final class TrashCovers {

    private static final String TAG = "TrashCovers";
    /** How a project in the folder names its own media ("joy:Recordings/x.mp4"). */
    private static final String JOY = "joy:";

    private final Context app;
    private boolean opened;
    @Nullable private JoyFolder folder;

    TrashCovers(@NonNull Context app) {
        this.app = app;
    }

    /** A link Glide can draw, or null for the glyph. */
    @Nullable
    String of(@NonNull TrashItem item, @Nullable FileKind kind) {
        if (kind == null || !JoyTrashBridge.isFolderItem(item)) return null;
        try {
            if (kind == FileKind.IMAGE || kind == FileKind.VIDEO) {
                Uri u = JoyTrashBridge.previewUri(app, item);
                if (u == null) u = documentUri(JoyFolder.TRASH + "/" + trashName(item));
                return u == null ? null : u.toString();
            }
            if (kind == FileKind.PROJECT) return projectCover(item);
        } catch (Exception e) {
            FLog.w(TAG, "no picture for " + item.getOriginalDisplayName(), e);
        }
        return null;
    }

    @NonNull
    private static String trashName(@NonNull TrashItem item) {
        return item.getTrashFileName().substring(JoyTrashBridge.PREFIX.length());
    }

    @Nullable
    private JoyFolder folder() {
        if (!opened) {
            opened = true;
            folder = JoyFolderHome.open(app);
        }
        return folder;
    }

    @Nullable
    private String projectCover(@NonNull TrashItem item) throws Exception {
        JoyFolder jf = folder();
        if (jf == null) return null;
        String ref;
        try (InputStream in = jf.fs.open(JoyFolder.TRASH + "/" + trashName(item))) {
            ref = firstClipSource(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        if (ref == null) return null;
        if (ref.startsWith(JOY)) {
            Uri u = documentUri(ref.substring(JOY.length()));
            return u == null ? null : u.toString();
        }
        // A link outside the folder still draws if the file is still there; a project:// link
        // pointed into the app's own copy, which went with the project.
        if (ref.startsWith("content://") || ref.startsWith("file://") || ref.startsWith("/")) return ref;
        return null;
    }

    /**
     * A document link for a path in the folder. Phone storage and SD cards name documents by path,
     * so no folder I/O; any other storage is asked.
     */
    @Nullable
    private Uri documentUri(@NonNull String rel) throws Exception {
        Uri tree = JoyFolderHome.treeUri(app);
        if (tree == null) return null;
        if ("com.android.externalstorage.documents".equals(tree.getAuthority())) {
            String root = DocumentsContract.getTreeDocumentId(tree);
            String id = root.endsWith(":") ? root + rel : root + "/" + rel;
            return DocumentsContract.buildDocumentUriUsingTree(tree, id);
        }
        SafJoyFs saf = JoyFolderHome.fsOf(folder());
        return saf == null ? null : saf.documentUri(rel);
    }

    /** timeline.clips[0].sourceUri, read as a stream and stopped there. Null when it has none. */
    @Nullable
    static String firstClipSource(@NonNull JsonReader j) throws java.io.IOException {
        j.setLenient(true);
        if (j.peek() != JsonToken.BEGIN_OBJECT) return null;
        j.beginObject();
        while (j.hasNext()) {
            if (!"timeline".equals(j.nextName()) || j.peek() != JsonToken.BEGIN_OBJECT) {
                j.skipValue();
                continue;
            }
            j.beginObject();
            while (j.hasNext()) {
                if (!"clips".equals(j.nextName()) || j.peek() != JsonToken.BEGIN_ARRAY) {
                    j.skipValue();
                    continue;
                }
                j.beginArray();
                if (!j.hasNext() || j.peek() != JsonToken.BEGIN_OBJECT) return null;
                j.beginObject();
                while (j.hasNext()) {
                    if ("sourceUri".equals(j.nextName()) && j.peek() == JsonToken.STRING) return j.nextString();
                    j.skipValue();
                }
                return null;
            }
            return null;
        }
        return null;
    }
}
