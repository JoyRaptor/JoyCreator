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
    private TextView pivotBtn;
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
        onCellSelected(gridView.getSelectedCell());
    }

    private void save() {
        sheet.setName(nameField.getText().toString().trim().isEmpty()
                ? getString(R.string.sprite_editor_default_name)
                : nameField.getText().toString().trim());
        if (isNewSheet && sheet.getSheetUri().isEmpty()) { finish(); return; } // picker cancelled
        if (isNewSheet && project.spriteSheetById(sheet.getId()) == null) {
            project.getSpriteSheets().add(sheet);
            isNewSheet = false;
        }
        boolean ok = storage.save(project);
        if (ok) AIChatState.signalModified(project.getId());
        Toast.makeText(this, ok ? R.string.sprite_editor_saved : R.string.sprite_editor_save_failed,
                Toast.LENGTH_SHORT).show();
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

    private void markDirty() { labDirty = true; syncSaveBtn(); noteChange(); }

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
        sb.append('\0').append(labCur).append('|');
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
            while (undoStack.size() > 40) undoStack.removeLast();
            redoStack.clear();
        }
        burstAt = now;
        settled = snapshot();
        syncUndo();
    }

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

        TextView prev = chip("◀");
        prev.setOnClickListener(v -> stepLab(-1));
        TextView next = chip("▶");
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
    }

    private void stepLab(int dir) {
        if (labSeq.isEmpty()) return;
        labCur = ((labCur + dir) % labSeq.size() + labSeq.size()) % labSeq.size();
        int cell = labSeq.get(labCur)[0];
        preview.setCursor(cell);
        gridView.setPlayingCell(cell);
        rebuildFilm();
        if (scrubBar != null) scrubBar.invalidate();
        if ("play".equals(labSection)) showSection(labSection);
    }

    // ── sections ─────────────────────────────────────────────────────────

    private void showSection(@NonNull String id) {
        labSection = id;
        syncNav();
        // Every number pill belongs to the section that built it; keeping stale ones alive
        // would have syncControls poking at views that are no longer on screen.
        stepperSyncs.clear();
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

        keyBtn = chip(sheet.getBgKeyColor() != 0
                ? getString(R.string.sprite_editor_key_clear) : getString(R.string.sprite_editor_key));
        keyBtn.setOnClickListener(v -> { onKeyChipTapped(); syncKeyChip(); });
        b.addView(keyBtn);
        syncKeyChip();

        pivotBtn = gchip(getString(R.string.sprite_editor_pivot),
                gridView.isPivotMode(), SpriteTheme.LIVE);
        pivotBtn.setOnClickListener(v -> {
            gridView.setPivotMode(!gridView.isPivotMode());
            tintToggle(pivotBtn, gridView.isPivotMode(), SpriteTheme.LIVE);
        });
        b.addView(pivotBtn);
        benchBody.addView(g);
        syncControls();

        // ── the cell's own identity: name, tags, viseme ──
        int cell = Math.max(0, gridView.getSelectedCell());
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
                sheet.setCellName(cell, e.toString());
                SpriteSheet.Cell meta = ensureCell(cell);
                meta.name = e.toString().trim();
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
            ensureCell(cell).enabled = on; markDirty(); gridView.refresh();
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
            Integer assigned = sheet.getVisemeMap().get(v);
            boolean on = assigned != null && assigned == cell;
            TextView vb = gchip(v, on, SpriteTheme.ACCENT_CELL);
            vb.setOnClickListener(x -> {
                if (on) sheet.getVisemeMap().remove(v);
                else sheet.getVisemeMap().put(v, cell);
                markDirty();
                showSection("slice");
            });
            cb.addView(vb);
        }
        benchBody.addView(cg);
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
        int cell = Math.max(0, gridView.getSelectedCell());
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
            new android.app.AlertDialog.Builder(this)
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
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
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
        SpriteSheet.CellXf t = sheet.cellTransform(cell);
        final SpriteSheet.CellXf xf = t == null ? new SpriteSheet.CellXf() : t.copy();
        String nm = sheet.cellName(cell);
        View g = group("align", SpriteTheme.ACCENT_ALIGN, "target", "Alignment",
                "cell " + cell + (nm == null || nm.isEmpty() ? "" : " · " + nm));
        FlowLayout b = bodyOf(g);

        b.addView(num("x", "movex", () -> xf.dx,
                v -> { xf.dx = v; applyXf(cell, xf); }, 1f, true, ""));
        b.addView(num("y", "movey", () -> xf.dy,
                v -> { xf.dy = v; applyXf(cell, xf); }, 1f, true, ""));
        b.addView(num("scale", "scale", () -> xf.scale,
                v -> { xf.scale = Math.max(0.05f, v); applyXf(cell, xf); }, 0.05f, false, "×"));
        b.addView(num("rot", "rot", () -> xf.rot,
                v -> { xf.rot = v; applyXf(cell, xf); }, 5f, true, "°"));

        TextView centre = ichip("wand", "Auto-centre");
        tintToggle(centre, true, SpriteTheme.ACCENT_ALIGN);
        centre.setOnClickListener(v -> { autoCentre(false); showSection("play"); });
        b.addView(centre);
        TextView feet = chip("Plant feet");
        feet.setOnClickListener(v -> { autoCentre(true); showSection("play"); });
        b.addView(feet);
        TextView reset = chip("Reset");
        reset.setOnClickListener(v -> { sheet.setCellTransform(cell, null); markDirty(); refreshArt(); showSection("play"); });
        b.addView(reset);
        TextView resetAll = chip("Reset all");
        resetAll.setOnClickListener(v -> {
            sheet.getCellTransforms().clear(); markDirty(); refreshArt(); showSection("play");
        });
        b.addView(resetAll);
        return g;
    }

    private void applyXf(int cell, @NonNull SpriteSheet.CellXf xf) {
        sheet.setCellTransform(cell, xf);
        markDirty();
        refreshArt();
    }
    private void refreshArt() {
        gridView.refresh();
        preview.invalidate();
        rebuildFilm();
    }

    @NonNull
    private View buildSeqGroup() {
        View g = group("seq", SpriteTheme.ACCENT_SEQ, "layers", "Sequence",
                labSeq.size() + " frames");
        FlowLayout b = bodyOf(g);
        TextView add = chip("+ this cell");
        add.setOnClickListener(v -> {
            labSeq.add(new int[]{Math.max(0, gridView.getSelectedCell()), 1});
            labCur = labSeq.size() - 1;
            rebuildFilm(); showSection("play");
        });
        b.addView(add);
        TextView all = chip("Add all");
        all.setOnClickListener(v -> {
            labSeq.clear();
            for (int i = 0; i < sheet.cellCount(); i++) {
                SpriteSheet.Cell m = sheet.cellAt(i);
                if (m != null && !m.enabled) continue;
                labSeq.add(new int[]{i, 1});
            }
            labCur = 0; rebuildFilm(); showSection("play");
        });
        b.addView(all);
        TextView holdUp = chip("Hold +");
        holdUp.setOnClickListener(v -> { bumpHold(+1); });
        b.addView(holdUp);
        TextView holdDn = chip("Hold −");
        holdDn.setOnClickListener(v -> { bumpHold(-1); });
        b.addView(holdDn);
        TextView clear = chip("Clear");
        clear.setOnClickListener(v -> { labSeq.clear(); labCur = 0; rebuildFilm(); showSection("play"); });
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
        new android.app.AlertDialog.Builder(this)
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
        hint.setText("Tap a clip to see it on the sheet. Its frames light up in order.");
        hint.setTextColor(SpriteTheme.DIMMER);
        hint.setTextSize(10f);
        benchBody.addView(hint);

        FlowLayout shelf = new FlowLayout(this);
        shelf.setPadding(0, (int) (5 * d), 0, (int) (5 * d));
        SpriteSheet.Preset picked = null;
        for (int i = 0; i < sheet.getPresets().size(); i++) {
            final SpriteSheet.Preset pr = sheet.getPresets().get(i);
            boolean sel = pr.id.equals(pickedClip);
            if (sel) picked = pr;
            View chip = spriteChip(0, pr, pr.name, pr.frames.size() + "f \u00b7 "
                    + (int) (pr.fps > 0 ? pr.fps : sheet.getFps()) + "fps", 0, false, sel);
            chip.setOnClickListener(v -> {
                pickedClip = pr.id.equals(pickedClip) ? null : pr.id;
                showSection("clips");
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
                labCur = 0;
                labHold = 0;
                labWrap = pick.type == null ? "loop" : pick.type;
                syncWrap();
                pickedClip = null;
                rebuildFilm();
                showSection("play");
            });
            acts.addView(load);

            TextView edit = ichip("tag", "Rename / retime");
            edit.setOnClickListener(v -> editPreset(pick));
            acts.addView(edit);

            TextView del = ichip("x", "Delete");
            del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
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

        new android.app.AlertDialog.Builder(this)
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
        note.setText("Alignment is stored with the sheet, so nothing has to be baked here — "
                + "a nudge you make now travels into every project that uses this sheet.");
        note.setTextColor(SpriteTheme.DIMMER);
        note.setTextSize(10.5f);
        benchBody.addView(note);
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
            chip.setOnClickListener(v -> {
                labCur = idx;
                labHold = 0;
                preview.setCursor(labSeq.get(idx)[0]);
                gridView.setPlayingCell(labSeq.get(idx)[0]);
                syncFilmCursor();
                if ("play".equals(labSection)) showSection("play");
            });
            chip.setOnLongClickListener(v -> {
                noteChange();
                labSeq.remove(idx);
                if (labCur >= labSeq.size()) labCur = Math.max(0, labSeq.size() - 1);
                rebuildFilm();
                if ("play".equals(labSection)) showSection("play");
                return true;
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.rightMargin = (int) (4 * d);
            strip.frames().addView(chip, bl);
            filmBoxes.add(chip);
        }
        filmRow.addView(strip);
        if (scrubBar != null) scrubBar.invalidate();
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
        String nm = sheet.cellName(cell);
        if (nm == null || nm.isEmpty()) {
            SpriteSheet.Cell m = sheet.cellAt(cell);
            nm = m == null ? null : m.name;
        }
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
                preview.setCursor(labSeq.get(labCur)[0]);
                gridView.setPlayingCell(labSeq.get(labCur)[0]);
                rebuildFilm();
                invalidate();
                return true;
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
        private final LinearLayout frames;
        FilmStrip(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundColor(0xFF1A1A1F);
            float d = c.getResources().getDisplayMetrics().density;
            addView(new Perf(c), new LayoutParams(LayoutParams.MATCH_PARENT, (int) (9 * d)));
            frames = new LinearLayout(c);
            frames.setOrientation(HORIZONTAL);
            int pad = (int) (3 * d);
            frames.setPadding(pad, pad, pad, pad);
            addView(frames);
            addView(new Perf(c), new LayoutParams(LayoutParams.MATCH_PARENT, (int) (9 * d)));
        }
        LinearLayout frames() { return frames; }

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
    private static class PresetThumb extends View {
        @Nullable private final SpriteSheetRenderer renderer;
        @Nullable private final SpriteSheet.Preset preset;
        private final RectF dest = new RectF();
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int still = 0;
        private int tick;
        private boolean running;

        PresetThumb(Context c, @Nullable SpriteSheetRenderer r, @Nullable SpriteSheet.Preset pr) {
            super(c);
            this.renderer = r;
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
            renderer.drawCell(canvas, cell, dest, null);
            if (preset != null) {
                float r = Math.min(getWidth(), getHeight()) * 0.17f;
                float cx = getWidth() - r - 1, cy = getHeight() - r - 1;
                dot.setColor("loop".equals(preset.type) ? SpriteTheme.SELECTED
                        : "once".equals(preset.type) ? SpriteTheme.LIVE : SpriteTheme.ACCENT_CELL);
                canvas.drawCircle(cx, cy, r, dot);
                ink.setColor("once".equals(preset.type) ? 0xFFFFFFFF : SpriteTheme.ON_ACCENT);
                ink.setTextSize(r * 1.35f);
                canvas.drawText("loop".equals(preset.type) ? "∞"
                        : "once".equals(preset.type) ? "1" : "⇄", cx, cy + r * 0.5f, ink);
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
        }

        void sync() { valueView.setText(text()); }

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
            new android.app.AlertDialog.Builder(SpriteSheetEditorActivity.this)
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
        if (sheet.getBgKeyColor() != 0) reloadRenderer();
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
            meta = new SpriteSheet.Cell(index, "");
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
        keyBtn.setText(keyed ? getString(R.string.sprite_editor_key_clear)
                : getString(R.string.sprite_editor_key));
        keyBtn.setBackgroundColor(keyed || gridView.isColorPickMode() ? 0xFF4A3B5C : 0xFF26262E);
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

    /** Import slicing metadata from a sidecar JSON file (grid, fps, pivot, bgKey, cell names). */
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
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path bgClip = new android.graphics.Path();

        CellCyclePreview(Context ctx) {
            super(ctx);
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
                activity.gridView.setPlayingCell(-1);
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
            dest.set(2, 2, getWidth() - 2, getHeight() - 2);
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
                        renderer.drawCell(canvas, c, dest, ghostPaint(
                                activity.onionPastColour, alphaFor(k, activity.onionStrength)));
                    }
                }
                for (int k = activity.onionFuture; k >= 1; k--) {
                    int c = activity.neighbourCell(showCell, k);
                    if (c >= 0 && c != showCell) {
                        renderer.drawCell(canvas, c, dest, ghostPaint(
                                activity.onionFutureColour, alphaFor(k, activity.onionStrength)));
                    }
                }
            }
            renderer.drawCell(canvas, showCell, dest, null);
            canvas.restoreToCount(outer);
        }
    }
}
