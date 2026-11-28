package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.intake.SheetCutter;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import java.util.ArrayList;
import java.util.List;

/**
 * The sheet cutter (the pilot's cutter): the sheet fills the screen with a dashed box round every drawing it found. Tap the
 * drawings you want, in the order you want them; Find the parts, or an Even grid for a mouth chart or frames. Make it takes the
 * tapped ones and the room makes a character of them, each a picture ready to be told what it is.
 */
public final class SheetCutterView extends FrameLayout {

    public interface Listener {
        /** The chosen drawings, in the order tapped, what part they are ({@code SheetParts.ZONES} key) and how they are grouped. */
        void make(List<SheetCutter.Piece> pieces, String zone, String group);

        void cancel();
    }

    private final SheetCutter.Sheet sheet;
    private final Bitmap pic;
    private final Listener listener;
    private List<SheetCutter.Comp> comps;
    private final List<SheetCutter.Comp> order = new ArrayList<>();
    private boolean grid;
    private String zone = "head", group = cc.joycreator.avatar.intake.SheetParts.PARTS;
    private final LinearLayout zoneRow, groupRow;
    private int across = 4, down = 3;
    private final View canvas;
    private final TextView note;
    private final LinearLayout tools;
    private LinearLayout bar;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float scale = 1, ox, oy;

