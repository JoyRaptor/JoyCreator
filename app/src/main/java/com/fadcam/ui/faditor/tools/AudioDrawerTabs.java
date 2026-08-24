package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.KeyframeDiamondControl;
import com.fadcam.ui.faditor.ObjectMenuSheet;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.AudioParams;
import com.fadcam.ui.faditor.model.VolumeKeyframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Content for {@link ObjectDrawer}'s AUDIO tabs (SPEC_AUDIO_UX_V1 §2.1:
 * Level · Clean · Tone · FX). Built like {@link PipDrawerTabs} — a {@link Host}
 * interface back to the editor, static ContentBuilder methods returning a View, COMPACT
 * one-line rows — so {@code ObjectDrawer} stays chrome and knows nothing about audio.
 *
* <p><b>SCOPED (2026-08-23): the Level, Pan, and Clean tabs.</b> Tone/FX still need C1.E's
 * real-time chain, which does not exist; §0 rule 6 forbids shipping their sliders ahead of
 * it. Within Level, exactly four controls ship because exactly four are real today:</p>
 * <ul>
 *   <li><b>Level</b> — flat gain ({@code volumeLevel}) or, once the envelope is armed,
 *       the envelope point under the playhead ({@code gainAtClipMs});</li>
 *   <li><b>Pan</b> — stereo position (−1 = full left, 0 = center, +1 = full right),</li>
 *       using the same equal-power law as export ({@link VolumeAudioProcessor}) so
 *       preview and export change together (A5.U).</li>
 *   <li><b>The volume envelope</b> — the diamond drops/deletes/jumps keys; fades ride
 *       the same envelope;</li>
 *   <li><b>Fade in / Fade out</b> — durations written through
 *       {@link AudioClip#getFadeInMs()}/{@link AudioClip#setFadeInMs(long)} and the
 *       fade-out mirror, THE single definition of a fade, matching what the timeline's
 *       fade drag handle writes.</li>
 * </ul>
 *
 * <p>"+ Cross-fade" (B2.E) and Compressor/limiter/normalize-peak (C1.E) are left OUT
 * entirely — no disabled placeholders.</p>
 */
public final class AudioDrawerTabs {

    private AudioDrawerTabs() {}

    private static final int TXT = 0xFFE8E8E8;
    private static final int TXT_DIM = 0xFFA0A0A0;
    private static final int SLIDER_STEPS = 1000;
    /** Key-match tolerance in clip-ms — the SAME tolerance addOrUpdateVolumeKeyframe uses. */
    private static final int KEY_TOLERANCE_MS = 40;

    /** Everything the tabs need back from the editor. */
    public interface Host {
        long playheadMs();
        /** Jump the timeline playhead — the envelope diamond's ‹ › key navigation. */
        void seekTo(long timelineMs);
        /** A value/keyframe changed — repaint preview + timeline and schedule a save. */
        void onChanged();
        /** Record one undo step. */
        void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo);
    }

    /**
     * The LEVEL tab: one Level slider (flat gain ↔ envelope-under-playhead, with its
     * keyframe diamond), an envelope state line, and the two fade sliders.
     */
    @NonNull
    public static View levelTab(@NonNull Context ctx, @NonNull AudioClip clip,
                                @NonNull Host host) {
        return levelTab(ctx, clip, host, true);
    }

    /**
     * @param allowPan offer the stereo pan row.
     *
     *        <p><b>False for a VIDEO clip's audio, and it has to be.</b> A video clip is shown
     *        this tab through a throwaway {@code AudioClip} proxy (see the drawer's construction
     *        in {@code FaditorEditorActivity}), because §2.2 promises a video clip's audio the
     *        same drawer. The proxy's Host copies volume, keyframes and mute back to the real
     *        {@code Clip} — but {@code Clip} has no pan at all ({@code getPan()} returns 0,
     *        {@code setPan()} is a documented no-op), so pan had nowhere to be copied to.</p>
     *
     *        <p>The result was worse than an inert control: the slider MOVED, showed its new
     *        value, and the pan died with the proxy the moment the drawer closed. Reopening
     *        showed centre again with no explanation. That is the same family as the volume
     *        rubber-band that only drew when keyframes existed (`G18`) and the master Solo row
     *        the logic ignored (`G22`) — a control that looks like it works.</p>
     *
     *        <p>Offering nothing is honest; offering something that silently discards the
     *        user's input is not. Giving {@code Clip} real pan is a feature — model field,
     *        persistence, and the master export path's VolumeAudioProcessor all need it — not
     *        a fix, so it is left as one.</p>
     */
    public static View levelTab(@NonNull Context ctx, @NonNull AudioClip clip,
                                @NonNull Host host, boolean allowPan) {
        LinearLayout root = column(ctx);
        final List<Runnable> refreshers = new ArrayList<>();
        refreshers.add(levelRow(ctx, root, clip, host));
        if (allowPan) refreshers.add(panRow(ctx, root, clip, host));
        refreshers.add(envelopeStateRow(ctx, root, clip, host));
        // A zero-length clip has nothing to fade — omit the rows rather than show dead ones.
        if (clip.getTrimmedDurationMs() > 0) {
            // Both fades share ONE line (JoyRaptor 2026-08-23) — see fadeRow's `half`.
            LinearLayout fades = new LinearLayout(ctx);
            fades.setOrientation(LinearLayout.HORIZONTAL);
            fades.setGravity(Gravity.CENTER_VERTICAL);
            root.addView(fades);
            refreshers.add(fadeRow(ctx, fades, "In", true, clip, host, true));   // TODO(strings)
            refreshers.add(fadeRow(ctx, fades, "Out", false, clip, host, true)); // TODO(strings)
        }
        // Same row-refresh contract as PipDrawerTabs.videoTab: hang the refresh off the VIEW
        // so the drawer can re-read every row at the live playhead without knowing tabs.
        root.setTag(R.id.faditor_tag_row_refresh, (Runnable) () -> {
            for (Runnable r : refreshers) r.run();
        });
        return root;
    }

    // ── Row 1: LEVEL ─────────────────────────────────────────────────────────────────────

    /**
     * Label · slider · value · diamond, one line. What the slider WRITES depends on the
     * clip's live mode: no envelope → the whole-clip gain; envelope armed → the key under
     * the playhead (add-or-update). One control that is honest in both modes beats two
     * controls fighting over one slider.
     */
    @NonNull
    private static Runnable levelRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                     @NonNull AudioParams clip, @NonNull Host host) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(2 * d), 0, Math.round(2 * d));

        TextView label = new TextView(ctx);
        label.setTextColor(TXT_DIM);
        label.setTextSize(11);
        label.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        label.setWidth(Math.round(52 * d));
        label.setMaxLines(1);
        label.setText("Level");                                            // TODO(strings)
        row.addView(label);

        FineSeekBar bar = new FineSeekBar(ctx);
        bar.setMax(SLIDER_STEPS);

        TextView value = new TextView(ctx);
        value.setTextColor(TXT);
        value.setTextSize(11);
        value.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        value.setWidth(Math.round(46 * d));
        value.setGravity(Gravity.END);

        final Runnable[] selfRefresh = new Runnable[1];
        final Runnable refreshAll = () -> {
            if (selfRefresh[0] != null) selfRefresh[0].run();
        };

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Nullable List<VolumeKeyframe> beforeKfs;
            float beforeVol;

            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                // isFineDriving: a fine drag reports fromUser == false exactly as the
                // playhead-tick refresh does — see PipDrawerTabs.propRow.
                if (!fromUser && !bar.isFineDriving()) return;
                writeLevel(clip, host, gainOf(p), localMs(clip, host.playheadMs()));
                value.setText(fmtGain(levelAt(clip, localMs(clip, host.playheadMs()))));
                host.onChanged();
            }
            // ONE undo step per gesture (§0 rule 7), not one per tick.
            @Override public void onStartTrackingTouch(SeekBar s) {
                beforeKfs = copyKeyframes(clip);
                beforeVol = clip.getVolumeLevel();
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                commitLevelGesture(clip, host, beforeKfs, beforeVol, refreshAll);
            }
        });

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(bar, blp);
        row.addView(value);
        // TAP THE NUMBER TO TYPE IT — exact percentages are unreachable on a 1000-step
        // slider (same justification as PipDrawerTabs' Scale row).
        value.setPaintFlags(value.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        value.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        value.setOnClickListener(v -> promptForGain(ctx, clip, host, refreshAll));

        KeyframeDiamondControl diamond = new KeyframeDiamondControl(ctx);
        diamond.bind(volumeProp(clip, host, refreshAll),
                new KeyframeDiamondControl.Host() {
                    @Override public long playheadMs() { return host.playheadMs(); }
                    @Override public void onFocus() { }
                    @Override public void onAction() {
                        host.onChanged();
                        if (selfRefresh[0] != null) selfRefresh[0].run();
                    }
                });
        row.addView(diamond);
        parent.addView(row);

        selfRefresh[0] = () -> {
            float cur = levelAt(clip, localMs(clip, host.playheadMs()));
            value.setText(fmtGain(cur));
            bar.setProgress(progressOf(cur));
            diamond.refresh(host.playheadMs());
        };
        return selfRefresh[0];
    }

    /**
     * Row for stereo pan slider. Center (0) = true no-op. Uses the same
     * equal-power law as VolumeAudioProcessor so preview and export match.
     */
    @NonNull
    private static Runnable panRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                   @NonNull AudioParams clip, @NonNull Host host) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(2 * d), 0, Math.round(2 * d));

        TextView label = new TextView(ctx);
        label.setTextColor(TXT_DIM);
        label.setTextSize(11);
        label.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        label.setWidth(Math.round(52 * d));
        label.setMaxLines(1);
        label.setText("Pan");                                            // TODO(strings)
        // A5.U BUGFIX 2026-08-24: this read parent.addView(label) — the label rendered as
        // its own full-width LINE above a label-less slider row, breaking the one-line
        // "Label · slider · value · diamond" idiom every other row in this file follows.
        row.addView(label);

        FineSeekBar bar = new FineSeekBar(ctx);
        bar.setMax(SLIDER_STEPS);

        TextView value = new TextView(ctx);
        value.setTextColor(TXT);
        value.setTextSize(11);
        value.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        value.setWidth(Math.round(46 * d));
        value.setGravity(Gravity.END);

        final Runnable[] selfRefresh = new Runnable[1];
        final Runnable refreshAll = () -> {
            if (selfRefresh[0] != null) selfRefresh[0].run();
        };

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            float beforePan;

            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser && !bar.isFineDriving()) return;
                writePan(clip, host, panOf(p));
                value.setText(fmtPan(panAt(clip)));
                host.onChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                // Captured HERE, not in undo: undo must restore the value the user had
                // BEFORE the drag, not the neutral centre — an undo that recentres is not
                // an undo, it is a second edit wearing its clothes.
                beforePan = panAt(clip);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                final float was = beforePan;
                final float now = panAt(clip);
                if (was == now) return;
                host.recordUndo("Pan",
                        () -> { clip.setPan(now); host.onChanged(); },
                        () -> { clip.setPan(was); host.onChanged(); });
            }
        });

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(bar, blp);
        row.addView(value);
        // TAP THE NUMBER TO TYPE IT — same as Level.
        value.setPaintFlags(value.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        value.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        value.setOnClickListener(v -> promptForPan(ctx, clip, host, refreshAll));

        row.addView(new View(ctx)); // spacer for diamond position parity
        parent.addView(row);

        selfRefresh[0] = () -> {
            float cur = panAt(clip);
            value.setText(fmtPan(cur));
            bar.setProgress(progressOfPan(cur));
        };
        return selfRefresh[0];
    }

    /**
     * The envelope diamond's brain. The envelope is LINEAR-only (no easing segments), so
     * the ease picker gets null and shows its disabled hint rather than pretending; ‹ ›
     * walk existing keys by seeking the playhead to them.
     */
    @NonNull
    private static ObjectMenuSheet.Prop volumeProp(@NonNull AudioParams clip, @NonNull Host host,
                                                   @Nullable Runnable refresh) {
        return new ObjectMenuSheet.Prop(
                "audioVolume", "Level", 0f, 2f,
                AudioDrawerTabs::fmtGain,
                ph -> levelAt(clip, localMs(clip, ph)),
                (v, ph) -> writeLevel(clip, host, v, localMs(clip, ph)),
                ph -> onKeyNear(clip, localMs(clip, ph)),
                () -> dropKeyHere(clip, host, refresh),
                () -> nudgeToKey(clip, host, -1),
                () -> nudgeToKey(clip, host, 1),
                () -> deleteKeyHere(clip, host, refresh),
                clip::hasVolumeKeyframes,
                null, null)
                .withSpan(ph -> {
                    long local = localMs(clip, ph);
                    return local >= 0 && local <= clip.getTrimmedDurationMs();
                });
    }

    /**
     * Envelope state line under the Level row: what the slider is currently driving, plus
     * the way back to flat. No envelope → no Clear chip — nothing for it to do.
     */
    @NonNull
    private static Runnable envelopeStateRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                             @NonNull AudioParams clip, @NonNull Host host) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = row(ctx);
        TextView state = new TextView(ctx);
        state.setTextColor(TXT_DIM);
        state.setTextSize(10);
        state.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        state.setPadding(Math.round(8 * d), 0, Math.round(8 * d), 0);
        row.addView(state, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = chip(ctx, "Clear", d);                            // TODO(strings)
        clear.setOnClickListener(v -> {
            if (!clip.hasVolumeKeyframes()) return;
            List<VolumeKeyframe> before = copyKeyframes(clip);
            clip.clearVolumeKeyframes();
            host.recordUndo("Clear envelope",
                    () -> { clip.setVolumeKeyframes(before); host.onChanged(); },
                    () -> { clip.clearVolumeKeyframes(); host.onChanged(); });
            host.onChanged();
        });
        row.addView(clear);
        parent.addView(row);

        return () -> {
            int n = clip.getVolumeKeyframes().size();
            state.setText(n == 0
                    ? "Flat gain — ◇ drops the first envelope point"      // TODO(strings)
                    : "Envelope · " + n + (n == 1 ? " pt" : " pts")
                            + " — slider drives the point under ▶");      // TODO(strings)
            clear.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        };
    }

    // ── Rows 2–3: FADE IN / FADE OUT ─────────────────────────────────────────────────────

    /**
     * Writes through {@link AudioClip#setFadeInMs(long)} / {@code setFadeOutMs} — the same
     * two-key convention the timeline's fade drag handle writes, so the slider and the
     * handle cannot ever disagree about what a fade is.
     */
    @NonNull
    /**
     * @param half JoyRaptor, 2026-08-23: "Fade in and fade out should be on the same line. They
     *             don't need a full screen's width." When true the row narrows its label and
     *             value columns and takes an equal share of a horizontal parent, so the pair
     *             costs ONE line of drawer height instead of two. Height over the preview is
     *             the scarcest thing in this drawer.
     */
