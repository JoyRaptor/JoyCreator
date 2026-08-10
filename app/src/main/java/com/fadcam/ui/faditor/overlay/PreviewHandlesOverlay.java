package com.fadcam.ui.faditor.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * G4 (gesture contract §1/§7-G4): manipulation-handles overlay for the video
 * preview. Shown when a layer-row item is TAP-SELECTED on the timeline —
 * draws the selected object's bounding box with corner SCALE handles and a
 * ROTATE stalk, and routes drags:
 *
 * <ul>
 *   <li>drag inside the box → MOVE (center X/Y)</li>
 *   <li>drag a corner handle → SCALE (uniform — the overlay models carry one
 *       {@code sizeFraction}, no independent W/H, so there are no aspect
 *       handles; the visualizer's own 8-handle box already covers the one
 *       item type with a free-form frame)</li>
 *   <li>drag the rotate stalk → ROTATE (snaps to cardinals when close)</li>
 *   <li>touches outside the box pass through untouched (empty-area rule,
 *       same as {@link TextOverlayLayer}/SpriteOverlayView)</li>
 * </ul>
 *
 * <p>Payload-agnostic via {@link Target} adapters the activity builds per
 * object type (same chrome-vs-payload split as {@code ObjectMenuSheet.Prop}).
 * All writes go through the target, which applies the SAME keyframe-aware
 * convention as the G2 menu sliders (armed → record/update a key at the
 * playhead; unarmed → static setters) and records ONE undo step per committed
 * gesture from a transform snapshot. This view never touches the model
 * directly.</p>
 */
public final class PreviewHandlesOverlay extends View {

    /** Adapter over one selected object; the activity owns model semantics. */
    public interface Target {
        /**
         * Current box of the object in THIS VIEW's pixel space at {@code timeMs};
         * returns false when the object should show no handles right now (outside
         * its time range, deleted, zero-sized canvas). {@code outRect} = the
         * UNROTATED bounds; the box is drawn rotated by {@link #rotationDeg}.
         */
        boolean frame(long timeMs, @NonNull RectF outRect);

        /** Rotation (deg, clockwise) to render/hit-test the box at. */
        float rotationDeg(long timeMs);

        /** Normalized center + size the gesture math anchors from. */
        float centerX(long timeMs);
        float centerY(long timeMs);
        float sizeFraction(long timeMs);

        /** Video-content rect (px in this view's space) — normalization basis. */
        @NonNull RectF videoRect();

        /** Gesture is starting — snapshot the transform for the undo step. */
        void beginGesture();

        /** Live writes during the drag (keyframe-aware, playhead-anchored). */
        void moveTo(float normCx, float normCy, long timeMs);
        void scaleTo(float sizeFraction, long timeMs);
        void rotateTo(float deg, long timeMs);

        /** Gesture ended with a change — record ONE undo step + persist. */
        void commit(@NonNull String what);

        /**
         * A clean double-tap landed inside the selected object's box (grammar:
         * double-tap = TYPE editor). Default no-op so existing targets compile.
         */
        default void onDoubleTapped() { }
    }

    /**
     * How this overlay finds out what the user just touched.
     *
     * <p><b>Selection has to happen HERE, not in the layers underneath.</b> Five sibling views
     * draw into the preview and each used to run its own hit-test and drag; the topmost one to
     * claim a touch won, which is why one layer could not be grabbed at all and why selecting
     * something never agreed with the timeline. This overlay is above all of them, so it is the
     * only place that can answer "what is under this finger" once.</p>
     */
    public interface SelectionSource {
        /**
         * Select the topmost object whose drawn box contains {@code (x,y)}, in THIS view's
         * pixels. Returning true means the implementation has already called
         * {@link #setTarget} with that object — the same gesture then continues as a drag on it.
         */
        boolean selectAt(float x, float y, long timeMs);

        /** The touch hit nothing. */
        void selectNone();
    }

    @Nullable private SelectionSource selectionSource;

    public void setSelectionSource(@Nullable SelectionSource s) { selectionSource = s; }

    private enum Mode { NONE, MOVE, SCALE, ROTATE, PINCH }

    /** Two-finger gesture state: the span and angle at the moment the second finger landed. */
    private float pinchStartSpan, pinchStartAngle, pinchStartSize, pinchStartRot;
    private boolean pinching;

    /** In-box tap pairing for the double-tap forwarder (see onTouchEvent UP). */
    private long lastInBoxTapUpMs;

    /** Center-snap threshold, TextOverlayLayer parity. */
    private static final float SNAP_THRESHOLD = 0.045f;
    /** Rotation snap radius (deg) around 0/±90/180. */
    private static final float ROT_SNAP_DEG = 5f;

