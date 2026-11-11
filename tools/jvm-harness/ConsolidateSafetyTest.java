import com.fadcam.ui.faditor.export.ExportNames;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectConsolidator;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * Work-loss audit 2026-11-10, second pass: "copy every asset into the project" must never put one
 * asset's bytes under another's name, must say when a file could not be copied, and an export must
 * never land on a finished export's name.
 */
public class ConsolidateSafetyTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    static File root;

    static File write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    static String text(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static Clip clip(File f) {
        return new Clip(android.net.Uri.parse("file://" + f.getAbsolutePath().replace('\\', '/')), 5_000L);
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("consolidate-safety").toFile();
        android.content.Context ctx = new android.content.Context() {
            @Override public java.io.File getFilesDir() { return root; }
        };

        // ── 1. Two different files with the same name ─────────────────────────────────────────
        File a = write(new File(root, "outside/holiday/clip.mp4"), "the HOLIDAY clip, first source");
        File b = write(new File(root, "outside/birthday/clip.mp4"), "the BIRTHDAY clip, a different file with the same name");
        File c = write(new File(root, "outside/other.mp4"), "a third clip");
        FaditorProject p = new FaditorProject("Same names");
        p.getTimeline().addClip(clip(a));
        p.getTimeline().addClip(clip(b));
        p.getTimeline().addClip(clip(c));
        p.getTimeline().addClip(clip(a));   // the holiday clip used twice

        ProjectConsolidator.Result r = ProjectConsolidator.consolidate(ctx, p, null);
        check(r.ok, "consolidate reports ok");
        check(r.failed == 0, "nothing failed, was " + r.failed);
        check(r.copied == 3, "three distinct files copied, was " + r.copied);

        File media = new File(root, "faditor/projects/" + p.getId() + "/media");
        File[] copies = media.listFiles();
        check(copies != null && copies.length == 3, "three files in the project's media folder, found "
                + (copies == null ? 0 : copies.length));

        // Every clip now reads the bytes it read before.
        String[] want = {text(a), text(b), text(c), text(a)};
        Set<String> seenPaths = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            String path = p.getTimeline().getClip(i).getSourceUri().getPath();
            File now = new File(path);
            seenPaths.add(now.getAbsolutePath());
            check(now.getAbsolutePath().startsWith(media.getAbsolutePath()), "clip " + i + " points inside the project");
            check(now.isFile() && want[i].equals(text(now)), "clip " + i + " still shows its own picture");
        }
        check(seenPaths.size() == 3, "the two same-named files have two copies, and the reused clip shares one");
        check(text(a).startsWith("the HOLIDAY") && text(b).startsWith("the BIRTHDAY"), "the originals are untouched");

        // ── 2. A file that cannot be read is said, not skipped in silence ─────────────────────
        File gone = new File(root, "outside/deleted-before-consolidate.mp4");
        File ok = write(new File(root, "outside/fine.mp4"), "this one is fine");
        FaditorProject q = new FaditorProject("One missing");
        q.getTimeline().addClip(clip(ok));
        q.getTimeline().addClip(clip(gone));
        ProjectConsolidator.Result r2 = ProjectConsolidator.consolidate(ctx, q, null);
        check(r2.copied == 1, "the readable file is copied, was " + r2.copied);
        check(r2.failed == 1, "the unreadable one is counted as failed, was " + r2.failed);
        check(r2.failedNames.size() == 1 && r2.failedNames.get(0).contains("deleted-before-consolidate"),
                "and named: " + r2.failedNames);
        check(q.getTimeline().getClip(1).getSourceUri().getPath().replace('\\', '/')
                        .endsWith("outside/deleted-before-consolidate.mp4"),
                "the clip that could not be copied still points at its original");

        // ── 3. An export never lands on a finished export's name ──────────────────────────────
        File outDir = new File(root, "exports");
        outDir.mkdirs();
        File first = ExportNames.freeFile(outDir, "My film.mp4");
        check(first.getName().equals("My film.mp4"), "a free name is used as it is");
        write(first, "the first export");
        File second = ExportNames.freeFile(outDir, "My film.mp4");
        check(second.getName().equals("My film 2.mp4"), "the second export gets its own name: " + second.getName());
        write(second, "the second export");
        File third = ExportNames.freeFile(outDir, "My film.mp4");
        check(third.getName().equals("My film 3.mp4"), "and the third: " + third.getName());
        check(text(first).equals("the first export"), "the first export is untouched");
        File noExt = ExportNames.freeFile(outDir, "frame");
        write(noExt, "x");
        check((ExportNames.freeFile(outDir, "frame")).getName().equals("frame 2"), "a name with no ending works too");

        System.out.println("ConsolidateSafetyTest: " + passes + " passed, " + fails + " failed");
        System.exit(fails > 0 ? 1 : 0);
    }
}
