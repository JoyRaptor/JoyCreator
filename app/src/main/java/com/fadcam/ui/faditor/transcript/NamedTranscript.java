package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import java.util.UUID;

/**
 * One named transcript version for a clip. A clip can hold several — e.g. a
 * fast Vosk pass with tight word timing for edit-by-text, plus a slower Whisper
 * pass with much better words for on-screen captions — and the user switches
 * which one is active.
 */
public class NamedTranscript {

    @NonNull public final String id;
    @NonNull public String label;
    /** "vosk" or "whisper" — used for the version chip's icon/colour. */
    @NonNull public String engine;
    @NonNull public Transcript transcript;

    public NamedTranscript(@NonNull String id, @NonNull String label,
                           @NonNull String engine, @NonNull Transcript transcript) {
        this.id = id;
        this.label = label;
        this.engine = engine;
        this.transcript = transcript;
    }

    public NamedTranscript(@NonNull String label, @NonNull String engine,
                           @NonNull Transcript transcript) {
        this(UUID.randomUUID().toString(), label, engine, transcript);
    }

    /** Deep copy (new transcript instance, same id/label/engine). */
    @NonNull
    public NamedTranscript copy() {
        return new NamedTranscript(id, label, engine, transcript.copy());
    }
}
