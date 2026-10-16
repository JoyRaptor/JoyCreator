package com.fadcam.ui.library;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the Library is showing right now, and the pure logic that decides it. No Android, so every
 * rule here is tested on a plain JVM ({@code tools/library-tests}).
 *
 * <ul>
 *   <li><b>Kind</b>: one kind, or null for All. All never includes a {@link LibraryKind#hiddenByDefault}
 *       kind; those are reached only through their own chip.</li>
 *   <li><b>Shared in</b>: any, only, or hide (owner ruling b, both directions).</li>
 *   <li><b>Search</b>: every word typed must appear in the name or its project's name; case and
 *       accents do not matter, so "cafe" finds "Café".</li>
 *   <li><b>Sort</b>: newest first, or by name with numbers in number order ("Take 2" before
 *       "Take 10").</li>
 * </ul>
 */
public final class LibraryFilter {

    public enum Shared {
        ANY, ONLY, HIDE;

        /** One button cycles the three: any, only, hide, any. */
        public Shared next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public enum Sort { NEWEST, NAME }

    private LibraryKind kind;
    private Shared shared = Shared.ANY;
    private Sort sort = Sort.NEWEST;
    private String query = "";
    private String[] words = new String[0];

    public LibraryKind kind() { return kind; }
    public Shared shared() { return shared; }
    public Sort sort() { return sort; }
    public String query() { return query; }

    public void setKind(LibraryKind k) { kind = k; }
    public void setShared(Shared s) { shared = s == null ? Shared.ANY : s; }
    public void setSort(Sort s) { sort = s == null ? Sort.NEWEST : s; }

    public void setQuery(String q) {
        query = q == null ? "" : q.trim();
        String folded = fold(query);
        words = folded.isEmpty() ? new String[0] : folded.split("\\s+");
    }

    /** True when anything narrows the list beyond the chosen kind (search or the shared chip). */
    public boolean narrowed() {
        return words.length > 0 || shared != Shared.ANY;
    }

    // ── matching ────────────────────────────────────────────────────────────────

    /** Shared-in and search only: what the per-kind counts are made of. */
    public boolean passesSharedAndSearch(LibraryItem it) {
        if (shared == Shared.ONLY && !it.sharedIn) return false;
        if (shared == Shared.HIDE && it.sharedIn) return false;
        if (words.length == 0) return true;
        String hay = fold(it.name) + " " + fold(it.parentName);
        for (String w : words) if (!hay.contains(w)) return false;
        return true;
    }

    public boolean matches(LibraryItem it) {
        if (kind == null) {
            if (it.kind.hiddenByDefault()) return false;
        } else if (it.kind != kind) {
            return false;
        }
        return passesSharedAndSearch(it);
    }

    /** The items to show, filtered and sorted. Never modifies {@code all}. */
    public List<LibraryItem> apply(List<LibraryItem> all) {
        List<LibraryItem> out = new ArrayList<>();
        for (LibraryItem it : all) if (matches(it)) out.add(it);
        Collections.sort(out, comparator(sort));
        return out;
    }

    /** How many each chip would show under the current shared-in and search settings. */
    public Map<LibraryKind, Integer> counts(List<LibraryItem> all) {
        Map<LibraryKind, Integer> out = new EnumMap<>(LibraryKind.class);
        for (LibraryKind k : LibraryKind.values()) out.put(k, 0);
        for (LibraryItem it : all) {
            if (passesSharedAndSearch(it)) out.put(it.kind, out.get(it.kind) + 1);
        }
        return out;
    }

    /** The All chip's count: everything that passes, except the hidden-by-default kinds. */
    public static int allCount(Map<LibraryKind, Integer> counts) {
        int n = 0;
        for (Map.Entry<LibraryKind, Integer> e : counts.entrySet()) {
            if (!e.getKey().hiddenByDefault()) n += e.getValue();
        }
        return n;
    }

    // ── ordering ────────────────────────────────────────────────────────────────

    public static Comparator<LibraryItem> comparator(Sort sort) {
        if (sort == Sort.NAME) {
            return (a, b) -> {
                int c = naturalCompare(a.name, b.name);
                return c != 0 ? c : Long.compare(b.modified, a.modified);
            };
        }
        return (a, b) -> {
            int c = Long.compare(b.modified, a.modified);
            return c != 0 ? c : naturalCompare(a.name, b.name);
        };
    }

    /** Case- and accent-blind, and digits compare as numbers: "Take 2" &lt; "Take 10". */
    public static int naturalCompare(String a, String b) {
        String x = fold(a), y = fold(b);
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            char cx = x.charAt(i), cy = y.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int si = i, sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) i++;
                while (j < y.length() && Character.isDigit(y.charAt(j))) j++;
                String nx = stripZeros(x.substring(si, i)), ny = stripZeros(y.substring(sj, j));
                if (nx.length() != ny.length()) return nx.length() - ny.length();
                int c = nx.compareTo(ny);
                if (c != 0) return c;
            } else {
                if (cx != cy) return cx - cy;
                i++;
                j++;
            }
        }
        return (x.length() - i) - (y.length() - j);
    }

    private static String stripZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') k++;
        return digits.substring(k);
    }

    /** Lower case, accents removed: what search and name order both compare. */
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
}
