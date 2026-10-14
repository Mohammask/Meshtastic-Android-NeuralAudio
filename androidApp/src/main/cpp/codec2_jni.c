#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>
#include "codec2.h"

#define TAG "Codec2Jni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Stubs for experimental wideband mode (not used in 8 kHz narrowband mesh)
void codec2_encode_wb(struct CODEC2 *c2, unsigned char * bits, short speech[]) {
    (void)c2; (void)bits; (void)speech;
}
void codec2_decode_wb(struct CODEC2 *c2, short speech[], const unsigned char * bits) {
    (void)c2; (void)speech; (void)bits;
}

JNIEXPORT jlong JNICALL
Java_org_meshtastic_feature_messaging_audio_Codec2Jni_nativeCreate(JNIEnv *env, jobject thiz, jint mode) {
    struct CODEC2 *c2 = codec2_create((int)mode);
    if (!c2) {
        LOGE("Failed to create Codec2 instance with mode: %d", mode);
        return 0;
    }
    // Disable bass_boost (0) while retaining formant sharpening post-filter (1).
    // David Rowe's default bass_boost=1 adds 3-6 dB of low-frequency boost (< 1kHz),
    // which drowns out the 1.5 kHz - 3.5 kHz consonant formants on mobile speakers.
    codec2_set_lpc_post_filter(c2, 1, 0, 0.2f, 0.4f);
    return (jlong)(intptr_t)c2;
}

JNIEXPORT void JNICALL
Java_org_meshtastic_feature_messaging_audio_Codec2Jni_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (c2) {
        codec2_destroy(c2);
    }
}

JNIEXPORT jint JNICALL
Java_org_meshtastic_feature_messaging_audio_Codec2Jni_nativeGetSamplesPerFrame(JNIEnv *env, jobject thiz, jlong handle) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (!c2) return 0;
    return (jint)codec2_samples_per_frame(c2);
}

JNIEXPORT jint JNICALL
Java_org_meshtastic_feature_messaging_audio_Codec2Jni_nativeGetBitsPerFrame(JNIEnv *env, jobject thiz, jlong handle) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (!c2) return 0;
    return (jint)codec2_bits_per_frame(c2);
}

JNIEXPORT jint JNICALL
Java_org_meshtastic_feature_messaging_audio_Codec2Jni_nativeGetBytesPerFrame(JNIEnv *env, jobject thiz, jlong handle) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (!c2) return 0;
    int nbit = codec2_bits_per_frame(c2);
    return (jint)((nbit + 7) / 8);
}

JNIEXPORT jint JNICALL
Java_org_meshtastic_feature_messaging_audio_Codec2Jni_nativeEncodeFrame(JNIEnv *env, jobject thiz, jlong handle,
                                                                       jshortArray speechIn, jint inOffset,
                                                                       jbyteArray bitsOut, jint outOffset) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (!c2) return -1;
    int nsam = codec2_samples_per_frame(c2);
    int nbyte = (codec2_bits_per_frame(c2) + 7) / 8;

    jshort *inBuf = (jshort *)malloc(nsam * sizeof(jshort));
    unsigned char *outBuf = (unsigned char *)malloc(nbyte * sizeof(unsigned char));
    if (!inBuf || !outBuf) {
        if (inBuf) free(inBuf);
        if (outBuf) free(outBuf);
        return -2;
    }

    (*env)->GetShortArrayRegion(env, speechIn, inOffset, nsam, inBuf);
    codec2_encode(c2, outBuf, inBuf);
    (*env)->SetByteArrayRegion(env, bitsOut, outOffset, nbyte, (const jbyte *)outBuf);

    free(inBuf);
    free(outBuf);
    return 0;
}

