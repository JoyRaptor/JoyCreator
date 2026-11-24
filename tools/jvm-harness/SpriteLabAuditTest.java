import com.fadcam.ui.faditor.sprite.SpriteSheet;

import java.util.ArrayList;
import java.util.List;

/**
 * Codex audit 11-15, the SpriteLab model rows that run without Android:
 * SP-01 (a reordered image sequence must show the picture that moved, not the slot's old one) and
 * SP-02 (copying a cell, as the sidecar import does, keeps its tags).
 *
 * <p>Run: {@code bash tools/jvm-harness/run-spritelab-audit.sh}</p>
 */
public class SpriteLabAuditTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        reorderedSequenceShowsTheMovedPicture();
        reorderSurvivesSaveAndReopen();
        cellCopyKeepsTags();
        saveSafetyOfTheEditorScreen();
        System.out.println("SpriteLabAuditTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL: " + name); }
    }

    static SpriteSheet seq(int n) {
        SpriteSheet s = new SpriteSheet("id", "seq", "");
        List<String> uris = new ArrayList<>();
        for (int i = 0; i < n; i++) uris.add("file:///f" + i + ".png");
        s.setSequenceFrames(uris);
        return s;
    }

    static void reorderedSequenceShowsTheMovedPicture() {
        SpriteSheet s = seq(4);
        s.setCellName(0, "wave");
        s.swapCells(0, 2);
        check("swap: slot 0 now shows picture 2", "file:///f2.png".equals(s.frameUriAt(0)));
        check("swap: slot 2 now shows picture 0", "file:///f0.png".equals(s.frameUriAt(2)));
        check("swap: the name moved with its picture", "wave".equals(s.cellName(2)));
        s.resetOrder();
        s.moveCell(3, 0);   // ripple: 3 to the front
        check("ripple: slot 0 shows picture 3", "file:///f3.png".equals(s.frameUriAt(0)));
        check("ripple: slot 1 shows picture 0", "file:///f0.png".equals(s.frameUriAt(1)));
        check("ripple: the stored file list itself is untouched",
                "file:///f0.png".equals(s.getFrameUris().get(0)));
        check("out of range is null, never a crash", s.frameUriAt(9) == null && s.frameUriAt(-1) == null);
    }

    static void reorderSurvivesSaveAndReopen() {
        SpriteSheet s = seq(3);
        s.swapCells(0, 1);
        SpriteSheet back = SpriteSheet.fromJson(s.toJson());
        check("reopen: slot 0 still shows picture 1", "file:///f1.png".equals(back.frameUriAt(0)));
    }

    static void cellCopyKeepsTags() {
        SpriteSheet.Cell c = new SpriteSheet.Cell(3, "jump");
        c.tags.add("happy");
        c.tags.add("action");
        c.enabled = false;
        SpriteSheet.Cell k = c.copy();
        check("cell copy keeps index, name, tags and off-switch",
                k.index == 3 && "jump".equals(k.name) && k.tags.size() == 2
                        && k.tags.contains("happy") && !k.enabled);
        k.tags.add("more");
        check("cell copy's tags are its own", c.tags.size() == 2);
    }

    // ── SAFE-1 second pass: the editor screen cannot run on the JVM, so its save rules are pinned
    // by reading its source. Each check fails on the code from before the fix.

    static String body(String src, String signature) {
        int at = src.indexOf(signature);
        if (at < 0) return "";
        int open = src.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    static void saveSafetyOfTheEditorScreen() {
        String src;
        try {
            src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            check("the editor source can be read: " + e, false);
            return;
        }
        String imp = body(src, "private void importSidecar(");
        check("importing a sidecar is an undo step that reaches the disk (markDirty)",
                imp.contains("markDirty();"));
        String img = body(src, "private void importSheetImage(");
        check("a new or relinked sheet picture marks the sheet changed",
                img.contains("markDirtyQuiet();") || img.contains("markDirty();"));
        check("save() reports whether the write worked",
                src.contains("private boolean save(boolean quiet)") && src.contains("return ok;"));
        String back = body(src, "public void onBackPressed(");
        check("Back does not close the screen when the save failed",
                back.contains("if (!save())"));
        String open = body(src, "private void openSheet(");
        check("opening another sheet does not close the screen when the save failed",
                open.contains("if (!save(true))"));
        check("the Save button never marks the sheet clean by itself",
                !src.contains("save(); labDirty = false;"));
        check("removing a second sheet does not overwrite the first (a list, not one slot)",
                src.contains("removedSheets.add(target)") && !src.contains("removedSheet = target;"));
    }
}
