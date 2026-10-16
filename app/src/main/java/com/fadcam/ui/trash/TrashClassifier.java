package com.fadcam.ui.trash;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.ui.library.LibraryClassifier;
import com.fadcam.ui.library.LibraryItem;
import com.fadcam.ui.library.LibraryKind;

/**
 * What a deleted thing is, from its name and where it was deleted from. Pure: tested on a JVM.
 *
 * <ul>
 *   <li>From the Joy Creator folder: the Library's own rule ({@link LibraryClassifier}), by ending
 *       and by what the readable name says made it. The folder forgets a file's origin when it is
 *       trashed, so a copy the Studio made reads as the picture or clip it is.</li>
 *   <li>From the app's own Trash (the classic Records path): the app made it. A still picture,
 *       a snapshot included, is a Photo; a video is a Recording unless its name says the Studio
 *       merged or converted it.</li>
 * </ul>
 */
public final class TrashClassifier {

    private TrashClassifier() {}

    /** The key {@link TrashEntry#key} carries, matching TrashItem's own equality. */
    public static String keyOf(String trashFileName, long deletedAt) {
        return (trashFileName == null ? "" : trashFileName) + "|" + deletedAt;
    }

    public static LibraryKind kindOf(String fileName, boolean inFolder, boolean snapshot,
                                     LibraryClassifier.Capture capture) {
        String name = fileName == null ? "" : fileName;
        if (inFolder) return LibraryClassifier.kindOf(name, null, capture);
        FileKind fk = FileKind.of(name);
        if (snapshot || fk == FileKind.IMAGE) return LibraryKind.PHOTOS;
        return LibraryClassifier.kindOf(name, LibraryClassifier.ORIGIN_RECORDING, capture);
    }

    /** The name a card shows: without its ending, as everywhere in the Library ("Lecture 3"). */
    public static String shownName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "";
        String base = FileKind.baseOf(fileName);
        return base.isEmpty() ? fileName : base;
    }

    /**
     * The whole entry. {@code thumb} and the size are filled in by the Android-side reader.
     *
     * @param snapshot a Snapshot Gallery (forensics) picture from the app's Trash
     */
    public static TrashEntry entry(String trashFileName, String fileName, long deletedAt, long size,
                                   boolean inFolder, boolean snapshot, LibraryClassifier.Capture capture) {
        LibraryKind kind = kindOf(fileName, inFolder, snapshot, capture);
        String key = keyOf(trashFileName, deletedAt);
        LibraryItem look = new LibraryItem(key, kind, shownName(fileName), deletedAt, size, false);
        look.fileKind = FileKind.of(fileName == null ? "" : fileName);
        look.fileName = fileName;
        return new TrashEntry(key, TrashKind.of(kind), look, deletedAt, inFolder);
    }
}
