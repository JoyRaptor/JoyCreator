package cc.joycreator.textlab;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Proves the see-through outputs ({@code final.frag} {@code uOut} 1 and 2) against the screen picture. Laid over
 * black, the words alone must give exactly what the screen picture shows over a black ground (with dark corners
 * and grain off: those belong to a whole frame, not to the words):
 * <ul>
 *   <li>{@code uOut 2} (8-bit, premultiplied, the look's finish): its colour IS the picture over black.</li>
 *   <li>{@code uOut 1} (linear light): finished here on the CPU with {@code final.frag}'s own film curve, colour
 *       and contrast, the way a GL compositor would after laying it over something.</li>
 * </ul>
 * GL thread only. Leaves the renderer on the screen output; the caller sets the look again (knobs were turned).
 */
final class ClearCheck {

    private ClearCheck() {}

    @NonNull
    static String run(@NonNull TextLabRenderer r, double t, @NonNull File dir, @NonNull String id) throws Exception {
        r.setKnob("vignette", 0.0);
        r.setKnob("grain", 0.0);

        r.output = TextLabRenderer.FOR_SCREEN;
        r.hasBack = false;
        r.fxaa = false;
        r.still(t);
        int w = r.width(), h = r.height();
        byte[] screen = rgba(r.readBitmap());

        r.output = TextLabRenderer.CLEAR;
        r.still(t);
        Bitmap clear = r.readBitmap();
        byte[] two = rgba(clear);

        r.output = TextLabRenderer.LINEAR_CLEAR;
        r.still(t);
        float[] lin = r.readLinear();
        double exposure = r.knob("exposure"), colour = r.knob("saturation"), contrast = r.knob("contrast");

        r.output = TextLabRenderer.FOR_SCREEN;
        r.hasBack = true;
        r.fxaa = true;

        // uOut 2 over black is its own colour (premultiplied); the screen picture is opaque
        int worst2 = 0, see = 0, overColour = 0;
        long sum2 = 0;
        for (int i = 0; i < w * h; i++) {
            int a = two[i * 4 + 3] & 255;
            if (a < 250) see++;
            for (int c = 0; c < 3; c++) {
                int d = Math.abs((two[i * 4 + c] & 255) - (screen[i * 4 + c] & 255));
                worst2 = Math.max(worst2, d);
                sum2 += d;
                if ((two[i * 4 + c] & 255) > a) overColour++;   // premultiplied: colour never above cover
            }
        }
        // uOut 1, finished here; its rows run bottom to top
        int worst1 = 0;
        long sum1 = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int k = ((h - 1 - y) * w + x) * 4, i = (y * w + x) * 4;
                double[] c = finish(lin[k] * exposure, lin[k + 1] * exposure, lin[k + 2] * exposure, colour, contrast);
                for (int ch = 0; ch < 3; ch++) {
                    int d = Math.abs((int) Math.round(c[ch] * 255) - (screen[i + ch] & 255));
                    worst1 = Math.max(worst1, d);
                    sum1 += d;
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream os = new FileOutputStream(new File(dir, id + "_clear.png"))) {
            clear.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        int n = w * h * 3;
        return String.format(Locale.ROOT,
                "clear check %s %dx%d t=%.1f: uOut 2 over black vs screen over black: worst %d, mean %.3f; uOut 1 finished: worst %d, mean %.3f; "
                        + "see-through pixels %.1f%%; colour above cover %d",
                id, w, h, t, worst2, (double) sum2 / n, worst1, (double) sum1 / n, 100.0 * see / (w * h), overColour);
    }

    /** A Bitmap's bytes as stored: premultiplied RGBA, top row first. */
    private static byte[] rgba(@NonNull Bitmap b) {
        ByteBuffer buf = ByteBuffer.allocate(b.getByteCount());
        b.copyPixelsToBuffer(buf);
        return buf.array();
    }

    // ---- final.frag's finish, the same sums -------------------------------------------------------

    private static double[] finish(double r, double g, double b, double colour, double contrast) {
        double[] c = film(r, g, b);
        double l = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
        for (int i = 0; i < 3; i++) {
            double v = l + (c[i] - l) * colour;
            v = srgb(Math.min(1, Math.max(0, v)));
            c[i] = Math.min(1, Math.max(0, (v - 0.5) * contrast + 0.5));
        }
        return c;
    }

    private static double[] film(double r, double g, double b) {
        double peak = Math.max(r, Math.max(g, b));
        if (peak < 1e-5) return new double[]{0, 0, 0};
        double knee = 0.6;
        double m = peak < knee ? peak : knee + (1 - knee) * Math.tanh((peak - knee) / (1 - knee));
        double k = m / peak;
        double s = smoothstep(1.6, 9.0, peak) * 0.8;
        return new double[]{r * k + (m - r * k) * s, g * k + (m - g * k) * s, b * k + (m - b * k) * s};
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static double srgb(double c) {
        return c < 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }
}
