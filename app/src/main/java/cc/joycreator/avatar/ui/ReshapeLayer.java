package cc.joycreator.avatar.ui;

import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.file.Reshape;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Arrange, Reshape with pins: the pins of the part in hand sit on the picture where they are now; tap the part for a new one, drag
 * one and the part stretches from there (the pins left alone hold the rest still). While a finger drags, the live file is written so
 * the picture follows; when it lifts the old offset is put back and the move is made again as ONE named undo step.
 */
public final class ReshapeLayer implements StageView.LandmarkLayer {

    public interface Host {
        CharacterDoc doc();

        String inHand();

        /** The stance being worked on (its id), or null for standing. */
        String poseId();

        PoseRenderer renderer();

        /** The part's matrix as drawn (part space to stage space), or null. */
        double[] matrix(String id);

        /** A pin dragged to (x, y) in the part's own space, written into the live file so the picture follows; no step. */
        void live(String nodeId, String pinId, double x, double y);

        /** The finger lifted: the old offset is put back and the drag is made as one named undo step. */
        void commit(String nodeId, String pinId, double x, double y, JSONArray oldOffset);

        /** A new pin on the part at (x, y) in the part's own space: one named undo step. */
        void drop(String nodeId, double x, double y);

        /** "Tap on the part itself to drop a pin." */
        void say(int string);
    }

    private static final int VIOLET = 0xFFA78BFA;

    private final Host host;
    private JSONArray old;

    public ReshapeLayer(Host host) {
        this.host = host;
    }

    private CharacterDoc.Node node() {
        CharacterDoc d = host.doc();
        String id = host.inHand();
        return d == null || id == null ? null : d.find(id);
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
    public boolean loupe() {
        return true;
    }

    private static double[] ap(double[] m, double x, double y) {
        return m == null ? new double[]{x, y} : new double[]{m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]};
    }

    private static double[] inv(double[] m, double x, double y) {
        if (m == null) return new double[]{x, y};
        double det = m[0] * m[3] - m[1] * m[2];
        if (Math.abs(det) < 1e-9) return new double[]{x, y};
        double ix = x - m[4], iy = y - m[5];
        return new double[]{(m[3] * ix - m[2] * iy) / det, (-m[1] * ix + m[0] * iy) / det};
    }

    @Override
    public List<StageView.Dot> dots() {
        List<StageView.Dot> out = new ArrayList<>();
        CharacterDoc.Node n = node();
        if (n == null) return out;
        String pose = host.poseId();
        JSONObject mesh = n.json.optJSONObject("mesh");
        JSONArray pins = mesh == null ? null : mesh.optJSONArray("pins");
        if (pins == null) return out;
        Map<String, Double> W = Reshape.weights(n.json, pose);
        double[] m = host.matrix(n.id);
        for (int i = 0; i < pins.length(); i++) {
            JSONObject q = pins.optJSONObject(i);
            if (q == null) continue;
            String home = Reshape.pinHome(n.json, q);                      // a pin dropped in another stance holds nothing here
            if (home.startsWith("pose:") && !W.containsKey(home)) continue;
            double[] at = ap(m, Reshape.shownAt(n.json, q, pose)[0], Reshape.shownAt(n.json, q, pose)[1]);
            StageView.Dot d = new StageView.Dot();
            d.x = (float) at[0];
            d.y = (float) at[1];
            d.color = VIOLET;
            d.label = String.valueOf(i + 1);
            d.tag = q.optString("id");
            out.add(d);
        }
        return out;
    }

    @Override
    public void tap(float x, float y) {
        CharacterDoc.Node n = node();
        if (n == null) return;
        PoseRenderer r = host.renderer();
        CharacterDoc.Node hit = r == null ? null : r.hit(x, y);
        if (hit == null || !within(n, hit)) {
            host.say(com.fadcam.R.string.avatar_reshape_tap_part);
            return;
        }
        double[] p = inv(host.matrix(n.id), x, y);
        host.drop(n.id, p[0], p[1]);
    }

    private static boolean within(CharacterDoc.Node top, CharacterDoc.Node q) {
        if (top == q || top.id.equals(q.id)) return true;
        for (CharacterDoc.Node k : top.kids) if (within(k, q)) return true;
        return false;
    }

    @Override
    public void picked(StageView.Dot d) {
        CharacterDoc.Node n = node();
        JSONObject fs = n == null ? null : Reshape.fixShape(n.json, host.poseId());
        JSONObject dd = fs == null ? null : fs.optJSONObject("d");
        JSONArray o = dd == null ? null : dd.optJSONArray((String) d.tag);
        try {
            old = o == null ? null : new JSONArray(o.toString());
        } catch (org.json.JSONException e) {
            old = null;
        }
    }

    @Override
    public void moved(StageView.Dot d, float x, float y, boolean last) {
        CharacterDoc.Node n = node();
        if (n == null) return;
        double[] p = inv(host.matrix(n.id), x, y);
        if (last) host.commit(n.id, (String) d.tag, p[0], p[1], old);
        else host.live(n.id, (String) d.tag, p[0], p[1]);
    }
}
