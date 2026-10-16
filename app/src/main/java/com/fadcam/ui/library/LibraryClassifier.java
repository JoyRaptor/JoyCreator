package com.fadcam.ui.library;

import com.fadcam.joyfolder.FileKind;

/**
 * Which Library kind a file in the Joy Creator folder belongs to, from its name, the origin the
 * folder index remembers for it, and what its readable name says made it. Pure: tested on a JVM.
 *
 * <p>The rules, in the order they win:</p>
 * <ol>
 *   <li>A copy the Studio made so a project survives an uninstall (origin {@code mirror}) is
 *       Project media, never a picture or clip of its own. The {@code .joyproject} itself, which
 *       carries the same origin, is still a project.</li>
 *   <li>A voiceover belongs with the project it was recorded in (owner ruling d), so it is
 *       Project media too, not a general recording.</li>
 *   <li>Everything else goes by its ending ({@link FileKind}); a video or picture is then told
 *       apart by who made it: the recorder, the exporter, or someone else.</li>
 * </ol>
 */
public final class LibraryClassifier {

    private LibraryClassifier() {}

    /** Origins written by {@code FolderIndex.put} callers. */
    public static final String ORIGIN_MIRROR = "mirror";
    public static final String ORIGIN_EXPORT = "export";
    public static final String ORIGIN_RECORDING = "recording";
    public static final String ORIGIN_SHARE_IN = "share-in";
    public static final String ORIGIN_IMPORT = "import";
    /** Not written by anyone yet; reserved by {@code Placement.What.VOICEOVER}. */
    public static final String ORIGIN_VOICEOVER = "voiceover";

    /** What the file's readable name says made it ("Screen recording …", "Photo …", "Merged …"). */
    public enum Capture {
        /** No recorder or exporter word at the start of the name. */
        NONE,
        /** Camera, front, dual, screen or stream. */
        RECORDING,
        /** Photo, selfie or screenshot. */
        PHOTO,
        /** Converted or merged by the Studio's batch tools. */
        STUDIO_OUTPUT
    }

    /** True for anything that arrived from outside the app: shared in, or imported. */
    public static boolean isSharedIn(String origin) {
        return ORIGIN_SHARE_IN.equals(origin) || ORIGIN_IMPORT.equals(origin);
    }

    public static LibraryKind kindOf(String fileName, String origin, Capture capture) {
        FileKind fk = FileKind.of(fileName == null ? "" : fileName);
        if (capture == null) capture = Capture.NONE;
        if (fk == FileKind.PROJECT) return LibraryKind.PROJECTS;
        if (ORIGIN_MIRROR.equals(origin) || ORIGIN_VOICEOVER.equals(origin)) {
            return LibraryKind.PROJECT_MEDIA;
        }
        switch (fk) {
            case DRAWING:      return LibraryKind.DRAWINGS;
            case SPRITE_SHEET: return LibraryKind.SPRITE_SHEETS;
            case AVATAR:       return LibraryKind.AVATARS;
            case BRUSH:
            case PAPER:
            case PALETTE:
            case LOOK:
            case FONT:
            case PLUGIN:       return LibraryKind.SUPPLIES;
            case DOCUMENT:     return LibraryKind.DOCUMENTS;
            case VIDEO:        return videoKind(origin, capture);
            case IMAGE:        return capture == Capture.PHOTO ? LibraryKind.PHOTOS : LibraryKind.IMAGES;
            case AUDIO:
                return capture == Capture.RECORDING || ORIGIN_RECORDING.equals(origin)
                        ? LibraryKind.RECORDINGS : LibraryKind.AUDIO;
            default:           return LibraryKind.OTHER;
        }
    }

    private static LibraryKind videoKind(String origin, Capture capture) {
        if (ORIGIN_EXPORT.equals(origin) || capture == Capture.STUDIO_OUTPUT) return LibraryKind.EXPORTS;
        if (ORIGIN_RECORDING.equals(origin) || capture == Capture.RECORDING) return LibraryKind.RECORDINGS;
        return LibraryKind.CLIPS;
    }
}
