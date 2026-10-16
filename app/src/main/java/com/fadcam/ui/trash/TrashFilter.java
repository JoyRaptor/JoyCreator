package com.fadcam.ui.trash;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which deleted things a chip shows, newest deleted first (what you just lost is what you most
 * likely want back), and how many each chip holds. Pure: tested on a JVM.
 */
public final class TrashFilter {

    private TrashFilter() {}

    /** @param chip null for All */
    public static List<TrashEntry> apply(List<TrashEntry> all, TrashKind chip) {
        List<TrashEntry> out = new ArrayList<>();
        for (TrashEntry e : all) {
            if (e != null && (chip == null || e.bucket == chip)) out.add(e);
        }
        Collections.sort(out, (a, b) -> {
            int c = Long.compare(b.deletedAt, a.deletedAt);
            return c != 0 ? c : a.key.compareTo(b.key);
        });
        return out;
    }

    /** Per chip, every chip present (zero when empty) so the chips never move. */
    public static Map<TrashKind, Integer> counts(List<TrashEntry> all) {
        Map<TrashKind, Integer> out = new EnumMap<>(TrashKind.class);
        for (TrashKind k : TrashKind.values()) out.put(k, 0);
        for (TrashEntry e : all) {
            if (e != null) out.put(e.bucket, out.get(e.bucket) + 1);
        }
        return out;
    }

    /** How many a Trash rule of {@code keepMillis} would delete right now. */
    public static int expiringUnder(List<TrashEntry> all, long now, long keepMillis) {
        int n = 0;
        for (TrashEntry e : all) {
            if (e != null && TrashExpiry.expiredUnder(e.deletedAt, now, keepMillis)) n++;
        }
        return n;
    }
}
