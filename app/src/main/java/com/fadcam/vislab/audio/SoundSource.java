package com.fadcam.vislab.audio;

import android.content.Context;

import androidx.annotation.NonNull;

/** Something VisLab can listen to: the microphone, a song, the phone's own audio. */
public interface SoundSource {

    /** Begin feeding the ring. Returns false when the source cannot start (permission, no file). */
    boolean start(@NonNull Context context);

    void stop();

    void setPaused(boolean paused);

    /**
     * Absolute frame the listener is hearing right now (a song's speaker position), or -1 for a
     * live source where the newest audio is what is heard.
     */
    long playheadFrame();

    /**
     * Called each frame, right after the analysis, with the frame being heard. A source that knows
     * exactly what it is playing (a MIDI file knows every kick) overwrites the guessed values in
     * {@code f}; everything else leaves it alone, which is what this default does, so the
     * analysis of a microphone, a phone's audio or a recorded song is untouched.
     *
     * @param playheadFrame the same value {@link #playheadFrame()} gave for this frame (always >= 0 here)
     * @param dt            seconds since the last frame
     */
    default void refine(@NonNull Features f, long playheadFrame, double dt) {
    }
}
