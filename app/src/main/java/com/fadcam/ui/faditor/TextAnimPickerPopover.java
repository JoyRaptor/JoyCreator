package com.fadcam.ui.faditor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transcript.CaptionAnimator;

/**
 * SPEC_TEXT_ANIMATION step 2: the text-animation preset grid and granularity selector — the
 * reporter's "KineMaster keyframe-curve picker, but presets". Deliberately the same shape as
 * {@link EasePickerPopover}: a compact popover of rounded-square tiles, each drawing its own
 * thumbnail FROM THE EVALUATOR ({@link CaptionAnimator#presetTransform}) rather than from a
 * hand-drawn asset, so a tile cannot advertise a motion the renderers do not produce.
 *
 * <h3>Two things this surface deliberately does</h3>
 * <ul>
 *   <li><b>Only implemented presets appear.</b> Five of the ten are declared on
 *       {@link CaptionAnimator.Preset} but cannot be expressed as a {@code Transform} yet, and
 *       each returns identity rather than an approximation. Offering them would be offering a
 *       tile that does nothing.</li>
 *   <li><b>Granularity sits in the same popover as the preset.</b> It is orthogonal to the preset
 *       — one preset at four granularities is four quite different effects — but it is never
 *       useful on its own, so splitting them across two surfaces would just cost a tap.</li>
 * </ul>
 *
 * <p>The TIMING is not here. It is the tape carets, for every preset at every granularity, which
 * is the reporter's model and the reason there is no duration control on this popover.</p>
 */
public final class TextAnimPickerPopover {

    public interface OnPick {
        void onPreset(@NonNull CaptionAnimator.Preset p);
        void onGranularity(@NonNull CaptionAnimator.Granularity g);
    }

    private static final int COLS = 3;

    // Same surface and ink as EasePickerPopover, and for the same reason: this floats over the
    // canvas — it opens from the text drawer's motion button — so it is "over video" and takes
    // the drawer's dark glass and the drawer's ink.
    private static final int TILE_BG = com.fadcam.ui.faditor.tools.TextOverlayDrawer.Kit.CTL;
    /** The animated "A"s. Drawer DIM — the tile's subject, readable on the glass. */
    private static final int GLYPH = Studio.DRAWER_DIM;
    /** Selected preset / granularity — "State colour always a RING — cyan selected" (record 06). */
    private static final int ACCENT = Studio.ARMED;
    private static final int RING_FILL = Studio.alpha(Studio.ARMED, 0x1F);
    private static final int TXT_DIM = Studio.DRAWER_LABEL;

    /**
     * How many glyphs each tile animates. Three is enough to show a STAGGER — which is half of
     * what distinguishes these presets — without turning a 60dp tile into a smear.
     */
    private static final int TILE_UNITS = 3;

    /**
     * The synthetic phrase every tile animates against: a 1.8s line made of a 0.9s entrance and a
     * 0.9s exit back to back, looping. These are the same arguments a renderer passes
     * {@link CaptionAnimator#unitProgress}, so a tile shows entrance-sweep → exit-sweep exactly as
     * the real thing does, including the stagger between units.
     *
     * <p><b>Why these tiles animate now, when the first version deliberately froze three
     * samples.</b> The frozen version was measured on 2026-07-29 and it did not work: TYPEWRITER
     * and FADE differed by a mean of <b>1.08/255</b> over their glyph area, 2.9% of pixels by more
     * than 8 — against 26.38 / 17.7% for a pair that plainly reads differently on the same
     * instrument. The reason is structural, not cosmetic: at progress {0, 0.5, 1} TYPEWRITER draws
     * A(1.0) A(1.0) and FADE draws A(0.5) A(1.0), so the ENTIRE difference between two presets was
     * one glyph's alpha. A still frame cannot show the shape of an eased curve; only motion can.
     * The label was doing all the work.</p>
     *
     * <p>The original argument for freezing — that a tile must not drift out of sync with playback
     * — does not apply, because a tile was never synced to playback in the first place. And the
     * rule that actually matters is <i>strengthened</i> here, not weakened: the tile now drives
     * {@code unitProgress} as well as {@code presetTransform}, so it renders more of the real
     * evaluator than the frozen version did, and still cannot advertise a motion the renderers do
     * not produce.</p>
     *
     * <p><b>Why the span is exactly twice the zone.</b> A first cut used a 2.4s span with 0.9s
     * zones, which leaves 0.6s of HOLD — and during the hold every unit sits at progress 1, so
     * every preset draws exactly the same three opaque glyphs. Measured on 2026-07-29, 5 of 16
     * sampled frames came back at an identical 51.24 mean for all six tiles: a quarter of the
     * loop was spending itself showing nothing that tells the presets apart. Span = 2 × zone
     * removes the hold entirely, so the tile is always inside a sweep.</p>
     */
    private static final long TILE_ZONE_MS = 900L;
    private static final long TILE_SPAN_MS = TILE_ZONE_MS * 2;

