package com.fadcam.ui.size;

import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

/**
 * The current window's size classes (TAB-1). Ask this, never "is it a tablet".
 *
 * <p>{@link Configuration#screenWidthDp} / {@code screenHeightDp} are the size of the window the
 * activity is in, not of the device: half a tablet in split-screen reads as half, a phone turned
 * sideways reads as wide and short. Each is the space left after the system bars, which is what
 * a layout actually gets.
 *
 * <p>For a decision about ONE view (a list inside a panel, say), measure the view itself with
 * {@link SizeClass#columns} or {@link ContentColumn}; this class is for whole-screen choices.
 */
public final class WindowSize {

    private WindowSize() {}

    public static int widthDp(@NonNull Context c) {
        return c.getResources().getConfiguration().screenWidthDp;
    }

    public static int heightDp(@NonNull Context c) {
        return c.getResources().getConfiguration().screenHeightDp;
    }

    @NonNull
    public static SizeClass.Width width(@NonNull Context c) {
        return SizeClass.width(widthDp(c));
    }

    @NonNull
    public static SizeClass.Height height(@NonNull Context c) {
        return SizeClass.height(heightDp(c));
    }

    /** MEDIUM or EXPANDED width: a phone sideways, split-screen on a tablet, any tablet. */
    public static boolean isWide(@NonNull Context c) {
        return width(c) != SizeClass.Width.COMPACT;
    }

    /** A tablet sideways or a big tablet upright: room for a side panel beside the main view. */
    public static boolean isExpanded(@NonNull Context c) {
        return width(c) == SizeClass.Width.EXPANDED;
    }

    /** Short window (a phone sideways): keep things that open from the bottom short. */
    public static boolean isShort(@NonNull Context c) {
        return height(c) == SizeClass.Height.COMPACT;
    }
}
