package com.fadcam.ui.faditor.text;

import android.graphics.Color;

import androidx.annotation.NonNull;

public class TextStyle {

    @NonNull private String id;
    @NonNull private String name;
    @NonNull private String fontFamily = "default";
    private int colorInt = Color.WHITE;
    private int strokeColorInt = Color.TRANSPARENT;
    private float strokeWidthPx;
    private int shadowColorInt = 0xCC000000;
    private float shadowRadiusPx;
    private int glowColorInt = Color.TRANSPARENT;
    private float glowRadiusPx;
    private int backgroundColorInt = Color.TRANSPARENT;
    @NonNull private String entrance = "none";
    @NonNull private String exit = "none";

    public TextStyle(@NonNull String id, @NonNull String name) {
        this.id = id;
        this.name = name;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getName() { return name; }
    @NonNull public String getFontFamily() { return fontFamily; }
    public int getColorInt() { return colorInt; }
    public int getStrokeColorInt() { return strokeColorInt; }
    public float getStrokeWidthPx() { return strokeWidthPx; }
    public int getShadowColorInt() { return shadowColorInt; }
    public float getShadowRadiusPx() { return shadowRadiusPx; }
    public int getGlowColorInt() { return glowColorInt; }
    public float getGlowRadiusPx() { return glowRadiusPx; }
    public int getBackgroundColorInt() { return backgroundColorInt; }
    @NonNull public String getEntrance() { return entrance; }
    @NonNull public String getExit() { return exit; }

    public void setId(@NonNull String id) { this.id = id; }
    public void setName(@NonNull String name) { this.name = name; }
    public void setFontFamily(@NonNull String fontFamily) { this.fontFamily = fontFamily; }
    public void setColorInt(int colorInt) { this.colorInt = colorInt; }
    public void setStrokeColorInt(int strokeColorInt) { this.strokeColorInt = strokeColorInt; }
    public void setStrokeWidthPx(float strokeWidthPx) { this.strokeWidthPx = Math.max(0f, strokeWidthPx); }
    public void setShadowColorInt(int shadowColorInt) { this.shadowColorInt = shadowColorInt; }
    public void setShadowRadiusPx(float shadowRadiusPx) { this.shadowRadiusPx = Math.max(0f, shadowRadiusPx); }
    public void setGlowColorInt(int glowColorInt) { this.glowColorInt = glowColorInt; }
    public void setGlowRadiusPx(float glowRadiusPx) { this.glowRadiusPx = Math.max(0f, glowRadiusPx); }
    public void setBackgroundColorInt(int backgroundColorInt) { this.backgroundColorInt = backgroundColorInt; }
    public void setEntrance(@NonNull String entrance) { this.entrance = entrance; }
    public void setExit(@NonNull String exit) { this.exit = exit; }
}
