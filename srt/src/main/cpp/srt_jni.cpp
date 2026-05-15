// srt_jni.cpp — JNI bridge to libsrt for KarPlayer.
//
// All native methods live on the Kotlin object `com.karplayer.srt.SrtNative`.
// When KARPLAYER_SRT_STUB is defined (libsrt not present), every call returns
// a sentinel error so the app stays compilable without the binary.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>

#define LOG_TAG "karplayer-srt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef KARPLAYER_SRT_STUB
    #include <srt/srt.h>
#endif

// Error codes mirrored to Kotlin (see SrtSocket.kt).
static constexpr jint KP_OK              = 0;
static constexpr jint KP_ERR_STUB        = -1000;
static constexpr jint KP_ERR_CREATE      = -1001;
static constexpr jint KP_ERR_SETOPT      = -1002;
static constexpr jint KP_ERR_CONNECT     = -1003;
static constexpr jint KP_ERR_INVALID     = -1004;
static constexpr jint KP_ERR_READ        = -1005;

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
#ifndef KARPLAYER_SRT_STUB
    if (srt_startup() < 0) {
        LOGE("srt_startup failed: %s", srt_getlasterror_str());
        return JNI_ERR;
    }
    LOGI("libsrt initialized");
#else
    LOGW("karplayer-srt loaded in STUB mode — libsrt not linked");
#endif
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
#ifndef KARPLAYER_SRT_STUB
    srt_cleanup();
#endif
}

// ----------------------------------------------------------------------------
// nativeCreate(): Long
// Returns an SRTSOCKET cast to jlong, or -1 on failure / stub.
// ----------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_karplayer_srt_SrtNative_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
#ifdef KARPLAYER_SRT_STUB
    return -1;
#else
    SRTSOCKET s = srt_create_socket();
    if (s == SRT_INVALID_SOCK) {
        LOGE("srt_create_socket failed: %s", srt_getlasterror_str());
        return -1;
    }
    return static_cast<jlong>(s);
#endif
}

