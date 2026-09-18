package com.fadcam.ui.faditor.assetbrowser;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.ui.faditor.model.FaditorProject;

import java.util.ArrayList;
import java.util.List;

/**
 * Semi-transparent dropdown panel that shows media assets from the pinned directory.
 *
 * <p>Drops down from the top of the screen (where the pin icon is), covering
 * most of the video preview area but NOT the timeline or bottom controls.
 * The background is semi-transparent (~88% opacity) so the user can still
 * see and scrub the video behind it.</p>
 *
 * <p><b>Layout structure:</b></p>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │ [📁] [/path/to/folder ←→] [filter] [🗑]     │ ← header bar
 * ├─────────────────────────────────────────────┤
 * │  [thumb] [thumb] [thumb] [thumb]            │
 * │  [thumb] [thumb] [thumb] [thumb]            │ ← grid
 * │  [thumb] [thumb] [thumb] [thumb]            │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>For future AI developers:</b> When implementing drag-to-timeline,
 * the panel's RecyclerView items will dispatch long-press events that the
 * activity's drag system picks up. The panel itself stays open during drag
 * so the user can see where they started. The panel is added to the
 * activity's root content view (android.R.id.content) as an overlay.</p>
 */
public class AssetBrowserPanel extends FrameLayout {

    private static final String TAG = "AssetBrowserPanel";

    /** Panel height as fraction of screen height. */
    private float panelHeightRatio = 0.55f;

    @NonNull
    private final Activity activity;
    @Nullable
    private FaditorProject project;
    @Nullable
    private Callback callback;

    // Header views
    private TextView pathText;
    private HorizontalScrollView pathScroll;
    private TextView btnChangeDir;
    private TextView btnPrevDir;
    private TextView btnNextDir;
    private TextView btnFilter;
    private TextView btnTrash;

    // Content
    private RecyclerView recyclerView;
    private AssetBrowserAdapter adapter;
    private ProgressBar loadingBar;
    private TextView emptyText;

    // State
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<AssetItem> allItems = new ArrayList<>();
    private final List<String> dirHistory = new ArrayList<>();
    private int historyIndex = -1;
    private FilterMode filterMode = FilterMode.ALL;

    private enum FilterMode {
        ALL, USED, UNUSED
    }

    // Animation
    private boolean isExpanded = false;


    public AssetBrowserPanel(@NonNull Context context) {
        super(context);
        this.activity = (Activity) context;
        init();
    }

    public interface Callback {
        void onAssetSelected(@NonNull AssetItem item);
        void onAssetDragStarted(@NonNull AssetItem item, @NonNull View sourceView, float localX, float localY);
        void onAssetInserted(@NonNull AssetItem item, int timelineIndex);
        void onChangeDirectoryRequested();
        void onNavigateDirectory(@NonNull String treeUriStr);
        void onRenameAsset(@NonNull AssetItem item, @NonNull String newName);
        void onDeleteFromHistory(@NonNull String treeUriStr);
        void onPanelCollapsed();
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    public void setProject(@Nullable FaditorProject project) {
        this.project = project;
        if (project != null) {
            dirHistory.clear();
            dirHistory.addAll(project.getAssetDirHistory());
            // Set current index to the pinned dir
            String current = project.getPinnedAssetDir();
            if (current != null) {
                historyIndex = dirHistory.indexOf(current);
                if (historyIndex < 0) {
                    dirHistory.add(current);
                    historyIndex = dirHistory.size() - 1;
                }
            }
        }
        updateNavButtons();
        loadAssets();
    }

    private void init() {
        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Semi-transparent scrim background
        setBackgroundColor(Color.argb(0, 0, 0, 0)); // Start transparent, animate in

        float density = getResources().getDisplayMetrics().density;

        // Main container panel
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.argb(225, 13, 13, 13)); // ~88% opacity
        LayoutParams panelLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.TOP;
        panel.setLayoutParams(panelLp);

        // ── Header bar ──────────────────────────────────────────
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(
                (int)(8 * density), (int)(6 * density),
                (int)(8 * density), (int)(6 * density));
        header.setBackgroundColor(Color.argb(255, 26, 26, 26));

