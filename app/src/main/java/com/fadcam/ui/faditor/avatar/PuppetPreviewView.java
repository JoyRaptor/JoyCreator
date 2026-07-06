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
    /** Warp mesh bands — plenty at puppet scale (plan: ~8–20 triangles/limb). */
    private static final int WARP_SEGMENTS = 10;
    /** Pin-snap crossfade window after a discrete cell swap (plan §Pin-warp). */
    private static final long CROSSFADE_MS = 130;

    public interface Listener {
        /** Drag while a cell is armed: deltas normalized to the view's size. */
        void onPartDragged(float dxNorm, float dyNorm);
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

    private float lastX, lastY;
    private boolean dragging = false;

    public PuppetPreviewView(Context ctx) {
        super(ctx);
        setBackgroundColor(0xFF15151A);
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

    @Override
    public boolean onTouchEvent(MotionEvent e) {
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

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        // center crosshair so "neutral" has a visible home
        canvas.drawLine(w / 2f, 0, w / 2f, h, bgGrid);
        canvas.drawLine(0, h / 2f, w, h / 2f, bgGrid);
        if (rig == null || resolved == null) return;

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
                android.graphics.Bitmap cell = cellBitmapFor(part.sheetId, r, ps.cellIndex);
                if (cell != null) {
                    warpPaint.setAlpha(255);
                    canvas.drawBitmapMesh(cell, 1, WARP_SEGMENTS, verts, 0, null, 0, warpPaint);
                    // Pin-snap crossfade: the OLD cell rides the SAME verts, so the
                    // swap happens over identical geometry (plan §Pin-warp).
                    if (fade > 0f && swap != null) {
                        android.graphics.Bitmap prev =
                                cellBitmapFor(part.sheetId, r, (int) swap[1]);
                        if (prev != null) {
                            warpPaint.setAlpha(Math.round(fade * 255));
                            canvas.drawBitmapMesh(prev, 1, WARP_SEGMENTS, verts, 0, null, 0, warpPaint);
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
    @Nullable
    private float[] warpVertsFor(@NonNull AvatarRig.Part part,
                                 @NonNull PuppetPoseResolver.PartState ps,
                                 float dw, float dh) {
        if (part.restPins.size() < PinWarpStrip.MIN_PINS) return null;
        if (ps.pins.size() != part.restPins.size()) return null;
        java.util.List<float[]> posed = new java.util.ArrayList<>(ps.pins.size());
        for (float[] pin : ps.pins) {
            posed.add(new float[]{
                    workRect.left + pin[0] * dw,
                    workRect.top + pin[1] * dh});
        }
        return PinWarpStrip.buildMeshVerts(part.restPins, posed, dw, WARP_SEGMENTS);
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
