package cc.joycreator.avatar.scene;

/**
 * The scene's shapes and numbers, one place (a port of the pilot's {@code al-scene.js}: SHAPES, frame, depth, place).
 * The stage is always 1920 x 1080; the frame is a window on it (16:9, 4:3, 1:1, 4:5, 9:16), so changing the shape never
 * moves anything. A thing's size follows where its feet stand: 1 at the front of the floor, {@code shrink} at the back.
 */
public final class SceneGeometry {
    public static final int W = 1920, H = 1080;
    /** The frame's shapes, in the order the button lists them, and their width over height. */
    public static final String[] SHAPES = {"16:9", "4:3", "1:1", "4:5", "9:16"};
    public static final double[] ASPECT = {16.0 / 9, 4.0 / 3, 1, 4.0 / 5, 9.0 / 16};

    private SceneGeometry() {
    }

    /** Width over height of a shape; an unknown one is 16:9 (the pilot's default). */
    public static double aspect(String shape) {
        for (int i = 0; i < SHAPES.length; i++) if (SHAPES[i].equals(shape)) return ASPECT[i];
        return 16.0 / 9;
    }

    /** The frame on the stage: {x, y, w, h, aspect}. It is as wide as the shape allows inside the stage's height. */
    public static double[] frame(String shape) {
        double ar = aspect(shape), fw = Math.min(W, H * ar);
        return new double[]{(W - fw) / 2, 0, fw, H, ar};
    }

    public static double clamp(double v, double a, double b) {
        return v < a ? a : v > b ? b : v;
    }

    /** How big a thing is drawn for where it stands: 1 at the front of the floor, {@code shrink} at the back. */
    public static double depth(double near, double far, double shrink, double y) {
        double t = (y / H - far) / (near - far);
        return shrink + (1 - shrink) * clamp(t, -0.35, 1.4);
    }

    /** Where a character's own drawing space lands on the stage: its feet (ground line, middle) on (x, y). */
    public static final class Place {
        public final double k, tx, ty, ax, ay, top;

        Place(double k, double tx, double ty, double ax, double ay, double top) {
            this.k = k;
            this.tx = tx;
            this.ty = ty;
            this.ax = ax;
            this.ay = ay;
            this.top = top;
        }
    }

    /**
     * @param s the item's own size (1 = as drawn); {@code x}, {@code y} where its feet stand
     * @param b the character's box in its own space {x0, y0, x1, y1}
     * @param axisX the character's middle line, or NaN (the middle of its box); {@code groundY} its ground line, or NaN (its bottom)
     */
    public static Place place(double x, double y, double s, double near, double far, double shrink, double[] b, double axisX, double groundY) {
        double ax = Double.isNaN(axisX) ? (b[0] + b[2]) / 2 : axisX, ay = Double.isNaN(groundY) ? b[3] : groundY;
        double k = 0.42 * H / Math.max(20, ay - b[1]) * (s == 0 ? 1 : s) * depth(near, far, shrink, y);
        return new Place(k, x - ax * k, y - ay * k, ax, ay, b[1]);
    }
}
