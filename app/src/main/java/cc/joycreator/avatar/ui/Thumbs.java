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
    /** Draws a whole drawing small (its pieces decoded at a quarter); made on first use, kept while the pictures are the same. */
    private RestPose drawer;

    void use(CharacterDoc d) {
        if (doc == null || !doc.name.equals(d.name) || doc.pictures.size() != d.pictures.size()) {
            cache.evictAll();
            drawer = null;
        } else if (drawer != null) drawer.retarget(d);
        doc = d;
    }

    /**
     * A whole drawing (a group or set of pieces) as the character shows it standing still, at most {@code max} px on a side, or
     * null when it shows nothing. Not its first piece: the owner saw a blank white box for the head's first drawing (11-28),
     * which was its upper lid.
     */
    Bitmap ofDrawing(CharacterDoc.Node n, int max) {
        if (doc == null || n == null) return null;
        if ("art".equals(n.type)) return of(n.img, max);
        String key = n.id + "#" + n.json.toString().hashCode() + "@" + max;      // (a piece moved: a new picture)
        Bitmap b = cache.get(key);
        if (b != null) return b;
        if (drawer == null) drawer = new RestPose(doc, 4);
        android.graphics.RectF r = drawer.bounds(n);
        if (r == null || r.width() < 1 || r.height() < 1) return null;
        float s = max / Math.max(r.width(), r.height());
        b = Bitmap.createBitmap(Math.max(1, Math.round(r.width() * s)), Math.max(1, Math.round(r.height() * s)), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        c.scale(s, s);
        c.translate(-r.left, -r.top);
        drawer.drawSubtree(c, n, 0, 255);
        cache.put(key, b);
        return b;
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
