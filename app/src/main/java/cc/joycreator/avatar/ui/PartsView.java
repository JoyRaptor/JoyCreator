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

        /** The stance row and card on Arrange. */
        StanceCard.Host stances();

        /** "Put it back": clears the part's move, turn and size, as one step. */
        void putBack(String id);

        /** Guess pins again from the pixels: "fan" (a limb's drawings), "mouth", "swing"; one named undo step. */
        void guessPins(String nodeId, String kind);

        /** Match the angles of this head (the landmarks screen). */
        void openLandmarks(String setId);
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
