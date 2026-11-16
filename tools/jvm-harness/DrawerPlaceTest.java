import com.fadcam.ui.faditor.tools.DrawerPlace;
import com.fadcam.ui.faditor.tools.DrawerPlace.Place;

/**
 * TAB-1 §K, the owner's 11-15 picks for Studio drawers on a wide window.
 *
 * <p>Where a drawer goes follows the free room (a 16:9 piece on an upright tablet drops down, a
 * 9:16 piece or anything on a tablet sideways stands beside); a phone held upright always keeps
 * the phone drawer; the picture slides away from a panel only as far as its own room allows and
 * never shrinks. Sizes in dp: the test tablet 1480 x 924 sideways, 924 x 1480 upright; the Note 9
 * 548 wide upright at its dense setting.
 *
 * <p>Run: {@code bash tools/jvm-harness/run-drawerplace.sh}
 */
public class DrawerPlaceTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        String A = DrawerPlace.AUTO, D = DrawerPlace.DROP, B = DrawerPlace.BESIDE;
        // A 16:9 picture in the tablet-sideways slot (1480 x ~560): room beside, none below.
        eq("tablet sideways, 16:9: editing desk", Place.DESK, DrawerPlace.choose(A, false, 1480, 484, 0));
        eq("tablet sideways, his column switch on", Place.COLUMN, DrawerPlace.choose(A, true, 1480, 484, 0));
        // 16:9 on an upright tablet (924 x ~900 slot): the room is below, so drop down.
        eq("tablet upright, 16:9: drops down", Place.DROP, DrawerPlace.choose(A, false, 924, 0, 380));
        // 9:16 on an upright tablet: room beside.
        eq("tablet upright, 9:16: beside", Place.DESK, DrawerPlace.choose(A, false, 924, 418, 0));
        eq("Note 9 upright: always the phone drawer", Place.DROP, DrawerPlace.choose(B, true, 548, 400, 0));
        eq("half a tablet, automatic: phone drawer (under 840)", Place.DROP, DrawerPlace.choose(A, false, 736, 300, 0));
        eq("half a tablet, Always beside: beside", Place.DESK, DrawerPlace.choose(B, false, 736, 300, 0));
        eq("Always drop down wins on a tablet", Place.DROP, DrawerPlace.choose(D, false, 1480, 484, 0));
        truth("switch offered on a tablet", DrawerPlace.switchable(1480));
        truth("no switch on the Note 9 upright", !DrawerPlace.switchable(548));

        // Slide aside: a 996-wide picture centred in a 1480 slot (left 242), 360 panel on the right.
        float s = DrawerPlace.panelShift(1, 1480, 360, 242, 1238);
        eq("right panel: slides left only as far as the panel covers", -118f, s);
        // A 1300-wide picture (left 90): covered 180, room 90 -> slides 90, the panel overlaps the rest.
        eq("right panel, little room: slides to the wall, then overlaps", -90f,
                DrawerPlace.panelShift(1, 1480, 360, 90, 1390));
        eq("left panel: slides right", 118f, DrawerPlace.panelShift(-1, 1480, 360, 242, 1238));
        eq("picture already clear: no slide", 0f, DrawerPlace.panelShift(1, 1480, 360, 100, 1000));
        eq("no panel: no slide", 0f, DrawerPlace.panelShift(0, 1480, 360, 242, 1238));

        // K5 left and right: one side only matches panelShift; both sides centre the picture in the band.
        eq("sides: right only = panelShift", DrawerPlace.panelShift(1, 1480, 360, 242, 1238),
                DrawerPlace.sidesShift(1480, 0, 360, 242, 1238));
        eq("sides: left only = panelShift", DrawerPlace.panelShift(-1, 1480, 360, 242, 1238),
                DrawerPlace.sidesShift(1480, 360, 0, 242, 1238));
        eq("sides: both 360 on a centred 996 picture: already centred, no slide", 0f,
                DrawerPlace.sidesShift(1480, 360, 360, 242, 1238));
        eq("sides: both, picture off-centre: centred in the band", 50f,
                DrawerPlace.sidesShift(1480, 360, 260, 242, 1238));
        eq("sides: never past its own room", 242f * -1,
                DrawerPlace.sidesShift(1480, 100, 900, 242, 1238));
        eq("sides: picture clear of both: no slide", 0f,
                DrawerPlace.sidesShift(1480, 200, 200, 300, 1100));

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void eq(String what, Object want, Object got) {
        if (want.equals(got)) { passed++; return; }
        failed++;
        System.out.println("FAIL " + what + ": want " + want + ", got " + got);
    }

    private static void truth(String what, boolean ok) { eq(what, true, ok); }
}
