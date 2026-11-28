package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterDoc;
import org.json.JSONObject;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Parts" (spec A2): the character part by part. A row of the parts the file names (head, arms, legs, tail ...), and
 * the card for the part in hand, which follows the four stages: Set up shows the part's drawings (a tap tries one on
 * the picture without changing the file), Arrange shows the stack (in front or behind), Motion and Perform say what
 * they are for. The big labelled body map replaces the row later; the card stays.
 */
public final class PartsView extends ScrollView {

    public interface Host {
        void inHand(String id);

        /** Look at one drawing of a set on the stage without changing the file; kid null puts it back. */
        void tryDrawing(String setId, String kidId);

        /** One step in the stack: -1 back one, +1 forward one, Integer.MIN_VALUE very back, Integer.MAX_VALUE very front. */
        void restack(String id, int by);

        /** The ids of what is drawn now, back to front (the picture's own draw list); empty before the first picture. */
        default List<String> drawOrder() {
            return new ArrayList<>();
        }

        /** In front or behind for a whole part or a part in a stance: one named undo step. */
        default void stackEdit(String stepName, String nodeId, cc.joycreator.avatar.file.Stack.Step step) {
        }

        /** Reshape with pins is on for the part in hand. */
        default boolean reshaping() {
            return false;
        }

        default void reshape(boolean on) {
        }

        /** "removeLast" (the last pin dropped in this pose) or "again" (every pin back). */
        default void reshapeAction(String what) {
        }

        /** "squash" (flatten shut, instead of a hinge) or "everywhere" (this way of talking at every head angle): one named undo step. */
        default void mouthAction(String nodeId, String action) {
        }

        /** Try: talk, on Set up: the mouth open this much (0 = off), shown on the stage without a step. */
        default void tryMouth(double v) {
        }

        /** What Motion and the eyes' card write through (null: none). */
        default MotionSections.Host motion() {
            return null;
        }

        /** The stance row and card on Arrange. */
        StanceCard.Host stances();

        /** "Put it back": clears the part's move, turn and size, as one step. */
        void putBack(String id);

        /** Guess pins again from the pixels: "fan" (a limb's drawings), "mouth", "swing"; one named undo step. */
        void guessPins(String nodeId, String kind);

        /** Match the angles of this head (the landmarks screen). */
        void openLandmarks(String setId);

        /** Face pins for the part in hand (the face session's section), or null where they do not belong. */
        default android.view.View facePinsSection(CharacterDoc.Node node) {
            return null;
        }
    }

    private final Host host;
    private final Thumbs thumbs = new Thumbs();
    private final float dp;
    private final LinearLayout column;
    private String trying;   // the drawing being tried, per the part in hand

    public PartsView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(px(12), px(4), px(12), px(16));
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    public void show(CharacterDoc doc, String inHandId, int stage) {
        thumbs.use(doc);
        column.removeAllViews();

        // the parts the file names, once each, in the order they are drawn
        final List<CharacterDoc.Node> parts = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        doc.walk((n, depth, parent) -> {
            if (!n.role.isEmpty() && seen.add(n.role)) parts.add(n);
        });
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(getContext());
        chips.setPadding(0, px(6), 0, px(10));
        for (CharacterDoc.Node n : parts) chips.addView(chip(RoleNames.of(n.role, n.name), n.id.equals(inHandId), view -> host.inHand(n.id),
                getContext().getString(R.string.avatar_hover_part, RoleNames.of(n.role, n.name))));
        scroll.addView(chips);
        column.addView(scroll);

        CharacterDoc.Node node = inHandId == null ? null : doc.find(inHandId);
        if (node == null) {
            column.addView(body(getContext().getString(R.string.avatar_pick_a_part)));
            return;
        }
        column.addView(heading(RoleNames.of(node.role, node.name)));
        switch (stage) {
            case 0:
                setUp(doc, node);
                break;
            case 1:
                arrange(doc, node);
                break;
            case 2:
                column.addView(body(getContext().getString(R.string.avatar_ask_stage_3)));
                break;
            default:
                column.addView(body(getContext().getString(R.string.avatar_ask_stage_4)));
        }
    }

