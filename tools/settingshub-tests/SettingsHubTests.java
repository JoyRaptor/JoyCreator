import com.fadcam.ui.settingshub.SettingsKey;
import com.fadcam.ui.settingshub.SettingsLines;
import com.fadcam.ui.settingshub.SettingsSearch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests for the Settings hub's pure logic (app/src/main/java/com/fadcam/ui/settingshub), run on the
 * hub's REAL data: the catalog is read from SettingsCatalog.java and the words from
 * strings_settings_hub.xml, so a search test fails if a title or a synonym changes underneath it.
 *
 * <p>It also checks, adversarially, that nothing the classic Settings tab offered went missing: every
 * row in fragment_settings_home.xml (and every row SettingsHomeFragment wires) is taken over by a hub
 * row or listed as left out on purpose, every hub row is in the catalog once, and every hub row has a
 * destination. Plain Java, no Gradle: see run.sh.</p>
 */
public final class SettingsHubTests {

    private static int passed, failed;
    private static final Path ROOT = Paths.get(".");
    private static final String HUB = "app/src/main/java/com/fadcam/ui/settingshub/";

    // The catalog as the app builds it: sections in order, each with its rows and their words.
    private static final List<String> sectionTitles = new ArrayList<>();
    private static final List<List<SettingsKey>> sections = new ArrayList<>();
    private static final Map<SettingsKey, Integer> sectionOf = new EnumMap<>(SettingsKey.class);
    private static final List<SettingsSearch.Entry> entries = new ArrayList<>();
    private static SettingsSearch search;

