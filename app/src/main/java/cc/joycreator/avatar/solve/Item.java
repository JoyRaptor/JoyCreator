package cc.joycreator.avatar.solve;

import org.json.JSONObject;

import java.util.List;

/**
 * One drawn piece of the character for one frame: a drawing's mesh, posed, in stage space. Drawn in list order, bottom first.
 * The pilot's {@code out.items[]} entry (al-solve.js).
 */
public final class Item {
    public String id, img, tag = "", under;
    /** posed vertices (x, y pairs), texture places (u, v pairs, 0..1), triangles */
    public float[] v, uv;
    public int[] idx;
    public double a;
    /** a faint copy (onion skin), never hit-tested */
    public boolean ghost;
    /** keeps its own colours when a colour set is on */
    public boolean nc;
    public int nx, ny;
    /** masks: "show only inside" / "hidden where" (the records as they are in the file) */
    public List<JSONObject> clip;
    /** the masks resolved after the frame: what is inside, what cuts */
    public Mask mask;
    Z zo;
    boolean dead;

    /** A resolved mask: the drawn pieces it must be inside, and those that cut it. */
    public static final class Mask {
        public String key;
        public List<Item> inside;
        public List<List<Item>> cuts, more;
    }

    Item copy() {
        Item o = new Item();
        o.id = id; o.img = img; o.tag = tag; o.under = under; o.v = v; o.uv = uv; o.idx = idx; o.a = a; o.ghost = ghost; o.nc = nc;
        o.nx = nx; o.ny = ny; o.clip = clip; o.mask = mask; o.zo = zo; o.dead = dead;
        return o;
    }

    /** A stance's (or a part's own) order rule: under / over / front / back of another part. Compared by identity, as in the pilot. */
    static final class Z {
        final String how, of;
        final Double at, tip;

        Z(String how, String of, Double at, Double tip) { this.how = how; this.of = of; this.at = at; this.tip = tip; }

        static Z of(JSONObject j) { return new Z(j.optString("how", ""), J.s(j, "of"), J.num(j, "at"), J.num(j, "tip")); }
    }
}
