package cc.joycreator.handoff;

/**
 * Where Joybot is and what the screen is doing at each moment of a hand-off between rooms (FX-1).
 * Pure maths, no Android: checked on the PC by {@code tools/jvm-harness/HandoffMovesTest}.
 *
 * <p>A port of the owner's picks page ({@code tasks/design/UI-Handoff-transitions-options.html},
 * version 3, 16 Nov). His rulings, which this file exists to keep:</p>
 * <ul>
 *   <li>He travels far, in smooth arcs the eye can follow, and comes toward us out of his button:
 *       at least twice his resting size while he works.</li>
 *   <li>Whatever he grabs is pinned to his fists through the whole motion (the seam, the peeling
 *       corner, the bed sheet, the wipe's edge), and he turns with what he pulls.</li>
 *   <li>The Big Pull escalates: a small steep tug, a harder wider one, then the whole side goes.</li>
 *   <li>The Box Kick has a run-up from the far side.</li>
 *   <li>Waiting (for an answer, or for the next room to open) is never a freeze.</li>
 * </ul>
 *
 * <p>Positions are written on the page's 360 x 740 sheet and stretched to the real screen
 * ({@link Stage#x}, {@link Stage#y}); sizes are multiples of his resting size, never stretched.</p>
 */
final class HandoffMoves {

    private HandoffMoves() {}

    static final int PULL = 0, PEEL = 1, KICK = 2, VACUUM = 3, MODES = 4;
    static final float PI = (float) Math.PI, TAU = PI * 2f;

    /** Seconds: launch, the move itself, where it can wait (a fraction of the move), landing, the slap (a fraction of the landing). */
    private static final float[][] TIME = {
            {.50f, 1.05f, .50f, .65f, .85f},
            {.50f, 1.40f, .55f, .45f, .70f},
            {.50f, .80f, 0f, .65f, .85f},
            {.45f, 1.25f, .50f, .45f, .70f}};
    /** He salutes, then flies home to his button. */
    static final float HOME_S = 1.0f;
    /** Shares of the way home: when the salute starts, and when he leaves for his button. */
    static final float HOME_SALUTE = .2f, HOME_FLY = .5f;
    /** Flying out of his button to his seat by the question. */
    static final float OUT_S = .6f;

    static float launchS(int m) { return TIME[m][0]; }
    static float mainS(int m) { return TIME[m][1]; }
    static float holdAt(int m) { return TIME[m][2]; }
    static float landS(int m) { return TIME[m][3]; }
    static float slapAt(int m) { return TIME[m][4]; }

    /**
     * How long one beat of his waiting move lasts: he leaves the wait only at the end of a beat, where
     * the waiting pose and the move it rejoins are the same pose, so there is never a jump. Zero: any moment.
     */
    static float holdBeatS(int m) {
        return m == PULL ? TAU / 8f : m == KICK ? TAU / 7f : m == VACUUM ? PI / 8f : 0f;
    }

    // What he is doing, for the picture.
    static final int ACT_SPIN = 0, ACT_PULL = 1, ACT_OOPS = 2, ACT_SLAP = 3, ACT_SALUTE = 4, ACT_IDLE = 5, ACTS = 6;
    // How hard his thruster works, for the rings and sparks (as the arrival's flight modes).
    static final int FX_CALM = 0, FX_HOVER = 1, FX_FLY = 2, FX_BURST = 3;

    /** The pulling picture is drawn at this share of his usual cell (its frames are framed tighter). */
    static final float PULL_CELL = .87f;
    /** His fists in the pulling picture, from the middle of its cell, as a share of the cell: per frame. */
    static final float[][] FISTS = {{.394f, .060f}, {.393f, .066f}, {.401f, .063f}, {.399f, .065f}, {.385f, .070f},
            {.399f, .070f}, {.396f, .063f}, {.401f, .056f}, {.395f, .056f}};
    private static final float FIST_X = .395f, FIST_Y = .063f;
    /** Where his fists are while he clutches a load to his chest (the oops picture), from his body centre, as a share of his cell. */
    static final float CHEST_X = -.025f, CHEST_Y = -.005f;

