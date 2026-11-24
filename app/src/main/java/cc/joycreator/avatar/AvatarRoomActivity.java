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
public final class AvatarRoomActivity extends Activity implements RoomMenu.Host {
    private static final String TAG = "AvatarRoom";
    private static final int REQ_OPEN = 41;

    /** The four stages, in the order the owner reads them (spec A2). The second one's word is one string. */
    private static final int[] STAGE_NAMES = {
            R.string.avatar_stage_1, R.string.avatar_stage_2, R.string.avatar_stage_3, R.string.avatar_stage_4};
    private static final int[] STAGE_HOVER = {
            R.string.avatar_hover_stage_1, R.string.avatar_hover_stage_2, R.string.avatar_hover_stage_3, R.string.avatar_hover_stage_4};
    private static final int[] STAGE_ASKS = {
            R.string.avatar_ask_stage_1, R.string.avatar_ask_stage_2, R.string.avatar_ask_stage_3, R.string.avatar_ask_stage_4};

    private final Handler main = new Handler(Looper.getMainLooper());
    private float dp;
    private LinearLayout root;
    private StageView stage;
    private LinearLayout panel;
    private TextView pathName;
    private TextView card;
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
        card = new TextView(this);
        card.setTextColor(0xFFD4D4D8);
        card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        card.setPadding(px(16), px(14), px(16), px(14));
        panel.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        // the save dot (spec A2): never says "saved" for a character nobody has saved; says so on a long press or hover
        saveDot = new View(this);
        LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(px(8), px(8));
        dot.leftMargin = px(10);
        bar.addView(saveDot, dot);
        paintSaveDot();
        // Undo, Redo, Export and Joybot join the bar as each one works.
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
        h.addView(pathName);
        return h;
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
        card.setText(STAGE_ASKS[idx]);
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
        stage.setCharacter(character);
        pathName.setText(character.name.isEmpty() ? getString(R.string.avatar_untitled) : character.name);
        paintSaveDot();
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
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
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
