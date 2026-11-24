package com.fadcam.ui.faditor.undo;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;

import java.util.Arrays;

/**
 * The whole-box look of a text object: what the text drawer changes when no words are selected
 * (colour, font, alignment, wrap and Fit, case, bold/italic/underline, outline, glow, shadow and
 * plate). Taken when the drawer opens and again when it closes, so the visit is ONE undo step
 * with its span edits (owner rule: one press, one step). Before 11-24 none of these changes had
 * an undo step: the drawer's step held the styled words only.
 *
 * <p>Animated values (a keyed outline width, say) live in the object's keyframes and are not
 * part of this: the keyframe diamonds record their own steps. Pure Java, tested on a JVM.</p>
 */
public final class TextBoxLook {

    private final int color, stroke, shadow, glow, plate;
    private final float strokeW, shadowR, shadowAngle, shadowDist, glowR, wrapEm;
    private final boolean fit, bold, italic, underline;
    @NonNull private final String font, align, textCase;

    private TextBoxLook(@NonNull TextOverlayItem o) {
        color = o.getColorInt();
        stroke = o.getStrokeColorInt();
        strokeW = o.getStrokeWidthPx();
        shadow = o.getShadowColorInt();
        shadowR = o.getShadowRadiusPx();
        shadowAngle = o.getShadowAngleDeg();
        shadowDist = o.getShadowDistancePx();
        glow = o.getGlowColorInt();
        glowR = o.getGlowRadiusPx();
        plate = o.getBackgroundColorInt();
        wrapEm = o.getWrapEm();
        fit = o.fitWidthSetting();
        bold = o.isBold();
        italic = o.isItalic();
        underline = o.isUnderline();
        font = o.getFontFamily();
        align = o.getTextAlign();
        textCase = o.getTextCase();
    }

    @NonNull
    public static TextBoxLook of(@NonNull TextOverlayItem o) {
        return new TextBoxLook(o);
    }

    /** Put this look back on {@code o}. */
    public void applyTo(@NonNull TextOverlayItem o) {
        o.setColorInt(color);
        o.setStrokeColorInt(stroke);
        o.setStrokeWidthPx(strokeW);
        o.setShadowColorInt(shadow);
        o.setShadowRadiusPx(shadowR);
        o.setShadowAngleDeg(shadowAngle);
        o.setShadowDistancePx(shadowDist);
        o.setGlowColorInt(glow);
        o.setGlowRadiusPx(glowR);
        o.setBackgroundColorInt(plate);
        o.setWrapEm(wrapEm);
        o.setFitWidth(fit);
        o.setBold(bold);
        o.setItalic(italic);
        o.setUnderline(underline);
        o.setFontFamily(font);
        o.setTextAlign(align);
        o.setTextCase(textCase);
    }

    public boolean sameAs(@NonNull TextBoxLook b) {
        return color == b.color && stroke == b.stroke && shadow == b.shadow && glow == b.glow
                && plate == b.plate && fit == b.fit && bold == b.bold && italic == b.italic
                && underline == b.underline && font.equals(b.font) && align.equals(b.align)
                && textCase.equals(b.textCase)
                && Arrays.equals(new float[]{strokeW, shadowR, shadowAngle, shadowDist, glowR, wrapEm},
                        new float[]{b.strokeW, b.shadowR, b.shadowAngle, b.shadowDist, b.glowR, b.wrapEm});
    }
}
