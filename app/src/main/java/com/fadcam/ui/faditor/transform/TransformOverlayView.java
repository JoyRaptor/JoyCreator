package com.fadcam.ui.faditor.transform;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE TRANSFORM SURFACE: eight smart handles, a floating spin arc, a long-press role ring, a
 * transient gesture HUD and a loupe — over one object, in one View.
 *
 * <p><b>Reusable by construction.</b> The only things this class knows about the app are behind
 * {@link Host}: it asks for a quad in its own pixels, reports gestures back as quads / translations
 * / similarities, and never touches a model, a keyframe or an undo stack. Together with
 * {@link TransformQuad} (pure arithmetic) and {@link HandleModel} (pure vocabulary) it can be
 * lifted into another app by writing one new {@code Host}.</p>
 *
 * <p><b>Why it is a separate View and not another branch inside PreviewHandlesOverlay.</b> That
 * class's own doc records the bug this project has already paid for: five sibling views each ran a
 * hit-test, the topmost claimed every touch, and objects underneath became ungrabbable. This view
 * does NOT reintroduce that — it and the ordinary handles overlay are strictly mutually exclusive.
 * The activity hides one to show the other, so at any instant there is still exactly one thing in
 * the preview reading a {@link MotionEvent}. Keeping it separate is what lets the transform
 * geometry stay liftable instead of being welded into 860 lines of FadCam selection logic.</p>
 *
 * <h3>The gesture grammar</h3>
 * <ul>
 *   <li>Every handle defaults to SCALE. Shape and colour say what it does before you touch it.</li>
 *   <li>LONG-PRESS (~450ms) a corner or an edge → the role ring, whose options are drawn as the
 *       very shapes they will install.</li>
 *   <li>ONE finger inside → pan. TWO fingers → scale + rotate about the midpoint between them.</li>
 *   <li>The amber spin arc floating outside the top edge is a PURE rotation and nothing else.</li>
 * </ul>
 *
 * <h3>Where bend joins</h3>
 * <p>Nowhere in this file, deliberately. The net is an additive layer drawn over the top with its
 * own control points, and the structural handles keep working underneath it — see
 * {@link HandleModel#BEND_IS_A_NET} and {@link TransformQuad#unitToQuad}. The ring already has its
 * slot ({@link #ringBendEnabled}), drawn greyed until that lane lands.</p>
 */
public class TransformOverlayView extends View {

    // ── Host ─────────────────────────────────────────────────────────────

    /**
     * Everything this view needs from the app, and the complete list of things it will ask of it.
     *
     * <p>The write channels are deliberately separate rather than one "here is the new quad".
     * A translation, a rotation and a uniform scale are exactly representable as the object's own
     * position / rotation / size properties, and those already animate, serialise and export.
     * Funnelling them through a corner-pin instead would push a perfectly ordinary move into a
     * distortion the exporter has to reconstruct — so the view tells the host WHICH kind of change
     * it is making, and the host writes it to the cheapest honest property.</p>
     */
    public interface Host {
        /** The object's quad in THIS view's pixels, TL,TR,BR,BL. False = nothing to show. */
        boolean readQuad(@NonNull float[] outQuad8);

        /** The object's own centre in this view's pixels — the pure-rotation pivot. */
        void readPivot(@NonNull float[] outXY);

        /**
         * SPEC B — the point the RENDERED picture's rotation turns about (the stored pivot),
         * in overlay pixels. The rotate handle's live preview must orbit THIS, not the
         * presented centre, or the handles spin in place while the picture swings round the
         * pivot and the two only meet at the gesture's start angle.
         */
        void readFoldPivot(@NonNull float[] outXY);

        /** A gesture is starting: take the ONE snapshot the whole gesture will undo to. */
        void beginGesture();

        /** The shape changed. Return false to REFUSE (out of range, unsolvable homography). */
        boolean writeQuad(@NonNull float[] quad8);

        /** Pure translation, in view pixels, measured from the gesture's start pose. */
        void writeTranslate(float dxPx, float dyPx);

        /**
         * Similarity: multiply size by {@code factor}, add {@code deltaDeg} of turn, and land the
         * object's centre on {@code (cxPx, cyPx)} — all measured from the gesture's start pose.
         */
        void writeSimilarity(float factor, float deltaDeg, float cxPx, float cyPx);

        /** Pure rotation to an ABSOLUTE angle in degrees. */
        void writeRotation(float deg);

        /** The gesture ended cleanly and changed something: record ONE undo step. */
        void commitGesture(@NonNull String what);

        /** The object's absolute rotation right now, in degrees. */
        float currentRotationDeg();

        /**
         * RESET THE OBJECT — geometry only. Position, size, rotation and every distortion go back
         * to the plain rectangle. Handle roles are NOT this method's business and must survive it.
         */
        void resetObjectGeometry();

        /** Mirror the picture inside its own box, about its own centre line. */
        void flip(boolean horizontal);

        // ── SPEC H bend ──────────────────────────────────────────────
        //
        // The net is an additive layer: the View owns visibility and hit-testing, the host
        // owns the pose (MeshProjection forward/inverse + MeshGuard). Defaults are inert so
        // the text/PiP/spine hosts — affine-only, no mesh render path — change not at all.

        /** True only for the image host (the only one with a pin/mesh render path). */
        default boolean supportsBend() { return false; }
        /** True when a warp is authored (drives the net's initial visibility). */
        default boolean hasBend() { return false; }
        /** Dots to draw/hit-test (9 for the L2 net), or 0 when there is nothing. */
        default int bendHandleCount() { return 0; }
        /** Lattice side for the grid lines (3 for L2), or 0 for no lines. */
        default int bendGridSide() { return 0; }
        /**
         * Project dot {@code i} through homography {@code h} (from
         * {@code TransformQuad.unitToQuad}) into view pixels. False = draw nothing.
         */
        default boolean bendHandlePosition(int i, float[] h, float[] out2) { return false; }
        /**
         * Drag dot {@code i} to a stage point (inverse homography + finger). Guarded;
         * false leaves the pose untouched and the drag simply stops.
         */
        default boolean bendDragTo(int i, float[] hInv, float x, float y) { return false; }
        /** A bend dot drag is starting: the ONE snapshot the whole drag will undo to. */
        default void beginBendGesture() { beginGesture(); }
        /** The bend drag ended cleanly: record ONE undo step. */
        default void commitBendGesture(String what) { commitGesture(what); }
    }

    @Nullable private Host host;

    /** Per-object handle roles. Owned here because they are chrome, not project data. */
    @NonNull private HandleModel handles = new HandleModel();

    public void setHost(@Nullable Host h, @NonNull HandleModel model) {
        host = h;
        handles = model;
        cancelGesture();
        cancelBendDrag();
        bendMode = false;
        closeRing();
        syncFromHost();
        setVisibility(h == null ? GONE : VISIBLE);
        invalidate();
    }

    @Nullable public Host host() { return host; }

    @NonNull public HandleModel handleModel() { return handles; }

    /** The playhead moved or the object was written from elsewhere — re-read the quad. */
    public void refresh() {
        if (dragKind == null && !pinching) syncFromHost();
        invalidate();
    }

    // ── Metrics ──────────────────────────────────────────────────────────

    private final float d;                     // density: 1dp in px

    private float dp(float v) { return v * d; }

    /** Base grab radius. 22dp radius = a 44dp target, whatever the glyph's drawn size. */
    private float grabPx() { return dp(22f); }

    /** How far outside the top edge the spin arc floats, on its hairline stalk. */
    private float rotateStandoffPx() { return dp(46f); }

    private static final long LONG_PRESS_MS = 450L;
    private static final float MOVE_SLOP_DP = 7f;
    private static final long HUD_FADE_MS = 400L;

    // ── State ────────────────────────────────────────────────────────────

    private final float[] quad = new float[8];       // live, in view px
    private final float[] quadAtGrab = new float[8]; // frozen when the finger went down
    private final float[] quadLastGood = new float[8];
    private boolean haveQuad;

    private final HandleModel.Handle[] handleBuf = new HandleModel.Handle[9];
    private int handleCount;

    @Nullable private HandleModel.Kind dragKind;
    private int dragIndex;
    private int dragPointerId = -1;
    private float grabOffsetX, grabOffsetY;   // handle centre minus finger, so nothing jumps
    private float downX, downY;
    private boolean moved;

    // Pure-rotation gesture state.
    private float rotPivotX, rotPivotY, rotStartAngleRad, rotStartDeg;

    // Two-finger state.
    private boolean pinching;
    private int pinchIdA = -1, pinchIdB = -1;
    private float pinchAx, pinchAy, pinchBx, pinchBy;   // finger positions at the start
    private float pinchPivotX, pinchPivotY;             // the object's centre at the start
    private float pinchFactor = 1f, pinchDeg = 0f;

    private final float[] scratch2 = new float[2];
    private final float[] scratchFactors = new float[2];
    private final float[] rotateHandle = new float[3];

    // ── Paints ───────────────────────────────────────────────────────────

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rectf = new RectF();

    /** The dark disc every glyph is drawn on, so thin ink survives a bright picture under it. */
    private static final int GLYPH_FILL = 0xFF0C0C10;

    public TransformOverlayView(@NonNull Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < handleBuf.length; i++) handleBuf[i] = new HandleModel.Handle();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        // NO OFFSCREEN LAYER. It bought nothing — this view invalidates on every frame of a drag,
        // so the layer was re-recorded every time it was used — and the loupe now draws the video
        // TextureView's layer through this canvas, which is one composition step it should not
        // have to make inside another one.
    }

    // ── Reading the object ───────────────────────────────────────────────

    private void syncFromHost() {
        Host h = host;
        haveQuad = h != null && h.readQuad(quad);
        if (haveQuad) System.arraycopy(quad, 0, quadLastGood, 0, 8);
    }

    /**
     * SPEC K — a preview-rect change (drawer resize) moves every box in this view's
     * pixel space. The quad is a snapshot in those pixels, so without a re-read the
     * handles stand where the picture was while the picture draws where it is now;
     * dragging then preserves the stale offset until release snaps it back. Re-read
     * on resize when no gesture is in flight (mid-drag the live quad owns the truth
     * and a sync would discard the finger).
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            refresh();
        }
    }

    /** Where the floating spin arc sits: outside the TOP edge, on that edge's OUTWARD normal. */
    private void computeRotateHandle() {
        TransformQuad.edgeMid(quad, TransformQuad.TOP, scratch2);
        double inward = Math.toRadians(TransformQuad.edgeInwardNormalDeg(quad, TransformQuad.TOP));
        float off = rotateStandoffPx();
        rotateHandle[0] = scratch2[0] - (float) Math.cos(inward) * off;
        rotateHandle[1] = scratch2[1] - (float) Math.sin(inward) * off;
        rotateHandle[2] = TransformQuad.edgeAngleDeg(quad, TransformQuad.TOP);
    }

    private void rebuildHandles() {
        computeRotateHandle();
        handleCount = handles.fill(handleBuf, quad, rotateHandle);
    }

    // ── Drawing ──────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas c) {
        // RE-ENTRANCY GUARD. The loupe draws the preview stack, and this view is a child of that
        // stack. We skip ourselves by name when we walk the children (see drawLoupeContent), but
        // a nested group could still route back here, and a stack overflow on the first drag is
        // not a bug worth risking for one boolean.
        if (drawingLoupeContent) return;
        if (host == null) return;
        if (!haveQuad) syncFromHost();
        if (!haveQuad) return;
        rebuildHandles();

        // The stalk first, so the arc's glyph sits on top of its own tether.
        TransformQuad.edgeMid(quad, TransformQuad.TOP, scratch2);
        stroke.setColor(withAlpha(HandleModel.COLOR_ROTATE, 0x60));
        stroke.setStrokeWidth(dp(0.9f));
        c.drawLine(scratch2[0], scratch2[1], rotateHandle[0], rotateHandle[1], stroke);

        // The quad itself.
        path.reset();
        path.moveTo(quad[0], quad[1]);
        for (int i = 1; i < 4; i++) path.lineTo(quad[i * 2], quad[i * 2 + 1]);
        path.close();
        stroke.setColor(withAlpha(HandleModel.COLOR_GUIDE, 0xE6));
        stroke.setStrokeWidth(dp(1.1f));
        c.drawPath(path, stroke);

        // The pinch pivot, made visible: this is the point it scales and spins about.
        if (pinching) {
            float mx = (pinchAx + pinchBx) / 2f, my = (pinchAy + pinchBy) / 2f;
            drawPinchPivot(c, mx, my);
        }

        for (int i = 0; i < handleCount; i++) {
            HandleModel.Handle h = handleBuf[i];
            boolean grabbed = dragKind == h.kind && dragIndex == h.index;
            float size = h.kind == HandleModel.Kind.ROTATE ? dp(21f)
                    : h.kind == HandleModel.Kind.CORNER ? dp(19f) : dp(17f);
            if (grabbed) size *= 1.28f;
            drawGlyph(c, h.shape, h.color, h.x, h.y, size, h.rotationDeg);
        }

        // SPEC H — the net, over the top. Dots re-project through the CURRENT quad every
        // frame, so a structural edit visibly carries the bend instead of wiping it.
        if (bendMode && host != null && host.supportsBend()) drawBendNet(c, host);

        drawExitPill(c);
        if (ringOpen) drawRing(c);
        drawLoupe(c);
        drawHud(c);
    }

    // ── Leaving the surface ──────────────────────────────────────────────
    //
    // The mode has to be leavable from inside itself. Everything else that dismisses it — picking
    // another object, closing the drawer — is a side effect of doing something else, and a mode
    // whose only exit is "go and do something unrelated" is a trap. One pill, its own 44dp target,
    // parked where no handle can be: the view's top-left, outside any plausible object box only
    // when the object is elsewhere, and drawn UNDER the ring and the loupe so it can never
    // intercept them.

    @Nullable private Runnable onExit;

    /** Supply the action the "Done" pill runs. Null hides the pill entirely. */
    public void setOnExit(@Nullable Runnable r) { onExit = r; invalidate(); }

    // ── Double-tap ───────────────────────────────────────────────────────
    //
    // CARRIED OVER FROM THE ORDINARY HANDLES, not invented here. Once this surface is up on a
    // selected object it consumes every touch inside the box, which is exactly what ate the second
    // tap of a double-tap in PreviewHandlesOverlay before it grew the same pairing (JoyRaptor
    // 2026-07-19). Double-tap = "open this object's editor" is muscle memory, and now that the
    // transform surface is what a selected image shows, losing it here would lose it entirely.

    /** Run when a clean, motionless double-tap lands inside the object. Null = no double-tap. */
    @Nullable private Runnable onDoubleTap;

    public void setOnDoubleTap(@Nullable Runnable r) { onDoubleTap = r; }

    private long lastBodyTapUpMs;

    private static final long DOUBLE_TAP_MS = 320L;

    private void exitPillRect(@NonNull RectF out) {
        float w = dp(64f), h = dp(34f), m = dp(8f);
        out.set(m, m, m + w, m + h);
    }

    private void drawExitPill(@NonNull Canvas c) {
        if (onExit == null) return;
        exitPillRect(rectf);
        fill.setColor(0xE6131318);
        c.drawRoundRect(rectf, dp(17f), dp(17f), fill);
        stroke.setColor(0xFF4C3F7A);
        stroke.setStrokeWidth(dp(1f));
        c.drawRoundRect(rectf, dp(17f), dp(17f), stroke);
        text.setColor(0xFFECECF2);
        text.setTextSize(dp(12f));
        text.setFakeBoldText(true);
        c.drawText("Done", rectf.centerX(), rectf.centerY() + dp(4f), text);
        text.setFakeBoldText(false);
    }

    /** Is this touch on the pill? The tested rect is padded out to a 44dp target. */
    private boolean hitsExitPill(float x, float y) {
        if (onExit == null) return false;
        exitPillRect(rectf);
        float padY = Math.max(0f, (dp(44f) - rectf.height()) / 2f);
        return x >= rectf.left && x <= rectf.right
                && y >= rectf.top - padY && y <= rectf.bottom + padY;
    }

    private void drawPinchPivot(@NonNull Canvas c, float mx, float my) {
        stroke.setColor(0x594FD1C5);
        stroke.setStrokeWidth(dp(0.8f));
        c.drawLine(pinchAx, pinchAy, pinchBx, pinchBy, stroke);
        stroke.setColor(0xF04FD1C5);
        stroke.setStrokeWidth(dp(1.2f));
        c.drawCircle(mx, my, dp(9f), stroke);
        float r = dp(15f);
        c.drawLine(mx - r, my, mx + r, my, stroke);
        c.drawLine(mx, my - r, mx, my + r, stroke);
    }

    /**
     * ONE drawing routine for handles, ring options and any legend, so they can never disagree
     * about what a shape means. {@code size} is the glyph's box; the drawn radius is a fixed
     * fraction of it and the ink weight is constant, which is what keeps the vocabulary readable
     * as small marks rather than heavy blobs.
     */
    private void drawGlyph(@NonNull Canvas c, HandleModel.Shape shape, int color,
                           float cx, float cy, float size, float rotDeg) {
        drawShape(c, shape, color, cx, cy, size * 0.335f, dp(1.75f), GLYPH_FILL, rotDeg);
    }

    private void drawShape(@NonNull Canvas c, HandleModel.Shape shape, int color,
                           float cx, float cy, float r, float ink, int fillColor, float rotDeg) {
        stroke.setColor(color);
        stroke.setStrokeWidth(ink);
        fill.setColor(fillColor);
        switch (shape) {
            case CIRCLE:
                c.drawCircle(cx, cy, r, fill);
                c.drawCircle(cx, cy, r, stroke);
                break;
            case SQUARE:
            case DIAMOND: {
                // A diamond IS a square turned 45° and drawn a shade smaller, so the two read as
                // the same family — "your right angles" versus "your right angles, leaned".
                float k = shape == HandleModel.Shape.DIAMOND ? r * 0.9f : r;
                c.save();
                c.rotate(rotDeg + (shape == HandleModel.Shape.DIAMOND ? 45f : 0f), cx, cy);
                rectf.set(cx - k, cy - k, cx + k, cy + k);
                float rad = k * 0.12f;
                c.drawRoundRect(rectf, rad, rad, fill);
                c.drawRoundRect(rectf, rad, rad, stroke);
                c.restore();
                break;
            }
            case TRIANGLE: {
                // Apex up before rotation; `rotDeg` swings it to the inward normal, which leaves
                // the flat back exactly parallel to the edge it is mounted on.
                c.save();
                c.rotate(rotDeg, cx, cy);
                path.reset();
                path.moveTo(cx, cy - r * 1.15f);
                path.lineTo(cx - r * 1.08f, cy + r * 0.8f);
                path.lineTo(cx + r * 1.08f, cy + r * 0.8f);
                path.close();
                c.drawPath(path, fill);
                c.drawPath(path, stroke);
                c.restore();
                break;
            }
            case ROTATE_ARC: {
                // An almost-closed arc with one arrowhead: it cannot be mistaken for a square
                // (right angles), a triangle (a direction) or a circle (anywhere).
                float rr = r * 0.92f;
                c.drawCircle(cx, cy, r * 1.35f, fill);
                rectf.set(cx - rr, cy - rr, cx + rr, cy + rr);
                stroke.setColor(color);
                c.drawArc(rectf, 288f, 324f, false, stroke);
                double ta = Math.toRadians(252f);
                float px = cx + rr * (float) Math.cos(ta), py = cy + rr * (float) Math.sin(ta);
                float tx = -(float) Math.sin(ta), ty = (float) Math.cos(ta);
                float nx = -ty, ny = tx;
                float hl = r * 0.55f, hw = r * 0.42f;
                path.reset();
                path.moveTo(px + tx * hl, py + ty * hl);
                path.lineTo(px - tx * hl * 0.35f + nx * hw, py - ty * hl * 0.35f + ny * hw);
                path.lineTo(px - tx * hl * 0.35f - nx * hw, py - ty * hl * 0.35f - ny * hw);
                path.close();
                fill.setColor(color);
                c.drawPath(path, fill);
                break;
            }
            case NET: {
                float a = cx - r, b = cy - r, s = r * 2f, t = s / 3f;
                rectf.set(a, b, a + s, b + s);
                c.drawRoundRect(rectf, r * 0.12f, r * 0.12f, fill);
                c.drawRoundRect(rectf, r * 0.12f, r * 0.12f, stroke);
                stroke.setStrokeWidth(ink * 0.6f);
                stroke.setColor(withAlpha(color, 0x8C));
                for (int i = 1; i <= 2; i++) {
                    c.drawLine(a + t * i, b, a + t * i, b + s, stroke);
                    c.drawLine(a, b + t * i, a + s, b + t * i, stroke);
                }
                break;
            }
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ── The transient gesture HUD ────────────────────────────────────────
    //
    // A chip that exists ONLY while a gesture is running and fades HUD_FADE_MS after release. It
    // reports the GESTURE — what is happening right now, in that gesture's own units. Anything
    // that reports persistent STATE belongs in the drawer; duplicating it here would put two
    // numbers for the same thing on screen and make the user pick.

    @Nullable private String hudText;
    private float hudX, hudY;
    private long hudEndedAtMs;

    private void setHud(@Nullable String s, float x, float y) {
        hudText = s;
        hudX = x;
        hudY = y;
        hudEndedAtMs = 0L;
    }

    private void drawHud(@NonNull Canvas c) {
        if (hudText == null) return;
        float alpha = 1f;
        if (hudEndedAtMs != 0L) {
            long age = SystemClock.uptimeMillis() - hudEndedAtMs;
            if (age >= HUD_FADE_MS) { hudText = null; return; }
            alpha = 1f - age / (float) HUD_FADE_MS;
            postInvalidateOnAnimation();
        }
        text.setTextSize(dp(11f));
        text.setFakeBoldText(true);
        float w = text.measureText(hudText) + dp(14f);
        float hgt = dp(22f);
        // Never sit on top of the handle it describes: above when there is room, below otherwise.
        float cx = TransformQuad.clamp(hudX, w / 2f + dp(4f), getWidth() - w / 2f - dp(4f));
        float top = hudY > dp(52f) ? hudY - dp(44f) : hudY + dp(30f);
        top = TransformQuad.clamp(top, dp(4f), getHeight() - hgt - dp(4f));
        rectf.set(cx - w / 2f, top, cx + w / 2f, top + hgt);
        fill.setColor(withAlpha(0x0A0A0E, (int) (0xD1 * alpha)));
        c.drawRoundRect(rectf, dp(7f), dp(7f), fill);
        stroke.setColor(withAlpha(0x8C8CAA, (int) (0x47 * alpha)));
        stroke.setStrokeWidth(dp(1f));
        c.drawRoundRect(rectf, dp(7f), dp(7f), stroke);
        text.setColor(withAlpha(0xCFD6E6, (int) (0xFF * alpha)));
        c.drawText(hudText, cx, top + hgt * 0.5f + dp(4f), text);
        text.setFakeBoldText(false);
    }

    // ── The loupe ────────────────────────────────────────────────────────
    //
    // WHAT IT ACTUALLY IS: the REAL preview, magnified about the point being placed, with the
    // quad, the handles and a crosshair drawn on top of it. It parks in whichever corner of the
    // view is FURTHEST from the finger, so a fat thumb never hides the point it is placing.
    //
    // HOW THE PICTURE GETS IN. This view is a child of the preview container, so the container's
    // coordinate space IS this view's coordinate space. We therefore do not need a screenshot at
    // all: inside the loupe's circular clip we apply
    //     translate(loupe centre) · scale(ZOOM) · translate(-handle point)
    // and then draw the container's OTHER children straight into the same canvas. What lands in
    // the circle is literally the pixels that are under the finger, at their true on-screen scale
    // times the zoom — a magnification, never a re-layout.
    //
    // Why children-one-by-one and not container.draw(canvas): because this view is one of those
    // children, and asking the container to draw itself would ask this view to draw itself, from
    // inside its own draw. Skipping ourselves in the loop is the exclusion; drawingLoupeContent is
    // the belt to that pair of braces (see onDraw).
    //
    // The video plane is an androidx.media3 PlayerView with surface_type="texture_view", and the
    // FX plane is a TextureView too, so both render INTO the view hierarchy and both come along
    // with an ordinary draw call. A SurfaceView would not have — it punches a hole in the window
    // and is composited outside the hierarchy — which is exactly why that attribute matters here.
    //
    // COST: one extra record of the preview subtree per drag frame, no allocation and no bitmap.
    // The loupe only exists while a handle is held, and the preview is not playing itself while
    // you are placing a corner, so this is the frame's only real work.

    private boolean loupeEnabled = true;
    private boolean loupeShowing;

    /** The preview container whose children ARE the picture. Null = geometry-only loupe. */
    @Nullable private android.view.ViewGroup loupeContentRoot;

    /** True while we are drawing that container's children, to make this view's onDraw inert. */
    private boolean drawingLoupeContent;

    /**
     * Point the loupe at the real picture: the container that parents the video surface and every
     * overlay plane. It must be an ancestor-or-parent of this view, and this view must be one of
     * its children (directly or not) so it can be skipped.
     */
    public void setLoupeContentSource(@Nullable android.view.ViewGroup root) {
        loupeContentRoot = root;
        invalidate();
    }

    public void setLoupeEnabled(boolean on) {
        loupeEnabled = on;
        if (!on) loupeShowing = false;
        invalidate();
    }

    public boolean isLoupeEnabled() { return loupeEnabled; }

    private static final float LOUPE_ZOOM = 2.2f;

    private void drawLoupe(@NonNull Canvas c) {
        if (!loupeShowing || !loupeEnabled || dragKind == null) return;
        HandleModel.Handle h = findHandle(dragKind, dragIndex);
        if (h == null) return;
        float dia = dp(118f), r = dia / 2f, m = dp(10f);

        // Furthest corner from the finger.
        float bestX = m, bestY = m, bestD = -1f;
        float[][] cand = {{m, m}, {getWidth() - dia - m, m},
                {m, getHeight() - dia - m}, {getWidth() - dia - m, getHeight() - dia - m}};
        for (float[] p : cand) {
            float dd = (float) Math.hypot(p[0] + r - h.x, p[1] + r - h.y);
            if (dd > bestD) { bestD = dd; bestX = p[0]; bestY = p[1]; }
        }

        int save = c.save();
        path.reset();
        path.addCircle(bestX + r, bestY + r, r, Path.Direction.CW);
        c.clipPath(path);
        fill.setColor(0xF008080B);
        c.drawCircle(bestX + r, bestY + r, r, fill);

        c.translate(bestX + r, bestY + r);
        c.scale(LOUPE_ZOOM, LOUPE_ZOOM);
        c.translate(-h.x, -h.y);

        // THE PICTURE ITSELF, first, so every line below lands on top of it.
        drawLoupeContent(c);

        // The same guide, magnified, plus a dot for every handle and a ring on the one held.
        path.reset();
        path.moveTo(quad[0], quad[1]);
        for (int i = 1; i < 4; i++) path.lineTo(quad[i * 2], quad[i * 2 + 1]);
        path.close();
        stroke.setColor(withAlpha(HandleModel.COLOR_GUIDE, 0xE6));
        stroke.setStrokeWidth(dp(1.1f) / LOUPE_ZOOM);
        c.drawPath(path, stroke);
        for (int i = 0; i < handleCount; i++) {
            HandleModel.Handle g = handleBuf[i];
            boolean on = g == h;
            stroke.setColor(g.color);
            stroke.setStrokeWidth((on ? dp(1.6f) : dp(1.1f)) / LOUPE_ZOOM);
            float rr = (on ? dp(4.2f) : dp(2.6f)) / LOUPE_ZOOM;
            if (on) {
                fill.setColor(g.color);
                c.drawCircle(g.x, g.y, rr, fill);
            }
            c.drawCircle(g.x, g.y, rr, stroke);
        }
        c.restoreToCount(save);

        stroke.setColor(0x47A78BFA);
        stroke.setStrokeWidth(dp(1f));
        c.drawCircle(bestX + r, bestY + r, r - dp(1f), stroke);
        stroke.setColor(0xD94FD1C5);
        float a = dp(5f), b = dp(13f);
        c.drawLine(bestX + r, bestY + r - b, bestX + r, bestY + r - a, stroke);
        c.drawLine(bestX + r, bestY + r + a, bestX + r, bestY + r + b, stroke);
        c.drawLine(bestX + r - b, bestY + r, bestX + r - a, bestY + r, stroke);
        c.drawLine(bestX + r + a, bestY + r, bestX + r + b, bestY + r, stroke);
    }

    /**
     * Draw the live preview into the canvas as it stands — already translated and scaled to the
     * loupe, already clipped to its circle by the caller.
     *
     * <p>Every child of the content root is drawn the way its parent would draw it (offset by its
     * layout position, through its own matrix, honouring its alpha) EXCEPT this view, which is
     * skipped so the loupe cannot contain a picture of itself. Nothing is allocated: no bitmap, no
     * matrix, no path — the ops are recorded straight into the display list that is already being
     * built for this frame.</p>
     */
    private void drawLoupeContent(@NonNull Canvas c) {
        android.view.ViewGroup root = loupeContentRoot;
        if (root == null || drawingLoupeContent) return;
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;

        // This view's origin expressed in the root's space. Normally (0,0) — the overlay is a
        // match_parent child of the container — but computed rather than assumed so that a future
        // layout that insets or nests it does not silently shift the magnified picture.
        float ox = 0f, oy = 0f;
        for (View v = this; v != null && v != root; ) {
            ox += v.getLeft();
            oy += v.getTop();
            android.view.ViewParent p = v.getParent();
            if (!(p instanceof View)) return;   // not under this root: refuse rather than guess
            v = (View) p;
            ox -= v.getScrollX();
            oy -= v.getScrollY();
        }

        drawingLoupeContent = true;
        int outer = c.save();
        try {
            // Root space → this view's space.
            c.translate(-ox, -oy);
            int n = root.getChildCount();
            for (int i = 0; i < n; i++) {
                View ch = root.getChildAt(i);
                if (ch == this) continue;                       // never draw ourselves
                if (ch.getVisibility() != VISIBLE) continue;
                if (ch.getWidth() <= 0 || ch.getHeight() <= 0) continue;
                float a = ch.getAlpha();
                if (a <= 0.01f) continue;
                int s = c.save();
                c.translate(ch.getLeft() - root.getScrollX(), ch.getTop() - root.getScrollY());
                android.graphics.Matrix m = ch.getMatrix();
                if (m != null && !m.isIdentity()) c.concat(m);
                if (a < 1f) {
                    c.saveLayerAlpha(0f, 0f, ch.getWidth(), ch.getHeight(), (int) (a * 255f));
                }
                try {
                    ch.draw(c);
                } catch (RuntimeException ignored) {
                    // A layer that cannot draw itself out of turn must not take the gesture down.
                }
                c.restoreToCount(s);
            }
        } finally {
            c.restoreToCount(outer);
            drawingLoupeContent = false;
        }
    }

    @Nullable
    private HandleModel.Handle findHandle(HandleModel.Kind kind, int index) {
        for (int i = 0; i < handleCount; i++) {
            if (handleBuf[i].kind == kind && handleBuf[i].index == index) return handleBuf[i];
        }
        return null;
    }

    /**
     * SPEC H — draw the bend net: lattice dots (blue) over lattice grid lines, all projected
     * through the live quad. A degenerate quad draws nothing rather than half a net; a refused
     * dot mid-frame does the same. No allocation: reused fields only.
     */
    private void drawBendNet(@NonNull Canvas c, @NonNull Host h) {
        float[] hh = TransformQuad.unitToQuad(quad);
        if (hh == null) return;
        int n = h.bendHandleCount();
        if (n <= 0 || n * 2 > bendPts.length) return;
        for (int i = 0; i < n; i++) {
            if (!h.bendHandlePosition(i, hh, bendScratch)) return;
            bendPts[i * 2] = bendScratch[0];
            bendPts[i * 2 + 1] = bendScratch[1];
        }
        int side = h.bendGridSide();
        if (side >= 2 && side * side == n) {
            stroke.setColor(withAlpha(HandleModel.COLOR_BEND, 0x8C));
            stroke.setStrokeWidth(dp(1f));
            for (int r = 0; r < side; r++) {
                for (int cc = 0; cc < side - 1; cc++) {
                    int a = r * side + cc;
                    c.drawLine(bendPts[a * 2], bendPts[a * 2 + 1],
                            bendPts[a * 2 + 2], bendPts[a * 2 + 3], stroke);
                }
            }
            for (int cc = 0; cc < side; cc++) {
                for (int r = 0; r < side - 1; r++) {
                    int a = r * side + cc, b = (r + 1) * side + cc;
                    c.drawLine(bendPts[a * 2], bendPts[a * 2 + 1],
                            bendPts[b * 2], bendPts[b * 2 + 1], stroke);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            boolean grabbed = i == bendDragIndex;
            float r = dp(grabbed ? 9f : 7f);
            fill.setColor(GLYPH_FILL);
            c.drawCircle(bendPts[i * 2], bendPts[i * 2 + 1], r, fill);
            stroke.setColor(HandleModel.COLOR_BEND);
            stroke.setStrokeWidth(dp(grabbed ? 2.3f : 1.75f));
            c.drawCircle(bendPts[i * 2], bendPts[i * 2 + 1], r, stroke);
        }
    }

    // ── The role ring ────────────────────────────────────────────────────
    //
    // Each option's own OUTLINE is the mode it installs — amber square = Scale, green diamond =
    // Tilt, red circle = Free, blue net = the Bend layer — with the word inside the shape. That is
    // why there is no icon set: the ring is the legend.

    private boolean ringOpen;
    private boolean ringIsCorner;
    private int ringIndex;
    private float ringCx, ringCy;

    /**
     * The bend slot's live state. FALSE while the mesh lane is unbuilt: the slot is still drawn,
     * greyed and inert, because its POSITION is approved muscle memory and moving the other three
     * options to close the gap would have to be undone when bend lands.
     */
    private boolean ringBendEnabled = false;

    public void setBendAvailable(boolean on) {
        ringBendEnabled = on;
        if (!on) {
            bendMode = false;
            cancelBendDrag();
        }
        invalidate();
    }

    // ── SPEC H bend net ──────────────────────────────────────────────
    //
    // The net sits OVER the top (HandleModel.BEND_IS_A_NET): its dots hit-test first while
    // visible, and the structural handles keep working underneath — a near-miss past the
    // dots' slightly smaller radius still finds them. Toggling the net writes nothing;
    // the pose is created on the first dot drag, so opening the tool is byte-identical.

    /** Net visible. Set by the ring Bend slot and by selection (shown when bent). */
    private boolean bendMode;
    /** Dot being dragged, or -1. Separate from dragKind (a bend never reshapes the quad). */
    private int bendDragIndex = -1;
    private boolean bendMoved;
    private float bendDownX, bendDownY;
    /** Projected dots, handle-major x,y. Sized to the coarsest lattice (25 handles). */
    private final float[] bendPts = new float[50];
    private final float[] bendScratch = new float[2];

    /**
     * Show or hide the bend net. Honoured only for a supporting host while the tool is
     * available — text/PiP/spine stay net-free however they are asked.
     */
    public void setBendVisible(boolean on) {
        Host h = host;
        bendMode = on && ringBendEnabled && h != null && h.supportsBend();
        if (!bendMode) cancelBendDrag();
        invalidate();
    }

    public boolean isBendVisible() { return bendMode; }

    private void cancelBendDrag() {
        bendDragIndex = -1;
        bendMoved = false;
    }

    /**
     * AFFINE ONLY: this object can be moved, scaled, rotated and mirrored, and nothing else.
     *
     * <p>Set for a SPINE (master) clip, whose picture is drawn by the GL preview chain and the
     * media3 effect chain — neither of which carries a perspective term for the base picture, so
     * there is no homography for a Tilt, a Free corner or a fold to be rendered by. Rather than
     * let the ring offer roles whose result nothing could draw, the ring greys Tilt, Free and the
     * fold exactly as it already greys the unbuilt bend slot, and every corner and edge behaves as
     * plain Scale however it is currently configured.</p>
     *
     * <p>It is a property of the OBJECT, not of the surface, which is why it is a flag here rather
     * than a second view: the handles, the arc, the loupe, the HUD and the gesture grammar are
     * identical for a spine clip and an image, and the owner should not have to learn two of
     * anything. {@code SpineTransformHost.writeQuad} refuses a non-affine quad as well; this flag
     * is the interface and that refusal is the safety net.</p>
     */
    private boolean affineOnly = false;

    public void setAffineOnly(boolean on) {
        if (affineOnly == on) return;
        affineOnly = on;
        if (on) handles.resetHelpers();
        closeRing();
        invalidate();
    }

    private float ringRadiusPx() { return dp(112f); }
    private float ringCardinalPx() { return dp(70f); }
    private float ringDiagonalPx() { return dp(78f) / 1.41421356f; }
    private float ringModeSizePx() { return dp(58f); }
    private float ringDiagSizePx() { return dp(46f); }

    /** N=Scale · E=Free · S=Tilt · W=Bend, as approved. Index-aligned with {@link #ringCardXY}. */
    private static final HandleModel.Role[] RING_ROLES =
            {HandleModel.Role.SCALE, HandleModel.Role.FREE, HandleModel.Role.TILT, null};

    private void ringCardXY(int i, float[] out) {
        float r = ringCardinalPx();
        float[][] p = {{0, -r}, {r, 0}, {0, r}, {-r, 0}};
        out[0] = ringCx + p[i][0];
        out[1] = ringCy + p[i][1];
    }

    private void ringDiagXY(int i, float[] out) {
        float r = ringDiagonalPx();
        float[][] p = {{r, -r}, {r, r}, {-r, r}, {-r, -r}};
        out[0] = ringCx + p[i][0];
        out[1] = ringCy + p[i][1];
    }

    private void openRing(boolean corner, int index, float atX, float atY) {
        loupeShowing = false;
        ringIsCorner = corner;
        ringIndex = index;
        float pad = ringRadiusPx() + dp(2f);
        ringCx = TransformQuad.clamp(atX, pad, Math.max(pad, getWidth() - pad));
        ringCy = TransformQuad.clamp(atY, pad, Math.max(pad, getHeight() - pad));
        ringOpen = true;
        invalidate();
    }

    public void closeRing() {
        if (!ringOpen) return;
        ringOpen = false;
        invalidate();
    }

    public boolean isRingOpen() { return ringOpen; }

    private void drawRing(@NonNull Canvas c) {
        float r = ringRadiusPx();
        fill.setColor(0xF613111C);
        c.drawCircle(ringCx, ringCy, r, fill);
        stroke.setColor(0xFF4C3F7A);
        stroke.setStrokeWidth(dp(1f));
        c.drawCircle(ringCx, ringCy, r, stroke);

        HandleModel.Role current = ringIsCorner ? handles.corner(ringIndex) : handles.edge(ringIndex);
        float sz = ringModeSizePx();
        for (int i = 0; i < 4; i++) {
            ringCardXY(i, scratch2);
            HandleModel.Role role = RING_ROLES[i];
            boolean bend = role == null;
            // affineOnly greys Tilt and Free the same way the unbuilt bend slot is greyed: the
            // slot keeps its approved position, and its colour says it will not do anything.
            boolean inert = bend ? !ringBendEnabled
                    : (affineOnly && role != HandleModel.Role.SCALE);
            boolean on = !bend && !inert && role == current;
            int col = inert ? 0xFF5A5A68
                    : (bend ? HandleModel.COLOR_BEND : HandleModel.colorOf(role));
            int back = on ? (role == HandleModel.Role.SCALE ? 0xFF3A2C0D
                    : role == HandleModel.Role.TILT ? 0xFF0F2E1E : 0xFF3A0F22) : 0xFF15151D;
            HandleModel.Shape shape = bend ? HandleModel.Shape.NET
                    : role == HandleModel.Role.SCALE ? HandleModel.Shape.SQUARE
                    : role == HandleModel.Role.TILT ? HandleModel.Shape.DIAMOND
                    : HandleModel.Shape.CIRCLE;
            // Same optical size whatever the outline: a circle has to be bigger than a square to
            // look the same weight, and a diamond smaller.
            float rad = shape == HandleModel.Shape.DIAMOND ? sz * 0.336f
                    : shape == HandleModel.Shape.CIRCLE ? sz * 0.448f : sz * 0.397f;
            drawShape(c, shape, col, scratch2[0], scratch2[1], rad,
                    on ? dp(2.3f) : dp(1.75f), back, 0f);
            text.setTextSize(dp(10f));
            text.setFakeBoldText(true);
            text.setColor(on ? Color.WHITE : col);
            c.drawText(bend ? "Bend" : HandleModel.nameOf(role),
                    scratch2[0], scratch2[1] + dp(3.5f), text);
        }

        // Diagonals. Corner: two mirrors. Edge: a fold and a mirror. Then the two resets, which
        // own strictly disjoint territory and say which in their own sub-label.
        String[] glyphs = ringIsCorner
                ? new String[]{"⇄", "⇅", "▣", "↺"}
                : new String[]{"⤴", (ringIndex == TransformQuad.TOP
                        || ringIndex == TransformQuad.BOTTOM) ? "⇄" : "⇅",
                        "▣", "↺"};
        String[] subs = ringIsCorner
                ? new String[]{"flip", "flip", "object", "helpers"}
                : new String[]{"fold", "mirror", "object", "helpers"};
        float ds = ringDiagSizePx();
        for (int i = 0; i < 4; i++) {
            ringDiagXY(i, scratch2);
            // The FOLD (edge slot 0) is a homography, so it is greyed on an affine-only object
            // for the same reason Tilt and Free are. Both mirrors and both resets stay live —
            // a mirror is a negative scale and a reset touches no shape at all.
            boolean inert = affineOnly && !ringIsCorner && i == 0;
            fill.setColor(0xFF171720);
            c.drawCircle(scratch2[0], scratch2[1], ds / 2f, fill);
            stroke.setColor(0xFF33333F);
            stroke.setStrokeWidth(dp(1f));
            c.drawCircle(scratch2[0], scratch2[1], ds / 2f, stroke);
            text.setColor(inert ? 0xFF5A5A68 : 0xFF9A9AAB);
            text.setTextSize(dp(14f));
            text.setFakeBoldText(false);
            c.drawText(glyphs[i], scratch2[0], scratch2[1] + dp(1f), text);
            text.setTextSize(dp(7.5f));
            text.setFakeBoldText(true);
            c.drawText(subs[i], scratch2[0], scratch2[1] + dp(12f), text);
        }

        text.setColor(0xFF6C6C7C);
        text.setTextSize(dp(9f));
        String which = (ringIsCorner ? "corner " : "edge ")
                + (ringIsCorner ? new String[]{"top-left", "top-right", "bottom-right", "bottom-left"}[ringIndex]
                : new String[]{"top", "right", "bottom", "left"}[ringIndex]);
        c.drawText(which, ringCx, ringCy + dp(3f), text);
        text.setFakeBoldText(false);
    }

    /** A touch while the ring is open. Returns true when the ring consumed it. */
    private boolean ringTouch(float x, float y) {
        float dxr = x - ringCx, dyr = y - ringCy;
        boolean insideRing = Math.hypot(dxr, dyr) <= ringRadiusPx();
        float pick = Math.max(dp(22f), ringModeSizePx() / 2f);
        for (int i = 0; i < 4; i++) {
            ringCardXY(i, scratch2);
            if (Math.hypot(x - scratch2[0], y - scratch2[1]) <= pick) {
                HandleModel.Role role = RING_ROLES[i];
                if (role == null) {
                    // SPEC H — the bend slot toggles the net OVER the top; structural handles
                    // keep working underneath. Inert (grey) until the lane lands — and inert
                    // LOUDLY, by staying grey rather than silently doing nothing.
                    if (!ringBendEnabled) return true;
                    Host hh = host;
                    if (hh != null && hh.supportsBend()) {
                        bendMode = !bendMode;
                        cancelBendDrag();
                    }
                    closeRing();
                    invalidate();
                    return true;
                }
                // Tilt and Free are not offered on an affine-only object: there is no renderer
                // for the distortion they would author. The touch is consumed rather than
                // dismissing the ring, so it reads as "this one is off", not as a mis-tap.
                if (affineOnly && role != HandleModel.Role.SCALE) return true;
                if (ringIsCorner) handles.setCorner(ringIndex, role);
                else handles.setEdge(ringIndex, role);
                closeRing();
                return true;
            }
        }
        float pickD = Math.max(dp(22f), ringDiagSizePx() / 2f);
        for (int i = 0; i < 4; i++) {
            ringDiagXY(i, scratch2);
            if (Math.hypot(x - scratch2[0], y - scratch2[1]) <= pickD) {
                diagAction(i);
                return true;
            }
        }
        if (insideRing) return true;      // a miss inside the dial is not a dismissal
        closeRing();
        return true;                      // ...but a touch outside it is, and it ends there
    }

    private void diagAction(int slot) {
        Host h = host;
        switch (slot) {
            case 0:
                if (ringIsCorner) {
                    if (h != null) { h.beginGesture(); h.flip(true); h.commitGesture("Flip"); }
                } else if (!affineOnly) {
                    foldOverEdge(ringIndex);
                }
                break;
            case 1:
                if (ringIsCorner) {
                    if (h != null) { h.beginGesture(); h.flip(false); h.commitGesture("Flip"); }
                } else if (h != null) {
                    // Mirroring ACROSS a top/bottom edge is a horizontal mirror of the picture;
                    // across a left/right edge it is a vertical one. Same axis the fold uses.
                    boolean horizontal = ringIndex == TransformQuad.TOP
                            || ringIndex == TransformQuad.BOTTOM;
                    h.beginGesture();
                    h.flip(horizontal);
                    h.commitGesture("Flip");
                }
                break;
            case 2:
                // RESET THE OBJECT — geometry only. Roles are deliberately untouched.
                if (h != null) {
                    h.beginGesture();
                    h.resetObjectGeometry();
                    h.commitGesture("Reset");
                }
                break;
            case 3:
                // RESET ALL HELPERS — handle roles only. Not one pixel of the picture moves, so
                // this is NOT an undo step: there is nothing in the project file to undo.
                handles.resetHelpers();
                break;
            default:
                break;
        }
        closeRing();
        syncFromHost();
        invalidate();
    }

    private void foldOverEdge(int e) {
        Host h = host;
        if (h == null) return;
        System.arraycopy(quad, 0, quadLastGood, 0, 8);
        if (!TransformQuad.foldOverEdge(quad, e)) return;
        if (!TransformQuad.isValid(quad)) { System.arraycopy(quadLastGood, 0, quad, 0, 8); return; }
        h.beginGesture();
        if (h.writeQuad(quad)) h.commitGesture("Fold");
        else System.arraycopy(quadLastGood, 0, quad, 0, 8);
        syncFromHost();
    }

    // ── Touch ────────────────────────────────────────────────────────────

    private final Runnable longPress = () -> {
        if (dragKind == null || moved || pinching) return;
        if (dragKind != HandleModel.Kind.CORNER && dragKind != HandleModel.Kind.EDGE) return;
        HandleModel.Handle h = findHandle(dragKind, dragIndex);
        cancelGesture();
        if (h != null) openRing(h.kind == HandleModel.Kind.CORNER, h.index, h.x, h.y);
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent e) {
        Host h = host;
        if (h == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(h, e);
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ringOpen) return true;
                startPinch(h, e);
                return true;
            case MotionEvent.ACTION_MOVE:
                return onMove(h, e);
            case MotionEvent.ACTION_POINTER_UP:
                if (pinching && e.getPointerCount() <= 2) {
                    endPinch(h, true);
                    // The surviving finger deliberately does NOT become a fresh pan: endPinch
                    // leaves dragKind null, so ACTION_MOVE finds nothing to drag. That is what
                    // stops a two-finger gesture that ends unevenly from sliding the object on
                    // the way out — the most common way a pinch "also moved it a bit".
                    return true;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return onUp(h, e.getActionMasked() == MotionEvent.ACTION_UP);
            default:
                return dragKind != null || pinching;
        }
    }

    private boolean onDown(@NonNull Host h, @NonNull MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (ringOpen) return ringTouch(x, y);
        if (hitsExitPill(x, y)) {
            Runnable r = onExit;
            if (r != null) r.run();
            return true;
        }
        if (!haveQuad) syncFromHost();
        if (!haveQuad) return false;
        rebuildHandles();

        // SPEC H — the net sits OVER the top: dots first, with a slightly smaller radius so
        // a near-miss still finds the structural handle underneath. A grabbed dot starts the
        // ONE snapshot its whole drag will undo to (host ensures the spec first, so the
        // first bend's undo restores "no bend at all").
        if (bendMode && h.supportsBend()) {
            float[] bh = TransformQuad.unitToQuad(quad);
            if (bh != null) {
                int bn = h.bendHandleCount();
                float br = dp(18f);
                int best = -1;
                float bestD = Float.MAX_VALUE;
                for (int i = 0; i < bn; i++) {
                    if (!h.bendHandlePosition(i, bh, bendScratch)) continue;
                    float d = (float) Math.hypot(bendScratch[0] - x, bendScratch[1] - y);
                    if (d <= br && d < bestD) { bestD = d; best = i; }
                }
                if (best >= 0) {
                    bendDragIndex = best;
                    bendMoved = false;
                    bendDownX = x;
                    bendDownY = y;
                    dragPointerId = e.getPointerId(0);
                    h.beginBendGesture();
                    setHud(null, x, y);
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    invalidate();
                    return true;
                }
            }
        }

        HandleModel.Handle hit = HandleModel.hitTest(handleBuf, handleCount, x, y, grabPx());
        if (hit == null && !TransformQuad.contains(quad, x, y)) {
            // Nothing of ours: let it through, so the empty-canvas rule survives.
            return false;
        }
        dragPointerId = e.getPointerId(0);
        downX = x;
        downY = y;
        moved = false;
        System.arraycopy(quad, 0, quadAtGrab, 0, 8);
        System.arraycopy(quad, 0, quadLastGood, 0, 8);

        if (hit != null) {
            dragKind = hit.kind;
            dragIndex = hit.index;
            // The handle centre minus the finger, so a slightly-off grab does not teleport it.
            grabOffsetX = hit.x - x;
            grabOffsetY = hit.y - y;
            if (hit.kind == HandleModel.Kind.ROTATE) {
                h.readPivot(scratch2);
                rotPivotX = scratch2[0];
                rotPivotY = scratch2[1];
                rotStartAngleRad = (float) Math.atan2(hit.y - rotPivotY, hit.x - rotPivotX);
                rotStartDeg = h.currentRotationDeg();
            } else {
                postDelayed(longPress, LONG_PRESS_MS);
                loupeShowing = loupeEnabled;
            }
        } else {
            dragKind = HandleModel.Kind.BODY;
            dragIndex = 0;
            grabOffsetX = 0f;
            grabOffsetY = 0f;
        }
        h.beginGesture();
        setHud(null, x, y);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        invalidate();
        return true;
    }

    private boolean onMove(@NonNull Host h, @NonNull MotionEvent e) {
        if (ringOpen) return true;
        if (pinching) { applyPinch(h, e); return true; }
        // SPEC H — a bend drag never reshapes the quad, so the homography is rebuilt from
        // the live quad every move: the dots track structural edits made underneath.
        if (bendDragIndex >= 0) { applyBendDrag(h, e); return true; }
        if (dragKind == null) return false;
        int idx = e.findPointerIndex(dragPointerId);
        if (idx < 0) return true;
        float x = e.getX(idx), y = e.getY(idx);
        if (!moved && Math.hypot(x - downX, y - downY) > dp(MOVE_SLOP_DP)) {
            moved = true;
            removeCallbacks(longPress);
        }
        if (!moved) return true;
        applyDrag(h, x + grabOffsetX, y + grabOffsetY, x, y);
        invalidate();
        return true;
    }

    private boolean onUp(@NonNull Host h, boolean clean) {
        removeCallbacks(longPress);
        if (pinching) { endPinch(h, clean); }
        // SPEC H — one dot drag is one undo press: the host puts ONCE (armed) and commits
        // through the same channel every other gesture uses. An unmoved tap commits nothing
        // (the snapshot then matches and records nothing).
        if (bendDragIndex >= 0) {
            boolean bm = bendMoved;
            bendDragIndex = -1;
            bendMoved = false;
            if (bm && clean) h.commitBendGesture("Bend");
            hudEndedAtMs = SystemClock.uptimeMillis();
            syncFromHost();
            invalidate();
            return true;
        }
        boolean was = dragKind != null;
        HandleModel.Kind kind = dragKind;
        boolean didMove = moved;
        cancelGesture();
        if (was && didMove && clean) {
            h.commitGesture(kind == HandleModel.Kind.BODY ? "Move"
                    : kind == HandleModel.Kind.ROTATE ? "Rotate"
                    : "Distort");
        } else if (was && !didMove && clean && kind == HandleModel.Kind.BODY) {
            // A tap on the picture that moved nothing. Pair it with the previous one and forward
            // the double-tap; a single tap deliberately does nothing at all.
            long nowMs = SystemClock.uptimeMillis();
            if (nowMs - lastBodyTapUpMs <= DOUBLE_TAP_MS) {
                lastBodyTapUpMs = 0L;
                Runnable dt = onDoubleTap;
                if (dt != null) dt.run();
            } else {
                lastBodyTapUpMs = nowMs;
            }
        }
        hudEndedAtMs = SystemClock.uptimeMillis();
        syncFromHost();
        invalidate();
        return was || ringOpen;
    }

    private void cancelGesture() {
        removeCallbacks(longPress);
        dragKind = null;
        dragPointerId = -1;
        moved = false;
        loupeShowing = false;
    }

    /**
     * One frame of a one-finger drag.
     *
     * <p>The shape ops write the LIVE quad (they are all "move toward the finger", so re-applying
     * them each frame converges rather than compounding); the scale ops rebuild from the grab-time
     * quad, which is what stops a scale from compounding against itself. Anything that comes out
     * invalid — folded through, collapsed, or refused by the host because it is beyond what the
     * corner-pin can express — is rolled back to the last good pose, so a drag pushed too far
     * simply stops moving instead of exploding.</p>
     */
    private void applyDrag(@NonNull Host h, float tx, float ty, float fingerX, float fingerY) {
        System.arraycopy(quad, 0, quadLastGood, 0, 8);
        boolean shapeChanged = true;
        String hud;
        switch (dragKind) {
            case BODY: {
                float dx = fingerX - downX, dy = fingerY - downY;
                System.arraycopy(quadAtGrab, 0, quad, 0, 8);
                TransformQuad.translate(quad, dx, dy);
                h.writeTranslate(dx, dy);
                shapeChanged = false;
                hud = signed(dx) + ", " + signed(dy);
                setHud(hud, fingerX, fingerY);
                return;
            }
            case ROTATE: {
                float ang = (float) Math.atan2(ty - rotPivotY, tx - rotPivotX);
                float deltaDeg = (float) Math.toDegrees(ang - rotStartAngleRad);
                // SPEC B — the live preview must orbit the SAME point the render orbits: the
                // stored pivot, not the presented centre. Rotating about the centre spun the
                // handles in place while the picture swung round the pivot — the two met only
                // at the gesture's start angle (JoyRaptor, 2026-09-05: "the helper making a 360
                // degree rotation on itself whereas the image is making a 360 plus moving in
                // a circle").
                h.readFoldPivot(scratch2);
                TransformQuad.rotateAbout(quad, quadAtGrab, scratch2[0], scratch2[1],
                        ang - rotStartAngleRad);
                float abs = rotStartDeg + deltaDeg;
                h.writeRotation(abs);
                shapeChanged = false;
                setHud(Math.round(norm180(abs)) + "° (" + signedDeg(norm180(deltaDeg)) + ")",
                        tx, ty);
                return;
            }
            case CORNER: {
                // affineOnly overrides whatever role the handle carries. The ring never lets a
                // spine clip's handle become Tilt or Free, but a handle configured while an IMAGE
                // was selected lives in that object's own HandleModel and the two maps are keyed
                // separately — this is the belt to the ring's braces, and it costs one read.
                HandleModel.Role r = affineOnly
                        ? HandleModel.Role.SCALE : handles.corner(dragIndex);
                if (r == HandleModel.Role.SCALE) {
                    if (!TransformQuad.scaleCorner(quad, quadAtGrab, dragIndex, tx, ty,
                            scratchFactors)) return;
                    hud = pct(scratchFactors[0])
                            + (Math.abs(scratchFactors[0] - scratchFactors[1]) < 0.005f ? ""
                            : " × " + pct(scratchFactors[1]));
                } else if (r == HandleModel.Role.TILT) {
                    TransformQuad.tiltCorner(quad, dragIndex, tx, ty);
                    hud = signed(tx - quadAtGrab[dragIndex * 2])
                            + ", " + signed(ty - quadAtGrab[dragIndex * 2 + 1]);
                } else {
                    TransformQuad.freeCorner(quad, dragIndex, tx, ty);
                    hud = signed(tx - quadAtGrab[dragIndex * 2])
                            + ", " + signed(ty - quadAtGrab[dragIndex * 2 + 1]);
                }
                break;
            }
            case EDGE: {
                HandleModel.Role r = affineOnly
                        ? HandleModel.Role.SCALE : handles.edge(dragIndex);
                TransformQuad.edgeMid(quadAtGrab, dragIndex, scratch2);
                if (r == HandleModel.Role.SCALE) {
                    if (!TransformQuad.scaleEdge(quad, quadAtGrab, dragIndex, tx, ty,
                            scratchFactors)) return;
                    hud = pct(scratchFactors[0]);
                } else if (r == HandleModel.Role.TILT) {
                    TransformQuad.tiltEdge(quad, dragIndex, tx, ty);
                    hud = signed(tx - scratch2[0]) + ", " + signed(ty - scratch2[1]);
                } else {
                    TransformQuad.freeEdge(quad, dragIndex, tx, ty);
                    hud = signed(tx - scratch2[0]) + ", " + signed(ty - scratch2[1]);
                }
                break;
            }
            default:
                return;
        }
        if (shapeChanged) {
            if (!TransformQuad.isValid(quad) || !h.writeQuad(quad)) {
                System.arraycopy(quadLastGood, 0, quad, 0, 8);
                return;
            }
            setHud(hud, tx, ty);
        }
    }

    /**
     * SPEC H — one frame of a bend-dot drag. The quad is untouched (a bend never reshapes
     * it); the host turns the finger into a guarded nudge and asks for the GL resync that
     * makes the preview follow. A refused dot (fold, degenerate frame) simply stops.
     */
    private void applyBendDrag(@NonNull Host h, @NonNull MotionEvent e) {
        int idx = e.findPointerIndex(dragPointerId);
        if (idx < 0) return;
        float x = e.getX(idx), y = e.getY(idx);
        if (!bendMoved && Math.hypot(x - bendDownX, y - bendDownY) > dp(MOVE_SLOP_DP)) {
            bendMoved = true;
        }
        if (!bendMoved) return;
        float[] hh = TransformQuad.unitToQuad(quad);
        if (hh == null) return;
        float[] inv = TransformQuad.invert3x3(hh);
        if (inv == null) return;
        if (h.bendDragTo(bendDragIndex, inv, x, y)) invalidate();
    }

    // ── Two fingers ──────────────────────────────────────────────────────

    private void startPinch(@NonNull Host h, @NonNull MotionEvent e) {
        if (e.getPointerCount() < 2) return;
        removeCallbacks(longPress);
        // A one-finger drag already in flight is ABSORBED, not committed: one continuous
        // two-finger gesture is one edit in the user's head, and it must be one undo step.
        // SPEC H — a bend-dot drag absorbs the same way: its live pose stays, and the
        // pinch's begin/commit covers it (its snapshot is already taken, so it is not
        // taken twice and the pre-bend state is what undo restores).
        if (dragKind == null && bendDragIndex < 0) h.beginGesture();
        if (bendDragIndex >= 0) {
            bendDragIndex = -1;
            bendMoved = false;
        }
        dragKind = null;
        loupeShowing = false;
        pinchIdA = e.getPointerId(0);
        pinchIdB = e.getPointerId(1);
        pinchAx = e.getX(0);
        pinchAy = e.getY(0);
        pinchBx = e.getX(1);
        pinchBy = e.getY(1);
        System.arraycopy(quad, 0, quadAtGrab, 0, 8);
        System.arraycopy(quad, 0, quadLastGood, 0, 8);
        h.readPivot(scratch2);
        pinchPivotX = scratch2[0];
        pinchPivotY = scratch2[1];
        pinchFactor = 1f;
        pinchDeg = 0f;
        pinching = true;
        moved = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        invalidate();
    }

    /**
     * Scale and rotate about the MIDPOINT BETWEEN THE FINGERS, and nothing else.
     *
     * <p>The similarity that carries the old midpoint to the new one is applied to the whole
     * object, so the picture point that was under the midpoint when the gesture began is still
     * under it at the end. That is written as one {@code writeSimilarity} rather than as a scale
     * plus a rotate plus a move, because the three are one gesture and splitting them across three
     * host calls would let a keyframe-armed host record three separate keys for one pinch.</p>
     */
    private void applyPinch(@NonNull Host h, @NonNull MotionEvent e) {
        int ia = e.findPointerIndex(pinchIdA), ib = e.findPointerIndex(pinchIdB);
        if (ia < 0 || ib < 0) return;
        float ax = e.getX(ia), ay = e.getY(ia), bx = e.getX(ib), by = e.getY(ib);
        float m0x = (pinchAx + pinchBx) / 2f, m0y = (pinchAy + pinchBy) / 2f;
        float m1x = (ax + bx) / 2f, m1y = (ay + by) / 2f;
        float d0 = (float) Math.max(1.0, Math.hypot(pinchBx - pinchAx, pinchBy - pinchAy));
        float d1 = (float) Math.hypot(bx - ax, by - ay);
        float f = TransformQuad.clamp(d1 / d0, 0.08f, 12f);
        double th = Math.atan2(by - ay, bx - ax) - Math.atan2(pinchBy - pinchAy, pinchBx - pinchAx);
        // Normalise into ±π so crossing the seam does not spin the object a whole turn.
        while (th > Math.PI) th -= 2 * Math.PI;
        while (th < -Math.PI) th += 2 * Math.PI;
        pinchFactor = f;
        pinchDeg = (float) Math.toDegrees(th);
        moved = true;

        System.arraycopy(quadLastGood, 0, quadAtGrab, 0, 8);   // keep the rollback pose current
        TransformQuad.pinch(quad, quadAtGrab, m0x, m0y, m1x, m1y, f, (float) th);
        // Where the object's own centre ends up under the same similarity.
        float dx = pinchPivotX - m0x, dy = pinchPivotY - m0y;
        float cs = (float) Math.cos(th), sn = (float) Math.sin(th);
        float ncx = m1x + (cs * dx - sn * dy) * f;
        float ncy = m1y + (sn * dx + cs * dy) * f;
        h.writeSimilarity(f, pinchDeg, ncx, ncy);
        setHud(pct(f) + "  " + signedDeg(pinchDeg), m1x, m1y);
        invalidate();
    }

    private void endPinch(@NonNull Host h, boolean clean) {
        if (!pinching) return;
        pinching = false;
        pinchIdA = pinchIdB = -1;
        if (clean && moved) h.commitGesture("Transform");
        hudEndedAtMs = SystemClock.uptimeMillis();
        moved = false;
        syncFromHost();
        invalidate();
    }

    // ── Formatting ───────────────────────────────────────────────────────

    private static String pct(float f) { return Math.round(f * 100f) + "%"; }

    private static String signed(float v) {
        int i = Math.round(v);
        return (i >= 0 ? "+" : "") + i;
    }

    private static String signedDeg(float v) {
        int i = Math.round(v);
        return (i >= 0 ? "+" : "") + i + "°";
    }

    private static float norm180(float deg) {
        while (deg > 180f) deg -= 360f;
        while (deg < -180f) deg += 360f;
        return deg;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(longPress);
        super.onDetachedFromWindow();
    }
}
