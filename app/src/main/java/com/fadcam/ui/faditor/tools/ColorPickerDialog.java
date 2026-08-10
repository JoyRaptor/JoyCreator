package com.fadcam.ui.faditor.tools;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * ONE colour picker for the whole app (JoyRaptor's compact bottom drawer, 2026-08-08).
 *
 * <p>A bottom drawer, so it never covers the preview: the hue ring + saturation/brightness
 * triangle sits top-left with the copyable hex field under it, the H/S/B sliders with tappable
 * numeric read-outs sit to its right, three honeycombed rows of fixed swatches follow (the
 * middle row nudged right so the circles interlock), and the bottom row holds eight RECENT
 * swatches shared by every caller in the app.</p>
 *
 * <p><b>Recents are app-wide on purpose.</b> A palette that only remembers what you did in one
 * drawer is a palette you have to rebuild in every other one; the whole value of "the colour I
 * just used" is that it follows you to the next thing you colour.</p>
 *
 * <p><b>Supports NONE as a first-class answer</b> ({@code null}), because several of the things
 * this picks for — outline, glow, background, plate — are legitimately absent, and forcing a
 * transparent black to mean "off" is how a colour picker starts lying about state.</p>
 */
public final class ColorPickerDialog {

    /** Result: a colour, or {@code null} for "none". */
    public interface OnPicked { void onPicked(@Nullable Integer color); }

    /**
     * Fired on EVERY change — slider drag, wheel drag, swatch tap — so the caller can paint the
     * live object while the dialog is still open. "I should not have to click the button Set to
     * see how it looks" (JoyRaptor, 2026-08-08): Set only closes the dialog, it does not apply
     * anything that live preview has not already applied.
     */
    public interface OnLive { void onLive(@Nullable Integer color); }

    private static final OnLive NO_LIVE = c -> { };

    private static final String PREFS = "faditor_color_picker";
    private static final String KEY_RECENTS = "recents";
    private static final int RECENT_SLOTS = 8;

    /** Useful fixed colours: greys at both ends, then a spread that covers the common asks.
     *  16 entries — exactly two full rows of 8 (JoyRaptor, 2026-08-08: "let's have it be 8 8 8"). */
    private static final int[] SWATCHES = {
            0xFF000000, 0xFF404040, 0xFF808080, 0xFFC0C0C0, 0xFFFFFFFF,
            0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
            0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60,
            0xFF6D4C41, 0xFF00BFA5, 0xFFFF7043,
    };

    /** Honeycomb stagger for the middle row: half the 25dp swatch pitch keeps circles interlocked. */
    private static final int ROW_OFFSET_DP = 13;

    /** Number of common swatches that fit the top (un-offset) row; the rest go in the middle. */
    private static final int TOP_ROW_SWATCHES = 8;

    /** Swatch circle size + right gap. 22+3=25dp pitch so 8 fit the narrow sliders column even on
     *  a 360dp phone (adversarial review M2 — the old 26+6=32dp pitch clipped the left swatches). */
    private static final int SWATCH_DP = 22;
    private static final int SWATCH_GAP_DP = 3;

    private ColorPickerDialog() {}

    /** Convenience overload for a caller with nothing to live-preview against. */
    public static void show(@NonNull Context ctx, @NonNull String title,
                            @Nullable Integer initial, boolean allowNone,
                            @NonNull OnPicked onPicked) {
        show(ctx, title, initial, allowNone, NO_LIVE, onPicked);
    }

    /**
     * @param initial the current colour, or {@code null} when the thing has none.
     * @param allowNone whether to offer the "none" swatch at all. Text colour, for instance,
     *                  cannot be none — invisible text is a bug, not a style.
     * @param onLive called on every change so the caller can paint the live object while the
     *               dialog is open — see {@link OnLive}.
     * @param onPicked called ONCE, when Set is pressed. Cancel instead replays {@code initial}
     *                 through {@code onLive} and calls neither.
     */
    public static void show(@NonNull Context ctx, @NonNull String title,
                            @Nullable Integer initial, boolean allowNone,
                            @NonNull OnLive onLive, @NonNull OnPicked onPicked) {
        show(ctx, title, initial, allowNone, onLive, onPicked, null, null);
    }

    /**
     * {@code show} plus a slide-aside contract for a bottom drawer that would otherwise sit
     * behind this bottom sheet (W2-4, §3.13): {@code onShown} runs once the sheet is up and
     * {@code onDismissed} runs on EVERY way out — Set, Cancel, back, scrim tap, swipe-away — so
     * a drawer hosting the swatch can slide horizontally out of the way while the sheet is up
     * and slide back the moment it closes. Either may be {@code null}.
     */
    public static void show(@NonNull Context ctx, @NonNull String title,
                            @Nullable Integer initial, boolean allowNone,
                            @NonNull OnLive onLive, @NonNull OnPicked onPicked,
                            @Nullable Runnable onShown, @Nullable Runnable onDismissed) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * d);

        final float[] hsb = new float[3];
        Color.colorToHSV(initial != null ? initial : 0xFFFFFFFF, hsb);
        final int[] alpha = {initial != null ? Color.alpha(initial) : 255};
        final boolean[] isNone = {initial == null};

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, Math.round(6 * d));
        root.setClipChildren(false);

        // The caller's label ("Glow", "Stop color", …) — the only way the drawer says what it is
        // picking for.
        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextColor(0xFFE8E8E8);
        titleView.setTextSize(14f);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, 0, 0, Math.round(6 * d));
        root.addView(titleView);

        // ── top: wheel (hex under it) on the left, H/S/B sliders on the right ───────────
        // The sliders' column is SHORTER than the wheel+hex column, so the swatch rows and the
        // Cancel/Set actions live UNDER the sliders inside the same right column — the picker
        // ends flush with the wheel instead of stacking another full-width block below it
        // (JoyRaptor, 2026-08-08).
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout leftCol = new LinearLayout(ctx);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        ColorWheelView wheel = new ColorWheelView(ctx);
        leftCol.addView(wheel, new LinearLayout.LayoutParams(
                Math.round(112 * d), Math.round(112 * d)));

        LinearLayout rightCol = new LinearLayout(ctx);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.END);
        // Clear air between the wheel's edge and the slider labels ("the corner of the B is
        // ~3-4px from touching the wheel" — JoyRaptor, 2026-08-08).
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.leftMargin = Math.round(10 * d);
        rightLp.rightMargin = Math.round(2 * d);

        LinearLayout sliders = new LinearLayout(ctx);
        sliders.setOrientation(LinearLayout.VERTICAL);
        sliders.setGravity(Gravity.CENTER_VERTICAL);
        rightCol.addView(sliders, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        top.addView(leftCol);
        top.addView(rightCol, rightLp);
        root.addView(top);

        final View preview = new View(ctx);
        final TextView hex = new TextView(ctx);
        final SeekBar[] bars = new SeekBar[3];
        final TextView[] nums = new TextView[3];

        // Declared before the rows so each row's listener can push the whole state at once.
        final Runnable[] syncFromHsb = new Runnable[1];

        String[] labels = {"H", "S", "B"};
        int[] maxes = {360, 100, 100};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView lab = new TextView(ctx);
            lab.setText(labels[i]);
            lab.setTextColor(0xFFBBBBBB);
            lab.setTextSize(13f);
            lab.setWidth(Math.round(18 * d));
            row.addView(lab);

            SeekBar bar = new SeekBar(ctx);
            bar.setMax(maxes[i]);
            bars[i] = bar;
            row.addView(bar, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // LOOKS LIKE TEXT, IS A CONTROL. Tapping opens a numeric entry, because a slider
            // cannot express "exactly 210" on a 360-wide track and the value is right there.
            TextView num = new TextView(ctx);
            num.setTextColor(0xFFE8E8E8);
            num.setTextSize(12.5f);
            num.setWidth(Math.round(34 * d));
            num.setGravity(Gravity.END);
            nums[i] = num;
            row.addView(num);
            sliders.addView(row);

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (!fromUser) return;
                    hsb[idx] = idx == 0 ? p : p / 100f;
                    isNone[0] = false;
                    syncFromHsb[0].run();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            num.setOnClickListener(v -> promptForNumber(ctx, labels[idx], maxes[idx],
                    Math.round(idx == 0 ? hsb[0] : hsb[idx] * 100f), val -> {
                        hsb[idx] = idx == 0 ? val : val / 100f;
                        isNone[0] = false;
                        syncFromHsb[0].run();
                    }));
        }

        // ── hex row: swatch · copy · value, UNDER the wheel ─────────────────────────────
        LinearLayout hexRow = new LinearLayout(ctx);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        hexRow.setPadding(0, Math.round(6 * d), 0, 0);

        FrameLayout previewBox = new FrameLayout(ctx);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                Math.round(26 * d), Math.round(26 * d)));
        hexRow.addView(previewBox);

        TextView copy = new TextView(ctx);
        copy.setText("⧉");
        copy.setTextSize(17f);
        copy.setTextColor(0xFFBBBBBB);
        copy.setPadding(Math.round(10 * d), 0, Math.round(6 * d), 0);
        hexRow.addView(copy);

        hex.setTextColor(0xFFE8E8E8);
        hex.setTextSize(14f);
        hex.setTypeface(android.graphics.Typeface.MONOSPACE);
        // Selectable so it can be copied by hand as well as by the icon — the icon is the fast
        // path, not the only one.
        hex.setTextIsSelectable(true);
        hexRow.addView(hex);
        leftCol.addView(hexRow);

        copy.setOnClickListener(v -> {
            ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("color", hex.getText().toString()));
                Toast.makeText(ctx, hex.getText().toString() + " copied",
                        Toast.LENGTH_SHORT).show();
            }
        });

        // ── fixed swatches (+ none), honeycomb rows ─────────────────────────────────────
        // Right-justified under the HSB sliders (the sliders' column is shorter than the wheel),
        // so the whole picker ends flush with the wheel column instead of running another
        // full-width band beneath it. Row 2's leftMargin offset rides INSIDE the right padding,
        // so the honeycomb interlock survives the right-justification.
        LinearLayout fixedRow1 = swatchRow(ctx);
        LinearLayout fixedRow2 = swatchRow(ctx);
        rightCol.addView(fixedRow1);
        rightCol.addView(fixedRow2);

        // The middle row's CONTAINER is nudged right by half a swatch + gap (never the
        // individual swatches), so the circles nest with the rows above and below instead of
        // marching in a rigid grid — the honeycomb look the owner asked for.
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.leftMargin = Math.round(ROW_OFFSET_DP * d);
        fixedRow2.setLayoutParams(row2Lp);

        for (int i = 0; i < SWATCHES.length; i++) {
            // M4 (adversarial review): the "none" must OCCUPY one of the 16 fixed slots — the
            // 16th — not be appended as a 9th cell of row 2 (which made the rows 8/9/8 and
            // widened row 2 past the column).
            if (allowNone && i == SWATCHES.length - 1) {
                fixedRow2.addView(noneSwatch(ctx, d, () -> {
                    isNone[0] = true;
                    syncFromHsb[0].run();
                }));
                continue;
            }
            final int c = SWATCHES[i];
            View sw = swatch(ctx, d, c, () -> {
                Color.colorToHSV(c, hsb);
                alpha[0] = 255;
                isNone[0] = false;
                syncFromHsb[0].run();
            });
            (i < TOP_ROW_SWATCHES ? fixedRow1 : fixedRow2).addView(sw);
        }

        // ── recents ─────────────────────────────────────────────────────────────────────
        // No label: the row of (mostly empty) circles below the fixed colours is self-evident.
        // The entire last row is history — nothing else ever lands there.
        LinearLayout recentRow = swatchRow(ctx);
        rightCol.addView(recentRow);
        List<Integer> recents = loadRecents(ctx);
        for (int i = 0; i < RECENT_SLOTS; i++) {
            if (i < recents.size()) {
                final int c = recents.get(i);
                recentRow.addView(swatch(ctx, d, c, () -> {
                    Color.colorToHSV(c, hsb);
                    alpha[0] = Color.alpha(c);
                    isNone[0] = false;
                    syncFromHsb[0].run();
                }));
            } else {
                // EMPTY slots are drawn, not omitted: the row keeps its shape as it fills, so
                // the swatch you used last does not move under your thumb every session.
                recentRow.addView(emptySwatch(ctx, d));
            }
        }

        // ── the one place that pushes state into every control ──────────────────────────
        syncFromHsb[0] = () -> {
            int rgb = Color.HSVToColor(alpha[0], hsb);
            bars[0].setProgress(Math.round(hsb[0]));
            bars[1].setProgress(Math.round(hsb[1] * 100f));
            bars[2].setProgress(Math.round(hsb[2] * 100f));
            nums[0].setText(String.valueOf(Math.round(hsb[0])));
            nums[1].setText(String.valueOf(Math.round(hsb[1] * 100f)));
            nums[2].setText(String.valueOf(Math.round(hsb[2] * 100f)));
            wheel.setHsb(hsb[0], hsb[1], hsb[2]);
            hex.setText(isNone[0] ? "none" : String.format("#%06X", rgb & 0xFFFFFF));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(4 * d);
            bg.setColor(isNone[0] ? 0x00000000 : rgb);
            bg.setStroke(Math.round(1 * d), 0xFF777777);
            preview.setBackground(bg);
            // LIVE. "I should not have to click Set to see how it looks" — every control in
            // this dialog funnels through syncFromHsb, so firing onLive here is the one place
            // that makes the whole dialog live without every slider/swatch remembering to.
            onLive.onLive(isNone[0] ? null : rgb);
        };
        wheel.setListener((h, s, b) -> {
            hsb[0] = h; hsb[1] = s; hsb[2] = b;
            isNone[0] = false;
            syncFromHsb[0].run();
        });
        syncFromHsb[0].run();

        // ── actions: Set closes, Cancel reverts ─────────────────────────────────────────
        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, Math.round(8 * d), 0, 0);

        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText(android.R.string.cancel);
        cancelBtn.setTextColor(0xFFBBBBBB);
        cancelBtn.setTextSize(14f);
        cancelBtn.setPadding(Math.round(14 * d), Math.round(6 * d),
                Math.round(14 * d), Math.round(6 * d));
        actions.addView(cancelBtn);

        TextView setBtn = new TextView(ctx);
        setBtn.setText(ctx.getString(com.fadcam.R.string.faditor_color_set));
        setBtn.setTextColor(0xFF4FC3F7);
        setBtn.setTextSize(14f);
        setBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setBtn.setPadding(Math.round(14 * d), Math.round(6 * d),
                Math.round(14 * d), Math.round(6 * d));
        actions.addView(setBtn);

        rightCol.addView(actions);

        // Bottom drawer (never covers the preview), wearing the app's own bottom-sheet theme so
        // the background gradient is the same one every other drawer in the app uses.
        BottomSheetDialog dlg = new BottomSheetDialog(ctx);
        dlg.setContentView(root);
        // Set does NOT apply anything — live preview already has. It only stops asking, and it
        // is the one path that writes to recents (a value you merely previewed and then
        // cancelled should not crowd out the ones you actually chose).
        setBtn.setOnClickListener(v -> {
            if (isNone[0]) { onPicked.onPicked(null); dlg.dismiss(); return; }
            int rgb = Color.HSVToColor(alpha[0], hsb);
            pushRecent(ctx, rgb);
            onPicked.onPicked(rgb);
            dlg.dismiss();
        });
        // Cancel REVERTS. The live object has been tracking every drag, so undoing that means
        // replaying the ORIGINAL value through the same onLive path — anything else leaves the
        // object showing whatever the last drag happened to land on. Back, scrim tap and
        // swiping the sheet away all land here.
        cancelBtn.setOnClickListener(v -> {
            onLive.onLive(initial);
            dlg.dismiss();
        });
        dlg.setOnCancelListener(ignored -> onLive.onLive(initial));
        // W2-4 (§3.13): fire the slide-aside/back contract on show and on EVERY dismissal — Set,
        // Cancel, back, scrim tap and swipe-away all funnel through onDismiss, so the hosting
        // drawer cannot be left stranded off-screen.
        dlg.setOnShowListener(ignored -> { if (onShown != null) onShown.run(); });
        dlg.setOnDismissListener(ignored -> { if (onDismissed != null) onDismissed.run(); });
        dlg.show();
    }

    // ── Recents storage ─────────────────────────────────────────────────────────────────

    @NonNull
    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public static List<Integer> loadRecents(@NonNull Context ctx) {
        List<Integer> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_RECENTS, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            try { out.add((int) Long.parseLong(part.trim(), 16)); } catch (Exception ignored) { }
            if (out.size() >= RECENT_SLOTS) break;
        }
        return out;
    }

    /** Most recent first, de-duplicated, capped at the number of slots the row draws. */
    public static void pushRecent(@NonNull Context ctx, @ColorInt int color) {
        List<Integer> cur = loadRecents(ctx);
        cur.remove(Integer.valueOf(color));
        cur.add(0, color);
        while (cur.size() > RECENT_SLOTS) cur.remove(cur.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cur.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Integer.toHexString(cur.get(i)));
        }
        prefs(ctx).edit().putString(KEY_RECENTS, sb.toString()).apply();
    }

    // ── Small views ─────────────────────────────────────────────────────────────────────

    @NonNull
    private static LinearLayout swatchRow(@NonNull Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        // Tight: the honeycomb offset does the nesting, not vertical margins.
        r.setPadding(0, Math.round(1 * r.getResources().getDisplayMetrics().density),
                0, Math.round(1 * r.getResources().getDisplayMetrics().density));
        return r;
    }

    @NonNull
    private static View swatch(@NonNull Context ctx, float d, @ColorInt int color,
                               @NonNull Runnable onTap) {
        View v = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(SWATCH_DP * d), Math.round(SWATCH_DP * d));
        lp.rightMargin = Math.round(SWATCH_GAP_DP * d);
        v.setLayoutParams(lp);
        v.setOnClickListener(x -> onTap.run());
        return v;
    }

    @NonNull
    private static View emptySwatch(@NonNull Context ctx, float d) {
        View v = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x00000000);
        bg.setStroke(Math.round(1 * d), 0x33FFFFFF);
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(SWATCH_DP * d), Math.round(SWATCH_DP * d));
        lp.rightMargin = Math.round(SWATCH_GAP_DP * d);
        v.setLayoutParams(lp);
        return v;
    }

    /** The circle with a diagonal through it: unmistakably "no colour at all". */
    @NonNull
    private static View noneSwatch(@NonNull Context ctx, float d, @NonNull Runnable onTap) {
        View v = new View(ctx) {
            private final android.graphics.Paint p =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(@NonNull android.graphics.Canvas c) {
                float r = Math.min(getWidth(), getHeight()) / 2f - 1.5f * d;
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(1.6f * d);
                p.setColor(0xFFCCCCCC);
                c.drawCircle(cx, cy, r, p);
                double a = Math.toRadians(45);
                float dx = (float) Math.cos(a) * r, dy = (float) Math.sin(a) * r;
                c.drawLine(cx - dx, cy + dy, cx + dx, cy - dy, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(SWATCH_DP * d), Math.round(SWATCH_DP * d));
        lp.rightMargin = Math.round(SWATCH_GAP_DP * d);
        v.setLayoutParams(lp);
        v.setOnClickListener(x -> onTap.run());
        return v;
    }

    private interface OnNumber { void onNumber(int value); }

    private static void promptForNumber(@NonNull Context ctx, @NonNull String label, int max,
                                        int current, @NonNull OnNumber cb) {
        EditText in = new EditText(ctx);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setText(String.valueOf(current));
        in.setSelectAllOnFocus(true);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(label)
                .setView(in)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    try {
                        int v = Integer.parseInt(in.getText().toString().trim());
                        cb.onNumber(Math.max(0, Math.min(max, v)));
                    } catch (NumberFormatException ignored) {
                        // Typed nonsense keeps the old value rather than snapping to zero,
                        // which would silently destroy a colour the user was refining.
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
