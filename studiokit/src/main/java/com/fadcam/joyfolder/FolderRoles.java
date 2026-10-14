package com.fadcam.joyfolder;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which of the user's folders collects what. Folders are earned: none of these exist until the
 * user makes one, accepts a suggestion, or does something that needs one (Supplies, Finished).
 *
 * <p>A role is found by name at the top of the folder ("Recordings", "assets"…) and then
 * remembered by path. If the folder disappears the role is simply dropped, and things go to the
 * top again. Nothing ever fails because a folder moved.</p>
 */
public final class FolderRoles {

    public enum Role {
        ASSETS("Assets"),
        RECORDINGS("Recordings"),
        EXPORTS("Exports"),
        FINISHED("Finished"),
        SUPPLIES("Supplies");

        public final String defaultName;

        Role(String defaultName) {
            this.defaultName = defaultName;
        }
    }

    private final Map<Role, String> paths = new EnumMap<>(Role.class);

    /** The folder for {@code role}, or null when the user doesn't have one. */
    public synchronized String get(Role role) {
        return paths.get(role);
    }

    public synchronized void set(Role role, String relDir) {
        if (relDir == null) paths.remove(role);
        else paths.put(role, relDir);
    }

    /**
     * Drop roles whose folder is gone; adopt top-level folders whose name matches a role.
     * @return true when anything changed
     */
    public synchronized boolean reconcile(JoyFs fs) throws IOException {
        boolean changed = false;
        for (Role r : Role.values()) {
            String p = paths.get(r);
            if (p != null) {
                JoyFs.Entry e = fs.stat(p);
                if (e == null || !e.dir) {
                    paths.remove(r);
                    changed = true;
                }
            }
        }
        for (JoyFs.Entry e : fs.list("")) {
            if (!e.dir) continue;
            for (Role r : Role.values()) {
                if (!paths.containsKey(r)
                        && e.name.toLowerCase(Locale.ROOT).equals(r.defaultName.toLowerCase(Locale.ROOT))) {
                    paths.put(r, e.name);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        for (Map.Entry<Role, String> e : paths.entrySet()) {
            o.addProperty(e.getKey().name().toLowerCase(Locale.ROOT), e.getValue());
        }
        return o;
    }

    public static FolderRoles fromJson(JsonObject o) {
        FolderRoles roles = new FolderRoles();
        if (o == null) return roles;
        for (Role r : Role.values()) {
            String key = r.name().toLowerCase(Locale.ROOT);
            if (o.has(key) && o.get(key).isJsonPrimitive()) roles.paths.put(r, o.get(key).getAsString());
        }
        return roles;
    }
}
