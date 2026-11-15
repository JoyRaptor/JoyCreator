package com.fadcam.ui.lobby.arrival;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Joybot's flight from the button he was called from to his seat in the chat: the path, how fast he
 * covers each part of it, and how he is turned and squashed at each moment.
 *
 * <p>A port of the design page's engine ({@code tasks/design/UI-Joybot-chat-arrival-options.html},
 * owner's picks of 15 Nov 2026). Everything is in dp, in the chat window's own coordinates, and is
 * a pure function of the time since the show began, so the UI thread (the drawer opening in his wake,
 * the title letters landing after him) and the GL thread (his picture and his effects) agree without
 * passing anything between them. {@link Player} adds the one thing that depends on history: which
 * way he faces.</p>
 *
 * <p>Owner, 14 Nov: "fly in head first, and then flip around and slow down and land like a SpaceX
 * rocket landing, or he'll land with a plop, or he'll drop straight down and kind of scoot". The
 * centre of rotation is his body; any time he is close to the ground he rights himself; the
 * reverse-thrust burst happens only on a steep last leg and is over before the end, never on a skim.</p>
 */
public final class Flight {

    public enum Approach { ROCKET, PLOP, SCOOT }
    /** Landing styles. The owner picked DOUBLE (15 Nov). */
    public enum Landing { GENTLE, CARTOON, DOUBLE }
    /** What his thruster is doing, which the effects read. */
    public enum Mode { FLY, BURST, COAST, HOVER, SKID, CALM }

    /** He leaves the button this long after the show starts. */
    public static final long T0 = 80L;

    /**
     * Depth (owner, 11-15 midday: "vary his scale through the loop-the-loops, so he sometimes seems to fly closer to us.
     * On the ground, or scooting along it, he is exactly his resting size"): where his path turns back against the way
     * he is travelling overall (the top of a loop) he swings toward us, up to this much bigger; it fades to nothing as
     * he nears the ground, so he touches down at his own size.
     */
    public static final float DEPTH = .3f;

    /** Where he is, how he is turned and what picture to show. Reused; never kept. */
    public static final class Pose {
        /** Body centre, dp. */
        public float x, y;
        /** Degrees, 0 = upright, about his body centre. */
        public float rot;
        /** Squash, about his hover peg. */
        public float sx = 1f, sy = 1f;
        /** Pop out of the button, about his body centre. */
        public float scale = 1f;
        /** 1 = as drawn (facing right), -1 = mirrored. */
        public int face = 1;
        /** True while he tumbles out of the button (the spin strip), else his home pose. */
        public boolean spinning;
        public int spinFrame;
        public Mode mode = Mode.FLY;
        /** Strength of the braking burn, 0..1. */
        public float k;
        /** True from the moment he touches down. */
        public boolean down;
        /** How he touched down, for the flash. */
        public Approach touch = Approach.PLOP;
        /** False before he leaves the button. */
        public boolean visible;
    }

    // ── the line ────────────────────────────────────────────────────────────

    /** A polyline with its running length and heading (degrees, 0 = heading up). */
    static final class Line {
        final float[] x, y, len, head;
        final float tot;

        Line(float[] x, float[] y) {
            this.x = x;
            this.y = y;
            int n = x.length;
            len = new float[n];
            for (int i = 1; i < n; i++) len[i] = len[i - 1] + (float) Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
            tot = len[n - 1] > 0f ? len[n - 1] : 1f;
            head = new float[n];
            float prev = Float.NaN;
            for (int m = 0; m < n; m++) {
                int a = Math.max(0, m - 1), b = Math.min(n - 1, m + 1);
                float h = (float) Math.toDegrees(Math.atan2(y[b] - y[a], x[b] - x[a])) + 90f;
                if (!Float.isNaN(prev)) {
                    while (h - prev > 180f) h -= 360f;
                    while (h - prev < -180f) h += 360f;
                }
                head[m] = h;
                prev = h;
            }
        }

