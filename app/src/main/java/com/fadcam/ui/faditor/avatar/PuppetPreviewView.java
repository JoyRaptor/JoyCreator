package com.fadcam.ui.faditor.avatar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A1-UI (PLAN_AVATAR_STUDIO): live puppet canvas. Renders the rig's parts at
 * their RESOLVED state ({@link PuppetPoseResolver} output supplied by the
 * activity — this view never blends poses itself, single-authority rule),
 * composing parent∘child transforms at draw time exactly as the doctrine
 * assigns to renderers.
 *
 * <p>A6: parts carrying a {@link AvatarRig.Part#restPins} chain draw through the
 * PIN-WARP path — {@link PinWarpStrip} builds the vertex grid and
 * {@code Canvas.drawBitmapMesh} warps the cell art along the RESOLVED pin chain
 * (the plan's "GL renderer" premise was wrong: Canvas mesh-draw does sparse
 * vertex warps natively, which keeps preview/export on the same drawing stack).
 * The resolver's {@code swapped} signal starts a PIN-SNAP CROSSFADE: the
 * previous cell draws over the new one for {@value #CROSSFADE_MS}ms with both
 * warped by the SAME vertex grid — the swap happens over identical geometry and
 * reads as a smooth turn (plan §Pin-warp). Convention violations (short chain,
 * non-monotonic rest pins, missing art) fall back to the rigid draw — never
 * crash, never garble. followWeight is treated as full inheritance (=1) with
 * the attenuated path deferred to the tracking phase. Missing sheet art draws
 * the S7-style MISSING placeholder.</p>
 *
 * <p>When an armed cell + selected part are set, one-finger drag on the canvas
 * reports normalized deltas so the activity can pose that part in the cell.</p>
 */
public class PuppetPreviewView extends View {

    /** A root part's neutral width as a fraction of the canvas' short side. */
    private static final float PART_BASE_FRACTION = 0.34f;
    /** Warp mesh bands. 24 bands ≈ 50 triangles/part — still nothing to a phone
     *  GPU (drawBitmapMesh is hardware-accelerated; mobile GPUs push millions of
     *  tris). 10 was the over-cautious v1 value; the first hand-test read strong
     *  bends as choppy, and density + joint-smoothed normals fixed it. */
    private static final int WARP_SEGMENTS = 24;
    private static final int WARP_SEGMENTS_MIN = 8, WARP_SEGMENTS_MAX = 64;

    /** Per-part band count: the part's own {@code warpSegments} (clamped) or the default. */
    private static int segmentsFor(@NonNull AvatarRig.Part part) {
        if (part.warpSegments <= 0) return WARP_SEGMENTS;
        return Math.max(WARP_SEGMENTS_MIN, Math.min(WARP_SEGMENTS_MAX, part.warpSegments));
    }

    /** Band count encoded in a vert array from {@link #warpVertsFor} ((segments+1)*4 floats). */
    private static int segmentsOf(@NonNull float[] verts) {
        return verts.length / 4 - 1;
    }
    /** Pin-snap crossfade window after a discrete cell swap (plan §Pin-warp). */
    private static final long CROSSFADE_MS = 130;

    public interface Listener {
        /** Drag while a cell is armed: deltas normalized to the view's size. */
        void onPartDragged(float dxNorm, float dyNorm);
    }

    /**
     * A6 pin authoring. The view renders/hit-tests the pin list the activity
     * supplies (rest chain OR an armed cell's posed pins — the view doesn't
     * know which; model writes stay in the activity, single-authority rule)
     * and reports edits in CELL space (0..1 of the selected part's art box).
     */
    public interface PinEditListener {
        /** Tap on empty part art — activity may add a pin here (rest mode only). */
        void onPinAdd(float cellX, float cellY);
        /** A handle is being dragged (continuous). */
        void onPinMove(int index, float cellX, float cellY);
    }

    @Nullable private AvatarRig rig;
    @Nullable private Map<String, PuppetPoseResolver.PartState> resolved;
    /** sheetId → renderer (null result = missing art). Owned by the activity. */
    @Nullable private java.util.function.Function<String, SpriteSheetRenderer> rendererLookup;
    @Nullable private String selectedPartId;
    private boolean dragEnabled = false;
    @Nullable private Listener listener;

    private final Paint missingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint missingText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix workMatrix = new Matrix();
    private final RectF workRect = new RectF();

    // A6: cell-bitmap cache for drawBitmapMesh (a sheet cell is a sub-rect; mesh
    // draw needs its own Bitmap). Keyed sheetId/cellIndex; cleared on bind().
    private final java.util.Map<String, android.graphics.Bitmap> cellBitmaps =
            new java.util.HashMap<>();
    /** partId → crossfade state from the resolver's swapped signal. */
    private final java.util.Map<String, long[]> swapAtMs = new java.util.HashMap<>();
    /** partId → the cell shown BEFORE the current one (crossfade source). */
    private final java.util.Map<String, Integer> lastCell = new java.util.HashMap<>();

    // A6 dangle physics: one verlet chain per dangle-tagged part, stepped ONCE
    // per frame in onDraw's preamble (warpVertsFor may run twice per frame when
    // the mesh overlay is up — double-stepping would double the sim rate).
    // Vsync-paced via postInvalidateOnAnimation while any dangle part exists.
    private final java.util.Map<String, DangleSim> dangleSims = new java.util.HashMap<>();
    private final java.util.Map<String, java.util.List<float[]>> frameDanglePins =
            new java.util.HashMap<>();
    private long lastFrameNanos;

    // A6 pin authoring state (null = pin mode off).
    @Nullable private List<float[]> pinEditing;
    @Nullable private PinEditListener pinListener;
    private final Paint pinFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinLink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix invMatrix = new Matrix();
    private int draggingPin = -1;
    private boolean pinMoved;
    private float pinDownX, pinDownY;

    private float lastX, lastY;
    private boolean dragging = false;

    public PuppetPreviewView(Context ctx) {
        super(ctx);
        setBackgroundColor(0xFF15151A);
        pinFill.setColor(0xFF64FFDA);
        pinRing.setStyle(Paint.Style.STROKE);
        pinRing.setStrokeWidth(3f);
        pinRing.setColor(0xFF15151A);
        pinLink.setStyle(Paint.Style.STROKE);
        pinLink.setStrokeWidth(2f);
        pinLink.setColor(0x9964FFDA);
        missingPaint.setStyle(Paint.Style.STROKE);
        missingPaint.setStrokeWidth(3f);
        missingPaint.setColor(0xFFE040FB);
        missingText.setColor(0xFFE040FB);
        missingText.setTextSize(26f);
        missingText.setTextAlign(Paint.Align.CENTER);
        selectPaint.setStyle(Paint.Style.STROKE);
        selectPaint.setStrokeWidth(3f);
        selectPaint.setColor(0xFFFFD54F);
        bgGrid.setColor(0x14FFFFFF);
        bgGrid.setStrokeWidth(1f);
    }

    public void setListener(@Nullable Listener l) { this.listener = l; }

    public void bind(@Nullable AvatarRig rig,
                     @Nullable java.util.function.Function<String, SpriteSheetRenderer> rendererLookup) {
        this.rig = rig;
        this.rendererLookup = rendererLookup;
        for (android.graphics.Bitmap b : cellBitmaps.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        cellBitmaps.clear();
        swapAtMs.clear();
        lastCell.clear();
        dangleSims.clear();
        lastFrameNanos = 0;
        invalidate();
    }

    /** New resolved state from the activity's resolve pass. */
    public void setResolved(@Nullable Map<String, PuppetPoseResolver.PartState> r) {
        // A6 pin-snap crossfade bookkeeping: catch the swapped edge BEFORE the
        // draw pass consumes the new cell, remembering which cell fades out.
        if (r != null) {
            long now = android.os.SystemClock.uptimeMillis();
            for (Map.Entry<String, PuppetPoseResolver.PartState> e : r.entrySet()) {
                PuppetPoseResolver.PartState ps = e.getValue();
                Integer prev = lastCell.get(e.getKey());
                if (ps.swapped && prev != null && prev != ps.cellIndex) {
                    swapAtMs.put(e.getKey(), new long[]{now, prev});
                }
                lastCell.put(e.getKey(), ps.cellIndex);
            }
        }
        this.resolved = r;
        invalidate();
    }

    public void setSelectedPart(@Nullable String partId) {
        this.selectedPartId = partId;
        invalidate();
    }

    /** Drag-to-pose only makes sense while a cell is armed. */
    public void setDragEnabled(boolean enabled) {
        this.dragEnabled = enabled;
        if (!enabled) dragging = false;
    }

    /**
     * A6: enter/exit pin editing. {@code pins} is the LIVE list the activity is
     * editing (rest chain or armed pose pins, in cell space) — the view renders
     * handles for it on the selected part and reports edits via {@code l}.
     * Null exits pin mode. Pin mode takes touch priority over pose-drag.
     */
    public void setPinEditing(@Nullable List<float[]> pins, @Nullable PinEditListener l) {
        this.pinEditing = pins;
        this.pinListener = pins == null ? null : l;
        this.draggingPin = -1;
        invalidate();
    }

    /** Art-box geometry of a part in its LOCAL space: {dw, dh, left, top}. */
    @Nullable
    private float[] partBox(@NonNull AvatarRig.Part part) {
        SpriteSheetRenderer r = rendererLookup != null ? rendererLookup.apply(part.sheetId) : null;
        float shortSide = Math.min(getWidth(), getHeight());
        float dw = shortSide * PART_BASE_FRACTION;
        float aspect = r != null ? r.cellAspect() : 1f;
        float dh = aspect > 0 ? dw / aspect : dw;
        float ax = part.anchorX != null ? part.anchorX : (r != null ? sheetPivotX(part) : 0.5f);
        float ay = part.anchorY != null ? part.anchorY : (r != null ? sheetPivotY(part) : 0.5f);
        return new float[]{dw, dh, -ax * dw, -ay * dh};
    }

    /** world ∘ flip matrix for a part — the full local→view transform. */
    @NonNull
    private Matrix fullPartMatrix(@NonNull AvatarRig.Part part) {
        Matrix m = new Matrix();
        PuppetPoseResolver.PartState ps = resolved != null ? resolved.get(part.id) : null;
        if (ps != null && (ps.flipH || ps.flipV)) {
            m.setScale(ps.flipH ? -1f : 1f, ps.flipV ? -1f : 1f);
        }
        m.postConcat(worldMatrix(part));
        return m;
    }

    /** Maps a view-space touch into the selected part's CELL space, or null. */
    @Nullable
    private float[] viewToCell(@NonNull AvatarRig.Part part, float vx, float vy) {
        float[] box = partBox(part);
        if (box == null || box[0] <= 0 || box[1] <= 0) return null;
        if (!fullPartMatrix(part).invert(invMatrix)) return null;
        float[] pt = {vx, vy};
        invMatrix.mapPoints(pt);
        return new float[]{(pt[0] - box[2]) / box[0], (pt[1] - box[3]) / box[1]};
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // A6 pin mode takes priority over pose-drag while active.
        if (pinEditing != null && selectedPartId != null && rig != null) {
            AvatarRig.Part part = rig.partById(selectedPartId);
            if (part != null && handlePinTouch(e, part)) return true;
        }
        if (!dragEnabled || selectedPartId == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                lastX = e.getX();
                lastY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                if (listener != null && getWidth() > 0 && getHeight() > 0) {
                    listener.onPartDragged(dx / getWidth(), dy / getHeight());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
        }
        return false;
    }

    /** Pin-mode gesture: drag a handle to move it, tap empty part art to add. */
    private boolean handlePinTouch(@NonNull MotionEvent e, @NonNull AvatarRig.Part part) {
        List<float[]> pins = pinEditing;
        if (pins == null) return false;
        float slop = 28f * getResources().getDisplayMetrics().density / 2f;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pinDownX = e.getX();
                pinDownY = e.getY();
                pinMoved = false;
                draggingPin = -1;
                float[] box = partBox(part);
                if (box == null) return false;
                Matrix m = fullPartMatrix(part);
                for (int i = 0; i < pins.size(); i++) {
                    float[] pt = {box[2] + pins.get(i)[0] * box[0],
                                  box[3] + pins.get(i)[1] * box[1]};
                    m.mapPoints(pt);
                    if (Math.hypot(pt[0] - e.getX(), pt[1] - e.getY()) <= slop * 2) {
                        draggingPin = i;
                        break;
                    }
                }
                float[] cell = viewToCell(part, e.getX(), e.getY());
                boolean insideArt = cell != null
                        && cell[0] >= -0.05f && cell[0] <= 1.05f
                        && cell[1] >= -0.05f && cell[1] <= 1.05f;
                if (draggingPin >= 0 || insideArt) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.hypot(e.getX() - pinDownX, e.getY() - pinDownY) > 8) pinMoved = true;
                if (draggingPin >= 0 && draggingPin < pins.size() && pinListener != null) {
                    float[] cell = viewToCell(part, e.getX(), e.getY());
                    if (cell != null) {
                        pinListener.onPinMove(draggingPin,
                                clamp01(cell[0]), clamp01(cell[1]));
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!pinMoved && draggingPin < 0 && pinListener != null) {
                    float[] cell = viewToCell(part, e.getX(), e.getY());
                    if (cell != null) {
                        pinListener.onPinAdd(clamp01(cell[0]), clamp01(cell[1]));
                        invalidate();
                    }
                }
                draggingPin = -1;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                draggingPin = -1;
                return true;
        }
        return false;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        // center crosshair so "neutral" has a visible home
        canvas.drawLine(w / 2f, 0, w / 2f, h, bgGrid);
        canvas.drawLine(0, h / 2f, w, h / 2f, bgGrid);
        if (rig == null || resolved == null) return;

        // A6 dangle preamble: step every dangle chain exactly once this frame.
        long now = System.nanoTime();
        float dt = lastFrameNanos > 0 ? (now - lastFrameNanos) / 1e9f : 1f / 60f;
        lastFrameNanos = now;
        frameDanglePins.clear();
        boolean anyDangle = false;
        for (AvatarRig.Part part : rig.getParts()) {
            if (!part.dangle) continue;
            PuppetPoseResolver.PartState ps = resolved.get(part.id);
            if (ps == null) continue;
            java.util.List<float[]> pins = danglePins(part, ps, dt);
            if (pins != null) {
                frameDanglePins.put(part.id, pins);
                anyDangle = true;
            }
        }
        if (anyDangle) postInvalidateOnAnimation();

        // Draw order: resolved z ascending, stable on the rig's part order.
        List<AvatarRig.Part> order = new ArrayList<>(rig.getParts());
        java.util.Collections.sort(order, (a, b) -> {
            PuppetPoseResolver.PartState sa = resolved.get(a.id), sb = resolved.get(b.id);
            return Integer.compare(sa != null ? sa.z : a.z, sb != null ? sb.z : b.z);
        });
        for (AvatarRig.Part part : order) {
            PuppetPoseResolver.PartState ps = resolved.get(part.id);
            if (ps == null) continue;
            workMatrix.set(worldMatrix(part));
            drawPart(canvas, part, ps, workMatrix);
        }

        // A6 pin authoring overlay: handles + chain links for the edited list,
        // drawn in VIEW space so handle size stays finger-sized at any part scale.
        if (pinEditing != null && selectedPartId != null) {
            AvatarRig.Part part = rig.partById(selectedPartId);
            float[] box = part != null ? partBox(part) : null;
            if (part != null && box != null && box[0] > 0) {
                Matrix m = fullPartMatrix(part);

                // Mesh wireframe (user-requested "why does it bend like that" view):
                // the LIVE warp grid, mapped local→view. Only when the part warps.
                PuppetPoseResolver.PartState mps = resolved.get(part.id);
                float[] mverts = mps != null ? warpVertsFor(part, mps, box[0], box[1]) : null;
                if (mverts != null) {
                    m.mapPoints(mverts);
                    int segs = segmentsOf(mverts);
                    for (int r = 0; r <= segs; r++) {
                        canvas.drawLine(mverts[r * 4], mverts[r * 4 + 1],
                                mverts[r * 4 + 2], mverts[r * 4 + 3], pinLink);
                        if (r < segs) {
                            canvas.drawLine(mverts[r * 4], mverts[r * 4 + 1],
                                    mverts[r * 4 + 4], mverts[r * 4 + 5], pinLink);
                            canvas.drawLine(mverts[r * 4 + 2], mverts[r * 4 + 3],
                                    mverts[r * 4 + 6], mverts[r * 4 + 7], pinLink);
                        }
                    }
                }
                float density = getResources().getDisplayMetrics().density;
                float radius = 9f * density;
                float prevX = 0, prevY = 0;
                for (int i = 0; i < pinEditing.size(); i++) {
                    float[] pt = {box[2] + pinEditing.get(i)[0] * box[0],
                                  box[3] + pinEditing.get(i)[1] * box[1]};
                    m.mapPoints(pt);
                    if (i > 0) canvas.drawLine(prevX, prevY, pt[0], pt[1], pinLink);
                    prevX = pt[0];
                    prevY = pt[1];
                }
                for (int i = 0; i < pinEditing.size(); i++) {
                    float[] pt = {box[2] + pinEditing.get(i)[0] * box[0],
                                  box[3] + pinEditing.get(i)[1] * box[1]};
                    m.mapPoints(pt);
                    canvas.drawCircle(pt[0], pt[1], radius, pinFill);
                    canvas.drawCircle(pt[0], pt[1], radius, pinRing);
                    missingText.setColor(0xFF15151A);
                    canvas.drawText(String.valueOf(i + 1), pt[0],
                            pt[1] + missingText.getTextSize() / 3f, missingText);
                    missingText.setColor(0xFFE040FB);
                }
            }
        }
    }

    /** parent∘child world transform for a part, from resolved per-part states.
     *  Cycle-guarded: a parent chain longer than the part count is broken off
     *  (dead/looped parent = treated as root, per the plan's orphan tolerance). */
    @NonNull
    private Matrix worldMatrix(@NonNull AvatarRig.Part part) {
        Matrix m = new Matrix();
        localInto(m, part);
        AvatarRig.Part cursor = rig != null ? rig.partById(part.parentId) : null;
        int hops = 0;
        int max = rig != null ? rig.getParts().size() : 0;
        while (cursor != null && hops++ < max) {
            Matrix pm = new Matrix();
            localInto(pm, cursor);
            m.postConcat(pm);
            cursor = rig.partById(cursor.parentId);
        }
        return m;
    }

    /** A part's LOCAL transform about its anchor: translate to resolved offset,
     *  rotate/scale around the anchor point. Roots are homed at canvas center. */
    private void localInto(@NonNull Matrix m, @NonNull AvatarRig.Part part) {
        PuppetPoseResolver.PartState ps = resolved != null ? resolved.get(part.id) : null;
        float px = ps != null ? ps.x : 0f;
        float py = ps != null ? ps.y : 0f;
        float sc = ps != null ? ps.scale : 1f;
        float rot = ps != null ? ps.rotationDeg : 0f;
        m.reset();
        m.postScale(sc, sc);
        m.postRotate(rot);
        boolean isRoot = part.parentId == null || (rig != null && rig.partById(part.parentId) == null);
        float homeX = isRoot ? getWidth() / 2f : 0f;
        float homeY = isRoot ? getHeight() / 2f : 0f;
        m.postTranslate(homeX + px * getWidth(), homeY + py * getHeight());
    }

    private void drawPart(@NonNull Canvas canvas, @NonNull AvatarRig.Part part,
                          @NonNull PuppetPoseResolver.PartState ps, @NonNull Matrix world) {
        SpriteSheetRenderer r = rendererLookup != null ? rendererLookup.apply(part.sheetId) : null;
        float shortSide = Math.min(getWidth(), getHeight());
        float dw = shortSide * PART_BASE_FRACTION;
        float aspect = r != null ? r.cellAspect() : 1f;
        float dh = aspect > 0 ? dw / aspect : dw;
        float ax = part.anchorX != null ? part.anchorX
                : (r != null ? sheetPivotX(part) : 0.5f);
        float ay = part.anchorY != null ? part.anchorY
                : (r != null ? sheetPivotY(part) : 0.5f);
        workRect.set(-ax * dw, -ay * dh, (1f - ax) * dw, (1f - ay) * dh);

        canvas.save();
        canvas.concat(world);
        if (ps.flipH || ps.flipV) {
            canvas.scale(ps.flipH ? -1f : 1f, ps.flipV ? -1f : 1f);
        }
        if (r != null) {
            // A6: warp when the part carries a valid rest chain and the resolver
            // supplied matching posed pins; anything off-contract = rigid draw.
            float[] verts = warpVertsFor(part, ps, dw, dh);
            long[] swap = swapAtMs.get(part.id);
            float fade = crossfadeAlpha(swap);
            if (verts != null) {
                int meshH = segmentsOf(verts); // matches whatever built these verts
                android.graphics.Bitmap cell = cellBitmapFor(part.sheetId, r, ps.cellIndex);
                if (cell != null) {
                    warpPaint.setAlpha(255);
                    canvas.drawBitmapMesh(cell, 1, meshH, verts, 0, null, 0, warpPaint);
                    // Pin-snap crossfade: the OLD cell rides the SAME verts, so the
                    // swap happens over identical geometry (plan §Pin-warp).
                    if (fade > 0f && swap != null) {
                        android.graphics.Bitmap prev =
                                cellBitmapFor(part.sheetId, r, (int) swap[1]);
                        if (prev != null) {
                            warpPaint.setAlpha(Math.round(fade * 255));
                            canvas.drawBitmapMesh(prev, 1, meshH, verts, 0, null, 0, warpPaint);
                            warpPaint.setAlpha(255);
                        }
                        postInvalidateOnAnimation();
                    }
                } else {
                    r.drawCell(canvas, ps.cellIndex, workRect, null);
                }
            } else {
                r.drawCell(canvas, ps.cellIndex, workRect, null);
                if (fade > 0f && swap != null) {
                    // Rigid parts crossfade too (same signal, same window).
                    warpPaint.setAlpha(Math.round(fade * 255));
                    r.drawCell(canvas, (int) swap[1], workRect, warpPaint);
                    warpPaint.setAlpha(255);
                    postInvalidateOnAnimation();
                }
            }
        } else {
            canvas.drawRect(workRect, missingPaint);
            canvas.drawText(part.id + "?", workRect.centerX(),
                    workRect.centerY() + missingText.getTextSize() / 3f, missingText);
        }
        if (part.id.equals(selectedPartId)) {
            canvas.drawRect(workRect, selectPaint);
        }
        canvas.restore();
    }

    /** Fading weight of a crossfade window, 0 when absent/expired. */
    private float crossfadeAlpha(@Nullable long[] swap) {
        if (swap == null) return 0f;
        long age = android.os.SystemClock.uptimeMillis() - swap[0];
        if (age >= CROSSFADE_MS) return 0f;
        return 1f - age / (float) CROSSFADE_MS;
    }

    /**
     * A6 warp verts for a part in its LOCAL draw space (the same space workRect
     * lives in — the world matrix is already on the canvas). Posed pins are
     * item-normalized (resolver contract) and map into the part's cell box; the
     * rest chain comes from {@link AvatarRig.Part#restPins}. Returns null on any
     * contract violation → rigid fallback.
     */
    /**
     * A6 dangle: for a dangle-tagged part, the verlet chain (anchored at the
     * part's first POSED pin in view space — the anchor's own motion is the
     * excitation) overrides the resolved pins. Returns cell-space pins, or null
     * when dangle doesn't apply (untagged / no chain / pin-editing this part —
     * physics fighting the user's drag would be maddening).
     */
    @Nullable
    private java.util.List<float[]> danglePins(@NonNull AvatarRig.Part part,
                                               @NonNull PuppetPoseResolver.PartState ps,
                                               float dtSeconds) {
        if (!part.dangle) return null;
        if (part.restPins.size() < PinWarpStrip.MIN_PINS) return null;
        // Unposed rig (no cell carries pins → resolver emits none): the rest chain
        // IS the pose. Without this, a freshly-authored dangle part is silently
        // inert until the user arms and poses a cell — an authoring dead-end.
        java.util.List<float[]> resolvedPins = ps.pins.isEmpty() ? part.restPins : ps.pins;
        if (resolvedPins.size() != part.restPins.size()) return null;
        if (pinEditing != null && part.id.equals(selectedPartId)) return null;
        float[] box = partBox(part);
        if (box == null || box[0] <= 0 || box[1] <= 0) return null;
        Matrix m = fullPartMatrix(part);
        if (!m.invert(invMatrix)) return null;

        DangleSim sim = dangleSims.get(part.id);
        if (sim == null || sim.nodeCount() != part.restPins.size()) {
            java.util.List<float[]> restPx = new java.util.ArrayList<>();
            for (float[] pin : part.restPins) {
                restPx.add(new float[]{pin[0] * box[0], pin[1] * box[1]});
            }
            sim = new DangleSim(restPx);
            dangleSims.put(part.id, sim);
        }
        float[] anchor = {box[2] + resolvedPins.get(0)[0] * box[0],
                          box[3] + resolvedPins.get(0)[1] * box[1]};
        m.mapPoints(anchor);
        sim.step(anchor[0], anchor[1], dtSeconds);

        java.util.List<float[]> cellPins = new java.util.ArrayList<>(sim.nodeCount());
        float[] pt = new float[2];
        for (int i = 0; i < sim.nodeCount(); i++) {
            pt[0] = sim.nodeX(i);
            pt[1] = sim.nodeY(i);
            invMatrix.mapPoints(pt);
            cellPins.add(new float[]{(pt[0] - box[2]) / box[0], (pt[1] - box[3]) / box[1]});
        }
        return cellPins;
    }

    @Nullable
    private float[] warpVertsFor(@NonNull AvatarRig.Part part,
                                 @NonNull PuppetPoseResolver.PartState ps,
                                 float dw, float dh) {
        if (part.restPins.size() < PinWarpStrip.MIN_PINS) return null;
        // Unposed rig fallback: rest chain = pose (identity warp) so freshly
        // authored pins are immediately live — mirrors danglePins' rule.
        java.util.List<float[]> resolvedPins = ps.pins.isEmpty() ? part.restPins : ps.pins;
        if (resolvedPins.size() != part.restPins.size()) return null;
        // Self-contained box origin (NOT the shared workRect, which holds the
        // LAST drawn part's rect — the mesh-overlay pass calls this outside
        // drawPart and would otherwise warp against a stale origin).
        float[] box = partBox(part);
        if (box == null || box[0] <= 0 || box[1] <= 0) return null;
        // A6 dangle: this frame's simulated chain (stepped once in onDraw's
        // preamble) overrides the resolver's pins for dangle-tagged parts.
        java.util.List<float[]> srcPins = frameDanglePins.get(part.id);
        if (srcPins == null) srcPins = resolvedPins;
        java.util.List<float[]> posed = new java.util.ArrayList<>(srcPins.size());
        for (float[] pin : srcPins) {
            posed.add(new float[]{
                    box[2] + pin[0] * dw,
                    box[3] + pin[1] * dh});
        }
        return PinWarpStrip.buildMeshVerts(part.restPins, posed, dw, segmentsFor(part));
    }

    /** Decode-once cell bitmap for mesh drawing (a cell is a sheet sub-rect). */
    @Nullable
    private android.graphics.Bitmap cellBitmapFor(@NonNull String sheetId,
                                                  @NonNull SpriteSheetRenderer r,
                                                  int cellIndex) {
        String key = sheetId + "/" + cellIndex;
        if (cellBitmaps.containsKey(key)) {
            android.graphics.Bitmap cached = cellBitmaps.get(key);
            // null stays cached (failed extraction — no per-frame retry storm).
            return cached != null && !cached.isRecycled() ? cached : null;
        }
        try {
            android.graphics.Rect src = r.cellRectBitmap(cellIndex);
            if (src.width() <= 0 || src.height() <= 0) return null;
            android.graphics.Bitmap cell = android.graphics.Bitmap.createBitmap(
                    r.getBitmap(), src.left, src.top, src.width(), src.height());
            cellBitmaps.put(key, cell);
            return cell;
        } catch (RuntimeException e) {
            cellBitmaps.put(key, null); // no per-frame retry storm
            return null;
        }
    }

    private float sheetPivotX(@NonNull AvatarRig.Part part) {
        com.fadcam.ui.faditor.sprite.SpriteSheet s = sheetOf(part);
        return s != null ? s.getPivotX() : 0.5f;
    }

    private float sheetPivotY(@NonNull AvatarRig.Part part) {
        com.fadcam.ui.faditor.sprite.SpriteSheet s = sheetOf(part);
        return s != null ? s.getPivotY() : 0.5f;
    }

    @Nullable private java.util.function.Function<String, com.fadcam.ui.faditor.sprite.SpriteSheet> sheetLookup;

    /** Activity supplies sheet lookup so pivots fall back correctly. */
    public void setSheetLookup(
            @Nullable java.util.function.Function<String, com.fadcam.ui.faditor.sprite.SpriteSheet> f) {
        this.sheetLookup = f;
    }

    @Nullable
    private com.fadcam.ui.faditor.sprite.SpriteSheet sheetOf(@NonNull AvatarRig.Part part) {
        return sheetLookup != null ? sheetLookup.apply(part.sheetId) : null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (android.graphics.Bitmap b : cellBitmaps.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        cellBitmaps.clear();
    }
}
