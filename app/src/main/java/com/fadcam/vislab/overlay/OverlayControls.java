package com.fadcam.vislab.overlay;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.motion.Motion;
import com.fadcam.vislab.room.Ui;

/**
 * The floating visualiser's one row of controls: previous look, its name (and what it is
 * listening to), next look, box / full screen, close. Frosted like the drawers, every button
 * labelled for a stylus hover.
 */
final class OverlayControls extends LinearLayout {

    interface Actions {
        void step(int dir);

        void toggleFull();

        void close();
    }

    private final TextView name, hears, mode;

    OverlayControls(@NonNull Context c, @NonNull Actions a) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(c, 6);
        setPadding(p, p, p, p);
        setBackground(Ui.round(VisOverlayService.LEGIBLE, Ui.dp(c, 999)));
        addView(button(c, "chevron_left", "Previous look", v -> a.step(-1)));
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
        LayoutParams wl = new LayoutParams(Ui.dp(c, 128), LayoutParams.WRAP_CONTENT);
        addView(words, wl);
        addView(button(c, "chevron_right", "Next look", v -> a.step(1)));
        mode = button(c, "fullscreen", "Fill the screen (touches pass through to the app below)", v -> a.toggleFull());
        addView(mode);
        addView(button(c, "close", "Turn the visualiser off", v -> a.close()));
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
