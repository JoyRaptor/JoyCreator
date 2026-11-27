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

import cc.joycreator.avatar.intake.WholeDrawing;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import java.util.ArrayList;
import java.util.List;

/**
 * The pin wizard (the pilot's intake.pinWizard): the whole drawing fills the screen and the wizard asks for one pin after
 * another: tap the head, the hands, the hips, the feet, the tail. Back takes back the last step, Skip leaves one out, and Done
 * (two pins at least) hands the pins to the room. Nothing in the file changes until Done.
 */
public final class PinWizard extends FrameLayout {

    public interface Listener {
        void done(List<WholeDrawing.Pin> pins);

        void cancel();
    }

    private final Bitmap pic;
    private final Listener listener;
    private final List<WholeDrawing.Pin> pins = new ArrayList<>();
    private final List<Boolean> did = new ArrayList<>();       // true = a pin, false = a skip
    private int step;
    private final View canvas;
    private final TextView note;
    private final LinearLayout bar;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float scale = 1, ox, oy;

    public PinWizard(Context c, Bitmap picture, String name, Listener l) {
        super(c);
        pic = picture;
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF09090B);
        text.setTextSize(11 * dp);
        text.setFakeBoldText(true);
        setClickable(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title = new TextView(c);
        title.setText(c.getString(R.string.avatar_wizard_title, name));
        title.setTextColor(0xFFFAFAFA);
        title.setTextSize(15);
        title.setPadding(px(16), px(12), px(16), px(8));
        col.addView(title);
        canvas = new View(c) {
            @Override
            protected void onDraw(Canvas g) {
                draw(g);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) tap(e.getX(), e.getY());
                return true;
            }
        };
        col.addView(canvas, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        note = new TextView(c);
        note.setTextColor(0xFFA1A1AA);
        note.setTextSize(13);
        note.setPadding(px(16), px(8), px(16), px(4));
        col.addView(note);
        bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(12), px(4), px(12), px(14));
        col.addView(bar);
        refresh();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void draw(Canvas g) {
        float w = canvas.getWidth(), h = canvas.getHeight();
        scale = Math.min((w - 40 * dp) / pic.getWidth(), (h - 40 * dp) / pic.getHeight());
        ox = (w - pic.getWidth() * scale) / 2;
        oy = (h - pic.getHeight() * scale) / 2;
        g.drawBitmap(pic, null, new RectF(ox, oy, ox + pic.getWidth() * scale, oy + pic.getHeight() * scale), paint);
        for (WholeDrawing.Pin p : pins) {
            float x = ox + (float) p.lx * scale, y = oy + (float) p.ly * scale;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xD903181C);
            g.drawCircle(x, y, 9 * dp, paint);
            paint.setColor(Studio.ARMED);
            g.drawCircle(x, y, 7 * dp, paint);
            float tw = text.measureText(p.name);
            paint.setColor(0xCC0D0D10);
            g.drawRoundRect(new RectF(x + 12 * dp, y - 9 * dp, x + 20 * dp + tw, y + 9 * dp), 6 * dp, 6 * dp, paint);
            text.setColor(0xFFE4E4E7);
            g.drawText(p.name, x + 16 * dp, y + 4 * dp, text);
        }
    }

    private void tap(float x, float y) {
        if (step >= WholeDrawing.STEPS.length) return;
        double lx = (x - ox) / scale, ly = (y - oy) / scale;
        if (lx < 0 || ly < 0 || lx > pic.getWidth() || ly > pic.getHeight()) return;
        String[] s = WholeDrawing.STEPS[step];
        pins.add(new WholeDrawing.Pin(s[0], s[1], lx, ly));
        did.add(true);
        step++;
        refresh();
    }

    private void refresh() {
        Context c = getContext();
        if (step < WholeDrawing.STEPS.length) note.setText(c.getString(R.string.avatar_wizard_tap, WholeDrawing.STEPS[step][2]));
        else note.setText(c.getString(R.string.avatar_wizard_enough, pins.size()));
        bar.removeAllViews();
        bar.addView(chip(c.getString(R.string.avatar_wizard_cancel), false, true, c.getString(R.string.avatar_hover_wizard_cancel), v -> listener.cancel()));
        if (!did.isEmpty())
            bar.addView(chip(c.getString(R.string.avatar_wizard_back), false, true, c.getString(R.string.avatar_hover_wizard_back), v -> {
                if (did.remove(did.size() - 1)) pins.remove(pins.size() - 1);
                step = Math.max(0, step - 1);
                refresh();
            }));
        if (step < WholeDrawing.STEPS.length)
            bar.addView(chip(c.getString(R.string.avatar_wizard_skip, WholeDrawing.STEPS[step][1].toLowerCase(java.util.Locale.ROOT)), false, true, c.getString(R.string.avatar_hover_wizard_skip), v -> {
                did.add(false);
                step++;
                refresh();
            }));
        View gap = new View(c);
        bar.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        boolean ok = pins.size() >= 2;
        bar.addView(chip(c.getString(R.string.avatar_done), ok, ok, c.getString(ok ? R.string.avatar_hover_wizard_done : R.string.avatar_hover_wizard_two), v -> {
            if (pins.size() >= 2) listener.done(new ArrayList<>(pins));
        }));
        canvas.invalidate();
    }

    private TextView chip(String label, boolean on, boolean enabled, String hover, View.OnClickListener tap) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), 0, px(14), 0);
        t.setTextColor(on ? Studio.GROUND : enabled ? 0xFFD4D4D8 : 0xFF52525B);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(18));
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36));
        lp.rightMargin = px(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }
}
