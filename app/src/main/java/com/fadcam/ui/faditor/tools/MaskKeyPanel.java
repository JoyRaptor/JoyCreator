package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.model.ChromaKey;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.MaskAnimator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonParser;

/**
 * §3a — the *Mask &amp; Key* panel: the authoring UI for {@link CompositingSpec}.
 *
 * <p><b>Why this is its own class.</b> The mask half of this panel used to be ~120 lines inside
 * {@code FaditorEditorActivity}, which is 27,000 lines and still growing — the pattern this
 * project keeps paying for. Adding the key half there would have made it worse, so the whole
 * panel moved out instead: the activity now hands over a {@link Host} and gets smaller, matching
 * {@code ObjectMenuSheet} / {@code FilterBottomSheet}. Nothing here knows about the editor
 * beyond that interface, which is also what makes the revert/undo contract legible in one
 * screenful instead of interleaved with unrelated dialog code.</p>
 *
 * <p><b>The live-write contract, kept verbatim from the mask dialog.</b> Every control writes
 * straight through to the model so the preview is the truth being tuned — and therefore ANY
 * dismissal that is not an explicit button reverts. The panel seeds a default box on open, so
 * without that rule merely opening the panel and pressing BACK punched a permanent hole in the
 * user's PiP. Read {@link #revert} before changing how this closes.</p>
 *
 * <p><b>The key is tuned against a keyed preview, never blind.</b> That was the binding
 * condition on shipping this UI at all: the export has keyed for months, but a tolerance slider
 * judged against an unkeyed preview is guesswork that looks like control. The live tier
 * ({@code ChromaKeyTextureView}) renders the same shader the export runs, so what this panel
 * shows is what the file will contain.</p>
 */
public final class MaskKeyPanel {

    /** Everything the panel needs from the editor, and nothing more. */
    public interface Host {
        /** Repaint the preview + timeline after a live write. */
        void onCompositingChanged();
        /** Persist immediately (revert paths — autosave may already have written the edit). */
        void saveNow();
        /** Debounced save (commit paths). */
        void scheduleSave();
        /** Record ONE undo step for the whole panel session. */
        void recordCompositingUndo(@NonNull String label, @NonNull Runnable redo,
                                   @NonNull Runnable undo);
        /**
         * Arm the eyedropper: the next tap on the PiP samples its RAW (un-keyed) colour and
         * calls back with 0xRRGGBB, or null if it could not be read. Implementations must
         * disarm after one tap — a dropper that stays armed eats the next drag.
         */
        void pickColorFromPreview(@NonNull ColorPicked cb);

        /**
         * The playhead, in ABSOLUTE timeline ms — the base mask keyframes are stored in,
         * deliberately the same one a PiP's {@code overlayTransform} uses so one clip does not
         * carry two conventions.
         */
        long playheadMs();
    }

    public interface ColorPicked { void onPicked(@Nullable Integer rgb); }

    /** Swatches offered before the dropper — the three keys people actually shoot against. */
    private static final int[] SWATCHES = {0x35F6BF, 0x050508, 0x000000, 0xF4F4F5};

    private final Activity activity;
    private final Clip clip;
    private final Host host;
    private final CompositingSpec spec;
    private final boolean hadSpec;
    private final String before;
    private final boolean[] committed = {false};

    @Nullable private View swatchRow;
    @Nullable private TextView keyColorLabel;
    /**
     * Held so the eyedropper can step out of the way. The panel is a MODAL dialog: it covers
     * the preview and eats every touch, so an armed dropper could never receive the tap it is
     * waiting for. Hiding (not dismissing) keeps every slider position and the revert contract
     * intact — {@code hide()} does not fire {@code setOnDismissListener}, so stepping aside to
     * sample a colour must not be mistaken for cancelling the panel.
     */
    @Nullable private androidx.appcompat.app.AlertDialog dialog;

    public MaskKeyPanel(@NonNull Activity activity, @NonNull Clip clip, @NonNull Host host) {
        this.activity = activity;
        this.clip = clip;
        this.host = host;
        this.spec = clip.getCompositing() != null ? clip.getCompositing() : new CompositingSpec();
        this.hadSpec = clip.getCompositing() != null;
        this.before = spec.toJson().toString();
    }

