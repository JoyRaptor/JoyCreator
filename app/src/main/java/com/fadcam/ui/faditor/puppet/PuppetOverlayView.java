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

import com.fadcam.ui.faditor.transform.HandleModel;
import com.fadcam.ui.faditor.transform.PreviewLoupe;

/**
 * THE PINS, ON THE PICTURE — SPEC_20260915_PUPPET_UI §1.
 *
 * <p>Draws a rig over one object and reads the gestures that author it: tap art to place a pin,
 * drag a pin to move it, drag between pins to bone them, and a puppet badge in the corner that
 * locks the lot.
 *
 * <h3>It is mutually exclusive with the other two handle surfaces</h3>
 * <p>{@code TransformOverlayView}'s doc records the bug this project already paid for: five
 * sibling views each ran a hit-test, the topmost claimed every touch, and objects underneath
 * became ungrabbable. This view does not reintroduce it — the activity shows exactly one of
 * {@code previewHandlesOverlay}, {@code transformOverlay} and this, so at any instant there is
 * still one thing in the preview reading a {@link MotionEvent}.
 *
 * <h3>Two stores, two gestures, one dot</h3>
 * <p>A pin has a REST POSITION — where it was placed, and what the triangles are built around —
 * and an OFFSET from it, which is what bends the picture. They are edited by different gestures
 * on purpose:
 * <ul>
 *   <li><b>Grab, drag a pin</b> → moves the OFFSET. The picture bends. Nothing re-triangulates,
 *       which is why an arm can be dragged smoothly instead of rebuilding the mesh per frame.</li>
 *   <li><b>A placement tool armed, drag an existing pin</b> → moves the REST position. That is
 *       re-rigging, so the mesh IS rebuilt, once, on release.</li>
 * </ul>
 * <p>The view still cannot key anything — recording a performance writes the pose TRACK, which is
 * a third store and a later stage. That is why it needs no playhead.
 *
 * <h3>Seeing the pins is the gate</h3>
 * <p>There is no hidden mode and no tab state to remember. If the pins are drawn they answer to
 * touch; if the badge is grey they do not. JoyRaptor asked for the control to live here rather
 * than in the drawer for a reason that decides it: the accident it prevents — knocking a pin on a
 * sprite that happens to have some — happens while the drawer is SHUT, which is exactly when a
 * drawer setting cannot help you.
 *
 * <p>Reusable by construction: everything it knows about the app is behind {@link Host}.
 */
public class PuppetOverlayView extends View {

    /** Everything this view needs from the editor, and the whole list of it. */
    public interface Host {
        /** The rig being drawn. Never null while this view is visible. */
        @NonNull PuppetRig rig();

        /**
         * The object's box in THIS view's pixels. False = nothing to draw, which is how the
         * view goes quiet during a layout churn rather than painting at a stale rect.
         */
        boolean readRect(@NonNull RectF out);

        int selectedPin();
        void setSelectedPin(int index);

        /** Which tool is armed in the drawer — decides what a touch on the art means. */
        @NonNull PuppetDrawerTool tool();

        /** Something changed: repaint the drawer rows, the preview and schedule a save. */
        void onRigChanged();

        /** ONE undo step for a whole gesture. */
        void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo);

        /**
         * True while the preview is promoted into its small floating shell. The badge hides
         * then — at that size a 38dp control would dominate the picture it sits on.
         */
        boolean previewIsSmall();

        // ── the POSE: what actually bends the picture ────────────────────────────

        /**
         * This pin's offset from its rest position, in unit space. False = no mesh yet, which is
         * every rig with fewer than two pins.
         */
        boolean readOffset(int pin, @NonNull float[] outXY);

        /** Move this pin's offset. Bends the picture; does NOT re-triangulate. */
        void writeOffset(int pin, float dx, float dy);

        /** A pose gesture is starting — take the ONE snapshot it will undo to. */
        void beginPose();

        /** The pose gesture ended and changed something: record ONE undo step. */
        void commitPose(@NonNull String label);

        /** The pins were ADDED or RE-PLACED — the mesh has to be rebuilt. */
        void onRigStructureChanged();

        /**
         * A pin was REMOVED, and which one.
         *
         * <p>Separate from {@link #onRigStructureChanged} because the index matters: after a
         * deletion every pin above it shifted down by one, and a pose carried across without
         * knowing that gives each surviving pin its neighbour's offset.
         */
        void onPinRemoved(int index);

        /**
         * Drag the whole CHAIN this pin belongs to, so the limb follows the finger.
         *
         * @return true when a chain was solved. False means this pin is on its own and the
         *         caller should just move it — which is the common case and not a failure.
         */
        boolean solveChainTo(int pin, float ux, float uy);

        /** True when this point is over the helper strip — the place pins go to be deleted. */
        boolean pointInHelper(float x, float y);

        /** A finger is at this point: let the strip get out of its way, unless it is the target. */
        void helperDodge(float x, float y, boolean isTarget);

        /**
         * The helper strip’s box in THIS view’s pixels; false when the strip is not up.
         *
         * <p>Only the magnifier asks, and only so that it can park somewhere else.
         */
        boolean readHelperRect(@NonNull RectF out);

        /** The gesture on the picture is over — the strip may start dodging again. */
        void helperGestureEnded();

        /**
         * PUT THE PINS AWAY and give the picture its transform box back.
         *
         * <p>JoyRaptor, 2026-09-16: <i>"when he has all the keys locked, there’s nothing I can
         * do with the object — I can’t move it or scale it, even though it’s selected. It
         * kind of behaves like it’s not selected."</i> Which it did: greying the pins took the
         * puppet out of play and gave nothing back, so a rigged picture became the one kind of
         * picture you could not move.
         *
         * <p>So the badge is a TOGGLE BETWEEN TWO TOOLS rather than an on/off for one. Pins away
         * means the ordinary transform box returns and the picture behaves like any other
         * selected object; the grey marionette appears on that surface as the way back.
         */
        void putPinsAway();

