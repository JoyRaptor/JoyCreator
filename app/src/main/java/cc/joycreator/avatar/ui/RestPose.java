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
final class RestPose implements PoseRenderer {
    private CharacterDoc doc;
    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    RestPose(CharacterDoc doc) {
        this.doc = doc;
    }

    /** The same character after an edit of its tree: the pictures are the same, so the decoded ones are kept. */
    @Override
    public void retarget(CharacterDoc d) {
        doc = d;
    }

    @Override
    public boolean samePictures(CharacterDoc d) {
        return doc.pictures == d.pictures || doc.pictures.size() == d.pictures.size() && doc.name.equals(d.name) && doc.pictures.keySet().equals(d.pictures.keySet());
    }

    /** One part drawn on its own, optionally tinted and see-through (the ghost of a neighbouring drawing while matching). */
    @Override
    public void drawSubtree(Canvas c, CharacterDoc.Node n, int tint, int alpha) {
        paint.setAlpha(alpha);
        if (tint != 0) paint.setColorFilter(new android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_ATOP));
        drawNode(c, n);
        paint.setColorFilter(null);
        paint.setAlpha(255);
    }

    /** Draws into the rectangle at the origin of the canvas, sized doc.w x doc.h (the caller scales). */
    @Override
    public void draw(Canvas c) {
        drawNode(c, doc.root);
    }

    private void drawNode(Canvas c, CharacterDoc.Node n) {
        if (n.hidden) return;
        switch (n.type) {
            case "art": {
                if (blinkHides(n)) break;
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

    @Override
    public void tryDrawing(String setId, String kidId) {
        if (kidId == null) tried.remove(setId);
        else tried.put(setId, kidId);
    }

    /** What the live hands and the take say now (the take's own lane names); empty until Perform or Motion drives it. */
    private Map<String, Object> sig = new HashMap<>();

    @Override
    public void setSignals(Map<String, Object> s) {
        sig = s == null ? new HashMap<>() : s;
    }

    private double num(String k) {
        Object v = sig.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    /**
     * The drawing a set shows. A crude stand-in for the solver until X2 lands: a turning head shows the angle nearest to
     * where the pad is (a hard swap, no bending), a mouth of shapes shows the shape, a set with buttons shows the one a
     * button switched on, otherwise the resting drawing.
     */
    private CharacterDoc.Node pick(CharacterDoc.Node set) {
        String t = tried.get(set.id);
        if (t != null) for (CharacterDoc.Node k : set.kids) if (k.id.equals(t) && !k.hidden) return k;
        if ("turn".equals(set.pickBy)) {
            CharacterDoc.Node best = null;
            double bd = 1e9;
            for (CharacterDoc.Node k : set.kids) {
                if (k.hidden || "mirror".equals(k.type)) continue;
                org.json.JSONArray at = k.json.optJSONArray("at");
                if (at == null || at.length() < 2) continue;
                double dx = at.optDouble(0) - num("turnX"), dy = at.optDouble(1) - num("turnY"), d = dx * dx + dy * dy;
                if (d < bd) {
                    bd = d;
                    best = k;
                }
            }
            if (best != null) return best;
        } else if ("viseme".equals(set.pickBy)) {
            Object v = sig.get("vis");
            for (CharacterDoc.Node k : set.kids) if (!k.hidden && v != null && v.equals(k.json.optString("shape", null))) return k;
        } else {
            for (CharacterDoc.Node k : set.kids) {
                String trig = k.json.optString("trig", "");
                if (!k.hidden && !trig.isEmpty() && num("g:" + trig) > 0.5) return k;
            }
            String rest = set.json.optJSONObject("pick") == null ? "" : set.json.optJSONObject("pick").optString("rest", "");
            if (!rest.isEmpty()) for (CharacterDoc.Node k : set.kids) if (k.id.equals(rest)) return k;
        }
        CharacterDoc.Node first = null;
        for (CharacterDoc.Node k : set.kids) {
            if (k.hidden) continue;
            if (first == null) first = k;
            if ("turn".equals(set.pickBy) && k.name.toLowerCase(Locale.ROOT).contains("front")) return k;
        }
        return first;
    }

    /** An eye drawn closed shows only while the eyes are shut, and the open ones hide then. */
    private boolean blinkHides(CharacterDoc.Node n) {
        String b = n.json.optString("blink", "");
        if (b.isEmpty()) return false;
        boolean shut = num("blinkL") > 0.5 || num("blinkR") > 0.5;
        return "closed".equals(b) ? !shut : shut;
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
    @Override
    public RectF bounds(CharacterDoc.Node n) {
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

    @Override
    public java.util.List<CharacterDoc.Node> shown(CharacterDoc.Node n) {
        java.util.List<CharacterDoc.Node> out = new java.util.ArrayList<>();
        collectShown(n, out);
        return out;
    }

    private void collectShown(CharacterDoc.Node n, java.util.List<CharacterDoc.Node> out) {
        if (n.hidden) return;
        out.add(n);
        switch (n.type) {
            case "set": {
                CharacterDoc.Node k = pick(n);
                if (k != null) collectShown(k, out);
                return;
            }
            case "art":
            case "mirror":
                return;
            default:
                for (CharacterDoc.Node k : n.kids) collectShown(k, out);
        }
    }

    @Override
    public void release() {
        for (Bitmap b : bitmaps.values()) b.recycle();
        bitmaps.clear();
    }
}
