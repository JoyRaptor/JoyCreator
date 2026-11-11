package com.fadcam.ui.faditor.project;

/**
 * What may travel in a project zip, and what a zip may write.
 *
 * <p>Pure Java: tested by {@code tools/jvm-harness/run-workloss.sh}.</p>
 */
public final class BundleRules {

    private BundleRules() {}

    /**
     * Files in a project's folder that belong to ONE phone's working copy and must not travel in a
     * bundle: the undo history and the backups hold whole earlier copies of the project, each
     * stamped with the original project's id. Carried into the copy, one Undo there brought back a
     * project with the original's id, and the copy saved over the original from then on (work-loss
     * audit 2026-11-11). They are also most of a bundle's size, and hold things the person deleted.
     * Left out on the way out AND on the way in, so a bundle made by an older build is safe too.
     *
     * @param rel the file's path inside the project folder, with either kind of slash
     */
    public static boolean notPartOfABundle(String rel) {
        String name = rel.replace('\\', '/');
        return name.equals("undo_history.json") || name.equals("undo_history.json.tmp")
                || name.equals("project.json.bak") || name.equals("project.json.tmp")
                || name.startsWith("backups/") || name.equals(".consolidate_undo.json");
    }

    /**
     * A zip entry that would land outside the project folder. A path PART equal to ".." is the
     * danger; a file merely named "my..clip.mp4" is not (the old test refused any ".." and so
     * dropped such a file from every import without a word).
     */
    public static boolean unsafePath(String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/")) return true;
        for (String part : n.split("/")) {
            if (part.equals("..")) return true;
        }
        return false;
    }
}
