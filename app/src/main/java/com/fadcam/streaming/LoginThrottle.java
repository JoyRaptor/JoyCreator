package com.fadcam.streaming;

import java.util.HashMap;
import java.util.Map;

/**
 * Slows down guessing of the Remote Studio pairing code (B3, 11-15). A six-digit code is a million
 * guesses; after five wrong tries one address must wait, 30 seconds at first and doubling with
 * every further miss, up to 15 minutes. A right answer clears that address. Plain Java, no
 * Android, so the JVM harness can test it.
 */
public final class LoginThrottle {

    static final int FREE_TRIES = 5;
    static final long FIRST_WAIT_MS = 30_000L;
    static final long MAX_WAIT_MS = 15 * 60_000L;

    private static final class Entry {
        int misses;
        long lockedUntil;
    }

    private final Map<String, Entry> byAddress = new HashMap<>();

    /** Milliseconds this address must still wait before it may try again; 0 when it may try now. */
    public synchronized long waitMs(String address, long nowMs) {
        Entry e = byAddress.get(key(address));
        if (e == null) return 0L;
        return Math.max(0L, e.lockedUntil - nowMs);
    }

    /** A wrong code from this address. */
    public synchronized void failed(String address, long nowMs) {
        Entry e = byAddress.get(key(address));
        if (e == null) {
            e = new Entry();
            byAddress.put(key(address), e);
        }
        e.misses++;
        if (e.misses >= FREE_TRIES) {
            int over = e.misses - FREE_TRIES;
            long wait = FIRST_WAIT_MS << Math.min(over, 10);
            e.lockedUntil = nowMs + Math.min(wait, MAX_WAIT_MS);
        }
    }

    /** The right code from this address: forget its misses. */
    public synchronized void succeeded(String address) {
        byAddress.remove(key(address));
    }

    private static String key(String address) {
        return address == null ? "?" : address;
    }
}
