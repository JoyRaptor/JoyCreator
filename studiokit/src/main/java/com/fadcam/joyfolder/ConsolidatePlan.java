package com.fadcam.joyfolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Gather Lecture 3 into one folder": what moves where, worked out before anything moves, so
 * the dialog can say exactly what will happen and the caller can apply it in one step.
 *
 * <ul>
 *   <li>The box is a folder named after the work, next to where the work is now. If a folder of
 *       that name is already there it is reused. Nothing depends on the name afterwards.</li>
 *   <li>The work, its files and its exports beside it move into the box.</li>
 *   <li>Files another work also uses are the user's call: leave them, or move them to Assets.</li>
 *   <li>Recordings in the Recordings folder move only when the box is ticked.</li>
 * </ul>
 */
public final class ConsolidatePlan {

    public enum SharedChoice { LEAVE, ASSETS }

    public static final class Options {
        public boolean moveRecordings;
        public SharedChoice shared = SharedChoice.ASSETS;
    }

    public static final class Move {
        public final String from;
        public final String to;

        Move(String from, String to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return from + " -> " + to;
        }
    }

    /** The box folder, e.g. "Lecture 3" or "Client A/Promo". */
    public String boxDir;
    public boolean boxExists;
    public final List<Move> moves = new ArrayList<>();
    /** Files other works also use. Moved to Assets or left, per the options. */
    public final List<String> sharedFiles = new ArrayList<>();
    /** Recordings the work uses that stay where they are because the box was unticked. */
    public final List<String> recordingsLeft = new ArrayList<>();
    /** True when the plan makes an Assets folder that doesn't exist yet. */
    public boolean createsAssets;

    /**
     * @param workRel    the work file, e.g. "Lecture 3.joyproject"
     * @param usedFiles  in-folder files the work uses (outside files are not ours to move)
     * @param allFiles   every file in the folder, to find exports beside the work and name clashes
     * @param usage      which works use which files
     */
    public static ConsolidatePlan plan(String workRel, Collection<String> usedFiles,
                                       Collection<String> allFiles, Set<String> existingDirs,
                                       UsageMap usage, FolderRoles roles, Options opts) {
        ConsolidatePlan p = new ConsolidatePlan();
        String workDir = Placement.parentOf(workRel);
        String workName = Placement.nameOf(workRel);
        String base = FileKind.baseOf(workName);
        if (!workDir.isEmpty() && Placement.nameOf(workDir).equalsIgnoreCase(ReadableNames.sanitize(base))) {
            // Already in a folder of its own name: gather into that one, never a folder inside it.
            p.boxDir = workDir;
            p.boxExists = true;
        } else {
            p.boxDir = Placement.join(workDir, ReadableNames.sanitize(base));
            p.boxExists = existingDirs.contains(p.boxDir);
        }

        String recordings = roles.get(FolderRoles.Role.RECORDINGS);
        String assets = roles.get(FolderRoles.Role.ASSETS);
        String assetsDir = assets != null ? assets : FolderRoles.Role.ASSETS.defaultName;

        // Names already taken in each destination, lower-cased, grown as the plan claims names.
        java.util.Map<String, Set<String>> taken = new java.util.HashMap<>();
        for (String f : allFiles) takenIn(taken, Placement.parentOf(f)).add(Placement.nameOf(f).toLowerCase(Locale.ROOT));
        for (String d : existingDirs) takenIn(taken, Placement.parentOf(d)).add(Placement.nameOf(d).toLowerCase(Locale.ROOT));

        Set<String> toMove = new LinkedHashSet<>();
        if (!Placement.parentOf(workRel).equals(p.boxDir)) toMove.add(workRel);
        for (String f : usedFiles) {
            if (Placement.isInside(f, p.boxDir)) continue;
            if (usage.usedByOthers(f, workRel)) {
                p.sharedFiles.add(f);
                continue;
            }
            if (recordings != null && Placement.isInside(f, recordings) && !opts.moveRecordings) {
                p.recordingsLeft.add(f);
                continue;
            }
            toMove.add(f);
        }
        // Exports beside the work: same folder, name starts with the work's name, nothing else uses them.
        String prefix = base.toLowerCase(Locale.ROOT);
        for (String f : allFiles) {
            if (!Placement.parentOf(f).equals(workDir) || f.equals(workRel)) continue;
            String n = Placement.nameOf(f).toLowerCase(Locale.ROOT);
            FileKind k = FileKind.of(n);
            boolean rendered = k == FileKind.VIDEO || k == FileKind.AUDIO || k == FileKind.IMAGE;
            boolean named = n.startsWith(prefix + ".") || n.startsWith(prefix + " ");
            if (rendered && named && usage.worksUsing(f).isEmpty()) toMove.add(f);
        }

        for (String f : toMove) {
            p.moves.add(new Move(f, claim(taken, p.boxDir, Placement.nameOf(f))));
        }
        if (opts.shared == SharedChoice.ASSETS) {
            for (String f : p.sharedFiles) {
                if (Placement.isInside(f, assetsDir)) continue;
                if (assets == null) p.createsAssets = true;
                p.moves.add(new Move(f, claim(taken, assetsDir, Placement.nameOf(f))));
            }
        }
        return p;
    }

    private static Set<String> takenIn(java.util.Map<String, Set<String>> taken, String dir) {
        Set<String> s = taken.get(dir);
        if (s == null) taken.put(dir, s = new HashSet<>());
        return s;
    }

    private static String claim(java.util.Map<String, Set<String>> taken, String dir, String name) {
        Set<String> names = takenIn(taken, dir);
        String unique = ReadableNames.unique(names, name);
        names.add(unique.toLowerCase(Locale.ROOT));
        return Placement.join(dir, unique);
    }
}
