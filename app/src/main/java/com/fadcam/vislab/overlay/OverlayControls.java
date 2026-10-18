package com.fadcam.vislab.overlay;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.motion.Motion;
import com.fadcam.vislab.room.Ui;

import java.util.List;

/**
 * The floating visualiser's one row of controls: previous look, its name (and what it is
 * listening to), next look, box / full screen, close. Tapping the name opens every look by name
 * above the row ({@link OverlayPicker}). Frosted like the drawers, every button labelled for a
 * stylus hover.
 */
final class OverlayControls extends LinearLayout {

    interface Actions {
        void step(int dir);

        void toggleFull();

        void close();

        @NonNull List<String> lookIds();

        @NonNull List<String> lookNames();

        @Nullable String currentLook();

        void pickLook(@NonNull String id);

        /** The person is using the controls: keep them up a while longer. */
        void touched();

        /** Pixels the look list may take above the row (a short box has less). */
        int roomForList();
    }

    private final TextView name, hears, mode;
    private final OverlayPicker picker;
    private final Actions actions;

    OverlayControls(@NonNull Context c, @NonNull Actions a) {
        super(c);
        actions = a;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        picker = new OverlayPicker(c, a);
        picker.setVisibility(GONE);
        LayoutParams pl = new LayoutParams(Ui.dp(c, 300), Ui.dp(c, 228));
        pl.bottomMargin = Ui.dp(c, 6);
        addView(picker, pl);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(c, 6);
        row.setPadding(p, p, p, p);
        row.setBackground(Ui.round(VisOverlayService.LEGIBLE, Ui.dp(c, 999)));
        row.addView(button(c, "chevron_left", "Previous look", v -> a.step(-1)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        words.setGravity(Gravity.CENTER);
        name = Ui.text(c, "", 13, Studio.INK, false, 600);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        hears = Ui.text(c, "", 10, Studio.INK_FAINT, false, 400);
        hears.setGravity(Gravity.CENTER);
        hears.setSingleLine(true);
        words.addView(name);
        words.addView(hears);
        Ui.label(words, "Every look by name: tap to choose");
        words.setOnClickListener(v -> {
            Motion.press(v);
            togglePicker();
        });
        LayoutParams wl = new LayoutParams(Ui.dp(c, 128), LayoutParams.WRAP_CONTENT);
        row.addView(words, wl);
        row.addView(button(c, "chevron_right", "Next look", v -> a.step(1)));
        mode = button(c, "fullscreen", "Fill the screen (touches pass through to the app below)", v -> a.toggleFull());
        row.addView(mode);
        row.addView(button(c, "close", "Turn the visualiser off", v -> a.close()));
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    void setLook(@NonNull String look) {
        name.setText(look);
    }

    void setSource(int kind) {
        hears.setText(kind == OverlaySound.RECORDING ? "hearing the recording"
                : kind == OverlaySound.MIC ? "hearing the microphone" : "waiting for sound");
    }

    void setFull(boolean full) {
        mode.setText(full ? "fullscreen_exit" : "fullscreen");
        Ui.label(mode, full ? "Back to a box you can move and resize"
                : "Fill the screen (touches pass through to the app below)");
    }

    private void togglePicker() {
        if (picker.getVisibility() == VISIBLE) {
            closePicker();
            return;
        }
        picker.show(actions.lookIds(), actions.lookNames(), actions.currentLook());
        LayoutParams pl = (LayoutParams) picker.getLayoutParams();
        pl.height = Math.max(Ui.dp(getContext(), 80), Math.min(Ui.dp(getContext(), 228), actions.roomForList()));
        picker.setLayoutParams(pl);
        picker.setVisibility(VISIBLE);
        actions.touched();
    }

    void closePicker() {
        picker.setVisibility(GONE);
    }

    boolean pickerOpen() {
        return picker.getVisibility() == VISIBLE;
    }

    @NonNull
    private static TextView button(@NonNull Context c, @NonNull String lig, @NonNull String hover,
                                   @NonNull View.OnClickListener l) {
        TextView t = Ui.icon(c, lig, 22, Studio.INK);
        t.setGravity(Gravity.CENTER);
        int s = Ui.dp(c, 40);
        t.setLayoutParams(new LayoutParams(s, s));
        Ui.label(t, hover);
        t.setOnClickListener(v -> {
            Motion.press(v);
            l.onClick(v);
        });
        return t;
    }
}
