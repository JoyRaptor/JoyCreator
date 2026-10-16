package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.FaditorInfoBottomSheet;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry-point fragment for the Faditor Mini tab.
 *
 * <p>Shows a hero section with "Start Project" button, recent projects list
 * with video thumbnails and multi-select deletion, and feature capability
 * cards. Launches {@link FaditorEditorActivity} for full-screen editing
 * via a video source bottom sheet.</p>
 */
public class FaditorMiniFragment extends BaseFragment {

    private static final String TAG = "FaditorMiniFragment";

    /** Starting a project: the source sheet, the picker, naming and building (shared with the Library). */
    private com.fadcam.ui.studio.NewProjectFlow newProject;

    /** Launcher for the editor Activity result. */
    private ActivityResultLauncher<Intent> editorLauncher;

    private ProjectStorage projectStorage;
    private LinearLayout recentProjectsSection;
    private LinearLayout recentProjectsList;
    private View emptyStateContainer;
    @Nullable private View joyFolderBanner;

    // ── Selection mode ───────────────────────────────────────────────
    private boolean selectionMode = false;
    private final Set<String> selectedIds = new HashSet<>();
    private List<ProjectStorage.ProjectSummary> currentProjects = new ArrayList<>();

    private View selectionActionBar;
    private TextView tvSelectionCount;
    private CheckBox cbSelectAll;
    private View btnSelectMode;
    private View btnDeleteSelected;
    private View btnCancelSelection;

    // ── Export dock ──────────────────────────────────────────────────
    private View exportDock;
    private ImageView exportDockIcon;
    private com.fadcam.ui.utils.AnimatedTextView exportDockTitle;
    private com.google.android.material.progressindicator.LinearProgressIndicator exportDockProgress;
    private TextView exportDockCurrent;
    private TextView exportDockEta;
    private TextView exportDockSummary;
    private com.fadcam.ui.utils.AnimatedTextView exportDockOk;
    /** The finished-video row: thumbnail (tap = play) + "view in Recordings". Completion only. */
    private View exportDockResult;
    private View exportDockThumbWrap;
    private ImageView exportDockThumb;
    private TextView exportDockOpenRecords;
    /** Path of the most recent completed export, for the two actions on that row. */
    @Nullable private String lastExportPath;
    private long exportStartTimeMs = 0;
    private float lastReportedProgress = 0f;
    
    // ── Export progress broadcast receiver ────────────────────────────
    private android.content.BroadcastReceiver exportProgressReceiver;
    private boolean isExportReceiverRegistered = false;
    private boolean isExportActive = false; // Track if export is currently running

    /** Background thread for loading thumbnails. */
    private final ExecutorService thumbnailExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FLog.d("FaditorMiniFragment", "✅ onCreate called!");
        projectStorage = new ProjectStorage(requireContext());

        // Initialize the broadcast receiver early
        initializeExportProgressReceiver();
        