        /** Open the drawer on the Puppet tab, with the pins up. */
        void openPuppetDrawer();

        /** Say what just happened, in words, beside the strip. */
        void say(@Nullable String what);

        /** Which bone is selected, or -1. A bone is a thing you can select; see the drawer. */
        int selectedBone();
        void setSelectedBone(int index);

        /**
         * True while the timeline is running.
         *
         * <p>Placing pins during playback is refused: <i>"we definitely cannot have people adding
         * pins while it is playing — that is a moving target"</i> (JoyRaptor, 2026-09-15).
         * Adding a pin is AUTHORING; dragging one is PERFORMING, and only the second belongs in
         * a moving picture.
         */
        boolean isPlaying();

        /**
         * The deformed triangles, for the Character scope's Show · Mesh toggle. Null when the
         * item has no mesh yet.
         */
        @Nullable PuppetMeshWire meshWire();

        /** True while a slider that changes reach is being dragged — show the ring regardless. */
        boolean reachPreview();

        /**
         * The item’s rotation in degrees, clockwise.
         *
         * <p>Without it {@link #readRect} describes the axis-aligned BOUNDS of a rotated picture
         * rather than the picture, so pins land off the artwork and shuffle about as the angle
         * sweeps during a rotate gesture.
         */
        float itemRotationDeg();

        /** Remove this pin, as ONE undo step. */
        void deletePin(int index);

