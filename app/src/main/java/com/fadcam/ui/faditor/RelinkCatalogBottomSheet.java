package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Relink catalog bottom sheet — shows ALL media in the project (clips + audio)
 * with OK / MISSING status badges, à la After Effects.
 *
 * <p>The user can see exactly which files are broken, which have been repaired,
 * and tap any missing entry to relink it. The activity handles the actual
 * file picking and validation; this sheet is the progress dashboard.</p>
 */
public class RelinkCatalogBottomSheet extends BottomSheetDialogFragment {

    public enum MediaType { VIDEO, IMAGE, AUDIO }
    public enum Status { OK, MISSING }

    public static class MediaEntry {
        public final int timelineIndex;
        public final MediaType type;
        @NonNull public final String displayName;
        @NonNull public final String uriString;
        public final long durationMs;
        public final boolean generated;
        @NonNull public Status status;
        @Nullable public String replacedWithName;

        public MediaEntry(int timelineIndex, @NonNull MediaType type,
                          @NonNull String displayName, @NonNull String uriString,
                          long durationMs, boolean generated, @NonNull Status status) {
            this.timelineIndex = timelineIndex;
            this.type = type;
            this.displayName = displayName;
            this.uriString = uriString;
            this.durationMs = durationMs;
            this.generated = generated;
            this.status = status;
        }
    }

    public interface Callback {
        /** User tapped a missing entry to relink it. */
        void onRelinkRequested(@NonNull MediaEntry entry);

        /** User tapped "Relink All Missing". */
        void onRelinkAllRequested();

        /** User tapped "Done" or dismissed. */
        void onCatalogClosed();
    }

    @Nullable private Callback callback;
    @NonNull private final List<MediaEntry> entries = new ArrayList<>();
    private LinearLayout listContainer;
    private TextView subtitleText;
    private TextView relinkAllButton;

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    /** Replace the entry list and refresh the display. */
    public void setEntries(@NonNull List<MediaEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        refreshList();
    }

    /** Update a single entry's status and refresh. */
    public void updateEntryStatus(int timelineIndex, @NonNull Status status,
                                  @Nullable String replacedWithName) {
        for (MediaEntry e : entries) {
            if (e.timelineIndex == timelineIndex) {
                e.status = status;
                e.replacedWithName = replacedWithName;
                break;
            }
        }
        refreshList();
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(
                        R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));

