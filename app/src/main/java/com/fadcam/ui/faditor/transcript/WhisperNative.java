package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

/**
 * Thin JNI bridge to whisper.cpp (see {@code app/src/main/cpp/whisper_jni.c}).
 *
 * <p>Lifecycle: {@link #initContext(String)} loads a ggml model file and returns
 * an opaque pointer; call {@link #fullTranscribe} per audio chunk, read the
 * resulting words via the segment accessors, then {@link #freeContext} when done.
 * Whisper is configured for one word per segment, so each segment is a single
 * word and its T0/T1 (in 10 ms units) are that word's timestamps.</p>
 *
 * <p>The native library is only built for arm64-v8a / armeabi-v7a; on other ABIs
 * {@link #isAvailable()} returns false and callers fall back to Vosk.</p>
 */
public final class WhisperNative {

    private static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("whisper_jni");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private WhisperNative() { }

    /** True if the native whisper library loaded for this device's ABI. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Load a ggml model file. Returns an opaque context pointer, or 0 on failure. */
    public static native long initContext(@NonNull String modelPath);

    /** Release a context returned by {@link #initContext(String)}. */
    public static native void freeContext(long ctxPtr);

    /**
     * Transcribe one chunk of mono 16 kHz float PCM (samples in [-1,1]).
     *
     * @return 0 on success, non-zero on failure.
     */
    public static native int fullTranscribe(long ctxPtr, int nThreads, @NonNull float[] audio);

    /** Number of word segments produced by the last {@link #fullTranscribe}. */
    public static native int getSegmentCount(long ctxPtr);

    /** Text of word segment {@code i}. */
    @NonNull
    public static native String getSegmentText(long ctxPtr, int i);

    /** Start time of segment {@code i}, in 10 ms units. */
    public static native long getSegmentT0(long ctxPtr, int i);

    /** End time of segment {@code i}, in 10 ms units. */
    public static native long getSegmentT1(long ctxPtr, int i);

    /** whisper.cpp system/SIMD info string (for diagnostics). */
    @NonNull
    public static native String systemInfo();
}
