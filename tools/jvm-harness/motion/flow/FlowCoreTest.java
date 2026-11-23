import com.fadcam.ui.faditor.motion.flow.FlowConfidence;
import com.fadcam.ui.faditor.motion.flow.FlowField;
import com.fadcam.ui.faditor.motion.flow.GlobalMotionFit;
import com.fadcam.ui.faditor.motion.flow.LumaImage;
import com.fadcam.ui.faditor.motion.flow.PyramidFlowEstimator;

/**
 * STU-7 C6: the footage-flow core against synthetic scenes whose true motion is known exactly.
 *
 * <p>Frames are rendered from a continuous procedural texture, so a moved frame is the texture
 * evaluated at the inverse-moved point: no resampling error, and the ground-truth flow is the
 * motion itself. Each scene has a negative control (a mutant argument) that must fail it.
 *
 * <p>Mutants: --no-pyramid (one level), --no-mean-norm, --no-consistency, --plain-ls,
 * --wrong-time-scale.
 */
public final class FlowCoreTest {
    private static final int W = 160, H = 120;
    private final String mutant;
    private int assertions;

    private FlowCoreTest(String mutant) { this.mutant = mutant; }

    public static void main(String[] args) {
        FlowCoreTest t = new FlowCoreTest(args.length == 0 ? "" : args[0]);
        // A mutant runs only the scene it targets, so it must fail THAT scene's check.
        switch (t.mutant) {
            case "--no-pyramid": t.largeTranslation(); return;
            case "--no-mean-norm": t.brightnessChange(); return;
            case "--no-consistency": t.occlusion(); return;
            case "--plain-ls": t.globalFit(); return;
            case "--wrong-time-scale": t.timeScale(); return;
            default: break;
        }
        t.identity();
        t.smallTranslation();
        t.largeTranslation();
        t.zoomAndRoll();
        t.brightnessChange();
        t.occlusion();
        t.flatRegion();
        t.globalFit();
        t.timeScale();
        t.scaledTo();
        System.out.println("FlowCoreTest: " + t.assertions + " assertions");
    }

    // ---- scenes ---------------------------------------------------------------------------

