package com.fadcam.ui.faditor.gltransitions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GLTransitionCatalog {

    public static final class Param {
        @NonNull public final String name;
        public final float defaultValue;
        public final float minValue;
        public final float maxValue;

        public Param(@NonNull String name, float defaultValue, float minValue, float maxValue) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }
    }

    public static final class Entry {
        @NonNull public final String id;
        @NonNull public final String displayName;
        @NonNull public final String category;
        @NonNull public final String costTier;
        @NonNull public final List<Param> params;

        public Entry(@NonNull String id, @NonNull String displayName,
                     @NonNull String category, @NonNull String costTier,
                     @NonNull List<Param> params) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.costTier = costTier;
            this.params = Collections.unmodifiableList(new ArrayList<>(params));
        }

        public float defaultFor(@NonNull String name) {
            for (Param param : params) {
                if (param.name.equals(name)) return param.defaultValue;
            }
            return 0f;
        }
    }

    private GLTransitionCatalog() {}

    @NonNull
    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        entries.add(entry("zoomInOut", "Zoom Punch", "zoom", "Light"));
        entries.add(entry("CrossZoom", "Cross Zoom", "zoom", "Light", params(param("strength", 0.4f, 0f, 1f))));
        entries.add(entry("DefocusBlur", "Defocus", "blur", "Heavy", params(param("blurSize", 0.02f, 0f, 0.2f))));
        entries.add(entry("tangentMotionBlur", "Motion Blur", "blur", "Heavy"));
        entries.add(entry("burn0", "Burn Soft", "flash", "Light"));
        entries.add(entry("burn", "Burn", "flash", "Light"));
        entries.add(entry("FilmBurn", "Film Burn", "flash", "Light", params(param("Seed", 2.31f, 0f, 10f))));
        entries.add(entry("Overexposure", "Overexposure", "flash", "Light", params(param("strength", 0.6f, 0f, 1f))));
        entries.add(entry("HSVfade", "HSV Fade", "color", "Light"));
        entries.add(entry("colorphase", "Color Phase", "color", "Light"));
        entries.add(entry("powerKaleido", "Kaleidoscope", "warp", "Medium", params(
                param("scale", 2.0f, 0.1f, 10f),
                param("z", 1.5f, 0.1f, 10f),
                param("speed", 5.0f, 0.1f, 20f))));
        entries.add(entry("polar_function", "Polar Warp", "warp", "Medium"));
        entries.add(entry("Dreamy", "Dreamy Wave", "warp", "Light"));
        entries.add(entry("swap", "Swap", "slide", "Light", params(
                param("reflection", 0.4f, 0f, 1f),
                param("perspective", 0.2f, 0f, 1f),
                param("depth", 3.0f, 0f, 10f))));
        entries.add(entry("crosswarp", "Cross Warp", "warp", "Light"));
        entries.add(entry("ripple", "Ripple", "wave", "Light", params(
                param("amplitude", 100.0f, 0f, 200f),
                param("speed", 50.0f, 0f, 200f))));
        entries.add(entry("Radial", "Radial Wipe", "wipe", "Light", params(param("smoothness", 1.0f, 0f, 10f))));
        entries.add(entry("heart", "Heart Reveal", "mask", "Light"));
        entries.add(entry("Drop_Zone_Flicker", "Flicker Drop", "strobe", "Medium", params(
                param("frameRate", 24.0f, 1f, 60f),
                param("rgbOffset", 0.014f, 0f, 0.1f),
                param("blockAmount", 0.72f, 0f, 1f),
                param("ghostAmount", 0.62f, 0f, 1f),
                param("redCyan", 0.58f, 0f, 1f),
                param("scanline", 0.075f, 0f, 0.2f))));
        entries.add(entry("cube", "Cube Rotate", "3d", "Medium", params(
                param("persp", 0.7f, 0f, 2f),
                param("unzoom", 0.3f, 0f, 1f),
                param("reflection", 0.4f, 0f, 1f),
                param("floating", 3.0f, 0f, 10f))));
        entries.add(entry("BookFlip", "Book Flip", "3d", "Medium"));
        entries.add(entry("InvertedPageCurl", "Page Curl", "3d", "Medium"));
        entries.add(entry("GridFlip", "Grid Flip", "3d", "Medium", params(
                param("pause", 0.1f, 0f, 1f),
                param("dividerWidth", 0.05f, 0f, 0.5f),
                param("randomness", 0.1f, 0f, 1f))));
        entries.add(entry("Fold", "Fold", "3d", "Medium"));
        entries.add(entry("SimpleFlip", "Simple Flip", "3d", "Light"));
        entries.add(entry("StereoViewer", "Split Viewer", "split", "Medium", params(
                param("zoom", 0.88f, 0f, 2f),
                param("corner_radius", 0.22f, 0f, 1f))));
        entries.add(entry("PushLeft", "Push Left", "push", "Light"));
        entries.add(entry("PushRight", "Push Right", "push", "Light"));
        entries.add(entry("PushUp", "Push Up", "push", "Light"));
        entries.add(entry("PushDown", "Push Down", "push", "Light"));
        entries.add(entry("WipeLeft", "Wipe Left", "wipe", "Light"));
        entries.add(entry("WipeRight", "Wipe Right", "wipe", "Light"));
        entries.add(entry("WipeUp", "Wipe Up", "wipe", "Light"));
        entries.add(entry("WipeDown", "Wipe Down", "wipe", "Light"));
        entries.add(entry("FadcamGlitch", "Glitch", "glitch", "Medium"));
        return Collections.unmodifiableList(entries);
    }

    @Nullable
    public static Entry find(@NonNull String id) {
        for (Entry entry : entries()) {
            if (entry.id.equals(id)) return entry;
        }
        return null;
    }

    @NonNull
    public static String displayName(@Nullable String id) {
        Entry entry = id == null ? null : find(id);
        return entry == null ? "GL Transition" : entry.displayName;
    }

    @NonNull
    private static Entry entry(@NonNull String id, @NonNull String displayName,
                               @NonNull String category, @NonNull String costTier) {
        return new Entry(id, displayName, category, costTier, new ArrayList<Param>());
    }

    @NonNull
    private static Entry entry(@NonNull String id, @NonNull String displayName,
                               @NonNull String category, @NonNull String costTier,
                               @NonNull List<Param> params) {
        return new Entry(id, displayName, category, costTier, params);
    }

    @NonNull
    private static List<Param> params(@NonNull Param... params) {
        List<Param> out = new ArrayList<>();
        Collections.addAll(out, params);
        return out;
    }

    @NonNull
    private static Param param(@NonNull String name, float defaultValue,
                               float minValue, float maxValue) {
        return new Param(name, defaultValue, minValue, maxValue);
    }
}