        // Folder/pin icon
        TextView folderIcon = new TextView(getContext());
        folderIcon.setTypeface(ResourcesCompat.getFont(getContext(), R.font.materialicons));
        folderIcon.setText("keyboard_arrow_up");
        folderIcon.setContentDescription(getContext().getString(R.string.faditor_asset_browser_unpin));
        folderIcon.setTextColor(Color.parseColor("#FF35F6BF"));
        folderIcon.setTextSize(18);
        folderIcon.setGravity(Gravity.CENTER);
        folderIcon.setOnClickListener(v -> collapse());
        folderIcon.setLayoutParams(new LinearLayout.LayoutParams(
                (int)(32 * density), (int)(32 * density)));

        // Path display (horizontally scrollable)
        pathScroll = new HorizontalScrollView(getContext());
        pathScroll.setHorizontalScrollBarEnabled(false);
        pathScroll.setLayoutParams(new LinearLayout.LayoutParams(
                0, (int)(28 * density), 1f));

        pathText = new TextView(getContext());
        pathText.setTextColor(Color.parseColor("#FFC4C4CE"));
        pathText.setTextSize(12);
        pathText.setSingleLine(true);
        pathText.setPadding((int)(4 * density), 0, (int)(4 * density), 0);
        pathText.setGravity(Gravity.CENTER_VERTICAL);
        pathScroll.addView(pathText);

        // Previous dir button (<)
        btnPrevDir = createIconButton("chevron_left", "#FF8A8A94", 28);
        btnPrevDir.setOnClickListener(v -> navigateDir(-1));

        // Next dir button (>)
        btnNextDir = createIconButton("chevron_right", "#FF8A8A94", 28);
        btnNextDir.setOnClickListener(v -> navigateDir(1));

        // Change directory button
        btnChangeDir = createIconButton("folder_open", "#FF35F6BF", 30);
        btnChangeDir.setOnClickListener(v -> {
            if (callback != null) callback.onChangeDirectoryRequested();
        });

        // Filter toggle button
        btnFilter = createIconButton("filter_list", "#FF8A8A94", 28);
        btnFilter.setOnClickListener(v -> cycleFilter());

        // Trash button
        btnTrash = createIconButton("playlist_remove", "#FF35F6BF", 28);
        btnTrash.setOnClickListener(v -> confirmDeleteFromHistory());

        header.addView(folderIcon);
        header.addView(pathScroll);
        header.addView(btnPrevDir);
        header.addView(btnNextDir);
        header.addView(btnChangeDir);
        header.addView(btnFilter);
        header.addView(btnTrash);
        panel.addView(header);

        // Divider
        View divider = new View(getContext());
        divider.setBackgroundColor(Color.parseColor("#FF2C2C35"));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        panel.addView(divider);

