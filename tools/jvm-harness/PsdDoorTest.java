import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.file.FormatCheck;
import cc.joycreator.avatar.intake.PsdDoor;
import cc.joycreator.avatar.intake.PsdReader;
import cc.joycreator.avatar.intake.RoleGuess;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The layered-file door, first half: a Photoshop file's layers become a character that follows the format's rules,
 * with roles guessed from names. Uses the hand-written files of PsdReaderTest. Run: bash tools/jvm-harness/run-avatar-psd.sh
 */
public class PsdDoorTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static String roleOfName(CharacterDoc d, String name) {
        final String[] r = {null};
        d.walk((n, depth, p) -> {
            if (name.equals(n.name)) r[0] = n.role;
        });
        return r[0];
    }

    public static void main(String[] a) throws Exception {
        // a character: Head (Left Eye, Nose, Mouth), Right Arm, a hidden Backdrop
        List<PsdReaderTest.Lay> order = new ArrayList<>();
        PsdReaderTest.Lay back = PsdReaderTest.layer("Backdrop", 0, 0, 4, 4, 1, 2, 3, 255, 0);
        back.flags = 2;
        order.add(back);
        order.add(PsdReaderTest.layer("Right Arm", 0, 4, 2, 2, 9, 9, 9, 255, 0));
        order.add(PsdReaderTest.marker("</Layer group>", 3));
        order.add(PsdReaderTest.layer("Mouth", 5, 5, 2, 1, 8, 8, 8, 255, 0));
        order.add(PsdReaderTest.layer("Nose", 4, 4, 1, 1, 7, 7, 7, 255, 0));
        order.add(PsdReaderTest.layer("Left Eye", 2, 2, 2, 2, 6, 6, 6, 255, 0));
        order.add(PsdReaderTest.marker("Head", 1));
        PsdReader psd = PsdReader.parse(PsdReaderTest.psd(order));
        PsdDoor.PngEncoder enc = (w, h, rgba) -> "data:image/png;base64,AAAA";
        PsdDoor.Summary sum = new PsdDoor.Summary();
        JSONObject made = PsdDoor.build(psd, "Test", enc, sum);

        List<FormatCheck.Problem> bad = FormatCheck.check(made);
        ok(bad.isEmpty(), "the new character follows the format's rules: " + bad);
        ok(sum.layers == 5 && sum.groups == 1, "the summary counts 5 layers and 1 group (" + sum.layers + "/" + sum.groups + ")");
        CharacterDoc d = CharacterDoc.parse(made.toString());
        ok(d.w == 8 && d.h == 8 && "Test".equals(d.name), "the canvas size and the name carry over");
        ok("head".equals(roleOfName(d, "Head")), "Head is the head");
        ok("eyeL".equals(roleOfName(d, "Left Eye")), "Left Eye is the left eye");
        ok("nose".equals(roleOfName(d, "Nose")) && "mouth".equals(roleOfName(d, "Mouth")), "Nose and Mouth are named by their names");
        ok("armR".equals(roleOfName(d, "Right Arm")), "Right Arm is the right arm");
        ok(roleOfName(d, "Backdrop") == null || roleOfName(d, "Backdrop").isEmpty(), "a layer with no telling name has no role");
        final boolean[] hid = {false};
        d.walk((n, depth, p) -> {
            if ("Backdrop".equals(n.name)) hid[0] = n.hidden;
        });
        ok(hid[0], "a layer hidden in Photoshop is hidden here");
        ok(FormatCheck.check(CharacterFile.upgrade(made).doc).isEmpty(), "it still follows the rules after upgrade");

        // names
        ok("armL".equals(RoleGuess.roleOf("LeftArm2", null, null)), "a run-together name is split: LeftArm2 is the left arm");
        ok("browR".equals(RoleGuess.roleOf("Eyebrow R", null, null)), "Eyebrow R is the right eyebrow");
        ok("lidL".equals(RoleGuess.roleOf("Lower Lid", null, null)) && "lidU".equals(RoleGuess.roleOf("Eyelid", null, null)), "lids: lower and upper");
        ok(RoleGuess.roleOf("Smile", "mouth", null) == null, "inside a mouth, nothing else is a part");
        ok("hair".equals(RoleGuess.roleOf("Front frill", null, null)), "a frill is hair");
        ok("Layer".equals(RoleGuess.clean("  +  ")) && "Left arm".equals(RoleGuess.clean("+ Left   arm")), "names are tidied");

        // a file with no pictures says so in one plain sentence
        List<PsdReaderTest.Lay> empty = new ArrayList<>();
        empty.add(PsdReaderTest.marker("</Layer group>", 3));
        empty.add(PsdReaderTest.marker("Nothing", 1));
        try {
            PsdDoor.build(PsdReader.parse(PsdReaderTest.psd(empty)), "x", enc, null);
            ok(false, "a file with no layers is refused");
        } catch (IOException e) {
            ok(e.getMessage().contains("no layers"), "a flattened file says so in one sentence");
        }
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
