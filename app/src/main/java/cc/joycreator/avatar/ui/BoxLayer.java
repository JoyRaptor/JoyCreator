package cc.joycreator.avatar.ui;

import android.graphics.RectF;

import cc.joycreator.avatar.file.CharacterDoc;

import com.fadcam.ui.faditor.Studio;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Arrange: the transform box on the part in hand (the pilot's {@code al-fit.js}): drag inside it to move, a corner to
 * resize, the round handle below it to turn. Nothing is repainted: the part remembers a move, a turn and a size
 * ({@code dx dy rot sc}, turning and resizing about its pivot), and "Put it back" clears them.
 *
 * <p>While a finger is down the numbers are written into the live file so the part follows it; when it lifts they are put
 * back and written again as ONE named undo step, so a drag is one step however long it was.</p>
 */
public final class BoxLayer implements StageView.LandmarkLayer {

    public interface Host {
        CharacterDoc doc();

        String inHand();

        PoseRenderer renderer();

        /** Writes the part's four numbers as one named undo step (the live numbers have been put back first). */
        void commitBox(String nodeId, double dx, double dy, double rot, double sc);

        void redraw();

        /** The part's move, turn and size right now (in the stance being worked on, if any): {dx, dy, rot, sc}. */
        double[] numbers(String nodeId);

        /** Shows the numbers while a finger is down, without making a step. */
        void live(String nodeId, double dx, double dy, double rot, double sc);
    }

    private final Host host;
    private double dx0, dy0, rot0, sc0;          // the part's numbers when the finger went down
    private float fx0, fy0;                      // where the finger went down (character pixels)
    private int mode = -1;                       // -1 none, 0..3 a corner, 4 the turn handle, 5 move inside

    public BoxLayer(Host host) {
        this.host = host;
    }

    private CharacterDoc.Node node() {
        CharacterDoc d = host.doc();
        String id = host.inHand();
        return d == null || id == null ? null : d.find(id);
    }

    private static double num(JSONObject j, String k, double def) {
        return j.has(k) ? j.optDouble(k, def) : def;
    }

    /** The point the part turns and resizes about: its pivot, else the middle of its drawing. */
    private float[] pivot(CharacterDoc.Node n, RectF b) {
        JSONObject p = n.json.optJSONObject("pivot");
        if (p != null && p.has("x")) return new float[]{(float) p.optDouble("x"), (float) p.optDouble("y")};
        return new float[]{b.centerX(), b.centerY()};
    }

    /** A point of the untransformed drawing, moved by the part's numbers. */
    private float[] apply(CharacterDoc.Node n, RectF b, float x, float y) {
        float[] pv = pivot(n, b);
        double[] nu = host.numbers(n.id);
        double dx = nu[0], dy = nu[1], rot = Math.toRadians(nu[2]), sc = nu[3];
        double rx = (x - pv[0]) * sc, ry = (y - pv[1]) * sc;
        return new float[]{(float) (pv[0] + dx + rx * Math.cos(rot) - ry * Math.sin(rot)), (float) (pv[1] + dy + rx * Math.sin(rot) + ry * Math.cos(rot))};
    }

    private RectF bounds(CharacterDoc.Node n) {
        PoseRenderer r = host.renderer();
        return r == null ? null : r.bounds(n);
    }

    @Override
    public CharacterDoc.Node solid() {
        return null;
    }

    @Override
    public CharacterDoc.Node ghost() {
        return null;
    }

    @Override
    public int ghostTint() {
        return 0;
    }

    @Override
    public List<StageView.Dot> dots() {
        List<StageView.Dot> out = new ArrayList<>();
        CharacterDoc.Node n = node();
        RectF b = n == null ? null : bounds(n);
        if (b == null) return out;
        float[][] c = {{b.left, b.top}, {b.right, b.top}, {b.right, b.bottom}, {b.left, b.bottom}};
        float[][] t = new float[4][];
        for (int i = 0; i < 4; i++) {
            t[i] = apply(n, b, c[i][0], c[i][1]);
            StageView.Dot d = new StageView.Dot();
            d.x = t[i][0];
            d.y = t[i][1];
            d.color = Studio.ARMED;
            d.tag = i;
            out.add(d);
        }
        // the turn handle: below the bottom edge, along the part's own "down"
        float[] mid = {(t[2][0] + t[3][0]) / 2f, (t[2][1] + t[3][1]) / 2f}, top = {(t[0][0] + t[1][0]) / 2f, (t[0][1] + t[1][1]) / 2f};
        float vx = mid[0] - top[0], vy = mid[1] - top[1], len = (float) Math.hypot(vx, vy);
        if (len > 1e-3) {
            StageView.Dot h = new StageView.Dot();
            h.x = mid[0] + vx / len * 34;
            h.y = mid[1] + vy / len * 34;
            h.color = Studio.ARMED;
            h.state = "behind";           // a ring, so it reads as a handle, not a pin
            h.tag = 4;
            out.add(h);
        }
        return out;
    }

    @Override
    public void tap(float x, float y) {
    }

    @Override
    public void picked(StageView.Dot d) {
        CharacterDoc.Node n = node();
        if (n == null) return;
        mode = (Integer) d.tag;
        remember(n);
        fx0 = d.x;
        fy0 = d.y;
    }

    private void remember(CharacterDoc.Node n) {
        double[] nu = host.numbers(n.id);
        dx0 = nu[0];
        dy0 = nu[1];
        rot0 = nu[2];
        sc0 = nu[3];
    }

    @Override
    public boolean beginMove(float x, float y) {
        CharacterDoc.Node n = node();
        RectF b = n == null ? null : bounds(n);
        if (b == null) return false;
        float minX = 1e9f, minY = 1e9f, maxX = -1e9f, maxY = -1e9f;
        float[][] c = {{b.left, b.top}, {b.right, b.top}, {b.right, b.bottom}, {b.left, b.bottom}};
        for (float[] p : c) {
            float[] q = apply(n, b, p[0], p[1]);
            minX = Math.min(minX, q[0]);
            maxX = Math.max(maxX, q[0]);
            minY = Math.min(minY, q[1]);
            maxY = Math.max(maxY, q[1]);
        }
        if (x < minX || x > maxX || y < minY || y > maxY) return false;
        mode = 5;
        remember(n);
        fx0 = x;
        fy0 = y;
        return true;
    }

    @Override
    public void moved(StageView.Dot d, float x, float y, boolean last) {
        drag(x, y, last);
    }

    @Override
    public void moveBy(float x, float y, boolean last) {
        drag(x, y, last);
    }

    private void drag(float x, float y, boolean last) {
        CharacterDoc.Node n = node();
        RectF b = n == null ? null : bounds(n);
        if (n == null || b == null || mode < 0) return;
        float[] pv = pivot(n, b);
        double dx = dx0, dy = dy0, rot = rot0, sc = sc0;
        float px = (float) (pv[0] + dx0), py = (float) (pv[1] + dy0);        // the pivot where it stands at the start
        if (mode == 5) {
            dx = dx0 + (x - fx0);
            dy = dy0 + (y - fy0);
        } else if (mode == 4) {
            rot = rot0 + Math.toDegrees(Math.atan2(y - py, x - px) - Math.atan2(fy0 - py, fx0 - px));
        } else {
            double a = Math.hypot(fx0 - px, fy0 - py), c = Math.hypot(x - px, y - py);
            if (a > 1e-3) sc = Math.max(0.05, Math.min(8, sc0 * c / a));
        }
        dx = Math.round(dx * 10) / 10.0;
        dy = Math.round(dy * 10) / 10.0;
        rot = Math.round(rot * 10) / 10.0;
        sc = Math.round(sc * 1000) / 1000.0;
        if (last) {
            host.live(n.id, dx0, dy0, rot0, sc0);                 // put the live numbers back, so the step records the true "before"
            mode = -1;
            host.commitBox(n.id, dx, dy, rot, sc);
        } else {
            host.live(n.id, dx, dy, rot, sc);
            host.redraw();
        }
    }

    /** Writes the four numbers (a zero move or turn and a size of one are simply absent). */
    public static void put(JSONObject j, double dx, double dy, double rot, double sc) throws JSONException {
        if (dx != 0) j.put("dx", dx);
        else j.remove("dx");
        if (dy != 0) j.put("dy", dy);
        else j.remove("dy");
        if (rot != 0) j.put("rot", rot);
        else j.remove("rot");
        if (sc != 1) j.put("sc", sc);
        else j.remove("sc");
    }
}
