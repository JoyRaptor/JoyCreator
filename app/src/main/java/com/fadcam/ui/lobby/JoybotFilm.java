package com.fadcam.ui.lobby;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * JOYBOT, ANIMATED — one spritesheet, played back a cell at a time.
 *
 * <p>JoyRaptor, 2026-09-18: <i>"his icon is white, but he will when large or in chat be an
 * animated claymation style avatar. I have two real spritesheets, im just trying to organize
 * them for you so that proper animation is easy later ... the idea is that he greets you as a
 * claymation and then he flies up into the corner and flattens into the white icon."</i>
 *
 * <p>This is the playback half of that, built so the art lands INTO something rather than
 * onto something. {@link JoybotView} owns the two white line-art stills and the transitions
 * between them; this owns a sheet and the question "which cell is showing".
 *
 * <h3>Why the sheet is never sliced into separate Bitmaps</h3>
 * The obvious implementation cuts a sheet into N bitmaps up front. For a claymation loop of,
 * say, 24 cells at 360×540 that is 18MB of ARGB_8888 held for the life of the screen, and it
 * duplicates memory the sheet already contains. Drawing a source RECT out of the one sheet
 * costs nothing extra per frame and holds exactly one bitmap.
 *
 * <h3>Frame rate is data, not a constant</h3>
 * Claymation is shot on twos or threes and that cadence is part of the performance — a sheet
 * authored at 12fps played back at 24 stops looking like stop-motion and starts looking like
 * a cartoon. So the rate travels with the sheet and the caller states it.
 */
public final class JoybotFilm {

    private final Bitmap sheet;
    private final int cols, rows, frames;
    private final int cellW, cellH;
    private final long framePeriodMs;
    private final boolean loop;
    /**
     * A SpriteLab animation instead of a straight run: each frame's source rectangle (so margins,
     * spacing and any frame order work) and how many ticks it is held. Null for the simple form.
     */
    @Nullable private final Rect[] cellRects;
    @Nullable private final int[] holds;
    private final int totalTicks;
    /** Ticks played once before the loop starts (the way INTO an action); 0 for a plain film. */
    private int introTicks = 0;
    /**
     * Where the character's body sits inside a cell, as fractions (left, top, right, bottom), when
     * the cell is bigger than he is to leave room for what he holds. Null = the whole cell is him.
     */
    @Nullable private RectF focus;
    /** True for the full-colour character: never tinted, and he leaves his orb while it plays. */
    private boolean actor = false;

    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private long startedAtMs = 0L;
    private boolean finished = false;

    /**
     * @param sheet  the spritesheet; cells run left to right, top to bottom
     * @param cols   cells across
     * @param rows   cells down
     * @param frames how many cells are actually USED — the last row is usually partly empty,
     *               and playing the blanks is the classic spritesheet bug
     * @param fps    the rate the sheet was authored at
     * @param loop   true for an idle cycle, false for a one-shot such as the greeting
     */
    public JoybotFilm(@NonNull Bitmap sheet, int cols, int rows, int frames, float fps,
                      boolean loop) {
        this.sheet = sheet;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.frames = Math.max(1, Math.min(frames, this.cols * this.rows));
        this.cellW = sheet.getWidth() / this.cols;
        this.cellH = sheet.getHeight() / this.rows;
        this.framePeriodMs = (long) (1000f / Math.max(1f, fps));
        this.loop = loop;
        this.cellRects = null;
        this.holds = null;
        this.totalTicks = this.frames;
    }

    /**
     * Play a named animation from a sprite sheet: {@code rects[i]} is frame i's place on the sheet
     * and {@code weights[i]} how many ticks it holds (1 = one tick; "on twos" = 2).
     */
    public JoybotFilm(@NonNull Bitmap sheet, @NonNull Rect[] rects, @Nullable int[] weights,
                      float fps, boolean loop) {
        this.sheet = sheet;
        this.cols = 1;
        this.rows = 1;
        this.frames = Math.max(1, rects.length);
        Rect first = rects.length > 0 ? rects[0] : new Rect(0, 0, sheet.getWidth(), sheet.getHeight());
        this.cellW = Math.max(1, first.width());
        this.cellH = Math.max(1, first.height());
        this.framePeriodMs = (long) (1000f / Math.max(1f, fps));
        this.loop = loop;
        this.cellRects = rects.length > 0 ? rects : new Rect[]{first};
        int[] h = new int[this.cellRects.length];
        int sum = 0;
        for (int i = 0; i < h.length; i++) {
            h[i] = weights != null && i < weights.length ? Math.max(1, weights[i]) : 1;
            sum += h[i];
        }
        this.holds = h;
        this.totalTicks = Math.max(1, sum);
    }