    public SheetCutterView(Context c, SheetCutter.Sheet sheet, Bitmap picture, Listener l) {
        super(c);
        this.sheet = sheet;
        this.pic = picture;
        this.listener = l;
        this.comps = sheet.found;
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Studio.SURFACE);
        setClickable(true);
        text.setTextSize(11 * dp);
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.CENTER);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title = new TextView(c);
        title.setText(R.string.avatar_cutter_title);
        title.setTextColor(Studio.INK);
        title.setTextSize(15);
        title.setPadding(px(16), px(12), px(16), px(8));
        col.addView(title);
        canvas = new View(c) {
            @Override
            protected void onDraw(Canvas g) {
                paintSheet(g);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) tap(e.getX(), e.getY());
                return true;
            }
        };
        col.addView(canvas, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        note = new TextView(c);
        note.setTextColor(Studio.INK_DIM);
        note.setTextSize(13);
        note.setPadding(px(16), px(8), px(16), px(4));
        col.addView(note);
        tools = new LinearLayout(c);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(px(12), px(2), px(12), px(2));
        android.widget.HorizontalScrollView toolScroll = new android.widget.HorizontalScrollView(c);     // the grid's tools are wider than a phone: they scroll
        toolScroll.setHorizontalScrollBarEnabled(false);
        toolScroll.addView(tools);
        col.addView(toolScroll);
        groupRow = new LinearLayout(c);
        groupRow.setPadding(px(12), px(2), px(12), px(2));
        zoneRow = new LinearLayout(c);
        android.widget.HorizontalScrollView zs = new android.widget.HorizontalScrollView(c), gs = new android.widget.HorizontalScrollView(c);
        zs.setHorizontalScrollBarEnabled(false);
        gs.setHorizontalScrollBarEnabled(false);
        zs.setPadding(px(12), px(2), px(12), px(2));
        gs.addView(groupRow);
        zs.addView(zoneRow);
        col.addView(gs);
        col.addView(zs);
        bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(12), px(4), px(12), px(14));
        col.addView(bar);
        refresh();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void paintSheet(Canvas g) {
        float w = canvas.getWidth(), h = canvas.getHeight();
        scale = Math.min((w - 24 * dp) / sheet.w, (h - 24 * dp) / sheet.h);
        ox = (w - sheet.w * scale) / 2;
        oy = (h - sheet.h * scale) / 2;
        g.drawBitmap(pic, null, new RectF(ox, oy, ox + sheet.w * scale, oy + sheet.h * scale), paint);
        paint.setStyle(Paint.Style.STROKE);
        for (SheetCutter.Comp c : comps) {
            int at = order.indexOf(c) + 1;
            float rx = ox + c.x0 * sheet.f * scale - 3 * dp, ry = oy + c.y0 * sheet.f * scale - 3 * dp;
            float rw = (c.x1 - c.x0 + 1) * sheet.f * scale + 6 * dp, rh = (c.y1 - c.y0 + 1) * sheet.f * scale + 6 * dp;
            paint.setStrokeWidth((at > 0 ? 2.5f : 1.2f) * dp);
            paint.setColor(at > 0 ? Studio.ARMED : 0x8CE4E4E7);
            paint.setPathEffect(at > 0 ? null : new android.graphics.DashPathEffect(new float[]{4 * dp, 4 * dp}, 0));
            g.drawRoundRect(new RectF(rx, ry, rx + rw, ry + rh), 6 * dp, 6 * dp, paint);
            paint.setPathEffect(null);
            if (at > 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Studio.ARMED);
                g.drawCircle(rx + 2 * dp, ry + 2 * dp, 10 * dp, paint);
                text.setColor(Studio.GROUND);
                g.drawText(String.valueOf(at), rx + 2 * dp, ry + 6 * dp, text);
                paint.setStyle(Paint.Style.STROKE);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void tap(float x, float y) {
        double px = ((x - ox) / scale) / sheet.f, py = ((y - oy) / scale) / sheet.f;
        SheetCutter.Comp hit = SheetCutter.hit(comps, px, py);
        if (hit == null) return;
        if (!order.remove(hit)) order.add(hit);
        refresh();
    }

    private void setMode(boolean g) {
        grid = g;
        comps = g ? SheetCutter.cells(sheet, across, down) : sheet.found;
        order.clear();
        refresh();
    }

    private void refresh() {
        Context c = getContext();
        String how = cc.joycreator.avatar.intake.SheetParts.LOOP.equals(group) ? c.getString(R.string.avatar_cutter_how_loop) : cc.joycreator.avatar.intake.SheetParts.SWAP.equals(group) ? c.getString(R.string.avatar_cutter_how_swap) : c.getString(R.string.avatar_cutter_how_parts);
        note.setText((grid ? c.getString(R.string.avatar_cutter_note_grid, comps.size()) : c.getString(R.string.avatar_cutter_note_found, comps.size())) + " " + how);
        tools.removeAllViews();
        tools.addView(chip(c.getString(R.string.avatar_cutter_find), !grid, true, c.getString(R.string.avatar_hover_cutter_find), v -> setMode(false)));
        tools.addView(chip(c.getString(R.string.avatar_cutter_grid), grid, true, c.getString(R.string.avatar_hover_cutter_grid), v -> setMode(true)));
        if (grid) {
            tools.addView(label(c.getString(R.string.avatar_cutter_across, across)));
            tools.addView(chip("−", false, across > 1, c.getString(R.string.avatar_hover_cutter_fewer), v -> {
                across = Math.max(1, across - 1);
                setMode(true);
            }));
            tools.addView(chip("+", false, across < 24, c.getString(R.string.avatar_hover_cutter_more), v -> {
                across = Math.min(24, across + 1);
                setMode(true);
            }));
            tools.addView(label(c.getString(R.string.avatar_cutter_down, down)));
            tools.addView(chip("−", false, down > 1, c.getString(R.string.avatar_hover_cutter_fewer), v -> {
                down = Math.max(1, down - 1);
                setMode(true);
            }));
            tools.addView(chip("+", false, down < 24, c.getString(R.string.avatar_hover_cutter_more), v -> {
                down = Math.min(24, down + 1);
                setMode(true);
            }));
            tools.addView(chip(c.getString(R.string.avatar_cutter_all), false, true, c.getString(R.string.avatar_hover_cutter_all), v -> {
                order.clear();
                order.addAll(comps);
                refresh();
            }));
        }
        groupRow.removeAllViews();
        String[][] groups = {{cc.joycreator.avatar.intake.SheetParts.PARTS, c.getString(R.string.avatar_cutter_parts), c.getString(R.string.avatar_hover_cutter_parts)},
                {cc.joycreator.avatar.intake.SheetParts.SWAP, c.getString(R.string.avatar_cutter_swap), c.getString(R.string.avatar_hover_cutter_swap)},
                {cc.joycreator.avatar.intake.SheetParts.LOOP, c.getString(R.string.avatar_cutter_loop), c.getString(R.string.avatar_hover_cutter_loop)}};
        for (String[] g : groups) groupRow.addView(chip(g[1], g[0].equals(group), true, g[2], v -> {
            group = g[0];
            refresh();
        }));
        zoneRow.removeAllViews();
        TextView these = label(c.getString(R.string.avatar_cutter_these));
        zoneRow.addView(these);
        for (String[] z : cc.joycreator.avatar.intake.SheetParts.ZONES) zoneRow.addView(chip(z[1], z[0].equals(zone), true, c.getString(R.string.avatar_hover_cutter_zone, z[1]), v -> {
            zone = z[0];
            refresh();
        }));
        bar.removeAllViews();
        bar.addView(chip(c.getString(R.string.avatar_wizard_cancel), false, true, c.getString(R.string.avatar_hover_wizard_cancel), v -> listener.cancel()));
        View gap = new View(c);
        bar.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        boolean ok = !order.isEmpty();
        bar.addView(chip(ok ? c.getString(R.string.avatar_cutter_make_n, order.size()) : c.getString(R.string.avatar_cutter_make), ok, ok,
                c.getString(R.string.avatar_hover_cutter_make), v -> make()));
        canvas.invalidate();
    }

    private void make() {
        List<SheetCutter.Piece> out = new ArrayList<>();
        for (SheetCutter.Comp c : order) {
            SheetCutter.Piece p = SheetCutter.extract(sheet, c);
            if (p != null) out.add(p);
        }
        if (out.isEmpty()) return;
        if (cc.joycreator.avatar.intake.SheetParts.LOOP.equals(group) && out.size() > 1) out = SheetCutter.align(out);   // frames stay in register
        listener.make(out, zone, group);
    }

    private TextView label(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(Studio.INK_DIM);
        t.setTextSize(12);
        t.setPadding(px(8), 0, px(6), 0);
        return t;
    }

    private TextView chip(String label, boolean on, boolean enabled, String hover, View.OnClickListener tap) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), 0, px(14), 0);
        t.setTextColor(on ? Studio.GROUND : enabled ? Studio.LABEL : Studio.INK_OFF);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(18));
        g.setColor(on ? Studio.ARMED : Studio.RAISED);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36));
        lp.rightMargin = px(6);
        t.setLayoutParams(lp);
        if (enabled) t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }
}
