package cc.joycreator.textlab;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The room's sliders: a row of tabs, one per slider group the look has (its icon, its name, a dot when the look has
 * changed it), then the chosen group's sliders and colours. The last tab, "Pieces", adds or takes away what the
 * look is built from. Built from {@code parts.json} ({@link TlParts}), as the look book's panel is.
 */
final class TlKnobPanel extends LinearLayout {

    /** What the panel asks of the room. */
    interface Host {
        /** The look as it is now (its {@code p} holds the changed sliders). */
        @NonNull JSONObject look();

        /** Before a change, once (a drag, a colour dialog): remember the step for undo. */
        void beginChange(@NonNull String what);

        /** A slider or colour moved (live, many times a drag). */
        void knob(@NonNull String name, @NonNull Object value);

        /** Open the pieces chooser. */
        void pieces();

        /** A change has ended (a slider let go, a colour dialog closed): whether anything is unsaved is worked out again. */
        void settled();
    }

    private static final int CYAN = 0xFF22D3EE, GROUND = 0xFF0B0B0C, CONTROL = 0xFF1F1F26, INK = 0xFFD4D4D8,
            DIM = 0xFF8A8A96;
    static final String PIECES = "Pieces";

    private final Activity a;
    private final TlParts parts;
    private final Host host;
    private final LinearLayout tabs, body;
    private final HorizontalScrollView tabScroll;
    /** False where the look's pieces are not the person's to change (a look worn outside the room): no Pieces tab. */
    private final boolean withPieces;
    private String showing;

    TlKnobPanel(@NonNull Activity a, @NonNull TlParts parts, @NonNull Host host) {
        this(a, parts, host, true);
    }

    TlKnobPanel(@NonNull Activity a, @NonNull TlParts parts, @NonNull Host host, boolean withPieces) {
        super(a);
        this.a = a;
        this.parts = parts;
        this.host = host;
        this.withPieces = withPieces;
        setOrientation(VERTICAL);
        tabScroll = new HorizontalScrollView(a);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(a);
        tabs.setPadding(dp(8), dp(4), dp(8), dp(4));
        tabScroll.addView(tabs);
        addView(tabScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView sc = new ScrollView(a);
        body = new LinearLayout(a);
        body.setOrientation(VERTICAL);
        body.setPadding(dp(14), dp(4), dp(14), dp(16));
        sc.addView(body);
        addView(sc, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** Rebuild for the look as it is now (a new look, a piece added or taken away, an undo). */
    void refresh() {
        JSONObject look = host.look();
        List<String> slots = parts.slotsFor(look);
        if (showing == null || !(slots.contains(showing) || withPieces && PIECES.equals(showing))) {
            String own = null;
            for (String s : slots) if (parts.needs(s) != null) { own = s; break; }   // the look's own special group first
            showing = own != null ? own : slots.isEmpty() ? PIECES : slots.get(0);
        }
        Set<String> used = parts.used(look);
        tabs.removeAllViews();
        for (String s : slots) tabs.addView(tab(s, TlIcons.forTab(s), used.contains(s)));
        if (withPieces) tabs.addView(tab(PIECES, 0, false));
        showBody();
    }

    private TextView tab(String slot, int icon, boolean used) {
        boolean on = slot.equals(showing);
        TextView t = new TextView(a);
        t.setText(PIECES.equals(slot) ? a.getString(com.fadcam.R.string.textlab_tab_pieces) : used ? slot + " •" : slot);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(on ? GROUND : INK);
        t.setPadding(dp(10), 0, dp(12), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(on ? CYAN : CONTROL);
        t.setBackground(g);
        if (icon != 0) {
            Drawable d = a.getDrawable(icon);
            if (d != null) {
                d = d.mutate();
                d.setTint(on ? GROUND : INK);
                d.setBounds(0, 0, dp(16), dp(16));
                t.setCompoundDrawablesRelative(d, null, null, null);
                t.setCompoundDrawablePadding(dp(5));
            }
        }
        String tip = PIECES.equals(slot) ? a.getString(com.fadcam.R.string.textlab_tab_pieces_tip)
                : a.getString(used ? com.fadcam.R.string.textlab_tab_tip_used : com.fadcam.R.string.textlab_tab_tip, slot);
        t.setContentDescription(tip);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) t.setTooltipText(tip);
        t.setOnClickListener(v -> {
            if (PIECES.equals(slot)) {
                host.pieces();
                return;
            }
            showing = slot;
            refresh();
        });
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.setMarginEnd(dp(6));
        t.setLayoutParams(lp);
        if (on) t.post(() -> tabScroll.smoothScrollTo(Math.max(0, t.getLeft() - (tabScroll.getWidth() - t.getWidth()) / 2), 0));
        return t;
    }

    private void showBody() {
        body.removeAllViews();
        if (PIECES.equals(showing)) return;
        JSONObject p = host.look().optJSONObject("p");
        for (TlParts.Knob k : parts.inSlot(showing)) {
            Object v = p != null && p.has(k.name) ? p.opt(k.name) : k.colour ? k.defColour : (Object) k.def;
            body.addView(k.colour ? colourRow(k, String.valueOf(v)) : sliderRow(k, num(v, k.def)));
        }
    }

    private static double num(Object v, double d) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private View sliderRow(TlParts.Knob k, double value) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        LinearLayout top = new LinearLayout(a);
        TextView l = label(k.label);
        top.addView(l, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView out = label(fmt(value));
        out.setTextColor(INK);
        top.addView(out);
        row.addView(top);
        SeekBar s = new SeekBar(a);
        s.setMax(1000);
        double span = k.max - k.min;
        s.setProgress(span > 0 ? (int) Math.round((value - k.min) / span * 1000) : 0);
        s.setProgressTintList(ColorStateList.valueOf(CYAN));
        s.setThumbTintList(ColorStateList.valueOf(CYAN));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3F3F46));
        s.setContentDescription(k.label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setTooltipText(k.label);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int pr, boolean user) {
                if (!user) return;
                double v = k.min + pr / 1000.0 * span;
                if (k.step > 0) v = k.min + Math.round((v - k.min) / k.step) * k.step;
                v = Math.round(v * 10000) / 10000.0;
                out.setText(fmt(v));
                host.knob(k.name, v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                host.beginChange(k.label);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                markUsed();
                host.settled();
            }
        });
        row.addView(s);
        return row;
    }

