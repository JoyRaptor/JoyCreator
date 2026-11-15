import com.fadcam.joyfolder.LegacyFolderMove;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for moving FadCam's old folders (LegacyFolderMove, SHELL-44 / FREE-2 step 3). Real temporary folders.
 * The rule under test everywhere: the old file is only ever removed when a checked copy exists, and only on a
 * later start than the one that copied it.
 */
public final class LegacyFolderMoveTests {

    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        twoStarts();
        dryRunChangesNothing();
        killedMidCopy();
        copiedButNotLogged();
        runTwiceIsHarmless();
        damagedCopyKeepsOriginal();
        stillReferencedWaits();
        clashKeepsOrRenames();
        folderUnitsAndResume();
        System.out.println("LegacyFolderMove: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ──

    static final class World {
        final File base;
        final File oldDir, newDir, oldProjects, newProjects, logFile;
        final Set<String> referenced = new HashSet<>();
        final List<String> repointed = new ArrayList<>();
        final LegacyFolderMove.Hooks hooks;

        World() throws IOException {
            base = Files.createTempDirectory("lfm").toFile();
            oldDir = new File(base, "Download/FadCam");
            newDir = new File(base, "Download/Joy Creator");
            oldProjects = new File(base, "Documents/FadRec/Projects");
            newProjects = new File(base, "Documents/Joy Creator/Screen drawings");
            logFile = new File(base, "moves.txt");
            oldDir.mkdirs();
            oldProjects.mkdirs();
            hooks = new LegacyFolderMove.Hooks() {
                @Override public void repoint(File from, File to) { repointed.add(from.getPath() + " -> " + to.getPath()); }
                @Override public boolean stillReferenced(File o) { return referenced.contains(o.getPath()); }
                @Override public boolean delete(File o) { return o.delete(); }
            };
        }

        List<LegacyFolderMove.Root> roots() {
            return Arrays.asList(new LegacyFolderMove.Root(oldDir, newDir, false),
                    new LegacyFolderMove.Root(oldProjects, newProjects, true));
        }

        LegacyFolderMove.Result run(long t) throws IOException {
            return LegacyFolderMove.run(roots(), LegacyFolderMove.fileStore(logFile), hooks, t,
                    Arrays.asList(new File(base, "Documents/FadRec")));
        }

        String log() throws IOException {
            return logFile.isFile() ? new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8) : "";
        }
    }

