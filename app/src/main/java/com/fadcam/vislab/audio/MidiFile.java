package com.fadcam.vislab.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Standard MIDI File (.mid / .midi / .kar, formats 0 and 1) read into exact events: every
 * note with its time in seconds, channel, pitch and how hard it was hit, the tempo and
 * time-signature maps, and the lyric / text events a karaoke (.kar) file carries its words in.
 *
 * <p>Pure Java, no Android and no libraries, so the same code is checked on the desk by
 * {@code tools/jvm-harness/MidiTest}. Running status, note-on with velocity 0 as a note-off,
 * tempo changes, SMPTE time bases, a RIFF "RMID" wrapper and a file cut short are all handled:
 * a damaged file gives what could be read, a file that is not MIDI throws.</p>
 *
 * <p>Channels are counted from 0 here: the General MIDI drum channel ("channel 10") is
 * {@link #DRUM_CHANNEL} = 9.</p>
 */
public final class MidiFile {

    public static final int DRUM_CHANNEL = 9;
    private static final int MAX_EVENTS = 4_000_000;
    /** A karaoke word stays on screen until the next one starts, but never longer than this. */
    private static final double MAX_WORD_HOLD = 2.0;
    private static final double LAST_WORD_HOLD = 1.0;
    /** A file whose only text is a couple of comments is not a karaoke file. */
    private static final int MIN_TEXT_WORDS = 4;

    /** One note-on or note-off. */
    public static final class Event {
        public final double time;
        public final int channel, note, velocity;
        public final boolean on;

        Event(double time, int channel, int note, int velocity, boolean on) {
            this.time = time;
            this.channel = channel;
            this.note = note;
            this.velocity = velocity;
            this.on = on;
        }
    }

    /** A whole note: when it starts, when it ends, how hard, and the instrument it was played on. */
    public static final class Note {
        public final double start, end;
        public final int channel, note, velocity, program;

        Note(double start, double end, int channel, int note, int velocity, int program) {
            this.start = start;
            this.end = end;
            this.channel = channel;
            this.note = note;
            this.velocity = velocity;
            this.program = program;
        }

        public boolean isDrum() {
            return channel == DRUM_CHANNEL;
        }
    }

    /** A tempo, from the tick it starts. */
    public static final class Tempo {
        public final long tick;
        public final double time;
        public final int microsPerQuarter;

        Tempo(long tick, double time, int microsPerQuarter) {
            this.tick = tick;
            this.time = time;
            this.microsPerQuarter = microsPerQuarter;
        }

        public double bpm() {
            return 60e6 / microsPerQuarter;
        }
    }

    /** A time signature, from the quarter-note position it starts. */
    public static final class Meter {
        public final double quarters;
        public final int numerator, denominator;

        Meter(double quarters, int numerator, int denominator) {
            this.quarters = quarters;
            this.numerator = numerator;
            this.denominator = denominator;
        }

        /** Length of one bar in quarter notes (6/8 = 3, 4/4 = 4, 3/4 = 3). */
        public double barQuarters() {
            return numerator * 4.0 / denominator;
        }
    }

    /** A lyric (type 0x05) or text (type 0x01) meta event: one syllable in a karaoke file. */
    public static final class Lyric {
        public final double time;
        public final String text;
        public final int type;

        Lyric(double time, String text, int type) {
            this.time = time;
            this.text = text;
            this.type = type;
        }
    }

    /** A sung word with the time it starts and the time it is done. */
    public static final class Word {
        public final String text;
        public final double start, end;

        Word(String text, double start, double end) {
            this.text = text;
            this.start = start;
            this.end = Math.max(end, start + 0.05);
        }
    }

    // ------------------------------------------------------------------ parse-time records

    private static final class Raw {
        final long tick;
        final int seq, channel, note, velocity, program;
        final boolean on;

        Raw(long tick, int seq, boolean on, int channel, int note, int velocity, int program) {
            this.tick = tick;
            this.seq = seq;
            this.on = on;
            this.channel = channel;
            this.note = note;
            this.velocity = velocity;
            this.program = program;
        }
    }

    private static final class RawText {
        final long tick;
        final int seq, type;
        final String text;

        RawText(long tick, int seq, int type, String text) {
            this.tick = tick;
            this.seq = seq;
            this.type = type;
            this.text = text;
        }
    }

    private static final class Open {
        final long start;
        long end = -1;
        final int channel, note, velocity, program;

        Open(long start, int channel, int note, int velocity, int program) {
            this.start = start;
            this.channel = channel;
            this.note = note;
            this.velocity = velocity;
            this.program = program;
        }
    }

    private static final Comparator<Raw> BY_TIME = (a, b) -> {
        if (a.tick != b.tick) return a.tick < b.tick ? -1 : 1;
        if (a.on != b.on) return a.on ? 1 : -1;   // an off before an on at the same tick: repeated notes work
        return Integer.compare(a.seq, b.seq);
    };

    private final int format, division, trackCount;
    private final boolean smpte;
    private final double smpteSecPerTick;

    private final List<Raw> raw = new ArrayList<>();
    private final List<RawText> rawText = new ArrayList<>();
    private final List<long[]> rawTempo = new ArrayList<>();    // {tick, seq, microsPerQuarter}
    private final List<long[]> rawMeter = new ArrayList<>();    // {tick, seq, numerator, denominator}
    private long endTick;
    private int seq, rp;

    private List<Event> events = Collections.emptyList();
    private List<Note> notes = Collections.emptyList();
    private List<Tempo> tempos = Collections.emptyList();
    private List<Meter> meters = Collections.emptyList();
    private List<Lyric> lyrics = Collections.emptyList();
    private List<Word> words;
    private long[] tmTick;
    private double[] tmSec;
    private int[] tmUs;
    private double[] mtQuarters;
    private double length;

    private MidiFile(int format, int division, int trackCount) {
        this.format = format;
        this.division = division;
        this.trackCount = trackCount;
        if ((division & 0x8000) != 0) {
            int hi = (division >> 8) & 0xFF;
            int fps = 256 - hi;                       // -24, -25, -29, -30 as a signed byte
            int perFrame = division & 0xFF;
            smpte = true;
            smpteSecPerTick = 1.0 / ((fps == 29 ? 29.97 : fps) * perFrame);
        } else {
            smpte = false;
            smpteSecPerTick = 0;
        }
    }

    // ------------------------------------------------------------------ reading

    /** True when the first bytes say this is a MIDI file (plain, or wrapped in RIFF "RMID"). */
    public static boolean looksLikeMidi(byte[] head, int n) {
        if (head == null) return false;
        if (n >= 4 && is(head, 0, "MThd")) return true;
        return n >= 12 && is(head, 0, "RIFF") && is(head, 8, "RMID");
    }

    /**
     * Read a whole file.
     *
     * @throws IOException when the bytes are not a MIDI file (or are format 2, which no player
     *                     of songs uses), with a plain reason
     */
    public static MidiFile parse(byte[] d) throws IOException {
        if (d == null || d.length < 14) throw new IOException("not a MIDI file");
        int p = 0;
        if (is(d, 0, "RIFF") && d.length >= 20 && is(d, 8, "RMID")) {
            p = 12;
            while (p + 8 <= d.length && !is(d, p, "data")) {
                long size = le32(d, p + 4);
                p += 8 + (int) Math.min(d.length, size + (size & 1));
            }
            p += 8;
        }
        if (p + 14 > d.length || !is(d, p, "MThd")) throw new IOException("not a MIDI file");
        long hlen = be32(d, p + 4);
        if (hlen < 6) throw new IOException("damaged MIDI header");
        int format = be16(d, p + 8), tracks = be16(d, p + 10), division = be16(d, p + 12);
        if (format > 1) throw new IOException("MIDI format " + format + " is not supported");
        if (division == 0 || ((division & 0x8000) != 0 && (division & 0xFF) == 0)) {
            throw new IOException("damaged MIDI time base");
        }
        MidiFile m = new MidiFile(format, division, tracks);
        long q = p + 8 + hlen;
        while (q + 8 <= d.length) {
            long size = be32(d, (int) q + 4);
            long body = q + 8;
            int end = (int) Math.min(d.length, body + size);   // a chunk that claims too much is cut at the file
            if (is(d, (int) q, "MTrk")) m.readTrack(d, (int) body, end);
            q = body + size;
        }
        m.finish();
        return m;
    }

    private void readTrack(byte[] d, int start, int end) throws IOException {
        rp = start;
        long tick = 0;
        int running = -1;
        int[] program = new int[16];
        while (rp < end) {
            long delta = vlq(d, end);
            if (delta < 0 || rp >= end) break;
            tick += delta;
            int status = d[rp] & 0xFF;
            if (status >= 0x80) rp++;
            else if (running >= 0) status = running;     // running status: the status byte is left out
            else break;                                  // data with nothing to belong to: damaged
            if (raw.size() > MAX_EVENTS) throw new IOException("too many events");
            if (status < 0xF0) {
                running = status;
                int hi = status & 0xF0, ch = status & 0x0F;
                int need = (hi == 0xC0 || hi == 0xD0) ? 1 : 2;
                if (rp + need > end) break;
                int a = d[rp++] & 0x7F;
                int b = need == 2 ? d[rp++] & 0x7F : 0;
                if (hi == 0x90 && b > 0) raw.add(new Raw(tick, seq++, true, ch, a, b, program[ch]));
                else if (hi == 0x90 || hi == 0x80) raw.add(new Raw(tick, seq++, false, ch, a, hi == 0x90 ? 0 : b, program[ch]));
                else if (hi == 0xC0) program[ch] = a;
            } else if (status == 0xFF) {
                if (rp >= end) break;
                int type = d[rp++] & 0xFF;
                long len = vlq(d, end);
                if (len < 0) break;
                int n = (int) Math.min(len, end - rp);   // cut short: use what is there
                meta(type, d, rp, n, tick);
                rp += n;
                if (type == 0x2F) break;                 // end of track
            } else if (status == 0xF0 || status == 0xF7) {
                long len = vlq(d, end);
                if (len < 0) break;
                rp += (int) Math.min(len, end - rp);
            } else {
                rp += status == 0xF2 ? 2 : (status == 0xF1 || status == 0xF3) ? 1 : 0;
            }
        }
        endTick = Math.max(endTick, tick);
    }

    private void meta(int type, byte[] d, int off, int n, long tick) {
        switch (type) {
            case 0x51:
                if (n >= 3) {
                    int us = ((d[off] & 0xFF) << 16) | ((d[off + 1] & 0xFF) << 8) | (d[off + 2] & 0xFF);
                    if (us > 0) rawTempo.add(new long[]{tick, seq++, us});
                }
                break;
            case 0x58:
                if (n >= 2 && (d[off] & 0xFF) > 0) {
                    rawMeter.add(new long[]{tick, seq++, d[off] & 0xFF, 1 << Math.min(d[off + 1] & 0xFF, 6)});
                }
                break;
            case 0x01:
            case 0x05:
                rawText.add(new RawText(tick, seq++, type, decode(d, off, n)));
                break;
            default:
                break;
        }
    }

    /** Variable-length number; -1 when the track ends before it does. */
    private long vlq(byte[] d, int end) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            if (rp >= end) return -1;
            int b = d[rp++] & 0xFF;
            v = (v << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) return v;
        }
        return v;
    }

    /** UTF-8 when it is valid UTF-8, else Windows-1252 (what most older karaoke files are in). */
    private static String decode(byte[] d, int off, int n) {
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(d, off, n)).toString();
        } catch (CharacterCodingException e) {
            try {
                s = new String(d, off, n, "windows-1252");
            } catch (java.io.UnsupportedEncodingException | RuntimeException x) {
                s = new String(d, off, n, StandardCharsets.ISO_8859_1);
            }
        }
        return s.replace("\u0000", "");
    }

    // ------------------------------------------------------------------ assembling

    private void finish() {
        // Tempo map: from the first tick, a later change at the same tick wins.
        Collections.sort(rawTempo, (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        List<long[]> tm = new ArrayList<>();
        for (long[] t : rawTempo) {
            if (!tm.isEmpty() && tm.get(tm.size() - 1)[0] == t[0]) tm.set(tm.size() - 1, t);
            else tm.add(t);
        }
        if (tm.isEmpty() || tm.get(0)[0] > 0) tm.add(0, new long[]{0, -1, 500000});   // 120 bpm until told otherwise
        int nt = tm.size();
        tmTick = new long[nt];
        tmSec = new double[nt];
        tmUs = new int[nt];
        List<Tempo> tl = new ArrayList<>(nt);
        for (int i = 0; i < nt; i++) {
            tmTick[i] = tm.get(i)[0];
            tmUs[i] = (int) tm.get(i)[2];
            tmSec[i] = i == 0 ? 0 : tmSec[i - 1] + (tmTick[i] - tmTick[i - 1]) * secPerTick(tmUs[i - 1]);
            tl.add(new Tempo(tmTick[i], smpte ? tmTick[i] * smpteSecPerTick : tmSec[i], tmUs[i]));
        }
        tempos = Collections.unmodifiableList(tl);

        // Time signatures, as quarter-note positions.
        Collections.sort(rawMeter, (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        List<long[]> mm = new ArrayList<>();
        for (long[] t : rawMeter) {
            if (!mm.isEmpty() && mm.get(mm.size() - 1)[0] == t[0]) mm.set(mm.size() - 1, t);
            else mm.add(t);
        }
        if (mm.isEmpty() || mm.get(0)[0] > 0) mm.add(0, new long[]{0, -1, 4, 4});
        mtQuarters = new double[mm.size()];
        List<Meter> ml = new ArrayList<>(mm.size());
        for (int i = 0; i < mm.size(); i++) {
            long[] t = mm.get(i);
            mtQuarters[i] = smpte ? secondsAtTick(t[0]) * 2.0 : (double) t[0] / division;
            ml.add(new Meter(mtQuarters[i], (int) t[2], (int) t[3]));
        }
        meters = Collections.unmodifiableList(ml);

        // Pair each note-on with its note-off (the oldest open one of that pitch).
        List<Raw> sorted = new ArrayList<>(raw);
        Collections.sort(sorted, BY_TIME);
        Map<Integer, ArrayDeque<Open>> open = new HashMap<>();
        List<Open> all = new ArrayList<>();
        List<Event> ev = new ArrayList<>(sorted.size());
        long lastTick = endTick;
        for (Raw r : sorted) {
            int key = r.channel * 128 + r.note;
            if (r.on) {
                Open o = new Open(r.tick, r.channel, r.note, r.velocity, r.program);
                all.add(o);
                ArrayDeque<Open> q = open.get(key);
                if (q == null) open.put(key, q = new ArrayDeque<>());
                q.addLast(o);
            } else {
                ArrayDeque<Open> q = open.get(key);
                if (q != null && !q.isEmpty()) q.pollFirst().end = r.tick;
            }
            lastTick = Math.max(lastTick, r.tick);
        }
        for (RawText t : rawText) lastTick = Math.max(lastTick, t.tick);
        for (Raw r : sorted) ev.add(new Event(secondsAtTick(r.tick), r.channel, r.note, r.velocity, r.on));
        events = Collections.unmodifiableList(ev);
        List<Note> nl = new ArrayList<>(all.size());
        for (Open o : all) {
            long e = o.end >= 0 ? o.end : lastTick;       // never released: held to the end
            nl.add(new Note(secondsAtTick(o.start), secondsAtTick(Math.max(e, o.start)), o.channel, o.note, o.velocity, o.program));
        }
        notes = Collections.unmodifiableList(nl);
        length = secondsAtTick(lastTick);

        // Lyric and text events, in time order, the same words on two tracks counted once.
        Collections.sort(rawText, (a, b) -> a.tick != b.tick ? Long.compare(a.tick, b.tick) : Integer.compare(a.seq, b.seq));
        List<Lyric> ll = new ArrayList<>(rawText.size());
        RawText prev = null;
        for (RawText t : rawText) {
            if (prev != null && prev.tick == t.tick && prev.type == t.type && prev.text.equals(t.text)) continue;
            ll.add(new Lyric(secondsAtTick(t.tick), t.text, t.type));
            prev = t;
        }
        lyrics = Collections.unmodifiableList(ll);
    }

    // ------------------------------------------------------------------ what was read

    public int format() {
        return format;
    }

    /** Ticks per quarter note, or 0 for an SMPTE time base. */
    public int ticksPerQuarter() {
        return smpte ? 0 : division;
    }

    public int trackCount() {
        return trackCount;
    }

    /** The whole piece in seconds: to the last end-of-track mark, or the last event if a file has none. */
    public double lengthSec() {
        return length;
    }

    /** Every note-on and note-off, in time order (an off sorts before an on at the same time). */
    public List<Event> events() {
        return events;
    }

    /** Every note with its start and end, by start time. Drum notes are the ones on {@link #DRUM_CHANNEL}. */
    public List<Note> notes() {
        return notes;
    }

    public List<Tempo> tempos() {
        return tempos;
    }

    public List<Meter> meters() {
        return meters;
    }

    /** Lyric and text meta events, by time, as the file has them (headers such as "@T title" included). */
    public List<Lyric> lyrics() {
        return lyrics;
    }

    // ------------------------------------------------------------------ time

    private double secPerTick(int us) {
        return us * 1e-6 / division;
    }

    /** Seconds at a tick, through the tempo map. */
    public double secondsAtTick(double tick) {
        if (smpte) return tick * smpteSecPerTick;
        int lo = 0, hi = tmTick.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (tmTick[mid] <= tick) lo = mid;
            else hi = mid - 1;
        }
        return tmSec[lo] + (tick - tmTick[lo]) * secPerTick(tmUs[lo]);
    }

    private int tempoIndexAt(double sec) {
        int lo = 0, hi = tmSec.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (tmSec[mid] <= sec) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** Where the music is, in quarter notes, at a time in seconds (an SMPTE file counts 120 per minute). */
    public double quartersAt(double sec) {
        if (smpte) return sec * 2.0;
        int i = tempoIndexAt(sec);
        return (tmTick[i] + (sec - tmSec[i]) / secPerTick(tmUs[i])) / division;
    }

    /** The time, in seconds, a quarter-note position falls on. */
    public double secondsAtQuarters(double q) {
        return smpte ? q * 0.5 : secondsAtTick(q * division);
    }

    /** Beats per minute at a time (quarter notes). */
    public double bpmAt(double sec) {
        return smpte ? 120.0 : 60e6 / tmUs[tempoIndexAt(sec)];
    }

    /** The time signature in force at a quarter-note position. */
    public Meter meterAtQuarters(double q) {
        int lo = 0, hi = mtQuarters.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (mtQuarters[mid] <= q) lo = mid;
            else hi = mid - 1;
        }
        return meters.get(lo);
    }

    // ------------------------------------------------------------------ karaoke words

    /**
     * The sung words, ready for a Words layer. Lyric events (0x05) are used when the file has
     * them; otherwise text events (0x01), the way .kar files carry a song, leaving out the
     * "@..." header lines. A syllable that ends in a space (or the next one starting with one,
     * or a "/" or "\" line mark) ends the word; syllables with none are joined: "Hel" "lo " is
     * one word, "Hello", starting when "Hel" is sung. A word lasts until the next begins, at most
     * two seconds.
     */
    public synchronized List<Word> words() {
        if (words == null) words = Collections.unmodifiableList(buildWords());
        return words;
    }

    private List<Word> buildWords() {
        List<Lyric> src = new ArrayList<>();
        for (Lyric l : lyrics) if (l.type == 0x05) src.add(l);
        if (src.isEmpty()) {
            for (Lyric l : lyrics) if (l.type == 0x01 && !l.text.startsWith("@")) src.add(l);
            if (src.size() < MIN_TEXT_WORDS) src.clear();
        }
        List<Word> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        double curStart = 0;
        boolean afterSpace = true;
        for (Lyric l : src) {
            String t = l.text;
            boolean brk = false;
            int i = 0;
            while (i < t.length() && (t.charAt(i) == '/' || t.charAt(i) == '\\')) {
                brk = true;
                i++;
            }
            String body = t.substring(i);
            if (body.indexOf('\n') >= 0 || body.indexOf('\r') >= 0) {
                brk = true;
                body = body.replace('\r', ' ').replace('\n', ' ');
            }
            String core = body.trim();
            if (core.isEmpty()) {              // a bare mark or space: just ends the word
                afterSpace = true;
                continue;
            }
            boolean leading = Character.isWhitespace(body.charAt(0));
            boolean trailing = Character.isWhitespace(body.charAt(body.length() - 1));
            if (cur.length() > 0 && (brk || leading || afterSpace)) {
                out.add(new Word(cur.toString(), curStart, Math.min(l.time, curStart + MAX_WORD_HOLD)));
                cur.setLength(0);
            }
            if (cur.length() == 0) curStart = l.time;
            cur.append(core);
            afterSpace = trailing;
        }
        if (cur.length() > 0) out.add(new Word(cur.toString(), curStart, curStart + LAST_WORD_HOLD));
        return out;
    }

    // ------------------------------------------------------------------ bytes

    private static boolean is(byte[] d, int p, String tag) {
        if (p < 0 || p + 4 > d.length) return false;
        for (int i = 0; i < 4; i++) if (d[p + i] != (byte) tag.charAt(i)) return false;
        return true;
    }

    private static int be16(byte[] d, int p) {
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    private static long be32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static long le32(byte[] d, int p) {
        return ((long) (d[p + 3] & 0xFF) << 24) | ((d[p + 2] & 0xFF) << 16) | ((d[p + 1] & 0xFF) << 8) | (d[p] & 0xFF);
    }
}
