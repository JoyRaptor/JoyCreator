import com.fadcam.vislab.audio.MidiFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * MIDI as a VisLab sound source (roadmap R5): the reader turns a file into exact events. A tiny
 * file is written here byte by byte (format 0, 480 ticks a quarter, 120 bpm then 60 bpm, a kick
 * on the drum channel, a bass note using running status, a karaoke lyric) and every time checked.
 */
public class MidiFileTest {

    static int fails, passes;

    public static void main(String[] args) throws Exception {
        MidiFile m = MidiFile.parse(file());
        check("format 0", m.format() == 0);
        check("480 ticks a quarter", m.ticksPerQuarter() == 480);
        List<MidiFile.Note> notes = m.notes();
        check("three notes (" + notes.size() + ")", notes.size() == 3);
        MidiFile.Note kick = notes.get(0);
        check("the kick is on the drum channel", kick.channel == MidiFile.DRUM_CHANNEL && kick.isDrum());
        check("the kick starts at 0 s", Math.abs(kick.start) < 1e-6);
        MidiFile.Note kick2 = notes.get(1);
        check("the second kick at 0.5 s (one quarter at 120 bpm): " + kick2.start, Math.abs(kick2.start - 0.5) < 1e-6);
        MidiFile.Note bass = notes.get(2);
        check("running status: the bass note is read (" + bass.note + ")", bass.note == 36 && bass.channel == 0);
        // Tempo halves at tick 960 (1.0 s): the bass note, one quarter after it, starts at 2.0 s.
        check("tempo change honoured: bass at 2.0 s: " + bass.start, Math.abs(bass.start - 2.0) < 1e-6);
        check("tempo map starts at 120 bpm", Math.abs(m.tempos().get(0).bpm() - 120) < 1e-6);
        check("seconds at tick 960 = 1.0", Math.abs(m.secondsAtTick(960) - 1.0) < 1e-6);
        List<MidiFile.Lyric> lyrics = m.lyrics();
        check("one lyric (" + lyrics.size() + ")", lyrics.size() == 1);
        if (!lyrics.isEmpty()) {
            check("lyric time 0.5 s: " + lyrics.get(0).time, Math.abs(lyrics.get(0).time - 0.5) < 1e-6);
            check("lyric text: " + lyrics.get(0).text, lyrics.get(0).text.contains("Joy"));
        }
        check("not MIDI: a WAV header", !MidiFile.looksLikeMidi("RIFF....WAVE".getBytes("US-ASCII"), 12));
        check("MIDI: an MThd header", MidiFile.looksLikeMidi(file(), 14));
        System.out.println("MidiFileTest: " + (fails == 0 ? "PASS" : "FAILURES: " + fails) + " (passed " + passes + ")");
        if (fails > 0) System.exit(1);
    }

    /** The test song, as the bytes of a .mid file. */
    static byte[] file() {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        meta(t, 0, 0x51, new byte[]{0x07, (byte) 0xA1, 0x20});      // 500000 us a quarter = 120 bpm
        midi(t, 0, 0x99, 36, 100);                                   // kick on (channel 10)
        midi(t, 240, 0x89, 36, 0);                                   // kick off
        meta(t, 240, 0x05, "Joy".getBytes());                        // lyric at tick 480
        midi(t, 0, 0x99, 36, 100);                                   // second kick at tick 480
        midi(t, 240, 0x89, 36, 0);
        meta(t, 240, 0x51, new byte[]{0x0F, 0x42, 0x40});            // tick 960: 1000000 us = 60 bpm
        midi(t, 480, 0x90, 36, 90);                                  // tick 1440: bass on, channel 1
        vlq(t, 480);                                                 // running status: same 0x90
        t.write(36);
        t.write(0);                                                  // velocity 0 = off
        meta(t, 0, 0x2F, new byte[0]);                               // end of track
        byte[] track = t.toByteArray();
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.writeBytes("MThd".getBytes());
        int32(f, 6);
        int16(f, 0);
        int16(f, 1);
        int16(f, 480);
        f.writeBytes("MTrk".getBytes());
        int32(f, track.length);
        f.writeBytes(track);
        return f.toByteArray();
    }

    static void midi(ByteArrayOutputStream o, int delta, int status, int a, int b) {
        vlq(o, delta);
        o.write(status);
        o.write(a);
        o.write(b);
    }

    static void meta(ByteArrayOutputStream o, int delta, int type, byte[] data) {
        vlq(o, delta);
        o.write(0xFF);
        o.write(type);
        vlq(o, data.length);
        o.writeBytes(data);
    }

    static void vlq(ByteArrayOutputStream o, int v) {
        int buf = v & 0x7F;
        while ((v >>= 7) > 0) {
            buf <<= 8;
            buf |= ((v & 0x7F) | 0x80);
        }
        while (true) {
            o.write(buf & 0xFF);
            if ((buf & 0x80) != 0) buf >>= 8;
            else break;
        }
    }

    static void int32(ByteArrayOutputStream o, int v) {
        o.write(v >>> 24);
        o.write(v >>> 16);
        o.write(v >>> 8);
        o.write(v);
    }

    static void int16(ByteArrayOutputStream o, int v) {
        o.write(v >>> 8);
        o.write(v);
    }

    static void check(String what, boolean ok) {
        if (ok) passes++;
        else fails++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
