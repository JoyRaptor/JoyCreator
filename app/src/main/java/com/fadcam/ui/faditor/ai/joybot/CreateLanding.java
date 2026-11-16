package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cc.joycreator.joybrush.android.JoyBrushLaunch;

/**
 * A create-tree landing in JoyBrush, as the JoyBrush screens lane's launch preset (11-15,
 * {@link JoyBrushLaunch}): a new drawing whose board is made at that shape, as one Undo step,
 * with the guide on. Bad values are dropped by JoyBrush quietly (worst case: a plain new drawing).
 */
public final class CreateLanding {
    private CreateLanding() { }

    @NonNull
    public static JoyBrushLaunch.Preset preset(@NonNull CreateTree.Landing l) {
        String board = null, aspect = null, guide = null;
        Integer fps = null, cols = null, rows = null, cellPx = null, tilePx = null;
        switch (l.kind.id) {
            case "paint":
                board = "image";
                aspect = l.get("shape");
                break;
            case "perspective":
                board = "image";
                aspect = l.get("shape");
                guide = "perspective-" + l.get("perspective");
                break;
            case "animation":
                board = "animation";
                aspect = l.get("shape");
                fps = num(l.get("fps"));
                break;
            case "tile": {
                board = "tile";
                String t = l.get("tile");
                if (t != null && t.contains("x")) aspect = t.replace('x', ':');   // a non-square tile: its shape
                else tilePx = num(t);
                break;
            }
            case "sprite_new": {
                board = "sprite";
                String g = l.get("grid");   // "4x4@64"
                if (g != null) {
                    String[] at = g.split("@");
                    String[] cr = at[0].split("x");
                    if (cr.length == 2) { cols = num(cr[0]); rows = num(cr[1]); }
                    if (at.length == 2) cellPx = num(at[1]);
                }
                break;
            }
            default:
                break;
        }
        return new JoyBrushLaunch.Preset(board, aspect, fps, cols, rows, guide, null, cellPx, tilePx);
    }

    @Nullable
    private static Integer num(@Nullable String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
