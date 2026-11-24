package cc.joycreator.avatar.ui;

import java.util.HashMap;
import java.util.Map;

/** The words for a part's role (as the pilot's cards say them); a part with no known role is called by its own name. */
final class RoleNames {
    private static final Map<String, String> WORDS = new HashMap<>();

    static {
        String[][] w = {
                {"head", "Head"}, {"body", "Body"}, {"torso", "Torso"}, {"tail", "Tail"}, {"hair", "Hair"}, {"face", "Face"},
                {"armL", "Left arm"}, {"armR", "Right arm"}, {"legL", "Left leg"}, {"legR", "Right leg"},
                {"eyeL", "Left eye"}, {"eyeR", "Right eye"}, {"lidU", "Upper lid"}, {"lidL", "Lower lid"},
                {"pupil", "Pupil"}, {"blink", "Blink"}, {"nose", "Nose"}, {"mouth", "Mouth"}, {"jaw", "Jaw"},
                {"browL", "Left eyebrow"}, {"browR", "Right eyebrow"}};
        for (String[] p : w) WORDS.put(p[0], p[1]);
    }

    private RoleNames() {}

    static String of(String role, String name) {
        String s = WORDS.get(role);
        if (s != null) return s;
        if (name != null && !name.isEmpty()) return name;
        return role == null || role.isEmpty() ? "Part" : role;
    }
}
