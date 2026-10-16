package com.fadcam.ui.trash;

import com.fadcam.ui.library.LibraryItem;

/**
 * One thing in the Trash, as the screen shows it. Pure data (tested on a plain JVM).
 *
 * <p>{@link #look} is a Library item, so the card can use the Library's own picture, tag, glyph and
 * corner colour unchanged. Its {@code modified} holds when the thing was deleted.</p>
 *
 * <p>The legacy {@code TrashItem} the helpers act on is not held here (it is Android-side); the
 * screen finds it again by {@link #key}.</p>
 */
public final class TrashEntry {

    /** Unique in the Trash: the trash file name and the moment it was deleted, as TrashItem.equals is. */
    public final String key;
    public final TrashKind bucket;
    public final LibraryItem look;
    public final long deletedAt;
    /** True when it lives in the Joy Creator folder's Trash (put back where it was, no destination). */
    public final boolean inFolder;

    public TrashEntry(String key, TrashKind bucket, LibraryItem look, long deletedAt, boolean inFolder) {
        this.key = key;
        this.bucket = bucket;
        this.look = look;
        this.deletedAt = deletedAt;
        this.inFolder = inFolder;
    }
}
