package com.fadcam.ui.faditor;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.documentfile.provider.DocumentFile;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.RecordingStoragePaths;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bottom sheet that presents video source options for Faditor Mini:
 * <ul>
 *   <li>"Browse Device" — opens system file picker for any video</li>
 *   <li>"FadCam Recordings" — lists recordings from the active storage location</li>
 * </ul>
 *
 * <p>Uses the same dark gradient styling as other FadCam bottom sheets
 * ({@code CustomBottomSheetDialogTheme} + {@code gradient_background}).</p>
 */
public class VideoSourceBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "VideoSourceBottomSheet";

    /** Callback interface for video source selection. */
    public interface Callback {
        /** User chose to browse device via system picker. */
        void onBrowseDevice();

        /** User selected a FadCam recording directly. */
        void onRecordingSelected(@NonNull Uri videoUri);

        /**
         * G21/B9: start a BLANK AUDIO project — no video, empty timeline, ready for
         * imported audio. Default no-op so relink-mode users of this sheet are untouched.
         */
        default void onStartBlankAudioProject() { }
    }

    @Nullable
    private Callback callback;

    /** When set, the sheet is in "relink" mode and names the file being sought. */
    @Nullable
    private String lookingForName;

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private LinearLayout recordingsContainer;
    private View loadingIndicator;
    private View emptyState;

    /**
     * Set the callback for video source selection events.
     */
    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    /**
     * Put the sheet in relink mode: the header names the missing file so the
     * user knows exactly which video to find.
     */
    public void setLookingFor(@Nullable String filename) {
        this.lookingForName = filename;
    }

    /**
     * Put the sheet in ADD mode: {@code title} replaces "Start New Project", and the
     * "Blank audio project" row is left out. Picking a video overlay inside the Studio opened
     * this sheet under the new-project title, offering to start an audio project mid-edit.
     */
    public void setAddTitle(@Nullable String title) {
        this.addTitle = title;
    }

    @Nullable private String addTitle;

    // ── Theme & dark styling ─────────────────────────────────────────

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        SheetKit.install(dialog, null);
        return dialog;
    }

    // ── View creation ────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        // Root layout
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, (int) (16 * dp));

        // ── Title ───────────────────────────────────────────────
        boolean relinkMode = lookingForName != null;
        root.addView(SheetKit.header(requireContext(), relinkMode
                ? getString(R.string.faditor_relink_sheet_title)
                : addTitle != null ? addTitle
                : getString(R.string.faditor_start_project), null).view);

        // Subtitle / helper text — in relink mode this names the missing file.
        TextView subtitle = SheetKit.subtitle(requireContext(), relinkMode
                ? getString(R.string.faditor_relink_sheet_sub, lookingForName)
                : getString(R.string.faditor_source_chooser_desc));
        if (relinkMode) subtitle.setTextColor(Studio.CAREFUL);
        root.addView(subtitle);

        // ── Option 1: Browse Device ─────────────────────────────
        root.addView(createBrowseRow(materialIcons, dp));

        // ── Option 2: Blank audio project (G21/B9) ──────────────
        // New-project used to FORCE a video pick, so an audio-first user (podcast,
        // voiceover, music) had to import a video they did not want just to reach a
        // timeline. Not offered in relink mode — that sheet is hunting one specific file.
        if (!relinkMode && addTitle == null) {
            root.addView(createBlankAudioRow(materialIcons, dp));
        }

        // ── Divider ─────────────────────────────────────────────
        root.addView(SheetKit.divider(requireContext()));

        // ── Section: Recordings ─────────────────────────────────
        root.addView(SheetKit.sectionLabel(requireContext(),
                getString(R.string.faditor_your_recordings), 0));

        // Helper subtitle for recordings
        root.addView(SheetKit.subtitle(requireContext(),
                getString(R.string.faditor_recordings_helper)));

        // Loading indicator
        loadingIndicator = createLoadingView(dp);
        root.addView(loadingIndicator);

        // Empty state
        emptyState = createEmptyState(materialIcons, dp);
        emptyState.setVisibility(View.GONE);
        root.addView(emptyState);

        // Scrollable recordings list
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (320 * dp)));

        recordingsContainer = new LinearLayout(requireContext());
        recordingsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(recordingsContainer);
        root.addView(scrollView);

        // Start background scan
        loadRecordings();

        return SheetKit.fitNavBar(root);
    }

    // ── "Browse Device" row ──────────────────────────────────────────

    /**
     * G21/B9 — the "Blank audio project" row, styled identically to Browse Device but with
     * the waveform mark: this start leads to a timeline of waveforms, not pictures.
     */
    @NonNull
    private View createBlankAudioRow(@Nullable Typeface iconFont, float dp) {
        // TODO(strings)
        LinearLayout row = SheetKit.detailRow(requireContext(), "graphic_eq", Studio.AUDIO,
                "Blank audio project", "Podcast, voiceover, music — import audio after",
                SheetKit.chevron(requireContext()), true);
        row.setOnClickListener(v -> {
            dismiss();
            if (callback != null) callback.onStartBlankAudioProject();
        });
        return row;
    }

    /** "Browse Device" — the folder glyph wears VIDEO, the kind of thing it goes to find. */
    @NonNull
    private View createBrowseRow(@Nullable Typeface iconFont, float dp) {
        LinearLayout row = SheetKit.detailRow(requireContext(), "folder_open", Studio.VIDEO,
                getString(R.string.faditor_browse_device),
                getString(R.string.faditor_browse_device_desc),
                SheetKit.chevron(requireContext()), true);
        row.setOnClickListener(v -> {
            dismiss();
            if (callback != null) callback.onBrowseDevice();
        });
        return row;
    }

    // ── Recording scanning ───────────────────────────────────────────

    private void loadRecordings() {
        scanExecutor.execute(() -> {
            List<RecordingItem> items = scanActiveStorage();
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> displayRecordings(items));
        });
    }

    /**
     * Scan <b>only</b> the currently active storage location.
     *
     * <p>If the user has "custom" storage mode selected, scans the SAF tree.
     * Otherwise, scans the internal FadCam/FadRec directories.</p>
     */
    @NonNull
    private List<RecordingItem> scanActiveStorage() {
        List<RecordingItem> items = new ArrayList<>();
        if (!isAdded()) return items;

        Context ctx = requireContext();
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(ctx);
        String storageMode = prefs.getStorageMode();

        FLog.d(TAG, "Storage mode: " + storageMode);

        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            // ── Custom SAF storage ──────────────────────────────
            String safUri = prefs.getCustomStorageUri();
            if (safUri != null) {
                try {
                    Uri treeUri = Uri.parse(safUri);
                    if (hasSafPermission(ctx, treeUri)) {
                        items.addAll(scanSafDir(ctx, treeUri));
                        FLog.d(TAG, "Scanned custom (SAF) storage: " + items.size() + " videos");
                    } else {
                        FLog.w(TAG, "No SAF permission for: " + safUri);
                    }
                } catch (Exception e) {
                    FLog.e(TAG, "Error scanning SAF storage", e);
                }
            } else {
                FLog.w(TAG, "Custom storage mode but no URI set");
            }
        } else {
            // ── Internal storage (default) ──────────────────────
            try {
                File externalDir = ctx.getExternalFilesDir(null);
                if (externalDir != null) {
                    File base = new File(externalDir, Constants.RECORDING_DIRECTORY);
                    items.addAll(scanCameraDir(
                            new File(base, Constants.RECORDING_SUBDIR_CAMERA),
                            getString(R.string.faditor_fadcam_source)));
                    // Legacy dual root folder compatibility
                    items.addAll(scanFileDir(
                            new File(base, Constants.RECORDING_SUBDIR_DUAL),
                            getString(R.string.faditor_fadcam_source)));
                    items.addAll(scanFileDir(
                            new File(base, Constants.RECORDING_SUBDIR_SCREEN),
                            getString(R.string.faditor_fadrec_source)));
                    items.addAll(scanNestedVideoDir(
                            new File(base, Constants.RECORDING_SUBDIR_FADITOR),
                            getString(R.string.faditor_fadcam_source)));
                    items.addAll(scanFileDir(
                            new File(base, Constants.RECORDING_SUBDIR_STREAM),
                            getString(R.string.faditor_fadrec_source)));
                    FLog.d(TAG, "Scanned internal storage: " + items.size() + " videos");
                }
            } catch (Exception e) {
                FLog.e(TAG, "Error scanning internal storage", e);
            }
        }

        // Sort newest first
        Collections.sort(items, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return items;
    }

    @NonNull
    private List<RecordingItem> scanFileDir(@NonNull File dir, @NonNull String source) {
        List<RecordingItem> items = new ArrayList<>();
        if (!dir.exists() || !dir.isDirectory()) return items;

        File[] files = dir.listFiles();
        if (files == null) return items;

        for (File f : files) {
            if (f.isFile()
                    && f.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)
                    && !f.getName().startsWith("temp_")) {
                items.add(new RecordingItem(
                        Uri.fromFile(f), f.getName(), f.length(), f.lastModified(), source));
            }
        }
        return items;
    }

    @NonNull
    private List<RecordingItem> scanCameraDir(@NonNull File cameraRoot, @NonNull String source) {
        List<RecordingItem> items = new ArrayList<>();
        if (!cameraRoot.exists() || !cameraRoot.isDirectory()) return items;

        File[] entries = cameraRoot.listFiles();
        if (entries == null) return items;
        for (File entry : entries) {
            if (entry == null) continue;
            if (entry.isFile()) {
                if (entry.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)
                        && !entry.getName().startsWith("temp_")) {
                    items.add(new RecordingItem(
                            Uri.fromFile(entry), entry.getName(), entry.length(), entry.lastModified(), source));
                }
                continue;
            }
            if (!entry.isDirectory()) continue;
            items.addAll(scanFileDir(entry, source));
        }
        return items;
    }

    @NonNull
    private List<RecordingItem> scanNestedVideoDir(@NonNull File rootDir, @NonNull String source) {
        List<RecordingItem> items = new ArrayList<>();
        if (!rootDir.exists() || !rootDir.isDirectory()) return items;
        File[] entries = rootDir.listFiles();
        if (entries == null) return items;
        for (File entry : entries) {
            if (entry == null) continue;
            if (entry.isFile()) {
                if (entry.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)
                        && !entry.getName().startsWith("temp_")) {
                    items.add(new RecordingItem(
                            Uri.fromFile(entry), entry.getName(), entry.length(), entry.lastModified(), source));
                }
                continue;
            }
            if (!entry.isDirectory()) continue;
            items.addAll(scanFileDir(entry, source));
        }
        return items;
    }

    @NonNull
    private List<RecordingItem> scanSafDir(@NonNull Context ctx, @NonNull Uri treeUri) {
        List<RecordingItem> items = new ArrayList<>();
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(ctx, treeUri);
            if (dir == null || !dir.isDirectory() || !dir.canRead()) return items;
            addSafCameraDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_CAMERA, getString(R.string.faditor_fadcam_source));
            addSafDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_DUAL, getString(R.string.faditor_fadcam_source));
            addSafDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_SCREEN, getString(R.string.faditor_fadrec_source));
            addSafNestedDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_FADITOR, getString(R.string.faditor_fadcam_source));
            addSafDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_STREAM, getString(R.string.faditor_fadrec_source));
        } catch (Exception e) {
            FLog.e(TAG, "Error listing SAF files", e);
        }
        return items;
    }

    private void addSafDirectoryItems(
            @NonNull List<RecordingItem> items,
            @NonNull DocumentFile baseDir,
            @NonNull String directoryName,
            @NonNull String source
    ) {
        DocumentFile child = RecordingStoragePaths.findOrCreateChildDirectory(baseDir, directoryName, false);
        if (child == null || !child.isDirectory() || !child.canRead()) return;
        for (DocumentFile doc : child.listFiles()) {
            if (doc == null || !doc.isFile()) continue;
            String name = doc.getName();
            String mime = doc.getType();
            if (name != null && mime != null && mime.startsWith("video/")
                    && name.endsWith(Constants.RECORDING_FILE_EXTENSION)
                    && !name.startsWith("temp_")) {
                items.add(new RecordingItem(
                        doc.getUri(), name, doc.length(), doc.lastModified(), source));
            }
        }
    }

    private void addSafCameraDirectoryItems(
            @NonNull List<RecordingItem> items,
            @NonNull DocumentFile baseDir,
            @NonNull String directoryName,
            @NonNull String source
    ) {
        DocumentFile child = RecordingStoragePaths.findOrCreateChildDirectory(baseDir, directoryName, false);
        if (child == null || !child.isDirectory() || !child.canRead()) return;
        for (DocumentFile doc : child.listFiles()) {
            if (doc == null) continue;
            if (doc.isFile()) {
                String name = doc.getName();
                String mime = doc.getType();
                if (name != null && mime != null && mime.startsWith("video/")
                        && name.endsWith(Constants.RECORDING_FILE_EXTENSION)
                        && !name.startsWith("temp_")) {
                    items.add(new RecordingItem(doc.getUri(), name, doc.length(), doc.lastModified(), source));
                }
                continue;
            }
            if (!doc.isDirectory() || !doc.canRead()) continue;
            for (DocumentFile nested : doc.listFiles()) {
                if (nested == null || !nested.isFile()) continue;
                String name = nested.getName();
                String mime = nested.getType();
                if (name != null && mime != null && mime.startsWith("video/")
                        && name.endsWith(Constants.RECORDING_FILE_EXTENSION)
                        && !name.startsWith("temp_")) {
                    items.add(new RecordingItem(nested.getUri(), name, nested.length(), nested.lastModified(), source));
                }
            }
        }
    }

    private void addSafNestedDirectoryItems(
            @NonNull List<RecordingItem> items,
            @NonNull DocumentFile baseDir,
            @NonNull String directoryName,
            @NonNull String source
    ) {
        DocumentFile child = RecordingStoragePaths.findOrCreateChildDirectory(baseDir, directoryName, false);
        if (child == null || !child.isDirectory() || !child.canRead()) return;
        for (DocumentFile doc : child.listFiles()) {
            if (doc == null) continue;
            if (doc.isFile()) {
                String name = doc.getName();
                String mime = doc.getType();
                if (name != null && mime != null && mime.startsWith("video/")
                        && name.endsWith(Constants.RECORDING_FILE_EXTENSION)
                        && !name.startsWith("temp_")) {
                    items.add(new RecordingItem(doc.getUri(), name, doc.length(), doc.lastModified(), source));
                }
                continue;
            }
            if (!doc.isDirectory() || !doc.canRead()) continue;
            for (DocumentFile nested : doc.listFiles()) {
                if (nested == null || !nested.isFile()) continue;
                String name = nested.getName();
                String mime = nested.getType();
                if (name != null && mime != null && mime.startsWith("video/")
                        && name.endsWith(Constants.RECORDING_FILE_EXTENSION)
                        && !name.startsWith("temp_")) {
                    items.add(new RecordingItem(nested.getUri(), name, nested.length(), nested.lastModified(), source));
                }
            }
        }
    }

    private boolean hasSafPermission(@NonNull Context ctx, @NonNull Uri treeUri) {
        try {
            for (android.content.UriPermission perm :
                    ctx.getContentResolver().getPersistedUriPermissions()) {
                if (perm.getUri().equals(treeUri) && perm.isReadPermission()) return true;
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error checking SAF permission", e);
        }
        return false;
    }

    // ── Display recordings ───────────────────────────────────────────

    private void displayRecordings(@NonNull List<RecordingItem> items) {
        if (recordingsContainer == null) return;

        loadingIndicator.setVisibility(View.GONE);
        recordingsContainer.removeAllViews();

        if (items.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            return;
        }

        emptyState.setVisibility(View.GONE);
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

        for (RecordingItem item : items) {
            View row = createRecordingRow(item, materialIcons, dateFormat);
            recordingsContainer.addView(row);
        }
    }

    /**
     * Create a single recording row with thumbnail, name, duration, and size.
     * Matches the visual pattern of the Records tab.
     */
    @NonNull
    private View createRecordingRow(@NonNull RecordingItem item,
                                    @Nullable Typeface iconFont,
                                    @NonNull SimpleDateFormat dateFormat) {
        float dp = getResources().getDisplayMetrics().density;
        Context ctx = requireContext();

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (8 * dp), (int) (8 * dp),
                (int) (12 * dp), (int) (8 * dp));
        row.setBackground(SheetKit.rowBackground(ctx));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(SheetKit.dp(ctx, SheetKit.ROW_INSET_DP), SheetKit.dp(ctx, 2),
                SheetKit.dp(ctx, SheetKit.ROW_INSET_DP), SheetKit.dp(ctx, 2));
        row.setLayoutParams(rowLp);
        SheetKit.press(row);
        SheetKit.label(row, item.name);

        // ── Thumbnail (rounded corners via CardView-like clipping) ────
        androidx.cardview.widget.CardView thumbCard = new androidx.cardview.widget.CardView(ctx);
        thumbCard.setCardElevation(0);
        thumbCard.setCardBackgroundColor(Studio.SUNK);
        // Concentric inside the row: 8dp row corner minus the 8dp inset leaves a tight 4dp.
        thumbCard.setRadius(4 * dp);
        LinearLayout.LayoutParams thumbCardLp = new LinearLayout.LayoutParams(
                (int) (80 * dp), (int) (50 * dp));
        thumbCardLp.setMarginEnd((int) (12 * dp));
        thumbCard.setLayoutParams(thumbCardLp);

        ImageView thumbView = new ImageView(ctx);
        thumbView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbView.setImageResource(R.drawable.ic_video_placeholder);
        thumbCard.addView(thumbView);

        // Load thumbnail with Glide
        try {
            Glide.with(ctx)
                    .asBitmap()
                    .load(item.uri)
                    .apply(new RequestOptions()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(200, 125)
                            .centerCrop()
                            .placeholder(R.drawable.ic_video_placeholder)
                            .error(R.drawable.ic_video_placeholder))
                    .thumbnail(0.1f)
                    .into(thumbView);
        } catch (Exception e) {
            FLog.w(TAG, "Error loading thumbnail for: " + item.name, e);
        }

        row.addView(thumbCard);

        // ── Text section ─────────────────────────────────────────
        LinearLayout textSection = new LinearLayout(ctx);
        textSection.setOrientation(LinearLayout.VERTICAL);
        textSection.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Filename (without extension)
        TextView nameView = new TextView(ctx);
        String displayName = item.name;
        if (displayName.endsWith(".mp4")) {
            displayName = displayName.substring(0, displayName.length() - 4);
        }
        nameView.setText(displayName);
        nameView.setTextColor(Studio.INK);
        nameView.setTextSize(13);
        com.fadcam.ui.type.Type.body(nameView, com.fadcam.ui.type.Type.SEMIBOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        textSection.addView(nameView);

        // Meta line: size · duration · source
        TextView metaView = new TextView(ctx);
        String meta = formatSize(item.size) + " · " + item.source;
        metaView.setText(meta);
        metaView.setTextColor(Studio.INK_FAINT);
        metaView.setTextSize(10);
        com.fadcam.ui.type.Type.mono(metaView, com.fadcam.ui.type.Type.REGULAR);
        metaView.setMaxLines(1);
        textSection.addView(metaView);

        // Load duration on background thread and update
        final TextView durationMeta = metaView;
        scanExecutor.execute(() -> {
            long durationMs = getVideoDuration(ctx, item.uri);
            if (!isAdded()) return;
            String durationStr = formatDuration(durationMs);
            String fullMeta = formatSize(item.size) + " · " + durationStr + " · " + item.source;
            requireActivity().runOnUiThread(() -> durationMeta.setText(fullMeta));
        });

        row.addView(textSection);

        // ── Edit icon ────────────────────────────────────────────
        TextView editIcon = new TextView(ctx);
        editIcon.setTypeface(iconFont);
        editIcon.setText("edit");
        // A resting glyph, not GO: the whole row is the button, this only says what it does.
        editIcon.setTextColor(SheetKit.ROW_ICON);
        editIcon.setTextSize(18);
        editIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                (int) (32 * dp), (int) (32 * dp));
        editLp.setMarginStart((int) (8 * dp));
        editIcon.setLayoutParams(editLp);
        row.addView(editIcon);

        row.setOnClickListener(v -> {
            dismiss();
            if (callback != null) callback.onRecordingSelected(item.uri);
        });

        return row;
    }

    // ── Helper views ─────────────────────────────────────────────────

    @NonNull
    private View createLoadingView(float dp) {
        TextView tv = new TextView(requireContext());
        tv.setText(R.string.faditor_scanning);
        tv.setTextColor(Studio.INK_FAINT);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, (int) (24 * dp), 0, (int) (24 * dp));
        return tv;
    }

    @NonNull
    private View createEmptyState(@Nullable Typeface iconFont, float dp) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding((int) (24 * dp), (int) (24 * dp),
                (int) (24 * dp), (int) (24 * dp));

        TextView icon = new TextView(requireContext());
        icon.setTypeface(iconFont);
        icon.setText("videocam_off");
        icon.setTextColor(Studio.INK_OFF);
        icon.setTextSize(32);
        icon.setGravity(Gravity.CENTER);
        layout.addView(icon);

        TextView msg = new TextView(requireContext());
        msg.setText(R.string.faditor_no_recordings);
        msg.setTextColor(Studio.INK_FAINT);
        msg.setTextSize(13);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, (int) (8 * dp), 0, 0);
        layout.addView(msg);

        return layout;
    }

    // ── Utility methods ──────────────────────────────────────────────

    /**
     * Retrieve video duration using FFprobeKit (primary) with MediaMetadataRetriever fallback.
     * Mirrors the approach used by RecordsAdapter for reliable duration on all URI schemes.
     *
     * @return duration in milliseconds, or 0 on error
     */
    private static long getVideoDuration(@NonNull Context ctx, @NonNull Uri uri) {
        // ── FFprobeKit (primary – matches RecordsAdapter) ────────
        try {
            String filePath = getFFprobePathForUri(uri);
            com.arthenica.ffmpegkit.MediaInformationSession session =
                    com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(filePath);
            com.arthenica.ffmpegkit.MediaInformation info = session.getMediaInformation();

            if (info != null) {
                String durationStr = info.getDuration();
                if (durationStr != null) {
                    double durationSec = Double.parseDouble(durationStr);
                    long durationMs = (long) (durationSec * 1000);
                    FLog.d(TAG, "Duration from FFprobe: " + durationMs + "ms for " + uri.getLastPathSegment());
                    return durationMs;
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "FFprobe duration failed for: " + uri, e);
        }

        // ── MediaMetadataRetriever fallback ──────────────────────
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();

            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                retriever.setDataSource(uri.getPath());
            } else {
                retriever.setDataSource(ctx, uri);
            }

            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                FLog.d(TAG, "Duration from MMR fallback: " + durationMs + "ms for " + uri.getLastPathSegment());
                return durationMs;
            }
        } catch (Exception e) {
            FLog.w(TAG, "MMR fallback failed for: " + uri, e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) { }
            }
        }

        FLog.w(TAG, "Could not determine duration for: " + uri);
        return 0;
    }

    /**
     * Build a file path suitable for FFprobeKit.
     * For content:// URIs (SAF), reconstructs a /storage/emulated/0 path when possible,
     * otherwise falls back to saf: protocol.
     */
    private static String getFFprobePathForUri(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return uri.getPath();
        }

        // For content:// URIs, try to reconstruct actual file path
        String path = uri.getPath();
        if (path != null && path.contains(":")) {
            int lastColonIndex = path.lastIndexOf(':');
            if (lastColonIndex >= 0 && lastColonIndex < path.length() - 1) {
                String relativePath = path.substring(lastColonIndex + 1);
                String reconstructedPath = "/storage/emulated/0/" + relativePath;
                java.io.File file = new java.io.File(reconstructedPath);
                if (file.exists() && file.canRead()) {
                    FLog.d(TAG, "FFprobe using reconstructed path: " + reconstructedPath);
                    return reconstructedPath;
                }
            }
        }

        // Fall back to SAF protocol
        FLog.d(TAG, "FFprobe using SAF protocol: saf:" + uri);
        return "saf:" + uri.toString();
    }

    @NonNull
    private static String formatDuration(long durationMs) {
        if (durationMs <= 0) return "0s";
        long totalSeconds = durationMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format(Locale.US, "%dm %02ds", minutes, seconds);
        } else {
            return String.format(Locale.US, "%ds", seconds);
        }
    }

    @NonNull
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ── Model ────────────────────────────────────────────────────────

    private static class RecordingItem {
        @NonNull final Uri uri;
        @NonNull final String name;
        final long size;
        final long lastModified;
        @NonNull final String source;

        RecordingItem(@NonNull Uri uri, @NonNull String name,
                      long size, long lastModified, @NonNull String source) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
            this.source = source;
        }
    }
}
