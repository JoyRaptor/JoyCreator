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
                if (t != null && t.contains("x")) aspect = ratio(t);   // a non-square tile: its shape
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

    /**
     * "300x200" (pixels) as the shape JoyBrush takes: "3:2". JoyBrush only accepts ratios up to 100 either way,
     * so the pixel pair is reduced by its common factor, then scaled down if it is still too big; before this a
     * non-square tile arrived as "300:200", was dropped quietly and the board came out square.
     */
    @Nullable
    static String ratio(@NonNull String pixels) {
        String[] p = pixels.toLowerCase(java.util.Locale.US).split("x");
        if (p.length != 2) return null;
        Integer w = num(p[0]), h = num(p[1]);
        if (w == null || h == null || w < 1 || h < 1) return null;
        int g = gcd(w, h);
        long rw = w / g, rh = h / g;
        while (rw > 100 || rh > 100) {
            rw = Math.max(1, Math.round(rw / 2.0));
            rh = Math.max(1, Math.round(rh / 2.0));
        }
        return rw + ":" + rh;
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    @Nullable
    private static Integer num(@Nullable String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
