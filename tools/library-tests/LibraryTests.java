import com.fadcam.joyfolder.FileKind;
import com.fadcam.ui.library.LibraryClassifier;
import com.fadcam.ui.library.LibraryClassifier.Capture;
import com.fadcam.ui.library.LibraryFilter;
import com.fadcam.ui.library.LibraryItem;
import com.fadcam.ui.library.LibraryKind;
import com.fadcam.ui.library.LibrarySections;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tests for the Library's pure logic (app/src/main/java/com/fadcam/ui/library): which kind a
 * folder file is, what each filter keeps, the chip counts, name order, and the date bands.
 * Plain Java, no Gradle: see run.sh.
 */
public final class LibraryTests {

    private static int passed, failed;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long TODAY = 1791849600000L;   // a local midnight, any will do

    public static void main(String[] args) {
        run("kinds: work, supplies, documents by ending", LibraryTests::kindsByEnding);
        run("kinds: recordings, photos, exports, clips by maker", LibraryTests::kindsByMaker);
        run("kinds: project media copies and voiceovers are hidden", LibraryTests::kindsProjectMedia);
        run("shared in: share-in and import, not joybot", LibraryTests::sharedInOrigins);
        run("filter: All skips project media, its own chip shows it", LibraryTests::filterAllAndHidden);
        run("filter: shared in any / only / hide", LibraryTests::filterShared);
        run("filter: search every word, case and accent blind", LibraryTests::filterSearch);
        run("counts: per kind under search and shared, All total", LibraryTests::counts);
        run("sort: newest, then name in number order", LibraryTests::sort);
        run("bands: today, yesterday, week, month, earlier", LibraryTests::bands);
        run("bands: rows carry headers only when banded", LibraryTests::bandRows);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void kindsByEnding() {
        eq(LibraryKind.PROJECTS, k("Promo.joyproject", "mirror"));
        eq(LibraryKind.DRAWINGS, k("Dino walk.joybrush", null));
        eq(LibraryKind.SPRITE_SHEETS, k("Run cycle.sprite.json", null));
        eq(LibraryKind.AVATARS, k("Bunny.avatar.json", null));
        eq(LibraryKind.SUPPLIES, k("Soft pencil.brush.json", null));
        eq(LibraryKind.SUPPLIES, k("Archivo.ttf", null));
        eq(LibraryKind.SUPPLIES, k("Warm.cube", null));
        eq(LibraryKind.DOCUMENTS, k("Lecture 3.srt", null));
        eq(LibraryKind.DOCUMENTS, k("notes.json", null));
        eq(LibraryKind.OTHER, k("archive.zip", null));
    }

    static void kindsByMaker() {
        eq(LibraryKind.RECORDINGS, LibraryClassifier.kindOf("Screen recording 2026-10-13 14.32.mp4", null, Capture.RECORDING));
        eq(LibraryKind.RECORDINGS, k("take.mp4", "recording"));
        eq(LibraryKind.PHOTOS, LibraryClassifier.kindOf("Photo 2026-10-13 14.32.jpg", null, Capture.PHOTO));
        eq(LibraryKind.EXPORTS, k("Promo.mp4", "export"));
        eq(LibraryKind.EXPORTS, LibraryClassifier.kindOf("Merged 2026-10-13.mp4", null, Capture.STUDIO_OUTPUT));
        eq(LibraryKind.CLIPS, k("from whatsapp.mp4", "share-in"));
        eq(LibraryKind.CLIPS, k("dropped in.mov", null));
        eq(LibraryKind.IMAGES, k("cat.png", "share-in"));
        eq(LibraryKind.IMAGES, k("joybot idea.png", "joybot"));
        eq(LibraryKind.AUDIO, k("song.mp3", "share-in"));
        eq(LibraryKind.RECORDINGS, k("mic take.m4a", "recording"));
    }

    static void kindsProjectMedia() {
        eq(LibraryKind.PROJECT_MEDIA, k("Lecture 3 image.jpg", "mirror"));
        eq(LibraryKind.PROJECT_MEDIA, k("Lecture 3 clip.mp4", "mirror"));
        eq(LibraryKind.PROJECT_MEDIA, k("Voiceover 1.wav", "voiceover"));
        check(LibraryKind.PROJECT_MEDIA.hiddenByDefault(), "project media hidden by default");
        check(!LibraryKind.IMAGES.hiddenByDefault(), "images shown");
    }

    static void sharedInOrigins() {
        check(LibraryClassifier.isSharedIn("share-in"), "share-in");
        check(LibraryClassifier.isSharedIn("import"), "import");
        check(!LibraryClassifier.isSharedIn("joybot"), "joybot is made here");
        check(!LibraryClassifier.isSharedIn("mirror"), "mirror");
        check(!LibraryClassifier.isSharedIn(null), "null");
    }

    static void filterAllAndHidden() {
        List<LibraryItem> all = sample();
        LibraryFilter f = new LibraryFilter();
        List<LibraryItem> shown = f.apply(all);
        for (LibraryItem it : shown) check(it.kind != LibraryKind.PROJECT_MEDIA, "All hides project media");
        eq(all.size() - 1, shown.size());
        f.setKind(LibraryKind.PROJECT_MEDIA);
        eq(1, f.apply(all).size());
        f.setKind(LibraryKind.IMAGES);
        eq(2, f.apply(all).size());
    }

    static void filterShared() {
        List<LibraryItem> all = sample();
        LibraryFilter f = new LibraryFilter();
        f.setShared(LibraryFilter.Shared.ONLY);
        List<LibraryItem> only = f.apply(all);
        eq(2, only.size());
        for (LibraryItem it : only) check(it.sharedIn, "only shared");
        f.setShared(LibraryFilter.Shared.HIDE);
        for (LibraryItem it : f.apply(all)) check(!it.sharedIn, "none shared");
        eq(LibraryFilter.Shared.ONLY, LibraryFilter.Shared.ANY.next());
        eq(LibraryFilter.Shared.HIDE, LibraryFilter.Shared.ONLY.next());
        eq(LibraryFilter.Shared.ANY, LibraryFilter.Shared.HIDE.next());
    }

    static void filterSearch() {
        List<LibraryItem> all = sample();
        LibraryFilter f = new LibraryFilter();
        f.setQuery("  CAFE  ");
        eq(Arrays.asList("Café sign"), names(f.apply(all)));
        f.setQuery("run restore");          // one word in the name, one in its project
        eq(Arrays.asList("Run cycle"), names(f.apply(all)));
        f.setQuery("nothing like this");
        eq(0, f.apply(all).size());
        check(f.narrowed(), "a query narrows");
        f.setQuery("");
        check(!f.narrowed(), "empty query does not");
    }

    static void counts() {
        List<LibraryItem> all = sample();
        LibraryFilter f = new LibraryFilter();
        Map<LibraryKind, Integer> c = f.counts(all);
        eq(2, c.get(LibraryKind.IMAGES));
        eq(1, c.get(LibraryKind.PROJECT_MEDIA));
        eq(0, c.get(LibraryKind.AVATARS));
        eq(all.size() - 1, LibraryFilter.allCount(c));
        f.setKind(LibraryKind.PROJECTS);              // the kind chosen never changes the counts
        eq(c, f.counts(all));
        f.setShared(LibraryFilter.Shared.ONLY);
        Map<LibraryKind, Integer> s = f.counts(all);
        eq(1, s.get(LibraryKind.IMAGES));
        eq(1, s.get(LibraryKind.CLIPS));
        eq(2, LibraryFilter.allCount(s));
    }

    static void sort() {
        List<LibraryItem> all = new ArrayList<>();
        all.add(item("Take 10", LibraryKind.CLIPS, TODAY + 3, false));
        all.add(item("take 2", LibraryKind.CLIPS, TODAY + 1, false));
        all.add(item("Take 1", LibraryKind.CLIPS, TODAY + 2, false));
        all.add(item("Ábaco", LibraryKind.CLIPS, TODAY, false));
        LibraryFilter f = new LibraryFilter();
        eq(Arrays.asList("Take 10", "Take 1", "take 2", "Ábaco"), names(f.apply(all)));
        f.setSort(LibraryFilter.Sort.NAME);
        eq(Arrays.asList("Ábaco", "Take 1", "take 2", "Take 10"), names(f.apply(all)));
        eq(0, LibraryFilter.naturalCompare("Take 02", "Take 2"));
        check(LibraryFilter.naturalCompare("a", "a1") < 0, "shorter first");
    }

    static void bands() {
        eq(LibrarySections.Band.TODAY, LibrarySections.bandOf(TODAY + 5, TODAY));
        eq(LibrarySections.Band.YESTERDAY, LibrarySections.bandOf(TODAY - 1, TODAY));
        eq(LibrarySections.Band.YESTERDAY, LibrarySections.bandOf(TODAY - DAY, TODAY));
        eq(LibrarySections.Band.THIS_WEEK, LibrarySections.bandOf(TODAY - DAY - 1, TODAY));
        eq(LibrarySections.Band.THIS_WEEK, LibrarySections.bandOf(TODAY - 6 * DAY, TODAY));
        eq(LibrarySections.Band.THIS_MONTH, LibrarySections.bandOf(TODAY - 7 * DAY, TODAY));
        eq(LibrarySections.Band.EARLIER, LibrarySections.bandOf(TODAY - 40 * DAY, TODAY));
    }

    static void bandRows() {
        List<LibraryItem> sorted = new ArrayList<>();
        sorted.add(item("a", LibraryKind.CLIPS, TODAY + 10, false));
        sorted.add(item("b", LibraryKind.CLIPS, TODAY + 5, false));
        sorted.add(item("c", LibraryKind.CLIPS, TODAY - 3 * DAY, false));
        List<LibrarySections.Row> rows = LibrarySections.rows(sorted, true, TODAY);
        eq(5, rows.size());
        check(rows.get(0).isHeader() && rows.get(0).band == LibrarySections.Band.TODAY, "today first");
        eq(2, rows.get(0).bandCount);
        check(rows.get(3).isHeader() && rows.get(3).band == LibrarySections.Band.THIS_WEEK, "then this week");
        eq(3, LibrarySections.rows(sorted, false, TODAY).size());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    static LibraryKind k(String name, String origin) {
        return LibraryClassifier.kindOf(name, origin, Capture.NONE);
    }

    static List<LibraryItem> sample() {
        List<LibraryItem> all = new ArrayList<>();
        all.add(item("Restore video test", LibraryKind.PROJECTS, TODAY + 9, false));
        LibraryItem sheet = item("Run cycle", LibraryKind.SPRITE_SHEETS, TODAY + 8, false);
        sheet.parentName = "Restore video test";
        all.add(sheet);
        all.add(item("Café sign", LibraryKind.IMAGES, TODAY + 7, true));
        all.add(item("Joybot idea", LibraryKind.IMAGES, TODAY + 6, false));
        all.add(item("From a friend", LibraryKind.CLIPS, TODAY + 5, true));
        all.add(item("Screen recording 2026-10-13", LibraryKind.RECORDINGS, TODAY + 4, false));
        all.add(item("Lecture 3 image", LibraryKind.PROJECT_MEDIA, TODAY + 3, false));
        return all;
    }

    static LibraryItem item(String name, LibraryKind kind, long modified, boolean shared) {
        LibraryItem it = new LibraryItem(kind + ":" + name, kind, name, modified, -1, shared);
        it.fileKind = FileKind.OTHER;
        return it;
    }

    static List<String> names(List<LibraryItem> items) {
        List<String> out = new ArrayList<>();
        for (LibraryItem it : items) out.add(it.name);
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
