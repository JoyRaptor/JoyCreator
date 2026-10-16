package com.fadcam.ui.settingshub;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finding a setting by what you would call it. Pure Java, tested on a plain JVM
 * ({@code tools/settingshub-tests}); the words themselves come from string resources.
 *
 * <ul>
 *   <li>Every word typed must be found somewhere in the row: its title, its other names
 *       (synonyms such as "flashlight" for Torch), its section, or its description.</li>
 *   <li>Case and accents never matter, and a plural finds its singular ("themes" finds Theme).</li>
 *   <li>Best first: a hit at the start of the title beats one inside it, which beats one in the
 *       other names, the section, and last the description. Ties keep the hub's own order.</li>
 * </ul>
 */
public final class SettingsSearch {

    /** One row as search sees it: the words, already in the user's language. */
    public static final class Entry {
        public final SettingsKey key;
        final String title, names, section, summary;
        final List<String> titleWords, nameWords, sectionWords, summaryWords;

        public Entry(SettingsKey key, String title, String summary, String section, String names) {
            this.key = key;
            this.title = fold(title);
            this.names = fold(names);
            this.section = fold(section);
            this.summary = fold(summary);
            titleWords = words(this.title);
            nameWords = words(this.names);
            sectionWords = words(this.section);
            summaryWords = words(this.summary);
        }
    }

    // Lower is better. A word found nowhere is a miss, and one miss rules the row out.
    static final int TITLE_START = 0, TITLE_WORD = 1, TITLE_INSIDE = 2, NAME_WORD = 3,
            SECTION_WORD = 4, SUMMARY_WORD = 5, INSIDE = 6, MISS = -1;

    private final List<Entry> entries;

    public SettingsSearch(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** The rows that match, best first. Nothing typed matches nothing: the hub shows itself then. */
    public List<SettingsKey> find(String query) {
        List<String> words = words(fold(query));
        List<SettingsKey> out = new ArrayList<>();
        if (words.isEmpty()) return out;
        List<int[]> scored = new ArrayList<>();   // {score, catalog index}
        for (int i = 0; i < entries.size(); i++) {
            int total = 0;
            for (String w : words) {
                int s = best(entries.get(i), w);
                if (s == MISS) { total = MISS; break; }
                total += s;
            }
            if (total != MISS) scored.add(new int[]{total, i});
        }
        Collections.sort(scored, (a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        for (int[] s : scored) out.add(entries.get(s[1]).key);
        return out;
    }

    /** The word as typed, or without a plural s, whichever finds more. */
    static int best(Entry e, String word) {
        int s = score(e, word);
        if (word.length() > 3 && word.endsWith("s")) {
            int t = score(e, word.substring(0, word.length() - 1));
            if (t != MISS && (s == MISS || t < s)) s = t;
        }
        return s;
    }

    static int score(Entry e, String word) {
        if (e.title.startsWith(word)) return TITLE_START;
        if (anyStarts(e.titleWords, word)) return TITLE_WORD;
        if (e.title.contains(word)) return TITLE_INSIDE;
        if (anyStarts(e.nameWords, word)) return NAME_WORD;
        if (anyStarts(e.sectionWords, word)) return SECTION_WORD;
        if (anyStarts(e.summaryWords, word)) return SUMMARY_WORD;
        if (e.names.contains(word) || e.summary.contains(word) || e.section.contains(word)) return INSIDE;
        return MISS;
    }

    private static boolean anyStarts(List<String> words, String w) {
        for (String x : words) if (x.startsWith(w)) return true;
        return false;
    }

    /** Lower case, accents removed: "Café" and "cafe" are one word. */
    public static String fold(String s) {
        if (s == null || s.isEmpty()) return "";
        String d = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(d.length());
        for (int k = 0; k < d.length(); k++) {
            char c = d.charAt(k);
            if (Character.getType(c) != Character.NON_SPACING_MARK) out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** Split on anything that is not a letter or a digit ("Wi-Fi" is "wi" and "fi"). */
    static List<String> words(String folded) {
        List<String> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= folded.length(); i++) {
            boolean letter = i < folded.length() && Character.isLetterOrDigit(folded.charAt(i));
            if (letter && start < 0) start = i;
            if (!letter && start >= 0) {
                out.add(folded.substring(start, i));
                start = -1;
            }
        }
        return out;
    }
}