        // ── Title row ──────────────────────────────────────────
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (4 * dp));

        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_relink_catalog_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title);

        View spacer = new View(requireContext());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                0, 0, 1f);
        titleRow.addView(spacer);

        TextView doneBtn = new TextView(requireContext());
        doneBtn.setText(R.string.faditor_relink_done);
        doneBtn.setTextColor(0xFF4CAF50);
        doneBtn.setTextSize(14);
        doneBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        doneBtn.setPadding((int) (8 * dp), 0, 0, 0);
        doneBtn.setOnClickListener(v -> {
            if (callback != null) callback.onCatalogClosed();
            dismiss();
        });
        titleRow.addView(doneBtn);

        root.addView(titleRow);

        // ── Subtitle (count) ───────────────────────────────────
        subtitleText = new TextView(requireContext());
        subtitleText.setTextColor(0xFF888888);
        subtitleText.setTextSize(13);
        subtitleText.setPadding((int) (20 * dp), 0,
                (int) (20 * dp), (int) (12 * dp));
        root.addView(subtitleText);

        // ── "Relink All Missing" button ────────────────────────
        relinkAllButton = new TextView(requireContext());
        relinkAllButton.setText(R.string.faditor_relink_all);
        relinkAllButton.setTextColor(0xFFFFC107);
        relinkAllButton.setTextSize(14);
        relinkAllButton.setTypeface(null, android.graphics.Typeface.BOLD);
        relinkAllButton.setPadding((int) (20 * dp), (int) (8 * dp),
                (int) (20 * dp), (int) (12 * dp));
        relinkAllButton.setOnClickListener(v -> {
            if (callback != null) callback.onRelinkAllRequested();
        });
        root.addView(relinkAllButton);

        // ── Scrollable media list ──────────────────────────────
        ScrollView scroll = new ScrollView(requireContext());
        listContainer = new LinearLayout(requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        root.addView(scroll);

        refreshList();
        return root;
    }

    private void refreshList() {
        if (listContainer == null || !isAdded()) return;
        listContainer.removeAllViews();

        float dp = getResources().getDisplayMetrics().density;
        int missingCount = 0;
        int totalCount = entries.size();

        for (MediaEntry entry : entries) {
            if (entry.status == Status.MISSING) missingCount++;

            View row = buildEntryRow(entry, dp);
            listContainer.addView(row);

            // Divider
            View div = new View(requireContext());
            div.setBackgroundColor(0xFF2A2A2A);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * dp));
            dlp.setMargins((int) (20 * dp), 0, (int) (20 * dp), 0);
            div.setLayoutParams(dlp);
            listContainer.addView(div);
        }

        // Update subtitle
        if (subtitleText != null) {
            int repaired = totalCount - missingCount;
            if (missingCount == 0) {
                subtitleText.setText(R.string.faditor_relink_all_ok);
                subtitleText.setTextColor(0xFF4CAF50);
            } else {
                subtitleText.setText(getString(
                        R.string.faditor_relink_repaired, repaired, totalCount));
                subtitleText.setTextColor(0xFFFFC107);
            }
        }

        // Hide "Relink All" if nothing missing
        if (relinkAllButton != null) {
            relinkAllButton.setVisibility(missingCount > 0 ? View.VISIBLE : View.GONE);
        }
    }

    @NonNull
    private View buildEntryRow(@NonNull MediaEntry entry, float dp) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (20 * dp), (int) (10 * dp),
                (int) (20 * dp), (int) (10 * dp));

        boolean missing = entry.status == Status.MISSING;

        // Type icon
        TextView typeIcon = new TextView(requireContext());
        typeIcon.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.materialicons));
        typeIcon.setTextSize(16);
        typeIcon.setTextColor(missing ? 0xFFFFC107 : 0xFF666666);
        switch (entry.type) {
            case VIDEO: typeIcon.setText("movie"); break;
            case IMAGE: typeIcon.setText("image"); break;
            case AUDIO: typeIcon.setText("graphic_eq"); break;
        }
        typeIcon.setPadding(0, 0, (int) (8 * dp), 0);
        row.addView(typeIcon);

        // Info column
        LinearLayout info = new LinearLayout(requireContext());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Name line
        TextView nameLine = new TextView(requireContext());
        nameLine.setTextSize(14);
        nameLine.setTextColor(missing ? 0xFFFFC107 : 0xFFCCCCCC);
        nameLine.setSingleLine(true);
        nameLine.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        String name = entry.displayName;
        if (entry.replacedWithName != null) {
            name += "  →  " + entry.replacedWithName;
        }
        nameLine.setText(name);
        info.addView(nameLine);

        // Detail line
        TextView detailLine = new TextView(requireContext());
        detailLine.setTextSize(11);
        detailLine.setTextColor(0xFF777777);
        detailLine.setSingleLine(true);
        StringBuilder detail = new StringBuilder();
        detail.append("#").append(entry.timelineIndex + 1);
        detail.append("  ").append(entry.type.name().toLowerCase());
        if (entry.durationMs > 0) {
            detail.append("  ").append(formatDuration(entry.durationMs));
        }
        if (entry.generated) {
            detail.append("  (").append(getString(R.string.faditor_relink_generated)).append(")");
        }
        detailLine.setText(detail.toString());
        info.addView(detailLine);

        // URI hint for missing entries
        if (missing) {
            TextView uriLine = new TextView(requireContext());
            uriLine.setTextSize(10);
            uriLine.setTextColor(0xFF555555);
            uriLine.setSingleLine(true);
            uriLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
            uriLine.setText(shortenUri(entry.uriString));
            info.addView(uriLine);
        }

        row.addView(info);

        // Status badge
        TextView badge = new TextView(requireContext());
        badge.setTextSize(11);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setPadding((int) (6 * dp), (int) (2 * dp), (int) (6 * dp), (int) (2 * dp));
        if (missing) {
            badge.setText("✗ " + getString(R.string.faditor_relink_missing));
            badge.setTextColor(0xFFFF5252);
            row.setOnClickListener(v -> {
                if (callback != null) callback.onRelinkRequested(entry);
            });
            row.setBackgroundResource(android.R.color.transparent);
        } else {
            badge.setText("✓ " + getString(R.string.faditor_relink_ok));
            badge.setTextColor(0xFF4CAF50);
        }
        row.addView(badge);

        return row;
    }

    @NonNull
    private String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        s = s % 60;
        return m + ":" + String.format("%02d", s);
    }

    @NonNull
    private String shortenUri(@NonNull String uri) {
        if (uri.length() > 80) return "…" + uri.substring(uri.length() - 78);
        return uri;
    }
}
