import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.intake.WholeDrawing;
import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Signals;
import cc.joycreator.avatar.solve.SolveOut;

import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** The whole-drawing door: the character it makes is a valid file, the pins land where they were tapped, and it draws. */
public class WholeDrawingTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] a) throws Exception {
        BufferedImage im = new BufferedImage(60, 120, BufferedImage.TYPE_INT_ARGB);
        for (int y = 10; y < 110; y++) for (int x = 10; x < 50; x++) im.setRGB(x, y, 0xFF3366CC);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(im, "png", bo);
        String url = "data:image/png;base64," + Base64.getEncoder().encodeToString(bo.toByteArray());
        JSONObject doc = WholeDrawing.build("Mine", 60, 120, url);
        List<WholeDrawing.Pin> pins = new ArrayList<>();
        pins.add(new WholeDrawing.Pin("head", "Head", 30, 20));
        pins.add(new WholeDrawing.Pin("hips", "Hips", 30, 70));
        pins.add(new WholeDrawing.Pin("footL", "Left foot", 20, 110));
        WholeDrawing.pin(doc, pins);

        CharacterFile.Upgraded up = CharacterFile.upgrade(new JSONObject(doc.toString()));
        ok(up != null && up.doc != null, "the file the door makes opens");
        JSONObject art = WholeDrawing.art(doc);
        ok("torso".equals(art.optString("role")) && art.optString("img").length() > 0, "the drawing is the body");
        ok(doc.optInt("w") == 102 && doc.optInt("h") == 162, "the stage is made roomier than the drawing (" + doc.optInt("w") + "x" + doc.optInt("h") + ")");
        JSONArray ps = art.getJSONObject("mesh").getJSONArray("pins");
        ok(ps.length() == 3 && "head".equals(ps.getJSONObject(0).optString("role")), "three pins, by role");
        ok(Math.abs(ps.getJSONObject(0).optDouble("x") - (art.optDouble("x") + 30)) < 1e-9 && Math.abs(ps.getJSONObject(2).optDouble("y") - (art.optDouble("y") + 110)) < 1e-9, "the pins land where they were tapped, in the character's space");
        ok(!doc.getJSONObject("settings").optBoolean("idle", true) && !doc.getJSONObject("settings").optBoolean("breathe", true), "it starts still (no idle, no breathing)");
        CharacterSolver sv = new CharacterSolver(up.doc, new WalkerTest.PngPics(up.doc));
        SolveOut out = sv.eval(new Signals(), new CharacterSolver.Opt());
        ok(out.items.size() == 1, "it draws as one picture (" + out.items.size() + ")");
        ok(WholeDrawing.STEPS.length == 7 && "head".equals(WholeDrawing.STEPS[0][0]) && "tail".equals(WholeDrawing.STEPS[6][0]), "the wizard asks for head, hands, hips, feet and tail in the pilot's order");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
