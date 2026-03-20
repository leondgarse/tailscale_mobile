// JNI bridge: libtailscale (Tsnet* C API) → Kotlin

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <unistd.h>
#include "libtailscale.h"

#define TAG "TailscaleJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int ts_handle = -1;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_tailscale_mobile_TailscaleJni_connect(
        JNIEnv *env, jclass clazz,
        jstring j_state_dir, jstring j_auth_key, jstring j_hostname)
{
    if (ts_handle >= 0) {
        TsnetClose(ts_handle);
        ts_handle = -1;
    }

    const char *state_dir = env->GetStringUTFChars(j_state_dir, nullptr);

    // Set Android-compatible dirs from Go side (os.Setenv, os.MkdirAll) before
    // TsnetNewServer, so logpolicy can find a writable directory.
    LOGI("state_dir=%s", state_dir);
    TsnetSetAndroidDirs(const_cast<char*>(state_dir));

    ts_handle = TsnetNewServer();
    if (ts_handle < 0) {
        LOGE("TsnetNewServer failed");
        env->ReleaseStringUTFChars(j_state_dir, state_dir);
        return -1;
    }
    const char *auth_key  = env->GetStringUTFChars(j_auth_key,  nullptr);
    const char *hostname  = env->GetStringUTFChars(j_hostname,  nullptr);

    TsnetSetDir(ts_handle,      const_cast<char*>(state_dir));
    TsnetSetAuthKey(ts_handle,  const_cast<char*>(auth_key));
    TsnetSetHostname(ts_handle, const_cast<char*>(hostname));
    TsnetSetEphemeral(ts_handle, 1);
    TsnetSetLogFD(ts_handle, -1);  // discard logs in production

    env->ReleaseStringUTFChars(j_state_dir, state_dir);
    env->ReleaseStringUTFChars(j_auth_key,  auth_key);
    env->ReleaseStringUTFChars(j_hostname,  hostname);

    // Use Loopback (userspace networking) instead of Up (TUN) — TUN requires
    // netlink_route_socket which Android blocks for untrusted apps.
    LOGI("TsnetLoopback: starting userspace networking…");
    char addr_buf[256] = {0};
    char proxy_buf[33] = {0};
    char local_buf[33] = {0};
    int ret = TsnetLoopback(ts_handle, addr_buf, sizeof(addr_buf), proxy_buf, local_buf);
    if (ret != 0) {
        char errbuf[256] = {0};
        TsnetErrmsg(ts_handle, errbuf, sizeof(errbuf));
        LOGE("TsnetLoopback failed (%d): %s", ret, errbuf);
        TsnetClose(ts_handle);
        ts_handle = -1;
        return -1;
    }
    LOGI("TsnetLoopback: connected, addr=%s", addr_buf);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_tailscale_mobile_TailscaleJni_getIp(JNIEnv *env, jclass clazz)
{
    if (ts_handle < 0) return env->NewStringUTF("");
    char buf[256] = {0};
    int ret = TsnetGetIps(ts_handle, buf, sizeof(buf));
    LOGI("TsnetGetIps: ret=%d buf=%s", ret, buf);
    if (ret != 0 || buf[0] == '\0') return env->NewStringUTF("");
    // TsnetGetIps returns newline-separated IPs; find the Tailscale 100.x.y.z address
    char *p = buf;
    while (p && *p) {
        char *nl = strchr(p, '\n');
        if (nl) *nl = '\0';
        if (strncmp(p, "100.", 4) == 0) {
            LOGI("TsnetGetIps: tailscale IP = %s", p);
            return env->NewStringUTF(p);
        }
        p = nl ? nl + 1 : nullptr;
    }
    return env->NewStringUTF("");
}

JNIEXPORT jint JNICALL
Java_com_tailscale_mobile_TailscaleJni_dial(
        JNIEnv *env, jclass clazz, jstring j_addr)
{
    if (ts_handle < 0) {
        LOGE("dial called before connect");
        return -1;
    }
    const char *addr = env->GetStringUTFChars(j_addr, nullptr);
    char network[] = "tcp";
    int conn = -1;
    int ret = TsnetDial(ts_handle, network, const_cast<char*>(addr), &conn);
    env->ReleaseStringUTFChars(j_addr, addr);

    if (ret != 0) {
        char errbuf[256] = {0};
        TsnetErrmsg(ts_handle, errbuf, sizeof(errbuf));
        LOGE("TsnetDial failed: %s", errbuf);
        return -1;
    }
    LOGI("TsnetDial: fd=%d", conn);
    return (jint)conn;
}

JNIEXPORT void JNICALL
Java_com_tailscale_mobile_TailscaleJni_closeFd(JNIEnv *env, jclass clazz, jint fd)
{
    if (fd >= 0) {
        ::close(fd);
        LOGI("closeFd: fd=%d", fd);
    }
}

JNIEXPORT void JNICALL
Java_com_tailscale_mobile_TailscaleJni_close(JNIEnv *env, jclass clazz)
{
    if (ts_handle >= 0) {
        TsnetClose(ts_handle);
        ts_handle = -1;
        LOGI("Tsnet closed");
    }
}

} // extern "C"
