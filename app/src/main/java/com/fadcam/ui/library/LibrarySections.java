package com.fadcam.ui.library;

import java.util.ArrayList;
import java.util.List;

/**
 * Newest-first lists read better in a few quiet bands: Today, Yesterday, This week, This month,
 * Earlier. Pure, so the banding is tested on a JVM; the words come from resources.
 *
 * <p>A name-sorted list gets no bands: an alphabet split into dates would be noise.</p>
 */
public final class LibrarySections {

    private LibrarySections() {}

    public enum Band { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, EARLIER }

    private static final long DAY = 24L * 60 * 60 * 1000;

    /** One line of the grid: a band heading (with how many follow) or an item. */
    public static final class Row {
        public final Band band;
        public final int bandCount;
        public final LibraryItem item;

        private Row(Band band, int bandCount, LibraryItem item) {
            this.band = band;
            this.bandCount = bandCount;
            this.item = item;
        }

        public boolean isHeader() {
            return item == null;
        }
    }

    /** Which band a time falls in, counted from the start of today (local midnight). */
    public static Band bandOf(long modified, long startOfToday) {
        if (modified >= startOfToday) return Band.TODAY;
        if (modified >= startOfToday - DAY) return Band.YESTERDAY;
        if (modified >= startOfToday - 6 * DAY) return Band.THIS_WEEK;
        if (modified >= startOfToday - 29 * DAY) return Band.THIS_MONTH;
        return Band.EARLIER;
    }

    /**
     * The rows for an already filtered and sorted list. With {@code banded} false, items only.
     * Bands appear in order and only when something is in them.
     */
    public static List<Row> rows(List<LibraryItem> sorted, boolean banded, long startOfToday) {
        List<Row> out = new ArrayList<>(sorted.size() + 5);
        if (!banded) {
            for (LibraryItem it : sorted) out.add(new Row(null, 0, it));
            return out;
        }
        int i = 0;
        while (i < sorted.size()) {
            Band band = bandOf(sorted.get(i).modified, startOfToday);
            int j = i;
            while (j < sorted.size() && bandOf(sorted.get(j).modified, startOfToday) == band) j++;
            out.add(new Row(band, j - i, null));
            for (int k = i; k < j; k++) out.add(new Row(band, 0, sorted.get(k)));
            i = j;
        }
        return out;
    }
}
