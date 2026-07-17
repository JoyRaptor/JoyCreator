package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text export/import for transcripts (JoyRaptor 2026-07-16: copy sections for external use — AI art
 * prompts etc. — and bring timestamped text back in as a caption track).
 *
 * <p>Export groups words into lines at natural pauses (≥{@link #LINE_GAP_MS}) or every
 * {@link #LINE_MAX_WORDS} words, and offers three flavors: plain prose, human-readable
 * {@code [mm:ss]} line stamps, and standard SRT. Struck words are skipped (they're "deleted").
 *
 * <p>Import auto-detects SRT, WebVTT, or {@code [mm:ss]}-prefixed lines. Block-level timing comes
 * from the stamps; word-level timing is interpolated evenly across each block's span (plenty for
 * captions). Returns {@code null} when nothing parseable is found.</p>
 */
public final class TranscriptIO {

    private static final long LINE_GAP_MS = 700;
    private static final int LINE_MAX_WORDS = 12;

    private TranscriptIO() { }

    // ── Export ──────────────────────────────────────────────────────────────

    /** Words as plain prose (struck words skipped). */
    @NonNull
    public static String toPlainText(@NonNull Transcript t) {
        StringBuilder sb = new StringBuilder();
        for (List<TranscriptWord> line : groupLines(t)) {
            appendWords(sb, line);
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** One {@code [mm:ss] line} per grouped line — the "simple readable" flavor. */
    @NonNull
    public static String toStampedText(@NonNull Transcript t) {
        StringBuilder sb = new StringBuilder();
        for (List<TranscriptWord> line : groupLines(t)) {
            long s = line.get(0).startMs;
            sb.append(String.format(Locale.US, "[%02d:%02d] ",
                    s / 60000, (s / 1000) % 60));
            appendWords(sb, line);
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Every word with its exact span — the app's own word-level view (what drives
     * the caption animations), one {@code [mm:ss.mmm-mm:ss.mmm] word} per line.
     * Round-trips losslessly through {@link #parse} (no interpolation on import).
     * Struck words are skipped like the other flavors.
     */
    @NonNull
    public static String toWordLevelText(@NonNull Transcript t) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptWord w : t.words) {
            if (w.struck) continue;
            sb.append('[').append(wordTime(w.startMs)).append('-')
                    .append(wordTime(w.endMs)).append("] ")
                    .append(w.text).append('\n');
        }
        return sb.toString().trim();
    }

    /** {@code h:mm:ss.mmm} above an hour, else {@code mm:ss.mmm}. */
    private static String wordTime(long ms) {
        long h = ms / 3600000;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%03d",
                    h, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000);
        }
        return String.format(Locale.US, "%02d:%02d.%03d",
                ms / 60000, (ms / 1000) % 60, ms % 1000);
    }

    /** Standard SRT blocks — the interoperable subtitle format. */
    @NonNull
    public static String toSrt(@NonNull Transcript t) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (List<TranscriptWord> line : groupLines(t)) {
            sb.append(n++).append('\n')
                    .append(srtTime(line.get(0).startMs)).append(" --> ")
                    .append(srtTime(line.get(line.size() - 1).endMs)).append('\n');
            appendWords(sb, line);
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    private static void appendWords(StringBuilder sb, List<TranscriptWord> line) {
        for (int i = 0; i < line.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(line.get(i).text);
        }
    }

    /** Unstruck words grouped into lines at pauses ≥LINE_GAP_MS or LINE_MAX_WORDS. */
    private static List<List<TranscriptWord>> groupLines(Transcript t) {
        List<List<TranscriptWord>> out = new ArrayList<>();
        List<TranscriptWord> cur = new ArrayList<>();
        TranscriptWord prev = null;
        for (TranscriptWord w : t.words) {
            if (w.struck) continue;
            boolean split = prev != null
                    && (w.startMs - prev.endMs >= LINE_GAP_MS || cur.size() >= LINE_MAX_WORDS);
            if (split && !cur.isEmpty()) {
                out.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(w);
            prev = w;
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private static String srtTime(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d,%03d",
                ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    // ── Import ──────────────────────────────────────────────────────────────

    /** {@code HH:MM:SS,mmm} or {@code MM:SS.mmm} (SRT/VTT timecodes). */
    private static final Pattern TIMECODE = Pattern.compile(
            "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})");
    /** SRT/VTT cue line: {@code start --> end}. */
    private static final Pattern CUE = Pattern.compile(
            "((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})");
    /** Simple readable stamp: {@code [mm:ss]} or {@code [h:mm:ss]} at line start. */
    private static final Pattern BRACKET = Pattern.compile(
            "^\\s*\\[(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?:[.,](\\d{1,3}))?\\]\\s*(.+)$");

    /** Word-level line: {@code [mm:ss.mmm-mm:ss.mmm] word} (hours optional). */
    private static final Pattern WORD_SPAN = Pattern.compile(
            "^\\s*\\[(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\.(\\d{1,3})\\s*-\\s*"
                    + "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\.(\\d{1,3})\\]\\s*(\\S.*?)\\s*$");

    /**
     * Parses SRT / WebVTT / {@code [mm:ss]}-stamped text into a Transcript with word timing
     * interpolated within each block — or, when the text is the word-level
     * {@code [start-end] word} flavor, reads the exact spans with no interpolation.
     * Returns null if no timed blocks were found.
     */
    @Nullable
    public static Transcript parse(@NonNull String raw) {
        Transcript wordLevel = parseWordLevel(raw);
        if (wordLevel != null) return wordLevel;
        List<long[]> spans = new ArrayList<>();   // {startMs, endMs}
        List<String> texts = new ArrayList<>();

        String[] lines = raw.replace("\r", "").split("\n");
        long pendingStart = -1, pendingEnd = -1;
        StringBuilder pendingText = new StringBuilder();
        for (String line : lines) {
            Matcher cue = CUE.matcher(line);
            if (cue.find()) {
                flushBlock(spans, texts, pendingStart, pendingEnd, pendingText);
                pendingStart = parseTimecode(cue.group(1));
                pendingEnd = parseTimecode(cue.group(2));
                pendingText.setLength(0);
                continue;
            }
            Matcher br = BRACKET.matcher(line);
            if (br.matches()) {
                flushBlock(spans, texts, pendingStart, pendingEnd, pendingText);
                long h = br.group(1) != null ? Long.parseLong(br.group(1)) : 0;
                long m = Long.parseLong(br.group(2));
                long sec = Long.parseLong(br.group(3));
                long msPart = br.group(4) != null
                        ? Long.parseLong((br.group(4) + "000").substring(0, 3)) : 0;
                pendingStart = ((h * 60 + m) * 60 + sec) * 1000 + msPart;
                pendingEnd = -1; // derived from the next block start (or word count)
                pendingText.setLength(0);
                pendingText.append(br.group(5));
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("WEBVTT")
                    || trimmed.matches("\\d+")) { // SRT cue index
                continue;
            }
            if (pendingStart >= 0) {
                if (pendingText.length() > 0) pendingText.append(' ');
                pendingText.append(trimmed);
            }
        }
        flushBlock(spans, texts, pendingStart, pendingEnd, pendingText);
        if (spans.isEmpty()) return null;

        // Bracket-stamped blocks have no explicit end — extend each to the next block's
        // start (or estimate ~330ms/word for the last one).
        for (int i = 0; i < spans.size(); i++) {
            if (spans.get(i)[1] < 0) {
                int wordCount = Math.max(1, texts.get(i).split("\\s+").length);
                long est = spans.get(i)[0] + wordCount * 330L;
                spans.get(i)[1] = (i + 1 < spans.size())
                        ? Math.min(spans.get(i + 1)[0], est + 4000) : est;
                if (spans.get(i)[1] <= spans.get(i)[0]) {
                    spans.get(i)[1] = spans.get(i)[0] + wordCount * 330L;
                }
            }
        }

        Transcript t = new Transcript();
        for (int i = 0; i < spans.size(); i++) {
            String[] ws = texts.get(i).trim().split("\\s+");
            long s = spans.get(i)[0], e = Math.max(s + 1, spans.get(i)[1]);
            long per = (e - s) / ws.length;
            for (int w = 0; w < ws.length; w++) {
                if (ws[w].isEmpty()) continue;
                long ws0 = s + w * per;
                t.words.add(new TranscriptWord(ws[w], ws0, w == ws.length - 1 ? e : ws0 + per));
            }
        }
        return t.words.isEmpty() ? null : t;
    }

    /**
     * Exact word-level import: every matching line contributes its span verbatim.
     * The whole text is treated as word-level when a majority of its non-empty
     * lines match (so a stray header line doesn't break it), else null.
     */
    @Nullable
    private static Transcript parseWordLevel(@NonNull String raw) {
        Transcript t = new Transcript();
        int nonEmpty = 0;
        for (String line : raw.replace("\r", "").split("\n")) {
            if (line.trim().isEmpty()) continue;
            nonEmpty++;
            Matcher m = WORD_SPAN.matcher(line);
            if (!m.matches()) continue;
            long s = spanMs(m.group(1), m.group(2), m.group(3), m.group(4));
            long e = spanMs(m.group(5), m.group(6), m.group(7), m.group(8));
            // A "word" line may carry a short phrase; keep it as one timed token
            // per line, exactly as exported.
            t.words.add(new TranscriptWord(m.group(9), s, Math.max(s + 1, e)));
        }
        if (t.words.isEmpty() || t.words.size() * 2 < nonEmpty) return null;
        return t;
    }

    private static long spanMs(@Nullable String h, String m, String s, String frac) {
        long hours = h != null ? Long.parseLong(h) : 0;
        long msPart = Long.parseLong((frac + "000").substring(0, 3));
        return ((hours * 60 + Long.parseLong(m)) * 60 + Long.parseLong(s)) * 1000 + msPart;
    }

    private static void flushBlock(List<long[]> spans, List<String> texts,
                                   long start, long end, StringBuilder text) {
        if (start >= 0 && text.length() > 0) {
            spans.add(new long[]{start, end});
            texts.add(text.toString());
        }
        text.setLength(0);
    }

    private static long parseTimecode(String tc) {
        Matcher m = TIMECODE.matcher(tc);
        if (!m.matches()) return 0;
        long h = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
        long min = Long.parseLong(m.group(2));
        long s = Long.parseLong(m.group(3));
        long ms = Long.parseLong((m.group(4) + "000").substring(0, 3));
        return ((h * 60 + min) * 60 + s) * 1000 + ms;
    }
}
