package com.fadcam.ui.faditor.markers;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compact native bookmarks and regions wheel for the editor timeline. */
public final class TimelineMarksPopup {
    public interface Host {
        MarkerWorkspace.State snapshot();
        void apply(MarkerWorkspace.State next, String undoLabel);
        long playheadMs();
        long durationMs();
        float fps();
        void seek(long ms);
        void exportRegions(List<MarkerWorkspace.Region> regions);
    }

    private static final int TAB_POINTS = 0;
    private static final int TAB_REGIONS = 1;
    private static final int[][] SWATCHES = {
            {MarkerPalette.start(0), MarkerPalette.end(0)},
            {MarkerPalette.start(1), MarkerPalette.end(1)},
            {MarkerPalette.start(2), MarkerPalette.end(2)},
            {MarkerPalette.start(3), MarkerPalette.end(3)}
    };
    private static final long SETTLE_MS = 210L;
    private final Context context;
    private final Host host;
    private PopupWindow popup;
    private android.app.AlertDialog editorDialog;
    private LinearLayout rows;
    private ScrollView scroller;
    private TextView status;
    private TextView loopButton;
    private TextView exportButton;
    private TextView draftLabel;
    private TextView pointsTab;
    private TextView regionsTab;
    private int tab = TAB_POINTS;
    private int focusedRow = -1;
    private boolean wheelTouching;
    private String editingId;
    private android.animation.ValueAnimator scrollAnimator;
    private final Handler settleHandler = new Handler(Looper.getMainLooper());
    private final Runnable settleAfterFlick = () -> {
        if (popup != null && rows != null && rows.getChildCount() > 0
                && (scrollAnimator == null || !scrollAnimator.isRunning())) {
            centerRow(nearestRow(), true);
        }
    };
    private long draftIn = -1;
    private long draftOut = -1;
    private FrameLayout listFrame;

    public TimelineMarksPopup(@NonNull Context context, @NonNull Host host) {
        this.context = context;
        this.host = host;
    }

    public void show(@NonNull View anchor) {
        show(anchor, null);
    }

