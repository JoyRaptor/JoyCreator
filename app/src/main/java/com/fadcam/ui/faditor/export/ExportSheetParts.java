package com.fadcam.ui.faditor.export;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.model.ExportSettings;

import java.util.Arrays;

/**
 * The export sheet's parts, lifted out of {@code FaditorEditorActivity.showExportConfirmation} (X5 of the Avatar build,
 * Studio reuse item 4) so Avatar Studio's room builds its sheet from the same rows: the file name, the size and the
 * quality (with the Draft chip), the tick boxes and their grey lines, and the export's progress and Stop.
 *
 * <p>Each method is the code that was inline in the editor, moved as it was: Studio's sheet looks and works exactly as
 * before. The editor keeps everything that is only Studio's (loudness, clean audio, ranges, named regions, the queue).</p>
 */
public final class ExportSheetParts {

    /** The sizes in the order of {@code R.array.export_resolutions}. */
    public static final ExportSettings.Resolution[] SIZES = {
            ExportSettings.Resolution.ORIGINAL,
            ExportSettings.Resolution.FHD_1080P,
            ExportSettings.Resolution.HD_720P,
            ExportSettings.Resolution.SD_480P};

    /** The qualities in the order of {@link #qualityLabels}. */
    public static final ExportSettings.Quality[] QUALITIES = {
            ExportSettings.Quality.HIGH,
            ExportSettings.Quality.MEDIUM,
            ExportSettings.Quality.LOW};

    private ExportSheetParts() {}

    @NonNull
    public static String[] sizeLabels(@NonNull Context c) {
        return c.getResources().getStringArray(R.array.export_resolutions);
    }

    @NonNull
    public static String[] qualityLabels(@NonNull Context c) {
        return new String[]{c.getString(R.string.remote_quality_high),
                c.getString(R.string.remote_quality_medium), c.getString(R.string.remote_quality_low)};
    }

    /** The label and the editable file name, pre-filled with {@code defaultName}. Returns the field. */
    @NonNull
    public static EditText nameRow(@NonNull Context c, @NonNull LinearLayout root,
                                   @NonNull String defaultName, int pad) {
        TextView fileNameLabel = new TextView(c);
        fileNameLabel.setText(R.string.faditor_export_filename_label);
        fileNameLabel.setTextColor(Studio.INK_FAINT);
        fileNameLabel.setTextSize(11);
        LinearLayout.LayoutParams fnLabelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fnLabelLp.topMargin = pad / 2;
        fileNameLabel.setLayoutParams(fnLabelLp);
        root.addView(fileNameLabel);

        EditText fileNameInput = new EditText(c);
        fileNameInput.setText(defaultName);
        fileNameInput.setSelectAllOnFocus(true);
        fileNameInput.setSingleLine(true);
        fileNameInput.setTextColor(Studio.INK);
        fileNameInput.setTextSize(14);
        fileNameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams fnInputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fnInputLp.topMargin = (int) (4 * c.getResources().getDisplayMetrics().density);
        fileNameInput.setLayoutParams(fnInputLp);
        root.addView(fileNameInput);
        return fileNameInput;
    }

