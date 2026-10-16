package com.fadcam.ui.trash;

import com.fadcam.ui.library.LibraryKind;

/**
 * The Trash's chips, in order: Projects, Drawings, Recordings, Photos, Other (All comes first and is
 * not a kind). Coarser than the Library's kinds on purpose: a trash is small and you are looking for
 * one thing you just lost, so five buckets beat thirteen. The card's own tag still says exactly what
 * a thing is (SHEET, CLIP, EXPORT), so the bucket never has to.
 *
 * <p>Pure Java (tested by {@code tools/trash-tests}). Each bucket borrows a Library kind for its label,
 * glyph and colour, so a chip here wears the same dot as its chip in the Library.</p>
 */
public enum TrashKind {
    PROJECTS(LibraryKind.PROJECTS),
    DRAWINGS(LibraryKind.DRAWINGS),
    /** Recordings, and every other video: exports and clips. */
    RECORDINGS(LibraryKind.RECORDINGS),
    /** Photos, snapshots, and every other still picture. */
    PHOTOS(LibraryKind.PHOTOS),
    /** Sprite sheets, avatars, sounds, documents, supplies, project media, anything else. */
    OTHER(LibraryKind.OTHER);

    /** The Library kind whose label, glyph and colours this chip wears. */
    public final LibraryKind look;

    TrashKind(LibraryKind look) {
        this.look = look;
    }

    /** Which chip a thing of this Library kind sits under. */
    public static TrashKind of(LibraryKind k) {
        if (k == null) return OTHER;
        switch (k) {
            case PROJECTS:   return PROJECTS;
            case DRAWINGS:   return DRAWINGS;
            case RECORDINGS:
            case EXPORTS:
            case CLIPS:      return RECORDINGS;
            case PHOTOS:
            case IMAGES:     return PHOTOS;
            default:         return OTHER;
        }
    }
}