    private final float density;
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rotatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint movePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    @Nullable private Target target;
    private long currentTimeMs;

    private Mode mode = Mode.NONE;
    private float downX, downY;
    private float startCx, startCy, startSize, startRot;
    /** Distance/angle from box center at gesture start (SCALE/ROTATE reference). */
    private float startDist, startAngle;
    private boolean moved;

    public PreviewHandlesOverlay(@NonNull Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(1.5f * density);
        boxPaint.setColor(0xE6FFFFFF);
        boxPaint.setPathEffect(new DashPathEffect(
                new float[]{6f * density, 4f * density}, 0f));
        handleFill.setStyle(Paint.Style.FILL);
        handleFill.setColor(0xFFFFFFFF);
        handleStroke.setStyle(Paint.Style.STROKE);
        handleStroke.setStrokeWidth(1f * density);
        handleStroke.setColor(0xFF444444);
        rotatePaint.setStyle(Paint.Style.FILL);
        rotatePaint.setColor(0xFFB388FF); // app purple accent (M10 target family)
        // W5-5 move affordance: a dim crosshair at the box centre says "you can drag me" — the
        // single most reported "nothing tells me I can move this" gap (JoyRaptor, 2026-08-08).
        movePaint.setStyle(Paint.Style.STROKE);
        movePaint.setStrokeWidth(1.8f * density);
        movePaint.setStrokeCap(Paint.Cap.ROUND);
        movePaint.setColor(0x99FFFFFF);
    }

    /** Show handles for {@code t} (null = hide). Selection drives this. */
    public void setTarget(@Nullable Target t) {
        if (target == t) return;
        target = t;
        mode = Mode.NONE;
        invalidate();
    }

    public boolean hasTarget() { return target != null; }

    /** Follow the playhead — the box tracks keyframed transforms and hides
     *  outside the object's time range. */
    public void setPlayheadMs(long timelineMs) {
        currentTimeMs = timelineMs;
        if (target != null) invalidate();
    }

    // ── Geometry ─────────────────────────────────────────────────────────

    private float handleRadiusPx() { return 14f * density; }
    private float rotateStalkPx() { return 28f * density; }

    /** Rotate {@code (x,y)} around {@code (cx,cy)} by {@code deg}; result in {@code out}. */
    private static void rotatePoint(float x, float y, float cx, float cy, float deg,
                                    @NonNull float[] out) {
        double rad = Math.toRadians(deg);
        float dx = x - cx, dy = y - cy;
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        out[0] = cx + dx * cos - dy * sin;
        out[1] = cy + dx * sin + dy * cos;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Target t = target;
        if (t == null || !t.frame(currentTimeMs, box)) return;
        float rot = t.rotationDeg(currentTimeMs);
        float cx = box.centerX(), cy = box.centerY();

        canvas.save();
        canvas.rotate(rot, cx, cy);
        canvas.drawRect(box, boxPaint);

        // Corner SCALE handles (squares, unrotated space — the canvas is rotated).
        float hs = 5.5f * density;
        drawCornerHandle(canvas, box.left, box.top, hs);
        drawCornerHandle(canvas, box.right, box.top, hs);
        drawCornerHandle(canvas, box.left, box.bottom, hs);
        drawCornerHandle(canvas, box.right, box.bottom, hs);

        // ROTATE stalk: line up from top-center to a filled dot.
        float stalkTop = box.top - rotateStalkPx();
        canvas.drawLine(cx, box.top, cx, stalkTop, boxPaint);
        canvas.drawCircle(cx, stalkTop, 5f * density, rotatePaint);
        canvas.drawCircle(cx, stalkTop, 5f * density, handleStroke);

        // W5-5 move affordance: four small arrows around the centre — the "you can drag me" cue.
        float g = Math.max(5f * density, Math.min(box.width(), box.height()) * 0.13f);
        drawMoveArrows(canvas, cx, cy, g);

        canvas.restore();
    }

    /** Four outward arrow heads around the box centre (move glyph). */
    private void drawMoveArrows(@NonNull Canvas canvas, float cx, float cy, float g) {
        float a = g * 0.55f;   // arrow head size
        // UP
        canvas.drawLine(cx, cy + g, cx, cy - g, movePaint);
        // DOWN
        canvas.drawLine(cx, cy - g, cx, cy + g, movePaint);
        // LEFT
        canvas.drawLine(cx - g, cy, cx + g, cy, movePaint);
        // RIGHT
        canvas.drawLine(cx + g, cy, cx - g, cy, movePaint);
        // four arrow heads
        canvas.save();
        canvas.translate(cx, cy);
        for (int i = 0; i < 4; i++) {
            canvas.rotate(90f * i);
            // upward head at (0, -g)
            canvas.drawLine(0, -g, -a, -g + a * 1.6f, movePaint);
            canvas.drawLine(0, -g, a, -g + a * 1.6f, movePaint);
        }
        canvas.restore();
    }

