package com.fadcam.ui.settingshub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The lines of the hub's list, in order: what the adapter draws. Pure, so the grouping is tested
 * on a JVM.
 *
 * <p>Browsing, each section is its heading and then its rows, the rows joined into one group whose
 * outer corners are round and inner corners nearly square. Searching, there are no headings: each
 * hit stands alone and names its own section above its title, best match first.</p>
 */
public final class SettingsLines {

    private SettingsLines() {}

    /** Where a row sits in its group, which decides its corners. */
    public enum Shape { ONLY, FIRST, MIDDLE, LAST }

    public static final class Line {
        /** Index of the section, into the catalog's list. */
        public final int section;
        /** The row, or null for a section heading. */
        public final SettingsKey key;
        public final Shape shape;
        /** A search hit: the row says which section it lives in. */
        public final boolean namesSection;

        Line(int section, SettingsKey key, Shape shape, boolean namesSection) {
            this.section = section;
            this.key = key;
            this.shape = shape;
            this.namesSection = namesSection;
        }

        public boolean isHeader() {
            return key == null;
        }
    }

    /** Every section's heading, then its rows. A section with no rows (none on this device) is left out. */
    public static List<Line> browse(List<List<SettingsKey>> sections) {
        List<Line> out = new ArrayList<>();
        for (int s = 0; s < sections.size(); s++) {
            List<SettingsKey> rows = sections.get(s);
            if (rows.isEmpty()) continue;
            out.add(new Line(s, null, Shape.ONLY, false));
            for (int i = 0; i < rows.size(); i++) {
                out.add(new Line(s, rows.get(i), shape(i, rows.size()), false));
            }
        }
        return out;
    }

    /** The hits, in the order given (best first), each one its own group. */
    public static List<Line> results(List<SettingsKey> hits, Map<SettingsKey, Integer> sectionOf) {
        List<Line> out = new ArrayList<>(hits.size());
        for (SettingsKey k : hits) {
            Integer s = sectionOf.get(k);
            out.add(new Line(s == null ? -1 : s, k, Shape.ONLY, true));
        }
        return out;
    }

    /** Index of the section the line at {@code position} belongs to, or -1. */
    public static int sectionAt(List<Line> lines, int position) {
        if (position < 0 || position >= lines.size()) return -1;
        return lines.get(position).section;
    }

    /** Position of a section's heading, or -1 when it is not shown. */
    public static int headerOf(List<Line> lines, int section) {
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.isHeader() && l.section == section) return i;
        }
        return -1;
    }

    static Shape shape(int index, int count) {
        if (count == 1) return Shape.ONLY;
        if (index == 0) return Shape.FIRST;
        if (index == count - 1) return Shape.LAST;
        return Shape.MIDDLE;
    }
}