        // ── Loading bar ─────────────────────────────────────────
        loadingBar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setIndeterminate(true);
        loadingBar.setVisibility(View.GONE);
        loadingBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(3 * density)));
        panel.addView(loadingBar);

        // ── Empty text ──────────────────────────────────────────
        emptyText = new TextView(getContext());
        emptyText.setTextColor(Color.parseColor("#FF8A8A94"));
        emptyText.setTextSize(14);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(0, (int)(32 * density), 0, (int)(32 * density));
        emptyText.setVisibility(View.GONE);
        panel.addView(emptyText);

        // ── RecyclerView grid ───────────────────────────────────
        recyclerView = new RecyclerView(getContext());
        int spanCount = 4; // 4 columns on phone
        GridLayoutManager glm = new GridLayoutManager(getContext(), spanCount);
        recyclerView.setLayoutManager(glm);
        recyclerView.setPadding(
                (int)(6 * density), (int)(6 * density),
                (int)(6 * density), (int)(6 * density));
        recyclerView.setClipToPadding(false);

        adapter = new AssetBrowserAdapter();
        adapter.setCallback(new AssetBrowserAdapter.Callback() {
            @Override
            public void onItemTapped(@NonNull AssetItem item) {
                if (callback != null) {
                    callback.onAssetSelected(item);
                }
            }

            @Override
            public void onItemDragStarted(@NonNull AssetItem item, @NonNull View sourceView,
                                          float localX, float localY) {
                if (callback != null) {
                    callback.onAssetDragStarted(item, sourceView, localX, localY);
                }
            }

            @Override
            public void onItemRenameRequested(@NonNull AssetItem item) {
                showRenameDialog(item);
            }
        });
        recyclerView.setAdapter(adapter);
        panel.addView(recyclerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int)(getResources().getDisplayMetrics().heightPixels * panelHeightRatio)));

        // Grab handle for resizing
        View grabHandle = new View(getContext());
        grabHandle.setBackgroundColor(Color.parseColor("#FF33333C"));
        LinearLayout.LayoutParams grabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(24 * density));
        panel.addView(grabHandle, grabLp);

        // Inner grip line
        View gripLine = new View(getContext());
        gripLine.setBackgroundColor(Color.parseColor("#FF52525B"));
        FrameLayout gripContainer = new FrameLayout(getContext());
        gripContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(4 * density)));
        gripContainer.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams gripLp2 = new FrameLayout.LayoutParams(
                (int)(40 * density), (int)(4 * density));
        gripLp2.gravity = Gravity.CENTER;
        gripLine.setLayoutParams(gripLp2);
        gripContainer.addView(gripLine);
        panel.addView(gripContainer);

        grabHandle.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            private float startRatio;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        startRatio = panelHeightRatio;
                        grabHandle.setBackgroundColor(Color.parseColor("#FF35F6BF"));
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float deltaY = startY - event.getRawY();
                        float deltaRatio = deltaY / getResources().getDisplayMetrics().heightPixels;
                        panelHeightRatio = Math.max(0.2f, Math.min(0.8f, startRatio + deltaRatio));
                        updatePanelHeight();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        grabHandle.setBackgroundColor(Color.parseColor("#FF33333C"));
                        return true;
                }
                return false;
            }
        });

        // ── Close-on-tap-outside scrim ──────────────────────────
        // Tapping the scrim area (below the panel) collapses the panel
        setOnClickListener(v -> collapse());

        // Panel should not trigger scrim close when tapped
        panel.setOnClickListener(v -> { /* consume */ });

        addView(panel);

        updatePanelHeight();

    }

    @NonNull
    private TextView createIconButton(@NonNull String icon, @NonNull String color, int sizeDp) {
        TextView tv = new TextView(getContext());
        tv.setTypeface(ResourcesCompat.getFont(getContext(), R.font.materialicons));
        tv.setText(icon);
        tv.setTextColor(Color.parseColor(color));
        tv.setTextSize(sizeDp * 0.7f);
        tv.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                (int)(sizeDp * d), (int)(32 * d)));
        tv.setBackground(null);
        TypedValue outValue = new TypedValue();
        if (getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
            tv.setBackgroundResource(outValue.resourceId);
        } else {
            tv.setBackgroundColor(Color.TRANSPARENT);
        }
        return tv;
    }

    // ── Public API ──────────────────────────────────────────────

    /** Animate the panel down into view. */
    public void expand() {
        if (isExpanded) return;
        isExpanded = true;
        setVisibility(VISIBLE);
        // Animate scrim alpha
        ValueAnimator scrimAnim = ValueAnimator.ofInt(0, 120);
        scrimAnim.addUpdateListener(a -> {
            int alpha = (int) a.getAnimatedValue();
            setBackgroundColor(Color.argb(alpha, 0, 0, 0));
        });
        scrimAnim.setDuration(200);
        scrimAnim.start();

        // Slide panel from -height to 0
        final View panel = getChildAt(0);
        panel.setTranslationY(-getResources().getDisplayMetrics().heightPixels);
        panel.animate()
                .translationY(0)
                .setDuration(250)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** Animate the panel up out of view and remove from parent. */
    public void collapse() {
        if (!isExpanded) {
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
            return;
        }
        isExpanded = false;

        final View panel = getChildAt(0);
        panel.animate()
                .translationY(-getResources().getDisplayMetrics().heightPixels)
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    if (getParent() instanceof ViewGroup) {
                        ((ViewGroup) getParent()).removeView(AssetBrowserPanel.this);
                    }
                    setVisibility(GONE);
                    if (callback != null) callback.onPanelCollapsed();
                })
                .start();

        // Fade scrim
        ValueAnimator scrimAnim = ValueAnimator.ofInt(120, 0);
        scrimAnim.addUpdateListener(a -> {
            int alpha = (int) a.getAnimatedValue();
            setBackgroundColor(Color.argb(alpha, 0, 0, 0));
        });
        scrimAnim.setDuration(200);
        scrimAnim.start();
    }

    /** Called when a directory has been pinned via the SAF picker. */
    public void onDirectoryPinned(@NonNull Uri treeUri) {
        if (project != null) {
            setProject(project); // reload history from project
        }
    }

    public void onDirectoryRemovedFromHistory(@Nullable String treeUriStr) {
        dirHistory.clear();
        if (project != null) {
            dirHistory.addAll(project.getAssetDirHistory());
        }
        if (treeUriStr != null) {
            while (dirHistory.remove(treeUriStr)) { }
        }
        if (project != null && project.getPinnedAssetDir() != null) {
            String current = project.getPinnedAssetDir();
            historyIndex = dirHistory.indexOf(current);
            if (historyIndex < 0) {
                dirHistory.add(current);
                historyIndex = dirHistory.size() - 1;
            }
        } else {
            historyIndex = -1;
        }
        updateNavButtons();
        loadAssets();
    }

    /** Refresh the asset list (e.g. after rename or directory change). */
    public void refresh() {
        loadAssets();
    }

    public void highlightAsset(@Nullable AssetItem item) {
        adapter.setHighlightedItem(item);
    }

    // ── Internal ────────────────────────────────────────────────

    private void loadAssets() {
        if (project == null || project.getPinnedAssetDir() == null) {
            updatePathDisplay("");
            emptyText.setText(R.string.faditor_asset_browser_no_dir);
            emptyText.setVisibility(VISIBLE);
            recyclerView.setVisibility(GONE);
            return;
        }

        String dir = project.getPinnedAssetDir();
        updatePathDisplay(AssetScanner.getDisplayPath(dir));
        loadingBar.setVisibility(VISIBLE);
        emptyText.setVisibility(GONE);
        recyclerView.setVisibility(GONE);

        // Scan on background thread
        new Thread(() -> {
            AssetScanner scanner = new AssetScanner(getContext());
            List<AssetItem> items = scanner.scan(dir, project.getTimeline());
            mainHandler.post(() -> {
                allItems.clear();
                allItems.addAll(items);
                applyFilter();
                loadingBar.setVisibility(GONE);
                if (filteredItems().isEmpty()) {
                    emptyText.setText(R.string.faditor_asset_browser_empty);
                    emptyText.setVisibility(VISIBLE);
                    recyclerView.setVisibility(GONE);
                } else {
                    emptyText.setVisibility(GONE);
                    recyclerView.setVisibility(VISIBLE);
                }
            });
        }, "AssetScanner").start();
    }

    private void updatePathDisplay(@NonNull String path) {
        pathText.setText(path.isEmpty() ? getContext().getString(R.string.faditor_asset_browser_no_dir) : path);
        // Scroll to end so the current path is visible
        pathScroll.post(() -> pathScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT));
    }

    private void updateNavButtons() {
        boolean hasPrev = historyIndex > 0;
        boolean hasNext = historyIndex >= 0 && historyIndex < dirHistory.size() - 1;
        btnPrevDir.setAlpha(hasPrev ? 1f : 0.3f);
        btnPrevDir.setEnabled(hasPrev);
        btnNextDir.setAlpha(hasNext ? 1f : 0.3f);
        btnNextDir.setEnabled(hasNext);
    }

    private void navigateDir(int direction) {
        if (historyIndex < 0) return;
        int newIndex = historyIndex + direction;
        if (newIndex < 0 || newIndex >= dirHistory.size()) return;
        historyIndex = newIndex;
        String newDir = dirHistory.get(newIndex);
        if (project != null) {
            project.setPinnedAssetDir(newDir);
        }
        if (callback != null) {
            callback.onNavigateDirectory(newDir);
        }
        updateNavButtons();
        loadAssets();
    }

    private void cycleFilter() {
        filterMode = FilterMode.values()[(filterMode.ordinal() + 1) % FilterMode.values().length];
        switch (filterMode) {
            case ALL:
                btnFilter.setText("filter_list");
                btnFilter.setTextColor(Color.parseColor("#FF8A8A94"));
                break;
            case USED:
                btnFilter.setText("check_circle");
                btnFilter.setTextColor(Color.parseColor("#FF35F6BF"));
                break;
            case UNUSED:
                btnFilter.setText("radio_button_unchecked");
                btnFilter.setTextColor(Color.parseColor("#FFFBBF24"));
                break;
        }
        applyFilter();
    }

    /**
     * Dual-stream pair detection (feature-dual-stream-recording-spec §3/Phase 4):
     * the recorder writes a screen file {@code <base>.mp4} and a raw-webcam sibling
     * {@code <base>_webcam.mp4} next to it. Given either half, return the OTHER half
     * scanned in this same directory, or null if no partner file is present. Only
     * VIDEO items pair. The returned item is always the WEBCAM (overlay) half's
     * partner-of-the-screen-clip is resolved by the caller via {@link #isWebcamSibling}.
     */
    @Nullable
    public AssetItem findDualStreamPartner(@NonNull AssetItem item) {
        if (item.type != AssetItem.Type.VIDEO) return null;
        String base = item.shortLabel(); // name without extension
        final String wantName;
        if (base.endsWith(WEBCAM_SUFFIX)) {
            // item is the webcam half → partner is the screen file (strip suffix)
            wantName = base.substring(0, base.length() - WEBCAM_SUFFIX.length());
        } else {
            // item is the screen half → partner is <base>_webcam
            wantName = base + WEBCAM_SUFFIX;
        }
        for (AssetItem other : allItems) {
            if (other == item || other.type != AssetItem.Type.VIDEO) continue;
            if (wantName.equals(other.shortLabel())) return other;
        }
        return null;
    }

    /** True when {@code item}'s name ends with the recorder's raw-webcam suffix. */
    public static boolean isWebcamSibling(@NonNull AssetItem item) {
        return item.type == AssetItem.Type.VIDEO && item.shortLabel().endsWith(WEBCAM_SUFFIX);
    }

    /** The dual-stream recorder's raw-webcam filename suffix (WebcamEncoderPipeline). */
    public static final String WEBCAM_SUFFIX = "_webcam";

    private List<AssetItem> filteredItems() {
        if (filterMode == FilterMode.ALL) return allItems;
        List<AssetItem> filtered = new ArrayList<>();
        for (AssetItem item : allItems) {
            if (filterMode == FilterMode.USED && item.isUsed) filtered.add(item);
            else if (filterMode == FilterMode.UNUSED && !item.isUsed) filtered.add(item);
        }
        return filtered;
    }

    private void applyFilter() {
        adapter.setItems(filteredItems());
    }

    private void showRenameDialog(@NonNull AssetItem item) {
        if (callback == null) return;
        // Use a simple EditText dialog
        android.widget.EditText input = new android.widget.EditText(getContext());
        input.setText(item.shortLabel());
        input.setHint(R.string.faditor_asset_browser_rename_hint);
        input.setSelectAllOnFocus(true);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.faditor_asset_browser_rename_title)
                .setView(input)
                .setPositiveButton(R.string.faditor_asset_browser_rename, (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        callback.onRenameAsset(item, newName);
                    }
                })
                .setNegativeButton(R.string.faditor_cancel, null)
                .show();
    }

    private void confirmDeleteFromHistory() {
        if (historyIndex < 0 || dirHistory.isEmpty()) {
            Toast.makeText(getContext(), R.string.faditor_asset_browser_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String currentDir = dirHistory.get(historyIndex);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.faditor_asset_browser_delete_title)
                .setMessage(R.string.faditor_asset_browser_delete_msg)
                .setPositiveButton(R.string.faditor_delete_confirm, (d, w) -> {
                    if (callback != null) {
                        callback.onDeleteFromHistory(currentDir);
                    }
                })
                .setNegativeButton(R.string.faditor_cancel, null)
                .show();
    }

    private void updatePanelHeight() {
        if (recyclerView != null) {
            ViewGroup.LayoutParams rlp = recyclerView.getLayoutParams();
            rlp.height = (int)(getResources().getDisplayMetrics().heightPixels * panelHeightRatio);
            recyclerView.setLayoutParams(rlp);
        }
    }

    public float getPanelHeightRatio() {
        return panelHeightRatio;
    }

    public void setPanelHeightRatio(float ratio) {
        this.panelHeightRatio = Math.max(0.2f, Math.min(0.8f, ratio));
        updatePanelHeight();
    }
}
