package com.fadcam.ui.faditor.effects;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.ColorLut;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.RgbAdjustment;

import java.util.ArrayList;
import java.util.List;

public class EffectStack {

    private float exposure;
    private float contrast;
    private float saturation = 1f;
    private float temperature;
    private float tint;
    private float highlights;
    private float shadows;
    private float fade;
    private float vignette;
    private float grain;
    private boolean lutEnabled;
    @Nullable private String lutId;
    /** LUT blend strength 0..1 (1 = full LUT, 0 = original). Baked into the LUT bitmap. */
    private float lutIntensity = 1f;

    public EffectStack() {}

    public EffectStack(@NonNull EffectStack other) {
        this.exposure = other.exposure;
        this.contrast = other.contrast;
        this.saturation = other.saturation;
        this.temperature = other.temperature;
        this.tint = other.tint;
        this.highlights = other.highlights;
        this.shadows = other.shadows;
        this.fade = other.fade;
        this.vignette = other.vignette;
        this.grain = other.grain;
        this.lutEnabled = other.lutEnabled;
        this.lutId = other.lutId;
        this.lutIntensity = other.lutIntensity;
    }

    /** Copy all values from another stack into this one (in place). */
    public void copyFrom(@NonNull EffectStack other) {
        this.exposure = other.exposure;
        this.contrast = other.contrast;
        this.saturation = other.saturation;
        this.temperature = other.temperature;
        this.tint = other.tint;
        this.highlights = other.highlights;
        this.shadows = other.shadows;
        this.fade = other.fade;
        this.vignette = other.vignette;
        this.grain = other.grain;
        this.lutEnabled = other.lutEnabled;
        this.lutId = other.lutId;
        this.lutIntensity = other.lutIntensity;
    }

    public float getExposure() { return exposure; }
    public void setExposure(float exposure) { this.exposure = clamp(exposure, -1f, 1f); }

    public float getContrast() { return contrast; }
    public void setContrast(float contrast) { this.contrast = clamp(contrast, -1f, 1f); }

    public float getSaturation() { return saturation; }
    public void setSaturation(float saturation) { this.saturation = clamp(saturation, 0f, 2f); }

    public float getTemperature() { return temperature; }
    public void setTemperature(float temperature) { this.temperature = clamp(temperature, -1f, 1f); }

    public float getTint() { return tint; }
    public void setTint(float tint) { this.tint = clamp(tint, -1f, 1f); }

    public float getHighlights() { return highlights; }
    public void setHighlights(float highlights) { this.highlights = clamp(highlights, -1f, 1f); }

    public float getShadows() { return shadows; }
    public void setShadows(float shadows) { this.shadows = clamp(shadows, -1f, 1f); }

    public float getFade() { return fade; }
    public void setFade(float fade) { this.fade = clamp(fade, -1f, 1f); }

    public float getVignette() { return vignette; }
    public void setVignette(float vignette) { this.vignette = clamp(vignette, 0f, 1f); }

    public float getGrain() { return grain; }
    public void setGrain(float grain) { this.grain = clamp(grain, 0f, 1f); }

    public boolean isLutEnabled() { return lutEnabled; }
    public void setLutEnabled(boolean enabled) { this.lutEnabled = enabled; }

    @Nullable public String getLutId() { return lutId; }
    public void setLutId(@Nullable String lutId) { this.lutId = lutId; }

    public float getLutIntensity() { return lutIntensity; }
    public void setLutIntensity(float lutIntensity) { this.lutIntensity = clamp(lutIntensity, 0f, 1f); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EffectStack)) return false;
        EffectStack e = (EffectStack) o;
        return exposure == e.exposure
                && contrast == e.contrast
                && saturation == e.saturation
                && temperature == e.temperature
                && tint == e.tint
                && highlights == e.highlights
                && shadows == e.shadows
                && fade == e.fade
                && vignette == e.vignette
                && grain == e.grain
                && lutEnabled == e.lutEnabled
                && lutIntensity == e.lutIntensity
                && (lutId == null ? e.lutId == null : lutId.equals(e.lutId));
    }

    @Override
    public int hashCode() {
        int r = Float.hashCode(exposure);
        r = 31 * r + Float.hashCode(contrast);
        r = 31 * r + Float.hashCode(saturation);
        r = 31 * r + Float.hashCode(temperature);
        r = 31 * r + Float.hashCode(tint);
        r = 31 * r + Float.hashCode(highlights);
        r = 31 * r + Float.hashCode(shadows);
        r = 31 * r + Float.hashCode(fade);
        r = 31 * r + Float.hashCode(vignette);
        r = 31 * r + Float.hashCode(grain);
        r = 31 * r + (lutEnabled ? 1 : 0);
        r = 31 * r + (lutId == null ? 0 : lutId.hashCode());
        r = 31 * r + Float.hashCode(lutIntensity);
        return r;
    }

    public boolean isActive() {
        return Math.abs(exposure) > 0.001f
                || Math.abs(contrast) > 0.001f
                || Math.abs(saturation - 1f) > 0.001f
                || Math.abs(temperature) > 0.001f
                || Math.abs(tint) > 0.001f
                || Math.abs(highlights) > 0.001f
                || Math.abs(shadows) > 0.001f
                || Math.abs(fade) > 0.001f
                || vignette > 0.001f
                || grain > 0.001f
                || (lutEnabled && lutId != null);
    }

    @NonNull
    public List<Effect> toEffects(@NonNull Context context, boolean hdr) {
        List<Effect> effects = new ArrayList<>();
        if (Math.abs(exposure) > 0.001f) {
            effects.add(new Brightness(exposure));
        }
        if (Math.abs(contrast) > 0.001f) {
            effects.add(new Contrast(contrast));
        }
        if (Math.abs(saturation - 1f) > 0.001f) {
            // HslAdjustment.Builder#adjustSaturation expects a PERCENTAGE (-100..100);
            // HslShaderProgram divides it by 100 internally. Passing the raw fractional
            // delta (e.g. 0.45 for a 1.45x boost) applied only ~0.45% saturation instead
            // of 45%, which is why export looked desaturated vs. the live preview (whose
            // ColorMatrix.setSaturation uses the multiplier directly, with no /100 step).
            effects.add(new HslAdjustment.Builder()
                    .adjustSaturation((saturation - 1f) * 100f)
                    .build());
        }
        if (Math.abs(temperature) > 0.001f || Math.abs(tint) > 0.001f) {
            effects.add(new RgbAdjustment.Builder()
                    .setRedScale(1f + temperature * 0.18f)
                    .setGreenScale(1f + tint * 0.08f)
                    .setBlueScale(1f - temperature * 0.18f)
                    .build());
        }
        if (hasCustomShaderAdjustments()) {
            try {
                effects.add(new ColorGradeShaderProgram(context, this));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create color grade shader", e);
            }
        }
        if (lutEnabled && lutId != null && lutIntensity > 0.001f) {
            ColorLut lut = LutManager.load(context, lutId, lutIntensity);
            if (lut != null) effects.add(lut);
        }
        if (hdr) {
            throw new IllegalStateException("Faditor effects do not support HDR output yet");
        }
        return effects;
    }

    private boolean hasCustomShaderAdjustments() {
        return Math.abs(highlights) > 0.001f
                || Math.abs(shadows) > 0.001f
                || Math.abs(fade) > 0.001f
                || vignette > 0.001f
                || grain > 0.001f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