    /** The real screen, and the real places the show starts, lands and ends. All dp. */
    static final class Stage {
        float w = 360f, h = 740f;
        /** His resting cell, dp (the chat's Joybot: a 64 dp body in a cell of about 85). */
        float cell = 85f;
        float btnX = 336f, btnY = 40f;
        boolean hasSeat;
        float seatX, seatY;
        /** The new item's rectangle in the next room. */
        float itemL = 56f, itemT = 598f, itemR = 340f, itemB = 630f;
        float homeX = 336f, homeY = 40f;
        /**
         * The move is played mirrored: the next room lies to the RIGHT of this one on the lobby dial, so it
         * comes in from the right (the moves are written for a room that comes in from the left). Everything
         * in this file stays in the move's own space; places are flipped on the way in ({@link #mx}) and
         * the drawing is flipped on the way out.
         */
        boolean mirror;
        /** A real screen x into the move's space, or back. */
        float mx(float realX) { return mirror ? w - realX : realX; }

        float x(float sheetX) { return sheetX * w / 360f; }
        float y(float sheetY) { return sheetY * h / 740f; }
        float itemCx() { return (itemL + itemR) * .5f; }
        float itemCy() { return (itemT + itemB) * .5f; }
        /** Where he stands to slap the item down: just above it. */
        float standX() { return clamp(itemCx(), .14f * w, .86f * w); }
        /** A thin item (a tape on a timeline): he stands on it. A big one (a whole sheet): he stands in it. */
        float standY() {
            float tall = itemB - itemT;
            return tall > 2.2f * cell ? itemT + .42f * tall : Math.max(.12f * h, itemT - .30f * cell);
        }
        /** The middle of the vacuum's wipe. */
        float c0x() { return .5f * w; }
        float c0y() { return .469f * h; }
        float sweepOut() { return Math.min(x(235f), .42f * h); }
        float sweepIn() { return x(26f); }
        /**
         * A wide window is a longer flight for the same sweep: he takes a little longer over it
         * rather than crossing a tablet in the time he crosses a phone held upright.
         */
        float pace() { return clamp((float) Math.pow((w / h) / (360f / 740f), .35), 1f, 1.5f); }
    }

    /** Joybot this frame. */
    static final class Pose {
        /** Body centre, dp. */
        float x, y;
        /** Times his resting size. */
        float sc = 1f;
        /** Radians, clockwise on screen. */
        float rot;
        /** Squash and stretch about his body. */
        float sx = 1f, sy = 1f;
        boolean flip, visible = true;
        int act = ACT_SPIN;
        /** His fists hold this point (dp): the picture is hung from it frame by frame. */
        boolean hand;
        float hx, hy;
        /** He clutches a load to his chest. */
        boolean carry;
        /** 0..1 how full the load is. */
        float load = 1f;
        int fx = FX_FLY;

        void reset() {
            sc = 1f; rot = 0f; sx = sy = 1f; flip = false; visible = true; act = ACT_SPIN;
            hand = false; carry = false; load = 1f; fx = FX_FLY;
        }

        void set(float px, float py, float scale) { x = px; y = py; sc = scale; }

        /** This pose as it is drawn on a screen {@code w} wide when the move is played mirrored. */
        void mirrored(Pose from, float w) {
            x = w - from.x; y = from.y; sc = from.sc; rot = -from.rot; sx = from.sx; sy = from.sy;
            flip = !from.flip; visible = from.visible; act = from.act; hand = from.hand;
            hx = w - from.hx; hy = from.hy; carry = from.carry; load = from.load; fx = from.fx;
        }
    }

    // ── small maths ─────────────────────────────────────────────────────────

