package com.fadcam.ui.faditor.puppet;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE HELPER — four controls on the picture, so mesh editing never needs the drawer.
 *
 * <p>JoyRaptor, 2026-09-15: <i>"the drawer is a good place for everything but annoying because it
 * covers the character — and unlike transform, which can be done from the drawer, with mesh edit
 * you NEED to click and manipulate in the preview window. So currently I am having to open and
 * close, selected, off layer, re-select. Too many clicks to toggle a state I switch between
 * often."</i>
 *
 * <p>That is a structural complaint, not a preference: a tool whose work happens on the picture
 * cannot have its controls on top of the picture. So the four things you reach for constantly
 * live here, the drawer keeps the other seventeen, and this strip gets out of the way.
 *
 * <h3>The grammar — every control does three or four jobs</h3>
 * <pre>
 *   SWATCH    tap: cycle the selected pin's type · hold: type ring · DRAG OUT: place a pin
 *   HAND      tap: pose &lt;-&gt; place · hold: lock every pin
 *   DIAMOND   tap: key here · hold: arm live record · SWIPE: jump to prev/next key
 *   Z         scrub: the selected pin's depth, with the number shown only while scrubbing
 * </pre>
 *
 * <h3>Two habits borrowed from things that already work</h3>
 * <ul>
 *   <li><b>It dodges like the loupe.</b> The loupe picks the corner furthest from the finger, and
 *       JoyRaptor named that as the best thing about it — so this does the same. It holds still
 *       while something is being dragged INTO it, because a bin that runs away is a cruel joke.</li>
 *   <li><b>It fades when a take FIRES</b>, not when one is armed. Armed is a state you want to
 *       SEE; the moment to get out of the way is when the playhead is rolling and a finger is on
 *       the puppet.</li>
 * </ul>
 *
 * <p>Colours and radii are SpriteLab's, via {@link PuppetPalette} — nothing here is a new hue.
 */
public class PuppetHelperView extends View {

    /** Everything this strip needs from the editor, and the whole list of it. */
    public interface Host {
        @Nullable PuppetRig rig();

        /** Index of the selected pin, or -1. */
        int selectedPin();

        /** Cycle the selected pin's type. */
        void cycleType();

        /** Set the selected pin's type outright (the ring). */
        void setType(@NonNull PuppetPin.Type type);

        boolean isPlaceMode();
        void togglePlaceMode();
        void toggleLockAll();

        /** Drop or remove a key for the selected pin at the playhead. */
        void toggleKeyAtPlayhead();
        boolean playheadIsOnKey();

        boolean isArmed();
        void toggleArmed();

        void jumpKey(boolean forward);

        /** 0..1 depth of the selected pin, or 0.5 when nothing is selected. */
        float depth();
        void setDepth(float v);

        /** Place a pin of {@code type} at this point in THIS VIEW's parent coordinates. */
        void placePinAt(float parentX, float parentY, @NonNull PuppetPin.Type type);

        /**
         * A drag-out is in flight at this parent point — show a ghost there, or hide it.
         *
         * <p>The strip is 58dp square and cannot draw outside itself, so the preview overlay
         * draws the ghost. Without it a drag-out is a guess: the pin appears where the finger
         * lifted and the user finds out afterwards.
         */
        void onPlaceDragMove(float parentX, float parentY, @Nullable PuppetPin.Type type);

        /** Repaint the preview and schedule a save. */
        void onChanged();
    }

    // ── geometry, in dp ──────────────────────────────────────────────────
    private static final float BTN = 44f;
    private static final float GAP = 7f;
    private static final float PAD = 7f;
    private static final float EDGE = 10f;
    private static final float R_BOX = 14f;
    private static final float R_BTN = 11f;
    private static final float HOLD_MS = 430f;
    private static final float SWIPE_DP = 26f;
    private static final float SCRUB_DP = 26f;

    private static final int N = 4;
    private static final int SWATCH = 0, HAND = 1, KEY = 2, DEPTH = 3;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF btn = new RectF();
    private final Path path = new Path();

    @Nullable private Host host;

    /** Which corner the strip is parked in: 0 BR, 1 BL, 2 TR, 3 TL. */
    private int corner = 0;
    private boolean faded;

