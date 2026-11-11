import com.fadcam.ui.faditor.project.DiskStamp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Work-loss audit 2026-11-10: telling that a project file was written by someone else, and keeping
 * what a save is about to replace.
 */
public class DiskStampTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("diskstamp").toFile();
        File project = new File(dir, "project.json");

        // A file that is not there yet.
        long[] none = DiskStamp.of(project);
        check(none[1] == -1, "a missing file has length -1");
        check(!DiskStamp.differs(none, project), "missing then still missing: the same");
        check(!DiskStamp.differs(null, project), "never seen: nothing to compare, so not changed");

        write(project, "{\"name\":\"one\"}");
        check(DiskStamp.differs(none, project), "missing then written: changed");

        long[] seen = DiskStamp.of(project);
        check(!DiskStamp.differs(seen, project), "untouched: the same");

        // Another writer: different length.
        write(project, "{\"name\":\"another writer\"}");
        check(DiskStamp.differs(seen, project), "rewritten with another length: changed");

        // Another writer: the SAME length, a later time.
        seen = DiskStamp.of(project);
        write(project, "{\"name\":\"another WRITER\"}");
        check(project.setLastModified(seen[0] + 5000), "test can set the time");
        check(DiskStamp.differs(seen, project), "rewritten with the same length, later: changed");

        // Deleted under us.
        seen = DiskStamp.of(project);
        check(project.delete(), "test can delete");
        check(DiskStamp.differs(seen, project), "deleted: changed");

        // Keeping what a save replaces: a whole copy, newest first, only the newest few.
        write(project, "first writer's work");
        File kept = DiskStamp.keepCopy(dir, project, DiskStamp.REPLACED_PREFIX, 3);
        check(kept != null && kept.isFile(), "a copy is kept");
        check(kept != null && kept.getParentFile().getName().equals(DiskStamp.BACKUPS_DIR), "in backups/");
        check(kept != null && new String(Files.readAllBytes(kept.toPath()), StandardCharsets.UTF_8)
                .equals("first writer's work"), "the copy is whole");
        check(new String(Files.readAllBytes(project.toPath()), StandardCharsets.UTF_8)
                .equals("first writer's work"), "the original is untouched");

        for (int i = 2; i <= 6; i++) {
            Thread.sleep(3);   // the name carries the time to the millisecond
            write(project, "writer " + i);
            DiskStamp.keepCopy(dir, project, DiskStamp.REPLACED_PREFIX, 3);
        }
        List<File> all = DiskStamp.kept(dir, DiskStamp.REPLACED_PREFIX);
        check(all.size() == 3, "only the newest 3 are kept, found " + all.size());
        check(!all.isEmpty() && new String(Files.readAllBytes(all.get(0).toPath()), StandardCharsets.UTF_8)
                .equals("writer 6"), "newest first");
        check(all.size() == 3 && new String(Files.readAllBytes(all.get(2).toPath()), StandardCharsets.UTF_8)
                .equals("writer 4"), "the oldest kept is the third newest");

        // Another kind of copy has its own count.
        DiskStamp.keepCopy(dir, project, DiskStamp.UNREADABLE_PREFIX, 3);
        check(DiskStamp.kept(dir, DiskStamp.UNREADABLE_PREFIX).size() == 1, "unreadable copies are counted apart");
        check(DiskStamp.kept(dir, DiskStamp.REPLACED_PREFIX).size() == 3, "and do not push out replaced ones");

        // Nothing to copy.
        check(DiskStamp.keepCopy(dir, new File(dir, "nope.json"), DiskStamp.REPLACED_PREFIX, 3) == null,
                "a missing source keeps nothing and does not throw");

        System.out.println("DiskStampTest: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
