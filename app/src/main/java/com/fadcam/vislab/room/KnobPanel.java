package com.fadcam.vislab.room;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.motion.Motion;
import com.fadcam.vislab.audio.Signals;
import com.fadcam.vislab.model.VisKnob;
import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.model.VisType;
import com.fadcam.vislab.model.VisTypes;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

/**
 * The VisLab editor, built entirely from the knob registry (types.json), so a new primitive
 * shows up here with no UI code. Tabs: Layers (the stack and the chosen layer's knobs),
 * Finish, Echo and Colours. Every knob has a ♪ that makes it follow a part of the music.
 * Choices are fully rounded pills (the drawer rule); the tabs use the room's slant row.
 */
final class KnobPanel extends LinearLayout {

    interface Host {
        @Nullable VisScene scene();

        /** The look's tree changed. */
        void edited();

        /** Brand lock: re-colour every look with these stops, or null to stop. */
        void brand(@Nullable String[] stops);
    }

    private static final String[][] PALETTES = {
            {"Neon", "#00F0FF", "#3D5AFE", "#B517FF", "#FF2BD6", "#FF7A1A"},
            {"Aurora", "#23F0C7", "#2AC9A0", "#5B8CFF", "#9B5CFF", "#E0AAFF"},
            {"Sunset", "#FFE66D", "#FF9A3C", "#FF2E63", "#B4166B", "#5B1FA8"},
            {"Ember", "#FFF3B0", "#FFBA08", "#F48C06", "#DC2F02", "#6A040F"},
            {"Ice", "#F0F9FF", "#7DD3FC", "#0EA5E9", "#2563EB", "#312E81"},
            {"Toxic", "#F5FFDD", "#CCFF00", "#39FF14", "#00C853", "#00695C"},
            {"Vapor", "#FF71CE", "#B967FF", "#01CDFE", "#05FFA1", "#FFFB96"},
            {"Gold", "#FFF6E0", "#F2D49B", "#D4A256", "#A67C52", "#5C4033"},
            {"Mono", "#FFFFFF", "#D4D4D8", "#A1A1AA", "#71717A", "#3F3F46"},
            {"Joy", "#35F6BF", "#97FE8B", "#FAA03D", "#FF008C", "#CC27FF"},
            {"Ocean", "#E9D8A6", "#94D2BD", "#0A9396", "#005F73", "#2B2D84"},
            {"Rose", "#FFE4EC", "#F48FB1", "#EC407A", "#C2185B", "#6A1B4D"},
            {"Spectrum", "#FF2D55", "#FF9500", "#FFE600", "#34C759", "#5AC8FA"},
    };

    private final VisTypes types;
    private final Host host;
    private final SlantRow tabs;
    private final LinearLayout body;
    private int tab;
    private int selLayer;
    @Nullable private String brandName;

    KnobPanel(@NonNull Context c, @NonNull VisTypes types, @NonNull Host host) {
        super(c);
        this.types = types;
        this.host = host;
        setOrientation(VERTICAL);
        setBackground(Ui.topRounded(Ui.DRAWER, Ui.dp(c, 16)));
        int pad = Ui.dp(c, 14);
        setPadding(pad, Ui.dp(c, 10), pad, 0);
        tabs = new SlantRow(c, Ui.VIZ_A, Ui.VIZ_B);
        tabs.add("layers", "Layers", "The pieces this look is built from");
        tabs.add("auto_awesome", "Finish", "Bloom, lens, glitch, film and framing");
        tabs.add("blur_on", "Echo", "Feedback trails under everything");
        tabs.add("palette", "Colours", "Palette and brand colours");
        tabs.setSelected(0);
        tabs.setOnPick(i -> {
            tab = i;
            rebuild();
        });
        addView(tabs, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        body.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 28));
        scroll.addView(body);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    void rebuild() {
        body.removeAllViews();
        VisScene s = host.scene();
        if (s == null) return;
        if (tab == 0) buildLayers(s);
        else if (tab == 1) buildKnobs(types.post, block(s.json(), "post"));
        else if (tab == 2) buildEcho(s);
        else buildColours(s);
    }

    void resetSelection() {
        selLayer = 0;
    }