    /**
     * A typed file name made safe for the file system: the characters Android, FAT and NTFS refuse
     * ({@code / \ : * ? " < > |}) taken out, trimmed, and {@code fallback} when nothing is left. The extension is added
     * by {@code ExportManager}, not here.
     */
    @NonNull
    public static String sanitizeName(@Nullable String rawInput, @NonNull String fallback) {
        if (rawInput == null) return fallback;
        String cleaned = rawInput.trim().replaceAll("[/\\\\:*?\"<>|]", "").trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /** The one-tap "Draft (fast)" chip (720p, Low). Wire it with {@link #bindDraftChip} once the spinners exist. */
    @NonNull
    public static TextView draftChip(@NonNull Context c, @NonNull LinearLayout root, int pad) {
        TextView chip = SheetKit.chip(c, c.getString(R.string.export_draft_chip));
        com.fadcam.ui.faditor.tools.ObjectDrawer.Kit.describe(chip, c.getString(R.string.export_draft_chip_desc));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = pad / 2;
        chip.setLayoutParams(chipLp);
        root.addView(chip);
        return chip;
    }

    /** The size row, set to {@code current}. */
    @NonNull
    public static Spinner sizeSpinner(@NonNull Context c, @NonNull LinearLayout root,
                                      @Nullable ExportSettings.Resolution current, int pad) {
        return spinner(c, root, c.getString(R.string.row_resolution_title), sizeLabels(c),
                Arrays.asList(SIZES).indexOf(current), pad);
    }

    /** The quality row, set to {@code current}. */
    @NonNull
    public static Spinner qualitySpinner(@NonNull Context c, @NonNull LinearLayout root,
                                         @Nullable ExportSettings.Quality current, int pad) {
        return spinner(c, root, c.getString(R.string.export_quality), qualityLabels(c),
                Arrays.asList(QUALITIES).indexOf(current), pad);
    }

    /** The Draft chip sets 720p and Low quality: cosmetic, it only drives the two rows. */
    public static void bindDraftChip(@NonNull View chip, @NonNull Spinner size, @NonNull Spinner quality) {
        chip.setOnClickListener(v -> {
            int resIdx = Arrays.asList(SIZES).indexOf(ExportSettings.Resolution.HD_720P);
            int qualIdx = Arrays.asList(QUALITIES).indexOf(ExportSettings.Quality.LOW);
            if (resIdx >= 0) size.setSelection(resIdx);
            if (qualIdx >= 0) quality.setSelection(qualIdx);
        });
    }

    @NonNull
    public static ExportSettings.Resolution sizeOf(@NonNull Spinner s) {
        return SIZES[Math.max(0, s.getSelectedItemPosition())];
    }

    @NonNull
    public static ExportSettings.Quality qualityOf(@NonNull Spinner s) {
        return QUALITIES[Math.max(0, s.getSelectedItemPosition())];
    }

    /**
     * A labelled dropdown row; returns its Spinner. Kept programmatic, as the rest of the sheet.
     */
    @NonNull
    public static Spinner spinner(@NonNull Context c, @NonNull LinearLayout root, @NonNull String label,
                                  @NonNull String[] options, int initialIndex, int pad) {
        TextView labelView = new TextView(c);
        labelView.setText(label);
        labelView.setTextColor(Studio.INK_FAINT);
        labelView.setTextSize(11);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = pad / 2;
        labelView.setLayoutParams(labelLp);
        root.addView(labelView);

        Spinner spinner = new Spinner(c);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, options) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(Studio.INK);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, initialIndex));
        root.addView(spinner);
        return spinner;
    }

    /** A sheet tick box, with its grey explainer line under it ({@code descRes} 0 = none). */
    @NonNull
    public static CheckBox checkbox(@NonNull Context c, @NonNull LinearLayout root, int labelRes, int descRes, int pad) {
        CheckBox box = new CheckBox(c);
        SheetKit.styleCheckbox(box);
        box.setText(labelRes);
        box.setTextColor(Studio.INK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.setLayoutParams(lp);
        root.addView(box);
        if (descRes != 0) note(c, root, descRes);
        return box;
    }

    /** The grey explainer line under a sheet control. */
    @NonNull
    public static TextView note(@NonNull Context c, @NonNull LinearLayout root, int textRes) {
        TextView desc = new TextView(c);
        desc.setText(textRes);
        desc.setTextColor(Studio.INK_FAINT);
        desc.setTextSize(11);
        root.addView(desc);
        return desc;
    }

    // ── Progress and Stop ─────────────────────────────────────────────

    /** What an export says while it runs (from {@link ExportService}'s broadcasts, on the main thread). */
    public interface Events {
        void onStarted();

        void onProgress(float progress, int item, int items, long bytes, long etaMs, @Nullable String phase);

        void onFinalizing();

        void onCompleted(@Nullable String outputPath, boolean audioOnly, boolean copyToFolderFailed);

        void onError(@Nullable String message, @Nullable String errorClass);

        void onCancelled();
    }

    /** A receiver that turns the export's broadcasts into {@link Events}. Register it with {@link #listen}. */
    @NonNull
    public static BroadcastReceiver receiver(@NonNull Events e) {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case ExportService.ACTION_EXPORT_STARTED:
                        e.onStarted();
                        break;
                    case ExportService.ACTION_EXPORT_PROGRESS:
                        e.onProgress(
                                intent.getFloatExtra(ExportService.EXTRA_PROGRESS, 0f),
                                intent.getIntExtra(ExportService.EXTRA_PROGRESS_ITEM, -1),
                                intent.getIntExtra(ExportService.EXTRA_PROGRESS_ITEMS, -1),
                                intent.getLongExtra(ExportService.EXTRA_PROGRESS_BYTES, -1L),
                                intent.getLongExtra(ExportService.EXTRA_PROGRESS_ETA_MS, -1L),
                                intent.getStringExtra(ExportService.EXTRA_PHASE));
                        break;
                    case ExportService.ACTION_EXPORT_FINALIZING:
                        e.onFinalizing();
                        break;
                    case ExportService.ACTION_EXPORT_COMPLETED:
                        e.onCompleted(intent.getStringExtra(ExportService.EXTRA_OUTPUT_PATH),
                                intent.getBooleanExtra(ExportService.EXTRA_AUDIO_ONLY, false),
                                intent.getBooleanExtra(ExportService.EXTRA_COPY_TO_FOLDER_FAILED, false));
                        break;
                    case ExportService.ACTION_EXPORT_ERROR:
                        e.onError(intent.getStringExtra(ExportService.EXTRA_ERROR_MESSAGE),
                                intent.getStringExtra(ExportService.EXTRA_ERROR_CLASS));
                        break;
                    case ExportService.ACTION_EXPORT_CANCELLED:
                        e.onCancelled();
                        break;
                }
            }
        };
    }

    /** Registers {@code r} for the export's broadcasts (package-scoped, from the :export process). */
    public static void listen(@NonNull Context c, @NonNull BroadcastReceiver r) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ExportService.ACTION_EXPORT_STARTED);
        filter.addAction(ExportService.ACTION_EXPORT_PROGRESS);
        filter.addAction(ExportService.ACTION_EXPORT_FINALIZING);
        filter.addAction(ExportService.ACTION_EXPORT_COMPLETED);
        filter.addAction(ExportService.ACTION_EXPORT_ERROR);
        filter.addAction(ExportService.ACTION_EXPORT_CANCELLED);
        androidx.core.content.ContextCompat.registerReceiver(c, r, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** Stop: a start-intent action reaches the :export process (no binding exists across processes). */
    public static void stop(@NonNull Context c) {
        Intent cancelIntent = new Intent(c, ExportService.class);
        cancelIntent.setAction(ExportService.ACTION_CANCEL_EXPORT);
        c.startService(cancelIntent);
    }
}