    public static void main(String[] args) throws Exception {
        loadCatalog();
        run("coverage: every classic row is in the hub or left out on purpose", SettingsHubTests::legacyLayoutCovered);
        run("coverage: every row the classic code wires is in the hub", SettingsHubTests::legacyCodeCovered);
        run("coverage: every hub mapping names a real classic row", SettingsHubTests::noStaleMapping);
        run("coverage: every key is in the catalog exactly once", SettingsHubTests::catalogComplete);
        run("coverage: every key has a destination", SettingsHubTests::destinationsComplete);
        run("coverage: every row's words exist in strings_settings_hub.xml", SettingsHubTests::stringsExist);
        run("search: nothing typed finds nothing", SettingsHubTests::emptyQuery);
        run("search: other names find the row (flashlight, trash, bitrate)", SettingsHubTests::synonyms);
        run("search: case and accents do not matter", SettingsHubTests::caseAndAccents);
        run("search: every word must match", SettingsHubTests::everyWord);
        run("search: a plural finds its singular", SettingsHubTests::plurals);
        run("search: a section's name finds its rows", SettingsHubTests::sectionWords);
        run("search: title start beats title word beats names beats description", SettingsHubTests::ranking);
        run("search: ties keep the hub's own order", SettingsHubTests::ties);
        run("lines: browse is heading then a joined group", SettingsHubTests::browseShapes);
        run("lines: an empty section is left out", SettingsHubTests::emptySection);
        run("lines: results stand alone and name their section", SettingsHubTests::resultLines);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ── coverage ──────────────────────────────────────────────────────────────────

    static void legacyLayoutCovered() throws Exception {
        String xml = read("app/src/main/res/layout/fragment_settings_home.xml");
        Set<String> rows = new TreeSet<>();
        Matcher tag = Pattern.compile("<(\\w+)([^>]*)>", Pattern.DOTALL).matcher(xml);
        while (tag.find()) {
            String attrs = tag.group(2);
            boolean row = attrs.contains("@style/SettingsGroupRow")
                    || (tag.group(1).equals("include") && attrs.contains("@layout/item_mini_app_card"));
            Matcher id = Pattern.compile("android:id=\"@\\+id/(\\w+)\"").matcher(attrs);
            if (row && id.find()) rows.add(id.group(1));
        }
        check(rows.size() >= 29, "found the classic rows (" + rows.size() + ")");
        for (String r : rows) {
            check(SettingsKey.forLegacyRow(r) != null || SettingsKey.LEGACY_LEFT_OUT.contains(r),
                    "classic row " + r + " has no hub row and is not listed as left out");
        }
    }

    static void legacyCodeCovered() throws Exception {
        String java = read("app/src/main/java/com/fadcam/ui/SettingsHomeFragment.java");
        Matcher m = Pattern.compile("(?:bindRow|setupMiniCard)\\(root, R\\.id\\.(group_\\w+)"
                + "|findViewById\\(R\\.id\\.(group_(?:onboarding|auto_update|debug_logging))\\)").matcher(java);
        Set<String> wired = new TreeSet<>();
        while (m.find()) wired.add(m.group(1) != null ? m.group(1) : m.group(2));
        check(wired.size() >= 29, "found the wired rows (" + wired.size() + ")");
        for (String r : wired) {
            check(SettingsKey.forLegacyRow(r) != null || SettingsKey.LEGACY_LEFT_OUT.contains(r),
                    "wired classic row " + r + " is not covered");
        }
    }

    static void noStaleMapping() throws Exception {
        String xml = read("app/src/main/res/layout/fragment_settings_home.xml");
        for (SettingsKey k : SettingsKey.values()) {
            if (k.legacyRow != null) check(xml.contains("@+id/" + k.legacyRow + "\""), k + " maps to missing " + k.legacyRow);
        }
        for (String r : SettingsKey.LEGACY_LEFT_OUT) check(xml.contains("@+id/" + r + "\""), "left-out " + r + " is not a classic row");
    }

    static void catalogComplete() {
        List<SettingsKey> all = new ArrayList<>();
        for (List<SettingsKey> s : sections) all.addAll(s);
        eq(SettingsKey.values().length, all.size());
        eq(EnumSet.allOf(SettingsKey.class), EnumSet.copyOf(all));
        eq(SettingsKey.CLASSIC_SETTINGS, all.get(all.size() - 1));   // the way back is last
    }

    static void destinationsComplete() throws Exception {
        String java = read(HUB + "SettingsDestinations.java");
        Set<String> cases = new LinkedHashSet<>();
        Matcher m = Pattern.compile("case (\\w+):").matcher(java);
        while (m.find()) cases.add(m.group(1));
        for (SettingsKey k : SettingsKey.values()) check(cases.contains(k.name()), k + " has no destination");
    }

    static void stringsExist() {
        for (SettingsSearch.Entry e : entries) check(e.key != null, "entry has a key");
        eq(SettingsKey.values().length, entries.size());
        eq(sections.size(), sectionTitles.size());
    }

    // ── search ────────────────────────────────────────────────────────────────────

    static void emptyQuery() {
        eq(0, search.find("").size());
        eq(0, search.find("   ").size());
        eq(0, search.find(null).size());
    }

    static void synonyms() {
        eq(SettingsKey.TORCH, first("flashlight"));
        eq(SettingsKey.RECENTLY_DELETED, first("trash"));
        eq(SettingsKey.CAMERA_VIDEO, first("bitrate"));
        eq(SettingsKey.QR_SCANNER, first("barcode"));
        eq(SettingsKey.SMART_DETECTION, first("forensics"));
        eq(SettingsKey.SETTINGS_BACKUP, first("import"));
    }

    static void caseAndAccents() {
        eq(SettingsKey.CAMERA_VIDEO, first("CAMÉRA"));
        eq(SettingsKey.TORCH, first("  tOrCh "));
    }

    static void everyWord() {
        eq(Arrays.asList(SettingsKey.CAMERA_VIDEO), search.find("camera bitrate"));
        eq(0, search.find("camera zzzz").size());
        List<SettingsKey> sd = search.find("sd card");
        check(sd.contains(SettingsKey.JOY_FOLDER) && sd.contains(SettingsKey.RECORDING_LOCATION), "sd card: both storage rows");
    }

    static void plurals() {
        eq(SettingsKey.APPEARANCE, first("themes"));
        eq(SettingsKey.NOTIFICATIONS, first("notifications"));
        eq(SettingsKey.SHORTCUTS, first("widgets"));
    }

    static void sectionWords() {
        List<SettingsKey> labs = search.find("labs");
        check(labs.containsAll(Arrays.asList(SettingsKey.MOTION_LAB, SettingsKey.SMART_DETECTION, SettingsKey.REMOTE)),
                "labs finds its three rows: " + labs);
        eq(SettingsKey.MOTION_LAB, labs.get(0));   // its title starts with "Lab"
    }

    static void ranking() {
        eq(SettingsKey.TORCH, first("torch"));             // title start, over Shortcuts' description
        check(search.find("torch").contains(SettingsKey.SHORTCUTS), "torch also finds Shortcuts");
        eq(SettingsKey.UPDATE_CHECK, first("update"));     // a title word, over names and descriptions
        eq(SettingsKey.SECURITY, first("lock"));
        eq(SettingsKey.SHOW_INTRO, first("intro"));
        eq(SettingsKey.CLASSIC_SETTINGS, first("classic"));

        List<SettingsSearch.Entry> made = Arrays.asList(
                new SettingsSearch.Entry(SettingsKey.AUDIO, "Other", "about sound", "S", ""),
                new SettingsSearch.Entry(SettingsKey.TORCH, "Other", "", "S", "sound"),
                new SettingsSearch.Entry(SettingsKey.SPACE, "Big sound", "", "S", ""),
                new SettingsSearch.Entry(SettingsKey.RATE, "Soundboard", "", "S", ""));
        eq(Arrays.asList(SettingsKey.RATE, SettingsKey.SPACE, SettingsKey.TORCH, SettingsKey.AUDIO),
                new SettingsSearch(made).find("sound"));
    }

    static void ties() {
        List<SettingsSearch.Entry> made = Arrays.asList(
                new SettingsSearch.Entry(SettingsKey.SPACE, "Alpha", "", "S", "same"),
                new SettingsSearch.Entry(SettingsKey.AUDIO, "Beta", "", "S", "same"),
                new SettingsSearch.Entry(SettingsKey.TORCH, "Gamma", "", "S", "same"));
        eq(Arrays.asList(SettingsKey.SPACE, SettingsKey.AUDIO, SettingsKey.TORCH), new SettingsSearch(made).find("same"));
    }

    // ── lines ─────────────────────────────────────────────────────────────────────

    static void browseShapes() {
        List<SettingsLines.Line> lines = SettingsLines.browse(Arrays.asList(
                Arrays.asList(SettingsKey.JOY_FOLDER, SettingsKey.SPACE, SettingsKey.RECENTLY_DELETED),
                Arrays.asList(SettingsKey.APPEARANCE)));
        eq(6, lines.size());
        check(lines.get(0).isHeader() && lines.get(0).section == 0, "heading first");
        eq(SettingsLines.Shape.FIRST, lines.get(1).shape);
        eq(SettingsLines.Shape.MIDDLE, lines.get(2).shape);
        eq(SettingsLines.Shape.LAST, lines.get(3).shape);
        check(lines.get(4).isHeader() && lines.get(4).section == 1, "second heading");
        eq(SettingsLines.Shape.ONLY, lines.get(5).shape);
        eq(4, SettingsLines.headerOf(lines, 1));
        eq(1, SettingsLines.sectionAt(lines, 5));
        eq(-1, SettingsLines.sectionAt(lines, 99));
        for (SettingsLines.Line l : lines) check(!l.namesSection, "browsing never names the section");
    }

    static void emptySection() {
        List<SettingsLines.Line> lines = SettingsLines.browse(Arrays.asList(
                Arrays.<SettingsKey>asList(), Arrays.asList(SettingsKey.ABOUT)));
        eq(2, lines.size());
        eq(-1, SettingsLines.headerOf(lines, 0));
        eq(0, SettingsLines.headerOf(lines, 1));
        // On a real device every section has rows, so every chip has somewhere to jump.
        List<SettingsLines.Line> real = SettingsLines.browse(sections);
        for (int s = 0; s < sections.size(); s++) check(SettingsLines.headerOf(real, s) >= 0, "section " + s + " shown");
    }

    static void resultLines() {
        List<SettingsLines.Line> lines = SettingsLines.results(search.find("torch"), sectionOf);
        check(!lines.isEmpty(), "torch has results");
        for (SettingsLines.Line l : lines) {
            check(!l.isHeader() && l.namesSection && l.shape == SettingsLines.Shape.ONLY, "a result stands alone");
            eq(sectionOf.get(l.key), l.section);
        }
    }

    // ── loading the real catalog and words ────────────────────────────────────────

    static void loadCatalog() throws Exception {
        Map<String, String> strings = strings(read("app/src/main/res/values/strings_settings_hub.xml"));
        // The "AI and Joybot" row keeps its words in a file of its own (Joybot lane, 10-28).
        strings.putAll(strings(read("app/src/main/res/values/strings_joybot_settings.xml")));
        String java = read(HUB + "SettingsCatalog.java");
        Matcher m = Pattern.compile(
                "new Section\\(R\\.string\\.(\\w+),\\s*R\\.string\\.\\w+,\\s*\"\\w+\""
                + "|(?:row|new Row)\\(SettingsKey\\.(\\w+),\\s*\"\\w+\",\\s*R\\.string\\.(\\w+),\\s*R\\.string\\.(\\w+),\\s*R\\.string\\.(\\w+)")
                .matcher(java);
        while (m.find()) {
            if (m.group(1) != null) {
                sectionTitles.add(need(strings, m.group(1)));
                sections.add(new ArrayList<>());
                continue;
            }
            SettingsKey k = SettingsKey.valueOf(m.group(2));
            int s = sections.size() - 1;
            sections.get(s).add(k);
            sectionOf.put(k, s);
            entries.add(new SettingsSearch.Entry(k, need(strings, m.group(3)), need(strings, m.group(4)),
                    sectionTitles.get(s), need(strings, m.group(5))));
        }
        search = new SettingsSearch(entries);
    }

    static Map<String, String> strings(String xml) {
        Map<String, String> out = new HashMap<>();
        Matcher m = Pattern.compile("<string name=\"(\\w+)\">(.*?)</string>", Pattern.DOTALL).matcher(xml);
        while (m.find()) out.put(m.group(1), unescape(m.group(2)));
        return out;
    }

    /** What aapt does to the few escapes these strings use. */
    static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'u' && i + 4 < s.length()) {
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                } else {
                    out.append(n == 'n' ? '\n' : n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString().replace("&amp;", "&");
    }

    static String need(Map<String, String> strings, String name) {
        String s = strings.get(name);
        if (s == null) throw new AssertionError("missing string " + name);
        return s;
    }

    static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(ROOT.resolve(rel)), StandardCharsets.UTF_8);
    }

    static SettingsKey first(String q) {
        List<SettingsKey> hits = search.find(q);
        if (hits.isEmpty()) throw new AssertionError("nothing found for \"" + q + "\"");
        return hits.get(0);
    }

    // ── harness ───────────────────────────────────────────────────────────────────

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
