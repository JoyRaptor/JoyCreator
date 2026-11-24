import cc.joycreator.textlab.LooksComeHome;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SAFE-1: a TextLab look saved while the Joy Creator folder was out of reach is kept in the app's
 * own folder; it must come home once the folder is back, and never be deleted before its copy is
 * proven. Run: {@code bash tools/jvm-harness/run-looks-home.sh}
 */
public class LooksComeHomeTest {

    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A folder in memory, with switches for the ways a real one lets you down. */
    static class FakeFolder implements LooksComeHome.Folder {
        final Map<String, byte[]> files = new HashMap<>();
        boolean failPut, corruptReadback;

        @Override public String holds(byte[] text) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                if (Arrays.equals(e.getValue(), text)) return e.getKey();
            }
            return null;
        }

        @Override public String put(String base, byte[] text) throws Exception {
            if (failPut) throw new IOException("folder full");
            String rel = "Supplies/Looks/" + base + ".text.json";
            for (int n = 2; files.containsKey(rel); n++) rel = "Supplies/Looks/" + base + " " + n + ".text.json";
            files.put(rel, text.clone());
            return rel;
        }

        @Override public byte[] read(String rel) {
            byte[] b = files.get(rel);
            if (b != null && corruptReadback) return Arrays.copyOf(b, Math.max(0, b.length - 3));
            return b;
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("looks-home").toFile();
        File look = new File(dir, "Fire.text.json");
        File preview = new File(dir, "Fire.5x4.sprite.webp");
        write(look, "{\"name\":\"Fire\",\"layers\":[1,2,3]}");
        write(preview, "picture");
        FakeFolder folder = new FakeFolder();

        // The folder refuses the write: nothing is deleted, nothing is reported moved.
        folder.failPut = true;
        check("a refused write moves nothing", LooksComeHome.run(dir, folder).isEmpty());
        check("...and the look is still in the app's folder", look.isFile() && preview.isFile());
        folder.failPut = false;

        // A copy that does not read back whole is not proof: the local look stays.
        folder.corruptReadback = true;
        check("a copy that reads back short counts as not moved", LooksComeHome.run(dir, folder).isEmpty());
        check("...and the look is still in the app's folder (even though the folder got a bad copy)", look.isFile());
        folder.corruptReadback = false;
        folder.files.clear();

        // The good case.
        List<String> moved = LooksComeHome.run(dir, folder);
        check("a look comes home", moved.size() == 1 && moved.get(0).equals("Supplies/Looks/Fire.text.json"));
        check("the folder holds exactly its text", Arrays.equals(folder.files.get("Supplies/Looks/Fire.text.json"),
                "{\"name\":\"Fire\",\"layers\":[1,2,3]}".getBytes(StandardCharsets.UTF_8)));
        check("it is gone from the app's folder only now, with its own picture",
                !look.exists() && !preview.exists());

        // A look the folder already has (the same bytes) is not written twice.
        write(look, "{\"name\":\"Fire\",\"layers\":[1,2,3]}");
        check("an identical look already in the folder is not duplicated", LooksComeHome.run(dir, folder).isEmpty());
        check("...and the redundant local copy is cleared", !look.exists() && folder.files.size() == 1);

        // A different look with the same name is a different look: kept under a free name.
        write(look, "{\"name\":\"Fire\",\"layers\":[9]}");
        List<String> second = LooksComeHome.run(dir, folder);
        check("a different look of the same name gets a free name",
                second.size() == 1 && second.get(0).equals("Supplies/Looks/Fire 2.text.json"));
        check("...and both survive", folder.files.size() == 2);

        // Files that are not looks are left alone; an empty file is never 'moved'.
        File other = new File(dir, "notes.txt");
        write(other, "mine");
        File empty = new File(dir, "Empty.text.json");
        write(empty, "");
        check("nothing happens for a note or an empty look", LooksComeHome.run(dir, folder).isEmpty());
        check("...both are still there", other.isFile() && empty.isFile());

        // A missing folder is not an error.
        check("a missing directory moves nothing", LooksComeHome.run(new File(dir, "nope"), folder).isEmpty());

        // ── Wiring, by reading the source (the Android classes cannot run here) ──
        String shelf = new String(Files.readAllBytes(new File(
                "app/src/main/java/cc/joycreator/textlab/TextLookShelf.java").toPath()), StandardCharsets.UTF_8);
        check("the shelf brings own-folder looks home before it lists the folder",
                shelf.contains("TextLabPreviews.bringOwnLooksHome("));
        String room = new String(Files.readAllBytes(new File(
                "app/src/main/java/cc/joycreator/textlab/TextLabActivity.java").toPath()), StandardCharsets.UTF_8);
        int more = room.indexOf("private void more()");
        int saveAsRow = room.indexOf("textlab_more_save_as)", more);
        int titleGate = room.indexOf("if (!titleMode) {", more);
        check("Save as is offered while editing a title too (before the !titleMode block)",
                more > 0 && saveAsRow > 0 && saveAsRow < titleGate);
        String marks = new String(Files.readAllBytes(new File(
                "app/src/main/java/com/fadcam/ui/faditor/markers/TimelineMarksPopup.java").toPath()), StandardCharsets.UTF_8);
        check("the marks popup's buttons are described by what they show, never a raw format string",
                !marks.contains("setContentDescription(context.getString(text))")
                        && marks.contains("exportButton.setContentDescription(exportButton.getText())"));
        String session = new String(Files.readAllBytes(new File(
                "app/src/main/java/com/fadcam/vislab/room/VisLabSession.java").toPath()), StandardCharsets.UTF_8);
        check("a VisLab part that fails to write is retried and the person told",
                session.contains("writePartWithRetry(name, part.toJson(), 0)")
                        && session.contains("attempt < PART_RETRIES")
                        && !session.contains("Couldn't save the part \" + name, e)"));

        System.out.println("LooksComeHomeTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