    /**
     * Play the first {@code frames} frames once, then loop the rest: the way into an action and
     * then the action. Only meaningful on a looping film.
     */
    @NonNull
    public JoybotFilm withIntro(int frames) {
        int t = 0;
        if (holds != null) for (int i = 0; i < Math.min(frames, holds.length - 1); i++) t += holds[i];
        introTicks = Math.max(0, Math.min(t, totalTicks - 1));
        return this;
    }

    // ── less loopy loops (owner, 11-15) ─────────────────────────────────────
    // "You can get loops to look less loopy by varying the frames that are next to each other ... if that is somewhat
    // randomized, or stuttered, he can type a lot more with random pauses, and it won't look like a loop at all."
    // "The painting, you can't really stutter ... but you can have it where he's always swiping one direction, or
    // swiping, mirror, then swiping another direction, or ping-ponging to get a variety of strokes."

    /** How the loop part plays. */
    public enum Style {
        /** As drawn, round and round. */
        LOOP,
        /** There and back. */
        PINGPONG,
        /** A random walk over neighbouring frames, mostly forward, with holds and the odd pause. */
        STUTTER,
        /** One fluid motion a pass at a time, forward or back, sometimes mirrored, now and then a breath between. */
        STROKES
    }

    private Style style = Style.LOOP;
    @Nullable private java.util.Random dice;
    /** The loop as it will play, tick by tick, from {@link #planFrom}: frame (0-based within the loop) | MIRROR. */
    private int[] plan = new int[96];
    private int planLen = 0, planFrom = 0, walkAt = 0;
    private static final int MIRROR = 1 << 16;
    private boolean mirrorNow = false;

    /** Play the loop part in {@code s} (only for an animation drawn from a list of frames, one tick each). */
    @NonNull
    public JoybotFilm withStyle(@Nullable Style s) {
        style = s != null ? s : Style.LOOP;
        if (style != Style.LOOP) dice = new java.util.Random();
        return this;
    }

    /** True while the frame being shown is mirrored (a stroke the other way, or the whole play facing the other way). */
    public boolean mirrored() { return mirrorNow ^ mirrorAll; }

    // ── choreography on top of the frames (owner, 11-15) ────────────────────
    /** Motion the view adds while this plays: see acts.json "moves". */
    public static final class Moves {
        public float scale = 1f;
        public float hopDp, hopMs;
        public float driftDp, driftMs;
        public boolean sparks;
        /** Starts with a backflip this time. */
        public boolean flip;
    }

    @Nullable private Moves moves;
    /** This whole play faces the other way (decided by whoever made it, so its way in, loop and way out agree). */
    private boolean mirrorAll;

    @NonNull
    public JoybotFilm withMoves(@Nullable Moves m, boolean mirror) {
        moves = m;
        mirrorAll = mirror;
        return this;
    }

    @Nullable public Moves moves() { return moves; }

    /** Set by the view for this draw: he faces the other way while drifting left (searching around). */
    private boolean facing;

    public void turnForDrift(boolean otherWay) { facing = otherWay; }

    public boolean mirroredPlay() { return mirrorAll; }

    /** When this play started (uptime ms). */
    public long startedAt() { return startedAtMs; }

    /** How long a one-shot plays, ms; 0 for a loop (it never ends). */
    public long onceMs() { return loop ? 0L : totalTicks * framePeriodMs; }

    private int loopFrames(int introFrames) { return cellRects == null ? 0 : cellRects.length - introFrames; }

    /** The loop's frame at loop tick {@code k}, writing the plan ahead as needed (and forgetting what has played). */
    private int planned(int k, int n) {
        if (k < planFrom) { planFrom = k; planLen = 0; }
        if (k - planFrom > 4096) {                         // keep the plan short: drop what has played
            int keep = Math.max(0, planLen - (k - planFrom));
            if (keep > 0) System.arraycopy(plan, k - planFrom, plan, 0, keep);
            planLen = keep;
            planFrom = k;
        }
        while (planFrom + planLen <= k) extend(n);
        return plan[k - planFrom];
    }

    private void put(int v) {
        if (planLen == plan.length) plan = java.util.Arrays.copyOf(plan, plan.length * 2);
        plan[planLen++] = v;
    }

