package com.fadcam.ui.faditor.avatar;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
    /** Standalone deep-link: open this library bundle dir (name under
     *  files/avatar_library). Only read when EXTRA_PROJECT_ID is absent. */
    public static final String EXTRA_LIBRARY_DIR = "avatar_studio_library_dir";

    private ProjectStorage storage;
    private FaditorProject project;
    private AvatarRig rig;

    // ── Standalone (library-backed) mode — JoyRaptor 2026-07-11: Avatar Studio is
    // reachable from the main menu with NO project; it then edits cross-project
    // AvatarLibrary bundles directly (list → open → edit → save-over). Every
    // project touchpoint routes through sheetList()/lookupSheet()/save() so
    // project mode stays behaviorally unchanged.
    private boolean libraryMode;
    @Nullable private AvatarLibrary.Entry libraryEntry;
    /** New-avatar flow: system image picker → seed bundle. Registered in
     *  onCreate unconditionally (ActivityResult contract requirement). */
    private ActivityResultLauncher<String[]> newAvatarImagePicker;
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
    /** A6 pin authoring mode: disarmed = edit the REST chain, armed = pose pins. */
    private boolean pinMode;
    private TextView pinsChip, pinDelChip, densityValue;
    private int partCounter = 0;

    /** A2: mounted while the synthetic tracking demo is live, else null. */
    @Nullable private TrackingDriverBus trackingBus;
    private TextView trackChip;
    /** D4: runtime CAMERA request code for the MediaPipe face-tracking swap. */
    private static final int RC_FACE_CAMERA = 4021;

    private float density() { return getResources().getDisplayMetrics().density; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new ProjectStorage(this);
        newAvatarImagePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onNewAvatarImage);

        String projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        if (projectId == null) {
            // Standalone: main-menu launch. Deep-linked entry or the chooser.
            libraryMode = true;
            String dirName = getIntent().getStringExtra(EXTRA_LIBRARY_DIR);
            if (dirName != null) {
                AvatarLibrary.Entry e = AvatarLibrary.load(
                        new java.io.File(AvatarLibrary.libraryDir(this), dirName));
                if (e == null) {
                    Toast.makeText(this, "Library avatar missing", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                openLibraryEntry(e);
            } else {
                showLibraryChooser();
            }
            return;
        }

        project = storage.load(projectId);
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
        initEditor();
    }

    /** Shared editor bring-up once {@link #rig} is bound (either mode). */
    private void initEditor() {
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
        // buildUi's syncPoseControls ran before selectedPartId was assigned, so
        // the per-part Mesh density label was stale ("24"); refresh now.
        syncPoseControls();
    }

    // ── Standalone library mode ──────────────────────────────────────────

    private void openLibraryEntry(@NonNull AvatarLibrary.Entry e) {
        libraryEntry = e;
        rig = e.rig;
        initEditor();
    }

    /** Bare launch: pick a library avatar or seed a new one from an image. */
    private void showLibraryChooser() {
        java.util.List<AvatarLibrary.Entry> entries = AvatarLibrary.list(this);
        String[] items = new String[entries.size() + 1];
        for (int i = 0; i < entries.size(); i++) items[i] = entries.get(i).rig.getName();
        items[entries.size()] = "+ New avatar from image…";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Avatar Studio — library")
                .setItems(items, (d, which) -> {
                    if (which == entries.size()) {
                        newAvatarImagePicker.launch(new String[]{"image/*"});
                    } else {
                        openLibraryEntry(entries.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    /**
     * New-avatar seed: the picked image becomes a 1-cell sheet + a one-part rig,
     * saved to the library IMMEDIATELY (bytes are copied out of the content uri
     * while we still hold the grant — no persistable permission needed), then
     * re-loaded so the editor works on stable bundle-file uris.
     */
    private void onNewAvatarImage(@Nullable Uri uri) {
        if (uri == null) { // picker dismissed
            if (rig == null) finish(); // nothing open behind the chooser
            return;
        }
        SpriteSheet sheet = SpriteSheet.create("sheet", uri.toString());
        AvatarRig newRig = AvatarRig.create(getString(R.string.avatar_studio_default_name));
        AvatarRig.Part part = new AvatarRig.Part("body", sheet.getId());
        newRig.getParts().add(part);
        java.io.File dir = AvatarLibrary.save(this, newRig,
                java.util.Collections.singletonList(sheet));
        AvatarLibrary.Entry e = dir != null ? AvatarLibrary.load(dir) : null;
        if (e == null) {
            Toast.makeText(this, "Couldn't create the avatar bundle", Toast.LENGTH_LONG).show();
            if (rig == null) finish();
            return;
        }
        openLibraryEntry(e);
    }

    /** The sheets this editor session can reference (mode seam). */
    private java.util.List<SpriteSheet> sheetList() {
        return libraryMode
                ? (libraryEntry != null ? libraryEntry.sheets : java.util.Collections.emptyList())
                : project.getSpriteSheets();
    }

    /** Sheet lookup across both modes (mode seam). */
    @Nullable
    private SpriteSheet lookupSheet(@NonNull String sheetId) {
        if (!libraryMode) return project.spriteSheetById(sheetId);
        if (libraryEntry != null) {
            for (SpriteSheet s : libraryEntry.sheets) {
                if (s.getId().equals(sheetId)) return s;
            }
        }
        return null;
    }

    // ── Resolve pipeline (the ONLY caller of the resolver here) ───────────

    private float sliderValue(@NonNull SeekBar s) {
        return (s.getProgress() / 100f) - 1f; // 0..200 → -1..1
    }

    private void resolveNow() {
        Map<String, Float> params = new HashMap<>();
        params.put(domain.driverX, sliderValue(yawSlider));
        if (domain.driverY != null) params.put(domain.driverY, sliderValue(pitchSlider));
        resolveWith(params);
    }

    /** Resolve + matrix-marker update from ANY param source (sliders or the
     *  A2 tracking bus — the same map either way, single-authority rule). */
    private void resolveWith(@NonNull Map<String, Float> params) {
        preview.setResolved(PuppetPoseResolver.resolve(rig, params, discreteState));
        Float xv = params.get(domain.driverX);
        float dx = xv != null ? Math.max(-1f, Math.min(1f, xv)) : 0f;
        Float yv = domain.driverY != null ? params.get(domain.driverY) : null;
        float dy = yv != null ? Math.max(-1f, Math.min(1f, yv)) : 0f;
        float gx = (dx + 1f) / 2f * (Math.max(1, domain.cols) - 1);
        float gy = domain.rows > 1 ? (dy + 1f) / 2f * (domain.rows - 1) : 0f;
        matrix.setDriverPoint(gx, gy);
    }

    // ── A2 tracking (synthetic source until the MediaPipe dep is approved) ──

    /** vsync-paced pull loop: bus snapshot → resolver → view (tracker pushes
     *  on its own thread; render pulls at its own rate — plan decoupling). */
    private final Runnable trackTick = new Runnable() {
        @Override public void run() {
            if (trackingBus == null) return;
            Map<String, Float> p = trackingBus.latest();
            if (p != null) {
                resolveWith(p);
                preview.setTrackedPinTargets(TrackingDriverBus.extractPinTargets(p));
            }
            preview.postOnAnimation(this);
        }
    };

    private void startTracking() {
        if (trackingBus != null) return;
        if (armedCol >= 0) onCellTapped(armedCol, armedRow); // disarm first
        // Every warpable part gets an IK orbit — including dangle parts, so
        // the tracking-outranks-physics rule is exercised on device.
        java.util.List<String> ikParts = new java.util.ArrayList<>();
        for (AvatarRig.Part part : rig.getParts()) {
            if (part.restPins.size() >= 2) ikParts.add(part.id);
        }
        trackingBus = new TrackingDriverBus();
        // D4 source swap: real MediaPipe face tracking when the camera is granted
        // AND the model asset is present; otherwise the synthetic source (the
        // permanent fallback the whole pipeline was proven on).
        boolean camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean modelPresent = MediaPipeTrackingSource.isModelPresent(this);
        boolean face = camGranted && modelPresent;
        if (!camGranted) {
            // Ask now; this session falls back to synthetic, next tap uses the camera.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, RC_FACE_CAMERA);
            Toast.makeText(this, "Grant camera, then tap Track again for face tracking",
                    Toast.LENGTH_SHORT).show();
        } else if (!modelPresent) {
            Toast.makeText(this, "Face model missing — using synthetic tracking",
                    Toast.LENGTH_SHORT).show();
        }
        TrackingSource source = face
                ? new MediaPipeTrackingSource(this, () -> {
                    if (trackingBus != null) trackingBus.requestReset();
                })
                : new SyntheticTrackingSource(ikParts);
        trackingBus.start(source, 20260706L);
        trackChip.setBackgroundColor(0xFF1B4A3B);
        yawSlider.setEnabled(false);
        pitchSlider.setEnabled(false);
        preview.postOnAnimation(trackTick);
        hintLine.setText(face
                ? "Tracking (face): move your head — the rig follows"
                : "Tracking (synthetic): driver bus is puppeting the rig");
        hintLine.setTextColor(0xFF64FFDA);
    }

    private void stopTracking() {
        if (trackingBus == null) return;
        trackingBus.stop();
        trackingBus = null;
        preview.removeCallbacks(trackTick);
        preview.setTrackedPinTargets(null);
        trackChip.setBackgroundColor(0xFF26262E);
        boolean armed = armedCol >= 0;
        yawSlider.setEnabled(!armed);
        pitchSlider.setEnabled(!armed);
        resolveNow();
        updateHint();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTracking(); // tracker thread must not outlive the visible studio
    }

    // ── Arming + cell authoring ────────────────────────────────────────────

    private void onCellTapped(int col, int row) {
        stopTracking(); // authoring wins — arming while tracked would fight the bus
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
        // A6: arming while pin mode is on re-targets pin editing to the armed
        // cell's pose (explicit user action → creation allowed).
        if (pinMode) syncPinEditing(true);
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
                // A6: the blended warp pins are part of "what you see" — snapshot
                // them too, so arming then posing pins starts from the blend.
                for (float[] pin : cur.pins) {
                    pose.pins.add(new float[]{pin[0], pin[1]});
                }
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
        java.util.List<SpriteSheet> sheets = sheetList();
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
        // A6 density reflects the SELECTED part (a rig property, not the armed
        // cell): enabled whenever the part actually warps (has a rest chain).
        if (densityValue != null) {
            AvatarRig.Part sel = selectedPartId != null ? rig.partById(selectedPartId) : null;
            boolean warps = sel != null && sel.restPins.size() >= 2;
            densityValue.setAlpha(warps ? 1f : 0.35f);
            densityValue.setText(sel == null || sel.warpSegments == 0
                    ? "24" : String.valueOf(sel.warpSegments));
        }
        // A6: keep pin editing bound to the current part/armed target — PEEK
        // only (never creates a pose from a passive sync).
        if (pinsChip != null) syncPinEditing(false);
    }

    private void editArmedPose(@NonNull java.util.function.Consumer<AvatarRig.PartPose> edit) {
        if (armedCol < 0 || selectedPartId == null) return;
        AvatarRig.PartPose pose = ensureArmedPose(selectedPartId);
        if (pose == null) return;
        edit.accept(pose);
        syncPoseControls();
        resolveNow();
    }

    // ── A6 pin authoring ──────────────────────────────────────────────────

    /**
     * Re-binds the preview's pin-edit surface to the current target: the selected
     * part's REST chain while disarmed, or the ARMED cell's posed pins (created +
     * seeded on entry — explicit user action, unlike the peek-only sync rule).
     * Rest edits keep the {@link PinWarpStrip} top→bottom convention: adds insert
     * sorted by y, moves clamp y between neighbors, and any COUNT change re-seeds
     * every cell pose's pins for that part (a count mismatch would silently drop
     * the whole warp to rigid — worse than losing per-cell pin tweaks).
     */
    private void syncPinEditing(boolean allowCreate) {
        AvatarRig.Part part = selectedPartId != null ? rig.partById(selectedPartId) : null;
        if (!pinMode || part == null) {
            pinMode = false;
            preview.setPinEditing(null, null);
            pinsChip.setBackgroundColor(0xFF26262E);
            pinDelChip.setAlpha(0.35f);
            updateHint();
            return;
        }
        pinsChip.setBackgroundColor(0xFF1B4A3B);
        java.util.List<float[]> target;
        boolean editingRest = armedCol < 0;
        if (editingRest) {
            target = part.restPins;
        } else {
            // Peek on passive syncs — creating a pose here would resurrect a
            // just-cleared cell (the peek-only sync rule). Creation happens only
            // on the explicit chip toggle / cell arm (allowCreate).
            AvatarRig.PartPose pose = allowCreate ? ensureArmedPose(part.id) : armedPosePeek();
            if (pose == null) {
                preview.setPinEditing(null, null);
                pinDelChip.setAlpha(0.35f);
                return;
            }
            if (pose.pins.size() != part.restPins.size()) {
                if (!allowCreate) { preview.setPinEditing(null, null); return; }
                pose.pins.clear();
                for (float[] pin : part.restPins) {
                    pose.pins.add(new float[]{pin[0], pin[1]});
                }
            }
            target = pose.pins;
        }
        pinDelChip.setAlpha(editingRest && !part.restPins.isEmpty() ? 1f : 0.35f);
        final AvatarRig.Part fPart = part;
        final boolean fRest = editingRest;
        preview.setPinEditing(target, new PuppetPreviewView.PinEditListener() {
            @Override
            public void onPinAdd(float cellX, float cellY) {
                if (!fRest) {
                    Toast.makeText(AvatarStudioActivity.this,
                            R.string.avatar_studio_pin_add_disarmed, Toast.LENGTH_SHORT).show();
                    return;
                }
                // Insert keeping y ascending (the warp convention).
                int at = 0;
                while (at < fPart.restPins.size()
                        && fPart.restPins.get(at)[1] < cellY) at++;
                fPart.restPins.add(at, new float[]{cellX, cellY});
                reseedCellPins(fPart);
                syncPinEditing(false); // re-bind (list identity + delete-chip state)
                resolveNow();
            }

            @Override
            public void onPinMove(int index, float cellX, float cellY) {
                java.util.List<float[]> pins = fRest ? fPart.restPins : target;
                if (index < 0 || index >= pins.size()) return;
                float y = cellY;
                if (fRest) {
                    // Clamp between neighbors so the chain stays monotonic.
                    if (index > 0) y = Math.max(y, pins.get(index - 1)[1] + 0.01f);
                    if (index < pins.size() - 1) y = Math.min(y, pins.get(index + 1)[1] - 0.01f);
                }
                pins.get(index)[0] = cellX;
                pins.get(index)[1] = y;
                resolveNow();
            }
        });
        updateHint();
    }

    /** Rest-chain count changed → every cell pose of this part re-seeds (identity). */
    private void reseedCellPins(@NonNull AvatarRig.Part part) {
        int reseeded = 0;
        for (AvatarRig.PoseDomain d : rig.getDomains()) {
            for (AvatarRig.Cell cell : d.cells) {
                AvatarRig.PartPose pose = cell.poseFor(part.id);
                if (pose == null) continue;
                pose.pins.clear();
                for (float[] pin : part.restPins) {
                    pose.pins.add(new float[]{pin[0], pin[1]});
                }
                reseeded++;
            }
        }
        if (reseeded > 0) {
            Toast.makeText(this, R.string.avatar_studio_pins_reseeded, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteLastRestPin() {
        AvatarRig.Part part = selectedPartId != null ? rig.partById(selectedPartId) : null;
        if (!pinMode || armedCol >= 0 || part == null || part.restPins.isEmpty()) return;
        part.restPins.remove(part.restPins.size() - 1);
        reseedCellPins(part);
        syncPinEditing(false);
        resolveNow();
    }

    // ── Save / lifecycle ──────────────────────────────────────────────────

    private void save() {
        String name = nameField.getText().toString().trim();
        rig.setName(name.isEmpty() ? getString(R.string.avatar_studio_default_name) : name);
        if (libraryMode) {
            // Standalone: save-over the bundle (AvatarLibrary.save is temp-then-
            // rename, so re-saving from the bundle's own sheets is safe). The
            // in-memory sheets keep their current uris this session; the fresh
            // bundle is what the next open reads.
            AvatarLibrary.Entry e = libraryEntry;
            java.io.File out = e != null
                    ? AvatarLibrary.save(this, rig, e.sheets) : null;
            Toast.makeText(this, out != null
                    ? getString(R.string.avatar_studio_saved)
                    : getString(R.string.avatar_studio_save_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        if (isNewRig && project.avatarRigById(rig.getId()) == null) {
            project.getAvatarRigs().add(rig);
            isNewRig = false;
        }
        boolean ok = storage.save(project);
        if (ok) AIChatState.signalModified(project.getId());
        Toast.makeText(this, ok ? R.string.avatar_studio_saved : R.string.avatar_studio_save_failed,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * A4: export to the cross-project avatar library. Saves the project first so
     * the bundle captures the current name/rig, then bundles every sheet a part
     * references (dedup'd; missing sheets skipped — the bundle stays loadable).
     */
    private void saveToLibrary() {
        if (libraryMode) { // standalone Save IS the library save
            save();
            return;
        }
        save();
        java.util.List<SpriteSheet> sheets = new java.util.ArrayList<>();
        for (AvatarRig.Part p : rig.getParts()) {
            SpriteSheet s = project.spriteSheetById(p.sheetId);
            if (s != null && !sheets.contains(s)) sheets.add(s);
        }
        java.io.File out = AvatarLibrary.save(this, rig, sheets);
        Toast.makeText(this, out != null
                ? "Saved to avatar library: " + rig.getName()
                : "Library save failed", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        // rig == null: standalone chooser still open, nothing to save yet.
        if (rig != null && nameField != null) save(); // autosave semantics, S2 precedent
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
        SpriteSheet sheet = lookupSheet(sheetId);
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
        // A4: publish this rig (+ the sheets its parts use) as a self-contained
        // library bundle so the recorder's avatar selector can find it.
        TextView libBtn = chip("Library ⇪");
        libBtn.setOnClickListener(v -> saveToLibrary());
        // Standalone mode edits the library directly — Save IS the library save.
        if (libraryMode) libBtn.setVisibility(View.GONE);
        top.addView(back);
        top.addView(nameField, nameLp);
        top.addView(saveBtn);
        top.addView(libBtn);
        root.addView(top);

        // Puppet canvas
        preview = new PuppetPreviewView(this);
        preview.bind(rig, this::rendererFor);
        preview.setSheetLookup(this::lookupSheet);
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

        // A6 pin authoring: toggle chip + delete-last (rest mode only).
        pinsChip = chip(getString(R.string.avatar_studio_pins));
        pinsChip.setOnClickListener(v -> {
            pinMode = !pinMode;
            syncPinEditing(true);
        });
        controls.addView(pinsChip, chipLp());
        pinDelChip = chip(getString(R.string.avatar_studio_pin_del));
        pinDelChip.setOnClickListener(v -> deleteLastRestPin());
        controls.addView(pinDelChip, chipLp());

        // A6 per-part warp mesh density (data-not-code): 0 = default (24), else [8,64].
        densityValue = addStepper(controls, getString(R.string.avatar_studio_density),
                delta -> {
                    AvatarRig.Part part = selectedPartId != null
                            ? rig.partById(selectedPartId) : null;
                    if (part == null) return;
                    int cur = part.warpSegments == 0 ? 24 : part.warpSegments;
                    part.warpSegments = Math.max(8, Math.min(64, cur + delta * 4));
                    syncPoseControls();
                    preview.invalidate();
                });

        // A2 tracking demo chip (label literal by design — the tracking UI is
        // provisional until the MediaPipe source lands; no strings.xml churn).
        trackChip = chip("🎯 Track");
        trackChip.setOnClickListener(v -> {
            if (trackingBus != null) stopTracking(); else startTracking();
        });
        controls.addView(trackChip, chipLp());

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