    // gesture
    private int downIndex = -1;
    private float downX, downY;
    private long downAt;
    private boolean held, swiped, scrubbing;
    private float scrubBase;
    private long jumpFlashAt;
    private boolean jumpForward;
    private final float[] ringHit = new float[N * 2];
    private boolean ringOpen;

    public PuppetHelperView(@NonNull Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        setWillNotDraw(false);
    }

    public void setHost(@Nullable Host h) { host = h; invalidate(); }

    /** Dim while a take is actually running. */
    public void setFaded(boolean f) {
        if (f == faded) return;
        faded = f;
        animate().alpha(f ? 0.14f : 1f).setDuration(500).start();
    }

    public void refresh() { invalidate(); }

    /**
     * Move out of the way of a finger at {@code (x, y)} in this view's parent coordinates.
     *
     * @param hold true while something is being dragged INTO the strip — then it stays put
     */
    public void dodge(float x, float y, float parentW, float parentH, boolean hold) {
        if (hold || ringOpen) return;
        int want = (y > parentH / 2f ? 2 : 0) + (x > parentW / 2f ? 1 : 0);
        if (want != corner) { corner = want; requestLayout(); invalidate(); }
    }

    public int corner() { return corner; }

    /** Where the strip wants to sit, in parent pixels: {left, top}. */
    @NonNull
    public float[] parkAt(float parentW, float parentH) {
        float w = widthPx(), h = heightPx(), e = EDGE * d;
        float left = ((corner & 1) == 1) ? e : parentW - w - e;
        float top = ((corner & 2) == 2) ? e : parentH - h - e;
        return new float[]{left, top};
    }

    public float widthPx() { return BTN * d + 2 * PAD * d; }
    public float heightPx() { return N * BTN * d + (N - 1) * GAP * d + 2 * PAD * d; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(Math.round(widthPx()), Math.round(heightPx()));
    }

    // ── drawing ──────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas c) {
        if (host == null) return;
        PuppetRig rig = host.rig();