    private void identity() {
        LumaImage a = render(0, (x, y) -> new double[]{x, y});
        FlowField f = estimator().estimate(a, a, 0, 33_367);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                check(f.u(x, y) == 0f && f.v(x, y) == 0f, "still footage gives exactly zero flow");
            }
        }
    }

    private void smallTranslation() {
        double tx = 3.4, ty = -1.7;
        FlowField f = translate(tx, ty, 1);
        double epe = epe(f, (x, y) -> new double[]{tx, ty}, 12);
        check(epe < 0.15, "small translation end-point error " + fmt(epe) + " < 0.15 px");
    }

    private void largeTranslation() {
        double tx = 17.0, ty = 9.0;
        FlowField f = translate(tx, ty, 1);
        double epe = epe(f, (x, y) -> new double[]{tx, ty}, 24);
        check(epe < 0.3, "large translation needs the pyramid: error " + fmt(epe) + " < 0.3 px");
    }

    private void zoomAndRoll() {
        double cx = W / 2.0, cy = H / 2.0, s = 1.05, rot = Math.toRadians(2.5);
        double cs = Math.cos(rot) * s, sn = Math.sin(rot) * s;
        Motion fwd = (x, y) -> new double[]{
                cx + cs * (x - cx) - sn * (y - cy), cy + sn * (x - cx) + cs * (y - cy)};
        LumaImage a = render(1, (x, y) -> new double[]{x, y});
        LumaImage b = render(1, inverseOf(fwd));
        FlowField f = estimator().estimate(a, b, 0, 33_367);
        double epe = epe(f, (x, y) -> {
            double[] p = fwd.at(x, y);
            return new double[]{p[0] - x, p[1] - y};
        }, 14);
        check(epe < 0.3, "zoom 1.05 + roll 2.5 deg: error " + fmt(epe) + " < 0.3 px");
    }

    private void brightnessChange() {
        double tx = 2.5, ty = 1.25;
        LumaImage a = render(2, (x, y) -> new double[]{x, y});
        LumaImage b = offset(render(2, (x, y) -> new double[]{x - tx, y - ty}), 0.12f);
        FlowField f = estimator().estimate(a, b, 0, 33_367);
        double epe = epe(f, (x, y) -> new double[]{tx, ty}, 12);
        check(epe < 0.2, "a flash (+0.12 brightness) must not throw the match: error " + fmt(epe));
    }

    private void occlusion() {
        // Static background; a 40x40 foreground square moves 6 px right.
        int rx = 50, ry = 40, rs = 40;
        double dx = 6;
        LumaImage a = composite(rx, ry, rs, 0);
        LumaImage b = composite(rx, ry, rs, dx);
        PyramidFlowEstimator est = estimator();
        FlowField fwd = est.estimate(a, b, 0, 33_367);
        FlowField bwd = est.estimate(b, a, 33_367, 0);
        FlowConfidence.Params cp = mutant.equals("--no-consistency")
                ? FlowConfidence.Params.DEFAULT.withoutConsistency() : FlowConfidence.Params.DEFAULT;
        FlowField conf = FlowConfidence.attach(fwd, bwd, a, b, cp);
        // Background strip just right of the square in frame A gets covered in frame B.
        double hidden = 0; int nh = 0;
        for (int y = ry + 4; y < ry + rs - 4; y++) {
            for (int x = rx + rs + 1; x < rx + rs + (int) dx - 1; x++) { hidden += conf.confidence(x, y); nh++; }
        }
        double open = 0; int no = 0;
        for (int y = 8; y < 30; y++) {
            for (int x = 8; x < W - 8; x++) { open += conf.confidence(x, y); no++; }
        }
        hidden /= nh;
        open /= no;
        check(hidden < 0.3, "covered background is not trusted: mean confidence " + fmt(hidden) + " < 0.3");
        check(open > 0.6, "open background is trusted: mean confidence " + fmt(open) + " > 0.6");
        // The square itself moves by dx; its interior flow is right.
        double sq = 0; int ns = 0;
        for (int y = ry + 8; y < ry + rs - 8; y++) {
            for (int x = rx + 8; x < rx + rs - 8; x++) {
                sq += Math.hypot(fwd.u(x, y) - dx, fwd.v(x, y)); ns++;
            }
        }
        check(sq / ns < 0.3, "moving square interior error " + fmt(sq / ns) + " < 0.3 px");
    }

    private void flatRegion() {
        float[] px = new float[W * H];
        java.util.Arrays.fill(px, 0.5f);
        LumaImage flat = new LumaImage(W, H, px);
        FlowField f = estimator().estimate(flat, flat, 0, 33_367);
        FlowField c = FlowConfidence.attach(f, f, flat, flat, FlowConfidence.Params.DEFAULT);
        double max = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) max = Math.max(max, c.confidence(x, y));
        check(max < 0.01, "a flat wall has no motion evidence: max confidence " + fmt(max));
    }

    private void globalFit() {
        // A pan-and-zoom field with a 35% region moving on its own (a person crossing).
        int n = W * H;
        float[] u = new float[n], v = new float[n], c = new float[n];
        java.util.Arrays.fill(c, 1f);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int k = y * W + x;
                boolean person = x < 56;
                u[k] = (float) (person ? 9.0 : 2.0 + 0.01 * x - 0.004 * y);
                v[k] = (float) (person ? -4.0 : -1.0 + 0.003 * x + 0.01 * y);
            }
        }
        FlowField f = new FlowField(W, H, u, v, c, 0, 33_367);
        GlobalMotionFit.Params p = mutant.equals("--plain-ls")
                ? GlobalMotionFit.Params.DEFAULT.withoutRansac() : GlobalMotionFit.Params.DEFAULT;
        GlobalMotionFit.Affine m = GlobalMotionFit.fit(f, p);
        double err = Math.abs(m.a0() - 2.0) + Math.abs(m.a1() - 0.01) + Math.abs(m.a2() + 0.004)
                + Math.abs(m.b0() + 1.0) + Math.abs(m.b1() - 0.003) + Math.abs(m.b2() - 0.01);
        check(err < 1e-3, "camera move ignores the person crossing: parameter error " + fmt(err));
        check(m.inlierFraction() > 0.6 && m.inlierFraction() < 0.7,
                "inlier fraction " + fmt(m.inlierFraction()) + " is the background share");
    }

    private void timeScale() {
        FlowField f = FlowField.zero(4, 4, 1_000_000, 1_033_367);   // a 29.97 fps pair
        long window = 16_683;                                        // 180 deg at 29.97
        double base = f.displacementScale(window, 1.0);
        double fast = f.displacementScale(window, 2.0);
        double rev = f.displacementScale(window, -1.0);
        boolean wrong = mutant.equals("--wrong-time-scale");
        check(Math.abs(base - 0.5) < 1e-3, "180 deg at 1x is half the frame-pair motion");
        check(Math.abs((wrong ? base : fast) - 1.0) < 1e-3, "2x speed smears twice as far");
        check(Math.abs(rev + 0.5) < 1e-3, "reverse flips the smear");
        check(f.displacementScale(window, 0) == 0, "a freeze frame has no motion");
        FlowField back = FlowField.zero(4, 4, 1_033_367, 1_000_000);
        check(Math.abs(back.displacementScale(window, 1.0) + 0.5) < 1e-3,
                "a backward field gives the opposite sign");
    }

    private void scaledTo() {
        int n = 8 * 6;
        float[] u = new float[n], v = new float[n];
        java.util.Arrays.fill(u, 1.5f);
        java.util.Arrays.fill(v, -0.5f);
        FlowField f = new FlowField(8, 6, u, v, null, 0, 1).scaledTo(32, 24);
        check(Math.abs(f.u(10, 10) - 6.0f) < 1e-5 && Math.abs(f.v(10, 10) + 2.0f) < 1e-5,
                "vectors scale with the grid");
    }

    // ---- helpers --------------------------------------------------------------------------

    private interface Motion { double[] at(double x, double y); }

    private PyramidFlowEstimator estimator() {
        PyramidFlowEstimator.Settings s = PyramidFlowEstimator.Settings.DEFAULT;
        if (mutant.equals("--no-pyramid")) s = s.withMaxLevels(1);
        if (mutant.equals("--no-mean-norm")) s = s.withMeanNormalise(false);
        return new PyramidFlowEstimator(s);
    }

    private FlowField translate(double tx, double ty, int seed) {
        LumaImage a = render(seed, (x, y) -> new double[]{x, y});
        LumaImage b = render(seed, (x, y) -> new double[]{x - tx, y - ty});
        return estimator().estimate(a, b, 0, 33_367);
    }

    private static Motion inverseOf(Motion fwd) {
        // Fixed-point inverse; the motions here are small and smooth.
        return (x, y) -> {
            double px = x, py = y;
            for (int i = 0; i < 30; i++) {
                double[] q = fwd.at(px, py);
                px += x - q[0];
                py += y - q[1];
            }
            return new double[]{px, py};
        };
    }

    /** Frame whose pixel p shows the texture at {@code lookup(p)}. */
    private static LumaImage render(int seed, Motion lookup) {
        float[] px = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double[] q = lookup.at(x, y);
                px[y * W + x] = (float) texture(seed, q[0], q[1]);
            }
        }
        return new LumaImage(W, H, px);
    }

    private static LumaImage composite(int rx, int ry, int rs, double dx) {
        float[] px = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean fg = x >= rx + dx && x < rx + rs + dx && y >= ry && y < ry + rs;
                px[y * W + x] = (float) (fg ? texture(7, x - dx, y) : texture(3, x, y));
            }
        }
        return new LumaImage(W, H, px);
    }

    private static LumaImage offset(LumaImage img, float add) {
        float[] px = new float[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = img.at(x, y) + add;
        return new LumaImage(W, H, px);
    }

    /** Smooth multi-octave value noise in roughly 0.2..0.8. */
    private static double texture(int seed, double x, double y) {
        double sum = 0, amp = 0.5, total = 0;
        for (int o = 0; o < 4; o++) {
            double period = 24.0 / (1 << o);
            sum += amp * valueNoise(seed * 31 + o, x / period, y / period);
            total += amp;
            amp *= 0.55;
        }
        return 0.2 + 0.6 * (sum / total);
    }

    private static double valueNoise(int seed, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = smooth(x - x0), fy = smooth(y - y0);
        double a = hash(seed, x0, y0), b = hash(seed, x0 + 1, y0);
        double c = hash(seed, x0, y0 + 1), d = hash(seed, x0 + 1, y0 + 1);
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    private static double hash(int seed, int x, int y) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private static double epe(FlowField f, Motion truth, int margin) {
        double sum = 0; int n = 0;
        for (int y = margin; y < H - margin; y++) {
            for (int x = margin; x < W - margin; x++) {
                double[] t = truth.at(x, y);
                sum += Math.hypot(f.u(x, y) - t[0], f.v(x, y) - t[1]);
                n++;
            }
        }
        return sum / n;
    }

    private static String fmt(double d) { return String.format("%.4f", d); }

    private void check(boolean ok, String what) {
        assertions++;
        if (!ok) throw new AssertionError(what);
    }
}
