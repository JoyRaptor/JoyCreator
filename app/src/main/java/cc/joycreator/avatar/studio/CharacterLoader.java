package cc.joycreator.avatar.studio;

import android.content.Context;
import android.util.Log;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.file.CharacterStore;

import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.android.JoyFolderHome;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finds the character a Studio item names: a built-in one ({@code asset:avatar/topps.character.json}) or one in the Joy
 * Creator folder (its path there). Kept for a while once read, as a character file can be large; a character the room
 * saves again is read again ({@link #forget}).
 */
public final class CharacterLoader {
    public static final String ASSET = "asset:";
    private static final String TAG = "AvatarStudio";
    private static final int KEEP = 6;

    /** A character and its pictures, read once. */
    public static final class Loaded {
        public final String ref;
        public final CharacterDoc doc;
        public final CharacterPictures.Beside beside;

        Loaded(String ref, CharacterDoc doc, CharacterPictures.Beside beside) {
            this.ref = ref;
            this.doc = doc;
            this.beside = beside;
        }
    }

    private static final Map<String, Loaded> CACHE = new LinkedHashMap<String, Loaded>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Loaded> e) {
            return size() > KEEP;
        }
    };

    private CharacterLoader() {}

    public static String assetRef(String assetPath) {
        return ASSET + assetPath;
    }

    /** The character, or null when it cannot be found or read (the item then shows its standing picture). */
    public static Loaded load(Context c, String ref) {
        if (ref == null) return null;
        synchronized (CACHE) {
            Loaded l = CACHE.get(ref);
            if (l != null) return l;
        }
        Loaded l = read(c.getApplicationContext(), ref);
        if (l != null) synchronized (CACHE) {
            CACHE.put(ref, l);
        }
        return l;
    }

    public static void forget(String ref) {
        synchronized (CACHE) {
            CACHE.remove(ref);
        }
    }

    private static Loaded read(Context c, String ref) {
        try {
            if (ref.startsWith(ASSET)) {
                return new Loaded(ref, CharacterAssets.load(c, ref.substring(ASSET.length())), null);
            }
            CharacterDoc d = CharacterStore.read(c, ref);
            if (d == null) return null;
            final String dir = Placement.parentOf(ref);
            CharacterPictures.Beside beside = rel -> {
                try {
                    JoyFolder jf = JoyFolderHome.open(c);
                    return jf == null ? null : jf.readAll(dir == null || dir.isEmpty() ? rel : Placement.join(dir, rel));
                } catch (Exception e) {
                    return null;
                }
            };
            return new Loaded(ref, d, beside);
        } catch (Exception e) {
            Log.w(TAG, "character not found: " + ref, e);
            return null;
        }
    }
}