    static File write(File f, String content) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(content.getBytes(StandardCharsets.UTF_8)); }
        return f;
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ── tests ──

    static void twoStarts() throws Exception {
        World w = new World();
        File v = write(new File(w.oldDir, "Camera 2026-11-13 07.23.mp4"), "video-bytes");
        LegacyFolderMove.Result r1 = w.run(1000);
        File copy = new File(w.newDir, v.getName());
        check(r1.copied == 1 && copy.isFile() && v.isFile(), "start 1 copies and keeps the original");
        check(read(copy).equals("video-bytes"), "the copy has the same content");
        check(w.repointed.size() == 1, "projects are pointed at the copy");
        check(w.log().contains("copied\t" + v.getPath() + "\t" + copy.getPath()), "the log names old and new path");
        check(w.log().contains(LegacyFolderMove.sha256(copy)), "the log holds the fingerprint");
        LegacyFolderMove.Result r2 = w.run(2000);
        check(r2.removed == 1 && !v.exists() && copy.isFile(), "start 2 removes the original, the copy stays");
        check(!w.oldDir.exists(), "the empty old folder is removed");
        check(w.log().contains("removed\t" + v.getPath()), "the log says removed");
    }

    static void dryRunChangesNothing() throws Exception {
        World w = new World();
        File v = write(new File(w.oldDir, "a.mp4"), "x");
        write(new File(w.oldProjects, "FadRec_1/project.fadrec"), "{}");
        List<String> plan = LegacyFolderMove.plan(w.roots());
        check(plan.size() == 2, "the plan lists every file");
        check(plan.get(0).startsWith("would-move\t" + v.getPath()), "the plan says from where");
        check(v.isFile() && !w.newDir.exists() && !w.logFile.exists(), "the dry run touches nothing");
    }

    static void killedMidCopy() throws Exception {
        World w = new World();
        File v = write(new File(w.oldDir, "a.mp4"), "full-content");
        // A start that died while copying: a half copy lies beside the new name, nothing logged.
        write(new File(w.newDir, "a.mp4" + LegacyFolderMove.PARTIAL), "full-");
        LegacyFolderMove.Result r = w.run(1000);
        File copy = new File(w.newDir, "a.mp4");
        check(r.copied == 1 && read(copy).equals("full-content"), "the next start copies it whole");
        check(!new File(w.newDir, "a.mp4" + LegacyFolderMove.PARTIAL).exists(), "the half copy is cleared");
        check(v.isFile(), "the original is still there after the copying start");
    }

    static void copiedButNotLogged() throws Exception {
        World w = new World();
        File v = write(new File(w.oldDir, "a.mp4"), "same");
        write(new File(w.newDir, "a.mp4"), "same");   // renamed into place, then killed before the log line
        LegacyFolderMove.Result r = w.run(1000);
        check(r.copied == 1 && !new File(w.newDir, "a 2.mp4").exists(), "a whole copy already in place is reused");
        check(v.isFile(), "and the original stays until a later start");
    }

    static void runTwiceIsHarmless() throws Exception {
        World w = new World();
        write(new File(w.oldDir, "a.mp4"), "x");
        w.run(1000);
        String log1 = w.log();
        LegacyFolderMove.Result again = w.run(1000);   // the same start running twice
        check(again.copied == 0 && again.removed == 0, "running twice in one start does nothing more");
        check(new File(w.oldDir, "a.mp4").isFile(), "and removes nothing");
        check(w.log().split("\n").length == log1.split("\n").length, "and adds no lines");
    }

    static void damagedCopyKeepsOriginal() throws Exception {
        World w = new World();
        File v = write(new File(w.oldDir, "a.mp4"), "good");
        w.run(1000);
        write(new File(w.newDir, "a.mp4"), "changed");   // the copy got damaged before the second start
        LegacyFolderMove.Result r = w.run(2000);
        check(r.kept == 1 && v.isFile() && read(v).equals("good"), "a damaged copy keeps the original");
        check(w.log().contains("kept\t" + v.getPath()), "the log says kept");
        LegacyFolderMove.Result r3 = w.run(3000);
        check(r3.removed == 0 && v.isFile(), "and later starts leave it alone");
    }

    static void stillReferencedWaits() throws Exception {
        World w = new World();
        File v = write(new File(w.oldDir, "a.mp4"), "x");
        w.run(1000);
        w.referenced.add(v.getPath());
        LegacyFolderMove.Result r = w.run(2000);
        check(r.waiting == 1 && v.isFile(), "an original a project still names is not removed");
        w.referenced.clear();
        LegacyFolderMove.Result r2 = w.run(3000);
        check(r2.removed == 1 && !v.exists(), "it goes once no project names it");
    }

    static void clashKeepsOrRenames() throws Exception {
        World w = new World();
        File v = write(new File(w.oldDir, "a.mp4"), "old");
        write(new File(w.newDir, "a.mp4"), "someone else's");
        LegacyFolderMove.Result r = w.run(1000);
        check(r.copied == 1 && read(new File(w.newDir, "a 2.mp4")).equals("old"), "a name clash gets ' 2'");
        check(read(new File(w.newDir, "a.mp4")).equals("someone else's"), "the other file is untouched");
        check(v.isFile(), "the original stays for now");
    }

    static void folderUnitsAndResume() throws Exception {
        World w = new World();
        File p1 = write(new File(w.oldProjects, "Talk/project.fadrec"), "{\"a\":1}");
        File p2 = write(new File(w.oldProjects, "Talk/thumbnail.png"), "png");
        write(new File(w.newProjects, "Talk/project.fadrec"), "{\"other\":2}");   // a different project, same name
        LegacyFolderMove.Result r = w.run(1000);
        File moved = new File(w.newProjects, "Talk_2");
        check(r.copied == 2 && read(new File(moved, "project.fadrec")).equals("{\"a\":1}"), "a clashing project folder becomes Talk_2");
        check(read(new File(w.newProjects, "Talk/project.fadrec")).equals("{\"other\":2}"), "the other project is untouched");
        check(w.log().contains("planned\t" + new File(w.oldProjects, "Talk").getPath()), "the folder's place is logged before copying");
        // A new file appears in the old project before the next start: it follows into the same planned folder.
        File p3 = write(new File(w.oldProjects, "Talk/notes.txt"), "n");
        LegacyFolderMove.Result r2 = w.run(2000);
        check(new File(moved, "notes.txt").isFile(), "a later file follows to the planned folder");
        check(r2.removed == 2 && !p1.exists() && !p2.exists() && p3.isFile(), "originals of the earlier start go, the new one waits");
        LegacyFolderMove.Result r3 = w.run(3000);
        check(r3.removed == 1 && !new File(w.base, "Documents/FadRec").exists(), "then everything is moved and FadRec is gone");
    }

    static void check(boolean ok, String what) {
        if (ok) { passed++; } else { failed++; System.out.println("FAIL: " + what); }
    }
}
