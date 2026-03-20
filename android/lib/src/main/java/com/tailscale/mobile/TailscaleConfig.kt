package com.tailscale.mobile

import java.io.Serializable

/**
 * Configuration for a Tailscale connection and TCP proxy target.
 *
 * @param authKey    Tailscale auth key (tskey-auth-…). Generate at https://login.tailscale.com/admin/settings/keys
 * @param peerIp     Tailscale IP of the target peer (100.x.y.z)
 * @param peerPort   TCP port on the peer to proxy to (default 1935 for RTMP)
 * @param hostname   Hostname this device registers as on the tailnet (default "mobile")
 */
data class TailscaleConfig(
    val authKey:  String,
    val peerIp:   String,
    val peerPort: Int    = 1935,
    val hostname: String = "mobile",
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
