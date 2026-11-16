import cc.joycreator.handoff.HandoffTestDoor;

/**
 * FX-1 (Joybot carries your work between rooms): the show's running order and Joybot's path, off
 * device. Every move is run frame by frame on several screens, with and without the question, with
 * the next room reporting early, late and very late, and with a skip.
 *
 * Usage: bash tools/jvm-harness/run-handoff.sh
 */
public final class HandoffMovesTest {
    public static void main(String[] a) {
        int fails = HandoffTestDoor.runAll();
        if (fails > 0) { System.out.println("HANDOFF FAIL: " + fails + " check(s) failed"); System.exit(1); }
        System.out.println("HANDOFF PASS");
    }
}
