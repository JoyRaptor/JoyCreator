package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

/**
 * One recognised word with its time span in the clip's source timeline.
 *
 * <p>The {@code struck} flag is the edit state: a struck word is "deleted"
 * (greyed strikethrough) but still present, so edits are lossless and
 * reversible. The timeline is re-derived from the words that are NOT struck.</p>
 */
public class TranscriptWord {

    @NonNull
    public final String text;

    /** Start time in source milliseconds. */
    public final long startMs;

    /** End time in source milliseconds. */
    public final long endMs;

    /** Edit state — true means struck out (excluded from the rendered video). */
    public boolean struck;

    /**
     * If true, the caption renderer starts a new phrase/line after this word.
     * Used to force a word or short phrase onto its own line without guessing
     * with spacer words.
     */
    public boolean forceLineBreakAfter;

    public TranscriptWord(@NonNull String text, long startMs, long endMs) {
        this.text = text;
        this.startMs = startMs;
        this.endMs = endMs;
        this.struck = false;
        this.forceLineBreakAfter = false;
    }

    public TranscriptWord(@NonNull String text, long startMs, long endMs, boolean struck) {
        this.text = text;
        this.startMs = startMs;
        this.endMs = endMs;
        this.struck = struck;
        this.forceLineBreakAfter = false;
    }

    public TranscriptWord(@NonNull String text, long startMs, long endMs,
                          boolean struck, boolean forceLineBreakAfter) {
        this.text = text;
        this.startMs = startMs;
        this.endMs = endMs;
        this.struck = struck;
        this.forceLineBreakAfter = forceLineBreakAfter;
    }
}

