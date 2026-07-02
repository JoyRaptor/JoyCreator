package com.fadcam.ui.faditor.effects;

public class ColorGradeParams {

    public float exposure;
    public float contrast;
    public float saturation = 1f;
    public float temperature;
    public float tint;
    public float highlights;
    public float shadows;
    public float fade;
    public float vignette;
    public float grain;

    public ColorGradeParams() {}

    public ColorGradeParams(EffectStack stack) {
        exposure = stack.getExposure();
        contrast = stack.getContrast();
        saturation = stack.getSaturation();
        temperature = stack.getTemperature();
        tint = stack.getTint();
        highlights = stack.getHighlights();
        shadows = stack.getShadows();
        fade = stack.getFade();
        vignette = stack.getVignette();
        grain = stack.getGrain();
    }
}
