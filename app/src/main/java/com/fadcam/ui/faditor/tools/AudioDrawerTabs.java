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

import java.util.ArrayList;
import java.util.List;

/**
 * Content for {@link ObjectDrawer}'s AUDIO tabs (SPEC_AUDIO_UX_V1 §2.1:
 * Level · Clean · Tone · FX). Built like {@link PipDrawerTabs} — a {@link Host}
 * interface back to the editor, static ContentBuilder methods returning a View, COMPACT
 * one-line rows — so {@code ObjectDrawer} stays chrome and knows nothing about audio.
 *
 * <p><b>SCOPED (2026-08-23): the Level and Clean tabs.</b> Tone/FX still need C1.E's
 * real-time chain, which does not exist; §0 rule 6 forbids shipping their sliders ahead of
 * it. Within Level, exactly three controls ship because exactly three are real today:</p>
 * <ul>
 *   <li><b>Level</b> — flat gain ({@code volumeLevel}) or, once the envelope is armed,
 *       the envelope point under the playhead ({@code gainAtClipMs});</li>
 *   <li><b>The volume envelope</b> — the diamond drops/deletes/jumps keys; fades ride
 *       the same envelope;</li>
 *   <li><b>Fade in / Fade out</b> — durations written through
 *       {@link AudioClip#getFadeInMs()}/{@link AudioClip#setFadeInMs(long)} and the
 *       fade-out mirror, THE single definition of a fade, matching what the timeline's
 *       fade drag handle writes.</li>
 * </ul>
 *
 * <p>Pan (A5.E) and “+ Cross-fade” (B2.E) are left OUT entirely — no disabled
 * placeholders. Compressor/limiter/normalize-peak also wait for C1.E.</p>
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
        LinearLayout root = column(ctx);
        final List<Runnable> refreshers = new ArrayList<>();
        refreshers.add(levelRow(ctx, root, clip, host));
        refreshers.add(envelopeStateRow(ctx, root, clip, host));
        // A zero-length clip has nothing to fade — omit the rows rather than show dead ones.
        if (clip.getTrimmedDurationMs() > 0) {
            refreshers.add(fadeRow(ctx, root, "Fade in", true, clip, host));   // TODO(strings)
            refreshers.add(fadeRow(ctx, root, "Fade out", false, clip, host)); // TODO(strings)
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
                                     @NonNull AudioClip clip, @NonNull Host host) {
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
            @Nullable List<AudioClip.VolumeKeyframe> beforeKfs;
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
     * The envelope diamond's brain. The envelope is LINEAR-only (no easing segments), so
     * the ease picker gets null and shows its disabled hint rather than pretending; ‹ ›
     * walk existing keys by seeking the playhead to them.
     */
    @NonNull
    private static ObjectMenuSheet.Prop volumeProp(@NonNull AudioClip clip, @NonNull Host host,
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
                                             @NonNull AudioClip clip, @NonNull Host host) {
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
            List<AudioClip.VolumeKeyframe> before = copyKeyframes(clip);
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
    private static Runnable fadeRow(@NonNull Context ctx, @NonNull LinearLayout parent,
                                    @NonNull String label, boolean fadeIn,
                                    @NonNull AudioClip clip, @NonNull Host host) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(2 * d), 0, Math.round(2 * d));

        TextView labelView = new TextView(ctx);
        labelView.setTextColor(TXT_DIM);
        labelView.setTextSize(11);
        labelView.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        labelView.setWidth(Math.round(52 * d));
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
        value.setWidth(Math.round(46 * d));
        value.setGravity(Gravity.END);

        final Runnable[] selfRefresh = new Runnable[1];
        final Runnable refreshAll = () -> {
            if (selfRefresh[0] != null) selfRefresh[0].run();
        };

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Nullable List<AudioClip.VolumeKeyframe> beforeKfs;

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
        parent.addView(row);

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
    @NonNull
    public static View cleanTab(@NonNull Context ctx, @NonNull AudioClip clip,
                                @NonNull Host host, @NonNull java.io.File projectDir,
                                @NonNull com.fadcam.ui.faditor.audio.BakedAudioCache cache) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = column(ctx);
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] baking = {false};

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
    private static long localMs(@NonNull AudioClip clip, long timelineMs) {
        return timelineMs - clip.getOffsetMs();
    }

    /**
     * The audible level at a clip-local time: the envelope when armed (it OVERRIDES the
     * whole-clip gain, both in preview and at export), else the flat gain.
     */
    private static float levelAt(@NonNull AudioClip clip, long localMs) {
        return clip.gainAtClipMs(localMs);
    }

    /**
     * Route a slider/value write to whatever is LIVE: an envelope point under the playhead
     * when the envelope is armed (clamped to the clip — a key outside it could never be
     * heard or seen), else the whole-clip flat gain. {@code gain} is the FINAL audible
     * gain the user sees on the row; B1.Q stores it as a MULTIPLIER over volumeLevel, so
     * moving the slider later rescales the whole envelope instead of stranding stale peaks.
     */
    private static void writeLevel(@NonNull AudioClip clip, @NonNull Host host,
                                   float gain, long localMs) {
        if (clip.hasVolumeKeyframes()
                && localMs >= 0 && localMs <= clip.getTrimmedDurationMs()) {
            clip.addOrUpdateVolumeKeyframe(localMs, multiplierFor(clip, gain));
        } else {
            clip.setVolumeLevel(gain);
        }
    }

    /** Desired FINAL gain → stored envelope multiplier (B1.Q). Silent clip → 0. */
    private static float multiplierFor(@NonNull AudioClip clip, float finalGain) {
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

    private static long fadeMsOf(int progress, long maxFade) {
        return Math.round(Math.max(0, Math.min(SLIDER_STEPS, progress))
                / (float) SLIDER_STEPS * maxFade);
    }

    @NonNull
    private static List<AudioClip.VolumeKeyframe> copyKeyframes(@NonNull AudioClip clip) {
        List<AudioClip.VolumeKeyframe> out = new ArrayList<>();
        for (AudioClip.VolumeKeyframe kf : clip.getVolumeKeyframes()) {
            out.add(new AudioClip.VolumeKeyframe(kf.timeMs, kf.volume));
        }
        return out;
    }

    private static boolean sameEnvelope(@Nullable List<AudioClip.VolumeKeyframe> a,
                                        @NonNull List<AudioClip.VolumeKeyframe> b) {
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

    private static void restoreEnvelope(@NonNull AudioClip clip,
                                        @NonNull List<AudioClip.VolumeKeyframe> kfs,
                                        float volumeLevel) {
        clip.setVolumeKeyframes(kfs);
        clip.setVolumeLevel(volumeLevel);
    }

    private static boolean onKeyNear(@NonNull AudioClip clip, long localMs) {
        for (AudioClip.VolumeKeyframe kf : clip.getVolumeKeyframes()) {
            if (Math.abs(kf.timeMs - localMs) <= KEY_TOLERANCE_MS) return true;
        }
        return false;
    }

    /** Hollow-diamond tap: drop a key AT the current audible level (then drag the slider). */
    private static void dropKeyHere(@NonNull AudioClip clip, @NonNull Host host,
                                    @Nullable Runnable refresh) {
        long local = localMs(clip, host.playheadMs());
        if (local < 0 || local > clip.getTrimmedDurationMs()) return; // span query guards first
        List<AudioClip.VolumeKeyframe> before = copyKeyframes(clip);
        clip.addOrUpdateVolumeKeyframe(local, multiplierFor(clip, levelAt(clip, local)));
        List<AudioClip.VolumeKeyframe> after = copyKeyframes(clip);
        host.recordUndo("Envelope point",
                () -> { clip.setVolumeKeyframes(after); notifyChanged(host, refresh); },
                () -> { clip.setVolumeKeyframes(before); notifyChanged(host, refresh); });
        host.onChanged();
    }

    /** Solid-diamond ×: remove THIS key (the nearest within tolerance). */
    private static void deleteKeyHere(@NonNull AudioClip clip, @NonNull Host host,
                                      @Nullable Runnable refresh) {
        long local = localMs(clip, host.playheadMs());
        List<AudioClip.VolumeKeyframe> before = copyKeyframes(clip);
        boolean removed = clip.getVolumeKeyframes()
                .removeIf(kf -> Math.abs(kf.timeMs - local) <= KEY_TOLERANCE_MS);
        if (!removed) return;
        List<AudioClip.VolumeKeyframe> after = copyKeyframes(clip);
        host.recordUndo("Delete envelope point",
                () -> { clip.setVolumeKeyframes(after); notifyChanged(host, refresh); },
                () -> { clip.setVolumeKeyframes(before); notifyChanged(host, refresh); });
        host.onChanged();
    }

    /** ‹ › : seek the playhead to the nearest key strictly before / after it. */
    private static void nudgeToKey(@NonNull AudioClip clip, @NonNull Host host, int dir) {
        long local = localMs(clip, host.playheadMs());
        AudioClip.VolumeKeyframe best = null;
        long bestDelta = Long.MAX_VALUE;
        for (AudioClip.VolumeKeyframe kf : clip.getVolumeKeyframes()) {
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

    private static void commitLevelGesture(@NonNull AudioClip clip, @NonNull Host host,
                                           @Nullable List<AudioClip.VolumeKeyframe> beforeKfs,
                                           float beforeVol, @Nullable Runnable refresh) {
        List<AudioClip.VolumeKeyframe> afterKfs = copyKeyframes(clip);
        float afterVol = clip.getVolumeLevel();
        if (beforeKfs == null || (sameEnvelope(beforeKfs, afterKfs) && beforeVol == afterVol)) {
            return;
        }
        host.recordUndo("Audio level",
                () -> { restoreEnvelope(clip, afterKfs, afterVol); notifyChanged(host, refresh); },
                () -> { restoreEnvelope(clip, beforeKfs, beforeVol); notifyChanged(host, refresh); });
    }

    private static void commitFadeGesture(@NonNull AudioClip clip, @NonNull Host host,
                                          boolean fadeIn,
                                          @Nullable List<AudioClip.VolumeKeyframe> beforeKfs,
                                          @Nullable Runnable refresh) {
        if (beforeKfs == null) return;
        List<AudioClip.VolumeKeyframe> afterKfs = copyKeyframes(clip);
        if (sameEnvelope(beforeKfs, afterKfs)) return;
        String label = fadeIn ? "Fade in" : "Fade out";                    // TODO(strings)
        host.recordUndo(label,
                () -> { clip.setVolumeKeyframes(afterKfs); notifyChanged(host, refresh); },
                () -> { clip.setVolumeKeyframes(beforeKfs); notifyChanged(host, refresh); });
    }

    // ── type-an-exact-value dialogs ──────────────────────────────────────────────────────

    private static void promptForGain(@NonNull Context ctx, @NonNull AudioClip clip,
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
            List<AudioClip.VolumeKeyframe> before = copyKeyframes(clip);
            float beforeVol = clip.getVolumeLevel();
            writeLevel(clip, host, Math.max(0f, Math.min(2f, typed / 100f)),
                    localMs(clip, host.playheadMs()));
            commitLevelGesture(clip, host, before, beforeVol, refresh);
            notifyChanged(host, refresh);
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
            List<AudioClip.VolumeKeyframe> before = copyKeyframes(clip);
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
