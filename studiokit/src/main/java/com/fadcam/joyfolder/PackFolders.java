package com.fadcam.joyfolder;

import java.util.Locale;

/**
 * THE PACK FOLDER CONTRACT (job PACK-1 and FILE-1; roadmap plan row 7): where a Joy pack lives in the person's Joy Creator
 * folder, in the same plain, Obsidian-style terms as the rest of the folder.
 *
 * <ul>
 *   <li><b>One flat folder, one folder per pack.</b> An installed pack is its unzipped self in
 *       {@code Supplies/Packs/<Pack name>/}: {@code joypack.json} at its top and its files beside or below it, with their
 *       usual readable endings ({@code .text.json}, {@code .brush.json}, {@code .ttf}...). Nothing else lives in
 *       {@code Supplies/Packs/}.</li>
 *   <li><b>Readable names.</b> The folder is named after the pack ({@link #folderName}), as a person would write it
 *       ("Film titles"), never its id or a number; a second pack with a taken name gets "Film titles 2" (the caller asks
 *       {@code JoyFolder.freeName}).</li>
 *   <li><b>Hand-editable.</b> A pack is plain JSON and ordinary files; opening and editing them in any editor is fine. An
 *       <i>update</i> of the same pack (same id) replaces the pack's folder as one step, so a change made <i>inside</i> a pack
 *       folder is lost on update. The app's Save never writes there: it saves the person's copy in the usual home for that
 *       kind ({@link #homeFor}, for example {@code Supplies/Looks/}), and <b>the person's copy always wins</b> and is never
 *       touched by a pack update or removal.</li>
 *   <li><b>Nothing lost on uninstall.</b> The folder is the person's own, outside the app: removing the app removes nothing
 *       here. Removing a pack is moving its folder to the Trash (the usual one, with Undo); the original {@code .joypack}
 *       file is not kept (the folder is the pack, and {@link #shareFileName} names the zip made when it is shared again).</li>
 *   <li><b>Hidden work stays hidden.</b> Names that start with a dot ({@code .Film titles.installing},
 *       {@code .Film titles.replaced}, the staging of {@link PackPublish}) are never packs ({@link #isPackFolderName}).</li>
 *   <li><b>Safe paths.</b> Every file a pack names stays inside its own folder ({@link #entryPath}).</li>
 * </ul>
 *
 * Pure Java, no file access: tested on a plain JVM by {@code tools/joyfolder-tests}.
 */
public final class PackFolders {
    /** Where installed packs live, one folder each. Same as {@link PackShelf#DIR}. */
    public static final String DIR = PackShelf.DIR;

    private PackFolders() {}

    /** The readable folder name for a pack called {@code packName}: its name, made safe; "Pack" when nothing is left. */
    public static String folderName(String packName) {
        String s = ReadableNames.sanitize(packName == null ? "" : packName).trim();
        if (s.isEmpty() || s.equals("Untitled")) return "Pack";
        return s;
    }

    /** The folder, relative to the Joy Creator folder, a pack of this name goes in ("Supplies/Packs/Film titles"). */
    public static String packDir(String packName) {
        return Placement.join(DIR, folderName(packName));
    }

    /** True for a folder name inside {@code Supplies/Packs/} that may be a pack (not hidden, not staging). */
    public static boolean isPackFolderName(String name) {
        if (name == null || name.isEmpty() || name.startsWith(".")) return false;
        String lower = name.toLowerCase(Locale.US);
        return !(lower.endsWith(".installing") || lower.endsWith(".replaced"));
    }

    /**
     * Where a pack's own file goes: {@code packDir} joined with the entry's path from the manifest. Null when the path is
     * not safe (absolute, has a drive, climbs out with "..", or is empty): such a file is never read or written.
     */
    public static String entryPath(String packDir, String entryFile) {
        if (packDir == null || entryFile == null) return null;
        String f = entryFile.replace('\\', '/').trim();
        if (f.isEmpty() || f.startsWith("/") || f.contains(":")) return null;
        StringBuilder clean = new StringBuilder();
        for (String part : f.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        if (clean.length() == 0) return null;
        return Placement.join(packDir, clean.toString());
    }

    /**
     * The folder, inside the Joy Creator folder, where the person's OWN copy of a thing of this socket is saved: the same
     * homes as the rest of Supplies ({@link FileKind#suppliesFolder}). Null for a socket this app does not know yet (kept
     * in the pack, no home until a room takes it).
     */
    public static String homeFor(String socket) {
        FileKind k = kindOf(socket);
        return k == null ? null : Placement.join("Supplies", k.suppliesFolder);
    }

    /** The kind of file a socket holds, or null when this app does not know the socket. */
    public static FileKind kindOf(String socket) {
        if (socket == null) return null;
        switch (socket.toLowerCase(Locale.US)) {
            case "text-look":
            case "vis-look":
            case "vis-part":
            case "grade":
            case "caption-style":
                return FileKind.LOOK;
            case "brush":
                return FileKind.BRUSH;
            case "paper":
                return FileKind.PAPER;
            case "palette":
                return FileKind.PALETTE;
            case "font":
                return FileKind.FONT;
            default:
                return null;
        }
    }

    /** The file name for a pack shared as one file: "Film titles 1.0.joypack" (the version only when there is one). */
    public static String shareFileName(String packName, String version) {
        String base = folderName(packName);
        String v = version == null ? "" : ReadableNames.sanitize(version).trim();
        if (v.isEmpty() || v.equals("Untitled")) return base + ".joypack";
        return base + " " + v + ".joypack";
    }
}