JNIEXPORT jint JNICALL
Java_org_meshtastic_feature_messaging_audio_Codec2Jni_nativeDecodeFrame(JNIEnv *env, jobject thiz, jlong handle,
                                                                       jbyteArray bitsIn, jint inOffset,
                                                                       jshortArray speechOut, jint outOffset) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (!c2) return -1;
    int nsam = codec2_samples_per_frame(c2);
    int nbyte = (codec2_bits_per_frame(c2) + 7) / 8;

    unsigned char *inBuf = (unsigned char *)malloc(nbyte * sizeof(unsigned char));
    jshort *outBuf = (jshort *)malloc(nsam * sizeof(jshort));
    if (!inBuf || !outBuf) {
        if (inBuf) free(inBuf);
        if (outBuf) free(outBuf);
        return -2;
    }

    (*env)->GetByteArrayRegion(env, bitsIn, inOffset, nbyte, (jbyte *)inBuf);
    codec2_decode(c2, outBuf, inBuf);
    (*env)->SetShortArrayRegion(env, speechOut, outOffset, nsam, outBuf);

    free(inBuf);
    free(outBuf);
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_org_meshtastic_feature_messaging_audio_Codec2Jni_nativeEncodeAll(JNIEnv *env, jobject thiz, jlong handle, jshortArray speechIn) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (!c2) return NULL;
    jsize totalSamples = (*env)->GetArrayLength(env, speechIn);
    int nsam = codec2_samples_per_frame(c2);
    int nbit = codec2_bits_per_frame(c2);
    if (nsam <= 0 || nbit <= 0 || totalSamples < nsam) return NULL;

    int numFrames = totalSamples / nsam;
    jshort *samples = (*env)->GetShortArrayElements(env, speechIn, NULL);
    if (!samples) return NULL;

    jbyteArray result = NULL;

    if (nbit == 28) {
        // Continuous 28-bit packing: 2 frames = 56 bits = exactly 7 bytes (zero padding bits)
        int numPairs = numFrames / 2;
        int hasOdd = numFrames % 2;
        int totalBytes = (numPairs * 7) + (hasOdd ? 4 : 0);

        unsigned char *outBytes = (unsigned char *)calloc(totalBytes, 1);
        if (!outBytes) {
            (*env)->ReleaseShortArrayElements(env, speechIn, samples, JNI_ABORT);
            return NULL;
        }

        unsigned char fA[4], fB[4];
        int outOffset = 0;

        for (int p = 0; p < numPairs; p++) {
            codec2_encode(c2, fA, samples + ((p * 2) * nsam));
            codec2_encode(c2, fB, samples + ((p * 2 + 1) * nsam));

            outBytes[outOffset + 0] = fA[0];
            outBytes[outOffset + 1] = fA[1];
            outBytes[outOffset + 2] = fA[2];
            outBytes[outOffset + 3] = (fA[3] & 0xF0) | ((fB[0] >> 4) & 0x0F);
            outBytes[outOffset + 4] = ((fB[0] & 0x0F) << 4) | ((fB[1] >> 4) & 0x0F);
            outBytes[outOffset + 5] = ((fB[1] & 0x0F) << 4) | ((fB[2] >> 4) & 0x0F);
            outBytes[outOffset + 6] = ((fB[2] & 0x0F) << 4) | ((fB[3] >> 4) & 0x0F);
            outOffset += 7;
        }

        if (hasOdd) {
            codec2_encode(c2, fA, samples + ((numFrames - 1) * nsam));
            outBytes[outOffset + 0] = fA[0];
            outBytes[outOffset + 1] = fA[1];
            outBytes[outOffset + 2] = fA[2];
            outBytes[outOffset + 3] = fA[3] & 0xF0;
            outOffset += 4;
        }

        result = (*env)->NewByteArray(env, totalBytes);
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, totalBytes, (const jbyte *)outBytes);
        }
        free(outBytes);
    } else {
        int nbyte = (nbit + 7) / 8;
        int totalBytes = numFrames * nbyte;
        unsigned char *outBytes = (unsigned char *)malloc(totalBytes);
        if (!outBytes) {
            (*env)->ReleaseShortArrayElements(env, speechIn, samples, JNI_ABORT);
            return NULL;
        }
        for (int i = 0; i < numFrames; i++) {
            codec2_encode(c2, outBytes + (i * nbyte), samples + (i * nsam));
        }
        result = (*env)->NewByteArray(env, totalBytes);
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, totalBytes, (const jbyte *)outBytes);
        }
        free(outBytes);
    }

    (*env)->ReleaseShortArrayElements(env, speechIn, samples, JNI_ABORT);
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_org_meshtastic_feature_messaging_audio_Codec2Jni_nativeDecodeAll(JNIEnv *env, jobject thiz, jlong handle, jbyteArray bitsIn) {
    struct CODEC2 *c2 = (struct CODEC2 *)(intptr_t)handle;
    if (!c2) return NULL;
    jsize totalBytes = (*env)->GetArrayLength(env, bitsIn);
    int nsam = codec2_samples_per_frame(c2);
    int nbit = codec2_bits_per_frame(c2);
    if (nsam <= 0 || nbit <= 0 || totalBytes <= 0) return NULL;

    jbyte *bytes = (*env)->GetByteArrayElements(env, bitsIn, NULL);
    if (!bytes) return NULL;

    jshortArray result = NULL;

    if (nbit == 28) {
        // Continuous 28-bit unpacking
        // Handle both continuous packed stream (7 bytes = 2 frames, rem >= 4 = 1 frame)
        // AND legacy 4-byte padded format fallback if detected
        int isLegacyPadded = (totalBytes % 4 == 0 && totalBytes % 7 != 0 && totalBytes % 7 != 4);

        if (isLegacyPadded) {
            int numFrames = totalBytes / 4;
            int totalSamples = numFrames * nsam;
            jshort *outSamples = (jshort *)malloc(totalSamples * sizeof(jshort));
            if (outSamples) {
                for (int i = 0; i < numFrames; i++) {
                    codec2_decode(c2, outSamples + (i * nsam), (const unsigned char *)(bytes + (i * 4)));
                }
                result = (*env)->NewShortArray(env, totalSamples);
                if (result) {
                    (*env)->SetShortArrayRegion(env, result, 0, totalSamples, outSamples);
                }
                free(outSamples);
            }
        } else {
            int numPairs = totalBytes / 7;
            int remBytes = totalBytes % 7;
            int hasOdd = (remBytes >= 4) ? 1 : 0;
            int numFrames = numPairs * 2 + hasOdd;

            if (numFrames > 0) {
                int totalSamples = numFrames * nsam;
                jshort *outSamples = (jshort *)malloc(totalSamples * sizeof(jshort));
                if (outSamples) {
                    unsigned char fA[4], fB[4];
                    int inOffset = 0;
                    const unsigned char *uBytes = (const unsigned char *)bytes;

                    for (int p = 0; p < numPairs; p++) {
                        fA[0] = uBytes[inOffset + 0];
                        fA[1] = uBytes[inOffset + 1];
                        fA[2] = uBytes[inOffset + 2];
                        fA[3] = uBytes[inOffset + 3] & 0xF0;

                        fB[0] = ((uBytes[inOffset + 3] & 0x0F) << 4) | ((uBytes[inOffset + 4] >> 4) & 0x0F);
                        fB[1] = ((uBytes[inOffset + 4] & 0x0F) << 4) | ((uBytes[inOffset + 5] >> 4) & 0x0F);
                        fB[2] = ((uBytes[inOffset + 5] & 0x0F) << 4) | ((uBytes[inOffset + 6] >> 4) & 0x0F);
                        fB[3] = (uBytes[inOffset + 6] & 0x0F) << 4;

                        codec2_decode(c2, outSamples + ((p * 2) * nsam), fA);
                        codec2_decode(c2, outSamples + ((p * 2 + 1) * nsam), fB);
                        inOffset += 7;
                    }

                    if (hasOdd) {
                        fA[0] = uBytes[inOffset + 0];
                        fA[1] = uBytes[inOffset + 1];
                        fA[2] = uBytes[inOffset + 2];
                        fA[3] = uBytes[inOffset + 3] & 0xF0;
                        codec2_decode(c2, outSamples + ((numFrames - 1) * nsam), fA);
                    }

                    result = (*env)->NewShortArray(env, totalSamples);
                    if (result) {
                        (*env)->SetShortArrayRegion(env, result, 0, totalSamples, outSamples);
                    }
                    free(outSamples);
                }
            }
        }
    } else {
        int nbyte = (nbit + 7) / 8;
        if (totalBytes >= nbyte) {
            int numFrames = totalBytes / nbyte;
            int totalSamples = numFrames * nsam;
            jshort *outSamples = (jshort *)malloc(totalSamples * sizeof(jshort));
            if (outSamples) {
                for (int i = 0; i < numFrames; i++) {
                    codec2_decode(c2, outSamples + (i * nsam), (const unsigned char *)(bytes + (i * nbyte)));
                }
                result = (*env)->NewShortArray(env, totalSamples);
                if (result) {
                    (*env)->SetShortArrayRegion(env, result, 0, totalSamples, outSamples);
                }
                free(outSamples);
            }
        }
    }

    (*env)->ReleaseByteArrayElements(env, bitsIn, bytes, JNI_ABORT);
    return result;
}
