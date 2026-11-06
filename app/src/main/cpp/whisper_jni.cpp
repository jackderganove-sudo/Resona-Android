// Resona: JNI bridge to whisper.cpp for AI sync (round 11 D1).
// Returns recognized words as UTF-8 "text\tstart\tend\n" lines (seconds);
// no lyrics are logged here.
#include <android/log.h>
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
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint threads, jdouble offsetSeconds, jstring language) {
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
    // Language of the lyrics when known; music makes auto-detection unreliable.
    const char *lang = language != nullptr ? env->GetStringUTFChars(language, nullptr) : nullptr;
    wp.language = lang != nullptr ? lang : "auto";
    // Singing over loud music looks like "no speech": with the default
    // threshold whole windows are dropped (0 words on the Pixel). Same as
    // the iPad: threshold off, repeated choruses are not hallucinations.
    wp.no_speech_thold = 1.1f;
    wp.entropy_thold = 2.8f;
    // One pass per window: without the threshold, retries at higher temperatures
    // on hard music took 4.4 × the song's length (Pixel: 207 s audio in 905 s).
    wp.temperature_inc = 0.0f;
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = nullptr;

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, wp, data, n);
    // Numbers only, never text: where the time goes per 30 s window.
    if (auto *t = whisper_get_timings(ctx)) {
        __android_log_print(ANDROID_LOG_INFO, "ResonaAISync", "window at %.0f s: encode %.0f ms, decode %.0f ms, batch %.0f ms, prompt %.0f ms, threads %d, rc %d",
                            offsetSeconds, t->encode_ms, t->decode_ms, t->batchd_ms, t->prompt_ms, threads, rc);
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
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