private static Runnable fadeRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                   @NonNull String label, boolean fadeIn,
                                   @NonNull AudioClip clip, @NonNull Host host,
                                   boolean half) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(2 * d), 0, Math.round(2 * d));

        TextView labelView = new TextView(ctx);
        labelView.setTextColor(TXT_DIM);
        labelView.setTextSize(11);
        labelView.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        labelView.setWidth(Math.round((half ? 40 : 52) * d));
        labelView.setMaxLines(1);
        labelView.setText(label);
        row.addView(labelView);

        long maxFade = Math.max(1, clip.getTrimmedDurationMs() / 2);

        FineSeekBar bar = new FineSeekBar(ctx);
        bar.setMax(SLIDER_STEPS);

        TextView value = new TextView(ctx);
        value.setTextColor(TXT);
        value.setTextSize(11);
        value.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        value.setWidth(Math.round((half ? 38 : 46) * d));
        value.setGravity(Gravity.END);

        final Runnable[] selfRefresh = new Runnable[1];
        final Runnable refreshAll = () -> {
            if (selfRefresh[0] != null) selfRefresh[0].run();
        };

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Nullable List<VolumeKeyframe> beforeKfs;

            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser && !bar.isFineDriving()) return;
                setFade(clip, fadeIn, fadeMsOf(p, maxFade));
                value.setText(fmtMs(readFade(clip, fadeIn)));
                host.onChanged();
            }
            // ONE undo step per gesture (§0 rule 7).
            @Override public void onStartTrackingTouch(SeekBar s) {
                beforeKfs = copyKeyframes(clip);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                commitFadeGesture(clip, host, fadeIn, beforeKfs, refreshAll);
            }
        });

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(bar, blp);
        row.addView(value);
        // TAP TO TYPE, in seconds ("0.5") — exactly 500ms is a fingertip lottery otherwise.
        value.setPaintFlags(value.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        value.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        value.setOnClickListener(v ->
                promptForFadeSeconds(ctx, clip, host, fadeIn, maxFade, refreshAll));
        if (half) {
            parent.addView(row, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            parent.addView(row);
        }

        selfRefresh[0] = () -> {
            long cur = readFade(clip, fadeIn);
            value.setText(fmtMs(cur));
            bar.setProgress(Math.round(Math.max(0, Math.min(maxFade, cur))
                    / (float) maxFade * SLIDER_STEPS));
        };
        return selfRefresh[0];
    }

    // ── Tab 2: CLEAN ─────────────────────────────────────────────────────────────────────

    /**
     * The CLEAN tab (C2.U): offline denoise + loudness normalization through
     * {@link BakedAudioCache}, with live progress and a way back to the original.
     *
     * <p>BAKED per §6.2, and honest about it: these run ffmpeg OFFLINE over the clip's
     * trimmed source span and REPOINT the clip at the rendered file — the progress bar is
     * real because the wait is real. Applying records ONE undo step (its undo restores the
     * original uri and deletes the artifact), and while a bake is applied the tab offers
     * "Revert to original" directly, which survives session restarts via the clip's
     * {@link AudioClip#getBakedFromUri()} bookkeeping.</p>
     *
     * <p>{@code projectDir} and {@code cache} come in as parameters rather than through
     * {@link Host} so wiring this tab touches nothing else: the caller passes
     * {@code ProjectStorage.projectDir(id)} and one shared cache instance.</p>
     */
    /**
     * Clips with a bake IN FLIGHT (ids). The Clean tab is rebuilt every time the drawer
     * opens, so its {@code baking} local cannot see across close/reopen — without this,
     * reopening mid-bake offered a second "Reduce noise" tap that started a SECOND ffmpeg
     * job on the same clip: last writer wins on sourceUri and the loser's output is an
     * orphaned artifact eating disk.
     */
    private static final java.util.Set<String> BAKING_CLIP_IDS = new java.util.HashSet<>();

    /** True while the named clip has a bake running, regardless of drawer rebuilds. */
    public static boolean isBaking(@NonNull String clipId) {
        return BAKING_CLIP_IDS.contains(clipId);
    }

    @NonNull
    public static View cleanTab(@NonNull Context ctx, @NonNull AudioParams clip,
                                @NonNull Host host, @NonNull java.io.File projectDir,
                                @NonNull com.fadcam.ui.faditor.audio.BakedAudioCache cache) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = column(ctx);
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        // Seeded from the CLIP-id registry, not a fresh local: a reopen mid-bake must show
        // the honest "Processing…" state, not an idle sheet inviting a duplicate job.
        final boolean[] baking = {BAKING_CLIP_IDS.contains(clip.getId())};

        TextView state = new TextView(ctx);
        state.setTextColor(TXT_DIM);
        state.setTextSize(10);
        state.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        state.setPadding(Math.round(8 * d), Math.round(4 * d),
                Math.round(8 * d), Math.round(6 * d));
        root.addView(state);

        LinearLayout actions = row(ctx);
        root.addView(actions);

        // Holder, not a plain local: the row's own listeners re-run this refresh, and a
        // lambda may not capture itself before its initializer completes.
        final Runnable[] refreshHolder = new Runnable[1];
        final Runnable refresh = () -> {
            actions.removeAllViews();
            if (baking[0]) {
                state.setText("Processing…");                                   // TODO(strings)
                return;
            }
            if (clip.isBakedSource()) {
                state.setText("Processed audio in use — original kept.");       // TODO(strings)
            } else {
                state.setText("Offline renders — the original file is never modified."); // TODO(strings)
            }
            TextView revert = chip(ctx, "Revert to original", d);               // TODO(strings)
            revert.setTextColor(clip.isBakedSource() ? TXT : TXT_DIM);
            revert.setOnClickListener(v -> {
                if (!clip.isBakedSource() || baking[0]) return;
                String origUri = clip.getBakedFromUri();
                String bakedPath = clip.getBakedFromFile();
                java.io.File bakedFile =
                        bakedPath == null ? null : new java.io.File(bakedPath);
                clip.setSourceUri(android.net.Uri.parse(origUri));
                clip.setBakedFrom(null, null);
                host.recordUndo("Revert to original",
                        () -> {
                            clip.setSourceUri(android.net.Uri.parse(
                                    bakedPath != null ? bakedPath : origUri));
                            clip.setBakedFrom(origUri, bakedPath);
                            host.onChanged();
                        },
                        () -> {
                            clip.setSourceUri(android.net.Uri.parse(origUri));
                            clip.setBakedFrom(null, null);
                            host.onChanged();
                        });
                // The artifact goes when we revert away from it.
                if (bakedFile != null) {
                    com.fadcam.ui.faditor.audio.BakedAudioCache.discardBake(bakedFile);
                }
                host.onChanged();
                if (refreshHolder[0] != null) refreshHolder[0].run();
            });
            actions.addView(revert);
        };
        refresh.run();
        refreshHolder[0] = refresh;

        // One apply action per chain; both share the bracket below.
        class Apply implements Runnable {
            private final String chain;
            private final String label;
            Apply(String chain, String label) { this.chain = chain; this.label = label; }

            @Override public void run() {
                if (baking[0] || clip.isBakedSource()) return;
                baking[0] = true;
                BAKING_CLIP_IDS.add(clip.getId());
                refresh.run();
                long startMs = clip.getInPointMs();
                long endMs = clip.getOutPointMs();
                com.fadcam.ui.faditor.audio.BakedAudioCache.Request req =
                        new com.fadcam.ui.faditor.audio.BakedAudioCache.Request(
                                clip.getSourceUri(), startMs, endMs, chain);
                cache.bakeAsync(projectDir, req,
                        frac -> main.post(() -> state.setText(frac < 0 ? "Processing…"
                                : label + " · " + Math.round(frac * 100f) + "%")),
                        (result, error) -> main.post(() -> {
                            baking[0] = false;
                            BAKING_CLIP_IDS.remove(clip.getId());
                            if (result == null) {
                                state.setText("Failed: "
                                        + (error != null ? error : "?"));   // TODO(strings)
                                return;
                            }
                            String origUri = clip.getSourceUri().toString();
                            java.io.File bakedFile = result.bakedFile;
                            android.net.Uri baked = android.net.Uri.fromFile(bakedFile);
                            clip.setSourceUri(baked);
                            clip.setBakedFrom(origUri, bakedFile.getAbsolutePath());
                            host.recordUndo(label,
                                    () -> {
                                        clip.setSourceUri(baked);
                                        clip.setBakedFrom(origUri,
                                                bakedFile.getAbsolutePath());
                                        host.onChanged();
                                    },
                                    () -> {
                                        clip.setSourceUri(android.net.Uri.parse(origUri));
                                        clip.setBakedFrom(null, null);
                                        com.fadcam.ui.faditor.audio.BakedAudioCache
                                                .discardBake(bakedFile);
                                        host.onChanged();
                                    });
                            host.onChanged();
                            refresh.run();
                        }));
            }
        }
        TextView dn = chip(ctx, "Reduce noise", d);                            // TODO(strings)
        dn.setOnClickListener(v -> new Apply(
                com.fadcam.ui.faditor.audio.BakedAudioCache.CHAIN_DENOISE,
                "Reduce noise").run());
        actions.addView(dn);
        TextView ln = chip(ctx, "Normalize loudness", d);                      // TODO(strings)
        ln.setOnClickListener(v -> new Apply(
                com.fadcam.ui.faditor.audio.BakedAudioCache.CHAIN_LOUDNORM,
                "Normalize loudness").run());
        actions.addView(ln);

        TextView note = new TextView(ctx);
        note.setText("Runs ffmpeg offline over this clip's trimmed range.");   // TODO(strings)
        note.setTextColor(TXT_DIM);
        note.setTextSize(10);
        note.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        note.setPadding(Math.round(8 * d), Math.round(2 * d), Math.round(8 * d), 0);
        root.addView(note);
        return root;
    }

    // ── Tab 3: FX ────────────────────────────────────────────────────────────────────────

    /**
     * C7: A/B bypass of the WHOLE real-time FX chain. One flag, session-scoped, read by
     * whatever wires the chain into preview/export (C1.E) — flipping it must mute every
     * effect at once so a tuned sound can be compared against the untouched source.
     *
     * <p>HONESTY (§0 rule 6): until that wiring lands, no engine reads this flag, so the
     * toggle says exactly that in its toast and the FX tab carries the standing note. The
     * control is not pretending to do something it does not; it is the switch the engine
     * will be wired to, shipped first so the wiring has a consumer on day one.</p>
     */
    public static volatile boolean fxChainBypassed = false;

    /**
     * C6: live gain reduction reported by the wired chain's compressor, in dB (0 = no
     * reduction). {@code Float.NaN} means nothing has reported yet — the bar rests at zero.
     * The engine calls {@link #reportGainReductionDb} per buffer or per tick; the drawer only
     * ever READS it, so preview and export can both feed it without touching this class.
     */
    public static volatile float reportedGainReductionDb = Float.NaN;

    /** Engine-side hook: report the compressor's current gain reduction in dB. */
    public static void reportGainReductionDb(float db) {
        reportedGainReductionDb = db;
    }

    /**
     * The FX tab (C6): the compressor's gain-reduction bar — because a compressor tuned blind
     * is guesswork. The bar is ALWAYS drawn (G18 resting-state rule): full-width dim track,
     * fill showing how many dB are being pushed down right now, value readout beside it.
     * Scale is 0..−12 dB, the range where vocal compression actually lives.
     */
    @NonNull
    public static View fxTab(@NonNull Context ctx, @NonNull Host host) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = column(ctx);

        TextView title = new TextView(ctx);
        title.setText("Compressor");                                       // TODO(strings)
        title.setTextColor(TXT);
        title.setTextSize(12.5f);
        title.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        root.addView(title);

        GainReductionBar bar = new GainReductionBar(ctx);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(10 * d)));

        LinearLayout valRow = row(ctx);
        TextView value = new TextView(ctx);
        value.setTextColor(TXT_DIM);
        value.setTextSize(10);
        value.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        value.setPadding(Math.round(8 * d), 0, Math.round(8 * d), 0);
        valRow.addView(value, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(valRow);

        // §0 rule 6: say what the control IS, including when it cannot be live yet.
        TextView note = new TextView(ctx);
        note.setTextColor(TXT_DIM);
        note.setTextSize(10);
        note.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        note.setPadding(Math.round(8 * d), Math.round(2 * d), Math.round(8 * d), 0);
        root.addView(note);

        Runnable refresh = () -> {
            float gr = reportedGainReductionDb;
            boolean live = !Float.isNaN(gr);
            bar.setLevelDb(live ? Math.max(0f, -gr) : 0f);
            value.setText(live ? fmtGr(gr)
                    : "0.0 dB");                                           // TODO(strings)
            note.setText(fxChainBypassed
                    ? "FX chain BYPASSED — hearing the untouched mix."      // TODO(strings)
                    : live ? "Gain reduction, live from the compressor."   // TODO(strings)
                    : "Waits for the real-time chain (C1.E) to report.");  // TODO(strings));
        };
        refresh.run();
        // Same row-refresh contract as levelTab: PipDrawerTabs.refreshRows walks the showing
        // drawer on every playhead tick, so the bar tracks live GR without owning a timer.
        root.setTag(R.id.faditor_tag_row_refresh, refresh);
        return root;
    }

    @NonNull
    private static String fmtGr(float negativeDb) {
        return String.format(java.util.Locale.US, "%.1f dB", negativeDb);
    }

    /**
     * The gain-reduction meter itself: a horizontal track whose fill grows RIGHT-TO-LEFT as
     * the compressor pushes the level down — reduction reads as subtraction, leftward.
     */
    private static final class GainReductionBar extends android.view.View {
        private static final int TRACK_COLOR = 0x22FFFFFF;
        private static final int FILL_COLOR = 0xFFFFB74D;
        /** Full scale: 12 dB of reduction sweeps the whole bar. */
        private static final float MAX_DB = 12f;
        private final android.graphics.Paint trackPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float level01 = 0f;

        GainReductionBar(@NonNull Context ctx) {
            super(ctx);
        }

        void setLevelDb(float reductionDb) {
            float f = Math.max(0f, Math.min(1f, reductionDb / MAX_DB));
            if (Math.abs(f - level01) > 0.002f) {
                level01 = f;
                invalidate();
            }
        }

        @Override
        protected void onDraw(@NonNull android.graphics.Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            trackPaint.setColor(TRACK_COLOR);
            canvas.drawRoundRect(0f, 0f, w, h, r, r, trackPaint);
            if (level01 > 0.005f) {
                fillPaint.setColor(FILL_COLOR);
                canvas.drawRoundRect(w - w * level01, 0f, w, h, r, r, fillPaint);
            }
        }
    }

    // ── shared builders (the PipDrawerTabs idiom) ────────────────────────────────────────

    @NonNull
    private static LinearLayout column(@NonNull Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Math.round(14 * d), 0, Math.round(14 * d), Math.round(10 * d));
        return l;
    }

    @NonNull
    private static LinearLayout row(@NonNull Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, Math.round(6 * d), 0, Math.round(2 * d));
        return l;
    }

    @NonNull
    private static TextView chip(@NonNull Context ctx, @NonNull String text, float d) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(TXT);
        t.setTextSize(12.5f);
        t.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        t.setPadding(Math.round(10 * d), Math.round(6 * d),
                Math.round(10 * d), Math.round(6 * d));
        t.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(8 * d);
        t.setLayoutParams(lp);
        return t;
    }

    // ── model plumbing ───────────────────────────────────────────────────────────────────

    /** Clip-local ms of a timeline position — the domain the envelope keys live in. */
    private static long localMs(@NonNull AudioParams clip, long timelineMs) {
        return timelineMs - clip.getOffsetMs();
    }

    /**
     * The audible level at a clip-local time: the envelope when armed (it OVERRIDES the
     * whole-clip gain, both in preview and at export), else the flat gain.
     */
    private static float levelAt(@NonNull AudioParams clip, long localMs) {
        return clip.gainAtClipMs(localMs);
    }

    /** The current pan at a clip-local time (no envelope for pan yet). */
    private static float panAt(@NonNull AudioParams clip) {
        return clip.getPan();
    }

    /** Write a pan value — no envelope support yet, just flat pan. */
    private static void writePan(@NonNull AudioParams clip, @NonNull Host host, float pan) {
        clip.setPan(pan);
    }

    /**
     * Route a slider/value write to whatever is LIVE: an envelope point under the playhead
     * when the envelope is armed (clamped to the clip — a key outside it could never be
     * heard or seen), else the whole-clip flat gain. {@code gain} is the FINAL audible
     * gain the user sees on the row; B1.Q stores it as a MULTIPLIER over volumeLevel, so
     * moving the slider later rescales the whole envelope instead of stranding stale peaks.
     */
    private static void writeLevel(@NonNull AudioParams clip, @NonNull Host host,
                                   float gain, long localMs) {
        if (clip.hasVolumeKeyframes()
                && localMs >= 0 && localMs <= clip.getTrimmedDurationMs()) {
            clip.addOrUpdateVolumeKeyframe(localMs, multiplierFor(clip, gain));
        } else {
            clip.setVolumeLevel(gain);
        }
    }

    /** Desired FINAL gain → stored envelope multiplier (B1.Q). Silent clip → 0. */
    private static float multiplierFor(@NonNull AudioParams clip, float finalGain) {
        float lvl = clip.getVolumeLevel();
        if (lvl < 0.0001f) return 0f;
        return Math.max(0f, Math.min(2f, finalGain / lvl));
    }

    private static void setFade(@NonNull AudioClip clip, boolean fadeIn, long fadeMs) {
        if (fadeIn) clip.setFadeInMs(fadeMs); else clip.setFadeOutMs(fadeMs);
    }

    private static long readFade(@NonNull AudioClip clip, boolean fadeIn) {
        return fadeIn ? clip.getFadeInMs() : clip.getFadeOutMs();
    }

    private static float gainOf(int progress) {
        return progress / (float) SLIDER_STEPS * 2f;
    }

    private static int progressOf(float gain) {
        return Math.round(Math.max(0f, Math.min(2f, gain)) / 2f * SLIDER_STEPS);
    }

    private static float panOf(int progress) {
        return progress / (float) SLIDER_STEPS * 2f - 1f; // 0..1000 -> -1..1
    }

    private static int progressOfPan(float pan) {
        return Math.round((pan + 1f) / 2f * SLIDER_STEPS);
    }

    private static long fadeMsOf(int progress, long maxFade) {
        return Math.round(Math.max(0, Math.min(SLIDER_STEPS, progress))
                / (float) SLIDER_STEPS * maxFade);
    }

    @NonNull
    private static List<VolumeKeyframe> copyKeyframes(@NonNull AudioParams clip) {
        List<VolumeKeyframe> out = new ArrayList<>();
        for (VolumeKeyframe kf : clip.getVolumeKeyframes()) {
            out.add(new VolumeKeyframe(kf.timeMs, kf.volume));
        }
        return out;
    }

    private static boolean sameEnvelope(@Nullable List<VolumeKeyframe> a,
                                        @NonNull List<VolumeKeyframe> b) {
        if (a == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).timeMs != b.get(i).timeMs
                    || Float.floatToIntBits(a.get(i).volume)
                            != Float.floatToIntBits(b.get(i).volume)) {
                return false;
            }
        }
        return true;
    }

    private static void restoreEnvelope(@NonNull AudioParams clip,
                                        @NonNull List<VolumeKeyframe> kfs,
                                        float volumeLevel) {
        clip.setVolumeKeyframes(kfs);
        clip.setVolumeLevel(volumeLevel);
    }

    private static boolean onKeyNear(@NonNull AudioParams clip, long localMs) {
        for (VolumeKeyframe kf : clip.getVolumeKeyframes()) {
            if (Math.abs(kf.timeMs - localMs) <= KEY_TOLERANCE_MS) return true;
        }
        return false;
    }

    /** Hollow-diamond tap: drop a key AT the current audible level (then drag the slider). */
    private static void dropKeyHere(@NonNull AudioParams clip, @NonNull Host host,
                                    @Nullable Runnable refresh) {
        long local = localMs(clip, host.playheadMs());
        if (local < 0 || local > clip.getTrimmedDurationMs()) return; // span query guards first
        List<VolumeKeyframe> before = copyKeyframes(clip);
        clip.addOrUpdateVolumeKeyframe(local, multiplierFor(clip, levelAt(clip, local)));
        List<VolumeKeyframe> after = copyKeyframes(clip);
        host.recordUndo("Envelope point",
                () -> { clip.setVolumeKeyframes(after); notifyChanged(host, refresh); },
                () -> { clip.setVolumeKeyframes(before); notifyChanged(host, refresh); });
        host.onChanged();
    }

    /** Solid-diamond ×: remove THIS key (the nearest within tolerance). */
    private static void deleteKeyHere(@NonNull AudioParams clip, @NonNull Host host,
                                      @Nullable Runnable refresh) {
        long local = localMs(clip, host.playheadMs());
        List<VolumeKeyframe> before = copyKeyframes(clip);
        boolean removed = clip.getVolumeKeyframes()
                .removeIf(kf -> Math.abs(kf.timeMs - local) <= KEY_TOLERANCE_MS);
        if (!removed) return;
        List<VolumeKeyframe> after = copyKeyframes(clip);
        host.recordUndo("Delete envelope point",
                () -> { clip.setVolumeKeyframes(after); notifyChanged(host, refresh); },
                () -> { clip.setVolumeKeyframes(before); notifyChanged(host, refresh); });
        host.onChanged();
    }

    /** ‹ › : seek the playhead to the nearest key strictly before / after it. */
    private static void nudgeToKey(@NonNull AudioParams clip, @NonNull Host host, int dir) {
        long local = localMs(clip, host.playheadMs());
        VolumeKeyframe best = null;
        long bestDelta = Long.MAX_VALUE;
        for (VolumeKeyframe kf : clip.getVolumeKeyframes()) {
            long delta = dir > 0 ? kf.timeMs - local : local - kf.timeMs;
            if (delta > 0 && delta < bestDelta) { bestDelta = delta; best = kf; }
        }
        if (best != null) host.seekTo(clip.getOffsetMs() + best.timeMs);
    }

    private static void notifyChanged(@NonNull Host host, @Nullable Runnable refresh) {
        host.onChanged();
        if (refresh != null) refresh.run();
    }

    // ── undo commits (one step per gesture) ──────────────────────────────────────────────

    private static void commitLevelGesture(@NonNull AudioParams clip, @NonNull Host host,
                                           @Nullable List<VolumeKeyframe> beforeKfs,
                                           float beforeVol, @Nullable Runnable refresh) {
        List<VolumeKeyframe> afterKfs = copyKeyframes(clip);
        float afterVol = clip.getVolumeLevel();
        if (beforeKfs == null || (sameEnvelope(beforeKfs, afterKfs) && beforeVol == afterVol)) {
            return;
        }
        host.recordUndo("Audio level",
                () -> { restoreEnvelope(clip, afterKfs, afterVol); notifyChanged(host, refresh); },
                () -> { restoreEnvelope(clip, beforeKfs, beforeVol); notifyChanged(host, refresh); });
    }

    private static void commitFadeGesture(@NonNull AudioParams clip, @NonNull Host host,
                                          boolean fadeIn,
                                          @Nullable List<VolumeKeyframe> beforeKfs,
                                          @Nullable Runnable refresh) {
        if (beforeKfs == null) return;
        List<VolumeKeyframe> afterKfs = copyKeyframes(clip);
        if (sameEnvelope(beforeKfs, afterKfs)) return;
        String label = fadeIn ? "Fade in" : "Fade out";                    // TODO(strings)
        host.recordUndo(label,
                () -> { clip.setVolumeKeyframes(afterKfs); notifyChanged(host, refresh); },
                () -> { clip.setVolumeKeyframes(beforeKfs); notifyChanged(host, refresh); });
    }

    // ── type-an-exact-value dialogs ──────────────────────────────────────────────────────

    private static void promptForGain(@NonNull Context ctx, @NonNull AudioParams clip,
                                      @NonNull Host host, @Nullable Runnable refresh) {
        float[] pad = dialogPadding(ctx);
        EditText input = new EditText(ctx);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(trimNumber(levelAt(clip, localMs(clip, host.playheadMs())) * 100f));
        input.setSelectAllOnFocus(true);
        input.setHint("0 … 200");                                          // TODO(strings)
        LinearLayout wrap = column(ctx);
        wrap.setPadding((int) pad[0], (int) pad[1], (int) pad[0], 0);
        wrap.addView(input);

        confirmDialog(ctx, "Level (%)", wrap, () -> {                      // TODO(strings)
            Float typed = leadingNumber(input.getText().toString());
            if (typed == null) return;
            List<VolumeKeyframe> before = copyKeyframes(clip);
            float beforeVol = clip.getVolumeLevel();
            writeLevel(clip, host, Math.max(0f, Math.min(2f, typed / 100f)),
                    localMs(clip, host.playheadMs()));
            commitLevelGesture(clip, host, before, beforeVol, refresh);
            notifyChanged(host, refresh);
        });
        input.requestFocus();
    }

    private static void promptForPan(@NonNull Context ctx, @NonNull AudioParams clip,
                                     @NonNull Host host, @Nullable Runnable refresh) {
        float[] pad = dialogPadding(ctx);
        EditText input = new EditText(ctx);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(trimNumber(panAt(clip) * 100f));
        input.setSelectAllOnFocus(true);
        input.setHint("-100 … 100");                                         // TODO(strings)
        LinearLayout wrap = column(ctx);
        wrap.setPadding((int) pad[0], (int) pad[1], (int) pad[0], 0);
        wrap.addView(input);

        confirmDialog(ctx, "Pan (%)", wrap, () -> {                          // TODO(strings)
            Float typed = leadingNumber(input.getText().toString());
            if (typed == null) return;
            float was = panAt(clip);
            float now = Math.max(-1f, Math.min(1f, typed / 100f));
            if (was == now) return;
            writePan(clip, host, now);
            // Rule 7: a typed value is a mutation like a drag — it gets an undo step.
            host.recordUndo("Pan",
                    () -> { clip.setPan(now); host.onChanged(); },
                    () -> { clip.setPan(was); host.onChanged(); });
            host.onChanged();
            if (refresh != null) refresh.run();
        });
        input.requestFocus();
    }

