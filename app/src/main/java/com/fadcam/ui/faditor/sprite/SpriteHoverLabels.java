package com.fadcam.ui.faditor.sprite;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;

/**
 * Hover-label text for the sprite sheet editor's number pills (ANI-1 C7). The pills show a short
 * key ("mg x", "piv y", "tol") that a stylus or mouse hover should spell out in plain words.
 * Strings live in strings_hover_puppet.xml.
 */
final class SpriteHoverLabels {

    private SpriteHoverLabels() {}

    /**
     * The tooltip for a pill, by the key it shows. An empty key is the onion-skin strength
     * pill, the only keyless pill in the editor.
     */
    @Nullable
    static String forNumPill(@NonNull Context c, @NonNull String key) {
        int res;
        switch (key) {
            case "":      res = R.string.hover_sprite_onion_strength; break;
            case "cols":  res = R.string.hover_sprite_cols; break;
            case "rows":  res = R.string.hover_sprite_rows; break;
            case "mg x":  res = R.string.hover_sprite_margin_x; break;
            case "mg y":  res = R.string.hover_sprite_margin_y; break;
            case "sp x":  res = R.string.hover_sprite_spacing_x; break;
            case "sp y":  res = R.string.hover_sprite_spacing_y; break;
            case "tol":   res = R.string.hover_sprite_tolerance; break;
            case "x":     res = R.string.hover_sprite_move_x; break;
            case "y":     res = R.string.hover_sprite_move_y; break;
            case "scale": res = R.string.hover_sprite_scale; break;
            case "rot":   res = R.string.hover_sprite_rotate; break;
            case "piv x": res = R.string.hover_sprite_pivot_x; break;
            case "piv y": res = R.string.hover_sprite_pivot_y; break;
            case "pad":   res = R.string.hover_sprite_bake_pad; break;
            default:      return null;
        }
        return c.getString(res);
    }
}
