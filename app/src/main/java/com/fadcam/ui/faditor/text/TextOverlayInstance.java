package com.fadcam.ui.faditor.text;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;

public class TextOverlayInstance {

    @NonNull private final TextOverlayItem item;
    @NonNull private TextStyle style;

    public TextOverlayInstance(@NonNull TextOverlayItem item, @NonNull TextStyle style) {
        this.item = item;
        this.style = style;
        apply(item, style);
    }

    @NonNull public TextOverlayItem getItem() { return item; }
    @NonNull public TextStyle getStyle() { return style; }

    public void setStyle(@NonNull TextStyle style) {
        this.style = style;
        apply(item, style);
    }

    public static void apply(@NonNull TextOverlayItem item, @NonNull TextStyle style) {
        item.setFontFamily(style.getFontFamily());
        item.setColorInt(style.getColorInt());
        item.setStrokeColorInt(style.getStrokeColorInt());
        item.setStrokeWidthPx(style.getStrokeWidthPx());
        item.setShadowColorInt(style.getShadowColorInt());
        item.setShadowRadiusPx(style.getShadowRadiusPx());
        item.setGlowColorInt(style.getGlowColorInt());
        item.setGlowRadiusPx(style.getGlowRadiusPx());
        item.setBackgroundColorInt(style.getBackgroundColorInt());
    }
}
