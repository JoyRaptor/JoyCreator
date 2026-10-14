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

    /**
     * @param dismissed roles the user said "not now" to
     */
    public static List<Suggestion> check(FolderIndex index, FolderRoles roles, Set<FolderRoles.Role> dismissed) {
        List<Suggestion> out = new ArrayList<>();
        offer(out, index, roles, dismissed, FolderRoles.Role.RECORDINGS, "recording");
        offer(out, index, roles, dismissed, FolderRoles.Role.EXPORTS, "export");
        return out;
    }

    private static void offer(List<Suggestion> out, FolderIndex index, FolderRoles roles,
                              Set<FolderRoles.Role> dismissed, FolderRoles.Role role, String origin) {
        if (roles.get(role) != null || dismissed.contains(role)) return;
        List<String> loose = new ArrayList<>();
        for (FolderIndex.Item it : index.items()) {
            if (!it.path.contains("/") && origin.equals(it.origin)) loose.add(it.path);
        }
        if (loose.size() >= THRESHOLD) out.add(new Suggestion(role, loose));
    }
}
