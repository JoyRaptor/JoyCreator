package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A module's struck notes as a MIDI file, so a .mod gets the exact kicks, snares and hats a .mid
 * gets ({@link MidiFeatures}) without a second copy of that logic (roadmap R13).
 *
 * <p>A module does not say which sample is a drum, so each is judged ({@link #judge}), by its
 * sound and by how it is used: noise (many zero crossings) is a hat, or a snare when duller; a
 * dull sample struck often at one or two pitches is a kick (chip-tune kicks are short waves, so the
 * sound alone cannot tell them from a bass). Anything else is an instrument and keeps its pitch.
 * Drums go on the General MIDI drum channel (36 kick, 38 snare, 42 hat), instruments on their
 * tracker channel with the period turned into a note (period 428, C-2, = middle C).</p>
 *
 * <p>The beat is the module's own: four rows, or eight when a module is written at double speed
 * (a beat shorter than 0.3 s), at its opening speed and tempo ({@link #secondsPerBeat}).</p>
 */
public final class ModMidi {

    public static final int KICK = 36, SNARE = 38, HAT = 42, NONE = -1;
    private static final int PPQ = 480;

    private ModMidi() {}

    /** Seconds in one beat: 4 rows, doubled while that is under 0.3 s. */
    public static double secondsPerBeat(int speed, int tempo) {
        double row = speed * 2.5 / Math.max(32, tempo);
        int rows = 4;
        while (rows * row < 0.3 && rows < 32) rows *= 2;
        return rows * row;
    }

    /** What each sample (index 1..31) is: {@link #KICK}, {@link #SNARE}, {@link #HAT} or {@link #NONE}. */
    @NonNull
    public static int[] judge(@NonNull ModFile mod, @NonNull List<ModPlayer.Event> events) {
        int[] hits = new int[32];
        @SuppressWarnings("unchecked") Set<Integer>[] pitches = new Set[32];
        for (ModPlayer.Event e : events) {
            if (e.sample < 1 || e.sample > 31) continue;
            hits[e.sample]++;
            if (pitches[e.sample] == null) pitches[e.sample] = new HashSet<>();
            pitches[e.sample].add(e.period);
        }
        int[] out = new int[32];
        out[0] = NONE;
        for (int i = 1; i <= 31; i++) {
            ModFile.Sample s = mod.samples[i - 1];
            int n = pitches[i] != null ? pitches[i].size() : 0;
            out[i] = judge(s, hits[i], n);
        }
        return out;
    }

    /** One sample, given how often it is struck and at how many pitches. Pure. */
    static int judge(@NonNull ModFile.Sample s, int hits, int pitches) {
        byte[] d = s.data;
        if (d.length < 16) return NONE;
        int n = Math.min(d.length, 4000), cross = 0;
        for (int i = 1; i < n; i++) if ((d[i - 1] < 0) != (d[i] < 0)) cross++;
        double z = cross / (double) n;
        if (z > 0.35) return HAT;
        if (z > 0.18) return SNARE;
        // A dull sample struck often at one or two pitches; a fully looping one only when its
        // loop is the tail of a longer sound (a thump, then a ring), not a bare repeating wave.
        boolean thumpThenRing = !s.loops() || s.loopLength * 2 < d.length;
        if (z < 0.06 && hits >= 8 && pitches >= 1 && pitches <= 2 && thumpThenRing) return KICK;
        return NONE;
    }

    public static boolean hasKick(@NonNull int[] judged) {
        for (int j : judged) if (j == KICK) return true;
        return false;
    }

    /** MIDI note for an Amiga period (428 = C-2 = MIDI 60). */
    static int noteOf(int period) {
        if (period <= 0) return 60;
        int n = (int) Math.round(60 + 12 * Math.log(428.0 / period) / Math.log(2));
        return Math.max(0, Math.min(127, n));
    }

    /** The notes of one pass as a Standard MIDI File (format 0), a beat of {@code secPerBeat}. */
    @NonNull
    public static byte[] toMidi(@NonNull List<ModPlayer.Event> events, double passSeconds,
                                double secPerBeat, @NonNull int[] judged) {
        double ticksPerSec = PPQ / secPerBeat;
        List<long[]> ev = new ArrayList<>();   // {tick, order, status, data1, data2}
        long order = 0;
        long drumTicks = Math.max(1, Math.round(0.06 * ticksPerSec));
        for (int i = 0; i < events.size(); i++) {
            ModPlayer.Event e = events.get(i);
            long on = Math.round(e.time * ticksPerSec);
            int vel = Math.max(1, Math.min(127, e.volume * 2));
            int d = e.sample >= 1 && e.sample <= 31 ? judged[e.sample] : NONE;
            if (d != NONE) {
                ev.add(new long[]{on, order++, 0x99, d, vel});
                ev.add(new long[]{on + drumTicks, order++, 0x89, d, 0});
                continue;
            }
            // An instrument sounds until its channel's next note, or half a second.
            double until = e.time + 0.5;
            for (int j = i + 1; j < events.size(); j++) {
                if (events.get(j).channel == e.channel) {
                    until = Math.min(until, events.get(j).time);
                    break;
                }
            }
            int chan = e.channel % 9;                // never 9, the drum channel
            int note = noteOf(e.period);
            ev.add(new long[]{on, order++, 0x90 | chan, note, vel});
            ev.add(new long[]{Math.max(on + 1, Math.round(until * ticksPerSec)), order++, 0x80 | chan, note, 0});
        }
        ev.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        ByteArrayOutputStream trk = new ByteArrayOutputStream();
        int us = (int) Math.round(secPerBeat * 1e6);
        meta(trk, 0, 0x51, new byte[]{(byte) (us >> 16), (byte) (us >> 8), (byte) us});
        long last = 0;
        for (long[] x : ev) {
            vlq(trk, x[0] - last);
            last = x[0];
            trk.write((int) x[2]);
            trk.write((int) x[3]);
            trk.write((int) x[4]);
        }
        // End of track at the end of the pass, so the file is exactly one pass long.
        long end = Math.max(last, Math.round(passSeconds * ticksPerSec));
        meta(trk, end - last, 0x2F, new byte[0]);
        byte[] t = trk.toByteArray();
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write('M'); f.write('T'); f.write('h'); f.write('d');
        int32(f, 6);
        int16(f, 0);
        int16(f, 1);
        int16(f, PPQ);
        f.write('M'); f.write('T'); f.write('r'); f.write('k');
        int32(f, t.length);
        f.write(t, 0, t.length);
        return f.toByteArray();
    }

    private static void meta(ByteArrayOutputStream o, long delta, int type, byte[] data) {
        vlq(o, delta);
        o.write(0xFF);
        o.write(type);
        vlq(o, data.length);
        o.write(data, 0, data.length);
    }

    private static void vlq(ByteArrayOutputStream o, long v) {
        long buf = v & 0x7F;
        while ((v >>= 7) > 0) {
            buf <<= 8;
            buf |= ((v & 0x7F) | 0x80);
        }
        while (true) {
            o.write((int) (buf & 0xFF));
            if ((buf & 0x80) != 0) buf >>= 8;
            else break;
        }
    }

    private static void int32(ByteArrayOutputStream o, int v) {
        o.write(v >>> 24);
        o.write(v >>> 16);
        o.write(v >>> 8);
        o.write(v);
    }

    private static void int16(ByteArrayOutputStream o, int v) {
        o.write(v >>> 8);
        o.write(v);
    }
}