    private View colourRow(TlParts.Knob k, String value) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(4));
        row.addView(label(k.label), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView sw = new TextView(a);
        paintSwatch(sw, value);
        sw.setContentDescription(a.getString(com.fadcam.R.string.textlab_colour_hover, k.label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sw.setTooltipText(a.getString(com.fadcam.R.string.textlab_colour_hover, k.label));
        sw.setOnClickListener(v -> {
            JSONObject p = host.look().optJSONObject("p");
            String cur = p != null && p.has(k.name) ? p.optString(k.name) : k.defColour;
            host.beginChange(k.label);
            TlColourDialog.show(a, k.label, cur != null ? cur : "#ffffff", c -> {
                host.knob(k.name, c);
                paintSwatch(sw, c);
            }, c -> {
                markUsed();
                host.settled();
            });
        });
        row.addView(sw, new LayoutParams(dp(56), dp(30)));
        return row;
    }

    private void paintSwatch(TextView sw, String value) {
        int c;
        try {
            c = Color.parseColor(value.split("\\*")[0].trim());
        } catch (IllegalArgumentException e) {
            c = Color.WHITE;
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), 0xFF3F3F46);
        sw.setBackground(g);
        sw.setText(value.contains("*") ? sw.getContext().getString(com.fadcam.R.string.textlab_glow) : "");
        sw.setTextSize(10);
        sw.setGravity(Gravity.CENTER);
        sw.setTextColor((Color.luminance(c) > 0.5f) ? GROUND : INK);
    }

    /** After a change: the tab gets its dot (once). */
    private void markUsed() {
        Set<String> used = parts.used(host.look());
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            String text = t.getText().toString();
            String slot = text.endsWith(" •") ? text.substring(0, text.length() - 2) : text;
            if (used.contains(slot) && !text.endsWith(" •")) t.setText(slot + " •");
        }
    }

    private TextView label(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(12);
        return t;
    }

    private static String fmt(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