// ----------------------------------------------------------------------------
// nativeConnect(handle, host, port, latencyMs, maxBwBps, inputBwBps,
//               mode, streamId, timeoutMs): Int
//
// mode: 0=CALLER, 1=LISTENER, 2=RENDEZVOUS
// Returns 0 on success or a negative KP_ERR_* code.
// ----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_karplayer_srt_SrtNative_nativeConnect(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jstring jhost, jint port,
        jint latencyMs, jlong maxBwBps, jlong inputBwBps,
        jint mode, jstring jstreamId, jint timeoutMs,
        jstring jpassphrase, jint pbkeylen) {

#ifdef KARPLAYER_SRT_STUB
    (void)env; (void)handle; (void)jhost; (void)port; (void)latencyMs;
    (void)maxBwBps; (void)inputBwBps; (void)mode; (void)jstreamId; (void)timeoutMs;
    (void)jpassphrase; (void)pbkeylen;
    return KP_ERR_STUB;
#else
    SRTSOCKET s = static_cast<SRTSOCKET>(handle);
    if (s == SRT_INVALID_SOCK) return KP_ERR_INVALID;

    const char* host = env->GetStringUTFChars(jhost, nullptr);
    if (!host) return KP_ERR_INVALID;

    // SRTO_TRANSTYPE must be set first — it is a "bulk preset" that resets a
    // number of other options to a coherent default. Setting it after other
    // options would clobber them. LIVE is what vMix/OBS/FFmpeg negotiate by
    // default for MPEG-TS streaming and implies MESSAGEAPI=1, TSBPDMODE=1,
    // TLPKTDROP=1, PAYLOADSIZE=1316.
    SRT_TRANSTYPE tt = SRTT_LIVE;
    if (srt_setsockopt(s, 0, SRTO_TRANSTYPE, &tt, sizeof(tt)) < 0) {
        LOGE("SRTO_TRANSTYPE failed: %s", srt_getlasterror_str());
        env->ReleaseStringUTFChars(jhost, host);
        return KP_ERR_SETOPT;
    }

    int rcvLatency = latencyMs;
    if (srt_setsockopt(s, 0, SRTO_RCVLATENCY, &rcvLatency, sizeof(rcvLatency)) < 0) {
        LOGE("SRTO_RCVLATENCY failed: %s", srt_getlasterror_str());
        env->ReleaseStringUTFChars(jhost, host);
        return KP_ERR_SETOPT;
    }

    int rcvsyn = 1;           // blocking recv; cancel via srt_close from caller
    srt_setsockopt(s, 0, SRTO_RCVSYN, &rcvsyn, sizeof(rcvsyn));
    int sndsyn = 1;
    srt_setsockopt(s, 0, SRTO_SNDSYN, &sndsyn, sizeof(sndsyn));

    int connTimeout = timeoutMs;
    srt_setsockopt(s, 0, SRTO_CONNTIMEO, &connTimeout, sizeof(connTimeout));

    if (maxBwBps >= 0) {
        int64_t mbw = static_cast<int64_t>(maxBwBps);
        srt_setsockopt(s, 0, SRTO_MAXBW, &mbw, sizeof(mbw));
    }
    if (inputBwBps > 0) {
        int64_t ibw = static_cast<int64_t>(inputBwBps);
        srt_setsockopt(s, 0, SRTO_INPUTBW, &ibw, sizeof(ibw));
    }

    if (jstreamId) {
        const char* sid = env->GetStringUTFChars(jstreamId, nullptr);
        if (sid && std::strlen(sid) > 0) {
            srt_setsockopt(s, 0, SRTO_STREAMID, sid, static_cast<int>(std::strlen(sid)));
        }
        if (sid) env->ReleaseStringUTFChars(jstreamId, sid);
    }

    // Encryption: PBKEYLEN must be set before PASSPHRASE. Valid lengths are
    // 16, 24, 32 (AES-128/192/256). Passing 0 lets the peer dictate it.
    if (jpassphrase) {
        const char* pp = env->GetStringUTFChars(jpassphrase, nullptr);
        if (pp && std::strlen(pp) > 0) {
            if (pbkeylen == 16 || pbkeylen == 24 || pbkeylen == 32) {
                int kl = pbkeylen;
                srt_setsockopt(s, 0, SRTO_PBKEYLEN, &kl, sizeof(kl));
            }
            srt_setsockopt(s, 0, SRTO_PASSPHRASE, pp, static_cast<int>(std::strlen(pp)));
        }
        if (pp) env->ReleaseStringUTFChars(jpassphrase, pp);
    }

    sockaddr_in sa{};
    sa.sin_family = AF_INET;
    sa.sin_port   = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host, &sa.sin_addr) != 1) {
        LOGE("inet_pton failed for host '%s'", host);
        env->ReleaseStringUTFChars(jhost, host);
        return KP_ERR_INVALID;
    }
    env->ReleaseStringUTFChars(jhost, host);

    int rc = -1;
    if (mode == 0) { // CALLER
        rc = srt_connect(s, reinterpret_cast<sockaddr*>(&sa), sizeof(sa));
    } else if (mode == 1) { // LISTENER
        if (srt_bind(s, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) < 0) {
            LOGE("srt_bind failed: %s", srt_getlasterror_str());
            return KP_ERR_CONNECT;
        }
        if (srt_listen(s, 1) < 0) {
            LOGE("srt_listen failed: %s", srt_getlasterror_str());
            return KP_ERR_CONNECT;
        }
        sockaddr_in peer{};
        int peerlen = sizeof(peer);
        SRTSOCKET accepted = srt_accept(s, reinterpret_cast<sockaddr*>(&peer), &peerlen);
        if (accepted == SRT_INVALID_SOCK) {
            LOGE("srt_accept failed: %s", srt_getlasterror_str());
            return KP_ERR_CONNECT;
        }
        // Close the listener; the accepted socket replaces the handle on the
        // Kotlin side via the returned-handle convention below.
        srt_close(s);
        // Stash accepted into handle via positive return — but JNI signature is int.
        // For now, listener mode is not exposed end-to-end (CALLER is the
        // primary path for an RX player); accept the listener case as a TODO.
        srt_close(accepted);
        return KP_ERR_CONNECT;
    } else if (mode == 2) { // RENDEZVOUS
        int rdv = 1;
        srt_setsockopt(s, 0, SRTO_RENDEZVOUS, &rdv, sizeof(rdv));
        if (srt_bind(s, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) < 0) {
            LOGE("srt_bind (rendezvous) failed: %s", srt_getlasterror_str());
            return KP_ERR_CONNECT;
        }
        rc = srt_connect(s, reinterpret_cast<sockaddr*>(&sa), sizeof(sa));
    } else {
        return KP_ERR_INVALID;
    }

    if (rc == SRT_ERROR) {
        int rej = srt_getrejectreason(s);
        const char* rejStr = srt_rejectreason_str(rej);
        LOGE("srt_connect failed: %s | rej_code=%d rej_reason=%s",
             srt_getlasterror_str(), rej, rejStr ? rejStr : "?");
        return KP_ERR_CONNECT;
    }
    LOGI("SRT connected (port=%d latency=%dms streamid_set=%d)",
         port, latencyMs, jstreamId ? 1 : 0);
    return KP_OK;
