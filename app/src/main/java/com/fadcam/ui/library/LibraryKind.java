package com.fadcam.ui.library;

/**
 * What the Library sorts everything into. The order here is the order of the chips.
 *
 * <p>Pure Java on purpose (no Android, no R): the filtering that runs on it is tested on a plain
 * JVM by {@code tools/library-tests}. Labels, glyphs and colours live in {@link LibraryKindStyle}.</p>
 *
 * <p>The owner's list (2026-10-16): Projects, Drawings, Sprite sheets, Avatars, Recordings, Photos,
 * Exports, Audio, Images, Documents, Fonts and supplies. Clips is the one addition: a video that is
 * neither a recording nor an export (shared in, or dropped in the folder) is to Recordings what an
 * Image is to Photos, and filing it under either would be a label that lies.</p>
 */
public enum LibraryKind {
    // Work: what you open and keep making.
    PROJECTS,
    DRAWINGS,
    SPRITE_SHEETS,
    AVATARS,
    // Made by the app's own cameras and exporter.
    RECORDINGS,
    PHOTOS,
    EXPORTS,
    // Material: what goes into work.
    CLIPS,
    IMAGES,
    AUDIO,
    DOCUMENTS,
    SUPPLIES,
    OTHER,
    /** The copies the app keeps inside the folder so a project survives an uninstall. */
    PROJECT_MEDIA;

    /** Never part of "All": reached only through its own chip (owner ruling c). */
    public boolean hiddenByDefault() {
        return this == PROJECT_MEDIA;
    }

    /**
     * Always given a chip, even when empty, so the chips never jump about as a search narrows
     * them: thumb memory needs fixed positions. OTHER appears only when something is in it.
     */
    public boolean alwaysListed() {
        return this != OTHER;
    }
}
