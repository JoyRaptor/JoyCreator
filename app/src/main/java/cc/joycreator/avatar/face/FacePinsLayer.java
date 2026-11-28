package cc.joycreator.avatar.face;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.file.Reshape;
import cc.joycreator.avatar.ui.PoseRenderer;
import cc.joycreator.avatar.ui.StageView;

/**
 * Face pins on the picture (the pilot's {@code al-facepins.js} markers and taps), in Set up: every face pin of the character as a
 * numbered dot in its own colour; a point of your face picked in the card, a tap on the character drops a pin there and binds it
 * (on the head's whole drawing: one piece); a finger on a dot drags the pin, letting go is ONE undo step.
 */
public final class FacePinsLayer implements StageView.LandmarkLayer {

    public interface Host {
        CharacterDoc doc();

        PoseRenderer renderer();

        /** A part's matrix as drawn (its own space to stage space), or null. */
        double[] matrix(String id);

        /** The face point picked in the card (a mesh index), or -1. */
        int pendingPoint();

        /** Drop a pin on {@code nodeId} at (x, y) in its own space and bind face point {@code pt} to it: one named undo step. */
        void dropFacePin(String nodeId, double x, double y, int pt);

        /** A face pin's pin dragged to (x, y) in its part's own space: one named undo step. */
        void moveFacePin(String nodeId, String pinId, double x, double y);

        /** A face pin tapped: the card shows it. */
        void pickFacePin(String id);

        void say(int string);
    }

    /** The pilot's face pin colours, one a pin, in turn. */
    public static final int[] COLORS = {0xFF22D3EE, 0xFFF472B6, 0xFFFACC15, 0xFF4ADE80, 0xFFA78BFA, 0xFFFB923C, 0xFF60A5FA, 0xFFF87171};

    public static int colorOf(int i) {
        return COLORS[((i % COLORS.length) + COLORS.length) % COLORS.length];
    }

    private final Host host;

    public FacePinsLayer(Host host) {
        this.host = host;
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
        CharacterDoc d = host.doc();
        if (d == null) return out;
        JSONArray l = FacePins.list(d.json);
        for (int i = 0; i < l.length(); i++) {
            JSONObject q = l.optJSONObject(i);
            CharacterDoc.Node n = d.find(q.optString("node"));
            JSONObject pin = n == null ? null : pinOf(n.json, q.optString("pin"));
            double[] m = n == null ? null : host.matrix(n.id);
            if (pin == null || m == null) continue;                        // (a drawing not shown now: its pins are not drawn)
            double[] s = Reshape.shownAt(n.json, pin, null), at = ap(m, s[0], s[1]);
            StageView.Dot dot = new StageView.Dot();
            dot.x = (float) at[0];
            dot.y = (float) at[1];
            dot.color = colorOf(i);
            dot.label = String.valueOf(i + 1);
            dot.tag = new String[]{n.id, pin.optString("id"), q.optString("id")};
            out.add(dot);
        }
        return out;
    }

    static JSONObject pinOf(JSONObject n, String id) {
        JSONObject mesh = n.optJSONObject("mesh");
        JSONArray pins = mesh == null ? null : mesh.optJSONArray("pins");
        for (int i = 0; pins != null && i < pins.length(); i++) if (id.equals(pins.optJSONObject(i).optString("id"))) return pins.optJSONObject(i);
        return null;
    }

    @Override
    public void tap(float x, float y) {
        int pt = host.pendingPoint();
        if (pt < 0) {
            host.say(com.fadcam.R.string.avatar_facepins_pick_first);
            return;
        }
        CharacterDoc d = host.doc();
        PoseRenderer r = host.renderer();
        CharacterDoc.Node hit = r == null ? null : r.hit(x, y);
        if (d == null || hit == null) {
            host.say(com.fadcam.R.string.avatar_facepins_tap_character);
            return;
        }
        String role = hit.role == null ? "" : hit.role;
        if (role.startsWith("arm") || role.startsWith("leg") || role.startsWith("hand") || role.startsWith("foot")) {
            host.say(com.fadcam.R.string.avatar_facepins_not_limbs);
            return;
        }
        JSONObject sheet = FacePins.sheetOf(d.json, hit.json);
        CharacterDoc.Node sn = sheet == null ? hit : d.find(sheet.optString("id"));
        if (sn == null) sn = hit;
        double[] p = inv(host.matrix(sn.id), x, y);
        host.dropFacePin(sn.id, p[0], p[1], pt);
    }

    @Override
    public void picked(StageView.Dot d) {
        host.pickFacePin(((String[]) d.tag)[2]);
    }

    @Override
    public void moved(StageView.Dot d, float x, float y, boolean last) {
        if (!last) return;
        String[] t = (String[]) d.tag;
        double[] p = inv(host.matrix(t[0]), x, y);
        host.moveFacePin(t[0], t[1], p[0], p[1]);
    }
}
