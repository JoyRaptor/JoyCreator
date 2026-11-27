package cc.joycreator.avatar.studio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import cc.joycreator.avatar.file.CharacterDoc;

import java.util.HashMap;
import java.util.Map;

/**
 * A character's pictures, decoded once: inside the file ({@code data:} text) or beside it in the Joy Creator folder
 * ({@code Name.pictures/<id>.png}, AVA_FORMAT.md B1 rule 4), read through {@link Beside}.
 */
public final class CharacterPictures {

    /** Reads a picture kept beside the character's file; null when it cannot. */
    public interface Beside {
        byte[] read(String relativePath);
    }

    private final CharacterDoc doc;
    private final Beside beside;
    private final Map<String, Bitmap> cache = new HashMap<>();

    public CharacterPictures(CharacterDoc doc, Beside beside) {
        this.doc = doc;
        this.beside = beside;
    }

    /** The picture with this id, or null (a missing picture is drawn as nothing, never as an error). */
    public synchronized Bitmap get(String img) {
        if (img == null || img.isEmpty()) return null;
        if (cache.containsKey(img)) return cache.get(img);
        Bitmap b = null;
        String src = doc.pictures.get(img);
        try {
            if (src != null && src.startsWith("data:")) {
                int comma = src.indexOf(',');
                if (comma > 0) {
                    byte[] raw = Base64.decode(src.substring(comma + 1), Base64.DEFAULT);
                    b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                }
            } else if (src != null && beside != null) {
                byte[] raw = beside.read(src);
                if (raw != null) b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            b = null;
        }
        cache.put(img, b);
        return b;
    }

    public synchronized void release() {
        for (Bitmap b : cache.values()) if (b != null) b.recycle();
        cache.clear();
    }
}
