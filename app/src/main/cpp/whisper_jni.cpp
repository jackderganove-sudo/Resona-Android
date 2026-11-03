// Resona: JNI bridge to whisper.cpp for AI sync (round 11 D1).
// Returns recognized words as UTF-8 "text\tstart\tend\n" lines (seconds);
// no lyrics are logged here.
#include <jni.h>
#include <atomic>
#include <string>
#include "whisper.h"

static std::atomic<bool> g_cancel(false);

static bool abort_cb(void *) { return g_cancel.load(); }

extern "C" JNIEXPORT jlong JNICALL
Java_moe_rukamori_archivetune_resona_aisync_WhisperNative_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_resona_aisync_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_resona_aisync_WhisperNative_nativeCancel(JNIEnv *, jobject, jboolean cancel) {
    g_cancel.store(cancel == JNI_TRUE);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_moe_rukamori_archivetune_resona_aisync_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint threads, jdouble offsetSeconds) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewByteArray(0);
    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.no_context = true;
    wp.single_segment = false;
    wp.token_timestamps = true;   // word times
    wp.max_len = 1;               // one word per segment …
    wp.split_on_word = true;      // … split on words, not tokens
    wp.language = "auto";
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = nullptr;

    int rc = whisper_full(ctx, wp, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0) return env->NewByteArray(0);

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        const double t0 = whisper_full_get_segment_t0(ctx, i) * 0.01 + offsetSeconds;
        const double t1 = whisper_full_get_segment_t1(ctx, i) * 0.01 + offsetSeconds;
        std::string t(text == nullptr ? "" : text);
        for (auto &c : t) if (c == '\t' || c == '\n') c = ' ';
        out += t + "\t" + std::to_string(t0) + "\t" + std::to_string(t1) + "\n";
    }
    // Raw UTF-8 bytes (NewStringUTF expects modified UTF-8 and breaks on emoji).
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}
