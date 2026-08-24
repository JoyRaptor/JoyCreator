package com.fadcam.ui.faditor.audio.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.audio.AudioProcessor;

import com.fadcam.ui.faditor.model.AudioParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * C1.E — single authority for the real-time FX chain. BOTH preview
 * ({@code MasterPlaybackEngine}'s audio sink) and export ({@code ExportManager}'s
 * per-clip {@code Effects}) build their processors HERE, so what the user hears while
 * editing is what lands in the file — the same one-authority rule the volume/pan
 * {@code VolumeAudioProcessor} wiring already follows.
 *
 * <p>This class imports NOTHING from the UI layer: it sits in {@code faditor/audio/fx/},
 * which the off-device harness ({@code run-audio-fx.sh}) compiles against stub classes.
 * Bypass and the GR-bar feed are INJECTED settings, not reads of screen state:
 *
 * <ul>
 *   <li><b>Bypass (C7)</b> — callers pass the flag in
 *       ({@link #buildForClip(AudioParams, boolean)} / {@link #buildGlobal(boolean)});
 *       export snapshots it once per composition build (a service must never consult a
 *       UI static), preview's wiring layer flips it live on the mounted chain so A/B is
 *       audible without a rebuild.</li>
 *   <li><b>Gain reduction (C6)</b> — flows engine → caller via
 *       {@link FxChain#getGainReductionDb()}; the UI polls it from its own side.</li>
 * </ul>
 */
public final class AudioFxChainFactory {

    private AudioFxChainFactory() {}

    /**
     * Build processors for one clip: resample/speed/volume ride earlier in the caller's
     * list; this appends the FX chain. Empty when the chain would be inaudible anyway
     * (muted clip). {@code bypassed} mounts the chain anyway — it passes through at
     * stream time, so an A/B flip needs no rebuild.
     */
    @NonNull
    public static List<AudioProcessor> buildForClip(@Nullable AudioParams clip,
                                                    boolean bypassed) {
        if (clip != null && clip.isMuted()) return Collections.emptyList();
        return Collections.<AudioProcessor>singletonList(
                FxChain.createVoiceChain(48000, bypassed));
    }

    /** Convenience overload using the default (not bypassed) state. */
    @NonNull
    public static List<AudioProcessor> buildForClip(@Nullable AudioParams clip) {
        return buildForClip(clip, false);
    }

    /** Convenience: append to an existing processor list when non-empty. */
    public static void addTo(@NonNull List<AudioProcessor> out, @Nullable AudioParams clip,
                             boolean bypassed) {
        List<AudioProcessor> built = buildForClip(clip, bypassed);
        if (!built.isEmpty()) out.addAll(built);
    }

    /**
     * Chain for a whole mix without a specific clip (the master-spine preview sink).
     * Same voice chain, so preview and export cannot disagree about what "the chain" is.
     */
    @NonNull
    public static List<AudioProcessor> buildGlobal(boolean bypassed) {
        return Collections.<AudioProcessor>singletonList(
                FxChain.createVoiceChain(48000, bypassed));
    }

    // ── A9: ONE authority for an AUDIO-LANE clip's full processing chain ────────────────

    /**
     * The COMPLETE per-clip lane chain — volume/envelope/pan AND the FX chain — built ONCE
     * here and consumed by BOTH sides:
     * <ul>
     *   <li>export: {@code ExportManager.buildLaneAudioSequence} puts these into the
     *       {@code EditedMediaItem Effects};</li>
     *   <li>preview: {@code AudioClipPreviewPlayer} mounts the same list into its
     *       ExoPlayer audio sink.</li>
     * </ul>
     * Before this method existed the two sides built their chains independently, which is
     * exactly how preview ignored pan entirely and approximated the envelope by polling
     * while export applied it sample-accurately.
     *
     * @param clip            live model — callers may call {@link #refreshLaneChain} after
     *                        edits to push fresh values into the returned processors
     * @param sourceSampleRate decoded rate of the clip's source ({@code <=0} = unknown, skip)
     * @param projectSampleRate the project rate everything must land on
     * @param bypassed        C7 snapshot / live flag
     */
    @NonNull
    public static List<AudioProcessor> buildLaneChain(@NonNull AudioParams clip,
                                                      int sourceSampleRate,
                                                      int projectSampleRate,
                                                      boolean bypassed) {
        List<AudioProcessor> out = new ArrayList<>();
        if (clip.isMuted()) return out;
        // A6: resample to project rate when the source differs (BEFORE gain math).
        if (sourceSampleRate > 0 && projectSampleRate > 0
                && sourceSampleRate != projectSampleRate) {
            out.add(new com.fadcam.ui.faditor.export.ResamplingAudioProcessor(
                    sourceSampleRate, projectSampleRate));
        }
        com.fadcam.ui.faditor.export.VolumeAudioProcessor vp =
                new com.fadcam.ui.faditor.export.VolumeAudioProcessor();
        vp.setPan(clip.getPan());
        boolean volumeAdjusted = false;
        if (clip.hasVolumeKeyframes()) {
            List<? extends com.fadcam.ui.faditor.model.VolumeKeyframe> kfs =
                    clip.getVolumeKeyframes();
            long[] times = new long[kfs.size()];
            float[] vols = new float[kfs.size()];
            for (int i = 0; i < kfs.size(); i++) {
                times[i] = kfs.get(i).timeMs;
                vols[i] = kfs.get(i).volume;
            }
            // B1.Q: multipliers need their base riding along.
            vp.setVolume(clip.getVolumeLevel());
            vp.setVolumeEnvelope(times, vols);
            volumeAdjusted = true;
        } else if (Math.abs(clip.getVolumeLevel() - 1.0f) >= 0.01f) {
            vp.setVolume(clip.getVolumeLevel());
            volumeAdjusted = true;
        }
        // HARNESS-CAUGHT (LaneChainParityTest, tonight): gating on volumeAdjusted alone
        // meant a PAN-ONLY clip (unity volume, no envelope) dropped the whole processor —
        // its pan silently vanished in preview AND export. Pan is work the processor does;
        // gate on it too.
        if (volumeAdjusted || Math.abs(clip.getPan()) >= 0.001f) {
            out.add(vp);
        }
        if (!bypassed) {
            out.add(FxChain.createVoiceChain(
                    projectSampleRate > 0 ? projectSampleRate : 48000));
        }
        return out;
    }

    /**
     * Push fresh model values into a chain previously returned by {@link #buildLaneChain}.
     * Cheap; called whenever the user moves a handle mid-playback so preview tracks the
     * model without rebuilding decoders. No-op on foreign processor lists.
     *
     * @return true when the list was a lane chain and was refreshed.
     */
    public static boolean refreshLaneChain(@NonNull List<AudioProcessor> chain,
                                           @NonNull AudioParams clip) {
        for (AudioProcessor p : chain) {
            if (p instanceof com.fadcam.ui.faditor.export.VolumeAudioProcessor) {
                com.fadcam.ui.faditor.export.VolumeAudioProcessor vp =
                        (com.fadcam.ui.faditor.export.VolumeAudioProcessor) p;
                vp.setPan(clip.getPan());
                if (clip.hasVolumeKeyframes()) {
                    List<? extends com.fadcam.ui.faditor.model.VolumeKeyframe> kfs =
                            clip.getVolumeKeyframes();
                    long[] times = new long[kfs.size()];
                    float[] vols = new float[kfs.size()];
                    for (int i = 0; i < kfs.size(); i++) {
                        times[i] = kfs.get(i).timeMs;
                        vols[i] = kfs.get(i).volume;
                    }
                    vp.setVolume(clip.getVolumeLevel());
                    vp.setVolumeEnvelope(times, vols);
                } else {
                    vp.setVolumeEnvelope(null, null);
                    vp.setVolume(clip.getVolumeLevel());
                }
                return true;
            }
        }
        return false;
    }
}
