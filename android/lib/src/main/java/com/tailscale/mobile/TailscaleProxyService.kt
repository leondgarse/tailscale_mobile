package com.tailscale.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Embeds Tailscale in-process and exposes a local TCP proxy port.
 *
 * Why VpnService: Android SELinux blocks /proc/net/ for untrusted apps, preventing Tailscale's
 * netmon from enumerating interfaces (v4=false → control client paused → auth never completes).
 * VpnService grants NETLINK_ROUTE permission which unblocks this. We never call Builder.establish()
 * — no TUN device is created.
 *
 * Why a local proxy: TsnetLoopback creates a SOCKS5 listener but Android apps open raw TCP sockets
 * that cannot be routed through SOCKS5. Instead, we accept connections on a ServerSocket bound to
 * 127.0.0.1 and pipe each one bidirectionally to a TsnetDial fd.
 *
 * Usage:
 *   // 1. Start service (from Activity after VpnService.prepare()):
 *   TailscaleProxyService.start(context, TailscaleConfig(authKey, peerIp, peerPort))
 *
 *   // 2. Poll until ready:
 *   while (!TailscaleProxyService.isReady) delay(500)
 *   val port = TailscaleProxyService.proxyPort   // connect to 127.0.0.1:port
 *
 *   // 3. Stop:
 *   TailscaleProxyService.stop(context)
 */
class TailscaleProxyService : VpnService() {

    companion object {
        private const val TAG = "TailscaleProxyService"

        const val ACTION_START = "com.tailscale.mobile.PROXY_START"
        const val ACTION_STOP  = "com.tailscale.mobile.PROXY_STOP"
        private const val EXTRA_CONFIG = "config"

        /** True once the local proxy ServerSocket is listening and ready to accept connections. */
        @Volatile var isReady = false

        /** Status message suitable for display in a UI. */
        @Volatile var status = "Tailscale: idle"

        /**
         * Local port on 127.0.0.1 that proxies TCP to the configured peer over Tailscale.
         * Valid (> 0) only when [isReady] is true.
         */
        @Volatile var proxyPort = 0

        /** Convenience: start the service with the given config. */
        fun start(context: android.content.Context, config: TailscaleConfig) {
            val intent = Intent(context, TailscaleProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
            context.startService(intent)
        }

        /** Convenience: stop the service. */
        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, TailscaleProxyService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    @Volatile private var config: TailscaleConfig? = null
    @Volatile private var proxyFd = -1
    @Volatile private var proxyServer: ServerSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        config = intent?.getSerializableExtra(EXTRA_CONFIG) as? TailscaleConfig
            ?: run { Log.e(TAG, "No config provided"); stopSelf(); return START_NOT_STICKY }
        startForegroundNotification()
        Thread { startTailscale() }.also { it.isDaemon = true }.start()
        return START_STICKY
    }

    override fun onDestroy() { super.onDestroy(); shutdown() }
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun shutdown() {
        isReady = false; proxyPort = 0
        proxyServer?.runCatching { close() }; proxyServer = null
        val fd = proxyFd; if (fd >= 0) { proxyFd = -1; TailscaleJni.closeFd(fd) }
        TailscaleJni.close()
        updateStatus("Tailscale: stopped")
    }

    private fun startTailscale() {
        val cfg = config ?: return
        try {
            updateStatus("Tailscale: connecting...")
            val ret = TailscaleJni.connect(filesDir.absolutePath, cfg.authKey, cfg.hostname)
            if (ret != 0) { updateStatus("Tailscale: connect failed"); return }
            updateStatus("Tailscale: authenticating...")
            waitForAuth()
        } catch (e: Exception) {
            Log.e(TAG, "startTailscale failed", e)
            updateStatus("Tailscale error: ${e.message}")
        }
    }

    private fun waitForAuth() {
        val deadline = System.currentTimeMillis() + 90_000L
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val ip = TailscaleJni.getIp()
            if (ip.isNotEmpty()) {
                Log.i(TAG, "Authenticated, ip=$ip (attempt $attempt)")
                updateStatus("Tailscale: authenticated, starting proxy...")
                startLocalProxy()
                return
            }
            updateStatus("Tailscale: authenticating (attempt $attempt)...")
            Thread.sleep(2000L)
        }
        updateStatus("Tailscale error: auth timeout after 90s")
    }

    private fun startLocalProxy() {
        val cfg = config ?: return
        try {
            val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            proxyServer = ss
            proxyPort = ss.localPort
            isReady = true
            updateStatus("Tailscale: ready (proxy :${ss.localPort})")
            Log.i(TAG, "Proxy listening on 127.0.0.1:${ss.localPort} → ${cfg.peerIp}:${cfg.peerPort}")

            while (!ss.isClosed) {
                val client: Socket = try { ss.accept() } catch (_: Exception) { break }
                Log.i(TAG, "Proxy: client ${client.remoteSocketAddress}")
                Thread { handleClient(client, cfg) }.also { it.isDaemon = true }.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy error: ${e.message}")
            isReady = false
            updateStatus("Tailscale error: proxy failed")
        }
    }

    private fun handleClient(client: Socket, cfg: TailscaleConfig) {
        val target = "${cfg.peerIp}:${cfg.peerPort}"
        val fd = TailscaleJni.dial(target)
        if (fd < 0) {
            Log.e(TAG, "TsnetDial to $target failed")
            client.runCatching { close() }; return
        }
        proxyFd = fd
        Log.i(TAG, "Proxy: fd=$fd connected to $target")
        updateStatus("Connected ●")

        val pfd   = ParcelFileDescriptor.adoptFd(fd)
        val tsIn  = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val tsOut = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        val clientIn  = client.getInputStream()
        val clientOut = client.getOutputStream()
        val buf = ByteArray(65536)

        val t1 = Thread {
            try { while (true) { val n = clientIn.read(buf); if (n < 0) break; tsOut.write(buf, 0, n) } }
            catch (_: Exception) {}
            finally { client.runCatching { close() }; pfd.runCatching { close() }; proxyFd = -1 }
        }.also { it.isDaemon = true; it.start() }

        try { while (true) { val n = tsIn.read(buf); if (n < 0) break; clientOut.write(buf, 0, n) } }
        catch (_: Exception) {}
        finally { client.runCatching { close() }; t1.interrupt() }

        Log.i(TAG, "Proxy: connection closed")
    }

    private fun startForegroundNotification() {
        val channelId = "tailscale_proxy"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Tailscale Proxy", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Tailscale")
            .setContentText("Connecting...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(2, notif)
    }

    private fun updateStatus(msg: String) { status = msg; Log.i(TAG, msg) }
}
