package cc.joycreator.avatar.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RectF;
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
    private CharacterDoc doc;
    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    RestPose(CharacterDoc doc) {
        this.doc = doc;
    }

    /** The same character after an edit of its tree: the pictures are the same, so the decoded ones are kept. */
    void retarget(CharacterDoc d) {
        doc = d;
    }

    boolean samePictures(CharacterDoc d) {
        return doc.pictures == d.pictures || doc.pictures.size() == d.pictures.size() && doc.name.equals(d.name) && doc.pictures.keySet().equals(d.pictures.keySet());
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

    /** "Try this drawing": the drawing the person chose to look at for a set, kept only while they look. */
    private final Map<String, String> tried = new HashMap<>();

    void tryDrawing(String setId, String kidId) {
        if (kidId == null) tried.remove(setId);
        else tried.put(setId, kidId);
    }

    /** The drawing a set shows when nothing drives it. */
    private CharacterDoc.Node pick(CharacterDoc.Node set) {
        String t = tried.get(set.id);
        if (t != null) for (CharacterDoc.Node k : set.kids) if (k.id.equals(t) && !k.hidden) return k;
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

    /** Where a part is on the picture (the drawings it shows now), or null when it shows nothing. */
    RectF bounds(CharacterDoc.Node n) {
        RectF out = new RectF();
        boolean[] any = {false};
        grow(n, out, any);
        return any[0] ? out : null;
    }

    private void grow(CharacterDoc.Node n, RectF out, boolean[] any) {
        if (n.hidden) return;
        switch (n.type) {
            case "art": {
                Bitmap b = bitmap(n.img);
                if (b == null) return;
                RectF r = new RectF(n.x, n.y, n.x + b.getWidth(), n.y + b.getHeight());
                if (any[0]) out.union(r);
                else out.set(r);
                any[0] = true;
                return;
            }
            case "set": {
                CharacterDoc.Node k = pick(n);
                if (k != null) grow(k, out, any);
                return;
            }
            case "mirror":
                return;
            default:
                for (CharacterDoc.Node k : n.kids) grow(k, out, any);
        }
    }

    void release() {
        for (Bitmap b : bitmaps.values()) b.recycle();
        bitmaps.clear();
    }
}
