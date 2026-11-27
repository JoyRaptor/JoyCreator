package cc.joycreator.avatar.studio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import cc.joycreator.avatar.file.CharacterDoc;

import java.util.Locale;
import java.util.Map;

/**
 * The stand-in {@link CharacterPoser}: the character standing still, one drawing of each set, no bending (the same
 * rule as the room's first picture, {@code avatar/ui/RestPose}). It is replaced by the solver's poser when X2 and X3
 * land, not extended: no movement is ever invented here.
 *
 * <p>One thing a take does change even now: the mouth shape. A set picked by mouth shape ({@code pick.by = viseme})
 * shows the drawing named for the take's {@code vis}, so lip sync can be seen and checked before the solver.</p>
 */
public final class RestPoser implements CharacterPoser {
    private final CharacterDoc doc;
    private final CharacterPictures pictures;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    public RestPoser(CharacterDoc doc, CharacterPictures pictures) {
        this.doc = doc;
        this.pictures = pictures;
    }

    @Override
    public void draw(Canvas c, Map<String, Object> signals, double seconds) {
        Object vis = signals == null ? null : signals.get("vis");
        drawNode(c, doc.root, vis instanceof String ? (String) vis : null);
    }

    private void drawNode(Canvas c, CharacterDoc.Node n, String vis) {
        if (n.hidden) return;
        switch (n.type) {
            case "art": {
                Bitmap b = pictures.get(n.img);
                if (b != null) c.drawBitmap(b, n.x, n.y, paint);
                break;
            }
            case "set": {
                CharacterDoc.Node k = pick(n, vis);
                if (k != null) drawNode(c, k, vis);
                break;
            }
            case "mirror":
                break;   // an angle that is another one flipped: the solver's job
            default:
                for (CharacterDoc.Node k : n.kids) drawNode(c, k, vis);
        }
    }

    /**
     * The drawing a set shows: the take's mouth shape for a mouth-shape set; else its resting drawing ({@code pick.rest});
     * else the front of a turn set, or the first.
     */
    static CharacterDoc.Node pick(CharacterDoc.Node set, String vis) {
        org.json.JSONObject pk = set.json.optJSONObject("pick");
        String rest = pk == null ? "" : pk.optString("rest", "");
        if (!rest.isEmpty() && !("viseme".equals(set.pickBy) && vis != null)) {
            for (CharacterDoc.Node k : set.kids) if (!k.hidden && k.id.equals(rest)) return k;
        }
        CharacterDoc.Node first = null;
        for (CharacterDoc.Node k : set.kids) {
            if (k.hidden) continue;
            if (first == null) first = k;
            if ("viseme".equals(set.pickBy) && vis != null && k.name.equalsIgnoreCase(vis)) return k;
            if ("turn".equals(set.pickBy) && k.name.toLowerCase(Locale.ROOT).contains("front")) return k;
        }
        if ("viseme".equals(set.pickBy)) {
            for (CharacterDoc.Node k : set.kids) if (!k.hidden && k.name.equalsIgnoreCase("Neutral")) return k;
        }
        return first;
    }

    @Override
    public void rewind() {}

    @Override
    public void release() {
        pictures.release();
    }
}
