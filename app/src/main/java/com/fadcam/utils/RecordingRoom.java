package com.fadcam.utils;

/**
 * How much room a recording needs, and when to stop it (work-loss audit F5). Nothing checked the
 * free space before: a recording on a nearly full phone started anyway, hit a write error somewhere
 * in the middle and went on recording into nothing. Plain arithmetic, no Android, so the JVM
 * harness tests it ({@code tools/jvm-harness/run-recording-room.sh}).
 *
 * <p>Free space the phone cannot report is {@code -1}: that is never a reason to refuse or to stop.</p>
 */
public final class RecordingRoom {

    /** Always left free: a stop in time finishes the file properly, and other apps write too. */
    public static final long STOP_MARGIN_BYTES = 64L * 1024 * 1024;
    /** A recording is not started unless it could run this long above the margin. */
    public static final int MIN_SECONDS_TO_START = 60;
    /** How often free space is looked at while recording. */
    public static final long CHECK_EVERY_MS = 3000;

    private RecordingRoom() {}

    /** Bytes one second of recording takes at these bitrates (bits per second). */
    public static long bytesPerSecond(long videoBitsPerSecond, long audioBitsPerSecond) {
        return Math.max(0L, videoBitsPerSecond + audioBitsPerSecond) / 8L;
    }

    /** The least free space a recording may start with. */
    public static long neededToStart(long bytesPerSecond) {
        return STOP_MARGIN_BYTES + MIN_SECONDS_TO_START * Math.max(0L, bytesPerSecond);
    }

    /** True when there is room to start (or when the free space is not known). */
    public static boolean enoughToStart(long freeBytes, long bytesPerSecond) {
        return freeBytes < 0 || freeBytes >= neededToStart(bytesPerSecond);
    }

    /** True when the recording must stop now, while there is still room to finish the file. */
    public static boolean mustStop(long freeBytes) {
        return freeBytes >= 0 && freeBytes < STOP_MARGIN_BYTES;
    }
}
