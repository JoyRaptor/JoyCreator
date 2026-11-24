package cc.joycreator.avatar;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.ui.StageView;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

/**
 * Avatar Studio, the character room on the phone (AVA-1, slice 1; tasks/avatar/AVA_PHONE_SPEC.md part A).
 *
 * <p>Views, not Compose (spec call 1). This is the skeleton: the top bar, the stage, the panel with its header and the
 * four stages. Cards, doors, Perform and recording are filled in by the later steps of slice 1; a control appears only
 * when it works (a dead knob is worse than a missing one).</p>
 */
public final class AvatarRoomActivity extends Activity {
    private static final String TAG = "AvatarRoom";

    /** The four stages, in the order the owner reads them (spec A2). The second one's word is one string. */
    private static final int[] STAGE_NAMES = {
            R.string.avatar_stage_1, R.string.avatar_stage_2, R.string.avatar_stage_3, R.string.avatar_stage_4};
    private static final int[] STAGE_HOVER = {
            R.string.avatar_hover_stage_1, R.string.avatar_hover_stage_2, R.string.avatar_hover_stage_3, R.string.avatar_hover_stage_4};
    private static final int[] STAGE_ASKS = {
            R.string.avatar_ask_stage_1, R.string.avatar_ask_stage_2, R.string.avatar_ask_stage_3, R.string.avatar_ask_stage_4};

    private float dp;
    private LinearLayout root;
    private StageView stage;
    private LinearLayout panel;
    private LinearLayout stageRow;
    private TextView card;
    private final TextView[] stageTabs = new TextView[4];
    private CharacterDoc character;
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
        if (character != null) stage.setCharacter(character);
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
        stageRow = buildStageRow();
        panel.addView(stageRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(36)));
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
        // Menu, Undo, Redo, Export and Joybot join the bar as each one works (a dead knob is worse than a missing one).
        return bar;
    }

    private View buildPanelHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(px(12), 0, px(12), 0);
        TextView path = new TextView(this);
        path.setText(character != null && !character.name.isEmpty() ? character.name : getString(R.string.avatar_untitled));
        path.setTextColor(0xFFFAFAFA);
        path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        TooltipCompat.setTooltipText(path, getString(R.string.avatar_hover_path_character));
        h.addView(path);
        return h;
    }

    private LinearLayout buildStageRow() {
        LinearLayout row = new LinearLayout(this);
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
}
