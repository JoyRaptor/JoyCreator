import com.fadcam.ui.faditor.model.LinkPose;
import com.fadcam.ui.faditor.model.SpaceLink;
import com.fadcam.ui.faditor.model.VisLabKeys;
import com.fadcam.vislab.model.PlaceFollow;
import com.google.gson.JsonObject;

/**
 * R8: a look's layer follows a Studio object. The link survives save / load / copy, a default
 * VisLabKeys stays default, and a proxy's motion since the link turns into the layer's offset the
 * way WaveformOverlayInstance.layerFollowAt computes it.
 */
public class VisFollowLinkTest {

    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("  FAIL " + what); }
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-3f; }

    /** A dummy whose pose is a function of time: moves right 0.2 per second, grows, turns. */
    static final class Dummy implements LinkPose {
        public String getId() { return "dummy1"; }
        public float animatedCenterX(long t) { return 0.3f + 0.2f * t / 1000f; }
        public float animatedCenterY(long t) { return 0.5f; }
        public float animatedSizeFraction(long t) { return 0.2f * (1f + t / 1000f); }
        public float animatedRotation(long t) { return 90f * t / 1000f; }
        public float animatedOpacity(long t) { return 1f; }
    }

    public static void main(String[] args) {
        Dummy d = new Dummy();
        VisLabKeys k = new VisLabKeys();
        check(k.isDefault(), "a fresh VisLabKeys is default");

        // Link layer 2 to the dummy as it stands at 0 ms, in a 16:9 frame.
        SpaceLink link = SpaceLink.capture(d, 0, 16f / 9f);
        link.parentRef = d;
        k.putFollow(2, link);
        check(!k.isDefault(), "a follow makes it non-default (it must be saved)");

        // Save / load / copy.
        JsonObject j = k.toJson();
        check(j.has("follow") && j.getAsJsonObject("follow").has("2"), "saved under follow.2: " + j);
        VisLabKeys back = new VisLabKeys();
        back.readJson(j);
        check(back.followOf(2) != null && "dummy1".equals(back.followOf(2).parentId), "loaded with its parent id");
        check(back.followOf(2) != null && near(back.followOf(2).baseX, 0.3f), "loaded with its base pose");
        check(k.equals(back), "round trip is equal");
        VisLabKeys cp = k.copy();
        check(cp.followOf(2) != null && cp.followOf(2) != link, "a copy has its own link object");
        cp.removeFollow(2);
        check(k.followOf(2) != null, "removing from the copy leaves the original");
        JsonObject bad = new JsonObject();
        JsonObject f = new JsonObject();
        f.addProperty("x", "nonsense");
        f.add("3", new JsonObject());
        bad.add("follow", f);
        VisLabKeys survivor = new VisLabKeys();
        survivor.readJson(bad);
        check(survivor.follows().isEmpty(), "malformed follow entries are dropped, not thrown");

        // The dummy's motion since the link is the layer's offset: nothing at 0, then it moves.
        float cx = 0.5f, cy = 0.5f;           // the visualizer's centre, frame fractions
        float[] w = new float[2], u = new float[2];
        link.toWorld(cx, cy, 0, w);
        check(near(w[0], cx) && near(w[1], cy), "linking moves nothing at the moment of linking");
        check(near(link.sizeToWorld(1f, 0), 1f) && near(link.rotToWorld(0f, 0), 0f), "no size or turn at 0 either");

        check(near(link.sizeToWorld(1f, 1000), 2f), "the dummy doubled, so the layer ratio is 2: " + link.sizeToWorld(1f, 1000));
        check(near(link.rotToWorld(0f, 1000), 90f), "the dummy turned 90 degrees: " + link.rotToWorld(0f, 1000));
        // Switched to position only (the Follow row's size / turn switches off), it is carried along.
        link.scale = false;
        link.rotation = false;
        link.toWorld(cx, cy, 1000, w);
        check(near(w[0], cx + 0.2f) && near(w[1], cy), "position only: carried right by what the dummy moved: " + w[0]);
        check(near(link.sizeToWorld(1f, 1000), 1f) && near(link.rotToWorld(0f, 1000), 0f), "size and turn switched off: none");

        // A visualizer box as wide as the frame: a move of dx frame fractions is the same box fraction.
        PlaceFollow.toLook(w[0] - cx, w[1] - cy, 16f / 9f, u);
        check(u[0] > 0f, "moving right is a positive Move across: " + u[0]);

        if (fails == 0) System.out.println("VisFollowLinkTest OK");
        else { System.out.println("VisFollowLinkTest FAILED: " + fails); System.exit(1); }
    }
}
