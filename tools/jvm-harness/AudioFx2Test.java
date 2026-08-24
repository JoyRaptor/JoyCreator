import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import com.fadcam.ui.faditor.audio.fx.DeEsserProcessor;
import com.fadcam.ui.faditor.audio.fx.DeHumProcessor;
import com.fadcam.ui.faditor.audio.fx.LimiterProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * C1.E, second half — the three FX processors {@code AudioFxTest} never touched.
 *
 * <p>run-audio-fx.sh covers EQ, compressor and gate. De-esser, de-hum and limiter shipped with
 * no direct test at all, and two of them were reported fixed for real bugs — the de-esser as a
 * pass-through stub that computed a reduction and never applied it, the de-hum with a
 * channel-state index that double-counted the channel stride. Neither fix had anything proving
 * it, which is the whole reason this file exists: a claimed fix with no test is a claim.</p>
 *
 * <p>Every processor here is judged the same way — it must do its job on the signal it targets,
 * leave a neighbouring signal alone, and stop doing anything when its own control says stop.
 * The middle one is what separates a real filter from a volume knob.</p>
 */
public class AudioFx2Test {
    static int fails = 0, total = 0;

    static void check(boolean c, String n) {
        total++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final int SR = 48000;

    static short[] sine(double amp, double f0, double ms) {
        int n = (int) (SR * ms / 1000.0);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            s[i] = (short) Math.max(-32768, Math.min(32767,
                    Math.round(amp * 32767.0 * Math.sin(2 * Math.PI * f0 * i / SR))));
        }
        return s;
    }

    /** Interleave the same mono signal into both channels. */
    static short[] toStereo(short[] mono) {
        short[] s = new short[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            s[2 * i] = mono[i];
            s[2 * i + 1] = mono[i];
        }
        return s;
    }

