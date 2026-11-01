package com.fadcam.vislab.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The forward decoders of one video. Two layers can show one video at different speeds or
 * offsets, and a decoder only walks forward, so one shared decoder was torn down every other
 * call. Each moment asked for goes to the decoder closest behind it: layers at one moment share a
 * decoder, layers at different moments each settle on their own, and neither needs to say who
 * it is. A decoder nothing has asked for lately is let go at once (a loop's old pass).
 *
 * <p>At most {@link #MAX} decoders per video: a sixth playback head of one video takes over the
 * longest-unused one, so that many different speeds of the same video do restart decoders.
 * Pure Java: the JVM harness tests it. Not thread-safe (one per video, on the GL thread).</p>
 */
public final class VideoHeads<R> {

    public interface Readers<R> {
        @NonNull R open();

        void close(@NonNull R reader);
    }

    /** Five different moments of one video at once is already a lot of hardware decoders. */
    static final int MAX = 5;
    /** Unused for this many calls = no layer is on it now (every layer asks once a frame). */
    private static final int STALE_CALLS = MAX * 2;

    private static final class Head<R> {
        final R reader;
        long lastUs;
        long usedCall;

        Head(R reader, long lastUs) {
            this.reader = reader;
            this.lastUs = lastUs;
        }
    }

    private final Readers<R> readers;
    private final List<Head<R>> heads = new ArrayList<>();
    private long calls;

    public VideoHeads(@NonNull Readers<R> readers) {
        this.readers = readers;
    }

    /** The decoder to ask for {@code us}: never one already past it. */
    @NonNull
    public R readerFor(long us) {
        calls++;
        Head<R> best = null;
        for (Head<R> h : heads) {
            if (h.lastUs <= us && (best == null || h.lastUs > best.lastUs)) best = h;
        }
        if (best == null) best = openFor(us);
        best.lastUs = us;
        best.usedCall = calls;
        dropStale();
        return best.reader;
    }

    @NonNull
    private Head<R> openFor(long us) {
        if (heads.size() >= MAX) {
            Head<R> oldest = heads.get(0);
            for (Head<R> h : heads) if (h.usedCall < oldest.usedCall) oldest = h;
            readers.close(oldest.reader);
            heads.remove(oldest);
        }
        Head<R> h = new Head<>(readers.open(), us);
        heads.add(h);
        return h;
    }

    private void dropStale() {
        for (int i = heads.size() - 1; i >= 0; i--) {
            Head<R> h = heads.get(i);
            if (calls - h.usedCall <= STALE_CALLS) continue;
            readers.close(h.reader);
            heads.remove(i);
        }
    }

    /** {@code reader} failed: close it, the next call opens a fresh one. */
    public void drop(@Nullable R reader) {
        for (int i = heads.size() - 1; i >= 0; i--) {
            if (heads.get(i).reader != reader) continue;
            readers.close(heads.get(i).reader);
            heads.remove(i);
        }
    }

    public void closeAll() {
        for (Head<R> h : heads) readers.close(h.reader);
        heads.clear();
    }

    public int open() {
        return heads.size();
    }
}