    public void show() {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        if (spec.masks.isEmpty()) spec.masks.add(new CompositingSpec.MaskShape());
        final CompositingSpec.MaskShape shape = spec.masks.get(0);

        final Runnable apply = () -> {
            clip.setCompositing(spec.isEmpty() ? null : spec);
            host.onCompositingChanged();
        };
        apply.run();

        // ── SHAPE ────────────────────────────────────────────────────────────────────────
        addHeader(root, R.string.faditor_mask_section_shape, density);
        slider(root, R.string.faditor_mask_x, 100, Math.round(shape.cx * 100),
                v -> { shape.cx = v / 100f; apply.run(); });
        slider(root, R.string.faditor_mask_y, 100, Math.round(shape.cy * 100),
                v -> { shape.cy = v / 100f; apply.run(); });
        slider(root, R.string.faditor_mask_w, 100, Math.round(shape.w * 100),
                v -> { shape.w = Math.max(0.02f, v / 100f); apply.run(); });
        slider(root, R.string.faditor_mask_h, 100, Math.round(shape.h * 100),
                v -> { shape.h = Math.max(0.02f, v / 100f); apply.run(); });
        slider(root, R.string.faditor_mask_round, 100, Math.round(shape.corner * 100),
                v -> { shape.corner = v / 100f; apply.run(); });
        // SPEC F / SPEC J — the mask angle is a ROTATION control, and a rotation SLIDER is the
        // SPEC A winding-collapse bug: this was the last one anywhere (the five object rows all
        // render the RotationDialView since SPEC F). A 0..360 SeekBar could not show winding,
        // could not author a turn past 360, and clamped a stored angle outside its window
        // before the finger even moved — and mask animation ("Move with the object" rotation
        // deltas, MaskAnimator) can absolutely put one there. The dial is the same control the
        // object rows use: raw degrees, countable rings, tap to type.
        LinearLayout rotRow = new LinearLayout(activity);
        rotRow.setOrientation(LinearLayout.HORIZONTAL);
        rotRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView rotLabel = new TextView(activity);
        rotLabel.setTextColor(Studio.INK_FAINT);
        rotLabel.setTextSize(12);
        rotLabel.setText(activity.getString(R.string.faditor_mask_rotate)
                + "  ·  " + Math.round(shape.rotationDeg));
        rotRow.addView(rotLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final RotationDialView rotDial = new RotationDialView(activity);
        rotDial.setDegrees(shape.rotationDeg);
        rotRow.addView(rotDial, new LinearLayout.LayoutParams(
                (int) (40 * density), (int) (40 * density)));
        rotDial.setListener(new RotationDialView.Listener() {
            @Override public void onDragStart() { }
            @Override public void onDragDelta() {
                shape.rotationDeg = rotDial.getDegrees();
                rotLabel.setText(activity.getString(R.string.faditor_mask_rotate)
                        + "  ·  " + Math.round(shape.rotationDeg));
                apply.run();
            }
            @Override public void onDragEnd() { }
            @Override public void onTap() {
                android.widget.EditText input = new android.widget.EditText(activity);
                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                input.setText(String.valueOf(Math.round(shape.rotationDeg)));
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(activity.getString(R.string.faditor_mask_rotate))
                        .setView(input)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            try {
                                float v = Float.parseFloat(input.getText().toString().trim());
                                dialSet(v);
                            } catch (NumberFormatException ignored) { }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
            private void dialSet(float v) {
                rotDial.setDegrees(v);
                shape.rotationDeg = v;
                rotLabel.setText(activity.getString(R.string.faditor_mask_rotate)
                        + "  ·  " + Math.round(v));
                apply.run();
            }
        });
        root.addView(rotRow);
        slider(root, R.string.faditor_mask_soften, 100, Math.round(spec.maskFeather * 100),
                v -> { spec.maskFeather = v / 100f; apply.run(); });

        CheckBox invert = new CheckBox(activity);
        invert.setText(R.string.faditor_mask_only_inside);
        invert.setTextColor(Studio.INK_DIM);
        invert.setChecked(spec.invertMasks);
        invert.setOnCheckedChangeListener((b, on) -> { spec.invertMasks = on; apply.run(); });
        root.addView(invert);

        // ── MOVE + ANIMATE (the writers for MaskAnimator) ───────────────────────────────
        // Inline literals: strings.xml is another agent's live file under the working protocol
        // noted in FaditorEditorActivity.

        CheckBox link = new CheckBox(activity);
        link.setText("Move with the object");
        link.setTextColor(Studio.INK_DIM);
        link.setChecked(shape.linkedToObject);
        link.setOnCheckedChangeListener((b, on) -> {
            shape.linkedToObject = on;
            if (on) {
                // CAPTURE the object's pose now. "Relative to the object" has no origin
                // without it, and the mask would jump the first time the object is anywhere
                // but its default pose.
                com.fadcam.ui.faditor.keyframe.KeyframeSet kf = clip.getOverlayTransform();
                long t = host.playheadMs();
                shape.linkBaseX = poseAt(kf, com.fadcam.ui.faditor.keyframe.KeyframeSet.X, t, 0.5f);
                shape.linkBaseY = poseAt(kf, com.fadcam.ui.faditor.keyframe.KeyframeSet.Y, t, 0.5f);
                shape.linkBaseScale = Math.max(0.001f, poseAt(kf,
                        com.fadcam.ui.faditor.keyframe.KeyframeSet.SCALE, t, 1f));
                shape.linkBaseRotDeg = poseAt(kf,
                        com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION, t, 0f);
            }
            apply.run();
        });
        root.addView(link);

        TextView linkHint = new TextView(activity);
        linkHint.setText("Off: the mask stays put and the object moves under it. "
                + "On: the mask travels with the object.");
        linkHint.setTextColor(Studio.INK_FAINT);
        linkHint.setTextSize(11.5f);
        linkHint.setPadding((int) (8 * density), 0, 0, (int) (6 * density));
        root.addView(linkHint);

        LinearLayout keyRow = new LinearLayout(activity);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView keyState = new TextView(activity);
        keyState.setTextColor(Studio.INK_FAINT);
        keyState.setTextSize(11.5f);
        final Runnable refreshKeyState = () ->
                keyState.setText(spec.hasMaskKeys() ? "  animated" : "  not animated");

        TextView addKey = chipButton("◆ Key at playhead", density);
        addKey.setOnClickListener(v -> {
            long t = host.playheadMs();
            if (spec.maskKeys == null) {
                spec.maskKeys = new com.fadcam.ui.faditor.keyframe.KeyframeSet();
            }
            com.fadcam.ui.faditor.keyframe.Easing ease =
                    com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
            // All seven at once, matching how every other object in this app arms keyframes:
            // a half-armed mask animates some parameters and snaps the rest, which reads as
            // the shape tearing rather than as an incomplete keyframe.
            spec.maskKeys.getOrCreate(MaskAnimator.CX).put(t, shape.cx, ease);
            spec.maskKeys.getOrCreate(MaskAnimator.CY).put(t, shape.cy, ease);
            spec.maskKeys.getOrCreate(MaskAnimator.W).put(t, shape.w, ease);
            spec.maskKeys.getOrCreate(MaskAnimator.H).put(t, shape.h, ease);
            spec.maskKeys.getOrCreate(MaskAnimator.CORNER).put(t, shape.corner, ease);
            spec.maskKeys.getOrCreate(MaskAnimator.ROTATION).put(t, shape.rotationDeg, ease);
            spec.maskKeys.getOrCreate(MaskAnimator.FEATHER).put(t, spec.maskFeather, ease);
            refreshKeyState.run();
            apply.run();
            android.widget.Toast.makeText(activity,
                    "Mask keyed at " + (t / 1000f) + "s",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        TextView clearKeys = chipButton("Clear", density);
        clearKeys.setOnClickListener(v -> {
            spec.maskKeys = null;
            refreshKeyState.run();
            apply.run();
        });
        keyRow.addView(addKey);
        keyRow.addView(clearKeys);
        keyRow.addView(keyState);
        root.addView(keyRow);
        refreshKeyState.run();

        // ── KEY ──────────────────────────────────────────────────────────────────────────
        addHeader(root, R.string.faditor_key_section, density);

        CheckBox keyOn = new CheckBox(activity);
        keyOn.setText(R.string.faditor_key_enable);
        keyOn.setTextColor(Studio.INK_DIM);
        keyOn.setChecked(spec.keyEnabled);
        root.addView(keyOn);

        // The key controls are built once and shown/hidden as a block: rebuilding them on
        // toggle would reset every slider the user had already set, which reads as the panel
        // throwing away their work each time they compare keyed against unkeyed.
        LinearLayout keyBody = new LinearLayout(activity);
        keyBody.setOrientation(LinearLayout.VERTICAL);
        root.addView(keyBody);

        keyColorLabel = new TextView(activity);
        keyColorLabel.setTextColor(Studio.INK_FAINT);
        keyColorLabel.setTextSize(12);
        keyBody.addView(keyColorLabel);
        refreshKeyColorLabel();

        swatchRow = buildSwatchRow(density, apply);
        keyBody.addView(swatchRow);

        slider(keyBody, R.string.faditor_key_tolerance, 100, Math.round(spec.keyTolerance * 100),
                v -> { spec.keyTolerance = v / 100f; apply.run(); });
        slider(keyBody, R.string.faditor_key_softness, 100, Math.round(spec.keyFuzziness * 100),
                v -> { spec.keyFuzziness = v / 100f; apply.run(); });
        // Spill/choke is signed, so the bar is 0..200 with 100 meaning zero — a SeekBar cannot
        // start negative and a second control for "which direction" would be worse.
        slider(keyBody, R.string.faditor_key_spill, 200, Math.round(spec.keyOffset * 100) + 100,
                v -> { spec.keyOffset = (v - 100) / 100f; apply.run(); });

        keyBody.setVisibility(spec.keyEnabled ? View.VISIBLE : View.GONE);
        keyOn.setOnCheckedChangeListener((b, on) -> {
            spec.keyEnabled = on;
            keyBody.setVisibility(on ? View.VISIBLE : View.GONE);
            apply.run();
        });

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.faditor_mask_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (d, w) -> commit())
                .setNeutralButton(R.string.faditor_mask_remove, (d, w) -> removeAll())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // ⚠ THE IMPORTANT LINE — see the class note. Dismissal of ANY kind (BACK, tap-outside,
        // rotation) reverts unless a button committed, because the controls write live and the
        // panel seeds a default box merely by opening.
        dlg.setOnDismissListener(d -> { if (!committed[0]) revert(); });
        dialog = dlg;
        dlg.show();
    }

    // ── Key colour ───────────────────────────────────────────────────────────────────────

    @NonNull
    private View buildSwatchRow(float density, @NonNull Runnable apply) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int sz = (int) (34 * density);
        int gap = (int) (8 * density);

        for (int rgb : SWATCHES) {
            View sw = new View(activity);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Studio.GROUND | rgb);
            // A stroke so the black and white swatches are visible on a dark dialog at all.
            bg.setStroke(Math.max(1, (int) (1.5f * density)), Studio.INK_FAINT);
            sw.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.rightMargin = gap;
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> {
                spec.keyColor = rgb;
                refreshKeyColorLabel();
                apply.run();
            });
            row.addView(sw);
        }

        TextView dropper = new TextView(activity);
        dropper.setText(R.string.faditor_key_eyedropper);
        dropper.setTextColor(Studio.ROOM_AVATAR_DEEP);
        dropper.setTextSize(14);
        dropper.setPadding(gap, gap / 2, gap, gap / 2);
        dropper.setOnClickListener(v -> {
            // Step aside so the tap can actually reach the preview, then come back either way.
            // "Either way" is the important half: an early return on failure that forgot to
            // re-show would strand the user with their panel gone and their edits pending.
            if (dialog != null) dialog.hide();
            final boolean[] resolved = {false};
            // SAFETY NET, not the mechanism. The dropper resolves on the next tap ON THE
            // PREVIEW; a tap anywhere else — the timeline, a system gesture — never reaches it,
            // and the panel would stay hidden with live edits pending and no way back. This
            // guarantees the panel always comes home. It is deliberately long enough not to
            // race a user who is lining up a careful tap.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (resolved[0]) return;
                resolved[0] = true;
                if (dialog != null && !activity.isFinishing()) dialog.show();
            }, 15000L);
            host.pickColorFromPreview(rgb -> {
                if (resolved[0]) return;   // the net already restored us; do not double-show
                resolved[0] = true;
                if (rgb != null) {
                    spec.keyColor = rgb;
                    refreshKeyColorLabel();
                    clip.setCompositing(spec.isEmpty() ? null : spec);
                    host.onCompositingChanged();
                } else {
                    // Say so rather than silently doing nothing — a dropper that appears to
                    // work and does not is how a user concludes the feature is broken.
                    android.widget.Toast.makeText(activity,
                            R.string.faditor_key_eyedropper_failed,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                if (dialog != null && !activity.isFinishing()) dialog.show();
            });
        });
        row.addView(dropper);
        return row;
    }

    private void refreshKeyColorLabel() {
        if (keyColorLabel == null) return;
        keyColorLabel.setText(activity.getString(R.string.faditor_key_color)
                + "  ·  " + String.format("#%06X", spec.keyColor & 0xF4F4F5));
    }

    // ── Commit / revert ──────────────────────────────────────────────────────────────────

    private void commit() {
        committed[0] = true;
        // DEEP COPY, not the live object. `spec` IS clip.getCompositing() when one existed, and
        // a later panel mutates it in place — so an undo action holding a reference would, on
        // redo, resurrect whatever the NEXT session did, including values explicitly cancelled.
        final CompositingSpec after = spec.isEmpty() ? null
                : CompositingSpec.fromJson(
                        JsonParser.parseString(spec.toJson().toString()).getAsJsonObject());
        clip.setCompositing(after);
        host.recordCompositingUndo(activity.getString(R.string.faditor_mask_title),
                () -> { clip.setCompositing(after); host.onCompositingChanged(); },
                this::revert);
        host.scheduleSave();
    }

    private void removeAll() {
        committed[0] = true;
        clip.setCompositing(null);
        host.onCompositingChanged();
        host.recordCompositingUndo(activity.getString(R.string.faditor_mask_remove),
                () -> { clip.setCompositing(null); host.onCompositingChanged(); },
                this::revert);
        host.scheduleSave();
    }

    /**
     * Restore the EXACT prior state, including "there was no spec at all". {@code fromJson} is
     * {@code @NonNull}, so a naive restore would leave a non-null empty spec behind and quietly
     * break the "compositing != null means the user configured something" reading.
     */
    private void revert() {
        if (!hadSpec) {
            clip.setCompositing(null);
        } else {
            clip.setCompositing(CompositingSpec.fromJson(
                    JsonParser.parseString(before).getAsJsonObject()));
        }
        host.onCompositingChanged();
        // Live writes schedule autosaves while the panel is open, so a memory-only revert
        // leaves the abandoned edit on disk.
        host.saveNow();
    }

    // ── Small builders ───────────────────────────────────────────────────────────────────

    /** One of the object's transform tracks at {@code t}, or {@code fallback} when unanimated. */
    private static float poseAt(@Nullable com.fadcam.ui.faditor.keyframe.KeyframeSet kf,
                                @NonNull String property, long t, float fallback) {
        return kf == null ? fallback : kf.valueAt(property, t, fallback);
    }

    /** Small tappable chip, matching the inline-view style the rest of this panel uses. */
    private TextView chipButton(@NonNull String label, float density) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextColor(Studio.INK);
        t.setTextSize(12.5f);
        int px = (int) (10 * density), py = (int) (6 * density);
        t.setPadding(px, py, px, py);
        t.setBackgroundColor(Studio.alpha(Studio.INK, 0x22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (8 * density);
        t.setLayoutParams(lp);
        return t;
    }

    private void addHeader(@NonNull LinearLayout parent, int labelRes, float density) {
        TextView t = new TextView(activity);
        t.setText(labelRes);
        t.setTextColor(Studio.INK);
        t.setTextSize(13);
        t.setPadding(0, (int) (12 * density), 0, (int) (2 * density));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        parent.addView(t);
    }

    private void slider(@NonNull LinearLayout parent, int labelRes, int max, int initial,
                        @NonNull java.util.function.Consumer<Integer> onChange) {
        TextView label = new TextView(activity);
        label.setTextColor(Studio.INK_FAINT);
        label.setTextSize(12);
        label.setText(activity.getString(labelRes) + "  ·  " + initial);
        parent.addView(label);

        SeekBar bar = new SeekBar(activity);
        bar.setMax(max);
        bar.setProgress(Math.max(0, Math.min(max, initial)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                label.setText(activity.getString(labelRes) + "  ·  " + p);
                onChange.accept(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        parent.addView(bar);
    }
}
