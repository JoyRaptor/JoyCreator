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
}
