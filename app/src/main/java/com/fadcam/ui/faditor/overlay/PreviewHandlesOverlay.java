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

    /**
     * A set of loose draggable POINTS over the preview — the gradient Curve's anchors, its bezier
     * handles, and (for the other gradient shapes) the centre.
     *
     * <p><b>Why it lives here and not in a view of its own.</b> A point is not a transform: it has
     * no box, no rotation and no size, so it cannot be expressed as a {@link Target}. The obvious
     * alternative — a second overlay that draws and drags its own points — is the exact bug
     * pattern this class was created to end: five sibling views each ran their own hit-test, the
     * topmost claimed every touch, and objects underneath became ungrabbable. So the points come
     * in as data and this class stays the only thing in the preview reading a {@code MotionEvent}.
     * Points are hit-tested BEFORE the selected object's own handles, because they are only ever
     * shown while their editor is deliberately open and are therefore what the user is aiming
     * at.</p>
     */
    public interface PointHandles {
        /** How many draggable points there are right now. */
        int count();

        /** Point {@code i}, normalised against {@link #videoRect()}. */
        float pointX(int i);
        float pointY(int i);

        /**
         * The point this one is a bezier HANDLE of, or {@code -1} when it is an anchor. Handles
         * draw smaller and on a tether line, so a glance says which anchor a handle belongs to
         * rather than leaving two identical dots to guess between.
         */
        int tetherTo(int i);

        /** Video-content rect (px in this view's space) — the normalisation basis. */
        @NonNull RectF videoRect();

        /**
         * The path to trace as a guide, as normalised {@code [x0,y0,x1,y1,…]}, or an empty array
         * for none. Supplied rather than derived because only the caller knows the real curve —
         * joining the anchors with straight lines would draw a shape the shader is not rendering.
         */
        @NonNull float[] guide();

        /** A drag on point {@code i} is starting — snapshot for the undo step. */
        void beginPointDrag(int i);

        /** Live write during the drag, in normalised coordinates. */
        void pointDragTo(int i, float nx, float ny);

        /** The drag ended cleanly — record ONE undo step and persist. */
        void commitPointDrag();
    }

    @Nullable private PointHandles pointHandles;

    /** Show (or clear, with {@code null}) the loose point handles. */
    public void setPointHandles(@Nullable PointHandles p) {
        if (pointHandles == p) return;
        pointHandles = p;
        draggingPoint = -1;
        invalidate();
    }

    public boolean hasPointHandles() { return pointHandles != null; }

    /** Index into {@link PointHandles} currently under the finger, or -1. */
    private int draggingPoint = -1;

    @Nullable private SelectionSource selectionSource;

    public void setSelectionSource(@Nullable SelectionSource s) { selectionSource = s; }

    /**
     * WYSIWYG reframe (2026-08-09): while a text overlay's drawer is open, the preview's text
     * box hosts the transparent in-canvas editor. The handles overlay sits ABOVE the text layer,
     * so without this it would eat every caret tap and drag inside the box — and its selection
     * chrome would fight the editor's own handles. While an item is being EDITED the whole
     * preview surrenders: {@link #onTouchEvent} passes everything through to the layers below
     * (the edited box is inert anyway; other boxes keep their own gestures) and {@link #onDraw}
     * skips the chrome.
     */
    @Nullable private String editingItemId;

    public void setEditingItemId(@Nullable String id) {
        if ((id == null) != (editingItemId == null)
                || (id != null && !id.equals(editingItemId))) {
            editingItemId = id;
            invalidate();
        }
    }

    /**
     * Is {@code (x,y)} inside the box of the item currently being edited?
     *
     * <p>Only answerable when that item is also the current {@link Target} — which it is whenever a
     * text drawer is open, because opening one selects its object. When it is not (a stale id after
     * the item was deleted, say) this reports false, and false is the safe answer: it means touches
     * are handled normally rather than dropped into a surface that is no longer there.</p>
     */
    private boolean hitsEditedBox(float x, float y) {
        Target t = target;
        if (t == null || editingItemId == null) return false;
        if (!t.frame(currentTimeMs, box)) return false;
        float cx = box.centerX(), cy = box.centerY();
        rotatePoint(x, y, cx, cy, -t.rotationDeg(currentTimeMs), pt);
        return box.contains(pt[0], pt[1]);
    }

    private enum Mode { NONE, MOVE, SCALE, ROTATE, PINCH }

    /** Two-finger gesture state: the span and angle at the moment the second finger landed. */
    private float pinchStartSpan, pinchStartAngle, pinchStartSize, pinchStartRot;
    private boolean pinching;
    /**
     * A first finger landed near — but not on — the selected object, and the stream is being held
     * in case a second one follows. See the ACTION_DOWN miss branch for why this is necessary at
     * all: without it a pinch whose fingers straddle the object is never delivered.
     */
    private boolean awaitingPinch;

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
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tetherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        // The curve itself, solid and a shade heavier than the dashed selection box — it is the
        // thing being authored, not chrome around something else.
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(2f * density);
        guidePaint.setStrokeCap(Paint.Cap.ROUND);
        guidePaint.setColor(0xFFB388FF);
        tetherPaint.setStyle(Paint.Style.STROKE);
        tetherPaint.setStrokeWidth(1f * density);
        tetherPaint.setColor(0x99B388FF);
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
        if (editingItemId != null) return;   // the drawer is open — the editor owns the chrome
        drawPointHandles(canvas);
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

    /**
     * The loose points, their tethers, and the guide path.
     *
     * <p>Drawn UNDER the selected object's box on purpose: the box is dashed chrome the user is
     * not currently aiming at, and the points are. Overlapping the other way round would let a
     * selection rectangle hide the handle being dragged.</p>
     */
    private void drawPointHandles(@NonNull Canvas canvas) {
        PointHandles p = pointHandles;
        if (p == null) return;
        RectF r = p.videoRect();
        if (r.width() <= 0 || r.height() <= 0) return;

        float[] guide = p.guide();
        for (int i = 0; i + 3 < guide.length; i += 2) {
            canvas.drawLine(r.left + guide[i] * r.width(), r.top + guide[i + 1] * r.height(),
                    r.left + guide[i + 2] * r.width(), r.top + guide[i + 3] * r.height(),
                    guidePaint);
        }

        int n = p.count();
        for (int i = 0; i < n; i++) {
            float x = r.left + p.pointX(i) * r.width();
            float y = r.top + p.pointY(i) * r.height();
            int parent = p.tetherTo(i);
            if (parent >= 0 && parent < n) {
                canvas.drawLine(r.left + p.pointX(parent) * r.width(),
                        r.top + p.pointY(parent) * r.height(), x, y, tetherPaint);
                canvas.drawCircle(x, y, 4f * density, rotatePaint);
                canvas.drawCircle(x, y, 4f * density, handleStroke);
            } else {
                canvas.drawCircle(x, y, 6.5f * density, handleFill);
                canvas.drawCircle(x, y, 6.5f * density, handleStroke);
            }
        }
    }

    /** Hit-test the loose points, nearest first. Returns the index or -1. */
    private int pointAt(@NonNull PointHandles p, float x, float y) {
        RectF r = p.videoRect();
        if (r.width() <= 0 || r.height() <= 0) return -1;
        float grab = handleRadiusPx();
        int best = -1;
        float bestD = Float.MAX_VALUE;
        // NEAREST, not first-within-range: an anchor and its handle can sit almost on top of each
        // other on a barely-bent curve, and "first match wins" would make the one underneath
        // permanently unreachable.
        for (int i = 0; i < p.count(); i++) {
            float d = dist(x, y, r.left + p.pointX(i) * r.width(),
                    r.top + p.pointY(i) * r.height());
            if (d <= grab && d < bestD) { bestD = d; best = i; }
        }
        return best;
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
        // ONLY the edited item's own box surrenders, not the whole canvas.
        //
        // This used to be `if (editingItemId != null) return false;` — the entire preview went
        // inert for as long as ANY text was being edited. Two things go wrong with that. The lesser
        // one is by design: no other object could be selected or dragged while a text drawer was
        // open. The serious one is that editingItemId is cleared by the drawer's onClose, which is
        // one-shot, so any path that disposes of the drawer without running it — notably the
        // placeholder cleanup that removes a text box the user never typed into — left the id
        // pointing at a DELETED item and the preview permanently untouchable. That is JoyRaptor's
        // 2026-08-12 report: an "Enter text" layer he could not select, no touches reaching the
        // preview at all, and no way to make another text layer, all needing an app restart.
        //
        // Deferring only touches that land on the edited box keeps the reason the guard existed —
        // the in-canvas editor owns its own caret taps and selection drags — while a stale id can
        // now cost at most one unreachable object instead of the whole surface.
        if (editingItemId != null && hitsEditedBox(e.getX(), e.getY())) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // LOOSE POINTS FIRST. They are only on screen while their editor is open, so a
                // finger near one is aiming at it — ahead of the selected object's own handles,
                // which are the general-purpose surface and are always up.
                PointHandles ph = pointHandles;
                if (ph != null) {
                    int hit = pointAt(ph, e.getX(), e.getY());
                    if (hit >= 0) {
                        draggingPoint = hit;
                        moved = false;
                        downX = e.getX();
                        downY = e.getY();
                        ph.beginPointDrag(hit);
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                }
                // TRY THE CURRENT SELECTION FIRST, then everything else. Touching the selected
                // object's own box must keep working exactly as it did — the handles are the
                // precision surface — and only a miss falls through to picking something new.
                Target cur = target;
                if (cur != null) {
                    boolean cd = onDown(cur, e.getX(), e.getY());
                    if (cd) return true;
                    // A MISS, but not necessarily a pass-through: WHEN YOU PINCH AN OBJECT, BOTH
                    // FINGERS USUALLY LAND OUTSIDE IT — one either side. Returning false here
                    // declined the whole gesture stream, so ACTION_POINTER_DOWN never arrived and
                    // the second finger was never seen. Two-finger scale and rotate were therefore
                    // dead on anything you pinched from outside its box, while the one-finger
                    // rotate stalk kept working — exactly JoyRaptor's "pinch zoom isn't working, only
                    // rotate" (2026-08-12).
                    //
                    // So hold the stream when the touch is close enough to be part of a pinch on
                    // THIS object, and decide what it really was on the way up: a second finger
                    // makes it a pinch, a lift with nothing else makes it the tap it looked like.
                    if (nearEnoughToPinch(cur, e.getX(), e.getY())) {
                        awaitingPinch = true;
                        moved = false;
                        downX = e.getX();
                        downY = e.getY();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                }
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
                if (draggingPoint >= 0) { onPointMove(e.getX(), e.getY()); return true; }
                Target t = target;
                // Holding for a possible second finger: keep the stream, move nothing. Dragging
                // the object from a point OUTSIDE it would be a grab the user never made.
                if (awaitingPinch && mode == Mode.NONE) {
                    if (Math.abs(e.getX() - downX) > 8 || Math.abs(e.getY() - downY) > 8) {
                        moved = true;
                    }
                    return true;
                }
                if (t == null || mode == Mode.NONE) return false;
                if (mode == Mode.PINCH) { onPinchMove(t, e); return true; }
                onDragMove(t, e.getX(), e.getY());
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (draggingPoint >= 0) {
                    PointHandles p = pointHandles;
                    boolean clean = e.getActionMasked() == MotionEvent.ACTION_UP;
                    draggingPoint = -1;
                    // A tap that never moved is not an edit — committing it would push an undo
                    // step that changes nothing, which is worse than no step at all.
                    if (p != null && moved && clean) p.commitPointDrag();
                    invalidate();
                    return true;
                }
                Target t = target;
                // The held-for-pinch touch turned out to be one finger after all. Do now what the
                // DOWN would have done had it not been held: retarget to whatever is under it, or
                // deselect. A drag that went nowhere else is still just a tap.
                if (awaitingPinch && mode == Mode.NONE) {
                    awaitingPinch = false;
                    boolean clean = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (clean && !moved && selectionSource != null
                            && !selectionSource.selectAt(e.getX(), e.getY(), currentTimeMs)) {
                        selectionSource.selectNone();
                    }
                    invalidate();
                    return true;
                }
                if (t == null || mode == Mode.NONE) return false;
                Mode finished = mode;
                boolean committed = e.getActionMasked() == MotionEvent.ACTION_UP;
                mode = Mode.NONE;
                pinching = false;
                awaitingPinch = false;
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
        awaitingPinch = false;
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
    /**
     * Is {@code (x,y)} close enough to {@code t}'s box to be one finger of a pinch on it?
     *
     * <p>Generous on purpose. A pinch to shrink starts with the fingers WIDE of the object, so a
     * tight margin would reject exactly the gesture this exists to catch. The margin scales with
     * the box so it stays proportionate on a small overlay and does not swallow half the canvas
     * around a large one, with a fixed floor for objects only a few dp across.</p>
     *
     * <p>Rejecting far touches is what keeps a tap on empty canvas passing through to whatever is
     * beneath the preview, which it must: this branch consumes the DOWN.</p>
     */
    private boolean nearEnoughToPinch(@NonNull Target t, float x, float y) {
        if (!t.frame(currentTimeMs, box)) return false;
        float cx = box.centerX(), cy = box.centerY();
        // Un-rotate the touch into the box's own frame, the same way the inside-box test does.
        rotatePoint(x, y, cx, cy, -t.rotationDeg(currentTimeMs), pt);
        float margin = Math.max(64f * density,
                0.4f * Math.max(box.width(), box.height()));
        return pt[0] >= box.left - margin && pt[0] <= box.right + margin
                && pt[1] >= box.top - margin && pt[1] <= box.bottom + margin;
    }

    private boolean onSecondFinger(@NonNull MotionEvent e) {
        Target t = target;
        if (t == null || e.getPointerCount() < 2) return false;
        awaitingPinch = false;   // resolved: it IS a pinch
        mode = Mode.PINCH;
        pinching = true;
        pinchRotating = false;
        pinchScaling = false;
        moved = false;
        pinchStartSpan = Math.max(1f, span(e));
        pinchStartAngle = twoFingerAngle(e);
        pinchStartSize = t.sizeFraction(currentTimeMs);
        pinchStartRot = t.rotationDeg(currentTimeMs);
        t.beginGesture();
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    /**
     * Degrees of two-finger twist to absorb before any rotation is applied.
     *
     * <p>Two fingers never move purely radially, so a pinch meant purely as a zoom always carries
     * a few degrees of incidental twist — and applying it from the first frame is why "scale seems
     * to animate rotation" (JoyRaptor, 2026-08-12). Absorbing this much makes a deliberate rotation
     * still easy (it is a small fraction of any real twist) while an accidental one never lands.
     * The threshold is SUBTRACTED once passed, so rotation begins from zero rather than jumping by
     * the whole deadzone the instant it engages.</p>
     */
    private static final float PINCH_ROT_DEADZONE_DEG = 7f;

    /** Scale deadzone, the mirror of {@link #PINCH_ROT_DEADZONE_DEG} for a twist-only gesture. */
    private static final float PINCH_SCALE_DEADZONE = 0.06f;

    /** Set once this pinch has cleared a deadzone — a channel that unlocks stays unlocked. */
    private boolean pinchRotating, pinchScaling;

    private void onPinchMove(@NonNull Target t, @NonNull MotionEvent e) {
        if (e.getPointerCount() < 2) return;
        float s = Math.max(1f, span(e));
        float ratio = s / pinchStartSpan;
        float deltaDeg = twoFingerAngle(e) - pinchStartAngle;
        // Normalise the delta into ±180 so crossing the ±π seam does not spin the object.
        while (deltaDeg > 180f) deltaDeg -= 360f;
        while (deltaDeg < -180f) deltaDeg += 360f;

        // Each channel unlocks on its OWN evidence. A gesture the user means as a zoom therefore
        // never rotates, and one meant as a twist never resizes — instead of both firing together
        // the moment either one moves, which is what the single shared gate did.
        if (!pinchScaling && Math.abs(ratio - 1f) > PINCH_SCALE_DEADZONE) pinchScaling = true;
        if (!pinchRotating && Math.abs(deltaDeg) > PINCH_ROT_DEADZONE_DEG) pinchRotating = true;
        if (!pinchScaling && !pinchRotating) return;
        moved = true;

        if (pinchScaling) {
            t.scaleTo(Math.max(0.01f, pinchStartSize * ratio), currentTimeMs);
        }
        if (pinchRotating) {
            // Subtract the deadzone so the object does not snap by 7° as rotation engages.
            float applied = deltaDeg - Math.signum(deltaDeg) * PINCH_ROT_DEADZONE_DEG;
            t.rotateTo(snapRotation(pinchStartRot + applied), currentTimeMs);
        }
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
        // Nearest wins rather than first-tested wins. The stalk sits 28dp clear of the box and the
        // grab radius is 14dp, so today the two can never both be in range — this is insurance
        // against a future tweak to either number silently making the stalk shadow the corners,
        // not a fix for an observed bug. Ties go to SCALE: a corner is what is under the finger
        // visually, and a stray rotation is harder to undo by eye than a stray resize.
        float stalkTop = box.top - rotateStalkPx();
        rotatePoint(cx, stalkTop, cx, cy, rot, pt);
        float dRotate = dist(x, y, pt[0], pt[1]);
        float dCorner = Float.MAX_VALUE;
        float[][] corners = {
                {box.left, box.top}, {box.right, box.top},
                {box.left, box.bottom}, {box.right, box.bottom}};
        for (float[] c : corners) {
            rotatePoint(c[0], c[1], cx, cy, rot, pt);
            dCorner = Math.min(dCorner, dist(x, y, pt[0], pt[1]));
        }
        // Ties go to SCALE: a corner is the thing under the finger visually, and an accidental
        // rotation is far more disruptive to undo by eye than an accidental resize.
        boolean onCorner = dCorner <= grab && dCorner <= dRotate;
        boolean onRotate = !onCorner && dRotate <= grab;
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
                // TRAVEL SCALES WITH THE OBJECT. clamp01 pinned the CENTRE inside the canvas,
                // so a big object could only be dragged until its middle reached an edge and
                // could never be pushed fully off — you cannot stage a pan-on, and on a zoomed
                // image the far edges stay permanently unreachable. The numeric sliders never
                // had this limit (they run -centerLimit..1+centerLimit), so finger and slider
                // disagreed about where the same object was allowed to go.
                t.moveTo(clampTravel(nx, box.width() / r.width()),
                        clampTravel(ny, box.height() / r.height()), currentTimeMs);
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

    /**
     * Drag the grabbed loose point to the finger.
     *
     * <p>Absolute, not a delta from the gesture start: a point has no size or rotation to
     * preserve, so "the point is where the finger is" is both simpler and what the user reads off
     * the screen. NOT clamped to 0..1 — a curve anchor or a bezier handle outside the frame is a
     * legitimate way to run the ramp off the edge, and clamping would silently straighten it.</p>
     */
    private void onPointMove(float x, float y) {
        PointHandles p = pointHandles;
        if (p == null) return;
        RectF r = p.videoRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        if (!moved && (Math.abs(x - downX) > 4 || Math.abs(y - downY) > 4)) moved = true;
        if (!moved) return;
        p.pointDragTo(draggingPoint, (x - r.left) / r.width(), (y - r.top) / r.height());
        invalidate();
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    /**
     * Clamp a normalised centre so the object can always be pushed JUST off the canvas, however
     * large it is.
     *
     * <p>{@code extent} is the object's own size as a fraction of the canvas on that axis, so the
     * allowance grows with it: a half-canvas object may travel 0.5 past an edge, a 200% one may
     * travel 1.0. The floor keeps a tiny object from being effectively pinned — "just off screen"
     * has to stay reachable for a 5% sticker too, which is the same reason
     * {@code TextOverlayItem.setCenterTravelLimit} floors at 0.5.</p>
     *
     * <p>The ceiling mirrors {@link com.fadcam.ui.faditor.keyframe.KeyframeSet#clampPos}'s reach
     * so a drag can never author a position the sliders and the serializer would reject.</p>
     */
    private static float clampTravel(float v, float extent) {
        // The rails now GROW with the object rather than being intersected with a fixed ±1 frame —
        // see KeyframeSet.posMinFor. Intersecting was what stopped a 400% image being dragged off
        // the canvas at all: its centre needs two frames of travel before the trailing edge clears
        // the frame, and the fixed rail halted it while it still covered everything.
        return com.fadcam.ui.faditor.keyframe.KeyframeSet.clampPos(v, extent);
    }
}
