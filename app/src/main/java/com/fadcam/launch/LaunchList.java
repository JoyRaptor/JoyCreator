package com.fadcam.launch;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The launch list's keys, in plain Java (no Android), so Joybot's help, tips and ideas, which are tested on a plain
 * JVM, can ask it too. {@link LaunchGate} fills it from {@code assets/launch/hidden_features.json} the first time
 * anything reads the list; until then nothing is hidden.
 */
public final class LaunchList {

    private static volatile Set<String> hidden = Collections.emptySet();

    private LaunchList() {}

    /** Called by {@link LaunchGate} once it has read the file (and by tests). */
    public static void set(Set<String> keys) {
        hidden = keys == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(new HashSet<>(keys));
    }

    public static boolean hidden(String key) {
        return key != null && hidden.contains(key);
    }

    /**
     * The key for one of Joybot's route names ("record" is Capture, "finder" is Smart Detection), or null for places
     * that are always open (the Library, Settings, Recently deleted, Space).
     */
    public static String keyForRoute(String route) {
        if (route == null) return null;
        switch (route) {
            case "studio":    return "room_studio";
            case "record":    return "room_capture";
            case "spritelab": return "room_sprite_lab";
            case "avatar":    return "room_avatar";
            case "vislab":    return "room_viz_lab";
            case "textlab":   return "room_textlab";
            case "joybrush":  return "room_joybrush";
            case "remote":    return "floor_remote";
            case "finder":    return "floor_detect";
            case "supplies":  return "floor_packs";
            default:          return null;
        }
    }

    /** True when a route leads to a place on the launch list. */
    public static boolean hiddenRoute(String route) {
        return hidden(keyForRoute(route));
    }
}
