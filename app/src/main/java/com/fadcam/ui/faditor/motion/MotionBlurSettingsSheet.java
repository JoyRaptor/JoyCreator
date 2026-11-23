package com.fadcam.ui.faditor.motion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.StudioDialog;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Edits one item's transform shutter as a single undo step. */
public final class MotionBlurSettingsSheet {
    public record State(boolean projectEnabled, boolean enabled, MotionShutter.Controls controls) {}

    private static final MotionShutter.Preset[] PRESETS = MotionShutter.Preset.values();
    private static final MotionShutter.Timing[] TIMINGS = MotionShutter.Timing.values();

    private final Context context;
    private final LinearLayout rows;
    private final AppCompatCheckBox projectEnabled, enabled;
    private final Spinner preset;
    private final Spinner timing;
    private final SeekBar angle;
    private final SeekBar phase;
    private final LinearLayout phaseRow;
    private final SeekBar mix;
    private final ShutterStrip strip;

    public static void show(Context context, State initial, @StringRes int coverage,
                            Consumer<State> onApply, Consumer<State> onInspect) {
        var builder = StudioDialog.builder(context);
        var sheet = new MotionBlurSettingsSheet(builder.getContext(), initial, coverage);
        var scroll = new ScrollView(builder.getContext());
        scroll.setFillViewport(true);
        scroll.addView(sheet.rows);
        var dialog = builder.setTitle(R.string.studio_motion_blur)
                .setView(scroll)
                .setNegativeButton(R.string.studio_ui_cancel, null)
                .setPositiveButton(R.string.studio_motion_apply, (ignored, button) ->
                        onApply.accept(sheet.state()))
                .setNeutralButton(R.string.studio_motion_inspect, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
                .setOnClickListener(view -> onInspect.accept(sheet.state())));
        dialog.show();
    }

    private MotionBlurSettingsSheet(Context context, State initial, @StringRes int coverage) {
        this.context = context;
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        int side = SheetKit.dp(context, 20);
        rows.setPadding(side, 0, side, SheetKit.dp(context, 8));

        projectEnabled = new AppCompatCheckBox(context);
        projectEnabled.setText(R.string.studio_motion_enable_project);
        projectEnabled.setChecked(initial.projectEnabled());
        rows.addView(projectEnabled);

        enabled = new AppCompatCheckBox(context);
        enabled.setText(R.string.studio_motion_enable_item);
        enabled.setChecked(initial.enabled());
        rows.addView(enabled);
        var coverageNote = new TextView(context);
        coverageNote.setText(coverage);
        coverageNote.setPadding(0, 0, 0, SheetKit.dp(context, 12));
        rows.addView(coverageNote);
        preset = selection(R.string.studio_motion_preset, R.array.studio_motion_presets);
        timing = selection(R.string.studio_motion_timing, R.array.studio_motion_timings);
        angle = slider(rows, R.string.studio_motion_angle_value, 0, 720, initial.controls().angle());
        phaseRow = new LinearLayout(context);
        phaseRow.setOrientation(LinearLayout.VERTICAL);
        rows.addView(phaseRow);
        phase = slider(phaseRow, R.string.studio_motion_phase_value, -720, 720,
                initial.controls().customPhase());
        strip = new ShutterStrip(context);
        rows.addView(strip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, SheetKit.dp(context, 30)));
        mix = slider(rows, R.string.studio_motion_mix_value, 0, 100, initial.controls().mixPercent());

