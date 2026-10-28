import com.fadcam.ui.faditor.model.LinkPose;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.vislab.studio.VisFollow;

/**
 * Roadmap R8: a look layer follows a Studio object. Where that object is, measured inside the
 * visualizer's box in the look's scene units (short side -1..+1, y up), whatever the box's place,
 * size and turn; and how much it has grown and turned.
 */
public class VisFollowTest {

    static int fails, passes;
    static final float ASPECT = 16f / 9f;

    /** A followed object with a fixed pose. */
    static LinkPose at(float x, float y, float size, float rot) {
        return new LinkPose() {
            @Override public String getId() { return "dummy"; }
            @Override public float animatedCenterX(long t) { return x; }
            @Override public float animatedCenterY(long t) { return y; }
            @Override public float animatedSizeFraction(long t) { return size; }
            @Override public float animatedRotation(long t) { return rot; }
            @Override public float animatedOpacity(long t) { return 1f; }
        };
    }

    static WaveformOverlayInstance viz(float rot) {
        WaveformOverlayInstance w = new WaveformOverlayInstance("viz", "bars");
        w.setVisLook("aurora-parts");
        w.setCenter(0.5f, 0.5f);
        w.setSize(0.8f, 0.25f);     // a wide strip: its short side is the height, 0.25 of the frame
        w.setRotationDeg(rot);
        return w;
    }

    static float[] follow(WaveformOverlayInstance w, LinkPose p, int layer) {
        w.getVisFollowRefs().clear();
        w.getVisFollowRefs().put(WaveformOverlayInstance.visFollowKey("aurora-parts", layer), p);
        return VisFollow.at(w, 0, ASPECT);
    }

