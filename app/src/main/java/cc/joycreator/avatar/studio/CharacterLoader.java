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
        /** When the file was last changed as it was read (0 for a built-in one). */
        final long modified;
        volatile long checkedAt;

        Loaded(String ref, CharacterDoc doc, CharacterPictures.Beside beside, long modified) {
            this.ref = ref;
            this.doc = doc;
            this.beside = beside;
            this.modified = modified;
            this.checkedAt = System.currentTimeMillis();
        }
    }

    /** How often a kept character's file is looked at again, at most (a stat in the person's folder). */
    static final long CHECK_MS = 2000;

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
        Loaded kept;
        synchronized (CACHE) {
            kept = CACHE.get(ref);
        }
        // the room saved the character again (its file changed): read it again, so Studio's preview and export both
        // draw the character as it is now, never one each (X6 M6)
        if (kept != null && (ref.startsWith(ASSET) || !changed(c.getApplicationContext(), kept))) return kept;
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

    /** True when the file behind a kept character changed since it was read (looked at once in {@link #CHECK_MS}). */
    static boolean changed(Context c, Loaded l) {
        long now = System.currentTimeMillis();
        if (now - l.checkedAt < CHECK_MS) return false;
        l.checkedAt = now;
        long m = modifiedOf(c, l.ref);
        return m != 0 && m != l.modified;
    }

    /** The character as Studio has it now: {@code l} itself, or a newer read when its file changed (null: unchanged). */
    public static Loaded newer(Context c, Loaded l) {
        if (l == null || l.ref.startsWith(ASSET) || !changed(c.getApplicationContext(), l)) return null;
        Loaded n = read(c.getApplicationContext(), l.ref);
        if (n != null) synchronized (CACHE) {
            CACHE.put(l.ref, n);
        }
        return n;
    }

    private static long modifiedOf(Context c, String ref) {
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            com.fadcam.joyfolder.JoyFs.Entry e = jf == null ? null : jf.fs.stat(ref);
            return e == null ? 0 : e.modified;
        } catch (Exception e) {
            return 0;
        }
    }

    private static Loaded read(Context c, String ref) {
        try {
            if (ref.startsWith(ASSET)) {
                return new Loaded(ref, CharacterAssets.load(c, ref.substring(ASSET.length())), null, 0);
            }
            long modified = modifiedOf(c, ref);
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
            return new Loaded(ref, d, beside, modified);
        } catch (Exception e) {
            Log.w(TAG, "character not found: " + ref, e);
            return null;
        }
    }
}