    private void drawCornerHandle(@NonNull Canvas canvas, float x, float y, float halfSize) {
        canvas.drawRect(x - halfSize, y - halfSize, x + halfSize, y + halfSize, handleFill);
        canvas.drawRect(x - halfSize, y - halfSize, x + halfSize, y + halfSize, handleStroke);
    }

    // ── Touch ────────────────────────────────────────────────────────────

    private final float[] pt = new float[2];

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // TRY THE CURRENT SELECTION FIRST, then everything else. Touching the selected
                // object's own box must keep working exactly as it did — the handles are the
                // precision surface — and only a miss falls through to picking something new.
                Target cur = target;
                if (cur != null && onDown(cur, e.getX(), e.getY())) return true;
                if (selectionSource == null) return false;
                if (!selectionSource.selectAt(e.getX(), e.getY(), currentTimeMs)) {
                    selectionSource.selectNone();
                    return false;   // empty canvas — let whatever is beneath have it
                }
                // selectAt has already retargeted us. Start the drag on the NEW object in the
                // SAME gesture: "I should be able to tap and move, and they all just select."
                Target picked = target;
                return picked != null && onDown(picked, e.getX(), e.getY());
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                return onSecondFinger(e);
            case MotionEvent.ACTION_POINTER_UP:
                if (pinching && e.getPointerCount() <= 2) {
                    // Down to one finger: end the two-finger gesture rather than letting the
                    // survivor drag the object from wherever it happens to be.
                    endGesture(true);
                    return true;
                }
                return mode != Mode.NONE;
            case MotionEvent.ACTION_MOVE: {
                Target t = target;
                if (t == null || mode == Mode.NONE) return false;
                if (mode == Mode.PINCH) { onPinchMove(t, e); return true; }
                onDragMove(t, e.getX(), e.getY());
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                Target t = target;
                if (t == null || mode == Mode.NONE) return false;
                Mode finished = mode;
                boolean committed = e.getActionMasked() == MotionEvent.ACTION_UP;
                mode = Mode.NONE;
                pinching = false;
                if (moved && committed) {
                    t.commit(finished == Mode.MOVE ? "Move"
                            : finished == Mode.SCALE ? "Scale"
                            : finished == Mode.PINCH ? "Transform" : "Rotate");
                } else if (!moved && finished == Mode.MOVE && committed) {
                    // JoyRaptor 2026-07-19: once the handles are up they consume every touch
                    // inside the box, which was EATING the second tap of a double-tap —
                    // the type editor became unreachable from the preview on a selected
                    // object. Pair clean in-box taps here and forward the double-tap.
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastInBoxTapUpMs <= 320) {
                        lastInBoxTapUpMs = 0;
                        t.onDoubleTapped();
                    } else {
                        lastInBoxTapUpMs = now;
                    }
                }
                invalidate();
                return true;
            }
            default:
                return false;
        }
    }

    /** Finish whatever is running, committing only when the gesture ended cleanly. */
    private void endGesture(boolean commit) {
        Target t = target;
        Mode finished = mode;
        mode = Mode.NONE;
        pinching = false;
        if (t != null && moved && commit) {
            t.commit(finished == Mode.PINCH ? "Transform"
                    : finished == Mode.SCALE ? "Scale"
                    : finished == Mode.ROTATE ? "Rotate" : "Move");
        }
        invalidate();
    }

    /**
     * A second finger landed — switch to PINCH: scale by span ratio, rotate by angle delta.
     *
     * <p>Takes over from a MOVE already in flight without committing it, because the user's
     * intent for one continuous two-finger gesture is one edit, not a move followed by a
     * transform. Both values come off the SAME pair of pointers, so scaling and rotating
     * happen together the way they do in every other editor.</p>
     */
    private boolean onSecondFinger(@NonNull MotionEvent e) {
        Target t = target;
        if (t == null || e.getPointerCount() < 2) return false;
        mode = Mode.PINCH;
        pinching = true;
        moved = false;
        pinchStartSpan = Math.max(1f, span(e));
        pinchStartAngle = twoFingerAngle(e);
        pinchStartSize = t.sizeFraction(currentTimeMs);
        pinchStartRot = t.rotationDeg(currentTimeMs);
        t.beginGesture();
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private void onPinchMove(@NonNull Target t, @NonNull MotionEvent e) {
        if (e.getPointerCount() < 2) return;
        float s = Math.max(1f, span(e));
        float ratio = s / pinchStartSpan;
        float deltaDeg = twoFingerAngle(e) - pinchStartAngle;
        // Normalise the delta into ±180 so crossing the ±π seam does not spin the object.
        while (deltaDeg > 180f) deltaDeg -= 360f;
        while (deltaDeg < -180f) deltaDeg += 360f;
        if (!moved && (Math.abs(ratio - 1f) > 0.02f || Math.abs(deltaDeg) > 2f)) moved = true;
        if (!moved) return;
        t.scaleTo(Math.max(0.01f, pinchStartSize * ratio), currentTimeMs);
        t.rotateTo(snapRotation(pinchStartRot + deltaDeg), currentTimeMs);
        invalidate();
    }

    private static float span(@NonNull MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private static float twoFingerAngle(@NonNull MotionEvent e) {
        return (float) Math.toDegrees(
                Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }

    /** Pull to the nearest cardinal when close — 0° above all, which is what "level" means. */
    private static float snapRotation(float deg) {
        float norm = ((deg % 360f) + 360f) % 360f;
        for (float cardinal : new float[]{0f, 90f, 180f, 270f, 360f}) {
            if (Math.abs(norm - cardinal) < ROT_SNAP_DEG) {
                return deg + (cardinal % 360f) - norm;
            }
        }
        return deg;
    }

    private boolean onDown(@NonNull Target t, float x, float y) {
        if (!t.frame(currentTimeMs, box)) return false;
        float rot = t.rotationDeg(currentTimeMs);
        float cx = box.centerX(), cy = box.centerY();
        float grab = handleRadiusPx();

        // Handle positions live in ROTATED space — rotate each anchor out and
        // hit-test circles around the results (accurate at any angle).
        float stalkTop = box.top - rotateStalkPx();
        rotatePoint(cx, stalkTop, cx, cy, rot, pt);
        boolean onRotate = dist(x, y, pt[0], pt[1]) <= grab;
        boolean onCorner = false;
        if (!onRotate) {
            float[][] corners = {
                    {box.left, box.top}, {box.right, box.top},
                    {box.left, box.bottom}, {box.right, box.bottom}};
            for (float[] c : corners) {
                rotatePoint(c[0], c[1], cx, cy, rot, pt);
                if (dist(x, y, pt[0], pt[1]) <= grab) { onCorner = true; break; }
            }
        }
        // Inside-box test: inverse-rotate the touch into the box's frame.
        boolean inside = false;
        if (!onRotate && !onCorner) {
            rotatePoint(x, y, cx, cy, -rot, pt);
            inside = box.contains(pt[0], pt[1]);
        }
        if (!onRotate && !onCorner && !inside) return false; // pass through

        mode = onRotate ? Mode.ROTATE : onCorner ? Mode.SCALE : Mode.MOVE;
        moved = false;
        downX = x;
        downY = y;
        startCx = t.centerX(currentTimeMs);
        startCy = t.centerY(currentTimeMs);
        startSize = t.sizeFraction(currentTimeMs);
        startRot = t.rotationDeg(currentTimeMs);
        startDist = Math.max(1f, dist(x, y, cx, cy));
        startAngle = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
        t.beginGesture();
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private void onDragMove(@NonNull Target t, float x, float y) {
        RectF r = t.videoRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        if (!moved && (Math.abs(x - downX) > 8 || Math.abs(y - downY) > 8)) moved = true;
        if (!moved) return;
        // Center from the CURRENT frame (it moves under a MOVE drag).
        if (!t.frame(currentTimeMs, box)) return;
        float cx = box.centerX(), cy = box.centerY();
        switch (mode) {
            case MOVE: {
                float nx = startCx + (x - downX) / r.width();
                float ny = startCy + (y - downY) / r.height();
                // Center snap (TextOverlayLayer convention; peer snap deliberately
                // omitted here — handles are the precision surface).
                if (Math.abs(nx - 0.5f) < SNAP_THRESHOLD) nx = 0.5f;
                if (Math.abs(ny - 0.5f) < SNAP_THRESHOLD) ny = 0.5f;
                t.moveTo(clamp01(nx), clamp01(ny), currentTimeMs);
                break;
            }
            case SCALE: {
                float scale = dist(x, y, cx, cy) / startDist;
                t.scaleTo(Math.max(0.01f, startSize * scale), currentTimeMs);
                break;
            }
            case ROTATE: {
                float angle = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
                t.rotateTo(snapRotation(startRot + (angle - startAngle)), currentTimeMs);
                break;
            }
            default:
                break;
        }
        invalidate();
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
