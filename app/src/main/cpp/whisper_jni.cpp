// Resona: JNI bridge to whisper.cpp for AI sync (round 11 D1).
// Returns recognized words as UTF-8 "text\tstart\tend\n" lines (seconds);
// no lyrics are logged here.
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>
#include "whisper.h"

static std::atomic<bool> g_cancel(false);

static bool abort_cb(void *) { return g_cancel.load(); }

// Measuring: only whisper's "… time = … ms / … runs" lines (numbers, never text).
static void timing_log(enum ggml_log_level, const char *text, void *) {
    if (text != nullptr && strstr(text, " time = ") != nullptr) {
        __android_log_print(ANDROID_LOG_INFO, "ResonaAISync", "whisper%s", text);
    }
}
static void silent_log(enum ggml_log_level, const char *, void *) {}

// "key=value,key=value" → value of key, or the fallback.
static float option(const std::string &options, const char *key, float fallback) {
    const std::string needle = std::string(key) + "=";
    size_t at = options.find(needle);
    if (at == std::string::npos || (at > 0 && options[at - 1] != ',')) return fallback;
    return strtof(options.c_str() + at + needle.size(), nullptr);
}

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
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint threads, jdouble offsetSeconds, jstring language, jstring optionsJ) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewByteArray(0);
    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);

    std::string options;
    if (optionsJ != nullptr) {
        const char *o = env->GetStringUTFChars(optionsJ, nullptr);
        options = o;
        env->ReleaseStringUTFChars(optionsJ, o);
    }
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.no_context = true;
    // One pass per window (we cut the song into 30 s windows ourselves). Otherwise
    // whisper moves on only a little on music and re-encodes again and again:
    // encoding took 1 s, but a window took 20 to 110 s on the Pixel.
    wp.single_segment = option(options, "single", 1) != 0;
    wp.token_timestamps = true;   // word times
    wp.max_len = 1;               // one word per segment …
    wp.split_on_word = true;      // … split on words, not tokens
    // Language of the lyrics when known; music makes auto-detection unreliable.
    const char *lang = language != nullptr ? env->GetStringUTFChars(language, nullptr) : nullptr;
    wp.language = lang != nullptr ? lang : "auto";
    // Singing over loud music looks like "no speech": with the default
    // threshold whole windows are dropped (0 words on the Pixel). Same as
    // the iPad: threshold off, repeated choruses are not hallucinations.
    wp.no_speech_thold = option(options, "nospeech", 1.1f);
    wp.entropy_thold = option(options, "entropy", 2.8f);
    wp.logprob_thold = option(options, "logprob", wp.logprob_thold);
    wp.audio_ctx = static_cast<int>(option(options, "actx", 0));
    wp.max_tokens = static_cast<int>(option(options, "maxtok", 0));
    // One pass per window: without the threshold, retries at higher temperatures
    // on hard music took 4.4 × the song's length (Pixel: 207 s audio in 905 s).
    wp.temperature_inc = option(options, "tempinc", 0.0f);
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = nullptr;

    whisper_log_set(silent_log, nullptr);
    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, wp, data, n);
    if (option(options, "timings", 0) != 0) {
        whisper_log_set(timing_log, nullptr);
        whisper_print_timings(ctx);
        whisper_log_set(silent_log, nullptr);
    }
    // Numbers only, never text: where the time goes per 30 s window.
    if (auto *t = whisper_get_timings(ctx)) {
        __android_log_print(ANDROID_LOG_INFO, "ResonaAISync", "window at %.0f s: encode %.0f ms, decode %.0f ms, batch %.0f ms, prompt %.0f ms, threads %d, rc %d",
                            offsetSeconds, t->encode_ms, t->decode_ms, t->batchd_ms, t->prompt_ms, threads, rc);
        delete t;  // whisper_get_timings allocates a new struct per call.
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

// Speech segments of the whole song as [start, end, start, end, …] in seconds.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_moe_rukamori_archivetune_resona_aisync_WhisperNative_nativeVad(
        JNIEnv *env, jobject, jstring modelPath, jfloatArray samples, jint threads,
        jfloat threshold, jint minSpeechMs, jint minSilenceMs, jint padMs) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_vad_context_params cp = whisper_vad_default_context_params();
    cp.n_threads = threads;
    cp.use_gpu = false;
    whisper_log_set(silent_log, nullptr);
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(modelPath, path);
    if (vctx == nullptr) return env->NewFloatArray(0);

    whisper_vad_params params = whisper_vad_default_params();
    params.threshold = threshold;
    params.min_speech_duration_ms = minSpeechMs;
    params.min_silence_duration_ms = minSilenceMs;
    params.speech_pad_ms = padMs;
    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    whisper_vad_segments *segments = whisper_vad_segments_from_samples(vctx, params, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    int count = segments != nullptr ? whisper_vad_segments_n_segments(segments) : 0;
    jfloatArray out = env->NewFloatArray(count * 2);
    if (count > 0) {
        auto *values = new jfloat[count * 2];
        for (int i = 0; i < count; ++i) {
            values[2 * i] = whisper_vad_segments_get_segment_t0(segments, i) / 100.0f;
            values[2 * i + 1] = whisper_vad_segments_get_segment_t1(segments, i) / 100.0f;
        }
        env->SetFloatArrayRegion(out, 0, count * 2, values);
        delete[] values;
    }
    if (segments != nullptr) whisper_vad_free_segments(segments);
    whisper_vad_free(vctx);
    return out;
}
