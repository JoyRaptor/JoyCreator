import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.DiskStamp;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Work-loss audit 2026-11-10, through the REAL ProjectStorage on a temporary folder:
 * the same project held in two places, saves racing each other, an audio-only project,
 * and a project file that will not read.
 */
public class WorkLossStorageTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    static File root;

    static ProjectStorage storage() {
        return new ProjectStorage(new android.content.Context() {
            @Override public java.io.File getFilesDir() { return root; }
        });
    }

    static File projectDir(String id) {
        return new File(root, "faditor/projects/" + id);
    }

    static FaditorProject video(String name) {
        FaditorProject p = new FaditorProject(name);
        p.getTimeline().addClip(new Clip(android.net.Uri.parse("file:///sdcard/Movies/a.mp4"), 10_000L));
        return p;
    }

    static FaditorProject audioOnly(String name) {
        FaditorProject p = new FaditorProject(name);
        p.getTimeline().addAudioClip(new AudioClip(android.net.Uri.parse("file:///sdcard/Music/talk.m4a"), 60_000L));
        return p;
    }

    static String text(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("workloss-storage").toFile();

        // ── 1. An audio-only project reopens as it was last saved, not one save behind ─────────
        {
            ProjectStorage ps = storage();
            FaditorProject p = audioOnly("Podcast");
            check(ps.save(p), "audio-only: first save");
            FaditorProject once = storage().load(p.getId());
            check(once != null, "audio-only: opens after a single save (there is no backup yet)");
            p.setName("Podcast, second take");
            check(ps.save(p), "audio-only: second save");
            FaditorProject back = storage().load(p.getId());
            check(back != null && "Podcast, second take".equals(back.getName()),
                    "audio-only: the LAST save comes back, was: " + (back == null ? null : back.getName()));
        }

        // ── 2. The same project held in two places ───────────────────────────────────────────
        {
            ProjectStorage first = storage();
            FaditorProject p = video("Shared");
            check(first.save(p), "two places: saved");
            String id = p.getId();

            ProjectStorage editorA = storage();
            FaditorProject a = editorA.load(id);
            check(a != null && !editorA.changedOutside(id), "A has just loaded: nothing changed outside");

            ProjectStorage editorB = storage();
            FaditorProject b = editorB.load(id);
            b.setName("B newer work");
            check(editorB.save(b), "B saves");
            check(!editorB.changedOutside(id), "B wrote it: for B nothing changed outside");
            check(editorA.changedOutside(id), "A sees that someone else wrote the project");

            // A saves anyway (it is the one on screen). What it replaces is kept.
            check(DiskStamp.kept(projectDir(id), DiskStamp.REPLACED_PREFIX).isEmpty(), "nothing kept yet");
            a.setName("A older copy");
            check(editorA.save(a), "A saves over it");
            List<File> kept = DiskStamp.kept(projectDir(id), DiskStamp.REPLACED_PREFIX);
            check(kept.size() == 1, "what A replaced is kept, found " + kept.size());
            check(!kept.isEmpty() && text(kept.get(0)).contains("B newer work"), "the kept copy is B's work");
            check(!editorA.changedOutside(id), "after its own save A is up to date");

            // A's own later saves keep nothing more.
            a.setName("A again");
            editorA.save(a);
            check(DiskStamp.kept(projectDir(id), DiskStamp.REPLACED_PREFIX).size() == 1, "A's own saves keep no extra copies");

            // A storage that never loaded the project makes no claim about it.
            check(!storage().changedOutside(id), "never loaded: no claim");
        }

        // ── 3. A queued save never lands on top of a later one ────────────────────────────────
        {
            ProjectStorage ps = storage();
            FaditorProject p = video("Race");
            ps.save(p);
            int wrong = 0;
            for (int i = 0; i < 150; i++) {
                p.setName("queued " + i);
                ps.saveAsync(p);
                p.setName("final " + i);
                ps.save(p);                 // on this thread, without waiting for the queue
                ps.flushPendingWrites();
                FaditorProject back = storage().load(p.getId());
                if (back == null || !("final " + i).equals(back.getName())) wrong++;
            }
            check(wrong == 0, "queued-then-direct saves: the later one always stands (" + wrong + " of 150 wrong)");
            check(DiskStamp.kept(projectDir(p.getId()), DiskStamp.REPLACED_PREFIX).isEmpty(),
                    "one storage racing itself is not 'someone else'");
        }

        // ── 4. Saves racing on several threads never leave an unreadable project ──────────────
        {
            final FaditorProject seed = video("Crowd");
            // Bulk, so a write takes long enough to overlap.
            for (int i = 0; i < 400; i++) {
                seed.getTimeline().addClip(new Clip(android.net.Uri.parse("file:///sdcard/Movies/clip" + i + ".mp4"), 1000L + i));
            }
            storage().save(seed);
            final String id = seed.getId();
            final int threads = 6, rounds = 25;
            final CountDownLatch go = new CountDownLatch(1);
            final List<Thread> all = new ArrayList<>();
            final int[] unreadable = {0};
            for (int t = 0; t < threads; t++) {
                final int n = t;
                Thread th = new Thread(() -> {
                    try {
                        go.await();
                        ProjectStorage mine = storage();
                        for (int r = 0; r < rounds; r++) {
                            FaditorProject p = mine.load(id);
                            if (p == null) { synchronized (unreadable) { unreadable[0]++; } continue; }
                            p.setName("writer " + n + " round " + r);
                            if ((r & 1) == 0) mine.save(p); else { mine.saveAsync(p); mine.flushPendingWrites(); }
                        }
                    } catch (Exception e) {
                        synchronized (unreadable) { unreadable[0]++; }
                    }
                });
                all.add(th);
                th.start();
            }
            go.countDown();
            for (Thread th : all) th.join();
            check(unreadable[0] == 0, "racing writers: every load found a readable project (" + unreadable[0] + " did not)");
            FaditorProject end = storage().load(id);
            check(end != null && end.getTimeline().getClipCount() == 401, "racing writers: the project is whole at the end");
            // The file itself, not a fall-back to the backup.
            String main = text(new File(projectDir(id), "project.json"));
            check(main.trim().endsWith("}") && main.contains("\"clips\""), "racing writers: project.json is a whole file");
            String[] tmp = projectDir(id).list((d, name) -> name.endsWith(".tmp"));
            check(tmp != null && tmp.length == 0, "racing writers: no temporary file left behind");
        }

        // ── 5. A project file that will not read: the backup opens, and the bad file is kept ──
        {
            ProjectStorage ps = storage();
            FaditorProject p = video("Damaged");
            ps.save(p);
            p.setName("Damaged, newer");
            ps.save(p);                                   // the first save is now the .bak
            File main = new File(projectDir(p.getId()), "project.json");
            String whole = text(main);
            try (FileOutputStream out = new FileOutputStream(main)) {
                out.write(whole.substring(0, whole.length() / 2).getBytes(StandardCharsets.UTF_8));   // cut off
            }
            FaditorProject back = storage().load(p.getId());
            check(back != null && "Damaged".equals(back.getName()), "a cut-off file: the backup opens");
            List<File> kept = DiskStamp.kept(projectDir(p.getId()), DiskStamp.UNREADABLE_PREFIX);
            check(kept.size() == 1, "the cut-off file is kept for a look, found " + kept.size());
            storage().load(p.getId());
            storage().load(p.getId());
            check(DiskStamp.kept(projectDir(p.getId()), DiskStamp.UNREADABLE_PREFIX).size() == 1,
                    "opening again does not keep it again");
        }

        // ── 6. A blank project (nothing added yet) is still a project ─────────────────────────
        {
            ProjectStorage ps = storage();
            FaditorProject p = new FaditorProject("Untitled audio");
            ps.save(p);
            FaditorProject back = storage().load(p.getId());
            check(back != null && "Untitled audio".equals(back.getName()), "a blank project opens");
            // ...but a backup with something in it still beats a main file with nothing in it.
            FaditorProject full = video("Had a clip");
            ps.save(full);
            ps.save(full);
            File main = new File(projectDir(full.getId()), "project.json");
            FaditorProject hollow = new FaditorProject("Had a clip");
            String hollowJson = ps.toJson(hollow).replace(hollow.getId(), full.getId());
            try (FileOutputStream out = new FileOutputStream(main)) {
                out.write(hollowJson.getBytes(StandardCharsets.UTF_8));
            }
            FaditorProject rescued = storage().load(full.getId());
            check(rescued != null && rescued.getTimeline().getClipCount() == 1,
                    "an emptied main file: the backup with the clip opens");
        }

        // ── 7. A project is the folder it lives in, whatever id its file carries ──────────────
        {
            ProjectStorage ps = storage();
            FaditorProject original = video("The original");
            ps.save(original);
            ps.save(original);                                   // so it has a backup too
            String originalId = original.getId();
            String originalBytes = text(new File(projectDir(originalId), "project.json"));

            // A copy of the whole folder under another id, its files still stamped with the
            // original's id: what a zip import left behind when the id rewrite did not happen.
            String copyId = "copy-" + originalId;
            File copyDir = projectDir(copyId);
            copyDir.mkdirs();
            for (String name : new String[]{"project.json", "project.json.bak"}) {
                Files.copy(new File(projectDir(originalId), name).toPath(), new File(copyDir, name).toPath());
            }
            ProjectStorage other = storage();
            FaditorProject copy = other.load(copyId);
            check(copy != null && copyId.equals(copy.getId()), "a copied folder loads as ITS project, was "
                    + (copy == null ? null : copy.getId()));
            copy.setName("The copy, edited");
            check(other.save(copy), "the copy saves");
            check(originalBytes.equals(text(new File(projectDir(originalId), "project.json"))),
                    "saving the copy leaves the original untouched");
            check(text(new File(copyDir, "project.json")).contains("The copy, edited"), "and lands in the copy's folder");

            // With the main file gone, the backup is read as this project too.
            new File(copyDir, "project.json").delete();
            FaditorProject fromBak = storage().load(copyId);
            check(fromBak != null && copyId.equals(fromBak.getId()), "a copied backup loads as its own project");

            // An undo snapshot from the original, restored in the copy, stays the copy.
            String snapshot = ps.toJson(original);
            FaditorProject restored = ps.fromJson(snapshot, copyId);
            check(restored != null && copyId.equals(restored.getId()), "a snapshot is restored as the project it is restored into");
            check(restored != null && restored.getTimeline().getClipCount() == 1, "with its content");
            FaditorProject same = ps.fromJson(snapshot, originalId);
            check(same != null && originalId.equals(same.getId()), "a snapshot of the same project is untouched");

            // What a bundle carries, and what a zip may write.
            check(com.fadcam.ui.faditor.project.BundleRules.notPartOfABundle("undo_history.json"), "undo history stays home");
            check(com.fadcam.ui.faditor.project.BundleRules.notPartOfABundle("backups/replaced-1.json"), "backups stay home");
            check(com.fadcam.ui.faditor.project.BundleRules.notPartOfABundle("project.json.bak"), "the backup stays home");
            check(!com.fadcam.ui.faditor.project.BundleRules.notPartOfABundle("project.json"), "the project travels");
            check(!com.fadcam.ui.faditor.project.BundleRules.notPartOfABundle("media/clip.mp4"), "media travels");
            check(!com.fadcam.ui.faditor.project.BundleRules.notPartOfABundle("timeline-marks.json"), "marks travel");
            check(com.fadcam.ui.faditor.project.BundleRules.unsafePath("../outside.json"), "a path out of the folder is refused");
            check(com.fadcam.ui.faditor.project.BundleRules.unsafePath("media/../../x"), "anywhere in the path");
            check(com.fadcam.ui.faditor.project.BundleRules.unsafePath("/absolute"), "an absolute path is refused");
            check(!com.fadcam.ui.faditor.project.BundleRules.unsafePath("media/my..clip.mp4"), "two dots in a NAME are fine");
        }

        System.out.println("WorkLossStorageTest: " + passes + " passed, " + fails + " failed");
        // Always exit: each ProjectStorage keeps a writer thread, which would hold the JVM open.
        System.exit(fails > 0 ? 1 : 0);
    }
}
