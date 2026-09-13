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
 *       ({@link #addTo} / {@link #buildGlobal(boolean)});
 *       export snapshots it once per composition build (a service must never consult a
 *       UI static), preview's wiring layer flips it live on the mounted chain so A/B is
 *       audible without a rebuild.</li>
 *   <li><b>Gain reduction (C6)</b> — flows engine → caller via
 *       {@link FxChain#getGainReductionDb()}; the UI polls it from its own side.</li>
 * </ul>
 */
public final class AudioFxChainFactory {

    private AudioFxChainFactory() {}

    /** Back-compatible form: no voice chain, 48 kHz. See the five-argument form. */
    public static void addTo(@NonNull List<AudioProcessor> out, @Nullable AudioParams clip,
                             boolean bypassed) {
        addTo(out, clip, bypassed, false, 48000);
    }

    /**
     * Master-spine form, with the same opt-in the lane path uses.
     *
     * @param applyVoiceChain the user's "process my audio" intent. This path applied the voice
     *        chain UNCONDITIONALLY, exactly as the lane path did — so a video of music, a
     *        concert, or any ambient footage had a gate and a de-esser across it on every
     *        export, with no control but the A/B bypass. Fixing only the lane path would have
     *        left the video's own audio still being processed without being asked.
     * @param sampleRate the PROJECT rate. This was hardcoded to 48000, so in a 44.1 kHz project
     *        every filter in the chain sat about 9% off its intended frequency — the 60 Hz hum
     *        notch landing near 55 Hz, which is the difference between removing hum and not.
     */
    public static void addTo(@NonNull List<AudioProcessor> out, @Nullable AudioParams clip,
                             boolean bypassed, boolean applyVoiceChain, int sampleRate) {
        if (!applyVoiceChain) return;
        if (clip != null && clip.isMuted()) return;
        out.add(FxChain.createVoiceChain(sampleRate > 0 ? sampleRate : 48000, bypassed));
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
    /**
     * Back-compatible overload: resample and gain only, NO voice processing.
     *
     * <p>This is the safe default on purpose. See the {@code applyVoiceChain} parameter on the
     * five-argument form for why leaving it on for everything was wrong.</p>
     */
    @NonNull
    public static List<AudioProcessor> buildLaneChain(@NonNull AudioParams clip,
                                                      int sourceSampleRate,
                                                      int projectSampleRate,
                                                      boolean bypassed) {
        return buildLaneChain(clip, sourceSampleRate, projectSampleRate, bypassed, false);
    }

    /**
     * @param applyVoiceChain run the speech processing chain (de-hum, low cut, gate, presence
     *        boost, compressor, de-esser, limiter) over this clip.
     *
     *        <p><b>Opt-in since 2026-08-24, and it must stay that way.</b> This chain used to be
     *        appended to EVERY audio clip in both preview and export, with no user control at
     *        all except the A/B bypass toggle. That is fine for a voice recording and actively
     *        destructive for anything else: the GATE chops quiet passages and reverb tails out
     *        of music, the DE-ESSER dulls cymbals and hi-hats, and the presence boost at 3 kHz
     *        is a vocal shape nobody asked to put on a song. Measured on a clean 800 Hz tone
     *        with no hum present, the chain alone took it to x0.605 — a 4.4 dB change to audio
     *        the user never asked to process.</p>
     *
     *        <p>Found by asking what the chain CONTAINS for a plain clip rather than whether
     *        each processor works: every processor was individually correct and the composition
     *        was wrong. Callers now pass the user's expressed intent — today that is the
     *        project's "Clean Audio" setting, the only control that means "process my audio".
     *        A per-clip FX toggle (§2's Clean/Tone/FX tabs) should replace it when it exists.</p>
     */
    @NonNull
    public static List<AudioProcessor> buildLaneChain(@NonNull AudioParams clip,
                                                      int sourceSampleRate,
                                                      int projectSampleRate,
                                                      boolean bypassed,
                                                      boolean applyVoiceChain) {
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
        // THE GATE IS GONE, AND IT SHOULD NOT COME BACK.
        //
        // It has been wrong twice. First it read `volumeAdjusted` alone, so a PAN-ONLY clip
        // (unity volume, no envelope) dropped the whole processor and its pan vanished from
        // preview AND export — caught by LaneChainParityTest. Pan was added to the condition.
        //
        // JoyRaptor, 2026-09-13: "fade handles don't seem to be fading the audio." Same fault,
        // third channel. An audio fade IS a volume envelope (AudioClip.setFadeInMs writes the
        // keyframe pair), but the chain is built ONCE when the preview player is created, and
        // refreshLaneChain can only push new values into a processor that is already mounted.
        // So a clip that was at unity with no envelope when its player was built — his was, and
        // a second sat at 0.9958, inside the 0.01 dead-band — had no processor to refresh, and
        // every fade drawn on it afterwards was inaudible. Nothing in the model was wrong; there
        // was simply nothing listening.
        //
        // Each fix widened the condition by one channel and left the next one waiting. The
        // condition is the bug: it asks what the clip looks like NOW to decide what it may ever
        // become. At unity gain, no envelope and centre pan this processor is a multiply by one,
        // and it is the same object export mounts, so always mounting it changes no output —
        // only whether a later edit can be heard.
        out.add(vp);
        if (applyVoiceChain && !bypassed) {
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
