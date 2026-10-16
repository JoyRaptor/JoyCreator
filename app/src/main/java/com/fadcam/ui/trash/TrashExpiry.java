package com.fadcam.ui.trash;

/**
 * How long a deleted thing has left before it goes for good. Pure: tested on a JVM.
 *
 * <p>Days count UP, the way phones' own Recently Deleted do: something deleted a minute ago under a
 * 30-day rule has "30 days left", and the last day reads in hours. A thing with three days or fewer
 * left is {@link #nearing}: the card says so in the careful amber, and nothing else on it changes.</p>
 */
public final class TrashExpiry {

    public static final long HOUR = 60L * 60 * 1000;
    public static final long DAY = 24 * HOUR;
    /** "Nearing": this much time or less left. */
    public static final long NEARING = 3 * DAY;

    public enum Unit {
        /** The Trash keeps things until they are deleted by hand. */
        KEPT,
        DAYS,
        HOURS,
        /** Under an hour, or already past due (it goes the next time the Trash is swept). */
        SOON
    }

    public final Unit unit;
    /** Days or hours, for those units; 0 otherwise. */
    public final int n;
    public final boolean nearing;

    private TrashExpiry(Unit unit, int n, boolean nearing) {
        this.unit = unit;
        this.n = n;
        this.nearing = nearing;
    }

    /** @param keepMillis how long the Trash keeps things, or negative when it keeps them until deleted */
    public static TrashExpiry of(long deletedAt, long now, long keepMillis) {
        if (keepMillis < 0) return new TrashExpiry(Unit.KEPT, 0, false);
        long left = deletedAt + keepMillis - now;
        boolean nearing = left <= NEARING;
        if (left > DAY) return new TrashExpiry(Unit.DAYS, (int) ceil(left, DAY), nearing);
        if (left > HOUR) return new TrashExpiry(Unit.HOURS, (int) ceil(left, HOUR), nearing);
        return new TrashExpiry(Unit.SOON, 0, true);
    }

    /**
     * True when a Trash rule of {@code keepMillis} would delete this now: the same test both
     * {@code TrashManager.autoDeleteExpiredItems} and {@code JoyFolder.emptyOlderThan} apply.
     */
    public static boolean expiredUnder(long deletedAt, long now, long keepMillis) {
        if (keepMillis < 0) return false;
        if (keepMillis == 0) return true;
        return now - deletedAt > keepMillis;
    }

    private static long ceil(long a, long b) {
        return (a + b - 1) / b;
    }
}
