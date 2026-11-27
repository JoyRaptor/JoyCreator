import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.file.FormatCheck;
import cc.joycreator.avatar.file.PinGuess;
import cc.joycreator.avatar.solve.Pics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Pins guessed from the pixels, against the numbers the pilot's own guessFan gives for Topps' two arms (run in the pilot,
 * 11-27). Run: bash tools/jvm-harness/run-avatar-stances.sh
 */
public class PinGuessTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static final class PngPics implements Pics {
        final Map<String, int[]> size = new HashMap<>();
        final Map<String, byte[]> alpha = new HashMap<>();

        PngPics(JSONObject ch) throws Exception {
            JSONObject pics = ch.getJSONObject("pictures");
            for (java.util.Iterator<String> it = pics.keys(); it.hasNext(); ) {
                String k = it.next(), u = pics.getString(k);
                BufferedImage im = javax.imageio.ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(u.substring(u.indexOf(',') + 1))));
                if (im == null) continue;
                int w = im.getWidth(), h = im.getHeight();
                byte[] al = new byte[w * h];
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) al[y * w + x] = (byte) ((im.getRGB(x, y) >>> 24) & 0xff);
                size.put(k, new int[]{w, h});
                alpha.put(k, al);
            }
        }

        public boolean has(String img) {
            return size.containsKey(img);
        }

        public int w(String img) {
            return size.get(img)[0];
        }

        public int h(String img) {
            return size.get(img)[1];
        }

        public int ver(String img) {
            return 1;
        }

        public byte[] alpha(String img) {
            return alpha.get(img);
        }
    }

    static JSONObject find(JSONObject n, String key, String val) {
        if (val.equals(n.optString(key))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject f = find(k.optJSONObject(i), key, val);
            if (f != null) return f;
        }
        return null;
    }

    static boolean near(JSONObject l, String p, double x, double y) throws Exception {
        return Math.abs(l.getJSONObject(p).getDouble("x") - x) < 0.01 && Math.abs(l.getJSONObject(p).getDouble("y") - y) < 0.01;
    }

    public static void main(String[] a) throws Exception {
        JSONObject doc = CharacterFile.upgrade(new JSONObject(new String(Files.readAllBytes(new File("app/src/main/assets/avatar/topps.character.json").toPath()), StandardCharsets.UTF_8))).doc;
        Pics pics = new PngPics(doc);
        double[] tc = {257, 327};                      // the torso's middle, as the pilot passes it
        for (String role : new String[]{"armL", "armR"}) {
            JSONObject set = find(doc.getJSONObject("root"), "role", role);
            JSONArray kids = set.getJSONArray("kids");
            for (int i = 0; i < kids.length(); i++) kids.getJSONObject(i).remove("limb");
            PinGuess.guessFan(set, pics, tc, false);
        }
        JSONObject armL = find(doc.getJSONObject("root"), "role", "armL"), armR = find(doc.getJSONObject("root"), "role", "armR");
        JSONObject k1 = find(armL, "id", "n3gtsf").getJSONObject("limb");
        ok(near(k1, "a", 289.14717626925267, 290.3046206503137) && near(k1, "b", 264.3428189038571, 279.6167515443151) && near(k1, "c", 239.53846153846155, 268.9288824383164), "the left arm's first drawing gets the pilot's three pins");
        JSONObject k2 = find(armL, "id", "n3issz").getJSONObject("limb");
        ok(near(k2, "b", 230.17902813299233, 266.0230179028133) && near(k2, "c", 171.41922290388547, 233.78663940013632), "and another drawing of it");
        JSONObject k3 = find(armL, "id", "n3nfn7").getJSONObject("limb");
        ok(near(k3, "c", 260.6115241635688, 406.8822800495663) && "n3nfn7".equals(armL.getJSONObject("pick").getString("rest")), "the resting drawing is the one that hangs lowest, as in the pilot");
        JSONObject r1 = find(armR, "id", "n3pymn").getJSONObject("limb");
        ok(near(r1, "a", 194.01874062968517, 284.2271364317841) && near(r1, "c", 215.1391304347826, 248.93623188405797), "the right arm's shoulder is where its drawings overlap");
        JSONObject r3 = find(armR, "id", "n7jqmx").getJSONObject("limb");
        ok(near(r3, "b", 187.66141732283464, 218.78477690288713) && near(r3, "c", 176.92504461629983, 151.02795954788817), "and the last drawing too");
        ok("n3thz2".equals(armR.getJSONObject("pick").getString("rest")), "the right arm rests on its lowest drawing");
        ok(find(armL, "id", "n3gtsf").getJSONObject("limb").getBoolean("guessed"), "a guess says it is a guess");
        ok(FormatCheck.check(doc).isEmpty(), "the character still follows the rules");

        // a part that swings: a root, a tip, and the pilot's settings for a bendy thing
        JSONObject tail = find(doc.getJSONObject("root"), "role", "tail");
        PinGuess.guessSwing(tail, pics, tc, "bendy");
        JSONObject sw = tail.getJSONObject("swing");
        ok("bendy".equals(sw.getString("kind")) && sw.getDouble("damp") == 0.3 && sw.getInt("segs") == 4 && tail.has("pivot") && sw.has("tip"), "a tail gets a bendy swing with a root and a tip");
        ok("stiff".equals(PinGuess.swingKindOf("Gold chain link")) && "bendy".equals(PinGuess.swingKindOf("long hair")) && PinGuess.swingKindOf("Torso") == null, "names say what swings: chains are stiff, hair is bendy");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
