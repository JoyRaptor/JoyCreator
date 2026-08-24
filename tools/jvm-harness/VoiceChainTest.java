import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import com.fadcam.ui.faditor.audio.fx.FxChain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The assembled voice chain, end to end — the regression guard for wiring de-hum into it.
 *
 * <p>The individual processors are covered elsewhere. What is NOT covered by testing them one
 * at a time is the chain as a whole: whether the pieces are all present, in a sensible order,
 * and whether adding one harms what the others were already doing. De-hum was added at the head
 * of this chain on 2026-08-24 after the orphan lint found it wired to nothing, and a change to
 * a shared default chain is exactly the sort that is discovered later, by ear, on someone's
 * finished video.</p>
 *
 * <p>The test signal is a voice-like tone with mains hum underneath, which is what a real home
 * recording is. The chain must take out the hum and leave the voice — both halves, because a
 * chain that removes everything scores perfectly on the first and ruins the recording.</p>
 */
public class VoiceChainTest {
    static int fails = 0, total = 0;

    static void check(boolean c, String n) {
        total++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final int SR = 48000;

    /** Voice-like tone at `voiceHz` plus mains hum at `humHz`. */
    static short[] voicePlusHum(double voiceAmp, double voiceHz, double humAmp, double humHz,
                                double ms) {
        int n = (int) (SR * ms / 1000.0);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double v = voiceAmp * Math.sin(2 * Math.PI * voiceHz * i / SR)
                    + humAmp * Math.sin(2 * Math.PI * humHz * i / SR);
            s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767.0)));
        }
        return s;
    }

    static ByteBuffer leBuf(short[] a) {
        ByteBuffer b = ByteBuffer.allocate(a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : a) b.putShort(v);
        b.flip();
        return b;
    }

    static final int[] CHUNKS = {997, 2048, 331};

    static short[] drive(AudioProcessor p, short[] in) throws Exception {
        p.configure(new AudioProcessor.AudioFormat(SR, 1, C.ENCODING_PCM_16BIT));
        p.flush();
        List<Short> out = new ArrayList<>();
        int ci = 0;
        for (int off = 0; off < in.length; ) {
            int len = Math.min(CHUNKS[ci++ % CHUNKS.length], in.length - off);
            short[] part = new short[len];
            System.arraycopy(in, off, part, 0, len);
            p.queueInput(leBuf(part));
            ByteBuffer o = p.getOutput();
            while (o.remaining() > 0) out.add(o.getShort());
            off += len;
        }
        p.queueEndOfStream();
        ByteBuffer o = p.getOutput();
        while (o.remaining() > 0) out.add(o.getShort());
        short[] r = new short[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /**
     * Energy at one frequency, by quadrature projection.
     *
     * <p>Measured per-frequency rather than as broadband RMS because the whole question is
     * whether one narrow band went away while another stayed — a total-energy figure cannot
     * tell "removed the hum" from "turned everything down".</p>
     */
    static double energyAt(short[] s, double hz, int lo) {
        double re = 0, im = 0;
        int n = 0;
        for (int i = lo; i < s.length; i++, n++) {
            double a = 2 * Math.PI * hz * i / SR;
            re += s[i] * Math.cos(a);
            im += s[i] * Math.sin(a);
        }
        if (n == 0) return 0;
        return Math.sqrt(re * re + im * im) / n / 32768.0;
    }

    public static void main(String[] args) throws Exception {
        final int SETTLE = SR / 4;                     // skip 250 ms of filter settling
        short[] in = voicePlusHum(0.30, 800, 0.15, 60, 1500);
        double inVoice = energyAt(in, 800, SETTLE);
        double inHum = energyAt(in, 60, SETTLE);
        System.out.println("      input : voice " + String.format("%.4f", inVoice)
                + "   hum " + String.format("%.4f", inHum));

        short[] out = drive(FxChain.createVoiceChain(SR), in);
        double outVoice = energyAt(out, 800, SETTLE);
        double outHum = energyAt(out, 60, SETTLE);
        System.out.println("      output: voice " + String.format("%.4f", outVoice)
                + "   hum " + String.format("%.4f", outHum));

        double humRatio = outHum / Math.max(1e-9, inHum);
        double voiceRatio = outVoice / Math.max(1e-9, inVoice);
        System.out.println("      hum x" + String.format("%.3f", humRatio)
                + "   voice x" + String.format("%.3f", voiceRatio));

        check(out.length > 0, "the assembled voice chain produces output at all");
        check(humRatio < 0.35, "voice chain REMOVES 60 Hz mains hum (x"
                + String.format("%.3f", humRatio) + ")");
        check(voiceRatio > 0.40, "and keeps the voice (x"
                + String.format("%.3f", voiceRatio) + ")");
        check(humRatio < voiceRatio,
                "hum is attenuated MORE than voice — selective, not just quieter");

        // NEGCTRL: bypassed, the chain must be a passthrough — hum and voice both survive.
        // Without this, "removes hum" would also pass on a chain that removes everything,
        // and the bypass itself would be untested at the chain level.
        short[] byp = drive(FxChain.createVoiceChain(SR, true), in);
        double bypHum = energyAt(byp, 60, SETTLE) / Math.max(1e-9, inHum);
        double bypVoice = energyAt(byp, 800, SETTLE) / Math.max(1e-9, inVoice);
        System.out.println("      bypassed: hum x" + String.format("%.3f", bypHum)
                + "   voice x" + String.format("%.3f", bypVoice));
        check(bypHum > 0.90, "NEGCTRL: bypassed chain leaves the hum (x"
                + String.format("%.3f", bypHum) + ")");
        check(bypVoice > 0.90, "NEGCTRL: bypassed chain leaves the voice (x"
                + String.format("%.3f", bypVoice) + ")");
        check(bypHum > humRatio,
                "NEGCTRL: bypass really differs from active (" + String.format("%.3f", bypHum)
                        + " vs " + String.format("%.3f", humRatio) + ")");

        // A recording with NO hum must come through essentially untouched at the voice
        // frequency — de-hum sits at the head of the chain now, and a narrow notch must not
        // cost anything when there is nothing to notch.
        short[] clean = voicePlusHum(0.30, 800, 0.0, 60, 1500);
        double cleanRatio = energyAt(drive(FxChain.createVoiceChain(SR), clean), 800, SETTLE)
                / Math.max(1e-9, energyAt(clean, 800, SETTLE));
        System.out.println("      hum-free input: voice x" + String.format("%.3f", cleanRatio));
        check(cleanRatio > 0.40, "a hum-free recording still passes (x"
                + String.format("%.3f", cleanRatio) + ")");

        // Output must never clip, whatever the chain does — the limiter is the last stage.
        int peak = 0;
        for (short v : out) peak = Math.max(peak, Math.abs(v));
        check(peak < 32767, "chain output does not clip (peak " + peak + ")");

        System.out.println(fails == 0 ? ("ALL GREEN (" + total + "/" + total + ")")
                : (fails + " FAILED of " + total));
        if (fails != 0) System.exit(1);
    }
}
