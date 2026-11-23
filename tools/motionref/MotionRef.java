import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * STU-7 footage reference (spec §8): turns a SHARP high-frame-rate clip into the "true" motion
 * blur a real camera would have recorded at a lower frame rate, plus the sharp low-rate frames
 * the blur effect is given as input.
 *
 * <pre>
 *   java MotionRef expose &lt;inDir&gt; &lt;inFps&gt; &lt;outFps&gt; &lt;angleDeg&gt; &lt;phaseDeg&gt; &lt;outDir&gt;
 *   java MotionRef compare &lt;dirA&gt; &lt;dirB&gt; [prefixA prefixB]
 *   java MotionRef selftest
 * </pre>
 *
 * <p>{@code inDir} holds PNG frames in name order (e.g. from
 * {@code ffmpeg -i clip240.mp4 in/%06d.png}); frame i is centred at i / inFps. For output frame k
 * (time t = k / outFps) the shutter is open over [t + phase/360·T, t + (phase+angle)/360·T],
 * T = 1/outFps: the same rule as {@code MotionShutter}. Each input frame stands for the interval
 * [i - 1/2, i + 1/2] / inFps and is weighted by its overlap with the window, so a window that
 * splits an input frame counts it in part. Averaging is in LINEAR light, sRGB-decoded and
 * re-encoded. Writes {@code true_NNNNNN.png} (the reference exposure) and
 * {@code sharp_NNNNNN.png} (the input frame nearest t: what the app's blur starts from).
 *
 * <p>{@code compare} prints mean absolute error and PSNR, per channel in 8-bit units, over
 * matching file names.
 *
 * <p>Plain Java (javax.imageio), no app code: it runs on the PC beside ffmpeg.
 */
public final class MotionRef {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { usage(); return; }
        switch (args[0]) {
            case "expose":
                if (args.length != 7) { usage(); return; }
                expose(new File(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]),
                        Double.parseDouble(args[4]), Double.parseDouble(args[5]), new File(args[6]));
                break;
            case "compare":
                compare(new File(args[1]), new File(args[2]),
                        args.length > 3 ? args[3] : "", args.length > 4 ? args[4] : "");
                break;
            case "selftest":
                selftest(args.length > 1 ? args[1] : "");
                break;
            default:
                usage();
        }
    }

    private static void usage() {
        System.out.println("MotionRef expose <inDir> <inFps> <outFps> <angleDeg> <phaseDeg> <outDir>");
        System.out.println("MotionRef compare <dirA> <dirB> [prefixA prefixB]");
        System.out.println("MotionRef selftest");
    }

    // ---- exposure -------------------------------------------------------------------------

    static int expose(File in, double inFps, double outFps, double angle, double phase, File out)
            throws IOException {
        File[] frames = in.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (frames == null || frames.length == 0) throw new IOException("no PNG frames in " + in);
        Arrays.sort(frames);
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot create " + out);
        double duration = frames.length / inFps;
        int count = (int) Math.floor(duration * outFps);
        int written = 0;
        for (int k = 0; k < count; k++) {
            double t = k / outFps;
            double[] w = weights(frames.length, inFps, outFps, angle, phase, t);
            if (w == null) continue;   // window runs off the clip: no honest reference
            BufferedImage img = average(frames, w);
            ImageIO.write(img, "png", new File(out, String.format("true_%06d.png", k)));
            int nearest = (int) Math.min(frames.length - 1, Math.max(0, Math.round(t * inFps)));
            ImageIO.write(ImageIO.read(frames[nearest]), "png", new File(out, String.format("sharp_%06d.png", k)));
            written++;
        }
        System.out.printf(Locale.ROOT, "wrote %d exposures (%d input frames, %.3f fps -> %.3f fps, %.0f deg)%n",
                written, frames.length, inFps, outFps, angle);
        return written;
    }

    /**
     * Overlap weights of every input frame with the shutter window at t; null when the window
     * reaches outside the clip. A zero-length window takes the frame containing t.
     */
    static double[] weights(int n, double inFps, double outFps, double angle, double phase, double t) {
        double period = 1.0 / outFps;
        double open = t + phase / 360.0 * period;
        double close = open + angle / 360.0 * period;
        double half = 0.5 / inFps;
        if (open < -half - 1e-12 || close > (n - 0.5) / inFps + 1e-12) return null;
        double[] w = new double[n];
        if (close - open < 1e-12) {
            int i = (int) Math.min(n - 1, Math.max(0, Math.round(t * inFps)));
            w[i] = 1;
            return w;
        }
        double sum = 0;
        int lo = Math.max(0, (int) Math.floor(open * inFps) - 1);
        int hi = Math.min(n - 1, (int) Math.ceil(close * inFps) + 1);
        for (int i = lo; i <= hi; i++) {
            double a = Math.max(open, i / inFps - half), b = Math.min(close, i / inFps + half);
            if (b > a) { w[i] = b - a; sum += b - a; }
        }
        for (int i = lo; i <= hi; i++) w[i] /= sum;
        return w;
    }

    private static BufferedImage average(File[] frames, double[] w) throws IOException {
        int width = -1, height = -1;
        double[] acc = null;
        for (int i = 0; i < w.length; i++) {
            if (w[i] == 0) continue;
            BufferedImage img = ImageIO.read(frames[i]);
            if (acc == null) {
                width = img.getWidth();
                height = img.getHeight();
                acc = new double[width * height * 3];
            } else if (img.getWidth() != width || img.getHeight() != height) {
                throw new IOException("frame size changes at " + frames[i]);
            }
            int[] px = img.getRGB(0, 0, width, height, null, 0, width);
            for (int p = 0; p < px.length; p++) {
                int c = px[p];
                acc[3 * p] += w[i] * LIN[(c >> 16) & 0xff];
                acc[3 * p + 1] += w[i] * LIN[(c >> 8) & 0xff];
                acc[3 * p + 2] += w[i] * LIN[c & 0xff];
            }
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[width * height];
        for (int p = 0; p < px.length; p++) {
            px[p] = (encode(acc[3 * p]) << 16) | (encode(acc[3 * p + 1]) << 8) | encode(acc[3 * p + 2]);
        }
        out.setRGB(0, 0, width, height, px, 0, width);
        return out;
    }

    private static final double[] LIN = new double[256];
    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LIN[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    static int encode(double lin) {
        lin = Math.max(0, Math.min(1, lin));
        double c = lin <= 0.0031308 ? lin * 12.92 : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
        return (int) Math.round(c * 255);
    }

    // ---- comparison -----------------------------------------------------------------------

    static double[] compare(File a, File b, String prefixA, String prefixB) throws IOException {
        File[] fa = a.listFiles((d, n) -> n.startsWith(prefixA) && n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (fa == null) throw new IOException("no frames in " + a);
        Arrays.sort(fa);
        double absSum = 0, sqSum = 0;
        long samples = 0;
        int frames = 0;
        for (File f : fa) {
            File g = new File(b, prefixB + f.getName().substring(prefixA.length()));
            if (!g.isFile()) continue;
            BufferedImage x = ImageIO.read(f), y = ImageIO.read(g);
            if (x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight()) {
                throw new IOException("size mismatch " + f + " vs " + g);
            }
            int[] p = x.getRGB(0, 0, x.getWidth(), x.getHeight(), null, 0, x.getWidth());
            int[] q = y.getRGB(0, 0, y.getWidth(), y.getHeight(), null, 0, y.getWidth());
            for (int i = 0; i < p.length; i++) {
                for (int s = 0; s <= 16; s += 8) {
                    int d = ((p[i] >> s) & 0xff) - ((q[i] >> s) & 0xff);
                    absSum += Math.abs(d);
                    sqSum += d * d;
                    samples++;
                }
            }
            frames++;
        }
        if (samples == 0) throw new IOException("no matching frames");
        double mae = absSum / samples, mse = sqSum / samples;
        double psnr = mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
        System.out.printf(Locale.ROOT, "frames %d  MAE %.3f/255  PSNR %.2f dB%n", frames, mae, psnr);
        return new double[]{mae, psnr};
    }

    // ---- self test ------------------------------------------------------------------------

    /**
     * A white bar sweeping across black at a known speed, rendered sharp at 240 fps. The 30 fps,
     * 180-degree exposure must be the analytic box smear: in linear light each column's value is
     * the fraction of the open shutter the bar covers it. Mutants: {@code --srgb-average} (average
     * coded values, the classic grey-streak bug) and {@code --no-partial} (whole input frames only)
     * must fail.
     */
    static void selftest(String mutant) throws IOException {
        File root = new File(System.getProperty("java.io.tmpdir"), "motionref-selftest-" + System.nanoTime());
        File in = new File(root, "in"), out = new File(root, "out");
        if (!in.mkdirs()) throw new IOException("cannot create " + in);
        int w = 160, h = 8, n = 240;            // one second at 240 fps
        double speed = 240;                      // px per second: 1 px per input frame
        double barW = 6;
        for (int i = 0; i < n; i++) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            double left = 10 + speed * (i / 240.0);
            for (int x = 0; x < w; x++) {
                // Box-filtered coverage of the pixel by the bar, so a sharp frame is exact.
                double cov = Math.max(0, Math.min(x + 1, left + barW) - Math.max(x, left));
                int v = encode(cov);
                for (int y = 0; y < h; y++) img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
            ImageIO.write(img, "png", new File(in, String.format("%06d.png", i)));
        }
        if (mutant.equals("--no-partial")) PARTIAL = false;
        if (mutant.equals("--srgb-average")) SRGB_AVERAGE = true;
        exposeForTest(in, out);
        // Analytic: output frame k at t = k/30, window [t - 1/120, t + 1/120] (180 deg centred).
        double worst = 0;
        for (int k = 2; k < 28; k++) {
            BufferedImage img = ImageIO.read(new File(out, String.format("true_%06d.png", k)));
            double t = k / 30.0, open = t - 1 / 120.0, close = t + 1 / 120.0;
            for (int x = 0; x < w; x++) {
                double lin = coverageOverWindow(x, open, close, speed, barW);
                int expect = encode(lin);
                int got = img.getRGB(x, 0) & 0xff;
                worst = Math.max(worst, Math.abs(got - expect));
            }
        }
        if (worst > 2) throw new AssertionError("exposure matches the analytic box smear: worst " + worst + "/255");
        System.out.println("MotionRef selftest: worst " + worst + "/255 against the analytic smear");
    }

    /** Linear coverage of pixel column x by the bar, averaged over the window (numeric). */
    private static double coverageOverWindow(int x, double open, double close, double speed, double barW) {
        int steps = 2000;
        double sum = 0;
        for (int s = 0; s < steps; s++) {
            double t = open + (s + 0.5) * (close - open) / steps;
            double left = 10 + speed * t;
            sum += Math.max(0, Math.min(x + 1, left + barW) - Math.max(x, left));
        }
        return sum / steps;
    }

    // Mutant switches for the self test only.
    private static boolean PARTIAL = true;
    private static boolean SRGB_AVERAGE = false;

    private static void exposeForTest(File in, File out) throws IOException {
        if (PARTIAL && !SRGB_AVERAGE) { expose(in, 240, 30, 180, -90, out); return; }
        File[] frames = in.listFiles((d, nm) -> nm.endsWith(".png"));
        Arrays.sort(frames);
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot create " + out);
        for (int k = 0; k < 30; k++) {
            double[] w = weights(frames.length, 240, 30, 180, -90, k / 30.0);
            if (w == null) continue;
            if (!PARTIAL) {
                double s = 0, max = 0;
                for (double v : w) max = Math.max(max, v);
                for (int i = 0; i < w.length; i++) { w[i] = w[i] > 0.99 * max ? 1 : 0; s += w[i]; }
                for (int i = 0; i < w.length; i++) w[i] /= s;
            }
            BufferedImage img = SRGB_AVERAGE ? averageCoded(frames, w) : average(frames, w);
            ImageIO.write(img, "png", new File(out, String.format("true_%06d.png", k)));
        }
    }

    private static BufferedImage averageCoded(File[] frames, double[] w) throws IOException {
        BufferedImage first = null;
        double[] acc = null;
        int width = 0, height = 0;
        for (int i = 0; i < w.length; i++) {
            if (w[i] == 0) continue;
            BufferedImage img = ImageIO.read(frames[i]);
            if (acc == null) { first = img; width = img.getWidth(); height = img.getHeight(); acc = new double[width * height]; }
            int[] px = img.getRGB(0, 0, width, height, null, 0, width);
            for (int p = 0; p < px.length; p++) acc[p] += w[i] * (px[p] & 0xff);
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < acc.length; p++) {
            int v = (int) Math.round(acc[p]);
            out.setRGB(p % width, p / width, (v << 16) | (v << 8) | v);
        }
        return out;
    }
}
