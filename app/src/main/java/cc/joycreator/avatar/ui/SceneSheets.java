package cc.joycreator.avatar.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.scene.RoomPrefs;
import cc.joycreator.avatar.scene.SceneGeometry;
import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The frame's sheet (hold the frame button) and a shot's sheet (hold or tap again on a shot): the pilot's {@code frame.sheet} and
 * {@code shotSheet}. Plain dialogs in the room's own style, redrawn in place so a choice shows at once.
 */
final class SceneSheets {
    private SceneSheets() {
    }

    private static int px(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** ON = solid cyan with the tick punched out; off = a grey well. */
    private static View tick(Context c, boolean on, int label, int sub, Runnable flip) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(c, 6), 0, px(c, 6));
        TextView box = new TextView(c);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(15);
        box.setText(on ? "✓" : "");
        box.setTextColor(Studio.GROUND);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(c, 5));
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        if (!on) g.setStroke(Math.max(1, px(c, 1)), 0xFF52525B);
        box.setBackground(g);
        row.addView(box, new LinearLayout.LayoutParams(px(c, 24), px(c, 24)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(px(c, 10), 0, 0, 0);
        TextView l = new TextView(c);
        l.setText(label);
        l.setTextColor(0xFFE4E4E7);
        l.setTextSize(13);
        words.addView(l);
        if (sub != 0) {
            TextView s = new TextView(c);
            s.setText(sub);
            s.setTextColor(Studio.INK_FAINT);
            s.setTextSize(11.5f);
            words.addView(s);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(v -> flip.run());
        TooltipCompat.setTooltipText(row, c.getString(label));
        return row;
    }

    private static TextView lab(Context c, int text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setAllCaps(true);
        t.setTextSize(10);
        t.setLetterSpacing(0.1f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextColor(Studio.INK_FAINT);
        t.setPadding(0, px(c, 14), 0, px(c, 6));
        return t;
    }

    private static TextView chip(Context c, String text, boolean on, String hover, Runnable fn) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12.5f);
        t.setPadding(px(c, 12), 0, px(c, 12), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(c, 16));
        g.setColor(on ? Studio.ARMED : Studio.RAISED);
        t.setBackground(g);
        t.setTextColor(on ? Studio.GROUND : Studio.INK);
        t.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        t.setOnClickListener(v -> fn.run());
        return t;
    }

    // ── the frame ──
    static void frame(Context c, RoomPrefs prefs, Runnable changed) {
        ScrollView sv = new ScrollView(c);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(c, 16), px(c, 8), px(c, 16), px(c, 8));
        sv.addView(body);
        AlertDialog d = new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.avatar_sc_frame_title).setView(sv)
                .setPositiveButton(R.string.avatar_sc_done, null).create();
        fillFrame(c, body, prefs, changed);
        d.show();
    }

    private static void fillFrame(Context c, LinearLayout body, RoomPrefs prefs, Runnable changed) {
        body.removeAllViews();
        Runnable again = () -> {
            changed.run();
            fillFrame(c, body, prefs, changed);
        };
        body.addView(tick(c, prefs.frame, R.string.avatar_sc_frame_show, R.string.avatar_sc_frame_show_sub, () -> {
            prefs.setFrame(!prefs.frame);
            again.run();
        }));
        body.addView(lab(c, R.string.avatar_sc_shape));
        LinearLayout shapes = new LinearLayout(c);
        for (String s : SceneGeometry.SHAPES) {
            TextView t = chip(c, s, s.equals(prefs.emptyShape), s, () -> {
                prefs.emptyShape = s;
                prefs.setFrame(true);
                again.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(c, 40), 1f);
            lp.rightMargin = px(c, 4);
            shapes.addView(t, lp);
        }
        body.addView(shapes);
        body.addView(lab(c, R.string.avatar_sc_margins));
        LinearLayout m = new LinearLayout(c);
        String[] keys = {"off", "title", "social"};
        int[] names = {R.string.avatar_sc_margin_off, R.string.avatar_sc_margin_title, R.string.avatar_sc_margin_social};
        int[] tips = {R.string.avatar_sc_margin_off_hover, R.string.avatar_sc_margin_title_hover, R.string.avatar_sc_margin_social_hover};
        for (int i = 0; i < 3; i++) {
            final String k = keys[i];
            TextView t = chip(c, c.getString(names[i]), k.equals(prefs.emptySafe), c.getString(tips[i]), () -> {
                prefs.emptySafe = k;
                prefs.setFrame(true);
                again.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(c, 34), 1f);
            lp.rightMargin = px(c, 4);
            m.addView(t, lp);
        }
        body.addView(m);
        body.addView(lab(c, R.string.avatar_sc_size));
        body.addView(tick(c, prefs.air, R.string.avatar_sc_air, R.string.avatar_sc_air_sub, () -> {
            prefs.air = !prefs.air;
            again.run();
        }));
        TextView note = new TextView(c);
        note.setText(R.string.avatar_sc_frame_note);
        note.setTextColor(Studio.INK_DIM);
        note.setTextSize(12.5f);
        note.setPadding(0, px(c, 12), 0, 0);
        body.addView(note);
    }

    // ── a shot ──
    /** {@code s} = {key, name, kind}. Your own shots can be named, framed again or removed; the worked-out ones can be kept as yours. */
    static void shot(Context c, RoomPrefs prefs, String characterKey, String[] s, Runnable changed, Runnable keep, Runnable reframe, String title) {
        boolean own = s[2].equals("own");
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(c, 16), px(c, 8), px(c, 16), px(c, 8));
        TextView note = new TextView(c);
        note.setText(own ? R.string.avatar_sc_shot_note_own : R.string.avatar_sc_shot_note_auto);
        note.setTextColor(Studio.INK_DIM);
        note.setTextSize(12.5f);
        body.addView(note);
        EditText name = null;
        if (own) {
            name = new EditText(c);
            name.setText(s[1]);
            name.setSingleLine(true);
            name.setInputType(InputType.TYPE_CLASS_TEXT);
            name.setTextColor(0xFFE4E4E7);
            name.setContentDescription(c.getString(R.string.avatar_sc_shot_name));
            body.addView(name);
        }
        final EditText nm = name;
        AlertDialog.Builder b = new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(body);
        if (own) {
            b.setNegativeButton(R.string.avatar_sc_shot_remove, (d, w) -> {
                try {
                    JSONArray mine = prefs.shotsOf(characterKey), n = new JSONArray();
                    for (int i = 0; i < mine.length(); i++) if (!s[0].equals(mine.optJSONObject(i).optString("id"))) n.put(mine.get(i));
                    prefs.emptyShots.put(characterKey, n);
                } catch (JSONException ignored) {
                }
                changed.run();
            });
            b.setNeutralButton(R.string.avatar_sc_shot_use, (d, w) -> {
                rename(prefs, characterKey, s[0], nm);
                reframe.run();
            });
            b.setPositiveButton(R.string.avatar_sc_done, (d, w) -> {
                rename(prefs, characterKey, s[0], nm);
                changed.run();
            });
        } else {
            b.setNeutralButton(R.string.avatar_sc_shot_keep, (d, w) -> keep.run());
            b.setPositiveButton(R.string.avatar_sc_done, null);
        }
        b.show();
    }

    private static void rename(RoomPrefs prefs, String characterKey, String id, EditText nm) {
        String v = nm == null ? "" : nm.getText().toString().trim();
        if (v.isEmpty()) return;
        try {
            JSONArray mine = prefs.shotsOf(characterKey);
            for (int i = 0; i < mine.length(); i++) if (id.equals(mine.optJSONObject(i).optString("id"))) mine.optJSONObject(i).put("name", v);
        } catch (JSONException ignored) {
        }
    }
}
