import androidx.media3.common.audio.AudioProcessor;

import com.fadcam.ui.faditor.audio.fx.AudioFxChainFactory;
import com.fadcam.ui.faditor.model.AudioClip;

import java.util.List;

/**
 * {@code AudioFxChainFactory.buildLaneChain} — the single place preview and export agree on
 * what happens to a clip's audio.
 *
 * <p>This asks a CONNECTION question rather than a correctness one, which is the kind that has
 * actually found bugs here. Every processor in this codebase can be individually perfect while
 * the chain that should contain it does not — de-hum was absent from the voice chain entirely,
 * and A6's resampler was constructed by nothing at all, both while their own tests passed.</p>
 *
 * <p>So: given a clip with particular settings, does the chain built for it CONTAIN what those
 * settings require, and — just as important — does a clip that needs nothing get nothing? A
 * chain that always inserts every processor is not free; it is a signal path the audio did not
 * ask to be dragged through.</p>
 */
public class LaneChainTest {
    static int fails = 0, total = 0;

    static void check(boolean c, String n) {
        total++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final int SR = 48000;

    static AudioClip clip() {
        return new AudioClip((android.net.Uri) null, 6000);
    }

    static boolean has(List<AudioProcessor> chain, String simpleName) {
        for (AudioProcessor p : chain) {
            if (p.getClass().getSimpleName().equals(simpleName)) return true;
        }
        return false;
    }

    static String names(List<AudioProcessor> chain) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(chain.get(i).getClass().getSimpleName());
        }
        return b.append("]").toString();
    }

    static int indexOf(List<AudioProcessor> chain, String simpleName) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).getClass().getSimpleName().equals(simpleName)) return i;
        }
        return -1;
    }

    public static void main(String[] args) {

        // ── 1. A plain clip that needs nothing ─────────────────────────────────────
        AudioClip plain = clip();
        List<AudioProcessor> cPlain = AudioFxChainFactory.buildLaneChain(plain, SR, SR, false);
        System.out.println("      plain clip        -> " + names(cPlain));
        check(!has(cPlain, "ResamplingAudioProcessor"),
                "a clip already at the project rate gets NO resampler");

        // ── 2. Sample-rate mismatch MUST insert the resampler ──────────────────────
        // This is the A6 failure written as an assertion: the processor existed, worked, and
        // was inserted by nothing, so exports were silently unresampled for hours.
        AudioClip needsRs = clip();
        List<AudioProcessor> cRs = AudioFxChainFactory.buildLaneChain(needsRs, 44100, 48000, false);
        System.out.println("      44100 -> 48000    -> " + names(cRs));
        check(has(cRs, "ResamplingAudioProcessor"),
                "a rate mismatch DOES insert the resampler (the A6 regression guard)");

        // Order matters: resampling must happen BEFORE gain math, or the envelope's
        // sample positions refer to a different timebase than the samples they land on.
        int iRs = indexOf(cRs, "ResamplingAudioProcessor");
        int iVol = indexOf(cRs, "VolumeAudioProcessor");
        check(iRs >= 0 && (iVol < 0 || iRs < iVol),
                "the resampler comes BEFORE gain (rs@" + iRs + " vol@" + iVol + ")");

        // ── 3. Unknown source rate must not guess ─────────────────────────────────
        AudioClip unknown = clip();
        check(!has(AudioFxChainFactory.buildLaneChain(unknown, 0, 48000, false),
                        "ResamplingAudioProcessor"),
                "an UNKNOWN source rate inserts no resampler rather than guessing");
        check(!has(AudioFxChainFactory.buildLaneChain(unknown, 44100, 0, false),
                        "ResamplingAudioProcessor"),
                "an unknown PROJECT rate likewise inserts nothing");

        // ── 4. A muted clip gets an EMPTY chain ───────────────────────────────────
        // Silence is cheaper than silence-through-six-processors, and it is also the only
        // honest answer: nothing downstream can matter.
        AudioClip muted = clip();
        muted.setMuted(true);
        List<AudioProcessor> cMuted = AudioFxChainFactory.buildLaneChain(muted, 44100, 48000, false);
        System.out.println("      muted clip        -> " + names(cMuted));
        check(cMuted.isEmpty(), "a MUTED clip gets an empty chain (" + cMuted.size() + ")");

        // ── 5. Pan and envelope reach the chain ───────────────────────────────────
        AudioClip panned = clip();
        panned.setPan(-0.8f);
        List<AudioProcessor> cPan = AudioFxChainFactory.buildLaneChain(panned, SR, SR, false);
        System.out.println("      panned clip       -> " + names(cPan));
        check(has(cPan, "VolumeAudioProcessor"),
                "a panned clip gets the processor that applies pan");

        AudioClip env = clip();
        env.addOrUpdateVolumeKeyframe(0, 0f);
        env.addOrUpdateVolumeKeyframe(1000, 1f);
        List<AudioProcessor> cEnv = AudioFxChainFactory.buildLaneChain(env, SR, SR, false);
        System.out.println("      envelope clip     -> " + names(cEnv));
        check(has(cEnv, "VolumeAudioProcessor"),
                "a clip with a volume envelope gets the processor that applies it");
        check(env.hasVolumeKeyframes(), "and the model still reports its keyframes");

        // ── 6. Bypass is a chain-level decision, not a per-processor one ───────────
        AudioClip byp = clip();
        byp.setPan(-0.8f);
        List<AudioProcessor> cOn = AudioFxChainFactory.buildLaneChain(byp, 44100, 48000, false);
        List<AudioProcessor> cOff = AudioFxChainFactory.buildLaneChain(byp, 44100, 48000, true);
        System.out.println("      bypass=false      -> " + names(cOn));
        System.out.println("      bypass=true       -> " + names(cOff));
        // Whatever bypass does to the FX portion, the RESAMPLER must survive it. Bypassing
        // effects must never change the sample rate of the export — that would turn an A/B
        // comparison into a pitch shift.
        check(has(cOff, "ResamplingAudioProcessor"),
                "bypass does NOT remove the resampler (A/B must not become a pitch shift)");

        // ── NEGATIVE CONTROLS ─────────────────────────────────────────────────────
        // The `has` helper must be able to say no, or every check above is decoration.
        check(!has(cPlain, "NoSuchProcessorXYZ"),
                "NEGCTRL: the chain matcher correctly reports an invented processor absent");
        check(!cRs.isEmpty(), "NEGCTRL: a non-muted clip's chain is not empty");
        // And a rate mismatch must differ from a match, or check 2 passes on a factory that
        // always inserts a resampler.
        check(has(cRs, "ResamplingAudioProcessor") && !has(cPlain, "ResamplingAudioProcessor"),
                "NEGCTRL: the resampler is CONDITIONAL, not always inserted");

        // ── 7. THE VOICE CHAIN IS OPT-IN ──────────────────────────────────────────
        // Until 2026-08-24 this chain was appended to EVERY audio clip in preview and
        // export, with no control but the A/B bypass. Fine for a voice recording, actively
        // destructive for anything else: the gate chops quiet passages and reverb tails out
        // of music, and the de-esser dulls cymbals. Nobody asked for that on a song.
        AudioClip music = clip();
        List<AudioProcessor> noFx = AudioFxChainFactory.buildLaneChain(music, SR, SR, false, false);
        List<AudioProcessor> withFx = AudioFxChainFactory.buildLaneChain(music, SR, SR, false, true);
        System.out.println("      applyVoiceChain=false -> " + names(noFx));
        System.out.println("      applyVoiceChain=true  -> " + names(withFx));
        check(!has(noFx, "FxChain"),
                "a clip that did NOT ask for processing gets no voice chain");
        check(has(withFx, "FxChain"),
                "a clip that DID ask for it gets one");
        check(!has(AudioFxChainFactory.buildLaneChain(music, SR, SR, false), "FxChain"),
                "the 4-arg overload defaults to NO voice chain (safe default)");
        // Bypass must still win over an explicit request, or the A/B button lies.
        check(!has(AudioFxChainFactory.buildLaneChain(music, SR, SR, true, true), "FxChain"),
                "bypass still wins over an explicit request");

        System.out.println(fails == 0 ? ("ALL GREEN (" + total + "/" + total + ")")
                : (fails + " FAILED of " + total));
        if (fails != 0) System.exit(1);
    }
}
