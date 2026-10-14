import com.fadcam.joyfolder.ConsolidatePlan;
import com.fadcam.joyfolder.FileJoyFs;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.Fingerprint;
import com.fadcam.joyfolder.FolderIndex;
import com.fadcam.joyfolder.FolderRoles;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;
import com.fadcam.joyfolder.Suggestions;
import com.fadcam.joyfolder.UsageMap;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

/**
 * Tests for the Joy Creator folder core (studiokit/src/main/java/com/fadcam/joyfolder).
 * Plain Java, no Gradle: see run.sh. Every test runs against a real temporary folder.
 */
public final class JoyFolderTests {

    private static int passed, failed;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    // 2026-10-13 14:32:00 UTC
    private static final long T = 1791901920000L;

    public static void main(String[] args) throws Exception {
        run("names: illegal characters, dots, length", JoyFolderTests::namesSanitize);
        run("names: code-like detection", JoyFolderTests::namesCodeLike);
        run("names: share-in and recordings", JoyFolderTests::namesShareIn);
        run("names: unique ignores case", JoyFolderTests::namesUnique);
        run("kinds: endings and bases", JoyFolderTests::kinds);
        run("placement: the three rules", JoyFolderTests::placement);
        run("roles: adopt by name, drop when gone", JoyFolderTests::roles);
        run("fingerprint: content identity, sampling", JoyFolderTests::fingerprint);
        run("index: hidden skipped, moved file found again", JoyFolderTests::index);
        run("index: plugin folder is one item", JoyFolderTests::indexPlugin);
        run("atomic write and crash recovery", JoyFolderTests::atomic);
        run("trash: trash, restore, clash, expiry", JoyFolderTests::trash);
        run("trash: listing newest first, empty", JoyFolderTests::trashList);
        run("consolidate: box, exports, shared, recordings", JoyFolderTests::consolidate);
        run("consolidate: leave shared, name clashes", JoyFolderTests::consolidateClash);
        run("consolidate: gathering twice never nests", JoyFolderTests::consolidateTwice);
        run("usage: unused and shared", JoyFolderTests::usage);
        run("suggestions: earned folders", JoyFolderTests::suggestions);
        run("suggestions: recordings known by name, moved into Recordings", JoyFolderTests::suggestionsByName);
        run("folder: prepareDir and addFile", JoyFolderTests::prepareAndAdd);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ── names ────────────────────────────────────────────────────────────

    static void namesSanitize() {
        eq("Lecture 3 part 2.mp4", ReadableNames.sanitize("Lecture 3: part/2.mp4").replace("  ", " "));
        eq("a b", ReadableNames.sanitize("a\tb"));
        eq("hidden", ReadableNames.sanitize("..hidden"));
        eq("trailing", ReadableNames.sanitize("trailing..."));
        eq("Untitled", ReadableNames.sanitize("  ?? "));
        String longName = ReadableNames.sanitize(repeat("x", 300) + ".brush.json");
        check(longName.length() <= 120, "long name capped: " + longName.length());
        check(longName.endsWith(".brush.json"), "ending kept when capped");
    }

    static void namesCodeLike() {
        for (String s : new String[]{"1000004521", "3f2a9c1e-7b2d-4c1a-9e8f-0a1b2c3d4e5f", "a1b2c3d4e5f6a7b8",
                "image", "IMAGE (3)", "download", "file_12", "untitled", "Xk29fj39Dk2lq0Pz7Ww1"}) {
            check(ReadableNames.looksCodeLike(s), "code-like: " + s);
        }
        for (String s : new String[]{"Dino walk", "IMG_20261013_143200", "Screenshot_20261013-143200",
                "Intro music", "PXL_20261013_143200123", "My cat"}) {
            check(!ReadableNames.looksCodeLike(s), "human: " + s);
        }
    }

    static void namesShareIn() {
        eq("From ChatGPT 2026-10-13 14.32.png", ReadableNames.forShareIn("1000004521.png", "ChatGPT", T, UTC, null));
        eq("Shared 2026-10-13 14.32.png", ReadableNames.forShareIn("image", null, T, UTC, ".png"));
        eq("My cat.jpg", ReadableNames.forShareIn("My cat.jpg", "Gallery", T, UTC, ".jpg"));
        eq("Soft pencil.brush.json", ReadableNames.forShareIn("Soft pencil.brush.json", "Drive", T, UTC, null));
        eq("Screen recording 2026-10-13 14.32.mp4", ReadableNames.recording("Screen recording", T, UTC, ".mp4"));
    }

    static void namesUnique() {
        Set<String> taken = new HashSet<>(Arrays.asList("photo.jpg", "photo 2.jpg", "soft pencil.brush.json"));
        eq("Photo 3.jpg", ReadableNames.unique(taken, "Photo.jpg"));   // keeps the case the user typed
        eq("Soft pencil 2.brush.json", ReadableNames.unique(taken, "Soft pencil.brush.json"));
        eq("new.png", ReadableNames.unique(taken, "new.png"));
    }

    static void kinds() {
        eq(FileKind.BRUSH, FileKind.of("Soft pencil.brush.json"));
        eq(FileKind.DOCUMENT, FileKind.of("notes.json"));
        eq(FileKind.SPRITE_SHEET, FileKind.of("Star guy.sprite.json"));
        eq(FileKind.LOOK, FileKind.of("Teal orange.CUBE"));
        eq(FileKind.PROJECT, FileKind.of("Lecture 3.joyproject"));
        eq(FileKind.VIDEO, FileKind.of("Take 1.MP4"));
        eq(FileKind.OTHER, FileKind.of("README"));
        eq(FileKind.OTHER, FileKind.of(".json"));
        eq("Soft pencil", FileKind.baseOf("Soft pencil.brush.json"));
        eq(".brush.json", FileKind.endingOf("Soft pencil.brush.json"));
        eq("Lecture 3 720p", FileKind.baseOf("Lecture 3 720p.mp4"));
        eq("Brushes", FileKind.BRUSH.suppliesFolder);
    }

    // ── placement ────────────────────────────────────────────────────────

    static void placement() {
        FolderRoles none = new FolderRoles();
        // Rule 1: belongs to a work -> where the work lives.
        eq("", dir(Placement.What.SHARE_IN, "Lecture 3.joyproject", none));
        eq("Client A", dir(Placement.What.SHARE_IN, "Client A/Promo.joyproject", none));
        eq("Client A", dir(Placement.What.IMPORT, "Client A/Promo.joyproject", none));
        eq("Client A", dir(Placement.What.EXPORT, "Client A/Promo.joyproject", none));
        eq("Client A", dir(Placement.What.VOICEOVER, "Client A/Promo.joyproject", none));
        // Rule 2: belongs to nothing -> its folder if any, else the top.
        eq("", dir(Placement.What.SHARE_IN, null, none));
        eq("", dir(Placement.What.RECORDING, null, none));
        FolderRoles some = new FolderRoles();
        some.set(FolderRoles.Role.ASSETS, "Assets");
        some.set(FolderRoles.Role.RECORDINGS, "Takes");
        some.set(FolderRoles.Role.EXPORTS, "Exports");
        eq("Assets", dir(Placement.What.SHARE_IN, null, some));
        eq("Takes", dir(Placement.What.RECORDING, null, some));
        // ...but a share-in with a work open still follows the work, Assets or not (owner's rule).
        eq("Client A", dir(Placement.What.SHARE_IN, "Client A/Promo.joyproject", some));
        // Rule 3: a folder made to collect a kind wins for that kind.
        eq("Exports", dir(Placement.What.EXPORT, "Client A/Promo.joyproject", some));
        eq("Takes", dir(Placement.What.VOICEOVER, "Client A/Promo.joyproject", some));
        // New work lands in the folder being browsed.
        eq("Client B", Placement.dirFor(Placement.What.NEW_WORK, null, "Client B", null, some).dir);
        // Supplies always have a home, made on first use.
        Placement.Result s = Placement.dirFor(Placement.What.SUPPLY, null, null, FileKind.BRUSH, none);
        eq("Supplies/Brushes", s.dir);
        eq(FolderRoles.Role.SUPPLIES, s.createsRole);
        Placement.Result f = Placement.dirFor(Placement.What.FINISHED, null, null, null, none);
        eq("Finished", f.dir);
        eq(FolderRoles.Role.FINISHED, f.createsRole);
    }

    static String dir(Placement.What what, String work, FolderRoles roles) {
        return Placement.dirFor(what, work, null, null, roles).dir;
    }

    static void roles() throws IOException {
        FileJoyFs fs = tempFs();
        fs.mkdirs("recordings");
        fs.mkdirs("Client A");
        FolderRoles r = new FolderRoles();
        check(r.reconcile(fs), "adopting changes something");
        eq("recordings", r.get(FolderRoles.Role.RECORDINGS));
        eq(null, r.get(FolderRoles.Role.ASSETS));
        // Renamed away: role dropped, things go to the top again.
        check(fs.rename("recordings", "Takes"), "rename");
        check(r.reconcile(fs), "drop changes something");
        eq(null, r.get(FolderRoles.Role.RECORDINGS));
        // Assigned by the user to any name, kept across reconciles.
        r.set(FolderRoles.Role.RECORDINGS, "Takes");
        check(!r.reconcile(fs), "stable");
        eq("Takes", FolderRoles.fromJson(r.toJson()).get(FolderRoles.Role.RECORDINGS));
    }

    // ── fingerprint and index ────────────────────────────────────────────

    static void fingerprint() throws IOException {
        FileJoyFs fs = tempFs();
        write(fs, "a.txt", "hello");
        write(fs, "b.txt", "hello");
        write(fs, "c.txt", "hellO");
        eq(Fingerprint.of(fs, "a.txt"), Fingerprint.of(fs, "b.txt"));
        check(!Fingerprint.of(fs, "a.txt").equals(Fingerprint.of(fs, "c.txt")), "different content");
        eq(5L, Fingerprint.sizeOf(Fingerprint.of(fs, "a.txt")));
        byte[] big = new byte[1_000_000];
        new Random(7).nextBytes(big);
        writeBytes(fs, "big.bin", big);
        big[500_000] ^= 1;   // inside the middle sample
        writeBytes(fs, "big2.bin", big);
        check(!Fingerprint.of(fs, "big.bin").equals(Fingerprint.of(fs, "big2.bin")), "middle sample sees a change");
        eq(-1L, Fingerprint.sizeOf("nonsense"));
    }

    static void index() throws IOException {
        FileJoyFs fs = tempFs();
        JoyFolder jf = JoyFolder.open(fs);
        write(fs, "Take 1.mp4", "take-one-bytes");
        write(fs, "Take 2.mp4", "take-two-bytes");   // same size, different bytes
        write(fs, "notes.txt.joytmp", "half");
        write(fs, ".hidden.txt", "x");
        jf.index.scan(fs);
        List<String> paths = new ArrayList<>();
        for (FolderIndex.Item it : jf.index.items()) paths.add(it.path);
        eq(Arrays.asList("Take 1.mp4", "Take 2.mp4"), paths);
        String fp = jf.index.fingerprintOf(fs, "Take 1.mp4");
        // The user moves and renames it in a file manager.
        fs.mkdirs("Recordings");
        check(fs.move("Take 1.mp4", "Recordings"), "move");
        check(fs.rename("Recordings/Take 1.mp4", "Opening.mp4"), "rename");
        jf.index.scan(fs);
        eq("Recordings/Opening.mp4", jf.index.find(fs, "Take 1.mp4", fp));
        eq("Take 2.mp4", jf.index.find(fs, "Take 2.mp4", jf.index.fingerprintOf(fs, "Take 2.mp4")));
        // A different file now sitting at the old path is not mistaken for it.
        write(fs, "Take 1.mp4", "imposter-bytes");
        jf.index.scan(fs);
        eq("Recordings/Opening.mp4", jf.index.find(fs, "Take 1.mp4", fp));
        // Gone for good.
        fs.delete("Recordings/Opening.mp4");
        jf.index.scan(fs);
        eq(null, jf.index.find(fs, "Take 1.mp4", fp));
        // Survives a save and reload.
        jf.saveIndex();
        JoyFolder again = JoyFolder.open(fs);
        check(again.index.get("Take 2.mp4") != null, "index reloaded");
    }

    static void indexPlugin() throws IOException {
        FileJoyFs fs = tempFs();
        write(fs, "Supplies/Plugins/Beat sync/plugin.json", "{}");
        write(fs, "Supplies/Plugins/Beat sync/beat-sync.js", "x");
        write(fs, "Supplies/Brushes/Chalk.brush.json", "{}");
        FolderIndex idx = new FolderIndex();
        idx.scan(fs);
        List<String> paths = new ArrayList<>();
        for (FolderIndex.Item it : idx.items()) paths.add(it.path + (it.plugin ? " [plugin]" : ""));
        eq(Arrays.asList("Supplies/Brushes/Chalk.brush.json", "Supplies/Plugins/Beat sync [plugin]"), paths);
    }

    // ── atomic writes ────────────────────────────────────────────────────

    static void atomic() throws IOException {
        FileJoyFs fs = tempFs();
        JoyFolder jf = JoyFolder.open(fs);
        jf.writeAtomic("Lecture 3.joyproject", "v1".getBytes(StandardCharsets.UTF_8));
        jf.writeAtomic("Lecture 3.joyproject", "v2".getBytes(StandardCharsets.UTF_8));
        eq("v2", read(fs, "Lecture 3.joyproject"));
        eq(Collections.singletonList("Lecture 3.joyproject"), topFiles(fs));

        // Crash after the old copy was set aside but before the new one moved in: the temp is complete.
        write(fs, "A.joyproject.joyold", "old");
        write(fs, "A.joyproject.joytmp", "new");
        // Crash after the new one moved in, before the old copy was deleted.
        write(fs, "B.joyproject", "new");
        write(fs, "B.joyproject.joyold", "old");
        // Crash while writing the temp of an existing file: keep the existing file.
        write(fs, "C.joyproject", "kept");
        write(fs, "C.joyproject.joytmp", "half");
        // Crash after setting the old copy aside with no temp left: put the old one back.
        write(fs, "D.joyproject.joyold", "old");
        JoyFolder.open(fs);
        eq("new", read(fs, "A.joyproject"));
        eq("new", read(fs, "B.joyproject"));
        eq("kept", read(fs, "C.joyproject"));
        eq("old", read(fs, "D.joyproject"));
        eq(Arrays.asList("A.joyproject", "B.joyproject", "C.joyproject", "D.joyproject", "Lecture 3.joyproject"),
                topFiles(fs));
    }

    // ── trash ────────────────────────────────────────────────────────────

    static void trash() throws IOException {
        FileJoyFs fs = tempFs();
        JoyFolder jf = JoyFolder.open(fs);
        write(fs, "Client A/Promo.joyproject", "promo");
        String name = jf.trash("Client A/Promo.joyproject", T);
        check(fs.stat("Client A/Promo.joyproject") == null, "gone from where it was");
        check(fs.stat(JoyFolder.TRASH + "/" + name) != null, "in Trash");
        eq(Collections.singletonList(name), jf.trashed());
        eq("Client A/Promo.joyproject", jf.restore(name));
        eq("promo", read(fs, "Client A/Promo.joyproject"));
        eq(0, jf.trashed().size());
        // Restoring when the name has been reused puts it beside, never over.
        String again = jf.trash("Client A/Promo.joyproject", T);
        write(fs, "Client A/Promo.joyproject", "newer");
        eq("Client A/Promo 2.joyproject", jf.restore(again));
        eq("newer", read(fs, "Client A/Promo.joyproject"));
        // Two items with the same name trashed in the same minute both survive.
        write(fs, "x/a.png", "1");
        write(fs, "y/a.png", "2");
        String t1 = jf.trash("x/a.png", T);
        String t2 = jf.trash("y/a.png", T);
        check(!t1.equals(t2), "distinct trash names");
        // Expiry.
        eq(0, jf.emptyOld(T + 29L * 24 * 3600 * 1000));
        eq(2, jf.emptyOld(T + 31L * 24 * 3600 * 1000));
        eq(0, jf.trashed().size());
    }

    static void trashList() throws IOException {
        FileJoyFs fs = tempFs();
        JoyFolder jf = JoyFolder.open(fs);
        write(fs, "Old.joyproject", "1");
        write(fs, "Client A/New.png", "2");
        jf.trash("Old.joyproject", T);
        jf.trash("Client A/New.png", T + 60_000);
        List<JoyFolder.TrashItem> items = jf.trashItems();
        eq(2, items.size());
        eq("New.png", items.get(0).originalName());       // newest first
        eq("Client A/New.png", items.get(0).from);
        eq(T + 60_000, items.get(0).at);
        eq(1L, jf.trashSize(items.get(0).name));
        // The user's own Trash setting: anything older than a minute goes, the newest stays.
        eq(1, jf.emptyOlderThan(T + 90_000, 60_000));
        eq(1, jf.trashItems().size());
        check(jf.deleteFromTrash(items.get(0).name), "deleted one for good");
        check(!jf.deleteFromTrash(items.get(0).name), "already gone");
        eq(-1L, jf.trashSize(items.get(0).name));
        write(fs, "Again.png", "3");
        jf.trash("Again.png", T);
        write(fs, "More.png", "4");
        jf.trash("More.png", T);
        eq(2, jf.emptyTrash());
        eq(0, jf.trashItems().size());
        eq(0, fs.list(JoyFolder.TRASH).size());
    }

    // ── consolidate ──────────────────────────────────────────────────────

    static void consolidate() {
        FolderRoles roles = new FolderRoles();
        roles.set(FolderRoles.Role.RECORDINGS, "Recordings");
        UsageMap usage = new UsageMap();
        String work = "Lecture 3.joyproject";
        List<String> used = Arrays.asList("Board.png", "Recordings/Take 4.mp4", "Logo.png");
        for (String f : used) usage.add(work, f);
        usage.add("Lecture 2.joyproject", "Logo.png");
        List<String> all = Arrays.asList(work, "Board.png", "Logo.png", "Lecture 3.mp4", "Lecture 3 720p.mp4",
                "Lecture 2.joyproject", "Lecture 30 notes.mp4", "Recordings/Take 4.mp4", "Rain.wav");
        Set<String> dirs = new HashSet<>(Collections.singletonList("Recordings"));

        ConsolidatePlan.Options opts = new ConsolidatePlan.Options();
        ConsolidatePlan p = ConsolidatePlan.plan(work, used, all, dirs, usage, roles, opts);
        eq("Lecture 3", p.boxDir);
        check(!p.boxExists, "box is new");
        eq(Arrays.asList(
                "Lecture 3.joyproject -> Lecture 3/Lecture 3.joyproject",
                "Board.png -> Lecture 3/Board.png",
                "Lecture 3.mp4 -> Lecture 3/Lecture 3.mp4",
                "Lecture 3 720p.mp4 -> Lecture 3/Lecture 3 720p.mp4",
                "Logo.png -> Assets/Logo.png"), moveStrings(p));
        eq(Collections.singletonList("Logo.png"), p.sharedFiles);
        eq(Collections.singletonList("Recordings/Take 4.mp4"), p.recordingsLeft);
        check(p.createsAssets, "makes Assets");

        opts.moveRecordings = true;
        ConsolidatePlan p2 = ConsolidatePlan.plan(work, used, all, dirs, usage, roles, opts);
        check(moveStrings(p2).contains("Recordings/Take 4.mp4 -> Lecture 3/Take 4.mp4"), "recordings come along when ticked");
    }

    static void consolidateClash() {
        FolderRoles roles = new FolderRoles();
        roles.set(FolderRoles.Role.ASSETS, "Assets");
        UsageMap usage = new UsageMap();
        String work = "Client A/Promo.joyproject";
        usage.add(work, "Client A/a.png");
        usage.add(work, "b/a.png");
        usage.add(work, "Assets/Music.mp3");
        usage.add("Other.joyproject", "Assets/Music.mp3");
        List<String> all = Arrays.asList(work, "Client A/a.png", "b/a.png", "Client A/Promo/a.png", "Assets/Music.mp3");
        Set<String> dirs = new HashSet<>(Arrays.asList("Client A", "Client A/Promo", "b", "Assets"));
        ConsolidatePlan.Options opts = new ConsolidatePlan.Options();
        opts.shared = ConsolidatePlan.SharedChoice.LEAVE;
        List<String> used = Arrays.asList("Client A/a.png", "b/a.png", "Assets/Music.mp3");
        ConsolidatePlan p = ConsolidatePlan.plan(work, used, all, dirs, usage, roles, opts);
        eq("Client A/Promo", p.boxDir);
        check(p.boxExists, "existing box reused");
        eq(Arrays.asList(
                "Client A/Promo.joyproject -> Client A/Promo/Promo.joyproject",
                "Client A/a.png -> Client A/Promo/a 2.png",
                "b/a.png -> Client A/Promo/a 3.png"), moveStrings(p));
        eq(Collections.singletonList("Assets/Music.mp3"), p.sharedFiles);
        check(!p.createsAssets, "left where it is");
    }

    static void consolidateTwice() {
        FolderRoles roles = new FolderRoles();
        UsageMap usage = new UsageMap();
        String work = "Lecture 3/Lecture 3.joyproject";
        usage.add(work, "Lecture 3/Board.png");
        usage.add(work, "Late.png");
        List<String> all = Arrays.asList(work, "Lecture 3/Board.png", "Late.png");
        Set<String> dirs = new HashSet<>(Collections.singletonList("Lecture 3"));
        ConsolidatePlan p = ConsolidatePlan.plan(work, Arrays.asList("Lecture 3/Board.png", "Late.png"),
                all, dirs, usage, roles, new ConsolidatePlan.Options());
        eq("Lecture 3", p.boxDir);
        eq(Collections.singletonList("Late.png -> Lecture 3/Late.png"), moveStrings(p));
    }

    static void usage() {
        UsageMap u = new UsageMap();
        u.add("A.joyproject", "x.png");
        u.add("B.joyproject", "x.png");
        u.add("A.joyproject", "y.png");
        List<String> all = Arrays.asList("A.joyproject", "x.png", "y.png", "z.png", "Supplies/Brushes/Chalk.brush.json");
        eq(Arrays.asList("z.png", "Supplies/Brushes/Chalk.brush.json"), u.unused(all));
        eq(Collections.singletonList("x.png"), u.usedByAtLeast(all, 2));
        check(u.usedByOthers("x.png", "A.joyproject"), "shared");
        check(!u.usedByOthers("y.png", "A.joyproject"), "not shared");
    }

    static void suggestions() {
        FolderIndex idx = new FolderIndex();
        for (int i = 1; i <= 19; i++) idx.put("Screen recording " + i + ".mp4", 10, 1, null, "recording");
        idx.put("Clip.mp4", 10, 1, null, null);
        FolderRoles roles = new FolderRoles();
        Set<FolderRoles.Role> dismissed = new HashSet<>();
        eq(0, Suggestions.check(idx, roles, dismissed).size());
        idx.put("Screen recording 20.mp4", 10, 1, null, "recording");
        List<Suggestions.Suggestion> s = Suggestions.check(idx, roles, dismissed);
        eq(1, s.size());
        eq(FolderRoles.Role.RECORDINGS, s.get(0).role);
        eq(20, s.get(0).files.size());
        dismissed.add(FolderRoles.Role.RECORDINGS);
        eq(0, Suggestions.check(idx, roles, dismissed).size());
        dismissed.clear();
        roles.set(FolderRoles.Role.RECORDINGS, "Recordings");
        eq(0, Suggestions.check(idx, roles, dismissed).size());
    }

    static void suggestionsByName() {
        // The recorder writes straight into the folder, so its files are known by name, not origin.
        FolderIndex idx = new FolderIndex();
        for (int i = 1; i <= 20; i++) idx.put("Camera 2026-10-13 23." + (10 + i) + ".mp4", 10, 1, null, null);
        idx.put("Old/Camera 2026-01-01 10.00.mp4", 10, 1, null, null);
        idx.put("Recordings/Camera 2026-10-13 23.11.mp4", 10, 1, null, null);
        Set<FolderRoles.Role> none = new HashSet<>();
        eq(0, Suggestions.check(idx, new FolderRoles(), none).size());
        List<Suggestions.Suggestion> s = Suggestions.check(idx, new FolderRoles(), none, n -> n.startsWith("Camera "));
        eq(1, s.size());
        eq(20, s.get(0).files.size());
        List<String> all = new ArrayList<>();
        for (FolderIndex.Item it : idx.items()) all.add(it.path);
        ConsolidatePlan p = Suggestions.plan(s.get(0), all);
        eq("Recordings", p.boxDir);
        eq(20, p.moves.size());
        boolean clashRenamed = false;
        for (ConsolidatePlan.Move m : p.moves) {
            check(m.to.startsWith("Recordings/"), "into Recordings: " + m);
            if (m.from.equals("Camera 2026-10-13 23.11.mp4")) clashRenamed = !m.to.equals("Recordings/Camera 2026-10-13 23.11.mp4");
        }
        check(clashRenamed, "a name already in Recordings gets a number");
    }

    static void prepareAndAdd() throws IOException {
        FileJoyFs fs = tempFs();
        JoyFolder jf = JoyFolder.open(fs);
        String dir = jf.prepareDir(Placement.What.SUPPLY, null, null, FileKind.BRUSH);
        eq("Supplies/Brushes", dir);
        eq("Supplies", jf.roles.get(FolderRoles.Role.SUPPLIES));
        check(fs.stat("Supplies/Brushes").dir, "folder made");
        eq("Supplies", JoyFolder.open(fs).roles.get(FolderRoles.Role.SUPPLIES));   // persisted
        String a = jf.addFile("", "photo.jpg", in("one"), "share-in");
        String b = jf.addFile("", "Photo.jpg", in("two"), "share-in");
        eq("photo.jpg", a);
        eq("Photo 2.jpg", b);
        eq("share-in", jf.index.get(b).origin);
        check(jf.index.get(b).fingerprint != null, "fingerprinted on add");
        eq("", jf.prepareDir(Placement.What.RECORDING, null, null, null));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    interface Body { void run() throws Exception; }

    static void run(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + "\n      " + t);
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static FileJoyFs tempFs() throws IOException {
        File dir = Files.createTempDirectory("joyfolder").toFile();
        dir.deleteOnExit();
        return new FileJoyFs(dir);
    }

    static void write(JoyFs fs, String rel, String text) throws IOException {
        writeBytes(fs, rel, text.getBytes(StandardCharsets.UTF_8));
    }

    static void writeBytes(JoyFs fs, String rel, byte[] b) throws IOException {
        try (OutputStream out = fs.create(rel)) {
            out.write(b);
        }
    }

    static String read(FileJoyFs fs, String rel) throws IOException {
        return new String(Files.readAllBytes(new File(fs.root(), rel).toPath()), StandardCharsets.UTF_8);
    }

    static List<String> topFiles(JoyFs fs) throws IOException {
        List<String> out = new ArrayList<>();
        for (JoyFs.Entry e : fs.list("")) if (!e.dir) out.add(e.name);
        Collections.sort(out);
        return out;
    }

    static List<String> moveStrings(ConsolidatePlan p) {
        List<String> out = new ArrayList<>();
        for (ConsolidatePlan.Move m : p.moves) out.add(m.toString());
        return out;
    }

    static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
