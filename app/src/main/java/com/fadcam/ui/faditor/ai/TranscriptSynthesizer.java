package com.fadcam.ui.faditor.ai;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.transcript.NamedTranscript;
import com.fadcam.ui.faditor.transcript.Transcript;
import com.fadcam.ui.faditor.transcript.TranscriptWord;
import com.fadcam.ui.faditor.util.SilenceDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transcript Synthesis Engine.
 *
 * <p>Merges two transcript versions — typically Vosk (precise timing, messy
 * words) and Whisper (clean words, rough timing) — into a single "best of
 * both" transcript. Uses silence detection data to confirm filler-word gaps.</p>
 *
 * <p>The algorithm:
 * <ol>
 *   <li><b>Align</b>: Subsequence-align Whisper's clean words against Vosk's
 *       word list by position (like git diff for words).</li>
 *   <li><b>Transfer timing</b>: For each aligned Whisper word, take Vosk's
 *       precise timestamp for that position.</li>
 *   <li><b>Detect fillers</b>: Where Vosk has a word but Whisper doesn't,
 *       check if the timespan overlaps a silence gap. If yes → mark as a
 *       filler word and pre-strike it (cuttable).</li>
 *   <li><b>Detect mishears</b>: If no silence overlap, the Vosk word was
 *       likely a mishearing — drop it silently.</li>
 *   <li><b>Output</b>: A new transcript with Whisper's words, Vosk's timing,
 *       and filler words pre-marked for cutting.</li>
 * </ol></p>
 */
public class TranscriptSynthesizer {

    private static final String TAG = "TranscriptSynth";

    @NonNull private final Context context;
    @NonNull private final ProjectStorage storage;
    @NonNull private final String projectId;

    public TranscriptSynthesizer(@NonNull Context context, @NonNull String projectId) {
        this.context = context.getApplicationContext();
        this.storage = new ProjectStorage(this.context);
        this.projectId = projectId;
    }

    /**
     * Result of a synthesis run.
     */
    public static class Result {
        public final boolean success;
        @Nullable public final String transcriptId;
        public final int wordCount;
        public final int fillersDetected;
        @Nullable public final String error;

        private Result(boolean success, @Nullable String id, int words, int fillers,
                       @Nullable String error) {
            this.success = success;
            this.transcriptId = id;
            this.wordCount = words;
            this.fillersDetected = fillers;
            this.error = error;
        }

        public static Result ok(@NonNull String id, int words, int fillers) {
            return new Result(true, id, words, fillers, null);
        }

        public static Result fail(@NonNull String error) {
            return new Result(false, null, 0, 0, error);
        }
    }

