// JNI bridge to whisper.cpp for FadCam's offline transcription.
//
// Exposes just enough of the whisper.cpp API for word-level transcription:
// load a model, run whisper_full on a chunk of 16 kHz mono float PCM with
// word-level timestamps enabled, then read back one word per "segment"
// (max_len = 1 + split_on_word makes whisper emit a segment per word).
//
// All times whisper reports are in 10 ms units; callers multiply by 10 for ms.

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define JFN(name) Java_com_fadcam_ui_faditor_transcript_WhisperNative_##name

JNIEXPORT jlong JNICALL
JFN(initContext)(JNIEnv *env, jclass clazz, jstring model_path) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    // CPU-only on phones; no GPU backend.
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (ctx == NULL) {
        LOGW("whisper_init failed for %s", path);
    }
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
JFN(freeContext)(JNIEnv *env, jclass clazz, jlong ptr) {
    (void) env; (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (ctx != NULL) whisper_free(ctx);
}

// Transcribe one chunk of mono 16 kHz float audio. Returns 0 on success.
JNIEXPORT jint JNICALL
JFN(fullTranscribe)(JNIEnv *env, jclass clazz, jlong ptr, jint n_threads,
                    jfloatArray audio) {
    (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (ctx == NULL) return -1;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = n_threads;
    params.offset_ms = 0;
    params.no_context = true;       // chunks are independent; we stitch in Java
    params.single_segment = false;
    // Word-level timestamps: one word per segment.
    params.token_timestamps = true;
    params.max_len = 1;
    params.split_on_word = true;
    params.suppress_blank = true;

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, params, samples, n_samples);
    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
JFN(getSegmentCount)(JNIEnv *env, jclass clazz, jlong ptr) {
    (void) env; (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    return ctx == NULL ? 0 : whisper_full_n_segments(ctx);
}

JNIEXPORT jstring JNICALL
JFN(getSegmentText)(JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    const char *text = whisper_full_get_segment_text(ctx, i);
    return (*env)->NewStringUTF(env, text == NULL ? "" : text);
}

// Start time of segment i, in 10 ms units.
JNIEXPORT jlong JNICALL
JFN(getSegmentT0)(JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    (void) env; (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    return whisper_full_get_segment_t0(ctx, i);
}

// End time of segment i, in 10 ms units.
JNIEXPORT jlong JNICALL
JFN(getSegmentT1)(JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    (void) env; (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    return whisper_full_get_segment_t1(ctx, i);
}

JNIEXPORT jstring JNICALL
JFN(systemInfo)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    const char *info = whisper_print_system_info();
    return (*env)->NewStringUTF(env, info == NULL ? "" : info);
}
