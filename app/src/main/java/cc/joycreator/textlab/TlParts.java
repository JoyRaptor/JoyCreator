package cc.joycreator.textlab;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a look can turn, read from {@code parts.json}: its slider groups (slots, the room's tabs), each slider, and
 * the PIECES a look is built from (the shader features a look lists, each bringing its own slider group). The twin
 * of the look book's panel rules in {@code tools/textlab-web/tl-app.js} ({@code SLOT_NEEDS}, {@code HIDDEN_FOR_TUBE},
 * {@code buildKnobs}): change both together.
 */
public final class TlParts {

    /** One slider (or colour) of a look. */
    public static final class Knob {
        public final String name, slot, label;
        /** A colour ("#rrggbb", or "#rrggbb*gain" for light brighter than white) rather than a number. */
        public final boolean colour;
        public final double min, max, step, def;
        @Nullable public final String defColour;

        Knob(String name, JSONObject k) {
            this.name = name;
            slot = k.optString("slot", "");
            label = k.optString("label", name);
            colour = k.has("c");
            defColour = colour ? k.optString("c") : null;
            def = k.optDouble("v", 0);
            min = k.optDouble("min", 0);
            max = k.optDouble("max", 1);
            step = k.optDouble("step", 0);
        }
    }

    /** A piece: a shader feature and the slider group it brings. */
    public static final class Piece {
        public final String feature, slot;

        Piece(String feature, String slot) {
            this.feature = feature;
            this.slot = slot;
        }
    }

    /** The slider group a feature brings (tl-app.js SLOT_NEEDS, inverted). */
    private static final String[][] NEEDS = {
            {"Tube", "TUBE"}, {"Glass", "REFRACT"}, {"Surface", "DETAIL"}, {"Pattern", "PATTERN"}, {"Ember", "EMBER"},
            {"Hologram", "HOLO"}, {"Stone", "STONE"}, {"Fur", "FUR"}, {"Lamps", "CELLS"}, {"Lightning", "ARCS"},
            {"Paper", "PAPER"}, {"Burn", "BURN"}, {"Vine", "VINE"}, {"Stitch", "STITCH"}, {"Balloon", "BALLOON"},
            {"Sand", "SAND"}, {"Bits", "BITS"}, {"Ink", "INK"}, {"Front", "FRONT"}, {"Shatter", "SHATTER"},
            {"Fog", "FOG"}, {"Flag", "FLAG"}, {"Panes", "STAINED"}, {"Rust", "RUST"}, {"Jelly", "JELLY"}};
    /** Groups a neon tube look hides: its letters are drawn glass, not bevelled type. */
    private static final Set<String> HIDDEN_FOR_TUBE = new HashSet<>(java.util.Arrays.asList(
            "Depth", "Coats", "Material", "Light", "Sides"));

    /** The Fire piece: a look's {@code "fire": true}, kept beside its features. */
    public static final String FIRE = "FIRE";

    public final List<String> slots = new ArrayList<>();
    public final Map<String, Knob> knobs = new LinkedHashMap<>();
    public final List<Piece> pieces = new ArrayList<>();
    private final Map<String, String> needs = new LinkedHashMap<>();

    public TlParts(@NonNull JSONObject parts) {
        JSONArray s = parts.optJSONArray("slots");
        if (s != null) for (int i = 0; i < s.length(); i++) slots.add(s.optString(i));
        JSONObject k = parts.optJSONObject("knobs");
        if (k != null) {
            for (Iterator<String> it = k.keys(); it.hasNext(); ) {
                String n = it.next();
                JSONObject o = k.optJSONObject(n);
                if (o != null) knobs.put(n, new Knob(n, o));
            }
        }
        pieces.add(new Piece(FIRE, "Fire"));   // fire is a switch of its own in a look ("fire": true), not a feature
        for (String[] p : NEEDS) {
            needs.put(p[0], p[1]);
            pieces.add(new Piece(p[1], p[0]));
        }
    }

    /** The feature a slot needs, or null for a slot every look has. */
    @Nullable
    public String needs(@NonNull String slot) {
        return needs.get(slot);
    }

