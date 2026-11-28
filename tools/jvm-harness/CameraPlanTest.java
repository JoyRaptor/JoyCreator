import com.fadcam.ui.faditor.avatar.face.CameraPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cameras per tracker (the owner, 11-27): which cameras a phone offers in plain words, and what opens for the face and the
 * body. Phones described as their camera systems list them. Run: bash tools/jvm-harness/run-avatar-face.sh
 */
public class CameraPlanTest {
    static int fails;

    static void check(boolean c, String what) {
        System.out.println((c ? "PASS  " : "FAIL  ") + what);
        if (!c) fails++;
    }

    static String keys(List<CameraPlan.Choice> cs) {
        StringBuilder b = new StringBuilder();
        for (CameraPlan.Choice c : cs) b.append(b.length() == 0 ? "" : ", ").append(c.key);
        return b.toString();
    }

    static CameraPlan.Lens front(String id) { return new CameraPlan.Lens(id, true, false, 2.9f, 1f); }

    static CameraPlan.Lens back(String id, float focal, float minZoom) { return new CameraPlan.Lens(id, false, true, focal, minZoom); }

    static List<Set<String>> pairs(String... ab) {
        List<Set<String>> out = new ArrayList<>();
        for (int i = 0; i + 1 < ab.length; i += 2) out.add(new HashSet<>(Arrays.asList(ab[i], ab[i + 1])));
        return out;
    }

    public static void main(String[] a) {
        // the cameras each phone offers
        List<CameraPlan.Choice> note9 = CameraPlan.choices(Arrays.asList(back("0", 4.3f, 1f), front("1")));
        check("front, back".equals(keys(note9)), "a phone with one back camera (the Note 9): Front and Back, no Back wide (" + keys(note9) + ")");
        List<CameraPlan.Choice> zoomOut = CameraPlan.choices(Arrays.asList(back("0", 5.4f, 0.5f), front("1")));
        check("front, back, back-wide".equals(keys(zoomOut)) && zoomOut.get(2).lens.id.equals("0") && zoomOut.get(2).zoom == 0.5f,
                "its back camera zooms out to 0.5 (an ultra-wide behind one camera): Back wide is that camera at 0.5");
        List<CameraPlan.Choice> ultra = CameraPlan.choices(Arrays.asList(back("0", 5.4f, 1f), front("1"), back("2", 2.2f, 1f), back("3", 9f, 1f)));
        check("front, back, back-wide".equals(keys(ultra)) && ultra.get(2).lens.id.equals("2") && ultra.get(1).lens.id.equals("0"),
                "an ultra-wide listed as its own camera: Back wide opens it; Back stays the main one; the long lens is not offered");
        List<CameraPlan.Choice> tablet = CameraPlan.choices(Arrays.asList(front("1")));
        check("front".equals(keys(tablet)), "a front camera only: only Front is offered (" + keys(tablet) + ")");
        List<CameraPlan.Choice> emu = CameraPlan.choices(Arrays.asList(back("0", 0f, 1f), front("1")));
        check("front, back".equals(keys(emu)), "focal lengths unknown (the emulator): no wide is guessed (" + keys(emu) + ")");

        // what opens
        CameraPlan.Plan p = CameraPlan.plan(null, null, true, note9, CameraPlan.none());
        check(p.face.key.equals("front") && p.body == p.face && !p.two && !p.refused, "nothing chosen yet: Front feeds the face and the body (one performer)");
        p = CameraPlan.plan("back", null, false, note9, CameraPlan.none());
        check(p.face.key.equals("back") && p.body == null && !p.two, "the face on Back, Shoulders off: only the back camera, no body");
        p = CameraPlan.plan("back", null, true, note9, CameraPlan.none());
        check(p.face.key.equals("back") && p.body == p.face && !p.refused, "the face moved to Back, the body not chosen: the body follows it (one camera, nothing refused)");
        p = CameraPlan.plan("front", "back", true, note9, pairs("0", "1"));
        check(p.two && p.face.key.equals("front") && p.body.key.equals("back") && !p.refused, "face Front, body Back on a phone that opens both: two cameras at once");
        p = CameraPlan.plan("front", "back", true, note9, CameraPlan.none());
        check(!p.two && p.refused && p.body == p.face && p.face.key.equals("front"),
                "the same on a phone that cannot open both: the face's camera feeds both, and it says so");
        p = CameraPlan.plan("front", "back-wide", true, ultra, pairs("0", "1"));
        check(p.refused && !p.two, "the pair it can open is Front and the main Back, not the wide one: refused, not guessed");
        p = CameraPlan.plan("front", "back-wide", true, ultra, pairs("1", "2"));
        check(p.two && p.body.lens.id.equals("2"), "...and where Front and the wide one open together: two cameras");
        p = CameraPlan.plan("back", "back-wide", true, zoomOut, CameraPlan.none());
        check(!p.two && !p.refused && p.sharedZoom == 0.5f, "Back and Back wide on one camera: one camera at its wide end feeds both (" + p.sharedZoom + ")");
        p = CameraPlan.plan("back-wide", "front", true, note9, CameraPlan.none());
        check(p.face.key.equals("front") && !p.refused, "a file saved on a phone with Back wide, opened on the Note 9: the face falls back to Front");
        p = CameraPlan.plan("front", "back-wide", true, note9, CameraPlan.none());
        check(p.body == p.face && !p.refused, "...and a body camera this phone lacks follows the face's, nothing to refuse");
        p = CameraPlan.plan("back", "back", true, tablet, CameraPlan.none());
        check(p.face.key.equals("front") && p.body == p.face, "a front-only tablet: Front for both whatever was saved");
        p = CameraPlan.plan("front", null, true, new ArrayList<>(), CameraPlan.none());
        check(p.face == null && p.body == null, "no camera at all: nothing to open");

        System.out.println(fails == 0 ? "PASSED" : "FAILED " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
