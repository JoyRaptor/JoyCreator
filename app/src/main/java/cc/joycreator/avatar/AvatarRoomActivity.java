package cc.joycreator.avatar;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.file.CharacterOpen;
import cc.joycreator.avatar.file.CharacterStore;
import cc.joycreator.avatar.ui.LayersView;
import cc.joycreator.avatar.perform.Inputs;
import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.ui.PartsView;
import cc.joycreator.avatar.ui.PerformPanel;
import cc.joycreator.avatar.ui.RoomEdit;
import cc.joycreator.avatar.ui.RoomMenu;
import cc.joycreator.avatar.ui.StageView;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.joyfolder.android.JoyFolderHome;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Avatar Studio, the character room on the phone (AVA-1, slice 1; tasks/avatar/AVA_PHONE_SPEC.md part A).
 *
 * <p>Views, not Compose (spec call 1). So far: the top bar (Menu, the save dot), the stage, the panel with its header
 * and the four stages, and the Menu (open from the folder or a file, save into the Joy Creator folder). Cards, doors,
 * Perform and recording are filled in by the later steps of slice 1; a control appears only when it works (a dead knob
 * is worse than a missing one).</p>
 */
public final class AvatarRoomActivity extends Activity implements RoomMenu.Host, LayersView.Host, PartsView.Host, PerformPanel.Host, cc.joycreator.avatar.ui.LandmarksScreen.Host, cc.joycreator.avatar.ui.PinsLayer.Host, cc.joycreator.avatar.ui.BoxLayer.Host, cc.joycreator.avatar.ui.MotionSections.Host, cc.joycreator.avatar.ui.StanceCard.Host, cc.joycreator.avatar.ui.ReshapeLayer.Host {
    private static final String TAG = "AvatarRoom";
    private static final int REQ_OPEN = 41;
    private static final int REQ_PICTURES = 42;
    private static final int REQ_PSD = 43;
    private static final int REQ_UPDATE = 46;

    /** The four stages, in the order the owner reads them (spec A2). The second one's word is one string. */
    private static final int[] STAGE_NAMES = {
            R.string.avatar_stage_1, R.string.avatar_stage_2, R.string.avatar_stage_3, R.string.avatar_stage_4};
    private static final int[] STAGE_HOVER = {
            R.string.avatar_hover_stage_1, R.string.avatar_hover_stage_2, R.string.avatar_hover_stage_3, R.string.avatar_hover_stage_4};
    private static final int[] STAGE_ASKS = {
            R.string.avatar_ask_stage_1, R.string.avatar_ask_stage_2, R.string.avatar_ask_stage_3, R.string.avatar_ask_stage_4};

    private static volatile java.lang.ref.WeakReference<AvatarRoomActivity> current;

    /** The room on screen, or null (Joybot's hands call {@link AvatarRoom}, which asks here). */
    static AvatarRoomActivity current() {
        java.lang.ref.WeakReference<AvatarRoomActivity> r = current;
        return r == null ? null : r.get();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private float dp;
    private LinearLayout root;
    private StageView stage;
    private LinearLayout panel;
    private TextView pathName;
    private PartsView parts;
    private PerformPanel performPanel;
    private volatile java.util.Map<String, Object> lastSignals;
    private cc.joycreator.avatar.perform.Walker walker;
    private org.json.JSONObject walkerDoc;
    private cc.joycreator.avatar.perform.Stick stick;
    private int frameNo;
    private cc.joycreator.avatar.ui.LandmarksScreen landmarks;
    private cc.joycreator.avatar.ui.SolverStage glStage;
    private boolean glWorks = true;
    private Object glPictures;
    private final java.util.Map<String, String> forced = new java.util.HashMap<>();
    private final cc.joycreator.avatar.ui.PinsLayer pins = new cc.joycreator.avatar.ui.PinsLayer(this);
    private final cc.joycreator.avatar.ui.BoxLayer box = new cc.joycreator.avatar.ui.BoxLayer(this);
    private final cc.joycreator.avatar.ui.ReshapeLayer reshapeLayer = new cc.joycreator.avatar.ui.ReshapeLayer(this);
    private boolean reshaping;
    private android.widget.FrameLayout panelBody;
    private com.fadcam.ui.faditor.avatar.MicVisemeSource mic;
    private static final int REQ_MIC = 44;
    private static final int REQ_CAMERA = 45;
    private com.fadcam.ui.faditor.avatar.face.FaceCapture faceCapture;
    // walk with your face (FaceWalk, its drawer) and toward the camera and away (DepthWalk): the face session's spots
    private final cc.joycreator.avatar.face.FaceWalk faceWalk = new cc.joycreator.avatar.face.FaceWalk();
    private cc.joycreator.avatar.face.FaceWalkDrawer faceWalkDrawer;
    private double[] bodyBox;                                   // the character's drawing at rest [x0, y0, x1, y1]
    private cc.joycreator.avatar.face.WalkerLegs legs;
    private cc.joycreator.avatar.perform.Walker legsOf;
    private final Inputs inputs = new Inputs();
    private boolean wasRecording;
    private boolean looping;
    private long lastFrameNs;
    private LayersView layers;
    private TextView partsTab, layersTab, undoButton, redoButton;
    private boolean showLayers;
    private String inHand;
    private RoomEdit edit;
    private boolean fromEdit;
    private final Runnable autosave = this::autosaveNow;
    private View saveDot;
    private final TextView[] stageTabs = new TextView[4];
    private CharacterDoc character;
    /** The character's file in the Joy Creator folder; null for Topps (his copy is made on the first save). */
    private String characterPath;
    /** What is on the stage differs from what is in the folder. Nothing edits yet, so this stays false. */
    private boolean unsaved;
    /** Perform is where Topps opens (spec A1): ready to play. */
    private int stageIndex = 3;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        current = new java.lang.ref.WeakReference<>(this);
        try {
            character = CharacterAssets.load(this, CharacterAssets.TOPPS);
        } catch (Exception e) {
            Log.w(TAG, "Topps did not open", e);
            Toast.makeText(this, R.string.avatar_cannot_open, Toast.LENGTH_LONG).show();
        }
        build();
        selectStage(stageIndex);
        showCharacter();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void build() {
        root = new LinearLayout(this);
        root.setBackgroundColor(Studio.GROUND);
        root.setFitsSystemWindows(true);

        LinearLayout left = new LinearLayout(this); // top bar + stage
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(buildTopBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)));
        stage = new StageView(this);
        stage.setHint(getString(R.string.avatar_hint_rest));
        android.widget.FrameLayout stageBox = new android.widget.FrameLayout(this);
        glStage = new cc.joycreator.avatar.ui.SolverStage(this);
        glStage.setFailure(() -> {                             // the GPU drawing could not start: the stand-in draws instead
            glWorks = false;
            updateGl();
        });
        stageBox.addView(glStage, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stageBox.addView(stage, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.setGlBacked(true);
        stage.setBodyDrag(new cc.joycreator.avatar.ui.StageView.BodyDrag() {
            @Override
            public boolean grab(float x, float y) {
                if (stageIndex != 3 || walker == null || !walker.travels()) return false;
                walker.grab(x, y);
                return true;
            }

            @Override
            public void drag(float x, float y) {
                if (walker != null) walker.drag(x, y);
            }

            @Override
            public void release() {
                if (walker != null) walker.release();
            }
        });
        stageBox.addView(buildStageTools(), new android.widget.FrameLayout.LayoutParams(px(44), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));
        left.addView(stageBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF09090B);
        panel.addView(buildPanelHeader(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)));
        panel.addView(buildStageRow(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(36)));
        parts = new PartsView(this, this);
        layers = new LayersView(this, this);
        android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        panelBody = body;
        body.addView(parts, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        performPanel = new PerformPanel(this, this);
        body.addView(performPanel, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(layers, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(panel);

        setContentView(root);
        applyOrientation(getResources().getConfiguration());
    }

    /** Portrait: stage above, panel below (never less than 44% to the stage). Landscape: panel on the right, 340 dp. */
    private void applyOrientation(Configuration cfg) {
        boolean land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        View left = root.getChildAt(0);
        if (land) {
            left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            // a tablet gets a wider card (more columns, never bigger buttons: dp stay as drawn)
            panel.setLayoutParams(new LinearLayout.LayoutParams(px(cfg.smallestScreenWidthDp >= 600 ? 480 : 340), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            boolean tablet = cfg.smallestScreenWidthDp >= 600;
            left.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, tablet ? 0.6f : 0.52f));
            panel.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, tablet ? 0.4f : 0.48f));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientation(c);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(8), 0, px(8), 0);
        TextView menu = roundButton("☰", getString(R.string.avatar_hover_menu));
        menu.setOnClickListener(v -> openMenu());
        bar.addView(menu, new LinearLayout.LayoutParams(px(36), px(36)));
        undoButton = roundButton("\u21B6", getString(R.string.avatar_hover_undo_none));
        undoButton.setOnClickListener(v -> { if (edit != null) edit.undo(); });
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(px(36), px(36));
        ul.leftMargin = px(8);
        bar.addView(undoButton, ul);
        redoButton = roundButton("\u21B7", getString(R.string.avatar_hover_redo_none));
        redoButton.setOnClickListener(v -> { if (edit != null) edit.redo(); });
        bar.addView(redoButton, ul);
        // the save dot (spec A2): never says "saved" for a character nobody has saved; says so on a long press or hover
        saveDot = new View(this);
        LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(px(8), px(8));
        dot.leftMargin = px(10);
        bar.addView(saveDot, dot);
        paintSaveDot();
        // the export mark: Studio's own vector in the Avatar gradient, at the bar's right end (spec A2)
        View gap = new View(this);
        bar.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        android.widget.ImageView export = new android.widget.ImageView(this);
        export.setImageResource(R.drawable.ic_export_studio);
        export.setColorFilter(Studio.ROOM_AVATAR);
        export.setPadding(px(6), px(6), px(6), px(6));
        TooltipCompat.setTooltipText(export, getString(R.string.avatar_hover_export));
        export.setContentDescription(getString(R.string.avatar_hover_export));
        export.setOnClickListener(v -> openExport());
        bar.addView(export, new LinearLayout.LayoutParams(px(40), px(40)));
        // Joybot joins the bar when his orb is wired to this room.
        return bar;
    }

    private TextView pickTool;

    /** The stage tools, a column at the stage's right edge (36 dp round, grey; on = solid cyan): Fit, the selection look, Pick a piece. */
    private View buildStageTools() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(4), px(8), px(4), 0);
        TextView fit = roundButton("\u2316", getString(R.string.avatar_hover_fit));
        fit.setOnClickListener(v -> stage.fitToHand());
        TextView look = roundButton("\u25CE", getString(R.string.avatar_hover_look));
        look.setOnClickListener(v -> {
            int i = stage.cycleLook();
            Toast.makeText(this, getResources().getStringArray(R.array.avatar_looks)[i], Toast.LENGTH_SHORT).show();
        });
        pickTool = roundButton("\u261D", getString(R.string.avatar_hover_pick));
        pickTool.setOnClickListener(v -> {
            boolean on = !stage.picking();
            stage.setPicking(on, id -> {
                stage.setPicking(false, null);
                paintPickTool();
                inHand(id);
            });
            paintPickTool();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(36), px(36));
        lp.bottomMargin = px(8);
        col.addView(fit, lp);
        col.addView(look, lp);
        col.addView(pickTool, lp);
        return col;
    }

