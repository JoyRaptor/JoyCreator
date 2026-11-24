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
import cc.joycreator.avatar.ui.PartsView;
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
public final class AvatarRoomActivity extends Activity implements RoomMenu.Host, LayersView.Host, PartsView.Host {
    private static final String TAG = "AvatarRoom";
    private static final int REQ_OPEN = 41;
    private static final int REQ_PICTURES = 42;
    private static final int REQ_PSD = 43;

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
        left.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF09090B);
        panel.addView(buildPanelHeader(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)));
        panel.addView(buildStageRow(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(36)));
        parts = new PartsView(this, this);
        layers = new LayersView(this, this);
        android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        body.addView(parts, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
            panel.setLayoutParams(new LinearLayout.LayoutParams(px(340), ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            left.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.52f));
            panel.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.48f));
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
        // Export and Joybot join the bar as each one works.
        return bar;
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
        if (layers != null) {
            layers.setVisibility(on ? View.VISIBLE : View.GONE);
            parts.setVisibility(on ? View.GONE : View.VISIBLE);
        }
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
    private void selectStage(int idx) {
        stageIndex = idx;
        for (int i = 0; i < 4; i++) {
            boolean on = i == idx;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(14));
            g.setColor(on ? Studio.ARMED : Color.TRANSPARENT);
            stageTabs[i].setBackground(g);
            stageTabs[i].setTextColor(on ? Studio.GROUND : 0xFFA1A1AA);
        }
        if (character != null) parts.show(character, inHand, stageIndex);
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
        edit.reset(character);
        pathName.setText(character.name.isEmpty() ? getString(R.string.avatar_untitled) : character.name);
        paintSaveDot();
    }

    /** RoomEdit says the character changed (a step, an undo, a redo, or a new one on the stage). */
    private void edited(CharacterDoc d) {
        character = d;
        stage.setCharacter(d);
        stage.setSelected(inHand);
        layers.show(d, inHand);
        parts.show(d, inHand, stageIndex);
        paintUndo();
        if (fromEdit) {
            unsaved = true;
            paintSaveDot();
            main.removeCallbacks(autosave);
            main.postDelayed(autosave, 1500);   // it saves by itself in a moment
        }
        fromEdit = true;
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
        inHand = id.equals(inHand) ? null : id;
        stage.setSelected(inHand);
        layers.show(character, inHand);
        parts.show(character, inHand, stageIndex);
    }

    @Override
    public void tryDrawing(String setId, String kidId) {
        stage.tryDrawing(setId, kidId);
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
    public void openTopps() {
        try {
            character = CharacterAssets.load(this, CharacterAssets.TOPPS);
            characterPath = null;
            unsaved = false;
            showCharacter();
        } catch (Exception e) {
            Log.w(TAG, "Topps did not open", e);
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
