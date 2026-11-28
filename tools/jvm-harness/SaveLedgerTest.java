import cc.joycreator.avatar.file.SaveLedger;

import java.util.ArrayList;
import java.util.List;

/** The audit's R2 and R4: where a character was saved belongs to that character, and two saves of a new one make one file. */
public class SaveLedgerTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    /** A folder: a path of null makes a new file. */
    static final class Folder implements SaveLedger.Saver<String> {
        final List<String> files = new ArrayList<>();

        public String save(String doc, String path) {
            if (path == null) {
                String made = doc + (files.isEmpty() ? "" : " " + (files.size() + 1)) + ".character";
                files.add(made);
                return made;
            }
            return path;
        }
    }

    public static void main(String[] a) {
        Folder f = new Folder();
        SaveLedger<String> led = new SaveLedger<>();
        String bring = new String("New character");               // a distinct object, as a character is
        // R4: two saves of a never-saved character queued before the first finished
        String p1 = led.run(bring, null, f);
        String p2 = led.run(bring, null, f);
        ok(f.files.size() == 1 && p1.equals(p2), "two saves of a new character make one file (" + f.files + ")");
        // R2: A's save names A's file; Topps (another character, no place) must not take it
        String topps = new String("Topps");
        ok(led.pathOf(topps) == null, "another character has no path because of A's save");
        led.adopt(topps, null);
        String pt = led.run(topps, null, f);
        ok(f.files.size() == 2 && !pt.equals(p1), "Topps is saved to its own file, not over A's");
        ok(p1.equals(led.pathOf(bring)), "A is still where it was");
        // a character opened from the folder keeps its place
        String opened = new String("Pip");
        led.adopt(opened, "Pip.character");
        ok("Pip.character".equals(led.run(opened, null, f)) && f.files.size() == 2, "an opened character is saved over its own file");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