        box.set(0, 0, getWidth(), getHeight());
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xE0111114);                    // SpriteLab --drawer
        c.drawRoundRect(box, R_BOX * d, R_BOX * d, fill);
        stroke.setColor(0xFF2C2C35);                  // --line
        stroke.setStrokeWidth(d);
        c.drawRoundRect(box, R_BOX * d, R_BOX * d, stroke);

        for (int i = 0; i < N; i++) {
            rectFor(i, btn);
            boolean armedRing = (i == KEY) && host.isArmed();
            int tint = tintFor(i, rig);

            fill.setColor(i == downIndex ? 0xFF262630 : 0xFF1C1C22);   // --ctl2 / --ctl
            if (armedRing) fill.setColor(0xFF33141F);
            c.drawRoundRect(btn, R_BTN * d, R_BTN * d, fill);
            stroke.setColor(armedRing ? PuppetPalette.STIFF : 0xFF2C2C35);
            stroke.setStrokeWidth(d);
            c.drawRoundRect(btn, R_BTN * d, R_BTN * d, stroke);

            switch (i) {
                case SWATCH: drawSwatch(c, btn, rig); break;
                case HAND: drawHand(c, btn); break;
                case KEY: drawKey(c, btn, tint); break;
                default: drawDepth(c, btn); break;
            }
        }

        if (ringOpen) drawRing(c);
    }

    private int tintFor(int i, @Nullable PuppetRig rig) {
        if (i == SWATCH || i == KEY) {
            PuppetPin p = selectedPin(rig);
            return p == null ? 0xFFE4E4E7 : PuppetPalette.of(p.type);
        }
        return 0xFFE4E4E7;
    }

    @Nullable
    private PuppetPin selectedPin(@Nullable PuppetRig rig) {
        if (host == null || rig == null) return null;
        int i = host.selectedPin();
        return (i >= 0 && i < rig.pinCount()) ? rig.pin(i) : null;
    }

    private void rectFor(int i, @NonNull RectF out) {
        float x = PAD * d, y = PAD * d + i * (BTN + GAP) * d;
        out.set(x, y, x + BTN * d, y + BTN * d);
    }

    private void drawSwatch(@NonNull Canvas c, @NonNull RectF r, @Nullable PuppetRig rig) {
        PuppetPin p = selectedPin(rig);
        PuppetPin.Type t = p == null ? PuppetPin.Type.FREE : p.type;
        PuppetShapes.draw(c, t, r.centerX(), r.centerY(), 10f * d, fill, stroke, d);
    }

    private void drawHand(@NonNull Canvas c, @NonNull RectF r) {
        boolean place = host != null && host.isPlaceMode();
        int size = Math.round(21f * d);
        PuppetIcons.IconDrawable g = PuppetIcons.of(
                place ? PuppetIcons.PIN : PuppetIcons.GRAB,
                place ? PuppetPalette.HELPER : 0xFFE4E4E7, size);
        int l = Math.round(r.centerX() - size / 2f), t = Math.round(r.centerY() - size / 2f);
        g.setBounds(l, t, l + size, t + size);
        g.draw(c);
    }

    private void drawKey(@NonNull Canvas c, @NonNull RectF r, int tint) {
        boolean on = host != null && host.playheadIsOnKey();
        boolean armed = host != null && host.isArmed();
        float half = 9f * d;
        path.reset();
        path.moveTo(r.centerX(), r.centerY() - half);
        path.lineTo(r.centerX() + half, r.centerY());
        path.lineTo(r.centerX(), r.centerY() + half);
        path.lineTo(r.centerX() - half, r.centerY());
        path.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(armed ? PuppetPalette.STIFF : tint);
        if (on || armed) {
            c.drawPath(path, fill);
        } else {
            stroke.setColor(tint);
            stroke.setStrokeWidth(2f * d);
            c.drawPath(path, stroke);
        }

        // A jump flashes an arrow, so a swipe cannot be mistaken for a key landing.
        long since = System.currentTimeMillis() - jumpFlashAt;
        if (since < 420) {
            float a = 1f - (since / 420f);
            text.setColor((((int) (a * 255)) << 24) | 0x00FFFFFF);
            text.setTextSize(20f * d);
            c.drawText(jumpForward ? "›" : "‹",
                    r.centerX(), r.centerY() + 7f * d, text);
            postInvalidateOnAnimation();
        }
    }

    private void drawDepth(@NonNull Canvas c, @NonNull RectF r) {
        text.setColor(scrubbing ? PuppetPalette.HELPER : 0xFFE4E4E7);
        text.setTextSize(scrubbing ? 15f * d : 16f * d);
        if (scrubbing && host != null) {
            // The number only while a finger is on it: what matters is the picture reordering.
            c.drawText(String.valueOf(Math.round(host.depth() * 100f)),
                    r.centerX(), r.centerY() + 5.5f * d, text);
        } else {
            c.drawText("Z", r.centerX(), r.centerY() + 6f * d, text);
        }
    }

    private void drawRing(@NonNull Canvas c) {
        rectFor(SWATCH, btn);
        float cx = btn.centerX(), cy = btn.centerY(), rad = 62f * d;
        PuppetPin.Type[] types = PuppetPin.Type.values();
        for (int i = 0; i < types.length; i++) {
            // Fanned into the picture, away from whichever edge the strip is parked against.
            double a = Math.toRadians(((corner & 1) == 1 ? 20 : 160) + i * ((corner & 1) == 1 ? 26 : -26));
            float x = cx + (float) Math.cos(a) * rad, y = cy + (float) Math.sin(a) * rad;
            ringHit[i * 2] = x;
            ringHit[i * 2 + 1] = y;
            btn.set(x - 20f * d, y - 20f * d, x + 20f * d, y + 20f * d);
            fill.setColor(0xF01C1C22);
            c.drawRoundRect(btn, R_BTN * d, R_BTN * d, fill);
            stroke.setColor(0xFF2C2C35);
            stroke.setStrokeWidth(d);
            c.drawRoundRect(btn, R_BTN * d, R_BTN * d, stroke);
            PuppetShapes.draw(c, types[i], x, y, 9f * d, fill, stroke, d);
        }
    }

    // ── touch ────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent e) {
        if (host == null) return false;
        float x = e.getX(), y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (ringOpen) {
                    int picked = ringIndexAt(x, y);
                    ringOpen = false;
                    if (picked >= 0) {
                        host.setType(PuppetPin.Type.values()[picked]);
                        host.onChanged();
                    }
                    invalidate();
                    return true;
                }
                downIndex = indexAt(x, y);
                downX = x; downY = y; downAt = System.currentTimeMillis();
                held = false; swiped = false; scrubbing = false;
                if (downIndex == DEPTH) scrubBase = host.depth();
                invalidate();
                return downIndex >= 0;

            case MotionEvent.ACTION_MOVE: {
                if (downIndex < 0) return false;
                float dx = x - downX, dy = y - downY;

                if (downIndex == DEPTH) {
                    if (!scrubbing && Math.hypot(dx, dy) > 6 * d) scrubbing = true;
                    if (scrubbing) {
                        // Either axis: up and right both mean "towards the front", which is the
                        // only mapping that does not need explaining.
                        float delta = (dx - dy) / (SCRUB_DP * d) * 0.1f;
                        host.setDepth(clamp01(scrubBase + delta));
                        host.onChanged();
                        invalidate();
                    }
                    return true;
                }

                if (downIndex == KEY && !swiped && Math.abs(dx) > SWIPE_DP * d) {
                    swiped = true;
                    jumpForward = dx > 0;
                    jumpFlashAt = System.currentTimeMillis();
                    host.jumpKey(jumpForward);
                    invalidate();
                    return true;
                }

                if (downIndex == SWATCH && pendingType == null && !held
                        && Math.hypot(dx, dy) > 12 * d) {
                    // DRAG OUT — the strip is a source. It captured DOWN, so Android keeps
                    // delivering the whole stream here even once the finger is over the picture;
                    // the coordinates just have to be lifted into the parent's space.
                    PuppetRig rg = host.rig();
                    PuppetPin sp = selectedPin(rg);
                    pendingType = sp == null ? PuppetPin.Type.FREE : sp.type;
                }
                if (pendingType != null) {
                    host.onPlaceDragMove(getLeft() + x, getTop() + y, pendingType);
                    return true;
                }

                if (!held && System.currentTimeMillis() - downAt > HOLD_MS
                        && Math.hypot(dx, dy) < 10 * d) {
                    held = true;
                    onHold(downIndex);
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (pendingType != null) {
                    PuppetPin.Type t = pendingType;
                    pendingType = null;
                    host.onPlaceDragMove(0f, 0f, null);        // take the ghost down
                    host.placePinAt(getLeft() + x, getTop() + y, t);
                    downIndex = -1; held = false; swiped = false; scrubbing = false;
                    invalidate();
                    return true;
                }
                int idx = downIndex;
                boolean wasHeld = held, wasSwipe = swiped, wasScrub = scrubbing;
                downIndex = -1; held = false; swiped = false; scrubbing = false;
                invalidate();
                if (idx < 0) return false;
                if (wasHeld || wasSwipe) return true;
                if (wasScrub) { host.onChanged(); return true; }
                if (System.currentTimeMillis() - downAt > HOLD_MS) { onHold(idx); return true; }
                onTap(idx);
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                if (pendingType != null) { pendingType = null; host.onPlaceDragMove(0f, 0f, null); }
                downIndex = -1; held = false; swiped = false; scrubbing = false;
                invalidate();
                return true;

            default:
                return false;
        }
    }

    private void onTap(int i) {
        if (host == null) return;
        switch (i) {
            case SWATCH: host.cycleType(); break;
            case HAND: host.togglePlaceMode(); break;
            case KEY: host.toggleKeyAtPlayhead(); break;
            default: break;      // Z is scrub-only: a tap on it would mean nothing
        }
        host.onChanged();
        invalidate();
    }

    private void onHold(int i) {
        if (host == null) return;
        switch (i) {
            case SWATCH: ringOpen = true; break;
            case HAND: host.toggleLockAll(); break;
            case KEY: host.toggleArmed(); break;
            default: break;
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        host.onChanged();
        invalidate();
    }

    /** The type being dragged out of the strip, or null when nothing is. */
    @Nullable private PuppetPin.Type pendingType;

    public boolean isPlacing() { return pendingType != null; }

    private int indexAt(float x, float y) {
        for (int i = 0; i < N; i++) {
            rectFor(i, btn);
            if (btn.contains(x, y)) return i;
        }
        return -1;
    }

    private int ringIndexAt(float x, float y) {
        for (int i = 0; i < N; i++) {
            if (Math.hypot(ringHit[i * 2] - x, ringHit[i * 2 + 1] - y) < 24 * d) return i;
        }
        return -1;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