    // ------------------------------------------------------------------ tabs

    private void buildLayers(@NonNull VisScene s) {
        Context c = getContext();
        JsonArray layers = s.layers();
        if (selLayer >= layers.size()) selLayer = Math.max(0, layers.size() - 1);
        for (int i = 0; i < layers.size(); i++) {
            if (!layers.get(i).isJsonObject()) continue;
            body.addView(layerRow(s, layers.get(i).getAsJsonObject(), i));
        }
        TextView add = pill(c, "+ Add a layer", false);
        Ui.label(add, "Add a new piece to this look");
        LinearLayout addBox = new LinearLayout(c);
        addBox.setOrientation(VERTICAL);
        addBox.setVisibility(GONE);
        add.setOnClickListener(v -> {
            Motion.press(v);
            addBox.setVisibility(addBox.getVisibility() == VISIBLE ? GONE : VISIBLE);
        });
        LayoutParams alp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(c, 6);
        body.addView(add, alp);
        for (VisTypes.Family fam : types.families) {
            addBox.addView(section(c, fam.label));
            WrapLayout w = new WrapLayout(c, Ui.dp(c, 6), Ui.dp(c, 6));
            for (VisType t : types.types.values()) {
                if (!t.family.equals(fam.id)) continue;
                TextView p = pill(c, t.label, false);
                Ui.label(p, t.about);
                p.setOnClickListener(v -> {
                    Motion.press(v);
                    JsonObject lo = new JsonObject();
                    lo.addProperty("type", t.id);
                    lo.addProperty("name", t.label);
                    lo.add("p", new JsonObject());
                    s.layers().add(lo);
                    selLayer = s.layers().size() - 1;
                    host.edited();
                    rebuild();
                });
                w.addView(p);
            }
            addBox.addView(w);
        }
        body.addView(addBox);
        if (layers.size() == 0 || !layers.get(selLayer).isJsonObject()) return;
        JsonObject L = layers.get(selLayer).getAsJsonObject();
        VisType t = types.type(str(L, "type"));
        if (t == null) return;
        body.addView(section(c, name(L, t) + ": layer"));
        VisKnob opacity = new VisKnob("opacity", "Opacity", -1, 0, 1, 1, 0, false, false, null, null);
        VisKnob add2 = new VisKnob("add", "Light / paint", -1, 0, 1, 1, 0, false, false, null, null);
        body.addView(knobRow(c, opacity, L));
        body.addView(knobRow(c, add2, L));
        body.addView(toggleRow(c, "Under echo", !(L.has("echo") && L.get("echo").isJsonPrimitive() && !L.get("echo").getAsBoolean()),
                "Draw into the echo trails, or crisp above them", on -> {
                    L.addProperty("echo", on);
                    host.edited();
                }));
        buildKnobs(t, knobsOf(L));
    }

    private void buildEcho(@NonNull VisScene s) {
        Context c = getContext();
        JsonObject echo = s.json().has("echo") && s.json().get("echo").isJsonObject() ? s.json().getAsJsonObject("echo") : null;
        boolean on = s.echoOn() != null;
        body.addView(toggleRow(c, "Echo", on, "Feedback trails on or off", v -> {
            JsonObject e = s.json().has("echo") && s.json().get("echo").isJsonObject() ? s.json().getAsJsonObject("echo") : new JsonObject();
            e.addProperty("on", v);
            if (!e.has("p")) e.add("p", new JsonObject());
            s.json().add("echo", e);
            host.edited();
            rebuild();
        }));
        TextView about = Ui.text(c, types.echo.about, 11.5f, Studio.INK_FAINT, false, 400);
        about.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        body.addView(about);
        if (on && echo != null) buildKnobs(types.echo, block(s.json(), "echo"));
    }

