package com.fadcam.ui.faditor.effects;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

public class ColorGradePanel extends LinearLayout {

    public interface Callback {
        void onColorGradeChanged(@NonNull EffectStack stack);
    }

    private final EffectStack stack = new EffectStack();
    private Callback callback;

    public ColorGradePanel(@NonNull Context context) {
        super(context);
        setOrientation(VERTICAL);
        rebuild();
    }

    public void setStack(@NonNull EffectStack stack) {
        this.stack.setExposure(stack.getExposure());
        this.stack.setContrast(stack.getContrast());
        this.stack.setSaturation(stack.getSaturation());
        this.stack.setTemperature(stack.getTemperature());
        this.stack.setTint(stack.getTint());
        this.stack.setHighlights(stack.getHighlights());
        this.stack.setShadows(stack.getShadows());
        this.stack.setFade(stack.getFade());
        this.stack.setVignette(stack.getVignette());
        this.stack.setGrain(stack.getGrain());
        this.stack.setLutEnabled(stack.isLutEnabled());
        this.stack.setLutId(stack.getLutId());
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @NonNull
    public EffectStack getStack() {
        return new EffectStack(stack);
    }

    private void rebuild() {
        addSlider("Exposure", -100, 100, 0, value -> stack.setExposure(value / 100f));
        addSlider("Contrast", -100, 100, 0, value -> stack.setContrast(value / 100f));
        addSlider("Saturation", 0, 200, 100, value -> stack.setSaturation(value / 100f));
        addSlider("Temperature", -100, 100, 0, value -> stack.setTemperature(value / 100f));
        addSlider("Tint", -100, 100, 0, value -> stack.setTint(value / 100f));
        addSlider("Highlights", -100, 100, 0, value -> stack.setHighlights(value / 100f));
        addSlider("Shadows", -100, 100, 0, value -> stack.setShadows(value / 100f));
        addSlider("Fade", -100, 100, 0, value -> stack.setFade(value / 100f));
        addSlider("Vignette", 0, 100, 0, value -> stack.setVignette(value / 100f));
        addSlider("Grain", 0, 100, 0, value -> stack.setGrain(value / 100f));
    }

    private void addSlider(@NonNull String label, int min, int max, int initial,
                           @NonNull SeekValueListener listener) {
        TextView text = new TextView(getContext());
        text.setText(label);
        text.setTextColor(0xFFFFFFFF);
        addView(text);
        SeekBar seekBar = new SeekBar(getContext());
        seekBar.setMax(max - min);
        seekBar.setProgress(initial - min);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) listener.onValue(min + progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (callback != null) callback.onColorGradeChanged(stack);
            }
        });
        addView(seekBar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private interface SeekValueListener {
        void onValue(int value);
    }
}
