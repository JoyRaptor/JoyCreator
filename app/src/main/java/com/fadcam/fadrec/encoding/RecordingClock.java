package com.fadcam.fadrec.encoding;

/**
 * Single source of truth for pause/resume state and rebased presentation
 * timestamps across the dual-stream recording feature (spec Decision 3).
 *
 * <p>The screen pipeline and the raw-webcam pipeline both consult ONE
 * {@code RecordingClock} instead of each tracking pause independently — tiny
 * per-pipeline timing differences in "exactly when did we pause/resume" would
 * otherwise compound across several pause/resume cycles into a visible desync.
 * The <b>pause state</b> ({@link #paused}, {@link #totalPausedTimeNanos}) is
 * genuinely shared; each stream keeps its own first-frame baseline via a
 * {@link Stream} so both files independently start at PTS 0 but subtract the
 * <i>same</i> accumulated pause duration — giving identical effective duration
 * and identical segment boundaries.</p>
 *
 * <p>This class is an extraction of the pause/rebase math that previously lived
 * privately in {@link ScreenRecordingPipeline} (RECORDING_HANDOFF §6). The
 * {@link #primary} stream reproduces that behavior byte-for-byte, so a plain
 * screen recording (dual-stream OFF) is unaffected by the refactor.</p>
 */
public final class RecordingClock {

    /** Guards ALL mutable state (pause fields + every stream's baselines). */
    private final Object lock = new Object();

    // ── Shared pause state (Decision 3: one tracker, never two) ──
    private boolean paused = false;
    private long pauseStartTimeNanos = -1;
    private long totalPausedTimeNanos = 0;

    /** The screen pipeline's stream — preserves the pre-refactor timeline exactly. */
    private final Stream primary = new Stream();

    /**
     * Begins a fresh recording timeline: resets the pause accumulator and the
     * primary stream's baseline. Mirrors the old ScreenRecordingPipeline start
     * block (recordingStartTimeNanos = now, firstVideoTimestampNanos = -1).
     */
    public void start() {
        synchronized (lock) {
            paused = false;
            pauseStartTimeNanos = -1;
            totalPausedTimeNanos = 0;
            primary.startLocked();
        }
    }

    /** @return true while the recording is paused (no frames should be written). */
    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }

    /**
     * Marks the timeline paused. No-op if already paused. Matches the old
     * ScreenRecordingPipeline.pauseRecording() timestamp bookkeeping.
     */
    public void pause() {
        synchronized (lock) {
            if (paused) {
                return;
            }
            pauseStartTimeNanos = System.nanoTime();
            paused = true;
        }
    }

    /**
     * Resumes the timeline, folding the just-elapsed pause span into the shared
     * accumulator so subsequent PTS have no gap. No-op if not paused.
     */
    public void resume() {
        synchronized (lock) {
            if (!paused) {
                return;
            }
            if (pauseStartTimeNanos > 0) {
                totalPausedTimeNanos += (System.nanoTime() - pauseStartTimeNanos);
                pauseStartTimeNanos = -1;
            }
            paused = false;
        }
    }

    // ── Primary (screen) stream convenience delegates ──

    /** Primary-stream audio PTS (µs) — identical to the old getSynchronizedAudioTimestamp(). */
    public long audioPtsUs() {
        return primary.audioPtsUs();
    }

    /** Primary-stream video PTS (µs) — identical to the old getSynchronizedVideoTimestamp(). */
    public long videoPtsUs(long codecTimestampUs) {
        return primary.videoPtsUs(codecTimestampUs);
    }

    /**
     * Creates a secondary stream (e.g. the raw-webcam encoder) that shares this
     * clock's pause state but keeps its own first-frame baseline, so it starts
     * at PTS 0 yet stays pause-aligned with the primary stream.
     */
    public Stream newStream() {
        return new Stream();
    }

    /**
     * Per-encoder timeline baseline. Shares the enclosing clock's pause
     * accumulator; owns only its own first-video / recording-start references.
     */
    public final class Stream {
        private long recordingStartTimeNanos = -1;
        private long firstVideoTimestampNanos = -1;

        /** Sets the wall-clock baseline for this stream (call once at start). */
        public void start() {
            synchronized (lock) {
                startLocked();
            }
        }

        private void startLocked() {
            recordingStartTimeNanos = System.nanoTime();
            firstVideoTimestampNanos = -1;
        }

        /**
         * Wall-clock-based audio PTS (µs) with shared pause duration subtracted.
         * Lazily anchors the baseline on first call if {@link #start()} was not
         * invoked (preserving the old lazy-init semantics).
         */
        public long audioPtsUs() {
            synchronized (lock) {
                if (recordingStartTimeNanos == -1) {
                    recordingStartTimeNanos = System.nanoTime();
                    return 0;
                }
                long elapsedNanos = System.nanoTime() - recordingStartTimeNanos - totalPausedTimeNanos;
                return elapsedNanos / 1000L;
            }
        }

        /**
         * Video PTS (µs) rebased to this stream's first encoded frame, with the
         * shared pause duration subtracted.
         */
        public long videoPtsUs(long codecTimestampUs) {
            synchronized (lock) {
                if (firstVideoTimestampNanos == -1) {
                    firstVideoTimestampNanos = codecTimestampUs * 1000L;
                    if (recordingStartTimeNanos == -1) {
                        recordingStartTimeNanos = System.nanoTime();
                    }
                    return 0;
                }
                long videoOffsetNanos = (codecTimestampUs * 1000L) - firstVideoTimestampNanos;
                return videoOffsetNanos / 1000L - (totalPausedTimeNanos / 1000L);
            }
        }
    }
}
