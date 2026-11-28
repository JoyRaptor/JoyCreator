package cc.joycreator.avatar.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;

import cc.joycreator.avatar.file.CharacterDoc;

/** Small pictures of a character's drawings for lists (decoded smaller, kept a few; the stage draws the full ones). */
final class Thumbs {
    private final LruCache<String, Bitmap> cache = new LruCache<>(80);
    private CharacterDoc doc;

    void use(CharacterDoc d) {
        if (doc == null || !doc.name.equals(d.name) || doc.pictures.size() != d.pictures.size()) cache.evictAll();
        doc = d;
    }

    /** The drawing's picture at most {@code max} px on a side, or null when it is not inside the file. */
    Bitmap of(String img, int max) {
        if (doc == null || img == null || img.isEmpty()) return null;
        String key = img + "@" + max;
        Bitmap b = cache.get(key);
        if (b != null) return b;
        String src = doc.pictures.get(img);
        if (src == null) return null;
        int comma = src.indexOf(',');
        if (!src.startsWith("data:") || comma < 0) return null;
        try {
            byte[] raw = Base64.decode(src.substring(comma + 1), Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        } catch (IllegalArgumentException e) {
            b = null;
        }
        if (b != null) cache.put(key, b);
        return b;
    }
}
