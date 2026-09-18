package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;
import com.fadcam.ui.faditor.Studio;

/**
 * The image-sequence import dialog (SPEC_IMAGE_SEQUENCE §3b).
 *
 * <p><b>It asks ONE question: how long.</b> The spec is explicit that this must NOT ask
 * "frames or slideshow?" — those differ only in per-frame duration, and asking it as a MODE
 * <i>"creates something the user has to understand and can regret"</i>. So there are two presets
 * that are just starting points, three entry fields that are three VIEWS of one number, and
 * nothing here is binding: everything is editable afterwards in the drawer and by dragging the
 * object on the timeline.</p>
 *
 * <p>The three fields — <b>frame rate</b>, <b>duration per image</b>, <b>total duration</b> — all
 * write the same model (§2: "Frames-per-second, seconds-per-frame and percentages are three VIEWS
 * of one array. Do not build them as three modes"). Editing any one updates the others live, and
 * all three round-trip through {@link SequenceTiming}, so the number shown cannot drift from the
 * number the resolver will use.</p>
 *
 * <p>Built programmatically rather than from a layout: {@code res/} and {@code strings.xml} are
 * another agent's live files under the working protocol in {@code FaditorEditorActivity}.</p>
 */
public final class SequenceImportDialog {

    /** What the user settled on. Only {@code fps} is really needed — the rest is derived. */
    public interface OnImport {
        /**
         * @param fps the cadence to store on the sheet; total duration and per-frame duration are
         *            views of it (see {@link SequenceTiming#fpsForTotalMs}).
         */
        void onImport(float fps);
    }

    private SequenceImportDialog() {}

    /**
     * Show the offer.
     *
     * @param frameCount how many images were found — shown prominently, because §3a's whole
     *                   conservatism rests on the user being able to say "that is not a sequence,
     *                   that is my camera roll" and decline.
     * @param headline   the detector's own wording, or a plain count for a hand-picked selection
     */
    public static void show(@NonNull Context ctx, @NonNull String headline, int frameCount,
                            @NonNull OnImport cb) {
        final int n = Math.max(1, frameCount);
        int pad = dp(ctx, 20);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(ctx, 8), pad, 0);

        TextView count = new TextView(ctx);
        count.setText(headline);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        count.setPadding(0, 0, 0, dp(ctx, 4));
        root.addView(count);

