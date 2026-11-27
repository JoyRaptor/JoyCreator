package cc.joycreator.avatar.ui;

import cc.joycreator.avatar.file.CharacterDoc;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Set up: the pins of the part in hand, on the picture. Yellow = where it turns, blue = holds a spot as it bends (the
 * pilot's colours). Only the pins of the drawings that are showing are drawn. A finger on a pin drags it, with the loupe
 * above the finger; letting go is ONE undo step. The pins are ordinary data in the file ({@code pivot}, {@code limb} a / b
 * / c, {@code swing.tip}); guessing them from the pixels is the solver lane's.
 */
public final class PinsLayer implements StageView.LandmarkLayer {

    public interface Host {
        CharacterDoc doc();

        /** The part in hand (an id), or null. */
        String inHand();

        PoseRenderer renderer();

        /** Moves one pin: {@code nodeId}'s {@code path} ("pivot", "limb.a", "limb.b", "limb.c", "swing.tip") to x, y, as one step. */
        void movePin(String nodeId, String path, float x, float y);
    }

    /** The pilot's pin colours: where it turns, and what it holds. */
    private static final int YELLOW = 0xFFFACC15, BLUE = 0xFF60A5FA;

    private final Host host;

    public PinsLayer(Host host) {
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

    @Override
    public List<StageView.Dot> dots() {
        List<StageView.Dot> out = new ArrayList<>();
        CharacterDoc doc = host.doc();
        PoseRenderer r = host.renderer();
        String id = host.inHand();
        CharacterDoc.Node n = id == null || doc == null ? null : doc.find(id);
        if (n == null || r == null) return out;
        for (CharacterDoc.Node k : r.shown(n)) {
            add(out, k, "pivot", k.json.optJSONObject("pivot"), YELLOW);
            JSONObject limb = k.json.optJSONObject("limb");
            if (limb != null) {
                add(out, k, "limb.a", limb.optJSONObject("a"), YELLOW);
                add(out, k, "limb.b", limb.optJSONObject("b"), BLUE);
                add(out, k, "limb.c", limb.optJSONObject("c"), BLUE);
            }
            JSONObject swing = k.json.optJSONObject("swing");
            if (swing != null) add(out, k, "swing.tip", swing.optJSONObject("tip"), BLUE);
        }
        return out;
    }

    private static void add(List<StageView.Dot> out, CharacterDoc.Node k, String path, JSONObject p, int color) {
        if (p == null || !p.has("x") || !p.has("y")) return;
        StageView.Dot d = new StageView.Dot();
        d.x = (float) p.optDouble("x");
        d.y = (float) p.optDouble("y");
        d.color = color;
        d.tag = new String[]{k.id, path};
        out.add(d);
    }

    @Override
    public void tap(float x, float y) {
    }

    @Override
    public void picked(StageView.Dot d) {
    }

    @Override
    public void moved(StageView.Dot d, float x, float y, boolean last) {
        if (!last) return;
        String[] t = (String[]) d.tag;
        host.movePin(t[0], t[1], x, y);
    }
}
