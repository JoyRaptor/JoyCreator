package cc.joycreator.textlab;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The engine's plain data: a look's font, a piece, a letter, a layout. Field em, y up. */
final class TlText {

    private TlText() {}

    /** A look's {@code font} block. NaN = not given (the look book's {@code undefined}). */
    static final class Font {
        String family = "archivo";
        double weight = Double.NaN, width = Double.NaN, slant = 0, tracking = Double.NaN, leading = Double.NaN;
        boolean caps;

        @NonNull
        static Font of(@Nullable JSONObject o) {
            Font f = new Font();
            if (o == null) return f;
            f.family = o.optString("family", "archivo");
            f.weight = o.optDouble("weight", Double.NaN);
            f.width = o.optDouble("width", Double.NaN);
            f.slant = o.optDouble("slant", 0);
            f.tracking = o.optDouble("tracking", Double.NaN);
            f.leading = o.optDouble("leading", Double.NaN);
            f.caps = o.optBoolean("caps", false);
            return f;
        }

        /** {@code font.weight || 700}, {@code font.width || 100}. */
        double weightOr700() {
            return Double.isNaN(weight) || weight == 0 ? 700 : weight;
        }

        double widthOr100() {
            return Double.isNaN(width) || width == 0 ? 100 : width;
        }

        @NonNull
        String key() {
            return family + "," + weight + "," + width + "," + slant;
        }
    }

    /** Points on a piece for the emitter: x, y, outward x, y in fours (cell em). */
    static final class Points {
        final double[] edge, fill, corner;

        Points(double[] edge, double[] fill, double[] corner) {
            this.edge = edge;
            this.fill = fill;
            this.corner = corner;
        }

        double[] of(int which) {
            return which == 0 ? edge : which == 1 ? fill : corner;
        }
    }

    /** One shape's distance picture on the GPU, and where it sits. */
    static final class Piece {
        int tex;
        double cellW, cellH;
        double originX, originY;   // a letter: from the cell's bottom left to the pen's point on the baseline
        double cellX, cellY;       // a tube: its cell in the letter's own em space
        boolean paint;             // a tube drawn from a path
        double len;
        Points points;
    }

    /** One letter (or one tube of a tube letter) laid out in the field. */
    static final class Letter {
        String ch;
        Piece piece;
        double baseline, cap, x, y, pivotX, pivotY;
        int index, count, order;
        @Nullable double[] arc;
        @Nullable Object shards;
    }

    static final class Layout {
        final List<Letter> letters = new ArrayList<>();
        double fieldW, fieldH, cap;
        boolean tubes;
    }

    /** A growable list of doubles. */
    static final class Doubles {
        double[] a = new double[64];
        int n;

        void add(double... xs) {
            for (double x : xs) {
                if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
                a[n++] = x;
            }
        }

        double[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}
