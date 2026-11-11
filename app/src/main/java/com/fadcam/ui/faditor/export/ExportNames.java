package com.fadcam.ui.faditor.export;

import java.io.File;

/**
 * Where an export's file goes when its name is already taken.
 *
 * <p>An export never replaces a finished one (work-loss audit 2026-11-10). The export sheet
 * remembers the file name typed for a project, so the second export of that project used to land
 * on the first one's name, and committing it deleted the first: the video already shared or kept
 * was gone. The Joy Creator folder never did this ({@code JoyFolder.freeName}); this is the same
 * rule for the phone's own folder, and for an export still waiting in the temporary folder because
 * its copy to the chosen folder failed.</p>
 *
 * <p>Pure {@code java.io}: tested by {@code tools/jvm-harness/run-consolidate.sh}.</p>
 */
public final class ExportNames {

    private ExportNames() {}

    /** {@code name} in {@code dir}, or "name 2", "name 3"… when a file of that name is already there. */
    public static File freeFile(File dir, String name) {
        File first = new File(dir, name);
        if (!first.exists()) return first;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            File next = new File(dir, stem + " " + i + ext);
            if (!next.exists()) return next;
        }
        return first;
    }
}