    static float lerp(float a, float b, float u) { return a + (b - a) * u; }
    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    static float cl(float v) { return clamp(v, 0f, 1f); }
    static float ss(float a, float b, float v) { float t = cl((v - a) / (b - a)); return t * t * (3f - 2f * t); }
    static float io(float u) { u = cl(u); return u < .5f ? 2f * u * u : 1f - (float) Math.pow(-2f * u + 2f, 2) / 2f; }
    private static float sin(float a) { return (float) Math.sin(a); }
    private static float cos(float a) { return (float) Math.cos(a); }
    /** The waiting move eases in; it is left only on a beat, so it needs no ease out. */
    static float env(float ht) { return ss(0f, .2f, ht); }

    /** A smooth curve through points {x, y, size}; {@code n} of them are used. */
    static void curve(float[][] p, int n, float u, Pose o) {
        int last = n - 1;
        float f = cl(u) * last;
        int i = Math.min(last - 1, (int) Math.floor(f));
        float t = f - i;
        float[] a = p[Math.max(0, i - 1)], b = p[i], c = p[i + 1], d = p[Math.min(last, i + 2)];
        o.x = hermite(a[0], b[0], c[0], d[0], t);
        o.y = hermite(a[1], b[1], c[1], d[1], t);
        o.sc = hermite(a[2], b[2], c[2], d[2], t);
    }

    private static float hermite(float a, float b, float c, float d, float t) {
        return .5f * ((2f * b) + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t * t + (-a + 3f * b - 3f * c + d) * t * t * t);
    }

    // ── the shapes the screen makes ─────────────────────────────────────────

    /**
     * The Big Pull's seam: {amp, sig, p}. The seam is a bulge {@code amp} of the screen wide at his
     * fists and {@code sig} of the screen tall; {@code p} is how far the last haul has gone.
     */
    static void pullShape(float u, boolean hold, float ht, float[] out) {
        float a, amp, sig, p = 0f;
        if (hold) {
            amp = .08f + .17f * env(ht) * (.5f - .5f * cos(ht * 8f));
            sig = .17f;
        } else if (u < .2f) {                 // a first tug: a small, steep piece
            a = u / .2f;
            amp = .13f * sin(PI * a);
            sig = lerp(.055f, .09f, a);
        } else if (u < .5f) {                 // harder: wider, and it does not all go back
            a = (u - .2f) / .3f;
            amp = .30f * sin(PI * a) + .08f * a;
            sig = lerp(.09f, .17f, a);
        } else {                              // the whole side goes
            a = io((u - .5f) / .5f);
            amp = lerp(.08f, .94f, a);
            sig = lerp(.17f, .5f, a) + 5f * a * a * a;
            p = a;
        }
        out[0] = amp; out[1] = sig; out[2] = p;
    }

    /** The Vacuum Sweep: {e, sweep angle, how far what is left has collapsed}. */
    static void vacShape(float u, boolean hold, float ht, float[] out) {
        float e = cl(u / .92f);
        out[0] = e;
        // waiting, he hauls the edge back and forth a good hand's width: seen on the Note 9, a smaller tug read as a freeze
        out[1] = lerp(-.85f, 6.95f, e) + (hold ? .24f * sin(ht * 8f) * env(ht) : 0f);
        out[2] = (float) Math.pow(e, 1.6);
    }

    /** The bed sheet: how far the old room has been dragged back in, across the landing. */
    static float sheetAt(float landU, int m) {
        return ss(0f, 1f, (landU / slapAt(m) * .85f - .3f) / .55f);
    }

    // ── Joybot ──────────────────────────────────────────────────────────────

    /** He pulls with his fists on {@code hx, hy}: his body follows from there. */
    static void grab(Pose o, Stage s, float hx, float hy, float rot, boolean flip, float sc) {
        o.act = ACT_PULL;
        o.hand = true;
        o.hx = hx; o.hy = hy;
        o.rot = rot; o.flip = flip; o.sc = sc;
        float size = s.cell * PULL_CELL * sc;
        float dx = (flip ? FIST_X : -FIST_X) * size, dy = -FIST_Y * size;
        float c = cos(rot), sn = sin(rot);
        o.x = hx + dx * c - dy * sn;
        o.y = hy + dx * sn + dy * c;
        o.fx = FX_HOVER;
    }