    /**
     * Run transcript synthesis on a clip.
     *
     * @param clipId       The clip to synthesize
     * @param voskLabel    Label fragment to identify the Vosk transcript (e.g. "vosk")
     * @param whisperLabel Label fragment to identify the Whisper transcript (e.g. "whisper")
     * @param runSilence   If true, run silence detection first to get gap data
     * @param sensitivity  Silence detection sensitivity (0..1)
     * @return Result with the new synthesized transcript
     */
    @NonNull
    public Result synthesize(@NonNull String clipId,
                             @NonNull String voskLabel,
                             @NonNull String whisperLabel,
                             boolean runSilence,
                             float sensitivity) {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return Result.fail("Project not found");

        Clip clip = null;
        for (int i = 0; i < proj.getTimeline().getClipCount(); i++) {
            if (proj.getTimeline().getClip(i).getId().equals(clipId)) {
                clip = proj.getTimeline().getClip(i);
                break;
            }
        }
        if (clip == null) return Result.fail("Clip not found: " + clipId);

        // Find the Vosk and Whisper transcripts
        NamedTranscript voskNt = null;
        NamedTranscript whisperNt = null;
        for (NamedTranscript nt : clip.getTranscripts()) {
            String label = (nt.engine + " " + nt.label).toLowerCase();
            if (label.contains(voskLabel.toLowerCase())) voskNt = nt;
            if (label.contains(whisperLabel.toLowerCase())) whisperNt = nt;
        }

        if (voskNt == null) return Result.fail("No Vosk transcript found. Generate one first.");
        if (whisperNt == null) return Result.fail("No Whisper transcript found. Generate one first.");

        Transcript vosk = voskNt.transcript;
        Transcript whisper = whisperNt.transcript;

        if (vosk.words.isEmpty()) return Result.fail("Vosk transcript is empty");
        if (whisper.words.isEmpty()) return Result.fail("Whisper transcript is empty");

        // Optionally run silence detection to get gap data
        List<long[]> silenceSpans = clip.getSilenceCandidates();
        if (runSilence && (silenceSpans == null || silenceSpans.isEmpty())) {
            FLog.i(TAG, "Running silence detection for synthesis...");
            silenceSpans = runSilenceDetection(clip, sensitivity);
            if (silenceSpans == null) silenceSpans = new ArrayList<>();
        }

        // Run the core synthesis algorithm
        SynthesisOutput output = alignAndMerge(vosk.words, whisper.words, silenceSpans);

        // Build the new transcript
        Transcript synthesized = new Transcript();
        for (MergedWord mw : output.mergedWords) {
            TranscriptWord w = new TranscriptWord(mw.text, mw.startMs, mw.endMs, mw.struck);
            synthesized.words.add(w);
        }

        // Save as a new named transcript version
        String newId = "synth_" + System.currentTimeMillis();
        NamedTranscript newNt = new NamedTranscript(newId,
                "Synthesized (Vosk timing + Whisper words)", "synth", synthesized);
        clip.addTranscript(newNt);
        // Re-synthesis REPLACES a previous un-edited synth run instead of stacking.
        // NOTE the deliberate limitation: synthesis auto-strikes fillers, and an
        // auto-struck word is indistinguishable from a user strike after the fact,
        // so TranscriptDedup treats such runs as "edited" and keeps them (doubt ⇒
        // keep — a user-touched run is never silently dropped). Only genuinely
        // pristine older synth runs are collapsed.
        if (!synthesized.words.isEmpty()) {
            com.fadcam.ui.faditor.transcript.TranscriptDedup.dedupClip(clip, true);
        }

        // Save the silence candidates if we ran detection
        if (runSilence && silenceSpans != null && !silenceSpans.isEmpty()) {
            clip.setSilenceCandidates(silenceSpans);
        }

        storage.save(proj);
        AIChatState.signalModified(projectId);

        FLog.i(TAG, "Synthesis complete: " + synthesized.words.size() + " words, "
                + output.fillerCount + " fillers detected");

        return Result.ok(newId, synthesized.words.size(), output.fillerCount);
    }

    // ── Core algorithm ──────────────────────────────────────────────

    private static class MergedWord {
        String text;
        long startMs;
        long endMs;
        boolean struck; // pre-marked as filler/cuttable
    }

    private static class SynthesisOutput {
        List<MergedWord> mergedWords = new ArrayList<>();
        int fillerCount = 0;
    }

