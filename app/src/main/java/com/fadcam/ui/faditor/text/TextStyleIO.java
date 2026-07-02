package com.fadcam.ui.faditor.text;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TextStyleIO {

    private TextStyleIO() {}

    @NonNull
    public static List<TextStyle> loadBuiltIns(@NonNull Context context) {
        List<TextStyle> styles = new ArrayList<>();
        styles.add(defaultStyle());
        addAsset(context, styles, "text_styles/caption_pop.json");
        addAsset(context, styles, "text_styles/title_bold.json");
        return styles;
    }

    @NonNull
    public static String toJson(@NonNull TextStyle style) {
        try {
            JSONObject json = new JSONObject();
            json.put("id", style.getId());
            json.put("name", style.getName());
            json.put("fontFamily", style.getFontFamily());
            json.put("colorInt", style.getColorInt());
            json.put("strokeColorInt", style.getStrokeColorInt());
            json.put("strokeWidthPx", style.getStrokeWidthPx());
            json.put("shadowColorInt", style.getShadowColorInt());
            json.put("shadowRadiusPx", style.getShadowRadiusPx());
            json.put("glowColorInt", style.getGlowColorInt());
            json.put("glowRadiusPx", style.getGlowRadiusPx());
            json.put("backgroundColorInt", style.getBackgroundColorInt());
            json.put("entrance", style.getEntrance());
            json.put("exit", style.getExit());
            return json.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    @NonNull
    public static TextStyle fromJson(@NonNull String json) {
        try {
            JSONObject obj = new JSONObject(json);
            TextStyle style = new TextStyle(
                    obj.optString("id", "custom"),
                    obj.optString("name", "Custom"));
            style.setFontFamily(obj.optString("fontFamily", "default"));
            style.setColorInt(obj.optInt("colorInt", Color.WHITE));
            style.setStrokeColorInt(obj.optInt("strokeColorInt", Color.TRANSPARENT));
            style.setStrokeWidthPx((float) obj.optDouble("strokeWidthPx", 0d));
            style.setShadowColorInt(obj.optInt("shadowColorInt", 0xCC000000));
            style.setShadowRadiusPx((float) obj.optDouble("shadowRadiusPx", 0d));
            style.setGlowColorInt(obj.optInt("glowColorInt", Color.TRANSPARENT));
            style.setGlowRadiusPx((float) obj.optDouble("glowRadiusPx", 0d));
            style.setBackgroundColorInt(obj.optInt("backgroundColorInt", Color.TRANSPARENT));
            style.setEntrance(obj.optString("entrance", "none"));
            style.setExit(obj.optString("exit", "none"));
            return style;
        } catch (Exception e) {
            return defaultStyle();
        }
    }

    private static void addAsset(@NonNull Context context, @NonNull List<TextStyle> styles,
                                 @NonNull String path) {
        try (InputStream in = context.getAssets().open(path)) {
            byte[] bytes = in.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            Object parsed = new org.json.JSONTokener(json).nextValue();
            if (parsed instanceof JSONArray) {
                JSONArray array = (JSONArray) parsed;
                for (int i = 0; i < array.length(); i++) {
                    styles.add(fromJson(array.getJSONObject(i).toString()));
                }
            } else if (parsed instanceof JSONObject) {
                styles.add(fromJson(((JSONObject) parsed).toString()));
            }
        } catch (Exception ignored) {
        }
    }

    @NonNull
    private static TextStyle defaultStyle() {
        TextStyle style = new TextStyle("default", "Default");
        style.setColorInt(Color.WHITE);
        style.setShadowColorInt(0xCC000000);
        style.setShadowRadiusPx(8f);
        return style;
    }
}