    private static final float[] T3 = new float[3];
    private static final float[][] PTS = new float[6][3];

    private static void pt(int i, float x, float y, float sc) { PTS[i][0] = x; PTS[i][1] = y; PTS[i][2] = sc; }

    /** The move itself. {@code u} 0..1 through it; {@code hold}: he is waiting, {@code ht} seconds so far. */
    static void mainPose(Stage s, int m, float u, boolean hold, float ht, Pose o) {
        o.reset();
        if (m == PULL) {
            pullShape(u, hold, ht, T3);
            grab(o, s, T3[0] * s.w, .5f * s.h, 0f, true, 2.3f);
        } else if (m == PEEL) {
            float an = 2.4f + ((u - .3f) * mainS(PEEL) + ht) * 5.2f;
            float cx = s.x(180f) + cos(an) * s.x(72f), cy = s.y(360f) + sin(an) * s.y(112f), cs = 1.9f + .6f * sin(an);
            // his fists ride the lip of the curl (handoff/peel.frag folds the page along the same line)
            float a = ss(0f, .3f, u);
            float kk = cl(1.55f * (1f - (1.065f - a * 1.2f)) / 1.3025f);
            grab(o, s, (1f - .55f * kk) * s.w, (1f - kk) * s.h, 1.0f, false, 1.7f);
            if (u >= .3f) {
                float b = ss(.3f, .42f, u), c = hold ? 0f : ss(.8f, 1f, u);
                o.x = lerp(lerp(o.x, cx, b), s.x(180f), c);
                o.y = lerp(lerp(o.y, cy, b), s.y(300f), c);
                o.sc = lerp(lerp(1.7f, cs, b), 1.6f, c);
                o.act = ACT_OOPS; o.hand = false; o.rot = 0f; o.carry = true; o.fx = FX_FLY;
            }
        } else if (m == KICK) {
            if (hold) {                        // braced at the far side, rocking back
                o.set(s.x(50f) - s.x(16f) * env(ht) * (.5f - .5f * cos(ht * 7f)), s.y(390f), 2.2f);
                o.act = ACT_PULL; o.fx = FX_HOVER;
            } else if (u < .4f) {              // the run-up
                float a = (float) Math.pow(u / .4f, 2.2);
                o.set(lerp(s.x(50f), s.x(340f), a), s.y(390f) - sin(a * PI) * s.y(24f), 2.2f);
                o.rot = .3f; o.fx = FX_BURST;
                if (u < .08f) o.act = ACT_PULL;
            } else {                           // thrown back off the hit
                pt(0, s.x(340f), s.y(390f), 2.2f); pt(1, s.x(262f), s.y(270f), 2.0f);
                pt(2, s.x(160f), s.y(230f), 1.8f); pt(3, s.x(200f), s.y(330f), 1.7f);
                curve(PTS, 4, io((u - .4f) / .6f), o);
            }
        } else {
            vacShape(u, hold, ht, T3);
            float e = T3[0], th = -PI / 2f + T3[1] + .08f;
            float rh = lerp(s.sweepOut(), s.sweepIn(), (float) Math.pow(e, .8));
            // he hauls the wipe's edge round, turning with it, spiralling in to where the ball forms
            grab(o, s, s.c0x() + cos(th) * rh, s.c0y() + sin(th) * rh, th + PI / 2f, true, lerp(2.2f, 1.6f, e));
            o.load = e;
            float c = ss(.92f, 1f, u);
            if (c > 0f) {
                float size = s.cell * 1.6f;
                o.hand = false;
                o.x = lerp(o.x, s.c0x() - CHEST_X * size, c);
                o.y = lerp(o.y, s.c0y() - CHEST_Y * size, c);
                o.sc = 1.6f;
                if (c > .5f) { o.act = ACT_OOPS; o.rot = 0f; o.flip = false; o.carry = true; o.load = 1f; }
            }
        }
    }