#endif
}

// ----------------------------------------------------------------------------
// nativeRead(handle, buffer, offset, length): Int
// Returns bytes read, 0 on graceful EOF/timeout, or negative KP_ERR_*.
// ----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_karplayer_srt_SrtNative_nativeRead(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jbyteArray buffer, jint offset, jint length) {

#ifdef KARPLAYER_SRT_STUB
    (void)env; (void)handle; (void)buffer; (void)offset; (void)length;
    return KP_ERR_STUB;
#else
    SRTSOCKET s = static_cast<SRTSOCKET>(handle);
    if (s == SRT_INVALID_SOCK) return KP_ERR_INVALID;
    if (length <= 0) return 0;

    jbyte* raw = env->GetByteArrayElements(buffer, nullptr);
    if (!raw) return KP_ERR_READ;

    // LIVE mode → SRTO_MESSAGEAPI=1 → must call srt_recvmsg (srt_recv would
    // throw MJ_NOTSUP/MN_INVALMSGAPI). Caller's buffer is expected to be at
    // least SRTO_PAYLOADSIZE (1316 bytes) — anything smaller risks the
    // message being truncated. ExoPlayer's loader uses 64 KB buffers so this
    // is safe in practice.
    int n = srt_recvmsg(s, reinterpret_cast<char*>(raw + offset), length);

    if (n == SRT_ERROR) {
        int code = srt_getlasterror(nullptr);
        env->ReleaseByteArrayElements(buffer, raw, JNI_ABORT);
        LOGW("srt_recvmsg error code=%d msg=%s len=%d",
             code, srt_getlasterror_str(), length);
        return KP_ERR_READ;
    }

    env->ReleaseByteArrayElements(buffer, raw, 0);
    return static_cast<jint>(n);
#endif
}

// ----------------------------------------------------------------------------
// nativeClose(handle)
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_karplayer_srt_SrtNative_nativeClose(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
#ifdef KARPLAYER_SRT_STUB
    (void)handle;
#else
    SRTSOCKET s = static_cast<SRTSOCKET>(handle);
    if (s != SRT_INVALID_SOCK) srt_close(s);
#endif
}

// ----------------------------------------------------------------------------
// nativeGetStats(handle, out: double[8]): Int
// Output layout (Kotlin parses by index):
//   [0] rttMs
//   [1] bitrateKbps           (mbpsRecvRate * 1000)
//   [2] packetLossPct         (pktRcvLossTotal / max(1,pktRecvTotal) * 100)
//   [3] jitterMs              (msRcvBuf — proxy until per-packet jitter is wired)
//   [4] receivedPackets       (pktRecvTotal)
//   [5] lostPackets           (pktRcvLossTotal)
//   [6] retransmittedPackets  (pktRcvRetransTotal)
//   [7] reserved
// Returns 0 on success or KP_ERR_*.
// ----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_karplayer_srt_SrtNative_nativeGetStats(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jdoubleArray out) {

#ifdef KARPLAYER_SRT_STUB
    (void)env; (void)handle; (void)out;
    return KP_ERR_STUB;
#else
    SRTSOCKET s = static_cast<SRTSOCKET>(handle);
    if (s == SRT_INVALID_SOCK) return KP_ERR_INVALID;
    if (env->GetArrayLength(out) < 8) return KP_ERR_INVALID;

    SRT_TRACEBSTATS st{};
    // clear=0 → cumulative; instant=1 → instantaneous snapshot.
    if (srt_bistats(s, &st, 0, 1) < 0) {
        LOGW("srt_bistats failed: %s", srt_getlasterror_str());
        return KP_ERR_READ;
    }

    jdouble values[8];
    values[0] = static_cast<jdouble>(st.msRTT);
    values[1] = static_cast<jdouble>(st.mbpsRecvRate) * 1000.0;
    double pl = 0.0;
    if (st.pktRecvTotal > 0) {
        pl = static_cast<double>(st.pktRcvLossTotal) /
             static_cast<double>(st.pktRecvTotal) * 100.0;
    }
    values[2] = pl;
    values[3] = static_cast<jdouble>(st.msRcvBuf);
    values[4] = static_cast<jdouble>(st.pktRecvTotal);
    values[5] = static_cast<jdouble>(st.pktRcvLossTotal);
    values[6] = static_cast<jdouble>(st.pktRetransTotal);
    values[7] = 0.0;
    env->SetDoubleArrayRegion(out, 0, 8, values);
    return KP_OK;
#endif
}

} // extern "C"