    public static void main(String[] args) {
        float[] f = follow(viz(0), at(0.5f, 0.5f, 1f, 0f), 2);
        check("centre on centre: layer 2 at (0, 0): " + str(f),
                f != null && f.length == 6 && f[0] == 2 && near(f[1], 0) && near(f[2], 0) && near(f[3], 1) && near(f[4], 0)
                        && f[5] == 0f);
        // Half the box's short side above the centre (screen y is down) = +1 in the look.
        f = follow(viz(0), at(0.5f, 0.5f - 0.125f, 1f, 0f), 0);
        check("top edge of the strip = y +1: " + str(f), f != null && near(f[1], 0) && near(f[2], 1));
        // The same distance to the right, in frame-height units, = x +1.
        f = follow(viz(0), at(0.5f + 0.125f / ASPECT, 0.5f, 1f, 0f), 0);
        check("right by half the short side = x +1: " + str(f), f != null && near(f[1], 1) && near(f[2], 0));
        // Box turned 90 degrees clockwise: its own "up" now points to the screen's right.
        f = follow(viz(90), at(0.5f + 0.125f / ASPECT, 0.5f, 1f, 0f), 0);
        check("box turned 90: screen-right is the look's up: " + str(f), f != null && near(f[1], 0) && near(f[2], 1));
        // Grown to twice its size and turned 30 degrees clockwise.
        f = follow(viz(0), at(0.5f, 0.5f, 2f, 30f), 0);
        check("size x2, turn 30 clockwise = -30 in the look: " + str(f), f != null && near(f[3], 2) && near(f[4], -30));
        // KEEPS ITS PLACE: the pose when chosen is "no movement"; only what changed since moves it.
        String key = WaveformOverlayInstance.visFollowKey("aurora-parts", 1);
        WaveformOverlayInstance k = viz(0);
        k.getVisFollowBase().put(key, VisFollow.capture(at(0.5f, 0.5f, 1f, 0f), 0));
        k.getVisFollowRefs().put(key, at(0.3f, 0.2f, 1f, 0f));
        k.getVisFollowBase().put(key, VisFollow.capture(at(0.3f, 0.2f, 1f, 0f), 0));
        f = VisFollow.at(k, 0, ASPECT);
        check("keeps its place: unmoved = no change, marked 'by': " + str(f),
                f != null && f[0] == 1 && near(f[1], 0) && near(f[2], 0) && near(f[3], 1) && near(f[4], 0) && f[5] == 1f);
        k.getVisFollowRefs().put(key, at(0.3f + 0.125f / ASPECT, 0.2f - 0.125f, 2f, 30f));
        f = VisFollow.at(k, 0, ASPECT);
        check("keeps its place: moved right and up by half the short side, grew x2, turned 30: " + str(f),
                f != null && near(f[1], 1) && near(f[2], 1) && near(f[3], 2) && near(f[4], -30) && f[5] == 1f);
        WaveformOverlayInstance k90 = viz(90);
        k90.getVisFollowBase().put(key, VisFollow.capture(at(0.3f, 0.2f, 1f, 0f), 0));
        k90.getVisFollowRefs().put(key, at(0.3f + 0.125f / ASPECT, 0.2f, 1f, 0f));
        f = VisFollow.at(k90, 0, ASPECT);
        check("keeps its place in a box turned 90: a move right is the look's up: " + str(f),
                f != null && near(f[1], 0) && near(f[2], 1));
        // What it copies (as Studio's link: Position / Size / Rotation).
        k.getVisFollowParts().put(key, "p");
        f = VisFollow.at(k, 0, ASPECT);
        check("keeps its place, position only: moves, does not grow or turn: " + str(f),
                f != null && near(f[1], 1) && near(f[2], 1) && near(f[3], 1) && near(f[4], 0) && f[5] == 1f);
        k.getVisFollowParts().put(key, "sr");
        f = VisFollow.at(k, 0, ASPECT);
        check("keeps its place, size and rotation only: stays put, grows and turns: " + str(f),
                f != null && near(f[1], 0) && near(f[2], 0) && near(f[3], 2) && near(f[4], -30));
        k.getVisFollowParts().put(key, "");
        check("copies nothing = no follow", VisFollow.at(k, 0, ASPECT) == null);
        k.getVisFollowParts().remove(key);
        WaveformOverlayInstance s = viz(0);
        s.getVisFollowParts().put(WaveformOverlayInstance.visFollowKey("aurora-parts", 0), "r");
        f = follow(s, at(0.5f + 0.125f / ASPECT, 0.5f, 2f, 30f), 0);
        check("snaps, rotation only: stays at its own Place ('by' nothing), size x1, turns: " + str(f),
                f != null && near(f[1], 0) && near(f[2], 0) && f[5] == 1f && near(f[3], 1) && near(f[4], -30));
        check("toggling: all minus size = pr; back = all (null)",
                "pr".equals(VisFollow.toggled(null, VisFollow.SIZE))
                        && VisFollow.toggled("pr", VisFollow.SIZE) == null
                        && "".equals(VisFollow.toggled("p", VisFollow.POSITION)));
        k.getVisFollowBase().put(key, "not,a,pose");
        f = VisFollow.at(k, 0, ASPECT);
        check("a broken pose falls back to snapping: " + str(f), f != null && f[5] == 0f);
        // A follow stored for another look does nothing while this one shows.
        WaveformOverlayInstance w = viz(0);
        w.getVisFollowRefs().put(WaveformOverlayInstance.visFollowKey("hypno", 0), at(0.5f, 0.5f, 1f, 0f));
        check("another look's follow is ignored", VisFollow.at(w, 0, ASPECT) == null);
        // No look (classic visualizer) = nothing.
        WaveformOverlayInstance classic = new WaveformOverlayInstance("viz2", "bars");
        classic.getVisFollowRefs().put("aurora-parts/0", at(0.5f, 0.5f, 1f, 0f));
        check("a classic visualizer has no follows", VisFollow.at(classic, 0, ASPECT) == null);

        System.out.println("VisFollowTest: " + (fails == 0 ? "PASS" : "FAILURES: " + fails) + " (passed " + passes + ")");
        if (fails > 0) System.exit(1);
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-3f; }

    static String str(float[] f) {
        if (f == null) return "none";
        StringBuilder sb = new StringBuilder();
        for (float v : f) sb.append(String.format("%.3f ", v));
        return sb.toString().trim();
    }

    static void check(String what, boolean ok) {
        if (ok) passes++;
        else fails++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
