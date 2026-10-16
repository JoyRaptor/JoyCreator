package com.fadcam.ui.library;

import com.fadcam.joyfolder.FileKind;

/**
 * One thing in the Library. Pure data, no Android, so the filter can be tested on a plain JVM.
 *
 * <p>The core fields say what it is. The "where" fields are filled once, by the reader that found
 * it, and say how to show and open it; most are null for most kinds.</p>
 */
public final class LibraryItem {

    /** Stable and unique across every source ("project:&lt;id&gt;", "folder:&lt;path&gt;"…). */
    public final String key;
    public final LibraryKind kind;
    /** The readable name, without a file ending. */
    public final String name;
    public final long modified;
    /** Bytes, or -1 when unknown (projects, drawings). */
    public final long size;
    /** Shared in from another app, or imported (owner ruling b). */
    public final boolean sharedIn;

    // ── where it lives and how to open it (set once by its reader) ──────────────
    /** What the file itself is, by its ending; null for things that are not one file. */
    public FileKind fileKind;
    /** The full file name with its ending, when it is a file. */
    public String fileName;
    /** A content:// or file:// link to the file itself, when it is a file. */
    public String uri;
    /** A link or absolute path a picture can be drawn from, or null for a glyph. */
    public String thumb;
    /** Its path inside the Joy Creator folder ("Recordings/x.mp4"), when it lives there. */
    public String folderPath;
    /** The project, or the project a sheet or character lives in. */
    public String projectId;
    /** The project's name, for "in Restore video test". */
    public String parentName;
    /** A sprite sheet's or a character rig's id inside its project. */
    public String partId;
    /** A Joy Brush drawing's working file name. */
    public String drawingFile;
    /** An Avatar Studio library bundle's folder name. */
    public String avatarDir;
    /** An imported font's absolute path. */
    public String fontPath;
    /** A recording's old Records category name, so its viewer behaves as it did there. */
    public String captureCategory;
    /** A project that is in the folder but not yet in the app (after a reinstall). */
    public boolean needsRestore;
    /** A project's layers (what sits on its main track), or -1 until its facts are read. */
    public int layers = -1;
    /** A project's running time in milliseconds, or -1 until its facts are read. */
    public long durationMs = -1;

    public LibraryItem(String key, LibraryKind kind, String name, long modified, long size,
                       boolean sharedIn) {
        this.key = key;
        this.kind = kind;
        this.name = name == null ? "" : name;
        this.modified = modified;
        this.size = size;
        this.sharedIn = sharedIn;
    }

    /** True when it plays as a video: recordings, exports, clips, and video project media. */
    public boolean isVideo() {
        return fileKind == FileKind.VIDEO;
    }

    /** True when it is a still picture file: photos, images, and picture project media. */
    public boolean isImage() {
        return fileKind == FileKind.IMAGE;
    }
}