    // ── Set up: its drawings ──────────────────────────────────────────────────────────────────────

    private void setUp(CharacterDoc doc, CharacterDoc.Node node) {
        if (EyeSections.isEye(node) && host.motion() != null) EyeSections.add(getContext(), column, doc, host.motion(), dp);
        if (MouthSections.isMouth(node) && host.motion() != null) MouthSections.add(getContext(), column, node, host.motion(), host, dp);
        column.addView(body(getContext().getString(R.string.avatar_pins_hint)));
        // pins guessed from the pixels, one tap to guess again
        LinearLayout guess = new LinearLayout(getContext());
        if ("reach".equals(node.pickBy))
            guess.addView(chip(getContext().getString(R.string.avatar_guess_pins), false, v -> host.guessPins(node.id, "fan"), getContext().getString(R.string.avatar_hover_guess_fan)));
        if ("mouth".equals(node.role))
            guess.addView(chip(getContext().getString(R.string.avatar_guess_hinge), false, v -> host.guessPins(node.id, "mouth"), getContext().getString(R.string.avatar_hover_guess_mouth)));
        if (node.json.optJSONObject("swing") != null)
            guess.addView(chip(getContext().getString(R.string.avatar_guess_pins), false, v -> host.guessPins(node.id, "swing"), getContext().getString(R.string.avatar_hover_guess_swing)));
        if (guess.getChildCount() > 0) column.addView(guess);
        android.view.View facePins = host.facePinsSection(node);                    // (a point of your face pulls a pin: FACE-1)
        if (facePins != null) column.addView(facePins);
        if (!"set".equals(node.type) || node.kids.isEmpty()) {
            column.addView(body(getContext().getString(R.string.avatar_ask_stage_1)));
            return;
        }
        column.addView(label(getContext().getString(R.string.avatar_its_drawings)));
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        for (CharacterDoc.Node k : node.kids) {
            final String name = k.name.isEmpty() ? k.id : k.name;
            boolean on = k.id.equals(trying);
            row.addView(tile(thumbOf(k), name, on, v -> {
                trying = k.id.equals(trying) ? null : k.id;
                host.tryDrawing(node.id, trying);
                show(doc, node.id, 0);
            }, getContext().getString(R.string.avatar_hover_try, name)));
        }
        scroll.addView(row);
        column.addView(scroll);
        column.addView(body(getContext().getString(R.string.avatar_try_note)));
        if ("turn".equals(node.pickBy) && node.kids.size() > 1) {
            LinearLayout lm = new LinearLayout(getContext());
            lm.addView(chip(getContext().getString(R.string.avatar_marks_title), false, v -> host.openLandmarks(node.id), getContext().getString(R.string.avatar_hover_marks_open)));
            column.addView(lm);
        }
    }

    // ── Arrange: in front or behind ───────────────────────────────────────────────────────────────

