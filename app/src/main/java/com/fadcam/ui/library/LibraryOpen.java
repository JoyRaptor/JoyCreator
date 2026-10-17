package com.fadcam.ui.library;

import android.app.Activity;

import androidx.annotation.NonNull;

/**
 * The Library's own "tap an item" opener, reachable from outside the package — Joybot's
 * {@code open_library_item} opens a thing exactly the way tapping it in the Library does
 * (a project in the Studio, a drawing in JoyBrush, a sheet in Sprite Lab, a video in the player).
 */
public final class LibraryOpen {
    private LibraryOpen() { }

    /** True when it opened; false when nothing opens it (the Library would show its menu instead). */
    public static boolean open(@NonNull Activity a, @NonNull LibraryItem item) {
        return LibraryOpener.open(a, item);
    }
}
