package com.fadcam.joyfolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * FILE-1, Space's "Move unused to Trash…": the logic of sending several files to Trash at once, pure Java so it is tested
 * off the device.
 *
 * <p>Two real risks lived in the old code. (1) The list of unused files was worked out when the screen loaded; a project
 * could start using one of them before the person pressed Move, and the file was trashed anyway
 * ({@link #plan} re-checks against the use NOW). (2) A failure on file 3 of 8 threw out of the loop: the first two were
 * already in Trash, the index was never saved, and the message said nothing was lost ({@link #run} carries on, always
 * saves the index, and {@link #summary} says exactly what happened).</p>
 */
public final class TrashBatch {
    private TrashBatch() {}

    /** What to do: the files to trash, and those left alone because a project uses them now. */
    public static final class Plan {
        public final List<String> toTrash = new ArrayList<>();
        public final List<String> inUse = new ArrayList<>();
    }

    /** What happened. */
    public static final class Result {
        public final List<String> moved = new ArrayList<>();
        /** Already gone when its turn came (nothing to do, nothing wrong). */
        public int missing;
        public final List<String> failed = new ArrayList<>();
        public final List<String> failedWhy = new ArrayList<>();
    }

    /**
     * Keep the ticked files, drop any that a project uses now.
     *
     * @param paths     the files offered, in order
     * @param ticked    which of them the person ticked (same length as paths)
     * @param usedNow   the paths a project uses at this moment (re-read just before the move)
     */
    public static Plan plan(List<String> paths, boolean[] ticked, Set<String> usedNow) {
        Plan p = new Plan();
        for (int i = 0; i < paths.size(); i++) {
            if (i >= ticked.length || !ticked[i]) continue;
            String path = paths.get(i);
            if (path == null) continue;
            if (usedNow != null && usedNow.contains(path)) p.inUse.add(path);
            else p.toTrash.add(path);
        }
        return p;
    }

    /**
     * Move each file to Trash. A file that fails is remembered and the others still go; the index is saved whatever
     * happened, so what did move is never forgotten.
     */
    public static Result run(JoyFolder jf, List<String> rels, long now) {
        Result r = new Result();
        for (String rel : rels) {
            try {
                if (rel == null || jf.fs.stat(rel) == null) {
                    r.missing++;
                    continue;
                }
                jf.trash(rel, now);
                r.moved.add(rel);
            } catch (Exception e) {
                r.failed.add(rel);
                r.failedWhy.add(SpaceErrors.plain(e));
            }
        }
        try {
            jf.saveIndex();
        } catch (Exception e) {
            r.failed.add("(the folder's index)");
            r.failedWhy.add(SpaceErrors.plain(e));
        }
        return r;
    }

    /** One plain message for the person: what moved, what was left because a project uses it, what failed and why. */
    public static String summary(Result r, int inUse) {
        StringBuilder sb = new StringBuilder();
        int n = r.moved.size();
        if (n > 0) {
            sb.append(n == 1 ? "1 file" : n + " files").append(" moved to Trash. Put them back from Settings → Recently deleted.");
        } else if (r.failed.isEmpty() && inUse == 0) {
            sb.append("Nothing needed moving.");
        }
        if (inUse > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(inUse == 1 ? "1 file was left because a project uses it now."
                    : inUse + " files were left because a project uses them now.");
        }
        if (!r.failed.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("Couldn't move ").append(Placement.nameOf(r.failed.get(0)));
            if (r.failed.size() > 1) sb.append(" and ").append(r.failed.size() - 1).append(r.failed.size() == 2 ? " other" : " others");
            sb.append(": ").append(r.failedWhy.get(0)).append(". Nothing was lost.");
        }
        return sb.toString();
    }
}
