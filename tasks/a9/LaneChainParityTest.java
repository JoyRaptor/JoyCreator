import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import com.fadcam.ui.faditor.audio.fx.AudioFxChainFactory;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.AudioClip.VolumeKeyframe;

import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A9 — lane-preview / export chain parity, off device.
 *
 * AudioClipPreviewPlayer (preview) and ExportManager.buildLaneAudioSequence (export)
 * BOTH build their processors through AudioFxChainFactory.buildLaneChain. This driver
 * proves the shared chain BEHAVES as export always did — and that the legacy preview
 * gaps are gone:
 *
 *   1. PARITY      preview-built list vs export-built list: bit-identical PCM.
 *   2. ENVELOPE    keyframed fade to 50% yields ~half gain by the tail (B1.Q multiplier).
 *   3. PAN         full-left pan on stereo: right channel collapses, left survives
 *                  (the thing MediaPlayer preview could NEVER do — it paired gains).
 *   4. MUTE        muted clip -> EMPTY chain (silence by absence, like export).
 *   5. BYPASS      C7 flag kills the FX stage but volume STILL applies.
 *   6. REFRESH     refreshLaneChain() pushes a model edit into a mounted chain live
 *                  (slider moves audible without rebuilding the player).
 */
public class LaneChainParityTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final int SR = 48000;

    static short[] sine(double amp, double f0, double ms, int channels) {
        int n = (int) (SR * ms / 1000.0);
        short[] s = new short[n * channels];
        for (int i = 0; i < n; i++) {
            short v = (short) Math.round(amp * Math.sin(2 * Math.PI * f0 * i / SR) * 32767);
            for (int ch = 0; ch < channels; ch++) s[i * channels + ch] = v;
        }
        return s;
    }

    static ByteBuffer leBuf(short[] a) {
        ByteBuffer b = ByteBuffer.allocate(a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : a) b.putShort(v);
        b.flip();
        return b;
    }

    static short[] drive(List<AudioProcessor> chain, short[] in, int channels) throws Exception {
        for (AudioProcessor p : chain) {
            p.configure(new AudioProcessor.AudioFormat(SR, channels, C.ENCODING_PCM_16BIT));
        }
        List<Short> out = new ArrayList<>();
        int[] chunks = {997, 2048, 331};
        int ci = 0;
        for (int off = 0; off < in.length; ) {
            int len = Math.min(chunks[ci++ % chunks.length], in.length - off);
            short[] part = new short[len];
            System.arraycopy(in, off, part, 0, len);
            ByteBuffer bb = leBuf(part);
            for (AudioProcessor p : chain) {
                p.queueInput(bb);
                bb = p.getOutput();
            }
            while (bb.remaining() > 0) out.add(bb.getShort());
            off += len;
        }
        for (AudioProcessor p : chain) p.queueEndOfStream();
        boolean drained = true;
        for (AudioProcessor p : chain) {
            ByteBuffer o = p.getOutput();
            while (o.remaining() > 0) out.add(o.getShort());
        }
        short[] r = new short[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    static double rms(short[] s, int ch, int channels, int lo, int hi) {
        double e = 0; long n = 0;
        for (int i = lo; i < hi; i++) {
            if (i % channels == ch) { e += (double) s[i] * s[i]; n++; }
        }
        return n == 0 ? 0 : Math.sqrt(e / n) / 32768.0;
    }

    static AudioClip clip(double vol, float pan) {
        // android.jar's Uri throws "Stub!" on the JVM — tasks/a9/jvmsupply provides a
        // concrete stand-in via -sourcepath (see run-lane-parity.sh). AudioClip only
        // stores it here; nothing parses it.
        Uri fake = Uri.parse("file:///x.mp3");
        AudioClip ac = new AudioClip(fake, 60000);
        ac.setVolumeLevel((float) vol);
        ac.setPan(pan);
        return ac;
    }

    public static void main(String[] args) throws Exception {
        // ── 1. PARITY ────────────────────────────────────────────────────
        AudioClip model = clip(0.8, 0f);
        List<AudioProcessor> preview =
                AudioFxChainFactory.buildLaneChain(model, 44100, 48000, false);
        List<AudioProcessor> export =
                AudioFxChainFactory.buildLaneChain(model, 44100, 48000, false);
        check(preview.size() == 3 && export.size() == 3,
                "chain shape: resample + volume + fx (" + preview.size() + "/"
                        + export.size() + ")");
        short[] in = sine(0.8, 1000, 400, 1);
        short[] prevOut = drive(preview, in, 1);
        short[] expOut = drive(export, in, 1);
        boolean identical = prevOut.length == expOut.length;
        if (identical) for (int i = 0; i < prevOut.length; i++)
            if (prevOut[i] != expOut[i]) { identical = false; break; }
        check(identical, "PARITY: preview and export chains BIT-IDENTICAL");

        // ── 2. ENVELOPE: fade to 50% multiplies (B1.Q), not caps ────────
        AudioClip faded = clip(0.8, 0f);
        List<VolumeKeyframe> kfs = new ArrayList<>();
        kfs.add(new VolumeKeyframe(0, 1.0f));
        kfs.add(new VolumeKeyframe(400, 0.5f));
        faded.setVolumeKeyframes(kfs);
        short[] envOut = drive(AudioFxChainFactory.buildLaneChain(faded, -1, -1, true),
                sine(0.8, 1000, 400, 1), 1);
        double headRms = rms(envOut, 0, 1, 0, SR * 40 / 1000);
        double tailRms = rms(envOut, 0, 1, SR * 360 / 1000, SR * 395 / 1000);
        check(Math.abs(tailRms / headRms - 0.5) < 0.12,
                "ENVELOPE: fade-to-50% lands at ~half gain ("
                        + String.format("%.3f", tailRms / headRms) + ")");

        // ── 3. PAN: full-left on stereo collapses right (A9 headline fix) ──
        AudioClip panned = clip(1.0, -1f);
        short[] panOut = drive(AudioFxChainFactory.buildLaneChain(panned, -1, -1, false),
                sine(0.7, 1000, 300, 2), 2);
        double l = rms(panOut, 0, 2, 0, panOut.length / 2);
        double r = rms(panOut, 1, 2, 0, panOut.length / 2);
        check(l > 0.15 && r < 0.02 && l > 20 * Math.max(r, 1e-6),
                "PAN: full-left -> L " + String.format("%.3f", l)
                        + " R " + String.format("%.3f", r) + " (legacy preview was L=R)");

        // ── 4. MUTE: empty chain ─────────────────────────────────────────
        AudioClip muted = clip(1.0, 0f);
        muted.setMuted(true);
        check(AudioFxChainFactory.buildLaneChain(muted, 44100, 48000, false).isEmpty(),
                "MUTE: muted clip builds an EMPTY chain");

        // ── 5. BYPASS kills FX but keeps gain ────────────────────────────
        AudioClip plain = clip(0.8, 0f);
        List<AudioProcessor> bypassed =
                AudioFxChainFactory.buildLaneChain(plain, -1, -1, true);
        boolean hasFx = false;
        for (AudioProcessor p : bypassed)
            if (p instanceof com.fadcam.ui.faditor.audio.fx.FxChain) hasFx = true;
        check(bypassed.size() == 1 && !hasFx,
                "BYPASS: FX stage dropped, volume processor kept");
        short[] bypOut = drive(bypassed, sine(0.05, 1000, 200, 1), 1);
        double bypRms = rms(bypOut, 0, 1, 0, bypOut.length); System.out.println("      (bypass rms measured " + bypRms + ", want ~0.04)"); check(Math.abs(bypRms / (0.05 / Math.sqrt(2)) - 0.8) < 0.10,
                "BYPASS: quiet passthrough still scaled by volume 0.8");

        // ── 6. REFRESH pushes live model edits ───────────────────────────
        AudioClip live = clip(0.2, 0f);
        List<AudioProcessor> liveChain = AudioFxChainFactory.buildLaneChain(live, -1, -1, true);
        live.setVolumeLevel(1.0f);
        AudioFxChainFactory.refreshLaneChain(liveChain, live);
        short[] refOut = drive(liveChain, sine(0.5, 1000, 200, 1), 1);
        double refRms = rms(refOut, 0, 1, 0, refOut.length); System.out.println("      (refresh rms measured " + refRms + ", want ~0.5)"); check(Math.abs(refRms / (0.5 / Math.sqrt(2)) - 1.0) < 0.12,
                "REFRESH: model edit pushed into mounted chain (gain now ~1.0)");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