    private void arrange(CharacterDoc doc, CharacterDoc.Node node) {
        final CharacterDoc.Node[] parent = {null};
        doc.walk((n, d, p) -> {
            if (n.id.equals(node.id)) parent[0] = p;
        });
        if (parent[0] == null) return;
        StanceCard.add(getContext(), column, doc, host.stances(), dp);
        final List<String> drawn = host.drawOrder();
        final String poseId = host.stances().poseEditing();
        cc.joycreator.avatar.file.Stack.Read stackRead = drawn.isEmpty() ? null : cc.joycreator.avatar.file.Stack.read(doc.json, drawn, node.id);
        if (stackRead != null && stackRead.mine(node.json) >= 0 && (poseId != null || stackRead.whole)) {
            stackSection(doc, node, stackRead, poseId);
            LinearLayout box2 = new LinearLayout(getContext());
            box2.setPadding(0, px(8), 0, 0);
            box2.addView(chip(getContext().getString(R.string.avatar_put_back), false, v -> host.putBack(node.id), getContext().getString(R.string.avatar_hover_put_back)));
            column.addView(label(getContext().getString(R.string.avatar_size_and_turn)));
            column.addView(body(getContext().getString(R.string.avatar_box_hint)));
            column.addView(box2);
            reshapeSection(doc, node, poseId);
            return;
        }
        column.addView(label(getContext().getString(R.string.avatar_in_front_or_behind)));
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        for (CharacterDoc.Node k : parent[0].kids) {
            String name = k.name.isEmpty() ? k.id : k.name;
            row.addView(tile(thumbOf(k), name, k.id.equals(node.id), v -> host.inHand(k.id), getContext().getString(R.string.avatar_hover_layer, name)));
        }
        scroll.addView(row);
        column.addView(scroll);
        LinearLayout buttons = new LinearLayout(getContext());
        buttons.setPadding(0, px(8), 0, 0);
        LinearLayout box = new LinearLayout(getContext());
        box.setPadding(0, px(8), 0, 0);
        box.addView(chip(getContext().getString(R.string.avatar_put_back), false, v -> host.putBack(node.id), getContext().getString(R.string.avatar_hover_put_back)));
        column.addView(label(getContext().getString(R.string.avatar_size_and_turn)));
        column.addView(body(getContext().getString(R.string.avatar_box_hint)));
        column.addView(box);
        buttons.addView(chip(getContext().getString(R.string.avatar_very_back), false, v -> host.restack(node.id, Integer.MIN_VALUE), getContext().getString(R.string.avatar_hover_very_back)));
        buttons.addView(chip(getContext().getString(R.string.avatar_back_one), false, v -> host.restack(node.id, -1), getContext().getString(R.string.avatar_hover_back_one)));
        buttons.addView(chip(getContext().getString(R.string.avatar_forward_one), false, v -> host.restack(node.id, 1), getContext().getString(R.string.avatar_hover_forward_one)));
        buttons.addView(chip(getContext().getString(R.string.avatar_very_front), false, v -> host.restack(node.id, Integer.MAX_VALUE), getContext().getString(R.string.avatar_hover_very_front)));
        HorizontalScrollView bs = new HorizontalScrollView(getContext());
        bs.setHorizontalScrollBarEnabled(false);
        bs.addView(buttons);
        column.addView(bs);
        reshapeSection(doc, node, poseId);
    }

    /** Reshape with pins: a chip on any drawing or folder (in a stance, only a stance with a button). */
    private void reshapeSection(CharacterDoc doc, CharacterDoc.Node node, String poseId) {
        if ("set".equals(node.type) || "mirror".equals(node.type) || node.json.has("limb")) return;
        JSONObject pose = poseId == null ? null : cc.joycreator.avatar.file.Stances.find(doc.json, poseId);
        if (pose != null && pose.optString("trig").isEmpty()) return;
        final Context c = getContext();
        boolean on = host.reshaping();
        column.addView(label(c.getString(R.string.avatar_reshape_title)));
        LinearLayout row = new LinearLayout(c);
        row.addView(chip(c.getString(R.string.avatar_reshape), on, v -> host.reshape(!on), c.getString(R.string.avatar_hover_reshape)));
        if (on) {
            if (!cc.joycreator.avatar.file.Reshape.ownPins(node.json, poseId).isEmpty())
                row.addView(chip(c.getString(R.string.avatar_reshape_remove), false, v -> host.reshapeAction("removeLast"), c.getString(R.string.avatar_hover_reshape_remove)));
            row.addView(chip(c.getString(R.string.avatar_reshape_again), false, v -> host.reshapeAction("again"), c.getString(R.string.avatar_hover_reshape_again)));
            row.addView(chip(c.getString(R.string.avatar_done), true, v -> host.reshape(false), c.getString(R.string.avatar_hover_reshape_done)));
        }
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        column.addView(hs);
        if (pose != null) column.addView(body(c.getString(R.string.avatar_reshape_stance_note, pose.optString("name"))));
        if (on) {
            boolean any = node.json.optJSONObject("mesh") != null && node.json.optJSONObject("mesh").optJSONArray("pins") != null && node.json.optJSONObject("mesh").optJSONArray("pins").length() > 0;
            column.addView(body(c.getString(any ? R.string.avatar_reshape_hint_have : R.string.avatar_reshape_hint_none)));
        }
    }

