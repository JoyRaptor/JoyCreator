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
 * <h3>Rigging and performing are different gestures on the same dot</h3>
 * <p>Dragging a pin here moves its REST POSITION — where it sits when nothing animates it. That
 * is rigging, and it is what this view does. Dragging while a recording is running moves the pose
 * TRACK instead, which is performing. Two different stores, deliberately, and this view only ever
 * touches the first: it cannot key anything, which is why it needs no playhead.
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
    }

    /**
     * The armed tool, mirrored out of the drawer.
     *
     * <p>Declared here rather than imported from {@code PuppetDrawerTabs} so this package keeps
     * pointing one way: the drawer may depend on the rig, the rig never depends on the drawer.
     */
    public enum PuppetDrawerTool { GRAB, PIN, STIFF, DANGLE, FREE, BONE }

    // ── sizes, in dp ─────────────────────────────────────────────────────

    private static final float DOT_R = 6.5f;
    private static final float DOT_R_SEL = 9f;
    private static final float HIT_R = 22f;          // generous: a finger is not a mouse
    private static final float BADGE = 38f;
    private static final float BADGE_INSET = 9f;
    private static final float HELPER_R = 30f;
    private static final float LABEL_SP = 9.5f;
    private static final float DRAG_SLOP = 6f;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF badgeRect = new RectF();
    private final RectF arc = new RectF();
    private final Path path = new Path();

    @Nullable private Host host;

    // gesture state
    private int dragPin = -1;
    private int boneFrom = -1;
    private float downX, downY, boneX, boneY;
    private boolean moved;
    private float grabDX, grabDY;

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

    @Nullable public Host host() { return host; }

    /** Re-read and repaint. Cheap; call it from anywhere that changes the rig. */
    public void refresh() { invalidate(); }

    // ── drawing ──────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas c) {
        if (host == null) return;
        PuppetRig rig = host.rig();
        if (!host.readRect(rect) || rect.width() <= 1f || rect.height() <= 1f) return;

        boolean locked = rig.locked;

        if (rig.showBones) drawBones(c, rig, locked);
        if (rig.showPins) drawPins(c, rig, locked);

        // The badge is the LAST thing drawn and the first thing hit-tested: it must stay
        // reachable even when a pin happens to sit under it.
        if (rig.pinCount() > 0 && !host.previewIsSmall()) drawBadge(c, locked);
    }

    private void drawBones(@NonNull Canvas c, @NonNull PuppetRig rig, boolean locked) {
        stroke.setStrokeWidth(2f * d);
        for (int i = 0; i < rig.boneCount(); i++) {
            PuppetRig.Bone b = rig.bone(i);
            if (b.rootPin >= rig.pinCount() || b.tipPin >= rig.pinCount()) continue;
            PuppetPin a = rig.pin(b.rootPin), z = rig.pin(b.tipPin);
            stroke.setColor(locked ? PuppetPalette.LOCKED : PuppetPalette.BONE);
            stroke.setAlpha(locked ? 110 : 210);
            c.drawLine(px(a.restX), py(a.restY), px(z.restX), py(z.restY), stroke);
        }
        stroke.setAlpha(255);

        // A bone being drawn right now — the rubber band follows the finger.
        if (boneFrom >= 0 && boneFrom < rig.pinCount()) {
            PuppetPin a = rig.pin(boneFrom);
            stroke.setColor(PuppetPalette.BONE);
            stroke.setStrokeWidth(2f * d);
            stroke.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{5f * d, 4f * d}, 0f));
            c.drawLine(px(a.restX), py(a.restY), boneX, boneY, stroke);
            stroke.setPathEffect(null);
        }
    }

    private void drawPins(@NonNull Canvas c, @NonNull PuppetRig rig, boolean locked) {
        int sel = host == null ? -1 : host.selectedPin();

        for (int i = 0; i < rig.pinCount(); i++) {
            PuppetPin p = rig.pin(i);
            float cx = px(p.restX), cy = py(p.restY);
            int hue = PuppetPalette.of(p.type, locked);
            boolean isSel = i == sel && !locked;

            // The selected FREE pin wears the transform tool's own vocabulary — see §1.
            if (isSel && p.type == PuppetPin.Type.FREE) drawFreeHelper(c, cx, cy);

            if (isSel) {
                fill.setColor(hue);
                fill.setAlpha(46);
                c.drawCircle(cx, cy, DOT_R_SEL * d + 5f * d, fill);
                fill.setAlpha(255);
            }

            // A dark ring first so a pin stays visible on artwork of its own colour.
            stroke.setColor(0xD9090B0E);
            stroke.setStrokeWidth(2.6f * d);
            c.drawCircle(cx, cy, (isSel ? DOT_R_SEL : DOT_R) * d, stroke);

            fill.setColor(hue);
            fill.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, (isSel ? DOT_R_SEL : DOT_R) * d, fill);

            // A muted pin is hollow — it is still there, it just does nothing.
            if (p.muted) {
                fill.setColor(0xFF090B0E);
                c.drawCircle(cx, cy, (isSel ? DOT_R_SEL : DOT_R) * d - 2.4f * d, fill);
            }

            if (isSel) {
                text.setColor(hue);
                c.drawText(p.name, cx, cy + DOT_R_SEL * d + 13f * d, text);
            }
        }
    }

    /**
     * The rotate arc and scale square from {@code HandleModel}, on a circle.
     *
     * <p>Not a lookalike: the amber is {@link HandleModel#COLOR_SCALE}, the arc is the same
     * gesture the transform tool's spin arc reads, and the circle is the shape that file already
     * assigns to a FREE corner. A Free pin behaves like a transform handle, so it has to look
     * like one.
     */
    private void drawFreeHelper(@NonNull Canvas c, float cx, float cy) {
        float r = HELPER_R * d;

        stroke.setColor(PuppetPalette.FREE);
        stroke.setStrokeWidth(1.3f * d);
        stroke.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{3f * d, 4f * d}, 0f));
        c.drawCircle(cx, cy, r, stroke);
        stroke.setPathEffect(null);

        // the amber spin arc, floating outside the top edge
        arc.set(cx - r * 0.86f, cy - r * 0.86f, cx + r * 0.86f, cy + r * 0.86f);
        stroke.setColor(HandleModel.COLOR_SCALE);
        stroke.setStrokeWidth(2.4f * d);
        c.drawArc(arc, -142f, 104f, false, stroke);

        // its arrowhead, at the arc's clockwise end
        double end = Math.toRadians(-38);
        float ax = cx + (float) Math.cos(end) * r * 0.86f;
        float ay = cy + (float) Math.sin(end) * r * 0.86f;
        path.reset();
        path.moveTo(ax, ay);
        path.lineTo(ax - 5.5f * d, ay - 3.4f * d);
        path.lineTo(ax + 1.4f * d, ay - 6.4f * d);
        path.close();
        fill.setColor(HandleModel.COLOR_SCALE);
        c.drawPath(path, fill);

        // the amber scale square, on the right
        float s = 5.4f * d;
        fill.setColor(0xFF12161C);
        c.drawRect(cx + r - s, cy - s, cx + r + s, cy + s, fill);
        stroke.setStrokeWidth(2f * d);
        c.drawRect(cx + r - s, cy - s, cx + r + s, cy + s, stroke);
    }

    /** The marionette, with a padlock in front of him when the rig is shut off. */
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

        float x = e.getX(), y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; moved = false;
                dragPin = -1; boneFrom = -1;

                // The badge first, always: it has to stay reachable even when a pin sits under
                // it, and it is the only control that still works on a locked rig.
                if (rig.pinCount() > 0 && !host.previewIsSmall() && badgeRect.contains(x, y)) {
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
                    dragPin = hit;
                    grabDX = px(rig.pin(hit).restX) - x;
                    grabDY = py(rig.pin(hit).restY) - y;
                    host.onRigChanged();
                    invalidate();
                    return true;
                }
                // Empty art with a placement tool armed: drop one on the UP, not here, so a
                // scroll that happens to start on the picture does not leave a pin behind.
                return toolPlaces(host.tool()) && rect.contains(x, y);

            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(x - downX, y - downY) > DRAG_SLOP * d) moved = true;
                if (boneFrom >= 0) { boneX = x; boneY = y; invalidate(); return true; }
                if (dragPin >= 0 && moved) {
                    PuppetPin p = rig.pin(dragPin);
                    p.restX = clamp01((x + grabDX - rect.left) / rect.width());
                    p.restY = clamp01((y + grabDY - rect.top) / rect.height());
                    invalidate();
                    return true;
                }
                return dragPin >= 0 || boneFrom >= 0;

            case MotionEvent.ACTION_UP:
                if (rig.pinCount() > 0 && !host.previewIsSmall()
                        && badgeRect.contains(x, y) && badgeRect.contains(downX, downY)) {
                    rig.locked = !rig.locked;
                    host.onRigChanged();
                    invalidate();
                    return true;
                }
                if (rig.locked) { reset(); return false; }

                if (boneFrom >= 0) {
                    finishBone(rig, x, y);
                    reset();
                    return true;
                }
                if (dragPin >= 0) {
                    if (moved) {
                        // ONE undo for the whole drag — the standing ruling. Recorded here and
                        // not per MOVE, and skipped when the pin came back to where it started.
                        final PuppetPin p = rig.pin(dragPin);
                        final float ax = p.restX, ay = p.restY;
                        final float bx = clamp01((downX + grabDX - rect.left) / rect.width());
                        final float by = clamp01((downY + grabDY - rect.top) / rect.height());
                        if (Math.abs(ax - bx) > 1e-4f || Math.abs(ay - by) > 1e-4f) {
                            host.recordUndo("Move pin",
                                    () -> { p.restX = ax; p.restY = ay; hostChanged(); },
                                    () -> { p.restX = bx; p.restY = by; hostChanged(); });
                        }
                        host.onRigChanged();
                    }
                    reset();
                    return true;
                }
                if (!moved && toolPlaces(host.tool()) && rect.contains(x, y)) {
                    int made = placePin(rig, typeOf(host.tool()), x, y);
                    if (made >= 0) {
                        host.setSelectedPin(made);
                        final int idx = made;
                        host.recordUndo("Add pin",
                                () -> { },     // redo re-runs through the drawer's own rebuild
                                () -> { rig.removePin(idx); hostChanged(); });
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

    private void reset() { dragPin = -1; boneFrom = -1; moved = false; }

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
            if (!rect.contains(x, y)) return;
            to = placePin(rig, PuppetPin.Type.FREE, x, y);
        }
        if (to < 0) return;
        PuppetPin a = rig.pin(boneFrom), z = rig.pin(to);
        float len = (float) Math.hypot(z.restX - a.restX, z.restY - a.restY);
        final int made = rig.addBone(boneFrom, to, len);
        if (made < 0) return;
        host.recordUndo("Add bone", () -> { }, () -> { rig.removeBone(made); hostChanged(); });
        host.onRigChanged();
    }

    private int placePin(@NonNull PuppetRig rig, @NonNull PuppetPin.Type type, float x, float y) {
        if (!rect.contains(x, y)) return -1;
        return rig.addPin(type,
                clamp01((x - rect.left) / rect.width()),
                clamp01((y - rect.top) / rect.height()));
    }

    /** The nearest pin within a finger's reach, or -1. Nearest, so overlapping pins are pickable. */
    private int pinAt(@NonNull PuppetRig rig, float x, float y) {
        int best = -1;
        float bestD = HIT_R * d;
        for (int i = 0; i < rig.pinCount(); i++) {
            PuppetPin p = rig.pin(i);
            float dist = (float) Math.hypot(px(p.restX) - x, py(p.restY) - y);
            if (dist <= bestD) { bestD = dist; best = i; }
        }
        return best;
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

    private float px(float ux) { return rect.left + ux * rect.width(); }
    private float py(float uy) { return rect.top + uy * rect.height(); }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
