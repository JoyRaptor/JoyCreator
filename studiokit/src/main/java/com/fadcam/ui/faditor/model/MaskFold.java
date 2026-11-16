package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE decision half of {@link MaskPathBuilder} — "which boolean op does each shape fold with,
 * and does this stack need the ordered fold at all" — plus the feather-cache
 * {@link #signature}. Pure ints and strings; no {@code android.graphics}.
 *
 * <p><b>Why it is its own file.</b> {@link MaskPathBuilder} imports {@code Path}, {@code Bitmap}
 * and {@code Canvas}, so the gson-only JVM harness cannot load it — the same reason
 * {@link CompositingSpec} and {@link MaskAnimator} are android-free and say so in their own
 * headers. The spec's requirement is that the fold decision be "JVM-testable without Path"; the
 * only way to satisfy that in this repo is for the decision to live where the harness can reach
 * it. {@code MaskPathBuilder} holds no second copy of any rule here — it calls this class — so
 * there is still exactly one authority, which is the point.</p>
 *
 * <p><b>THE GATE.</b> {@link Fold#sequential} is false unless some shape actually uses
 * {@link CompositingSpec#MODE_INTERSECT}. Every project that exists today therefore takes the
 * shipped two-bucket code path, unchanged, and the byte-identical feather-0 clip path is
 * preserved BY CONSTRUCTION rather than by careful reading (spec §1.2).</p>
 */
public final class MaskFold {

    private MaskFold() {}

    /** {@link Fold#ops} values, mirroring {@code Path.Op} without importing android. */
    public static final int OP_UNION = 0;
    public static final int OP_DIFFERENCE = 1;
    public static final int OP_INTERSECT = 2;

    /** What {@link MaskPathBuilder#buildVisiblePath} is about to do, as plain ints. */
    public static final class Fold {
        /** One op per shape, in list order. */
        @NonNull public final int[] ops;
        /**
         * false = the shipped two-bucket algorithm (union of the adds, minus the union of the
         * subtracts), and {@link #ops} merely names each shape's BUCKET — order is irrelevant.
         * true = the ordered accumulate, where {@link #ops} IS the sequence and order matters.
         */
        public final boolean sequential;

        Fold(@NonNull int[] ops, boolean sequential) {
            this.ops = ops;
            this.sequential = sequential;
        }
    }

    /** @see Fold */
    @NonNull
    public static Fold foldOps(@Nullable CompositingSpec spec) {
        if (spec == null || spec.masks.isEmpty()) return new Fold(new int[0], false);
        boolean sequential = spec.usesIntersect();
        int[] ops = new int[spec.masks.size()];
        for (int i = 0; i < ops.length; i++) {
            CompositingSpec.MaskShape m = spec.masks.get(i);
            int op = m.isIntersect() ? OP_INTERSECT
                    : m.isSubtract() ? OP_DIFFERENCE : OP_UNION;
            // The SEED, and only in the ordered fold. Folding the first shape into an empty
            // accumulator would make a leading INTERSECT erase the whole stack and a leading
            // SUBTRACT a no-op — neither is what anyone drawing the first shape of a mask
            // means. In the two-bucket path shape 0's op is its BUCKET, and a leading subtract
            // there really does mean "put me in the sub bucket", so it is left alone.
            if (sequential && i == 0) op = OP_UNION;
            ops[i] = op;
        }
        return new Fold(ops, sequential);
    }

    /**
     * The ORDER the GL fold must run the shapes in, as indices into {@code spec.masks} — the
     * one place the Canvas rule is carried over to the shaders (audit R3-MASK-01).
     *
     * <p>The GL consumers fold the packed list in order (seed with the first, then
     * union/difference/intersect). That is the right reading for the sequential fold, so there
     * the order is the list's own. In the two-bucket case it was wrong: Canvas takes
     * union(adds) minus union(subtracts) whatever the order, but folding Add, Subtract, Add in
     * sequence lets the last Add bring back what the Subtract removed. Running every add first
     * and every subtract after gives exactly (adds) minus (subtracts) in the same fold.</p>
     *
     * <p>A stack with no add at all removes from nothing: Canvas's region is empty. That is
     * returned as an EMPTY array, and the packer then writes one off-screen shape (which
     * covers no pixel) rather than seeding the fold with a subtract.</p>
     */
    @NonNull
    public static int[] glOrder(@Nullable CompositingSpec spec) {
        if (spec == null || spec.masks.isEmpty()) return new int[0];
        int n = spec.masks.size();
        int[] out = new int[n];
        int k = 0;
        if (spec.usesIntersect()) {
            for (int i = 0; i < n; i++) out[k++] = i;
            return out;
        }
        for (int i = 0; i < n; i++) if (!spec.masks.get(i).isSubtract()) out[k++] = i;
        if (k == 0) return new int[0];
        for (int i = 0; i < n; i++) if (spec.masks.get(i).isSubtract()) out[k++] = i;
        return out;
    }

    /**
     * Everything that changes the feather erase bitmap, and nothing that does not — the key
     * {@code MaskPathBuilder}'s LRU is looked up by.
     *
     * <p>Carries {@code mode} rather than the old add/subtract letter (two specs differing only
     * by intersect-vs-add produce different bitmaps) and {@code slot} (the geometry was resolved
     * FROM the slot's keyframe tracks, so the same numbers in different slots are not
     * interchangeable). Both are spec §1.2 requirements.</p>
     */
    @NonNull
    public static String signature(@NonNull CompositingSpec spec) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(spec.invertMasks ? 'I' : 'n').append(spec.maskFeather);
        for (CompositingSpec.MaskShape m : spec.masks) {
            sb.append(';').append(m.cx).append(',').append(m.cy).append(',')
              .append(m.w).append(',').append(m.h).append(',')
              .append(m.corner).append(',').append(m.rotationDeg)
              .append('m').append(m.mode).append('#').append(m.slot)
              // A per-shape override changes the blur radius that shape is drawn with, so two
              // stacks identical but for one shape's softness are DIFFERENT bitmaps. Omitting
              // this would serve the first one's cached bitmap for the second.
              .append('f').append(m.feather);
        }
        return sb.toString();
    }
}
