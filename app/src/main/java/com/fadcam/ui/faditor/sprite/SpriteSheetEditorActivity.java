package com.fadcam.ui.faditor.sprite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
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
    private TextView onionBtn;
    private boolean onionMode = false;
    private TextView keyBtn;
    private TextView playBtn;
    private CellCyclePreview preview;
    private TextView fpsValue;
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
    private TextView wrapBtn;
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

        View divider = new View(this);
        divider.setBackgroundColor(SpriteTheme.LINE);
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
    }

    /** Back · name · four coloured section icons · save. One line. */
    @NonNull
    private View buildTopBar(float d) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (8 * d);
        top.setPadding(pad, pad / 2, pad, pad / 2);

        TextView back = chip("←");
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

        // Four sections, four colours. Solid when active, plain grey when not — there is no
        // half-opaque middle state anywhere in this package.
        addNav(top, "slice", "▦", SpriteTheme.ACCENT_GRID);
        addNav(top, "play",  "✛", SpriteTheme.ACCENT_ALIGN);
        addNav(top, "clips", "🎞", SpriteTheme.ACCENT_CLIPS);
        addNav(top, "out",   "⭱", SpriteTheme.ACCENT_OUT);

        saveBtn = chip("💾");
        saveBtn.setOnClickListener(v -> { save(); labDirty = false; syncSaveBtn(); });
        top.addView(saveBtn);
        syncSaveBtn();
        return top;
    }

    private void addNav(@NonNull LinearLayout parent, @NonNull String id,
                        @NonNull String glyph, int colour) {
        TextView b = chip(glyph);
        b.setTag(colour);
        b.setOnClickListener(v -> showSection(id));
        navBtns.put(id, b);
        parent.addView(b);
    }

    private void syncNav() {
        for (java.util.Map.Entry<String, TextView> e : navBtns.entrySet()) {
            TextView b = e.getValue();
            boolean on = e.getKey().equals(labSection);
            int colour = (Integer) b.getTag();
            b.setBackgroundColor(on ? colour : SpriteTheme.CONTROL);
            b.setTextColor(on ? SpriteTheme.ON_ACCENT : SpriteTheme.DIM);
        }
    }

    /** Pink while there is something unsaved; plain grey the moment there is not. */
    private void syncSaveBtn() {
        if (saveBtn == null) return;
        saveBtn.setBackgroundColor(labDirty ? SpriteTheme.LIVE : SpriteTheme.CONTROL);
        saveBtn.setTextColor(labDirty ? 0xFFFFFFFF : SpriteTheme.DIMMER);
    }

    private void markDirty() { labDirty = true; syncSaveBtn(); }

    @NonNull
    private View buildTransport(float d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (9 * d);
        row.setPadding(pad, (int) (4 * d), pad, (int) (4 * d));

        preview = new CellCyclePreview(this);   // lives in the Play section; built here so the
        preview.bind(sheet, renderer);          // transport can drive it from any section

        playBtn = chip(getString(R.string.sprite_editor_play));
        playBtn.setOnClickListener(v -> {
            boolean now = !preview.isPlaying();
            preview.setPlaying(now);
            playBtn.setText(now ? getString(R.string.sprite_editor_stop)
                    : getString(R.string.sprite_editor_play));
        });
        row.addView(playBtn);

        TextView prev = chip("◀");
        prev.setOnClickListener(v -> stepLab(-1));
        TextView next = chip("▶");
        next.setOnClickListener(v -> stepLab(+1));
        row.addView(prev);
        row.addView(next);

        wrapBtn = chip(endGlyphFor(sheet));
        wrapBtn.setOnClickListener(v -> { cycleSheetWrap(); wrapBtn.setText(endGlyphFor(sheet)); tintWrap(); });
        tintWrap();
        row.addView(wrapBtn);

        LinearLayout fpsBox = stepperShell(row, "FPS");
        TextView minus = stepBtn("−");
        fpsValue = stepValue();
        TextView plus = stepBtn("+");
        minus.setOnClickListener(v -> { sheet.setFps(sheet.getFps() - 0.5f); syncControls(); markDirty(); });
        plus.setOnClickListener(v -> { sheet.setFps(sheet.getFps() + 0.5f); syncControls(); markDirty(); });
        fpsBox.addView(minus); fpsBox.addView(fpsValue); fpsBox.addView(plus);

        onionBtn = chip(getString(R.string.sprite_editor_onion));
        onionBtn.setOnClickListener(v -> {
            onionMode = !onionMode;
            tintToggle(onionBtn, onionMode, SpriteTheme.ACCENT_VIEW);
            preview.invalidate();
        });
        tintToggle(onionBtn, onionMode, SpriteTheme.ACCENT_VIEW);
        row.addView(onionBtn);
        return row;
    }

    private void tintWrap() {
        if (wrapBtn == null) return;
        String t = sheet.getPresets().isEmpty() ? "loop" : sheet.getPresets().get(0).type;
        int c = "loop".equals(t) ? SpriteTheme.ACCENT_GRID
                : "pingpong".equals(t) ? SpriteTheme.ACCENT_CELL : SpriteTheme.LIVE;
        wrapBtn.setBackgroundColor(c);
        wrapBtn.setTextColor(c == SpriteTheme.LIVE ? 0xFFFFFFFF : SpriteTheme.ON_ACCENT);
    }

    @NonNull
    private static String endGlyphFor(@NonNull SpriteSheet sh) {
        String t = sh.getPresets().isEmpty() ? "loop" : sh.getPresets().get(0).type;
        return "loop".equals(t) ? "∞" : "pingpong".equals(t) ? "⇄" : "▸|";
    }

    private void cycleSheetWrap() {
        if (sheet.getPresets().isEmpty()) return;
        SpriteSheet.Preset p = sheet.getPresets().get(0);
        p.type = "loop".equals(p.type) ? "pingpong" : "pingpong".equals(p.type) ? "once" : "loop";
        markDirty();
    }

    /** Solid accent or plain grey — never a wash. */
    private static void tintToggle(@NonNull TextView v, boolean on, int colour) {
        v.setBackgroundColor(on ? colour : SpriteTheme.CONTROL);
        v.setTextColor(on ? SpriteTheme.ON_ACCENT : SpriteTheme.DIM);
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
    private LinearLayout group(@NonNull String key, int colour, @NonNull String glyph,
                               @NonNull String title, @Nullable String sub) {
        float d = density();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, (int) (4 * d), 0, (int) (3 * d));

        TextView icon = new TextView(this);
        icon.setText(glyph);
        icon.setTextColor(colour);
        icon.setTextSize(13f);
        head.addView(icon);

        TextView t = new TextView(this);
        t.setText("  " + title.toUpperCase());
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(10.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("  " + sub);
            e.setTextColor(SpriteTheme.DIMMER);
            e.setTextSize(9.5f);
            head.addView(e);
        }

        View sp = new View(this);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView caret = new TextView(this);
        caret.setTextColor(SpriteTheme.DIMMER);
        caret.setTextSize(11f);
        head.addView(caret);

        final FlowLayout body = new FlowLayout(this);
        body.setPadding(0, 0, 0, (int) (5 * d));

        boolean open = openGroups.contains(key);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        caret.setText(open ? "⌄" : "›");
        head.setOnClickListener(v -> {
            boolean nowOpen = !openGroups.contains(key);
            if (nowOpen) openGroups.add(key); else openGroups.remove(key);
            body.setVisibility(nowOpen ? View.VISIBLE : View.GONE);
            caret.setText(nowOpen ? "⌄" : "›");
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
        View g = group("slice1", SpriteTheme.ACCENT_GRID, "▦", "Grid & slicing",
                cellSizeLabel());
        FlowLayout b = bodyOf(g);
        stepperSyncs.clear();
        addStepper(b, "Cols", () -> sheet.getCols(),
                delta -> { sheet.setGrid(sheet.getCols() + delta, sheet.getRows()); markDirty(); });
        addStepper(b, "Rows", () -> sheet.getRows(),
                delta -> { sheet.setGrid(sheet.getCols(), sheet.getRows() + delta); markDirty(); });
        addStepper(b, "Marg X", () -> sheet.getMarginX(),
                delta -> { sheet.setMargins(sheet.getMarginX() + delta * 2, sheet.getMarginY()); markDirty(); });
        addStepper(b, "Marg Y", () -> sheet.getMarginY(),
                delta -> { sheet.setMargins(sheet.getMarginX(), sheet.getMarginY() + delta * 2); markDirty(); });
        addStepper(b, "Space X", () -> sheet.getSpacingX(),
                delta -> { sheet.setSpacing(sheet.getSpacingX() + delta * 2, sheet.getSpacingY()); markDirty(); });
        addStepper(b, "Space Y", () -> sheet.getSpacingY(),
                delta -> { sheet.setSpacing(sheet.getSpacingX(), sheet.getSpacingY() + delta * 2); markDirty(); });

        TextView detect = gchip("✲ Detect", false, SpriteTheme.ACCENT_GRID);
        detect.setOnClickListener(v -> { autoDetectGrid(); markDirty(); showSection("slice"); });
        b.addView(detect);

        keyBtn = chip(sheet.getBgKeyColor() != 0
                ? getString(R.string.sprite_editor_key_clear) : getString(R.string.sprite_editor_key));
        keyBtn.setOnClickListener(v -> { onKeyChipTapped(); syncKeyChip(); });
        b.addView(keyBtn);
        syncKeyChip();

        LinearLayout tolBox = stepperShell(b, "Tol");
        TextView tm = stepBtn("−");
        TextView tv = stepValue();
        TextView tp = stepBtn("+");
        Runnable tolSync = () -> tv.setText(Math.round(sheet.getKeyTolerance() * 100) + "%");
        stepperSyncs.add(tolSync);
        tm.setOnClickListener(v -> { adjustTolerance(-0.02f, tolSync); markDirty(); });
        tp.setOnClickListener(v -> { adjustTolerance(0.02f, tolSync); markDirty(); });
        tolBox.addView(tm); tolBox.addView(tv); tolBox.addView(tp);
        tolSync.run();

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
        View cg = group("cellEd", SpriteTheme.ACCENT_CELL, "🏷",
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
        TextView t = gchip("◍", onionMode, SpriteTheme.ACCENT_VIEW);
        t.setOnClickListener(v -> {
            onionMode = !onionMode;
            tintToggle(t, onionMode, SpriteTheme.ACCENT_VIEW);
            tintToggle(onionBtn, onionMode, SpriteTheme.ACCENT_VIEW);
            preview.invalidate();
        });
        bar.addView(t);
        // The colour IS the control: the pill shows how many ghosts on that side.
        TextView past = chip("1");
        past.setBackgroundColor(SpriteTheme.LIVE);
        past.setTextColor(0xFFFFFFFF);
        past.setAlpha(onionMode ? 1f : 0.35f);
        bar.addView(past);
        TextView future = chip("1");
        future.setBackgroundColor(SpriteTheme.SELECTED);
        future.setTextColor(SpriteTheme.ON_ACCENT);
        future.setAlpha(onionMode ? 1f : 0.35f);
        bar.addView(future);
        return bar;
    }

    @NonNull
    private View buildAlignGroup(int cell) {
        SpriteSheet.CellXf t = sheet.cellTransform(cell);
        final SpriteSheet.CellXf xf = t == null ? new SpriteSheet.CellXf() : t.copy();
        String nm = sheet.cellName(cell);
        View g = group("align", SpriteTheme.ACCENT_ALIGN, "✦", "Alignment",
                "cell " + cell + (nm == null || nm.isEmpty() ? "" : " · " + nm));
        FlowLayout b = bodyOf(g);

        addFloatStepper(b, "↔ x", () -> xf.dx, v -> { xf.dx = v; applyXf(cell, xf); }, 1f);
        addFloatStepper(b, "↕ y", () -> xf.dy, v -> { xf.dy = v; applyXf(cell, xf); }, 1f);
        addFloatStepper(b, "scale", () -> xf.scale, v -> { xf.scale = Math.max(0.05f, v); applyXf(cell, xf); }, 0.05f);
        addFloatStepper(b, "rot", () -> xf.rot, v -> { xf.rot = v; applyXf(cell, xf); }, 5f);

        TextView centre = gchip("✲ Auto-centre", false, SpriteTheme.ACCENT_ALIGN);
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
        View g = group("seq", SpriteTheme.ACCENT_SEQ, "≣", "Sequence",
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
        TextView saveClip = gchip("🎬 Save clip", true, SpriteTheme.ACCENT_SEQ);
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
    private void buildClipsSection() {
        float d = density();
        if (sheet.getPresets().isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No animations yet. Build a sequence in Play, then Save clip.");
            empty.setTextColor(SpriteTheme.DIMMER);
            empty.setTextSize(11.5f);
            benchBody.addView(empty);
            return;
        }
        for (int i = 0; i < sheet.getPresets().size(); i++) {
            final SpriteSheet.Preset pr = sheet.getPresets().get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(SpriteTheme.CONTROL);
            int pad = (int) (6 * d);
            row.setPadding(pad, pad, pad, pad);

            PresetThumb th = new PresetThumb(this, renderer, pr);
            row.addView(th, new LinearLayout.LayoutParams((int) (46 * d), (int) (46 * d)));

            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.VERTICAL);
            TextView nm = new TextView(this);
            nm.setText(pr.name);
            nm.setTextColor(0xFFFFFFFF);
            nm.setTextSize(13f);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            meta.addView(nm);
            TextView sub = new TextView(this);
            sub.setText(pr.frames.size() + " frames · "
                    + (pr.fps > 0 ? (int) pr.fps : (int) sheet.getFps()) + " fps · " + pr.type);
            sub.setTextColor(SpriteTheme.DIMMER);
            sub.setTextSize(10f);
            meta.addView(sub);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mLp.leftMargin = (int) (8 * d);
            row.addView(meta, mLp);

            TextView load = chip("⤒");
            load.setOnClickListener(v -> {
                labSeq.clear();
                for (int k = 0; k < pr.frames.size(); k++) {
                    labSeq.add(new int[]{pr.frames.get(k),
                            SequenceTiming.weightAt(pr.weights, k)});
                }
                labCur = 0;
                rebuildFilm();
                showSection("play");
            });
            row.addView(load);

            TextView edit = chip("✎");
            edit.setOnClickListener(v -> editPreset(pr));
            row.addView(edit);

            TextView del = chip("✕");
            del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setTitle("Delete “" + pr.name + "”?")
                    .setPositiveButton("Delete", (dl, w) -> {
                        sheet.getPresets().remove(pr); markDirty(); showSection("clips");
                    })
                    .setNegativeButton("Cancel", null).show());
            row.addView(del);

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = (int) (6 * d);
            benchBody.addView(row, rLp);
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
        View g = group("exp", SpriteTheme.ACCENT_OUT, "⭱", "Export",
                "sheet + .sprite.json");
        FlowLayout b = bodyOf(g);
        TextView ex = gchip("⭱ Write .sprite.json", true, SpriteTheme.ACCENT_OUT);
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
        filmRow.removeAllViews();
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
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setBackgroundColor(i == labCur ? SpriteTheme.LIVE : SpriteTheme.CONTROL);
            int cp = (int) (2 * d);
            box.setPadding(cp, cp, cp, cp);

            PresetThumb th = new PresetThumb(this, renderer, null);
            th.setStill(f[0]);
            box.addView(th, new LinearLayout.LayoutParams((int) (44 * d), (int) (44 * d)));

            TextView lb = new TextView(this);
            String nm = sheet.cellName(f[0]);
            lb.setText((nm != null && !nm.isEmpty() ? nm : "c" + f[0])
                    + (f[1] > 1 ? " ×" + f[1] : ""));
            lb.setTextColor(i == labCur ? 0xFF3B0322 : SpriteTheme.DIMMER);
            lb.setTextSize(8.5f);
            lb.setMaxLines(1);
            box.addView(lb);

            box.setOnClickListener(v -> {
                labCur = idx;
                preview.setCursor(labSeq.get(idx)[0]);
                gridView.setPlayingCell(labSeq.get(idx)[0]);
                rebuildFilm();
                if (scrubBar != null) scrubBar.invalidate();
            });
            box.setOnLongClickListener(v -> {
                labSeq.remove(idx);
                if (labCur >= labSeq.size()) labCur = Math.max(0, labSeq.size() - 1);
                rebuildFilm();
                if (scrubBar != null) scrubBar.invalidate();
                return true;
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.rightMargin = (int) (4 * d);
            strip.frames().addView(box, bl);
        }
        filmRow.addView(strip);
        if (scrubBar != null) scrubBar.invalidate();
    }

    /**
     * A float stepper, for the alignment numbers. The integer {@link #addStepper} could not
     * carry a scale of 1.15 or a rotation of 7.5 degrees.
     */
    private void addFloatStepper(@NonNull ViewGroup parent, @NonNull String label,
                                 @NonNull FloatGet get, @NonNull FloatSet set, float step) {
        LinearLayout box = stepperShell(parent, label);
        TextView minus = stepBtn("−");
        TextView value = stepValue();
        TextView plus = stepBtn("+");
        Runnable sync = () -> value.setText(step >= 1f
                ? String.valueOf(Math.round(get.get()))
                : String.format(java.util.Locale.US, "%.2f", get.get()));
        minus.setOnClickListener(v -> { set.set(get.get() - step); sync.run(); });
        plus.setOnClickListener(v -> { set.set(get.get() + step); sync.run(); });
        box.addView(minus); box.addView(value); box.addView(plus);
        sync.run();
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

    private interface IntGet { int get(); }
    private interface IntDelta { void apply(int delta); }
    private final java.util.List<Runnable> stepperSyncs = new java.util.ArrayList<>();

    private void addStepper(@NonNull ViewGroup parent, @NonNull String label,
                            @NonNull IntGet get, @NonNull IntDelta apply) {
        LinearLayout box = stepperShell(parent, label);
        TextView minus = stepBtn("−");
        TextView value = stepValue();
        TextView plus = stepBtn("+");
        Runnable sync = () -> value.setText(String.valueOf(get.get()));
        stepperSyncs.add(sync);
        minus.setOnClickListener(v -> { apply.apply(-1); gridChanged(); });
        plus.setOnClickListener(v -> { apply.apply(+1); gridChanged(); });
        box.addView(minus);
        box.addView(value);
        box.addView(plus);
        sync.run();
    }

    private LinearLayout stepperShell(@NonNull ViewGroup parent, @NonNull String label) {
        float d = density();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (10 * d);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(11f);
        title.setPadding(0, 0, (int) (4 * d), 0);
        box.addView(title);
        parent.addView(box, lp);
        return box;
    }

    private TextView stepBtn(@NonNull String glyph) {
        float d = density();
        TextView b = chip(glyph);
        b.setMinWidth((int) (34 * d));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private TextView stepValue() {
        float d = density();
        TextView v = new TextView(this);
        v.setTextColor(Color.WHITE);
        v.setMinWidth((int) (30 * d));
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private TextView chip(@NonNull String text) {
        float d = density();
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(0xFF26262E);
        t.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        return t;
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
        if (fpsValue != null) fpsValue.setText(String.valueOf(sheet.getFps()));
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

    /** Tolerance only affects anything once a key color is set; still adjustable
     *  beforehand so the eyedropper pick applies the pre-set value immediately. */
    private void adjustTolerance(float delta, @NonNull Runnable sync) {
        float next = Math.max(0f, Math.min(0.5f, sheet.getKeyTolerance() + delta));
        sheet.setBgKey(sheet.getBgKeyColor(), next);
        sync.run();
        if (sheet.getBgKeyColor() != 0) reloadRenderer();
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
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!playing || sheet == null) return;
                cursor = nextEnabled(cursor + 1);
                invalidate();
                // S2b filmstrip polish: mirror the playing cell on the main grid so
                // the run cycle is visible at full size, not just in the tiny preview.
                if (activity != null) activity.gridView.setPlayingCell(cursor);
                postDelayed(this, (long) (1000f / Math.max(0.5f, sheet.getFps())));
            }
        };

        CellCyclePreview(Context ctx) {
            super(ctx);
            setBackgroundColor(0xFF1B1B22);
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
            dest.set(2, 2, getWidth() - 2, getHeight() - 2);
            int showCell = Math.min(cursor, sheet.cellCount() - 1);
            // S2b onion skin: ghost the previous AND next enabled cell (by index) at
            // ~30% alpha under/over the selected cell — the classic run-cycle flow check.
            if (activity != null && activity.onionMode && showCell >= 0) {
                onionPaint.setAlpha(77); // ~30%
                int prev = previousEnabled(showCell);
                if (prev >= 0 && prev != showCell) renderer.drawCell(canvas, prev, dest, onionPaint);
                int next = nextEnabled(showCell + 1);
                if (next != showCell && next != prev) renderer.drawCell(canvas, next, dest, onionPaint);
            }
            renderer.drawCell(canvas, showCell, dest, null);
        }
    }
}
