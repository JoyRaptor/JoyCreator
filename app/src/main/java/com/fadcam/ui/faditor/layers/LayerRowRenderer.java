package com.fadcam.ui.faditor.layers;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns ALL rendering, measurement and header-touch hit-testing for the M6 multi-row
 * timeline UI driven by the schema-v8 Track model (PLAN Part 6 + Part 7, row M6).
 *
 * <p><b>Extract-on-touch (PLAN §1.2(E)):</b> {@code EditorTimelineView} (4,881+ lines) must
 * NOT grow with row-rendering feature logic. It delegates here via a handful of thin hook
 * calls (measure/draw/touch) and this class owns everything else: paints, row geometry,
 * collapse/hide/lock/mute icon hit-zones, and the small vertical scroll used to keep the
 * pinned rows above always visible while extra layer/audio rows scroll beneath them.</p>
 *
 * <p><b>What this does NOT do (out of scope for M6):</b> moving/trimming/deleting items on
 * a row (M7); drag-between-layers or creating new tracks (M10); any preview/export effect
 * — hidden/locked/muted only affect how this class renders/hit-tests THIS row (PLAN Part 7
 * M6 scope item 4); wiring those into playback is M-COMP-1 / M-EXPORT-1 (see the TODOs
 * below at each toggle site).</p>
 *
 * <p><b>Plain single-track projects are unaffected:</b> {@link #layout} is a no-op (draws
 * nothing, adds zero height, hit-tests nothing) whenever both {@code layers} and
 * {@code audioTracks} are empty — exactly the case for a project with no extra Track beyond
 * the auto-migrated master (PLAN Part 7 M6 scope item 6; DESIGN §5 "never bury the
 * timeline"). The existing read-only VIZ/CC/overlay rows drawn by
 * {@code EditorTimelineView#drawLayers} are untouched by this class (PLAN §1.2(E) allows
 * leaving them if re-routing is invasive — it is, given their tight coupling to that view's
 * own selection/drag state; see the M6 build report for the full rationale).</p>
 */
public final class LayerRowRenderer {

    // ── Row geometry (dp) ───────────────────────────────────────────
    private static final float HEADER_WIDTH_DP = 92f;
    private static final float ROW_HEIGHT_EXPANDED_DP = 34f;
    private static final float ROW_HEIGHT_COLLAPSED_DP = 14f;
    private static final float ROW_GAP_DP = 3f;
    private static final float TOP_GAP_DP = 6f;
    private static final float ICON_SIZE_DP = 12f;
    private static final float ICON_GAP_DP = 4f;
    private static final float CARET_SIZE_DP = 6f;
    /** Cap the visible height of the scrollable layer/audio-row region (rest scrolls). */
    private static final float MAX_VISIBLE_ROWS_DP = 140f;

    // ── Colors (frosted dark glass — DESIGN §5) ─────────────────────
    private static final int COLOR_HEADER_BG      = 0x99141420; // semi-transparent dark
    private static final int COLOR_HEADER_BG_LOCK = 0x99201414;
    private static final int COLOR_ROW_BODY_BG    = 0x661A1A24;
    private static final int COLOR_ROW_NAME       = 0xFFEDEDED;
    private static final int COLOR_ICON_ON        = 0xFFFFFFFF;
    private static final int COLOR_ICON_OFF       = 0x66FFFFFF;
    private static final int COLOR_ITEM_TEXT      = 0xDD8C3DFA;   // purple (TEXT/STICKER)
    private static final int COLOR_ITEM_VIDEO     = 0xDD4397FD;   // blue (VIDEO/IMAGE)
    private static final int COLOR_ITEM_AUDIO     = 0xDD35F6BF;   // aqua (AUDIO)
    private static final int COLOR_ITEM_HIDDEN    = 0x552A2A2A;   // dimmed/ghosted
    private static final int COLOR_STRIP          = 0x99CC27FF;   // collapsed summary strip

    /** Which header icon zone a touch landed on. */
    public enum HitZone { CARET, HIDE, LOCK, MUTE, NONE }

    /** Result of a header hit-test: which track + which zone. */
    public static final class HeaderHit {
        @NonNull public final Track track;
        @NonNull public final HitZone zone;
        HeaderHit(@NonNull Track track, @NonNull HitZone zone) {
            this.track = track; this.zone = zone;
        }
    }

    /** Which part of an item block a touch landed on (M7). */
    public enum ItemZone { BODY, LEFT_HANDLE, RIGHT_HANDLE }

    /** Result of a row-BODY item hit-test (M7): which track + item + zone. */
    public static final class ItemHit {
        @NonNull public final Track track;
        @NonNull public final TimedItem item;
        @NonNull public final ItemZone zone;
        ItemHit(@NonNull Track track, @NonNull TimedItem item, @NonNull ItemZone zone) {
            this.track = track; this.item = item; this.zone = zone;
        }
    }

    private final float density;
    private final Paint headerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowBodyBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint itemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint itemLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path caretPath = new Path();
    private final Path mutePath = new Path();

    /** Rows laid out on the last {@link #layout} call, top-to-bottom, for draw/hit-test. */
    private final List<RowLayout> rows = new ArrayList<>();

    /** Vertical scroll offset (px) within the capped-height row region. */
    private float scrollOffsetPx = 0f;
    private float contentHeightPx = 0f;
    private float viewportHeightPx = 0f;

    private static final class RowLayout {
        final Track track;
        /** True if this row came from the {@code layers} (floating) list, false if {@code audioTracks} (M10). */
        final boolean floatingBand;
        final RectF headerRect = new RectF();
        final RectF bodyRect = new RectF();
        final RectF caretRect = new RectF();
        final RectF hideRect = new RectF();
        final RectF lockRect = new RectF();
        final RectF muteRect = new RectF();
        RowLayout(Track t, boolean floatingBand) { track = t; this.floatingBand = floatingBand; }
    }

    /** Height (px) of the "drop here to create a new layer" zone drawn below the last row during a drag (M10). */
    private static final float NEW_LAYER_ZONE_DP = 30f;
    private static final int COLOR_NEW_LAYER_ZONE = 0x448C3DFA;
    private static final int COLOR_NEW_LAYER_ZONE_ARMED = 0xAA8C3DFA;
    private static final int COLOR_DROP_TARGET_RING = 0xFFFFFFFF;
    private final RectF newLayerZoneRect = new RectF();
    private final Paint dropTargetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LayerRowRenderer(float density) {
        this.density = density;
        headerBgPaint.setStyle(Paint.Style.FILL);
        rowBodyBgPaint.setStyle(Paint.Style.FILL);
        namePaint.setColor(COLOR_ROW_NAME);
        namePaint.setTextSize(10f * density);
        namePaint.setTypeface(Typeface.DEFAULT_BOLD);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(1.6f * density);
        itemPaint.setStyle(Paint.Style.FILL);
        itemLabelPaint.setTextSize(9f * density);
        itemLabelPaint.setColor(0xFFFFFFFF);
        stripPaint.setStyle(Paint.Style.FILL);
        dropTargetPaint.setStyle(Paint.Style.STROKE);
        dropTargetPaint.setStrokeWidth(2f * density);
        dropTargetPaint.setColor(COLOR_DROP_TARGET_RING);
    }

    /** True when there is nothing to draw (plain single-track project — PLAN scope item 6). */
    public boolean isEmpty(@NonNull List<Track> layers, @NonNull List<Track> audioTracks) {
        return layers.isEmpty() && audioTracks.isEmpty();
    }

    /**
     * Total extra height (px) this renderer needs below the existing timeline content.
     * Called from {@code EditorTimelineView#onMeasure}. Zero for a plain project.
     */
    public float measureExtraHeightPx(@NonNull List<Track> layers, @NonNull List<Track> audioTracks) {
        if (isEmpty(layers, audioTracks)) return 0f;
        float rowGap = ROW_GAP_DP * density;
        float total = 0f;
        for (Track t : layers) total += rowHeightPx(t) + rowGap;
        for (Track t : audioTracks) total += rowHeightPx(t) + rowGap;
        float capped = Math.min(total, MAX_VISIBLE_ROWS_DP * density);
        return TOP_GAP_DP * density + capped;
    }

    private float rowHeightPx(@NonNull Track t) {
        return (t.isCollapsed() ? ROW_HEIGHT_COLLAPSED_DP : ROW_HEIGHT_EXPANDED_DP) * density;
    }

    /**
     * Lay out + draw every layer/audio row starting at {@code topPx}, within a viewport
     * capped at {@link #MAX_VISIBLE_ROWS_DP} (extra rows scroll — this is the "master row
     * stays pinned; layer/audio rows scroll beneath it" behavior from PLAN §6.1: the master
     * row and all existing timeline content live entirely above {@code topPx} and are never
     * part of this scrolling region).
     *
     * <p>Called from inside the god view's {@code canvas.translate(-hScrollOffsetPx, 0)}
     * content-space block, so item bodies (which must line up with {@code timeToX}) are
     * laid out directly in that space, but the row HEADER column (name/caret/hide/lock/
     * mute — {@code hScrollOffsetPx} wide) is counter-translated back to screen-space so it
     * stays pinned to the left edge like the rest of the timeline's left gutter, instead of
     * scrolling out of view with the timeline content.</p>
     *
     * @param widthPx        viewport (screen) width — the header column is pinned within it.
     * @param hScrollOffsetPx the god view's current horizontal content scroll offset.
     * @param timeToX        maps absolute timeline ms to on-canvas (content-space) x (reuses
     *                       the god view's exact segment-aware mapping).
     * @param totalMs        timeline total duration, for items with an unbounded end.
     */
    public void layout(@NonNull Canvas canvas, @NonNull List<Track> layers,
                        @NonNull List<Track> audioTracks, float topPx, float widthPx,
                        float hScrollOffsetPx, long totalMs, @NonNull TimeToX timeToX) {
        layout(canvas, layers, audioTracks, topPx, widthPx, hScrollOffsetPx, totalMs, timeToX, false, false);
    }

    /**
     * M10 overload: {@code dragActive} draws the "drop here to create a new layer" zone
     * below the last row (PLAN Part 7 row M10 scope 2); {@code dragOverNewLayerZone}
     * highlights it as armed (finger currently over it) vs merely visible.
     */
    public void layout(@NonNull Canvas canvas, @NonNull List<Track> layers,
                        @NonNull List<Track> audioTracks, float topPx, float widthPx,
                        float hScrollOffsetPx, long totalMs, @NonNull TimeToX timeToX,
                        boolean dragActive, boolean dragOverNewLayerZone) {
        rows.clear();
        newLayerZoneRect.setEmpty();
        if (isEmpty(layers, audioTracks)) { contentHeightPx = 0f; return; }

        float rowGap = ROW_GAP_DP * density;
        float y = TOP_GAP_DP * density;
        for (Track t : layers) y = addRow(t, y, hScrollOffsetPx, widthPx, rowGap, true);
        for (Track t : audioTracks) y = addRow(t, y, hScrollOffsetPx, widthPx, rowGap, false);
        if (dragActive) {
            float zoneH = NEW_LAYER_ZONE_DP * density;
            newLayerZoneRect.set(hScrollOffsetPx + HEADER_WIDTH_DP * density, y,
                    hScrollOffsetPx + widthPx, y + zoneH);
            y += zoneH + rowGap;
        }
        contentHeightPx = y;
        viewportHeightPx = Math.min(contentHeightPx, MAX_VISIBLE_ROWS_DP * density);
        scrollOffsetPx = clampScroll(scrollOffsetPx);

        canvas.save();
        canvas.clipRect(hScrollOffsetPx, topPx, hScrollOffsetPx + widthPx, topPx + viewportHeightPx);
        canvas.translate(0f, topPx - scrollOffsetPx);
        for (RowLayout row : rows) {
            drawRow(canvas, row, totalMs, timeToX);
        }
        if (dragActive && !newLayerZoneRect.isEmpty()) {
            drawNewLayerZone(canvas, dragOverNewLayerZone);
        }
        canvas.restore();
    }

    private void drawNewLayerZone(@NonNull Canvas canvas, boolean armed) {
        stripPaint.setColor(armed ? COLOR_NEW_LAYER_ZONE_ARMED : COLOR_NEW_LAYER_ZONE);
        canvas.drawRoundRect(newLayerZoneRect, 4f * density, 4f * density, stripPaint);
        if (armed) {
            canvas.drawRoundRect(newLayerZoneRect, 4f * density, 4f * density, dropTargetPaint);
        }
        String label = "+ New layer";
        float ty = newLayerZoneRect.centerY() + itemLabelPaint.getTextSize() / 3f;
        itemLabelPaint.setColor(0xFFFFFFFF);
        canvas.drawText(label, newLayerZoneRect.left + 8f * density, ty, itemLabelPaint);
    }

    /**
     * @param hScrollOffsetPx the god view's horizontal content scroll offset, used so the
     *                        header rect is stored in the SAME content-space coordinates
     *                        the caller hit-tests against (see {@link #hitTestHeader}) even
     *                        though it is drawn pinned to the screen edge.
     */
    private float addRow(@NonNull Track t, float y, float hScrollOffsetPx, float widthPx,
                          float rowGap, boolean floatingBand) {
        RowLayout row = new RowLayout(t, floatingBand);
        float h = rowHeightPx(t);
        row.headerRect.set(hScrollOffsetPx, y, hScrollOffsetPx + HEADER_WIDTH_DP * density, y + h);
        row.bodyRect.set(hScrollOffsetPx + HEADER_WIDTH_DP * density, y, hScrollOffsetPx + widthPx, y + h);
        layoutHeaderIcons(row, h);
        rows.add(row);
        return y + h + rowGap;
    }

    private void layoutHeaderIcons(@NonNull RowLayout row, float rowH) {
        float iconSize = ICON_SIZE_DP * density;
        float gap = ICON_GAP_DP * density;
        float cy = row.headerRect.top + rowH / 2f;
        float caretCx = row.headerRect.left + iconSize * 0.7f;
        row.caretRect.set(caretCx - iconSize / 2f, cy - iconSize / 2f,
                caretCx + iconSize / 2f, cy + iconSize / 2f);
        // Hide/lock/mute icons right-aligned in the header, evenly spaced.
        float right = row.headerRect.right - gap;
        row.muteRect.set(right - iconSize, cy - iconSize / 2f, right, cy + iconSize / 2f);
        right -= iconSize + gap;
        row.lockRect.set(right - iconSize, cy - iconSize / 2f, right, cy + iconSize / 2f);
        right -= iconSize + gap;
        row.hideRect.set(right - iconSize, cy - iconSize / 2f, right, cy + iconSize / 2f);
    }

    private void drawRow(@NonNull Canvas canvas, @NonNull RowLayout row,
                          long totalMs, @NonNull TimeToX timeToX) {
        Track t = row.track;
        boolean collapsed = t.isCollapsed();

        headerBgPaint.setColor(t.isLocked() ? COLOR_HEADER_BG_LOCK : COLOR_HEADER_BG);
        canvas.drawRect(row.headerRect, headerBgPaint);
        rowBodyBgPaint.setColor(COLOR_ROW_BODY_BG);
        canvas.drawRect(row.bodyRect, rowBodyBgPaint);

        // Caret (collapse toggle) — right-pointing when collapsed, down when expanded.
        drawCaret(canvas, row.caretRect, collapsed);

        // Track name, clipped to the space between the caret and the icon cluster.
        canvas.save();
        canvas.clipRect(row.caretRect.right + 4f * density, row.headerRect.top,
                row.hideRect.left - 2f * density, row.headerRect.bottom);
        float ty = row.headerRect.centerY() + namePaint.getTextSize() / 3f;
        canvas.drawText(t.getName(), row.caretRect.right + 4f * density, ty, namePaint);
        canvas.restore();

        drawEyeIcon(canvas, row.hideRect, !t.isHidden());
        drawLockIcon(canvas, row.lockRect, t.isLocked());
        drawMuteIcon(canvas, row.muteRect, !t.isMuted(), t.getKind() == TrackKind.AUDIO
                || t.getKind() == TrackKind.VIDEO || t.getKind() == TrackKind.MASTER);

        if (collapsed) {
            drawCollapsedStrip(canvas, row, totalMs, timeToX);
        } else {
            drawExpandedItems(canvas, row, t, totalMs, timeToX);
        }

        // M10: highlight this row when a cross-row item drag is currently hovering it
        // (PLAN Part 7 row M10 scope 1 "item block follows the finger across rows").
        if (dragTargetTrackId != null && dragTargetTrackId.equals(t.getId())) {
            RectF ring = new RectF(row.bodyRect);
            ring.inset(1f * density, 1f * density);
            canvas.drawRoundRect(ring, 3f * density, 3f * density, dropTargetPaint);
        }
    }

    /** Id of the track row currently highlighted as a cross-row drag target, or null (M10). */
    @Nullable private String dragTargetTrackId;

    /** Set/clear which row (by track id) should render the drag-target highlight ring (M10). */
    public void setDragTargetTrackId(@Nullable String trackId) { this.dragTargetTrackId = trackId; }

    private void drawCaret(@NonNull Canvas canvas, @NonNull RectF r, boolean collapsed) {
        caretPath.reset();
        float cx = r.centerX(), cy = r.centerY();
        float s = CARET_SIZE_DP * density / 2f;
        if (collapsed) {
            caretPath.moveTo(cx - s * 0.6f, cy - s);
            caretPath.lineTo(cx + s * 0.8f, cy);
            caretPath.lineTo(cx - s * 0.6f, cy + s);
        } else {
            caretPath.moveTo(cx - s, cy - s * 0.6f);
            caretPath.lineTo(cx, cy + s * 0.8f);
            caretPath.lineTo(cx + s, cy - s * 0.6f);
        }
        caretPath.close();
        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setColor(COLOR_ICON_ON);
        canvas.drawPath(caretPath, iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
    }

    private void drawEyeIcon(@NonNull Canvas canvas, @NonNull RectF r, boolean visible) {
        iconPaint.setColor(visible ? COLOR_ICON_ON : COLOR_ICON_OFF);
        canvas.drawOval(r, iconPaint);
        canvas.drawCircle(r.centerX(), r.centerY(), r.width() * 0.16f, iconPaint);
        if (!visible) {
            canvas.drawLine(r.left, r.bottom, r.right, r.top, iconPaint);
        }
    }

    private void drawLockIcon(@NonNull Canvas canvas, @NonNull RectF r, boolean locked) {
        iconPaint.setColor(locked ? COLOR_ICON_ON : COLOR_ICON_OFF);
        float bodyTop = r.top + r.height() * 0.42f;
        canvas.drawRect(r.left, bodyTop, r.right, r.bottom, iconPaint);
        RectF shackle = new RectF(r.left + r.width() * 0.2f, r.top,
                r.right - r.width() * 0.2f, bodyTop + r.height() * 0.1f);
        canvas.drawArc(shackle, 180f, 180f, false, iconPaint);
    }

    private void drawMuteIcon(@NonNull Canvas canvas, @NonNull RectF r, boolean unmuted,
                               boolean applicable) {
        iconPaint.setColor(!applicable ? COLOR_ICON_OFF : (unmuted ? COLOR_ICON_ON : COLOR_ICON_OFF));
        float midY = r.centerY();
        canvas.drawRect(r.left, midY - r.height() * 0.18f, r.left + r.width() * 0.4f,
                midY + r.height() * 0.18f, iconPaint);
        mutePath.reset();
        mutePath.moveTo(r.left + r.width() * 0.4f, r.top);
        mutePath.lineTo(r.right, r.top);
        mutePath.lineTo(r.right, r.bottom);
        mutePath.lineTo(r.left + r.width() * 0.4f, r.bottom);
        mutePath.close();
        Paint.Style prev = iconPaint.getStyle();
        iconPaint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(mutePath, iconPaint);
        iconPaint.setStyle(prev);
        if (!unmuted && applicable) {
            canvas.drawLine(r.left, r.bottom, r.right, r.top, iconPaint);
        }
    }

    private void drawCollapsedStrip(@NonNull Canvas canvas, @NonNull RowLayout row,
                                     long totalMs, @NonNull TimeToX timeToX) {
        Track t = row.track;
        stripPaint.setColor(t.isHidden() ? COLOR_ITEM_HIDDEN : COLOR_STRIP);
        float midY = row.bodyRect.centerY();
        float h = 3f * density;
        for (TimedItem item : t.getItems()) {
            float x0 = timeToX.map(item.getTimelineStartMs());
            long dur = item.getDisplayDurationMs(totalMs);
            float x1 = Math.max(x0 + 2f * density, timeToX.map(item.getTimelineStartMs() + dur));
            canvas.drawRect(x0, midY - h / 2f, x1, midY + h / 2f, stripPaint);
        }
    }

    private void drawExpandedItems(@NonNull Canvas canvas, @NonNull RowLayout row,
                                    @NonNull Track t, long totalMs, @NonNull TimeToX timeToX) {
        int baseColor = baseColorFor(t.getKind());
        boolean ghosted = t.isHidden();
        float top = row.bodyRect.top + 3f * density;
        float bottom = row.bodyRect.bottom - 3f * density;
        for (TimedItem item : t.getItems()) {
            float x0 = timeToX.map(item.getTimelineStartMs());
            long dur = item.getDisplayDurationMs(totalMs);
            float x1 = Math.max(x0 + 6f * density, timeToX.map(item.getTimelineStartMs() + dur));
            itemPaint.setColor(ghosted ? COLOR_ITEM_HIDDEN : baseColor);
            canvas.drawRoundRect(x0, top, x1, bottom, 3f * density, 3f * density, itemPaint);
            String label = labelFor(item);
            if (label != null && !label.isEmpty()) {
                canvas.save();
                canvas.clipRect(x0, top, x1, bottom);
                itemLabelPaint.setColor(ghosted ? 0x88FFFFFF : 0xFFFFFFFF);
                canvas.drawText(label, x0 + 5f * density,
                        row.bodyRect.centerY() + itemLabelPaint.getTextSize() / 3f, itemLabelPaint);
                canvas.restore();
            }
        }
    }

    private int baseColorFor(@NonNull TrackKind kind) {
        switch (kind) {
            case TEXT:
            case STICKER:
                return COLOR_ITEM_TEXT;
            case AUDIO:
                return COLOR_ITEM_AUDIO;
            default:
                return COLOR_ITEM_VIDEO;
        }
    }

    @Nullable
    private String labelFor(@NonNull TimedItem item) {
        if (item.getTextOverlay() != null) {
            return item.getTextOverlay().isImage() ? "IMG" : item.getTextOverlay().getText();
        }
        if (item.getAudioClip() != null) return item.getAudioClip().getLabel();
        if (item.getClip() != null) return null; // master items already show thumbnails elsewhere
        return null;
    }

    // ── Touch (header toggles + collapse caret + row-region vertical scroll) ──

    /** Functional geometry bridge into the god view's segment-aware time→x mapping. */
    public interface TimeToX { float map(long timeMs); }

    /**
     * Hit-test a DOWN/tap at content coordinates (already scroll-adjusted for the
     * god view's own horizontal scroll) against the last-laid-out header icon zones.
     * Returns {@code null} if the tap is outside the row region or on a locked
     * track's body (locked tracks ignore taps entirely — PLAN Part 7 M6 scope item 4).
     */
    @Nullable
    public HeaderHit hitTestHeader(float x, float y, float topPx) {
        float localY = y - topPx + scrollOffsetPx;
        if (y < topPx || y > topPx + viewportHeightPx) return null;
        for (RowLayout row : rows) {
            if (localY < row.headerRect.top || localY > row.headerRect.bottom) continue;
            if (row.caretRect.contains(x, localY)) return new HeaderHit(row.track, HitZone.CARET);
            if (row.hideRect.contains(x, localY)) return new HeaderHit(row.track, HitZone.HIDE);
            if (row.lockRect.contains(x, localY)) return new HeaderHit(row.track, HitZone.LOCK);
            if (row.muteRect.contains(x, localY)) return new HeaderHit(row.track, HitZone.MUTE);
            return new HeaderHit(row.track, HitZone.NONE); // header hit, no icon — still consume
        }
        return null;
    }

    /**
     * True if {@code (x,y)} falls within the row region at all (used by the god view to
     * decide whether to route the touch here vs. its own segment/audio hit-testing).
     */
    public boolean isWithinRowRegion(float y, float topPx) {
        return !rows.isEmpty() && y >= topPx && y <= topPx + viewportHeightPx;
    }

    /** True if the row body under {@code y} belongs to a LOCKED track (taps must be ignored). */
    public boolean isRowLocked(float y, float topPx) {
        float localY = y - topPx + scrollOffsetPx;
        for (RowLayout row : rows) {
            if (localY >= row.headerRect.top && localY <= row.headerRect.bottom) {
                return row.track.isLocked();
            }
        }
        return false;
    }

    // ── M10: drag-between-layers + drop-to-new-layer geometry queries ─────────

    /**
     * The track whose ROW (header or body, expanded or collapsed) contains content-space
     * {@code y}, or {@code null} if {@code y} is outside every row (e.g. in the gap
     * between rows, or in the new-layer zone — use {@link #isWithinNewLayerZone} for
     * that). Used by {@link LayerGestureController} to find the cross-row drag target
     * as the finger moves, independent of lock/hidden state (the caller decides whether
     * a locked/hidden row is a valid drop target — PLAN Part 7 M10 scope 5: "no drags
     * in or out" of locked/hidden rows).
     */
    @Nullable
    public Track rowTrackAt(float y, float topPx) {
        float localY = y - topPx + scrollOffsetPx;
        for (RowLayout row : rows) {
            if (localY >= row.headerRect.top && localY <= row.bodyRect.bottom) {
                return row.track;
            }
        }
        return null;
    }

    /**
     * True if this track's row came from the {@code layers} (floating TEXT/STICKER/
     * IMAGE/VIDEO) band rather than {@code audioTracks} — used to decide whether a
     * cross-row drag is even eligible (an item can only move within its own band;
     * PLAN Part 7 row M10 does not scope cross-band moves) and which {@link TrackKind}
     * a same-band "new layer" drop should create.
     */
    public boolean isFloatingBandRow(@NonNull Track track) {
        for (RowLayout row : rows) {
            if (row.track.getId().equals(track.getId())) return row.floatingBand;
        }
        return true;
    }

    /**
     * True if content-space {@code y} falls within the "drop here to create a new
     * layer" zone drawn by {@link #layout} when {@code dragActive} was true (only
     * meaningful right after such a call — the zone rect is empty otherwise, so this
     * always returns false when no drag is active).
     */
    public boolean isWithinNewLayerZone(float y, float topPx) {
        if (newLayerZoneRect.isEmpty()) return false;
        float localY = y - topPx + scrollOffsetPx;
        return localY >= newLayerZoneRect.top && localY <= newLayerZoneRect.bottom;
    }

    /** Half-width (px) of an item's edge trim-handle hit-zone, shared with the item-hit-test. */
    private static final float ITEM_HANDLE_HALF_WIDTH_DP = 10f;

    /**
     * Hit-test a DOWN/tap at content coordinates against the item blocks drawn by
     * {@link #drawExpandedItems} in the last {@link #layout} call (M7; PLAN §6.3).
     * Only EXPANDED, unlocked, non-hidden rows are eligible — a collapsed row's thin
     * summary strip is not individually editable, a locked track ignores all row-body
     * gestures (PLAN Part 7 M7 scope item 3), and a hidden track's items are not
     * interactive (same scope item). Returns {@code null} when the touch is outside the
     * row region, on a track header, or misses every item's body/handle zone (a miss
     * inside the row body is still "within the row" — callers use
     * {@link #isWithinRowRegion} first to decide whether to fall through at all).
     *
     * @param selectedItemId when non-null, trim-handle zones are only hit-tested for
     *                       the item with this id (mirrors the existing audio/overlay
     *                       convention: handles only appear on the selected item).
     */
    @Nullable
    public ItemHit hitTestItem(float x, float y, float topPx, long totalMs,
                                @NonNull TimeToX timeToX, @Nullable String selectedItemId) {
        float localY = y - topPx + scrollOffsetPx;
        if (y < topPx || y > topPx + viewportHeightPx) return null;
        float handleHalf = ITEM_HANDLE_HALF_WIDTH_DP * density;
        for (RowLayout row : rows) {
            if (localY < row.bodyRect.top || localY > row.bodyRect.bottom) continue;
            Track t = row.track;
            if (t.isCollapsed() || t.isLocked() || t.isHidden()) return null;
            float top = row.bodyRect.top + 3f * density;
            float bottom = row.bodyRect.bottom - 3f * density;
            if (localY < top || localY > bottom) return null;
            for (TimedItem item : t.getItems()) {
                float x0 = timeToX.map(item.getTimelineStartMs());
                long dur = item.getDisplayDurationMs(totalMs);
                float x1 = Math.max(x0 + 6f * density, timeToX.map(item.getTimelineStartMs() + dur));
                if (x < x0 - handleHalf || x > x1 + handleHalf) continue;
                boolean selected = selectedItemId != null && selectedItemId.equals(item.getId());
                if (selected && x <= x0 + handleHalf) {
                    return new ItemHit(t, item, ItemZone.LEFT_HANDLE);
                }
                if (selected && x >= x1 - handleHalf) {
                    return new ItemHit(t, item, ItemZone.RIGHT_HANDLE);
                }
                if (x >= x0 && x <= x1) {
                    return new ItemHit(t, item, ItemZone.BODY);
                }
            }
            return null; // inside this row's body but not on any item
        }
        return null;
    }

    /** Scroll the row region by {@code dy} px (clamped); returns true if it consumed the scroll. */
    public boolean scrollBy(float dy) {
        float before = scrollOffsetPx;
        scrollOffsetPx = clampScroll(scrollOffsetPx + dy);
        return scrollOffsetPx != before;
    }

    private float clampScroll(float v) {
        float max = Math.max(0f, contentHeightPx - viewportHeightPx);
        return Math.max(0f, Math.min(v, max));
    }

    public float getContentHeightPx() { return contentHeightPx; }
    public float getViewportHeightPx() { return viewportHeightPx; }
}
