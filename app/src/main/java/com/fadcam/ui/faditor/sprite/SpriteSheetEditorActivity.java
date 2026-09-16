package com.fadcam.ui.faditor.sprite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.R;
import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * S2 — full-screen sprite-sheet SETUP EDITOR (PLAN_SPRITE_ANIMATION): slice a
 * source image into a named grid of cells. Cols/rows/margins/spacing steppers,
 * tap-a-cell → name + enabled, drag-pivot mode, fps, live cell-cycle preview.
 * Write-back via the established cross-activity pattern: load project →
 * mutate → {@link ProjectStorage#save} → {@link AIChatState#signalModified} →
 * the editor reloads on resume (ChatAssistantActivity precedent).
 *
 * <p>S2b (deferred, documented in the plan): auto-detect grid (gutter scan),
 * onion skin, bg color-key UI (engine support already ships), sw600dp
 * two-pane, sidecar export button.</p>
 */
public class SpriteSheetEditorActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_ID = "sprite_editor_project_id";
    /** Absent/null = create a NEW sheet (image picker opens immediately). */
    public static final String EXTRA_SHEET_ID = "sprite_editor_sheet_id";
    /** S7: open the image picker immediately to REPLACE an existing sheet's
     *  image (dead URI relink) — grid/cells/pivot survive untouched. */
    public static final String EXTRA_RELINK = "sprite_editor_relink";

    private ProjectStorage storage;
    private FaditorProject project;
    private SpriteSheet sheet;
    @Nullable private SpriteSheetRenderer renderer;
    private boolean isNewSheet = false;

    private SpriteGridEditorView gridView;
    private EditText nameField;
    private LinearLayout cellPanel;
    private TextView cellTitle;
    private EditText cellNameField;
    private Switch cellEnabled;
    private boolean onionMode = false;
    /** How many frames ghost behind and ahead. 0 turns that side off. */
    private int onionPast = 1;
    private int onionFuture = 1;
    /** Ghost colours. Past is a state colour by default, but the artist gets to say. */
    private int onionPastColour = SpriteTheme.LIVE;
    private int onionFutureColour = SpriteTheme.SELECTED;
    /** How loud the nearest ghost is. Distant ones fall off from here. */
    private float onionStrength = 0.40f;
    /** Which of {@link #BG_NAMES} the preview sits on. */
    int previewBg = 0;
    private TextView onionToggle;
    private TextView pastPill;
    private TextView futurePill;
    private BgSwatch bgSwatch;
    private TextView keyBtn;
    private TextView playBtn;
    private CellCyclePreview preview;
    private NumPill fpsPill;
    /** The Lab's own playback mode. It is what a saved clip inherits. */
    private String labWrap = "loop";
    private final java.util.Map<String, TextView> wrapBtns = new java.util.LinkedHashMap<>();
    /** The subtitle of the slice group, so a dragged number can update it in place. */
    private TextView sliceSub;
    private final float density() { return getResources().getDisplayMetrics().density; }

    private final ActivityResultLauncher<String[]> imagePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    // New-sheet flow has nothing to edit without an image; a
                    // cancelled RELINK just stays in the editor (S7).
                    if (isNewSheet) finish();
                    return;
                }
                importSheetImage(uri);
            });

    private final ActivityResultLauncher<String[]> sidecarImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                importSidecar(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new ProjectStorage(this);
        String projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        project = projectId != null ? storage.load(projectId) : null;
        if (project == null) {
            Toast.makeText(this, R.string.sprite_editor_no_project, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String sheetId = getIntent().getStringExtra(EXTRA_SHEET_ID);
        sheet = project.spriteSheetById(sheetId);
        // Sheet must exist BEFORE buildUi(): the steppers' initial sync reads
        // sheet geometry (device-caught NPE on the new-sheet flow).
        if (sheet == null) {
            isNewSheet = true;
            sheet = SpriteSheet.create(getString(R.string.sprite_editor_default_name), "");
        }
        buildUi();
        nameField.setText(sheet.getName());
        if (isNewSheet) {
            imagePicker.launch(new String[]{"image/*"});
        } else if (getIntent().getBooleanExtra(EXTRA_RELINK, false)) {
            // S7 relink: same import path as a new sheet — copy into the bundle,
            // point sheetUri at it, re-decode. Slicing metadata is untouched.
            reloadRenderer(); // show whatever loads (or MISSING) behind the picker
            imagePicker.launch(new String[]{"image/*"});
        } else {
            reloadRenderer();
        }
    }

    /** Copy the picked image into the project bundle (assets/ convention) and load it. */
    private void importSheetImage(@NonNull Uri src) {
        try {
            File assetsDir = new File(storage.projectDir(project.getId()), "assets");
            if (!assetsDir.exists()) assetsDir.mkdirs();
            File dest = new File(assetsDir, "sheet-" + UUID.randomUUID() + ".png");
            try (InputStream in = getContentResolver().openInputStream(src);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            sheet.setSheetUri(Uri.fromFile(dest).toString());
            reloadRenderer();
        } catch (Exception e) {
            Toast.makeText(this, R.string.sprite_editor_import_failed, Toast.LENGTH_LONG).show();
            if (isNewSheet) finish();
        }
    }

    private void reloadRenderer() {
        if (renderer != null) renderer.recycle();
        renderer = SpriteSheetRenderer.load(this, sheet);
        gridView.setSheet(sheet, renderer); // null renderer → MISSING affordance (S7 rule)
        preview.bind(sheet, renderer);
        // The film strip lives outside benchBody, so showSection does not touch it. Its chips
        // draw through the renderer we just replaced; leaving them alone is how a recycled
        // bitmap reaches a canvas.
        rebuildFilm();
        onCellSelected(gridView.getSelectedCell());
    }

    private void save() { save(false); }

    /**
     * @param quiet true for an autosave — no toast, and never the "picker was cancelled"
     *              finish(), because the user did not ask for anything to close.
     */
    private void save(boolean quiet) {
        sheet.setName(nameField.getText().toString().trim().isEmpty()
                ? getString(R.string.sprite_editor_default_name)
                : nameField.getText().toString().trim());
        if (isNewSheet && sheet.getSheetUri().isEmpty()) {
            if (!quiet) finish();   // picker cancelled
            return;
        }
        if (isNewSheet && project.spriteSheetById(sheet.getId()) == null) {
            project.getSpriteSheets().add(sheet);
            isNewSheet = false;
        }
        boolean ok = storage.save(project);
        if (ok) AIChatState.signalModified(project.getId());
        if (ok) { labDirty = false; syncSaveBtn(); }
        if (!quiet) {
            Toast.makeText(this, ok ? R.string.sprite_editor_saved : R.string.sprite_editor_save_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // The rest of the editor autosaves on pause; this screen only saved on Back, so
        // switching apps in the middle of naming a sheet threw the naming away — and naming
        // is the slow, valuable part.
        if (benchBody != null) benchBody.removeCallbacks(autoSave);
        if (labDirty) save(true);
    }

    @Override
    public void onBackPressed() {
        // Autosave semantics, matching the rest of the app (editor autosaves on pause).
        save();
        super.onBackPressed();
    }

    // ── UI construction (programmatic; dark, matches the studio drawers' idiom) ──

    // ── Lab state ────────────────────────────────────────────────────────
    /** The roll being assembled: {cellIndex, holdTicks}. "Save clip" turns it into a preset. */
    private final java.util.List<int[]> labSeq = new java.util.ArrayList<>();
    private final java.util.List<View> filmBoxes = new java.util.ArrayList<>();
    private int labCur = 0;
    /** "slice" | "play" | "clips" | "out" */
    private String labSection = "play";
    private int benchPx;
    private boolean labDirty = false;
    private int labSelected = 0;
    private ScrollView benchScroll;
    private LinearLayout benchBody;
    private LinearLayout filmRow;
    private TextView rollHint;
    private HorizontalScrollView filmScroll;
    private ScrubBar scrubBar;
    private TextView saveBtn;
    private final java.util.Map<String, TextView> navBtns = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> openGroups =
            new java.util.HashSet<>(java.util.Arrays.asList("seq", "align", "slice1", "cellEd", "exp"));

    private static final String[] VISEMES = {"REST", "AA", "EE", "OO", "CLOSURE", "FRIC"};

    /**
     * The Lab (tools/spritelab/SpriteLabMobile.html, layout C).
     *
     * <p>Grid on top, a workbench under a draggable divider, the transport pinned above the
     * scrub, and the roll of film at the bottom. The divider is the whole idea: shrink the grid
     * to a row you scroll and the preview takes the full width, which is how you align one
     * frame precisely without losing sight of the grid it belongs to.</p>
     */
    @SuppressLint("SetTextI18n")
    private void buildUi() {
        float d = density();
        benchPx = (int) (300 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SpriteTheme.BG);

        root.addView(buildTopBar(d));

        gridView = new SpriteGridEditorView(this);
        gridView.setListener(new SpriteGridEditorView.Listener() {
            @Override public void onCellTapped(int index) { onCellTappedInLab(index); }
            @Override public void onPivotChanged(float px, float py) { markDirty(); }
            @Override public void onColorPicked(int argb) { applyBgKey(argb); }
            @Override public void onCellDragged(int from, int to) { reorderCells(from, to); }
        });
        root.addView(gridView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View divider = new Divider(this);
        divider.setOnTouchListener(new View.OnTouchListener() {
            float downY; int startPx;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY(); startPx = benchPx; return true;
                    case MotionEvent.ACTION_MOVE: {
                        int want = (int) (startPx - (e.getRawY() - downY));
                        benchPx = Math.max((int) (150 * density()),
                                Math.min((int) (520 * density()), want));
                        ViewGroup.LayoutParams lp = benchScroll.getLayoutParams();
                        lp.height = benchPx;
                        benchScroll.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        showSection(labSection);   // reflow: the preview may now span
                        return true;
                }
                return false;
            }
        });
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (9 * d)));

        benchScroll = new ScrollView(this);
        benchScroll.setVerticalScrollBarEnabled(false);
        benchBody = new LinearLayout(this);
        benchBody.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (9 * d);
        benchBody.setPadding(pad, (int) (6 * d), pad, (int) (6 * d));
        benchScroll.addView(benchBody);
        root.addView(benchScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, benchPx));

        root.addView(buildTransport(d));

        scrubBar = new ScrubBar(this);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (20 * d));
        sLp.leftMargin = pad; sLp.rightMargin = pad; sLp.bottomMargin = (int) (5 * d);
        root.addView(scrubBar, sLp);

        // The roll. Sprockets belong HERE, where the strip really is film you are cutting; in
        // the editor drawer the same chips are tap-to-key buttons and wear none.
        HorizontalScrollView filmScroll = new HorizontalScrollView(this);
        filmScroll.setHorizontalScrollBarEnabled(false);
        filmRow = new LinearLayout(this);
        filmRow.setOrientation(LinearLayout.HORIZONTAL);
        filmRow.setPadding(pad, 0, pad, (int) (6 * d));
        filmScroll.addView(filmRow);
        this.filmScroll = filmScroll;

        rollHint = chip("Drag sideways to reorder \u00b7 up to remove");
        // INVISIBLE, never GONE: showing it must not re-lay-out the strip underneath, or the
        // lifted chip jumps a hint-height away from the finger the moment you pick it up.
        rollHint.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.leftMargin = pad;
        hLp.bottomMargin = (int) (4 * d);
        root.addView(rollHint, hLp);
        root.addView(filmScroll);

        // Kept for the cell editor, which now lives inside the Slice section.
        cellPanel = new LinearLayout(this);
        cellPanel.setOrientation(LinearLayout.VERTICAL);
        cellTitle = new TextView(this);
        cellNameField = new EditText(this);
        cellEnabled = new Switch(this);

        setContentView(root);
        showSection(labSection);
        rebuildFilm();
        settled = snapshot();
    }

    /** Back · name · four coloured section icons · save. One line. */
    @NonNull
    private View buildTopBar(float d) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (8 * d);
        top.setPadding(pad, pad / 2, pad, pad / 2);

        TextView back = ichip("back");
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back);

        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setTextColor(SpriteTheme.INK);
        nameField.setTextSize(14f);
        nameField.setBackground(null);
        nameField.setHint(R.string.sprite_editor_name_hint);
        nameField.setHintTextColor(SpriteTheme.DIMMER);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nLp.leftMargin = pad / 2;
        top.addView(nameField, nLp);

        undoBtn = ichip("undo");
        undoBtn.setOnClickListener(v -> undo());
        top.addView(undoBtn);
        redoBtn = ichip("redo");
        redoBtn.setOnClickListener(v -> redo());
        top.addView(redoBtn);
        syncUndo();

        // Four sections, four colours, in ONE segmented pill. Solid when active, plain grey
        // when not — there is no half-opaque middle state anywhere in this package.
        LinearLayout nav = seg();
        addNav(nav, "slice", "grid",   SpriteTheme.ACCENT_GRID);
        addNav(nav, "play",  "target", SpriteTheme.ACCENT_ALIGN);
        addNav(nav, "clips", "clips",  SpriteTheme.ACCENT_CLIPS);
        addNav(nav, "out",   "out",    SpriteTheme.ACCENT_OUT);
        space(nav, d, 1);
        top.addView(nav);

        saveBtn = ichip("save");
        saveBtn.setOnClickListener(v -> { save(); labDirty = false; syncSaveBtn(); });
        top.addView(saveBtn);
        syncSaveBtn();
        space(top, d, 5);
        return top;
    }

    /** Space a row's children by the design's gap, without a layout file to say it in. */
    private void space(@NonNull LinearLayout row, float d, int dp) {
        for (int i = 1; i < row.getChildCount(); i++) {
            View ch = row.getChildAt(i);
            ViewGroup.LayoutParams lp = ch.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) lp).leftMargin = (int) (dp * d);
            } else {
                LinearLayout.LayoutParams n = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                n.leftMargin = (int) (dp * d);
                ch.setLayoutParams(n);
            }
        }
    }

    private void addNav(@NonNull LinearLayout parent, @NonNull String id,
                        @NonNull String icon, int colour) {
        TextView b = segBtn(icon);
        b.setTag(colour);
        b.setOnClickListener(v -> showSection(id));
        navBtns.put(id, b);
        parent.addView(b);
    }

    private void syncNav() {
        for (java.util.Map.Entry<String, TextView> e : navBtns.entrySet()) {
            TextView b = e.getValue();
            tintSeg(b, e.getKey().equals(labSection), (Integer) b.getTag());
        }
    }

    /** Pink while there is something unsaved; plain grey the moment there is not. */
    private void syncSaveBtn() {
        if (saveBtn == null) return;
        tintToggle(saveBtn, labDirty, SpriteTheme.LIVE);
    }

    private void markDirty() {
        labDirty = true;
        syncSaveBtn();
        noteChange();
        scheduleSave();
    }

    /**
     * Write the sheet a moment after you stop changing it.
     *
     * <p>Saving only on pause and on Back means anything between the last pause and a crash,
     * a low-memory kill or a reinstall is gone. During this build I twice could not account
     * for where a cell name went, and could not reproduce it either; rather than leave that
     * open, the window in which work exists only in RAM is now about a second wide.</p>
     *
     * <p>Debounced rather than per-change: a number drag would otherwise write the project
     * file thirty times a second.</p>
     */
    private void scheduleSave() {
        if (benchBody == null || restoring) return;
        benchBody.removeCallbacks(autoSave);
        benchBody.postDelayed(autoSave, 1500);
    }

    private final Runnable autoSave = new Runnable() {
        @Override public void run() {
            if (labDirty && !isFinishing()) save(true);
        }
    };

    // ── undo ─────────────────────────────────────────────────────────────
    //
    // Snapshots, not commands. The sheet already knows how to write and read itself, so a
    // step is one string; the alternative is an undoable twin of every edit in this file,
    // and those rot the moment someone adds an edit and forgets the twin.

    private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> redoStack = new java.util.ArrayDeque<>();
    private TextView undoBtn;
    private TextView redoBtn;
    /** The state as it stands now, ready to become the next undo step. */
    private String settled;
    private long burstAt;
    /**
     * How long a run of changes counts as ONE press. A drag widens it so the whole gesture
     * is a single step — JoyRaptor's rule is one press, one step.
     */
    private int burstMs = 600;

    @NonNull
    private String snapshot() {
        StringBuilder sb = new StringBuilder(sheet.toJson().toString());
        sb.append('\0').append(labWrap).append('|').append(labCur).append('|');
        for (int[] f : labSeq) sb.append(f[0]).append(':').append(f[1]).append(',');
        return sb.toString();
    }

    private boolean restoring;

    private void restore(@NonNull String snap) {
        int cut = snap.indexOf('\0');
        if (cut < 0) return;
        SpriteSheet restored;
        try {
            restored = SpriteSheet.fromJson(
                    com.google.gson.JsonParser.parseString(snap.substring(0, cut)).getAsJsonObject());
        } catch (RuntimeException e) {
            return;   // a snapshot we cannot read is not worth crashing over
        }
        java.util.List<SpriteSheet> all = project.getSpriteSheets();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(sheet.getId())) { all.set(i, restored); break; }
        }
        sheet = restored;

        restoring = true;
        labSeq.clear();
        String tail = snap.substring(cut + 1);
        int wrapBar = tail.indexOf('|');
        if (wrapBar >= 0) {
            labWrap = tail.substring(0, wrapBar);
            tail = tail.substring(wrapBar + 1);
            syncWrap();
        }
        int bar = tail.indexOf('|');
        labCur = 0;
        if (bar >= 0) {
            try { labCur = Integer.parseInt(tail.substring(0, bar)); } catch (NumberFormatException ignored) { }
            tail = tail.substring(bar + 1);
        }
        for (String part : tail.split(",")) {
            if (part.isEmpty()) continue;
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            try {
                labSeq.add(new int[]{Integer.parseInt(part.substring(0, colon)),
                        Integer.parseInt(part.substring(colon + 1))});
            } catch (NumberFormatException ignored) { }
        }
        labCur = Math.max(0, Math.min(Math.max(0, labSeq.size() - 1), labCur));
        labHold = 0;

        reloadRenderer();
        rebuildFilm();
        showSection(labSection);
        nameField.setText(sheet.getName());
        restoring = false;
    }

    /** Fold this change into the current step, or open a new one if the last has settled. */
    private void noteChange() {
        if (restoring) return;   // rebuilding the UI must not look like a fresh edit
        long now = android.os.SystemClock.uptimeMillis();
        if (settled == null) { settled = snapshot(); burstAt = now; return; }
        if (now - burstAt > burstMs) {
            undoStack.push(settled);
            // SPEC_20260910_SPRITELAB_UI §10 asks for 100. A snapshot is one serialised
            // sheet, so the whole stack weighs less than a single decoded frame of the art.
            while (undoStack.size() > 100) undoStack.removeLast();
            redoStack.clear();
        }
        burstAt = now;
        // Serialising the whole sheet on every keystroke and every drag tick is work nobody
        // sees; take the snapshot once the hand comes off the control. A stale `settled` is
        // harmless in the meantime, because undo snapshots the live state itself.
        if (benchBody != null) {
            benchBody.removeCallbacks(settle);
            benchBody.postDelayed(settle, 250);
        } else {
            settled = snapshot();
        }
        syncUndo();
    }

    private final Runnable settle = new Runnable() {
        @Override public void run() {
            if (!restoring) settled = snapshot();
        }
    };

    private void undo() {
        if (undoStack.isEmpty()) { Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show(); return; }
        redoStack.push(snapshot());
        restore(undoStack.pop());
        settled = snapshot();
        burstAt = 0;
        labDirty = true;
        syncSaveBtn();
        syncUndo();
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(snapshot());
        restore(redoStack.pop());
        settled = snapshot();
        burstAt = 0;
        labDirty = true;
        syncSaveBtn();
        syncUndo();
    }

    /** Grey means there is nothing back there — the button says so before you press it. */
    private void syncUndo() {
        if (undoBtn != null) {
            int ink = undoStack.isEmpty() ? SpriteTheme.DIMMER : SpriteTheme.INK;
            undoBtn.setTextColor(ink);
            tintIcon(undoBtn, ink);
        }
        if (redoBtn != null) {
            int ink = redoStack.isEmpty() ? SpriteTheme.DIMMER : SpriteTheme.INK;
            redoBtn.setTextColor(ink);
            tintIcon(redoBtn, ink);
        }
    }

    @NonNull
    private View buildTransport(float d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (9 * d);
        row.setPadding(pad, (int) (4 * d), pad, (int) (4 * d));

        preview = new CellCyclePreview(this);   // lives in the Play section; built here so the
        preview.bind(sheet, renderer);          // transport can drive it from any section

        playBtn = ichip("play");
        playBtn.setOnClickListener(v -> togglePlay());
        row.addView(playBtn);

        TextView prev = ichip("prev");
        prev.setOnClickListener(v -> stepLab(-1));
        TextView next = ichip("next");
        next.setOnClickListener(v -> stepLab(+1));
        row.addView(prev);
        row.addView(next);

        // How the roll ENDS, as three visible states rather than one button you have to
        // press twice to find out what it does now.
        LinearLayout wrapSeg = seg();
        addWrap(wrapSeg, "loop",     "loop", SpriteTheme.ACCENT_GRID);
        addWrap(wrapSeg, "pingpong", "ping", SpriteTheme.ACCENT_CELL);
        addWrap(wrapSeg, "once",     "once", SpriteTheme.LIVE);
        space(wrapSeg, d, 1);
        syncWrap();
        row.addView(wrapSeg);

        TextView rev = ichip("rev");
        rev.setOnClickListener(v -> {
            if (labSeq.isEmpty()) return;
            java.util.Collections.reverse(labSeq);
            labCur = labSeq.size() - 1 - Math.max(0, Math.min(labSeq.size() - 1, labCur));
            rebuildFilm();
            if (scrubBar != null) scrubBar.invalidate();
        });
        row.addView(rev);

        fpsPill = new NumPill("fps", null, () -> sheet.getFps(),
                v -> { sheet.setFps(Math.max(0.5f, Math.min(60f, v))); markDirty(); },
                1f, true, "");
        row.addView(fpsPill);

        space(row, d, 4);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private void addWrap(@NonNull LinearLayout parent, @NonNull String type,
                         @NonNull String icon, int colour) {
        TextView b = segBtn(icon);
        b.setTag(colour);
        b.setOnClickListener(v -> { labWrap = type; syncWrap(); markDirty(); });
        wrapBtns.put(type, b);
        parent.addView(b);
    }

    private void syncWrap() {
        for (java.util.Map.Entry<String, TextView> e : wrapBtns.entrySet()) {
            TextView b = e.getValue();
            tintSeg(b, e.getKey().equals(labWrap), (Integer) b.getTag());
        }
    }

    /** Play or stop the roll, keeping the button's face honest about which it will do. */
    private void togglePlay() {
        boolean now = !preview.isPlaying();
        preview.setPlaying(now);
        syncPlayBtn();
    }

    private void syncPlayBtn() {
        if (playBtn == null) return;
        boolean on = preview != null && preview.isPlaying();
        float d = density();
        playBtn.setCompoundDrawables(
                SpriteIcons.of(on ? "pause" : "play", on ? inkOn(SpriteTheme.SELECTED)
                        : SpriteTheme.INK, (int) (16 * d)), null, null, null);
        tintToggle(playBtn, on, SpriteTheme.SELECTED);
    }

    /** Solid accent or plain grey — never a wash. */
    private static void tintToggle(@NonNull TextView v, boolean on, int colour) {
        float d = v.getResources().getDisplayMetrics().density;
        int ink = on ? inkOn(colour) : SpriteTheme.DIM;
        v.setBackground(pillBg(on ? colour : SpriteTheme.CONTROL,
                on ? colour : SpriteTheme.LINE, d));
        v.setTextColor(ink);
        tintIcon(v, ink);
    }

    /**
     * The cell {@code offset} frames away from {@code from} — along the roll if one is being
     * built, otherwise along the enabled cells of the sheet.
     */
    int neighbourCell(int from, int offset) {
        if (!labSeq.isEmpty()) {
            // Prefer the frame the playhead is actually on. Searching for the first entry
            // that uses this cell ghosts the wrong neighbours the moment a cell repeats,
            // which on a hand-made sheet is most of the time.
            int at = -1;
            int cur = Math.max(0, Math.min(labSeq.size() - 1, labCur));
            if (labSeq.get(cur)[0] == from) at = cur;
            if (at < 0) {
                for (int i = 0; i < labSeq.size(); i++) if (labSeq.get(i)[0] == from) { at = i; break; }
            }
            if (at < 0) at = cur;
            int want = at + offset;
            if (want < 0 || want >= labSeq.size()) return -1;
            return labSeq.get(want)[0];
        }
        int c = from + offset;
        if (c < 0 || c >= sheet.cellCount()) return -1;
        SpriteSheet.Cell m = sheet.cellAt(c);
        return (m != null && !m.enabled) ? -1 : c;
    }

    private int labHold = 0;
    private int labDir = 1;

    /**
     * Step the roll by one FRAME, not one entry: a hold of x4 stays put for four ticks.
     *
     * @return false when a "once" roll has just run off its end, so playback stops there.
     */
    boolean advanceRoll() {
        if (labSeq.isEmpty()) return false;
        labCur = Math.max(0, Math.min(labSeq.size() - 1, labCur));
        if (++labHold < Math.max(1, labSeq.get(labCur)[1])) return true;
        labHold = 0;
        if (labSeq.size() == 1) return !"once".equals(labWrap);
        if ("pingpong".equals(labWrap)) {
            labCur += labDir;
            if (labCur >= labSeq.size()) { labCur = labSeq.size() - 2; labDir = -1; }
            else if (labCur < 0) { labCur = 1; labDir = 1; }
            return true;
        }
        labCur++;
        if (labCur < labSeq.size()) return true;
        if ("once".equals(labWrap)) { labCur = labSeq.size() - 1; return false; }
        labCur = 0;
        return true;
    }

    /** Retint the film instead of rebuilding it — playback touches this every frame. */
    void syncFilmCursor() {
        for (int i = 0; i < filmBoxes.size(); i++) {
            Object t = filmBoxes.get(i).getTag();
            if (t instanceof ChipTint) ((ChipTint) t).set(i == labCur, false);
        }
        if (scrubBar != null) scrubBar.invalidate();
        // The playhead moved, so the numbers on screen belong to a different frame now.
        syncAlign();
    }

    /**
     * THE current frame: the one the preview is showing and the one the controls edit.
     *
     * <p>Before this existed the preview followed the playhead and the alignment panel followed
     * the grid selection, and six things moved one without the other. You would nudge x and
     * watch a different drawing move.</p>
     */
    private int currentCell() {
        if (!labSeq.isEmpty()) {
            int i = Math.max(0, Math.min(labSeq.size() - 1, labCur));
            return labSeq.get(i)[0];
        }
        return Math.max(0, gridView.getSelectedCell());
    }

    /**
     * Point EVERYTHING at one cell: the grid's highlight, the preview, the alignment panel.
     *
     * <p>Every path that moves the playhead calls this. That is the whole fix — not a repair to
     * any one of them, but a single door they all have to go through.</p>
     */
    private void focusCell(int cell, boolean rebuild) {
        if (cell < 0 || sheet == null || sheet.cellCount() == 0) return;
        cell = Math.min(cell, sheet.cellCount() - 1);
        labSelected = cell;
        gridView.setSelectedCell(cell);
        gridView.setPlayingCell(cell);
        if (preview != null) preview.setCursor(cell);
        if (rebuild && benchBody != null) showSection(labSection);
    }

    /** Move the playhead to a frame of the roll, and point everything at what it lands on. */
    private void focusRoll(int index, boolean rebuild) {
        if (labSeq.isEmpty()) return;
        labCur = Math.max(0, Math.min(labSeq.size() - 1, index));
        labHold = 0;
        syncFilmCursor();
        focusCell(labSeq.get(labCur)[0], rebuild);
    }

    private void stepLab(int dir) {
        if (labSeq.isEmpty()) return;
        focusRoll(((labCur + dir) % labSeq.size() + labSeq.size()) % labSeq.size(), true);
    }

    // ── sections ─────────────────────────────────────────────────────────

    private void showSection(@NonNull String id) {
        labSection = id;
        syncNav();
        // Every number pill belongs to the section that built it; keeping stale ones alive
        // would have syncControls poking at views that are no longer on screen.
        stepperSyncs.clear();
        dragBtns.clear();
        clipShelf = null;
        clipDragFrom = -1;
        if (!"out".equals(id)) { pickedSheet = null; removedSheet = null; }
        // A picked clip lights its own frames on the grid. Leaving the section without
        // dropping it left those badges sitting over a roll that had nothing to do with them.
        if (!"clips".equals(id) && pickedClip != null) {
            pickedClip = null;
            gridView.setRoll(labSeq);
        }
        // Arranging is a thing you do in Slice. Leaving with it still armed means the next
        // drag on the sheet silently rearranges your work, in a section that does not even
        // show you the control that did it.
        if (!"slice".equals(id) && dragMode != DragMode.PAN) setDragMode(DragMode.PAN);
        benchBody.removeAllViews();
        switch (id) {
            case "slice": buildSliceSection(); break;
            case "clips": buildClipsSection(); break;
            case "out":   buildOutSection();   break;
            default:      buildPlaySection();  break;
        }
    }

    /** A collapsible group: coloured icon, white title, grey caret, on black. */
    @NonNull
    private TextView lastGroupSub;

    private LinearLayout group(@NonNull String key, int colour, @NonNull String icon,
                               @NonNull String title, @Nullable String sub) {
        float d = density();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, (int) (4 * d), 0, (int) (3 * d));

        TextView ic = new TextView(this);
        ic.setCompoundDrawables(SpriteIcons.of(icon, colour, (int) (14 * d)), null, null, null);
        ic.setCompoundDrawablePadding(0);
        head.addView(ic);

        TextView t = new TextView(this);
        t.setText("  " + title.toUpperCase());
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(10.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t);

        lastGroupSub = null;
        if (sub != null && !sub.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("  " + sub);
            e.setTextColor(SpriteTheme.DIMMER);
            e.setTextSize(9.5f);
            head.addView(e);
            lastGroupSub = e;
        }

        View sp = new View(this);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView caret = new TextView(this);
        caret.setCompoundDrawables(
                SpriteIcons.of("caret", SpriteTheme.DIMMER, (int) (13 * d)), null, null, null);
        caret.setCompoundDrawablePadding(0);
        head.addView(caret);

        final FlowLayout body = new FlowLayout(this);
        body.setPadding(0, 0, 0, (int) (5 * d));

        boolean open = openGroups.contains(key);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        caret.setRotation(open ? 0f : -90f);
        head.setOnClickListener(v -> {
            boolean nowOpen = !openGroups.contains(key);
            if (nowOpen) openGroups.add(key); else openGroups.remove(key);
            body.setVisibility(nowOpen ? View.VISIBLE : View.GONE);
            caret.animate().rotation(nowOpen ? 0f : -90f).setDuration(180).start();
            // Alignment opening or closing decides whether Sequence spans both columns.
            if ("align".equals(key) && "play".equals(labSection)) showSection("play");
        });

        wrap.addView(head);
        wrap.addView(body);
        wrap.setTag(body);
        return wrap;
    }

    /** The body a {@link #group} hands back, so callers can fill it. */
    @NonNull
    private static FlowLayout bodyOf(@NonNull View group) { return (FlowLayout) group.getTag(); }

    /** A pill that carries its group's colour when it is active, and grey when it is not. */
    @NonNull
    private TextView gchip(@NonNull String text, boolean active, int colour) {
        TextView c = chip(text);
        tintToggle(c, active, colour);
        return c;
    }

    // ── SLICE ────────────────────────────────────────────────────────────
    private void buildSliceSection() {
        float d = density();
        View g = group("slice1", SpriteTheme.ACCENT_GRID, "grid", "Grid & slicing",
                cellSizeLabel());
        FlowLayout b = bodyOf(g);
        sliceSub = lastGroupSub;
        b.addView(num("cols", null, () -> sheet.getCols(),
                v -> { sheet.setGrid(Math.max(1, Math.round(v)), sheet.getRows()); gridNumChanged(); },
                1f, true, ""));
        b.addView(num("rows", null, () -> sheet.getRows(),
                v -> { sheet.setGrid(sheet.getCols(), Math.max(1, Math.round(v))); gridNumChanged(); },
                1f, true, ""));
        b.addView(num("mg x", null, () -> sheet.getMarginX(),
                v -> { sheet.setMargins(Math.max(0, Math.round(v)), sheet.getMarginY()); gridNumChanged(); },
                1f, true, ""));
        b.addView(num("mg y", null, () -> sheet.getMarginY(),
                v -> { sheet.setMargins(sheet.getMarginX(), Math.max(0, Math.round(v))); gridNumChanged(); },
                1f, true, ""));
        b.addView(num("sp x", null, () -> sheet.getSpacingX(),
                v -> { sheet.setSpacing(Math.max(0, Math.round(v)), sheet.getSpacingY()); gridNumChanged(); },
                1f, true, ""));
        b.addView(num("sp y", null, () -> sheet.getSpacingY(),
                v -> { sheet.setSpacing(sheet.getSpacingX(), Math.max(0, Math.round(v))); gridNumChanged(); },
                1f, true, ""));
        b.addView(num("tol", null, () -> sheet.getKeyTolerance() * 100f,
                v -> { setTolerance(v / 100f); }, 1f, true, "%"));

        TextView detect = ichip("wand", "Detect");
        tintToggle(detect, true, SpriteTheme.ACCENT_GRID);
        detect.setOnClickListener(v -> { autoDetectGrid(); markDirty(); showSection("slice"); });
        b.addView(detect);
        detectRow(b);

        keyBtn = ichip("drop", "Bg key");
        keyBtn.setOnClickListener(v -> { onKeyChipTapped(); syncKeyChip(); });
        b.addView(keyBtn);
        syncKeyChip();

        // What a DRAG on the sheet does. Four mutually exclusive answers, so one segment —
        // not four chips that look like the three display switches next to them and can be
        // armed two at a time.
        LinearLayout dragSeg = seg();
        // The word rides INSIDE the pill. Outside it, the wrap put "drag" at the end of the
        // previous line and left the segment orphaned underneath, labelling nothing.
        TextView dragLabel = new TextView(this);
        dragLabel.setText("drag");
        dragLabel.setTextSize(9.5f);
        dragLabel.setTextColor(SpriteTheme.DIMMER);
        dragLabel.setPadding((int) (7 * d), 0, (int) (3 * d), 0);
        dragSeg.addView(dragLabel);
        addDrag(dragSeg, DragMode.PAN, "Pan", SpriteTheme.DIM);
        addDrag(dragSeg, DragMode.PIVOT, "Pivot", SpriteTheme.LIVE);
        addDrag(dragSeg, DragMode.SWAP, "Swap", SpriteTheme.ACCENT_GRID);
        addDrag(dragSeg, DragMode.RIPPLE, "Ripple", SpriteTheme.ACCENT_GRID);
        space(dragSeg, d, 1);
        syncDrag();
        b.addView(dragSeg);

        if (sheet.hasCustomOrder()) {
            TextView reset = ichip("undo", "Reset order");
            reset.setOnClickListener(v -> {
                sheet.resetOrder();
                markDirty();
                refreshArt();
                showSection("slice");
                Toast.makeText(this, "Every drawing is back where it started",
                        Toast.LENGTH_SHORT).show();
            });
            b.addView(reset);
        }
        benchBody.addView(g);
        syncControls();

        // ── the cell's own identity: name, tags, viseme ──
        int cell = currentCell();
        String nm = sheet.cellName(cell);
        View cg = group("cellEd", SpriteTheme.ACCENT_CELL, "tag",
                "Cell " + cell, nm == null || nm.isEmpty() ? "unnamed" : nm);
        FlowLayout cb = bodyOf(cg);

        cellNameField = new EditText(this);
        cellNameField.setSingleLine(true);
        cellNameField.setTextColor(SpriteTheme.INK);
        cellNameField.setTextSize(12f);
        cellNameField.setHint(R.string.sprite_editor_cell_name_hint);
        cellNameField.setHintTextColor(SpriteTheme.DIMMER);
        cellNameField.setText(nm == null ? "" : nm);
        cellNameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a2, int b2, int c2) {}
            @Override public void onTextChanged(CharSequence c, int a2, int b2, int c2) {}
            @Override public void afterTextChanged(Editable e) {
                // currentCell(), not the captured index: a re-slice or a playback frame can
                // change what that number means while this field is still on screen.
                int at = currentCell();
                ensureCell(at);                // so the name has somewhere to live
                sheet.setCellName(at, e.toString());
                markDirty();
                gridView.refresh();
            }
        });
        LinearLayout.LayoutParams nfLp = new LinearLayout.LayoutParams(
                (int) (170 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
        cb.addView(cellNameField, nfLp);

        cellEnabled = new Switch(this);
        cellEnabled.setText(getString(R.string.sprite_editor_cell_enabled));
        cellEnabled.setTextColor(SpriteTheme.DIM);
        SpriteSheet.Cell meta = sheet.cellAt(cell);
        cellEnabled.setChecked(meta == null || meta.enabled);
        cellEnabled.setOnCheckedChangeListener((bv, on) -> {
            ensureCell(currentCell()).enabled = on; markDirty(); gridView.refresh();
        });
        cb.addView(cellEnabled);

        // Visemes. A cell is not consumed by an assignment: "surprised" stays an expression
        // AND answers for OO, which is the whole economy of a small hand-made sheet.
        TextView vLabel = new TextView(this);
        vLabel.setText("Viseme");
        vLabel.setTextColor(SpriteTheme.DIMMER);
        vLabel.setTextSize(10f);
        cb.addView(vLabel);
        for (String v : VISEMES) {
            boolean on = v.equals(sheet.visemeOfCell(cell));
            TextView vb = gchip(v, on, SpriteTheme.ACCENT_CELL);
            vb.setOnClickListener(x -> {
                sheet.assignViseme(v, on ? -1 : currentCell());
                markDirty();
                showSection("slice");
            });
            cb.addView(vb);
        }
        benchBody.addView(cg);
    }

    /**
     * What the grid DRAWS, and what looks wrong.
     *
     * <p>On a 6x8 sheet the lettering is most of the picture, so it comes off. Suspect is the
     * cheap version of the question "did I slice this right": a cell whose ink runs into its
     * own edge is either clipped or the grid is off by a row.</p>
     */
    private void detectRow(@NonNull FlowLayout b) {
        TextView grid = ichip("grid", "Grid");
        tintToggle(grid, gridView.isShowGrid(), SpriteTheme.ACCENT_GRID);
        grid.setOnClickListener(v -> {
            gridView.setShowGrid(!gridView.isShowGrid());
            tintToggle(grid, gridView.isShowGrid(), SpriteTheme.ACCENT_GRID);
        });
        b.addView(grid);

        TextView names = ichip("tag", "Names");
        tintToggle(names, gridView.isShowLabels(), SpriteTheme.ACCENT_GRID);
        names.setOnClickListener(v -> {
            gridView.setShowLabels(!gridView.isShowLabels());
            tintToggle(names, gridView.isShowLabels(), SpriteTheme.ACCENT_GRID);
        });
        b.addView(names);

        TextView sus = chip("Suspect");
        tintToggle(sus, gridView.isShowingSuspect(), SpriteTheme.WARN);
        sus.setOnClickListener(v -> {
            if (gridView.isShowingSuspect()) {
                gridView.setSuspect(null);
            } else {
                java.util.Set<Integer> bad = findSuspectCells();
                gridView.setSuspect(bad);
                Toast.makeText(this, bad.isEmpty()
                        ? "Every cell fits inside its own box"
                        : bad.size() + (bad.size() == 1 ? " cell looks clipped"
                                                        : " cells look clipped"),
                        Toast.LENGTH_SHORT).show();
            }
            tintToggle(sus, gridView.isShowingSuspect(), SpriteTheme.WARN);
        });
        b.addView(sus);

        TextView many = ichip("tag", "Name many\u2026");
        many.setOnClickListener(v -> nameMany(Math.max(0, gridView.getSelectedCell())));
        b.addView(many);
    }

    /** The four things a drag on the sheet can mean. Exactly one is true at a time. */
    private enum DragMode { PAN, PIVOT, SWAP, RIPPLE }

    private final java.util.Map<DragMode, TextView> dragBtns = new java.util.LinkedHashMap<>();

    private void addDrag(@NonNull LinearLayout parent, @NonNull DragMode mode,
                         @NonNull String label, int colour) {
        float d = density();
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(11f);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight((int) (24 * d));
        b.setPadding((int) (9 * d), (int) (2 * d), (int) (9 * d), (int) (2 * d));
        b.setTag(colour);
        b.setOnClickListener(v -> {
            setDragMode(mode);
            if (mode == DragMode.SWAP || mode == DragMode.RIPPLE) {
                Toast.makeText(this, mode == DragMode.SWAP
                        ? "Drag a cell onto another to trade places"
                        : "Drag a cell where you want it; the rest shuffle up",
                        Toast.LENGTH_SHORT).show();
            }
        });
        dragBtns.put(mode, b);
        parent.addView(b);
    }

    private DragMode dragMode = DragMode.PAN;

    /** One setter, so two modes can never be armed at once. */
    private void setDragMode(@NonNull DragMode mode) {
        dragMode = mode;
        gridView.setPivotMode(mode == DragMode.PIVOT);
        gridView.setReorderMode(mode == DragMode.SWAP ? SpriteGridEditorView.Reorder.SWAP
                : mode == DragMode.RIPPLE ? SpriteGridEditorView.Reorder.RIPPLE
                : SpriteGridEditorView.Reorder.OFF);
        syncDrag();
    }

    private void syncDrag() {
        for (java.util.Map.Entry<DragMode, TextView> e : dragBtns.entrySet()) {
            TextView b = e.getValue();
            // Pan is the neutral mode, but it is still a mode, and DIM-on-CONTROL made the
            // active one look switched off. A light grey pill with dark ink reads as chosen
            // without borrowing a colour that already means something else.
            tintSeg(b, dragMode == e.getKey(), (Integer) b.getTag());
        }
    }

    /**
     * Cells whose ink touches their own edge, or that are empty.
     *
     * <p>Both mean the same thing in practice: the grid is wrong, or the art was rendered off
     * its tile. Either way you want to know BEFORE you spend an hour aligning.</p>
     */
    @NonNull
    private java.util.Set<Integer> findSuspectCells() {
        java.util.Set<Integer> bad = new java.util.LinkedHashSet<>();
        if (renderer == null) return bad;
        android.graphics.Bitmap bmp = renderer.getBitmap();
        if (bmp == null || bmp.isRecycled()) return bad;
        for (int i = 0; i < sheet.cellCount(); i++) {
            SpriteSheet.Cell m = sheet.cellAt(i);
            if (m != null && !m.enabled) continue;
            android.graphics.Rect cr = renderer.cellRectBitmap(i);
            float[] box = inkBox(bmp, i);
            if (box == null) { bad.add(i); continue; }   // nothing drawn here at all
            float w = Math.max(1, cr.width()), h = Math.max(1, cr.height());
            if (box[0] <= 1f || box[1] <= 1f || box[2] >= w - 1f || box[3] >= h - 1f) {
                bad.add(i);
            }
        }
        return bad;
    }

    /**
     * Name every cell in one sitting, with the art in front of you.
     *
     * <p>Going back to the grid, tapping a cell, scrolling to the field and typing is four
     * moves per name; on a sixteen-cell sheet that is sixty-four moves, which is why sheets
     * stay unnamed and the assistant stays blind.</p>
     */
    private void nameMany(int startCell) {
        if (sheet.cellCount() == 0) return;
        final float d = density();
        final int[] at = {Math.max(0, Math.min(sheet.cellCount() - 1, startCell))};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (16 * d);
        box.setPadding(pad, pad, pad, 0);

        final PresetThumb art = new PresetThumb(this, renderer, null);
        box.addView(art, new LinearLayout.LayoutParams((int) (120 * d), (int) (120 * d)));

        final TextView caption = new TextView(this);
        caption.setTextColor(SpriteTheme.DIMMER);
        caption.setTextSize(11f);
        box.addView(caption);

        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(R.string.sprite_editor_cell_name_hint);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        box.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable show = () -> {
            art.setStill(at[0]);
            String nm = sheet.cellName(at[0]);
            field.setText(nm == null ? "" : nm);
            field.setSelection(field.getText().length());
            // "Cell 0 of 31" on a 32-cell sheet: the number was right and the idiom was
            // not, because "N of M" counts and that M was a maximum index. Say both plainly.
            caption.setText("Cell " + at[0] + "  \u00b7  " + (at[0] + 1)
                    + " of " + sheet.cellCount());
        };
        final Runnable commit = () -> {
            ensureCell(at[0]);
            sheet.setCellName(at[0], field.getText().toString());
            markDirty();
        };
        final Runnable step = () -> {
            commit.run();
            at[0] = (at[0] + 1) % sheet.cellCount();
            show.run();
        };
        show.run();
        field.setOnEditorActionListener((v, action, e) -> { step.run(); return true; });

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Name the cells")
                .setView(box)
                // "Next" keeps the dialog open by design — that is the whole point of it.
                .setNeutralButton("Next \u203a", null)
                .setPositiveButton("Done", (dl, w) -> {
                    commit.run();
                    gridView.refresh();
                    showSection("slice");
                })
                .setNegativeButton("Cancel", null)
                .show()
                .getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
                .setOnClickListener(v -> step.run());
    }

    @NonNull
    private String cellSizeLabel() {
        if (renderer == null) return sheet.cellCount() + " cells";
        android.graphics.Rect r = SpriteSheetRenderer.cellRectSource(
                sheet, 0, renderer.sourceWidth(), renderer.sourceHeight());
        return r.width() + "×" + r.height() + " px · " + sheet.cellCount() + " cells";
    }

    // ── PLAY ─────────────────────────────────────────────────────────────
    private void buildPlaySection() {
        float d = density();
        boolean full = benchPx >= (int) (340 * d);
        int pvW = full ? ViewGroup.LayoutParams.MATCH_PARENT : (int) (Math.min(184, benchPx / d * 0.56) * d);
        int pvH = full ? Math.min((int) (290 * d), benchPx - (int) (150 * d))
                       : Math.min(benchPx - (int) (84 * d), (int) (pvW * 0.95f));
        if (pvH < (int) (70 * d)) pvH = (int) (70 * d);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(full ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        if (preview.getParent() instanceof ViewGroup) {
            ((ViewGroup) preview.getParent()).removeView(preview);
        }
        left.addView(preview, new LinearLayout.LayoutParams(
                full ? ViewGroup.LayoutParams.MATCH_PARENT : pvW, pvH));
        left.addView(buildOnionBar(d, full ? ViewGroup.LayoutParams.MATCH_PARENT : pvW));
        cols.addView(left, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        int cell = currentCell();
        right.addView(buildAlignGroup(cell));
        // Sequence spans beneath both ONLY while Alignment is open; collapse Alignment and it
        // moves up into the space that just freed, instead of leaving a black rectangle.
        boolean alignOpen = openGroups.contains("align");
        if (!alignOpen) right.addView(buildSeqGroup());
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                full ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                ViewGroup.LayoutParams.WRAP_CONTENT, full ? 0f : 1f);
        rLp.leftMargin = full ? 0 : (int) (7 * d);
        cols.addView(right, rLp);

        benchBody.addView(cols);
        if (alignOpen) benchBody.addView(buildSeqGroup());
    }

    @NonNull
    private View buildOnionBar(float d, int width) {
        // No header on this row: the label would cost as much as the content.
        FlowLayout bar = new FlowLayout(this);
        bar.setPadding(0, (int) (4 * d), 0, 0);
        onionToggle = ichip("onion");
        onionToggle.setOnClickListener(v -> {
            onionMode = !onionMode;
            syncOnion();
            preview.invalidate();
        });
        bar.addView(onionToggle);

        // The colour IS the control: slide a pill to change how many ghosts that side
        // shows, tap it to choose what colour they come out. Two buttons instead of two
        // swatches plus two number fields.
        pastPill = onionPill(true);
        futurePill = onionPill(false);
        bar.addView(pastPill);
        bar.addView(futurePill);

        bar.addView(num("", null, () -> onionStrength * 100f,
                v -> {
                    onionStrength = Math.max(0.05f, Math.min(1f, v / 100f));
                    preview.invalidate();
                }, 5f, true, "%"));

        // One button for the ground behind the art: tap cycles it, hold lists it.
        bgSwatch = new BgSwatch(this);
        bgSwatch.setOnClickListener(v -> {
            previewBg = (previewBg + 1) % BG_NAMES.length;
            bgSwatch.invalidate();
            preview.invalidate();
        });
        bgSwatch.setOnLongClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Preview background")
                    .setItems(BG_NAMES, (dl, which) -> {
                        previewBg = which;
                        bgSwatch.invalidate();
                        preview.invalidate();
                    })
                    .show();
            return true;
        });
        bar.addView(bgSwatch, new ViewGroup.LayoutParams((int) (26 * d), (int) (26 * d)));

        syncOnion();
        return bar;
    }

    /**
     * One side's ghosts. Sliding changes how many; tapping picks the colour they draw in.
     *
     * <p>When onion skin is off the pills go plain grey rather than a wash of their own
     * colour: a half-opaque control is a lie about whether it is on.</p>
     */
    @NonNull
    private TextView onionPill(boolean past) {
        final float d = density();
        TextView pill = chip("0");
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setPadding((int) (9 * d), (int) (5 * d), (int) (9 * d), (int) (5 * d));
        pill.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            boolean dragged;
            @Override public boolean onTouch(View v, MotionEvent e) {
                float per = 13 * d;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        dragged = false;
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int steps = (int) ((e.getRawX() - downX) / per);
                        if (steps != 0) {
                            dragged = true;
                            downX += steps * per;
                            if (past) onionPast = Math.max(0, Math.min(6, onionPast + steps));
                            else onionFuture = Math.max(0, Math.min(6, onionFuture + steps));
                            syncOnion();
                            preview.invalidate();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        if (!dragged) showGhostSwatch(past);
                        return true;
                    default:
                        return true;
                }
            }
        });
        return pill;
    }

    /** Keep the three onion controls telling the same story. */
    private void syncOnion() {
        if (onionToggle != null) tintToggle(onionToggle, onionMode, SpriteTheme.ACCENT_VIEW);
        if (pastPill != null) {
            pastPill.setText(String.valueOf(onionPast));
            tintToggle(pastPill, onionMode && onionPast > 0, onionPastColour);
        }
        if (futurePill != null) {
            futurePill.setText(String.valueOf(onionFuture));
            tintToggle(futurePill, onionMode && onionFuture > 0, onionFutureColour);
        }
    }

    /** The ghost colours on offer. State colours first, because those are the defaults. */
    private static final int[] SWATCHES = {
            SpriteTheme.LIVE, SpriteTheme.SELECTED, SpriteTheme.ACCENT_GRID,
            SpriteTheme.ACCENT_OUT, SpriteTheme.ACCENT_VIEW, SpriteTheme.ACCENT_CELL,
            0xFFFFFFFF, 0xFFFB7185};

    private void showGhostSwatch(boolean past) {
        float d = density();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int pad = (int) (16 * d);
        row.setPadding(pad, pad, pad, pad);
        final androidx.appcompat.app.AlertDialog dlg = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(past ? "Past ghosts" : "Future ghosts")
                .setView(row)
                .setNegativeButton("Close", null)
                .create();
        for (int c : SWATCHES) {
            final int colour = c;
            View sw = new View(this);
            sw.setBackground(pillBg(colour, SpriteTheme.LINE, d));
            sw.setOnClickListener(v -> {
                if (past) onionPastColour = colour; else onionFutureColour = colour;
                syncOnion();
                preview.invalidate();
                dlg.dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (34 * d), (int) (34 * d));
            lp.leftMargin = (int) (4 * d);
            row.addView(sw, lp);
        }
        dlg.show();
    }

    // ── the ground behind the art ────────────────────────────────────────

    /** The eight preview grounds, in the order the swatch cycles them. */
    static final String[] BG_NAMES = {"Checker", "Dotted grid", "Black", "White",
            "Zinc", "Cyan", "Magenta", "Green"};
    /** Flat fills for the ones that are just a colour; the first two are patterns. */
    private static final int[] BG_FLAT = {0, 0, 0xFF000000, 0xFFFFFFFF, 0xFF18181C,
            0xFF22D3EE, 0xFFF43F8E, 0xFF34D399};

    /**
     * Paint one of the preview grounds into {@code r}.
     *
     * <p>The little swatch and the big preview call this same method, so the button can
     * never lie about what you are about to get.</p>
     */
    static void drawBg(@NonNull Canvas c, @NonNull RectF r, int which, float d,
                       @NonNull Paint p) {
        int idx = Math.max(0, Math.min(BG_FLAT.length - 1, which));
        p.setColorFilter(null);
        p.setStyle(Paint.Style.FILL);
        int save = c.save();
        c.clipRect(r);
        if (idx == 0) {
            float sq = 7.5f * d;
            p.setColor(0xFF212128);
            c.drawRect(r, p);
            p.setColor(0xFF3A3A45);
            int row = 0;
            for (float y = r.top; y < r.bottom; y += sq, row++) {
                for (int col = row & 1; ; col += 2) {
                    float x = r.left + col * sq;
                    if (x >= r.right) break;
                    c.drawRect(x, y, Math.min(x + sq, r.right), Math.min(y + sq, r.bottom), p);
                }
            }
        } else if (idx == 1) {
            p.setColor(0xFF121215);
            c.drawRect(r, p);
            p.setColor(0xFF3F3F46);
            float pitch = 11 * d;
            for (float y = r.top + pitch / 2; y < r.bottom; y += pitch) {
                for (float x = r.left + pitch / 2; x < r.right; x += pitch) {
                    c.drawCircle(x, y, 1.2f * d, p);
                }
            }
        } else {
            p.setColor(BG_FLAT[idx]);
            c.drawRect(r, p);
        }
        c.restoreToCount(save);
    }

    /** A round button that IS the ground it selects. */
    private class BgSwatch extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();

        BgSwatch(Context c) { super(c); }

        @Override protected void onDraw(Canvas canvas) {
            float d = density();
            r.set(0, 0, getWidth(), getHeight());
            float rad = Math.min(getWidth(), getHeight()) / 2f;
            int save = canvas.save();
            clip.reset();
            clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            // A tighter pitch, so the pattern still reads at button size.
            drawBg(canvas, r, previewBg, d * 0.45f, p);
            canvas.restoreToCount(save);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(SpriteTheme.LINE);
            canvas.drawRoundRect(r, rad, rad, p);
        }
    }

    /**
     * Luminance, then a hue. The shape has to stay readable, so brightness survives and only
     * the colour is replaced; the 0.30 floor keeps dark art from disappearing into the ground
     * instead of reading as a ghost.
     */
    @NonNull
    private static Paint ghostPaint(int colour, int alpha) {
        float r = ((colour >> 16) & 0xFF) / 255f;
        float g = ((colour >> 8) & 0xFF) / 255f;
        float b = (colour & 0xFF) / 255f;
        float k = 0.70f;
        float[] m = {
                0.2126f * r * k, 0.7152f * r * k, 0.0722f * r * k, 0f, 0.30f * r * 255f,
                0.2126f * g * k, 0.7152f * g * k, 0.0722f * g * k, 0f, 0.30f * g * 255f,
                0.2126f * b * k, 0.7152f * b * k, 0.0722f * b * k, 0f, 0.30f * b * 255f,
                0f, 0f, 0f, 1f, 0f
        };
        Paint p = new Paint();
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(
                new android.graphics.ColorMatrix(m)));
        p.setAlpha(alpha);
        return p;
    }

    @NonNull
    private View buildAlignGroup(int cell) {
        String nm = sheet.cellName(cell);
        View g = group("align", SpriteTheme.ACCENT_ALIGN, "target", "Alignment",
                "cell " + cell + (nm == null || nm.isEmpty() ? "" : " · " + nm));
        alignSub = lastGroupSub;
        FlowLayout b = bodyOf(g);

        // LIVE, not captured. These used to hold a private copy of ONE cell's transform, taken
        // when the panel was built. Playback moves the frame without rebuilding anything, so
        // the slider showed one drawing's numbers and wrote them to another's. Reading the
        // current frame at the moment of the edit cannot drift.
        b.addView(num("x", "movex", () -> liveXf().dx,
                v -> editXf(x -> x.dx = v), 1f, true, ""));
        b.addView(num("y", "movey", () -> liveXf().dy,
                v -> editXf(x -> x.dy = v), 1f, true, ""));
        b.addView(num("scale", "scale", () -> liveXf().scale,
                v -> editXf(x -> x.scale = Math.max(0.05f, v)), 0.05f, false, "×"));
        b.addView(num("rot", "rot", () -> liveXf().rot,
                v -> editXf(x -> x.rot = v), 5f, true, "°"));

        TextView centre = ichip("wand", "Auto-centre");
        tintToggle(centre, true, SpriteTheme.ACCENT_ALIGN);
        centre.setOnClickListener(v -> { autoCentre(false); showSection("play"); });
        b.addView(centre);
        TextView feet = chip("Plant feet");
        feet.setOnClickListener(v -> { autoCentre(true); showSection("play"); });
        b.addView(feet);
        TextView reset = chip("Reset");
        reset.setOnClickListener(v -> {
            sheet.setCellTransform(currentCell(), null);
            markDirty(); refreshArt(); showSection("play");
        });
        b.addView(reset);
        TextView resetAll = chip("Reset all");
        resetAll.setOnClickListener(v -> {
            sheet.getCellTransforms().clear(); markDirty(); refreshArt(); showSection("play");
        });
        b.addView(resetAll);

        // Line every frame up against THIS one, rather than against the pivot. Auto-centre is
        // right when the sheet has no reference frame; this is right when it has one and you
        // have already got that frame where you want it.
        TextView match = chip("Match");
        match.setOnClickListener(v -> matchToCurrent());
        b.addView(match);

        // The same nudge on every frame. Useful the moment you discover the whole sheet sits
        // six pixels left, which is most AI-generated sheets.
        TextView copyAll = chip("Copy to all");
        copyAll.setOnClickListener(v -> copyXfToAll());
        b.addView(copyAll);

        // Numbers, not an automatic fix. Sometimes the drift IS the animation.
        TextView drift = chip("Drift\u2026");
        drift.setOnClickListener(v -> showDrift());
        b.addView(drift);

        // The pivot everything else measures against: auto-centre, rotation and the bake all
        // use it, so it belongs beside them rather than buried in the slicing controls.
        b.addView(num("piv x", null, () -> sheet.getPivotX() * 100f,
                v -> { sheet.setPivot(v / 100f, sheet.getPivotY()); markDirty(); refreshArt(); },
                5f, true, "%"));
        b.addView(num("piv y", null, () -> sheet.getPivotY() * 100f,
                v -> { sheet.setPivot(sheet.getPivotX(), v / 100f); markDirty(); refreshArt(); },
                5f, true, "%"));
        return g;
    }

    /**
     * The frames these bulk tools act on: the roll if there is one, otherwise the whole sheet.
     *
     * <p>Shared so auto-centre, match, copy-to-all and drift can never disagree about what
     * "every frame" means — which they would, written four times.</p>
     */
    @NonNull
    private java.util.List<Integer> alignTargets() {
        java.util.List<Integer> cells = new java.util.ArrayList<>();
        if (!labSeq.isEmpty()) { for (int[] f : labSeq) if (!cells.contains(f[0])) cells.add(f[0]); }
        else for (int i = 0; i < sheet.cellCount(); i++) cells.add(i);
        return cells;
    }

    /** Every frame's ink box, measured once, keyed by cell. Empty cells are simply absent. */
    @NonNull
    private java.util.Map<Integer, float[]> inkBoxes(@NonNull java.util.List<Integer> cells) {
        java.util.Map<Integer, float[]> boxes = new java.util.LinkedHashMap<>();
        if (renderer == null) return boxes;
        android.graphics.Bitmap bmp = renderer.getBitmap();
        if (bmp == null || bmp.isRecycled()) return boxes;
        for (int c : cells) {
            float[] box = inkBox(bmp, c);
            if (box != null) boxes.put(c, box);
        }
        return boxes;
    }

    /**
     * Align every frame's ink to the CURRENT frame's ink.
     *
     * <p>The current frame is left exactly as it is — it is the reference, and a tool that
     * moved its own reference would be impossible to reason about.</p>
     */
    private void matchToCurrent() {
        if (renderer == null) { Toast.makeText(this, "No art loaded", Toast.LENGTH_SHORT).show(); return; }
        android.graphics.Bitmap bmp = renderer.getBitmap();
        if (bmp == null || bmp.isRecycled()) return;
        float toSource = renderer.sourceWidth() / (float) Math.max(1, bmp.getWidth());
        final int ref = currentCell();
        float[] target = inkBox(bmp, ref);
        if (target == null) {
            Toast.makeText(this, "This frame is empty \u2014 nothing to match to",
                    Toast.LENGTH_LONG).show();
            return;
        }
        float tx = (target[0] + target[2]) * 0.5f, ty = (target[1] + target[3]) * 0.5f;
        java.util.Map<Integer, float[]> boxes = inkBoxes(alignTargets());
        int n = 0;
        for (java.util.Map.Entry<Integer, float[]> e : boxes.entrySet()) {
            int c = e.getKey();
            if (c == ref) continue;
            float[] box = e.getValue();
            SpriteSheet.CellXf t = sheet.cellTransform(c);
            SpriteSheet.CellXf xf = t == null ? new SpriteSheet.CellXf() : t.copy();
            xf.dx += (tx - (box[0] + box[2]) * 0.5f) * toSource;
            xf.dy += (ty - (box[1] + box[3]) * 0.5f) * toSource;
            sheet.setCellTransform(c, xf);
            n++;
        }
        markDirty();
        refreshArt();
        syncAlign();
        Toast.makeText(this, n == 0 ? "Nothing else to match"
                : n + " frames matched to cell " + ref, Toast.LENGTH_SHORT).show();
    }

    /** Put THIS frame's alignment on every frame. One press, one undo step. */
    private void copyXfToAll() {
        final int from = currentCell();
        SpriteSheet.CellXf src = sheet.cellTransform(from);
        int n = 0;
        for (int c : alignTargets()) {
            if (c == from) continue;
            sheet.setCellTransform(c, src == null ? null : src.copy());
            n++;
        }
        markDirty();
        refreshArt();
        syncAlign();
        Toast.makeText(this, src == null
                ? "Cleared the alignment on " + n + " frames"
                : "Copied cell " + from + " alignment to " + n + " frames",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Show the drift as NUMBERS rather than fixing it.
     *
     * <p>Auto-centre is the right answer when a sheet wobbles by accident. Sometimes the wobble
     * IS the animation, and then what you want is to see how far each frame sits from the rest
     * and decide yourself. This reports and changes nothing — with auto-centre one tap away for
     * when the numbers tell you it was an accident after all.</p>
     */
    private void showDrift() {
        java.util.List<Integer> cells = alignTargets();
        java.util.Map<Integer, float[]> boxes = inkBoxes(cells);
        if (boxes.isEmpty()) {
            Toast.makeText(this, "No ink to measure", Toast.LENGTH_SHORT).show();
            return;
        }
        float sx = 0, sy = 0;
        for (float[] b : boxes.values()) {
            sx += (b[0] + b[2]) * 0.5f;
            sy += (b[1] + b[3]) * 0.5f;
        }
        float ax = sx / boxes.size(), ay = sy / boxes.size();
        StringBuilder sb = new StringBuilder();
        sb.append("How far each frame sits from the average, in cell pixels.\n")
          .append("Positive x is right, positive y is down.\n\n");
        float worst = 0; int worstCell = -1;
        for (java.util.Map.Entry<Integer, float[]> e : boxes.entrySet()) {
            float[] b = e.getValue();
            float dx = (b[0] + b[2]) * 0.5f - ax, dy = (b[1] + b[3]) * 0.5f - ay;
            float mag = (float) Math.hypot(dx, dy);
            if (mag > worst) { worst = mag; worstCell = e.getKey(); }
            String nm = sheet.cellName(e.getKey());
            sb.append(String.format(java.util.Locale.US, "cell %-3d  x %+5.0f  y %+5.0f",
                    e.getKey(), dx, dy));
            if (nm != null && !nm.isEmpty()) sb.append("   ").append(nm);
            sb.append('\n');
        }
        for (int c : cells) {
            if (!boxes.containsKey(c)) sb.append("cell ").append(c).append("   empty\n");
        }
        if (worstCell >= 0) {
            sb.append("\nFurthest out: cell ").append(worstCell)
              .append(", about ").append(Math.round(worst)).append(" px from the average.");
        }
        TextView t = new TextView(this);
        t.setText(sb.toString());
        t.setTextSize(12f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(SpriteTheme.INK);
        int pad = (int) (18 * density());
        t.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Drift")
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Auto-centre it", (d, w) -> autoCentre(false))
                .show();
    }

    /**
     * Move the current frame by a drag on the preview.
     *
     * <p>Live and cheap: the art follows the finger and nothing rebuilds. The number pills catch
     * up in {@link #alignGestureEnded}, once, when the hand comes off.</p>
     */
    void nudgeCurrentCell(float ddx, float ddy) {
        int cell = currentCell();
        SpriteSheet.CellXf t = sheet.cellTransform(cell);
        SpriteSheet.CellXf xf = t == null ? new SpriteSheet.CellXf() : t.copy();
        xf.dx += ddx;
        xf.dy += ddy;
        sheet.setCellTransform(cell, xf);
        markDirty();
        refreshArt();
    }

    /** Pinch the current frame. */
    void scaleCurrentCell(float factor) {
        if (factor <= 0f || Math.abs(factor - 1f) < 1e-4f) return;
        int cell = currentCell();
        SpriteSheet.CellXf t = sheet.cellTransform(cell);
        SpriteSheet.CellXf xf = t == null ? new SpriteSheet.CellXf() : t.copy();
        xf.scale = Math.max(0.05f, Math.min(8f, xf.scale * factor));
        sheet.setCellTransform(cell, xf);
        markDirty();
        refreshArt();
    }

    /** The hand came off the preview: let the numbers show what the drag did. */
    void alignGestureEnded() {
        if ("play".equals(labSection)) showSection("play");
    }

    private interface XfEdit { void apply(@NonNull SpriteSheet.CellXf xf); }

    private TextView alignSub;

    /** The CURRENT frame's transform, read fresh, as a copy nobody can mutate behind us. */
    @NonNull
    private SpriteSheet.CellXf liveXf() {
        SpriteSheet.CellXf t = sheet.cellTransform(currentCell());
        return t == null ? new SpriteSheet.CellXf() : t.copy();
    }

    /**
     * Edit the CURRENT frame's transform.
     *
     * <p>Both the cell and its values are read at the moment of the edit. A captured copy is
     * how a slider ends up moving a drawing you stopped looking at three frames ago.</p>
     */
    private void editXf(@NonNull XfEdit edit) {
        int cell = currentCell();
        SpriteSheet.CellXf t = sheet.cellTransform(cell);
        SpriteSheet.CellXf xf = t == null ? new SpriteSheet.CellXf() : t.copy();
        edit.apply(xf);
        sheet.setCellTransform(cell, xf);
        markDirty();
        refreshArt();
        syncAlign();
    }

    /**
     * Bring the alignment panel up to date WITHOUT rebuilding it.
     *
     * <p>Called on every playback frame and every scrub move, so it has to stay cheap: one
     * label and a handful of setText. Rebuilding the bench thirty times a second is not an
     * option, and leaving the panel showing the previous frame's numbers is what started all
     * of this.</p>
     */
    private void syncAlign() {
        if (alignSub != null) {
            int cell = currentCell();
            String nm = sheet.cellName(cell);
            alignSub.setText("  cell " + cell
                    + (nm == null || nm.isEmpty() ? "" : " - " + nm));
        }
        for (Runnable r : stepperSyncs) r.run();
    }
    private void refreshArt() {
        gridView.refresh();
        preview.invalidate();
        // Repaint the roll, do not rebuild it. A nudge changes how the frames LOOK, not which
        // frames they are, and building forty chips per drag tick is how a slider feels broken.
        invalidateFilm();
    }

    /** Repaint every thumbnail in the film strip in place. */
    private void invalidateFilm() {
        for (View chip : filmBoxes) invalidateTree(chip);
    }

    private static void invalidateTree(@NonNull View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    @NonNull
    private View buildSeqGroup() {
        View g = group("seq", SpriteTheme.ACCENT_SEQ, "layers", "Sequence",
                labSeq.size() + " frames");
        FlowLayout b = bodyOf(g);
        TextView add = ichip("plus", "this cell");
        add.setOnClickListener(v -> {
            noteChange();
            labSeq.add(new int[]{currentCell(), 1});
            rebuildFilm();
            focusRoll(labSeq.size() - 1, true);
        });
        b.addView(add);
        TextView all = chip("Add all");
        all.setOnClickListener(v -> {
            noteChange();
            labSeq.clear();
            for (int i = 0; i < sheet.cellCount(); i++) {
                SpriteSheet.Cell m = sheet.cellAt(i);
                if (m != null && !m.enabled) continue;
                labSeq.add(new int[]{i, 1});
            }
            rebuildFilm();
            focusRoll(0, true);
        });
        b.addView(all);
        TextView holdUp = chip("Hold +");
        holdUp.setOnClickListener(v -> { bumpHold(+1); });
        b.addView(holdUp);
        TextView holdDn = chip("Hold −");
        holdDn.setOnClickListener(v -> { bumpHold(-1); });
        b.addView(holdDn);
        TextView clear = chip("Clear");
        clear.setOnClickListener(v -> {
            noteChange();
            labSeq.clear();
            labCur = 0;
            labHold = 0;
            rebuildFilm();
            // No roll left, so the grid selection is the current frame again. Point everything
            // at it rather than leaving the preview on a frame that no longer exists anywhere.
            focusCell(Math.max(0, gridView.getSelectedCell()), true);
        });
        b.addView(clear);
        TextView saveClip = ichip("clips", "Save clip");
        tintToggle(saveClip, true, SpriteTheme.ACCENT_SEQ);
        saveClip.setOnClickListener(v -> saveLabClip());
        b.addView(saveClip);
        return g;
    }

    private void bumpHold(int delta) {
        if (labSeq.isEmpty()) return;
        int[] f = labSeq.get(Math.max(0, Math.min(labSeq.size() - 1, labCur)));
        f[1] = Math.max(1, Math.min(16, f[1] + delta));
        rebuildFilm();
        if (scrubBar != null) scrubBar.invalidate();
    }

    /** The roll becomes a named animation on the sheet — which is what the drawer reads back. */
    private void saveLabClip() {
        if (labSeq.size() < 1) {
            Toast.makeText(this, "Tap cells to build a sequence first", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText("clip" + (sheet.getPresets().size() + 1));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Name this animation")
                .setView(input)
                .setPositiveButton("Save", (dl, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "clip" + (sheet.getPresets().size() + 1);
                    SpriteSheet.Preset pr = new SpriteSheet.Preset(
                            java.util.UUID.randomUUID().toString(), name);
                    pr.type = "loop";
                    pr.fps = sheet.getFps();
                    for (int[] f : labSeq) {
                        pr.frames.add(f[0]);
                        pr.weights.add(Math.max(1, f[1]));
                    }
                    sheet.getPresets().add(pr);
                    markDirty();
                    Toast.makeText(this, "“" + pr.name + "” saved · "
                            + pr.frames.size() + " frames", Toast.LENGTH_SHORT).show();
                    showSection("clips");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── CLIPS ────────────────────────────────────────────────────────────
    /** The id of the clip whose frames the grid is lighting, or null. */
    @Nullable private String pickedClip;

    // ── rearranging the shelf ────────────────────────────────────────────

    @Nullable private FlowLayout clipShelf;
    private int clipDragFrom = -1;
    private int clipDropAt = -1;
    @Nullable private View clipDragView;
    private float clipDragStartX, clipDragStartY;

    private void beginClipDrag(int index, @NonNull View chip) {
        clipDragFrom = index;
        clipDropAt = index;
        clipDragView = chip;
        int[] at = new int[2];
        chip.getLocationOnScreen(at);
        clipDragStartX = at[0] + chip.getWidth() / 2f;
        clipDragStartY = at[1] + chip.getHeight() / 2f;
        chip.setScaleX(1.12f);
        chip.setScaleY(1.12f);
        chip.setElevation(12 * density());
        if (chip.getParent() != null) chip.getParent().requestDisallowInterceptTouchEvent(true);
        chip.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    private void moveClipDrag(float rawX, float rawY) {
        View chip = clipDragView;
        if (chip == null || clipShelf == null) return;
        chip.setTranslationX(rawX - clipDragStartX);
        chip.setTranslationY(rawY - clipDragStartY);
        clipDropAt = nearestClip(rawX, rawY);
    }

    /**
     * The chip whose centre is closest to the finger.
     *
     * <p>A wrapped shelf has no single line of gaps to fall between, so "nearest centre" is
     * both simpler than a 2D gap model and what the hand expects: you drop ON the one you want
     * to take the place of.</p>
     */
    private int nearestClip(float rawX, float rawY) {
        if (clipShelf == null) return clipDragFrom;
        int best = clipDragFrom;
        float bestD = Float.MAX_VALUE;
        int[] at = new int[2];
        for (int i = 0; i < clipShelf.getChildCount(); i++) {
            View c = clipShelf.getChildAt(i);
            c.getLocationOnScreen(at);
            float cx = at[0] + c.getWidth() / 2f, cy = at[1] + c.getHeight() / 2f;
            // The lifted chip has moved with the finger; judge it by where it STARTED.
            if (i == clipDragFrom) { cx = clipDragStartX; cy = clipDragStartY; }
            float dx = cx - rawX, dy = cy - rawY;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD) { bestD = d2; best = i; }
        }
        return best;
    }

    private void endClipDrag(boolean commit) {
        View chip = clipDragView;
        int from = clipDragFrom, to = clipDropAt;
        clipDragFrom = -1;
        clipDropAt = -1;
        clipDragView = null;
        if (chip != null) {
            chip.setScaleX(1f); chip.setScaleY(1f);
            chip.setTranslationX(0); chip.setTranslationY(0);
            chip.setElevation(0f);
        }
        java.util.List<SpriteSheet.Preset> all = sheet.getPresets();
        if (!commit || from < 0 || to < 0 || from == to
                || from >= all.size() || to >= all.size()) return;
        noteChange();
        all.add(to, all.remove(from));
        markDirty();
        showSection("clips");
    }

    private void buildClipsSection() {
        float d = density();
        if (sheet.getPresets().isEmpty()) {
            pickedClip = null;
            gridView.setRoll(labSeq);
            TextView empty = new TextView(this);
            empty.setText("No animations yet. Build a sequence in Play, then Save clip.");
            empty.setTextColor(SpriteTheme.DIMMER);
            empty.setTextSize(11.5f);
            benchBody.addView(empty);
            return;
        }

        TextView hint = new TextView(this);
        hint.setText("Tap a clip to see it on the sheet. Long-press to drag it somewhere else.");
        hint.setTextColor(SpriteTheme.DIMMER);
        hint.setTextSize(10f);
        benchBody.addView(hint);

        FlowLayout shelf = new FlowLayout(this);
        shelf.setPadding(0, (int) (5 * d), 0, (int) (5 * d));
        clipShelf = shelf;
        SpriteSheet.Preset picked = null;
        for (int i = 0; i < sheet.getPresets().size(); i++) {
            final SpriteSheet.Preset pr = sheet.getPresets().get(i);
            boolean sel = pr.id.equals(pickedClip);
            if (sel) picked = pr;
            final int at = i;
            View chip = spriteChip(0, pr, pr.name, pr.frames.size() + "f \u00b7 "
                    + (int) (pr.fps > 0 ? pr.fps : sheet.getFps()) + "fps", 0, false, sel);
            chip.setOnClickListener(v -> {
                pickedClip = pr.id.equals(pickedClip) ? null : pr.id;
                showSection("clips");
            });
            // Same gesture as the roll: long-press lifts, then drag to a new place. The shelf
            // WRAPS, so the drop target is the nearest chip centre rather than a gap on a line.
            chip.setOnLongClickListener(v -> { beginClipDrag(at, chip); return true; });
            chip.setOnTouchListener((v, e) -> {
                if (clipDragFrom != at) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        moveClipDrag(e.getRawX(), e.getRawY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        endClipDrag(true);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        endClipDrag(false);
                        return true;
                    default:
                        return false;
                }
            });
            shelf.addView(chip);
        }
        benchBody.addView(shelf);

        // A picked clip lights its own frames on the sheet, in its own order — which is the
        // fastest way to see what a clip actually is without loading it over your work.
        if (picked != null) {
            java.util.List<int[]> asRoll = new java.util.ArrayList<>();
            for (int k = 0; k < picked.frames.size(); k++) {
                asRoll.add(new int[]{picked.frames.get(k),
                        SequenceTiming.weightAt(picked.weights, k)});
            }
            gridView.setRoll(asRoll);

            final SpriteSheet.Preset pick = picked;
            FlowLayout acts = new FlowLayout(this);
            TextView load = ichip("layers", "Load into sequence");
            tintToggle(load, true, SpriteTheme.ACCENT_CLIPS);
            load.setOnClickListener(v -> {
                noteChange();
                labSeq.clear();
                labSeq.addAll(asRoll);
                labWrap = pick.type == null ? "loop" : pick.type;
                syncWrap();
                pickedClip = null;
                rebuildFilm();
                labSection = "play";
                focusRoll(0, true);
            });
            acts.addView(load);

            TextView edit = ichip("tag", "Rename / retime");
            edit.setOnClickListener(v -> editPreset(pick));
            acts.addView(edit);

            TextView del = ichip("x", "Delete");
            del.setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Delete \u201c" + pick.name + "\u201d?")
                    .setPositiveButton("Delete", (dl, w) -> {
                        sheet.getPresets().remove(pick);
                        pickedClip = null;
                        markDirty();
                        showSection("clips");
                    })
                    .setNegativeButton("Cancel", null).show());
            acts.addView(del);
            benchBody.addView(acts);
        } else {
            gridView.setRoll(labSeq);
        }
    }

    /** Name, cadence and wrap in ONE dialog — renaming a clip and retiming it are one thought. */
    private void editPreset(@NonNull SpriteSheet.Preset pr) {
        float d = density();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        box.setPadding(pad, pad, pad, 0);

        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(pr.name);
        box.addView(name);

        final TextView wrap = new TextView(this);
        wrap.setTextSize(13f);
        wrap.setPadding(0, pad / 2, 0, pad / 2);
        final String[] types = {"loop", "pingpong", "once"};
        final int[] idx = {java.util.Arrays.asList(types).indexOf(pr.type)};
        if (idx[0] < 0) idx[0] = 0;
        wrap.setText("Playback: " + types[idx[0]] + "  (tap to change)");
        wrap.setTextColor(SpriteTheme.SELECTED);
        wrap.setOnClickListener(v -> {
            idx[0] = (idx[0] + 1) % types.length;
            wrap.setText("Playback: " + types[idx[0]] + "  (tap to change)");
        });
        box.addView(wrap);

        final EditText fps = new EditText(this);
        fps.setSingleLine(true);
        fps.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        fps.setHint("fps");
        fps.setText(String.valueOf((int) (pr.fps > 0 ? pr.fps : sheet.getFps())));
        box.addView(fps);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Edit clip")
                .setView(box)
                .setPositiveButton("Save", (dl, w) -> {
                    String n = name.getText().toString().trim();
                    if (!n.isEmpty()) pr.name = n;
                    pr.type = types[idx[0]];
                    try {
                        float f = Float.parseFloat(fps.getText().toString().trim());
                        if (f > 0) pr.fps = Math.min(120f, f);
                    } catch (NumberFormatException ignored) { }
                    markDirty();
                    showSection("clips");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── EXPORT ───────────────────────────────────────────────────────────
    private void buildOutSection() {
        View g = group("exp", SpriteTheme.ACCENT_OUT, "out", "Export",
                "sheet + .sprite.json");
        FlowLayout b = bodyOf(g);
        TextView ex = ichip("out", "Write .sprite.json");
        tintToggle(ex, true, SpriteTheme.ACCENT_OUT);
        ex.setOnClickListener(v -> exportSidecar());
        b.addView(ex);
        TextView im = chip("Import .sprite.json");
        im.setOnClickListener(v -> sidecarImportLauncher.launch(new String[]{"application/json"}));
        b.addView(im);
        TextView relink = chip("Relink art");
        relink.setOnClickListener(v -> imagePicker.launch(new String[]{"image/*"}));
        b.addView(relink);
        benchBody.addView(g);

        TextView note = new TextView(this);
        note.setText("Alignment is stored with the sheet, so nothing has to be baked here \u2014 "
                + "a nudge you make now travels into every project that uses this sheet.");
        note.setTextColor(SpriteTheme.DIMMER);
        note.setTextSize(10.5f);
        benchBody.addView(note);

        buildBakeGroup();
        buildJsonGroup();
        buildSheetsGroup();
    }

    // ── the project's sheets ─────────────────────────────────────────────

    /** Which sheet the Sheets group has selected, or null for none. */
    @Nullable private String pickedSheet;
    /** The last sheet removed on this screen, kept so it can be put back. */
    @Nullable private SpriteSheet removedSheet;
    private int removedAt;

    /**
     * A row in the Sheets group: name, one line of truth, no picture.
     *
     * <p>Deliberately not {@link #spriteChip} — see the comment at its only call site.</p>
     */
    @NonNull
    private View sheetChip(@NonNull String name, @NonNull String sub, boolean open, boolean sel) {
        float d = density();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (7 * d);
        box.setPadding(pad, (int) (5 * d), pad, (int) (5 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(open ? SpriteTheme.LIVE : SpriteTheme.CONTROL);
        bg.setCornerRadius(9 * d);
        bg.setStroke((int) (2 * d), sel ? SpriteTheme.SELECTED : 0x00000000);
        box.setBackground(bg);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(11f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFFFFFFFF);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(t);

        TextView s2 = new TextView(this);
        s2.setText(sub);
        s2.setTextSize(8.5f);
        s2.setTextColor(open ? 0xFF3B0322 : SpriteTheme.DIMMER);
        s2.setMaxLines(1);
        box.addView(s2);
        return box;
    }

    /**
     * Every sprite sheet in this project, and what you can do to one.
     *
     * <p>Until now a sheet could be created — imported, baked, relinked — and never removed,
     * so a project accumulated them with no way to tidy up from inside the app. That is not a
     * tidiness nit: the only alternative was editing project.json by hand, which is exactly
     * the outside-the-app edit that has silently destroyed work in this repo before.</p>
     */
    private void buildSheetsGroup() {
        java.util.List<SpriteSheet> all = new java.util.ArrayList<>(project.getSpriteSheets());
        // A brand-new sheet is not in the project list until it is first saved, and leaving it
        // out made the panel claim "1 sheet" while you were editing a second one.
        if (project.spriteSheetById(sheet.getId()) == null) all.add(sheet);
        View g = group("sheets", SpriteTheme.ACCENT_CELL, "layers", "Sheets in this project",
                count(all.size(), "sheet"));
        FlowLayout b = bodyOf(g);

        for (SpriteSheet o : all) {
            final String id = o.getId();
            boolean isOpen = id.equals(sheet.getId());
            boolean sel = id.equals(pickedSheet);
            String sub = isOpen ? "open now"
                    : o.getSheetUri().isEmpty() ? "no art"
                    : o.cellCount() + " cells";
            if (!o.getBakedFrom().isEmpty() && !isOpen) {
                sub = "baked \u00b7 " + android.text.TextUtils.join(" + ", o.getBakedFrom());
            }
            // A sheet whose art has gone is the one you most want to find here, to relink it
            // or to remove it, and it is invisible otherwise: a sheet is just a row of numbers
            // until something tries to draw it.
            if (artIsMissing(o)) sub = "\u26a0 art is missing";
            // NO thumbnail. spriteChip draws through THIS activity's renderer, so every row
            // would show cell 0 of the sheet you already have open — you would be choosing
            // which sheet to delete from a list where all the pictures are the same and none
            // of them is the sheet in question. A name and a sub-line that are true beat a
            // picture that is false.
            View chip = sheetChip(o.getName(), sub, isOpen, sel);
            chip.setOnClickListener(v -> {
                pickedSheet = id.equals(pickedSheet) ? null : id;
                showSection("out");
            });
            b.addView(chip);
        }

        if (removedSheet != null) {
            TextView undo = ichip("undo", "Put \u201c" + removedSheet.getName() + "\u201d back");
            tintToggle(undo, true, SpriteTheme.WARN);
            undo.setOnClickListener(v -> {
                java.util.List<SpriteSheet> list = project.getSpriteSheets();
                int at = Math.max(0, Math.min(list.size(), removedAt));
                list.add(at, removedSheet);
                removedSheet = null;
                markDirty();
                save(true);
                showSection("out");
            });
            b.addView(undo);
        }

        final SpriteSheet picked = pickedSheet == null ? null
                : project.spriteSheetById(pickedSheet);
        if (picked == null) {
            TextView hint = new TextView(this);
            hint.setText("Tap a sheet to open, rename or remove it.");
            hint.setTextColor(SpriteTheme.DIMMER);
            hint.setTextSize(10f);
            b.addView(hint);
            benchBody.addView(g);
            return;
        }

        boolean isOpen = picked.getId().equals(sheet.getId());
        if (!isOpen) {
            TextView open = ichip("grid", "Open");
            tintToggle(open, true, SpriteTheme.ACCENT_CELL);
            open.setOnClickListener(v -> openSheet(picked.getId()));
            b.addView(open);
        }

        TextView rename = ichip("tag", "Rename");
        rename.setOnClickListener(v -> renameSheet(picked));
        b.addView(rename);

        String why = sheetInUseReason(picked.getId());
        TextView del = ichip("trash", isOpen ? "Cannot remove the open sheet"
                : why != null ? "In use by " + why : "Remove");
        if (isOpen || why != null) {
            // Present but plainly refusing, with the reason ON the button. A control that is
            // simply missing makes you wonder whether you looked in the wrong place.
            final String reason = why;
            del.setOnClickListener(v -> Toast.makeText(this, isOpen
                    ? "Open a different sheet first, then remove this one."
                    : "Used by " + reason + ". Remove those first \u2014 taking the sheet away "
                      + "would leave them with nothing to draw.", Toast.LENGTH_LONG).show());
        } else {
            tintToggle(del, true, SpriteTheme.LIVE);
            del.setOnClickListener(v -> confirmRemoveSheet(picked));
        }
        b.addView(del);

        benchBody.addView(g);
    }

    /**
     * Has this sheet's image gone?
     *
     * <p>Only ever answers TRUE when it is certain: a path it can resolve and check that is not
     * there. Anything it cannot resolve (a content:// grant, a scheme it does not know) is
     * reported as fine, because a false "missing" on good art would send someone relinking
     * something that never broke.</p>
     */
    private boolean artIsMissing(@NonNull SpriteSheet sh) {
        String uri = sh.getSheetUri();
        if (uri.isEmpty()) return false;              // "no art" is a different statement
        try {
            File dir = storage.projectDir(project.getId());
            android.net.Uri resolved =
                    com.fadcam.ui.faditor.project.AssetResolver.resolve(dir, uri);
            if (resolved == null || !"file".equals(resolved.getScheme())) return false;
            String path = resolved.getPath();
            return path != null && !new File(path).exists();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Why this sheet cannot be removed, or null when nothing depends on it.
     *
     * <p><b>There are TWO stores of sheet ids, not one.</b> Sprites on the timeline carry one;
     * every PART of an avatar rig carries another, and the two never overlap — placing an
     * avatar copies each part's sheet into the project and places a single sprite pointing at
     * the neutral bake, so a part sheet has exactly zero timeline references. Counting only
     * sprites armed Remove on precisely the sheets a puppet is built from, and removing one
     * takes that body part out of the export with no error anywhere.</p>
     */
    @Nullable
    private String sheetInUseReason(@NonNull String sheetId) {
        int sprites = 0, parts = 0;
        java.util.Set<String> rigs = new java.util.LinkedHashSet<>();
        try {
            for (SpriteOverlayItem o : project.getTimeline().getSpriteOverlays()) {
                if (sheetId.equals(o.getSheetId())) sprites++;
            }
        } catch (RuntimeException ignored) {
            // A project with no timeline is not a reason to refuse to draw the panel.
        }
        try {
            for (com.fadcam.ui.faditor.avatar.AvatarRig r : project.getAvatarRigs()) {
                for (com.fadcam.ui.faditor.avatar.AvatarRig.Part p : r.getParts()) {
                    if (sheetId.equals(p.sheetId)) { parts++; rigs.add(r.getName()); }
                }
            }
        } catch (RuntimeException ignored) {
            // Same: an older project with no rigs still gets a working panel.
        }
        if (parts > 0) {
            return count(parts, "part") + " of "
                    + (rigs.size() == 1 ? "\u201c" + rigs.iterator().next() + "\u201d"
                                        : count(rigs.size(), "rig"));
        }
        if (sprites > 0) return count(sprites, "sprite") + " on the timeline";
        return null;
    }

    private void renameSheet(@NonNull SpriteSheet target) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(target.getName());
        in.setSelectAllOnFocus(true);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Rename sheet")
                .setView(in)
                .setPositiveButton("Rename", (dl, w) -> {
                    String n = in.getText().toString().trim();
                    if (n.isEmpty()) return;
                    target.setName(n);
                    // The open sheet's name also lives in the top bar's field, which is what
                    // save() reads back — miss this and the rename is undone on the next save.
                    if (target.getId().equals(sheet.getId())) nameField.setText(n);
                    markDirty();
                    showSection("out");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemoveSheet(@NonNull SpriteSheet target) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Remove \u201c" + target.getName() + "\u201d?")
                .setMessage("It leaves this project's sheet list. The image file stays on disk, "
                        + "but the slicing, the names, the alignment and the animations on it "
                        + "go with it.\n\nYou can put it back while you are still on this "
                        + "screen. After that, only by re-importing and doing the work again.")
                .setPositiveButton("Remove", (dl, w) -> {
                    // NOT noteChange(). The undo snapshot holds the open sheet only, so an
                    // undo step here would light the button and then do nothing about the
                    // thing you actually asked to undo. Recovery is the chip below instead,
                    // which is visible and honest about how long it lasts.
                    java.util.List<SpriteSheet> all = project.getSpriteSheets();
                    removedAt = all.indexOf(target);
                    removedSheet = target;
                    all.remove(target);
                    pickedSheet = null;
                    markDirty();
                    save(true);
                    showSection("out");
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    // ── bake ─────────────────────────────────────────────────────────────

    private int bakeCols = 8;
    private int bakePad = 2;
    private boolean bakeContentFit = true;
    private boolean bakeJpeg = false;
    /**
     * Sheets ADDED to the bake, beyond the one you are looking at.
     *
     * <p>Opt in, not opt out. The first cut excluded-by-exception, and in a four-sheet project
     * pressing Bake without touching anything merged all four — including, if one happened to
     * be an imported image sequence, several hundred frames. "Bake" must mean "bake this",
     * and merging must be something you asked for.</p>
     */
    private final java.util.Set<String> bakeInclude = new java.util.HashSet<>();

    /** The sheet you are looking at, plus whatever you added to it. */
    @NonNull
    private java.util.List<SpriteSheet> bakeSources() {
        java.util.List<SpriteSheet> out = new java.util.ArrayList<>();
        out.add(sheet);                                   // the open one always leads
        for (SpriteSheet o : project.getSpriteSheets()) {
            if (o.getId().equals(sheet.getId())) continue;
            if (o.getSheetUri().isEmpty()) continue;
            if (bakeInclude.contains(o.getId())) out.add(o);
        }
        return out;
    }

    /**
     * Flatten to a new sheet.
     *
     * <p>Alignment already travels as data, so this is NOT how you move work to the phone. It
     * is for the two things data cannot do: hand a finished sheet to something that is not Joy
     * Creator, and MERGE several sheets into one, which a single image URI cannot express.</p>
     */
    private void buildBakeGroup() {
        float d = density();
        java.util.List<SpriteSheet> srcs = bakeSources();
        int totalFrames = 0;
        for (SpriteSheet o : srcs) totalFrames += SpriteBaker.framesToBake(o).size();

        View g = group("bake", SpriteTheme.ACCENT_OUT, "grid", "Bake a new sheet",
                count(totalFrames, "frame") + (srcs.size() > 1
                        ? " \u00b7 " + count(srcs.size(), "sheet") : ""));
        FlowLayout b = bodyOf(g);

        // The rail: every sheet with art, lit when it is going into the bake. On the desktop
        // this is a load/unload/restore affair because loading costs a file picker; here the
        // sheets are already in the project, so include-or-not is the whole of it.
        java.util.List<SpriteSheet> others = new java.util.ArrayList<>();
        for (SpriteSheet o : project.getSpriteSheets()) {
            if (o.getId().equals(sheet.getId())) continue;
            if (!o.getSheetUri().isEmpty()) others.add(o);
        }
        if (!others.isEmpty()) {
            TextView lbl = new TextView(this);
            lbl.setText("merge in");
            lbl.setTextSize(9.5f);
            lbl.setTextColor(SpriteTheme.DIMMER);
            b.addView(lbl);
            for (SpriteSheet o : others) {
                final String id = o.getId();
                TextView c = chip(o.getName() + "  "
                        + SpriteBaker.framesToBake(o).size() + "f");
                tintToggle(c, bakeInclude.contains(id), SpriteTheme.ACCENT_CELL);
                c.setOnClickListener(v -> {
                    if (!bakeInclude.remove(id)) bakeInclude.add(id);
                    showSection("out");
                });
                b.addView(c);
            }
        }

        b.addView(num("cols", null, () -> bakeCols,
                v -> bakeCols = Math.max(1, Math.min(32, Math.round(v))), 1f, true, ""));
        b.addView(num("pad", null, () -> bakePad,
                v -> bakePad = Math.max(0, Math.min(64, Math.round(v))), 1f, true, "px"));

        LinearLayout fitSeg = seg();
        TextView fitArt = segText("Fit to art");
        TextView fitCell = segText("Keep cell size");
        Runnable syncFit = () -> {
            tintSeg(fitArt, bakeContentFit, SpriteTheme.ACCENT_OUT);
            tintSeg(fitCell, !bakeContentFit, SpriteTheme.ACCENT_OUT);
        };
        fitArt.setOnClickListener(v -> { bakeContentFit = true; syncFit.run(); });
        fitCell.setOnClickListener(v -> { bakeContentFit = false; syncFit.run(); });
        fitSeg.addView(fitArt); fitSeg.addView(fitCell);
        space(fitSeg, d, 1);
        syncFit.run();
        b.addView(fitSeg);

        LinearLayout fmtSeg = seg();
        TextView png = segText("PNG");
        TextView jpg = segText("JPG");
        Runnable syncFmt = () -> {
            tintSeg(png, !bakeJpeg, SpriteTheme.ACCENT_OUT);
            tintSeg(jpg, bakeJpeg, SpriteTheme.ACCENT_OUT);
        };
        png.setOnClickListener(v -> { bakeJpeg = false; syncFmt.run(); });
        jpg.setOnClickListener(v -> {
            bakeJpeg = true;
            syncFmt.run();
            Toast.makeText(this, "JPG has no transparency \u2014 the sheet bakes onto white",
                    Toast.LENGTH_LONG).show();
        });
        fmtSeg.addView(png); fmtSeg.addView(jpg);
        space(fmtSeg, d, 1);
        syncFmt.run();
        b.addView(fmtSeg);

        TextView go = ichip("out", "Bake sheet + JSON");
        tintToggle(go, true, SpriteTheme.ACCENT_OUT);
        go.setOnClickListener(v -> doBake(false));
        b.addView(go);

        TextView frames2 = ichip("film", "Frames");
        frames2.setOnClickListener(v -> doBake(true));
        b.addView(frames2);

        benchBody.addView(g);
    }

    /** A plain text button for inside a {@link #seg}. */
    @NonNull
    private TextView segText(@NonNull String label) {
        float d = density();
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(11f);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight((int) (24 * d));
        t.setPadding((int) (9 * d), (int) (2 * d), (int) (9 * d), (int) (2 * d));
        return t;
    }

    /**
     * Do the bake, on a background thread because it decodes every merged sheet and touches
     * every pixel twice.
     *
     * @param framesOnly write one numbered PNG per frame instead of one sheet.
     */
    private void doBake(boolean framesOnly) {
        if (renderer == null) {
            Toast.makeText(this, "No art loaded", Toast.LENGTH_SHORT).show();
            return;
        }
        final SpriteBaker.Options opt = new SpriteBaker.Options();
        opt.cols = bakeCols;
        opt.pad = bakePad;
        opt.fit = bakeContentFit ? SpriteBaker.Fit.CONTENT : SpriteBaker.Fit.CELL;
        opt.jpeg = bakeJpeg && !framesOnly;   // numbered frames are always PNG

        final String base = safeBase(sheet.getName());

        // Snapshot on the MAIN thread. framesToBake walks the live preset list and
        // spriteSheetById walks the live sheet list; doing either from the worker races every
        // edit and every autosave, and a ConcurrentModificationException there is an uncaught
        // exception on a non-UI thread, which is process death rather than a message.
        final java.util.List<SpriteSheet> sheets = new java.util.ArrayList<>(bakeSources());
        final java.util.List<java.util.List<Integer>> cellLists = new java.util.ArrayList<>();
        for (SpriteSheet o : sheets) {
            cellLists.add(new java.util.ArrayList<>(SpriteBaker.framesToBake(o)));
        }
        final File assets = new File(storage.projectDir(project.getId()), "assets");

        // A modal while it runs. It is a second or two of work on every pixel twice, and the
        // alternative is letting the sheet be edited underneath the thread reading it.
        final android.app.Dialog busy = busyDialog(framesOnly ? "Writing frames\u2026" : "Baking\u2026");
        busy.show();

        new Thread(() -> {
            java.util.List<SpriteBaker.Source> sources = new java.util.ArrayList<>();
            java.util.List<SpriteSheetRenderer> opened = new java.util.ArrayList<>();
            String message;
            SpriteBaker.Result result = null;
            File written = null;
            try {
                sources.add(new SpriteBaker.Source(sheets.get(0), renderer, cellLists.get(0)));
                for (int i = 1; i < sheets.size(); i++) {
                    SpriteSheetRenderer r = SpriteSheetRenderer.load(this, sheets.get(i));
                    if (r == null) continue;
                    opened.add(r);
                    sources.add(new SpriteBaker.Source(sheets.get(i), r, cellLists.get(i)));
                }
                if (!assets.exists() && !assets.mkdirs()) {
                    message = "Could not create the project's assets folder";
                } else if (framesOnly) {
                    File dir = new File(assets, base + "_frames");
                    if (!dir.exists() && !dir.mkdirs()) {
                        message = "Could not create " + dir.getName();
                    } else {
                        int n = SpriteBaker.writeFrames(sources, opt, dir, base);
                        message = n == 0 ? "Nothing to write"
                                : n + " frames written to " + dir.getName();
                    }
                } else {
                    result = SpriteBaker.bake(sources, opt);
                    if (result == null) {
                        message = "Nothing to bake";
                    } else {
                        File img = new File(assets, base + "-baked-" + System.currentTimeMillis()
                                + (opt.jpeg ? ".jpg" : ".png"));
                        written = SpriteBaker.write(result.bitmap, img, opt.jpeg, opt.jpegQuality);
                        message = written == null ? "Could not write the image"
                                : result.cols + "\u00d7" + result.rows + " \u00b7 "
                                  + result.bitmap.getWidth() + "\u00d7"
                                  + result.bitmap.getHeight() + " px";
                    }
                }
            } catch (Throwable t) {
                message = "Bake failed: " + t.getClass().getSimpleName();
            }
            for (SpriteSheetRenderer r : opened) r.recycle();

            final String msg = message;
            final SpriteBaker.Result baked = result;
            final File file = written;
            runOnUiThread(() -> {
                // The Activity can be gone: Back during a bake finishes it, and a dialog or a
                // save from here would either crash on a dead window or write this Activity's
                // stale project over whatever the editor has since saved.
                if (isFinishing() || isDestroyed()) {
                    if (baked != null) baked.bitmap.recycle();
                    return;
                }
                try { busy.dismiss(); } catch (RuntimeException ignored) { }
                if (baked == null || file == null) {
                    if (baked != null) baked.bitmap.recycle();
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    return;
                }
                // The model is only ever touched here, on the main thread.
                SpriteSheet made = SpriteBaker.describe(sources, baked,
                        sheet.getName() + " baked", android.net.Uri.fromFile(file).toString());
                int wanted = 0;
                for (SpriteBaker.Source src : sources) wanted += src.sheet.getPresets().size();
                int lost = wanted - made.getPresets().size();
                baked.bitmap.recycle();
                project.getSpriteSheets().add(made);
                labDirty = true;
                save(true);
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Baked")
                        .setMessage(msg + " \u00b7 "
                                + count(made.getPresets().size(), "animation")
                                + (lost > 0 ? "\n\n" + count(lost, "animation")
                                    + " could not be carried across: they refer to frames this "
                                    + "bake does not contain." : "")
                                + "\n\nThe original is untouched \u2014 this is a new sheet in "
                                + "the same project.")
                        .setPositiveButton("Open it", (dl, w) -> openSheet(made.getId()))
                        .setNegativeButton("Stay here", null)
                        .show();
            });
        }, "sprite-bake").start();
    }

    /** A modal that says what is happening and cannot be dismissed by accident. */
    @NonNull
    private android.app.Dialog busyDialog(@NonNull String text) {
        float d = density();
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14f);
        t.setTextColor(SpriteTheme.INK);
        int pad = (int) (26 * d);
        t.setPadding(pad, pad, pad, pad);
        android.app.Dialog dl = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setView(t)
                .setCancelable(false)
                .create();
        return dl;
    }

    /** Letters, digits and dashes only: this becomes a file name. */
    @NonNull
    private static String safeBase(@NonNull String name) {
        String s2 = name.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        if (s2.startsWith("-")) s2 = s2.substring(1);
        return s2.isEmpty() ? "sprite" : s2;
    }

    private void openSheet(@NonNull String sheetId) {
        save(true);
        android.content.Intent i = new android.content.Intent(this, SpriteSheetEditorActivity.class);
        i.putExtra(EXTRA_PROJECT_ID, project.getId());
        i.putExtra(EXTRA_SHEET_ID, sheetId);
        startActivity(i);
        finish();
    }

    /**
     * What Joy Creator's assistant actually reads.
     *
     * <p>Naming a cell "surprised" is not a labelling nicety: it is the sentence the AI gets
     * to use. Showing the JSON here is how the person doing the naming can SEE that the
     * names, the visemes and the clips made it into the contract.</p>
     */
    private void buildJsonGroup() {
        float d = density();
        int named = sheet.getCellNames().size();
        View g = group("json", SpriteTheme.ACCENT_GRID, "braces", "JSON",
                count(named, "named cell") + " \u00b7 "
                        + count(sheet.getVisemeMap().size(), "viseme") + " \u00b7 "
                        + count(sheet.getPresets().size(), "animation"));
        FlowLayout b = bodyOf(g);

        final String json = prettyJson();
        TextView box = new TextView(this);
        box.setText(json);
        box.setTextSize(9.5f);
        box.setTypeface(Typeface.MONOSPACE);
        box.setTextColor(SpriteTheme.DIM);
        // A card, not a pill: the design rounds a block of text by RADIUS_CARD, and a
        // fully-rounded pill eats the first and last character of every line.
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xFF0B0B0E);
        panel.setCornerRadius(SpriteTheme.RADIUS_CARD * d);
        panel.setStroke(Math.max(1, (int) d), SpriteTheme.LINE);
        box.setBackground(panel);
        int p = (int) (8 * d);
        box.setPadding(p, p, p, p);
        box.setMaxLines(14);
        box.setVerticalScrollBarEnabled(true);
        box.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        b.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (150 * d)));

        TextView copy = ichip("braces", "Copy");
        tintToggle(copy, true, SpriteTheme.ACCENT_GRID);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cb =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(android.content.ClipData.newPlainText("sprite.json", json));
                Toast.makeText(this, "JSON copied", Toast.LENGTH_SHORT).show();
            }
        });
        b.addView(copy);

        benchBody.addView(g);

        if (named == 0) {
            TextView nudge = new TextView(this);
            nudge.setText("No cell is named yet. A named cell is something the assistant can "
                    + "ask for by name \u2014 \u201cmake him look surprised\u201d instead of "
                    + "\u201cuse frame 3\u201d. Name them in Slice.");
            nudge.setTextColor(SpriteTheme.DIMMER);
            nudge.setTextSize(10.5f);
            benchBody.addView(nudge);
        }
    }

    /** "1 animation", not "1 animations". */
    @NonNull
    private static String count(int n, @NonNull String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** Gson writes one long line; a human reading it on a phone needs the newlines. */
    @NonNull
    private String prettyJson() {
        try {
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(sheet.toJson());
        } catch (RuntimeException e) {
            return sheet.toJson().toString();
        }
    }

    // ── the roll ─────────────────────────────────────────────────────────
    private void rebuildFilm() {
        if (filmRow == null) return;
        float d = density();
        gridView.setRoll(labSeq);
        filmRow.removeAllViews();
        filmBoxes.clear();
        if (labSeq.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Tap cells on the sheet to build a sequence");
            hint.setTextColor(SpriteTheme.DIMMER);
            hint.setTextSize(10.5f);
            filmRow.addView(hint);
            if (scrubBar != null) scrubBar.invalidate();
            return;
        }
        FilmStrip strip = new FilmStrip(this);
        for (int i = 0; i < labSeq.size(); i++) {
            final int idx = i;
            int[] f = labSeq.get(i);
            View chip = spriteChip(f[0], null, cellLabel(f[0]),
                    "#" + (i + 1) + " \u00b7 c" + f[0], f[1], i == labCur, false);
            chip.setOnClickListener(v -> focusRoll(idx, true));
            chip.setOnLongClickListener(v -> { beginRollDrag(idx, chip); return true; });
            chip.setOnTouchListener((v, e) -> {
                if (rollDragFrom != idx) return false;   // not lifted: tap and long-press as usual
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        moveRollDrag(e.getRawX(), e.getRawY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        endRollDrag(true);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        endRollDrag(false);
                        return true;
                    default:
                        return false;
                }
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.rightMargin = (int) (4 * d);
            strip.frames().addView(chip, bl);
            filmBoxes.add(chip);
        }
        filmRow.addView(strip);
        filmFrames = strip.frames();
        if (scrubBar != null) scrubBar.invalidate();
    }

    // ── rearranging the roll by hand ─────────────────────────────────────
    //
    // Long-press LIFTS; then sideways reorders and up-and-out removes. Starting the drag on
    // movement alone would have taken horizontal scrolling away from a long roll, which is the
    // only way to reach the far end of one.

    @Nullable private FilmStrip.DropRow filmFrames;
    private int rollDragFrom = -1;
    private int rollDropAt = -1;
    @Nullable private View rollDragView;
    private float rollDragStartX, rollDragStartY;
    private boolean rollWillRemove;

    private void beginRollDrag(int index, @NonNull View chip) {
        rollDragFrom = index;
        rollDropAt = index;
        rollDragView = chip;
        rollWillRemove = false;
        int[] at = new int[2];
        chip.getLocationOnScreen(at);
        rollDragStartX = at[0] + chip.getWidth() / 2f;
        rollDragStartY = at[1] + chip.getHeight() / 2f;
        chip.setScaleX(1.12f);
        chip.setScaleY(1.12f);
        chip.setElevation(12 * density());
        if (chip.getParent() != null) chip.getParent().requestDisallowInterceptTouchEvent(true);
        if (filmFrames != null) filmFrames.setDropAt(index);
        chip.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        if (rollHint != null) rollHint.setVisibility(View.VISIBLE);
        if (filmScroll != null) filmScroll.requestDisallowInterceptTouchEvent(true);
    }

    private void moveRollDrag(float rawX, float rawY) {
        View chip = rollDragView;
        if (chip == null || filmFrames == null) return;
        chip.setTranslationX(rawX - rollDragStartX);
        chip.setTranslationY(rawY - rollDragStartY);

        // Far enough above the strip and it is a removal, not a move.
        rollWillRemove = rawY < rollDragStartY - chip.getHeight();
        chip.setAlpha(rollWillRemove ? 0.55f : 1f);
        if (rollWillRemove) {
            rollDropAt = -1;
            filmFrames.setDropAt(-1);
        } else {
            // Keep it, do not merely draw it. Passing the gap straight to the view and never
            // storing it made the drop line perfect and the reorder a no-op every time.
            rollDropAt = dropIndexFor(rawX);
            filmFrames.setDropAt(rollDropAt);
        }
        autoScrollFilm(rawX);
        if (rollHint != null) {
            rollHint.setText(rollWillRemove ? "Release to remove this frame"
                    : "Drag sideways to reorder \u00b7 up to remove");
            tintToggle(rollHint, rollWillRemove, SpriteTheme.LIVE);
        }
    }

    /**
     * Nudge the strip when the finger reaches its edge.
     *
     * <p>Without this, a roll longer than the screen cannot have a frame moved past the visible
     * window at all — you would have to drop it, scroll, and pick it up again.</p>
     */
    private void autoScrollFilm(float rawX) {
        if (filmScroll == null) return;
        int[] at = new int[2];
        filmScroll.getLocationOnScreen(at);
        float edge = 44 * density();
        float left = at[0], right = at[0] + filmScroll.getWidth();
        if (rawX < left + edge) filmScroll.scrollBy((int) (-12 * density()), 0);
        else if (rawX > right - edge) filmScroll.scrollBy((int) (12 * density()), 0);
    }

    /** Which gap a finger at {@code rawX} is over: 0..size, where size means "past the end". */
    private int dropIndexFor(float rawX) {
        if (filmFrames == null) return rollDragFrom;
        int[] at = new int[2];
        for (int i = 0; i < filmFrames.getChildCount(); i++) {
            View c = filmFrames.getChildAt(i);
            c.getLocationOnScreen(at);
            if (rawX < at[0] + c.getWidth() / 2f) return i;
        }
        return filmFrames.getChildCount();
    }

    private void endRollDrag(boolean commit) {
        View chip = rollDragView;
        int from = rollDragFrom;
        boolean remove = rollWillRemove;
        int to = rollDropAt;
        rollDragFrom = -1;
        rollDropAt = -1;
        rollDragView = null;
        rollWillRemove = false;
        if (filmFrames != null) filmFrames.setDropAt(-1);
        if (rollHint != null) rollHint.setVisibility(View.INVISIBLE);
        if (chip != null) {
            chip.setScaleX(1f); chip.setScaleY(1f);
            chip.setTranslationX(0); chip.setTranslationY(0);
            chip.setAlpha(1f);
            chip.setElevation(0f);
        }
        if (!commit || from < 0 || from >= labSeq.size()) return;
        if (!remove && to < 0) return;

        if (remove) {
            noteChange();
            labSeq.remove(from);
            if (labCur >= labSeq.size()) labCur = Math.max(0, labSeq.size() - 1);
        } else {
            // A gap index past the removal point shifts down by one once the frame is out.
            int dest = to > from ? to - 1 : to;
            if (dest < 0 || dest == from) return;
            noteChange();
            int[] moved = labSeq.remove(from);
            labSeq.add(Math.min(dest, labSeq.size()), moved);
            labCur = Math.min(dest, labSeq.size() - 1);
        }
        rebuildFilm();
        focusRoll(labCur, "play".equals(labSection));
    }

    /**
     * THE chip. One design for a still and for a saved animation; the only difference is a
     * mode dot in the corner, which is exactly how the web design draws it.
     *
     * <p>Two chip designs is how you end up explaining to someone that the square ones are
     * frames and the other square ones are clips.</p>
     */
    @NonNull
    private View spriteChip(int cell, @Nullable SpriteSheet.Preset preset,
                            @Nullable String label, @Nullable String sub,
                            int hold, boolean now, boolean sel) {
        float d = density();
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (2 * d);
        body.setPadding(pad, pad, pad, pad);

        PresetThumb th = new PresetThumb(this, renderer, preset);
        if (preset == null) th.setStill(cell);
        body.addView(th, new LinearLayout.LayoutParams((int) (44 * d), (int) (32 * d)));

        TextView lb = new TextView(this);
        lb.setText(label == null ? "" : label);
        lb.setTextSize(8f);
        lb.setTextColor(0xFFFFFFFF);
        lb.setTypeface(Typeface.DEFAULT_BOLD);
        lb.setMaxLines(1);
        lb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lb.setGravity(Gravity.CENTER);
        body.addView(lb);

        TextView sb = new TextView(this);
        sb.setText(sub == null ? "" : sub);
        sb.setTextSize(7f);
        sb.setMaxLines(1);
        sb.setGravity(Gravity.CENTER);
        body.addView(sb);

        android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(
                (int) (52 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.addView(body, bl);

        if (hold > 1) {
            TextView hd = new TextView(this);
            hd.setText("x" + hold);
            hd.setTextSize(7.5f);
            hd.setTypeface(Typeface.DEFAULT_BOLD);
            hd.setTextColor(SpriteTheme.ON_ACCENT);
            android.graphics.drawable.GradientDrawable hb = new GradientDrawable();
            hb.setColor(SpriteTheme.ACCENT_GRID);
            hb.setCornerRadius(3 * d);
            hd.setBackground(hb);
            hd.setPadding((int) (3 * d), 0, (int) (3 * d), 0);
            android.widget.FrameLayout.LayoutParams hl = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hl.gravity = Gravity.START | Gravity.TOP;
            wrap.addView(hd, hl);
        }

        ChipTint tint = new ChipTint(body, sb, d);
        tint.set(now, sel);
        wrap.setTag(tint);
        return wrap;
    }

    /** Repaint a chip's state without rebuilding it — playback touches this every frame. */
    private static class ChipTint {
        private final View body;
        private final TextView sub;
        private final float d;
        ChipTint(@NonNull View body, @NonNull TextView sub, float d) {
            this.body = body; this.sub = sub; this.d = d;
        }
        void set(boolean now, boolean sel) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(now ? SpriteTheme.LIVE : SpriteTheme.CONTROL);
            bg.setCornerRadius(9 * d);
            bg.setStroke((int) (2 * d), sel ? SpriteTheme.SELECTED : 0x00000000);
            body.setBackground(bg);
            sub.setTextColor(now ? 0xFF3B0322 : SpriteTheme.DIMMER);
        }
    }

    /** The name to put under a cell's chip: what it is called, or what number it is. */
    @NonNull
    private String cellLabel(int cell) {
        String nm = sheet.cellName(cell);   // reconciles both name stores for us
        return nm == null || nm.isEmpty() ? ("cell " + cell) : nm;
    }

    private interface FloatGet { float get(); }
    private interface FloatSet { void set(float v); }

    /**
     * Measure each cell's real ink and move it so that blob lands on the pivot — or, for
     * {@code feet}, so its BOTTOM edge lines up with the others.
     *
     * <p>Centre-aligning a walk makes the character skate, because its centre of mass rises
     * and falls and the feet follow it. That is why both exist.</p>
     */
    private void autoCentre(boolean feet) {
        if (renderer == null) { Toast.makeText(this, "No art loaded", Toast.LENGTH_SHORT).show(); return; }
        android.graphics.Bitmap bmp = renderer.getBitmap();
        if (bmp == null || bmp.isRecycled()) return;
        float toSource = renderer.sourceWidth() / (float) Math.max(1, bmp.getWidth());

        java.util.List<Integer> cells = new java.util.ArrayList<>();
        if (!labSeq.isEmpty()) { for (int[] f : labSeq) if (!cells.contains(f[0])) cells.add(f[0]); }
        else for (int i = 0; i < sheet.cellCount(); i++) cells.add(i);

        // Pass one: measure. Pass two: move. Averaging first means the set settles toward
        // itself rather than every frame chasing the pivot independently.
        java.util.Map<Integer, float[]> boxes = new java.util.LinkedHashMap<>();
        float sumBottom = 0f; int n = 0;
        for (int c : cells) {
            float[] box = inkBox(bmp, c);
            if (box == null) continue;
            boxes.put(c, box);
            sumBottom += box[3];
            n++;
        }
        if (n == 0) { Toast.makeText(this, "Nothing to centre", Toast.LENGTH_SHORT).show(); return; }
        float avgBottom = sumBottom / n;

        for (java.util.Map.Entry<Integer, float[]> e : boxes.entrySet()) {
            int c = e.getKey();
            float[] box = e.getValue();   // l, t, r, b in CELL-local bitmap px
            android.graphics.Rect cr = renderer.cellRectBitmap(c);
            float cw = Math.max(1, cr.width()), ch = Math.max(1, cr.height());
            float targetX = sheet.getPivotX() * cw;
            float cx = (box[0] + box[2]) * 0.5f;
            SpriteSheet.CellXf t = sheet.cellTransform(c);
            SpriteSheet.CellXf xf = t == null ? new SpriteSheet.CellXf() : t.copy();
            xf.dx += (targetX - cx) * toSource;
            xf.dy += (feet ? (avgBottom - box[3]) : (sheet.getPivotY() * ch - (box[1] + box[3]) * 0.5f))
                    * toSource;
            sheet.setCellTransform(c, xf);
        }
        markDirty();
        refreshArt();
        Toast.makeText(this, feet ? "Bottom edges aligned" : "Centred " + n + " cells",
                Toast.LENGTH_SHORT).show();
    }

    /** {left, top, right, bottom} of a cell's non-transparent pixels, or null when empty. */
    @Nullable
    private float[] inkBox(@NonNull android.graphics.Bitmap bmp, int cell) {
        android.graphics.Rect r = renderer.cellRectBitmap(cell);
        int x0 = Math.max(0, r.left), y0 = Math.max(0, r.top);
        int x1 = Math.min(bmp.getWidth(), r.right), y1 = Math.min(bmp.getHeight(), r.bottom);
        int w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0) return null;
        int[] px = new int[w];
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            bmp.getPixels(px, 0, w, x0, y0 + y, w, 1);
            for (int x = 0; x < w; x++) {
                if (((px[x] >>> 24) & 0xFF) <= 16) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) return null;
        return new float[]{minX, minY, maxX + 1, maxY + 1};
    }

    // ── small views ──────────────────────────────────────────────────────

    /**
     * The bench's top edge. A bare line reads as decoration, so it wears the design's grab
     * pill: a thing you can obviously take hold of.
     */
    private static class Divider extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF grip = new RectF();
        private final float d;
        Divider(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }
        @Override protected void onDraw(Canvas canvas) {
            p.setColor(SpriteTheme.LINE);
            canvas.drawRect(0, getHeight() / 2f - 0.5f * d, getWidth(), getHeight() / 2f + 0.5f * d, p);
            float w = 48 * d, h = Math.min(getHeight(), (int) (7 * d));
            grip.set((getWidth() - w) / 2f, (getHeight() - h) / 2f,
                    (getWidth() + w) / 2f, (getHeight() + h) / 2f);
            p.setColor(SpriteTheme.CONTROL_HI);
            canvas.drawRoundRect(grip, h / 2f, h / 2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(SpriteTheme.LINE);
            canvas.drawRoundRect(grip, h / 2f, h / 2f, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    /** A row that wraps. Chips fill the width beside the preview, then carry on beneath it. */
    private static class FlowLayout extends ViewGroup {
        private final int gap;
        FlowLayout(Context c) {
            super(c);
            gap = (int) (4 * c.getResources().getDisplayMetrics().density);
        }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int width = MeasureSpec.getSize(wSpec);
            int x = getPaddingLeft(), y = getPaddingTop(), rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                measureChild(ch, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), hSpec);
                if (x + ch.getMeasuredWidth() > width - getPaddingRight() && x > getPaddingLeft()) {
                    x = getPaddingLeft(); y += rowH + gap; rowH = 0;
                }
                x += ch.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, ch.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + rowH + getPaddingBottom());
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int x = getPaddingLeft(), y = getPaddingTop(), rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                if (x + ch.getMeasuredWidth() > width - getPaddingRight() && x > getPaddingLeft()) {
                    x = getPaddingLeft(); y += rowH + gap; rowH = 0;
                }
                ch.layout(x, y, x + ch.getMeasuredWidth(), y + ch.getMeasuredHeight());
                x += ch.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, ch.getMeasuredHeight());
            }
        }
    }

    /** Weighted scrub: each frame's width is its hold, so a ×4 reads four times as wide. */
    private class ScrubBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ScrubBar(Context c) { super(c); }
        @Override protected void onDraw(Canvas canvas) {
            if (labSeq.isEmpty()) return;
            int total = 0;
            for (int[] f : labSeq) total += Math.max(1, f[1]);
            float x = 0, w = getWidth();
            for (int i = 0; i < labSeq.size(); i++) {
                float seg = w * Math.max(1, labSeq.get(i)[1]) / (float) total;
                p.setColor(i == labCur ? SpriteTheme.LIVE : 0xFF33333D);
                canvas.drawRect(x + 1, 2, x + seg - 1, getHeight() - 2, p);
                x += seg;
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (labSeq.isEmpty()) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                    || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                int total = 0;
                for (int[] f : labSeq) total += Math.max(1, f[1]);
                float t = Math.max(0f, Math.min(0.999f, e.getX() / Math.max(1, getWidth()))) * total;
                int acc = 0;
                for (int i = 0; i < labSeq.size(); i++) {
                    acc += Math.max(1, labSeq.get(i)[1]);
                    if (t < acc) { labCur = i; break; }
                }
                // Rebuilding the bench on every move event would be unusable, so the panel
                // is retargeted once, on the way up, by onTouchEvent's ACTION_UP below.
                focusRoll(labCur, false);
                invalidate();
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                focusRoll(labCur, true);
            }
            return true;
        }
    }

    /**
     * The roll: perforated stock above and below the frames. The holes are punched THROUGH,
     * with film left either side of each one — biting the edge instead reads as castle
     * crenellations rather than sprockets.
     */
    private static class FilmStrip extends LinearLayout {
        private final DropRow frames;
        FilmStrip(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundColor(0xFF1A1A1F);
            float d = c.getResources().getDisplayMetrics().density;
            addView(new Perf(c), new LayoutParams(LayoutParams.MATCH_PARENT, (int) (9 * d)));
            frames = new DropRow(c);
            frames.setOrientation(HORIZONTAL);
            int pad = (int) (3 * d);
            frames.setPadding(pad, pad, pad, pad);
            addView(frames);
            addView(new Perf(c), new LayoutParams(LayoutParams.MATCH_PARENT, (int) (9 * d)));
        }
        DropRow frames() { return frames; }

        /**
         * The row of frames, which also draws where a dragged one would land.
         *
         * <p>A lifted chip with no drop line tells you something is moving but not where it is
         * going, and on a roll of twenty frames that is the only question you have.</p>
         */
        static class DropRow extends LinearLayout {
            private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final float d;
            private int dropAt = -1;

            DropRow(Context c) {
                super(c);
                d = c.getResources().getDisplayMetrics().density;
                line.setColor(SpriteTheme.LIVE);
                setWillNotDraw(false);
            }

            void setDropAt(int index) {
                if (dropAt == index) return;
                dropAt = index;
                invalidate();
            }

            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (dropAt < 0) return;
                float x;
                if (dropAt >= getChildCount()) {
                    View last = getChildCount() == 0 ? null : getChildAt(getChildCount() - 1);
                    x = last == null ? getPaddingLeft() : last.getRight() + 2 * d;
                } else {
                    x = getChildAt(dropAt).getLeft() - 2 * d;
                }
                canvas.drawRoundRect(x - 1.5f * d, getPaddingTop(), x + 1.5f * d,
                        getHeight() - getPaddingBottom(), 1.5f * d, 1.5f * d, line);
            }
        }

        private static class Perf extends View {
            private final Paint hole = new Paint();
            private final float d;
            Perf(Context c) {
                super(c);
                d = c.getResources().getDisplayMetrics().density;
                hole.setColor(SpriteTheme.BG);
            }
            @Override protected void onDraw(Canvas canvas) {
                float pitch = 13 * d, w = 6 * d;
                float top = getHeight() * 0.25f, bot = getHeight() * 0.75f;
                for (float x = 4 * d; x < getWidth(); x += pitch) {
                    canvas.drawRoundRect(new RectF(x, top, x + w, bot), 1.5f * d, 1.5f * d, hole);
                }
            }
        }
    }

    /** One frame, still or playing. A preset animates at its own cadence with a mode dot. */
    /**
     * One frame, still or playing.
     *
     * <p>NON-static, and it reads the activity's CURRENT renderer every draw. It used to hold
     * the renderer it was built with in a final field, and the film strip lives outside
     * benchBody so nothing rebuilt it — so relinking the art, clearing the colour key or
     * dragging the tolerance pill recycled that bitmap while these chips still pointed at it.
     * The next redraw threw "trying to use a recycled bitmap". That was a crash I could reach
     * in three taps.</p>
     */
    private class PresetThumb extends View {
        @Nullable private final SpriteSheet.Preset preset;
        private final RectF dest = new RectF();
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int still = 0;
        private int tick;
        private boolean running;

        PresetThumb(Context c, @Nullable SpriteSheetRenderer ignored,
                    @Nullable SpriteSheet.Preset pr) {
            super(c);
            this.preset = pr;
            ink.setTextAlign(Paint.Align.CENTER);
            ink.setFakeBoldText(true);
            if (pr != null) start();
        }
        void setStill(int cell) { still = cell; invalidate(); }
        private void start() {
            if (running || preset == null || preset.frames.isEmpty()) return;
            running = true;
            step();
        }
        private void step() {
            if (!running) return;
            tick++;
            invalidate();
            float fps = preset != null && preset.fps > 0f ? preset.fps : 8f;
            postDelayed(this::step, (long) (1000f / Math.max(1f, Math.min(60f, fps))));
        }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); running = false; }

        @Override protected void onDraw(Canvas canvas) {
            SpriteSheetRenderer renderer = SpriteSheetEditorActivity.this.renderer;
            if (renderer == null) return;
            dest.set(1, 1, getWidth() - 1, getHeight() - 1);
            int cell = still;
            if (preset != null && !preset.frames.isEmpty()) {
                int n = preset.frames.size();
                int i;
                if ("pingpong".equals(preset.type) && n > 1) {
                    int period = 2 * n - 2, k = tick % period;
                    i = k < n ? k : period - k;
                } else i = tick % n;
                Integer f = preset.frames.get(Math.max(0, Math.min(n - 1, i)));
                cell = f == null ? 0 : f;
            }
            renderer.drawCellFitted(canvas, cell, dest, null);
            if (preset != null) {
                float r = Math.min(getWidth(), getHeight()) * 0.17f;
                float cx = getWidth() - r - 1, cy = getHeight() - r - 1;
                dot.setColor("loop".equals(preset.type) ? SpriteTheme.SELECTED
                        : "once".equals(preset.type) ? SpriteTheme.LIVE : SpriteTheme.ACCENT_CELL);
                canvas.drawCircle(cx, cy, r, dot);
                SpriteIcons.IconDrawable mark = SpriteIcons.of(
                        SpritePalettePanel.endIcon(preset.type),
                        "once".equals(preset.type) ? 0xFFFFFFFF : SpriteTheme.ON_ACCENT,
                        Math.round(r * 1.5f));
                mark.setBounds(Math.round(cx - r * 0.75f), Math.round(cy - r * 0.75f),
                        Math.round(cx + r * 0.75f), Math.round(cy + r * 0.75f));
                mark.draw(canvas);
            }
        }
    }

    private final java.util.List<Runnable> stepperSyncs = new java.util.ArrayList<>();

    /** A pill: a flat fill on a one-pixel line, which is every control in this design. */
    @NonNull
    private static GradientDrawable pillBg(int fill, int stroke, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(SpriteTheme.RADIUS_PILL * d);
        g.setStroke(Math.max(1, (int) d), stroke);
        return g;
    }

    /** The ink that reads ON a saturated accent. Pink is dark enough to need white. */
    private static int inkOn(int accent) {
        return accent == SpriteTheme.LIVE ? 0xFFFFFFFF : SpriteTheme.ON_ACCENT;
    }

    @NonNull
    private TextView chip(@NonNull String text) {
        float d = density();
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11.5f);
        t.setTextColor(SpriteTheme.INK);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pillBg(SpriteTheme.CONTROL, SpriteTheme.LINE, d));
        t.setPadding((int) (11 * d), (int) (5 * d), (int) (11 * d), (int) (5 * d));
        t.setMinHeight((int) (28 * d));
        return t;
    }

    /**
     * A chip whose face is one of the web design's icons rather than a stand-in glyph.
     *
     * <p>Left and right padding are equal and no minimum width is set, so the icon lands
     * dead centre without any of the hand-tuning a compound drawable usually needs.</p>
     */
    @NonNull
    private TextView ichip(@NonNull String icon) {
        float d = density();
        TextView t = chip("");
        t.setCompoundDrawables(SpriteIcons.of(icon, SpriteTheme.INK, (int) (16 * d)),
                null, null, null);
        t.setCompoundDrawablePadding(0);
        t.setPadding((int) (8 * d), (int) (5 * d), (int) (8 * d), (int) (5 * d));
        return t;
    }

    /** Icon then label, the way every labelled button in the design is built. */
    @NonNull
    private TextView ichip(@NonNull String icon, @NonNull String text) {
        float d = density();
        TextView t = chip(text);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setCompoundDrawables(SpriteIcons.of(icon, SpriteTheme.INK, (int) (16 * d)),
                null, null, null);
        t.setCompoundDrawablePadding((int) (5 * d));
        t.setPadding((int) (9 * d), (int) (5 * d), (int) (11 * d), (int) (5 * d));
        return t;
    }

    /** Retint a chip's icon along with its ink. An icon never goes half-opaque. */
    private static void tintIcon(@NonNull TextView v, int ink) {
        for (Drawable dr : v.getCompoundDrawables()) {
            if (dr instanceof SpriteIcons.IconDrawable) {
                ((SpriteIcons.IconDrawable) dr).setColour(ink);
            }
        }
    }

    /** The design's segmented control: one pill holding several borderless buttons. */
    @NonNull
    private LinearLayout seg() {
        float d = density();
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.HORIZONTAL);
        g.setGravity(Gravity.CENTER_VERTICAL);
        g.setBackground(pillBg(SpriteTheme.CONTROL, SpriteTheme.LINE, d));
        int p = (int) (2 * d);
        g.setPadding(p, p, p, p);
        return g;
    }

    @NonNull
    private TextView segBtn(@NonNull String icon) {
        float d = density();
        TextView t = new TextView(this);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight((int) (24 * d));
        t.setCompoundDrawables(SpriteIcons.of(icon, SpriteTheme.DIMMER, (int) (15 * d)),
                null, null, null);
        t.setCompoundDrawablePadding(0);
        t.setPadding((int) (9 * d), (int) (2 * d), (int) (9 * d), (int) (2 * d));
        t.setBackground(pillBg(0x00000000, 0x00000000, d));
        return t;
    }

    /** A segment button lit in its own colour, or transparent and grey when it is not. */
    private static void tintSeg(@NonNull TextView v, boolean on, int colour) {
        float d = v.getResources().getDisplayMetrics().density;
        int ink = on ? inkOn(colour) : SpriteTheme.DIMMER;
        v.setBackground(pillBg(on ? colour : 0x00000000, 0x00000000, d));
        v.setTextColor(ink);
        tintIcon(v, ink);
    }

    /**
     * One number, one pill: drag it sideways to change the value, tap it to type one.
     *
     * <p>The old {@code [-] [value] [+]} stepper cost three chips of width apiece, which is
     * why the alignment row ran off the side of the phone. This is the same control in the
     * space of one chip and matches the web design's {@code .num} exactly.</p>
     */
    private class NumPill extends LinearLayout {
        private final TextView valueView;
        private final FloatGet get;
        private final FloatSet set;
        private final float step;
        private final boolean integral;
        private final String suffix;
        private final String key;
        private float downX;
        private boolean dragged;
        /** Widest the number has ever been, in pixels. The pill never gives this space back. */
        private int valueFloor;

        NumPill(@NonNull String key, @Nullable String icon, @NonNull FloatGet get,
                @NonNull FloatSet set, float step, boolean integral, @NonNull String suffix) {
            super(SpriteSheetEditorActivity.this);
            this.get = get; this.set = set; this.step = step;
            this.integral = integral; this.suffix = suffix; this.key = key;
            float d = density();
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackground(pillBg(0xFF0B0B0E, SpriteTheme.LINE, d));
            setPadding((int) (9 * d), (int) (4 * d), (int) (9 * d), (int) (4 * d));
            setMinimumHeight((int) (26 * d));
            setClickable(true);

            if (icon != null) {
                TextView ic = new TextView(SpriteSheetEditorActivity.this);
                ic.setCompoundDrawables(SpriteIcons.of(icon, SpriteTheme.DIMMER, (int) (13 * d)),
                        null, null, null);
                ic.setCompoundDrawablePadding(0);
                ic.setPadding(0, 0, (int) (4 * d), 0);
                addView(ic);
            }
            if (!key.isEmpty()) {
                TextView k = new TextView(SpriteSheetEditorActivity.this);
                k.setText(key);
                k.setTextSize(9.5f);
                k.setTextColor(SpriteTheme.DIMMER);
                k.setPadding(0, 0, (int) (5 * d), 0);
                addView(k);
            }
            valueView = new TextView(SpriteSheetEditorActivity.this);
            valueView.setTextSize(11.5f);
            valueView.setTextColor(SpriteTheme.INK);
            valueView.setTypeface(Typeface.DEFAULT_BOLD);
            addView(valueView);
            sync();
            // A digit of headroom up front, so the common 0 -> 12 crossing costs no re-flow at
            // all. Without it the very first drag still jostles the row once.
            reserve(valueView.getText() + "0");
        }

        void sync() {
            valueView.setText(text());
            reserve(valueView.getText().toString());
        }

        /**
         * A pill may GROW to fit a longer number. It never shrinks back.
         *
         * <p>These pills sit in a flow layout beside real buttons. Dragging {@code tol} from 0%
         * to 12% widened the pill by a digit, which pushed {@code Detect} onto the next row —
         * the primary action hopping out from under your finger while you were still setting up
         * the thing it acts on. Reserving the high-water mark keeps the row still.</p>
         */
        private void reserve(@NonNull String probe) {
            int w = (int) Math.ceil(valueView.getPaint().measureText(probe));
            if (w > valueFloor) {
                valueFloor = w;
                valueView.setMinWidth(w);
            }
        }

        @NonNull
        private String text() {
            float v = get.get();
            return (integral ? String.valueOf(Math.round(v))
                             : String.format(java.util.Locale.US, "%.2f", v)) + suffix;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float d = density();
            float perStep = 9 * d;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    dragged = false;
                    burstMs = 4000;   // the whole gesture is one undo step
                    // Inside two nested scrollers; without this the first millimetre of a
                    // horizontal drag is stolen and the number never moves.
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int steps = (int) ((e.getRawX() - downX) / perStep);
                    if (steps != 0) {
                        dragged = true;
                        downX += steps * perStep;
                        set.set(get.get() + steps * step);
                        sync();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    burstMs = 600;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (!dragged) askForValue();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    burstMs = 600;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        /** Dragging is for feel; typing is for "exactly 12". Both reach the same setter. */
        private void askForValue() {
            final EditText in = new EditText(SpriteSheetEditorActivity.this);
            in.setSingleLine(true);
            in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            in.setText(integral ? String.valueOf(Math.round(get.get()))
                                : String.format(java.util.Locale.US, "%.2f", get.get()));
            in.setSelectAllOnFocus(true);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(SpriteSheetEditorActivity.this)
                    .setTitle(key.isEmpty() ? "Value" : key)
                    .setView(in)
                    .setPositiveButton("Set", (dl, w) -> {
                        try {
                            set.set(Float.parseFloat(in.getText().toString().trim()));
                            sync();
                        } catch (NumberFormatException ignored) {
                            // An unparseable entry means "never mind", not a crash.
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    /** Build a number pill and register it so {@link #syncControls} can refresh it. */
    @NonNull
    private NumPill num(@NonNull String key, @Nullable String icon, @NonNull FloatGet get,
                        @NonNull FloatSet set, float step, boolean integral,
                        @NonNull String suffix) {
        NumPill p = new NumPill(key, icon, get, set, step, integral, suffix);
        stepperSyncs.add(p::sync);
        return p;
    }

    /**
     * A grid number moved. Re-slice and repaint, but do NOT rebuild the section: the pill
     * being dragged lives inside it, and destroying it mid-drag ends the gesture.
     */
    private void gridNumChanged() {
        if (sheet.cellCount() > 0 && gridView.getSelectedCell() >= sheet.cellCount()) {
            gridView.setSelectedCell(sheet.cellCount() - 1);
        }
        if (sliceSub != null) sliceSub.setText("  " + cellSizeLabel());
        markDirty();
        gridView.refresh();
        if (preview != null) preview.invalidate();
        // Re-slicing renumbers every cell, so the cell editor below is now describing a
        // different drawing. It cannot rebuild mid-drag without destroying the pill under the
        // finger, so it catches up once, shortly after the numbers stop moving.
        if (benchBody != null) {
            benchBody.removeCallbacks(resliceCatchUp);
            benchBody.postDelayed(resliceCatchUp, 350);
        }
    }

    private final Runnable resliceCatchUp = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && "slice".equals(labSection)) showSection("slice");
        }
    };

    /**
     * A drawing was dragged to another slot.
     *
     * <p>Swap exchanges the two. Ripple pulls the drawing out and drops it in, shuffling
     * everything between — which is what you want when a sheet is right except that one frame
     * is in the wrong place.</p>
     *
     * <p>The name, the alignment, the viseme and the enabled flag travel with the drawing;
     * that is JoyRaptor's ruling and it is why {@code SpriteSheet} keys them by the source
     * cell and looks them up through the order.</p>
     */
    private void reorderCells(int from, int to) {
        SpriteGridEditorView.Reorder mode = gridView.getReorderMode();
        if (mode == SpriteGridEditorView.Reorder.OFF) return;
        if (mode == SpriteGridEditorView.Reorder.SWAP) sheet.swapCells(from, to);
        else sheet.moveCell(from, to);
        markDirty();
        refreshArt();
        gridView.refresh();
        focusCell(to, true);
    }

    /**
     * Set the colour-key tolerance outright, which is what a dragged pill hands over.
     *
     * <p>Tolerance only changes anything once a key colour is set, but it stays adjustable
     * beforehand so the eyedropper applies the value you already chose.</p>
     */
    private void setTolerance(float value) {
        sheet.setBgKey(sheet.getBgKeyColor(), Math.max(0f, Math.min(0.5f, value)));
        markDirty();
        // Re-key the art WITHOUT rebuilding the section: the pill being dragged lives in it.
        if (sheet.getBgKeyColor() != 0) {
            if (renderer != null) renderer.recycle();
            renderer = SpriteSheetRenderer.load(this, sheet);
            gridView.setSheet(sheet, renderer);
            preview.bind(sheet, renderer);
            preview.setCursor(labSeq.isEmpty() ? labSelected : labSeq.get(
                    Math.max(0, Math.min(labSeq.size() - 1, labCur)))[0]);
            invalidateFilm();
        }
        gridView.refresh();
        if (preview != null) preview.invalidate();
    }

    private void gridChanged() {
        // Geometry-only change: rects derive from the sheet each draw; the selected
        // index may now be out of range.
        if (gridView.getSelectedCell() >= sheet.cellCount()) {
            gridView.setSelectedCell(sheet.cellCount() - 1);
        }
        syncControls();
        onCellSelected(gridView.getSelectedCell());
    }

    private void syncControls() {
        for (Runnable r : stepperSyncs) r.run();
        if (fpsPill != null) fpsPill.sync();
        if (gridView != null) gridView.refresh();
        if (preview != null) preview.invalidate();
    }

    @NonNull
    private SpriteSheet.Cell ensureCell(int index) {
        SpriteSheet.Cell meta = sheet.cellAt(index);
        if (meta == null) {
            // cellAt maps display -> source; the record has to be created under the SOURCE, or
            // it answers for a different slot the moment the sheet is rearranged.
            meta = new SpriteSheet.Cell(sheet.sourceCell(index), "");
            sheet.getCells().add(meta);
        }
        return meta;
    }

    /**
     * SELECT a cell — what the internal callers (reload, grid reshape) mean. Does not touch
     * the roll: a re-decode must not silently lengthen the sequence you are building.
     */
    private void onCellSelected(int index) {
        if (index < 0 || sheet == null) return;
        labSelected = index;
        gridView.setSelectedCell(index);
        if (preview != null) preview.setCursor(index);
        if (benchBody != null) showSection(labSection);
        syncControls();
    }

    /**
     * TAP a cell — append it to the roll and make it the frame you are aligning.
     *
     * <p>Tap the same cell four times and it plays four times; the film strip below shows the
     * order you built. Long-pressing a frame down there takes it back off.</p>
     */
    private void onCellTappedInLab(int index) {
        if (index < 0 || sheet == null) return;
        noteChange();
        labSeq.add(new int[]{index, 1});
        labCur = labSeq.size() - 1;
        labSelected = index;
        gridView.setSelectedCell(index);
        gridView.setPlayingCell(index);
        if (preview != null) preview.setCursor(index);
        rebuildFilm();
        showSection(labSection);
    }

    // ── S2b: auto-detect / bg-key / sidecar ───────────────────────────────

    /** Gutter-scan the decoded bitmap; detected geometry is scaled back to
     *  SOURCE pixels (the space the sheet's margins/spacing live in). */
    private void autoDetectGrid() {
        if (renderer == null) {
            Toast.makeText(this, R.string.sprite_editor_auto_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        android.graphics.Bitmap bmp = renderer.getBitmap();
        SpriteGridDetector.Result r = SpriteGridDetector.detect(bmp, sheet.getBgKeyColor());
        if (r == null) {
            Toast.makeText(this, R.string.sprite_editor_auto_failed, Toast.LENGTH_LONG).show();
            return;
        }
        float sx = renderer.sourceWidth() / (float) Math.max(1, bmp.getWidth());
        float sy = renderer.sourceHeight() / (float) Math.max(1, bmp.getHeight());
        sheet.setGrid(r.cols, r.rows);
        sheet.setMargins(Math.round(r.marginX * sx), Math.round(r.marginY * sy));
        sheet.setSpacing(Math.round(r.spacingX * sx), Math.round(r.spacingY * sy));
        gridChanged();
        Toast.makeText(this, getString(R.string.sprite_editor_auto_applied, r.cols, r.rows),
                Toast.LENGTH_SHORT).show();
    }

    /** Key chip: no key set → arm pick mode; key set → clear it. */
    private void onKeyChipTapped() {
        if (sheet.getBgKeyColor() != 0) {
            sheet.setBgKey(0, 0f);
            reloadRenderer(); // re-decode without the key
            syncKeyChip();
            Toast.makeText(this, R.string.sprite_editor_key_cleared, Toast.LENGTH_SHORT).show();
            return;
        }
        gridView.setColorPickMode(!gridView.isColorPickMode());
        syncKeyChip();
        if (gridView.isColorPickMode()) {
            Toast.makeText(this, R.string.sprite_editor_key_pick_hint, Toast.LENGTH_SHORT).show();
        }
    }

    private void applyBgKey(int argb) {
        // Opaque key color; keep whatever tolerance the slider already holds (8%
        // default for flat-color bgs when the user hasn't touched the slider yet).
        float tol = sheet.getKeyTolerance() > 0f ? sheet.getKeyTolerance() : 0.08f;
        sheet.setBgKey(0xFF000000 | (argb & 0x00FFFFFF), tol);
        reloadRenderer(); // one-time color→alpha at decode (preview==export pixels)
        syncKeyChip();
        syncControls();
    }

    private void syncKeyChip() {
        if (keyBtn == null) return;
        boolean keyed = sheet.getBgKeyColor() != 0;
        // "Key" alone read as "keyframe" on a screen that has none. It is the background
        // colour being knocked out, and the button should say so.
        keyBtn.setText(keyed ? "Clear bg key"
                : gridView.isColorPickMode() ? "Tap the colour" : "Bg key");
        tintToggle(keyBtn, keyed || gridView.isColorPickMode(), SpriteTheme.ACCENT_CELL);
    }

    /** Write the standalone sidecar (<image>.sprite.json, same JSON as the
     *  project embed) next to the sheet image in the project bundle. */
    private void exportSidecar() {
        try {
            android.net.Uri uri = android.net.Uri.parse(sheet.getSheetUri());
            String path = uri.getPath();
            if (path == null || !"file".equals(uri.getScheme())) {
                Toast.makeText(this, R.string.sprite_editor_sidecar_failed, Toast.LENGTH_LONG).show();
                return;
            }
            File img = new File(path);
            String base = img.getName().contains(".")
                    ? img.getName().substring(0, img.getName().lastIndexOf('.')) : img.getName();
            File out = new File(img.getParentFile(), base + ".sprite.json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(sheet.toJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            // S2b: the plan asks for the written path in the toast — sidecar_saved
            // stays the a11y-friendly base string, path appended as a literal.
            Toast.makeText(this, getString(R.string.sprite_editor_sidecar_saved) + ": " + out.getPath(),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.sprite_editor_sidecar_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Import slicing metadata from a sidecar JSON file.
     *
     * <p>Whatever {@code exportSidecar} writes, this has to read, or the round trip quietly
     * destroys work — and since 2026-09-13 the exporter writes per-cell ALIGNMENT, which is the
     * single most laborious thing on the sheet to recreate.</p>
     */
    private void importSidecar(@NonNull android.net.Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) { throw new Exception("null stream"); }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            String text = new String(buf.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
            com.fadcam.ui.faditor.sprite.SpriteSheet imported =
                    com.fadcam.ui.faditor.sprite.SpriteSheet.fromJson(
                            com.google.gson.JsonParser.parseString(text).getAsJsonObject());
            sheet.setGrid(imported.getCols(), imported.getRows());
            sheet.setMargins(imported.getMarginX(), imported.getMarginY());
            sheet.setSpacing(imported.getSpacingX(), imported.getSpacingY());
            sheet.setFps(imported.getFps());
            sheet.setPivot(imported.getPivotX(), imported.getPivotY());
            sheet.setBgKey(imported.getBgKeyColor(), imported.getKeyTolerance());
            sheet.getCells().clear();
            for (com.fadcam.ui.faditor.sprite.SpriteSheet.Cell c : imported.getCells()) {
                com.fadcam.ui.faditor.sprite.SpriteSheet.Cell copy =
                        new com.fadcam.ui.faditor.sprite.SpriteSheet.Cell(c.index, c.name);
                copy.enabled = c.enabled;
                sheet.getCells().add(copy);
            }
            // Cell names and ANIMATIONS. The sidecar's whole reason to exist is that a sheet
            // sliced and choreographed elsewhere can arrive whole; importing the grid but
            // dropping the presets delivered an empty sheet and called it a success. Replace
            // rather than merge: the file is a description of this sheet, not an addition to it.
            sheet.getCellNames().clear();
            sheet.getCellNames().putAll(imported.getCellNames());

            // ALIGNMENT, visemes and the arrangement. The exporter writes all three; a hand
            // written importer that copies "the fields I remembered" silently destroys them,
            // and per-cell alignment is the most laborious thing on a sheet to recreate. If
            // SpriteSheet grows another per-cell map, it belongs in this block too.
            sheet.getCellTransforms().clear();
            for (java.util.Map.Entry<Integer, com.fadcam.ui.faditor.sprite.SpriteSheet.CellXf> e
                    : imported.getCellTransforms().entrySet()) {
                sheet.getCellTransforms().put(e.getKey(), e.getValue().copy());
            }
            sheet.getVisemeMap().clear();
            sheet.getVisemeMap().putAll(imported.getVisemeMap());
            // The incoming file describes its own arrangement; keeping ours would apply this
            // sheet's permutation to somebody else's numbering.
            sheet.adoptOrder(imported);

            sheet.getPresets().clear();
            for (com.fadcam.ui.faditor.sprite.SpriteSheet.Preset p : imported.getPresets()) {
                com.fadcam.ui.faditor.sprite.SpriteSheet.Preset np =
                        new com.fadcam.ui.faditor.sprite.SpriteSheet.Preset(p.id, p.name);
                np.type = p.type;
                np.fps = p.fps;
                // Frames are cell INDICES against the grid we just adopted, so anything the
                // sidecar's own grid cannot address is a typo, not a frame — drop it here
                // rather than let the resolver meet it later.
                int cells = Math.max(0, imported.getCols() * imported.getRows());
                for (int fi = 0; fi < p.frames.size(); fi++) {
                    int f = p.frames.get(fi);
                    if (f < 0 || f >= cells) continue;
                    np.frames.add(f);
                    np.weights.add(com.fadcam.ui.faditor.sprite.SequenceTiming
                            .weightAt(p.weights, fi));
                }
                if (!np.frames.isEmpty()) sheet.getPresets().add(np);
            }
            gridChanged();
            reloadRenderer();
            Toast.makeText(this, R.string.sprite_editor_sidecar_imported, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.sprite_editor_sidecar_import_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        preview.setPlaying(false);
        if (renderer != null) renderer.recycle();
    }

    /** Tiny live preview cycling ENABLED cells at the sheet's fps ("does it play?"). */
    private static class CellCyclePreview extends View {
        @Nullable private SpriteSheet sheet;
        @Nullable private SpriteSheetRenderer renderer;
        @Nullable private SpriteSheetEditorActivity activity;
        private boolean playing = false;
        private int cursor = 0;
        private final RectF dest = new RectF();
        private final Paint onionPaint = new Paint();
        /** Ghosts fade with distance, so "one frame back" and "three back" are tellable apart. */
        private static int alphaFor(int distance, float strength) {
            float a = 255f * strength * (float) Math.pow(0.62, Math.max(0, distance - 1));
            return Math.max(12, Math.min(255, Math.round(a)));
        }
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!playing || sheet == null) return;
                long ms = (long) (1000f / Math.max(0.5f, sheet.getFps()));
                // Play the ROLL when there is one. Cycling the sheet instead would ignore
                // the sequence the user just built, which is the whole point of the Lab.
                if (activity != null && !activity.labSeq.isEmpty()) {
                    if (!activity.advanceRoll()) {
                        setPlaying(false);
                        activity.syncPlayBtn();
                        return;
                    }
                    cursor = activity.labSeq.get(activity.labCur)[0];
                    invalidate();
                    activity.gridView.setPlayingCell(cursor);
                    activity.syncFilmCursor();
                    postDelayed(this, ms);
                    return;
                }
                cursor = nextEnabled(cursor + 1);
                invalidate();
                // S2b filmstrip polish: mirror the playing cell on the main grid so
                // the run cycle is visible at full size, not just in the tiny preview.
                if (activity != null) activity.gridView.setPlayingCell(cursor);
                postDelayed(this, ms);
            }
        };

        private final RectF bgRect = new RectF();
        private final RectF hudBg = new RectF();
        private final Paint hudInk = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path bgClip = new android.graphics.Path();

        CellCyclePreview(Context ctx) {
            super(ctx);
            hudInk.setColor(0xFFFFFFFF);
            hudInk.setFakeBoldText(true);
            if (ctx instanceof SpriteSheetEditorActivity) activity = (SpriteSheetEditorActivity) ctx;
        }

        void bind(@Nullable SpriteSheet s, @Nullable SpriteSheetRenderer r) {
            this.sheet = s;
            this.renderer = r;
            cursor = 0;
            invalidate();
        }

        boolean isPlaying() { return playing; }

        void setCursor(int cell) {
            cursor = Math.max(0, cell);
            invalidate();
        }

        void setPlaying(boolean p) {
            playing = p;
            removeCallbacks(tick);
            if (p) {
                post(tick);
            } else if (activity != null) {
                // Whatever frame it stopped on is now THE current frame. Without this the
                // panel still pointed at wherever the playhead was before you pressed play.
                // Whatever frame it stopped on is THE current frame, roll or no roll. The
                // no-roll branch used to leave preview.cursor as a third store of "which
                // cell", so a drag on the preview then moved whatever the grid had selected.
                if (!activity.labSeq.isEmpty()) activity.focusRoll(activity.labCur, true);
                else activity.focusCell(cursor, true);
            }
        }

        private int nextEnabled(int from) {
            if (sheet == null || sheet.cellCount() == 0) return 0;
            int n = sheet.cellCount();
            for (int i = 0; i < n; i++) {
                int idx = (from + i) % n;
                SpriteSheet.Cell meta = sheet.cellAt(idx);
                if (meta == null || meta.enabled) return idx;
            }
            return from % n;
        }

        /** Scan backward from {@code from} (excluding {@code from}) for an enabled cell, wrap around. */
        private int previousEnabled(int from) {
            if (sheet == null || sheet.cellCount() == 0) return -1;
            int n = sheet.cellCount();
            for (int i = 1; i <= n; i++) {
                int idx = (from - i + n) % n;
                SpriteSheet.Cell meta = sheet.cellAt(idx);
                if (meta == null || meta.enabled) return idx;
            }
            return -1;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (renderer == null || sheet == null) return;
            float d = getResources().getDisplayMetrics().density;
            bgRect.set(0, 0, getWidth(), getHeight());
            int outer = canvas.save();
            bgClip.reset();
            bgClip.addRoundRect(bgRect, 8 * d, 8 * d, android.graphics.Path.Direction.CW);
            canvas.clipPath(bgClip);
            drawBg(canvas, bgRect, activity != null ? activity.previewBg : 0, d, bgPaint);
            // Fit, do not fill. The preview spans the whole width when the bench is tall, and
            // stretching a square character into that box made him look fat.
            dest.set(renderer.fitCell(Math.max(0, Math.min(cursor, sheet.cellCount() - 1)),
                    new RectF(2, 2, getWidth() - 2, getHeight() - 2)));
            int showCell = Math.min(cursor, sheet.cellCount() - 1);
            // S2b onion skin: ghost the previous AND next enabled cell (by index) at
            // ~30% alpha under/over the selected cell — the classic run-cycle flow check.
            if (activity != null && activity.onionMode && showCell >= 0) {
                // Past pink, future cyan, dimming with distance. Ghosts follow the ROLL when
                // there is one — the frames either side in the animation you are building are
                // what you are judging against, not whatever happens to sit next on the sheet.
                for (int k = activity.onionPast; k >= 1; k--) {
                    int c = activity.neighbourCell(showCell, -k);
                    if (c >= 0 && c != showCell) {
                        renderer.drawCell(canvas, c, renderer.fitCell(c, bgRect), ghostPaint(
                                activity.onionPastColour, alphaFor(k, activity.onionStrength)));
                    }
                }
                for (int k = activity.onionFuture; k >= 1; k--) {
                    int c = activity.neighbourCell(showCell, k);
                    if (c >= 0 && c != showCell) {
                        renderer.drawCell(canvas, c, renderer.fitCell(c, bgRect), ghostPaint(
                                activity.onionFutureColour, alphaFor(k, activity.onionStrength)));
                    }
                }
            }
            renderer.drawCell(canvas, showCell, dest, null);

            // The HUD: what frame this is and where it sits. It is also the only thing that
            // says the preview is a CONTROL and not a picture.
            if (activity != null) {
                SpriteSheet.CellXf t = sheet.cellTransform(showCell);
                String hud = "c" + showCell
                        + "  x" + Math.round(t == null ? 0 : t.dx)
                        + " y" + Math.round(t == null ? 0 : t.dy)
                        + "  " + String.format(java.util.Locale.US, "%.2f",
                                t == null ? 1f : t.scale) + "\u00d7"
                        + "  " + Math.round(t == null ? 0 : t.rot) + "\u00b0";
                hudInk.setTextSize(9.5f * d);
                float pad = 4 * d;
                float tw = hudInk.measureText(hud);
                hudBg.set(pad, getHeight() - pad - 15 * d, pad + tw + 2 * pad,
                        getHeight() - pad);
                bgPaint.setColorFilter(null);
                bgPaint.setStyle(Paint.Style.FILL);
                bgPaint.setColor(0xB0000000);
                canvas.drawRoundRect(hudBg, 4 * d, 4 * d, bgPaint);
                canvas.drawText(hud, pad * 2, getHeight() - pad - 4.5f * d, hudInk);
            }
            canvas.restoreToCount(outer);
        }

        // ── the preview is a control ─────────────────────────────────────
        //
        // JoyRaptor, of the web app: "I can't just straight manipulate it from the preview
        // window, which is something that I could do before." Dragging the art is the whole
        // reason the alignment numbers exist; typing them is the fallback, not the method.

        private float lastX, lastY;
        private boolean dragging;
        private android.view.ScaleGestureDetector pinch;

        private void ensureGestures() {
            if (pinch != null || activity == null) return;
            pinch = new android.view.ScaleGestureDetector(getContext(),
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScale(android.view.ScaleGestureDetector g) {
                            if (activity != null) activity.scaleCurrentCell(g.getScaleFactor());
                            return true;
                        }
                    });
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (activity == null || renderer == null || sheet == null) return false;
            ensureGestures();
            if (pinch != null) pinch.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX(); lastY = e.getY();
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging && e.getPointerCount() == 1) {
                        // View pixels into SOURCE pixels, so a nudge means the same thing here
                        // as it does in the number pill.
                        float k = srcPerView();
                        activity.nudgeCurrentCell((e.getX() - lastX) * k,
                                (e.getY() - lastY) * k);
                        lastX = e.getX(); lastY = e.getY();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) { dragging = false; activity.alignGestureEnded(); }
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return true;
            }
        }

        /** How many source pixels one view pixel is worth in this preview. */
        private float srcPerView() {
            if (renderer == null || sheet == null) return 1f;
            int cell = Math.max(0, Math.min(cursor, sheet.cellCount() - 1));
            android.graphics.Rect cr = renderer.cellRectBitmap(cell);
            float dw = Math.max(1f, dest.width());
            return Math.max(1, cr.width()) / dw;
        }
    }
}
