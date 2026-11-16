import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import com.fadcam.ui.faditor.audio.fx.AudioFxChainFactory;
import com.fadcam.ui.faditor.compositor.PreviewMuteGate;
import com.fadcam.ui.faditor.compositor.PreviewStreamClock;
import com.fadcam.ui.faditor.export.VolumeAudioProcessor;
import com.fadcam.ui.faditor.model.AudioClip;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * The editor's sound preview: mute, and a fade that keeps its place (Codex round 2: R2-AUD-02, W1).
 *
 * <p>Off device the player itself cannot run, so this checks the three pieces it is now made of:
 * the mute gate, the chain it mounts, and the clock that tells the fade where playback resumed.
 * The listening check is on the phone (STATE row).</p>
 */
public class PreviewAudioTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static int firstSampleAfter(VolumeAudioProcessor p, int frames) {
        short[] in = new short[frames];
        java.util.Arrays.fill(in, (short) 10000);
        ByteBuffer b = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder());
        b.asShortBuffer().put(in);
        p.queueInput(b);
        return p.getOutput().order(ByteOrder.nativeOrder()).asShortBuffer().get(0);
    }

    static VolumeAudioProcessor fadeIn() throws Exception {
        VolumeAudioProcessor p = new VolumeAudioProcessor();
        p.setVolume(1f);
        p.setVolumeEnvelope(new long[]{0L, 1000L}, new float[]{0f, 1f});
        p.configure(new AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT));
        p.flush();
        return p;
    }

    public static void main(String[] a) throws Exception {
        // ── R2-AUD-02: the mute gate ──────────────────────────────────────────
        check(PreviewMuteGate.playerGain(true, 1f, 1f) == 0f, "a muted clip is shut even at full level");
        check(PreviewMuteGate.playerGain(false, 0f, 0f) == 0f, "effective level 0 (its lane muted) is shut");
        check(PreviewMuteGate.playerGain(false, 0.7f, 0.7f) == 1f, "a sounding clip is open (the processors set the level)");
        check(PreviewMuteGate.playerGain(false, 1.6f, 1.6f) == 1f, "a boosted clip is open, not boosted twice");
        check(PreviewMuteGate.playerGain(false, Float.NaN, Float.NaN) == 0f, "a broken level is shut, not loud");

        // A clip muted when the editor opens still gets its level processor in PREVIEW, so unmuting
        // it brings back its level and fades; export still drops a muted clip.
        AudioClip muted = new AudioClip((android.net.Uri) null, 6000);
        muted.setMuted(true);
        List<AudioProcessor> preview = AudioFxChainFactory.buildPreviewLaneChain(muted, 44100, 44100, false, false);
        boolean hasVol = false;
        for (AudioProcessor p : preview) hasVol |= p instanceof VolumeAudioProcessor;
        check(hasVol, "preview chain of a muted clip still holds its level processor");
        check(AudioFxChainFactory.buildLaneChain(muted, 44100, 44100, false, false).isEmpty(),
                "export chain of a muted clip is still empty (export unchanged)");

        // ── W1: a fade keeps its place across a flush ───────────────────────
        // Without a clock (export): after a flush the next input is the item's start, as before.
        VolumeAudioProcessor exp = fadeIn();
        firstSampleAfter(exp, 600);
        exp.flush();
        check(Math.abs(firstSampleAfter(exp, 1)) <= 60, "export (no clock): a flush restarts at the item's start, as before");

        // With the preview clock: seek to 500 ms; the fade resumes half way, not from silence.
        PreviewStreamClock clock = new PreviewStreamClock();
        VolumeAudioProcessor pv = fadeIn();
        pv.setStreamClock(clock);
        long offset = 1_000_000_000_000L;                 // the player's own constant in buffer times
        clock.onBuffer(offset);                            // the first buffer is the clip's start
        int s0 = firstSampleAfter(pv, 100);
        check(Math.abs(s0) <= 60, "preview: silent at the start of a fade-in (" + s0 + ")");
        clock.seekingTo(500_000L);
        clock.onSinkFlush();
        pv.flush();
        clock.onBuffer(offset + 500_000L + 7);             // the sink's first buffer after the seek
        int s1 = firstSampleAfter(pv, 1);
        check(Math.abs(s1 - 5000) <= 60, "preview: after a seek to 500 ms the fade is half way (" + s1 + ")");

        // Drift correction: Media3 flushes the processors to apply a speed nudge, with no seek.
        clock.onBuffer(offset + 800_000L);
        pv.flush();                                        // no onSinkFlush: not a seek
        int s2 = firstSampleAfter(pv, 1);
        check(Math.abs(s2 - 8000) <= 60, "preview: a speed nudge at 800 ms keeps the fade at 800 ms (" + s2 + ")");

        // A later seek re-learns the constant even if the player changed it.
        clock.seekingTo(200_000L);
        clock.onSinkFlush();
        pv.flush();
        clock.onBuffer(5_000_000_000_000L);
        int s3 = firstSampleAfter(pv, 1);
        check(Math.abs(s3 - 2000) <= 60, "preview: a seek back to 200 ms reads 200 ms (" + s3 + ")");

        if (fails > 0) { System.out.println(fails + " FAILED"); System.exit(1); }
        System.out.println("PreviewAudioTest: all passed");
    }
}