        /** Point and heading at fraction u of the length: {x, y, heading}. */
        void at(float u, @NonNull float[] out) {
            float d = clamp(u, 0f, 1f) * tot;
            int lo = 0, hi = len.length - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (len[mid] < d) lo = mid; else hi = mid;
            }
            float span = len[hi] - len[lo];
            float f = span > 0f ? (d - len[lo]) / span : 0f;
            out[0] = lerp(x[lo], x[hi], f);
            out[1] = lerp(y[lo], y[hi], f);
            out[2] = lerp(head[lo], head[hi], f);
        }
    }

    /** Catmull-Rom through {@code pts} ({x0,y0,x1,y1,…}), {@code per} samples a span. */
    @NonNull
    static Line catmull(@NonNull float[] pts, int per) {
        int n = pts.length / 2;
        int count = (n - 1) * per + 1;
        float[] ox = new float[count], oy = new float[count];
        int o = 0;
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(0, i - 1), i3 = Math.min(n - 1, i + 2);
            float x0 = pts[i0 * 2], y0 = pts[i0 * 2 + 1], x1 = pts[i * 2], y1 = pts[i * 2 + 1];
            float x2 = pts[(i + 1) * 2], y2 = pts[(i + 1) * 2 + 1], x3 = pts[i3 * 2], y3 = pts[i3 * 2 + 1];
            for (int k = 0; k < per; k++) {
                float t = k / (float) per, t2 = t * t, t3 = t2 * t;
                ox[o] = .5f * ((2 * x1) + (-x0 + x2) * t + (2 * x0 - 5 * x1 + 4 * x2 - x3) * t2 + (-x0 + 3 * x1 - 3 * x2 + x3) * t3);
                oy[o] = .5f * ((2 * y1) + (-y0 + y2) * t + (2 * y0 - 5 * y1 + 4 * y2 - y3) * t2 + (-y0 + 3 * y1 - 3 * y2 + y3) * t3);
                o++;
            }
        }
        ox[o] = pts[(n - 1) * 2];
        oy[o] = pts[(n - 1) * 2 + 1];
        return new Line(ox, oy);
    }

    // ── the plan ────────────────────────────────────────────────────────────

    /** The braking leg: where on the path the burst runs, and whether he floats the rest of the way. */
    static final class Leg {
        final float a, b;
        final boolean hover;
        Leg(float a, float b, boolean hover) { this.a = a; this.b = b; this.hover = hover; }
    }

    public final boolean quick;
    public final Approach approach;
    public final Landing landing;
    /** Button centre and his body centre on the seat, dp. */
    public final float sx, sy, ex, ey;
    /** From his body centre down to his hover peg (the point that lands), dp. */
    public final float pegUp;
    /** How long the flight takes, from {@link #T0} to touchdown, ms. */
    public final long dur;
    final Line line;
    @Nullable final Leg leg;
    /** The way he travels overall, button to seat, as a heading (degrees, 0 = up). */
    final float chord;
    /**
     * How far his heading has turned back against {@link #chord} along the path (0 = with it, 1 = the opposite way),
     * smoothed along the path so his size swells and settles rather than jumping in a tight turn.
     */
    private final float[] back;
    private final float[] warp;
    private final float warpTotal;

    /**
     * @param uv    one of the owner's paths, normalised so it starts at (0,0) and ends at (1,1), or null for the hop
     * @param short_ the later-opens "show, but shorter"
     */
    public Flight(float sx, float sy, float ex, float ey, float pegUp, @Nullable float[] uv,
                  @NonNull Approach approach, @NonNull Landing landing, boolean short_) {
        this.sx = sx;
        this.sy = sy;
        this.ex = ex;
        this.ey = ey;
        this.pegUp = pegUp;
        this.quick = uv == null;
        this.approach = quick ? Approach.SCOOT : approach;
        this.landing = landing;
        this.chord = (float) Math.toDegrees(Math.atan2(ey - sy, ex - sx)) + 90f;
        if (quick) {
            line = catmull(new float[]{sx, sy, lerp(sx, ex, .55f), Math.min(sy, ey) + 40f, ex, ey}, 26);
        } else {
            // Drop and scoot: the path ends just beside his seat; the scoot covers the rest after landing.
            float endX = approach == Approach.SCOOT ? ex + 70f : ex;
            int n = uv.length / 2;
            float[] p = new float[n * 2];
            for (int i = 0; i < n; i++) {
                p[i * 2] = sx + uv[i * 2] * (endX - sx);
                p[i * 2 + 1] = sy + uv[i * 2 + 1] * (ey - sy);
            }
            line = catmull(p, 2);
        }
        if (quick || approach == Approach.SCOOT) leg = null;
        else if (approach == Approach.ROCKET) leg = new Leg(.7f, .95f, false);
        else leg = lastSteepLeg(line, ey + pegUp, sy, pegUp);
        back = backProfile(line, chord, 200, 14);
        int N = 240;
        warp = new float[N + 1];
        for (int i = 1; i <= N; i++) warp[i] = warp[i - 1] + (1f / N) / speedAt((i - .5f) / N);
        warpTotal = warp[N];
        // The hop: quick, but never so quick across a big window that he skips (about 1.2 dp/ms).
        if (quick) dur = Math.round(clamp(line.tot / 1.2f, 440f, 650f));
        else if (short_) dur = Math.round(800f * warpTotal);
        else dur = Math.round(clamp(line.tot / 1.55f, 650f, 1700f) * warpTotal);
    }

    /** {@link #back} sampled at {@code n + 1} points, box-smoothed twice over {@code half} samples either side. */
    @NonNull
    static float[] backProfile(@NonNull Line line, float chord, int n, int half) {
        float[] raw = new float[n + 1], p = new float[3];
        for (int i = 0; i <= n; i++) {
            line.at(i / (float) n, p);
            raw[i] = .5f - .5f * (float) Math.cos(Math.toRadians(p[2] - chord));
        }
        for (int pass = 0; pass < 2; pass++) {
            float[] out = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                float sum = 0f;
                int c = 0;
                for (int k = Math.max(0, i - half); k <= Math.min(n, i + half); k++) { sum += raw[k]; c++; }
                out[i] = sum / c;
            }
            raw = out;
        }
        return raw;
    }

    /** {@link #back} at fraction {@code u} of the path. */
    float backAt(float u) {
        float f = clamp(u, 0f, 1f) * (back.length - 1);
        int i = Math.min(back.length - 2, (int) f);
        return lerp(back[i], back[i + 1], f - i);
    }

    /** Touchdown, ms after the show starts. */
    public long tLand() { return T0 + dur; }

    /** He has stopped moving and turned to face front: the chat's own Joybot takes over. */
    public long tSettle() {
        Landing style = landingStyle();
        long land = style == Landing.DOUBLE ? 560L : style == Landing.CARTOON ? 400L : 300L;
        return tLand() + land + (approach == Approach.SCOOT && !quick ? 640L : 0L) + 40L;
    }

    /** Fraction of the path he has covered at {@code t} ms (UI thread uses this for the wake reveal). */
    public float progress(long t) {
        float u = clamp((t - T0) / (float) dur, 0f, 1f);
        return quick ? hopEase(u) : warpInv(u);
    }

    /** The hop's ease: a sine in and out, gentle enough at its fastest to stay one figure, not a skip. */
    static float hopEase(float u) { return (float) (.5 - .5 * Math.cos(Math.PI * clamp(u, 0f, 1f))); }

    /** His body centre at fraction {@code ue} of the path: {x, y, heading}. */
    public void at(float ue, @NonNull float[] out) { line.at(ue, out); }

    private Landing landingStyle() {
        if (quick) return Landing.GENTLE;
        if (approach == Approach.ROCKET) return Landing.GENTLE;
        if (approach == Approach.PLOP && landing == Landing.GENTLE) return Landing.CARTOON;
        return landing;
    }

    /**
     * The last steep run (heading mostly straight down) that ends late in the route, or low near the
     * ground: the owner's blue path goes down the side and then along the floor.
     */
    @Nullable
    static Leg lastSteepLeg(@NonNull Line line, float groundY, float topY, float pegUp) {
        int N = 200;
        float[] down = new float[N + 1], ys = new float[N + 1];
        float[] p = new float[3], q = new float[3];
        for (int i = 0; i <= N; i++) {
            float u = i / (float) N;
            line.at(Math.min(u, .994f), p);
            line.at(Math.min(u + .006f, 1f), q);
            float dx = q[0] - p[0], dy = q[1] - p[1], l = (float) Math.hypot(dx, dy);
            down[i] = dy / (l > 0f ? l : 1f);
            ys[i] = p[1] + pegUp;
        }
        int k = N;
        while (k > 0) {
            while (k > 0 && down[k] <= .72f) k--;
            if (k <= 0) break;
            int end = k;
            while (k > 0 && down[k - 1] > .55f) k--;
            int st = k;
            k--;
            boolean low = (groundY - ys[end]) < .22f * (groundY - topY);
            float fe = end / (float) N, fs = st / (float) N;
            if (fe - fs >= .035f && fe > .3f && (fe >= .8f || low)) {
                float b = Math.min(fe - .015f, .965f), a = Math.max(fs, b - .25f);
                if (b - a < .03f) continue;
                return new Leg(a, b, (1f - b) > .12f);
            }
        }
        return null;
    }

    /** How fast he covers the path at each point (1 = cruising): launch, braking burn, engines cut. */
    float speedAt(float u) {
        float s = u < .08f ? .45f + .55f * smooth(u / .08f) : 1f;
        boolean rocket = approach == Approach.ROCKET;
        if (leg != null) {
            if (u >= leg.a && u <= leg.b) s = Math.min(s, lerp(1f, rocket ? .16f : .24f, smooth((u - leg.a) / (leg.b - leg.a))));
            else if (u > leg.b) s = rocket ? .16f : leg.hover ? lerp(.24f, .6f, smooth((u - leg.b) / .06f))
                    : lerp(.24f, 1.15f, smooth((u - leg.b) / (1f - leg.b)));
        } else if (approach == Approach.SCOOT && !quick && u > .88f) {
            s = lerp(1f, .6f, smooth((u - .88f) / .12f));
        }
        return s;
    }

    private float warpInv(float f) {
        float target = clamp(f, 0f, 1f) * warpTotal;
        int lo = 0, hi = warp.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (warp[mid] < target) lo = mid; else hi = mid;
        }
        float span = warp[hi] - warp[lo];
        return (lo + (span > 0f ? (target - warp[lo]) / span : 0f)) / (warp.length - 1);
    }

    /** His turn along the path: out of the button with a flip, head first, then upright for the landing. */
    float rotAt(float u) {
        float[] p = new float[3];
        line.at(u, p);
        float hd = p[2];
        if (quick) return lerp(0f, -14f, (float) Math.sin(Math.PI * u));
        line.at(.1f, p);
        float h0 = p[2];
        if (u < .1f) return lerp(-360f, h0, easeIO(u / .1f));
        float flip = approach == Approach.PLOP ? .94f : approach == Approach.SCOOT ? .86f : .72f;
        if (u < flip) return hd;
        line.at(flip, p);
        float hf = p[2], up = Math.round(hf / 360f) * 360f;
        if (approach == Approach.ROCKET) return lerp(hf, up, easeIO(clamp((u - flip) / .12f, 0f, 1f)));
        return lerp(hf, up + (approach == Approach.SCOOT ? -14f : 0f), easeIO((u - flip) / (1f - flip)));
    }

    /** Squash keyframes {ms, sx, sy, dy(dp)}. */
    private static final float[][] GENTLE = {{0, 1.14f, .86f, 0}, {110, .96f, 1.05f, 0}, {220, 1.02f, .98f, 0}, {300, 1, 1, 0}};
    private static final float[][] CARTOON = {{0, 1.36f, .66f, 0}, {90, .82f, 1.22f, 0}, {200, 1.08f, .93f, 0}, {300, .98f, 1.03f, 0}, {400, 1, 1, 0}};
    private static final float[][] DOUBLE = {{0, 1.30f, .72f, 0}, {110, .86f, 1.18f, -8}, {250, .95f, 1.06f, -22}, {390, 1.20f, .82f, 0}, {470, .96f, 1.05f, 0}, {560, 1, 1, 0}};

    private static void landAt(@NonNull Landing style, float t, @NonNull float[] out) {
        float[][] k = style == Landing.DOUBLE ? DOUBLE : style == Landing.CARTOON ? CARTOON : GENTLE;
        out[0] = 1f; out[1] = 1f; out[2] = 0f;
        if (t >= k[k.length - 1][0]) return;
        for (int i = 0; i < k.length - 1; i++) {
            if (t >= k[i][0] && t < k[i + 1][0]) {
                float f = smooth((t - k[i][0]) / (k[i + 1][0] - k[i][0]));
                out[0] = lerp(k[i][1], k[i + 1][1], f);
                out[1] = lerp(k[i][2], k[i + 1][2], f);
                out[2] = lerp(k[i][3], k[i + 1][3], f);
                return;
            }
        }
    }

    // ── playing it ──────────────────────────────────────────────────────────

    /** Evaluates poses frame by frame. One per thread: it remembers which way he faces. */
    public static final class Player {
        private final Flight f;
        private int face = 1;
        private long lastT = -1L;
        private float lastX;
        private final float[] p = new float[3], land = new float[3];

        public Player(@NonNull Flight f) { this.f = f; }

        public void pose(long t, @NonNull Pose out) {
            out.visible = t >= T0;
            if (t < T0) { out.mode = Mode.FLY; out.down = false; return; }
            long tLand = f.tLand();
            if (t < tLand) { flying(t, out); return; }
            landed(t, tLand, out);
        }

        private void flying(long t, @NonNull Pose o) {
            float u = clamp((t - T0) / (float) f.dur, 0f, 1f);
            float ue = f.progress(t);
            f.line.at(ue, p);
            float r = f.rotAt(ue);
            Leg info = f.leg;
            boolean inBurst = info != null && ue >= info.a && ue <= info.b;
            boolean afterBurst = info != null && ue > info.b;
            float near2 = 0f, burnUp = 0f;
            if (!f.quick && ue > .3f) {
                // Any time he is close to the ground he rights himself, and he fires his reverse
                // thrusters feet-first: upright from just before the burn to touchdown.
                float gap = (f.ey + f.pegUp) - (p[1] + f.pegUp);
                near2 = smooth(1f - clamp((gap - 20f) / 130f, 0f, 1f));
                burnUp = info != null ? smooth((ue - (info.a - .08f)) / .08f) : 0f;
                r = lerp(r, Math.round(r / 360f) * 360f, Math.max(near2, burnUp));
            }
            float vx = lastT >= 0 && t > lastT ? (p[0] - lastX) / ((t - lastT) / 1000f) : 0f;
            lastT = t;
            lastX = p[0];
            float upW = !f.quick && ue > .3f ? Math.max(near2, burnUp) : 0f;
            if (upW > .5f && Math.abs(vx) > 60f) face = vx < 0f ? -1 : 1;
            float pop = clamp((t - T0) / 160f, 0f, 1f);
            float spd = (float) Math.sin(Math.PI * ue);
            float depth = 1f;
            if (!f.quick) {
                float away = smooth((ue - .1f) / .1f) * (1f - Math.max(near2, burnUp));
                depth = 1f + DEPTH * f.backAt(ue) * away;
            }
            o.x = p[0];
            o.y = p[1];
            o.rot = r;
            o.sx = 1f - .07f * spd;
            o.sy = 1f + .10f * spd;
            if (inBurst) { o.sx = 1.04f; o.sy = .96f; }
            else if (afterBurst && f.approach != Approach.ROCKET && !info.hover) { o.sx = .95f; o.sy = 1.07f; }
            o.scale = lerp(.35f, 1f, easeOut(pop)) * depth;
            o.face = face;
            o.spinning = !f.quick && ue < .1f;
            o.spinFrame = (int) ((t - T0) / 55L);
            o.mode = inBurst ? Mode.BURST
                    : afterBurst && f.approach != Approach.ROCKET ? (info.hover ? Mode.HOVER : Mode.COAST)
                    : Mode.FLY;
            o.k = inBurst ? 1f - .6f * (ue - info.a) / (info.b - info.a) : 0f;
            o.down = false;
            o.touch = f.approach;
            if (u >= 1f) o.mode = Mode.FLY;
        }

        private void landed(long t, long tLand, @NonNull Pose o) {
            float lt2 = t - tLand;
            float raw = lt2;
            Landing style = f.landingStyle();
            boolean yours = !f.quick;
            float scootX = 0f, scootLean = 0f;
            if (yours && f.approach == Approach.SCOOT) {
                // Drop and scoot: he lands beside his seat, then hovers across into it, leaning.
                float sk = clamp((lt2 - 120f) / 520f, 0f, 1f);
                scootX = 70f * (1f - easeIO(sk));
                scootLean = -12f * (float) Math.sin(Math.PI * sk);
                lt2 = Math.max(0f, lt2 - 640f) + (lt2 < 120f ? lt2 : 0f);
            }
            landAt(style, lt2, land);
            float rr = 0f;
            if (!yours) {
                float end = f.rotAt(1f);
                rr = end - Math.round(end / 360f) * 360f;
            }
            boolean skimming = yours && f.approach == Approach.SCOOT && scootX > 1f && raw > 100f;
            boolean settled = yours && f.approach == Approach.SCOOT ? raw > 660f : raw > 220f;
            if (skimming) face = -1; else if (settled) face = 1;
            o.x = f.ex + scootX;
            o.y = f.ey + land[2];
            o.rot = lerp(rr, 0f, smooth(lt2 / 160f)) + scootLean;
            o.sx = land[0];
            o.sy = land[1];
            o.scale = 1f;
            o.face = face;
            o.spinning = false;
            o.mode = skimming ? Mode.SKID : Mode.CALM;
            o.k = 0f;
            o.down = true;
            o.touch = f.quick ? Approach.SCOOT : f.approach;
        }
    }

    // ── easing ──────────────────────────────────────────────────────────────

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float clamp(float x, float a, float b) { return Math.max(a, Math.min(b, x)); }
    static float easeIO(float t) { return t < .5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f; }
    static float easeOut(float t) { return 1f - (float) Math.pow(1f - t, 3); }
    static float smooth(float t) { t = clamp(t, 0f, 1f); return t * t * (3f - 2f * t); }
    /** The drawer curve, cubic-bezier(.32,.72,0,1) near enough. */
    static float drawerCurve(float t) { return 1f - (float) Math.pow(1f - t, 4); }
}
