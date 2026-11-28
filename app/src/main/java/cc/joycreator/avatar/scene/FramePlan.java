package cc.joycreator.avatar.scene;

/**
 * Where the frame sits on the stage's glass, and how the strip pushes it (a port of the pilot's {@code al-frame.js} geometry).
 * All numbers are dp. The frame is only a shape: it shows the picture's edges and tells export what to cut. It is as big as the
 * screen allows (the owner, 11-21: "don't leave scene space on the table"); the earlier room round it is a tick box ({@code air}).
 *
 * <p>The strip (Scene, Character, Shots) and the two round buttons are a DRAWER over the stage. Like Video Studio's transcript panel
 * ({@code FaditorEditorActivity.transcriptDrawerShiftPx}) it moves the picture away by the part of it the drawer would cover,
 * only as far as the picture's own room allows, and never makes it smaller; what is still covered stays under the see-through strip.
 * Where it comes in follows the frame's shape (Studio's {@code DrawerPlace}): upright frames get it from the side, wide ones from the
 * bottom, a square one from whichever side the screen has more room on.</p>
 */
public final class FramePlan {
    /** What the two round buttons need: a row under a wide frame, a column beside an upright one. */
    public static final double BAND = 44;
    public static final double GAP = 6;

    private FramePlan() {
    }

    /** A rectangle: x, y, w, h. */
    public static final class Rect {
        public final double x, y, w, h;

        public Rect(double x, double y, double w, double h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public double right() {
            return x + w;
        }

        public double bottom() {
            return y + h;
        }
    }

    /** The room the picture has: the stage less what its own tools and hint take (left, top, right, bottom). */
    public static Rect area(double stageW, double stageH, double left, double top, double right, double bottom) {
        return new Rect(left, top, Math.max(40, stageW - left - right), Math.max(80, stageH - top - bottom));
    }

    /** The biggest frame of this shape that fits the area; with {@code air} a margin all round (6% of its size and 14). */
    public static Rect fit(Rect a, double ar, boolean air) {
        double c = air ? 0.06 : 0, m = air ? 14 : 0;
        double fw = Math.max(24, Math.min((a.w - 2 * m) / (1 + 2 * c), (a.h - 2 * m) / (1 + 2 * c) * ar)), fh = fw / ar;
        return new Rect(a.x + (a.w - fw) / 2, a.y + (a.h - fh) / 2, fw, fh);
    }

    /** True when the strip comes in from the side (an upright frame, or a square one with more room at the sides). */
    public static boolean side(double ar, Rect a, boolean air) {
        if (ar < 0.999) return true;
        if (ar > 1.001) return false;
        Rect f = fit(a, ar, air);
        return a.w - f.w >= a.h - f.h;
    }

    /**
     * How far the frame moves for a drawer.
     *
     * @param sideStrip the strip is at the side (else the bottom)
     * @param stripFrom where the strip starts: its left edge (side) or its top edge (bottom); NaN when there is no strip
     * @param buttons the two round buttons are showing
     * @return {dx, dy}
     */
    public static double[] push(Rect f, Rect a, double stageW, double stageH, boolean sideStrip, double stripFrom, boolean buttons) {
        boolean up = !Double.isNaN(stripFrom);
        if (!up && !buttons) return new double[]{0, 0};
        if (sideStrip) {
            double free = (up ? stripFrom : stageW) - (buttons ? BAND : 0) - GAP, covered = Math.max(0, f.right() - free);
            return new double[]{-Math.min(covered, Math.max(0, f.x - a.x)), 0};
        }
        double free = (up ? stripFrom : stageH) - (buttons ? BAND : 0) - GAP, covered = Math.max(0, f.bottom() - free);
        return new double[]{0, -Math.min(covered, Math.max(0, f.y - a.y))};
    }

    /** The frame as the strip and buttons leave it. */
    public static Rect rect(Rect a, double ar, boolean air, double stageW, double stageH, double stripFrom, boolean buttons) {
        Rect f = fit(a, ar, air);
        double[] d = push(f, a, stageW, stageH, side(ar, a, air), stripFrom, buttons);
        return new Rect(f.x + d[0], f.y + d[1], f.w, f.h);
    }

    /** The push glides the way Studio's does (a decelerating 220 ms): call once a frame with where it should be. */
    public static final class Glide {
        public double x, y;
        private boolean started;

        public double[] to(double dx, double dy, double dt) {
            double k = started ? 1 - Math.exp(-14 * Math.min(0.1, dt)) : 1;
            started = true;
            x += (dx - x) * k;
            y += (dy - y) * k;
            if (Math.abs(dx - x) < 0.3) x = dx;
            if (Math.abs(dy - y) < 0.3) y = dy;
            return new double[]{x, y};
        }
    }
}