    // ── Arrange: in front or behind, for a whole part or in a stance ─────────────────────────────

    private void stackSection(CharacterDoc doc, CharacterDoc.Node node, cc.joycreator.avatar.file.Stack.Read r, String poseId) {
        final Context c = getContext();
        final JSONObject z = cc.joycreator.avatar.file.Stack.getZ(doc.json, poseId, node.json);
        final JSONObject pose = poseId == null ? null : cc.joycreator.avatar.file.Stances.find(doc.json, poseId);
        String title = c.getString(R.string.avatar_in_front_or_behind) + (pose != null ? c.getString(R.string.avatar_in_the_stance, pose.optString("name")) : "");
        column.addView(label(title));
        final int me = r.mine(node.json);
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        TextView back = new TextView(c), front = new TextView(c);
        for (TextView t : new TextView[]{back, front}) {
            t.setTextSize(9);
            t.setTextColor(0xFF71717A);
            t.setGravity(Gravity.CENTER);
        }
        back.setText(R.string.avatar_stack_back);
        front.setText(R.string.avatar_stack_front);
        row.addView(back);
        for (int i = 0; i < r.runs.size(); i++) {
            final cc.joycreator.avatar.file.Stack.Run run = r.runs.get(i);
            CharacterDoc.Node u = doc.find(run.unit.optString("id"));
            if (u == null) continue;
            final String nm = RoleNames.of(u.role, u.name);
            final boolean mine = run.unit == node.json, backOf = i < me;
            String hover = mine ? c.getString(R.string.avatar_stack_me, nm) : c.getString(backOf ? R.string.avatar_stack_behind : R.string.avatar_stack_ahead, RoleNames.of(node.role, node.name), nm);
            row.addView(tile(thumbOf(u), nm, mine, mine ? null : v -> stackDo(node, nm, backOf ? "behind" : "in front of", (ch, pid, n, drawn) -> cc.joycreator.avatar.file.Stack.past(ch, pid, n, run.unit, backOf)), hover));
        }
        row.addView(front);
        scroll.addView(row);
        column.addView(scroll);
        LinearLayout chips = new LinearLayout(c);
        chips.addView(chip(c.getString(R.string.avatar_back_one), false, v -> stackDo(node, null, "back one", (ch, pid, n, d) -> cc.joycreator.avatar.file.Stack.step(ch, pid, d, n, -1)), c.getString(R.string.avatar_hover_back_one)));
        chips.addView(chip(c.getString(R.string.avatar_forward_one), false, v -> stackDo(node, null, "forward one", (ch, pid, n, d) -> cc.joycreator.avatar.file.Stack.step(ch, pid, d, n, 1)), c.getString(R.string.avatar_hover_forward_one)));
        final boolean isBack = z != null && "back".equals(z.optString("how")), isFront = z != null && "front".equals(z.optString("how"));
        chips.addView(chip(c.getString(R.string.avatar_very_back), isBack, v -> stackDo(node, null, "very back", (ch, pid, n, d) -> cc.joycreator.avatar.file.Stack.setZ(ch, pid, n, isBack ? null : new JSONObject().put("how", "back"))), c.getString(R.string.avatar_hover_very_back)));
        chips.addView(chip(c.getString(R.string.avatar_very_front), isFront, v -> stackDo(node, null, "very front", (ch, pid, n, d) -> cc.joycreator.avatar.file.Stack.setZ(ch, pid, n, isFront ? null : new JSONObject().put("how", "front"))), c.getString(R.string.avatar_hover_very_front)));
        if (z != null)
            chips.addView(chip(c.getString(pose != null ? R.string.avatar_as_standing : R.string.avatar_its_own_place), false, v -> stackDo(node, null, "its own place", (ch, pid, n, d) -> cc.joycreator.avatar.file.Stack.setZ(ch, pid, n, null)), c.getString(R.string.avatar_hover_forget_order)));
        HorizontalScrollView cs = new HorizontalScrollView(c);
        cs.setHorizontalScrollBarEnabled(false);
        cs.setPadding(0, px(8), 0, 0);
        cs.addView(chips);
        column.addView(cs);
        if (pose != null && z != null) {                                        // the moment the order changes
            column.addView(label(c.getString(R.string.avatar_changes_over)));
            android.widget.SeekBar sb = new android.widget.SeekBar(c);
            sb.setMax(90);
            sb.setProgress((int) Math.round(z.optDouble("at", 0.5) * 100) - 5);
            sb.getProgressDrawable().setTint(Studio.ARMED);
            sb.getThumb().setTint(Studio.ARMED);
            sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                }

                public void onStartTrackingTouch(android.widget.SeekBar s) {
                }

                public void onStopTrackingTouch(android.widget.SeekBar s) {
                    final double at = (s.getProgress() + 5) / 100.0;
                    stackDo(node, null, "changes over", (ch, pid, n, d) -> {
                        JSONObject w = cc.joycreator.avatar.file.Stack.getZ(ch, pid, n);
                        if (w != null) w.put("at", at);
                    });
                }
            });
            column.addView(sb);
        }
        column.addView(body(c.getString(pose != null ? R.string.avatar_stack_note_stance : R.string.avatar_stack_note)));
    }

    private void stackDo(CharacterDoc.Node node, String targetName, String what, cc.joycreator.avatar.file.Stack.Step step) {
        String nm = RoleNames.of(node.role, node.name);
        host.stackEdit(getContext().getString(R.string.avatar_stack_step, nm, what + (targetName == null ? "" : " " + targetName)), node.id, step);
    }

    // ── small pieces ──────────────────────────────────────────────────────────────────────────────

    private Bitmap thumbOf(CharacterDoc.Node n) {
        String img = firstImg(n);
        return img == null ? null : thumbs.of(img, 100);
    }

    private static String firstImg(CharacterDoc.Node n) {
        if ("art".equals(n.type)) return n.img;
        for (CharacterDoc.Node k : n.kids) {
            if (k.hidden) continue;
            String i = firstImg(k);
            if (i != null) return i;
        }
        return null;
    }

    private View tile(Bitmap pic, String name, boolean on, View.OnClickListener tap, String hover) {
        LinearLayout t = new LinearLayout(getContext());
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(px(2), px(2), px(2), px(2));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(10));
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        t.setBackground(g);
        ImageView iv = new ImageView(getContext());
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (pic != null) iv.setImageBitmap(pic);
        t.addView(iv, new LinearLayout.LayoutParams(px(46), px(36)));
        TextView n = new TextView(getContext());
        n.setText(name);
        n.setTextSize(9);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setGravity(Gravity.CENTER);
        n.setTextColor(on ? Studio.GROUND : 0xFFA1A1AA);
        t.addView(n, new LinearLayout.LayoutParams(px(46), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(50), px(56));
        lp.rightMargin = px(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    private View chip(String text, boolean on, View.OnClickListener tap, String hover) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(12), 0, px(12), 0);
        t.setTextColor(on ? Studio.GROUND : 0xFFD4D4D8);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(16));
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32));
        lp.rightMargin = px(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    private TextView heading(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(16);
        t.setTextColor(0xFFFAFAFA);
        t.setPadding(0, px(4), 0, px(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(0xFF71717A);
        t.setPadding(0, px(8), 0, px(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(0xFFA1A1AA);
        t.setPadding(0, px(8), 0, px(8));
        return t;
    }
}
