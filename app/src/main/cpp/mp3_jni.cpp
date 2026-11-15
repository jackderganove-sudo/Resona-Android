// Resona: MP3 encoding (LAME) for the PS5 stick – 320 kbit/s CBR, no ID3 (written in Kotlin).
#include <jni.h>
#include <vector>
#include "lame.h"

extern "C" JNIEXPORT jlong JNICALL
Java_moe_rukamori_archivetune_resona_files_Mp3Native_nativeOpen(JNIEnv *, jobject, jint sampleRate, jint channels, jint kbps) {
    lame_t lame = lame_init();
    if (lame == nullptr) return 0;
    lame_set_in_samplerate(lame, sampleRate);
    lame_set_out_samplerate(lame, sampleRate);
    lame_set_num_channels(lame, channels);
    lame_set_mode(lame, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(lame, vbr_off);
    lame_set_brate(lame, kbps);
    lame_set_quality(lame, 2);
    lame_set_write_id3tag_automatic(lame, 0);
    lame_set_bWriteVbrTag(lame, 0);
    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        return 0;
    }
    return reinterpret_cast<jlong>(lame);
}

/** Interleaved 16-bit PCM → MP3 bytes. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_moe_rukamori_archivetune_resona_files_Mp3Native_nativeEncode(JNIEnv *env, jobject, jlong handle, jshortArray pcm, jint frames) {
    auto lame = reinterpret_cast<lame_t>(handle);
    jshort *samples = env->GetShortArrayElements(pcm, nullptr);
    std::vector<unsigned char> out(static_cast<size_t>(1.25 * frames + 7200));
    int n = lame_get_num_channels(lame) == 1
        ? lame_encode_buffer(lame, samples, samples, frames, out.data(), static_cast<int>(out.size()))
        : lame_encode_buffer_interleaved(lame, samples, frames, out.data(), static_cast<int>(out.size()));
    env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);
    if (n < 0) n = 0;
    jbyteArray result = env->NewByteArray(n);
    env->SetByteArrayRegion(result, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_moe_rukamori_archivetune_resona_files_Mp3Native_nativeFlush(JNIEnv *env, jobject, jlong handle) {
    auto lame = reinterpret_cast<lame_t>(handle);
    std::vector<unsigned char> out(7200);
    int n = lame_encode_flush(lame, out.data(), static_cast<int>(out.size()));
    if (n < 0) n = 0;
    jbyteArray result = env->NewByteArray(n);
    env->SetByteArrayRegion(result, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_resona_files_Mp3Native_nativeClose(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) lame_close(reinterpret_cast<lame_t>(handle));
}
