import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.audio.Signals;
import com.fadcam.vislab.model.CompiledScene;
import com.fadcam.vislab.model.PlaceFollow;
import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.model.VisTypes;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * R8: a Studio proxy can move a look's layer. The conversion from frame fractions to Place units,
 * and a layer that follows on top of its own Place without touching the look.
 */
public class VisPlaceFollowTest {

    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            fails++;
            System.out.println("  FAIL " + what);
        }
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-4f; }

    public static void main(String[] args) throws Exception {
        float[] o = new float[2];

        // A landscape 16:9 frame: one scene unit is half the HEIGHT, so the whole width is 2 x 16/9.
        PlaceFollow.toLook(1f, 0f, 16f / 9f, o);
        check(near(o[0], 2f * 16f / 9f) && near(o[1], 0f), "all the way across a 16:9 frame = " + o[0]);
        PlaceFollow.toLook(0f, 1f, 16f / 9f, o);
        check(near(o[0], 0f) && near(o[1], -2f), "all the way DOWN a 16:9 frame = y -2 (look y is up): " + o[1]);

        // A portrait 9:16 frame: one scene unit is half the WIDTH, so the whole height is 2 x 16/9.
        PlaceFollow.toLook(1f, 0f, 9f / 16f, o);
        check(near(o[0], 2f), "all the way across a 9:16 frame = 2: " + o[0]);
        PlaceFollow.toLook(0f, -0.5f, 9f / 16f, o);
        check(near(o[1], 16f / 9f), "half way UP a 9:16 frame = 16/9: " + o[1]);

        // A square frame is the plain case, and a nonsense aspect does not blow up.
        PlaceFollow.toLook(0.25f, 0.25f, 1f, o);
        check(near(o[0], 0.5f) && near(o[1], -0.5f), "square frame, quarter right and down");
        PlaceFollow.toLook(0.25f, 0.25f, 0f, o);
        check(near(o[0], 0.5f) && near(o[1], -0.5f), "aspect 0 reads as square");

        // A layer follows on top of its own Place; the look's own values are not touched.
        VisTypes types = VisTypes.parse(new String(Files.readAllBytes(
                Paths.get("app/src/main/assets/vislab/types.json")), StandardCharsets.UTF_8));
        JsonObject lay = JsonParser.parseString("{\"id\":\"f\",\"layers\":["
                + "{\"type\":\"spectrum\",\"x\":0.5,\"y\":-0.25,\"size\":2,\"turn\":30},"
                + "{\"type\":\"spectrum\"}]}").getAsJsonObject();
        CompiledScene cs = new CompiledScene(new VisScene(lay), types, null);
        Signals sig = new Signals();
        sig.set(new Features(), 1.0);
        for (CompiledScene.Layer L : cs.layers) L.eval(sig);
        CompiledScene.Layer a = cs.layers.get(0), b = cs.layers.get(1);
        check(near(a.x(), 0.5f) && near(a.y(), -0.25f) && near(a.size(), 2f) && near(a.turn(), 30f),
                "before following, Place reads as drawn");

        a.follow(0.1f, 0.2f, 1.5f, 15f);
        check(near(a.x(), 0.6f) && near(a.y(), -0.05f), "follow adds to Move across / up");
        check(near(a.size(), 3f), "follow multiplies Size (2 x 1.5): " + a.size());
        check(near(a.turn(), 45f), "follow adds to Turn (30 + 15): " + a.turn());
        check(near(b.x(), 0f) && near(b.size(), 1f) && near(b.turn(), 0f), "a layer that does not follow stays put");

        a.eval(sig);
        check(near(a.x(), 0.6f) && near(a.turn(), 45f), "follow survives a re-evaluation of the knobs");

        a.follow(0f, 0f, 1f, 0f);
        check(near(a.x(), 0.5f) && near(a.size(), 2f) && near(a.turn(), 30f), "follow (0, 0, 1, 0) puts it back exactly");
        a.follow(0f, 0f, -3f, 0f);
        check(a.size() >= 0.05f, "a follow ratio that would flip or vanish the layer is held above zero: " + a.size());

        if (fails == 0) System.out.println("VisPlaceFollowTest OK");
        else {
            System.out.println("VisPlaceFollowTest FAILED: " + fails);
            System.exit(1);
        }
    }
}