    private void paintPickTool() {
        boolean on = stage.picking();
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        pickTool.setBackground(g);
        pickTool.setTextColor(on ? Studio.GROUND : 0xFFFAFAFA);
    }

    /** The export sheet (slice 2's RoomExportSheet): the character is saved first, so the file it works from is current. */
    private void openExport() {
        final CharacterDoc d = character;
        if (d == null) return;
        final String path = characterPath;
        final java.util.Map<String, Object> sig = lastSignals;
        JoyFolderHome.io().execute(() -> {
            String rel = CharacterStore.save(this, d, path);
            main.post(() -> {
                if (isFinishing()) return;
                if (rel == null) {
                    Toast.makeText(this, R.string.avatar_cannot_save, Toast.LENGTH_LONG).show();
                    return;
                }
                characterPath = rel;
                unsaved = false;
                paintSaveDot();
                cc.joycreator.avatar.studio.RoomExportSheet.show(this, rel, characterName(), sig);
            });
        });
    }

    private void paintSaveDot() {
        boolean saved = characterPath != null && !unsaved;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(saved ? Studio.ARMED : 0xFF52525B);
        saveDot.setBackground(g);
        String s = getString(saved ? R.string.avatar_hover_saved : R.string.avatar_hover_not_saved);
        TooltipCompat.setTooltipText(saveDot, s);
        saveDot.setContentDescription(s);
    }

    private View buildPanelHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(px(12), 0, px(12), 0);
        pathName = new TextView(this);
        pathName.setTextColor(0xFFFAFAFA);
        pathName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        TooltipCompat.setTooltipText(pathName, getString(R.string.avatar_hover_path_character));
        h.addView(pathName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Parts | Layers: two equal ways to look at the same character, so a two-way switch (rule A0)
        partsTab = switchHalf(getString(R.string.avatar_parts), getString(R.string.avatar_hover_parts));
        layersTab = switchHalf(getString(R.string.avatar_layers), getString(R.string.avatar_hover_layers));
        partsTab.setOnClickListener(v -> setLayersShown(false));
        layersTab.setOnClickListener(v -> setLayersShown(true));
        h.addView(partsTab, new LinearLayout.LayoutParams(px(64), px(32)));
        h.addView(layersTab, new LinearLayout.LayoutParams(px(64), px(32)));
        setLayersShown(false);
        return h;
    }

