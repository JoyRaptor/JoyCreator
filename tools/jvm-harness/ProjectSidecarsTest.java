import com.fadcam.joyfolder.studio.ProjectSidecars;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Work-loss audit 2026-11-10: timeline marks travel inside a project's copy in the Joy Creator folder
 * and come back when the project is restored.
 */
public class ProjectSidecarsTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String marks = "{\"schemaVersion\":1,\"points\":[{\"id\":\"a\",\"name\":\"Chorus\",\"timeMs\":5000,\"color\":1}],\"regions\":[]}";
        String bookmarks = "{\"bookmarksMs\":[5000]}";

        // A project with no marks carries nothing.
        File plain = Files.createTempDirectory("sidecars-plain").toFile();
        check(ProjectSidecars.read(plain) == null, "no marks: nothing carried");

        // Marks and the older bookmark list are both carried, as they are.
        File phone = Files.createTempDirectory("sidecars-phone").toFile();
        write(new File(phone, "timeline-marks.json"), marks);
        write(new File(phone, "bookmarks.json"), bookmarks);
        write(new File(phone, "undo_history.json"), "[]");
        JsonObject carried = ProjectSidecars.read(phone);
        check(carried != null && carried.has("timeline-marks.json"), "marks are carried");
        check(carried != null && carried.has("bookmarks.json"), "the bookmark list is carried");
        check(carried != null && !carried.has("undo_history.json"), "undo history is not");
        check(carried != null && carried.get("timeline-marks.json").equals(JsonParser.parseString(marks)),
                "marks are carried unchanged");

        // A write of the marks file that was cut off: the good copy is the .bak (AtomicFile's way).
        File cut = Files.createTempDirectory("sidecars-cut").toFile();
        write(new File(cut, "timeline-marks.json"), "{\"schemaVersion\":1,\"poi");
        write(new File(cut, "timeline-marks.json.bak"), marks);
        JsonObject fromBak = ProjectSidecars.read(cut);
        check(fromBak != null && fromBak.get("timeline-marks.json").equals(JsonParser.parseString(marks)),
                "a cut-off write: the backup is carried");

        // A damaged file is left out, never carried as rubbish and never a failure.
        File bad = Files.createTempDirectory("sidecars-bad").toFile();
        write(new File(bad, "timeline-marks.json"), "not json at all");
        check(ProjectSidecars.read(bad) == null, "a damaged marks file is left out");

        // Restore into a fresh project folder (after a reinstall).
        File fresh = Files.createTempDirectory("sidecars-fresh").toFile();
        ProjectSidecars.restore(fresh, carried);
        check(new File(fresh, "timeline-marks.json").isFile(), "marks are written back");
        check(JsonParser.parseString(read(new File(fresh, "timeline-marks.json"))).equals(JsonParser.parseString(marks)),
                "the same marks come back");
        check(new File(fresh, "bookmarks.json").isFile(), "the bookmark list is written back");
        String[] left = fresh.list((d, n) -> n.endsWith(".tmp"));
        check(left != null && left.length == 0, "no temporary files are left");

        // Marks already on the phone are never replaced by the folder copy's.
        File has = Files.createTempDirectory("sidecars-has").toFile();
        String newer = "{\"schemaVersion\":1,\"points\":[],\"regions\":[]}";
        write(new File(has, "timeline-marks.json"), newer);
        ProjectSidecars.restore(has, carried);
        check(read(new File(has, "timeline-marks.json")).equals(newer), "marks on the phone are kept");

        // Only the known files are written, whatever a hand-edited folder copy says.
        File odd = Files.createTempDirectory("sidecars-odd").toFile();
        JsonObject tricky = new JsonObject();
        tricky.add("../outside.json", JsonParser.parseString("{}"));
        tricky.add("project.json", JsonParser.parseString("{}"));
        tricky.add("timeline-marks.json", JsonParser.parseString(marks));
        ProjectSidecars.restore(odd, tricky);
        check(!new File(odd.getParentFile(), "outside.json").exists(), "a path is never followed out of the project");
        check(!new File(odd, "project.json").exists(), "an unknown name is not written");
        check(new File(odd, "timeline-marks.json").isFile(), "the known one still is");

        // Nothing carried: nothing to do, no failure.
        ProjectSidecars.restore(Files.createTempDirectory("sidecars-none").toFile(), null);
        passes++;

        System.out.println("ProjectSidecarsTest: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