        /**
         * The bottom edge of the open drawer in this view's pixels, or 0.
         *
         * <p>The drawer comes down from the TOP over the preview, so its grip sits on top of the
         * picture. Touches above this line are not ours: swallowing one would stop the drawer
         * being resized, and a tool that jams another tool is worse than a tool that is missing.
         */
        float drawerBottomPx();
    }

    /**
     * The armed tool, mirrored out of the drawer.
     *
     * <p>Declared here rather than imported from {@code PuppetDrawerTabs} so this package keeps
     * pointing one way: the drawer may depend on the rig, the rig never depends on the drawer.
     */
    public enum PuppetDrawerTool { GRAB, PIN, STIFF, DANGLE, FREE, BONE }

    // ── sizes, in dp ─────────────────────────────────────────────────────

    /**
     * An UNSELECTED pin is half the selected one. JoyRaptor asked for exactly that, and it earns
     * its keep on a rigged character: ten dots at full size read as a crowd, and the one you are
     * working on has to win without being hunted for.
     */
    private static final float DOT_R = 4.5f;
    private static final float DOT_R_SEL = 9f;
    private static final float HIT_R = 22f;          // generous: a finger is not a mouse
    private static final float BADGE = 38f;
    private static final float BADGE_INSET = 9f;
    private static final float LABEL_SP = 9.5f;
    private static final float DRAG_SLOP = 6f;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] off = new float[2];
    private final RectF badgeRect = new RectF();
    private final RectF helperRect = new RectF();

    /** Reused line buffer for the wireframe — one drawLines call, no per-frame allocation. */
    @Nullable private float[] meshLines;

    /** The item’s rotation in degrees, read once per draw and once per gesture. */
    private float rotDeg;
    /** Scratch for {@link #toUnit} — a touch handler must not allocate. */
    private final float[] unit = new float[2];

    /** How near a finger has to be to a bone’s shaft to select it. */
    private static final float BONE_HIT_DP = 16f;

    /** Scratch for {@link #pinsUnder} — a finger cannot plausibly be over more than a few. */
    private final int[] under = new int[12];

    /** When the finger went down on the badge, so a hold can be told from a tap on release. */
    private long badgeDownAt;

    // The long press. Posted on DOWN over a pin, cancelled by movement or release.
    private static final long HOLD_MS = 420L;
    @Nullable private Runnable holdTask;

    private void cancelHold() {
        if (holdTask != null) { removeCallbacks(holdTask); holdTask = null; }
    }

    @Override
    protected void onDetachedFromWindow() {
        // A posted long-press outlives the view otherwise, and fires into a host that may have
        // moved on to a different item — which would select a pin on the wrong character.
        cancelHold();
        super.onDetachedFromWindow();
    }

    // The poof: where, what colour, and when it started.
    private static final long POOF_MS = 260L;
    private long poofAt;
    private float poofX, poofY;
    private int poofHue = 0xFFFFFFFF;
    private final RectF arc = new RectF();
    private final Path path = new Path();
    private final PreviewLoupe loupe = new PreviewLoupe();
    @Nullable private android.view.ViewGroup loupeRoot;

    @Nullable private Host host;

    // gesture state
    private int dragPin = -1;
    private int boneFrom = -1;
    private float downX, downY, boneX, boneY;
    private boolean moved;
    private boolean posing;
    /** True while the dragged pin is over the strip, so the pin can show it is about to go. */
    private boolean wasOverStrip;
    private float grabDX, grabDY;
    private float downRestX, downRestY;
    private final float[] downOff = new float[2];

    public PuppetOverlayView(@NonNull Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(LABEL_SP * d);
        text.setFakeBoldText(true);
        text.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        setWillNotDraw(false);
    }

    public void setHost(@Nullable Host h) { this.host = h; invalidate(); }

    /**
     * Point the magnifier at the real picture — the container that parents the video surface and
     * every overlay plane, exactly as the transform surface is wired.
     */
    public void setLoupeContentSource(@Nullable android.view.ViewGroup root) {
        loupeRoot = root;
        invalidate();
    }

    @Nullable public Host host() { return host; }

    /** Re-read and repaint. Cheap; call it from anywhere that changes the rig. */
    public void refresh() { invalidate(); }

    // ── the ghost a drag out of the helper leaves under the finger ───────
    @Nullable private PuppetPin.Type ghostType;
    private float ghostX, ghostY;

    /**
     * The pin at this point in THIS view’s pixels, or -1 for bare artwork.
     *
     * <p>Public because a drag out of the helper strip means two different things depending on
     * what it lands on, and the strip cannot hit-test pins — it is 58dp square and the pins are
     * out on the picture.
     */
    public int pinUnder(float x, float y) {
        if (host == null) return -1;
        PuppetRig rig = host.rig();
        if (!host.readRect(rect)) return -1;
        return pinAt(rig, x, y);
    }

    /**
     * Show (or with a null type, hide) the pin being dragged out of the helper strip.
     *
     * <p>Drawn HERE rather than by the strip because the strip is 58dp square and the finger is
     * out over the picture — and because putting a second full-screen view up to follow it would
     * be the several-views-one-hit-test bug this class already avoids.
     */
    public void setPlaceGhost(@Nullable PuppetPin.Type type, float x, float y) {
        ghostType = type;
        ghostX = x;
        ghostY = y;
        invalidate();
    }

    // ── drawing ──────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas c) {
        // RE-ENTRANCY. The loupe draws the preview stack and this view is one of its children;
        // without this the magnifier would draw itself, magnified, forever.
        if (loupe.isDrawingContent()) return;
        if (host == null) return;
        PuppetRig rig = host.rig();
        if (!host.readRect(rect) || rect.width() <= 1f || rect.height() <= 1f) return;
        rotDeg = host.itemRotationDeg();

        boolean locked = rig.locked;

        // THE TRIANGLES, first and faintest — they are the floor everything else stands on.
        if (rig.showMesh) drawMesh(c);

        // THE REACH RING. What the grey eye beside a slider has always named and never drawn.
        if ((rig.showReach || host.reachPreview()) && !locked) drawReach(c, rig);

        if (rig.showBones) drawBones(c, rig, locked);
        if (rig.showPins) drawPins(c, rig, locked);

        // THE POOF — a pin that was dragged into the strip, going. Drawn after the pins so it
        // is not occluded by the ones that remain.
        drawPoof(c);

        // The badge is the LAST thing drawn and the first thing hit-tested: it must stay
        // reachable even when a pin happens to sit under it.
        if (rig.pinCount() > 0 && !host.previewIsSmall()) drawBadge(c, locked);

        // The pin being dragged out of the helper, under the finger, at full size so its SHAPE
        // is readable before it lands — which is the whole point of showing it at all.
        if (ghostType != null) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x33000000);
            c.drawCircle(ghostX, ghostY, 17f * d, fill);
            PuppetShapes.draw(c, ghostType, ghostX, ghostY, DOT_R_SEL * d,
                    PuppetPalette.of(ghostType), fill, stroke, d);
        }

        // THE MAGNIFIER, last, over everything. Only while a pin is actually being moved: a
        // finger is parked on top of the very thing it is dragging, and on a puppet that thing
        // is in the middle of the artwork rather than out on a corner.
        if (dragPin >= 0 && moved && !locked) {
            PuppetPin dp = rig.pin(dragPin);
            // Hand it the strip box so the two floating things stop choosing the same corner.
            RectF avoid = host.readHelperRect(helperRect) ? helperRect : null;
            loupe.draw(c, this, loupeRoot, posedX(dragPin, dp), posedY(dragPin, dp), d,
                    fill, stroke, (lc, invZoom) -> drawLoupeDecor(lc, rig, invZoom), avoid);
        }
    }

    /**
     * What the magnifier shows ON TOP of the picture: the bones, and every pin, with the one
     * being dragged filled. Drawn at {@code invZoom} so a line inside the circle is the same
     * thickness on screen as the same line outside it.
     */
    private void drawLoupeDecor(@NonNull Canvas c, @NonNull PuppetRig rig, float invZoom) {
        stroke.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < rig.boneCount(); i++) {
            PuppetRig.Bone b = rig.bone(i);
            if (b.rootPin >= rig.pinCount() || b.tipPin >= rig.pinCount()) continue;
            PuppetPin a = rig.pin(b.rootPin), z = rig.pin(b.tipPin);
            stroke.setColor(PuppetPalette.BONE);
            stroke.setStrokeWidth(2f * d * invZoom);
            c.drawLine(posedX(b.rootPin, a), posedY(b.rootPin, a),
                    posedX(b.tipPin, z), posedY(b.tipPin, z), stroke);
        }
        for (int i = 0; i < rig.pinCount(); i++) {
            PuppetPin p = rig.pin(i);
            boolean on = i == dragPin;
            float rr = (on ? 4.2f : 2.6f) * d * invZoom;
            int hue = PuppetPalette.of(p.type, false);
            PuppetShapes.draw(c, p.type, posedX(i, p), posedY(i, p), rr, hue, fill, stroke,
                    d * invZoom);
        }
    }

    /** Where a pin actually IS right now: its rest position plus whatever the pose moved it by. */
    private float posedX(int i, @NonNull PuppetPin p) {
        boolean got = host != null && host.readOffset(i, off);
        return px(p.restX + (got ? off[0] : 0f), p.restY + (got ? off[1] : 0f));
    }

    private float posedY(int i, @NonNull PuppetPin p) {
        boolean got = host != null && host.readOffset(i, off);
        return py(p.restX + (got ? off[0] : 0f), p.restY + (got ? off[1] : 0f));
    }

    /**
     * The mesh, as a faint wireframe.
     *
     * <p>Every edge is drawn twice — once per triangle that shares it — and that is deliberate:
     * de-duplicating edges costs a hash per frame to save alpha that is already at 10%, and a
     * shared edge reading very slightly stronger is if anything the more useful picture.
     */
    private void drawMesh(@NonNull Canvas c) {
        PuppetMeshWire w = host == null ? null : host.meshWire();
        if (w == null || !w.has()) return;
        float[] xy = w.xy;
        short[] idx = w.idx;
        if (xy == null || idx == null) return;

        int need = w.indexCount * 2;
        if (meshLines == null || meshLines.length < need * 2) meshLines = new float[need * 2];
        int at = 0;
        for (int t = 0; t + 2 < w.indexCount; t += 3) {
            int a = (idx[t] & 0xFFFF) * 2, b = (idx[t + 1] & 0xFFFF) * 2;
            int cc = (idx[t + 2] & 0xFFFF) * 2;
            if (a + 1 >= xy.length || b + 1 >= xy.length || cc + 1 >= xy.length) continue;
            at = edge(meshLines, at, xy, a, b);
            at = edge(meshLines, at, xy, b, cc);
            at = edge(meshLines, at, xy, cc, a);
        }
        if (at == 0) return;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(PuppetPalette.MESH);
        stroke.setStrokeWidth(Math.max(1f, 0.8f * d));
        // ONE drawLines call for the whole wireframe. A per-triangle drawLine loop on a 600
        // triangle mesh is 1800 calls a frame, which is visible as a stutter while scrubbing.
        c.drawLines(meshLines, 0, at, stroke);
    }

    private int edge(@NonNull float[] out, int at, @NonNull float[] xy, int a, int b) {
        if (at + 4 > out.length) return at;
        // BOTH coordinates through both maps. A rotation mixes the axes, so a mapper that is
        // handed one of them has to invent the other — which drew the wireframe sheared across
        // the picture while every pin sat correctly, because the pins passed both.
        out[at] = px(xy[a], xy[a + 1]);
        out[at + 1] = py(xy[a], xy[a + 1]);
        out[at + 2] = px(xy[b], xy[b + 1]);
        out[at + 3] = py(xy[b], xy[b + 1]);
        return at + 4;
    }

    /**
     * How far the selected pin reaches.
     *
     * <p>The radius is the thing the slider beside the eye actually changes: a Stiff pin’s patch
     * is its own Area, and every other pin is governed by the character’s Softness. It is a
     * READING of the falloff rather than a hard boundary — MLS has no edge — so it is drawn
     * dashed, which is the conventional way to say "about here" rather than "exactly here".
     */
    private void drawReach(@NonNull Canvas c, @NonNull PuppetRig rig) {
        int sel = host == null ? -1 : host.selectedPin();
        if (sel < 0 || sel >= rig.pinCount()) return;
        PuppetPin p = rig.pin(sel);
        float unit = p.type == PuppetPin.Type.STIFF
                ? Math.max(0.03f, p.stiffArea * 0.5f)
                : 0.10f + rig.softness * 0.42f;
        float r = unit * Math.min(rect.width(), rect.height());
        if (r < 4f * d) return;

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(PuppetPalette.of(p.type, false));
        stroke.setAlpha(120);
        stroke.setStrokeWidth(1.4f * d);
        stroke.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{6f * d, 5f * d}, 0f));
        c.drawCircle(posedX(sel, p), posedY(sel, p), r, stroke);
        stroke.setPathEffect(null);
        stroke.setAlpha(255);
    }

    /**
     * A pin going.
     *
     * <p>JoyRaptor asked for this by name: <i>"dragging a keyframe off any island causes it to
     * delete with a poof animation. This is the sort of innovation I am wanting."</i> Three rings
     * expanding and fading over 260ms, in the pin’s own colour, so the eye follows the thing
     * that left rather than noticing that something is now absent.
     *
     * <p>It is NOT a warning and it is not a confirmation — one undo press brings the pin back.
     * An animation that meant "gone forever" would make people afraid of the gesture, and a
     * gesture people fear is worse than a menu.
     */
    private void drawPoof(@NonNull Canvas c) {
        if (poofAt == 0L) return;
        long age = android.os.SystemClock.uptimeMillis() - poofAt;
        if (age > POOF_MS) { poofAt = 0L; return; }
        float t = age / (float) POOF_MS;
        float ease = 1f - (1f - t) * (1f - t);

        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            float phase = clamp01(ease - i * 0.14f);
            if (phase <= 0f) continue;
            float r = (7f + phase * 26f + i * 3f) * d;
            int a = Math.round((1f - phase) * 190f);
            stroke.setColor(poofHue);
            stroke.setAlpha(a);
            stroke.setStrokeWidth((2.2f - i * 0.5f) * d);
            c.drawCircle(poofX, poofY, r, stroke);
        }
        stroke.setAlpha(255);
        postInvalidateOnAnimation();
    }

    /** Start the poof at a pin’s last on-screen position. */
    private void poof(float x, float y, int hue) {
        poofX = x; poofY = y; poofHue = hue;
        poofAt = android.os.SystemClock.uptimeMillis();
        invalidate();
    }

    private void drawBones(@NonNull Canvas c, @NonNull PuppetRig rig, boolean locked) {
        stroke.setStrokeWidth(2f * d);
        for (int i = 0; i < rig.boneCount(); i++) {
            PuppetRig.Bone b = rig.bone(i);
            if (b.rootPin >= rig.pinCount() || b.tipPin >= rig.pinCount()) continue;
            PuppetPin a = rig.pin(b.rootPin), z = rig.pin(b.tipPin);
            boolean selBone = !locked && host != null && host.selectedBone() == i;
            stroke.setColor(locked ? PuppetPalette.LOCKED : PuppetPalette.BONE);
            stroke.setAlpha(locked ? 110 : (selBone ? 255 : 210));
            // A selected bone gets a HALO rather than a different colour: colour already means
            // "this is a bone", and overloading it would make a selected bone read as a new type.
            if (selBone) {
                stroke.setStrokeWidth(6f * d);
                stroke.setAlpha(70);
                c.drawLine(posedX(b.rootPin, a), posedY(b.rootPin, a),
                        posedX(b.tipPin, z), posedY(b.tipPin, z), stroke);
                stroke.setStrokeWidth(2f * d);
                stroke.setAlpha(255);
            }
            c.drawLine(posedX(b.rootPin, a), posedY(b.rootPin, a),
                    posedX(b.tipPin, z), posedY(b.tipPin, z), stroke);
        }
        stroke.setAlpha(255);

        // A bone being drawn right now — the rubber band follows the finger.
        if (boneFrom >= 0 && boneFrom < rig.pinCount()) {
            PuppetPin a = rig.pin(boneFrom);
            stroke.setColor(PuppetPalette.BONE);
            stroke.setStrokeWidth(2f * d);
            stroke.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{5f * d, 4f * d}, 0f));
            c.drawLine(posedX(boneFrom, a), posedY(boneFrom, a), boneX, boneY, stroke);
            stroke.setPathEffect(null);
        }
    }

    private void drawPins(@NonNull Canvas c, @NonNull PuppetRig rig, boolean locked) {
        int sel = host == null ? -1 : host.selectedPin();

        for (int i = 0; i < rig.pinCount(); i++) {
            PuppetPin p = rig.pin(i);
            float cx = posedX(i, p), cy = posedY(i, p);
            int hue = PuppetPalette.of(p.type, locked);
            boolean isSel = i == sel && !locked;

            // (A Free pin wore a rotate arc and scale square here until 2026-09-16. The engine
            // stores x and y per pin and nothing else, so they promised something no renderer
            // could draw — see the spec section 1.)

            if (isSel) {
                fill.setColor(hue);
                fill.setAlpha(46);
                c.drawCircle(cx, cy, DOT_R_SEL * d + 5f * d, fill);
                fill.setAlpha(255);
            }

            // SHAPE AS WELL AS COLOUR — a pushpin anchors, a square resists, a teardrop hangs,
            // a circle goes anywhere. One drawer shared with the helper's swatch, so the button
            // that changes a type and the pin it changed cannot end up disagreeing.
            float rr = (isSel ? DOT_R_SEL : DOT_R) * d;
            if (isSel && wasOverStrip && i == dragPin) {
                // About to be removed: a ring the colour of a warning, drawn before the pin so
                // the pin still reads on top of it.
                stroke.setColor(PuppetPalette.STIFF);
                stroke.setStrokeWidth(2f * d);
                c.drawCircle(cx, cy, rr + 7f * d, stroke);
            }
            if (p.muted) {
                PuppetShapes.drawMuted(c, p.type, cx, cy, rr, hue, fill, stroke, d);
            } else {
                PuppetShapes.draw(c, p.type, cx, cy, rr, hue, fill, stroke, d);
            }

            if (isSel) {
                text.setColor(hue);
                c.drawText(p.name, cx, cy + DOT_R_SEL * d + 13f * d, text);
            }
        }
    }

    /**
     * The marionette — the way OUT of the pins and back to the transform box.
     *
     * <p>He is drawn live (green) here because the pins are up: this surface does not exist
     * otherwise. The padlock he used to wear is gone with the state it described.
     */
    private void drawBadge(@NonNull Canvas c, boolean locked) {
        badgeRect.set(getWidth() - (BADGE + BADGE_INSET) * d, BADGE_INSET * d,
                getWidth() - BADGE_INSET * d, (BADGE + BADGE_INSET) * d);

        fill.setColor(0xA8090B0E);
        c.drawRoundRect(badgeRect, 10f * d, 10f * d, fill);
        stroke.setColor(0x1AFFFFFF);
        stroke.setStrokeWidth(1f * d);
        c.drawRoundRect(badgeRect, 10f * d, 10f * d, stroke);

        int tint = locked ? 0xFF6B7280 : 0xFF57B45C;
        int size = Math.round(21f * d);
        PuppetIcons.IconDrawable man = PuppetIcons.of(PuppetIcons.PUPPET, tint, size);
        int left = Math.round(badgeRect.centerX() - size / 2f);
        int top = Math.round(badgeRect.centerY() - size / 2f);
        man.setBounds(left, top, left + size, top + size);
        man.draw(c);

        if (locked) {
            int ls = Math.round(13f * d);
            PuppetIcons.IconDrawable lock =
                    PuppetIcons.of(PuppetIcons.LOCK, 0xFF9AA2AE, ls);
            int ll = Math.round(badgeRect.centerX() - ls * 0.05f);
            int lt = Math.round(badgeRect.centerY() + ls * 0.02f);
            lock.setBounds(ll, lt, ll + ls, lt + ls);
            lock.draw(c);
        }
    }

    // ── touch ────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent e) {
        if (host == null) return false;
        PuppetRig rig = host.rig();
        if (!host.readRect(rect) || rect.width() <= 1f) return false;
        rotDeg = host.itemRotationDeg();

        float x = e.getX(), y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; moved = false;
                dragPin = -1; boneFrom = -1; posing = false;

                // NOT OURS. The drawer comes down from the top OVER the picture, so its resize
                // grip sits on the same pixels the pins do. Claiming a touch up there is how you
                // jam the drawer shut at whatever height it happened to be.
                if (y < host.drawerBottomPx()) return false;

                // The badge first, always: it has to stay reachable even when a pin sits under
                // it, and it is the only control that still works on a locked rig.
                if (rig.pinCount() > 0 && !host.previewIsSmall() && badgeRect.contains(x, y)) {
                    badgeDownAt = System.currentTimeMillis();
                    return true;
                }
                if (rig.locked) return false;    // fall through to whatever is beneath

                int hit = pinAt(rig, x, y);
                if (host.tool() == PuppetDrawerTool.BONE) {
                    // A bone drag starts at a pin, or plants one where there wasn't a pin —
                    // which is what lets one tool rig a whole arm in a single set of drags.
                    boneFrom = hit >= 0 ? hit : placePin(rig, PuppetPin.Type.FREE, x, y);
                    boneX = x; boneY = y;
                    invalidate();
                    return true;
                }
                if (hit >= 0) {
                    host.setSelectedPin(hit);
                    host.setSelectedBone(-1);
                    host.say(rig.pin(hit).name + " \u00b7 " + rig.pin(hit).typeLabel());
                    // LONG PRESS takes whatever is under this one. Armed on every pin, because
                    // whether a pin has a neighbour underneath is not something you can tell by
                    // looking, so the gesture has to be available wherever it might be needed.
                    final float hx = x, hy = y;
                    cancelHold();
                    holdTask = () -> { holdTask = null; if (!moved) cycleUnder(rig, hx, hy); };
                    postDelayed(holdTask, HOLD_MS);
                    dragPin = hit;
                    // GRAB poses; a placement tool re-places. Either way the grab point is the
                    // pin's CURRENT on-screen position, so it does not jump under the finger.
                    posing = host.tool() == PuppetDrawerTool.GRAB;
                    grabDX = posedX(hit, rig.pin(hit)) - x;
                    grabDY = posedY(hit, rig.pin(hit)) - y;
                    if (posing) {
                        host.beginPose();
                        host.readOffset(hit, downOff);
                    } else {
                        downRestX = rig.pin(hit).restX;
                        downRestY = rig.pin(hit).restY;
                    }
                    host.onRigChanged();
                    invalidate();
                    return true;
                }
                // Empty art with a placement tool armed: drop one on the UP, not here, so a
                // scroll that happens to start on the picture does not leave a pin behind.
                return toolPlaces(host.tool()) && onPicture(x, y);

            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(x - downX, y - downY) > DRAG_SLOP * d) moved = true;
                if (boneFrom >= 0) { boneX = x; boneY = y; invalidate(); return true; }
                if (dragPin >= 0 && moved) {
                    PuppetPin p = rig.pin(dragPin);
                    // THE STRIP IS A SINK AS WELL AS A SOURCE. Drag a pin into it and it goes;
                    // drag the swatch out of it and one arrives. One place, two directions, and
                    // nothing to teach — the bin is not somewhere else.
                    cancelHold();
                    boolean overStrip = host.pointInHelper(x, y);
                    host.helperDodge(x, y, overStrip);
                    if (overStrip != wasOverStrip) { wasOverStrip = overStrip; invalidate(); }
                    toUnit(x + grabDX, y + grabDY);
                    float ux = unit[0], uy = unit[1];
                    if (posing) {
                        // A PIN ON A CHAIN DRAGS THE LIMB. solveChainTo runs the bones back to
                        // their root, honouring joint limits and stretch, and writes every pin on
                        // the way — which is what makes dragging a wrist move the arm instead of
                        // tearing the hand off it. A lone pin says false and moves by itself.
                        //
                        // The offset is measured from REST and is deliberately NOT clamped to the
                        // picture: an arm reaching out of frame is a legitimate pose, the same
                        // call PuppetDeformer.clampComponent already makes.
                        if (!host.solveChainTo(dragPin, ux, uy)) {
                            host.writeOffset(dragPin, ux - p.restX, uy - p.restY);
                        }
                    } else {
                        p.restX = clamp01(ux);
                        p.restY = clamp01(uy);
                    }
                    invalidate();
                    return true;
                }

                // NO PIN. A bone’s shaft is the next thing worth hitting: a bone is a thing you
                // can select, and until now there was no way to reach one — so every control the
                // drawer holds for a bone was unreachable, which is why none of them existed.
                if (host.tool() == PuppetDrawerTool.GRAB) {
                    int hb = boneAt(rig, x, y);
                    if (hb >= 0) {
                        host.setSelectedBone(hb);
                        PuppetRig.Bone hbb = rig.bone(hb);
                        host.say(rig.pin(hbb.rootPin).name + " \u2192 "
                                + rig.pin(hbb.tipPin).name);
                        host.onRigChanged();
                        invalidate();
                        return true;
                    }
                }
                return dragPin >= 0 || boneFrom >= 0;

            case MotionEvent.ACTION_UP:
                if (rig.pinCount() > 0 && !host.previewIsSmall()
                        && badgeRect.contains(x, y) && badgeRect.contains(downX, downY)) {
                    // HELD on him: the drawer, where the deeper controls are. TAPPED: the pins go
                    // away and the transform box comes back. One control, two depths, and the
                    // shallow one is the one you will want ninety times out of a hundred.
                    if (System.currentTimeMillis() - badgeDownAt > HOLD_MS) {
                        host.openPuppetDrawer();
                    } else {
                        host.putPinsAway();
                    }
                    return true;
                }
                if (rig.locked) { reset(); return false; }

                if (boneFrom >= 0) {
                    finishBone(rig, x, y);
                    reset();
                    return true;
                }
                if (dragPin >= 0) {
                    if (moved && host.pointInHelper(x, y)) {
                        // Released over the strip: the pin puffs away. Undoable, always — an
                        // animation that meant "gone forever" would make people stop trusting
                        // the gesture, and a gesture people fear is worse than a menu.
                        int gone = dragPin;
                        PuppetPin gp = rig.pin(gone);
                        poof(posedX(gone, gp), posedY(gone, gp),
                                PuppetPalette.of(gp.type, false));
                        host.say("Removed " + gp.name + " \u2014 undo brings it back");
                        wasOverStrip = false;
                        reset();
                        host.deletePin(gone);
                        invalidate();
                        return true;
                    }
                    if (moved) {
                        // ONE undo for the whole drag — the standing ruling. Recorded on release,
                        // never per MOVE, and skipped entirely when the pin ended where it began.
                        if (posing) {
                            host.readOffset(dragPin, off);
                            if (Math.abs(off[0] - downOff[0]) > 1e-5f
                                    || Math.abs(off[1] - downOff[1]) > 1e-5f) {
                                host.commitPose("Bend");
                            }
                        } else {
                            final PuppetPin p = rig.pin(dragPin);
                            final float ax = p.restX, ay = p.restY;
                            final float bx = downRestX, by = downRestY;
                            if (Math.abs(ax - bx) > 1e-4f || Math.abs(ay - by) > 1e-4f) {
                                host.recordUndo("Move pin",
                                        () -> { p.restX = ax; p.restY = ay; structureChanged(); },
                                        () -> { p.restX = bx; p.restY = by; structureChanged(); });
                                // A pin that moved is a DIFFERENT rig: the triangles were built
                                // around where it used to be.
                                host.onRigStructureChanged();
                            }
                        }
                        host.onRigChanged();
                    }
                    reset();
                    return true;
                }
                if (!moved && toolPlaces(host.tool()) && onPicture(x, y)) {
                    // NOT WHILE IT IS PLAYING. Adding a pin is authoring and dragging one is
                    // performing; only the second belongs in a moving picture, and a pin dropped
                    // onto a frame that has already gone lands somewhere nobody chose.
                    if (host.isPlaying()) {
                        host.say("Pause to add pins \u2014 dragging them still records");
                        reset();
                        return true;
                    }
                    int made = placePin(rig, typeOf(host.tool()), x, y);
                    if (made >= 0) {
                        host.setSelectedPin(made);
                        host.say("Placed " + rig.pin(made).name);
                        final int idx = made;
                        host.recordUndo("Add pin",
                                () -> { },     // redo re-runs through the drawer's own rebuild
                                () -> { rig.removePin(idx); onPinRemoved(idx); });
                        host.onRigStructureChanged();
                        host.onRigChanged();
                    }
                    invalidate();
                    reset();
                    return true;
                }
                reset();
                return false;

            case MotionEvent.ACTION_CANCEL:
                reset();
                invalidate();
                return false;

            default:
                return false;
        }
    }

    private void hostChanged() {
        if (host != null) host.onRigChanged();
        invalidate();
    }

    /**
     * Long press on a pin: take the NEXT pin underneath the same finger.
     *
     * <p>The preview deliberately does not open a fourth copy of the type ring here. That ring
     * already lives on the helper strip’s swatch and on the drawer’s, both a thumb away, and a
     * third would be three places to keep in step. Cycling what is underneath has no other home,
     * and without it a pin behind another is simply unreachable.
     *
     * <p>It NAMES what it landed on — "L.Elbow · 2 of 3" — because blind cycling is a guess.
     */
    private void cycleUnder(@NonNull PuppetRig rig, float x, float y) {
        if (host == null || rig.locked) return;
        int n = pinsUnder(rig, x, y, under);
        if (n == 0) return;
        if (n == 1) {
            host.setSelectedPin(under[0]);
            host.setSelectedBone(-1);
            host.say(rig.pin(under[0]).name + " \u00b7 nothing underneath it");
            host.onRigChanged();
            invalidate();
            return;
        }
        int cur = host.selectedPin();
        int at = 0;
        for (int i = 0; i < n; i++) if (under[i] == cur) { at = i + 1; break; }
        int pick = under[at % n];
        host.setSelectedPin(pick);
        host.setSelectedBone(-1);
        host.say(rig.pin(pick).name + " \u00b7 " + (at % n + 1) + " of " + n);
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        host.onRigChanged();
        invalidate();
    }

    private void reset() {
        cancelHold();
        dragPin = -1; boneFrom = -1; moved = false; posing = false; wasOverStrip = false;
        // The strip latches in place once a finger comes near it, so that it cannot flee the
        // very thing being dragged towards it. Every way out of a gesture is through here,
        // which makes this the one place that latch can be honestly cleared.
        if (host != null) host.helperGestureEnded();
    }

    private void structureChanged() {
        if (host != null) { host.onRigStructureChanged(); host.onRigChanged(); }
        invalidate();
    }

    private void onPinRemoved(int index) {
        if (host != null) { host.onPinRemoved(index); host.onRigChanged(); }
        invalidate();
    }

    /**
     * Land a bone on whatever is under the finger — an existing pin, or a new one planted there.
     *
     * <p>A refusal is silent on purpose: {@code addBone} turns down a bone to itself, a second
     * parent and anything that would close a loop, and none of those are worth a toast. The user
     * sees no bone appear, which is the whole message.
     */
    private void finishBone(@NonNull PuppetRig rig, float x, float y) {
        if (boneFrom < 0 || boneFrom >= rig.pinCount()) return;
        if (!moved) return;                       // a tap is not a bone
        int to = pinAt(rig, x, y);
        if (to == boneFrom) return;
        if (to < 0) {
            if (!onPicture(x, y)) return;
            to = placePin(rig, PuppetPin.Type.FREE, x, y);
        }
        if (to < 0) return;
        PuppetPin a = rig.pin(boneFrom), z = rig.pin(to);
        float len = (float) Math.hypot(z.restX - a.restX, z.restY - a.restY);
        final int made = rig.addBone(boneFrom, to, len);
        if (made < 0) return;
        host.recordUndo("Add bone", () -> { }, () -> { rig.removeBone(made); hostChanged(); });
        host.onRigChanged();     // a bone adds no handle, so the mesh is unchanged
    }

    private int placePin(@NonNull PuppetRig rig, @NonNull PuppetPin.Type type, float x, float y) {
        if (!onPicture(x, y)) return -1;
        toUnit(x, y);
        return rig.addPin(type, clamp01(unit[0]), clamp01(unit[1]));
    }

    /** The nearest pin within a finger's reach, or -1. Nearest, so overlapping pins are pickable. */
    private int pinAt(@NonNull PuppetRig rig, float x, float y) {
        int best = -1;
        float bestD = HIT_R * d;
        for (int i = 0; i < rig.pinCount(); i++) {
            PuppetPin p = rig.pin(i);
            float dist = (float) Math.hypot(posedX(i, p) - x, posedY(i, p) - y);
            if (dist <= bestD) { bestD = dist; best = i; }
        }
        return best;
    }

    /**
     * Every pin under the finger, nearest first.
     *
     * <p>Overlapping pins are the normal case on a character — a shoulder and the top of an arm
     * are drawn on top of each other — and {@link #pinAt} can only ever return the nearest, which
     * makes the one behind unreachable. This is what long-press cycles through.
     */
    private int pinsUnder(@NonNull PuppetRig rig, float x, float y, @NonNull int[] out) {
        int n = 0;
        float reach = HIT_R * d;
        for (int i = 0; i < rig.pinCount() && n < out.length; i++) {
            PuppetPin p = rig.pin(i);
            if (Math.hypot(posedX(i, p) - x, posedY(i, p) - y) <= reach) out[n++] = i;
        }
        return n;
    }

    /**
     * The bone whose shaft the finger is on, or -1.
     *
     * <p>Only consulted once no PIN was hit, so a joint always wins over the bones that meet at
     * it — the pin is the smaller target and the one that does more.
     */
    private int boneAt(@NonNull PuppetRig rig, float x, float y) {
        int best = -1;
        float bestD = BONE_HIT_DP * d;
        for (int i = 0; i < rig.boneCount(); i++) {
            PuppetRig.Bone b = rig.bone(i);
            if (b.rootPin < 0 || b.tipPin < 0
                    || b.rootPin >= rig.pinCount() || b.tipPin >= rig.pinCount()) continue;
            PuppetPin a = rig.pin(b.rootPin), z = rig.pin(b.tipPin);
            float dist = pointToSegment(x, y,
                    posedX(b.rootPin, a), posedY(b.rootPin, a),
                    posedX(b.tipPin, z), posedY(b.tipPin, z));
            if (dist <= bestD) { bestD = dist; best = i; }
        }
        return best;
    }

    private static float pointToSegment(float px, float py,
                                        float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay;
        float len2 = vx * vx + vy * vy;
        if (len2 < 1e-6f) return (float) Math.hypot(px - ax, py - ay);
        float t = ((px - ax) * vx + (py - ay) * vy) / len2;
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        return (float) Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }

    private static boolean toolPlaces(@NonNull PuppetDrawerTool t) {
        return t == PuppetDrawerTool.PIN || t == PuppetDrawerTool.STIFF
                || t == PuppetDrawerTool.DANGLE || t == PuppetDrawerTool.FREE;
    }

    @NonNull
    private static PuppetPin.Type typeOf(@NonNull PuppetDrawerTool t) {
        switch (t) {
            case PIN: return PuppetPin.Type.PIN;
            case STIFF: return PuppetPin.Type.STIFF;
            case DANGLE: return PuppetPin.Type.DANGLE;
            default: return PuppetPin.Type.FREE;
        }
    }

    // ══ UNIT SPACE ↔ SCREEN, THROUGH THE ITEM’S OWN ROTATION ═══════════════
    //
    // {@code rect} is the item’s box as if it were UPRIGHT. Until 2026-09-16 that was the whole
    // map, and it had two visible consequences on a rotated picture:
    //
    //   * every pin sat somewhere other than where it was placed, because the box being measured
    //     was the AXIS-ALIGNED BOUNDS of a rotated view rather than the view;
    //   * pins scattered and snapped back DURING a rotate — JoyRaptor saw this and called it
    //     sloppy — because those bounds grow and shrink as the angle sweeps, so the same unit
    //     coordinate mapped to a different pixel on every frame of the gesture.
    //
    // Both are one bug. The item has an angle; the map has to use it. Everything is expressed
    // through these four methods so there is exactly one place the rotation is applied and
    // exactly one place it is undone.

    private float px(float ux, float uy) {
        float x = rect.left + ux * rect.width();
        if (rotDeg == 0f) return x;
        float y = rect.top + uy * rect.height();
        double r = Math.toRadians(rotDeg);
        float cx = rect.centerX(), cy = rect.centerY();
        return cx + (float) ((x - cx) * Math.cos(r) - (y - cy) * Math.sin(r));
    }

    private float py(float ux, float uy) {
        float y = rect.top + uy * rect.height();
        if (rotDeg == 0f) return y;
        float x = rect.left + ux * rect.width();
        double r = Math.toRadians(rotDeg);
        float cx = rect.centerX(), cy = rect.centerY();
        return cy + (float) ((x - cx) * Math.sin(r) + (y - cy) * Math.cos(r));
    }

    /** Screen pixels back to unit space, written into {@link #unit}. Exactly inverts the pair above. */
    private void toUnit(float x, float y) {
        float lx = x, ly = y;
        if (rotDeg != 0f) {
            double r = Math.toRadians(-rotDeg);
            float cx = rect.centerX(), cy = rect.centerY();
            lx = cx + (float) ((x - cx) * Math.cos(r) - (y - cy) * Math.sin(r));
            ly = cy + (float) ((x - cx) * Math.sin(r) + (y - cy) * Math.cos(r));
        }
        unit[0] = rect.width() <= 0f ? 0f : (lx - rect.left) / rect.width();
        unit[1] = rect.height() <= 0f ? 0f : (ly - rect.top) / rect.height();
    }

    /** True when this screen point is on the picture — the ROTATED picture, not its bounds. */
    private boolean onPicture(float x, float y) {
        toUnit(x, y);
        return unit[0] >= 0f && unit[0] <= 1f && unit[1] >= 0f && unit[1] <= 1f;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
