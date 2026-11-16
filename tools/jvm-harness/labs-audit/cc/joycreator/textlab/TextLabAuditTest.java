package cc.joycreator.textlab;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Codex audit 11-15, TextLab: typing in the room's undo (LAB-06), the unsaved-work draft and its undo steps
 * (KNOWN-LAB-01) and the export's stop on its last step (LAB-04), with the REAL TlUndo, TlDraft and StopStream.
 * Run by tools/jvm-harness/run-labs-audit.sh.
 */
public final class TextLabAuditTest {

    private static int pass, fail;

    public static void main(String[] a) throws Exception {
        typingIsOneStep();
        typingForgetsRedo();
        aDraftKeepsEverything();
        badDraftsAreRefused();
        stopEndsTheCopy();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    // ---- LAB-06 -----------------------------------------------------------------------------------

    static void typingIsOneStep() {
        TlUndo u = new TlUndo();
        String look = "{\"id\":\"dust\"}";
        u.typed(look, "TEXT", "Words");    // T -> TEXTH (first letter)
        u.typed(look, "TEXTH", "Words");
        u.typed(look, "TEXTHI", "Words");
        check("typing is an undo step", u.canUndo());
        TlUndo.Step s = u.undo(look, "TEXTHIS");
        check("one press takes back the whole run", s != null && "TEXT".equals(s.words) && !u.canUndo());
        TlUndo.Step r = u.redo(look, "TEXT");
        check("and redo brings the run back", r != null && "TEXTHIS".equals(r.words));

        TlUndo v = new TlUndo();
        v.typed(look, "A", "Words");
        v.endTyping();                      // left the box
        v.typed(look, "AB", "Words");
        check("leaving the box ends a run", v.undo(look, "ABC") != null && v.canUndo());
    }

    static void typingForgetsRedo() {
        TlUndo u = new TlUndo();
        u.before("{\"k\":1}", "HOT", "Shine");   // a slider moved
        TlUndo.Step s = u.undo("{\"k\":2}", "HOT");
        check("undo of the slider", s != null && u.canRedo());
        u.typed("{\"k\":1}", "HOT", "Words");    // then the person types
        check("typing forgets the old redo (it would put the old words back)", !u.canRedo());
        TlUndo.Step t = u.undo("{\"k\":1}", "HOTTER");
        check("and the typing is what Undo takes back", t != null && "HOT".equals(t.words));
    }

    // ---- KNOWN-LAB-01 -----------------------------------------------------------------------------

    static void aDraftKeepsEverything() throws Exception {
        TlUndo u = new TlUndo();
        u.before("{\"s\":1}", "ONE", "Shine");
        u.before("{\"s\":2}", "ONE", "Colour");
        u.undo("{\"s\":3}", "ONE");               // one step ahead, two back... now one back, one ahead
        TlDraft d = new TlDraft("{\"s\":2,\"name\":\"Dust\"}", "ONE MORE", "{\"s\":1}", "ONE",
                "folder:Supplies/Looks/Dust.text.json", "Dust", u.save());
        File f = new File(Files.createTempDirectory("tldraft").toFile(), "textlab/draft.json");
        TlDraft.write(f, d.toJson());
        TlDraft back = TlDraft.read(f);
        check("the draft reads back", back != null);
        if (back == null) return;
        check("its look, words and saved state", back.look.equals(d.look) && back.words.equals("ONE MORE")
                && back.savedLook.equals("{\"s\":1}") && back.savedWords.equals("ONE"));
        check("the look it was editing", "folder:Supplies/Looks/Dust.text.json".equals(back.key) && "Dust".equals(back.name));
        TlUndo w = new TlUndo();
        w.load(back.undo);
        check("its undo and redo steps", w.canUndo() && w.canRedo() && "Shine".equals(w.undoWhat()) && "Colour".equals(w.redoWhat()));
        TlUndo.Step s = w.undo("{\"s\":2}", "ONE MORE");
        check("the undo step is the same one", s != null && "{\"s\":1}".equals(s.look) && !w.canUndo());
        TlDraft.delete(f);
        check("deleted when no longer needed", TlDraft.read(f) == null);
    }

    static void badDraftsAreRefused() {
        check("nothing", TlDraft.parse(null) == null && TlDraft.parse("") == null);
        check("not JSON", TlDraft.parse("draft") == null);
        check("a later version", TlDraft.parse("{\"version\":2,\"look\":\"{}\"}") == null);
        check("a look that does not read", TlDraft.parse("{\"version\":1,\"look\":\"not a look\"}") == null);
        check("a good one", TlDraft.parse("{\"version\":1,\"look\":\"{}\",\"words\":\"HI\"}") != null);
    }

    // ---- LAB-04 -----------------------------------------------------------------------------------

    static void stopEndsTheCopy() throws Exception {
        boolean[] stop = {false};
        InputStream in = new StopStream(new ByteArrayInputStream(new byte[1 << 20]), () -> stop[0]);
        byte[] buf = new byte[4096];
        int got = in.read(buf);
        check("reads while not stopped", got > 0);
        stop[0] = true;
        boolean stopped = false;
        try {
            in.read(buf);
        } catch (ExportStopped s) {
            stopped = true;
        }
        check("Stop ends the copy at the next piece", stopped);
    }

    static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
    }
}
