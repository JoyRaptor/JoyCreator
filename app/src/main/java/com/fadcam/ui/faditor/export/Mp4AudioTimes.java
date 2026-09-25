package com.fadcam.ui.faditor.export;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Presentation times of every sample in a progressive MP4's SOUND track, straight from its
 * sample table (mdhd timescale, stts durations, edts/elst start) - no sample data is read.
 *
 * <p>Why not MediaExtractor: its {@code advance()} reads each sample's bytes, so walking the
 * 48-min lecture's 129,380 audio frames took 18 s on the Note 20 (2026-09-25). The table holds
 * the same times in a few hundred KB. Returns null for anything it does not fully understand
 * (fragmented files, no sound track), and the caller falls back to MediaExtractor.</p>
 */
final class Mp4AudioTimes {

    private Mp4AudioTimes() { }

    /** Sample times in microseconds, ascending; null when not a plain MP4 with a sound track. */
    @Nullable
    static long[] read(@NonNull java.io.File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long[] moov = find(f, 0, f.length(), "moov");
            if (moov == null) return null;
            long pos = moov[0];
            while (pos + 8 <= moov[1]) {
                long[] box = header(f, pos, moov[1]);
                if (box == null) return null;
                if ("trak".equals(type(f, pos))) {
                    long[] times = soundTrack(f, box[0], box[1]);
                    if (times != null) return times;
                }
                pos = box[1];
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    @Nullable
    private static long[] soundTrack(RandomAccessFile f, long start, long end) throws IOException {
        long[] mdia = find(f, start, end, "mdia");
        if (mdia == null) return null;
        long[] hdlr = find(f, mdia[0], mdia[1], "hdlr");
        if (hdlr == null) return null;
        f.seek(hdlr[0] + 8);   // version/flags, pre_defined
        byte[] h = new byte[4];
        f.readFully(h);
        if (!"soun".equals(new String(h, java.nio.charset.StandardCharsets.US_ASCII))) return null;

        long[] mdhd = find(f, mdia[0], mdia[1], "mdhd");
        if (mdhd == null) return null;
        f.seek(mdhd[0]);
        int version = f.readUnsignedByte();
        f.seek(mdhd[0] + (version == 1 ? 20 : 12));
        long timescale = f.readInt() & 0xFFFFFFFFL;
        if (timescale <= 0) return null;

        // Edit list: the media time presentation starts at (AAC priming, typically).
        long mediaStart = 0;
        long[] edts = find(f, start, end, "edts");
        long[] elst = edts == null ? null : find(f, edts[0], edts[1], "elst");
        if (elst != null) {
            f.seek(elst[0]);
            int ev = f.readUnsignedByte();
            f.seek(elst[0] + 4);
            long entries = f.readInt() & 0xFFFFFFFFL;
            for (long i = 0; i < entries; i++) {
                long mediaTime;
                if (ev == 1) {
                    f.readLong();                 // segment duration
                    mediaTime = f.readLong();
                } else {
                    f.readInt();
                    mediaTime = f.readInt();      // signed: -1 = empty edit
                }
                f.readInt();                      // media rate
                if (mediaTime >= 0) {
                    mediaStart = mediaTime;
                    break;
                }
            }
        }

        long[] minf = find(f, mdia[0], mdia[1], "minf");
        long[] stbl = minf == null ? null : find(f, minf[0], minf[1], "stbl");
        long[] stts = stbl == null ? null : find(f, stbl[0], stbl[1], "stts");
        if (stts == null) return null;
        f.seek(stts[0] + 4);
        long entries = f.readInt() & 0xFFFFFFFFL;
        if (entries <= 0 || entries * 8 > stts[1] - stts[0]) return null;
        byte[] table = new byte[(int) (entries * 8)];
        f.readFully(table);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(table);
        long total = 0;
        for (long i = 0; i < entries; i++) {
            total += b.getInt(8 * (int) i) & 0xFFFFFFFFL;
        }
        if (total <= 0 || total > 50_000_000L) return null;
        long[] out = new long[(int) total];
        long t = 0;
        int n = 0;
        for (int i = 0; i < entries; i++) {
            long count = b.getInt(8 * i) & 0xFFFFFFFFL;
            long delta = b.getInt(8 * i + 4) & 0xFFFFFFFFL;
            for (long k = 0; k < count; k++) {
                out[n++] = (t - mediaStart) * 1_000_000L / timescale;
                t += delta;
            }
        }
        return out;
    }

    /** Payload [start, end) of the first child box of {@code want} in [from, to). */
    @Nullable
    private static long[] find(RandomAccessFile f, long from, long to, String want)
            throws IOException {
        long pos = from;
        while (pos + 8 <= to) {
            long[] box = header(f, pos, to);
            if (box == null) return null;
            if (want.equals(type(f, pos))) return box;
            pos = box[1];
        }
        return null;
    }

    /** {payload start, box end} of the box at {@code pos}. */
    @Nullable
    private static long[] header(RandomAccessFile f, long pos, long limit) throws IOException {
        f.seek(pos);
        long size = f.readInt() & 0xFFFFFFFFL;
        long payload = pos + 8;
        if (size == 1) {
            f.seek(pos + 8);
            size = f.readLong();
            payload = pos + 16;
        } else if (size == 0) {
            size = limit - pos;
        }
        if (size < payload - pos || pos + size > limit) return null;
        return new long[]{payload, pos + size};
    }

    private static String type(RandomAccessFile f, long pos) throws IOException {
        f.seek(pos + 4);
        byte[] t = new byte[4];
        f.readFully(t);
        return new String(t, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
