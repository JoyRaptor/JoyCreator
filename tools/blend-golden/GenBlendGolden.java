import com.fadcam.ui.faditor.model.BlendModes;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * GENERATES {@code joybrush/core/src/commonTest/.../blend/BlendGolden.kt} by RUNNING the Studio's
 * own Java reference, {@code BlendModes.blend(b, s, mode)}, over a fixed set of (backdrop, source)
 * pairs.
 *
 * <p><b>Why a generator and not a table somebody typed.</b> Joy Brush's core is pure Kotlin
 * (Lead ruling R23: it must stay that way for the iOS door), so it cannot CALL the Studio's Java.
 * The two implementations of the same 26 equations therefore have to be tied together by
 * something other than a careful reading. This file is that something: it compiles against the
 * Studio's real source, runs its real arithmetic, and writes down the exact floats. Every value in
 * {@code BlendGolden.kt} is therefore an OBSERVATION of the Studio, not a claim about it — and
 * {@code tools/gen_blend_golden.sh --check} regenerates and diffs, so an edit to
 * {@code BlendModes.java} that is not followed by a regeneration fails loudly instead of quietly
 * diverging.
 *
 * <p><b>Float literals are {@code Float.toString}, exactly.</b> That is the shortest decimal that
 * round-trips to the same 32-bit value, so the committed table holds the Studio's bits, not an
 * approximation of them, and the parity test's 1e-6 tolerance is measuring a transcription error
 * rather than a formatting error.
 *
 * <p><b>Compiled against</b> {@code app/src/main/java} (or {@code studiokit/src/main/java} after
 * D.05 — whichever exists), with {@code tools/jvm-harness/stubs} on the sourcepath for
 * {@code androidx.annotation}. Nothing here is a copy of {@code BlendModes.java}: it is the class.
 *
 * <p>Usage: {@code java GenBlendGolden <out.kt>}
 */
public final class GenBlendGolden {

    /** Fixed, and named in the generated file's header: the table must be reproducible. */
    static final long SEED = 20260929L;

    /** The spec's random half. */
    static final int RANDOM_PAIRS = 400;

    /** The spec's 27 corner colours {0, 0.5, 1}^3. */
    static final float[] CORNER = {0f, 0.5f, 1f};

    /**
     * Out-of-range pairs. NOT in the spec, and deliberately so: with b, s in 0..1 EVERY one of the
     * 26 modes happens to land in 0..1, so a table of in-range rows cannot tell an UNCLAMPED
     * implementation from a clamping one. The contract in {@code BlendModes.blend}'s javadoc is
     * that the result is returned unclamped and the caller clamps, so the contract has to be pinned
     * by rows that only an unclamped implementation can satisfy. Magnitudes are kept at +-3 so
     * nothing overflows to Infinity and every literal stays finite.
     */
    static final int OUT_OF_RANGE_PAIRS = 60;
    static final float OUT_OF_RANGE_LIMIT = 3f;

    static final int CORNER_TRIPLES = CORNER.length * CORNER.length * CORNER.length;              // 27
    /** b == s for each corner triple. The spec's reading of "the 27 corner cases". */
    static final int CORNER_SELF_PAIRS = CORNER_TRIPLES;                                           // 27
    /**
     * Every corner triple against every OTHER one, 27*26. Also not in the spec: with b == s the
     * whole-colour modes are nearly free (DARKER_COLOR and LIGHTER_COLOR return b either way,
     * COLOR/HUE/SATURATION/LUMINOSITY all collapse to the input), so the spec's 27 corner rows
     * would leave five of the twenty-six modes essentially untested at the values most likely to
     * expose a branch error.
     */
    static final int CORNER_CROSS_PAIRS = CORNER_TRIPLES * CORNER_TRIPLES - CORNER_TRIPLES;       // 702

    static final int SPEC_PAIRS = RANDOM_PAIRS + CORNER_SELF_PAIRS;                                 // 427
    static final int PAIR_COUNT = SPEC_PAIRS + CORNER_CROSS_PAIRS + OUT_OF_RANGE_PAIRS;            // 1189

    /** The 26 Studio modes. Asserted against {@code BlendModes.modeCode} below. */
    static final int MODE_COUNT = 26;

    /** Floats per array literal. Keeps each generated call site small enough to compile quickly. */
    static final int CHUNK = 8192;

