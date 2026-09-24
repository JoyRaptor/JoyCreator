package com.fadcam.ui.faditor.transform;

import com.fadcam.ui.faditor.Studio;

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
import android.view.ViewGroup;

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

        /**
         * The canvas rect the gesture pixels are measured against, in this view's pixels.
         * The view snapshots it when a gesture starts and rebases its frozen state if the
         * rect moves underneath it (drawer resize, controls fade) instead of baking the
         * gap into the project on release.
         */
        @NonNull
        RectF videoRect();

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

        /**
         * Can this object actually mirror?
         *
         * <p>Text and PiP cannot — neither has an honest render path for a mirrored picture, so
         * their {@code flip} bodies are deliberately empty. But the ring called
         * {@code beginGesture / flip / commitGesture} unconditionally, and the targets record an
         * undo action whenever a gesture was begun. So flipping a text box or a PiP did nothing
         * AND cost the user an undo press, twice over: press undo and the first press appears to
         * do nothing at all.
         *
         * <p>Gated here rather than by making the empty bodies "smarter", for the same reason
         * {@link #supportsBend()} is: a capability the renderers do not have is a fact about the
         * type, and the surface should not offer it in the first place.
         */
        default boolean supportsFlip() { return true; }
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
        if (dragKind == null && !pinching) {
            syncFromHost();
        } else {
            rebaseForRectChange();
        }
        invalidate();
    }

    /**
     * SPEC K — the canvas rect moved underneath a live gesture (drawer resize, controls
     * fade, anything re-laying-out the preview container mid-drag). The frozen snapshot
     * and the live finger would then speak different frames, and the commit would bake
     * the gap into the project as a teleport on finger-up. Rebase every stored pixel to
     * the new rect instead: the gesture continues exactly where the finger is, with no
     * model write, no undo step and no snap. Pure chrome.
     */
    private void rebaseForRectChange() {
        Host h = host;
        if (h == null || !haveRectAtGrab) return;
        RectF cur;
        try {
            cur = h.videoRect();
        } catch (RuntimeException e) {
            return;   // no rect, no rebase — the gesture keeps its frame
        }
        if (cur == null || cur.width() <= 0.5f || cur.height() <= 0.5f) return;
        float oL = rectAtGrab.left, oT = rectAtGrab.top;
        float oW = rectAtGrab.width(), oH = rectAtGrab.height();
        if (!(oW > 0.5f) || !(oH > 0.5f)) return;
        if (cur.left == oL && cur.top == oT && cur.width() == oW && cur.height() == oH) return;
        float sx = cur.width() / oW, sy = cur.height() / oH;
        if (!isFinite(sx) || !isFinite(sy)) return;
        // Every frozen pixel moves to the new frame; live fingers already live there.
        TransformQuad.rebasePoints(quad, oL, oT, oW, oH,
                cur.left, cur.top, cur.width(), cur.height());
        TransformQuad.rebasePoints(quadAtGrab, oL, oT, oW, oH,
                cur.left, cur.top, cur.width(), cur.height());
        TransformQuad.rebasePoints(quadLastGood, oL, oT, oW, oH,
                cur.left, cur.top, cur.width(), cur.height());
        // Differences scale with the rect; origins remap.
        float[] dd = {downX, downY};
        if (TransformQuad.rebasePoints(dd, oL, oT, oW, oH,
                cur.left, cur.top, cur.width(), cur.height())) {
            downX = dd[0];
            downY = dd[1];
        }
        grabOffsetX *= sx;
        grabOffsetY *= sy;
        if (pinching) {
            float[] pf = {pinchAx, pinchAy, pinchBx, pinchBy, pinchPivotX, pinchPivotY};
            if (TransformQuad.rebasePoints(pf, oL, oT, oW, oH,
                    cur.left, cur.top, cur.width(), cur.height())) {
                pinchAx = pf[0]; pinchAy = pf[1];
                pinchBx = pf[2]; pinchBy = pf[3];
                pinchPivotX = pf[4]; pinchPivotY = pf[5];
            }
        }
        if (dragKind == HandleModel.Kind.ROTATE) {
            float[] rp = {rotPivotX, rotPivotY, rotGrabX, rotGrabY};
            if (TransformQuad.rebasePoints(rp, oL, oT, oW, oH,
                    cur.left, cur.top, cur.width(), cur.height())) {
                rotPivotX = rp[0];
                rotPivotY = rp[1];
                rotGrabX = rp[2];
                rotGrabY = rp[3];
            }
            // The grab angle is re-derived in the new frame so the rotation does not jump;
            // non-uniform rect changes distort angles slightly, and continuity wins.
            rotStartAngleRad = (float) Math.atan2(rotGrabY - rotPivotY, rotGrabX - rotPivotX);
        }
        // A ring opened pre-resize would mis-hit; it reopens on the next long-press.
        closeRing();
        rectAtGrab.set(cur);
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    // ── Metrics ──────────────────────────────────────────────────────────

    private final float d;                     // density: 1dp in px

    private float dp(float v) { return v * d; }

    /** Base grab radius. 22dp radius = a 44dp target, whatever the glyph's drawn size. */
    private float grabPx() { return dp(22f); }

    /** How far outside the top edge the spin arc floats, on its hairline stalk. */
    private float rotateStandoffPx() { return dp(46f); }

    /**
     * Rotation detent width. Inside {@code ENTER} of a cardinal the angle sticks to it; once
     * escaped it does not re-stick until back inside {@code EXIT}... deliberately the SMALLER of
     * the two, so breaking out is sticky and re-entering is not grabby. Tuned here in one line —
     * JoyRaptor asked for "a bit more difficult", not "impossible".
     */
    private static final float ROT_DETENT_ENTER_DEG = 3.5f;
    private static final float ROT_DETENT_EXIT_DEG = 1.5f;

    /**
     * Degrees of two-finger twist absorbed before a pinch starts turning the object — see
     * applyPinch. The old handles' number (PreviewHandlesOverlay.PINCH_ROT_DEADZONE_DEG), which
     * JoyRaptor had used for weeks without complaint.
     */
    private static final float PINCH_ROT_DEADZONE_DEG = 7f;

    /**
     * THE ONE ROTATION SNAP HOOK. Every rotation this surface writes — the one-finger arc and
     * the two-finger pinch — passes its absolute angle (degrees, after the cardinal detent)
     * through here, so the global snap only has to be wired in one place.
     *
     * <p>TODO(snap): the lead is building the global snap settings (master toggle + per-category
     * flags, including "rotation"). Wire that here and nowhere else; until then this is the
     * identity, so nothing about rotation changes.
     */
    private float snapRotation(float deg) {
        // SnapSettings "Rotation": catch the nearest multiple of the chosen step (15 degrees by
        // default) within 4 degrees, scaled by the panel's Gentle / Normal / Strong.
        Context c = getContext();
        float reach = com.fadcam.ui.faditor.tools.SnapSettings.reach(
                c, com.fadcam.ui.faditor.tools.SnapSettings.Kind.ROTATION);
        if (reach <= 0f) return deg;
        int step = com.fadcam.ui.faditor.tools.SnapSettings.rotationStepDeg(c);
        float nearest = Math.round(deg / step) * (float) step;
        return Math.abs(deg - nearest) <= ROT_SNAP_REACH_DEG * reach ? nearest : deg;
    }

    /** Rotation snap's reach at Normal strength. */
    private static final float ROT_SNAP_REACH_DEG = 4f;

    private static final long LONG_PRESS_MS = 450L;
    private static final float MOVE_SLOP_DP = 7f;
    private static final long HUD_FADE_MS = 400L;

    /**
     * Uniform-snap hysteresis for corner scaling, as relative factor disagreement:
     * break out of uniform past 15%, rejoin under 10%. Tuned so ordinary diagonal
     * drags stay locked while a deliberate sideways push escapes on purpose.
     */
    private static final float SNAP_BREAK_REL = 0.15f;
    private static final float SNAP_REJOIN_REL = 0.10f;

    /**
     * Snap-tint fade for the quad outline during corner scales: CYAN at rest and
     * at identity, tilt-green while snapped uniform, free-red once broken out. The
     * edges carry the state, never the handle glyphs (their shape+colour vocabulary
     * already means the handle's role, not the drag's momentary state).
     */
    private static final long SNAP_FADE_MS = 150L;
    private int snapTintFrom = HandleModel.COLOR_SELECTION;
    private int snapTintTo = HandleModel.COLOR_SELECTION;
    private long snapTintStartMs = 0L;

    /** Point the outline tint at {@code color}; fades from whatever it shows now. */
    private void setSnapTint(int color) {
        if (color == snapTintTo) return;
        snapTintFrom = snapTintNow();
        snapTintTo = color;
        snapTintStartMs = SystemClock.uptimeMillis();
    }

    /** The outline colour right now, advancing an in-flight fade (and continuing it). */
    private int snapTintNow() {
        long age = SystemClock.uptimeMillis() - snapTintStartMs;
        if (age >= SNAP_FADE_MS) return snapTintTo;
        if (age <= 0) return snapTintFrom;
        postInvalidateOnAnimation();
        return blendArgb(snapTintFrom, snapTintTo, age / (float) SNAP_FADE_MS);
    }

    private static int blendArgb(int a, int b, float f) {
        float g = Math.max(0f, Math.min(1f, f));
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * g);
        int gg = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * g);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * g);
        int al = Math.round(((a >>> 24)) + (((b >>> 24)) - ((a >>> 24))) * g);
        return (al << 24) | (r << 16) | (gg << 8) | bl;
    }

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
    /** True once this corner-scale drag has broken out of uniform snap to free aspect. */
    private boolean cornerSnapBroken;
    private float grabOffsetX, grabOffsetY;   // handle centre minus finger, so nothing jumps
    private float downX, downY;
    private boolean moved;

    // Pure-rotation gesture state.
    private float rotPivotX, rotPivotY, rotStartAngleRad, rotStartDeg;
    /** True once this drag has escaped the cardinal detent (see ROT_DETENT_*). */
    private boolean rotDetentBroken;
    /** Rotate-handle grab point, for re-deriving the grab angle after a rect rebase. */
    private float rotGrabX, rotGrabY;

    /** Canvas rect the gesture pixels are measured against, frozen at gesture start. */
    private final RectF rectAtGrab = new RectF();
    private boolean haveRectAtGrab;

    // Two-finger state.
    private boolean pinching;
    private int pinchIdA = -1, pinchIdB = -1;
    private float pinchAx, pinchAy, pinchBx, pinchBy;   // finger positions at the start
    private float pinchPivotX, pinchPivotY;             // the object's centre at the start
    private float pinchFactor = 1f, pinchDeg = 0f;
    /** The object's absolute rotation when the pinch began — the base the detent reads. */
    private float pinchStartDeg;
    /** This pinch has cleared {@link #PINCH_ROT_DEADZONE_DEG}; once unlocked it stays so. */
    private boolean pinchRotating;
    /** This pinch has escaped the cardinal detent (the one-finger ROT_DETENT_* rule). */
    private boolean pinchDetentBroken;

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
    private static final int GLYPH_FILL = Studio.SURFACE;

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
        if (loupe.isDrawingContent()) return;
        if (host == null) return;
        if (!haveQuad) syncFromHost();
        if (!haveQuad) return;
        rebuildHandles();
        drawSnapGuides(c);

        // The stalk first, so the arc's glyph sits on top of its own tether.
        TransformQuad.edgeMid(quad, TransformQuad.TOP, scratch2);
        stroke.setColor(withAlpha(HandleModel.COLOR_ROTATE, 0x60));
        stroke.setStrokeWidth(dp(0.9f));
        c.drawLine(scratch2[0], scratch2[1], rotateHandle[0], rotateHandle[1], stroke);

        // The quad itself, tinted by the corner-scale snap state (purple rest).
        path.reset();
        path.moveTo(quad[0], quad[1]);
        for (int i = 1; i < 4; i++) path.lineTo(quad[i * 2], quad[i * 2 + 1]);
        path.close();
        stroke.setColor(withAlpha(snapTintNow(), 0xE6));
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

        // The ghost goes UNDER the pills: it can be large, and "Done"/"Bend" must never end up
        // reading through a translucent wash of the object.
        drawReframeGhost(c);
        drawExitPill(c);
        drawBendPill(c);
        if (ringOpen) drawRing(c);
        drawLoupe(c);
        drawHud(c);
    }

    // ── The reframe GHOST ──────────────────────────────────────────────────
    //
    // SPEC K gave this job to a pill: when the selected quad's centroid left the view, a
    // rounded "Reframe" lozenge parked at the nearest on-screen point and one tap brought the
    // object home. SPEC M §3 is JoyRaptor's verdict on that pill —
    //     "The reframe UI button doesn't look good so I want you to explore alternatives."
    // — and of the three mocks in tasks/design/REFRAME_PILL_OPTIONS.html he picked C:
    //     "also i picked C - Ghost outline."
    // So the pill is GONE, and there is no widget at all in its place. What we draw instead is
    // the object's OWN quad, translated (never re-cornered) until it lands on screen, at low
    // opacity: shape, rotation and mirror all survive, because a pure translation of the four
    // live corners cannot change any of them. That is what makes it read as "your thing is over
    // there" rather than as a button. Tapping it runs the same undoable translate the pill ran,
    // so it is still exactly ONE undo press.
    //
    // Not to be confused with the pasteboard ghost (SPEC M §4), which dims off-canvas content
    // that is still partly in view. This one is for an object that is entirely gone. With SPEC L
    // baking translation out of the corner pin and SPEC P's commit backstop keeping 15% of the
    // quad on canvas, it should almost never appear — it is a safety net, not a feature.

    private final float[] reframeScratch = new float[2];
    /** The ghost's four corners, view pixels. Rebuilt by {@link #reframeGhostQuad()}. */
    private final float[] ghostQuad = new float[8];

    /** True when the ghost should be drawn and armed right now. */
    private boolean showReframeGhost() {
        if (host == null || !haveQuad) return false;
        if (dragKind != null || pinching || ringOpen || bendDragIndex >= 0) return false;
        TransformQuad.centroid(quad, reframeScratch);
        float cx = reframeScratch[0], cy = reframeScratch[1];
        return cx < 0f || cy < 0f || cx > getWidth() || cy > getHeight();
    }

    /** Where the object's centroid is dragged back to — the pill's own landing rule. */
    private float reframeHomeMargin() {
        return Math.min(dp(72f), Math.min(getWidth(), getHeight()) * 0.25f);
    }

    /**
     * Build the ghost into {@link #ghostQuad}. Two steps, both shape-preserving:
     *
     * <ol>
     *   <li><b>Clamp to the edge by TRANSLATION.</b> The centroid is clamped into the view by
     *       the same margin the tap will use, and all four corners move by that one delta — so
     *       the ghost sits against whichever edge the object left through, and its outline is
     *       congruent with the real quad. A rotated object's ghost is rotated the same way; a
     *       mirrored one stays mirrored, because corner ORDER is never touched.</li>
     *   <li><b>Shrink only if it would swamp the screen.</b> A huge object would otherwise
     *       cover the preview in a grey wash, which is the opposite of quiet. A uniform scale
     *       about the ghost's own centroid — never above 1 — caps its bounding box at 45% of
     *       the short side. Uniform scale is a similarity: shape, rotation and mirror all
     *       survive it too.</li>
     * </ol>
     *
     * @return false when the quad is degenerate or off-view maths went non-finite
     */
    private boolean reframeGhostQuad() {
        TransformQuad.centroid(quad, reframeScratch);
        float m = reframeHomeMargin();
        float tx = Math.max(m, Math.min(getWidth() - m, reframeScratch[0]));
        float ty = Math.max(m, Math.min(getHeight() - m, reframeScratch[1]));
        float dx = tx - reframeScratch[0], dy = ty - reframeScratch[1];
        if (!isFinite(dx) || !isFinite(dy)) return false;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float gx = quad[i * 2] + dx, gy = quad[i * 2 + 1] + dy;
            if (!isFinite(gx) || !isFinite(gy)) return false;
            ghostQuad[i * 2] = gx;
            ghostQuad[i * 2 + 1] = gy;
            minX = Math.min(minX, gx); maxX = Math.max(maxX, gx);
            minY = Math.min(minY, gy); maxY = Math.max(maxY, gy);
        }
        float span = Math.max(maxX - minX, maxY - minY);
        float cap = Math.min(getWidth(), getHeight()) * 0.45f;
        if (span > cap && cap > 1f) {
            float s = cap / span;
            for (int i = 0; i < 4; i++) {
                ghostQuad[i * 2] = tx + (ghostQuad[i * 2] - tx) * s;
                ghostQuad[i * 2 + 1] = ty + (ghostQuad[i * 2 + 1] - ty) * s;
            }
            minX = tx + (minX - tx) * s; maxX = tx + (maxX - tx) * s;
            minY = ty + (minY - ty) * s; maxY = ty + (maxY - ty) * s;
        }
        // Step 3 — a second translation, so the WHOLE outline is on screen. Clamping the
        // centroid alone leaves half a ghost hanging off the edge it came from, which reads as
        // clipping rather than as "here it is"; nudging the bounding box inside costs nothing,
        // and translation still cannot alter shape, rotation or mirror.
        float pad = dp(8f);
        float nx = 0f, ny = 0f;
        if (maxX - minX <= getWidth() - 2f * pad) {
            if (minX < pad) nx = pad - minX;
            else if (maxX > getWidth() - pad) nx = getWidth() - pad - maxX;
        }
        if (maxY - minY <= getHeight() - 2f * pad) {
            if (minY < pad) ny = pad - minY;
            else if (maxY > getHeight() - pad) ny = getHeight() - pad - maxY;
        }
        if (nx != 0f || ny != 0f) {
            for (int i = 0; i < 4; i++) {
                ghostQuad[i * 2] += nx;
                ghostQuad[i * 2 + 1] += ny;
            }
        }
        return true;
    }

    private void drawReframeGhost(@NonNull Canvas c) {
        if (!showReframeGhost()) return;
        if (!reframeGhostQuad()) return;
        path.reset();
        path.moveTo(ghostQuad[0], ghostQuad[1]);
        for (int i = 1; i < 4; i++) path.lineTo(ghostQuad[i * 2], ghostQuad[i * 2 + 1]);
        path.close();
        fill.setColor(0x24F4F4F5);
        c.drawPath(path, fill);
        stroke.setColor(0x8CF4F4F5);
        stroke.setStrokeWidth(dp(1.2f));
        c.drawPath(path, stroke);
        // A hairline back to where the object actually is, so the ghost says WHICH WAY as well
        // as "here". Clipped by the view like everything else; it just points off the edge.
        stroke.setColor(0x40F4F4F5);
        stroke.setStrokeWidth(dp(0.9f));
        float gcx = (ghostQuad[0] + ghostQuad[2] + ghostQuad[4] + ghostQuad[6]) / 4f;
        float gcy = (ghostQuad[1] + ghostQuad[3] + ghostQuad[5] + ghostQuad[7]) / 4f;
        c.drawLine(gcx, gcy, reframeScratch[0], reframeScratch[1], stroke);
    }

    /** Is this touch on the ghost (when shown)? A tiny ghost still gets a 44dp target. */
    private boolean hitsReframeGhost(float x, float y) {
        if (!showReframeGhost()) return false;
        if (!reframeGhostQuad()) return false;
        if (TransformQuad.contains(ghostQuad, x, y)) return true;
        float gcx = (ghostQuad[0] + ghostQuad[2] + ghostQuad[4] + ghostQuad[6]) / 4f;
        float gcy = (ghostQuad[1] + ghostQuad[3] + ghostQuad[5] + ghostQuad[7]) / 4f;
        return Math.hypot(x - gcx, y - gcy) <= dp(22f);
    }

    /** Move the object just far enough to grab again — one undoable translate. */
    private void doReframe() {
        Host h = host;
        if (h == null || !haveQuad) return;
        TransformQuad.centroid(quad, reframeScratch);
        float m = reframeHomeMargin();
        float tx = Math.max(m, Math.min(getWidth() - m, reframeScratch[0]));
        float ty = Math.max(m, Math.min(getHeight() - m, reframeScratch[1]));
        float dx = tx - reframeScratch[0], dy = ty - reframeScratch[1];
        if (!isFinite(dx) || !isFinite(dy)) return;
        if (Math.hypot(dx, dy) < dp(2f)) return;   // rounding dust, not a loss
        h.beginGesture();
        h.writeTranslate(dx, dy);
        h.commitGesture("Reframe");
        invalidate();
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

    /**
     * How far an open top drawer covers this view, in px (0 when none). The Done and Bend pills
     * are canvas controls: under a see-through drawer they read as the drawer's own buttons and
     * cannot be pressed, so they move down to sit just below its edge instead.
     */
    private float chromeTopInset;

    public void setChromeTopInset(float px) {
        px = Math.max(0f, px);
        if (px == chromeTopInset) return;
        chromeTopInset = px;
        invalidate();
    }

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
    /**
     * Where the first tap lifted, view-local px. Pairing is time AND distance: a tap, a
     * drag, then a tap must not pair into a double-tap (TEXT_REPAIR_PASS, 2026-09-23).
     */
    private float lastBodyTapX;
    private float lastBodyTapY;

    private static final long DOUBLE_TAP_MS = 320L;

    private void exitPillRect(@NonNull RectF out) {
        float w = dp(64f), h = dp(34f), m = dp(8f), top = m + chromeTopInset;
        out.set(m, top, m + w, top + h);
    }

    private void drawExitPill(@NonNull Canvas c) {
        if (onExit == null) return;
        exitPillRect(rectf);
        fill.setColor(Studio.alpha(Studio.SUNK, 0xE6));
        c.drawRoundRect(rectf, dp(17f), dp(17f), fill);
        stroke.setColor(Studio.FILM_EDGE);
        stroke.setStrokeWidth(dp(1f));
        c.drawRoundRect(rectf, dp(17f), dp(17f), stroke);
        text.setColor(Studio.INK);
        text.setTextSize(dp(12f));
        text.setFakeBoldText(true);
        c.drawText("Done", rectf.centerX(), rectf.centerY() + dp(4f), text);
        text.setFakeBoldText(false);
    }

    // ── The Bend switch ──────────────────────────────────────────────────
    //
    // SPEC M §1.3 — AN ALWAYS-REACHABLE WAY OUT. Bend used to be reachable only through the
    // long-press ring, and the ring lives on a handle: with the net covering every handle,
    // the mode had no exit at all. The handles are reachable again (see bendLayout), but
    // "long-press the right handle and find the right quarter of a ring" is not an escape
    // hatch a stuck user finds. So the net gets its own switch, in a fixed place, on screen
    // the whole time an object that CAN bend is selected — the same persistent Bend button the
    // approved prototype has (TRANSFORM_UI_FEEL.html: "the Bend button IS a state light").
    //
    // It is a state light, in the prototype's own three colours:
    //   grey outline  — net off
    //   blue outline  — net armed, nothing bent yet
    //   solid blue    — the picture is genuinely bent
    // Top-right, mirroring the Done pill's top-left, so neither can ever sit on the object;
    // drawn (and hit) before the ring and the loupe so it can never intercept them.

    private void bendPillRect(@NonNull RectF out) {
        float w = dp(78f), h = dp(34f), m = dp(8f), top = m + chromeTopInset;
        out.set(getWidth() - m - w, top, getWidth() - m, top + h);
    }

    /** Shown whenever the selected object can bend and no gesture is in flight. */
    private boolean showBendPill() {
        Host h = host;
        if (h == null || !haveQuad || !ringBendEnabled || !h.supportsBend()) return false;
        return dragKind == null && bendDragIndex < 0 && !pinching && !ringOpen;
    }

    private void drawBendPill(@NonNull Canvas c) {
        if (!showBendPill()) return;
        Host h = host;
        boolean bent = h != null && h.hasBend();
        bendPillRect(rectf);
        fill.setColor(bendMode && bent ? Studio.LINE : Studio.alpha(Studio.SUNK, 0xE6));
        c.drawRoundRect(rectf, dp(17f), dp(17f), fill);
        stroke.setColor(!bendMode ? Studio.LINE : (bent ? HandleModel.COLOR_BEND : Studio.INK_FAINT));
        stroke.setStrokeWidth(dp(bendMode ? 1.6f : 1f));
        c.drawRoundRect(rectf, dp(17f), dp(17f), stroke);
        text.setColor(!bendMode ? Studio.INK_FAINT : (bent ? Studio.INK : Studio.INK_DIM));
        text.setTextSize(dp(12f));
        text.setFakeBoldText(true);
        c.drawText(bendMode ? "Bend · on" : "Bend", rectf.centerX(), rectf.centerY() + dp(4f), text);
        text.setFakeBoldText(false);
    }

    /** Is this touch on the Bend switch? Padded out to a 44dp target. */
    private boolean hitsBendPill(float x, float y) {
        if (!showBendPill()) return false;
        bendPillRect(rectf);
        float padY = Math.max(0f, (dp(44f) - rectf.height()) / 2f);
        return x >= rectf.left && x <= rectf.right
                && y >= rectf.top - padY && y <= rectf.bottom + padY;
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
        stroke.setColor(Studio.alpha(Studio.AUDIO, 0x59));
        stroke.setStrokeWidth(dp(0.8f));
        c.drawLine(pinchAx, pinchAy, pinchBx, pinchBy, stroke);
        stroke.setColor(Studio.alpha(Studio.AUDIO, 0xF0));
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
        fill.setColor(withAlpha(0x0D0D10, (int) (0xD1 * alpha)));
        c.drawRoundRect(rectf, dp(7f), dp(7f), fill);
        stroke.setColor(withAlpha(0x8A8A94, (int) (0x47 * alpha)));
        stroke.setStrokeWidth(dp(1f));
        c.drawRoundRect(rectf, dp(7f), dp(7f), stroke);
        text.setColor(withAlpha(0xC4C4CE, (int) (0xFF * alpha)));
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

    /**
     * The magnifier, over the handle being dragged.
     *
     * <p>The CHROME — where the circle goes, the clip, walking the preview stack to draw the real
     * picture magnified, the crosshair and the rim — moved to {@link PreviewLoupe} when the
     * puppet surface needed the same thing. This method keeps only what is specific to THIS
     * tool: which handle is being held, and what to draw over the magnified picture.
     *
     * <p>Two copies of that chrome existed for a day and were filed as debt the same day. They
     * were identical by construction and would not have stayed identical: the first person to
     * tune a zoom or a rim colour would have tuned one of them.
     */
    private void drawLoupe(@NonNull Canvas c) {
        if (!loupeShowing || !loupeEnabled || dragKind == null) return;
        final HandleModel.Handle h = findHandle(dragKind, dragIndex);
        if (h == null) return;
        loupe.draw(c, this, loupeContentRoot, h.x, h.y, density(), fill, stroke,
                (lc, invZoom) -> {
                    // The object's outline, then every handle — the held one filled.
                    path.reset();
                    path.moveTo(quad[0], quad[1]);
                    for (int i = 1; i < 4; i++) path.lineTo(quad[i * 2], quad[i * 2 + 1]);
                    path.close();
                    stroke.setStyle(Paint.Style.STROKE);
                    stroke.setColor(withAlpha(snapTintNow(), 0xE6));
                    stroke.setStrokeWidth(dp(1.1f) * invZoom);
                    lc.drawPath(path, stroke);
                    for (int i = 0; i < handleCount; i++) {
                        HandleModel.Handle g = handleBuf[i];
                        boolean on = g == h;
                        float rr = (on ? dp(4.2f) : dp(2.6f)) * invZoom;
                        if (on) {
                            fill.setStyle(Paint.Style.FILL);
                            fill.setColor(g.color);
                            lc.drawCircle(g.x, g.y, rr, fill);
                        }
                        stroke.setColor(g.color);
                        stroke.setStrokeWidth((on ? dp(1.6f) : dp(1.1f)) * invZoom);
                        lc.drawCircle(g.x, g.y, rr, stroke);
                    }
                });
    }

    /** The shared magnifier. Re-entrancy is its business, not this view's. */
    private final PreviewLoupe loupe = new PreviewLoupe();

    /** Density, for the shared loupe — this view speaks dp everywhere else. */
    private float density() {
        return getResources().getDisplayMetrics().density;
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
        int n = bendLayout(h);
        if (n <= 0) return;
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
        // SPEC M §1 — the tether. The blue dot is only ever a DRAWING of a net point, pulled
        // inboard so it can never sit on the corner/edge glyph it shares a spot with; the
        // hairline says which point it actually drives. Straight out of the approved
        // prototype (TRANSFORM_UI_FEEL.html, "tie each inboard blue dot back to the net
        // point it actually drives").
        stroke.setColor(withAlpha(HandleModel.COLOR_BEND, 0x8C));
        stroke.setStrokeWidth(dp(0.9f));
        for (int i = 0; i < n; i++) {
            float tx = bendPts[i * 2], ty = bendPts[i * 2 + 1];
            float dx2 = bendDots[i * 2], dy2 = bendDots[i * 2 + 1];
            if (Math.hypot(tx - dx2, ty - dy2) > 2f) c.drawLine(dx2, dy2, tx, ty, stroke);
        }
        for (int i = 0; i < n; i++) {
            boolean grabbed = i == bendDragIndex;
            float r = dp(grabbed ? 9f : 7f);
            fill.setColor(GLYPH_FILL);
            c.drawCircle(bendDots[i * 2], bendDots[i * 2 + 1], r, fill);
            stroke.setColor(HandleModel.COLOR_BEND);
            stroke.setStrokeWidth(dp(grabbed ? 2.3f : 1.75f));
            c.drawCircle(bendDots[i * 2], bendDots[i * 2 + 1], r, stroke);
        }
    }

    /**
     * SPEC M §1 — where the net's TRUE points are, and where their grab dots are DRAWN.
     *
     * <p>JoyRaptor, on the build that shipped the net: <i>"covers the handles so once applied you
     * cannot even access the corners to turn it off or scale or anything."</i> He was right,
     * and it was structural: a 3×3 lattice puts eight of its nine points exactly on the four
     * corner glyphs and the four edge glyphs, and the net was hit-tested FIRST with an 18dp
     * radius, so a direct corner tap could never reach the corner handle — and the long-press
     * ring that turns Bend off lives on a handle. Locked in.</p>
     *
     * <p>The fix is the prototype's, which had it right all along: every outer dot is pushed
     * <b>inboard</b> of the handle it shares a spot with by
     * {@code clamp(shortestEdge * 0.20, 8dp, 21dp)} (never more than 42% of the way to the
     * net's centre, so a small object cannot collapse its own net), drawn with a hairline
     * tether back to the point it drives. The outer ink is always structure; the inner blue
     * dot is always bend. The centre point has no handle under it and does not move.</p>
     *
     * <p>Nothing here touches the bend VALUE — that is the (u,v) offset the host owns, and a
     * drag re-adds the dot's own offset before writing (see the grab offsets in onDown), so
     * the picture bends about the true point and not about the drawing of it.</p>
     *
     * @return the handle count, or -1 when the net cannot be laid out this frame
     */
    private int bendLayout(@NonNull Host h) {
        if (!haveQuad) return -1;
        float[] hh = TransformQuad.unitToQuad(quad);
        if (hh == null) return -1;
        int n = h.bendHandleCount();
        if (n <= 0 || n * 2 > bendPts.length) return -1;
        for (int i = 0; i < n; i++) {
            if (!h.bendHandlePosition(i, hh, bendScratch)) return -1;
            bendPts[i * 2] = bendScratch[0];
            bendPts[i * 2 + 1] = bendScratch[1];
        }
        int side = h.bendGridSide();
        int centre = (side >= 2 && side * side == n && (side & 1) == 1) ? n / 2 : -1;
        float refX, refY;
        if (centre >= 0) {
            refX = bendPts[centre * 2];
            refY = bendPts[centre * 2 + 1];
        } else {
            TransformQuad.centroid(quad, bendScratch);
            refX = bendScratch[0];
            refY = bendScratch[1];
        }
        float inboard = bendInboardPx();
        // SPEC S — the side is only allowed to change when NOTHING is under the finger. A dot
        // that swaps sides mid-drag pulls the picture out from under the user, so every side
        // decision is frozen for the whole gesture and re-read on the first layout after the
        // finger lifts (onUp clears bendDragIndex, then invalidate() lands us back here).
        boolean settled = bendDragIndex < 0 && dragKind == null && !pinching;
        for (int i = 0; i < n; i++) {
            float tx = bendPts[i * 2], ty = bendPts[i * 2 + 1];
            float dx = refX - tx, dy = refY - ty;
            float len = (float) Math.hypot(dx, dy);
            if (i == centre || len < 1f || !isFinite(len)) {
                bendOuter[i] = false;
                bendDots[i * 2] = tx;
                bendDots[i * 2 + 1] = ty;
                continue;
            }
            float push = Math.min(inboard, len * 0.42f);
            // How much daylight an INNER dot would have left between itself and the reference.
            // That gap — not the raw point distance — is what the eye reads as crowding, and it
            // is what the hysteresis band is expressed in.
            if (settled) {
                float innerGap = len - push;
                if (bendOuter[i]) {
                    if (innerGap > dp(BEND_OUTER_EXIT_DP)) bendOuter[i] = false;
                } else {
                    if (innerGap < dp(BEND_OUTER_ENTER_DP)) bendOuter[i] = true;
                }
            }
            float sign = bendOuter[i] ? -1f : 1f;
            bendDots[i * 2] = tx + sign * dx / len * push;
            bendDots[i * 2 + 1] = ty + sign * dy / len * push;
        }
        return n;
    }

    /** How far inboard of the real handles the blue net dots sit. Prototype's own formula. */
    private float bendInboardPx() {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2;
            m = Math.min(m, (float) Math.hypot(quad[a] - quad[b], quad[a + 1] - quad[b + 1]));
        }
        if (!isFinite(m) || m <= 0f) return dp(8f);
        return Math.max(dp(8f), Math.min(dp(21f), m * 0.20f));
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
    /** Where each net point's grab dot is DRAWN — pushed inboard of the structural handle. */
    private final float[] bendDots = new float[50];
    /**
     * SPEC S — which side of its own net point each dot is drawn on: false = inboard (the
     * SPEC M default, which keeps the dot off the corner/edge glyph), true = OUTBOARD.
     *
     * <p>JoyRaptor: <i>"when bend handles get brought in too much, how they are to slip from an
     * inner side buffer to an outer side buffer past a threshold after finger lifts."</i>
     * Dragging net points toward the middle pulls their inboard dots further in still, so the
     * group collapses into an illegible clump. Past the threshold the dot moves to the far end
     * of its own tether instead — same point, same tether, other side — and the reading stays
     * unambiguous. Pushing OUTWARD can never recreate the SPEC M trap: the structural glyph
     * sits ON the net point, so an outer dot is at least {@code bendInboardPx()} (≥8dp) clear
     * of it, and the handle still wins a contested tap by the 8dp tie-break in onDown.</p>
     */
    private final boolean[] bendOuter = new boolean[25];
    /**
     * SPEC S hysteresis band, measured in dp of daylight between the DRAWN dot and the net's
     * reference point.
     *
     * <p>The eight non-centre dots of a 3x3 net sit on a RING around that reference, so their
     * spacing from each other follows from the ring's radius: eight dots on a radius-g ring are
     * {@code 2*g*sin(pi/8) = 0.765*g} apart. A dot is 14dp across, and it needs about its own
     * width of clear air to read as a separate, grabbable thing — call it 22dp centre to
     * centre, which needs {@code g ≈ 29dp}. Hence <b>30dp: below that the group has stopped
     * being eight dots and become one blob</b>, and every dot moves to the far side of its own
     * net point, where the ring opens out again.</p>
     *
     * <p>It comes back inboard only at <b>44dp</b> — half as much room again. That 14dp band is
     * a whole dot wider than any nudge, so a dot parked on the boundary cannot chatter; and
     * because the side is only ever re-read between gestures, the worst case is one settled
     * flip per finger lift.</p>
     */
    private static final float BEND_OUTER_ENTER_DP = 30f;
    private static final float BEND_OUTER_EXIT_DP = 44f;
    private final float[] bendScratch = new float[2];
    /** True net point minus the finger at grab, so an inboard dot bends about its own point. */
    private float bendGrabDx, bendGrabDy;

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
        // JoyRaptor, 2026-09-10: "the main circle that is the pop-up menu for long holds on
        // transform handles is semi-transparent but needs to be more transparent/less opaque."
        // 0xF6 was 96% -- semi-transparent in name only; the picture underneath was invisible
        // and the ring read as a modal dialog rather than something floating over the work.
        // 0xCC is 80%: the glyphs still carry their contrast against it, and you can now see
        // what you are about to change.
        fill.setColor(Studio.alpha(Studio.SUNK, 0xCC));
        c.drawCircle(ringCx, ringCy, r, fill);
        stroke.setColor(Studio.FILM_EDGE);
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
            int col = inert ? Studio.INK_OFF
                    : (bend ? HandleModel.COLOR_BEND : HandleModel.colorOf(role));
            int back = on ? (role == HandleModel.Role.SCALE ? Studio.LINE
                    : role == HandleModel.Role.TILT ? Studio.LINE : Studio.LANE_B) : Studio.PANEL;
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
                : new String[]{"⤴︎", (ringIndex == TransformQuad.TOP
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
            fill.setColor(Studio.PANEL);
            c.drawCircle(scratch2[0], scratch2[1], ds / 2f, fill);
            stroke.setColor(Studio.OFF);
            stroke.setStrokeWidth(dp(1f));
            c.drawCircle(scratch2[0], scratch2[1], ds / 2f, stroke);
            text.setColor(inert ? Studio.INK_OFF : Studio.INK_FAINT);
            text.setTextSize(dp(14f));
            text.setFakeBoldText(false);
            c.drawText(glyphs[i], scratch2[0], scratch2[1] + dp(1f), text);
            text.setTextSize(dp(7.5f));
            text.setFakeBoldText(true);
            c.drawText(subs[i], scratch2[0], scratch2[1] + dp(12f), text);
        }

        text.setColor(Studio.INK_OFF);
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
        if (insideRing) {
            // SPEC K — a miss inside the dial is not a dismissal, but it is not silence
            // either: an unanswered tap reads as "folding stopped working". Say which
            // thing to tap, briefly, on the chip.
            setHud("Tap a symbol", x, y);
            hudEndedAtMs = SystemClock.uptimeMillis();
            invalidate();
            return true;
        }
        closeRing();
        return true;                      // ...but a touch outside it is, and it ends there
    }

    /**
     * Mirror, but only if the object can. See {@link Host#supportsFlip()} — on a type that cannot,
     * this used to open and commit a gesture that changed nothing, which spent an undo press on a
     * no-op.
     */
    private void doFlip(@Nullable Host h, boolean horizontal) {
        if (h == null || !h.supportsFlip()) return;
        h.beginGesture();
        h.flip(horizontal);
        h.commitGesture("Flip");
    }

    private void diagAction(int slot) {
        Host h = host;
        TransformDiag.log("ring " + (ringIsCorner ? "corner" : "edge") + ringIndex + " slot=" + slot);
        switch (slot) {
            case 0:
                if (ringIsCorner) {
                    doFlip(h, true);
                } else if (!affineOnly) {
                    foldOverEdge(ringIndex);
                }
                break;
            case 1:
                if (ringIsCorner) {
                    doFlip(h, false);
                } else {
                    // Mirroring ACROSS a top/bottom edge is a horizontal mirror of the picture;
                    // across a left/right edge it is a vertical one. Same axis the fold uses.
                    doFlip(h, ringIndex == TransformQuad.TOP
                            || ringIndex == TransformQuad.BOTTOM);
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
        if (h.writeQuad(quad)) {
            h.commitGesture("Fold");
            TransformDiag.log("fold edge=" + e + " accepted");
        } else {
            // SPEC K — a refused fold used to die silently and read as "folding stopped
            // working". The pin budget (±2 extents) is real: a fold of an already hard
            // distorted quad can ask for more than the tracks can carry, and clamping some
            // corners and not others would shear the picture instead of folding it. Say so
            // on the gesture chip (mid-drag refusals stay silent by design — the drag
            // simply stops moving — but a discrete tap deserves an answer).
            System.arraycopy(quadLastGood, 0, quad, 0, 8);
            TransformDiag.log("fold edge=" + e + " refused-range");
            TransformQuad.edgeMid(quad, e, scratch2);
            setHud("Pin limit", scratch2[0], scratch2[1]);
            // No finger is down (this came from the ring), so nothing will fade the chip:
            // start its fade now, and repaint to show it.
            hudEndedAtMs = SystemClock.uptimeMillis();
        }
        syncFromHost();
        invalidate();
    }

    // ── Touch ────────────────────────────────────────────────────────────

    private final Runnable longPress = () -> {
        if (dragKind == null || moved || pinching) return;
        if (dragKind != HandleModel.Kind.CORNER && dragKind != HandleModel.Kind.EDGE) return;
        HandleModel.Handle h = findHandle(dragKind, dragIndex);
        cancelGesture();
        if (h != null) openRing(h.kind == HandleModel.Kind.CORNER, h.index, h.x, h.y);
    };

    // ── Two fingers from anywhere: the HOLD ─────────────────────────────
    //
    // JoyRaptor, 2026-09-24, on the old helper: "so long as an object was selected and I had two
    // fingers touching the screen, I was moving/panning, scaling, or rotating that object — even
    // if my fingers weren't inside the bounds of the box ... especially handy for very small
    // objects where you can't physically fit your fingers ... One finger tapping on a different
    // object selects that new object, but two fingers cancel that."
    //
    // A second finger was only ever seen when the FIRST landed on the box: onDown returned false
    // for anything else, and a view that declines ACTION_DOWN never sees that stream again, so
    // its ACTION_POINTER_DOWN went to whatever was underneath. The old PreviewHandlesOverlay
    // solved the same problem with awaitingPinch — keep the stream on the chance a second finger
    // follows — and this is that trick, with one difference: it decides QUICKLY and then gives
    // the stream away intact, so one-finger behaviour is what it was.
    //
    //   * An off-box first finger is HELD (the DOWN is kept, nothing moves) for PINCH_HOLD_MS.
    //   * A second finger inside that window: a pinch on the SELECTED object, through the same
    //     startPinch/applyPinch/commit a pinch that starts on the box uses (one undo step, keys
    //     like any handle drag). The DOWN is never delivered, so nothing else gets selected.
    //     In Bend mode it is the same pinch: the whole object, never a dot.
    //   * Anything else — the finger lifts, drags past slop, or simply stays down past the
    //     window — RELEASES the hold: the kept DOWN and every event after it are dispatched to
    //     the views beneath, in the order the parent would have offered them, so a tap selects,
    //     a drag drags, a caption is still hit, and an empty-canvas tap still reaches the
    //     container's own click. The only difference a single finger can feel is a still finger
    //     arriving up to PINCH_HOLD_MS late — a tap or a drag is handed over at once.

    /**
     * How long an off-box first finger waits for a second. A touch longer than the platform
     * tap timeout (100ms), because two fingers put down "together" routinely land 100-150ms apart.
     */
    private static final long PINCH_HOLD_MS = 150L;

    /** An off-box first finger is being held for a possible second one. */
    private boolean holding;
    /** The held ACTION_DOWN, view-local; replayed to the views beneath on release. */
    @Nullable private MotionEvent heldDown;
    private int heldPointerId = -1;
    private float heldX, heldY;
    /** The hold was released: every event of this stream goes to {@link #forwardTarget}. */
    private boolean forwarding;
    /** The sibling that took the replayed DOWN; null with {@link #forwardToParent} or nobody. */
    @Nullable private View forwardTarget;
    /** No sibling wanted it: the container's own onTouchEvent (its click) gets the stream. */
    private boolean forwardToParent;
    /**
     * True while we are dispatching into a view beneath. A sibling can hand the stream straight
     * back (PreviewHandlesOverlay.handoffGesture, after it selects an object this surface then
     * owns); those calls must take the ordinary path, never the hold or the forward again.
     */
    private boolean inForward;

    private final Runnable holdTimeout = () -> {
        if (holding) releaseHold();
    };

    private boolean beginHold(@NonNull MotionEvent e) {
        clearHold();
        holding = true;
        heldDown = MotionEvent.obtain(e);
        heldPointerId = e.getPointerId(0);
        heldX = e.getX();
        heldY = e.getY();
        postDelayed(holdTimeout, PINCH_HOLD_MS);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private void clearHold() {
        removeCallbacks(holdTimeout);
        holding = false;
        if (heldDown != null) {
            heldDown.recycle();
            heldDown = null;
        }
        heldPointerId = -1;
    }

    /** One event of a held stream. */
    private boolean onHeldEvent(@NonNull MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN: {
                // The second finger arrived in time: this is a pinch on the SELECTED object, and
                // the held DOWN — which would have selected whatever was under it — is dropped.
                clearHold();
                Host h = host;
                if (h == null || ringOpen) return true;
                syncFromHost();
                if (!haveQuad) return true;
                startPinch(h, e);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = e.findPointerIndex(heldPointerId);
                if (idx >= 0 && Math.hypot(e.getX(idx) - heldX, e.getY(idx) - heldY)
                        > dp(MOVE_SLOP_DP)) {
                    // A one-finger drag: hand it over now, not after the window.
                    releaseHold();
                    return forwardEvent(e);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                // A tap: replay it beneath, DOWN then this UP.
                releaseHold();
                return forwardEvent(e);
            case MotionEvent.ACTION_CANCEL:
                clearHold();
                return true;
            default:
                return true;
        }
    }

    /** Give the held stream away: replay the kept DOWN to whatever beneath would have taken it. */
    private void releaseHold() {
        MotionEvent down = heldDown;
        heldDown = null;
        clearHold();
        forwarding = true;
        forwardTarget = null;
        forwardToParent = false;
        if (down == null) return;
        try {
            ViewGroup p = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
            if (p == null) return;
            for (View c : siblingsBeneath(p)) {
                MotionEvent ce = toSibling(down, c);
                boolean inside = ce.getX() >= 0f && ce.getY() >= 0f
                        && ce.getX() < c.getWidth() && ce.getY() < c.getHeight();
                boolean took = false;
                if (inside) {
                    inForward = true;
                    try {
                        took = c.dispatchTouchEvent(ce);
                    } finally {
                        inForward = false;
                    }
                }
                ce.recycle();
                if (took) {
                    forwardTarget = c;
                    return;
                }
            }
            // Nobody beneath wanted it — which, un-held, would have fallen to the container.
            MotionEvent pe = toParent(down, p);
            inForward = true;
            try {
                forwardToParent = p.onTouchEvent(pe);
            } finally {
                inForward = false;
                pe.recycle();
            }
        } finally {
            down.recycle();
        }
    }

    /** One event of a released stream, delivered where the replayed DOWN landed. */
    private boolean forwardEvent(@NonNull MotionEvent e) {
        int a = e.getActionMasked();
        try {
            ViewGroup p = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
            View t = forwardTarget;
            if (p != null && t != null && t.getParent() == p) {
                MotionEvent ce = toSibling(e, t);
                inForward = true;
                try {
                    t.dispatchTouchEvent(ce);
                } finally {
                    inForward = false;
                    ce.recycle();
                }
            } else if (p != null && forwardToParent) {
                MotionEvent pe = toParent(e, p);
                inForward = true;
                try {
                    p.onTouchEvent(pe);
                } finally {
                    inForward = false;
                    pe.recycle();
                }
            }
        } finally {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                forwarding = false;
                forwardTarget = null;
                forwardToParent = false;
            }
        }
        return true;
    }

    /**
     * The parent's children BENEATH this view, in the order the parent offers a DOWN: highest
     * Z first, later child first on a tie (ViewGroup.buildTouchDispatchChildList). Views above
     * this one already declined the DOWN before it reached us.
     */
    @NonNull
    private java.util.List<View> siblingsBeneath(@NonNull ViewGroup p) {
        final int me = p.indexOfChild(this);
        final float myZ = getZ();
        java.util.List<View> out = new java.util.ArrayList<>();
        final java.util.Map<View, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < p.getChildCount(); i++) {
            View c = p.getChildAt(i);
            if (c == this || c == null) continue;
            if (c.getVisibility() != VISIBLE && c.getAnimation() == null) continue;
            float z = c.getZ();
            if (z < myZ || (z == myZ && i < me)) {
                out.add(c);
                index.put(c, i);
            }
        }
        java.util.Collections.sort(out, (x, y) -> {
            int byZ = Float.compare(y.getZ(), x.getZ());
            return byZ != 0 ? byZ : Integer.compare(index.get(y), index.get(x));
        });
        return out;
    }

    /** {@code e} (this view's coordinates) in sibling {@code c}'s own coordinates. */
    @NonNull
    private MotionEvent toSibling(@NonNull MotionEvent e, @NonNull View c) {
        MotionEvent ce = MotionEvent.obtain(e);
        if (!getMatrix().isIdentity()) ce.transform(getMatrix());
        ce.offsetLocation(getLeft() - c.getLeft(), getTop() - c.getTop());
        if (!c.getMatrix().isIdentity()) {
            android.graphics.Matrix inv = new android.graphics.Matrix();
            if (c.getMatrix().invert(inv)) ce.transform(inv);
        }
        return ce;
    }

    /** {@code e} (this view's coordinates) in the parent's own coordinates. */
    @NonNull
    private MotionEvent toParent(@NonNull MotionEvent e, @NonNull ViewGroup p) {
        MotionEvent pe = MotionEvent.obtain(e);
        if (!getMatrix().isIdentity()) pe.transform(getMatrix());
        pe.offsetLocation(getLeft() - p.getScrollX(), getTop() - p.getScrollY());
        return pe;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent e) {
        // A stream this surface is holding or has handed on is routed BEFORE the host check: the
        // selection can change under it (a replayed tap selects another object), and the rest
        // of the stream still has to arrive where its DOWN went. Calls that come back while we
        // are forwarding take the ordinary path below.
        if (!inForward) {
            if (forwarding) return forwardEvent(e);
            if (holding) return onHeldEvent(e);
        }
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
        // SPEC M §1.3 — the escape hatch, tested before anything the net can cover.
        if (hitsBendPill(x, y)) {
            Host bh = host;
            if (bh != null && bh.supportsBend()) {
                bendMode = !bendMode;
                cancelBendDrag();
            }
            invalidate();
            return true;
        }
        // SPEC M §3 — the ghost. AFTER the pills, because it is drawn under them and can be
        // large enough to sit beneath both; a tap that looks like it landed on "Done" must be
        // "Done". One tap, one undoable translate, object home.
        if (hitsReframeGhost(x, y)) {
            doReframe();
            return true;
        }
        if (!haveQuad) syncFromHost();
        if (!haveQuad) return false;
        rebuildHandles();

        HandleModel.Handle hit = HandleModel.hitTest(handleBuf, handleCount, x, y, grabPx());

        // SPEC M §1 — STRUCTURAL HANDLES OUTRANK THE NET. This block used to run FIRST, with
        // an 18dp radius, on dots that sat exactly on the corner and edge glyphs: with Bend
        // on, a corner tap could only ever grab a bend dot, so scale, flip and the long-press
        // ring — the only way to turn Bend off — all became unreachable. Now the handle is
        // tested first and keeps the touch unless a bend dot is CLEARLY the nearer target
        // (8dp of daylight), which the inboard offset in bendLayout() guarantees for a
        // deliberate tap on the blue dot. A grabbed dot starts the ONE snapshot its whole
        // drag will undo to (host ensures the spec first, so the first bend's undo restores
        // "no bend at all").
        if (bendMode && h.supportsBend()) {
            int bn = bendLayout(h);
            if (bn > 0) {
                // 20dp and the 8dp margin, restored 2026-09-24. f4b6b7e4 widened them to 24/2
                // for "having a difficult time moving the warp handles", and JoyRaptor ruled the
                // size was never the problem: "it used to work well". The reach was not what
                // regressed — see MeshBendSeam.dragTo for what stopped the dots following.
                float br = dp(20f);
                int best = -1;
                float bestD = Float.MAX_VALUE;
                for (int i = 0; i < bn; i++) {
                    float d = (float) Math.hypot(bendDots[i * 2] - x, bendDots[i * 2 + 1] - y);
                    if (d <= br && d < bestD) { bestD = d; best = i; }
                }
                float handleD = hit == null ? Float.MAX_VALUE
                        : (float) Math.hypot(hit.x - x, hit.y - y);
                if (best >= 0 && bestD + dp(8f) < handleD) {
                    bendDragIndex = best;
                    bendMoved = false;
                    bendDownX = x;
                    bendDownY = y;
                    // The dot is drawn inboard of the point it drives: carry that gap through
                    // the drag, or the first move would snap the bend by the offset.
                    bendGrabDx = bendPts[best * 2] - x;
                    bendGrabDy = bendPts[best * 2 + 1] - y;
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

        if (hit == null && !TransformQuad.contains(quad, x, y)) {
            // Nothing of ours — but maybe the first finger of a pinch on the selected object.
            // HOLD it briefly (see beginHold); if no second finger follows, the stream goes to
            // the views beneath exactly as a pass-through would, so the empty-canvas rule and
            // tap-to-select-another survive. A DOWN handed back to us mid-forward is never held.
            //
            // PREVIEW ONLY (JoyRaptor, 2026-09-24: "anywhere on the preview area only"). This
            // view is a child of player_container, so the timeline — its own two-finger zoom and
            // scroll — never reaches here at all. The strip an open top drawer covers
            // (chromeTopInset) is excluded too: a finger there belongs to the drawer.
            if (!inForward && y >= chromeTopInset) return beginHold(e);
            return false;
        }
        dragPointerId = e.getPointerId(0);
        downX = x;
        downY = y;
        moved = false;
        cornerSnapBroken = false;
        System.arraycopy(quad, 0, quadAtGrab, 0, 8);
        System.arraycopy(quad, 0, quadLastGood, 0, 8);
        // Freeze the canvas rect with the gesture: if it moves underneath us, refresh()
        // rebases this frozen state instead of baking the gap into the project on release.
        haveRectAtGrab = false;
        try {
            RectF vr = h.videoRect();
            if (vr != null && vr.width() > 0.5f && vr.height() > 0.5f) {
                rectAtGrab.set(vr);
                haveRectAtGrab = true;
            }
        } catch (RuntimeException caught) {
            haveRectAtGrab = false;
        }

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
                rotGrabX = hit.x;
                rotGrabY = hit.y;
                rotStartAngleRad = (float) Math.atan2(hit.y - rotPivotY, hit.x - rotPivotX);
                rotDetentBroken = false;
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
            // A drag is not half of a double-tap (TEXT_REPAIR_PASS, 2026-09-23).
            lastBodyTapUpMs = 0L;
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
            // A completed drag breaks any pending tap pairing (TEXT_REPAIR_PASS, 2026-09-23).
            lastBodyTapUpMs = 0L;
            h.commitGesture(kind == HandleModel.Kind.BODY ? "Move"
                    : kind == HandleModel.Kind.ROTATE ? "Rotate"
                    : "Distort");
        } else if (was && !didMove && clean && kind == HandleModel.Kind.BODY) {
            // A tap on the picture that moved nothing. Pair it with the previous one and forward
            // the double-tap; a single tap deliberately does nothing at all. downX/downY is
            // the DOWN point, but an unmoved tap lifted within slop of it, so it stands in
            // for the UP point without threading the MotionEvent through (TEXT_REPAIR_PASS).
            long nowMs = SystemClock.uptimeMillis();
            int slopPx = android.view.ViewConfiguration.get(getContext())
                    .getScaledTouchSlop();
            boolean nearLastTap = nowMs - lastBodyTapUpMs <= DOUBLE_TAP_MS
                    && Math.abs(downX - lastBodyTapX) <= slopPx
                    && Math.abs(downY - lastBodyTapY) <= slopPx;
            if (nearLastTap) {
                lastBodyTapUpMs = 0L;
                Runnable dt = onDoubleTap;
                if (dt != null) dt.run();
            } else {
                lastBodyTapUpMs = nowMs;
                lastBodyTapX = downX;
                lastBodyTapY = downY;
            }
        }
        hudEndedAtMs = SystemClock.uptimeMillis();
        syncFromHost();
        invalidate();
        return was || ringOpen;
    }

    // ── Canvas snap (SnapSettings "Canvas centre") ───────────────────────────────────────
    // The old handles caught the canvas centre; this surface never did, so a picture could not
    // be centred by hand (JoyRaptor, 2026-09-24: snapping "is very important for moving fast").
    // Centre-to-centre and edge-to-edge, per axis, within 3% of the frame at Normal.

    private static final float CANVAS_SNAP_FRAC = 0.03f;

    /**
     * Where the OTHER objects are (their drawn boxes, this view's px) for "Other objects" snap:
     * the dragged object's centre and edges catch theirs, per axis, with the same guide line.
     */
    public interface SnapTargets { void collect(@NonNull java.util.List<RectF> out); }

    @Nullable private SnapTargets snapTargets;
    private final java.util.List<RectF> snapTargetScratch = new java.util.ArrayList<>();

    public void setSnapTargets(@Nullable SnapTargets targets) { snapTargets = targets; }
    private final float[] snapDxy = new float[2];
    /** Where a snapped axis is drawn (view px), NaN when that axis is free. */
    private float snapGuideX = Float.NaN, snapGuideY = Float.NaN;
    private final Paint snapGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Pull a body drag of {@code dxy} (from the grab) onto the canvas centre or edges. */
    private void snapTranslate(@NonNull float[] dxy) {
        snapGuideX = Float.NaN;
        snapGuideY = Float.NaN;
        Host h = host;
        if (h == null) return;
        float reach = com.fadcam.ui.faditor.tools.SnapSettings.reach(
                getContext(), com.fadcam.ui.faditor.tools.SnapSettings.Kind.CANVAS);
        if (reach <= 0f) return;
        RectF vr = h.videoRect();
        if (vr == null || vr.width() <= 0f || vr.height() <= 0f) return;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i += 2) {
            minX = Math.min(minX, quadAtGrab[i]);     maxX = Math.max(maxX, quadAtGrab[i]);
            minY = Math.min(minY, quadAtGrab[i + 1]); maxY = Math.max(maxY, quadAtGrab[i + 1]);
        }
        float thr = CANVAS_SNAP_FRAC * reach * Math.min(vr.width(), vr.height());
        float[] ax = snapAxis(minX + dxy[0], maxX + dxy[0], vr.left, vr.right, thr);
        float[] ay = snapAxis(minY + dxy[1], maxY + dxy[1], vr.top, vr.bottom, thr);
        // Other objects: only on an axis the canvas did not already catch — the frame wins a tie.
        float oreach = com.fadcam.ui.faditor.tools.SnapSettings.reach(
                getContext(), com.fadcam.ui.faditor.tools.SnapSettings.Kind.OBJECTS);
        if (oreach > 0f && snapTargets != null && (ax == null || ay == null)) {
            snapTargetScratch.clear();
            snapTargets.collect(snapTargetScratch);
            float othr = CANVAS_SNAP_FRAC * oreach * Math.min(vr.width(), vr.height());
            for (RectF o : snapTargetScratch) {
                if (ax == null) ax = snapAxisTo(minX + dxy[0], maxX + dxy[0], o.left, o.right, othr);
                if (ay == null) ay = snapAxisTo(minY + dxy[1], maxY + dxy[1], o.top, o.bottom, othr);
                if (ax != null && ay != null) break;
            }
        }
        if (ax != null) { dxy[0] += ax[0]; snapGuideX = ax[1]; }
        if (ay != null) { dxy[1] += ay[0]; snapGuideY = ay[1]; }
    }

    /** Like {@link #snapAxis}, plus edge-to-opposite-edge, so objects can sit flush side by side. */
    @Nullable
    private static float[] snapAxisTo(float lo, float hi, float oLo, float oHi, float thr) {
        float[] best = snapAxis(lo, hi, oLo, oHi, thr);
        float bestD = best == null ? thr : Math.abs(best[0]);
        float[][] flush = {{lo, oHi}, {hi, oLo}};
        for (float[] p : flush) {
            float d = Math.abs(p[1] - p[0]);
            if (d <= bestD) { bestD = d; best = new float[]{p[1] - p[0], p[1]}; }
        }
        return best;
    }

    /** {shift, guide} for the closest of centre/near-edge/far-edge within thr, or null. */
    @Nullable
    private static float[] snapAxis(float lo, float hi, float frameLo, float frameHi, float thr) {
        float[][] pairs = {
                {(lo + hi) / 2f, (frameLo + frameHi) / 2f},
                {lo, frameLo},
                {hi, frameHi},
        };
        float[] best = null;
        float bestD = thr;
        for (float[] p : pairs) {
            float d = Math.abs(p[1] - p[0]);
            if (d <= bestD) { bestD = d; best = new float[]{p[1] - p[0], p[1]}; }
        }
        return best;
    }

    private void drawSnapGuides(@NonNull Canvas c) {
        if (Float.isNaN(snapGuideX) && Float.isNaN(snapGuideY)) return;
        Host h = host;
        RectF vr = h == null ? null : h.videoRect();
        if (vr == null) return;
        snapGuidePaint.setStyle(Paint.Style.STROKE);
        snapGuidePaint.setStrokeWidth(dp(1f));
        snapGuidePaint.setColor(HandleModel.COLOR_SELECTION);
        if (!Float.isNaN(snapGuideX)) c.drawLine(snapGuideX, vr.top, snapGuideX, vr.bottom, snapGuidePaint);
        if (!Float.isNaN(snapGuideY)) c.drawLine(vr.left, snapGuideY, vr.right, snapGuideY, snapGuidePaint);
    }

    private void cancelGesture() {
        snapGuideX = Float.NaN;
        snapGuideY = Float.NaN;
        removeCallbacks(longPress);
        dragKind = null;
        dragPointerId = -1;
        moved = false;
        cornerSnapBroken = false;
        // The outline fades back to purple on release (snapTintNow animates it).
        setSnapTint(HandleModel.COLOR_SELECTION);
        loupeShowing = false;
        haveRectAtGrab = false;
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
                snapDxy[0] = dx; snapDxy[1] = dy;
                snapTranslate(snapDxy);
                dx = snapDxy[0]; dy = snapDxy[1];
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
                // 2026-09-13 detent. The SAME snapped angle drives the live quad and the
                // written pose — if the preview rotated by the raw delta and the model stored
                // the snapped one, the picture and the handles would disagree by up to the
                // detent width, which is the preview/export parity bug in miniature.
                float rawAbs = rotStartDeg + deltaDeg;
                // Update the hysteresis flag FIRST, then snap through it, so one frame can
                // never both escape and re-stick.
                float offDeg = Math.abs(rawAbs - Math.round(rawAbs / 90f) * 90f);
                if (rotDetentBroken) {
                    if (offDeg <= ROT_DETENT_EXIT_DEG) rotDetentBroken = false;
                } else if (offDeg > ROT_DETENT_ENTER_DEG) {
                    rotDetentBroken = true;
                }
                float snapAbs = snapRotation(TransformQuad.detentCardinalDeg(
                        rawAbs, ROT_DETENT_ENTER_DEG, ROT_DETENT_EXIT_DEG, rotDetentBroken));
                float snapDelta = snapAbs - rotStartDeg;
                h.readFoldPivot(scratch2);
                TransformQuad.rotateAbout(quad, quadAtGrab, scratch2[0], scratch2[1],
                        (float) Math.toRadians(snapDelta));
                float abs = snapAbs;
                deltaDeg = snapDelta;
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
                    // Uniform snap: near-diagonal drags scale proportionally by default
                    // (the modern convention — Photoshop, Affinity, Figma — and the only
                    // sane default on a phone, which has no Shift key); pushing clearly
                    // off-diagonal breaks out to free aspect until nearly diagonal again.
                    if (!TransformQuad.scaleCornerFactors(quadAtGrab, dragIndex, tx, ty,
                            scratchFactors)) return;
                    float tol = cornerSnapBroken ? SNAP_REJOIN_REL : SNAP_BREAK_REL;
                    float[] sf = scratch2;
                    boolean snapped = TransformQuad.snapUniformFactors(
                            scratchFactors[0], scratchFactors[1], tol, sf);
                    cornerSnapBroken = !snapped;
                    // JoyRaptor 2026-09-13: grow from the CENTRE, not the opposite corner.
                    TransformQuad.scaleCornerApplyAboutCentre(
                            quad, quadAtGrab, dragIndex, sf[0], sf[1]);
                    // Outline tint: purple at identity (no change, or back where it
                    // started), tilt-green while snapped uniform, free-red broken out.
                    if (Math.abs(sf[0] - 1f) < 0.005f && Math.abs(sf[1] - 1f) < 0.005f) {
                        setSnapTint(HandleModel.COLOR_SELECTION);
                    } else if (snapped) {
                        setSnapTint(HandleModel.COLOR_TILT);
                    } else {
                        setSnapTint(HandleModel.COLOR_FREE);
                    }
                    hud = pct(sf[0])
                            + (Math.abs(sf[0] - sf[1]) < 0.005f ? ""
                            : " × " + pct(sf[1]));
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
        // The dot is drawn inboard of the point it drives; bend about the POINT.
        if (h.bendDragTo(bendDragIndex, inv, x + bendGrabDx, y + bendGrabDy)) invalidate();
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
        // Freeze the canvas rect with the pinch, same as a one-finger down.
        haveRectAtGrab = false;
        try {
            RectF vr = h.videoRect();
            if (vr != null && vr.width() > 0.5f && vr.height() > 0.5f) {
                rectAtGrab.set(vr);
                haveRectAtGrab = true;
            }
        } catch (RuntimeException caught) {
            haveRectAtGrab = false;
        }
        h.readPivot(scratch2);
        pinchPivotX = scratch2[0];
        pinchPivotY = scratch2[1];
        pinchFactor = 1f;
        pinchDeg = 0f;
        pinchStartDeg = h.currentRotationDeg();
        pinchRotating = false;
        pinchDetentBroken = false;
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
        // ROTATION EASE (JoyRaptor, 2026-09-24: "rotation is a little bit hard to manage").
        // Two fingers never travel purely apart, so every pinch meant as a zoom carries a few
        // degrees of incidental twist, and applying it from the first frame made the picture
        // wobble while it scaled. The twist is ignored until it passes a small dead-zone —
        // the same 7° the old handles used — and the dead-zone is then SUBTRACTED, so turning
        // starts from zero instead of jumping 7° the moment it engages. Once unlocked it stays
        // unlocked for the rest of the gesture. Then the one-finger arc's cardinal detent
        // (ROT_DETENT_*, same hysteresis) and the global snap hook, so both ways of turning
        // an object stick to the same angles.
        float rawDeg = (float) Math.toDegrees(th);
        if (!pinchRotating && Math.abs(rawDeg) > PINCH_ROT_DEADZONE_DEG) pinchRotating = true;
        float deg = 0f;
        if (pinchRotating) {
            float rawAbs = pinchStartDeg + rawDeg - Math.signum(rawDeg) * PINCH_ROT_DEADZONE_DEG;
            float offDeg = Math.abs(rawAbs - Math.round(rawAbs / 90f) * 90f);
            if (pinchDetentBroken) {
                if (offDeg <= ROT_DETENT_EXIT_DEG) pinchDetentBroken = false;
            } else if (offDeg > ROT_DETENT_ENTER_DEG) {
                pinchDetentBroken = true;
            }
            float snapAbs = snapRotation(TransformQuad.detentCardinalDeg(
                    rawAbs, ROT_DETENT_ENTER_DEG, ROT_DETENT_EXIT_DEG, pinchDetentBroken));
            deg = snapAbs - pinchStartDeg;
        }
        th = Math.toRadians(deg);
        pinchFactor = f;
        pinchDeg = deg;
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
        haveRectAtGrab = false;
        cornerSnapBroken = false;
        setSnapTint(HandleModel.COLOR_SELECTION);
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
        clearHold();
        forwarding = false;
        forwardTarget = null;
        forwardToParent = false;
        super.onDetachedFromWindow();
    }
}