    private TextView switchHalf(String label, String hover) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    private void setLayersShown(boolean on) {
        showLayers = on;
        for (int i = 0; i < 2; i++) {
            TextView t = i == 0 ? partsTab : layersTab;
            boolean chosen = (i == 1) == on;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(10));
            g.setColor(chosen ? Studio.ARMED : 0xFF27272A);
            t.setBackground(g);
            t.setTextColor(chosen ? Studio.GROUND : 0xFFA1A1AA);
        }
        applyPanels();
        refreshPins();
        updateGl();
    }

    /** Layers when asked for; otherwise the performer's panel on Motion and Perform, the parts card on the first two. */
    private void applyPanels() {
        if (layers == null || parts == null || performPanel == null) return;
        boolean matching = landmarks != null && landmarks.isOpen();
        boolean perform = !showLayers && !matching && stageIndex >= 2;
        layers.setVisibility(showLayers && !matching ? View.VISIBLE : View.GONE);
        parts.setVisibility(!showLayers && !matching && !perform ? View.VISIBLE : View.GONE);
        performPanel.setVisibility(perform ? View.VISIBLE : View.GONE);
        if (landmarks != null) landmarks.view().setVisibility(matching ? View.VISIBLE : View.GONE);
        if (glStage != null) updateGl();
    }

    private LinearLayout buildStageRow() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(px(8), 0, px(8), 0);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(STAGE_NAMES[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            TooltipCompat.setTooltipText(t, getString(STAGE_HOVER[i]));
            t.setContentDescription(getString(STAGE_HOVER[i]));
            t.setOnClickListener(v -> selectStage(idx));
            stageTabs[i] = t;
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return row;
    }

    /** Chosen = solid cyan with the label punched out to the ground colour; the rest grey (rule: ON = solid cyan). */
    // ── Set up: the pins of the part in hand ──────────────────────────────────────────────────────

    @Override
    public CharacterDoc doc() {
        return character;
    }

    @Override
    public String inHand() {
        return inHand;
    }

    @Override
    public cc.joycreator.avatar.ui.PoseRenderer renderer() {
        return stage.renderer();
    }

    @Override
    public void movePin(String nodeId, String path, float x, float y) {
        edit.apply(getString(R.string.avatar_step_move_pin), d -> {
            org.json.JSONObject j = d.find(nodeId).json;
            org.json.JSONObject at;
            if (path.startsWith("limb.")) at = j.getJSONObject("limb").getJSONObject(path.substring(5));
            else if (path.equals("swing.tip")) at = j.getJSONObject("swing").getJSONObject("tip");
            else at = j.getJSONObject(path);
            at.put("x", x);
            at.put("y", y);
        });
    }

    /** Pins show on Set up for the part in hand, unless the landmarks screen has the stage. */
    private void refreshPins() {
        if (stage == null || (landmarks != null && landmarks.isOpen())) return;
        boolean on = inHand != null && !showLayers;
        stage.setLandmarks(on && stageIndex == 0 ? pins : on && stageIndex == 1 ? (reshaping ? reshapeLayer : box) : null);
    }

    // ── Arrange: reshape with pins ────────────────────────────────────────────────────────────────

    @Override
    public boolean reshaping() {
        return reshaping && inHand != null;
    }

    /** The picture shows the shape being made (this stance's, or the standing one) at full while reshaping. */
    private void pushShapes() {
        java.util.Map<String, java.util.Map<String, Double>> m = new java.util.HashMap<>();
        CharacterDoc.Node n = reshaping && inHand != null && character != null ? character.find(inHand) : null;
        if (n != null) m.put(n.id, cc.joycreator.avatar.file.Reshape.weights(n.json, poseJson() == null ? null : poseEdit));
        glStage.setShapes(m);
    }

    @Override
    public void reshape(boolean on) {
        if (on && inHand != null) {
            CharacterDoc.Node n = character.find(inHand);
            if (n == null) return;
            final String pid = poseJson() == null ? null : poseEdit;
            edit.apply(getString(R.string.avatar_step_reshape_on), d -> {
                org.json.JSONObject nn = d.find(inHand).json;
                cc.joycreator.avatar.file.Reshape.on(nn, pid == null ? null : cc.joycreator.avatar.file.Stances.find(d.json, pid));
            });
        }
        reshaping = on;
        pushShapes();
        refreshPins();
        parts.show(character, inHand, stageIndex);
    }

    /** Leave reshaping (the part in hand, the stance or the stage changed). */
    private void stopReshape() {
        if (!reshaping) return;
        reshaping = false;
        pushShapes();
    }

    @Override
    public void reshapeAction(String what) {
        if (inHand == null) return;
        final String pid = poseJson() == null ? null : poseEdit;
        final String id = inHand;
        if (what.equals("removeLast")) edit.apply(getString(R.string.avatar_step_reshape_remove), d -> cc.joycreator.avatar.file.Reshape.removePin(d.find(id).json, pid));
        else edit.apply(getString(R.string.avatar_step_reshape_again), d -> cc.joycreator.avatar.file.Reshape.startAgain(d.find(id).json, pid));
    }

    @Override
    public String poseId() {
        return poseJson() == null ? null : poseEdit;
    }

    @Override
    public double[] matrix(String id) {
        return glStage.matrix(id);
    }

    @Override
    public void live(String nodeId, String pinId, double x, double y) {
        try {
            cc.joycreator.avatar.file.Reshape.dragTo(character.find(nodeId).json, poseId(), pinId, x, y);
        } catch (org.json.JSONException e) {
            Log.w(TAG, "live reshape refused", e);
            return;
        }
        redraw();
    }

    @Override
    public void commit(String nodeId, String pinId, double x, double y, org.json.JSONArray oldOffset) {
        final String pid = poseId();
        try {                                                    // put the live offset back so the step starts from what was there
            org.json.JSONObject fs = cc.joycreator.avatar.file.Reshape.fixShape(character.find(nodeId).json, pid);
            org.json.JSONObject dd = fs == null ? null : fs.optJSONObject("d");
            if (dd != null) {
                if (oldOffset == null) dd.remove(pinId);
                else dd.put(pinId, oldOffset);
            }
        } catch (org.json.JSONException e) {
            Log.w(TAG, "reshape put-back refused", e);
        }
        edit.apply(getString(R.string.avatar_step_reshape_pin), d -> cc.joycreator.avatar.file.Reshape.dragTo(d.find(nodeId).json, pid, pinId, x, y));
    }

    @Override
    public void drop(String nodeId, double x, double y) {
        final String pid = poseId();
        edit.apply(getString(R.string.avatar_step_reshape_drop), d -> cc.joycreator.avatar.file.Reshape.dropPin(d.find(nodeId).json, pid, x, y, (nid, px, py, w) -> glStage.warpAt(nid, px, py, w)));
    }

    @Override
    public void say(int string) {
        Toast.makeText(this, string, Toast.LENGTH_SHORT).show();
    }

    // ── Motion: values of the part in hand and of the character ────────────────────────────────────

    @Override
    public CharacterDoc.Node inHandNode() {
        return character == null || inHand == null ? null : character.find(inHand);
    }

    @Override
    public cc.joycreator.avatar.ui.MotionSections.Host motion() {
        return this;
    }

    private static org.json.JSONObject walkTo(org.json.JSONObject root, String[] parts, int upTo) throws org.json.JSONException {
        org.json.JSONObject o = root;
        for (int i = 0; i < upTo; i++) {
            org.json.JSONObject next = o.optJSONObject(parts[i]);
            if (next == null) {
                next = new org.json.JSONObject();
                o.put(parts[i], next);
            }
            o = next;
        }
        return o;
    }

    /** Sets {@code path} (dotted; a number picks an item of a list, "links.0.amt") to a value. */
    private static void setPath(org.json.JSONObject root, String path, Object value) throws org.json.JSONException {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (cur instanceof org.json.JSONArray) cur = ((org.json.JSONArray) cur).get(Integer.parseInt(parts[i]));
            else {
                org.json.JSONObject o = (org.json.JSONObject) cur;
                Object next = o.opt(parts[i]);
                if (next == null) {
                    next = new org.json.JSONObject();
                    o.put(parts[i], next);
                }
                cur = next;
            }
        }
        String last = parts[parts.length - 1];
        if (cur instanceof org.json.JSONArray) ((org.json.JSONArray) cur).put(Integer.parseInt(last), value);
        else ((org.json.JSONObject) cur).put(last, value);
    }

    @Override
    public void roundHead(String nodeId, String action, String stepName) {
        edit.apply(stepName, d -> {
            org.json.JSONObject hd = d.find(nodeId).json;
            if (action.equals("off")) {
                if (hd.optJSONObject("ball") != null) hd.getJSONObject("ball").put("on", false);
            } else if (action.equals("on") && hd.optJSONObject("ball") != null) {
                hd.getJSONObject("ball").put("on", true);
            } else if (!cc.joycreator.avatar.file.Ball.guess(d.json, hd, stanceBounds2(d))) {
                throw new org.json.JSONException("no head drawing to make round");
            } else hd.getJSONObject("ball").put("on", true);
        });
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    @Override
    public void setValue(String nodeId, String path, Object value, String stepName) {
        edit.apply(stepName, d -> setPath(nodeId == null ? d.json : d.find(nodeId).json, path, value));
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    @Override
    public void remove(String nodeId, String path, String stepName) {
        edit.apply(stepName, d -> {
            org.json.JSONObject root = nodeId == null ? d.json : d.find(nodeId).json;
            String[] parts = path.split("\\.");
            walkTo(root, parts, parts.length - 1).remove(parts[parts.length - 1]);
        });
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    // ── Arrange: the box on the part in hand ──────────────────────────────────────────────────────

    /** The stance being worked on (its id), or null: standing. */
    private String poseEdit;
    private double poseAmt = 1;

    private org.json.JSONObject poseJson() {
        return poseEdit == null || character == null ? null : cc.joycreator.avatar.file.Stances.find(character.json, poseEdit);
    }

    @Override
    public void redraw() {
        stage.invalidate();
        if (character != null) glStage.setCharacter(character, true);        // the picture follows the finger
    }

    @Override
    public double[] numbers(String nodeId) {
        CharacterDoc.Node n = character.find(nodeId);
        org.json.JSONObject p = poseJson();
        if (p != null) {
            org.json.JSONObject d = p.optJSONObject("d"), e = d == null ? null : d.optJSONObject(nodeId);
            return e == null ? new double[]{0, 0, 0, 1} : new double[]{e.optDouble("x", 0), e.optDouble("y", 0), e.optDouble("rot", 0), e.has("sc") ? e.optDouble("sc", 1) : 1};
        }
        org.json.JSONObject j = n == null ? new org.json.JSONObject() : n.json;
        return new double[]{j.optDouble("dx", 0), j.optDouble("dy", 0), j.optDouble("rot", 0), j.has("sc") ? j.optDouble("sc", 1) : 1};
    }

    /** Writes a part's four numbers where they live now: on the part, or in the stance being worked on. */
    private void writeNumbers(CharacterDoc d, String nodeId, double dx, double dy, double rot, double sc) throws org.json.JSONException {
        org.json.JSONObject p = d.json.optJSONArray("poses") == null || poseEdit == null ? null : cc.joycreator.avatar.file.Stances.find(d.json, poseEdit);
        if (p == null) {
            cc.joycreator.avatar.ui.BoxLayer.put(d.find(nodeId).json, dx, dy, rot, sc);
            return;
        }
        org.json.JSONObject e = cc.joycreator.avatar.file.Stances.entry(p, nodeId);
        e.put("x", dx);
        e.put("y", dy);
        e.put("rot", rot);
        if (sc != 1) e.put("sc", sc);
        else e.remove("sc");
    }

    @Override
    public void live(String nodeId, double dx, double dy, double rot, double sc) {
        try {
            writeNumbers(character, nodeId, dx, dy, rot, sc);
        } catch (org.json.JSONException e) {
            Log.w(TAG, "live numbers refused", e);
        }
    }

    @Override
    public void commitBox(String nodeId, double dx, double dy, double rot, double sc) {
        CharacterDoc.Node n = character.find(nodeId);
        if (n == null) return;
        final String label = n.name.isEmpty() ? n.id : n.name;
        double[] before = numbers(nodeId);
        int word = Math.abs(rot - before[2]) > 1e-6 ? R.string.avatar_step_box_turn : Math.abs(sc - before[3]) > 1e-6 ? R.string.avatar_step_box_size : R.string.avatar_step_box_move;
        edit.apply(getString(word, label), d -> writeNumbers(d, nodeId, dx, dy, rot, sc));
    }

    @Override
    public void putBack(String id) {
        CharacterDoc.Node n = character.find(id);
        if (n == null) return;
        double[] cur = numbers(id);
        if (cur[0] == 0 && cur[1] == 0 && cur[2] == 0 && cur[3] == 1) return;
        edit.apply(getString(R.string.avatar_step_put_back, n.name.isEmpty() ? n.id : n.name), d -> {
            org.json.JSONObject p = poseEdit == null ? null : cc.joycreator.avatar.file.Stances.find(d.json, poseEdit);
            org.json.JSONObject e = p == null || p.optJSONObject("d") == null ? null : p.getJSONObject("d").optJSONObject(id);
            if (p != null && e != null && e.optBoolean("auto", false)) {            // worked out: back to how it was worked out
                e.put("x", e.optDouble("autoX", 0));
                e.put("y", e.optDouble("autoY", 0));
                e.put("rot", e.optDouble("autoRot", 0));
                e.remove("sc");
            } else writeNumbers(d, id, 0, 0, 0, 1);
        });
    }

    // ── Arrange: stances ──────────────────────────────────────────────────────────────────────────

    private cc.joycreator.avatar.file.Stances.Bounds stanceBounds() {
        return n -> {
            CharacterDoc.Node k = character.find(n.optString("id"));
            cc.joycreator.avatar.ui.PoseRenderer r = stage.renderer();
            return k == null || r == null ? null : r.restBounds(k);
        };
    }

    private void pushPoses() {
        java.util.Map<String, Double> m = new java.util.HashMap<>();
        if (poseEdit != null && poseJson() != null) m.put(poseEdit, poseAmt);
        glStage.setPoses(m);
    }

    @Override
    public cc.joycreator.avatar.ui.StanceCard.Host stances() {
        return this;
    }

    @Override
    public String poseEditing() {
        return poseJson() == null ? null : poseEdit;
    }

    @Override
    public void editStance(String idOrNull) {
        stopReshape();
        poseEdit = idOrNull;
        poseAmt = 1;
        pushPoses();
        parts.show(character, inHand, stageIndex);
        refreshPins();
        stage.invalidate();
    }

    private void promptText(String title, String initial, java.util.function.Consumer<String> then) {
        final android.widget.EditText box = new android.widget.EditText(this);
        box.setText(initial);
        box.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dl, w) -> {
                    String v = box.getText().toString().trim();
                    if (!v.isEmpty()) then.accept(v);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void addStance() {
        promptText(getString(R.string.avatar_name_stance), getString(R.string.avatar_name_stance_default), name -> {
            final String[] made = {null};
            edit.apply(getString(R.string.avatar_step_add_stance), d -> made[0] = cc.joycreator.avatar.file.Stances.add(d.json, name).getString("id"));
            if (made[0] != null) editStance(made[0]);
        });
    }

    @Override
    public void stanceAction(String action) {
        final org.json.JSONObject p = poseJson();
        switch (action) {
            case "fours": {
                final String[] made = {null};
                edit.apply(getString(R.string.avatar_step_make_fours), d -> {
                    org.json.JSONObject f = cc.joycreator.avatar.file.Stances.makeFours(d.json, null, stanceBounds2(d));
                    if (f != null) made[0] = f.getString("id");
                });
                if (made[0] != null) editStance(made[0]);
                else Toast.makeText(this, R.string.avatar_no_fours, Toast.LENGTH_LONG).show();
                return;
            }
            case "again": {
                if (p == null) return;
                final String id = p.optString("id");
                edit.apply(getString(R.string.avatar_step_work_out_again), d -> {
                    org.json.JSONObject q = cc.joycreator.avatar.file.Stances.find(d.json, id);
                    cc.joycreator.avatar.file.Stances.clearHand(q);
                    if (q.optJSONObject("fours") != null) cc.joycreator.avatar.file.Stances.makeFours(d.json, q.getJSONObject("fours").optDouble("angle"), stanceBounds2(d));
                });
                return;
            }
            case "copy": {
                if (p == null) return;
                final String id = p.optString("id");
                final String[] made = {null};
                edit.apply(getString(R.string.avatar_step_copy_stance), d -> made[0] = cc.joycreator.avatar.file.Stances.copy(d.json, cc.joycreator.avatar.file.Stances.find(d.json, id)).getString("id"));
                if (made[0] != null) editStance(made[0]);
                return;
            }
            case "rename": {
                if (p == null) return;
                final String id = p.optString("id");
                promptText(getString(R.string.avatar_name_stance), p.optString("name"), name -> edit.apply(getString(R.string.avatar_step_rename_stance),
                        d -> cc.joycreator.avatar.file.Stances.rename(d.json, cc.joycreator.avatar.file.Stances.find(d.json, id), name)));
                return;
            }
            case "remove": {
                if (p == null) return;
                final String id = p.optString("id");
                edit.apply(getString(R.string.avatar_step_remove_stance), d -> cc.joycreator.avatar.file.Stances.remove(d.json, cc.joycreator.avatar.file.Stances.find(d.json, id)));
                editStance(null);
                return;
            }
            default:
        }
    }

    /** Bounds for stance work done on the document inside a step (the nodes are the step's own JSON). */
    private cc.joycreator.avatar.file.Stances.Bounds stanceBounds2(CharacterDoc d) {
        return n -> {
            CharacterDoc.Node k = d.find(n.optString("id"));
            cc.joycreator.avatar.ui.PoseRenderer r = stage.renderer();
            return k == null || r == null ? null : r.restBounds(k);
        };
    }

    @Override
    public void setStanceValue(String path, Object value, String stepName) {
        final org.json.JSONObject p = poseJson();
        if (p == null) return;
        final String id = p.optString("id");
        edit.apply(stepName, d -> {
            org.json.JSONObject q = cc.joycreator.avatar.file.Stances.find(d.json, id);
            if (path.equals("fours.angle")) {
                cc.joycreator.avatar.file.Stances.makeFours(d.json, ((Number) value).doubleValue(), stanceBounds2(d));
            } else if (path.equals("fours.drop")) {
                q.getJSONObject("fours").put("drop", value);
            } else if (path.startsWith("walk.")) {
                walkTo(d.json.getJSONObject("settings"), new String[]{"walk"}, 1).put(path.substring(5), value);
            } else q.put(path, value);
        });
        parts.show(character, inHand, stageIndex);
    }

    @Override
    public double poseAmount() {
        return poseAmt;
    }

    @Override
    public void setPoseAmount(double v) {
        poseAmt = v;
        pushPoses();
    }

    @Override
    public boolean canFours() {
        return character != null && cc.joycreator.avatar.file.Stances.canFours(character.json, stanceBounds());
    }

    @Override
    public void guessPins(String nodeId, String kind) {
        final cc.joycreator.avatar.ui.PoseRenderer r = stage.renderer();
        if (r == null || character == null) return;
        final cc.joycreator.avatar.solve.Pics pics = r.pics();
        CharacterDoc.Node n = character.find(nodeId);
        if (n == null) return;
        final String label = n.name.isEmpty() ? n.id : n.name;
        // the torso's middle says which end of a limb is the shoulder, when the drawings do not overlap enough to tell
        final double[][] tc = {null};
        character.walk((k, d, p) -> {
            if (tc[0] == null && ("torso".equals(k.role) || "body".equals(k.role))) {
                double[] b = r.restBounds(k);
                if (b != null) tc[0] = new double[]{(b[0] + b[2]) / 2, (b[1] + b[3]) / 2};
            }
        });
        edit.apply(getString(R.string.avatar_step_guess_pins, label), d -> {
            org.json.JSONObject j = d.find(nodeId).json;
            if (kind.equals("fan")) cc.joycreator.avatar.file.PinGuess.guessFan(j, pics, tc[0], false);
            else if (kind.equals("swing")) cc.joycreator.avatar.file.PinGuess.guessSwing(j, pics, tc[0], null);
            else {
                org.json.JSONObject art = j;
                if ("set".equals(j.optString("type"))) {
                    String rest = j.optJSONObject("pick") == null ? "" : j.getJSONObject("pick").optString("rest", "");
                    org.json.JSONArray ks = j.optJSONArray("kids");
                    for (int i = 0; ks != null && i < ks.length(); i++) if (ks.getJSONObject(i).optString("id").equals(rest)) art = ks.getJSONObject(i);
                }
                double[][] nose = {null}, face = {null};
                d.walk((k, dd, p) -> {
                    if (nose[0] == null && "nose".equals(k.role)) nose[0] = r.restBounds(k);
                    if (face[0] == null && "face".equals(k.role)) face[0] = r.restBounds(k);
                });
                cc.joycreator.avatar.file.PinGuess.guessMouth(art, pics, nose[0], face[0]);
            }
        });
    }

    @Override
    public void openLandmarks(String setId) {
        if (character == null || landmarks == null) return;
        landmarks.open(character, setId);
        applyPanels();
    }

    @Override
    public void closeLandmarks() {
        if (landmarks != null) landmarks.close();
        applyPanels();
        if (character != null) parts.show(character, inHand, stageIndex);
        refreshPins();
        updateGl();
    }

    @Override
    public void onBackPressed() {
        if (landmarks != null && landmarks.isOpen()) closeLandmarks();
        else super.onBackPressed();
    }

    private void selectStage(int idx) {
        if (landmarks != null && landmarks.isOpen()) closeLandmarks();
        if (inputs.recorder.recording() && idx < 2) endRecord();     // a pass belongs to Motion and Perform
        if (idx < 2 && inputs.micOn()) micOff();                     // the microphone listens only in Motion and Perform
        if (idx < 2 && inputs.faceOn()) faceOff();                   // and so does the camera
        stageIndex = idx;
        if (idx != 1) stopReshape();
        for (int i = 0; i < 4; i++) {
            boolean on = i == idx;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(14));
            g.setColor(on ? Studio.ARMED : Color.TRANSPARENT);
            stageTabs[i].setBackground(g);
            stageTabs[i].setTextColor(on ? Studio.GROUND : 0xFFA1A1AA);
        }
        if (character != null) {
            parts.show(character, inHand, stageIndex);
            performPanel.bind(character, stageIndex == 3);
        }
        applyPanels();
        refreshPins();
    }

    private TextView roundButton(String glyph, String hover) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFFAFAFA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF27272A);
        t.setBackground(g);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    /** Puts {@link #character} on the stage and its name in the panel header. */
    private void showCharacter() {
        if (character == null) return;
        fromEdit = false;
        if (edit == null) edit = new RoomEdit(character, this::edited);
        if (landmarks == null) {
            landmarks = new cc.joycreator.avatar.ui.LandmarksScreen(this, stage, edit, this);
            panelBody.addView(landmarks.view(), new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            landmarks.view().setVisibility(View.GONE);
        }
        if (landmarks.isOpen()) landmarks.close();
        edit.reset(character);
        feedInputs(character);
        pathName.setText(character.name.isEmpty() ? getString(R.string.avatar_untitled) : character.name);
        paintSaveDot();
    }

    /** The GPU drawing shows unless the landmarks screen has the stage (it draws its own solid and ghost) or the GPU failed. */
    private void updateGl() {
        boolean matching = landmarks != null && landmarks.isOpen();
        boolean on = glWorks && !matching;
        glStage.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        stage.setGlBacked(on);
    }

    /** RoomEdit says the character changed (a step, an undo, a redo, or a new one on the stage). */
    private void edited(CharacterDoc d) {
        character = d;
        stage.setCharacter(d);
        Object pics = d.json.opt("pictures");
        glStage.setCharacter(d, pics == glPictures);
        glPictures = pics;
        inputs.setSettings(d.json.optJSONObject("settings"));
        inputs.setStances(d.json);
        if (walker == null || walkerDoc != d.json) {
            walker = new cc.joycreator.avatar.perform.Walker(d.json, inputs);
            walkerDoc = d.json;
            stick = new cc.joycreator.avatar.perform.Stick(d.json, inputs, walker, new cc.joycreator.avatar.perform.Stick.Later() {
                @Override
                public void after(long ms, Runnable r) {
                    main.postDelayed(r, ms);
                }

                @Override
                public void cancel(Object tag) {
                    main.removeCallbacksAndMessages(null);
                }
            });
        } else walker.refresh();
        stickSize();
        bodyBox = null;                                         // (walking with your face measures the drawing again)
        if (inputs.faceOn()) watchFacePins();
        stage.setSelected(inHand);
        layers.show(d, inHand);
        parts.show(d, inHand, stageIndex);
        paintUndo();
        if (landmarks != null) landmarks.refresh(d);
        syncTake(d);
        performPanel.refreshSettings(d);
        if (fromEdit) {
            unsaved = true;
            paintSaveDot();
            main.removeCallbacks(autosave);
            main.postDelayed(autosave, 1500);   // it saves by itself in a moment
        }
        fromEdit = true;
    }

    // ── performing: the hands, the take and the frame loop ──────────────────────────────────────────

    /** A new character on the stage: its buttons, its take, hands let go, the controls rebuilt. */
    private void feedInputs(CharacterDoc d) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        org.json.JSONArray tr = d.json.optJSONArray("triggers");
        for (int i = 0; tr != null && i < tr.length(); i++) {
            org.json.JSONObject t = tr.optJSONObject(i);
            if (t != null) ids.add(t.optString("id"));
        }
        inputs.recorder.stop();
        inputs.letGoOfEverything();
        inputs.setButtons(ids);
        inputs.recorder.setTake(takeOf(d));
        performPanel.bind(d, stageIndex == 3);
    }

    private static Take takeOf(CharacterDoc d) {
        org.json.JSONObject t = d.json.optJSONObject("take");
        Take take = t == null ? null : Take.fromJson(t);
        return take != null && take.n > 0 ? take : null;
    }

    /** An undo or redo changed the take in the file: the recorder follows (a pass in progress is left alone). */
    private void syncTake(CharacterDoc d) {
        if (inputs.recorder.recording()) return;
        Take have = inputs.recorder.take(), want = takeOf(d);
        boolean same = have == null ? want == null : have.sameAs(want);
        if (!same) inputs.recorder.setTake(want);
    }

    @Override
    public void toggleMic() {
        if (inputs.micOn()) {
            micOff();
            return;
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        micOn();
    }

    private void micOn() {
        if (mic == null) mic = new com.fadcam.ui.faditor.avatar.MicVisemeSource();
        mic.start(this);
        inputs.mic(true);
        performPanel.tick();
    }

    /** Lets go of the microphone at once (leaving the room, or the chip tapped off). */
    private void micOff() {
        if (mic != null) mic.stop();
        inputs.mic(false);
        performPanel.tick();
    }

    @Override
    public void toggleFace() {
        if (inputs.faceOn()) {                                  // the pilot's chip: always, then only while talking, then off
            if (inputs.faceDrive().mode() == cc.joycreator.avatar.face.FaceDrive.Mode.ALWAYS) {
                inputs.faceDrive().setMode(cc.joycreator.avatar.face.FaceDrive.Mode.TALK);
                performPanel.tick();
                return;
            }
            faceOff();
            return;
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        faceOn();
    }

    private com.fadcam.ui.faditor.avatar.face.FacePreviewView facePreview;

    @Override
    public void recentreFace() {
        if (faceCapture != null) faceCapture.setNeutral();
        inputs.faceDrive().recentre();                          // (and the head moves from where the pad is now)
    }

    /** The face camera watches the points the character's face pins follow (their lanes ride a take as the pilot's). */
    private void watchFacePins() {
        if (faceCapture == null || character == null) return;
        org.json.JSONArray fp = character.json.optJSONArray("facePins");
        java.util.LinkedHashSet<Integer> pts = new java.util.LinkedHashSet<>();
        for (int i = 0; fp != null && i < fp.length(); i++) {
            org.json.JSONObject p = fp.optJSONObject(i);
            if (p != null && p.has("point")) pts.add(p.optInt("point"));
        }
        int[] a = new int[pts.size()];
        int j = 0;
        for (int v : pts) a[j++] = v;
        faceCapture.watchPoints(a);
    }

    /** The drawer for walking with your face (null: this character does not walk). */
    @Override
    public View faceWalkDrawer() {
        if (walker == null || !walker.canWalk()) return null;
        if (faceWalkDrawer == null) faceWalkDrawer = new cc.joycreator.avatar.face.FaceWalkDrawer(this, faceWalk, new cc.joycreator.avatar.face.FaceWalkDrawer.Host() {
            @Override
            public boolean faceCameraOn() {
                return inputs.faceOn();
            }

            @Override
            public boolean walksInDepth() {
                return walker != null && walker.canWalk();
            }
        });
        else faceWalkDrawer.paint();
        return faceWalkDrawer;
    }

    /**
     * Once a frame in Perform, before the walker steps: the depth model while depth is in use (the face's Closer and further,
     * Up close), then walking with your face (the pilot's al-face-walk.js and al-depth-walk.js).
     */
    private void faceWalkStep(double dt) {
        if (character == null) return;
        if (bodyBox == null || (frameNo & 31) == 0) {
            org.json.JSONObject r = character.json.optJSONObject("root");
            CharacterDoc.Node k = r == null ? null : character.find(r.optString("id"));
            cc.joycreator.avatar.ui.PoseRenderer pr = stage.renderer();
            double[] b = k == null || pr == null ? null : pr.restBounds(k);
            if (b != null && b.length >= 4) bodyBox = b;
        }
        boolean depth = walker.canWalk() && (faceWalk.closer() || faceWalk.upClose());   // (+ the joystick in perspective: slice 1)
        cc.joycreator.avatar.perform.DepthWalk.prep(walker, depth, character.json, bodyBox);
        double h = bodyBox == null ? character.json.optDouble("h", 512) : bodyBox[3] - bodyBox[1];
        double lim = 0.7 * (bodyBox == null ? character.json.optDouble("w", 512) : bodyBox[2] - bodyBox[0]);
        boolean replay = inputs.recorder.playing() && !inputs.recorder.recording();
        if (legsOf != walker) {
            legs = new cc.joycreator.avatar.face.WalkerLegs(walker);
            legsOf = walker;
        }
        faceWalk.step(dt, inputs.faceNow(), legs, h, lim, replay);
    }

    @Override
    public boolean faceMirror() {
        return faceCapture != null && faceCapture.tuning().mirror;
    }

    @Override
    public void setFaceMirror(boolean on) {
        if (faceCapture == null) return;
        com.fadcam.ui.faditor.avatar.face.FaceTuning t = faceCapture.tuning();
        t.mirror = on;
        faceCapture.setTuning(t);
    }

    @Override
    public View facePreview() {
        if (facePreview == null) facePreview = new com.fadcam.ui.faditor.avatar.face.FacePreviewView(this);
        return facePreview;
    }

    private void faceOn() {
        try {
            if (faceCapture == null) faceCapture = new com.fadcam.ui.faditor.avatar.face.FaceCapture(this);
            faceCapture.setPreview(facePreview instanceof com.fadcam.ui.faditor.avatar.MediaPipeTrackingSource.PreviewSink ? (com.fadcam.ui.faditor.avatar.MediaPipeTrackingSource.PreviewSink) facePreview : null, false);
            faceCapture.start();
            inputs.face(true);
            watchFacePins();
        } catch (RuntimeException e) {
            Log.w(TAG, "face camera did not start", e);
            Toast.makeText(this, R.string.avatar_face_failed, Toast.LENGTH_LONG).show();
        }
        if (faceWalkDrawer != null) faceWalkDrawer.paint();
        performPanel.tick();
    }

    /** Lets go of the camera at once (leaving the room, or the chip tapped off). */
    private void faceOff() {
        if (faceCapture != null) {
            faceCapture.stop();
            faceCapture.setPreview(null, false);
        }
        if (facePreview != null) facePreview.setCameraOn(false);
        inputs.face(false);
        if (faceWalkDrawer != null) faceWalkDrawer.paint();
        performPanel.tick();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_CAMERA && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && !isFinishing()
                && stageIndex >= 2) faceOn();
        if (req == REQ_MIC && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && !isFinishing()
                && stageIndex >= 2) micOn();            // a permission granted late never starts capture outside Perform
    }

    /** The microphone's shape names as the character's mouth shapes. */
    private static final String[] MIC_SHAPES = {"Neutral", "Aa", "Ee", "W-Oo", "M", "S"};

    /** How big the character is, for the joystick's speed (a body height a second at the rim). */
    private void stickSize() {
        if (stick == null || character == null) return;
        org.json.JSONObject r = character.json.optJSONObject("root");
        CharacterDoc.Node k = r == null ? null : character.find(r.optString("id"));
        cc.joycreator.avatar.ui.PoseRenderer pr = stage.renderer();
        double[] b = k == null || pr == null ? null : pr.restBounds(k);
        if (b != null && b.length >= 4) stick.setBodySize(b[2] - b[0], b[3] - b[1]);
    }

    @Override
    public cc.joycreator.avatar.perform.Stick stick() {
        return stick;
    }

    @Override
    public boolean stickOpen() {
        return getSharedPreferences("avatar_room", MODE_PRIVATE).getBoolean("stick", false);
    }

    @Override
    public void setStickOpen(boolean on) {
        if (!on && stick != null) stick.release();
        getSharedPreferences("avatar_room", MODE_PRIVATE).edit().putBoolean("stick", on).apply();
    }

    @Override
    public void openStickGestures() {
        if (stick == null) return;
        cc.joycreator.avatar.ui.StickSheet.show(this, stick, characterName(), (slot, key, step) -> edit.apply(step, doc -> cc.joycreator.avatar.perform.Stick.setSlot(doc.json, slot, key)));
    }

    @Override
    public void setSetting(String key, boolean on, String stepName) {
        edit.apply(stepName, doc -> {
            org.json.JSONObject st = doc.json.optJSONObject("settings");
            if (st == null) {
                st = new org.json.JSONObject();
                doc.json.put("settings", st);
            }
            st.put(key, on);
        });
    }

    @Override
    public Inputs inputs() {
        return inputs;
    }

    @Override
    public void toggleRecord() {
        if (inputs.recorder.recording()) endRecord();
        else {
            inputs.recorder.stop();
            inputs.record();
            wasRecording = true;
        }
        performPanel.tick();
    }

    /** Ends a pass and keeps the take as ONE named undo step. */
    private void endRecord() {
        inputs.recorder.stop();
        wasRecording = false;
        commitTake(getString(R.string.avatar_step_record));
    }

    private void commitTake(String stepName) {
        if (edit == null) return;
        final Take t = inputs.recorder.take();
        edit.apply(stepName, doc -> {
            if (t == null || t.n == 0) doc.json.remove("take");
            else doc.json.put("take", t.toJson());
        });
    }

    @Override
    public void togglePlay() {
        if (inputs.recorder.recording()) return;
        if (inputs.recorder.playing()) inputs.recorder.stop();
        else inputs.recorder.play();
        performPanel.tick();
    }

    @Override
    public void takeBack() {
        if (inputs.recorder.recording() || !inputs.recorder.undoPass()) return;
        commitTake(getString(R.string.avatar_step_take_back));
        performPanel.tick();
    }

    @Override
    public void clearTake() {
        if (inputs.recorder.take() == null) return;
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.avatar_clear_ask)
                .setPositiveButton(R.string.avatar_clear_yes, (dl, w) -> {
                    inputs.recorder.clear();
                    commitTake(getString(R.string.avatar_step_clear_take));
                    performPanel.tick();
                })
                .setNegativeButton(R.string.avatar_clear_no, null)
                .show();
    }

    private final android.view.Choreographer.FrameCallback frameLoop = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long ns) {
            if (!looping) return;
            double dt = lastFrameNs == 0 ? 1 / 60.0 : Math.min(0.1, (ns - lastFrameNs) / 1e9);
            lastFrameNs = ns;
            frame(dt);
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void frame(double dt) {
        if (stageIndex < 2) {                                   // building: the still pose, nothing moving
            stage.setSignals(null);
            glStage.frame(null, false, dt, stage.viewScale(), stage.viewX(), stage.viewY());
            return;
        }
        if (inputs.micOn() && mic != null) {
            float db = mic.currentAudioDb();
            int cls = mic.currentVisemeClassIndex();
            inputs.micHeard(Float.isNaN(db) ? 0 : Math.max(0, Math.min(1, (db + 50) / 40)), cls >= 0 && cls < MIC_SHAPES.length ? MIC_SHAPES[cls] : "Neutral");
        }
        if (inputs.faceOn() && faceCapture != null) inputs.faceSaw(faceCapture.sample(System.nanoTime()));
        java.util.Map<String, Object> sig = inputs.frame(dt, System.currentTimeMillis());
        lastSignals = sig;
        stage.setSignals(sig);
        cc.joycreator.avatar.solve.Signals S = cc.joycreator.avatar.ui.SignalMap.toSignals(sig, inputs.time(), inputs.breath());
        if ((frameNo++ & 31) == 0) stickSize();
        if (stick != null) stick.step(dt);
        if (walker != null) faceWalkStep(dt);
        if (walker != null && (walker.canWalk() || walker.travels())) {
            walker.step(dt, S, glStage.anchors(), glStage.tips());
            S.flip = inputs.flip();
        }
        glStage.frame(S, true, dt, stage.viewScale(), stage.viewX(), stage.viewY());
        Object tx = sig.get("turnX"), ty = sig.get("turnY");
        if (tx instanceof Number && ty instanceof Number) performPanel.showHead(((Number) tx).doubleValue(), ((Number) ty).doubleValue());
        performPanel.tick();
        if (wasRecording && !inputs.recorder.recording()) endRecord();     // the pass ended itself (the take's limit)
    }

    @Override
    protected void onResume() {
        super.onResume();
        glStage.onResume();
        looping = true;
        lastFrameNs = 0;
        android.view.Choreographer.getInstance().postFrameCallback(frameLoop);
    }

    private void paintUndo() {
        String u = edit.undoName(), r = edit.redoName();
        paintHistoryButton(undoButton, u == null ? getString(R.string.avatar_hover_undo_none) : getString(R.string.avatar_hover_undo, u), u != null);
        paintHistoryButton(redoButton, r == null ? getString(R.string.avatar_hover_redo_none) : getString(R.string.avatar_hover_redo, r), r != null);
    }

    private void paintHistoryButton(TextView b, String hover, boolean live) {
        b.setAlpha(live ? 1f : 0.35f);
        TooltipCompat.setTooltipText(b, hover);
        b.setContentDescription(hover);
    }

    @Override
    public void inHand(String id) {
        stopReshape();
        inHand = id.equals(inHand) ? null : id;
        stage.setSelected(inHand);
        layers.show(character, inHand);
        parts.show(character, inHand, stageIndex);
        refreshPins();
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    @Override
    public void tryDrawing(String setId, String kidId) {
        stage.tryDrawing(setId, kidId);
        if (kidId == null) forced.remove(setId);
        else forced.put(setId, kidId);
        glStage.setForce(new java.util.HashMap<>(forced));
    }

    @Override
    public java.util.List<String> drawOrder() {
        return glStage == null ? new java.util.ArrayList<>() : glStage.drawOrder();
    }

    @Override
    public void stackEdit(String stepName, String nodeId, cc.joycreator.avatar.file.Stack.Step step) {
        final String pid = poseJson() == null ? null : poseEdit;
        final java.util.List<String> drawn = drawOrder();
        edit.apply(stepName, doc -> {
            CharacterDoc.Node k = doc.find(nodeId);
            if (k != null) step.apply(doc.json, pid, k.json, drawn);
        });
    }

    @Override
    public void restack(String id, int by) {
        CharacterDoc.Node node = character.find(id);
        if (node == null) return;
        final CharacterDoc.Node[] parent = {null};
        character.walk((n, d, p) -> {
            if (n.id.equals(id)) parent[0] = p;
        });
        if (parent[0] == null) return;
        final String parentId = parent[0].id;
        final int count = parent[0].kids.size();
        int at = 0;
        for (int i = 0; i < count; i++) if (parent[0].kids.get(i).id.equals(id)) at = i;
        final int from = at;
        final int to = by == Integer.MIN_VALUE ? 0 : by == Integer.MAX_VALUE ? count - 1 : Math.max(0, Math.min(count - 1, at + by));
        if (to == from) return;
        String label = node.name.isEmpty() ? node.id : node.name;
        int word = by == Integer.MIN_VALUE ? R.string.avatar_step_very_back : by == Integer.MAX_VALUE ? R.string.avatar_step_very_front
                : by < 0 ? R.string.avatar_step_back_one : R.string.avatar_step_forward_one;
        edit.apply(getString(word, label), doc -> {
            org.json.JSONArray kids = doc.find(parentId).json.getJSONArray("kids");
            Object moved = kids.get(from);
            kids.remove(from);
            org.json.JSONArray rebuilt = new org.json.JSONArray();
            for (int i = 0, j = 0; i < count; i++) {
                if (i == to) rebuilt.put(moved);
                else rebuilt.put(kids.get(j++));
            }
            doc.find(parentId).json.put("kids", rebuilt);
        });
    }

    @Override
    public void eye(CharacterDoc.Node node) {
        final boolean hide = !node.hidden;
        final String label = node.name.isEmpty() ? node.id : node.name;
        edit.apply(getString(hide ? R.string.avatar_step_hide : R.string.avatar_step_show, label), doc -> {
            org.json.JSONObject j = doc.find(node.id).json;
            if (hide) j.put("hidden", true);
            else j.remove("hidden");
        });
    }

    // ── Joybot's hands (AvatarRoom calls these on the main thread; every change is one named undo step) ──

    /** The character in words: its name, the stage, the part in hand, and the parts and layers it has. */
    String joybotDescribe() {
        if (character == null) return "No character is open.";
        final StringBuilder parts = new StringBuilder();
        final int[] layers = {0, 0};
        final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        character.walk((n, depth, p) -> {
            if (p == null) return;
            layers[0]++;
            if (n.hidden) layers[1]++;
            if (!n.role.isEmpty() && seen.add(n.role)) parts.append(parts.length() == 0 ? "" : ", ").append(n.name.isEmpty() ? n.role : n.name).append(" (").append(n.role).append(")");
        });
        CharacterDoc.Node hand = inHand == null ? null : character.find(inHand);
        return characterName() + ": " + layers[0] + " layers (" + layers[1] + " hidden). Stage: " + getString(STAGE_NAMES[stageIndex])
                + ". In hand: " + (hand == null ? "nothing" : (hand.name.isEmpty() ? hand.id : hand.name))
                + ". Parts: " + (parts.length() == 0 ? "none named yet" : parts) + ". "
                + (unsaved || characterPath == null ? "Not saved yet." : "Saved in the Joy Creator folder.");
    }

    /** Finds a layer by what a person would call it: its name, or its part (role), ignoring case. */
    private CharacterDoc.Node byName(String what) {
        if (character == null || what == null) return null;
        final String w = what.trim().toLowerCase(java.util.Locale.ROOT);
        final CharacterDoc.Node[] hit = {null, null};
        character.walk((n, d, p) -> {
            if (p == null) return;
            String nm = n.name.toLowerCase(java.util.Locale.ROOT);
            if (hit[0] == null && nm.equals(w)) hit[0] = n;
            if (hit[1] == null && (nm.contains(w) || n.role.toLowerCase(java.util.Locale.ROOT).equals(w))) hit[1] = n;
        });
        return hit[0] != null ? hit[0] : hit[1];
    }

    /** Puts a part in hand by name; false when no layer is called that. */
    boolean joybotChoose(String what) {
        CharacterDoc.Node n = byName(what);
        if (n == null) return false;
        inHand = n.id;
        stage.setSelected(inHand);
        layers.show(character, inHand);
        parts.show(character, inHand, stageIndex);
        return true;
    }

    /** Switches to a stage by name (set up / arrange / motion / perform); false when it is not one. */
    boolean joybotStage(String name) {
        String w = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        for (int i = 0; i < 4; i++) {
            if (getString(STAGE_NAMES[i]).toLowerCase(java.util.Locale.ROOT).replace(" ", "").equals(w)) {
                selectStage(i);
                return true;
            }
        }
        return false;
    }

    /** Shows or hides a layer by name as one undo step; returns a sentence, or null when no layer is called that. */
    String joybotShown(String what, boolean shown) {
        CharacterDoc.Node n = byName(what);
        if (n == null) return null;
        if (n.hidden != shown) return (n.name.isEmpty() ? n.id : n.name) + " is already " + (shown ? "shown." : "hidden.");
        final String id = n.id;
        final String label = n.name.isEmpty() ? n.id : n.name;
        edit.apply(getString(shown ? R.string.avatar_step_joybot_show : R.string.avatar_step_joybot_hide, label), doc -> {
            org.json.JSONObject j = doc.find(id).json;
            if (shown) j.remove("hidden");
            else j.put("hidden", true);
        });
        return (shown ? "Showing " : "Hid ") + label + ".";
    }

    /** Moves a layer in front or behind its neighbours: "back one", "forward one", "very back", "very front". */
    String joybotStack(String what, String how) {
        CharacterDoc.Node n = byName(what);
        if (n == null) return null;
        String h = how == null ? "" : how.trim().toLowerCase(java.util.Locale.ROOT);
        int by = h.contains("very back") ? Integer.MIN_VALUE : h.contains("very front") ? Integer.MAX_VALUE
                : h.contains("back") ? -1 : h.contains("forward") || h.contains("front") ? 1 : 0;
        if (by == 0) return "";
        restack(n.id, by);
        return "Moved " + (n.name.isEmpty() ? n.id : n.name) + " " + h + ".";
    }

    /** Saves into the Joy Creator folder now (the same as Menu, Save). */
    void joybotSave() {
        saveNow();
    }

    private void autosaveNow() {
        if (unsaved && character != null) saveQuietly();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (current() == this) current = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        glStage.onPause();
        looping = false;
        micOff();                                               // the microphone's light goes out with the room
        faceOff();                                              // and so does the camera's
        if (inputs.recorder.recording()) endRecord();          // leaving the room ends a pass; nothing keeps listening
        main.removeCallbacks(autosave);
        if (unsaved && character != null) saveQuietly();
    }

    private void saveQuietly() {
        final CharacterDoc d = character;
        final String path = characterPath;
        JoyFolderHome.io().execute(() -> {
            String rel = CharacterStore.save(this, d, path);
            main.post(() -> {
                if (rel == null) return;       // still on the stage; tried again with the next change
                characterPath = rel;
                if (character == d) {
                    unsaved = false;
                    paintSaveDot();
                }
            });
        });
    }

    // ── the Menu ──────────────────────────────────────────────────────────────────────────────────

    private void openMenu() {
        JoyFolderHome.io().execute(() -> {
            List<CharacterStore.Entry> mine = CharacterStore.list(this);   // folder reads never on the main thread
            main.post(() -> {
                if (!isFinishing()) RoomMenu.show(this, this, mine);
            });
        });
    }

    @Override
    public String characterName() {
        return character == null || character.name.isEmpty() ? getString(R.string.avatar_untitled) : character.name;
    }

    @Override
    public void openBuiltIn(String asset) {
        try {
            character = CharacterAssets.load(this, asset);
            characterPath = null;
            unsaved = false;
            showCharacter();
        } catch (Exception e) {
            Log.w(TAG, "built-in character did not open", e);
            Toast.makeText(this, R.string.avatar_cannot_open, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void openFromFolder(CharacterStore.Entry entry) {
        JoyFolderHome.io().execute(() -> {
            CharacterDoc d = CharacterStore.read(this, entry.path);
            main.post(() -> {
                if (isFinishing()) return;
                if (d == null) {
                    Toast.makeText(this, R.string.avatar_cannot_open, Toast.LENGTH_LONG).show();
                    return;
                }
                character = d;
                characterPath = entry.path;
                unsaved = false;
                showCharacter();
            });
        });
    }

    @Override
    public void openFromPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            Log.w(TAG, "no file picker", e);
        }
    }

    @Override
    public void newFromPictures() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICTURES);
        } catch (Exception e) {
            Log.w(TAG, "no file picker", e);
        }
    }

    @Override
    public void newFromLayeredFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, REQ_PSD);
        } catch (Exception e) {
            Log.w(TAG, "no file picker", e);
        }
    }

    @Override
    public void updateTheArt() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, REQ_UPDATE);
        } catch (Exception e) {
            Log.w(TAG, "no file picker", e);
        }
    }

    /** The PNG text of one layer's pixels (the phone's bitmap code behind {@code PsdDoor.PngEncoder}). */
    private static String pngOf(int w, int h, byte[] rgba) {
        int[] px = new int[w * h];
        for (int k = 0; k < px.length; k++) {
            int o = k * 4;
            px[k] = ((rgba[o + 3] & 255) << 24) | ((rgba[o] & 255) << 16) | ((rgba[o + 1] & 255) << 8) | (rgba[o + 2] & 255);
        }
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, w, 0, 0, w, h);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
        b.recycle();
        return "data:image/png;base64," + android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP);
    }

    /** Reads the repainted file on a copy, then lays the result over the character as ONE undo step. */
    private void updateDoor(final Uri uri) {
        final CharacterDoc now = character;
        if (now == null) return;
        JoyFolderHome.io().execute(() -> {
            org.json.JSONObject copy = null;
            cc.joycreator.avatar.intake.PsdUpdate.Result res = null;
            String say = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException(getString(R.string.avatar_psd_unreadable));
                cc.joycreator.avatar.intake.PsdReader psd = cc.joycreator.avatar.intake.PsdReader.parse(readAll(in));
                copy = new org.json.JSONObject(now.json.toString());
                res = cc.joycreator.avatar.intake.PsdUpdate.apply(copy, psd, AvatarRoomActivity::pngOf);
            } catch (java.io.IOException e) {
                say = e.getMessage();
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "update not read", e);
            }
            final org.json.JSONObject fresh = copy;
            final cc.joycreator.avatar.intake.PsdUpdate.Result r = res;
            final String line = say;
            main.post(() -> {
                if (isFinishing()) return;
                if (fresh == null || r == null) {
                    Toast.makeText(this, line != null ? line : getString(R.string.avatar_psd_unreadable), Toast.LENGTH_LONG).show();
                    return;
                }
                if (character != now) return;                       // another character came on the stage meanwhile
                edit.apply(getString(R.string.avatar_step_update_art), d -> {
                    d.json.put("root", fresh.getJSONObject("root"));
                    d.json.put("pictures", fresh.getJSONObject("pictures"));
                    if (fresh.has("fine")) d.json.put("fine", fresh.getJSONObject("fine"));
                });
                Toast.makeText(this, getString(R.string.avatar_update_done, r.replaced + r.finer, r.added, r.left), Toast.LENGTH_LONG).show();
            });
        });
    }

    /** Reads the layered file, builds the character (every layer's picture inside it), and puts it on the stage. */
    private void layeredDoor(final Uri uri) {
        JoyFolderHome.io().execute(() -> {
            CharacterDoc made = null;
            String say = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException(getString(R.string.avatar_psd_unreadable));
                cc.joycreator.avatar.intake.PsdReader psd = cc.joycreator.avatar.intake.PsdReader.parse(readAll(in));
                cc.joycreator.avatar.intake.PsdDoor.Summary sum = new cc.joycreator.avatar.intake.PsdDoor.Summary();
                org.json.JSONObject doc = cc.joycreator.avatar.intake.PsdDoor.build(psd, getString(R.string.avatar_untitled), (w, h, rgba) -> {
                    int[] px = new int[w * h];
                    for (int k = 0; k < px.length; k++) {
                        int o = k * 4;
                        px[k] = ((rgba[o + 3] & 255) << 24) | ((rgba[o] & 255) << 16) | ((rgba[o + 1] & 255) << 8) | (rgba[o + 2] & 255);
                    }
                    android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                    b.setPixels(px, 0, w, 0, 0, w, h);
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
                    b.recycle();
                    return "data:image/png;base64," + android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP);
                }, sum);
                made = CharacterOpen.read(doc.toString());
                if (made != null) say = getString(R.string.avatar_psd_found, sum.layers, sum.groups);
            } catch (java.io.IOException e) {
                say = e.getMessage();           // already one plain sentence
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "layered file not opened", e);
            }
            final CharacterDoc result = made;
            final String line = say;
            main.post(() -> {
                if (isFinishing()) return;
                if (result == null) {
                    Toast.makeText(this, line != null ? line : getString(R.string.avatar_psd_unreadable), Toast.LENGTH_LONG).show();
                    return;
                }
                character = result;
                characterPath = null;
                unsaved = true;
                inHand = null;
                showCharacter();
                setLayersShown(true);
                Toast.makeText(this, line, Toast.LENGTH_LONG).show();
                main.postDelayed(autosave, 1500);
            });
        });
    }

    /** Reads each picked picture (at most 512 px on a side), builds the new character, and puts it on the stage. */
    private void pictureDoor(final List<Uri> uris) {
        JoyFolderHome.io().execute(() -> {
            java.util.ArrayList<cc.joycreator.avatar.file.LooseCharacter.Pic> pics = new java.util.ArrayList<>();
            int n = 0;
            for (Uri u : uris) {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) continue;
                    byte[] raw = readAll(in);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2;
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = sample;
                    android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                    if (b == null) continue;
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
                    String url = "data:image/png;base64," + android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP);
                    n++;
                    pics.add(new cc.joycreator.avatar.file.LooseCharacter.Pic(getString(R.string.avatar_picture_n, n), b.getWidth(), b.getHeight(), url));
                    b.recycle();
                } catch (Exception | OutOfMemoryError e) {
                    Log.w(TAG, "picture not read", e);
                }
            }
            CharacterDoc made = null;
            if (!pics.isEmpty()) {
                try {
                    made = CharacterOpen.read(cc.joycreator.avatar.file.LooseCharacter.build(getString(R.string.avatar_untitled), pics).toString());
                } catch (Exception e) {
                    Log.w(TAG, "character not made", e);
                }
            }
            final CharacterDoc result = made;
            main.post(() -> {
                if (isFinishing()) return;
                if (result == null) {
                    Toast.makeText(this, R.string.avatar_no_pictures, Toast.LENGTH_LONG).show();
                    return;
                }
                character = result;
                characterPath = null;
                unsaved = true;
                inHand = null;
                showCharacter();
                setLayersShown(true);      // the pictures are the layers: show them
                main.postDelayed(autosave, 1500);
            });
        });
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_UPDATE && res == RESULT_OK && data != null && data.getData() != null) {
            updateDoor(data.getData());
            return;
        }
        if (req == REQ_PSD && res == RESULT_OK && data != null && data.getData() != null) {
            layeredDoor(data.getData());
            return;
        }
        if (req == REQ_PICTURES && res == RESULT_OK && data != null) {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) pictureDoor(uris);
            return;
        }
        if (req != REQ_OPEN || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        JoyFolderHome.io().execute(() -> {
            CharacterDoc d = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    d = CharacterOpen.read(new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                Log.w(TAG, "file not read", e);
            }
            final CharacterDoc opened = d;
            main.post(() -> {
                if (isFinishing()) return;
                if (opened == null) {
                    Toast.makeText(this, R.string.avatar_cannot_open, Toast.LENGTH_LONG).show();
                    return;
                }
                character = opened;
                characterPath = null;      // not in the folder yet: Save puts it there
                unsaved = true;
                showCharacter();
            });
        });
    }

    @Override
    public void saveNow() {
        final CharacterDoc d = character;
        if (d == null) return;
        JoyFolderHome.io().execute(() -> {
            String rel = CharacterStore.save(this, d, characterPath);
            main.post(() -> {
                if (isFinishing()) return;
                if (rel == null) {
                    Toast.makeText(this, R.string.avatar_cannot_save, Toast.LENGTH_LONG).show();
                    return;
                }
                characterPath = rel;
                unsaved = false;
                paintSaveDot();
                Toast.makeText(this, R.string.avatar_saved, Toast.LENGTH_SHORT).show();
            });
        });
    }
}
