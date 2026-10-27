import com.fadcam.vislab.audio.MidiFile;
import com.fadcam.vislab.audio.ModFile;
import com.fadcam.vislab.audio.ModMidi;
import com.fadcam.vislab.audio.ModPlayer;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Tracker modules as a VisLab sound source (roadmap R13). A tiny 4-channel module is written here
 * byte by byte: sample 1 a dull low thump (a kick), sample 2 white noise (a hat), one pattern with
 * the kick every 8 rows and the hat every 4, at the default speed 6 / tempo 125 (a row = 0.12 s).
 * Real modules given as arguments are read, played one pass and summarised (not committed: the
 * owner's own files stay on his machine).
 */
public class ModFileTest {

    static int fails, passes;

    public static void main(String[] args) throws Exception {
        ModFile m = ModFile.parse(module());
        check("4 channels from M.K.", m.channels == 4);
        check("title", "joy test".equals(m.title));
        check("one position", m.songLength == 1);
        check("kick on row 0, channel 0, sample 1", m.sampleAt(0, 0, 0) == 1 && m.periodAt(0, 0, 0) == 428);
        check("hat on row 4, channel 1, sample 2", m.sampleAt(0, 4, 1) == 2);
        ModPlayer.Pass pass = ModPlayer.dryRun(m);
        List<ModPlayer.Event> ev = pass.events;
        int[] judged = ModMidi.judge(m, ev);
        check("the thump, struck 8 times at one pitch, is a kick", judged[1] == ModMidi.KICK);
        check("the noise is a hat", judged[2] == ModMidi.HAT);
        check("one pass = 64 rows x 0.12 s = 7.68 s: " + pass.seconds, Math.abs(pass.seconds - 7.68) < 0.02);
        check("speed 6, tempo 125", pass.speed == 6 && pass.tempo == 125);
        check("a beat = 4 rows = 0.48 s", Math.abs(ModMidi.secondsPerBeat(6, 125) - 0.48) < 1e-9);
        check("a double-speed module (speed 3) beats every 8 rows = 0.48 s",
                Math.abs(ModMidi.secondsPerBeat(3, 125) - 0.48) < 1e-9);
        check("8 kicks + 16 hats struck: " + ev.size(), ev.size() == 24);
        double second = -1;
        int seen = 0;
        for (ModPlayer.Event e : ev) if (e.sample == 1 && ++seen == 2) second = e.time;
        check("the second kick at 0.96 s: " + second, Math.abs(second - 0.96) < 0.01);

        MidiFile mid = MidiFile.parse(ModMidi.toMidi(ev, pass.seconds, ModMidi.secondsPerBeat(6, 125), judged));
        int kicks = 0, hats = 0;
        for (MidiFile.Note n : mid.notes()) {
            if (n.isDrum() && n.note == ModMidi.KICK) kicks++;
            if (n.isDrum() && n.note == ModMidi.HAT) hats++;
        }
        check("as MIDI: 8 kicks and 16 hats on the drum channel (" + kicks + ", " + hats + ")", kicks == 8 && hats == 16);
        check("as MIDI: the pass is 7.68 s long: " + mid.lengthSec(), Math.abs(mid.lengthSec() - 7.68) < 0.02);

        ModPlayer p = new ModPlayer(m, 44100);
        short[] pcm = new short[44100];
        p.render(pcm, 0, pcm.length);
        long energy = 0;
        for (short s : pcm) energy += Math.abs(s);
        check("one second played is not silent (" + energy + ")", energy > 100000);

        for (String path : args) summarise(path);
        System.out.println("ModFileTest: " + (fails == 0 ? "PASS" : "FAILURES: " + fails) + " (passed " + passes + ")");
        if (fails > 0) System.exit(1);
    }

    /** A real module: its shape, one pass, and what its samples were judged to be. */
    static void summarise(String path) throws Exception {
        ModFile m = ModFile.parse(Files.readAllBytes(Paths.get(path)));
        ModPlayer.Pass ps = ModPlayer.dryRun(m);
        double[] pass = {ps.seconds};
        List<ModPlayer.Event> ev = ps.events;
        int[] judged = ModMidi.judge(m, ev);
        StringBuilder kinds = new StringBuilder();
        for (int i = 0; i < 31; i++) {
            if (m.samples[i].data.length == 0) continue;
            int d = judged[i + 1];
            kinds.append(i + 1).append(':').append(d == ModMidi.KICK ? "kick" : d == ModMidi.SNARE ? "snare"
                    : d == ModMidi.HAT ? "hat" : "tone").append(' ');
        }
        ModPlayer p = new ModPlayer(m, 44100);
        float level = ModPlayer.loudness(m, Math.min(20, ps.seconds));
        p.setLevel(level);
        short[] pcm = new short[44100 * 4];
        p.render(pcm, 0, pcm.length);
        int peak = 0;
        for (short s : pcm) peak = Math.max(peak, Math.abs(s));
        check(Paths.get(path).getFileName() + " does not clip at level " + String.format("%.2f", level)
                + " (peak " + peak + ")", peak < 32000);
        System.out.println("  " + Paths.get(path).getFileName() + ": \"" + m.title + "\" " + m.channels + " ch, pass "
                + String.format("%.1f", pass[0]) + " s, speed " + ps.speed + " tempo " + ps.tempo + ", beat "
                + String.format("%.2f", ModMidi.secondsPerBeat(ps.speed, ps.tempo)) + " s, " + ev.size()
                + " notes, peak " + peak + ", samples " + kinds);
        check(Paths.get(path).getFileName() + " plays a pass with notes", pass[0] > 1 && !ev.isEmpty() && peak > 1000);
    }

    // ------------------------------------------------------------------ the test module

    static byte[] module() {
        byte[] kick = new byte[2000], hat = new byte[1200];
        for (int i = 0; i < kick.length; i++) {
            double env = Math.exp(-i / 600.0);
            kick[i] = (byte) Math.round(120 * env * Math.sin(2 * Math.PI * i * 60 / 8287.0));
        }
        long seed = 12345;
        for (int i = 0; i < hat.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            hat[i] = (byte) Math.round((seed >> 56) * Math.exp(-i / 400.0) * 0.8);   // signed: real noise
        }
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        byte[] title = new byte[20];
        byte[] t = "joy test".getBytes();
        System.arraycopy(t, 0, title, 0, t.length);
        f.writeBytes(title);
        for (int i = 0; i < 31; i++) {
            byte[] h = new byte[30];
            byte[] s = i == 0 ? kick : i == 1 ? hat : new byte[0];
            int words = s.length / 2;
            h[22] = (byte) (words >> 8);
            h[23] = (byte) words;
            h[25] = s.length > 0 ? (byte) 64 : 0;   // volume
            h[29] = 1;                               // loop length 1 word = no loop
            f.writeBytes(h);
        }
        f.write(1);    // song length
        f.write(127);
        f.writeBytes(new byte[128]);                 // order: pattern 0
        f.writeBytes("M.K.".getBytes());
        byte[] pat = new byte[64 * 4 * 4];
        for (int row = 0; row < 64; row++) {
            if (row % 8 == 0) note(pat, row, 0, 1, 428);
            if (row % 4 == 0) note(pat, row, 1, 2, 428);
        }
        f.writeBytes(pat);
        f.writeBytes(kick);
        f.writeBytes(hat);
        return f.toByteArray();
    }

    static void note(byte[] pat, int row, int ch, int sample, int period) {
        int o = (row * 4 + ch) * 4;
        pat[o] = (byte) ((sample & 0xF0) | ((period >> 8) & 0x0F));
        pat[o + 1] = (byte) period;
        pat[o + 2] = (byte) ((sample & 0x0F) << 4);
    }

    static void check(String what, boolean ok) {
        if (ok) passes++;
        else fails++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