    private GenBlendGolden() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: GenBlendGolden <out.kt>");
            System.exit(2);
        }

        verifyModeTable();

        float[][] bs = buildPairs();
        Path out = Paths.get(args[0]);
        write(out, bs);
        System.err.println("wrote " + out + " — " + PAIR_COUNT + " pairs x " + MODE_COUNT
                + " modes = " + (PAIR_COUNT * MODE_COUNT) + " rows");
    }

    /**
     * The generated table's code→name column comes from {@code BlendModes.ALL}, which is only the
     * shader-code table because {@code modeName} indexes it. If that ever stops being true, every
     * row in the file means something else, so it is checked here — in the generator, where a
     * failure is a bug report rather than a mysterious parity failure later.
     */
    private static void verifyModeTable() {
        if (BlendModes.ALL.length != MODE_COUNT) {
            throw new IllegalStateException("BlendModes.ALL has " + BlendModes.ALL.length
                    + " modes, this generator was written for " + MODE_COUNT);
        }
        for (int code = 0; code < MODE_COUNT; code++) {
            int round = BlendModes.modeCode(BlendModes.ALL[code]);
            if (round != code) {
                throw new IllegalStateException("BlendModes.ALL[" + code + "] is "
                        + BlendModes.ALL[code] + " but modeCode() calls it " + round
                        + " — ALL's index is the shader code only while these agree");
            }
        }
    }

    /** The (b, s) pairs, in the exact order they appear in the table. */
    private static float[][] buildPairs() {
        float[][] corners = cornerTriples();
        float[][] pairs = new float[PAIR_COUNT][];
        int p = 0;

        // 1. the spec's 400 random pairs. Six draws per pair, backdrop first.
        Random rnd = new Random(SEED);
        for (int i = 0; i < RANDOM_PAIRS; i++) {
            float[] v = new float[6];
            for (int k = 0; k < 6; k++) v[k] = rnd.nextFloat();
            pairs[p++] = v;
        }

        // 2. the spec's 27 corner cases, read as (t, t).
        for (int i = 0; i < CORNER_SELF_PAIRS; i++) {
            float[] t = corners[i];
            pairs[p++] = new float[]{t[0], t[1], t[2], t[0], t[1], t[2]};
        }

        // 3. every corner against every other corner.
        for (int i = 0; i < CORNER_TRIPLES; i++) {
            for (int j = 0; j < CORNER_TRIPLES; j++) {
                if (i == j) continue;
                float[] b = corners[i];
                float[] s = corners[j];
                pairs[p++] = new float[]{b[0], b[1], b[2], s[0], s[1], s[2]};
            }
        }

        // 4. out of range: unclamped contract.
        for (int i = 0; i < OUT_OF_RANGE_PAIRS; i++) {
            float[] v = new float[6];
            for (int k = 0; k < 6; k++) {
                v[k] = rnd.nextFloat() * 2f * OUT_OF_RANGE_LIMIT - OUT_OF_RANGE_LIMIT;
            }
            pairs[p++] = v;
        }

        if (p != PAIR_COUNT) {
            throw new IllegalStateException("built " + p + " pairs, PAIR_COUNT says " + PAIR_COUNT);
        }
        return pairs;
    }

    /** {0, 0.5, 1}^3 in a fixed nesting order (red outer, blue inner). */
    private static float[][] cornerTriples() {
        float[][] out = new float[CORNER_TRIPLES][];
        int i = 0;
        for (int r = 0; r < CORNER.length; r++) {
            for (int g = 0; g < CORNER.length; g++) {
                for (int b = 0; b < CORNER.length; b++) {
                    out[i++] = new float[]{CORNER[r], CORNER[g], CORNER[b]};
                }
            }
        }
        return out;
    }

    private static void write(Path out, float[][] bs) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            header(w);

            w.write("    const val SEED: Long = " + SEED + "L\n");
            w.write("    const val MODE_COUNT: Int = " + MODE_COUNT + "\n");
            w.write("    const val RANDOM_PAIRS: Int = " + RANDOM_PAIRS + "\n");
            w.write("    const val CORNER_SELF_PAIRS: Int = " + CORNER_SELF_PAIRS + "\n");
            w.write("    const val CORNER_CROSS_PAIRS: Int = " + CORNER_CROSS_PAIRS + "\n");
            w.write("    const val OUT_OF_RANGE_PAIRS: Int = " + OUT_OF_RANGE_PAIRS + "\n");
            w.write("    /** Rows [0, SPEC_PAIRS) are exactly the cases the spec asks for. */\n");
            w.write("    const val SPEC_PAIRS: Int = " + SPEC_PAIRS + "\n");
            w.write("    const val PAIR_COUNT: Int = " + PAIR_COUNT + "\n");
            w.write("    /** First out-of-range pair: rows [SPEC_PAIRS + CORNER_CROSS_PAIRS, ...). */\n");
            w.write("    const val FIRST_OUT_OF_RANGE_PAIR: Int = " + (SPEC_PAIRS + CORNER_CROSS_PAIRS) + "\n");
            w.write("\n");

            w.write("    /** The Studio's mode NAMES, indexed by its mode CODE. */\n");
            w.write("    val NAMES: List<String> = listOf(\n");
            for (int code = 0; code < MODE_COUNT; code++) {
                w.write("        \"" + BlendModes.ALL[code] + "\","
                        + (code == MODE_COUNT - 1 ? "" : "  // " + code) + "\n");
            }
            w.write("    )\n\n");

            w.write("    /** Backdrop then source, straight rgb, 6 floats per pair. */\n");
            w.write("    val BS: FloatArray = floatArrayOf(\n");
            for (float[] pair : bs) {
                w.write("        " + lit(pair[0]) + ", " + lit(pair[1]) + ", " + lit(pair[2])
                        + ", " + lit(pair[3]) + ", " + lit(pair[4]) + ", " + lit(pair[5]) + ",\n");
            }
            w.write("    )\n\n");

            float[] expected = expected(bs);
            w.write("    /**\n");
            w.write("     * The Studio's answer, UNCLAMPED, 3 floats per (pair, mode). Indexed\n");
            w.write("     * {@code EXPECTED[pair * (MODE_COUNT * 3) + mode * 3 + channel]}.\n");
            w.write("     */\n");
            w.write("    val EXPECTED: FloatArray = ");
            int parts = (expected.length + CHUNK - 1) / CHUNK;
            for (int i = 0; i < parts; i++) {
                w.write((i == 0 ? "" : " + ") + "part" + i + "()");
            }
            w.write("\n");
            for (int i = 0; i < parts; i++) {
                int from = i * CHUNK;
                int to = Math.min(expected.length, from + CHUNK);
                w.write("\n    private fun part" + i + "(): FloatArray = floatArrayOf(\n");
                for (int k = from; k < to; k++) {
                    w.write("        " + lit(expected[k]) + ",\n");
                }
                w.write("    )\n");
            }

            w.write("\n    fun expected(mode: Int, pair: Int, channel: Int): Float =\n");
            w.write("        EXPECTED[pair * (MODE_COUNT * 3) + mode * 3 + channel]\n");
            w.write("}\n");
        }
    }

    /** Every (pair, mode) row, in pair-major order. */
    private static float[] expected(float[][] bs) {
        float[] out = new float[PAIR_COUNT * MODE_COUNT * 3];
        int at = 0;
        for (float[] pair : bs) {
            float[] b = {pair[0], pair[1], pair[2]};
            float[] s = {pair[3], pair[4], pair[5]};
            for (int code = 0; code < MODE_COUNT; code++) {
                float[] r = BlendModes.blend(b, s, code);
                for (int ch = 0; ch < 3; ch++) {
                    float v = r[ch];
                    if (Float.isNaN(v) || Float.isInfinite(v)) {
                        throw new IllegalStateException("mode " + code + " produced " + v
                                + " at pair " + at / (MODE_COUNT * 3)
                                + " — the table is written as finite float literals, so a case that"
                                + " overflows cannot be stored exactly and must not be here");
                    }
                    out[at++] = v;
                }
            }
        }
        return out;
    }

    /**
     * One float literal, from {@code Float.toString}. That is the shortest decimal which reads back
     * to the identical 32-bit value, so the committed table stores the Studio's bits rather than a
     * rounded version of them.
     */
    private static String lit(float v) {
        return Float.toString(v) + "f";
    }

    private static void header(BufferedWriter w) throws IOException {
        w.write("// GENERATED FILE — DO NOT EDIT BY HAND.\n");
        w.write("//\n");
        w.write("// Every number below is an OBSERVATION of the Studio's own Java reference\n");
        w.write("// (app/src/main/java/com/fadcam/ui/faditor/model/BlendModes.java,\n");
        w.write("// BlendModes.blend(b, s, mode)), produced by tools/blend-golden/GenBlendGolden.java.\n");
        w.write("// Nothing in it was typed, rounded or reviewed by eye — Lead ruling R23.\n");
        w.write("//\n");
        w.write("// Regenerate:  bash tools/gen_blend_golden.sh\n");
        w.write("// Drift check: bash tools/gen_blend_golden.sh --check   (non-zero on any difference)\n");
        w.write("//\n");
        w.write("// Pair order: " + RANDOM_PAIRS + " from java.util.Random(" + SEED + "), then the "
                + CORNER_SELF_PAIRS + "\n");
        w.write("// corner cases {0, 0.5, 1}^3 as (t, t), then " + CORNER_CROSS_PAIRS + " corner-vs-other-corner pairs,\n");
        w.write("// then " + OUT_OF_RANGE_PAIRS + " pairs in +-" + (long) OUT_OF_RANGE_LIMIT
                + ". The last two groups are additions: they are what proves the results are UNCLAMPED\n");
        w.write("// (in range, every mode lands in 0..1 anyway) and what gives the whole-colour modes\n");
        w.write("// something to choose between.\n");
        w.write("//\n");
        w.write("// Values are unclamped, exactly like the GLSL's callers see them.\n");
        w.write("package cc.joycreator.joybrush.core.blend\n\n");
        w.write("/** The Studio's blend maths, recorded. See the header above for how to regenerate it. */\n");
        w.write("object BlendGolden {\n");
    }
}
