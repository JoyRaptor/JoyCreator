package com.fadcam.ui.faditor.audio.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.audio.AudioProcessor;

import com.fadcam.ui.faditor.model.AudioParams;

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
}
