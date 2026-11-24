package cc.joycreator.avatar.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Base64;

import cc.joycreator.avatar.file.CharacterDoc;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Draws a character standing still: the tree as the file has it, one drawing of each set, no bending.
 *
 * <p>This is the room's first picture, before the solver port (X2) and the GL drawing (X3) exist. It is deliberately
 * not the real pose: the stage says so nowhere to the user, but it is the only thing that draws a character until
 * {@code CharacterSolver} lands, and it is replaced then, not extended.</p>
 */
final class RestPose {
    private final CharacterDoc doc;
    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    RestPose(CharacterDoc doc) {
        this.doc = doc;
    }

    /** Draws into the rectangle at the origin of the canvas, sized doc.w x doc.h (the caller scales). */
    void draw(Canvas c) {
        drawNode(c, doc.root);
    }

    private void drawNode(Canvas c, CharacterDoc.Node n) {
        if (n.hidden) return;
        switch (n.type) {
            case "art": {
                Bitmap b = bitmap(n.img);
                if (b != null) c.drawBitmap(b, n.x, n.y, paint);
                break;
            }
            case "set": {
                CharacterDoc.Node k = pick(n);
                if (k != null) drawNode(c, k);
                break;
            }
            case "mirror":
                break; // an angle that is another one flipped: the solver's job
            default:
                for (CharacterDoc.Node k : n.kids) drawNode(c, k);
        }
    }

    /** The drawing a set shows when nothing drives it. */
    private static CharacterDoc.Node pick(CharacterDoc.Node set) {
        CharacterDoc.Node first = null;
        for (CharacterDoc.Node k : set.kids) {
            if (k.hidden) continue;
            if (first == null) first = k;
            if ("turn".equals(set.pickBy) && k.name.toLowerCase(Locale.ROOT).contains("front")) return k;
        }
        return first;
    }

    private Bitmap bitmap(String img) {
        if (img.isEmpty()) return null;
        Bitmap b = bitmaps.get(img);
        if (b != null) return b;
        String src = doc.pictures.get(img);
        if (src == null) return null;
        int comma = src.indexOf(',');
        if (!src.startsWith("data:") || comma < 0) return null; // a picture beside the file: the folder reader's job
        try {
            byte[] raw = Base64.decode(src.substring(comma + 1), Base64.DEFAULT);
            b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (IllegalArgumentException e) {
            b = null;
        }
        if (b != null) bitmaps.put(img, b);
        return b;
    }

    void release() {
        for (Bitmap b : bitmaps.values()) b.recycle();
        bitmaps.clear();
    }
}
