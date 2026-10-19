package com.fadcam.vislab.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.motion.Motion;
import com.fadcam.vislab.room.Ui;

import java.util.List;

/**
 * Every look by name, above the floating visualiser's controls: tap the look's name to open it,
 * tap a look to switch. Inside the overlay's own window (a sheet cannot open over other apps).
 */
@SuppressLint("ViewConstructor")
final class OverlayPicker extends ScrollView {

    private final LinearLayout list;
    private final OverlayControls.Actions actions;

    OverlayPicker(@NonNull Context c, @NonNull OverlayControls.Actions a) {
        super(c);
        actions = a;
        setVerticalScrollBarEnabled(false);
        setBackground(Ui.round(VisOverlayService.LEGIBLE, Ui.dp(c, 16)));
        int p = Ui.dp(c, 6);
        setPadding(p, p, p, p);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        addView(list);
        setOnScrollChangeListener((v, x, y, ox, oy) -> actions.touched());
    }

    /** Fill with the looks, the one showing marked. */
    void show(@NonNull List<String> ids, @NonNull List<String> names, @Nullable String current) {
        Context c = getContext();
        list.removeAllViews();
        int at = -1;
        for (int i = 0; i < ids.size() && i < names.size(); i++) {
            String id = ids.get(i);
            boolean on = id.equals(current);
            if (on) at = i;
            TextView t = Ui.text(c, names.get(i), 13, on ? Studio.GROUND : Studio.INK, false, on ? 700 : 500);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setSingleLine(true);
            int h = Ui.dp(c, 12);
            t.setPadding(h, 0, h, 0);
            t.setMinHeight(Ui.dp(c, 38));
            if (on) t.setBackground(Ui.round(Studio.ARMED, Ui.dp(c, 999)));   // ON = solid cyan (the owner's rule)
            Ui.label(t, "Show " + names.get(i));
            t.setOnClickListener(v -> {
                Motion.press(v);
                actions.pickLook(id);
            });
            list.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        final int row = Ui.dp(c, 38), first = at;
        if (first > 2) post(() -> scrollTo(0, (first - 2) * row));   // the one showing in view
    }
}
