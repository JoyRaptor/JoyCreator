package com.fadcam.ui.faditor.effects;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.effect.ColorLut;
import androidx.media3.effect.SingleColorLut;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LutManager {

    public static final String BUILTIN_WARM = "warm";
    public static final String BUILTIN_COOL = "cool";
    public static final String BUILTIN_PUNCHY = "punchy";
    public static final String BUILTIN_DESATURATED = "desaturated";
    public static final String BUILTIN_TEAL_ORANGE = "teal_orange";

    private LutManager() {}

    @NonNull
    public static List<LutPreset> builtInPresets() {
        List<LutPreset> presets = new ArrayList<>();
        presets.add(new LutPreset(BUILTIN_WARM, "Warm", "luts/" + BUILTIN_WARM + ".cube", null));
        presets.add(new LutPreset(BUILTIN_COOL, "Cool", "luts/" + BUILTIN_COOL + ".cube", null));
        presets.add(new LutPreset(BUILTIN_PUNCHY, "Punchy", "luts/" + BUILTIN_PUNCHY + ".cube", null));
        presets.add(new LutPreset(BUILTIN_DESATURATED, "Desaturated", "luts/" + BUILTIN_DESATURATED + ".cube", null));
        presets.add(new LutPreset(BUILTIN_TEAL_ORANGE, "Teal Orange", "luts/" + BUILTIN_TEAL_ORANGE + ".cube", null));
        return Collections.unmodifiableList(presets);
    }

    @Nullable
    public static ColorLut load(@NonNull Context context, @NonNull String id) {
        try {
            return SingleColorLut.createFromBitmap(loadBitmap(context, id));
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public static Bitmap loadBitmap(@NonNull Context context, @NonNull String id) {
        try (InputStream in = context.getAssets().open("luts/" + id + ".png")) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        }
        try {
            int[][][] cube = parseCube(context.getAssets().open("luts/" + id + ".cube"));
            if (cube == null) return null;
            return cubeToBitmap(cube);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public static int[][][] parseCube(@NonNull InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        int size = 0;
        List<float[]> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("TITLE")
                    || trimmed.startsWith("DOMAIN") || trimmed.startsWith("LUT_1D_SIZE")) {
                continue;
            }
            if (trimmed.startsWith("LUT_3D_SIZE")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2) size = Integer.parseInt(parts[1]);
                continue;
            }
            if (trimmed.startsWith("LUT_3D")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 3) continue;
            rows.add(new float[]{
                    Float.parseFloat(parts[0]),
                    Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2])
            });
        }
        if (size <= 0) size = cubeSizeFromRows(rows.size());
        if (size <= 0 || size > 64 || rows.size() < size * size * size) return null;
        int[][][] cube = new int[size][size][size];
        int index = 0;
        for (int r = 0; r < size; r++) {
            for (int g = 0; g < size; g++) {
                for (int b = 0; b < size; b++) {
                    float[] rgb = rows.get(index++);
                    cube[r][g][b] = Color.argb(255,
                            clampByte(rgb[0]), clampByte(rgb[1]), clampByte(rgb[2]));
                }
            }
        }
        return cube;
    }

    @NonNull
    public static Bitmap cubeToBitmap(@NonNull int[][][] cube) {
        int n = cube.length;
        int[] pixels = new int[n * n * n];
        int index = 0;
        for (int r = 0; r < n; r++) {
            for (int g = 0; g < n; g++) {
                for (int b = 0; b < n; b++) {
                    pixels[index++] = cube[r][g][b];
                }
            }
        }
        return Bitmap.createBitmap(pixels, n, n * n, Bitmap.Config.ARGB_8888);
    }

    private static int cubeSizeFromRows(int rows) {
        int root = (int) Math.round(Math.cbrt(rows));
        return root * root * root == rows ? root : 0;
    }

    private static int clampByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }
}
