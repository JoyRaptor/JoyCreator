package com.fadcam.ui.settingshub;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeping the search field above the keyboard, as the Library does (copied from
 * {@code LibraryFragment}, which was checked on the Note 9's Android 10): the window resizes while
 * the hub is up, and the dock is lifted by however much of the keyboard the system bars do not
 * already cover.
 */
final class SettingsKeyboard {

    interface Lift {
        /** How far the dock now sits above its resting place, in px (0 with the keyboard down). */
        void onLift(int px);
    }

    /** The window's keyboard mode before the hub took over; -1 when it has not. */
    private int previousMode = -1;

    /**
     * The search field sits at the bottom; with the host window's own mode (pan, or nothing) the
     * keyboard covered it and you typed blind. Resize while the hub is resumed.
     */
    void takeOver(@NonNull Activity a) {
        Window w = a.getWindow();
        previousMode = w.getAttributes().softInputMode;
        w.setSoftInputMode((previousMode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /** Put the host's own mode back when the hub leaves. */
    void giveBack(Activity a) {
        if (previousMode != -1 && a != null) a.getWindow().setSoftInputMode(previousMode);
        previousMode = -1;
    }

    /**
     * Keep {@code dock} above the keyboard. Up to Android 10 the window's own bottom inset already
     * includes the keyboard (MainActivity pads the overlay by it); from 11 the keyboard is its own
     * inset, so the dock is lifted by the part of it the system bars do not already cover.
     */
    static void liftOver(@NonNull View root, @NonNull View dock, @NonNull Lift lift) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int px;
            if (Build.VERSION.SDK_INT >= 30) {
                int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                int bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                px = ime - bars;
            } else {
                // Android 10 and older: the keyboard arrives inside the system window inset, and the
                // stable inset is what the bars alone take. The difference is the keyboard.
                px = insets.getSystemWindowInsetBottom() - insets.getStableInsetBottom();
            }
            px = Math.max(0, px);
            dock.setTranslationY(-px);
            lift.onLift(px);
            return insets;
        });
    }
}
