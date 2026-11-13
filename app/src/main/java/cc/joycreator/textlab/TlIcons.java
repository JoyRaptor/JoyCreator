package cc.joycreator.textlab;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.fadcam.R;

import java.util.HashMap;
import java.util.Map;

/**
 * TextLab's own icons (owner-approved 2026-11-12): one per property tab of a look, keyed by its slot in
 * {@code parts.json}. White line drawings; tint them (zinc off, ground-coloured on solid cyan). The Look row's icons
 * are used by name in {@link TextLookRow}. Drawn and kept in {@code tasks/design/textlab-icons/}
 * ({@code to_android.py} writes the drawables).
 */
public final class TlIcons {

    private static final Map<String, Integer> TABS = new HashMap<>();

    static {
        put("Shape", R.drawable.ic_tl_tab_shape);
        put("Depth", R.drawable.ic_tl_tab_depth);
        put("Coats", R.drawable.ic_tl_tab_coats);
        put("Material", R.drawable.ic_tl_tab_material);
        put("Wear", R.drawable.ic_tl_tab_wear);
        put("Light", R.drawable.ic_tl_tab_light);
        put("Sides", R.drawable.ic_tl_tab_sides);
        put("Floor", R.drawable.ic_tl_tab_floor);
        put("Tube", R.drawable.ic_tl_tab_tube);
        put("Glass", R.drawable.ic_tl_tab_glass);
        put("Surface", R.drawable.ic_tl_tab_surface);
        put("Pattern", R.drawable.ic_tl_tab_pattern);
        put("Stone", R.drawable.ic_tl_tab_stone);
        put("Fur", R.drawable.ic_tl_tab_fur);
        put("Lamps", R.drawable.ic_tl_tab_lamps);
        put("Lightning", R.drawable.ic_tl_tab_lightning);
        put("Paper", R.drawable.ic_tl_tab_paper);
        put("Burn", R.drawable.ic_tl_tab_burn);
        put("Vine", R.drawable.ic_tl_tab_vine);
        put("Stitch", R.drawable.ic_tl_tab_stitch);
        put("Balloon", R.drawable.ic_tl_tab_balloon);
        put("Sand", R.drawable.ic_tl_tab_sand);
        put("Bits", R.drawable.ic_tl_tab_bits);
        put("Ink", R.drawable.ic_tl_tab_ink);
        put("Front", R.drawable.ic_tl_tab_front);
        put("Shatter", R.drawable.ic_tl_tab_shatter);
        put("Fog", R.drawable.ic_tl_tab_fog);
        put("Flag", R.drawable.ic_tl_tab_flag);
        put("Panes", R.drawable.ic_tl_tab_panes);
        put("Rust", R.drawable.ic_tl_tab_rust);
        put("Rivets", R.drawable.ic_tl_tab_rivets);
        put("Jelly", R.drawable.ic_tl_tab_jelly);
        put("Ember", R.drawable.ic_tl_tab_ember);
        put("Fire", R.drawable.ic_tl_tab_fire);
        put("Hologram", R.drawable.ic_tl_tab_hologram);
        put("Finish", R.drawable.ic_tl_tab_finish);
        put("Arrive", R.drawable.ic_tl_tab_arrive);
    }

    private TlIcons() {}

    private static void put(String slot, @DrawableRes int res) {
        TABS.put(slot, res);
    }

    /** The icon for a property tab ("Depth", "Fire"...), or 0 for a slot that has none yet. */
    @DrawableRes
    public static int forTab(@NonNull String slot) {
        Integer r = TABS.get(slot);
        return r != null ? r : 0;
    }
}
