package cc.joycreator.avatar.file;

import org.json.JSONException;

import java.util.List;

/**
 * Turns text into a character the room will open, or null: a file that does not follow the format's rules is not
 * opened at all (the room says one plain sentence; the detail goes to the log only).
 */
public final class CharacterOpen {
    private CharacterOpen() {}

    public static CharacterDoc read(String text) {
        try {
            CharacterDoc doc = CharacterDoc.parse(text);
            List<FormatCheck.Problem> bad = FormatCheck.check(doc.json);
            if (!bad.isEmpty()) {
                android.util.Log.w("AvatarRoom", "character refused: " + bad.get(0));
                return null;
            }
            return doc;
        } catch (JSONException | RuntimeException e) {
            android.util.Log.w("AvatarRoom", "character not read", e);
            return null;
        }
    }
}
