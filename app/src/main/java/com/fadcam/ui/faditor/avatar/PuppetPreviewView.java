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
 * <p>Scaffold scope: Canvas + rigid parts (no pin warp — that's the limb
 * phase's GL renderer), followWeight treated as full inheritance (=1) with the
 * attenuated path deferred to the tracking phase. Missing sheet art draws the
 * S7-style MISSING placeholder, never crashes.</p>
 *
 * <p>When an armed cell + selected part are set, one-finger drag on the canvas
 * reports normalized deltas so the activity can pose that part in the cell.</p>
 */
public class PuppetPreviewView extends View {

    /** A root part's neutral width as a fraction of the canvas' short side. */
    private static final float PART_BASE_FRACTION = 0.34f;

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
    private final Matrix workMatrix = new Matrix();
    private final RectF workRect = new RectF();

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
        invalidate();
    }

    /** New resolved state from the activity's resolve pass. */
    public void setResolved(@Nullable Map<String, PuppetPoseResolver.PartState> r) {
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
            r.drawCell(canvas, ps.cellIndex, workRect, null);
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
}
