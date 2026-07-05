package com.fadcam.ui.faditor.sprite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
    private TextView keyBtn;
    private TextView playBtn;
    private CellCyclePreview preview;
    private TextView fpsValue;
    private final float density() { return getResources().getDisplayMetrics().density; }

    private final ActivityResultLauncher<String[]> imagePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) { finish(); return; }
                importSheetImage(uri);
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

    @SuppressLint("SetTextI18n")
    private void buildUi() {
        float d = density();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101014);

        // Top bar: back, name, save
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (10 * d);
        top.setPadding(pad, pad, pad, pad);
        TextView back = chip("←");
        back.setOnClickListener(v -> onBackPressed());
        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setTextColor(Color.WHITE);
        nameField.setHint(R.string.sprite_editor_name_hint);
        nameField.setHintTextColor(0x66FFFFFF);
        LinearLayout.LayoutParams nameLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = nameLp.rightMargin = (int) (8 * d);
        TextView saveBtn = chip(getString(R.string.sprite_editor_save));
        saveBtn.setOnClickListener(v -> save());
        top.addView(back);
        top.addView(nameField, nameLp);
        top.addView(saveBtn);
        root.addView(top);

        // Grid canvas
        gridView = new SpriteGridEditorView(this);
        root.addView(gridView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        gridView.setListener(new SpriteGridEditorView.Listener() {
            @Override public void onCellTapped(int index) { onCellSelected(index); }
            @Override public void onPivotChanged(float px, float py) { /* live-drawn */ }
            @Override public void onColorPicked(int argb) { applyBgKey(argb); }
        });

        // Cell panel (selected-cell name + enabled)
        cellPanel = new LinearLayout(this);
        cellPanel.setOrientation(LinearLayout.HORIZONTAL);
        cellPanel.setGravity(Gravity.CENTER_VERTICAL);
        cellPanel.setPadding(pad, (int) (6 * d), pad, (int) (6 * d));
        cellPanel.setBackgroundColor(0xFF1B1B22);
        cellTitle = new TextView(this);
        cellTitle.setTextColor(0xFFFFD54F);
        cellTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cellNameField = new EditText(this);
        cellNameField.setSingleLine(true);
        cellNameField.setTextColor(Color.WHITE);
        cellNameField.setHint(R.string.sprite_editor_cell_name_hint);
        cellNameField.setHintTextColor(0x66FFFFFF);
        LinearLayout.LayoutParams cnLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cnLp.leftMargin = (int) (10 * d);
        cellNameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int idx = gridView.getSelectedCell();
                if (idx < 0) return;
                SpriteSheet.Cell meta = ensureCell(idx);
                meta.name = s.toString().trim();
                gridView.refresh();
            }
        });
        cellEnabled = new Switch(this);
        cellEnabled.setText(R.string.sprite_editor_cell_enabled);
        cellEnabled.setTextColor(Color.WHITE);
        cellEnabled.setOnCheckedChangeListener((btn, checked) -> {
            int idx = gridView.getSelectedCell();
            if (idx < 0) return;
            ensureCell(idx).enabled = checked;
            gridView.refresh();
        });
        cellPanel.addView(cellTitle);
        cellPanel.addView(cellNameField, cnLp);
        cellPanel.addView(cellEnabled);
        root.addView(cellPanel);

        // Controls strip: steppers + toggles + live preview
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(pad, (int) (6 * d), pad, (int) (10 * d));

        preview = new CellCyclePreview(this);
        int pv = (int) (52 * d);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(pv, pv);
        pvLp.rightMargin = (int) (10 * d);
        controls.addView(preview, pvLp);

        playBtn = chip(getString(R.string.sprite_editor_play));
        playBtn.setOnClickListener(v -> {
            boolean now = !preview.isPlaying();
            preview.setPlaying(now);
            playBtn.setText(now ? getString(R.string.sprite_editor_stop)
                    : getString(R.string.sprite_editor_play));
        });
        controls.addView(playBtn);

        pivotBtn = chip(getString(R.string.sprite_editor_pivot));
        pivotBtn.setOnClickListener(v -> {
            gridView.setPivotMode(!gridView.isPivotMode());
            pivotBtn.setBackgroundColor(gridView.isPivotMode() ? 0xFF4A3B5C : 0xFF26262E);
        });
        controls.addView(pivotBtn);

        // S2b: algorithmic grid auto-detect (gutter scan).
        TextView autoBtn = chip(getString(R.string.sprite_editor_auto));
        autoBtn.setOnClickListener(v -> autoDetectGrid());
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp.leftMargin = (int) (8 * d);
        controls.addView(autoBtn, autoLp);

        // S2b: background color-key (engine applies it once at decode; this is the UI).
        keyBtn = chip(getString(R.string.sprite_editor_key));
        keyBtn.setOnClickListener(v -> onKeyChipTapped());
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keyLp.leftMargin = (int) (8 * d);
        controls.addView(keyBtn, keyLp);
        syncKeyChip();

        // S2b: standalone sidecar export (<image>.sprite.json — the sharing format).
        TextView sidecarBtn = chip(getString(R.string.sprite_editor_export_sidecar));
        sidecarBtn.setOnClickListener(v -> exportSidecar());
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.leftMargin = (int) (8 * d);
        controls.addView(sidecarBtn, scLp);

        addStepper(controls, "Cols", () -> sheet.getCols(),
                delta -> sheet.setGrid(sheet.getCols() + delta, sheet.getRows()));
        addStepper(controls, "Rows", () -> sheet.getRows(),
                delta -> sheet.setGrid(sheet.getCols(), sheet.getRows() + delta));
        addStepper(controls, "MgX", () -> sheet.getMarginX(),
                delta -> sheet.setMargins(sheet.getMarginX() + delta * 2, sheet.getMarginY()));
        addStepper(controls, "MgY", () -> sheet.getMarginY(),
                delta -> sheet.setMargins(sheet.getMarginX(), sheet.getMarginY() + delta * 2));
        addStepper(controls, "SpX", () -> sheet.getSpacingX(),
                delta -> sheet.setSpacing(sheet.getSpacingX() + delta * 2, sheet.getSpacingY()));
        addStepper(controls, "SpY", () -> sheet.getSpacingY(),
                delta -> sheet.setSpacing(sheet.getSpacingX(), sheet.getSpacingY() + delta * 2));

        // fps stepper (float, 0.5 steps)
        LinearLayout fpsBox = stepperShell(controls, "FPS");
        TextView minus = stepBtn("−");
        fpsValue = stepValue();
        TextView plus = stepBtn("+");
        minus.setOnClickListener(v -> { sheet.setFps(sheet.getFps() - 0.5f); syncControls(); });
        plus.setOnClickListener(v -> { sheet.setFps(sheet.getFps() + 0.5f); syncControls(); });
        fpsBox.addView(minus);
        fpsBox.addView(fpsValue);
        fpsBox.addView(plus);

        scroll.addView(controls);
        root.addView(scroll);
        setContentView(root);
    }

    private interface IntGet { int get(); }
    private interface IntDelta { void apply(int delta); }
    private final java.util.List<Runnable> stepperSyncs = new java.util.ArrayList<>();

    private void addStepper(@NonNull LinearLayout parent, @NonNull String label,
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

    private LinearLayout stepperShell(@NonNull LinearLayout parent, @NonNull String label) {
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
        fpsValue.setText(String.valueOf(sheet.getFps()));
        gridView.refresh();
        preview.invalidate();
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

    private void onCellSelected(int index) {
        if (index < 0) { cellPanel.setVisibility(View.GONE); return; }
        cellPanel.setVisibility(View.VISIBLE);
        cellTitle.setText(getString(R.string.sprite_editor_cell_prefix) + " " + index);
        SpriteSheet.Cell meta = sheet.cellAt(index);
        cellNameField.setText(meta != null ? meta.name : "");
        cellEnabled.setChecked(meta == null || meta.enabled);
        syncControls();
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
        // Opaque key color; 8% tolerance is a sane default for flat-color bgs.
        sheet.setBgKey(0xFF000000 | (argb & 0x00FFFFFF), 0.08f);
        reloadRenderer(); // one-time color→alpha at decode (preview==export pixels)
        syncKeyChip();
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
            Toast.makeText(this, R.string.sprite_editor_sidecar_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.sprite_editor_sidecar_failed, Toast.LENGTH_LONG).show();
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
        private boolean playing = false;
        private int cursor = 0;
        private final RectF dest = new RectF();
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!playing || sheet == null) return;
                cursor = nextEnabled(cursor + 1);
                invalidate();
                postDelayed(this, (long) (1000f / Math.max(0.5f, sheet.getFps())));
            }
        };

        CellCyclePreview(Context ctx) {
            super(ctx);
            setBackgroundColor(0xFF1B1B22);
        }

        void bind(@Nullable SpriteSheet s, @Nullable SpriteSheetRenderer r) {
            this.sheet = s;
            this.renderer = r;
            cursor = 0;
            invalidate();
        }

        boolean isPlaying() { return playing; }

        void setPlaying(boolean p) {
            playing = p;
            removeCallbacks(tick);
            if (p) post(tick);
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

        @Override
        protected void onDraw(Canvas canvas) {
            if (renderer == null || sheet == null) return;
            dest.set(2, 2, getWidth() - 2, getHeight() - 2);
            renderer.drawCell(canvas, Math.min(cursor, sheet.cellCount() - 1), dest, null);
        }
    }
}