        timing.setSelection(initial.controls().timing().ordinal());
        syncSelections();
        onSelected(timing, index -> syncSelections());
        onSelected(preset, index -> {
            if (index >= PRESETS.length || index == presetIndex()) return;
            var selected = PRESETS[index];
            timing.setSelection(selected.timing.ordinal());
            angle.setProgress(selected.angle);
            syncSelections();
        });
    }

    private State state() {
        return new State(projectEnabled.isChecked(), enabled.isChecked(), controls());
    }

    private MotionShutter.Controls controls() {
        return new MotionShutter.Controls(angle.getProgress(),
                TIMINGS[Math.max(0, timing.getSelectedItemPosition())], phase.getProgress() - 720,
                mix.getProgress());
    }

    private int presetIndex() {
        int selectedTiming = timing.getSelectedItemPosition();
        for (var entry : PRESETS) {
            if (entry.angle == angle.getProgress() && entry.timing.ordinal() == selectedTiming) {
                return entry.ordinal();
            }
        }
        return PRESETS.length;
    }

    private void syncSelections() {
        phaseRow.setVisibility(timing.getSelectedItemPosition() == MotionShutter.Timing.CUSTOM.ordinal()
                ? View.VISIBLE : View.GONE);
        preset.setSelection(presetIndex());
        strip.setControls(controls());
    }

    private Spinner selection(@StringRes int labelId, int entriesId) {
        var label = new TextView(context);
        label.setText(labelId);
        rows.addView(label);
        var spinner = new Spinner(context);
        spinner.setId(View.generateViewId());
        label.setLabelFor(spinner.getId());
        ArrayAdapter<CharSequence> choices = ArrayAdapter.createFromResource(context, entriesId,
                android.R.layout.simple_spinner_item);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(choices);
        spinner.setMinimumHeight(SheetKit.dp(context, 48));
        rows.addView(spinner);
        return spinner;
    }

    private SeekBar slider(LinearLayout parent, @StringRes int labelId, int min, int max, int initial) {
        var label = new TextView(context);
        label.setText(context.getString(labelId, initial));
        parent.addView(label);
        var slider = new SeekBar(context);
        slider.setId(View.generateViewId());
        label.setLabelFor(slider.getId());
        slider.setMax(max - min);
        slider.setProgress(initial - min);
        slider.setMinimumHeight(SheetKit.dp(context, 48));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(context.getString(labelId, progress + min));
                if (fromUser) syncSelections();
            }
        });
        parent.addView(slider);
        return slider;
    }

    /** The exposure and playhead in shutter degrees. The sliders expose the same values to TalkBack. */
    private static final class ShutterStrip extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackColor, exposureColor;
        private MotionShutter.Controls controls = MotionShutter.Controls.NATURAL;

        ShutterStrip(Context context) {
            super(context);
            trackColor = ContextCompat.getColor(context, R.color.s_line);
            exposureColor = ContextCompat.getColor(context, R.color.s_ink);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setControls(MotionShutter.Controls value) {
            controls = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float padding = SheetKit.dp(getContext(), 12);
            float width = Math.max(0, getWidth() - 2 * padding);
            float centerY = getHeight() / 2f;
            double open = controls.phase();
            double close = open + controls.angle();
            double halfRange = Math.max(360.0, Math.max(Math.abs(open), Math.abs(close)));
            float zeroX = padding + width / 2f;
            float openX = zeroX + (float) (width * open / (2 * halfRange));
            float closeX = zeroX + (float) (width * close / (2 * halfRange));
            paint.setColor(trackColor);
            paint.setStrokeWidth(SheetKit.dp(getContext(), 2));
            canvas.drawLine(padding, centerY, padding + width, centerY, paint);
            paint.setColor(exposureColor);
            paint.setStrokeWidth(SheetKit.dp(getContext(), 6));
            if (closeX > openX) canvas.drawLine(openX, centerY, closeX, centerY, paint);
            paint.setStrokeWidth(SheetKit.dp(getContext(), 1));
            canvas.drawLine(zeroX, padding / 2f, zeroX, getHeight() - padding / 2f, paint);
        }
    }

    private static void onSelected(Spinner spinner, IntConsumer change) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> view) {}
            @Override public void onItemSelected(AdapterView<?> view, View row, int index, long id) {
                change.accept(index);
            }
        });
    }
}
