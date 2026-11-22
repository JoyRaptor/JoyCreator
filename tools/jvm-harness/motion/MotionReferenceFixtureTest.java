import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionItemAdapters;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** High-sample CPU reference. Source art is decoded to linear, premultiplied RGBA before filtering. */
public final class MotionReferenceFixtureTest {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 80;
    private static final long CENTER_US = 100_000;
    private static final MotionShutter.FrameRate FPS = new MotionShutter.FrameRate(25, 1);
    private static final BufferedImage ART = artwork();
    private static final float[] LINEAR_ART = linearArt(ART);
    private static final MotionBreaks NO_BREAKS = MotionBreaks.none();

    private interface Sampler { Transform at(long timeUs); }
    private record Transform(double a, double b, double c, double d, double x, double y) {
        static Transform pose(double x, double y, double scale, double angle) {
            double cos = Math.cos(angle) * scale;
            double sin = Math.sin(angle) * scale;
            return new Transform(cos, -sin, sin, cos, x, y);
        }

        Transform then(Transform child) {
            return new Transform(a * child.a + b * child.c, a * child.b + b * child.d,
                    c * child.a + d * child.c, c * child.b + d * child.d,
                    a * child.x + b * child.y + x, c * child.x + d * child.y + y);
        }
    }

    private record Metrics(double mass, double centerX, double centerY, double spreadX,
                           double spreadY) {}

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args.length == 0 ? "tools/jvm-harness/out-motion" : args[0], "reference");
        String mutant = args.length > 1 ? args[1] : "";
        Files.createDirectories(output);

        Sampler slow = at -> Transform.pose(48 + delta(at) * 2, 40, 1, 0);
        Sampler fast = at -> Transform.pose(48 + delta(at) * (mutant.equals("slide") ? 2 : 19), 40, 1, 0);
        var thin = metrics(fixture(output, "slide-slow", slow, NO_BREAKS));
        var wide = metrics(fixture(output, "slide-fast", fast, NO_BREAKS));
        check(wide.spreadX() > thin.spreadX() + 3, "fast slide spreads farther");
        check(Math.abs(wide.mass() - thin.mass()) < 1.1, "slide preserves integrated coverage");

        var zoom = metrics(fixture(output, "zoom", at -> Transform.pose(48, 40,
                mutant.equals("zoom") ? 1 : 2 + delta(at), 0), NO_BREAKS));
        check(zoom.mass() > thin.mass() * 3.5 && zoom.mass() < thin.mass() * 5.3,
                "zoom must grow coverage about its pivot");
        check(Math.abs(zoom.centerX() - 48) < 2, "zoom stays near the pivot");
        var authored = new SpriteOverlayItem("reference-zoom", "reference-texture");
        authored.setCenter(.5f, .5f);
        authored.getKeyframes().getOrCreate(KeyframeSet.SCALE).put(90, .1f, Easing.LINEAR);
        authored.getKeyframes().getOrCreate(KeyframeSet.SCALE).put(110, .3f, Easing.LINEAR);
        ItemMotionSampler spriteZoom = MotionItemAdapters.sprite(authored, WIDTH, HEIGHT, 2.5f);
        var studioZoom = metrics(fixture(output, "studio-sprite-zoom",
                timeUs -> fromUnitQuad(spriteZoom.at(timeUs)), NO_BREAKS));
        check(Math.abs(studioZoom.mass() - zoom.mass()) < 0.1, "Studio zoom matches reference coverage");

        var spin = metrics(fixture(output, "spin", at -> Transform.pose(48, 40, 1,
                mutant.equals("spin") ? 0 : delta(at) * Math.PI / 2), NO_BREAKS));
        check(spin.spreadY() > thin.spreadY() + 1, "spin sweeps the narrow axis");

        var linked = metrics(fixture(output, "parent-child", at -> {
            Transform parent = Transform.pose(44 + delta(at) * 4, 38, 1, delta(at) * 0.8);
            Transform child = Transform.pose(17, 0, 1, 0.03);
            return mutant.equals("parent") ? child : parent.then(child);
        }, NO_BREAKS));
        check(linked.centerX() > 55 && Math.abs(linked.centerY() - 38) < 2,
                "child is drawn in the moving parent's space");

        var hold = new KeyframeTrack("x");
        hold.put(0, 18, Easing.HOLD);
        hold.put(100, 68, Easing.LINEAR);
        var cut = new MotionBreaks.Builder().addSteps(hold, 0).build();
        Sampler teleport = at -> Transform.pose(hold.valueAt(at / 1000.0, 0), 40, 1, 0);
        var after = fixture(output, "teleport", teleport, mutant.equals("teleport") ? NO_BREAKS : cut);
        check(metrics(after).centerX() > 65 && (after.getRGB(18, 40) >>> 24) == 0,
                "teleport must never smear across a hold boundary");

        Sampler still = at -> Transform.pose(48 + (mutant.equals("still") ? delta(at) * 3 : 0), 40, 1, 0);
        var stillImage = fixture(output, "still", still, NO_BREAKS);
        var sharp = render(at -> Transform.pose(48, 40, 1, 0), NO_BREAKS, 1);
        check(pixelExact(stillImage, sharp), "still image is bit exact");
        System.out.println("MotionReferenceFixtureTest: 8 images, 128/256 convergence and motion assertions passed");
    }

    private static Transform fromUnitQuad(ItemMotionSampler.Affine a) {
        return new Transform(a.xx() / ART.getWidth(), a.xy() / ART.getHeight(),
                a.yx() / ART.getWidth(), a.yy() / ART.getHeight(), a.x(.5, .5), a.y(.5, .5));
    }

    private static double delta(long timeUs) { return (timeUs - CENTER_US) / 10_000.0; }

    private static BufferedImage fixture(Path dir, String name, Sampler sampler, MotionBreaks breaks)
            throws IOException {
        var coarse = render(sampler, breaks, 128);
        var fine = render(sampler, breaks, 256);
        double difference = meanAlphaDifference(coarse, fine);
        check(difference < 0.0015, name + " failed alpha convergence");
        check(ImageIO.write(fine, "png", dir.resolve(name + ".png").toFile()), "PNG writer unavailable");
        var m = metrics(fine);
        System.out.printf(Locale.ROOT, "%s coverage=%.3f meanAlphaDelta=%.7f center=(%.3f,%.3f)%n",
                name, m.mass(), difference, m.centerX(), m.centerY());
        return fine;
    }

    private static BufferedImage render(Sampler sampler, MotionBreaks breaks, int count) {
        var shutter = MotionShutter.sample(CENTER_US, FPS, 180, -90, MotionShutter.Shape.BOX,
                count, breaks);
        double[] rgba = new double[WIDTH * HEIGHT * 4];
        for (int sample = 0; sample < shutter.timesUs().length; sample++) {
            long at = shutter.timesUs()[sample];
            double weight = shutter.weights()[sample];
            Transform t = sampler.at(at);
            double determinant = t.a * t.d - t.b * t.c;
            if (Math.abs(determinant) < 1e-9) continue;
            for (int py = 0; py < HEIGHT; py++) {
                for (int px = 0; px < WIDTH; px++) {
                    double dx = px + 0.5 - t.x;
                    double dy = py + 0.5 - t.y;
                    double sx = (t.d * dx - t.b * dy) / determinant + ART.getWidth() / 2.0 - 0.5;
                    double sy = (t.a * dy - t.c * dx) / determinant + ART.getHeight() / 2.0 - 0.5;
                    addBilinear(rgba, (py * WIDTH + px) * 4, sx, sy, weight);
                }
            }
        }
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int offset = (y * WIDTH + x) * 4;
                double alpha = rgba[offset + 3];
                int alphaByte = byteValue(alpha);
                if (alphaByte == 0) continue;
                int red = byteValue(encoded(rgba[offset] / alpha));
                int green = byteValue(encoded(rgba[offset + 1] / alpha));
                int blue = byteValue(encoded(rgba[offset + 2] / alpha));
                image.setRGB(x, y, (alphaByte << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private static void addBilinear(double[] target, int offset, double x, double y, double weight) {
        if (x <= -1 || y <= -1 || x >= ART.getWidth() || y >= ART.getHeight()) return;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double u = x - x0;
        double v = y - y0;
        add(target, offset, x0, y0, weight * (1 - u) * (1 - v));
        add(target, offset, x0 + 1, y0, weight * u * (1 - v));
        add(target, offset, x0, y0 + 1, weight * (1 - u) * v);
        add(target, offset, x0 + 1, y0 + 1, weight * u * v);
    }

    private static void add(double[] target, int offset, int x, int y, double weight) {
        if (x < 0 || y < 0 || x >= ART.getWidth() || y >= ART.getHeight() || weight == 0) return;
        int source = (y * ART.getWidth() + x) * 4;
        for (int channel = 0; channel < 4; channel++) {
            target[offset + channel] += weight * LINEAR_ART[source + channel];
        }
    }

    private static BufferedImage artwork() {
        var image = new BufferedImage(20, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (x == 0 || y == 0 || x == 19 || y == 7) ? 110 : 255;
                int rgb = (x + 2 * y) % 5 == 0 ? 0xffcf24 : 0xf02278;
                image.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        return image;
    }

    private static float[] linearArt(BufferedImage image) {
        float[] pixels = new float[image.getWidth() * image.getHeight() * 4];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int value = image.getRGB(x, y);
                int offset = (y * image.getWidth() + x) * 4;
                float alpha = (value >>> 24) / 255f;
                pixels[offset + 3] = alpha;
                for (int c = 0; c < 3; c++) {
                    double encoded = ((value >> (16 - c * 8)) & 255) / 255.0;
                    pixels[offset + c] = (float) (alpha * decoded(encoded));
                }
            }
        }
        return pixels;
    }

    private static Metrics metrics(BufferedImage image) {
        double mass = 0, sx = 0, sy = 0, xx = 0, yy = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                double a = (image.getRGB(x, y) >>> 24) / 255.0;
                double px = x + 0.5;
                double py = y + 0.5;
                mass += a;
                sx += a * px;
                sy += a * py;
                xx += a * px * px;
                yy += a * py * py;
            }
        }
        double cx = sx / mass;
        double cy = sy / mass;
        return new Metrics(mass, cx, cy, Math.sqrt(xx / mass - cx * cx),
                Math.sqrt(yy / mass - cy * cy));
    }

    private static double meanAlphaDifference(BufferedImage a, BufferedImage b) {
        long total = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                total += Math.abs((a.getRGB(x, y) >>> 24) - (b.getRGB(x, y) >>> 24));
            }
        }
        return total / (255.0 * WIDTH * HEIGHT);
    }

    private static boolean pixelExact(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    private static double decoded(double x) { return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }
    private static double encoded(double x) { return x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055; }
    private static int byteValue(double x) { return (int) Math.round(255 * Math.min(1, Math.max(0, x))); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
