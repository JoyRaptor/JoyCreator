package com.fadcam.ui.faditor.markers;

import com.fadcam.ui.faditor.Studio;

/** Marker identity uses the existing room/action gradients, never a second private palette. */
public final class MarkerPalette {
    private MarkerPalette() { }
    public static int start(int color) {
        switch (color) {
            case 1: return Studio.GO;
            case 2: return Studio.ROOM_VIZ;
            case 3: return Studio.ROOM_CAPTURE;
            default: return Studio.ROOM_AVATAR;
        }
    }
    public static int end(int color) {
        switch (color) {
            case 1: return Studio.GO_END;
            case 2: return Studio.ROOM_VIZ_DEEP;
            case 3: return Studio.ROOM_SPRITE;
            default: return Studio.ROOM_AVATAR_DEEP;
        }
    }
}