        // Register receiver immediately in onCreate so it persists across fragment lifecycle
        if (exportProgressReceiver != null && !isExportReceiverRegistered) {
            try {
                FLog.d("FaditorMiniFragment", "Registering receiver in onCreate");
                android.content.IntentFilter filter = new android.content.IntentFilter();
                filter.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_STARTED);
                filter.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_PROGRESS);
                filter.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_COMPLETED);
                filter.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_ERROR);
                filter.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_CANCELLED);
                // OOP export: the service broadcasts globally (package-scoped) from its
                // :export process — LocalBroadcastManager is in-process-only and can't hear it.
                androidx.core.content.ContextCompat.registerReceiver(requireContext(),
                    exportProgressReceiver, filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
                isExportReceiverRegistered = true;
                FLog.d("FaditorMiniFragment", "✅ Export receiver registered in onCreate");
            } catch (Exception e) {
                FLog.e("FaditorMiniFragment", "Error registering export receiver in onCreate: " + e.getMessage(), e);
            }
        }

        // Register editor result handler
        editorLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        FLog.d(TAG, "Editor returned successfully");
                    }
                    // Refresh recent projects list when returning
                    refreshRecentProjects();
                }
        );
        // The picker is registered by the flow, after the editor launcher it reports back through.
        newProject = new com.fadcam.ui.studio.NewProjectFlow(this, editorLauncher);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        FLog.d("FaditorMiniFragment", "✅ onCreateView called!");
        View view = inflater.inflate(R.layout.fragment_faditor_mini, container, false);

        // Request focus on first focusable element for D-pad/TV remote support.
        view.post(() -> {
            View first = com.fadcam.ui.utils.DpadSettingsFocusHelper.findFirstFocusable(view);
            if (first != null) first.requestFocus();
        });

        // Setup "Start Project" button → opens video source chooser
        View selectButton = view.findViewById(R.id.btn_select_video);
        if (selectButton != null) {
            selectButton.setOnClickListener(v -> newProject.start());
        }

        // Avatar Studio, standalone (library-backed — no project needed): avatars
        // serve both the screen recorder and the editor, so the studio's front
        // door lives on this main surface next to the editor branding.
        View btnAvatarStudio = view.findViewById(R.id.btn_avatar_studio);
        if (btnAvatarStudio != null) {
            androidx.appcompat.widget.TooltipCompat.setTooltipText(btnAvatarStudio, "Avatar Studio");
            btnAvatarStudio.setOnClickListener(v -> startActivity(new android.content.Intent(
                    requireContext(),
                    com.fadcam.ui.faditor.avatar.AvatarStudioActivity.class)));
        }

        // Info icon → opens Faditor info bottom sheet
        View btnInfo = view.findViewById(R.id.btn_info);
        if (btnInfo != null) {
            androidx.appcompat.widget.TooltipCompat.setTooltipText(btnInfo, btnInfo.getContext().getString(R.string.video_menu_info));
            btnInfo.setOnClickListener(v -> {
                FaditorInfoBottomSheet.newInstance()
                        .show(getChildFragmentManager(), "faditor_info");
            });
        }

        recentProjectsSection = view.findViewById(R.id.recent_projects_section);
        recentProjectsList = view.findViewById(R.id.recent_projects_list);
        emptyStateContainer = view.findViewById(R.id.empty_state_container);

        // "Keep your work safe": shown only until a Joy Creator folder is chosen and reachable.
        if (recentProjectsSection != null && recentProjectsSection.getParent() instanceof ViewGroup) {
            ViewGroup host = (ViewGroup) recentProjectsSection.getParent();
            joyFolderBanner = com.fadcam.joyfolder.studio.JoyFolderBanner.attach(
                    host, host.indexOfChild(recentProjectsSection), 16);
        }

        // Selection UI
        selectionActionBar = view.findViewById(R.id.selection_action_bar);
        tvSelectionCount = view.findViewById(R.id.tv_selection_count);
        cbSelectAll = view.findViewById(R.id.cb_select_all);
        btnSelectMode = view.findViewById(R.id.btn_select_mode);
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected);
        btnCancelSelection = view.findViewById(R.id.btn_cancel_selection);

        // Toggle selection mode
        if (btnSelectMode != null) {
            androidx.appcompat.widget.TooltipCompat.setTooltipText(btnSelectMode, btnSelectMode.getContext().getString(R.string.desc_select_mode));
            btnSelectMode.setContentDescription(btnSelectMode.getContext().getString(R.string.desc_select_mode));
            btnSelectMode.setOnClickListener(v -> toggleSelectionMode());
        }
        if (btnCancelSelection != null) {
            androidx.appcompat.widget.TooltipCompat.setTooltipText(btnCancelSelection, btnCancelSelection.getContext().getString(R.string.desc_cancel_selection));
            btnCancelSelection.setContentDescription(btnCancelSelection.getContext().getString(R.string.desc_cancel_selection));
            btnCancelSelection.setOnClickListener(v -> exitSelectionMode());
        }
        if (btnDeleteSelected != null) {
            androidx.appcompat.widget.TooltipCompat.setTooltipText(btnDeleteSelected, btnDeleteSelected.getContext().getString(R.string.desc_delete_selected));
            btnDeleteSelected.setContentDescription(btnDeleteSelected.getContext().getString(R.string.desc_delete_selected));
            btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        }
        if (cbSelectAll != null) {
            cbSelectAll.setOnCheckedChangeListener((btn, checked) -> {
                if (!btn.isPressed()) return; // Only respond to user clicks
                if (checked) {
                    for (ProjectStorage.ProjectSummary p : currentProjects) {
                        selectedIds.add(p.id);
                    }
                } else {
                    selectedIds.clear();
                }
                updateSelectionUI();
                refreshProjectRowCheckboxes();
            });
        }

        // ── Export dock initialization ────────────────────────────────
        exportDock = view.findViewById(R.id.faditor_export_dock);
        exportDockIcon = view.findViewById(R.id.faditor_export_dock_icon);
        exportDockTitle = view.findViewById(R.id.faditor_export_dock_title);
        exportDockProgress = view.findViewById(R.id.faditor_export_dock_progress);
        exportDockCurrent = view.findViewById(R.id.faditor_export_dock_current);
        exportDockEta = view.findViewById(R.id.faditor_export_dock_eta);
        exportDockSummary = view.findViewById(R.id.faditor_export_dock_summary);
        exportDockOk = view.findViewById(R.id.faditor_export_dock_ok);
        exportDockResult = view.findViewById(R.id.faditor_export_dock_result);
        exportDockThumbWrap = view.findViewById(R.id.faditor_export_dock_thumb_wrap);
        exportDockThumb = view.findViewById(R.id.faditor_export_dock_thumb);
        exportDockOpenRecords = view.findViewById(R.id.faditor_export_dock_open_records);
        if (exportDockThumbWrap != null) {
            exportDockThumbWrap.setOnClickListener(v -> playLastExport());
        }
        if (exportDockOpenRecords != null) {
            exportDockOpenRecords.setOnClickListener(v -> openRecordsTab());
        }

        // OK/Cancel button: Cancel during export, dismiss after completion
        if (exportDockOk != null) {
            exportDockOk.setOnClickListener(v -> {
                if (isExportActive) {
                    // Send cancel intent to ExportService
                    Intent cancelIntent = new Intent(requireContext(), 
                        com.fadcam.ui.faditor.export.ExportService.class);
                    cancelIntent.setAction(com.fadcam.ui.faditor.export.ExportService.ACTION_CANCEL_EXPORT);
                    requireContext().startService(cancelIntent);
                    FLog.d(TAG, "Sent cancel intent to ExportService");
                } else {
                    // After completion/error/cancel, dismiss the dock
                    hideDockWithAnimation();
                }
            });
        }

        return view;
    }

    /**
     * Initialize the broadcast receiver for export progress updates.
     * Called from onCreateView to set up the receiver instance.
     */
    private void initializeExportProgressReceiver() {
        if (exportProgressReceiver != null) return; // Already initialized
        
        FLog.d("FaditorMiniFragment", "Initializing export progress broadcast receiver...");

        exportProgressReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (!isAdded()) {
                    FLog.d("FaditorMiniFragment", "Received broadcast but fragment not added, ignoring");
                    return; // Fragment detached
                }
                
                String action = intent.getAction();
                if (action == null) {
                    FLog.d("FaditorMiniFragment", "Received broadcast with null action");
                    return;
                }
                
                FLog.d("FaditorMiniFragment", "Export broadcast received: action=" + action);

                if (com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_STARTED.equals(action)) {
                    String outputPath = intent.getStringExtra(
                        com.fadcam.ui.faditor.export.ExportService.EXTRA_OUTPUT_PATH);
                    FLog.d("FaditorMiniFragment", "Export started: " + outputPath);
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportStarted(outputPath));

                } else if (com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_PROGRESS.equals(action)) {
                    float progress = intent.getFloatExtra(
                        com.fadcam.ui.faditor.export.ExportService.EXTRA_PROGRESS, 0f);
                    FLog.d("FaditorMiniFragment", "Export progress: " + progress);
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportProgress(progress));

                } else if (com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_COMPLETED.equals(action)) {
                    String outputPath = intent.getStringExtra(
                        com.fadcam.ui.faditor.export.ExportService.EXTRA_OUTPUT_PATH);
                    FLog.d("FaditorMiniFragment", "Export completed: " + outputPath);
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportCompleted(outputPath));

                } else if (com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_ERROR.equals(action)) {
                    String errorMsg = intent.getStringExtra(
                        com.fadcam.ui.faditor.export.ExportService.EXTRA_ERROR_MESSAGE);
                    FLog.d("FaditorMiniFragment", "Export error: " + errorMsg);
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportError(errorMsg != null ? errorMsg : "Unknown error"));

                } else if (com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_CANCELLED.equals(action)) {
                    FLog.d("FaditorMiniFragment", "Export cancelled");
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportCancelled());
                }
            }
        };
        FLog.d("FaditorMiniFragment", "Broadcast receiver initialized successfully");
    }

    @Override
    public void onResume() {
        super.onResume();
        FLog.d("FaditorMiniFragment", "onResume called");
        refreshRecentProjects();
        com.fadcam.joyfolder.studio.JoyFolderBanner.refresh(joyFolderBanner);
    }

    @Override
    public void onPause() {
        super.onPause();
        FLog.d("FaditorMiniFragment", "onPause called");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Ensure broadcast receiver is unregistered
        if (exportProgressReceiver != null && isExportReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(exportProgressReceiver);
            } catch (Exception e) {
                FLog.w("FaditorMiniFragment", "Error unregistering export receiver: " + e.getMessage());
            }
            isExportReceiverRegistered = false;
        }
        
        thumbnailExecutor.shutdownNow();
    }

    // ── Selection Mode ───────────────────────────────────────────────

    private void toggleSelectionMode() {
        if (selectionMode) {
            exitSelectionMode();
        } else {
            enterSelectionMode();
        }
    }

    private void enterSelectionMode() {
        selectionMode = true;
        selectedIds.clear();
        if (selectionActionBar != null) selectionActionBar.setVisibility(View.VISIBLE);
        updateSelectionUI();
        refreshRecentProjects();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedIds.clear();
        if (selectionActionBar != null) selectionActionBar.setVisibility(View.GONE);
        if (cbSelectAll != null) cbSelectAll.setChecked(false);
        refreshRecentProjects();
    }

    private void updateSelectionUI() {
        int count = selectedIds.size();
        if (tvSelectionCount != null) {
            if (count == 0) {
                tvSelectionCount.setText(R.string.faditor_select_items);
            } else {
                tvSelectionCount.setText(getString(R.string.faditor_selected_count, count));
            }
        }
        if (btnDeleteSelected != null) {
            btnDeleteSelected.setAlpha(count > 0 ? 1.0f : 0.3f);
            btnDeleteSelected.setEnabled(count > 0);
        }
        // Update select-all checkbox without triggering listener
        if (cbSelectAll != null && !currentProjects.isEmpty()) {
            boolean allSelected = selectedIds.size() == currentProjects.size();
            if (cbSelectAll.isChecked() != allSelected) {
                cbSelectAll.setOnCheckedChangeListener(null);
                cbSelectAll.setChecked(allSelected);
                cbSelectAll.setOnCheckedChangeListener((btn, checked) -> {
                    if (!btn.isPressed()) return;
                    if (checked) {
                        for (ProjectStorage.ProjectSummary p : currentProjects) {
                            selectedIds.add(p.id);
                        }
                    } else {
                        selectedIds.clear();
                    }
                    updateSelectionUI();
                    refreshProjectRowCheckboxes();
                });
            }
        }
    }

    private void refreshProjectRowCheckboxes() {
        if (recentProjectsList == null) return;
        for (int i = 0; i < recentProjectsList.getChildCount(); i++) {
            View child = recentProjectsList.getChildAt(i);
            CheckBox cb = child.findViewWithTag("checkbox");
            String projectId = (String) child.getTag();
            if (cb != null && projectId != null) {
                cb.setChecked(selectedIds.contains(projectId));
            }
        }
    }

    /** With a Joy Creator folder, a delete is one tap from undone: "Moved to Trash · Undo". */
    private void offerUndoDelete(@NonNull java.util.List<String> deletedIds) {
        View root = getView();
        if (root == null || deletedIds.isEmpty()
                || !com.fadcam.joyfolder.android.JoyFolderHome.isReachable(requireContext())) return;
        String msg = deletedIds.size() == 1 ? "Project moved to Trash" : deletedIds.size() + " projects moved to Trash";
        com.google.android.material.snackbar.Snackbar.make(root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setDuration(8000)   // long enough to notice and reach; the item is in Trash either way
                .setActionTextColor(0xFF22D3EE)
                .setAction("Undo", v -> com.fadcam.joyfolder.studio.StudioMirror.undoDelete(requireContext(), deletedIds, n -> {
                    if (!isAdded()) return;
                    refreshRecentProjects();
                    Toast.makeText(requireContext(), n == 1 ? "Project put back" : n + " projects put back",
                            Toast.LENGTH_SHORT).show();
                }))
                .show();
    }

    private void confirmDeleteSelected() {
        int count = selectedIds.size();
        if (count == 0) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.faditor_delete_projects_title)
                .setMessage(com.fadcam.joyfolder.android.JoyFolderHome.isReachable(requireContext())
                        // With a Joy Creator folder, deleting is no longer final: the folder copy goes to Trash.
                        ? (count == 1
                                ? "Delete this project? You can put it back from Trash."
                                : "Delete " + count + " projects? You can put them back from Trash.")
                        : getString(R.string.faditor_delete_projects_message, count))
                .setPositiveButton(R.string.faditor_delete_confirm, (dialog, which) -> {
                    final java.util.List<String> deletedIds = new java.util.ArrayList<>(selectedIds);
                    for (String id : selectedIds) {
                        projectStorage.delete(id);
                    }
                    exitSelectionMode();
                    refreshRecentProjects();
                    offerUndoDelete(deletedIds);
                })
                .setNegativeButton(R.string.faditor_cancel, null)
                .show();
    }

    // ── Recent Projects ──────────────────────────────────────────────

    /**
     * Populate the recent projects list from saved projects.
     */
    /** The dot's question, per file: Copy, Move or Leave (BringInDialog), for a closed project. */
    private void offerBringIn(@NonNull String projectId,
                              @NonNull com.fadcam.joyfolder.studio.StudioMirror.Outside outside) {
        final android.app.Activity activity = requireActivity();
        final android.content.Context app = activity.getApplicationContext();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        com.fadcam.joyfolder.android.JoyFolderHome.io().execute(() -> {
            java.util.List<String> refs = com.fadcam.joyfolder.studio.StudioMirror.outsideFiles(app, projectId);
            main.post(() -> {
                if (!isAdded()) return;
                if (refs.isEmpty()) { refreshRecentProjects(); return; }
                com.fadcam.joyfolder.studio.BringInDialog.show(activity, refs, choices -> {
                    java.util.List<String> take = new java.util.ArrayList<>();
                    java.util.Set<String> moving = new java.util.HashSet<>();
                    for (java.util.Map.Entry<String, com.fadcam.joyfolder.studio.BringInDialog.Choice> e : choices.entrySet()) {
                        if (e.getValue() == com.fadcam.joyfolder.studio.BringInDialog.Choice.LEAVE) continue;
                        take.add(e.getKey());
                        if (e.getValue() == com.fadcam.joyfolder.studio.BringInDialog.Choice.MOVE) moving.add(e.getKey());
                    }
                    if (take.isEmpty()) return;
                    Toast.makeText(app, "Bringing them into your Joy Creator folder\u2026", Toast.LENGTH_SHORT).show();
                    com.fadcam.joyfolder.android.JoyFolderHome.io().execute(() -> {
                        String msg;
                        try {
                            java.util.Map<String, String> copied =
                                    com.fadcam.joyfolder.studio.StudioMirror.bringInSome(app, projectId, take, null);
                            java.util.Map<String, String> moved = new java.util.HashMap<>();
                            for (String r : moving) if (copied.containsKey(r)) moved.put(r, copied.get(r));
                            java.util.List<String> left = new java.util.ArrayList<>();
                            if (!moved.isEmpty()) {
                                com.fadcam.joyfolder.studio.StudioMirror.rememberMoved(app, moved);
                                com.fadcam.joyfolder.studio.StudioMirror.repointOthers(app, moved, projectId);
                                left = com.fadcam.joyfolder.studio.OriginalsRemover.remove(activity, new java.util.ArrayList<>(moved.keySet()));
                            }
                            int c = copied.size() - moved.size();
                            msg = (c > 0 ? "Copied " + c + (moved.isEmpty() ? "" : " and moved " + moved.size())
                                    : "Moved " + moved.size()) + " into your Joy Creator folder."
                                    + (left.isEmpty() ? "" : " Couldn't remove the original of " + android.text.TextUtils.join(", ", left)
                                    + ": it's safely copied, so you can delete the original yourself.");
                        } catch (Exception e) {
                            msg = "Couldn't bring them all in: " + e.getMessage() + ". Nothing was lost.";
                        }
                        final String m = msg;
                        main.post(() -> {
                            Toast.makeText(app, m, Toast.LENGTH_LONG).show();
                            if (isAdded()) refreshRecentProjects();
                        });
                    });
                });
            });
        });
    }

    private void refreshRecentProjects() {
        if (recentProjectsList == null || recentProjectsSection == null) return;

        currentProjects = projectStorage.listProjects();
        recentProjectsList.removeAllViews();

        if (currentProjects.isEmpty()) {
            recentProjectsSection.setVisibility(View.GONE);
            if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.VISIBLE);
            return;
        }

        recentProjectsSection.setVisibility(View.VISIBLE);
        if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.GONE);

        // Update project count badge in the section header
        TextView countBadge = recentProjectsSection.findViewById(R.id.project_count_badge);
        if (countBadge != null) {
            countBadge.setText(String.valueOf(currentProjects.size()));
            countBadge.setVisibility(View.VISIBLE);
        }

        // Show all projects
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

        for (int i = 0; i < currentProjects.size(); i++) {
            ProjectStorage.ProjectSummary summary = currentProjects.get(i);
            View row = createProjectRow(summary, materialIcons, dateFormat);
            recentProjectsList.addView(row);
        }
    }

    /**
     * Create a single project row view with thumbnail, extracted filename, and optional checkbox.
     */
    @NonNull
    private View createProjectRow(@NonNull ProjectStorage.ProjectSummary summary,
                                  @Nullable Typeface iconFont,
                                  @NonNull SimpleDateFormat dateFormat) {
        float dp = getResources().getDisplayMetrics().density;

        // Outer row container
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = (int) (14 * dp);
        int vPad = (int) (10 * dp);
        row.setPadding(hPad, vPad, hPad, vPad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = (int) (6 * dp);
        row.setLayoutParams(rowLp);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        row.setTag(summary.id);

        // ── Checkbox (only in selection mode) ────────────────────
        CheckBox cb = new CheckBox(requireContext());
        cb.setTag("checkbox");
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
        cb.setMinWidth(0);
        cb.setMinHeight(0);
        cb.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                (int) (36 * dp), (int) (36 * dp));
        cbLp.setMarginEnd((int) (8 * dp));
        cb.setLayoutParams(cbLp);
        cb.setChecked(selectedIds.contains(summary.id));
        cb.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        cb.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                selectedIds.add(summary.id);
            } else {
                selectedIds.remove(summary.id);
            }
            updateSelectionUI();
        });
        row.addView(cb);

        // ── Thumbnail ────────────────────────────────────────────
        int thumbSize = (int) (52 * dp);
        ImageView thumbnail = new ImageView(requireContext());
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundResource(R.drawable.settings_group_card_bg);
        thumbnail.setClipToOutline(true);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(thumbSize, thumbSize);
        thumbLp.setMarginEnd((int) (12 * dp));
        thumbnail.setLayoutParams(thumbLp);

        // Set fallback icon first, then load real thumbnail async
        thumbnail.setImageResource(android.R.color.transparent);
        thumbnail.setBackgroundResource(R.drawable.settings_group_card_bg);

        // Load thumbnail on background thread
        if (summary.videoUri != null) {
            loadThumbnailAsync(thumbnail, summary.videoUri);
        }
        row.addView(thumbnail);

        // ── Text section ─────────────────────────────────────────
        LinearLayout textSection = new LinearLayout(requireContext());
        textSection.setOrientation(LinearLayout.VERTICAL);
        textSection.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // The PROJECT'S OWN NAME wins; the source filename is only a fallback.
        //
        // This row used to call extractDisplayName(videoUri) unconditionally, ignoring
        // summary.name entirely — even though it is right there and is populated from
        // project.json. Two consequences, both bad:
        //   • Renaming a project looked like it did nothing. The new name showed in the editor's
        //     title bar and nowhere else, so the list still read the old filename.
        //   • Projects cut from ONE source were indistinguishable. On the sandbox, five separate
        //     projects all rendered as "FadCam_20260621_145132" — the date column was the only
        //     way to tell them apart, and two shared a date to the minute.
        // "Untitled" is treated as absent, so a project the user never named keeps the helpful
        // filename it has always shown; only deliberate names take over.
        String displayName = (summary.name != null && !summary.name.trim().isEmpty()
                && !"Untitled".equals(summary.name.trim()))
                ? summary.name.trim()
                : extractDisplayName(summary.videoUri);
        TextView name = new TextView(requireContext());
        name.setText(displayName);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(14);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textSection.addView(name);

        // Last modified date with icon
        LinearLayout dateRow = new LinearLayout(requireContext());
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dateRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dateRowLp.topMargin = (int) (3 * dp);
        dateRow.setLayoutParams(dateRowLp);

        // Date icon (Material icon)
        TextView dateIcon = new TextView(requireContext());
        dateIcon.setTypeface(iconFont);
        dateIcon.setText("event");
        dateIcon.setTextColor(0xFF777777);
        dateIcon.setTextSize(14);
        dateIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dateIconLp = new LinearLayout.LayoutParams(
                (int) (12 * dp), (int) (12 * dp));
        dateIconLp.setMarginEnd((int) (4 * dp));
        dateIcon.setLayoutParams(dateIconLp);
        dateRow.addView(dateIcon);

        // Date text
        TextView date = new TextView(requireContext());
        date.setText(dateFormat.format(new Date(summary.lastModified)));
        date.setTextColor(0xFF999999);
        date.setTextSize(12);
        date.setTypeface(null, Typeface.BOLD);
        date.setMaxLines(1);
        dateRow.addView(date);

        textSection.addView(dateRow);

        // Cache size (remuxed files for this project's clips) with icon
        LinearLayout cacheRow = new LinearLayout(requireContext());
        cacheRow.setOrientation(LinearLayout.HORIZONTAL);
        cacheRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cacheRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cacheRowLp.topMargin = (int) (3 * dp);
        cacheRow.setLayoutParams(cacheRowLp);

        // Cache icon (drawable matching Records tab)
        ImageView cacheIcon = new ImageView(requireContext());
        cacheIcon.setImageResource(R.drawable.database_24px);
        cacheIcon.setColorFilter(0xFF666666, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams cacheIconLp = new LinearLayout.LayoutParams(
                (int) (12 * dp), (int) (12 * dp));
        cacheIconLp.setMarginEnd((int) (4 * dp));
        cacheIcon.setLayoutParams(cacheIconLp);
        cacheRow.addView(cacheIcon);

        // Cache size text
        TextView cacheSize = new TextView(requireContext());
        String cacheSizeText = getCacheSizeText(summary);
        cacheSize.setText(cacheSizeText);
        cacheSize.setTextColor(0xFF666666);
        cacheSize.setTextSize(12);
        cacheSize.setTypeface(null, Typeface.BOLD);
        cacheSize.setMaxLines(1);
        cacheRow.addView(cacheSize);

        textSection.addView(cacheRow);

        row.addView(textSection);

        // ── Yellow dot: files outside the Joy Creator folder ─────
        // Owner, 2026-10-14: an easy way to see that a project still links to files outside its
        // folder, and to bring them in by tapping the dot. The count comes from the project's last
        // save (StudioMirror), so drawing the list reads no files.
        com.fadcam.joyfolder.studio.StudioMirror.Outside outside =
                com.fadcam.joyfolder.studio.StudioMirror.outside(requireContext(), summary.id);
        if (outside != null) {
            TextView dot = new TextView(requireContext());
            dot.setText("●");
            dot.setTextColor(com.fadcam.ui.faditor.Studio.CAREFUL);
            dot.setTextSize(14);
            dot.setGravity(Gravity.CENTER);
            dot.setLayoutParams(new LinearLayout.LayoutParams((int) (36 * dp), (int) (36 * dp)));
            String what = outside.count == 1 ? "1 file is outside your Joy Creator folder"
                    : outside.count + " files are outside your Joy Creator folder";
            dot.setContentDescription(what);
            if (android.os.Build.VERSION.SDK_INT >= 26) dot.setTooltipText(what);
            dot.setOnClickListener(v -> offerBringIn(summary.id, outside));
            row.addView(dot);
        }

        // ── Arrow icon ───────────────────────────────────────────
        if (!selectionMode) {
            TextView arrow = new TextView(requireContext());
            arrow.setTypeface(iconFont);
            arrow.setText("chevron_right");
            arrow.setTextColor(0xFF444444);
            arrow.setTextSize(18);
            arrow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            arrowLp.setMarginStart((int) (4 * dp));
            arrow.setLayoutParams(arrowLp);
            row.addView(arrow);
        }

        // Click behaviour
        row.setClickable(true);
        row.setFocusable(true);

        if (selectionMode) {
            // In selection mode, tap toggles checkbox
            row.setOnClickListener(v -> {
                cb.setChecked(!cb.isChecked());
            });
        } else {
            // Normal mode: tap opens editor, long-press enters selection
            row.setOnClickListener(v -> {
                if (summary.videoUri != null) {
                    newProject.openEditor(Uri.parse(summary.videoUri), summary.id);
                }
            });
            row.setOnLongClickListener(v -> {
                enterSelectionMode();
                selectedIds.add(summary.id);
                updateSelectionUI();
                refreshRecentProjects();
                return true;
            });
        }

        return row;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * A source video's readable name, or "Untitled": the file name rule now lives in
     * {@link com.fadcam.ui.studio.SourceName}, shared with new projects and the Library.
     */
    @NonNull
    private String extractDisplayName(@Nullable String uriString) {
        String name = com.fadcam.ui.studio.SourceName.of(requireContext(), uriString);
        return name != null ? name : getString(R.string.projects_untitled);
    }

    /**
     * Load a video thumbnail asynchronously and set it on the ImageView.
     */
    private void loadThumbnailAsync(@NonNull ImageView imageView, @NonNull String uriString) {
        thumbnailExecutor.execute(() -> {
            Bitmap bmp = null;
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                Uri uri = Uri.parse(uriString);

                if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                    retriever.setDataSource(uri.getPath());
                } else {
                    // Try reconstructed file path first (more reliable for fMP4)
                    String path = uri.getPath();
                    boolean set = false;
                    if (path != null && path.contains(":")) {
                        int lastColon = path.lastIndexOf(':');
                        if (lastColon >= 0 && lastColon < path.length() - 1) {
                            String rel = path.substring(lastColon + 1);
                            File f = new File("/storage/emulated/0/" + rel);
                            if (f.exists() && f.canRead()) {
                                retriever.setDataSource(f.getAbsolutePath());
                                set = true;
                            }
                        }
                    }
                    if (!set) {
                        retriever.setDataSource(requireContext(), uri);
                    }
                }
                bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception e) {
                FLog.w(TAG, "Thumbnail extraction failed", e);
            } finally {
                if (retriever != null) {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            }

            final Bitmap finalBmp = bmp;
            if (finalBmp != null && isAdded()) {
                imageView.post(() -> imageView.setImageBitmap(finalBmp));
            }
        });
    }

    // Starting a project (source sheet, picker, naming, building) is NewProjectFlow,
    // shared with the Library's Projects view.

    /**
     * Calculate cache size for a project and format it as a human-readable string.
     * Cache includes remuxed video files created for seeking support.
     */
    @NonNull
    private String getCacheSizeText(@NonNull ProjectStorage.ProjectSummary summary) {
        try {
            // Load project to get all clip source URIs
            FaditorProject project = projectStorage.load(summary.id);
            if (project == null || project.getTimeline() == null) {
                return "Cache: 0 KB";
            }

            long totalCacheSize = 0;
            com.fadcam.playback.FragmentedMp4Remuxer remuxer =
                    new com.fadcam.playback.FragmentedMp4Remuxer(requireContext());

            // Sum cache for all video clips
            Timeline timeline = project.getTimeline();
            for (int i = 0; i < timeline.getClipCount(); i++) {
                Clip clip = timeline.getClip(i);
                if (clip != null) {
                    android.net.Uri sourceUri = clip.getSourceUri();
                    if (sourceUri != null) {
                        String filename = extractFilenameFromUri(sourceUri.toString());
                        if (filename != null) {
                            String baseName = filename.substring(0,
                                    Math.max(filename.lastIndexOf('.'), filename.length()));
                            totalCacheSize += remuxer.getCacheSizeForPrefix(baseName);
                        }
                    }
                }
            }

            // Sum cache for all audio clips
            List<AudioClip> audioClips = timeline.getAudioClips();
            if (audioClips != null) {
                for (AudioClip audioClip : audioClips) {
                    if (audioClip != null && audioClip.getSourceUri() != null) {
                        String filename = extractFilenameFromUri(audioClip.getSourceUri().toString());
                        if (filename != null) {
                            String baseName = filename.substring(0,
                                    Math.max(filename.lastIndexOf('.'), filename.length()));
                            totalCacheSize += remuxer.getCacheSizeForPrefix(baseName);
                        }
                    }
                }
            }

            // Format size as KB or MB
            if (totalCacheSize == 0) {
                return "Cache: 0 KB";
            } else if (totalCacheSize < 1024_000) {
                long kb = totalCacheSize / 1024;
                return "Cache: " + kb + " KB";
            } else {
                double mb = totalCacheSize / 1024.0 / 1024.0;
                return String.format("Cache: %.1f MB", mb);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not calculate cache size: " + e.getMessage());
            return "Cache: unknown";
        }
    }

    /**
     * Extract filename from a URI (file path or content URI).
     */
    @Nullable
    private String extractFilenameFromUri(@NonNull String uri) {
        try {
            if (uri.startsWith("file://")) {
                uri = uri.substring(7);  // Remove "file://" prefix
            } else if (uri.startsWith("content://")) {
                int lastSlash = uri.lastIndexOf('/');
                if (lastSlash >= 0) {
                    return uri.substring(lastSlash + 1);
                }
                return null;
            }

            int lastSlash = uri.lastIndexOf('/');
            if (lastSlash >= 0) {
                return uri.substring(lastSlash + 1);
            }
            return uri;
        } catch (Exception e) {
            FLog.w(TAG, "Could not extract filename from URI: " + uri, e);
            return null;
        }
    }

    // ── Export Dock Management ───────────────────────────────────────

    /**
     * Show the export dock with a smooth animation.
     * Mirrors the animation style from Records tab deletion dock.
     */
    private void showDockWithAnimation() {
        if (exportDock == null) return;
        
        if (exportDock.getVisibility() == View.VISIBLE) {
            return; // Already visible
        }

        exportDock.setVisibility(View.VISIBLE);
        exportDock.setAlpha(0f);
        exportDock.setTranslationY(-16f);
        
        exportDock.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .start();
        
        FLog.d(TAG, "Export dock shown with animation");
    }

    /**
     * Hide the export dock with a smooth animation.
     */
    private void hideDockWithAnimation() {
        if (exportDock == null || exportDock.getVisibility() != View.VISIBLE) return;

        exportDock.animate()
                .alpha(0f)
                .translationY(-16f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (exportDock != null) {
                        exportDock.setVisibility(View.GONE);
                    }
                    // Clear the finished-video row with the dock, so the NEXT export cannot flash
                    // the previous one's poster frame while its own is still decoding.
                    if (exportDockResult != null) exportDockResult.setVisibility(View.GONE);
                    if (exportDockThumb != null) exportDockThumb.setImageDrawable(null);
                    lastExportPath = null;
                })
                .start();
        
        FLog.d(TAG, "Export dock hidden with animation");
    }

    /**
     * Update the export dock with current progress snapshot.
     * Calculates ETA based on progress rate and elapsed time.
     */
    private void updateExportDockWithProgress(float progress) {
        if (exportDock == null || exportDock.getVisibility() != View.VISIBLE) return;

        // Update progress bar
        if (exportDockProgress != null) {
            int progressPercent = Math.round(progress * 100f);
            exportDockProgress.setProgress(progressPercent);
        }

        // Calculate and update ETA
        long elapsedMs = System.currentTimeMillis() - exportStartTimeMs;
        if (progress > 0 && elapsedMs > 500) {
            // Estimate total time based on progress rate
            long totalEstimatedMs = (long) (elapsedMs / progress);
            long remainingMs = totalEstimatedMs - elapsedMs;

            if (remainingMs > 0) {
                String etaText = formatEta(remainingMs);
                if (exportDockEta != null) {
                    exportDockEta.setText(etaText);
                }
            }
        }

        // Store last progress for completion detection
        lastReportedProgress = progress;
    }

    /**
     * Format remaining milliseconds into human-readable ETA string.
     */
    @NonNull
    private String formatEta(long remainingMs) {
        long seconds = remainingMs / 1000;
        if (seconds < 60) {
            return getString(R.string.export_eta_seconds, seconds);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return getString(R.string.export_eta_minutes, minutes);
        }
        long hours = minutes / 60;
        return getString(R.string.export_eta_hours, hours);
    }

    /**
     * Handle export completion: show checkmark, hide OK button initially, then show after delay.
     */
    private void handleExportCompleted(String outputPath) {
        if (exportDock == null) return;

        FLog.d(TAG, "Export completed: " + outputPath);
        isExportActive = false;

        // Update icon to checkmark
        if (exportDockIcon != null) {
            exportDockIcon.setImageResource(android.R.drawable.ic_input_add);  // Use system icon as fallback
            // Try to set to check_circle if available
            try {
                exportDockIcon.setImageResource(R.drawable.ic_check_circle);
            } catch (Exception ignore) {}
        }

        // Update title
        if (exportDockTitle != null) {
            exportDockTitle.animateSlot(getString(R.string.faditor_export_completed), 300);
        }

        // Ensure progress is at 100%
        if (exportDockProgress != null) {
            exportDockProgress.setProgress(100);
        }

        // Show dismiss hint (matching Records tab style)
        if (exportDockEta != null) {
            exportDockEta.setText(getString(R.string.records_delete_header_dismiss_hint));
        }

        // Show file size summary
        if (exportDockSummary != null && outputPath != null) {
            long fileSizeBytes = new java.io.File(outputPath).length();
            String sizeStr = android.text.format.Formatter.formatFileSize(requireContext(), fileSizeBytes);
            exportDockSummary.setText(getString(R.string.export_file_size, sizeStr));
        }

        // The finished video itself: a thumbnail to play and a way to go find it. Without this the
        // only thing the user could do with the thing they had just made was dismiss it.
        lastExportPath = outputPath;
        showExportResultRow(outputPath);

        // Show OK button after a brief delay
        if (exportDockOk != null) {
            exportDockOk.postDelayed(() -> {
                if (exportDockOk != null) {
                    exportDockOk.setVisibility(View.VISIBLE);
                    exportDockOk.animateSlot(getString(R.string.records_delete_header_action_ok), 300);
                    exportDockOk.setAlpha(0f);
                    exportDockOk.animate()
                            .alpha(1f)
                            .setDuration(200)
                            .start();
                }
            }, 600);
        }
    }

    /**
     * Reveal the finished-video row and decode its poster frame.
     *
     * <p>The decode is OFF the main thread: {@code MediaMetadataRetriever.setDataSource} on a fresh
     * export costs 100–300ms, and this runs at the exact moment the completion animation is
     * playing. The row appears immediately with the play badge over an empty tile and the frame
     * drops in when it lands, rather than the dock hitching on its way to "complete".</p>
     */
    private void showExportResultRow(@Nullable String outputPath) {
        if (exportDockResult == null) return;
        if (outputPath == null || !new java.io.File(outputPath).exists()) {
            exportDockResult.setVisibility(View.GONE);
            return;
        }
        exportDockResult.setVisibility(View.VISIBLE);
        if (exportDockThumb != null) exportDockThumb.setImageDrawable(null);
        final String path = outputPath;
        new Thread(() -> {
            android.graphics.Bitmap frame = null;
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            try {
                mmr.setDataSource(path);
                // A poster from ~1s in, falling back to the first frame: many exports open on a
                // fade or a black transition, and a black tile reads as "something went wrong".
                frame = mmr.getFrameAtTime(1_000_000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) frame = mmr.getFrameAtTime();
            } catch (Exception e) {
                FLog.w(TAG, "Export thumbnail decode failed for " + path, e);
            } finally {
                try { mmr.release(); } catch (Exception ignore) {}
            }
            final android.graphics.Bitmap out = frame;
            if (out == null || exportDockThumb == null) return;
            exportDockThumb.post(() -> {
                // The dock may have been dismissed while we decoded.
                if (exportDockThumb != null && exportDockResult != null
                        && exportDockResult.getVisibility() == View.VISIBLE) {
                    exportDockThumb.setImageBitmap(out);
                }
            });
        }, "export-thumb").start();
    }

    /** Play the just-exported file through the app's own player. */
    private void playLastExport() {
        if (lastExportPath == null || getActivity() == null) return;
        java.io.File f = new java.io.File(lastExportPath);
        if (!f.exists()) {
            Toast.makeText(getContext(), getString(R.string.records_error_opening_media),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // FileProvider, not Uri.fromFile: a file:// URI in an Intent throws
            // FileUriExposedException on N+ even within our own process.
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(), requireContext().getPackageName() + ".provider", f);
            Intent intent = new Intent(getActivity(), VideoPlayerActivity.class);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra("item_display_name", f.getName());
            startActivity(intent);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to play export " + lastExportPath, e);
            Toast.makeText(getContext(), getString(R.string.records_error_opening_media),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Switch to the Records tab, where the new file is now listed. */
    private void openRecordsTab() {
        if (getActivity() instanceof com.fadcam.MainActivity) {
            ((com.fadcam.MainActivity) getActivity()).openRecordsTab();
        }
    }

    /**
     * Handle export error: show error icon and message.
     */
    private void handleExportError(String errorMessage) {
        if (exportDock == null) return;

        FLog.d(TAG, "Export error: " + errorMessage);
        isExportActive = false;

        // Update icon to error
        if (exportDockIcon != null) {
            exportDockIcon.setImageResource(R.drawable.ic_error);
        }

        // Update title
        if (exportDockTitle != null) {
            exportDockTitle.animateSlot(getString(R.string.faditor_export_failed), 300);
        }

        // Show error message
        if (exportDockCurrent != null) {
            exportDockCurrent.setText(errorMessage);
        }

        // Show ETA as error hint
        if (exportDockEta != null) {
            exportDockEta.setText(R.string.export_error_hint);
        }

        // Show OK button to dismiss
        if (exportDockOk != null) {
            exportDockOk.setVisibility(View.VISIBLE);
            exportDockOk.animateSlot(getString(R.string.records_delete_header_action_ok), 300);
        }
    }

    /**
     * Check if there's an active export and bind to ExportService to receive updates.
     * Called from onResume() to restore dock state after configuration change.
     */
    private void checkAndBindExportService() {
        try {
            // Create a temporary intent to check if service is running
            // For now, just schedule a check after a delay
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isAdded()) return;
                
                // This would be called from ExportService callbacks in a real implementation
                // For now, the Faditor editor will notify us when it launches the service
            }, 100);
        } catch (Exception e) {
            FLog.e(TAG, "Error checking export service", e);
        }
    }

    /**
     * Global notification receiver for export notifications.
     * Should be called by the ExportService or from the Faditor editor.
     */
    public void onExportStarted(String outputPath) {
        if (!isAdded()) return;

        FLog.d(TAG, "Export started: " + outputPath);
        exportStartTimeMs = System.currentTimeMillis();
        lastReportedProgress = 0f;
        isExportActive = true;

        // Update dock content
        if (exportDockTitle != null) {
            exportDockTitle.setText(getString(R.string.faditor_export_progress));
        }
        if (exportDockCurrent != null) {
            // Extract just the filename from the full path
            String filename = new java.io.File(outputPath).getName();
            exportDockCurrent.setText(filename);
        }
        if (exportDockProgress != null) {
            exportDockProgress.setProgress(0);
        }
        if (exportDockIcon != null) {
            // Use a Material icon or drawable for export/download state
            // For now, use a generic info/download icon; production code can use a dedicated icon
            exportDockIcon.setImageResource(android.R.drawable.ic_menu_save);
        }
        if (exportDockOk != null) {
            // Show Cancel button during export
            exportDockOk.setVisibility(View.VISIBLE);
            exportDockOk.animateSlot(getString(R.string.records_delete_header_action_cancel), 300);
        }

        showDockWithAnimation();
    }

    /**
     * Called when export progress updates (0.0 - 1.0).
     */
    public void onExportProgress(float progress) {
        if (!isAdded() || exportDock == null) return;
        updateExportDockWithProgress(progress);
    }

    /**
     * Called when export completes successfully.
     */
    public void onExportCompleted(String outputPath) {
        if (!isAdded() || exportDock == null) return;
        handleExportCompleted(outputPath);
    }

    /**
     * Called when export fails.
     */
    public void onExportError(String errorMessage) {
        if (!isAdded() || exportDock == null) return;
        handleExportError(errorMessage);
    }

    /**
     * Called when export is cancelled by user.
     */
    public void onExportCancelled() {
        if (!isAdded() || exportDock == null) return;
        
        FLog.d(TAG, "Export cancelled");
        isExportActive = false;

        // Update icon to info/cancelled state
        if (exportDockIcon != null) {
            exportDockIcon.setImageResource(android.R.drawable.ic_dialog_info);
        }

        // Update title
        if (exportDockTitle != null) {
            exportDockTitle.animateSlot(getString(R.string.faditor_export_cancelled), 300);
        }

        // Show progress at current state (don't reset)
        if (exportDockProgress != null) {
            // Keep current progress value
        }

        // Clear ETA
        if (exportDockEta != null) {
            exportDockEta.setText(getString(R.string.records_delete_header_dismiss_hint));
        }

        // Show OK button to dismiss
        if (exportDockOk != null) {
            exportDockOk.setVisibility(View.VISIBLE);
            exportDockOk.animateSlot(getString(R.string.records_delete_header_action_ok), 300);
        }
    }
}