    /** The look's features (its pieces). */
    @NonNull
    public static Set<String> features(@NonNull JSONObject look) {
        Set<String> out = new HashSet<>();
        JSONArray f = look.optJSONArray("features");
        if (f != null) for (int i = 0; i < f.length(); i++) out.add(f.optString(i));
        Object fire = look.opt("fire");
        if (fire != null && fire != JSONObject.NULL && !Boolean.FALSE.equals(fire)) out.add(FIRE);
        return out;
    }

    /** The slider groups this look shows, in the file's order (the look book's rule). */
    @NonNull
    public List<String> slotsFor(@NonNull JSONObject look) {
        Set<String> feats = features(look);
        boolean rivets = false;
        JSONArray ps = look.optJSONArray("particles");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                JSONObject pp = p != null ? p.optJSONObject("p") : null;
                if (pp != null && pp.optInt("from", -1) == 4) rivets = true;
            }
        }
        List<String> out = new ArrayList<>();
        for (String slot : slots) {
            String need = needs.get(slot);
            if (need != null && !feats.contains(need)) continue;
            if (slot.equals("Fire") && !feats.contains(FIRE)) continue;
            if (slot.equals("Rivets") && !rivets) continue;
            if (feats.contains("TUBE") && HIDDEN_FOR_TUBE.contains(slot)) continue;
            out.add(slot);
        }
        return out;
    }

    /** The slot groups this look has changed from the defaults (marked on their tabs). */
    @NonNull
    public Set<String> used(@NonNull JSONObject look) {
        Set<String> out = new HashSet<>();
        JSONObject p = look.optJSONObject("p");
        if (p == null) return out;
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
            Knob k = knobs.get(it.next());
            if (k != null) out.add(k.slot);
        }
        return out;
    }

    /** The sliders of one group, in the file's order. */
    @NonNull
    public List<Knob> inSlot(@NonNull String slot) {
        List<Knob> out = new ArrayList<>();
        for (Knob k : knobs.values()) if (k.slot.equals(slot)) out.add(k);
        return out;
    }

    /** The look with {@code feature} added or taken away (a copy; the look itself is not changed). */
    @NonNull
    public static JSONObject withFeature(@NonNull JSONObject look, @NonNull String feature, boolean on) throws Exception {
        JSONObject out = new JSONObject(look.toString());
        if (FIRE.equals(feature)) {
            if (on) out.put("fire", true);
            else out.remove("fire");
            return out;
        }
        List<String> f = new ArrayList<>(features(look));
        f.remove(FIRE);
        f.remove(feature);
        if (on) f.add(feature);
        Collections.sort(f);
        JSONArray a = new JSONArray();
        for (String s : f) a.put(s);
        out.put("features", a);
        return out;
    }

    /**
     * The progress a look plays (burn, blow away, grow, shatter), or null: {auto knob, gone knob, label, kind}. The
     * label is English for Joybot and the checks; the screen shows the kind's own words ({@code broken}, {@code grown},
     * {@code blown}, {@code burned} or {@code progress}) from {@code strings_textlab.xml}.
     */
    @Nullable
    public static String[] progress(@NonNull JSONObject look) {
        Set<String> f = features(look);
        if (f.contains("SHATTER")) return new String[]{"shatterAuto", "shatter", "Broken", "broken"};
        if (!f.contains("FRONT")) return null;
        if (f.contains("VINE")) return new String[]{"frontAuto", "frontGone", "Grown", "grown"};
        if (f.contains("SAND")) return new String[]{"frontAuto", "frontGone", "Blown away", "blown"};
        if (f.contains("BURN")) return new String[]{"frontAuto", "frontGone", "Burned away", "burned"};
        return new String[]{"frontAuto", "frontGone", "Progress", "progress"};
    }

    /** The progress bar's 0..1 as the gone knob's value (tl-app.js progOf.at). */
    public static double progressValue(@NonNull String goneKnob, double v) {
        return goneKnob.equals("shatter") ? v : 0.02 + v * 1.08;
    }
}