    /** The big sweep in between: {x, y, size} on the page's sheet, before the move's own first pose. */
    private static final float[][][] SWEEP = {
            {{250, 230, 1.7f}, {290, 520, 2.6f}, {150, 640, 2.5f}, {84, 470, 2.3f}},
            {{200, 250, 1.6f}, {90, 420, 2.4f}, {220, 640, 2.2f}},
            {{240, 200, 1.6f}, {110, 160, 1.9f}, {40, 290, 2.2f}},
            {{230, 420, 2.0f}, {250, 200, 2.4f}, {120, 90, 2.3f}}};

    private static final Pose END = new Pose();

    /** From where he was ({@code fx, fy, fsc}: his seat by the question, or his button) into the move. */
    static void launchPose(Stage s, int m, float u, float fromX, float fromY, float fromSc, Pose o) {
        mainPose(s, m, 0f, holdAt(m) == 0f, 0f, END);
        float[][] mid = SWEEP[m];
        pt(0, fromX, fromY, fromSc);
        for (int i = 0; i < mid.length; i++) pt(i + 1, s.x(mid[i][0]), s.y(mid[i][1]), mid[i][2]);
        pt(mid.length + 1, END.x, END.y, END.sc);
        o.reset();
        curve(PTS, mid.length + 2, io(u), o);
        if (u > .9f && END.act == ACT_PULL) {      // he turns into the pose he grabs with
            o.act = ACT_PULL;
            o.flip = END.flip;
            o.rot = END.rot * ss(.9f, 1f, u);
        }
    }

    /** After the move: to the new item and the slap. Pull and Kick drag the old room back in like a bed sheet on the way. */
    static void landPose(Stage s, int m, float u, Pose o) {
        mainPose(s, m, 1f, false, 0f, END);
        float ex = END.x, ey = END.y, esc = END.sc;
        float sx = s.standX(), sy = s.standY(), slap = slapAt(m);
        o.reset();
        if (u >= slap) {
            o.set(sx, sy, 1f);
            o.act = ACT_SLAP; o.fx = FX_CALM;
            return;
        }
        if (m == PULL || m == KICK) {
            float edgeX = s.x(352f), edgeY = s.y(330f), grip = s.cell * PULL_CELL * 2.2f;
            if (u < .3f) {                     // back to the screen's edge
                pt(0, ex, ey, esc); pt(1, s.x(300f), s.y(215f), 2.0f);
                pt(2, edgeX - FIST_X * grip, edgeY - FIST_Y * grip, 2.2f);
                curve(PTS, 3, io(u / .3f), o);
                if (u > .24f) o.act = ACT_PULL;
            } else {                           // and round, down to the item, the sheet in his fists
                float a = (u - .3f) / (slap - .3f);
                float end = s.cell * PULL_CELL;
                float hx = sx + FIST_X * end, hy = sy + FIST_Y * end;
                arc(edgeX, edgeY, 2.2f, hx, hy, 1f, .16f, 2.3f);
                curve(PTS, 4, io(a), o);
                grab(o, s, o.x, o.y, .5f * sin(a * PI), false, o.sc);
            }
        } else {
            arc(ex, ey, esc, sx, sy, 1f, .14f, 1.7f);
            curve(PTS, 4, io(u / slap), o);
            o.act = ACT_OOPS; o.carry = true;
        }
    }

    /** A bowed path from a to b into PTS[0..3]: the two points between sit off the straight line by {@code bow} of its length. */
    private static void arc(float ax, float ay, float asc, float bx, float by, float bsc, float bow, float midSc) {
        float dx = bx - ax, dy = by - ay, len = (float) Math.hypot(dx, dy);
        float nx = len > 0f ? -dy / len : 0f, ny = len > 0f ? dx / len : 0f;
        if (ny < 0f) { nx = -nx; ny = -ny; }     // bow downward: he swoops, he does not hop
        pt(0, ax, ay, asc);
        pt(1, ax + dx * .33f + nx * len * bow, ay + dy * .33f + ny * len * bow, midSc);
        pt(2, ax + dx * .72f + nx * len * bow * .8f, ay + dy * .72f + ny * len * bow * .8f, lerp(midSc, bsc, .6f));
        pt(3, bx, by, bsc);
    }