    /**
     * The core merge algorithm — bidirectional subsequence alignment.
     *
     * <p>Both transcripts can have extra words the other doesn't:
     * <ul>
     *   <li>Vosk may have fillers (um/uh) that Whisper cleaned up</li>
     *   <li>Whisper may have words Vosk missed (better recognition)</li>
     *   <li>Whisper may expand contractions ("don't" → "do not")</li>
     * </ul></p>
     *
     * <p>Uses a dynamic-programming LCS (longest common subsequence) approach
     * for robust alignment, then walks the alignment to produce merged output.
     * For each aligned pair, we prefer the word that looks more correct
     * (longer = usually better, since mishears tend to be shorter/garbled).</p>
     */
    @NonNull
    private SynthesisOutput alignAndMerge(
            @NonNull List<TranscriptWord> voskWords,
            @NonNull List<TranscriptWord> whisperWords,
            @NonNull List<long[]> silenceSpans) {

        SynthesisOutput output = new SynthesisOutput();

        // Build LCS alignment table
        int n = voskWords.size();
        int m = whisperWords.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (wordsMatch(voskWords.get(i).text, whisperWords.get(j).text)) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        // Walk the alignment
        int vi = 0, wi = 0;
        while (vi < n && wi < m) {
            if (wordsMatch(voskWords.get(vi).text, whisperWords.get(wi).text)) {
                // Aligned! Pick the better word + Vosk timing
                MergedWord mw = new MergedWord();
                mw.startMs = voskWords.get(vi).startMs;
                mw.endMs = voskWords.get(vi).endMs;

                // Prefer the longer/less-garbled word
                String vText = voskWords.get(vi).text;
                String wText = whisperWords.get(wi).text;
                // Strip possessive 's for comparison
                String vNorm = normalize(vText);
                String wNorm = normalize(wText);
                if (wNorm.length() > vNorm.length() && wText.length() > vText.length()) {
                    mw.text = wText; // Whisper's version is more complete
                } else if (vNorm.length() > wNorm.length() && vText.length() > wText.length()) {
                    mw.text = vText; // Vosk's version is more complete
                } else {
                    // Same length — prefer Whisper (usually cleaner capitalization/punctuation)
                    mw.text = wText;
                }
                mw.struck = false;
                output.mergedWords.add(mw);
                vi++;
                wi++;
            } else if (lcs[vi + 1][wi] >= lcs[vi][wi + 1]) {
                // Vosk has an extra word here (filler or mishearing)
                TranscriptWord vWord = voskWords.get(vi);
                if (overlapsSilence(vWord, silenceSpans)) {
                    // Real filler — keep it but mark for cutting
                    MergedWord mw = new MergedWord();
                    mw.text = vWord.text;
                    mw.startMs = vWord.startMs;
                    mw.endMs = vWord.endMs;
                    mw.struck = true;
                    output.mergedWords.add(mw);
                    output.fillerCount++;
                } else if (isLikelyFiller(vWord.text)) {
                    // Common filler word even without silence overlap
                    MergedWord mw = new MergedWord();
                    mw.text = vWord.text;
                    mw.startMs = vWord.startMs;
                    mw.endMs = vWord.endMs;
                    mw.struck = true;
                    output.mergedWords.add(mw);
                    output.fillerCount++;
                }
                // If not a filler and no silence, it's likely a mishearing — drop
                vi++;
            } else {
                // Whisper has an extra word here — Vosk missed it
                // Keep it with Whisper's own timing (estimated)
                TranscriptWord wWord = whisperWords.get(wi);
                MergedWord mw = new MergedWord();
                mw.text = wWord.text;
                mw.startMs = wWord.startMs;
                mw.endMs = wWord.endMs;
                mw.struck = false;
                output.mergedWords.add(mw);
                wi++;
            }
        }

        // Handle remaining Vosk words
        while (vi < n) {
            TranscriptWord vWord = voskWords.get(vi);
            if (overlapsSilence(vWord, silenceSpans) || isLikelyFiller(vWord.text)) {
                MergedWord mw = new MergedWord();
                mw.text = vWord.text;
                mw.startMs = vWord.startMs;
                mw.endMs = vWord.endMs;
                mw.struck = true;
                output.mergedWords.add(mw);
                output.fillerCount++;
            }
            vi++;
        }

        // Handle remaining Whisper words
        while (wi < m) {
            TranscriptWord wWord = whisperWords.get(wi);
            MergedWord mw = new MergedWord();
            mw.text = wWord.text;
            mw.startMs = wWord.startMs;
            mw.endMs = wWord.endMs;
            mw.struck = false;
            output.mergedWords.add(mw);
            wi++;
        }

        return output;
    }