    /** Opens the wheel, optionally centering and opening a bookmark held on the ruler. */
    public void show(@NonNull View anchor, @Nullable String focusId) {
        dismiss();
        editingId = null;
        tab = TAB_POINTS;
        LinearLayout panel = buildPanel();
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(440), screenW - dp(28));
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(panel.getMeasuredHeight(), screenH - dp(28));
        // Keep every action reachable in landscape and with larger system text.
        ScrollView viewport = new ScrollView(context);
        viewport.setFillViewport(true);
        viewport.setVerticalScrollBarEnabled(false);
        viewport.addView(panel, new ScrollView.LayoutParams(-1, -2));
        popup = new PopupWindow(viewport, width, height, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setTouchable(true);
        popup.setElevation(dp(18));
        popup.setClippingEnabled(true);
        popup.setAnimationStyle(android.R.style.Animation_Dialog);
        popup.setOnDismissListener(() -> {
            popup = null;
            if (editorDialog != null) editorDialog.dismiss();
            settleHandler.removeCallbacks(settleAfterFlick);
            if (scrollAnimator != null) scrollAnimator.cancel();
            editingId = null;
        });
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);
        refresh();
        if (focusId != null) {
            rows.post(() -> {
                for (MarkerWorkspace.Point point : host.snapshot().points) {
                    if (focusId.equals(point.id)) { editPoint(point); return; }
                }
            });
        }
    }

    public void dismiss() {
        settleHandler.removeCallbacks(settleAfterFlick);
        if (scrollAnimator != null) scrollAnimator.cancel();
        if (editorDialog != null) editorDialog.dismiss();
        if (popup != null) {
            PopupWindow old = popup;
            popup = null;
            old.dismiss();
        }
    }

    public void refresh() {
        if (rows == null || popup == null) return;
        MarkerWorkspace.State state = host.snapshot();
        int previousScrollY = scroller.getScrollY();
        boolean keepSettling = scrollAnimator != null && scrollAnimator.isRunning();
        if (keepSettling) scrollAnimator.cancel();
        String previousCenterId = null;
        if (rows.getChildCount() > 0) {
            int currentFocus = keepSettling && focusedRow >= 0
                    ? focusedRow : nearestRow();
            int oldCenter = Math.max(0, Math.min(rows.getChildCount() - 1, currentFocus));
            Object tag = rows.getChildAt(oldCenter).getTag();
            if (tag instanceof String) previousCenterId = (String) tag;
        }
        rows.removeAllViews();
        focusedRow = -1;
        List<?> items = tab == TAB_POINTS ? state.sortedPoints() : state.sortedRegions();
        for (Object item : items) {
            if (tab == TAB_POINTS) addPointRow((MarkerWorkspace.Point) item);
            else addRegionRow((MarkerWorkspace.Region) item, state);
        }
        if (items.isEmpty()) {
            TextView empty = label(tab == TAB_POINTS ? R.string.timeline_marks_empty_bookmarks
                    : R.string.timeline_marks_empty_regions, 13, Studio.INK_DIM);
            empty.setGravity(Gravity.CENTER);
            rows.addView(empty, new LinearLayout.LayoutParams(-1, dp(58)));
        }
        pointsTab.setText(context.getString(R.string.timeline_marks_bookmarks_count, state.points.size()));
        regionsTab.setText(context.getString(R.string.timeline_marks_regions_count, state.regions.size()));
        paintTab(pointsTab, tab == TAB_POINTS);
        paintTab(regionsTab, tab == TAB_REGIONS);
        loopButton.setText(state.loopEnabled ? R.string.timeline_marks_loop_on : R.string.timeline_marks_loop_off);
        loopButton.setTextColor(state.loopEnabled ? Studio.ON_GO : Studio.INK_DIM);
        loopButton.setBackground(round(state.loopEnabled ? Studio.alpha(Studio.ARMED, 0xB3) : 0xCC1C1C22,
                dp(22), state.loopEnabled ? 0 : 0x662C2C35, dp(state.loopEnabled ? 0 : 1)));
        long count = 0;
        for (MarkerWorkspace.Region region : state.regions) if (region.exportEnabled) count++;
        exportButton.setText(context.getString(R.string.timeline_marks_export_count, count));
        if (editingId != null) {
            status.setText(context.getString(R.string.timeline_marks_editing_label, displayName(editingId)));
            status.setTextColor(Studio.ARMED);
        } else {
            status.setText(statusText(state));
            status.setTextColor(Studio.INK_DIM);
        }
        status.setVisibility(state.points.isEmpty() && state.regions.isEmpty() ? View.GONE : View.VISIBLE);
        postCenterActive(state, previousCenterId, previousScrollY, keepSettling);
    }

    private LinearLayout buildPanel() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        // A bitmap background normally contributes its full intrinsic height to measure().
        // Keep this original, untinted image while letting the widget size to its controls.
        root.setBackground(new android.graphics.drawable.DrawableWrapper(
                context.getDrawable(R.drawable.studio_backdrop_graffiti)) {
            @Override public int getMinimumWidth() { return 0; }
            @Override public int getMinimumHeight() { return 0; }
        });
        root.setContentDescription(context.getString(R.string.timeline_marks_popup_description));
        root.setFocusableInTouchMode(true);

        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(5), dp(2), dp(2), dp(4));
        TextView eyebrow = label(R.string.timeline_marks_studio, 9, Studio.INK_FAINT);
        Type.display(eyebrow, Type.BOLD);
        eyebrow.setLetterSpacing(.15f);
        TextView title = label(R.string.timeline_marks_title, 17, Studio.INK);
        Type.display(title, Type.EXTRA);
        LinearLayout titleBlock = new LinearLayout(context);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.addView(eyebrow);
        titleBlock.addView(title);
        head.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = iconButton(android.R.drawable.ic_menu_close_clear_cancel,
                R.string.timeline_marks_close, Studio.INK_DIM);
        close.setOnClickListener(v -> dismiss());
        head.addView(close, squareButton());
        root.addView(head);

        LinearLayout tabs = new LinearLayout(context);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(30), dp(2), dp(30), dp(4));
        pointsTab = label(R.string.timeline_marks_bookmarks_count, 12, Studio.INK_DIM);
        regionsTab = label(R.string.timeline_marks_regions_count, 12, Studio.INK_DIM);
        pointsTab.setGravity(Gravity.CENTER);
        regionsTab.setGravity(Gravity.CENTER);
        pointsTab.setPadding(dp(13), dp(7), dp(20), dp(7));
        regionsTab.setPadding(dp(20), dp(7), dp(13), dp(7));
        pointsTab.setBackground(new TabSeamDrawable(true, false));
        regionsTab.setBackground(new TabSeamDrawable(false, false));
        pointsTab.setContentDescription(context.getString(R.string.timeline_marks_bookmarks));
        regionsTab.setContentDescription(context.getString(R.string.timeline_marks_regions));
        pointsTab.setOnClickListener(v -> { tab = TAB_POINTS; refresh(); });
        regionsTab.setOnClickListener(v -> { tab = TAB_REGIONS; refresh(); });
        tabs.addView(pointsTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        tabs.addView(regionsTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        root.addView(tabs);

        LinearLayout nav = new LinearLayout(context);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(3), 0, dp(3), 0);
        status = label(R.string.timeline_marks_status_empty, 11, Studio.INK_DIM);
        Type.mono(status, Type.MEDIUM);
        status.setGravity(Gravity.CENTER);
        nav.addView(status, new LinearLayout.LayoutParams(-1, dp(32)));
        root.addView(nav);

        listFrame = new FrameLayout(context);
        listFrame.setBackground(round(Color.TRANSPARENT, dp(13), 0x442C2C35, dp(1)));
        LinearLayout wheel = new LinearLayout(context);
        wheel.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = iconButton(android.R.drawable.ic_media_previous, R.string.timeline_marks_previous, Studio.INK_DIM);
        TextView right = iconButton(android.R.drawable.ic_media_next, R.string.timeline_marks_next, Studio.INK_DIM);
        scroller = new ScrollView(context);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                wheelTouching = true;
                if (scrollAnimator != null) scrollAnimator.cancel();
                settleHandler.removeCallbacks(settleAfterFlick);
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                wheelTouching = false;
                settleHandler.removeCallbacks(settleAfterFlick);
                settleHandler.postDelayed(settleAfterFlick, 140L);
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        scroller.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            if (wheelTouching || (scrollAnimator != null && scrollAnimator.isRunning())) return;
            settleHandler.removeCallbacks(settleAfterFlick);
            settleHandler.postDelayed(settleAfterFlick, 140L);
        });
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        // A 46dp lead/trail lets the first and last 48dp row settle on the 140dp wheel's center.
        rows.setPadding(0, dp(46), 0, dp(46));
        scroller.addView(rows, new ScrollView.LayoutParams(-1, -2));
        wheel.addView(left, new LinearLayout.LayoutParams(dp(44), -1));
        wheel.addView(scroller, new LinearLayout.LayoutParams(0, -1, 1));
        wheel.addView(right, new LinearLayout.LayoutParams(dp(44), -1));
        left.setOnClickListener(v -> step(-1));
        right.setOnClickListener(v -> step(1));
        listFrame.addView(wheel, new FrameLayout.LayoutParams(-1, -1));
        View focusBand = new View(context);
        focusBand.setBackground(round(0x00000000, dp(10), Studio.ARMED, dp(1)));
        focusBand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams bandLp = new FrameLayout.LayoutParams(-1, dp(43), Gravity.CENTER);
        bandLp.leftMargin = dp(46);
        bandLp.rightMargin = dp(46);
        listFrame.addView(focusBand, bandLp);
        root.addView(listFrame, new LinearLayout.LayoutParams(-1, dp(140)));

        LinearLayout capture = new LinearLayout(context);
        capture.setGravity(Gravity.CENTER_VERTICAL);
        capture.setPadding(dp(3), dp(5), dp(3), dp(3));
        TextView setIn = textButton(R.string.timeline_marks_set_in, R.drawable.ic_marker_flag_start);
        draftLabel = label(R.string.timeline_marks_new_region, 10, Studio.INK_FAINT);
        draftLabel.setGravity(Gravity.CENTER);
        draftLabel.setSingleLine(true);
        draftLabel.setEllipsize(TextUtils.TruncateAt.END);
        draftLabel.setBackground(round(Studio.SURFACE, dp(22), Studio.LINE, dp(1)));
        draftLabel.setContentDescription(context.getString(R.string.timeline_marks_cancel_region_capture));
        draftLabel.setOnClickListener(v -> { draftIn = draftOut = -1; updateDraftLabel(); });
        updateDraftLabel();
        TextView setOut = textButton(R.string.timeline_marks_set_out, R.drawable.ic_marker_flag_end);
        capture.addView(setIn, new LinearLayout.LayoutParams(0, dp(44), 1));
        capture.addView(draftLabel, new LinearLayout.LayoutParams(dp(96), dp(44)));
        capture.addView(setOut, new LinearLayout.LayoutParams(0, dp(44), 1));
        setIn.setOnClickListener(v -> captureIn());
        setOut.setOnClickListener(v -> captureOut());
        root.addView(capture);

        LinearLayout addPoint = new LinearLayout(context);
        addPoint.setGravity(Gravity.CENTER);
        TextView add = textButton(R.string.timeline_marks_add_bookmark, 0);
        addPoint.addView(add, new LinearLayout.LayoutParams(-1, dp(44)));
        add.setOnClickListener(v -> addPoint());
        root.addView(addPoint);

        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(2), dp(3), dp(2), dp(1));
        loopButton = textButton(R.string.timeline_marks_loop_off, 0);
        exportButton = textButton(R.string.timeline_marks_export_count, R.drawable.ic_export_studio);
        footer.addView(loopButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        footer.addView(exportButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        loopButton.setOnClickListener(v -> toggleLoop());
        exportButton.setOnClickListener(v -> exportSelected());
        root.addView(footer);
        return root;
    }

    private void addPointRow(MarkerWorkspace.Point point) {
        LinearLayout row = rowShell();
        TextView time = stamp(point.color, compactSeconds(point.timeMs));
        LinearLayout band = nameBand(point.color);
        TextView name = label(point.name, 13, Studio.ON_GO);
        configureName(name);
        band.addView(name, new LinearLayout.LayoutParams(-1, -1));
        row.addView(time, new LinearLayout.LayoutParams(dp(61), dp(34)));
        time.setOnLongClickListener(v -> { centerRow(rows.indexOfChild(row), true); editPoint(point); return true; });
        row.addView(band, new LinearLayout.LayoutParams(0, dp(34), 1));
        row.setContentDescription(context.getString(R.string.timeline_marks_bookmark_row, point.name, compactSeconds(point.timeMs)));
        row.setTag(point.id);
        row.setOnClickListener(v -> { centerRow(rows.indexOfChild(row), true); host.seek(clamp(point.timeMs)); });
        time.setOnClickListener(v -> row.performClick());
        band.setOnClickListener(v -> row.performClick());
        row.setOnLongClickListener(v -> { centerRow(rows.indexOfChild(row), true); editPoint(point); return true; });
        band.setOnLongClickListener(v -> { centerRow(rows.indexOfChild(row), true); editPoint(point); return true; });
        TextView more = iconButton(android.R.drawable.ic_menu_edit, R.string.timeline_marks_edit_bookmark, Studio.INK_DIM);
        row.addView(more, squareButton());
        more.setOnClickListener(v -> editPoint(point));
        styleEditing(row, point.id);
        rows.addView(row, rowLp());
    }

    private void addRegionRow(MarkerWorkspace.Region region, MarkerWorkspace.State state) {
        LinearLayout row = rowShell();
        TextView start = flag(R.drawable.ic_marker_flag_start, R.string.timeline_marks_region_in, region.inMs);
        TextView name = label(region.name, 12, Studio.ON_GO);
        configureName(name);
        TextView duration = label("· " + compactSeconds(Math.max(0, region.outMs - region.inMs)), 9, Studio.ON_GO);
        Type.mono(duration, Type.MEDIUM);
        duration.setSingleLine(true);
        duration.setGravity(Gravity.CENTER_VERTICAL);
        start.setOnClickListener(v -> host.seek(clamp(region.inMs)));
        TextView end = flag(R.drawable.ic_marker_flag_end, R.string.timeline_marks_region_out, region.outMs);
        row.addView(start, new LinearLayout.LayoutParams(dp(59), dp(44)));
        LinearLayout band = nameBand(region.color);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        duration.setPadding(dp(2), 0, dp(6), 0);
        band.addView(name);
        band.addView(duration, new LinearLayout.LayoutParams(-2, -1));
        row.addView(band, new LinearLayout.LayoutParams(0, dp(34), 1));
        row.addView(end, new LinearLayout.LayoutParams(dp(59), dp(44)));
        TextView export = iconButton(R.drawable.ic_export_studio, R.string.timeline_marks_toggle_export, region.exportEnabled ? Studio.ARMED : Studio.INK_OFF);
        export.setAlpha(region.exportEnabled ? 1f : .45f);
        row.addView(export, squareButton());
        export.setOnClickListener(v -> toggleRegionExport(region));
        row.setContentDescription(context.getString(R.string.timeline_marks_region_row, region.name,
                compactSeconds(region.inMs), compactSeconds(region.outMs)));
        row.setTag(region.id);
        row.setOnClickListener(v -> {
            centerRow(rows.indexOfChild(row), true);
            activateRegion(region, true);
            host.seek(clamp(region.inMs));
        });
        band.setOnClickListener(v -> row.performClick());
        row.setOnLongClickListener(v -> { centerRow(rows.indexOfChild(row), true); editRegion(region); return true; });
        band.setOnLongClickListener(v -> { centerRow(rows.indexOfChild(row), true); editRegion(region); return true; });
        end.setOnClickListener(v -> host.seek(clamp(region.outMs)));
        styleEditing(row, region.id, region.id.equals(state.activeRegionId));
        rows.addView(row, rowLp());
    }

    private void styleEditing(View row, String id) { styleEditing(row, id, false); }

    private void styleEditing(View row, String id, boolean activeRegion) {
        boolean selected = id != null && id.equals(editingId);
        row.setBackground(round(selected ? Studio.alpha(Studio.ARMED, 0x30)
                        : activeRegion ? Studio.alpha(Studio.ARMED, 0x20) : 0x1AFFFFFF, dp(22),
                selected || activeRegion ? Studio.ARMED : 0x332C2C35, dp(selected ? 2 : 1)));
        if (selected) {
            row.setContentDescription(row.getContentDescription() + ", " +
                    context.getString(R.string.timeline_marks_editing_label, displayName(id)));
        }
    }

    private LinearLayout nameBand(int color) {
        LinearLayout band = new LinearLayout(context);
        band.setGravity(Gravity.CENTER_VERTICAL);
        band.setPadding(dp(7), 0, dp(7), 0);
        band.setBackground(new GradientStampDrawable(SWATCHES[Math.max(0, Math.min(SWATCHES.length - 1, color))]));
        return band;
    }

    private String displayName(String id) {
        MarkerWorkspace.State s = host.snapshot();
        for (MarkerWorkspace.Point p : s.points) if (p.id.equals(id)) return p.name;
        for (MarkerWorkspace.Region r : s.regions) if (r.id.equals(id)) return r.name;
        return "";
    }

    private void editPoint(MarkerWorkspace.Point source) {
        MarkerWorkspace.Point point = source.copy();
        editingId = point.id;
        refresh();
        editDialog(point.name, point.timeMs, -1, point.color, false, point.id, (name, in, out, color) -> {
            MarkerWorkspace.State next = host.snapshot().copy();
            for (MarkerWorkspace.Point p : next.points) if (p.id.equals(point.id)) {
                p.name = name; p.timeMs = in; p.color = color; break;
            }
            host.apply(next, context.getString(R.string.timeline_marks_undo_edit_bookmark));
            editingId = null;
            refresh();
        });
    }

    private void editRegion(MarkerWorkspace.Region source) {
        MarkerWorkspace.Region region = source.copy();
        editingId = region.id;
        refresh();
        editDialog(region.name, region.inMs, region.outMs, region.color, true, region.id, (name, in, out, color) -> {
            if (out <= in) { toast(R.string.timeline_marks_invalid_range); return; }
            MarkerWorkspace.State next = host.snapshot().copy();
            for (MarkerWorkspace.Region r : next.regions) if (r.id.equals(region.id)) {
                r.name = name; r.inMs = in; r.outMs = out; r.color = color; break;
            }
            next.normalize(host.durationMs());
            host.apply(next, context.getString(R.string.timeline_marks_undo_edit_region));
            editingId = null;
            refresh();
        });
    }

    private interface EditDone { void save(String name, long inMs, long outMs, int color); }

    private void editDialog(String currentName, long inMs, long outMs, int color, boolean region,
                            String targetId, EditDone done) {
        LinearLayout form = new LinearLayout(context);
        form.setPadding(dp(20), dp(12), dp(20), dp(5));
        form.setOrientation(LinearLayout.VERTICAL);
        EditText name = input(currentName, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                R.string.timeline_marks_name_hint);
        form.addView(name, new LinearLayout.LayoutParams(-1, dp(48)));
        EditText start = input(secondsValue(inMs), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                R.string.timeline_marks_in_ms);
        form.addView(start, new LinearLayout.LayoutParams(-1, dp(44)));
        EditText end = input(region ? secondsValue(outMs) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                R.string.timeline_marks_out_ms);
        Type.mono(start, Type.REGULAR);
        Type.mono(end, Type.REGULAR);
        end.setVisibility(region ? View.VISIBLE : View.GONE);
        form.addView(end, new LinearLayout.LayoutParams(-1, dp(44)));
        if (region) {
            LinearLayout bookmarkShortcuts = new LinearLayout(context);
            bookmarkShortcuts.setGravity(Gravity.CENTER);
            TextView useIn = textButton(R.string.timeline_marks_use_bookmark_in, 0);
            TextView useOut = textButton(R.string.timeline_marks_use_bookmark_out, 0);
            bookmarkShortcuts.addView(useIn, new LinearLayout.LayoutParams(0, dp(44), 1));
            bookmarkShortcuts.addView(useOut, new LinearLayout.LayoutParams(0, dp(44), 1));
            form.addView(bookmarkShortcuts);
            useIn.setOnClickListener(v -> fillNearestBookmark(start, inMs));
            useOut.setOnClickListener(v -> fillNearestBookmark(end, outMs));
        }
        LinearLayout colors = new LinearLayout(context);
        colors.setGravity(Gravity.CENTER);
        final int[] chosen = {Math.max(0, Math.min(SWATCHES.length - 1, color))};
        for (int i = 0; i < SWATCHES.length; i++) {
            final int index = i;
            View chip = new View(context);
            GradientDrawable chipGradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, SWATCHES[i]);
            chipGradient.setCornerRadius(dp(16));
            if (i == chosen[0]) chipGradient.setStroke(dp(2), Studio.INK);
            chip.setBackground(chipGradient);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.setMargins(dp(4), dp(8), dp(4), dp(5));
            chip.setOnClickListener(v -> {
                chosen[0] = index;
                for (int j = 0; j < colors.getChildCount(); j++) {
                    GradientDrawable swatch = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, SWATCHES[j]);
                    swatch.setCornerRadius(dp(16));
                    if (j == chosen[0]) swatch.setStroke(dp(2), Studio.INK);
                    colors.getChildAt(j).setBackground(swatch);
                }
            });
            colors.addView(chip, lp);
        }
        form.addView(colors);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(region ? R.string.timeline_marks_edit_region : R.string.timeline_marks_edit_bookmark)
                .setView(form)
                .setNegativeButton(R.string.timeline_marks_cancel, null)
                .setNeutralButton(R.string.timeline_marks_delete, null)
                .setPositiveButton(R.string.timeline_marks_save, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (editorDialog == dialog) editorDialog = null;
            editingId = null;
            refresh();
        });
        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                final long a;
                final long b;
                try {
                    a = decimalMillis(start.getText().toString());
                    b = region ? decimalMillis(end.getText().toString()) : -1;
                } catch (NumberFormatException ex) {
                    start.setError(context.getString(R.string.timeline_marks_invalid_time));
                    toast(R.string.timeline_marks_invalid_time);
                    return;
                }
                if (region && b <= a) {
                    end.setError(context.getString(R.string.timeline_marks_invalid_range));
                    toast(R.string.timeline_marks_invalid_range);
                    return;
                }
                String text = name.getText().toString().trim();
                done.save(text.isEmpty() ? context.getString(R.string.timeline_marks_untitled) : text, a, b, chosen[0]);
                dialog.dismiss();
            });
            if (targetId != null) {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    deleteItem(targetId, region);
                    dialog.dismiss();
                });
            }
        });
        editorDialog = dialog;
        dialog.show();
    }

    private void addPoint() {
        long time = clamp(host.playheadMs());
        MarkerWorkspace.State next = host.snapshot().copy();
        long halfFrame = Math.max(1L, Math.round(500f / Math.max(1f, host.fps())));
        for (MarkerWorkspace.Point existing : next.points) {
            if (Math.abs(existing.timeMs - time) <= halfFrame) {
                tab = TAB_POINTS;
                editingId = null;
                refresh();
                centerAndSeek(existing);
                return;
            }
        }
        MarkerWorkspace.Point point = new MarkerWorkspace.Point(context.getString(R.string.timeline_marks_bookmark_number, next.points.size() + 1), time, 0);
        next.points.add(point);
        host.apply(next, context.getString(R.string.timeline_marks_undo_add_bookmark));
        tab = TAB_POINTS;
        editingId = null;
        refresh();
        toast(R.string.timeline_marks_bookmark_added);
    }

    private void captureIn() {
        draftIn = clamp(host.playheadMs());
        updateDraftLabel();
        if (draftOut < 0) toast(R.string.timeline_marks_in_captured);
        completeDraftIfReady();
    }

    private void captureOut() {
        draftOut = clamp(host.playheadMs());
        updateDraftLabel();
        if (draftIn < 0) toast(R.string.timeline_marks_out_captured);
        completeDraftIfReady();
    }

    private void updateDraftLabel() {
        if (draftLabel == null) return;
        if (draftIn >= 0 && draftOut >= 0) {
            draftLabel.setText(context.getString(R.string.timeline_marks_draft_both,
                    compactSeconds(draftIn), compactSeconds(draftOut)));
        } else if (draftIn >= 0) {
            draftLabel.setText(context.getString(R.string.timeline_marks_draft_in, compactSeconds(draftIn)));
        } else if (draftOut >= 0) {
            draftLabel.setText(context.getString(R.string.timeline_marks_draft_out, compactSeconds(draftOut)));
        } else {
            draftLabel.setText(R.string.timeline_marks_new_region);
        }
        draftLabel.setContentDescription(context.getString(R.string.timeline_marks_cancel_region_capture)
                + ", " + draftLabel.getText());
    }

    private void completeDraftIfReady() {
        if (draftIn < 0 || draftOut < 0) return;
        if (draftOut <= draftIn) {
            toast(R.string.timeline_marks_invalid_range);
            return;
        }
        MarkerWorkspace.State next = host.snapshot().copy();
        MarkerWorkspace.Region region = new MarkerWorkspace.Region(
                context.getString(R.string.timeline_marks_region_number, next.regions.size() + 1), draftIn, draftOut, 3);
        region.exportEnabled = true;
        next.regions.add(region);
        next.activeRegionId = region.id;
        next.normalize(host.durationMs());
        host.apply(next, context.getString(R.string.timeline_marks_undo_add_region));
        draftIn = draftOut = -1;
        updateDraftLabel();
        tab = TAB_REGIONS;
        editingId = null;
        refresh();
        toast(R.string.timeline_marks_region_added);
    }

    private void toggleLoop() {
        MarkerWorkspace.State next = host.snapshot().copy();
        next.loopEnabled = !next.loopEnabled;
        host.apply(next, context.getString(R.string.timeline_marks_undo_loop));
        refresh();
    }

    private void activateRegion(MarkerWorkspace.Region region, boolean toggleIfActive) {
        MarkerWorkspace.State next = host.snapshot().copy();
        if (region.id.equals(next.activeRegionId)) {
            if (!toggleIfActive) return;
            next.activeRegionId = null;
            next.loopEnabled = false;
        } else {
            next.activeRegionId = region.id;
        }
        host.apply(next, context.getString(R.string.timeline_marks_undo_active_region));
        refresh();
    }

    private void toggleRegionExport(MarkerWorkspace.Region region) {
        MarkerWorkspace.State next = host.snapshot().copy();
        for (MarkerWorkspace.Region r : next.regions) if (r.id.equals(region.id)) { r.exportEnabled = !r.exportEnabled; break; }
        host.apply(next, context.getString(R.string.timeline_marks_undo_export_selection));
        refresh();
    }

    private void deleteItem(String id, boolean region) {
        MarkerWorkspace.State next = host.snapshot().copy();
        if (region) {
            for (int i = next.regions.size() - 1; i >= 0; i--) {
                if (id.equals(next.regions.get(i).id)) next.regions.remove(i);
            }
            if (id.equals(next.activeRegionId)) {
                next.activeRegionId = null;
                next.loopEnabled = false;
            }
            host.apply(next, context.getString(R.string.timeline_marks_undo_delete_region));
        } else {
            for (int i = next.points.size() - 1; i >= 0; i--) {
                if (id.equals(next.points.get(i).id)) next.points.remove(i);
            }
            host.apply(next, context.getString(R.string.timeline_marks_undo_delete_bookmark));
        }
        editingId = null;
        refresh();
    }

    private void exportSelected() {
        List<MarkerWorkspace.Region> selected = new ArrayList<>();
        for (MarkerWorkspace.Region r : host.snapshot().sortedRegions()) if (r.exportEnabled) selected.add(r.copy());
        if (selected.isEmpty()) { toast(R.string.timeline_marks_nothing_to_export); return; }
        host.exportRegions(selected);
    }

    private void step(int direction) {
        if (rows == null || rows.getChildCount() == 0) return;
        int current = scrollAnimator != null && scrollAnimator.isRunning() && focusedRow >= 0
                ? focusedRow : nearestRow();
        int target = Math.max(0, Math.min(rows.getChildCount() - 1, current + direction));
        centerRow(target, true);
        Object tag = rows.getChildAt(target).getTag();
        if (tag instanceof String) {
            MarkerWorkspace.State state = host.snapshot();
            if (tab == TAB_POINTS) {
                for (MarkerWorkspace.Point point : state.points) if (tag.equals(point.id)) { host.seek(clamp(point.timeMs)); break; }
            } else {
                for (MarkerWorkspace.Region region : state.regions) if (tag.equals(region.id)) {
                    activateRegion(region, false);
                    host.seek(clamp(region.inMs));
                    break;
                }
            }
        }
    }

    private void centerAndSeek(MarkerWorkspace.Point point) {
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (point.id.equals(rows.getChildAt(i).getTag())) {
                centerRow(i, true);
                break;
            }
        }
        host.seek(clamp(point.timeMs));
    }

    private void fillNearestBookmark(EditText target, long referenceMs) {
        List<MarkerWorkspace.Point> points = host.snapshot().sortedPoints();
        if (points.isEmpty()) { toast(R.string.timeline_marks_no_bookmarks); return; }
        MarkerWorkspace.Point nearest = points.get(0);
        for (MarkerWorkspace.Point point : points) {
            if (Math.abs(point.timeMs - referenceMs) < Math.abs(nearest.timeMs - referenceMs)) nearest = point;
        }
        target.setText(secondsValue(nearest.timeMs));
    }

    private int nearestRow() {
        if (rows == null || scroller == null || rows.getChildCount() == 0) return 0;
        int centerY = scroller.getScrollY() + scroller.getHeight() / 2;
        int best = 0, distance = Integer.MAX_VALUE;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            int d = Math.abs(child.getTop() + child.getHeight() / 2 - centerY);
            if (d < distance) { best = i; distance = d; }
        }
        return best;
    }

    private void centerRow(int index, boolean animate) {
        if (index < 0 || index >= rows.getChildCount()) return;
        focusedRow = index;
        updateMarquees();
        if (status != null && editingId == null) status.setText(statusText(host.snapshot()));
        View child = rows.getChildAt(index);
        int target = Math.max(0, child.getTop() + child.getHeight() / 2 - scroller.getHeight() / 2);
        if (scrollAnimator != null) scrollAnimator.cancel();
        if (Math.abs(target - scroller.getScrollY()) <= dp(1)) {
            if (animate) child.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            return;
        }
        scrollAnimator = android.animation.ValueAnimator.ofInt(scroller.getScrollY(), target);
        scrollAnimator.setDuration(SETTLE_MS);
        scrollAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        scrollAnimator.addUpdateListener(a -> scroller.scrollTo(0, (Integer) a.getAnimatedValue()));
        scrollAnimator.start();
        if (animate) child.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    private void updateMarquees() {
        if (rows == null) return;
        boolean animate = animationsAllowed();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) child;
            TextView name = null;
            int bandIndex = tab == TAB_POINTS ? 1 : 1;
            if (row.getChildCount() > bandIndex && row.getChildAt(bandIndex) instanceof LinearLayout) {
                LinearLayout band = (LinearLayout) row.getChildAt(bandIndex);
                if (band.getChildCount() > 0 && band.getChildAt(0) instanceof TextView) {
                    name = (TextView) band.getChildAt(0);
                }
            }
            if (name != null) name.setSelected(animate && i == focusedRow);
        }
    }

    private void postCenterActive(MarkerWorkspace.State state, String previousCenterId, int previousScrollY,
                                  boolean preserveMotion) {
        if (rows == null) return;
        rows.post(() -> {
            int target = -1;
            scroller.scrollTo(0, previousScrollY);
            if (editingId != null) {
                for (int i = 0; i < rows.getChildCount(); i++) if (editingId.equals(rows.getChildAt(i).getTag())) { target = i; break; }
            }
            if (target < 0 && tab == TAB_REGIONS && state.activeRegion() != null) {
                String id = state.activeRegion().id;
                for (int i = 0; i < rows.getChildCount(); i++) if (id.equals(rows.getChildAt(i).getTag())) { target = i; break; }
            }
            if (target < 0 && previousCenterId != null) {
                for (int i = 0; i < rows.getChildCount(); i++) if (previousCenterId.equals(rows.getChildAt(i).getTag())) { target = i; break; }
            }
            if (target < 0 && tab == TAB_POINTS && !state.points.isEmpty()) {
                long playhead = host.playheadMs();
                MarkerWorkspace.Point point = state.sortedPoints().get(0);
                for (MarkerWorkspace.Point candidate : state.sortedPoints()) {
                    if (Math.abs(candidate.timeMs - playhead) < Math.abs(point.timeMs - playhead)) point = candidate;
                }
                for (int i = 0; i < rows.getChildCount(); i++) if (point.id.equals(rows.getChildAt(i).getTag())) { target = i; break; }
            }
            if (target < 0 && tab == TAB_REGIONS && !state.regions.isEmpty()) {
                String id = state.sortedRegions().get(0).id;
                for (int i = 0; i < rows.getChildCount(); i++) if (id.equals(rows.getChildAt(i).getTag())) { target = i; break; }
            }
            if (target >= 0) centerRow(target, preserveMotion);
        });
    }

    private String statusText(MarkerWorkspace.State state) {
        MarkerWorkspace.Region active = state.activeRegion();
        if (tab == TAB_REGIONS && active != null) {
            return context.getString(R.string.timeline_marks_active_region_status, active.name);
        }
        int size = tab == TAB_POINTS ? state.points.size() : state.regions.size();
        if (size == 0) return context.getString(R.string.timeline_marks_status_empty);
        int selected = (focusedRow >= 0 ? focusedRow : nearestRow()) + 1;
        return context.getString(R.string.timeline_marks_position, Math.min(selected, size), size);
    }

    private void paintTab(TextView view, boolean active) {
        view.setTextColor(active ? Studio.ARMED : Studio.INK_DIM);
        Type.body(view, active ? Type.BOLD : Type.SEMIBOLD);
        view.setSelected(active);
        view.setBackground(new TabSeamDrawable(view == pointsTab, active));
    }

    private LinearLayout rowShell() {
        LinearLayout row = new LinearLayout(context);
        row.setTag(null);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(3), 0);
        row.setBackground(round(0x1AFFFFFF, dp(22), 0x332C2C35, dp(1)));
        return row;
    }

    private void configureName(TextView name) {
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        name.setMarqueeRepeatLimit(-1);
        name.setSelected(false);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setPadding(dp(5), 0, dp(3), 0);
    }

    private TextView stamp(int color, String timeText) {
        int index = Math.max(0, Math.min(SWATCHES.length - 1, color));
        TextView stamp = label(timeText, 9, Studio.ON_GO);
        stamp.setGravity(Gravity.CENTER);
        Type.mono(stamp, Type.MEDIUM);
        stamp.setBackground(new GradientStampDrawable(SWATCHES[index]));
        stamp.setTextColor(Studio.ON_GO);
        return stamp;
    }

    private TextView flag(int resId, int description, long time) {
        TextView button = textButton(description, resId);
        button.setText(compactSeconds(time));
        button.setTextSize(9);
        Type.mono(button, Type.MEDIUM);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setCompoundDrawablePadding(dp(2));
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setContentDescription(context.getString(description) + " " + compactSeconds(time));
        return button;
    }

    private TextView textButton(int text, int icon) {
        TextView button = label(text, 11, Studio.INK);
        button.setGravity(Gravity.CENTER);
        button.setBackground(round(0xCC1C1C22, dp(22), 0x662C2C35, dp(1)));
        if (icon != 0) {
            button.setPadding(dp(3), 0, dp(7), 0);
            button.setCompoundDrawablePadding(dp(4));
            button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        }
        button.setMinHeight(dp(42));
        button.setMinWidth(dp(44));
        button.setContentDescription(context.getString(text));
        return button;
    }

    private TextView iconButton(int drawable, int description, int tint) {
        TextView button = new TextView(context);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(tint);
        button.setTextSize(20);
        button.setText("");
        button.setBackground(round(0x5526262E, dp(22), 0x332C2C35, dp(1)));
        button.setContentDescription(context.getString(description));
        if (drawable != 0) {
            button.setCompoundDrawablesWithIntrinsicBounds(drawable, 0, 0, 0);
            Drawable icon = button.getCompoundDrawables()[0];
            if (icon != null && drawable != R.drawable.ic_export_studio) icon.setTint(tint);
        }
        return button;
    }

    private EditText input(String value, int type, int hint) {
        EditText edit = new EditText(context);
        edit.setSingleLine(true);
        edit.setText(value);
        edit.setInputType(type);
        edit.setHint(hint);
        edit.setTextColor(Studio.INK);
        edit.setHintTextColor(Studio.INK_FAINT);
        edit.setTextSize(14);
        edit.setSelectAllOnFocus(true);
        return edit;
    }

    private TextView label(int res, int size, int color) {
        TextView view = new TextView(context);
        view.setText(res);
        view.setTextSize(size);
        view.setTextColor(color);
        Type.body(view, Type.REGULAR);
        return view;
    }

    private TextView label(CharSequence text, int size, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        Type.body(view, Type.REGULAR);
        return view;
    }

    private LinearLayout.LayoutParams squareButton() {
        return new LinearLayout.LayoutParams(dp(44), dp(44));
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.setMargins(dp(1), dp(2), dp(1), dp(2));
        return lp;
    }

    private GradientDrawable round(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    private boolean animationsAllowed() {
        try {
            return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception ignored) { return true; }
    }

    private long clamp(long value) { return Math.max(0, Math.min(Math.max(0, host.durationMs()), value)); }
    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static String compactSeconds(long millis) {
        double sec = millis / 1000.0;
        if (Math.abs(sec - Math.rint(sec)) < .05) return String.format(Locale.US, "%.0fs", sec);
        return String.format(Locale.US, "%.1fs", sec);
    }

    private static long decimalMillis(String value) {
        double n = Double.parseDouble(value.trim());
        if (n < 0) throw new NumberFormatException();
        return Math.round(n * 1000.0);
    }

    private static String secondsValue(long millis) { return String.format(Locale.US, "%.3f", millis / 1000.0); }

    private void toast(int text) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show(); }

    private final class GradientStampDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int[] colors;
        GradientStampDrawable(int[] colors) { this.colors = colors; }
        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float inset = dp(5);
            float radius = dp(4);
            paint.setShader(new android.graphics.LinearGradient(b.left, b.top, b.right, b.bottom,
                    colors[0], colors[1], android.graphics.Shader.TileMode.CLAMP));
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(b.left + radius, b.top);
            path.lineTo(b.right - radius, b.top);
            path.quadTo(b.right, b.top, b.right, b.top + radius);
            path.lineTo(b.right - inset, b.bottom - radius);
            path.quadTo(b.right - inset, b.bottom, b.right - inset - radius, b.bottom);
            path.lineTo(b.left + inset + radius, b.bottom);
            path.quadTo(b.left + inset, b.bottom, b.left + inset, b.bottom - radius);
            path.lineTo(b.left, b.top + radius);
            path.quadTo(b.left, b.top, b.left + radius, b.top);
            path.close();
            canvas.drawPath(path, paint);
            paint.setShader(null);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private final class TabSeamDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final boolean left;
        private final boolean active;
        TabSeamDrawable(boolean left, boolean active) { this.left = left; this.active = active; }
        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            paint.setColor(active ? Studio.alpha(Studio.ARMED, 0x66) : Studio.RAISED);
            android.graphics.Path p = new android.graphics.Path();
            if (left) {
                p.moveTo(b.left, b.top); p.lineTo(b.right, b.top); p.lineTo(b.right - dpLocal(10), b.bottom); p.lineTo(b.left, b.bottom);
            } else {
                p.moveTo(b.left + dpLocal(10), b.top); p.lineTo(b.right, b.top); p.lineTo(b.right, b.bottom); p.lineTo(b.left, b.bottom);
            }
            p.close(); canvas.drawPath(p, paint);
        }
        private int dpLocal(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
