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
public final class AvatarRoomActivity extends Activity implements RoomMenu.Host, LayersView.Host, PartsView.Host, PerformPanel.Host, cc.joycreator.avatar.ui.LandmarksScreen.Host, cc.joycreator.avatar.ui.PinsLayer.Host, cc.joycreator.avatar.ui.BoxLayer.Host, cc.joycreator.avatar.ui.MotionSections.Host, cc.joycreator.avatar.ui.StanceCard.Host, cc.joycreator.avatar.ui.ReshapeLayer.Host, cc.joycreator.avatar.face.FacePinsLayer.Host, cc.joycreator.avatar.face.FacePinsSection.Host, cc.joycreator.avatar.ui.ShrugSections.Host {
    private static final String TAG = "AvatarRoom";
    private static final int REQ_OPEN = 41;
    private static final int REQ_PICTURES = 42;
    private static final int REQ_PSD = 43;
    private static final int REQ_UPDATE = 46;
    private static final int REQ_WHOLE = 47;
    private static final int REQ_SHEET = 48;
    private static final int REQ_SCENEPIC = 49;               // pictures for a scene (slice 3)
    private String scenePicKind = "obj";

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
    private LinearLayout bottomRow;                      // holds the panel (and, on a tablet held upright, the rail beside it)
    private cc.joycreator.avatar.ui.LayersView rail;     // the tablet's rail: the layers, always there (spec A14)
    private TextView pathName;
    private PartsView parts;
    private PerformPanel performPanel;
    private volatile java.util.Map<String, Object> lastSignals;
    private cc.joycreator.avatar.perform.Walker walker;
    private org.json.JSONObject walkerDoc;
    private cc.joycreator.avatar.perform.Stick stick;
    private int frameNo;
    private double testMouth;                                          // Try: talk, on Set up
    private String testVis;                                            // Try a sound, on Set up
    private cc.joycreator.avatar.ui.LandmarksScreen landmarks;
    private cc.joycreator.avatar.ui.SolverStage glStage;
    private cc.joycreator.avatar.ui.SceneUi sceneUi;
    private cc.joycreator.avatar.ui.SceneRoom sceneRoom;       // a scene in hand (slice 3, row 3)
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
    private org.json.JSONObject maskSet;                        // the turning head (it may wear the face mask, FACE-2)
    // face pins in Set up (FACE-1): the section open, the point picked, the pin picked
    private boolean facePinsOpen;
    private int pendingPoint = -1;
    private String pickedPin;
    private final cc.joycreator.avatar.face.FacePinsLayer facePinsLayer = new cc.joycreator.avatar.face.FacePinsLayer(this);
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
    /** Where each character on this screen was saved, by the character itself: a save finishing late never points another character at its file. */
    private final cc.joycreator.avatar.file.SaveLedger<CharacterDoc> ledger = new cc.joycreator.avatar.file.SaveLedger<>();
    private View doorView;                                            // the pin wizard or the sheet cutter, while one is over the room
    private android.graphics.Bitmap doorBitmap;
    private int editStamp;                                            // counts the edits, so a save knows whether more came while it ran
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
        // Perform: a finger on a hand takes the hand (it wins over the body); anywhere else on the body walks it
        stage.setBodyDrag(new cc.joycreator.avatar.ui.HandGrab(stage, () -> stageIndex == 3 && glWorks ? glStage.tips() : null,
                glStage::toInner, new cc.joycreator.avatar.ui.HandGrab.Hands() {
                    @Override public void hold(String side, double x, double y) { inputs.holdHand(side, x, y); }

                    @Override public void letGo(String side) { inputs.letGoHand(side); }
                }, new cc.joycreator.avatar.ui.StageView.BodyDrag() {
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
        }));
        cc.joycreator.avatar.ui.SceneStage sceneGl = new cc.joycreator.avatar.ui.SceneStage(this);
        sceneRoom = new cc.joycreator.avatar.ui.SceneRoom(new cc.joycreator.avatar.ui.SceneRoom.Host() {
            @Override
            public android.content.Context ctx() {
                return AvatarRoomActivity.this;
            }

            @Override
            public void toast(String text) {
                Toast.makeText(AvatarRoomActivity.this, text, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void sceneShown(boolean shown) {
                glStage.setVisibility(shown ? View.GONE : View.VISIBLE);
                stage.setVisibility(shown ? View.GONE : View.VISIBLE);
                applyPanels();
                if (sceneUi != null) sceneUi.roomChanged();
            }

            @Override
            public void stageChosen(int idx) {
                if (idx != stageIndex) selectStage(idx);
            }

            @Override
            public void saved() {
                if (sceneUi != null) sceneUi.refreshScenes();
            }

            @Override
            public void handChanged(CharacterDoc d, Take take) {           // the performer works on this scene character now
                character = d;
                characterPath = null;
                unsaved = false;
                showCharacter();
                inputs.recorder.setTake(take);
                applyPanels();
            }

            @Override
            public cc.joycreator.avatar.perform.TakeRecorder handRecorder() {
                return inputs.recorder;
            }

            @Override
            public void pickPictures(String kind) {
                scenePicKind = kind;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, "obj".equals(kind));
                try {
                    startActivityForResult(i, REQ_SCENEPIC);
                } catch (Exception e) {
                    Log.w(TAG, "no file picker", e);
                }
            }
        }, sceneGl);
        stageBox.addView(sceneRoom.stageView(), new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stageBox.addView(buildStageTools(), new android.widget.FrameLayout.LayoutParams(px(44), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));
        sceneUi = new cc.joycreator.avatar.ui.SceneUi(this, new cc.joycreator.avatar.ui.SceneHost(this, () -> stageIndex == 3, () -> character, this::characterName,
                () -> characterPath, () -> stage, () -> glStage, () -> selectStage(1)));      // the frame, the director's chair and the strip (slice 3)
        stageBox.addView(sceneUi, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sceneUi.attachRoom(sceneRoom);
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
        body.addView(sceneRoom.cardView(), new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rail = new cc.joycreator.avatar.ui.LayersView(this, this);
        rail.setBackgroundColor(0xFF0D0D10);
        bottomRow = new LinearLayout(this);
        bottomRow.addView(panel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(bottomRow);

        setContentView(root);
        applyOrientation(getResources().getConfiguration());
    }

    /** Portrait: stage above, panel below (never less than 44% to the stage). Landscape: panel on the right, 340 dp. */
    private void applyOrientation(Configuration cfg) {
        boolean land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean tablet = cfg.smallestScreenWidthDp >= 600;
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        View left = null;
        for (int i = 0; i < root.getChildCount() && left == null; i++) if (root.getChildAt(i) != bottomRow && root.getChildAt(i) != rail) left = root.getChildAt(i);
        if (rail.getParent() instanceof ViewGroup) ((ViewGroup) rail.getParent()).removeView(rail);
        rail.setVisibility(tablet ? View.VISIBLE : View.GONE);
        // the rail (layers, always there, 200 dp held upright and 220 dp sideways): beside the card below the stage, or at the far left; dp stay as drawn
        if (tablet && land) root.addView(rail, 0, new LinearLayout.LayoutParams(px(220), ViewGroup.LayoutParams.MATCH_PARENT));
        else if (tablet) bottomRow.addView(rail, 0, new LinearLayout.LayoutParams(px(200), ViewGroup.LayoutParams.MATCH_PARENT));
        if (land) {
            left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            // a tablet gets a wider card (more columns, never bigger buttons: dp stay as drawn)
            bottomRow.setLayoutParams(new LinearLayout.LayoutParams(px(tablet ? 480 : 340), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            left.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, tablet ? 0.6f : 0.52f));
            bottomRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, tablet ? 0.4f : 0.48f));
        }
        if (character != null) rail.show(character, inHand);
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
        undoButton.setOnClickListener(v -> { if (edit != null && edit.undo()) rebindCards(); });
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(px(36), px(36));
        ul.leftMargin = px(8);
        bar.addView(undoButton, ul);
        redoButton = roundButton("\u21B7", getString(R.string.avatar_hover_redo_none));
        redoButton.setOnClickListener(v -> { if (edit != null && edit.redo()) rebindCards(); });
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
        if (character == null) return;
        final java.util.Map<String, Object> sig = lastSignals;
        saveCurrent(rel -> {
            if (isFinishing()) return;
            if (rel == null) {
                Toast.makeText(this, R.string.avatar_cannot_save, Toast.LENGTH_LONG).show();
                return;
            }
            cc.joycreator.avatar.studio.RoomExportSheet.show(this, rel, characterName(), sig);
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
        boolean inScene = sceneRoom != null && sceneRoom.isOpen();         // a scene in hand has its own cards
        boolean perform = !showLayers && !matching && (inScene ? stageIndex == 3 && sceneRoom.hand() != null : stageIndex >= 2);
        layers.setVisibility(showLayers && !matching && !inScene ? View.VISIBLE : View.GONE);
        parts.setVisibility(!showLayers && !matching && !perform && !inScene ? View.VISIBLE : View.GONE);
        performPanel.setVisibility(perform ? View.VISIBLE : View.GONE);
        if (sceneRoom != null) sceneRoom.cardView().setVisibility(inScene && !matching && !perform ? View.VISIBLE : View.GONE);
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
        stage.setLandmarks(on && stageIndex == 0 ? (facePinsOpen ? facePinsLayer : pins) : on && stageIndex == 1 ? (reshaping ? reshapeLayer : box) : null);
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
    public void restore(String nodeId, String pinId, org.json.JSONArray oldOffset) {
        final String pid = poseId();
        try {
            org.json.JSONObject fs = cc.joycreator.avatar.file.Reshape.fixShape(character.find(nodeId).json, pid);
            org.json.JSONObject dd = fs == null ? null : fs.optJSONObject("d");
            if (dd != null) {
                if (oldOffset == null) dd.remove(pinId);
                else dd.put(pinId, oldOffset);
            }
        } catch (org.json.JSONException e) {
            Log.w(TAG, "reshape put-back refused", e);
        }
        redraw();
    }

    @Override
    public void commit(String nodeId, String pinId, double x, double y, org.json.JSONArray oldOffset) {
        final String pid = poseId();
        restore(nodeId, pinId, oldOffset);                       // put the live offset back so the step starts from what was there
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
    public void setMany(String stepName, java.util.List<Object[]> items) {
        edit.apply(stepName, d -> {
            for (Object[] it : items) setPath(it[0] == null ? d.json : d.find((String) it[0]).json, (String) it[1], it[2]);
        });
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    @Override
    public void addLink(String nodeId, org.json.JSONObject link) {
        edit.apply(getString(R.string.avatar_step_add_follow), d -> {
            org.json.JSONObject j = d.find(nodeId).json;
            org.json.JSONArray l = j.optJSONArray("links");
            if (l == null) j.put("links", l = new org.json.JSONArray());
            l.put(link);
            if (j.optJSONObject("pivot") == null && !"art".equals(j.optString("type"))) {         // a part that moves needs a point to turn and resize about
                double[] b = stage.renderer() == null ? null : stage.renderer().restBounds(d.find(nodeId));
                if (b != null) j.put("pivot", new org.json.JSONObject().put("x", (b[0] + b[2]) / 2).put("y", (b[1] + b[3]) / 2));
            }
        });
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    @Override
    public void removeLink(String nodeId, int index) {
        edit.apply(getString(R.string.avatar_step_remove_follow), d -> {
            org.json.JSONArray l = d.find(nodeId).json.optJSONArray("links");
            if (l != null && index >= 0 && index < l.length()) l.remove(index);
        });
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    @Override
    public void makeSwing(String nodeId, String kind) {
        guessPins(nodeId, "swing:" + kind);
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    @Override
    public void blinkNow() {
        inputs.blinkNow(System.currentTimeMillis());
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
    public void renameNode(String nodeId) {
        CharacterDoc.Node n = character == null ? null : character.find(nodeId);
        if (n == null) return;
        promptText(getString(R.string.avatar_rename_title), n.name, name -> edit.apply(getString(R.string.avatar_step_rename_part), d -> d.find(nodeId).json.put("name", name)));
    }

    @Override
    public void removeNode(String nodeId) {
        final CharacterDoc.Node n = character == null ? null : character.find(nodeId);
        if (n == null) return;
        final String label = n.name.isEmpty() ? n.id : n.name;
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.avatar_remove_title, label))
                .setMessage(R.string.avatar_remove_body)
                .setPositiveButton(R.string.avatar_remove, (dl, w) -> {
                    edit.apply(getString(R.string.avatar_step_remove_part, label), d -> {
                        final org.json.JSONArray[] holder = {null};
                        d.walk((k, depth, parent) -> {
                            if (k.id.equals(nodeId) && parent != null) holder[0] = parent.json.optJSONArray("kids");
                        });
                        org.json.JSONArray ks = holder[0];
                        for (int i = 0; ks != null && i < ks.length(); i++) if (nodeId.equals(ks.getJSONObject(i).optString("id"))) {
                            ks.remove(i);
                            break;
                        }
                    });
                    if (nodeId.equals(inHand)) inHand = null;
                    refreshPins();
                    parts.show(character, inHand, stageIndex);
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
    public void tryVis(String shape) {
        testVis = shape;
        testMouth = shape == null ? 0 : 1;
        glStage.requestRender();
    }

    @Override
    public void tryMouth(double v) {
        testVis = null;
        testMouth = v;
        glStage.requestRender();
    }

    /** The mouth card's two bigger acts: "squash" (flatten shut, instead of a hinge) and "everywhere" (this way of talking at every head angle). */
    @Override
    public void mouthAction(String nodeId, String action) {
        final cc.joycreator.avatar.ui.PoseRenderer r = stage.renderer();
        if (r == null || character == null) return;
        final cc.joycreator.avatar.solve.Pics pics = r.pics();
        edit.apply(getString(action.equals("squash") ? R.string.avatar_step_mouth_squash : action.equals("hinge") ? R.string.avatar_step_mouth_hinge : action.equals("swap") ? R.string.avatar_step_mouth_swap : R.string.avatar_step_mouth_everywhere), d -> {
            org.json.JSONObject j = d.find(nodeId).json;
            org.json.JSONObject art = mouthArt(j);
            if (action.equals("swap")) {
                art.remove("hinge");
                art.remove("squash");
                return;
            }
            if (action.equals("hinge")) {
                String keep = art.optJSONObject("squash") != null ? art.getJSONObject("squash").optString("rest", "open")
                        : art.optJSONObject("hinge") != null ? art.getJSONObject("hinge").optString("rest", "open") : "open";
                art.remove("squash");
                final double[][] nose1 = {null}, face1 = {null};
                d.walk((k, dd, p) -> {
                    if (nose1[0] == null && "nose".equals(k.role)) nose1[0] = r.restBounds(k);
                    if (face1[0] == null && "face".equals(k.role)) face1[0] = r.restBounds(k);
                });
                cc.joycreator.avatar.file.PinGuess.guessMouth(art, pics, nose1[0], face1[0]);
                art.remove("squash");
                org.json.JSONObject h = art.optJSONObject("hinge");
                if (h == null) art.put("hinge", h = new org.json.JSONObject().put("angle", 30));
                h.put("rest", keep);
                return;
            }
            if (action.equals("squash")) {
                String keep = art.optJSONObject("hinge") != null ? art.getJSONObject("hinge").optString("rest", "open") : "open";
                double[] b = r.restBounds(d.find(art.optString("id")));
                art.remove("hinge");
                art.put("squash", new org.json.JSONObject().put("rest", keep).put("min", 0.14));
                if (b != null) art.put("pivot", new org.json.JSONObject().put("x", (b[0] + b[2]) / 2).put("y", b[1] + (b[3] - b[1]) * 0.15));
                return;
            }
            final double[][] nose = {null}, face = {null};
            d.walk((k, dd, p) -> {
                if (nose[0] == null && "nose".equals(k.role)) nose[0] = r.restBounds(k);
                if (face[0] == null && "face".equals(k.role)) face[0] = r.restBounds(k);
            });
            final java.util.List<org.json.JSONObject> others = new java.util.ArrayList<>();
            d.walk((k, dd, p) -> {
                if ("mouth".equals(k.role) && !k.id.equals(j.optString("id"))) others.add(k.json);
            });
            org.json.JSONObject hinge = art.optJSONObject("hinge"), squash = art.optJSONObject("squash");
            for (org.json.JSONObject o : others) {
                org.json.JSONObject a = mouthArt(o);
                if (a == art || !"art".equals(a.optString("type", "art"))) continue;
                double[] b = r.restBounds(d.find(a.optString("id")));
                if (hinge != null) {
                    org.json.JSONObject mine = a.optJSONObject("hinge");
                    int sign = mine != null && mine.optDouble("angle", 30) < 0 ? -1 : 1;
                    if (mine == null) {
                        a.remove("squash");
                        cc.joycreator.avatar.file.PinGuess.guessMouth(a, pics, nose[0], face[0]);
                        a.remove("squash");
                        mine = a.optJSONObject("hinge");
                        if (mine == null) a.put("hinge", mine = new org.json.JSONObject().put("rest", "open").put("angle", 30));
                        if (a.optJSONObject("pivot") == null && b != null) a.put("pivot", new org.json.JSONObject().put("x", (b[0] + b[2]) / 2).put("y", b[1]));
                    }
                    mine.put("rest", hinge.optString("rest", "open"));
                    mine.put("angle", Math.abs(hinge.optDouble("angle", 30)) * (mine.optDouble("angle", 30) < 0 ? -1 : sign));
                } else if (squash != null) {
                    a.remove("hinge");
                    a.put("squash", new org.json.JSONObject().put("rest", squash.optString("rest", "open")).put("min", squash.optDouble("min", 0.14)));
                    if (b != null) a.put("pivot", new org.json.JSONObject().put("x", (b[0] + b[2]) / 2).put("y", b[1] + (b[3] - b[1]) * 0.15));
                }
            }
        });
    }

    private static org.json.JSONObject mouthArt(org.json.JSONObject j) {
        if (!"set".equals(j.optString("type"))) return j;
        String rest = j.optJSONObject("pick") == null ? "" : j.optJSONObject("pick").optString("rest", "");
        org.json.JSONArray ks = j.optJSONArray("kids");
        for (int i = 0; ks != null && i < ks.length(); i++) if (ks.optJSONObject(i).optString("id").equals(rest)) return ks.optJSONObject(i);
        return ks != null && ks.length() > 0 ? ks.optJSONObject(0) : j;
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
            else if (kind.startsWith("swing")) {
                String sk = kind.contains(":") ? kind.substring(kind.indexOf(':') + 1) : null;               // swing:bendy, swing:stiff
                org.json.JSONObject target = j;
                if (!"art".equals(j.optString("type", "art"))) {                                              // a set or folder: the first drawing showing
                    java.util.List<CharacterDoc.Node> shown = r.shown(d.find(nodeId));
                    for (CharacterDoc.Node k : shown) if ("art".equals(k.type)) {
                        target = d.find(k.id).json;
                        break;
                    }
                }
                cc.joycreator.avatar.file.PinGuess.guessSwing(target, pics, sk != null && sk.equals("stiff") ? null : tc[0], sk);
            }
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
        if (doorView != null) {                                        // the pin wizard or the sheet cutter over the room: Back leaves it, not the room
            View v = doorView;
            doorView = null;
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            if (doorBitmap != null) {
                doorBitmap.recycle();
                doorBitmap = null;
            }
        } else if (landmarks != null && landmarks.isOpen()) closeLandmarks();
        else super.onBackPressed();
    }

    /** A drawing tried on Set up is not kept: it goes when the stage or the character changes. */
    private void clearTried() {
        if (!forced.isEmpty()) {
            forced.clear();
            glStage.setForce(new java.util.HashMap<>(forced));
        }
        stage.tryDrawing(null, null);
        if (parts != null) parts.clearTrying();
    }

    /** After Undo or Redo the Motion and Perform cards are drawn again, or their rows would hold parts the history replaced. */
    private void rebindCards() {
        if (character != null && stageIndex >= 2) performPanel.bind(character, stageIndex == 3);
    }

    private void selectStage(int idx) {
        if (landmarks != null && landmarks.isOpen()) closeLandmarks();
        if (inputs.recorder.recording() && idx < 2) endRecord();     // a pass belongs to Motion and Perform
        if (idx < 2 && inputs.micOn()) micOff();                     // the microphone listens only in Motion and Perform
        if (idx < 2 && inputs.faceOn()) faceOff();                   // and so does the camera
        stageIndex = idx;
        if (idx != 1) stopReshape();
        testMouth = 0;
        clearTried();
        if (parts != null) parts.scrollTo(0, 0);                    // a new stage opens at the top of its card
        if (performPanel != null) performPanel.scrollTo(0, 0);
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
        if (sceneRoom != null && sceneRoom.isOpen() && sceneRoom.stage() != idx) sceneRoom.setStage(idx);
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
        shrugTried = false;                                     // (a new character: set up to shrug when the shoulders are next seen)
        shoulderHinted = false;
        feedInputs(character);
        pathName.setText(character.name.isEmpty() ? getString(R.string.avatar_untitled) : character.name);
        stage.setHint(getString(R.string.avatar_hint_character, character.name.isEmpty() ? getString(R.string.avatar_untitled) : character.name));
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
        inputs.setButtons(triggerIds(d));                        // a new stance's button works at once, not when the character is reopened
        if (walker == null || walkerDoc != d.json) {
            walker = new cc.joycreator.avatar.perform.Walker(d.json, inputs);
            walkerDoc = d.json;
            stick = new cc.joycreator.avatar.perform.Stick(d.json, inputs, walker, new cc.joycreator.avatar.perform.OwnJobs(new cc.joycreator.avatar.perform.OwnJobs.Poster() {
                @Override
                public void post(Runnable r, long ms) {
                    main.postDelayed(r, ms);
                }

                @Override
                public void remove(Runnable r) {
                    main.removeCallbacks(r);
                }
            }));
        } else walker.refresh();
        stickSize();
        bodyBox = null;                                         // (walking with your face measures the drawing again)
        maskSet = cc.joycreator.avatar.solve.MaskBend.headSet(d.json.optJSONObject("root"));   // FACE-2: the head that may wear the mask
        if (inputs.faceOn()) watchFacePins();
        stage.setSelected(inHand);
        layers.show(d, inHand);
        rail.show(d, inHand);
        parts.show(d, inHand, stageIndex);
        paintUndo();
        if (landmarks != null) landmarks.refresh(d);
        syncTake(d);
        performPanel.refreshSettings(d);
        syncCameras();                                          // (cameras per tracker: an undone choice opens the camera it names)
        if (fromEdit) {
            unsaved = true;
            editStamp++;
            paintSaveDot();
            main.removeCallbacks(autosave);
            main.postDelayed(autosave, 1500);   // it saves by itself in a moment
        }
        fromEdit = true;
    }

    // ── performing: the hands, the take and the frame loop ──────────────────────────────────────────

    /** A new character on the stage: its buttons, its take, hands let go, the controls rebuilt. */
    /** The ids of the character's buttons (a stance made, copied or worked out brings a new one). */
    private static java.util.ArrayList<String> triggerIds(CharacterDoc d) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        org.json.JSONArray tr = d.json.optJSONArray("triggers");
        for (int i = 0; tr != null && i < tr.length(); i++) {
            org.json.JSONObject t = tr.optJSONObject(i);
            if (t != null) ids.add(t.optString("id"));
        }
        return ids;
    }

    private void feedInputs(CharacterDoc d) {
        inputs.recorder.stop();
        inputs.letGoOfEverything();
        inputs.setButtons(triggerIds(d));
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

    private cc.joycreator.avatar.face.DirectionPrompts dirPrompts;
    private android.app.AlertDialog dirDialog;
    private int dirShown = -1;
    private static final int[] DIR_WORDS = {R.string.avatar_face_dir_yaw, R.string.avatar_face_dir_pitch, R.string.avatar_face_dir_roll,
            R.string.avatar_face_dir_lean, R.string.avatar_face_dir_eye};

    /** Check directions (the pilot's): five short moves, each read by itself; the frame loop moves them on. */
    @Override
    public void checkDirections() {
        if (faceCapture == null || !inputs.faceOn()) return;
        final com.fadcam.ui.faditor.avatar.face.FaceCapture fc = faceCapture;
        dirPrompts = new cc.joycreator.avatar.face.DirectionPrompts(new cc.joycreator.avatar.face.DirectionPrompts.Face() {
            @Override
            public void check(String what) {
                fc.checkDirection(what);
            }

            @Override
            public com.fadcam.ui.faditor.avatar.face.FaceCalibration.DirectionCheck last() {
                return fc.lastDirectionCheck();
            }
        });
        dirShown = -1;
        dirDialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.avatar_face_dirs_title)
                .setMessage("")
                .setNegativeButton(R.string.avatar_face_dirs_stop, (d, w) -> finishDirections())
                .setCancelable(false)
                .create();
        dirDialog.show();
        dirPrompts.start(System.nanoTime());
        showDirection();
    }

    private void showDirection() {
        if (dirDialog == null || dirPrompts == null || dirPrompts.prompt() < 0 || dirPrompts.prompt() == dirShown) return;
        dirShown = dirPrompts.prompt();
        dirDialog.setMessage(getString(R.string.avatar_face_dirs_step, dirShown + 1, DIR_WORDS.length, getString(DIR_WORDS[dirShown]))
                + "\n\n" + getString(R.string.avatar_face_dirs_note));
    }

    private void finishDirections() {
        if (dirPrompts == null) return;
        int n = dirPrompts.answered();
        dirPrompts.stop();
        dirPrompts = null;
        if (dirDialog != null && dirDialog.isShowing()) dirDialog.dismiss();
        dirDialog = null;
        Toast.makeText(this, n == 0 ? getString(R.string.avatar_face_dirs_none) : n == DIR_WORDS.length ? getString(R.string.avatar_face_dirs_all)
                : getString(R.string.avatar_face_dirs_some, n, DIR_WORDS.length), Toast.LENGTH_LONG).show();
    }

    // ── face pins in Set up (FACE-1: the pilot's al-facepins.js card and markers) ──────────────────────────

    @Override
    public android.view.View facePinsSection(CharacterDoc.Node node) {
        String r = node == null || node.role == null ? "" : node.role;
        if (node == null || r.startsWith("arm") || r.startsWith("leg") || r.startsWith("hand") || r.startsWith("foot")) return null;
        return new cc.joycreator.avatar.face.FacePinsSection(this, this);
    }

    @Override
    public org.json.JSONObject character() {
        return character == null ? new org.json.JSONObject() : character.json;
    }

    @Override
    public boolean facePinsOpen() {
        return facePinsOpen;
    }

    @Override
    public void setFacePinsOpen(boolean on) {
        facePinsOpen = on;
        pendingPoint = -1;
        refreshPins();
        redraw();
    }

    @Override
    public int pendingPoint() {
        return pendingPoint;
    }

    @Override
    public void pickPoint(int pt) {
        pendingPoint = pt;
    }

    @Override
    public String pickedFacePin() {
        return pickedPin;
    }

    @Override
    public void pickFacePin(String id) {
        pickedPin = id;
        if (character != null) parts.show(character, inHand, stageIndex);
    }

    /** Face-size units to the character's own: half the head's width, at least 20 (the pilot's). */
    private double facePinScale() {
        CharacterDoc.Node hd = null;
        for (CharacterDoc.Node k : character == null ? java.util.Collections.<CharacterDoc.Node>emptyList() : allNodes()) if ("head".equals(k.role)) { hd = k; break; }
        cc.joycreator.avatar.ui.PoseRenderer pr = stage.renderer();
        double[] b = hd == null || pr == null ? null : pr.restBounds(hd);
        return b == null || b.length < 4 ? 60 : Math.max(20, (b[2] - b[0]) * 0.5);
    }

    private java.util.List<CharacterDoc.Node> allNodes() {
        java.util.List<CharacterDoc.Node> out = new java.util.ArrayList<>();
        character.walk((n, depth, parent) -> out.add(n));
        return out;
    }

    @Override
    public void dropFacePin(String nodeId, double x, double y, int pt) {
        final double k = facePinScale();
        final String[] made = new String[1];
        edit.apply(getString(R.string.avatar_step_facepin_add), d -> {
            org.json.JSONObject n = d.find(nodeId).json;
            cc.joycreator.avatar.face.FacePins.sheetMesh(n);
            org.json.JSONObject pin = cc.joycreator.avatar.file.Reshape.dropPin(n, null, x, y, (nid, px, py, w) -> glStage.warpAt(nid, px, py, w));
            made[0] = cc.joycreator.avatar.face.FacePins.bind(d.json, n, pin.getString("id"), pt, true, k);
        });
        pendingPoint = -1;
        pickedPin = made[0];
        if (inputs.faceOn()) watchFacePins();
        if (character != null) parts.show(character, inHand, stageIndex);
    }

    @Override
    public void moveFacePin(String nodeId, String pinId, double x, double y) {
        edit.apply(getString(R.string.avatar_step_facepin_move), d -> {
            org.json.JSONObject mesh = d.find(nodeId).json.getJSONObject("mesh");
            org.json.JSONArray pins = mesh.getJSONArray("pins");
            for (int i = 0; i < pins.length(); i++) {
                org.json.JSONObject q = pins.getJSONObject(i);
                if (pinId.equals(q.optString("id"))) { q.put("x", Math.round(x)); q.put("y", Math.round(y)); }
            }
        });
    }

    @Override
    public void facePinStrength(String id, double v) {
        edit.apply(getString(R.string.avatar_step_facepin_strength), d -> cc.joycreator.avatar.face.FacePins.strength(d.json, id, v));
    }

    @Override
    public void removeFacePin(String id) {
        edit.apply(getString(R.string.avatar_step_facepin_remove), d -> cc.joycreator.avatar.face.FacePins.unbind(d.json, id));
        if (id.equals(pickedPin)) pickedPin = null;
        if (character != null) parts.show(character, inHand, stageIndex);
    }

    @Override
    public void allFacePins(double g) {
        edit.apply(getString(R.string.avatar_step_facepins_all), d -> cc.joycreator.avatar.face.FacePins.setGain(d.json, d.json.getJSONObject("root"), g));
    }

    /** Face pins still on a single slice of a head (made before one piece) move onto the head's whole drawing, where they show. */
    @Override
    public void facePinsOnePiece() {
        if (character == null) return;
        final double k = facePinScale();
        java.util.List<double[]> at = new java.util.ArrayList<>();
        java.util.List<String[]> todo = new java.util.ArrayList<>();
        org.json.JSONArray l = cc.joycreator.avatar.face.FacePins.list(character.json);
        for (int i = 0; i < l.length(); i++) {
            org.json.JSONObject q = l.optJSONObject(i);
            CharacterDoc.Node n = character.find(q.optString("node"));
            org.json.JSONObject sh = n == null ? null : cc.joycreator.avatar.face.FacePins.sheetOf(character.json, n.json);
            if (n == null || sh == null || sh == n.json) continue;
            org.json.JSONObject pin = null;
            org.json.JSONObject mesh = n.json.optJSONObject("mesh");
            org.json.JSONArray pins = mesh == null ? null : mesh.optJSONArray("pins");
            for (int j = 0; pins != null && j < pins.length(); j++) if (q.optString("pin").equals(pins.optJSONObject(j).optString("id"))) pin = pins.optJSONObject(j);
            double[] m = glStage.matrix(n.id), ms = glStage.matrix(sh.optString("id"));
            if (pin == null || m == null || ms == null) continue;
            double px = m[0] * pin.optDouble("x") + m[2] * pin.optDouble("y") + m[4], py = m[1] * pin.optDouble("x") + m[3] * pin.optDouble("y") + m[5];
            double det = ms[0] * ms[3] - ms[1] * ms[2];
            if (Math.abs(det) < 1e-9) continue;
            double ix = px - ms[4], iy = py - ms[5];
            at.add(new double[]{(ms[3] * ix - ms[2] * iy) / det, (-ms[1] * ix + ms[0] * iy) / det, q.optInt("point"), q.has("amt") ? q.optDouble("amt", 1) : 1});
            todo.add(new String[]{q.optString("id"), sh.optString("id")});
        }
        if (todo.isEmpty()) return;
        edit.apply(getString(R.string.avatar_step_facepins_one_piece), d -> {
            for (int i = 0; i < todo.size(); i++) {
                double[] t = at.get(i);
                cc.joycreator.avatar.face.FacePins.unbind(d.json, todo.get(i)[0]);
                org.json.JSONObject sh = d.find(todo.get(i)[1]).json;
                cc.joycreator.avatar.face.FacePins.sheetMesh(sh);
                org.json.JSONObject pin = cc.joycreator.avatar.file.Reshape.dropPin(sh, null, t[0], t[1], (nid, px, py, w) -> glStage.warpAt(nid, px, py, w));
                String nid = cc.joycreator.avatar.face.FacePins.bind(d.json, sh, pin.getString("id"), (int) t[2], true, k);
                cc.joycreator.avatar.face.FacePins.strength(d.json, nid, t[3]);
            }
        });
        if (character != null) parts.show(character, inHand, stageIndex);
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
        for (int v : cc.joycreator.avatar.face.MaskDrive.watch(maskSet)) pts.add(v);   // FACE-2: the face mask's points too
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
        boolean depth = walker.canWalk() && (faceWalk.closer() || faceWalk.upClose() || (stick != null && stick.depthOn()));
        cc.joycreator.avatar.perform.DepthWalk.prep(walker, depth, character.json, bodyBox);
        double h = bodyBox == null ? character.json.optDouble("h", 512) : bodyBox[3] - bodyBox[1];
        double lim = 0.7 * (bodyBox == null ? character.json.optDouble("w", 512) : bodyBox[2] - bodyBox[0]);
        boolean replay = inputs.recorder.playing() && !inputs.recorder.recording();
        if (legsOf != walker) {
            legs = new cc.joycreator.avatar.face.WalkerLegs(walker);
            legsOf = walker;
        }
        faceWalk.step(dt, inputs.faceNow(), legs, h, lim, replay);
        // room for it to come close: below the feet and either side (the pilot's Perform fit while depth is in use)
        boolean room = walker.z != null && bodyBox != null;
        stage.setRoom(room ? (float) (0.495 * h) : 0, room ? (float) (lim) : 0);
    }

    @Override
    public boolean faceMirror() {                               // (on = like your reflection: the engine's flag is the opposite)
        return cc.joycreator.avatar.face.MirrorChip.on(faceCapture == null ? null : faceCapture.tuning());
    }

    @Override
    public void setFaceMirror(boolean on) {
        if (faceCapture == null) return;
        com.fadcam.ui.faditor.avatar.face.FaceTuning t = faceCapture.tuning();
        cc.joycreator.avatar.face.MirrorChip.set(t, on);
        faceCapture.setTuning(t);
    }

    // ── shoulder shrugs (SHRUG-1: the pilot's al-pose.js) ──────────────────────────────────────────────────────

    private boolean shrugTried, shoulderHinted;

    /** The face capture, made when first asked for (its settings are this phone's; the camera starts only with Face). */
    private com.fadcam.ui.faditor.avatar.face.FaceCapture faceCaptureNow() {
        if (faceCapture == null) {
            faceCapture = new com.fadcam.ui.faditor.avatar.face.FaceCapture(this);
            faceCapture.setOnCamerasKnown(() -> {                   // (cameras per tracker: the chips can show now)
                if (stageIndex >= 2 && character != null) performPanel.bind(character, stageIndex == 3);
            });
            syncCameras();
        }
        return faceCapture;
    }

    // ── cameras per tracker (the owner, 11-27: one person acts the body, another does the face) ─────────────

    @Override
    public java.util.List<String> cameraChoices() {
        return faceCaptureNow().cameraChoices();
    }

    @Override
    public String faceCamera() {
        org.json.JSONObject st = character == null ? null : character.json.optJSONObject("settings");
        return st == null ? com.fadcam.ui.faditor.avatar.face.CameraPlan.FRONT : st.optString("faceCamera", com.fadcam.ui.faditor.avatar.face.CameraPlan.FRONT);
    }

    @Override
    public String bodyCamera() {
        org.json.JSONObject st = character == null ? null : character.json.optJSONObject("settings");
        return st == null ? faceCamera() : st.optString("bodyCamera", faceCamera());   // (one camera for both until chosen)
    }

    @Override
    public void setCamera(boolean body, String key) {
        int word = "back-wide".equals(key) ? R.string.avatar_cam_back_wide : "back".equals(key) ? R.string.avatar_cam_back : R.string.avatar_cam_front;
        edit.apply(getString(body ? R.string.avatar_step_body_camera : R.string.avatar_step_face_camera, getString(word)),
                d -> setPath(d.json, body ? "settings.bodyCamera" : "settings.faceCamera", key));   // (the step's listener opens it and redraws the chips)
    }

    @Override
    public boolean camerasRefused() {
        return faceCapture != null && faceCapture.camerasRefused();
    }

    private String camerasSeen;

    /** The face capture follows the character's camera choices (a step, an undo, another character); the panel too. */
    private void syncCameras() {
        if (faceCapture == null || character == null) return;
        String now = faceCamera() + "/" + bodyCamera();
        faceCapture.setCameras(faceCamera(), bodyCamera());
        if (camerasSeen != null && !camerasSeen.equals(now) && stageIndex >= 2) performPanel.bind(character, stageIndex == 3);
        camerasSeen = now;
    }

    @Override
    public boolean shouldersShown() {
        return character != null && faceCaptureNow().canReadShoulders() && cc.joycreator.avatar.face.Shrugs.drives(character.json);
    }

    @Override
    public boolean shouldersOn() {
        return faceCaptureNow().shouldersOn();
    }

    @Override
    public void setShouldersOn(boolean on) {
        faceCaptureNow().setShouldersOn(on);
        shoulderHinted = false;
    }

    @Override
    public float shoulderSensitivity() {
        return faceCaptureNow().shoulderSensitivity();
    }

    @Override
    public void setShoulderSensitivity(float v) {
        faceCaptureNow().setShoulderSensitivity(v);
    }

    @Override
    public void setShrugs(boolean on, String stepName) {
        edit.apply(stepName, d -> cc.joycreator.avatar.face.Shrugs.setOn(d.json, on, shrugBounds()));
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    @Override
    public void setShrugAmount(boolean arms, double px, String stepName) {
        edit.apply(stepName, d -> cc.joycreator.avatar.face.Shrugs.setAmount(d.json, arms, px));
        if (stageIndex == 2) performPanel.bind(character, false);
    }

    @Override
    public void tryShrug(double v) {
        inputs.tryShrug(v);
    }

    private cc.joycreator.avatar.face.Shrugs.Bounds shrugBounds() {
        return n -> {
            CharacterDoc.Node k = character.find(n.optString("id"));
            cc.joycreator.avatar.ui.PoseRenderer r = stage.renderer();
            return k == null || r == null ? null : r.restBounds(k);
        };
    }

    /**
     * Each frame while the camera reads the shoulders: the first time they are seen, the character is set up to shrug (one
     * undo step, said in a toast); out of the picture for a while, a word on how to bring them in (once per tick of Shoulders).
     */
    private void watchShoulders() {
        com.fadcam.ui.faditor.avatar.face.FaceCapture fc = faceCapture;
        if (fc == null || !fc.shouldersOn() || !fc.canReadShoulders() || character == null) return;
        if (fc.shouldersSeen()) {
            if (shrugTried) return;
            shrugTried = true;
            if (cc.joycreator.avatar.face.Shrugs.canRig(character.json)
                    && edit.apply(getString(R.string.avatar_step_shrugs_set_up), d -> cc.joycreator.avatar.face.Shrugs.rig(d.json, shrugBounds())))
                Toast.makeText(this, getString(R.string.avatar_shrugs_set_up, character.name.isEmpty() ? getString(R.string.avatar_untitled) : character.name),
                        Toast.LENGTH_LONG).show();
        } else if (!shoulderHinted && fc.shouldersUnseenSeconds() > 2.5) {
            shoulderHinted = true;
            Toast.makeText(this, R.string.avatar_shoulders_hint, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public View facePreview() {
        if (facePreview == null) facePreview = new com.fadcam.ui.faditor.avatar.face.FacePreviewView(this);
        return facePreview;
    }

    private void faceOn() {
        try {
            faceCaptureNow();
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
        cc.joycreator.avatar.ui.StickSheet.show(this, stick, characterName(), new cc.joycreator.avatar.ui.StickSheet.Host() {
            @Override
            public void setSlot(String slot, String key, String step) {
                edit.apply(step, doc -> cc.joycreator.avatar.perform.Stick.setSlot(doc.json, slot, key));
            }

            @Override
            public void setDepth(boolean on, String step) {
                edit.apply(step, doc -> cc.joycreator.avatar.perform.Stick.setDepth(doc.json, on));
            }

            @Override
            public void setAngle(int deg, String step) {
                edit.apply(step, doc -> cc.joycreator.avatar.perform.Stick.setAngle(doc.json, deg));
            }
        });
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
        if (sceneRoom != null && sceneRoom.isOpen() && sceneRoom.hand() != null) {      // in a scene the take belongs to the scene
            sceneRoom.takeKept(inputs.recorder.take());
            return;
        }
        if (edit == null) return;
        final Take t = inputs.recorder.take();
        edit.apply(stepName, doc -> {
            if (t == null || t.n == 0) doc.json.remove("take");
            else doc.json.put("take", t.toJson(doc.json.optJSONObject("take")));          // (a take's voice stays through a pass)
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
        if (sceneUi != null) sceneUi.tick(dt);                  // the frame, the strip and the shot's camera (slice 3)
        if (stageIndex < 2) {                                   // building: the still pose, nothing moving (but Try: talk)
            stage.setSignals(null);
            if (testMouth > 0.001) {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("mouth", testMouth);
                if (testVis != null) m.put("vis", testVis);
                glStage.frame(cc.joycreator.avatar.ui.SignalMap.toSignals(m, inputs.time(), 0), true, dt, stage.viewScale(), stage.viewX(), stage.viewY());
                return;
            }
            glStage.frame(null, false, dt, stage.viewScale(), stage.viewX(), stage.viewY());
            return;
        }
        if (inputs.micOn() && mic != null) {
            float db = mic.currentAudioDb();
            int cls = mic.currentVisemeClassIndex();
            inputs.micHeard(Float.isNaN(db) ? 0 : Math.max(0, Math.min(1, (db + 50) / 40)), cls >= 0 && cls < MIC_SHAPES.length ? MIC_SHAPES[cls] : "Neutral");
        }
        if (inputs.faceOn() && faceCapture != null) {
            inputs.faceSaw(faceCapture.sample(System.nanoTime()));
            inputs.faceDrive().mask(maskSet, faceMirror(), faceCapture.restPoints());   // FACE-2: the mask, as Mirror and your neutral are now
            watchShoulders();                                   // SHRUG-1: set up to shrug when your shoulders are first seen
        }
        java.util.Map<String, Object> sig = inputs.frame(dt, System.currentTimeMillis());
        lastSignals = sig;
        stage.setSignals(sig);
        cc.joycreator.avatar.solve.Signals S = cc.joycreator.avatar.ui.SignalMap.toSignals(sig, inputs.time(), inputs.breath());
        if ((frameNo++ & 31) == 0) stickSize();
        if (stick != null) stick.step(dt);
        if (walker != null) faceWalkStep(dt);
        if (dirPrompts != null) {                              // Check directions moves on as each move is read
            if (dirPrompts.step(System.nanoTime()) || !inputs.faceOn()) finishDirections();
            else showDirection();
        }
        if (walker != null && (walker.canWalk() || walker.travels())) {
            walker.step(dt, S, glStage.anchors(), glStage.tips());
            S.flip = inputs.flip();
        }
        if (sceneRoom != null && sceneRoom.isOpen()) sceneRoom.handSignals(S);       // the scene draws the one in hand from these
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
        rail.show(character, inHand);
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
        rail.show(character, inHand);
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

    /**
     * Saves one character in the folder, one save at a time (the folder has one thread), reading where it was saved before at the
     * moment its turn comes: two saves of a never-saved character make one file, not two. {@code done} gets the path, or null.
     */
    private void saveDoc(final CharacterDoc d, final String startPath, final java.util.function.Consumer<String> done) {
        JoyFolderHome.io().execute(() -> {
            String rel = ledger.run(d, startPath, (doc, path) -> CharacterStore.save(this, doc, path));
            main.post(() -> done.accept(rel));
        });
    }

    /** The character on the stage, saved now: its path is kept only while it is still the one on the stage. */
    private void saveCurrent(final java.util.function.Consumer<String> after) {
        final CharacterDoc d = character;
        if (d == null) return;
        final int stamp = editStamp;
        saveDoc(d, characterPath, rel -> {
            if (rel != null && character == d) {
                characterPath = rel;
                if (editStamp == stamp) {
                    unsaved = false;
                    paintSaveDot();
                } else if (unsaved) {                                   // more edits came while it saved: again in a moment
                    main.removeCallbacks(autosave);
                    main.postDelayed(autosave, 1500);
                }
            }
            if (after != null) after.accept(rel);
        });
    }

    private void saveQuietly() {
        saveCurrent(null);
    }

    /** Puts another character on the stage. The one that was there is saved first if it has changes (edits younger than the autosave included). */
    private void adopt(CharacterDoc next, String path, boolean isUnsaved) {
        main.removeCallbacks(autosave);
        clearTried();
        if (unsaved && character != null && character != next) saveDoc(character, characterPath, rel -> {
        });
        character = next;
        characterPath = path;
        unsaved = isUnsaved;
        ledger.adopt(next, path);
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
            adopt(CharacterAssets.load(this, asset), null, false);
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
                adopt(d, entry.path, false);
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
    public void newFromSheet() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            startActivityForResult(i, REQ_SHEET);
        } catch (Exception e) {
            Log.w(TAG, "no file picker", e);
        }
    }

    /** The sheet is read (down to a size the phone can work on), its plain background taken away, its drawings found; the cutter opens over the room. */
    private void sheetDoor(final Uri uri) {
        JoyFolderHome.io().execute(() -> {
            android.graphics.Bitmap b = null;
            cc.joycreator.avatar.intake.SheetCutter.Sheet sheet = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    byte[] raw = readAll(in);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 2000) sample *= 2;
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = sample;
                    o.inMutable = true;
                    android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                    if (src != null) {
                        int w = src.getWidth(), h = src.getHeight();
                        int[] px = new int[w * h];
                        src.getPixels(px, 0, w, 0, 0, w, h);
                        sheet = cc.joycreator.avatar.intake.SheetCutter.read(px, w, h);
                        b = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
                        src.recycle();
                    }
                }
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "sheet not read", e);
            }
            final android.graphics.Bitmap pic = b;
            final cc.joycreator.avatar.intake.SheetCutter.Sheet read = sheet;
            main.post(() -> {
                if (isFinishing()) return;
                if (pic == null || read == null) {
                    Toast.makeText(this, R.string.avatar_no_pictures, Toast.LENGTH_LONG).show();
                    return;
                }
                openCutter(read, pic);
            });
        });
    }

    private void openCutter(final cc.joycreator.avatar.intake.SheetCutter.Sheet sheet, final android.graphics.Bitmap pic) {
        final cc.joycreator.avatar.ui.SheetCutterView[] cv = new cc.joycreator.avatar.ui.SheetCutterView[1];
        cv[0] = new cc.joycreator.avatar.ui.SheetCutterView(this, sheet, pic, new cc.joycreator.avatar.ui.SheetCutterView.Listener() {
            @Override
            public void make(java.util.List<cc.joycreator.avatar.intake.SheetCutter.Piece> pieces, String zone, String group) {
                ((ViewGroup) cv[0].getParent()).removeView(cv[0]);
                pic.recycle();
                finishSheet(pieces, zone, group);
            }

            @Override
            public void cancel() {
                ((ViewGroup) cv[0].getParent()).removeView(cv[0]);
                pic.recycle();
            }
        });
        addContentView(cv[0], new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        doorView = cv[0];
        doorBitmap = pic;
    }

    /** The cut drawings become a new character: each a picture, laid out in Extras, ready to be told what it is. */
    private void finishSheet(final java.util.List<cc.joycreator.avatar.intake.SheetCutter.Piece> pieces, final String zone, final String group) {
        JoyFolderHome.io().execute(() -> {
            CharacterDoc made = null;
            try {
                java.util.ArrayList<cc.joycreator.avatar.file.LooseCharacter.Pic> pics = new java.util.ArrayList<>();
                int n = 0;
                for (cc.joycreator.avatar.intake.SheetCutter.Piece p : pieces) {
                    android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(p.px, p.w, p.h, android.graphics.Bitmap.Config.ARGB_8888);
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
                    b.recycle();
                    n++;
                    pics.add(new cc.joycreator.avatar.file.LooseCharacter.Pic(getString(R.string.avatar_part_n, n), p.w, p.h,
                            "data:image/png;base64," + android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP)));
                }
                made = CharacterOpen.read(cc.joycreator.avatar.intake.SheetParts.build(getString(R.string.avatar_untitled), pics, zone, group).toString());
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "character not made", e);
            }
            final CharacterDoc result = made;
            main.post(() -> {
                if (isFinishing()) return;
                if (result == null) {
                    Toast.makeText(this, R.string.avatar_no_pictures, Toast.LENGTH_LONG).show();
                    return;
                }
                adopt(result, null, true);
                inHand = null;
                showCharacter();
                setLayersShown(true);
                main.postDelayed(autosave, 1500);
            });
        });
    }

    @Override
    public void newFromWholeDrawing() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            startActivityForResult(i, REQ_WHOLE);
        } catch (Exception e) {
            Log.w(TAG, "no file picker", e);
        }
    }

    /** The whole drawing is read (down to a size the phone can pin on), its see-through edges trimmed, and the pin wizard opens over the room. */
    private void wholeDoor(final Uri uri) {
        JoyFolderHome.io().execute(() -> {
            android.graphics.Bitmap b = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    byte[] raw = readAll(in);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1400) sample *= 2;
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = sample;
                    b = trimmed(android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o));
                }
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "drawing not read", e);
            }
            final android.graphics.Bitmap pic = b;
            main.post(() -> {
                if (isFinishing()) return;
                if (pic == null) {
                    Toast.makeText(this, R.string.avatar_no_pictures, Toast.LENGTH_LONG).show();
                    return;
                }
                openPinWizard(pic);
            });
        });
    }

    /** The picture without its see-through edges, or null when nothing is drawn on it. */
    private static android.graphics.Bitmap trimmed(android.graphics.Bitmap b) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight(), x0 = w, y0 = h, x1 = -1, y1 = -1;
        int[] px = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(px, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) if ((px[x] >>> 24) > 10) {
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (x1 < 0) return null;
        return android.graphics.Bitmap.createBitmap(b, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private void openPinWizard(final android.graphics.Bitmap pic) {
        final String name = getString(R.string.avatar_untitled);
        final cc.joycreator.avatar.ui.PinWizard[] wiz = new cc.joycreator.avatar.ui.PinWizard[1];
        wiz[0] = new cc.joycreator.avatar.ui.PinWizard(this, pic, name, new cc.joycreator.avatar.ui.PinWizard.Listener() {
            @Override
            public void done(java.util.List<cc.joycreator.avatar.intake.WholeDrawing.Pin> pins) {
                ((ViewGroup) wiz[0].getParent()).removeView(wiz[0]);
                finishWhole(pic, name, pins);
            }

            @Override
            public void cancel() {
                ((ViewGroup) wiz[0].getParent()).removeView(wiz[0]);
                pic.recycle();
            }
        });
        addContentView(wiz[0], new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        doorView = wiz[0];
        doorBitmap = pic;
    }

    private void finishWhole(final android.graphics.Bitmap pic, final String name, final java.util.List<cc.joycreator.avatar.intake.WholeDrawing.Pin> pins) {
        JoyFolderHome.io().execute(() -> {
            CharacterDoc made = null;
            try {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                pic.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
                String url = "data:image/png;base64," + android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP);
                org.json.JSONObject doc = cc.joycreator.avatar.intake.WholeDrawing.build(name, pic.getWidth(), pic.getHeight(), url);
                cc.joycreator.avatar.intake.WholeDrawing.pin(doc, pins);
                made = CharacterOpen.read(doc.toString());
            } catch (Exception e) {
                Log.w(TAG, "character not made", e);
            }
            pic.recycle();
            final CharacterDoc result = made;
            main.post(() -> {
                if (isFinishing()) return;
                if (result == null) {
                    Toast.makeText(this, R.string.avatar_no_pictures, Toast.LENGTH_LONG).show();
                    return;
                }
                adopt(result, null, true);
                inHand = null;
                showCharacter();
                Toast.makeText(this, R.string.avatar_wizard_pinned, Toast.LENGTH_LONG).show();
                main.postDelayed(autosave, 1500);
            });
        });
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
                adopt(result, null, true);
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
                adopt(result, null, true);
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
        if (req == REQ_SCENEPIC && res == RESULT_OK && data != null && sceneRoom != null) {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            sceneRoom.picturesChosen(scenePicKind, uris);
            return;
        }
        if (req == REQ_SHEET && res == RESULT_OK && data != null && data.getData() != null) {
            sheetDoor(data.getData());
            return;
        }
        if (req == REQ_WHOLE && res == RESULT_OK && data != null && data.getData() != null) {
            wholeDoor(data.getData());
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
                adopt(opened, null, true);                // not in the folder yet: Save puts it there
                showCharacter();
            });
        });
    }

    @Override
    public void saveNow() {
        saveCurrent(rel -> {
            if (isFinishing()) return;
            Toast.makeText(this, rel == null ? R.string.avatar_cannot_save : R.string.avatar_saved, rel == null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        });
    }
}