    private void extend(int n) {
        java.util.Random r = dice != null ? dice : (dice = new java.util.Random());
        if (n <= 1) { put(0); return; }
        switch (style) {
            case PINGPONG:
                for (int i = 0; i < n; i++) put(i);
                for (int i = n - 2; i > 0; i--) put(i);
                return;
            case STUTTER: {
                float x = r.nextFloat();
                if (x < .62f) walkAt = (walkAt + 1) % n;                       // on
                else if (x < .76f) walkAt = (walkAt + n - 1) % n;              // back a frame
                else if (x < .92f) put(walkAt);                                // a hold
                else { int p = 3 + r.nextInt(8); for (int i = 0; i < p; i++) put(walkAt); }   // a pause
                put(walkAt);
                return;
            }
            case STROKES: {
                boolean back = r.nextInt(3) == 0, flip = r.nextInt(4) == 0;
                int m = flip ? MIRROR : 0;
                for (int i = 0; i < n; i++) put((back ? n - 1 - i : i) | m);
                if (r.nextInt(3) == 0) {                                       // a breath before the next stroke
                    int last = (back ? 0 : n - 1) | m, p = 1 + r.nextInt(3);
                    for (int i = 0; i < p; i++) put(last);
                }
                return;
            }
            default:
                for (int i = 0; i < n; i++) put(i);
        }
    }

    /** Mark this as the full-colour character and say where his body is inside a cell. */
    @NonNull
    public JoybotFilm asActor(@Nullable RectF bodyInCell) {
        actor = true;
        focus = bodyInCell;
        return this;
    }

    /** Something to do once a non-looping film has played out (the next link of a chain). */
    @Nullable private Runnable onEnd;
    /** Which frame the last draw showed; -1 before the first. Lets the view react to a new pose. */
    private int shown = -1;

    @NonNull
    public JoybotFilm thenRun(@Nullable Runnable r) { onEnd = r; return this; }

    /** Hands over the end action, once. */
    @Nullable
    public Runnable takeOnEnd() { Runnable r = onEnd; onEnd = null; return r; }

    public int shownFrame() { return shown; }

    public boolean isActor() { return actor; }
    @Nullable public RectF focus() { return focus; }

    public void start(long nowMs) {
        startedAtMs = nowMs;
        finished = false;
    }

    /** True once a non-looping film has played its last cell. */
    public boolean isFinished() { return finished; }

    public int cellWidth()  { return cellW; }
    public int cellHeight() { return cellH; }

    /**
     * Draw the cell for {@code nowMs} into {@code into}.
     *
     * <p>The frame is derived from ELAPSED TIME rather than incremented per draw. A counter
     * would make playback speed depend on how often the view happens to be invalidated, so
     * the same film would run at one speed on an idle screen and another while the timeline
     * behind it is being scrubbed.
     */
    public void draw(@NonNull Canvas canvas, @NonNull RectF into, @Nullable Paint paint,
                     long nowMs) {
        if (sheet.isRecycled()) return;
        long elapsed = Math.max(0L, nowMs - startedAtMs);
        int tick = (int) (elapsed / framePeriodMs);
        mirrorNow = false;
        int introFrames = introTicks;                       // styled films hold every frame one tick
        int n = loopFrames(introFrames);
        if (loop && style != Style.LOOP && cellRects != null && n > 0 && tick >= introTicks) {
            int v = planned(tick - introTicks, n);
            mirrorNow = (v & MIRROR) != 0;
            int i = introFrames + (v & 0xFFFF);
            src.set(cellRects[i]);
            shown = i;
            drawCell(canvas, into, paint);
            return;
        }
        if (tick >= totalTicks) {
            if (loop) {
                tick = introTicks + (tick - introTicks) % (totalTicks - introTicks);
            } else {
                tick = totalTicks - 1;
                finished = true;
            }
        }
        if (cellRects != null && holds != null) {
            int i = 0;
            while (i < holds.length - 1 && tick >= holds[i]) { tick -= holds[i]; i++; }
            src.set(cellRects[i]);
            shown = i;
        } else {
            int i = tick;
            shown = i;
            int cx = i % cols, cy = i / cols;
            src.set(cx * cellW, cy * cellH, (cx + 1) * cellW, (cy + 1) * cellH);
        }
        drawCell(canvas, into, paint);
    }

    /** The current source cell into {@code into}; mirrored about his body's own upright line when a stroke goes the other way. */
    private void drawCell(@NonNull Canvas canvas, @NonNull RectF into, @Nullable Paint paint) {
        dst.set(into);
        if (!(mirrorNow ^ mirrorAll ^ facing)) { canvas.drawBitmap(sheet, src, dst, paint); return; }
        float axis = focus != null ? dst.left + dst.width() * focus.centerX() : dst.centerX();
        int save = canvas.save();
        canvas.scale(-1f, 1f, axis, 0f);
        canvas.drawBitmap(sheet, src, dst, paint);
        canvas.restoreToCount(save);
    }
}