    private void buildColours(@NonNull VisScene s) {
        Context c = getContext();
        body.addView(section(c, "Palette: tap to use on this look"));
        WrapLayout w = new WrapLayout(c, Ui.dp(c, 6), Ui.dp(c, 6));
        for (String[] p : PALETTES) {
            View chip = paletteChip(c, p, false);
            Ui.label(chip, "Use the " + p[0] + " palette on this look");
            chip.setOnClickListener(v -> {
                Motion.press(v);
                JsonArray a = new JsonArray();
                for (int i = 1; i < p.length; i++) a.add(p[i]);
                s.json().add("palette", a);
                host.edited();
            });
            w.addView(chip);
        }
        body.addView(w);
        body.addView(section(c, "Brand lock: every look in your colours"));
        WrapLayout b = new WrapLayout(c, Ui.dp(c, 6), Ui.dp(c, 6));
        TextView off = pill(c, "Off", brandName == null);
        Ui.label(off, "Each look keeps its own colours");
        off.setOnClickListener(v -> {
            brandName = null;
            host.brand(null);
            rebuild();
        });
        b.addView(off);
        for (String[] p : PALETTES) {
            View chip = paletteChip(c, p, p[0].equals(brandName));
            Ui.label(chip, "Show every look in the " + p[0] + " palette");
            chip.setOnClickListener(v -> {
                Motion.press(v);
                brandName = p[0];
                String[] stops = new String[5];
                System.arraycopy(p, 1, stops, 0, 5);
                host.brand(stops);
                rebuild();
            });
            b.addView(chip);
        }
        body.addView(b);
        body.addView(toggleRow(c, "See-through", s.background() == null,
                "No background: the look sits over whatever is underneath", on -> {
                    if (on) s.json().add("background", com.google.gson.JsonNull.INSTANCE);
                    else s.json().addProperty("background", "#000000");
                    host.edited();
                }));
    }

    // ------------------------------------------------------------------ rows

    private View layerRow(@NonNull VisScene s, @NonNull JsonObject L, int index) {
        Context c = getContext();
        VisType t = types.type(str(L, "type"));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(c, 6);
        row.setPadding(p, p, p, p);
        boolean sel = index == selLayer;
        row.setBackground(Ui.round(sel ? Studio.PRESSED : (0xA6 << 24 | (Studio.RAISED & 0xFFFFFF)), Ui.dp(c, 12)));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 5);
        row.setLayoutParams(lp);
        boolean on = !(L.has("on") && L.get("on").isJsonPrimitive() && !L.get("on").getAsBoolean());
        TextView eye = iconButton(c, on ? "visibility" : "visibility_off", on ? Studio.INK : Studio.INK_FAINT,
                "Show or hide this layer", v -> {
                    L.addProperty("on", !on);
                    host.edited();
                    rebuild();
                });
        row.addView(eye);
        LinearLayout names = new LinearLayout(c);
        names.setOrientation(VERTICAL);
        names.setPadding(Ui.dp(c, 6), 0, 0, 0);
        names.addView(Ui.text(c, name(L, t), 12.5f, sel ? Studio.INK : Studio.LABEL, false, 600));
        names.addView(Ui.text(c, t != null ? t.label : str(L, "type"), 10.5f, Studio.INK_FAINT, false, 400));
        row.addView(names, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(iconButton(c, "arrow_upward", Studio.INK_DIM, "Move up: drawn later, on top", v -> move(s, index, 1)));
        row.addView(iconButton(c, "arrow_downward", Studio.INK_DIM, "Move down: drawn earlier, behind", v -> move(s, index, -1)));
        row.addView(iconButton(c, "content_copy", Studio.INK_DIM, "Duplicate this layer", v -> {
            JsonObject copy = L.deepCopy();
            copy.addProperty("seed", (int) (Math.random() * 1000));
            insert(s.layers(), index + 1, copy);
            selLayer = index + 1;
            host.edited();
            rebuild();
        }));
        row.addView(iconButton(c, "close", Studio.INK_DIM, "Remove this layer", v -> {
            s.layers().remove(index);
            selLayer = Math.max(0, index - 1);
            host.edited();
            rebuild();
        }));
        row.setOnClickListener(v -> {
            selLayer = index;
            rebuild();
        });
        Ui.label(row, "Edit " + name(L, t));
        return row;
    }

    private void move(@NonNull VisScene s, int index, int dir) {
        JsonArray a = s.layers();
        int to = index + dir;
        if (to < 0 || to >= a.size()) return;
        JsonElement e = a.remove(index);
        insert(a, to, e);
        selLayer = to;
        host.edited();
        rebuild();
    }

