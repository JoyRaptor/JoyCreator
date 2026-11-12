package com.fadcam.ui.faditor.export;

/**
 * How much room an export needs, and whether the phone clearly lacks it (code review 2026-11-11,
 * E15: nothing checked free space, so a long export ran for an hour and then failed on a full phone).
 *
 * <p>Pure arithmetic with no Android types, so it is tested on the PC. The numbers are
 * deliberately generous: this is a refusal for "clearly cannot hold it", never a precise size.</p>
 */
final class ExportSpace {

    private ExportSpace() { }

    /** From this length an export is made in 5-minute parts plus a join (ExportManager does the same). */
    static final long CHUNKED_FROM_MS = 720_000L;

    /** Room kept free beyond the estimate: the phone needs some to keep working. */
    static final long RESERVE_BYTES = 64L * 1024 * 1024;

    /** A single picture, or a clip too short to measure. */
    static final long FLOOR_BYTES = 24L * 1024 * 1024;

    private static final long AUDIO_BPS = 320_000L;

    /** Bits per second assumed for the picture at each quality; HIGH leaves the encoder on its default. */
    static long videoBitsPerSecond(String qualityName) {
        if ("LOW".equals(qualityName)) return 4_000_000L;
        if ("MEDIUM".equals(qualityName)) return 8_000_000L;
        return 20_000_000L;
    }

    /**
     * Bytes the export may need on the phone at its most: the finished file with a quarter to
     * spare, and for a long export the parts AND the joined file, so twice that.
     *
     * @param durationMs  length of what is exported
     * @param qualityName LOW, MEDIUM or HIGH (anything else counts as HIGH)
     * @param audioOnly   sound only
     * @param singleFrame one picture
     */
    static long estimateBytes(long durationMs, String qualityName, boolean audioOnly,
                              boolean singleFrame) {
        if (singleFrame) return FLOOR_BYTES;
        long ms = Math.max(0L, durationMs);
        long bps = audioOnly ? AUDIO_BPS : videoBitsPerSecond(qualityName) + AUDIO_BPS;
        long bytes = ms / 1000L * bps / 8L;
        bytes += bytes / 4L;
        if (!audioOnly && ms >= CHUNKED_FROM_MS) bytes *= 2L;
        return Math.max(FLOOR_BYTES, bytes);
    }

    /** True when {@code freeBytes} clearly cannot hold {@code neededBytes} plus the reserve. */
    static boolean shortOfSpace(long neededBytes, long freeBytes) {
        if (freeBytes < 0L) return false;   // unknown: let the export try
        return freeBytes < neededBytes + RESERVE_BYTES;
    }

    /** How much more room to ask the person to make, in bytes. */
    static long missingBytes(long neededBytes, long freeBytes) {
        return Math.max(0L, neededBytes + RESERVE_BYTES - Math.max(0L, freeBytes));
    }
}