    /** A salute where he stands, then home to his button in the new room. */
    static void homePose(Stage s, float u, Pose o) {
        float sx = s.standX(), sy = s.standY();
        o.reset();
        if (u < HOME_FLY) {                    // the slap follows through, then the salute
            o.set(sx, sy, 1f);
            o.act = u < HOME_SALUTE ? ACT_SLAP : ACT_SALUTE; o.fx = FX_CALM;
            return;
        }
        pt(0, sx, sy, 1f);
        pt(1, clamp(sx - .24f * s.w, .1f * s.w, .9f * s.w), clamp(sy - .2f * s.h, .1f * s.h, .9f * s.h), 1.9f);
        pt(2, clamp(s.homeX - .24f * s.w, .1f * s.w, .9f * s.w), clamp(s.homeY + .3f * s.h, .1f * s.h, .9f * s.h), 1.7f);
        pt(3, s.homeX, s.homeY, .3f);
        curve(PTS, 4, io((u - HOME_FLY) / (1f - HOME_FLY)), o);
    }

    /**
     * Out of his button to his seat by the question, then his neutral hover from the chat while he
     * waits ({@code JoybotBody}'s "Eve drift": a slow figure eight, leaning the way he drifts, a soft
     * squash at the bottom, 12 dp above his seat).
     */
    static void askPose(Stage s, float t, Pose o) {
        o.reset();
        pt(0, s.btnX, s.btnY, .3f);
        pt(1, clamp(s.btnX - .24f * s.w, .1f * s.w, .9f * s.w), clamp(s.btnY + .16f * s.h, .1f * s.h, .9f * s.h), 1.6f);
        pt(2, .42f * s.w, .45f * s.h, 2.4f);
        pt(3, clamp(s.seatX + .02f * s.w, .08f * s.w, .92f * s.w), s.seatY - .11f * s.h, 1.8f);
        pt(4, s.seatX, s.seatY, 1f);
        curve(PTS, 5, io(t / OUT_S), o);
        if (t >= OUT_S) {
            float h = t - OUT_S, k = ss(0f, .5f, h);
            float low = Math.max(0f, sin(TAU * h / 2.9f + .6f));
            o.act = ACT_IDLE; o.fx = FX_CALM;
            o.x += k * 2.6f * sin(TAU * h / 5.2f);
            o.y += k * (3.4f * sin(TAU * h / 2.9f + .6f) - 12f);
            o.rot = k * (float) Math.toRadians(3.0) * cos(TAU * h / 5.2f);
            o.sx = 1f + .025f * low * low;
            o.sy = 1f - .025f * low * low;
        }
    }

    /** The question was dismissed: straight back into the button he came out of, shrinking into it. */
    static final float RETREAT_S = .5f;

    static void retreatPose(Stage s, float u, float fromX, float fromY, float fromSc, Pose o) {
        o.reset();
        pt(0, fromX, fromY, fromSc);
        pt(1, lerp(fromX, s.btnX, .45f) + (.5f * s.w - lerp(fromX, s.btnX, .45f)) * .5f, lerp(fromY, s.btnY, .45f), 1.9f);
        pt(2, s.btnX, s.btnY, .3f);
        curve(PTS, 3, io(u), o);
    }

    /**
     * Carrying something heavy: the chat's "nervous" body (a fine shiver, leaning back), which the
     * owner picked for it on 16 Nov ("makes it look like he's got a lot of effort").
     */
    static void strain(Pose o, float t) {
        o.x += 1.2f * o.sc * sin(TAU * t * 9f);
        o.rot += (float) Math.toRadians(-4.0 + .8 * Math.sin(TAU * t * 7.0));
    }
}