private static void promptForFadeSeconds(@NonNull Context ctx, @NonNull AudioClip clip,
                                         @NonNull Host host, boolean fadeIn,
                                         long maxFade, @Nullable Runnable refresh) {
        float[] pad = dialogPadding(ctx);
        EditText input = new EditText(ctx);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(trimNumber(readFade(clip, fadeIn) / 1000f));
        input.setSelectAllOnFocus(true);
        input.setHint("0 … " + trimNumber(maxFade / 1000f));               // TODO(strings)
        LinearLayout wrap = column(ctx);
        wrap.setPadding((int) pad[0], (int) pad[1], (int) pad[0], 0);
        wrap.addView(input);

        confirmDialog(ctx, fadeIn ? "Fade in (s)" : "Fade out (s)", wrap, () -> {
            Float typed = leadingNumber(input.getText().toString());       // TODO(strings)
            if (typed == null) return;
            List<VolumeKeyframe> before = copyKeyframes(clip);
            long ms = Math.round(Math.max(0f, Math.min(maxFade / 1000f, typed)) * 1000f);
            setFade(clip, fadeIn, ms);
            commitFadeGesture(clip, host, fadeIn, before, refresh);
            notifyChanged(host, refresh);
        });
        input.requestFocus();
    }

    private static void confirmDialog(@NonNull Context ctx, @NonNull String title,
                                      @NonNull View body, @NonNull Runnable onOk) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> onOk.run())
                .show();
    }

    /** The {h, v} dialog-content padding, in px. */
    private static float[] dialogPadding(@NonNull Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return new float[]{20 * d, 8 * d};
    }

    // ── formatting ───────────────────────────────────────────────────────────────────────

    @NonNull
    private static String fmtGain(float gain) {
        return Math.round(gain * 100f) + "%";
    }

    @NonNull
    private static String fmtPan(float pan) {
        if (pan == 0f) return "C";
        return (pan > 0 ? "R" : "L") + Math.round(Math.abs(pan) * 100f) + "%";
    }

    @NonNull
    private static String fmtMs(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format(java.util.Locale.US, "%.2fs", ms / 1000f);
    }

    /** "500" not "500.0"; keeps decimals only when they carry information. */
    @NonNull
    private static String trimNumber(float v) {
        if (Math.abs(v - Math.round(v)) < 0.005f) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** The first number in {@code s}, or null (same reader as PipDrawerTabs). */
    @Nullable
    private static Float leadingNumber(@NonNull String s) {
        int i = 0, n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        int start = i;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        boolean digits = false, dot = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { digits = true; i++; }
            else if (c == '.' && !dot) { dot = true; i++; }
            else break;
        }
        if (!digits) return null;
        try {
            return Float.parseFloat(s.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
