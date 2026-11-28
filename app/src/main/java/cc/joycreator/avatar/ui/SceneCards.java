package cc.joycreator.avatar.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterStore;
import cc.joycreator.avatar.scene.Scene;
import cc.joycreator.avatar.scene.SceneGeometry;
import com.fadcam.R;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.ui.faditor.Studio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The scene's panel for the four stages (the pilot's scene cards): Set up (name, set, what is in it, delete), Arrange (the thing in
 * hand: size, how far back, flip, another, remove; add a character), Motion (wind, haze, the thing's sway) and Perform. Plain Views,
 * rebuilt whenever something changes; every control has a hover label and one change is one undo step.
 */
final class SceneCards {
    private final SceneRoom room;
    private final Context c;
    private final float dp;

    SceneCards(SceneRoom room, Context c) {
        this.room = room;
        this.c = c;
        this.dp = c.getResources().getDisplayMetrics().density;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private String s(int id, Object... a) {
        return c.getString(id, a);
    }

    void fill(LinearLayout out, int stage) {
        Scene sc = room.scene();
        if (sc == null) return;
        TextView head = text(sc.name().isEmpty() ? s(R.string.avatar_sc_untitled) : sc.name(), 15, Studio.INK, true);
        out.addView(head, new LinearLayout.LayoutParams(-1, -2));
        if (stage == 0) setUp(out, sc);
        else if (stage == 1) arrange(out, sc);
        else if (stage == 2) motion(out, sc);
        else perform(out, sc);
    }

    // ── Set up ──
    private void setUp(LinearLayout out, Scene sc) {
        label(out, s(R.string.avatar_sc_set_label));
        LinearLayout row = new LinearLayout(c);
        JSONObject made = sc.made();
        String cur = made == null ? "" : made.optString("made");
        for (int i = 0; i < Scene.SET_KEYS.length; i++) {
            final String k = Scene.SET_KEYS[i];
            row.addView(chip(Scene.SET_NAMES[i], k.equals(cur), s(R.string.avatar_sc_set_hover, Scene.SET_NAMES[i]), () -> {
                room.pictures().put("set:" + k, SetPaint.paint(k));
                room.setPicture("set:" + k, room.pictures().get("set:" + k));
                sc.setSet(k, "set:" + k);
                room.edited();
            }));
        }
        row.addView(chip(s(R.string.avatar_sc_set_none), made == null, s(R.string.avatar_sc_set_none_hover), () -> {
            if (sc.made() != null) sc.clearSet();
            room.edited();
        }));
        out.addView(scrolled(row));
        label(out, s(R.string.avatar_sc_inside));
        JSONArray items = sc.items();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            final String id = it.optString("id");
            String kind = Scene.kindName(it.optString("kind"));
            out.addView(rowOf(it.optString("name") + "  ·  " + kind, id.equals(room.selected()), s(R.string.avatar_sc_item_hover, it.optString("name")), () -> room.select(id)));
        }
        LinearLayout acts = new LinearLayout(c);
        acts.addView(chip(s(R.string.avatar_sc_rename), false, s(R.string.avatar_sc_rename_hover), () -> rename(sc)));
        acts.addView(chip(s(R.string.avatar_sc_undo), false, s(R.string.avatar_sc_undo_hover), () -> {
            sc.undo(false);
            room.edited();
        }));
        acts.addView(chip(s(R.string.avatar_sc_leave), false, s(R.string.avatar_sc_leave_hover), () -> room.close(true)));
        acts.addView(chip(s(R.string.avatar_sc_delete), false, s(R.string.avatar_sc_delete_hover), () -> new AlertDialog.Builder(c)
                .setTitle(s(R.string.avatar_sc_delete_title, sc.name()))
                .setMessage(s(R.string.avatar_sc_delete_body))
                .setPositiveButton(s(R.string.avatar_sc_delete), (d, w) -> room.deleteScene())
                .setNegativeButton(android.R.string.cancel, null).show()));
        out.addView(scrolled(acts));
    }

    private void rename(Scene sc) {
        final EditText e = new EditText(c);
        e.setText(sc.name());
        e.setSingleLine();
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(c).setTitle(s(R.string.avatar_sc_rename)).setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    sc.rename(e.getText().toString());
                    room.edited();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    // ── Arrange ──
    private void arrange(LinearLayout out, Scene sc) {
        String id = room.selected();
        JSONObject it = id == null ? null : sc.item(id);
        out.addView(addRow(sc));
        if (it == null) {
            out.addView(text(s(R.string.avatar_sc_arrange_hint), 12, Studio.INK_DIM, false));
            return;
        }
        boolean back = "back".equals(it.optString("kind")) || "front".equals(it.optString("kind"));
        label(out, it.optString("name"));
        out.addView(slider(s(R.string.avatar_sc_size), s(R.string.avatar_sc_size_hover), (int) Math.round(it.optDouble("s", 1) * 100), 20, 300, "%", v -> set(sc, id, "s", v / 100.0)));
        if (!back) {
            out.addView(slider(s(R.string.avatar_sc_back), s(R.string.avatar_sc_back_hover), (int) Math.round(SceneGeometry.clamp(sc.backOf(it.optDouble("y")), 0, 100)), 0, 100, "", v -> set(sc, id, "y", sc.yForBack(v))));
        }
        LinearLayout row = new LinearLayout(c);
        row.addView(chip(s(R.string.avatar_sc_flip), it.optBoolean("flip"), s(R.string.avatar_sc_flip_hover), () -> set(sc, id, "flip", !it.optBoolean("flip"))));
        if (!back) row.addView(chip(s(R.string.avatar_sc_another), false, s(R.string.avatar_sc_another_hover), () -> {
            JSONObject n = sc.copy(id);
            if (n != null) {
                room.select(n.optString("id"));
                room.castChanged();
                room.edited();
            }
        }));
        row.addView(chip(s(R.string.avatar_sc_remove), false, s(R.string.avatar_sc_remove_hover), () -> {
            sc.remove(id);
            room.select(null);
            room.castChanged();
            room.edited();
        }));
        out.addView(scrolled(row));
    }

    private View addRow(Scene sc) {
        LinearLayout row = new LinearLayout(c);
        row.addView(chip("+ " + s(R.string.avatar_sc_character), false, s(R.string.avatar_sc_add_hover), () -> addCharacter(sc)));
        return scrolled(row);
    }

    void addCharacter(Scene sc) {
        final Context cx = c;
        JoyFolderHome.io().execute(() -> {
            final List<CharacterStore.Entry> mine = CharacterStore.list(cx);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                final List<String[]> pick = new java.util.ArrayList<>();                       // {label, uid, stock}
                pick.add(new String[]{"Topps", null, "topps"});
                pick.add(new String[]{"Pip", null, "pip"});
                for (CharacterStore.Entry e : mine) {
                    CharacterDoc0 d = CharacterDoc0.of(cx, e);
                    if (d != null) pick.add(new String[]{e.name, d.uid, null});
                }
                String[] names = new String[pick.size()];
                for (int i = 0; i < names.length; i++) names[i] = pick.get(i)[0];
                new AlertDialog.Builder(cx).setTitle(s(R.string.avatar_sc_add_title)).setItems(names, (dlg, i) -> {
                    String[] p = pick.get(i);
                    JSONObject it = sc.addCast(p[0], p[1], p[2]);
                    if (it != null) {
                        room.select(it.optString("id"));
                        room.castChanged();
                        room.edited();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
            });
        });
    }

    /** A character on the shelf: just the name and the uid its file carries. */
    private static final class CharacterDoc0 {
        final String uid;

        CharacterDoc0(String uid) {
            this.uid = uid;
        }

        static CharacterDoc0 of(Context c, CharacterStore.Entry e) {
            cc.joycreator.avatar.file.CharacterDoc d = CharacterStore.read(c, e.path);
            if (d == null) return null;
            String u = d.json.optString("uid", "");
            return u.isEmpty() ? null : new CharacterDoc0(u);
        }
    }

    private void set(Scene sc, String id, String key, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, v);
        sc.set(id, m, null);
        room.edited();
    }

    // ── Motion ──
    private void motion(LinearLayout out, Scene sc) {
        out.addView(slider(s(R.string.avatar_sc_wind), s(R.string.avatar_sc_wind_hover), (int) Math.round((sc.doc.has("wind") ? sc.doc.optDouble("wind") : 0.5) * 100), 0, 100, "", v -> doc(sc, "wind", v / 100.0)));
        out.addView(slider(s(R.string.avatar_sc_haze), s(R.string.avatar_sc_haze_hover), (int) Math.round((sc.doc.has("haze") ? sc.doc.optDouble("haze") : 0.5) * 100), 0, 100, "", v -> doc(sc, "haze", v / 100.0)));
        String id = room.selected();
        JSONObject it = id == null ? null : sc.item(id);
        if (it != null && "obj".equals(it.optString("kind"))) {
            out.addView(slider(s(R.string.avatar_sc_sway), s(R.string.avatar_sc_sway_hover), (int) Math.round(it.optDouble("sway", 0) * 100), 0, 100, "", v -> set(sc, id, "sway", v / 100.0)));
        } else {
            out.addView(text(s(R.string.avatar_sc_motion_hint), 12, Studio.INK_DIM, false));
        }
    }

    private void doc(Scene sc, String key, double v) {
        try {
            sc.edit("Change the " + key, d -> d.put(key, v));
        } catch (RuntimeException ignored) {
        }
        room.edited();
    }

    // ── Perform ──
    private void perform(LinearLayout out, Scene sc) {
        out.addView(text(s(R.string.avatar_sc_perform_hint), 12, Studio.INK_DIM, false));
        label(out, s(R.string.avatar_sc_inside));
        for (JSONObject it : sc.cast()) {
            final String id = it.optString("id");
            out.addView(rowOf(it.optString("name"), id.equals(room.selected()), s(R.string.avatar_sc_item_hover, it.optString("name")), () -> room.select(id)));
        }
    }

    // ── small pieces ──
    private TextView text(String t, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(0, px(4), 0, px(6));
        return v;
    }

    private void label(LinearLayout out, String t) {
        TextView v = text(t.toUpperCase(), 9, 0xFF34D399, false);
        v.setLetterSpacing(0.1f);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setPadding(0, px(10), 0, px(4));
        out.addView(v);
    }

    private TextView chip(String t, boolean on, String hover, Runnable fn) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(12);
        v.setGravity(Gravity.CENTER);
        v.setPadding(px(12), 0, px(12), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(15));
        g.setColor(on ? Studio.ARMED : Studio.RAISED);
        v.setBackground(g);
        v.setTextColor(on ? Studio.GROUND : Studio.INK);
        TooltipCompat.setTooltipText(v, hover);
        v.setContentDescription(hover);
        v.setOnClickListener(x -> fn.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(32));
        lp.rightMargin = px(6);
        v.setLayoutParams(lp);
        return v;
    }

    private View scrolled(LinearLayout row) {
        android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(row);
        return h;
    }

    private View rowOf(String t, boolean on, String hover, Runnable fn) {
        TextView v = text(t, 13, on ? Studio.GROUND : Studio.INK, false);
        v.setPadding(px(10), px(8), px(10), px(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(10));
        g.setColor(on ? Studio.ARMED : Studio.RAISED);
        v.setBackground(g);
        TooltipCompat.setTooltipText(v, hover);
        v.setContentDescription(hover);
        v.setOnClickListener(x -> fn.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(4);
        v.setLayoutParams(lp);
        return v;
    }

    private interface IntFn {
        void run(int v);
    }

    /** A slider with its name and number; one drag is one undo step (the change lands when the finger lifts). */
    private View slider(String name, String hover, int value, int min, int max, String unit, IntFn fn) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(name + "  " + value + unit, 12, Studio.INK, false);
        box.addView(t);
        SeekBar b = new SeekBar(c);
        b.setMax(max - min);
        b.setProgress(value - min);
        TooltipCompat.setTooltipText(b, hover);
        b.setContentDescription(hover);
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                t.setText(name + "  " + (p + min) + unit);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                fn.run(sb.getProgress() + min);
            }
        });
        box.addView(b);
        return box;
    }
}
