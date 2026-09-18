package com.fadcam.ui.faditor.move;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A variable-speed jog/shuttle for moving the selected object along the timeline
 * (SPEC_OBJECT_TIME_SCRUBBER §1). Drag the thumb off centre and hold: the object travels at a
 * velocity that grows NON-LINEARLY with displacement — a hair off centre nudges frame-by-frame,
 * a big deflection covers long distances. Release and the thumb eases back to centre while the
 * motion glides to a stop (inertial), so nothing snaps.
 *
 * <p><b>Smoothness contract (user requirement — "buttery, not jarring"):</b> motion is driven by
 * a {@link Choreographer} frame callback, so travel is integrated against real frame time (dt)
 * and is frame-synced rather than event-rate-dependent. The spring-back to centre is a physically
 * eased decay, and it keeps ticking so the object DECELERATES to a stop instead of halting the
 * instant the finger lifts.</p>
 *
 * <p>The widget is deliberately dumb about the timeline: it reports integrated {@code deltaMs}
 * per frame via {@link Listener#onScrubTick(long)} and a single {@link Listener#onScrubEnd()}
 * once the finger is up AND the glide has settled. The host integrates those deltas into a
 * desired start and runs it through {@link ObjectTimeMover}.</p>
 */
public final class TimeShuttleView extends View {

    public interface Listener {
        /** Called each frame while engaged/gliding with the ms the object should advance. */
        void onScrubTick(long deltaMs);
        /** Called once the finger is up and the inertial glide has settled at centre. */
        void onScrubEnd();
        /** Called on the very first touch-down, before any tick (host snapshots for undo). */
        void onScrubStart();
    }

    // Feel tunables (all retunable — feel can only be judged on-device).
    /** Fraction of half-width around centre that produces ZERO velocity (kills jitter at rest). */
    private static final float DEAD_ZONE = 0.06f;
    /** Curve exponent: higher = finer near centre, steeper toward the ends. */
    private static final float CURVE = 3.0f;
    /** Velocity (timeline ms per real second) at full deflection. */
    private long maxMsPerSec = 15000L;
    /** Inertial spring-back duration (ms) after release. */
    private static final long SPRING_BACK_MS = 260L;

    private final float density;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    /**
     * Drawn width. Was 220dp, which made the control span a third of the phone purely so the
     * GESTURE had room. Now that travel is measured against the screen (see
     * {@link #setNormalizedFromRaw}), the grip only has to be comfortable to hold.
     */
    private static final float WIDTH_DP = 72f;

    /** Screen X at touch-down; every deflection is relative to it. */
    private float downRawX = 0f;
    /** Px from touch-down to full deflection. 0 = use {@link #defaultTravelHalfPx()}. */
    private float travelHalfPx = 0f;

    /** Current normalized deflection in [-1, 1]; 0 = centre. */
    private float normalized = 0f;
    private boolean engaged = false;         // finger down
    private boolean springingBack = false;   // finger up, easing to centre
    private long springStartNanos = 0L;
    private float springFrom = 0f;
    private long lastFrameNanos = 0L;
    @Nullable private Listener listener;

    private final Choreographer.FrameCallback frameCallback = this::onFrame;
    private boolean frameScheduled = false;

    public TimeShuttleView(@NonNull Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        trackPaint.setColor(0xFF2C2C35);
        tickPaint.setColor(0xFF52525B);
        tickPaint.setStrokeWidth(1f * density);
        fillPaint.setColor(0xFF35F6BF);   // green fill grows with deflection (speed cue)
        thumbPaint.setColor(0xFFF4F4F5);
        thumbShadow.setColor(0x66000000);
    }

    // XML inflation requires Context+AttributeSet constructor — was programmatic-only before V2
    public TimeShuttleView(Context ctx, @Nullable android.util.AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        trackPaint.setColor(0xFF2C2C35);
        tickPaint.setColor(0xFF52525B);
        tickPaint.setStrokeWidth(1f * density);
        fillPaint.setColor(0xFF35F6BF);
        thumbPaint.setColor(0xFFF4F4F5);
        thumbShadow.setColor(0x66000000);
    }

    public TimeShuttleView(Context ctx, @Nullable android.util.AttributeSet attrs, int defStyleAttr) {
        super(ctx, attrs, defStyleAttr);
        density = ctx.getResources().getDisplayMetrics().density;
        trackPaint.setColor(0xFF2C2C35);
        tickPaint.setColor(0xFF52525B);
        tickPaint.setStrokeWidth(1f * density);
        fillPaint.setColor(0xFF35F6BF);
        thumbPaint.setColor(0xFFF4F4F5);
        thumbShadow.setColor(0x66000000);
    }

    public void setListener(@Nullable Listener l) { this.listener = l; }

    /** Retune the top scrub speed (ms of timeline per second at full deflection). */
    public void setMaxMsPerSec(long v) { if (v > 0) maxMsPerSec = v; }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = resolveSizeAndState((int) (WIDTH_DP * density), widthSpec, 0);
        int h = resolveSizeAndState((int) (44 * density), heightSpec, 0);
        setMeasuredDimension(w, h);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                engaged = true;
                springingBack = false;
                // The gesture is measured from WHERE THE FINGER LANDED, not from the widget's
                // centre, so touching down off-centre does not jolt the object sideways before
                // the drag has begun.
                downRawX = e.getRawX();
                normalized = 0f;
                if (listener != null) listener.onScrubStart();
                lastFrameNanos = 0L;
                ensureFrameLoop();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (engaged) { setNormalizedFromRaw(e.getRawX()); invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                engaged = false;
                beginSpringBack();
                return true;
            default:
                return false;
        }
    }

    /**
     * Deflection is measured against {@link #travelHalfPx} — a slice of the SCREEN — not
     * against the widget's own width.
     *
     * <p>JoyRaptor, on the old behaviour: <i>"The word nudge scrubber doesn't need to be that
     * big. Make it a third the size. Once you are dragging it, the drag can have velocity
     * scale with distance from center using the whole screen, but we don't need the control
     * to span the whole screen for that."</i></p>
     *
     * <p>Those were one number before: the control had to BE as wide as the gesture you
     * wanted, so a precise shuttle cost 220dp of a phone screen. Splitting them means the
     * widget can shrink to a thumb-sized grip while the gesture keeps its full travel — and
     * the space either side is freed for the formatting row (SPEC_20260829_WORD_SYNC §3.6).</p>
     */
    private void setNormalizedFromRaw(float rawX) {
        float half = travelHalfPx > 0f ? travelHalfPx : defaultTravelHalfPx();
        float n = (rawX - downRawX) / half;
        normalized = Math.max(-1f, Math.min(1f, n));
    }

    /**
     * A third of the screen either side of the touch-down point. Chosen so a comfortable
     * thumb arc reaches full speed without the finger leaving the display, and so the whole
     * usable range is available whichever side of the screen the grip happens to sit on.
     */
    private float defaultTravelHalfPx() {
        return getResources().getDisplayMetrics().widthPixels / 3f;
    }

    /**
     * Override the gesture's travel distance (px from touch-down to full deflection).
     * Independent of the widget's drawn size — that is the entire point.
     */
    public void setTravelHalfPx(float px) { travelHalfPx = px > 0f ? px : 0f; }

    /** Whether the shuttle is currently engaged (finger down or springing back). Consumed by Word Sync §3.2. */
    public boolean isEngaged() { return engaged || springingBack || Math.abs(normalized) > 0.001f; }
    public boolean isFingerDown() { return engaged; }
    public float getNormalized() { return normalized; }

    private void beginSpringBack() {
        if (Math.abs(normalized) < 0.001f) { normalized = 0f; invalidate(); finishIfIdle(); return; }
        springingBack = true;
        springFrom = normalized;
        springStartNanos = 0L;                        // set on the next frame
        ensureFrameLoop();
    }

    // ── frame-synced drive loop ──────────────────────────────────────────────

    private void ensureFrameLoop() {
        if (!frameScheduled) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private void onFrame(long frameNanos) {
        frameScheduled = false;
        if (lastFrameNanos == 0L) lastFrameNanos = frameNanos;
        long dtNanos = frameNanos - lastFrameNanos;
        lastFrameNanos = frameNanos;
        float dtSec = dtNanos / 1_000_000_000f;
        if (dtSec < 0f) dtSec = 0f;
        if (dtSec > 0.05f) dtSec = 0.05f;             // clamp after a stall so nothing lurches

        if (springingBack) {
            if (springStartNanos == 0L) springStartNanos = frameNanos;
            float t = (frameNanos - springStartNanos) / (SPRING_BACK_MS * 1_000_000f);
            if (t >= 1f) {
                normalized = 0f;
                springingBack = false;
            } else {
                // Ease-out decay: 1-(1-t)^3 — quick then gentle, no overshoot.
                float ease = 1f - (1f - t) * (1f - t) * (1f - t);
                normalized = springFrom * (1f - ease);
            }
        }

        long deltaMs = Math.round(velocityMsPerSec(normalized) * dtSec);
        if (deltaMs != 0 && listener != null) listener.onScrubTick(deltaMs);
        invalidate();

        if (engaged || springingBack || Math.abs(normalized) > 0.001f) {
            ensureFrameLoop();
        } else {
            finishIfIdle();
        }
    }

    private void finishIfIdle() {
        if (!engaged && !springingBack && listener != null) listener.onScrubEnd();
    }

    /** Non-linear velocity curve: dead zone → signed power ramp → maxMsPerSec at the ends. */
    private float velocityMsPerSec(float n) {
        float a = Math.abs(n);
        if (a <= DEAD_ZONE) return 0f;
        float u = (a - DEAD_ZONE) / (1f - DEAD_ZONE);  // 0..1 past the dead zone
        float mag = (float) Math.pow(u, CURVE) * maxMsPerSec;
        return Math.signum(n) * mag;
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float trackH = 6f * density;
        float radius = trackH / 2f;

        // Track.
        tmpRect.set(density * 2f, cy - radius, w - density * 2f, cy + radius);
        canvas.drawRoundRect(tmpRect, radius, radius, trackPaint);

        // Speed fill from centre toward the thumb (visual cue of magnitude + direction).
        float thumbCx = cx + normalized * (w / 2f - 12f * density);
        fillPaint.setAlpha((int) (90 + 140 * Math.min(1f, Math.abs(normalized))));
        if (thumbCx >= cx) tmpRect.set(cx, cy - radius, thumbCx, cy + radius);
        else tmpRect.set(thumbCx, cy - radius, cx, cy + radius);
        canvas.drawRoundRect(tmpRect, radius, radius, fillPaint);

        // Centre notch + a few reference ticks.
        for (int i = -2; i <= 2; i++) {
            float tx = cx + i * (w / 2f - 12f * density) / 2.2f;
            float tickH = (i == 0 ? 10f : 6f) * density;
            canvas.drawLine(tx, cy - tickH, tx, cy + tickH, tickPaint);
        }

        // Thumb (with a soft shadow so it reads as liftable).
        float thumbR = 11f * density;
        canvas.drawCircle(thumbCx, cy + 1.5f * density, thumbR, thumbShadow);
        canvas.drawCircle(thumbCx, cy, thumbR, thumbPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameScheduled = false;
        }
    }
}
