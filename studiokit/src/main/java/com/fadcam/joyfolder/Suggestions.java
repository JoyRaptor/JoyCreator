package com.fadcam.joyfolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Folders are earned. When one kind of thing piles up at the top, offer its folder once:
 * "You have 38 recordings at the top. Put them in a Recordings folder?"
 *
 * <p>A dismissed suggestion is never shown again for that kind. Nothing here moves a file; the
 * caller asks, and on yes makes the folder, moves the pile and records the role.</p>
 */
public final class Suggestions {

    private Suggestions() {}

    /** How many loose items of one kind at the top before we offer a folder. */
    public static final int THRESHOLD = 20;

    public static final class Suggestion {
        public final FolderRoles.Role role;
        public final List<String> files;

        Suggestion(FolderRoles.Role role, List<String> files) {
            this.role = role;
            this.files = files;
        }
    }

    /** Tells a recording by its name (the recorder writes straight into the folder, so no origin is noted). */
    public interface NameTest {
        boolean matches(String name);
    }

    /**
     * @param dismissed roles the user said "not now" to
     */
    public static List<Suggestion> check(FolderIndex index, FolderRoles roles, Set<FolderRoles.Role> dismissed) {
        return check(index, roles, dismissed, null);
    }

    /**
     * @param isRecording also counts files with a recorder's name ("Camera 2026-10-13 23.51.mp4"); may be null
     */
    public static List<Suggestion> check(FolderIndex index, FolderRoles roles, Set<FolderRoles.Role> dismissed,
                                         NameTest isRecording) {
        List<Suggestion> out = new ArrayList<>();
        offer(out, index, roles, dismissed, FolderRoles.Role.RECORDINGS, "recording", isRecording);
        offer(out, index, roles, dismissed, FolderRoles.Role.EXPORTS, "export", null);
        return out;
    }

    private static void offer(List<Suggestion> out, FolderIndex index, FolderRoles roles,
                              Set<FolderRoles.Role> dismissed, FolderRoles.Role role, String origin,
                              NameTest byName) {
        if (roles.get(role) != null || dismissed.contains(role)) return;
        List<String> loose = new ArrayList<>();
        for (FolderIndex.Item it : index.items()) {
            if (it.path.contains("/")) continue;
            if (origin.equals(it.origin) || (byName != null && byName.matches(it.path))) loose.add(it.path);
        }
        if (loose.size() >= THRESHOLD) out.add(new Suggestion(role, loose));
    }

    /**
     * What "yes" does: the pile moves into the role's folder (its usual name, reused if the user
     * already made one), keeping names unless one is taken there.
     *
     * @param allFiles every file in the folder, for name clashes in a folder that already exists
     */
    public static ConsolidatePlan plan(Suggestion s, java.util.Collection<String> allFiles) {
        ConsolidatePlan p = new ConsolidatePlan();
        p.boxDir = s.role.defaultName;
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (String f : allFiles) {
            if (Placement.parentOf(f).equalsIgnoreCase(p.boxDir)) {
                p.boxExists = true;
                taken.add(Placement.nameOf(f).toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (String f : s.files) {
            String name = ReadableNames.unique(taken, Placement.nameOf(f));
            taken.add(name.toLowerCase(java.util.Locale.ROOT));
            p.moves.add(new ConsolidatePlan.Move(f, Placement.join(p.boxDir, name)));
        }
        return p;
    }
}
