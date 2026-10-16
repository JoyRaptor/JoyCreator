import com.fadcam.ui.library.LibraryClassifier.Capture;
import com.fadcam.ui.library.LibraryKind;
import com.fadcam.ui.trash.TrashClassifier;
import com.fadcam.ui.trash.TrashEntry;
import com.fadcam.ui.trash.TrashExpiry;
import com.fadcam.ui.trash.TrashFilter;
import com.fadcam.ui.trash.TrashKind;
import com.fadcam.ui.trash.TrashSelection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tests for the Recently Deleted screen's pure logic (app/src/main/java/com/fadcam/ui/trash): which
 * chip a deleted thing sits under, its card name, days left and the amber line, the chip counts,
 * the order, and the selection surviving a reload. Plain Java, no Gradle: see run.sh.
 */
public final class TrashTests {

    private static int passed, failed;
    private static final long H = TrashExpiry.HOUR, D = TrashExpiry.DAY;
    private static final long NOW = 1791849600000L;
    private static final long KEEP30 = 30 * D;

    public static void main(String[] args) {
        run("kind: folder things by ending and maker", TrashTests::folderKinds);
        run("kind: the app's own trash is recordings and photos", TrashTests::appKinds);
        run("chips: five buckets, exports and clips are recordings", TrashTests::buckets);
        run("name: no ending, never empty", TrashTests::names);
        run("key: matches TrashItem equality (file + time)", TrashTests::keys);
        run("expiry: days count up, last day in hours", TrashExpiry_days());
        run("expiry: nearing at three days or fewer", TrashTests::nearing);
        run("expiry: kept forever, and past due", TrashTests::keptAndPast);
        run("expiry: a new rule deletes what the helpers would", TrashTests::expiredUnder);
        run("filter: chip, newest deleted first", TrashTests::filter);
        run("counts: every chip present, zero when empty", TrashTests::counts);
        run("selection: toggle, cover, survive a reload", TrashTests::selection);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void folderKinds() {
        eq(LibraryKind.PROJECTS, TrashClassifier.kindOf("Lecture 3.joyproject", true, false, Capture.NONE));
        eq(LibraryKind.DRAWINGS, TrashClassifier.kindOf("Dino.joybrush", true, false, Capture.NONE));
        eq(LibraryKind.SPRITE_SHEETS, TrashClassifier.kindOf("Run.sprite.json", true, false, Capture.NONE));
        eq(LibraryKind.DOCUMENTS, TrashClassifier.kindOf("crossfades.json", true, false, Capture.NONE));
        eq(LibraryKind.IMAGES, TrashClassifier.kindOf("z1.png", true, false, Capture.NONE));
        eq(LibraryKind.PHOTOS, TrashClassifier.kindOf("Photo 2026-10-15.jpg", true, false, Capture.PHOTO));
        eq(LibraryKind.RECORDINGS, TrashClassifier.kindOf("Screen recording 1.mp4", true, false, Capture.RECORDING));
        eq(LibraryKind.CLIPS, TrashClassifier.kindOf("from a friend.mp4", true, false, Capture.NONE));
    }

    static void appKinds() {
        eq(LibraryKind.RECORDINGS, TrashClassifier.kindOf("FadCam_2026.mp4", false, false, Capture.NONE));
        eq(LibraryKind.EXPORTS, TrashClassifier.kindOf("Merged 2026.mp4", false, false, Capture.STUDIO_OUTPUT));
        eq(LibraryKind.PHOTOS, TrashClassifier.kindOf("snap.jpg", false, false, Capture.NONE));
        eq(LibraryKind.PHOTOS, TrashClassifier.kindOf("frame_0001.webp", false, true, Capture.NONE));
    }

    static void buckets() {
        eq(TrashKind.PROJECTS, TrashKind.of(LibraryKind.PROJECTS));
        eq(TrashKind.DRAWINGS, TrashKind.of(LibraryKind.DRAWINGS));
        eq(TrashKind.RECORDINGS, TrashKind.of(LibraryKind.EXPORTS));
        eq(TrashKind.RECORDINGS, TrashKind.of(LibraryKind.CLIPS));
        eq(TrashKind.PHOTOS, TrashKind.of(LibraryKind.IMAGES));
        eq(TrashKind.OTHER, TrashKind.of(LibraryKind.SPRITE_SHEETS));
        eq(TrashKind.OTHER, TrashKind.of(LibraryKind.DOCUMENTS));
        eq(TrashKind.OTHER, TrashKind.of(LibraryKind.PROJECT_MEDIA));
        eq(TrashKind.OTHER, TrashKind.of(null));
        eq(5, TrashKind.values().length);
    }

    static void names() {
        eq("Lecture 3", TrashClassifier.shownName("Lecture 3.joyproject"));
        eq("Run", TrashClassifier.shownName("Run.sprite.json"));
        eq("crossfades", TrashClassifier.shownName("crossfades.json"));
        eq("README", TrashClassifier.shownName("README"));
        eq("", TrashClassifier.shownName(null));
        TrashEntry e = TrashClassifier.entry("joyfolder/2026 z1.png", "z1.png", NOW, 10, true, false, Capture.NONE);
        eq("z1", e.look.name);
        eq(TrashKind.PHOTOS, e.bucket);
        check(e.inFolder, "in folder");
        eq(NOW, e.look.modified);
    }

    static void keys() {
        eq("VideoRecordings/a.mp4|5", TrashClassifier.keyOf("VideoRecordings/a.mp4", 5));
        check(!TrashClassifier.keyOf("a", 1).equals(TrashClassifier.keyOf("a", 2)), "same file, other time");
    }

    static Body TrashExpiry_days() {
        return () -> {
            TrashExpiry x = TrashExpiry.of(NOW, NOW + 60_000, KEEP30);
            eq(TrashExpiry.Unit.DAYS, x.unit);
            eq(30, x.n);
            check(!x.nearing, "fresh is not nearing");
            x = TrashExpiry.of(NOW, NOW + 27 * D, KEEP30);
            eq(3, x.n);
            x = TrashExpiry.of(NOW, NOW + 29 * D + H, KEEP30);
            eq(TrashExpiry.Unit.HOURS, x.unit);
            eq(23, x.n);
        };
    }

    static void nearing() {
        check(TrashExpiry.of(NOW, NOW + 27 * D, KEEP30).nearing, "exactly 3 days left");
        check(!TrashExpiry.of(NOW, NOW + 27 * D - 1, KEEP30).nearing, "a moment over 3 days");
        check(TrashExpiry.of(NOW, NOW + 29 * D, KEEP30).nearing, "1 day left");
    }

    static void keptAndPast() {
        TrashExpiry k = TrashExpiry.of(NOW, NOW + 400 * D, -1);
        eq(TrashExpiry.Unit.KEPT, k.unit);
        check(!k.nearing, "kept is never amber");
        TrashExpiry p = TrashExpiry.of(NOW, NOW + 31 * D, KEEP30);
        eq(TrashExpiry.Unit.SOON, p.unit);
        check(p.nearing, "past due is amber");
    }

    static void expiredUnder() {
        check(!TrashExpiry.expiredUnder(NOW, NOW + 400 * D, -1), "never");
        check(TrashExpiry.expiredUnder(NOW, NOW, 0), "immediately takes everything");
        check(!TrashExpiry.expiredUnder(NOW, NOW + 7 * D, 7 * D), "exactly at the age stays (strict >)");
        check(TrashExpiry.expiredUnder(NOW, NOW + 7 * D + 1, 7 * D), "past the age goes");
        List<TrashEntry> all = sample();
        eq(2, TrashFilter.expiringUnder(all, NOW + 3 * D + 3, 3 * D));
    }

    static void filter() {
        List<TrashEntry> all = sample();
        eq(Arrays.asList("c", "b", "a", "d"), keys(TrashFilter.apply(all, null)));
        eq(Arrays.asList("c", "a"), keys(TrashFilter.apply(all, TrashKind.RECORDINGS)));
        eq(0, TrashFilter.apply(all, TrashKind.DRAWINGS).size());
    }

    static void counts() {
        Map<TrashKind, Integer> c = TrashFilter.counts(sample());
        eq(5, c.size());
        eq(2, c.get(TrashKind.RECORDINGS));
        eq(1, c.get(TrashKind.PROJECTS));
        eq(1, c.get(TrashKind.OTHER));
        eq(0, c.get(TrashKind.DRAWINGS));
    }

    static void selection() {
        TrashSelection s = new TrashSelection();
        check(s.isEmpty(), "starts empty");
        check(s.toggle("a"), "chosen");
        check(s.toggle("b"), "chosen");
        check(!s.toggle("a"), "unchosen");
        eq(1, s.size());
        s.addAll(Arrays.asList("a", "c"));
        check(s.coversAll(Arrays.asList("a", "c")), "covers shown");
        check(!s.coversAll(new ArrayList<>()), "nothing shown is never covered");
        s.retainOnly(Arrays.asList("a", "z"));
        eq(Arrays.asList("a"), s.keys());
        s.clear();
        check(s.isEmpty(), "cleared");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    static List<TrashEntry> sample() {
        List<TrashEntry> all = new ArrayList<>();
        all.add(entry("a", TrashKind.RECORDINGS, NOW + 2));
        all.add(entry("b", TrashKind.PROJECTS, NOW + 3));
        all.add(entry("c", TrashKind.RECORDINGS, NOW + 4));
        all.add(entry("d", TrashKind.OTHER, NOW - 5 * D));
        return all;
    }

    static TrashEntry entry(String key, TrashKind bucket, long at) {
        return new TrashEntry(key, bucket, null, at, false);
    }

    static List<String> keys(List<TrashEntry> es) {
        List<String> out = new ArrayList<>();
        for (TrashEntry e : es) out.add(e.key);
        return out;
    }

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
}
