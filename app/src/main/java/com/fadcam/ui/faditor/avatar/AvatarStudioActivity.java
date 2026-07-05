package com.fadcam.ui.faditor.avatar;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.R;
import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

import java.util.HashMap;
import java.util.Map;

/**
 * A1-UI (PLAN_AVATAR_STUDIO): the Avatar Studio MATRIX EDITOR scaffold —
 * scrub/slider-driven, zero ML. Proves the whole pose-matrix model on-device:
 * ARM a cell → arrange parts (drag on the puppet canvas, steppers for
 * scale/rot/sprite-cell, flips) → the cell auto-snapshots; DISARM → the yaw/
 * pitch sliders puppet the rig live through {@link PuppetPoseResolver}
 * (bilinear blend + empty-cell inheritance + per-part discrete hysteresis —
 * the exact runtime path the webcam tracker will drive in A2).
 *
 * <p>Same idiom as {@code SpriteSheetEditorActivity} (S2): programmatic dark
 * UI, load project → mutate → {@link ProjectStorage#save} →
 * {@link AIChatState#signalModified} write-back, autosave on back. Scaffold
 * edits the rig's FIRST pose domain (default: "head" 3×3 yaw×pitch);
 * multi-domain UI (limb strips) arrives with the limb phase.</p>
 */
