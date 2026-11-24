import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.ui.RoomEdit;

import org.json.JSONException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The room's one way to change a character: a named step, one press of Undo each, redo cleared by a new step, a refused
 * step changes nothing. Run: bash tools/jvm-harness/run-avatar-edit.sh
 */
public class RoomEditTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static String firstArt(CharacterDoc d) {
        final String[] id = {null};
        d.walk((n, depth, p) -> {
            if (id[0] == null && "art".equals(n.type)) id[0] = n.id;
        });
        return id[0];
    }

    static boolean hidden(RoomEdit e, String id) {
        return e.doc().find(id).hidden;
    }

    public static void main(String[] a) throws Exception {
        String text = new String(Files.readAllBytes(new File("tasks/avatar/format-tests/v2-crest-hidden.character.json").toPath()), StandardCharsets.UTF_8);
        CharacterDoc d = CharacterDoc.parse(text);
        final int[] heard = {0};
        RoomEdit e = new RoomEdit(d, doc -> heard[0]++);
        e.reset(d);
        String x = firstArt(e.doc());
        final String[] ids = new String[2];
        e.doc().walk((n, depth, p) -> {
            if ("art".equals(n.type)) {
                if (ids[0] == null) ids[0] = n.id;
                else if (ids[1] == null) ids[1] = n.id;
            }
        });
        ok(e.undoName() == null && e.redoName() == null, "a fresh character has nothing to undo or redo");
        ok(e.apply("Hide A", doc -> doc.find(ids[0]).json.put("hidden", true)), "a step is accepted");
        ok(hidden(e, ids[0]), "the step changed the character");
        ok("Hide A".equals(e.undoName()), "Undo is named for the step");
        ok(e.apply("Hide B", doc -> doc.find(ids[1]).json.put("hidden", true)), "a second step is accepted");
        ok(e.undo(), "undo works");
        ok(hidden(e, ids[0]) && !hidden(e, ids[1]), "one press of Undo takes back exactly one step");
        ok("Hide B".equals(e.redoName()), "Redo is named for the step taken back");
        ok(e.redo() && hidden(e, ids[1]), "redo puts the step back");
        e.undo();
        ok(e.apply("Show A", doc -> doc.find(ids[0]).json.remove("hidden")), "a new step after an undo is accepted");
        ok(e.redoName() == null, "a new step clears what could be redone");
        int before = heard[0];
        String undoBefore = e.undoName();
        boolean refused = e.apply("Break", doc -> { throw new JSONException("no"); });
        ok(!refused, "a step that fails is refused");
        ok(e.undoName().equals(undoBefore) && heard[0] == before, "a refused step adds no undo step and tells nobody");
        ok(!hidden(e, ids[0]), "a refused step leaves the character as it was");
        e.undo();
        e.undo();
        ok(!e.undo(), "undo with nothing left says no");
        ok(!hidden(e, ids[0]) && !hidden(e, ids[1]), "all the way back is the file as opened");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