        TextView hint = new TextView(ctx);
        hint.setText("How long should it run? You can change all of this later.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        hint.setTextColor(SpriteTheme.DIM);
        hint.setPadding(0, 0, 0, dp(ctx, 14));
        root.addView(hint);

        // ── The three views of one number ───────────────────────────────────
        final EditText fpsField = field(ctx);
        final EditText perField = field(ctx);
        final EditText totalField = field(ctx);
        root.addView(labelled(ctx, "Frame rate (fps)", fpsField));
        root.addView(labelled(ctx, "Each image", perField));
        root.addView(labelled(ctx, "Total length", totalField));

        // A re-entrancy latch, not a cosmetic guard: each field writes the other two, so without
        // it the first keystroke starts an endless mutual update and the caret jumps while the
        // user is still typing.
        final boolean[] syncing = {false};
        final float[] fps = {SequenceTiming.PRESET_ANIMATION_FPS};

        final Runnable render = () -> {
            syncing[0] = true;
            try {
                long total = SequenceTiming.totalMsForFps(null, n, fps[0]);
                fpsField.setText(trim(fps[0]));
                perField.setText(DurationParser.formatMs(Math.round(total / (float) n)));
                totalField.setText(DurationParser.formatMs(total));
            } finally {
                syncing[0] = false;
            }
        };

        fpsField.addTextChangedListener(new Simple(() -> {
            if (syncing[0]) return;
            float v = parseFloat(fpsField.getText().toString());
            if (Float.isNaN(v) || v <= 0f) return;
            fps[0] = SequenceTiming.clampFps(v);
            syncing[0] = true;
            try {
                long total = SequenceTiming.totalMsForFps(null, n, fps[0]);
                perField.setText(DurationParser.formatMs(Math.round(total / (float) n)));
                totalField.setText(DurationParser.formatMs(total));
            } finally { syncing[0] = false; }
        }));

        perField.addTextChangedListener(new Simple(() -> {
            if (syncing[0]) return;
            long ms = DurationParser.parseMs(perField.getText().toString(), fps[0]);
            if (ms <= 0) return;
            fps[0] = SequenceTiming.fpsForPerFrameMs(null, n, ms);
            syncing[0] = true;
            try {
                fpsField.setText(trim(fps[0]));
                totalField.setText(DurationParser.formatMs(
                        SequenceTiming.totalMsForFps(null, n, fps[0])));
            } finally { syncing[0] = false; }
        }));

        totalField.addTextChangedListener(new Simple(() -> {
            if (syncing[0]) return;
            long ms = DurationParser.parseMs(totalField.getText().toString(), fps[0]);
            if (ms <= 0) return;
            fps[0] = SequenceTiming.fpsForTotalMs(null, n, ms);
            syncing[0] = true;
            try {
                fpsField.setText(trim(fps[0]));
                perField.setText(DurationParser.formatMs(Math.round(ms / (float) n)));
            } finally { syncing[0] = false; }
        }));

        // ── The two presets — starting points, not modes ────────────────────
        LinearLayout presets = new LinearLayout(ctx);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4));
        presets.addView(chip(ctx, "Animation  ~12 fps", () -> {
            fps[0] = SequenceTiming.PRESET_ANIMATION_FPS;
            render.run();
        }));
        presets.addView(chip(ctx, "Slideshow  ~3s each", () -> {
            fps[0] = SequenceTiming.fpsForPerFrameMs(
                    null, n, SequenceTiming.PRESET_SLIDESHOW_MS_PER_FRAME);
            render.run();
        }));
        root.addView(presets);

        render.run();

        new MaterialAlertDialogBuilder(ctx)
                .setTitle("Import image sequence")
                .setView(root)
                .setPositiveButton("Import", (d, w) -> cb.onImport(SequenceTiming.clampFps(fps[0])))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── small builders ──────────────────────────────────────────────────────

    private static EditText field(@NonNull Context ctx) {
        EditText e = new EditText(ctx);
        e.setSingleLine(true);
        // TYPE_CLASS_TEXT, deliberately, not a number keypad: §3c's whole point is that "2m30s"
        // and "1/6 min" are things people type, and a numeric IME cannot express either.
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        return e;
    }

    private static View labelled(@NonNull Context ctx, @NonNull String label,
                                 @NonNull EditText f) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(SpriteTheme.DIM);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 130),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(t);
        f.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(f);
        return row;
    }

    private static View chip(@NonNull Context ctx, @NonNull String label,
                             @NonNull Runnable onTap) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        t.setBackgroundColor(Studio.alpha(Studio.INK, 0x22));
        t.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(ctx, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private static String trim(float v) {
        String s = String.format(Locale.US, "%.2f", v);
        // "12" reads better than "12.00" in a field the user is about to edit.
        if (s.endsWith(".00")) return s.substring(0, s.length() - 3);
        if (s.endsWith("0")) return s.substring(0, s.length() - 1);
        return s;
    }

    private static float parseFloat(@Nullable String s) {
        try {
            return Float.parseFloat(s == null ? "" : s.trim().replace(',', '.'));
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    private static int dp(@NonNull Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** TextWatcher with only afterTextChanged, to keep the listeners above readable. */
    private static final class Simple implements TextWatcher {
        private final Runnable r;
        Simple(Runnable r) { this.r = r; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) { r.run(); }
    }

    /**
     * Convenience for the caller that has a detector candidate: the headline and count together.
     */
    public static void showFor(@NonNull Context ctx,
                               @NonNull SequenceDetector.Candidate candidate,
                               @NonNull OnImport cb) {
        show(ctx, candidate.describe(), candidate.count(), cb);
    }

    /** Headline for a hand-picked multi-selection, where nothing was "found". */
    @NonNull
    public static String headlineForPicked(@NonNull List<String> uris) {
        return String.format(Locale.US, "%d images selected", uris.size());
    }
}
