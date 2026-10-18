package com.fadcam.vislab.room;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.lobby.SlantDrawable;
import com.fadcam.ui.motion.Motion;

import java.util.ArrayList;
import java.util.List;

/**
 * A row of choices in the lobby's sheared shape: one object divided, scanned in one sweep.
 * The first chip is squared off on the left so the row sits flush in the gutter; every edge
 * after it leans the same way. Off: raised zinc face, the icon carries the gradient. On: the
 * face takes the gradient and the icon and label are punched out in pure black.
 */
final class SlantRow extends LinearLayout {

    interface OnPick {
        void onPick(int index);
    }

    private static final int SLANT_DP = 10;

    private final int gradA, gradB;
    private final List<LinearLayout> chips = new ArrayList<>();
    private final List<TextView> icons = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private int selected = -1;
    @Nullable private OnPick onPick;

    SlantRow(@NonNull Context c, int gradA, int gradB) {
        super(c);
        this.gradA = gradA;
        this.gradB = gradB;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
    }

    void setOnPick(@Nullable OnPick l) {
        onPick = l;
    }

    @SuppressLint("ClickableViewAccessibility")
    void add(@NonNull String ligature, @NonNull String label, @NonNull String hover) {
        Context c = getContext();
        final int index = chips.size();
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(HORIZONTAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        LayoutParams lp = new LayoutParams(0, Ui.dp(c, 34), 1f);
        lp.setMarginEnd(Math.round(3.5f * c.getResources().getDisplayMetrics().density));
        cell.setLayoutParams(lp);
        TextView ic = Ui.icon(c, ligature, 15, Studio.INK);
        cell.addView(ic);
        TextView tv = Ui.text(c, label, 11, Studio.INK_DIM, true, 700);
        tv.setSingleLine(true);
        LayoutParams tlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(Ui.dp(c, 5));
        cell.addView(tv, tlp);
        Ui.label(cell, hover);
        cell.setOnClickListener(v -> {
            Motion.press(v);
            setSelected(index);
            if (onPick != null) onPick.onPick(index);
        });
        cell.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.post(() -> paint(index));
            }
            return false;
        });
        ic.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> paint(index));
        chips.add(cell);
        icons.add(ic);
        labels.add(tv);
        addView(cell);
        paint(index);
    }

    void setSelected(int index) {
        int old = selected;
        selected = index;
        if (old >= 0 && old < chips.size()) paint(old);
        if (index >= 0 && index < chips.size()) paint(index);
    }

    int selected() {
        return selected;
    }

    private void paint(int i) {
        Context c = getContext();
        LinearLayout cell = chips.get(i);
        boolean on = i == selected;
        boolean pressed = cell.isPressed();
        float slant = Ui.dp(c, SLANT_DP);
        float corner = Ui.dp(c, 3);
        boolean first = i == 0;
        cell.setBackground(on ? new SlantDrawable(gradA, gradB, slant, corner, first, false)
                : new SlantDrawable(pressed ? Studio.PRESSED : Studio.RAISED, pressed ? Studio.PRESSED : Studio.RAISED,
                slant, corner, first, false));
        TextView ic = icons.get(i);
        if (on) {
            ic.getPaint().setShader(null);
            ic.setTextColor(Studio.GROUND);
        } else {
            int w = Math.max(1, ic.getWidth());
            ic.setTextColor(Studio.INK);
            ic.getPaint().setShader(new LinearGradient(0, 0, w, 0, gradA, gradB, Shader.TileMode.CLAMP));
        }
        ic.invalidate();
        labels.get(i).setTextColor(on ? Studio.GROUND : Studio.INK_DIM);
        cell.setSelected(on);
    }

    /** Hide or show a chip (a source this phone cannot offer). */
    void setChipVisible(int i, boolean visible) {
        if (i >= 0 && i < chips.size()) chips.get(i).setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
