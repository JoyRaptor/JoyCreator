package com.fadcam.ui.faditor.waveform;

/**
 * A1 + A2: a QUANTIZED, MAX-POOLED mip pyramid of a shaped quad-band tape. This is the data
 * structure {@link TapeWaveformRenderer#columns} reads from — it collapses the old per-pixel
 * multi-frame max scan (O(frames)) into ~1 read per pixel (O(pixels)) when zoomed out, and cuts
 * shaped memory ~4x by storing amplitudes as unsigned bytes instead of floats.
 *
 * <p><b>Pyramid (A1).</b> Per band, level 0 is the full-resolution shaped envelope; level {@code L}
 * is the pairwise MAX of level {@code L-1} (never a mean — transients must survive), halving the
 * length each time (ceil), down to {@link #MIN_LEVEL_LEN}. A band index at level 0 maps to index
 * {@code i >> L} at level L, because level L already holds the max over {@code 2^L} source frames.</p>
 *
 * <p><b>Quantization (A2).</b> Shaped values are already clamped 0..1, so each is stored as
 * {@code round(v*255)} in a {@code byte}. Java bytes are <b>signed</b>: every read masks
 * {@code & 0xFF}. The raw linear RMS ({@code BandedWaveformData.rms}) stays float — only this
 * shaped product and its mips are quantized.</p>
 *
 * <p>Deliberately Android-free so the pure math is JVM-harness testable.</p>
 */
public final class ShapedTape {

    /** Stop building levels once a level is this short or shorter. */
    public static final int MIN_LEVEL_LEN = 256;

    /** {@code mip[band]} is {@code null} for an absent band; else {@code mip[band][level][index]}. */
    private final byte[][][] mip;

    private ShapedTape(byte[][][] mip) {
        this.mip = mip;
    }

    /**
     * Build the quantized pyramid from shaped 0..1 float profiles. {@code shaped[band]} may be
     * {@code null} (band not computed / off) — that band stays {@code null} here.
     */
    public static ShapedTape build(float[][] shaped) {
        byte[][][] m = new byte[shaped.length][][];
        for (int b = 0; b < shaped.length; b++) {
            m[b] = shaped[b] == null ? null : buildBand(shaped[b]);
        }
        return new ShapedTape(m);
    }

    /** Build the quantized max-pool pyramid for one band (level 0 = full-res quantized). */
    public static byte[][] buildBand(float[] band) {
        byte[] lvl0 = new byte[band.length];
        for (int i = 0; i < band.length; i++) lvl0[i] = quant(band[i]);

        int levels = 1;
        for (int len = band.length; len > MIN_LEVEL_LEN; ) {
            len = (len + 1) >> 1;
            levels++;
        }
        byte[][] out = new byte[levels][];
        out[0] = lvl0;
        for (int L = 1; L < levels; L++) {
            byte[] prev = out[L - 1];
            int nlen = (prev.length + 1) >> 1;
            byte[] cur = new byte[nlen];
            for (int i = 0; i < nlen; i++) {
                int a = prev[2 * i] & 0xFF;
                int j = 2 * i + 1;
                int c = j < prev.length ? (prev[j] & 0xFF) : a;
                cur[i] = (byte) (a >= c ? a : c);
            }
            out[L] = cur;
        }
        return out;
    }

    /** Quantize a 0..1 amplitude to an unsigned byte (0..255), clamped. */
    public static byte quant(float v) {
        if (v <= 0f) return 0;
        if (v >= 1f) return (byte) 255;
        return (byte) Math.round(v * 255f);
    }

    /** @return whether the band was computed (non-null pyramid). */
    public boolean hasBand(int b) {
        return b >= 0 && b < mip.length && mip[b] != null;
    }

    /** Number of pyramid levels for a band (>= 1). */
    public int levelCount(int b) {
        return mip[b].length;
    }

    /** The byte array for a band at level {@code L} (0 = full-res), clamped to the pyramid. */
    public byte[] level(int b, int L) {
        byte[][] band = mip[b];
        if (L < 0) L = 0;
        else if (L >= band.length) L = band.length - 1;
        return band[L];
    }

    /** Full-resolution frame count of a band (level-0 length). */
    public int frameCount(int b) {
        return mip[b][0].length;
    }

    /**
     * Pick the pyramid level for a given frames-per-pixel: {@code floor(log2(framesPerPx))},
     * clamped to the levels that exist. At {@code framesPerPx < 2} this is level 0 (identical to
     * the un-mipped max scan — visual parity), deeper zoom-out steps up the pyramid.
     */
    public int levelFor(int b, float framesPerPx) {
        int fpp = (int) framesPerPx;
        int L = fpp >= 2 ? (31 - Integer.numberOfLeadingZeros(fpp)) : 0;
        int max = mip[b].length - 1;
        return L > max ? max : L;
    }
}