    private static void insert(@NonNull JsonArray a, int at, @NonNull JsonElement e) {
        JsonArray copy = new JsonArray();
        for (int i = 0; i < a.size(); i++) {
            if (i == at) copy.add(e);
            copy.add(a.get(i));
        }
        if (at >= a.size()) copy.add(e);
        while (a.size() > 0) a.remove(0);
        for (JsonElement x : copy) a.add(x);
    }

    private void buildKnobs(@NonNull VisType t, @NonNull JsonObject holder) {
        Context c = getContext();
        String group = null;
        for (VisKnob k : t.knobs) {
            if (k.group != null && !k.group.equals(group)) {
                body.addView(section(c, k.group));
                group = k.group;
            }
            body.addView(knobRow(c, k, holder));
        }
    }

    /** One knob: label, slider (or pills for a choice), value, and the ♪ music link. */
    @NonNull
    private View knobRow(@NonNull Context c, @NonNull VisKnob k, @NonNull JsonObject holder) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(VERTICAL);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(c, 34));
        TextView label = Ui.text(c, k.label, 12, Studio.INK_DIM, false, 500);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(label, new LayoutParams(Ui.dp(c, 98), LayoutParams.WRAP_CONTENT));
        wrap.addView(row);
        if (k.options != null || k.toggle) {
            WrapLayout pills = new WrapLayout(c, Ui.dp(c, 5), Ui.dp(c, 5));
            String[] opts = k.options != null ? k.options : new String[]{"Off", "On"};
            int cur = Math.round(base(holder, k));
            for (int i = 0; i < opts.length; i++) {
                final int value = i;
                TextView p = pill(c, opts[i], i == cur);
                Ui.label(p, k.label + ": " + opts[i]);
                p.setOnClickListener(v -> {
                    Motion.press(v);
                    holder.addProperty(k.id, value);
                    host.edited();
                    for (int j = 0; j < pills.getChildCount(); j++) stylePill((TextView) pills.getChildAt(j), j == value);
                });
                pills.addView(p);
            }
            row.addView(pills, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            return wrap;
        }
        SeekBar bar = new SeekBar(c);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.VIZ_A));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.VIZ_A));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Studio.LINE));
        bar.setProgress(Math.round(toSlider(k, base(holder, k)) * 1000));
        Ui.label(bar, k.label);
        row.addView(bar, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView val = Ui.text(c, fmt(base(holder, k)), 11, Studio.INK_FAINT, false, 400);
        val.setGravity(Gravity.END);
        row.addView(val, new LayoutParams(Ui.dp(c, 40), LayoutParams.WRAP_CONTENT));
        boolean hasMod = mods(holder, k.id) != null;
        TextView note = Ui.icon(c, "music_note", 16, hasMod ? Studio.GROUND : Studio.INK_FAINT);
        note.setBackground(hasMod ? Ui.round(Ui.VIZ_A, Ui.dp(c, 14)) : null);
        int np = Ui.dp(c, 5);
        note.setPadding(np, np, np, np);
        Ui.label(note, "Make " + k.label + " react to the music");
        row.addView(note);
        WrapLayout modBox = new WrapLayout(c, Ui.dp(c, 5), Ui.dp(c, 5));
        modBox.setVisibility(GONE);
        modBox.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 8));
        wrap.addView(modBox);
        note.setOnClickListener(v -> {
            Motion.press(v);
            modBox.setVisibility(modBox.getVisibility() == VISIBLE ? GONE : VISIBLE);
            fillMods(c, modBox, k, holder, note);
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                float v = fromSlider(k, p / 1000f);
                setBase(holder, k.id, v);
                val.setText(fmt(v));
                host.edited();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        return wrap;
    }

    private void fillMods(@NonNull Context c, @NonNull WrapLayout box, @NonNull VisKnob k,
                          @NonNull JsonObject holder, @NonNull TextView note) {
        box.removeAllViews();
        JsonObject m = mods(holder, k.id);
        for (VisTypes.Signal sig : types.signals) {
            if (Signals.indexOf(sig.id) < 0) continue;
            boolean on = m != null && m.has(sig.id);
            TextView p = pill(c, on ? sig.label + "  " + fmt(m.get(sig.id).getAsFloat()) : sig.label, on);
            Ui.label(p, on ? "Tap to change how much " + sig.label + " moves " + k.label + "; hold to remove"
                    : "React to " + sig.label);
            p.setOnClickListener(v -> {
                Motion.press(v);
                JsonObject mm = ensureMods(holder, k);
                if (!mm.has(sig.id)) mm.addProperty(sig.id, 0.25f);
                else {
                    // Step through useful strengths: gentle, medium, strong, inverted.
                    float a = mm.get(sig.id).getAsFloat();
                    float next = a >= 0.99f ? -0.25f : (a < 0 ? 0.1f : (a < 0.2f ? 0.25f : (a < 0.4f ? 0.5f : 1f)));
                    mm.addProperty(sig.id, next);
                }
                host.edited();
                fillMods(c, box, k, holder, note);
            });
            p.setOnLongClickListener(v -> {
                JsonObject mm = mods(holder, k.id);
                if (mm != null) {
                    mm.remove(sig.id);
                    if (mm.size() == 0) setPlain(holder, k);
                }
                host.edited();
                fillMods(c, box, k, holder, note);
                return true;
            });
            box.addView(p);
        }
        boolean hasMod = mods(holder, k.id) != null;
        note.setTextColor(hasMod ? Studio.GROUND : Studio.INK_FAINT);
        note.setBackground(hasMod ? Ui.round(Ui.VIZ_A, Ui.dp(c, 14)) : null);
    }

    private View toggleRow(@NonNull Context c, @NonNull String label, boolean on, @NonNull String hover,
                           @NonNull java.util.function.Consumer<Boolean> set) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(c, 36));
        row.addView(Ui.text(c, label, 12, Studio.INK_DIM, false, 500), new LayoutParams(Ui.dp(c, 98), LayoutParams.WRAP_CONTENT));
        TextView p = pill(c, on ? "On" : "Off", on);
        Ui.label(p, hover);
        final boolean[] state = {on};
        p.setOnClickListener(v -> {
            Motion.press(v);
            state[0] = !state[0];
            p.setText(state[0] ? "On" : "Off");
            stylePill(p, state[0]);
            set.accept(state[0]);
        });
        row.addView(p);
        return row;
    }

    // ------------------------------------------------------------------ bits

    @NonNull
    private static TextView section(@NonNull Context c, @NonNull String s) {
        TextView t = Ui.text(c, s.toUpperCase(Locale.ROOT), 10.5f, Studio.INK_FAINT, false, 600);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 4));
        return t;
    }

    /** A fully rounded pill (the drawer rule for choices). */
    @NonNull
    private static TextView pill(@NonNull Context c, @NonNull String s, boolean on) {
        TextView t = Ui.text(c, s, 11.5f, Studio.INK, false, 600);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(c, 30));
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
        stylePill(t, on);
        return t;
    }

    private static void stylePill(@NonNull TextView t, boolean on) {
        Context c = t.getContext();
        t.setBackground(Ui.round(on ? Studio.ARMED : Studio.RAISED, Ui.dp(c, 999)));
        t.setTextColor(on ? Studio.GROUND : Studio.INK_DIM);
    }

    @NonNull
    private static View paletteChip(@NonNull Context c, @NonNull String[] p, boolean on) {
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(VERTICAL);
        int pad = Ui.dp(c, 6);
        chip.setPadding(pad, pad, pad, pad);
        chip.setBackground(Ui.round(on ? Studio.ARMED : Studio.RAISED, Ui.dp(c, 10)));
        LinearLayout sw = new LinearLayout(c);
        sw.setOrientation(HORIZONTAL);
        sw.setClipToOutline(true);
        sw.setBackground(Ui.round(Color.BLACK, Ui.dp(c, 3)));
        for (int i = 1; i < p.length; i++) {
            View v = new View(c);
            v.setBackgroundColor(Ui.parse(p[i], Color.GRAY));
            sw.addView(v, new LayoutParams(Ui.dp(c, 16), Ui.dp(c, 10)));
        }
        chip.addView(sw);
        TextView name = Ui.text(c, p[0], 10.5f, on ? Studio.GROUND : Studio.INK_DIM, false, 600);
        name.setPadding(0, Ui.dp(c, 4), 0, 0);
        chip.addView(name);
        return chip;
    }

    @NonNull
    private TextView iconButton(@NonNull Context c, @NonNull String lig, int color, @NonNull String hover,
                                @NonNull View.OnClickListener l) {
        TextView t = Ui.icon(c, lig, 18, color);
        int s = Ui.dp(c, 32);
        t.setLayoutParams(new LayoutParams(s, s));
        Ui.label(t, hover);
        t.setOnClickListener(v -> {
            Motion.press(v);
            l.onClick(v);
        });
        return t;
    }

    // ------------------------------------------------------------------ JSON

    @NonNull
    private static JsonObject block(@NonNull JsonObject root, @NonNull String key) {
        JsonObject b = root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : null;
        if (b == null) {
            b = new JsonObject();
            root.add(key, b);
        }
        return knobsOf(b);
    }

    @NonNull
    private static JsonObject knobsOf(@NonNull JsonObject block) {
        JsonObject p = block.has("p") && block.get("p").isJsonObject() ? block.getAsJsonObject("p") : null;
        if (p == null) {
            p = new JsonObject();
            block.add("p", p);
        }
        return p;
    }

    private static float base(@NonNull JsonObject holder, @NonNull VisKnob k) {
        JsonElement e = holder.get(k.id);
        if (e == null || e.isJsonNull()) return k.def;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsFloat();
            if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
            return k.def;
        }
        if (e.isJsonObject() && e.getAsJsonObject().has("v")) return e.getAsJsonObject().get("v").getAsFloat();
        return k.def;
    }

    private static void setBase(@NonNull JsonObject holder, @NonNull String id, float v) {
        JsonElement e = holder.get(id);
        float r = Math.round(v * 10000f) / 10000f;
        if (e != null && e.isJsonObject()) e.getAsJsonObject().addProperty("v", r);
        else holder.addProperty(id, r);
    }

    @Nullable
    private static JsonObject mods(@NonNull JsonObject holder, @NonNull String id) {
        JsonElement e = holder.get(id);
        if (e == null || !e.isJsonObject()) return null;
        JsonElement m = e.getAsJsonObject().get("mod");
        return m != null && m.isJsonObject() && m.getAsJsonObject().size() > 0 ? m.getAsJsonObject() : null;
    }

    @NonNull
    private static JsonObject ensureMods(@NonNull JsonObject holder, @NonNull VisKnob k) {
        JsonElement e = holder.get(k.id);
        JsonObject o;
        if (e != null && e.isJsonObject()) o = e.getAsJsonObject();
        else {
            o = new JsonObject();
            o.addProperty("v", base(holder, k));
            holder.add(k.id, o);
        }
        if (!o.has("mod") || !o.get("mod").isJsonObject()) o.add("mod", new JsonObject());
        return o.getAsJsonObject("mod");
    }

    private static void setPlain(@NonNull JsonObject holder, @NonNull VisKnob k) {
        holder.addProperty(k.id, base(holder, k));
    }

    private static float toSlider(@NonNull VisKnob k, float v) {
        if (k.log && k.min > 0) return (float) (Math.log(v / k.min) / Math.log(k.max / k.min));
        return k.max == k.min ? 0 : (v - k.min) / (k.max - k.min);
    }

    private static float fromSlider(@NonNull VisKnob k, float s) {
        float v = k.log && k.min > 0 ? (float) (k.min * Math.pow(k.max / k.min, s)) : k.min + s * (k.max - k.min);
        if (k.step > 0) v = Math.round(v / k.step) * k.step;
        return v;
    }

    @NonNull
    private static String fmt(float v) {
        float a = Math.abs(v);
        if (a >= 100) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, a < 0.1f ? "%.3f" : "%.2f", v);
    }

    @NonNull
    private static String str(@NonNull JsonObject o, @NonNull String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    @NonNull
    private static String name(@NonNull JsonObject L, @Nullable VisType t) {
        String n = str(L, "name");
        return !n.isEmpty() ? n : (t != null ? t.label : "Layer");
    }

}