    /** Common filler words that should be marked as cuttable. */
    private boolean isLikelyFiller(@NonNull String word) {
        String n = normalize(word);
        return n.equals("um") || n.equals("uh") || n.equals("ah") || n.equals("er")
                || n.equals("umm") || n.equals("uhh") || n.equals("hmm")
                || n.equals("mm") || n.equals("hm") || n.equals("like")
                || n.equals("so") || n.equals("basically") || n.equals("literally")
                || n.equals("actually") || n.equals("right") || n.equals("okay")
                || n.equals("ok") || n.equals("yeah");
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Case-insensitive, punctuation-stripped word comparison.
     * "So," matches "so" matches "SO".
     */
    private boolean wordsMatch(@NonNull String a, @NonNull String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.equals(nb)) return true;
        // Handle common Vosk/Whisper disagreements:
        // "gonna" vs "going to", "yeah" vs "ya", etc.
        // For now, also check if one starts with the other (short words)
        if (na.length() >= 3 && nb.length() >= 3) {
            if (na.startsWith(nb) || nb.startsWith(na)) return true;
        }
        return false;
    }

    @NonNull
    private String normalize(@NonNull String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .trim();
    }

    /**
     * Check if a word's timespan overlaps any silence gap.
     */
    private boolean overlapsSilence(@NonNull TranscriptWord word,
                                    @NonNull List<long[]> silenceSpans) {
        for (long[] span : silenceSpans) {
            // Overlap if word starts before span ends AND word ends after span starts
            if (word.startMs < span[1] && word.endMs > span[0]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Run silence detection synchronously and return the silent spans.
     */
    @Nullable
    private List<long[]> runSilenceDetection(@NonNull Clip clip, float sensitivity) {
        AtomicReference<List<long[]>> resultRef = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        SilenceDetector sd = new SilenceDetector(context);
        sd.detect(clip.getSourceUri(), clip.getInPointMs(), clip.getOutPointMs(),
                sensitivity, new SilenceDetector.Callback() {
                    @Override
                    public void onResult(@NonNull List<long[]> keepRangesMs,
                                         int gapsRemoved, long msSaved) {
                        // Convert keep ranges to silence gaps
                        List<long[]> silence = new ArrayList<>();
                        long cursor = clip.getInPointMs();
                        for (long[] keep : keepRangesMs) {
                            if (keep[0] > cursor) {
                                silence.add(new long[]{cursor, keep[0]});
                            }
                            cursor = Math.max(cursor, keep[1]);
                        }
                        if (cursor < clip.getOutPointMs()) {
                            silence.add(new long[]{cursor, clip.getOutPointMs()});
                        }
                        resultRef.set(silence);
                        latch.countDown();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        FLog.e(TAG, "Silence detection failed during synthesis", e);
                        latch.countDown();
                    }
                });

        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            FLog.e(TAG, "Silence detection interrupted", e);
        }
        sd.shutdown();
        return resultRef.get();
    }

    /**
     * Build a human-readable summary of a synthesis result for the AI.
     */
    @NonNull
    public static String describeResult(@NonNull Result result) {
        if (!result.success) return "Synthesis failed: " + result.error;
        StringBuilder sb = new StringBuilder();
        sb.append("Transcript synthesis complete!\n");
        sb.append("  Words: ").append(result.wordCount).append("\n");
        sb.append("  Fillers detected and pre-marked for cutting: ")
                .append(result.fillersDetected).append("\n");
        sb.append("  Transcript ID: ").append(result.transcriptId).append("\n\n");
        sb.append("The synthesized transcript uses Whisper's clean words with ");
        sb.append("Vosk's precise timing. Filler words (um, uh, etc.) detected ");
        sb.append("via silence gap cross-checking are pre-marked as [CUT].\n\n");
        sb.append("You can now ask me to cut all filler words, or the user can ");
        sb.append("review them in the transcript panel.");
        return sb.toString();
    }
}
