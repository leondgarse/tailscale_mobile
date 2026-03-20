package com.tailscale.mobile

/**
 * JNI bridge to libtailscale (C library via tailscale_jni.so).
 *
 * Usage:
 *   TailscaleJni.connect(stateDir, authKey, "my-device")  // blocks until loopback listener up
 *   val ip = TailscaleJni.getIp()                         // "100.x.y.z" once authenticated
 *   val fd = TailscaleJni.dial("100.x.y.z:1935")          // raw TCP fd through Tailscale
 *   TailscaleJni.closeFd(fd)
 *   TailscaleJni.close()
 */
object TailscaleJni {
    init {
        System.loadLibrary("tailscale_jni")
    }

    /** Connect to Tailscale network. Blocks until TsnetLoopback listener is up. Returns 0 on success, -1 on error. */
    @JvmStatic external fun connect(stateDir: String, authKey: String, hostname: String): Int

    /** Returns the device's Tailscale IP (e.g. "100.x.y.z"), or empty string if not yet authenticated. Non-blocking. */
    @JvmStatic external fun getIp(): String

    /** Dial a TCP address on the tailnet. Returns a raw socket fd, or -1 on error. */
    @JvmStatic external fun dial(addr: String): Int

    /** Close a file descriptor returned by dial(). */
    @JvmStatic external fun closeFd(fd: Int)

    /** Shut down the Tailscale instance. */
    @JvmStatic external fun close()
}
