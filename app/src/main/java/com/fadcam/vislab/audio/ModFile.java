package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A ProTracker-style module (.mod), the demoscene tracker format (roadmap R13): 31 sampled
 * instruments, an order list of up to 128 positions, and patterns of 64 rows by N channels, each
 * cell a sample number, an Amiga period (the pitch) and one effect. Read-only; {@link ModPlayer}
 * plays it.
 *
 * <p>Only the 31-sample kind with a channel tag at byte 1080 ("M.K.", "M!K!", "FLT4", "4CHN",
 * "6CHN", "8CHN", "xxCH") is accepted: it is what every tracker since 1989 writes, and the tag is
 * what tells a module from any other file.</p>
 */
public final class ModFile {

    /** One sampled instrument. */
    public static final class Sample {
        @NonNull public final String name;
        /** Signed 8-bit sound. */
        @NonNull public final byte[] data;
        /** -8..7, eighths of a semitone. */
        public final int finetune;
        /** 0..64. */
        public final int volume;
        /** Where the repeat starts and how long it is, in bytes; a length of 2 or less = no repeat. */
        public final int loopStart, loopLength;

        Sample(@NonNull String name, @NonNull byte[] data, int finetune, int volume, int loopStart, int loopLength) {
            this.name = name;
            this.data = data;
            this.finetune = finetune;
            this.volume = volume;
            this.loopStart = loopStart;
            this.loopLength = loopLength;
        }

        public boolean loops() {
            return loopLength > 2 && loopStart + loopLength <= data.length;
        }
    }

    public static final int ROWS = 64;
    private static final int HEADER = 1084;

    @NonNull public final String title;
    public final int channels;
    public final int songLength;
    @NonNull public final int[] order;
    @NonNull public final Sample[] samples;
    private final byte[] patterns;
    private final int patternCount;

    private ModFile(@NonNull String title, int channels, int songLength, @NonNull int[] order,
                    @NonNull Sample[] samples, @NonNull byte[] patterns, int patternCount) {
        this.title = title;
        this.channels = channels;
        this.songLength = songLength;
        this.order = order;
        this.samples = samples;
        this.patterns = patterns;
        this.patternCount = patternCount;
    }

    /** True when {@code d} starts like a module (its channel tag at byte 1080). */
    public static boolean looksLikeMod(@NonNull byte[] d, int n) {
        return n >= HEADER && channelsFor(new String(d, 1080, 4, StandardCharsets.ISO_8859_1)) > 0;
    }

    static int channelsFor(@NonNull String tag) {
        switch (tag) {
            case "M.K.": case "M!K!": case "FLT4": case "4CHN": return 4;
            case "6CHN": return 6;
            case "8CHN": case "FLT8": case "CD81": case "OKTA": return 8;
            default:
                if (tag.length() == 4 && tag.endsWith("CH") && Character.isDigit(tag.charAt(0))
                        && Character.isDigit(tag.charAt(1))) {
                    int c = Integer.parseInt(tag.substring(0, 2));
                    return c >= 1 && c <= 32 ? c : 0;
                }
                return 0;
        }
    }

    @NonNull
    public static ModFile parse(@NonNull byte[] d) throws IOException {
        if (!looksLikeMod(d, d.length)) throw new IOException("not a module");
        int channels = channelsFor(new String(d, 1080, 4, StandardCharsets.ISO_8859_1));
        String title = text(d, 0, 20);
        int songLength = Math.max(1, Math.min(128, d[950] & 0xFF));
        int[] order = new int[128];
        int patternCount = 0;
        for (int i = 0; i < 128; i++) {
            order[i] = d[952 + i] & 0xFF;
            patternCount = Math.max(patternCount, order[i] + 1);
        }
        int patternBytes = patternCount * ROWS * channels * 4;
        if (HEADER + patternBytes > d.length) throw new IOException("module cut short in its patterns");
        byte[] patterns = new byte[patternBytes];
        System.arraycopy(d, HEADER, patterns, 0, patternBytes);
        Sample[] samples = new Sample[31];
        int at = HEADER + patternBytes;
        for (int i = 0; i < 31; i++) {
            int h = 20 + i * 30;
            int len = word(d, h + 22) * 2;
            int ft = d[h + 24] & 0x0F;
            if (ft > 7) ft -= 16;
            int vol = Math.min(64, d[h + 25] & 0xFF);
            int ls = word(d, h + 26) * 2, ll = word(d, h + 28) * 2;
            // A module cut short keeps what it has: a sample past the end is silent, not an error.
            int have = Math.max(0, Math.min(len, d.length - at));
            byte[] data = new byte[have];
            if (have > 0) System.arraycopy(d, at, data, 0, have);
            at += len;
            samples[i] = new Sample(text(d, h, 22), data, ft, vol, ls, ll);
        }
        return new ModFile(title, channels, songLength, order, samples, patterns, patternCount);
    }

    // ------------------------------------------------------------------ cells

    private int cell(int pattern, int row, int channel) {
        return ((pattern * ROWS + row) * channels + channel) * 4;
    }

    public boolean hasPattern(int pattern) {
        return pattern >= 0 && pattern < patternCount;
    }

    /** 1..31, or 0 for no sample in this cell. */
    public int sampleAt(int pattern, int row, int channel) {
        int o = cell(pattern, row, channel);
        return (patterns[o] & 0xF0) | ((patterns[o + 2] >> 4) & 0x0F);
    }

    /** The Amiga period (lower = higher pitch), or 0 for no note. */
    public int periodAt(int pattern, int row, int channel) {
        int o = cell(pattern, row, channel);
        return ((patterns[o] & 0x0F) << 8) | (patterns[o + 1] & 0xFF);
    }

    public int effectAt(int pattern, int row, int channel) {
        return patterns[cell(pattern, row, channel) + 2] & 0x0F;
    }

    public int paramAt(int pattern, int row, int channel) {
        return patterns[cell(pattern, row, channel) + 3] & 0xFF;
    }

    private static int word(byte[] d, int o) {
        return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
    }

    @NonNull
    private static String text(byte[] d, int o, int n) {
        int end = o;
        while (end < o + n && d[end] != 0) end++;
        return new String(d, o, end - o, StandardCharsets.ISO_8859_1).trim();
    }
}