public class AvatarStudioActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_ID = "avatar_studio_project_id";
    /** Absent/null = create a NEW rig. */
    public static final String EXTRA_RIG_ID = "avatar_studio_rig_id";

    private ProjectStorage storage;
    private FaditorProject project;
    private AvatarRig rig;
    private boolean isNewRig = false;
    private AvatarRig.PoseDomain domain;

    private final Map<String, SpriteSheetRenderer> renderers = new HashMap<>();
    private final PuppetPoseResolver.DiscreteState discreteState =
            new PuppetPoseResolver.DiscreteState();

    private PuppetPreviewView preview;
    private PoseMatrixView matrix;
    private EditText nameField;
    private TextView hintLine;
    private SeekBar yawSlider, pitchSlider;
    private LinearLayout partChipRow;
    private TextView flipHChip, flipVChip, mirrorChip;
    private TextView cellValue, scaleValue, rotValue;

    private int armedCol = -1, armedRow = -1;
    @Nullable private String selectedPartId;
    private int partCounter = 0;

    private float density() { return getResources().getDisplayMetrics().density; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new ProjectStorage(this);
        String projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        project = projectId != null ? storage.load(projectId) : null;
        if (project == null) {
            Toast.makeText(this, R.string.avatar_studio_no_project, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String rigId = getIntent().getStringExtra(EXTRA_RIG_ID);
        rig = project.avatarRigById(rigId);
        if (rig == null) {
            isNewRig = true;
            rig = AvatarRig.create(getString(R.string.avatar_studio_default_name));
        }
        // Scaffold edits the first domain; a fresh rig gets the canonical head grid.
        if (rig.getDomains().isEmpty()) {
            AvatarRig.PoseDomain head = new AvatarRig.PoseDomain("head");
            head.driverX = "yaw";
            head.driverY = "pitch";
            head.cols = 3;
            head.rows = 3;
            rig.getDomains().add(head);
        }
        domain = rig.getDomains().get(0);
        buildUi();
        nameField.setText(rig.getName());
        if (!rig.getParts().isEmpty()) selectedPartId = rig.getParts().get(0).id;
        rebuildPartChips();
        refreshMatrix();
        resolveNow();
        updateHint();
    }

    // ── Resolve pipeline (the ONLY caller of the resolver here) ───────────

    private float sliderValue(@NonNull SeekBar s) {
        return (s.getProgress() / 100f) - 1f; // 0..200 → -1..1
    }

    private void resolveNow() {
        Map<String, Float> params = new HashMap<>();
        params.put(domain.driverX, sliderValue(yawSlider));
        if (domain.driverY != null) params.put(domain.driverY, sliderValue(pitchSlider));
        preview.setResolved(PuppetPoseResolver.resolve(rig, params, discreteState));
        float gx = (sliderValue(yawSlider) + 1f) / 2f * (Math.max(1, domain.cols) - 1);
        float gy = domain.rows > 1
                ? (sliderValue(pitchSlider) + 1f) / 2f * (domain.rows - 1) : 0f;
        matrix.setDriverPoint(gx, gy);
    }

    // ── Arming + cell authoring ────────────────────────────────────────────

    private void onCellTapped(int col, int row) {
        if (col == armedCol && row == armedRow) {
            armedCol = armedRow = -1; // disarm
        } else {
            armedCol = col;
            armedRow = row;
            // Sliders snap to the armed extreme so the canvas SHOWS the cell.
            yawSlider.setProgress(Math.round(
                    (domain.cols > 1 ? (col / (float) (domain.cols - 1)) * 2f - 1f : 0f) * 100f) + 100);
            pitchSlider.setProgress(Math.round(
                    (domain.rows > 1 ? (row / (float) (domain.rows - 1)) * 2f - 1f : 0f) * 100f) + 100);
        }
        boolean armed = armedCol >= 0;
        yawSlider.setEnabled(!armed);
        pitchSlider.setEnabled(!armed);
        preview.setDragEnabled(armed);
        matrix.setArmed(armedCol, armedRow);
        syncPoseControls();
        resolveNow();
        updateHint();
    }

    /** The armed cell's pose for a part — created on first edit, SEEDED from the
     *  current resolved (blended) state so authoring starts from what you see. */
    @Nullable
    private AvatarRig.PartPose ensureArmedPose(@Nullable String partId) {
        if (armedCol < 0 || partId == null) return null;
        AvatarRig.Cell cell = domain.cellAt(armedCol, armedRow);
        if (cell == null) {
            cell = new AvatarRig.Cell();
            cell.col = armedCol;
            cell.row = armedRow;
            domain.cells.add(cell);
            refreshMatrix();
        }
        AvatarRig.PartPose pose = cell.poseFor(partId);
        if (pose == null) {
            pose = new AvatarRig.PartPose(partId);
            Map<String, Float> params = new HashMap<>();
            params.put(domain.driverX, sliderValue(yawSlider));
            if (domain.driverY != null) params.put(domain.driverY, sliderValue(pitchSlider));
            PuppetPoseResolver.PartState cur = PuppetPoseResolver
                    .resolve(rig, params, new PuppetPoseResolver.DiscreteState()).get(partId);
            if (cur != null) {
                pose.x = cur.x;
                pose.y = cur.y;
                pose.scale = cur.scale;
                pose.rotationDeg = cur.rotationDeg;
                pose.cellIndex = cur.cellIndex;
                pose.flipH = cur.flipH;
                pose.flipV = cur.flipV;
            }
            cell.poses.add(pose);
        }
        return pose;
    }

    private void clearArmedCell() {
        if (armedCol < 0) return;
        AvatarRig.Cell cell = domain.cellAt(armedCol, armedRow);
        if (cell != null) {
            domain.cells.remove(cell);
            Toast.makeText(this, R.string.avatar_studio_cell_cleared, Toast.LENGTH_SHORT).show();
        }
        refreshMatrix();
        syncPoseControls();
        resolveNow();
    }

    private void mirrorArmedPose() {
        if (armedCol < 0) return;
        int mirrorCol = (domain.cols - 1) - armedCol;
        if (mirrorCol == armedCol) return; // center column, nothing to mirror
        AvatarRig.Cell src = domain.cellAt(armedCol, armedRow);
        if (src == null || src.poses.isEmpty()) return;
        AvatarRig.Cell dst = domain.cellAt(mirrorCol, armedRow);
        if (dst == null) {
            dst = new AvatarRig.Cell();
            dst.col = mirrorCol;
            dst.row = armedRow;
            domain.cells.add(dst);
        }
        dst.poses.clear();
        for (AvatarRig.PartPose p : src.poses) {
            AvatarRig.PartPose mp = new AvatarRig.PartPose(p.partId);
            mp.cellIndex = p.cellIndex;
            mp.x = -p.x;
            mp.y = p.y;
            mp.scale = p.scale;
            mp.rotationDeg = -p.rotationDeg;
            mp.flipH = !p.flipH;
            mp.flipV = p.flipV;
            mp.z = p.z;
            for (float[] pin : p.pins) {
                mp.pins.add(new float[]{1f - pin[0], pin[1]});
            }
            dst.poses.add(mp);
        }
        refreshMatrix();
        resolveNow();
        Toast.makeText(this, R.string.avatar_studio_mirrored, Toast.LENGTH_SHORT).show();
    }

    private void refreshMatrix() {
        int n = Math.max(1, domain.cols) * Math.max(1, domain.rows);
        boolean[] authored = new boolean[n];
        for (AvatarRig.Cell c : domain.cells) {
            int idx = c.row * Math.max(1, domain.cols) + c.col;
            if (idx >= 0 && idx < n) authored[idx] = true;
        }
        matrix.setGrid(domain.cols, domain.rows, authored);
    }

    private void updateHint() {
        hintLine.setText(armedCol >= 0
                ? getString(R.string.avatar_studio_armed_hint)
                : getString(R.string.avatar_studio_disarm_hint));
        hintLine.setTextColor(armedCol >= 0 ? 0xFFFFD54F : 0x99FFFFFF);
    }

    // ── Part management ────────────────────────────────────────────────────

    private void addPartFlow() {
        java.util.List<SpriteSheet> sheets = project.getSpriteSheets();
        if (sheets.isEmpty()) {
            Toast.makeText(this, R.string.avatar_studio_no_sheets, Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[sheets.size()];
        for (int i = 0; i < sheets.size(); i++) items[i] = sheets.get(i).getName();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.avatar_studio_pick_sheet)
                .setItems(items, (d, which) -> promptPartName(sheets.get(which).getId()))
                .show();
    }

    private void promptPartName(@NonNull String sheetId) {
        EditText input = new EditText(this);
        input.setHint(R.string.avatar_studio_part_name_hint);
        input.setSingleLine(true);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.avatar_studio_add_part)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String base = input.getText().toString().trim();
                    if (base.isEmpty()) base = "part";
                    String id = base;
                    while (rig.partById(id) != null) id = base + "-" + (++partCounter);
                    AvatarRig.Part part = new AvatarRig.Part(id, sheetId);
                    part.z = rig.getParts().size(); // stack new parts on top
                    rig.getParts().add(part);
                    selectedPartId = id;
                    rebuildPartChips();
                    syncPoseControls();
                    resolveNow();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void partLongPress(@NonNull AvatarRig.Part part) {
        String[] items = {
                getString(R.string.avatar_studio_part_menu_parent),
                getString(R.string.avatar_studio_part_menu_unparent),
                getString(R.string.avatar_studio_part_menu_remove)};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(part.id)
                .setItems(items, (d, which) -> {
                    if (which == 0) pickParent(part);
                    else if (which == 1) { part.parentId = null; resolveNow(); }
                    else removePart(part);
                })
                .show();
    }

    private void pickParent(@NonNull AvatarRig.Part part) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (AvatarRig.Part p : rig.getParts()) {
            if (!p.id.equals(part.id)) candidates.add(p.id);
        }
        if (candidates.isEmpty()) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.avatar_studio_parent_pick_title, part.id))
                .setItems(candidates.toArray(new String[0]), (d, which) -> {
                    // Cycle guard: walking up from the chosen parent must not reach us.
                    String cursor = candidates.get(which);
                    int hops = 0;
                    boolean cycle = false;
                    while (cursor != null && hops++ <= rig.getParts().size()) {
                        if (cursor.equals(part.id)) { cycle = true; break; }
                        AvatarRig.Part cp = rig.partById(cursor);
                        cursor = cp != null ? cp.parentId : null;
                    }
                    if (!cycle) {
                        part.parentId = candidates.get(which);
                        resolveNow();
                    }
                })
                .show();
    }

    private void removePart(@NonNull AvatarRig.Part part) {
        rig.getParts().remove(part);
        // Orphan tolerance: children of the removed part become roots.
        for (AvatarRig.Part p : rig.getParts()) {
            if (part.id.equals(p.parentId)) p.parentId = null;
        }
        if (part.id.equals(selectedPartId)) {
            selectedPartId = rig.getParts().isEmpty() ? null : rig.getParts().get(0).id;
        }
        rebuildPartChips();
        syncPoseControls();
        resolveNow();
    }

    @SuppressLint("SetTextI18n")
    private void rebuildPartChips() {
        partChipRow.removeAllViews();
        float d = density();
        TextView add = chip(getString(R.string.avatar_studio_add_part));
        add.setOnClickListener(v -> addPartFlow());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * d);
        partChipRow.addView(add, lp);
        for (AvatarRig.Part part : rig.getParts()) {
            TextView pc = chip(part.parentId != null ? part.id + " ↳" + part.parentId : part.id);
            if (part.id.equals(selectedPartId)) {
                pc.setBackgroundColor(0xFF4A3B5C);
                pc.setTypeface(Typeface.DEFAULT_BOLD);
            }
            pc.setOnClickListener(v -> {
                selectedPartId = part.id;
                preview.setSelectedPart(part.id);
                rebuildPartChips();
                syncPoseControls();
            });
            pc.setOnLongClickListener(v -> {
                partLongPress(part);
                return true;
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = (int) (6 * d);
            partChipRow.addView(pc, plp);
        }
        preview.setSelectedPart(selectedPartId);
    }

    // ── Pose controls (armed-cell editing) ────────────────────────────────

    /** The armed pose IF it already exists (peek — no auto-create on sync). */
    @Nullable
    private AvatarRig.PartPose armedPosePeek() {
        if (armedCol < 0 || selectedPartId == null) return null;
        AvatarRig.Cell cell = domain.cellAt(armedCol, armedRow);
        return cell != null ? cell.poseFor(selectedPartId) : null;
    }

    @SuppressLint("SetTextI18n")
    private void syncPoseControls() {
        AvatarRig.PartPose pose = armedPosePeek();
        boolean editable = armedCol >= 0 && selectedPartId != null;
        float alpha = editable ? 1f : 0.35f;
        for (View v : new View[]{cellValue, scaleValue, rotValue, flipHChip, flipVChip, mirrorChip}) {
            v.setAlpha(alpha);
        }
        cellValue.setText(String.valueOf(pose != null ? pose.cellIndex : 0));
        scaleValue.setText(String.format(java.util.Locale.US, "%.2f",
                pose != null ? pose.scale : 1f));
        rotValue.setText(String.format(java.util.Locale.US, "%.0f°",
                pose != null ? pose.rotationDeg : 0f));
        flipHChip.setBackgroundColor(pose != null && pose.flipH ? 0xFF4A3B5C : 0xFF26262E);
        flipVChip.setBackgroundColor(pose != null && pose.flipV ? 0xFF4A3B5C : 0xFF26262E);
    }

    private void editArmedPose(@NonNull java.util.function.Consumer<AvatarRig.PartPose> edit) {
        if (armedCol < 0 || selectedPartId == null) return;
        AvatarRig.PartPose pose = ensureArmedPose(selectedPartId);
        if (pose == null) return;
        edit.accept(pose);
        syncPoseControls();
        resolveNow();
    }

    // ── Save / lifecycle ──────────────────────────────────────────────────

    private void save() {
        String name = nameField.getText().toString().trim();
        rig.setName(name.isEmpty() ? getString(R.string.avatar_studio_default_name) : name);
        if (isNewRig && project.avatarRigById(rig.getId()) == null) {
            project.getAvatarRigs().add(rig);
            isNewRig = false;
        }
        boolean ok = storage.save(project);
        if (ok) AIChatState.signalModified(project.getId());
        Toast.makeText(this, ok ? R.string.avatar_studio_saved : R.string.avatar_studio_save_failed,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        save(); // autosave semantics, S2 precedent
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (SpriteSheetRenderer r : renderers.values()) {
            if (r != null) r.recycle();
        }
        renderers.clear();
    }

    @Nullable
    private SpriteSheetRenderer rendererFor(@NonNull String sheetId) {
        if (renderers.containsKey(sheetId)) return renderers.get(sheetId);
        SpriteSheet sheet = project.spriteSheetById(sheetId);
        SpriteSheetRenderer r = sheet != null ? SpriteSheetRenderer.load(this, sheet) : null;
        renderers.put(sheetId, r); // null cached too = MISSING affordance, no retry storm
        return r;
    }

    // ── UI construction (S2 idiom: programmatic, dark) ────────────────────

    @SuppressLint("SetTextI18n")
    private void buildUi() {
        float d = density();
        int pad = (int) (10 * d);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101014);

        // Top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(pad, pad, pad, pad);
        TextView back = chip("←");
        back.setOnClickListener(v -> onBackPressed());
        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setTextColor(Color.WHITE);
        nameField.setHint(R.string.avatar_studio_name_hint);
        nameField.setHintTextColor(0x66FFFFFF);
        LinearLayout.LayoutParams nameLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = nameLp.rightMargin = (int) (8 * d);
        TextView saveBtn = chip(getString(R.string.avatar_studio_save));
        saveBtn.setOnClickListener(v -> save());
        top.addView(back);
        top.addView(nameField, nameLp);
        top.addView(saveBtn);
        root.addView(top);

        // Puppet canvas
        preview = new PuppetPreviewView(this);
        preview.bind(rig, this::rendererFor);
        preview.setSheetLookup(id -> project.spriteSheetById(id));
        preview.setListener((dxNorm, dyNorm) -> editArmedPose(pose -> {
            pose.x += dxNorm;
            pose.y += dyNorm;
        }));
        root.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Hint line
        hintLine = new TextView(this);
        hintLine.setTextSize(12f);
        hintLine.setGravity(Gravity.CENTER);
        hintLine.setPadding(pad, (int) (4 * d), pad, (int) (4 * d));
        root.addView(hintLine);

        // Matrix + sliders row
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        mid.setPadding(pad, 0, pad, 0);
        matrix = new PoseMatrixView(this);
        matrix.setListener(this::onCellTapped);
        int matrixSize = (int) (132 * d);
        mid.addView(matrix, new LinearLayout.LayoutParams(matrixSize, matrixSize));

        LinearLayout sliderCol = new LinearLayout(this);
        sliderCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams scLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        scLp.leftMargin = (int) (10 * d);
        yawSlider = labeledSlider(sliderCol, getString(R.string.avatar_studio_yaw));
        pitchSlider = labeledSlider(sliderCol, getString(R.string.avatar_studio_pitch));
        mid.addView(sliderCol, scLp);
        root.addView(mid);

        // Part chips
        HorizontalScrollView partScroll = new HorizontalScrollView(this);
        partScroll.setHorizontalScrollBarEnabled(false);
        partChipRow = new LinearLayout(this);
        partChipRow.setOrientation(LinearLayout.HORIZONTAL);
        partChipRow.setPadding(pad, (int) (8 * d), pad, (int) (4 * d));
        partScroll.addView(partChipRow);
        root.addView(partScroll);

        // Pose controls strip
        HorizontalScrollView ctrlScroll = new HorizontalScrollView(this);
        ctrlScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(pad, (int) (4 * d), pad, pad);

        cellValue = addStepper(controls, getString(R.string.avatar_studio_cell_stepper),
                delta -> editArmedPose(p -> p.cellIndex = Math.max(0, p.cellIndex + delta)));
        scaleValue = addStepper(controls, getString(R.string.avatar_studio_scale),
                delta -> editArmedPose(p -> p.scale =
                        Math.max(0.05f, Math.min(5f, p.scale + delta * 0.05f))));
        rotValue = addStepper(controls, getString(R.string.avatar_studio_rotation),
                delta -> editArmedPose(p -> p.rotationDeg += delta * 5f));

        flipHChip = chip(getString(R.string.avatar_studio_flip_h));
        flipHChip.setOnClickListener(v -> editArmedPose(p -> p.flipH = !p.flipH));
        controls.addView(flipHChip, chipLp());
        flipVChip = chip(getString(R.string.avatar_studio_flip_v));
        flipVChip.setOnClickListener(v -> editArmedPose(p -> p.flipV = !p.flipV));
        controls.addView(flipVChip, chipLp());

        mirrorChip = chip(getString(R.string.avatar_studio_mirror));
        mirrorChip.setOnClickListener(v -> mirrorArmedPose());
        controls.addView(mirrorChip, chipLp());

        TextView clearCell = chip(getString(R.string.avatar_studio_clear_cell));
        clearCell.setOnClickListener(v -> clearArmedCell());
        controls.addView(clearCell, chipLp());

        ctrlScroll.addView(controls);
        root.addView(ctrlScroll);
        setContentView(root);
        syncPoseControls();
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (8 * density());
        return lp;
    }

    private SeekBar labeledSlider(@NonNull LinearLayout parent, @NonNull String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(11f);
        title.setMinWidth((int) (38 * density()));
        SeekBar bar = new SeekBar(this);
        bar.setMax(200);
        bar.setProgress(100);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) resolveNow();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams barLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title);
        row.addView(bar, barLp);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private interface IntDelta { void apply(int delta); }

    /** −/value/+ stepper, S2 pattern; returns the value TextView for syncs. */
    private TextView addStepper(@NonNull LinearLayout parent, @NonNull String label,
                                @NonNull IntDelta apply) {
        float d = density();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (8 * d);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(11f);
        title.setPadding(0, 0, (int) (4 * d), 0);
        box.addView(title);
        TextView minus = chip("−");
        minus.setMinWidth((int) (34 * d));
        minus.setGravity(Gravity.CENTER);
        minus.setOnClickListener(v -> apply.apply(-1));
        TextView value = new TextView(this);
        value.setTextColor(Color.WHITE);
        value.setMinWidth((int) (40 * d));
        value.setGravity(Gravity.CENTER);
        TextView plus = chip("+");
        plus.setMinWidth((int) (34 * d));
        plus.setGravity(Gravity.CENTER);
        plus.setOnClickListener(v -> apply.apply(+1));
        box.addView(minus);
        box.addView(value);
        box.addView(plus);
        parent.addView(box, lp);
        return value;
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
}
