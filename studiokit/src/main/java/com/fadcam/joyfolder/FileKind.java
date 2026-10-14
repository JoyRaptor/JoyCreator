package com.fadcam.joyfolder;

import java.util.Locale;

/**
 * What a file in the Joy Creator folder is, read from its name alone.
 *
 * <p>Every kind the studio makes has its own readable ending, so sorting by type in any file
 * manager groups like with like, and the app never needs a folder to know what something is.
 * Supplies name the folder they live in inside {@code Supplies/}.</p>
 */
public enum FileKind {
    // Work: what you open and make.
    PROJECT(Group.WORK, null, ".joyproject"),
    DRAWING(Group.WORK, null, ".joybrush"),
    SPRITE_SHEET(Group.WORK, null, ".sprite.json"),
    AVATAR(Group.WORK, null, ".avatar.json"),
    // Supplies: what you make art with.
    BRUSH(Group.SUPPLY, "Brushes", ".brush.json"),
    PAPER(Group.SUPPLY, "Papers", ".paper.json"),
    PALETTE(Group.SUPPLY, "Palettes", ".palette.json"),
    LOOK(Group.SUPPLY, "Looks", ".grade.json", ".caption.json", ".waveform.json", ".cube"),
    FONT(Group.SUPPLY, "Fonts", ".ttf", ".otf"),
    /** A folder holding a {@code plugin.json} or {@code mod.json}. Never matched by name. */
    PLUGIN(Group.SUPPLY, "Plugins"),
    // Material: what goes into work.
    VIDEO(Group.MATERIAL, null, ".mp4", ".mov", ".m4v", ".webm", ".mkv", ".3gp"),
    AUDIO(Group.MATERIAL, null, ".m4a", ".mp3", ".wav", ".ogg", ".opus", ".flac", ".aac"),
    IMAGE(Group.MATERIAL, null, ".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".heif", ".bmp"),
    DOCUMENT(Group.MATERIAL, null, ".pdf", ".txt", ".md", ".srt", ".vtt", ".json", ".html"),
    OTHER(Group.MATERIAL, null);

    public enum Group { WORK, SUPPLY, MATERIAL }

    public final Group group;
    /** For supplies, the folder inside Supplies/ this kind lives in. */
    public final String suppliesFolder;
    private final String[] endings;

    FileKind(Group group, String suppliesFolder, String... endings) {
        this.group = group;
        this.suppliesFolder = suppliesFolder;
        this.endings = endings;
    }

    /** The first (canonical) ending, e.g. {@code .brush.json}; empty for PLUGIN and OTHER. */
    public String ending() {
        return endings.length == 0 ? "" : endings[0];
    }

    /** The kind of a file by name. Double endings win over plain ones (.brush.json before .json). */
    public static FileKind of(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        FileKind plain = null;
        for (FileKind k : values()) {
            for (String e : k.endings) {
                if (!n.endsWith(e) || n.length() == e.length()) continue;
                boolean dbl = e.indexOf('.', 1) > 0;
                if (dbl) return k;
                if (plain == null) plain = k;
            }
        }
        return plain != null ? plain : OTHER;
    }

    /** The ending of {@code fileName} including double endings we know, or "" when it has none. */
    public static String endingOf(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        for (FileKind k : values()) {
            for (String e : k.endings) {
                if (e.indexOf('.', 1) > 0 && n.endsWith(e) && n.length() > e.length()) {
                    return fileName.substring(fileName.length() - e.length());
                }
            }
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : "";
    }

    /** The name without its ending: "Soft pencil.brush.json" → "Soft pencil". */
    public static String baseOf(String fileName) {
        return fileName.substring(0, fileName.length() - endingOf(fileName).length());
    }

    public boolean isWork() {
        return group == Group.WORK;
    }
}