    private TextAnimPickerPopover() {}

    /**
     * @param anchor      the motion button the popover hangs off of.
     * @param current     the clip's current preset (green-ringed).
     * @param currentGran the clip's current granularity (green-ringed).
     */
    public static void show(@NonNull View anchor,
                            @Nullable CaptionAnimator.Preset current,
                            @Nullable CaptionAnimator.Granularity currentGran,
                            @NonNull OnPick onPick) {
        show(anchor, current, currentGran, null, onPick);
    }

    /**
     * @param allowedGrans granularities this OBJECT can actually honour, or null for all of
     *                     them. Same rule as {@code Preset.implemented} above: this picker never
     *                     offers a control that provably will not do what it says. A row with one
     *                     chip is deliberately still drawn, so the setting is visible and its
     *                     value is not a mystery.
     *                     <p><b>Both call sites pass null today.</b> This javadoc used to say "A
     *                     TEXT BOX passes {@code {BLOCK}}: its preview is a {@code TextView},
     *                     which cannot transform individual characters" — that wall was
     *                     demolished when both surfaces moved onto the one shared
     *                     {@code TextBoxRenderer}, and the restriction was removed rather than
     *                     merely relaxed. The parameter is kept because the RULE it enforces is
     *                     still the right one; it simply has no restricted caller at the moment.
     */
    public static void show(@NonNull View anchor,
                            @Nullable CaptionAnimator.Preset current,
                            @Nullable CaptionAnimator.Granularity currentGran,
                            @Nullable java.util.Set<CaptionAnimator.Granularity> allowedGrans,
                            @NonNull OnPick onPick) {
        Context ctx = anchor.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        // Concentric corners: tile radius 10 + the 4dp cell margin around it = 14.
        container.setBackground(com.fadcam.ui.faditor.tools.TextOverlayDrawer.Kit.surface(ctx, 14));
        container.setElevation(16 * d);
        int pad = (int) (10 * d);
        container.setPadding(pad, pad, pad, pad);

        final PopupWindow pop = new PopupWindow(container,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        pop.setElevation(16 * d);
        // The window's own fade is replaced by the record's arrival in popShow.
        pop.setAnimationStyle(0);

        container.addView(caption(ctx, d, ctx.getString(com.fadcam.R.string.faditor_lc_motion_title)));

        int tile = (int) (60 * d);
        int cell = (int) (4 * d);
        LinearLayout gridRow = null;
        int shown = 0;
        for (CaptionAnimator.Preset p : CaptionAnimator.Preset.values()) {
            if (!p.implemented) continue; // see class doc — an unimplemented tile does nothing
            if (shown % COLS == 0) {
                gridRow = new LinearLayout(ctx);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(gridRow);
            }
            shown++;
            final PresetTileView view = new PresetTileView(ctx, p, p == current);
            // The tile draws its name but a drawn name is invisible to TalkBack and to a hover.
            com.fadcam.ui.faditor.tools.TextOverlayDrawer.Kit.describe(view, CaptionAnimator.presetLabel(p));
            com.fadcam.ui.faditor.tools.TextOverlayDrawer.Kit.pressable(view);
            view.setOnClickListener(v -> {
                view.setSelectedRing(true);
                onPick.onPreset(p);
                anchor.postDelayed(pop::dismiss, 150);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tile);
            lp.setMargins(cell, cell, cell, cell);
            gridRow.addView(view, lp);
        }

        // ── Granularity: what counts as one animating unit ──────────────
        container.addView(caption(ctx, d, ctx.getString(com.fadcam.R.string.faditor_lc_motion_by)));
        LinearLayout granRow = new LinearLayout(ctx);
        granRow.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(granRow);
        CaptionAnimator.Granularity[] allGrans = CaptionAnimator.Granularity.values();
        // In Granularity's declaration order, which is what indexes this array.
        String[] allGranNames = {
                ctx.getString(com.fadcam.R.string.faditor_lc_gran_letter),
                ctx.getString(com.fadcam.R.string.faditor_lc_gran_word),
                ctx.getString(com.fadcam.R.string.faditor_lc_gran_sentence),
                ctx.getString(com.fadcam.R.string.faditor_lc_gran_block)};
        java.util.List<CaptionAnimator.Granularity> granList = new java.util.ArrayList<>();
        java.util.List<String> granNameList = new java.util.ArrayList<>();
        for (int i = 0; i < allGrans.length; i++) {
            if (allowedGrans != null && !allowedGrans.contains(allGrans[i])) continue;
            granList.add(allGrans[i]);
            granNameList.add(allGranNames[i]);
        }
        CaptionAnimator.Granularity[] grans =
                granList.toArray(new CaptionAnimator.Granularity[0]);
        String[] granNames = granNameList.toArray(new String[0]);
        final TextView[] granChips = new TextView[grans.length];
        for (int i = 0; i < grans.length; i++) {
            final CaptionAnimator.Granularity g = grans[i];
            final int idx = i;
            // Record 06 `.dchip`, from the one chip builder. It was a hand-built 8dp-radius box
            // whose UNSELECTED state was half-alpha dim grey on grey — the same unreadable
            // off-state the text drawer's toggles were reported for.
            TextView chip = com.fadcam.ui.faditor.tools.TextOverlayDrawer.Kit.chip(ctx, granNames[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins((int) (3 * d), (int) (4 * d), (int) (3 * d), (int) (2 * d));
            granRow.addView(chip, lp);
            granChips[i] = chip;
            styleGranChip(chip, d, g == currentGran);
            // Picking a granularity does NOT dismiss. It is orthogonal to the preset, so the
            // common move is to choose one and then immediately try it against a different
            // preset; closing the popover would cost a reopen every time. Picking a PRESET does
            // dismiss, because that is the primary action.
            chip.setOnClickListener(v -> {
                onPick.onGranularity(g);
                for (int j = 0; j < granChips.length; j++) {
                    styleGranChip(granChips[j], d, j == idx);
                }
            });
        }

        popShow(pop, container, anchor, d);
    }

    /**
     * Selected/unselected look for a granularity chip, so the two states cannot drift apart —
     * and now the same look every drawer chip has: a cyan ring when selected, the control fill
     * and drawer-dim ink when not. Nothing is faded with alpha any more.
     */
    private static void styleGranChip(@NonNull TextView chip, float d, boolean selected) {
        com.fadcam.ui.faditor.tools.TextOverlayDrawer.Kit.setChipOn(chip, selected);
    }

    /** A section heading inside the popover — record 06 `.dsec`, from the one builder. */
    @NonNull
    private static TextView caption(@NonNull Context ctx, float d, @NonNull String text) {
        TextView tv = com.fadcam.ui.faditor.tools.TextOverlayDrawer.Kit.sectionLabel(ctx, text);
        tv.setPadding((int) (6 * d), (int) (6 * d), 0, (int) (2 * d));
        return tv;
    }

    private static void popShow(@NonNull PopupWindow pop, @NonNull View container,
                                @NonNull View anchor, float d) {
        com.fadcam.ui.faditor.tools.TextOverlayDrawer.Kit.popIn(container);
        // Prefer above the anchor so the popover never covers the drawer rows below it; fall
        // back to below only when there isn't room. Same rule as EasePickerPopover.
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int h = container.getMeasuredHeight();
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int gap = (int) (6 * d);
        if (loc[1] - h - gap > 0) {
            pop.showAsDropDown(anchor, 0, -(h + anchor.getHeight() + gap), Gravity.START);
        } else {
            pop.showAsDropDown(anchor, 0, gap, Gravity.START);
        }
    }

    /**
     * One preset tile: {@link #TILE_UNITS} "A"s looping through the synthetic phrase described on
     * {@link #TILE_SPAN_MS}, each put through {@link CaptionAnimator#unitProgress} and then
     * {@link CaptionAnimator#presetTransform} exactly as a renderer would, plus the preset's name.
     * The transform is applied the same way both renderers apply it — translate, then scale about
     * the unit's own centre — so the thumbnail is the motion, not an artist's idea of it.
     */
    private static final class PresetTileView extends View {
        private final CaptionAnimator.Preset preset;
        private final float density;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean selected;

        /**
         * When this tile's loop clock started. Every tile in the row is attached in the same pass,
         * so they share a phase to within a frame — deliberately. Tiles running the SAME clock is
         * what lets a user compare two presets by looking at them side by side; staggering them
         * would mean any difference on screen might be phase rather than preset.
         */
        private long startedAtMs;

        PresetTileView(@NonNull Context ctx, @NonNull CaptionAnimator.Preset p, boolean selected) {
            super(ctx);
            this.preset = p;
            this.selected = selected;
            this.density = ctx.getResources().getDisplayMetrics().density;
            labelPaint.setColor(TXT_DIM);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            // A BlurMaskFilter is a NO-OP on a hardware canvas, and a popover's window is hardware
            // accelerated — so without this the blur below would be a call into a void that looks
            // like function, which is the exact failure mode this project has already shipped once
            // (a setter nobody read). Mirrors TextBoxView.applyBlurLayerPolicy.
            //
            // Decided ONCE in the constructor rather than per frame because a tile's preset is
            // final, unlike a text box's: only GHOST's tile pays, and only while the popover is up.
            if (CaptionAnimator.presetBlurs(p)) {
                setLayerType(LAYER_TYPE_SOFTWARE, null);
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            startedAtMs = android.os.SystemClock.uptimeMillis();
        }

        void setSelectedRing(boolean s) {
            if (s == selected) return;
            selected = s;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            float w = getWidth(), h = getHeight();
            float rad = 10 * density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(TILE_BG);
            c.drawRoundRect(0, 0, w, h, rad, rad, paint);

            if (preset == CaptionAnimator.Preset.NONE) {
                // ⊘ "no animation" — the same affordance the ease picker gives LINEAR.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f * density);
                paint.setColor(GLYPH);
                float cx = w / 2f, cy = h * 0.42f, rr = Math.min(w, h) * 0.18f;
                c.drawCircle(cx, cy, rr, paint);
                float s = rr * 0.72f;
                c.drawLine(cx - s, cy + s, cx + s, cy - s, paint);
            } else {
                drawUnits(c, w, h);
            }

            labelPaint.setTextSize(8.5f * density);
            c.drawText(label(), w / 2f, h - 6 * density, labelPaint);

            if (selected) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(RING_FILL);
                c.drawRoundRect(0, 0, w, h, rad, rad, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f * density);
                paint.setColor(ACCENT);
                float inset = 1f * density;
                c.drawRoundRect(inset, inset, w - inset, h - inset, rad, rad, paint);
            }

            // Drive the loop from onDraw rather than a ValueAnimator: the tile is a pure function
            // of the clock, so there is no state to keep in sync, and the loop stops on its own
            // the moment the popover goes away and onDraw is no longer called. NONE is left
            // static on purpose — a still tile in a row of moving ones IS the affordance.
            if (preset != CaptionAnimator.Preset.NONE) {
                postInvalidateOnAnimation();
            }
        }

        private void drawUnits(@NonNull Canvas c, float w, float h) {
            // fontPx is what the distance-based presets scale their motion against, so the
            // thumbnail must pass a real one rather than 1 — otherwise RISE and GHOST, whose
            // whole character is a font-proportional offset, would render as identity.
            float fontPx = h * 0.30f;
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(fontPx);
            float baseY = h * 0.55f;
            // Each tile runs its own loop clock against the synthetic phrase. Staggering comes
            // from unitProgress, the same function the renderers use, rather than from three
            // hand-picked sample values.
            long mediaMs = (android.os.SystemClock.uptimeMillis() - startedAtMs) % TILE_SPAN_MS;
            for (int i = 0; i < TILE_UNITS; i++) {
                float progress = CaptionAnimator.unitProgress(
                        mediaMs, 0L, TILE_SPAN_MS, TILE_ZONE_MS, TILE_ZONE_MS, i, TILE_UNITS);
                // `i` is passed on so UNSCRAMBLE's per-unit scatter direction shows in the tile.
                // Without it the three glyphs would slide in along one shared vector and the tile
                // would advertise a diagonal wipe the renderers do not produce.
                CaptionAnimator.Transform t =
                        CaptionAnimator.presetTransform(preset, progress, fontPx, i);
                if (t.alpha <= 0.004f) continue;
                if (!CaptionAnimator.revealDrawsAnything(t.revealFrac)) continue;
                float cx = w * (0.26f + 0.24f * i);
                c.save();
                c.translate(t.dx, t.dy);
                c.scale(t.scaleX, t.scaleY, cx, baseY - fontPx * 0.35f);
                // GLYPH is an opaque token already. This was `GROUND | (GLYPH & 0xF4F4F5)` — the
                // palette sweep turned a 0x00FFFFFF bit mask into a colour value, which quietly
                // shaved bits off the glyph's channels. There was never anything to mask.
                paint.setColor(CaptionAnimator.applyAlpha(GLYPH, t.alpha));
                // The tile's own FIFTH channel: GHOST's blur, applied only when the TARGET's
                // renderer actually draws it.
                //
                // This comment used to read "t.blurPx is deliberately NOT applied. Neither
                // CaptionOverlayView nor CaptionExportRenderer consumes it today… When a renderer
                // gains blur, this line is where the tile follows it." A renderer HAS since gained
                // blur — TextBoxRenderer — so this is that line following it.
                //
                // This was briefly a per-target PARAMETER (targetBlursGhost), because for a few
                // hours on 2026-07-31 a text box blurred and a caption did not, and blurring the
                // tile for a caption would have advertised softness that target never drew. That
                // asymmetry is gone: captions consume blurPx too now, on both their surfaces, so
                // EVERY consumer of Transform#blurPx blurs and the parameter had exactly one
                // value. It was removed rather than left as always-true — a flag with one value
                // is dead flexibility that reads as a real choice.
                //
                // If a future target genuinely cannot blur, put it back rather than approximating:
                // the rule this file exists to enforce is that a tile never advertises a motion
                // its renderer does not produce.
                boolean blurTile = t.blurPx > 0.25f;
                if (blurTile) {
                    paint.setMaskFilter(new android.graphics.BlurMaskFilter(
                            t.blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL));
                }
                // Also drive the SUBSTITUTION channel, not just the transform. MATRIX is identity
                // in geometry and alpha on purpose — its whole motion is which character is drawn
                // — so a tile that only applied presetTransform would render MATRIX as three
                // static "A"s, i.e. exactly the dead tile this file was rewritten to stop.
                String glyph = CaptionAnimator.substituteUnit(preset, "A", progress, i);
                // ...and the THIRD channel, for the same reason. MASK_WIPE is identity in geometry
                // and alpha exactly as MATRIX is, so a tile that applied only presetTransform would
                // advertise it as three static "A"s. The tile draws CENTRED, so its slot is the
                // glyph's measured advance about cx — the renderers pass a left edge and a measured
                // width, which is the same rect described from the other end.
                if (t.revealFrac < 1f) {
                    float gw = paint.measureText(glyph);
                    CaptionAnimator.revealClip(cx - gw / 2f, baseY, gw, fontPx, t.revealFrac,
                            revealTmp);
                    c.clipRect(revealTmp[0], revealTmp[1], revealTmp[2], revealTmp[3]);
                }
                // ...and the FOURTH channel, for the third time and the same reason. ODOMETER is
                // identity in geometry and alpha exactly as MATRIX and MASK_WIPE are — its entire
                // motion is two glyph rows moving through a slot — so a tile that stopped at the
                // three channels above would advertise it as three static "A"s. "A" is on the
                // uppercase ring, so the sample glyph genuinely rolls rather than sitting still
                // for want of a ring.
                CaptionAnimator.rollUnit(preset, glyph, progress, rollTmp);
                if (rollTmp.rolling) {
                    float gw = paint.measureText(glyph);
                    CaptionAnimator.rollClip(cx - gw / 2f, baseY, gw, fontPx, rollClipTmp);
                    c.clipRect(rollClipTmp[0], rollClipTmp[1], rollClipTmp[2], rollClipTmp[3]);
                    float slotH = rollClipTmp[3] - rollClipTmp[1];
                    c.save();
                    c.translate(0f, rollTmp.phase * slotH);
                    c.drawText(rollTmp.incoming, cx, baseY, paint);
                    c.restore();
                    c.save();
                    c.translate(0f, (rollTmp.phase - 1f) * slotH);
                    c.drawText(rollTmp.outgoing, cx, baseY, paint);
                    c.restore();
                } else {
                    c.drawText(glyph, cx, baseY, paint);
                }
                c.restore();
                // Cleared per unit, not once after the loop: `paint` is shared with the tile's
                // background round-rect and with the NONE glyph, so a filter left set would blur
                // the next frame's chrome as well as the next unit. Same discipline
                // TextBoxRenderer.drawUnit uses on its own shared TextPaint.
                if (blurTile) paint.setMaskFilter(null);
            }
        }

        /** Scratch for {@link CaptionAnimator#revealClip} — onDraw runs on every frame. */
        private final float[] revealTmp = new float[4];

        /** Scratch for the roll channel — same reason as {@link #revealTmp}. */
        private final CaptionAnimator.Roll rollTmp = new CaptionAnimator.Roll();
        private final float[] rollClipTmp = new float[4];

        /**
         * From the ONE authority, not a fourth private copy. This switch used to live here and
         * fall back to {@code preset.name()}, which is how the caption drawer shipped a bare
         * "MATRIX" for a session.
         */
        @NonNull
        private String label() {
            return CaptionAnimator.presetLabel(preset);
        }
    }
}