    static short[] channel(short[] stereo, int ch) {
        short[] s = new short[stereo.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = stereo[2 * i + ch];
        return s;
    }

    static ByteBuffer leBuf(short[] a) {
        ByteBuffer b = ByteBuffer.allocate(a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : a) b.putShort(v);
        b.flip();
        return b;
    }

    // Irregular chunk sizes on purpose: the export pipeline never hands a processor one neat
    // buffer, and buffer-boundary state is where these processors have already broken once.
    static final int[] CHUNKS = {997, 2048, 331};

    static short[] drive(AudioProcessor p, short[] in, int channels) throws Exception {
        p.configure(new AudioProcessor.AudioFormat(SR, channels, C.ENCODING_PCM_16BIT));
        p.flush();
        List<Short> out = new ArrayList<>();
        int ci = 0;
        for (int off = 0; off < in.length; ) {
            int len = Math.min(CHUNKS[ci++ % CHUNKS.length], in.length - off);
            len -= len % channels;                       // never split a frame
            if (len <= 0) len = channels;
            len = Math.min(len, in.length - off);
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

    /** RMS in -1..1 units over the settled part of the signal. */
    static double rms(short[] s) {
        int lo = Math.min(s.length / 4, SR / 10), hi = s.length;
        double e = 0;
        for (int i = lo; i < hi; i++) e += (double) s[i] * s[i];
        return Math.sqrt(e / Math.max(1, hi - lo)) / 32768.0;
    }

    static double peak(short[] s) {
        int lo = Math.min(s.length / 4, SR / 10);
        int m = 0;
        for (int i = lo; i < s.length; i++) m = Math.max(m, Math.abs(s[i]));
        return m / 32768.0;
    }

    /** out/in RMS for a mono tone pushed through `p`. */
    static double ratio(AudioProcessor p, double amp, double hz) throws Exception {
        short[] in = sine(amp, hz, 500);
        short[] out = drive(p, in, 1);
        return rms(out) / rms(in);
    }

    public static void main(String[] args) throws Exception {

        // ── DE-ESSER ────────────────────────────────────────────────────────────────
        // Sibilance lives around 5-8 kHz. The processor must pull that down and leave the
        // voice's body alone; a de-esser that ducks everything is just a quiet button.
        DeEsserProcessor de = new DeEsserProcessor();
        de.setThresholdDb(-30f);
        de.setMaxReductionDb(12f);
        double sib = ratio(de, 0.7, 7000);
        DeEsserProcessor de2 = new DeEsserProcessor();
        de2.setThresholdDb(-30f);
        de2.setMaxReductionDb(12f);
        double body = ratio(de2, 0.7, 500);
        System.out.println("      de-esser: 7kHz " + String.format("%.3f", sib)
                + "   500Hz " + String.format("%.3f", body));
        check(sib < 0.90, "DeEsser reduces 7 kHz sibilance (ratio " + String.format("%.3f", sib) + ")");
        check(body > 0.90, "DeEsser leaves 500 Hz body alone (" + String.format("%.3f", body) + ")");
        check(sib < body, "DeEsser is FREQUENCY-selective, not a volume knob");

        // NEGCTRL: told to reduce by nothing, it must be a passthrough. This is also the
        // control that would have caught the original stub — a stub passes this and fails
        // the sibilance check above, so the pair pins the behaviour from both sides.
        DeEsserProcessor deOff = new DeEsserProcessor();
        deOff.setThresholdDb(-30f);
        deOff.setMaxReductionDb(0f);
        double off = ratio(deOff, 0.7, 7000);
        check(off > 0.97, "NEGCTRL: maxReduction 0 dB is a passthrough (" + String.format("%.3f", off) + ")");

        // NEGCTRL: a threshold above the signal must not engage.
        DeEsserProcessor deHigh = new DeEsserProcessor();
        deHigh.setThresholdDb(0f);
        deHigh.setMaxReductionDb(12f);
        double quietSib = ratio(deHigh, 0.02, 7000);
        check(quietSib > 0.90,
                "NEGCTRL: quiet sibilance below threshold is untouched (" + String.format("%.3f", quietSib) + ")");

        // ── DE-HUM ──────────────────────────────────────────────────────────────────
        // A narrow notch at mains frequency. Narrow is the whole point: 60 Hz goes, the bass
        // note next to it stays.
        DeHumProcessor dh = new DeHumProcessor();
        dh.setHumFreqHz(60);
        dh.setQ(50f);
        double hum = ratio(dh, 0.5, 60);
        DeHumProcessor dh2 = new DeHumProcessor();
        dh2.setHumFreqHz(60);
        dh2.setQ(50f);
        double near = ratio(dh2, 0.5, 200);
        System.out.println("      de-hum: 60Hz " + String.format("%.3f", hum)
                + "   200Hz " + String.format("%.3f", near));
        check(hum < 0.60, "DeHum notches 60 Hz (" + String.format("%.3f", hum) + ")");
        check(near > 0.85, "DeHum leaves 200 Hz alone (" + String.format("%.3f", near) + ")");

        // NEGCTRL: tuned to 50 Hz, a 60 Hz tone must survive noticeably better than when
        // tuned to 60. Without this, "notches 60 Hz" would pass on a broadband low cut.
        DeHumProcessor dh50 = new DeHumProcessor();
        dh50.setHumFreqHz(50);
        dh50.setQ(50f);
        double mistuned = ratio(dh50, 0.5, 60);
        System.out.println("      de-hum mistuned to 50Hz on a 60Hz tone: " + String.format("%.3f", mistuned));
        check(mistuned > hum,
                "NEGCTRL: the notch is TUNED — mistuning it lets the hum through ("
                        + String.format("%.3f", mistuned) + " > " + String.format("%.3f", hum) + ")");

        // STEREO — the reported channel-state bug. Both channels carry the identical signal,
        // so any per-channel state error shows up as the two outputs differing.
        DeHumProcessor dhS = new DeHumProcessor();
        dhS.setHumFreqHz(60);
        dhS.setQ(50f);
        short[] stereoIn = toStereo(sine(0.5, 60, 500));
        short[] stereoOut = drive(dhS, stereoIn, 2);
        short[] L = channel(stereoOut, 0), R = channel(stereoOut, 1);
        long diff = 0;
        for (int i = 0; i < Math.min(L.length, R.length); i++) diff += Math.abs(L[i] - R[i]);
        double avgDiff = diff / (double) Math.max(1, Math.min(L.length, R.length));
        System.out.println("      de-hum stereo L/R mean |diff|: " + String.format("%.3f", avgDiff));
        check(avgDiff < 1.0,
                "DeHum treats both channels identically (mean |L-R| " + String.format("%.3f", avgDiff) + ")");
        check(rms(L) / rms(channel(stereoIn, 0)) < 0.60,
                "DeHum still notches in stereo, not just mono");

        // ── LIMITER ─────────────────────────────────────────────────────────────────
        // A limiter that does not limit means clipping in the export — the one failure the
        // user cannot fix afterwards.
        LimiterProcessor lim = new LimiterProcessor();
        lim.setCeilingDb(-6f);                                   // ~0.501 linear
        short[] loud = sine(0.95, 440, 500);
        short[] limited = drive(lim, loud, 1);
        double pk = peak(limited);
        System.out.println("      limiter: in peak " + String.format("%.3f", peak(loud))
                + " -> out peak " + String.format("%.3f", pk));
        check(pk < 0.60, "Limiter holds the ceiling (" + String.format("%.3f", pk) + " < 0.60)");
        check(pk > 0.30, "and does not crush far below it (" + String.format("%.3f", pk) + ")");

        LimiterProcessor lim2 = new LimiterProcessor();
        lim2.setCeilingDb(-6f);
        short[] quiet = sine(0.1, 440, 500);
        double qr = rms(drive(lim2, quiet, 1)) / rms(quiet);
        check(qr > 0.95, "Limiter leaves signal below the ceiling alone (" + String.format("%.3f", qr) + ")");

        // NEGCTRL: a ceiling at full scale must not reduce a 0.95 signal.
        LimiterProcessor limOpen = new LimiterProcessor();
        limOpen.setCeilingDb(0f);
        double openPk = peak(drive(limOpen, sine(0.95, 440, 500), 1));
        check(openPk > 0.85,
                "NEGCTRL: a 0 dB ceiling passes a loud signal (" + String.format("%.3f", openPk) + ")");

        // Nothing may ever exceed full scale, whatever the settings.
        check(peak(limited) <= 1.0 && openPk <= 1.0, "output never exceeds full scale");

        System.out.println(fails == 0 ? ("ALL GREEN (" + total + "/" + total + ")")
                : (fails + " FAILED of " + total));
        if (fails != 0) System.exit(1);
    }
}
