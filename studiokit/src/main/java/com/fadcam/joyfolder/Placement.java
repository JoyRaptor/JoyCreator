package com.fadcam.joyfolder;

/**
 * Where a new file goes. The whole policy, in one place:
 *
 * <ol>
 *   <li><b>Anything that belongs to a piece of work goes wherever that work lives.</b> A share-in
 *       while a project is open, an import into it, a voiceover recorded in it.</li>
 *   <li><b>Anything that belongs to no work goes to its folder if the user has one, otherwise the
 *       top.</b> Share-ins with nothing open go to Assets; recordings go to Recordings.</li>
 *   <li>A folder the user made to collect a kind wins for that kind: with an Exports folder, every
 *       export goes there; with Recordings, every recording, voiceovers included.</li>
 * </ol>
 *
 * Supplies are the exception that always has a home: {@code Supplies/<Kind>/}, made on first use.
 */
public final class Placement {

    private Placement() {}

    public enum What {
        /** Shared in from another app. */
        SHARE_IN,
        /** Picked from the gallery or a file manager inside the app. */
        IMPORT,
        /** Rendered out of a work. */
        EXPORT,
        /** From the screen or camera recorder, not tied to a work. */
        RECORDING,
        /** Recorded inside a work (voiceover, mic take). */
        VOICEOVER,
        /** A new project, drawing or sprite sheet. */
        NEW_WORK,
        /** A brush, paper, palette, look, font or plugin. */
        SUPPLY,
        /** An export the user marked finished. */
        FINISHED
    }

    /** Where to put it, and which role the caller should record if it creates that folder. */
    public static final class Result {
        public final String dir;
        /** Non-null when {@code dir} is a role folder that may not exist yet. */
        public final FolderRoles.Role createsRole;

        Result(String dir, FolderRoles.Role createsRole) {
            this.dir = dir;
            this.createsRole = createsRole;
        }
    }

    /**
     * @param workRel     the open work's file (e.g. "Client A/Promo.joyproject"), or null
     * @param browsingDir the folder the gallery is showing, for NEW_WORK; null means the top
     * @param supplyKind  for SUPPLY, which kind
     */
    public static Result dirFor(What what, String workRel, String browsingDir,
                                FileKind supplyKind, FolderRoles roles) {
        String workDir = workRel == null ? null : parentOf(workRel);
        switch (what) {
            case SHARE_IN:
            case IMPORT:
                if (workDir != null) return new Result(workDir, null);
                return orTop(roles.get(FolderRoles.Role.ASSETS));
            case EXPORT:
                if (roles.get(FolderRoles.Role.EXPORTS) != null) return new Result(roles.get(FolderRoles.Role.EXPORTS), null);
                return new Result(workDir != null ? workDir : "", null);
            case RECORDING:
                return orTop(roles.get(FolderRoles.Role.RECORDINGS));
            case VOICEOVER:
                if (roles.get(FolderRoles.Role.RECORDINGS) != null) return new Result(roles.get(FolderRoles.Role.RECORDINGS), null);
                return new Result(workDir != null ? workDir : "", null);
            case NEW_WORK:
                return new Result(browsingDir == null ? "" : browsingDir, null);
            case SUPPLY: {
                String supplies = roles.get(FolderRoles.Role.SUPPLIES);
                FolderRoles.Role creates = supplies == null ? FolderRoles.Role.SUPPLIES : null;
                if (supplies == null) supplies = FolderRoles.Role.SUPPLIES.defaultName;
                String kindFolder = supplyKind == null || supplyKind.suppliesFolder == null
                        ? "" : "/" + supplyKind.suppliesFolder;
                return new Result(supplies + kindFolder, creates);
            }
            case FINISHED: {
                String fin = roles.get(FolderRoles.Role.FINISHED);
                return fin != null ? new Result(fin, null)
                        : new Result(FolderRoles.Role.FINISHED.defaultName, FolderRoles.Role.FINISHED);
            }
            default:
                return new Result("", null);
        }
    }

    private static Result orTop(String roleDir) {
        return new Result(roleDir == null ? "" : roleDir, null);
    }

    /** "a/b/c.txt" → "a/b"; "c.txt" → "". */
    public static String parentOf(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    /** "a/b/c.txt" → "c.txt". */
    public static String nameOf(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? rel : rel.substring(slash + 1);
    }

    /** Join a folder and a name; the top folder is "". */
    public static String join(String dir, String name) {
        return dir == null || dir.isEmpty() ? name : dir + "/" + name;
    }

    /** True when {@code rel} is {@code dir} itself or anywhere inside it. */
    public static boolean isInside(String rel, String dir) {
        if (dir == null) return false;
        if (dir.isEmpty()) return true;
        return rel.equals(dir) || rel.startsWith(dir + "/");
    }
}
