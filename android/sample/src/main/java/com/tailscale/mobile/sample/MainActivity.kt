/**
 * Sample: RTMP streaming over Tailscale.
 *
 * This sample shows how to use TailscaleProxyService to stream camera video via RTMP
 * to a private server (e.g. MediaMTX) on a Tailscale network — without installing
 * the Tailscale app on the device.
 *
 * Full flow:
 *   1. User enters: Tailscale auth key, RTMP server peer IP (100.x.y.z), RTMP path.
 *   2. Camera + Mic permissions are requested.
 *   3. VPN permission is requested (required for NETLINK_ROUTE; no TUN device is created).
 *   4. TailscaleProxyService.start() — connects to tailnet, opens ServerSocket on 127.0.0.1:PORT.
 *   5. Poll TailscaleProxyService.isReady until the proxy is listening (~4–8s).
 *   6. GenericStream (RootEncoder) pushes RTMP to rtmp://127.0.0.1:PORT/<path>.
 *   7. TailscaleProxyService pipes each TCP connection → TsnetDial → peerIp:1935 via WireGuard.
 *
 * The RTMP server only needs to be reachable on its Tailscale IP — no port forwarding required.
 *
 * Dependencies (android/sample/build.gradle.kts):
 *   implementation("com.github.pedroSG94.RootEncoder:library:2.6.7")  // GenericStream, Camera2Source
 */
package com.tailscale.mobile.sample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.generic.GenericStream
import com.tailscale.mobile.TailscaleConfig
import com.tailscale.mobile.TailscaleProxyService

class MainActivity : AppCompatActivity(), ConnectChecker {

    companion object {
        private const val TAG = "TailscaleSample"
        private const val PERM_REQUEST = 1
        private const val VPN_REQUEST  = 2
        private const val POLL_MS      = 500L
    }

    private lateinit var texturePreview: TextureView
    private lateinit var tvStatus:       TextView
    private lateinit var etAuthKey:      EditText
    private lateinit var etPeerIp:       EditText
    private lateinit var etStreamSuffix: EditText
    private lateinit var btnStart:       Button
    private lateinit var btnStop:        Button

    private val mainHandler     = Handler(Looper.getMainLooper())
    private val keyframeHandler = Handler(Looper.getMainLooper())
    private var keyframeRunnable: Runnable? = null
    private var pollRunnable:     Runnable? = null

    private var stream:      GenericStream? = null
    private var wakeLock:    PowerManager.WakeLock? = null
    private var isStreaming  = false
    private var streamSuffix = "/live/stream"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        texturePreview  = findViewById(R.id.texturePreview)
        tvStatus        = findViewById(R.id.tvStatus)
        etAuthKey       = findViewById(R.id.etAuthKey)
        etPeerIp        = findViewById(R.id.etPeerIp)
        etStreamSuffix  = findViewById(R.id.etStreamSuffix)
        btnStart        = findViewById(R.id.btnStart)
        btnStop         = findViewById(R.id.btnStop)
        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener  { stopStream() }
    }

    override fun onDestroy() { super.onDestroy(); stopStream() }

    private fun onStartClicked() {
        val authKey = etAuthKey.text.toString().trim()
        val peerIp  = etPeerIp.text.toString().trim()
        streamSuffix = etStreamSuffix.text.toString().trim().let { if (it.startsWith("/")) it else "/$it" }
        if (authKey.isEmpty()) { setStatus("Enter auth key"); return }
        if (peerIp.isEmpty())  { setStatus("Enter peer Tailscale IP"); return }
        btnStart.isEnabled = false
        checkPermissionsAndStart(TailscaleConfig(authKey = authKey, peerIp = peerIp))
    }

    private fun checkPermissionsAndStart(config: TailscaleConfig) {
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startTailscale(config)
        else {
            // stash config for after permission result
            pendingConfig = config
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQUEST)
        }
    }

    private var pendingConfig: TailscaleConfig? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            pendingConfig?.let { startTailscale(it) }
        else { setStatus("Permissions denied"); btnStart.isEnabled = true }
    }

    private fun startTailscale(config: TailscaleConfig) {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingConfig = config
            startActivityForResult(vpnIntent, VPN_REQUEST)
            return
        }
        launchService(config)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == Activity.RESULT_OK) pendingConfig?.let { launchService(it) }
            else { setStatus("VPN permission denied"); btnStart.isEnabled = true }
        }
    }

    private fun launchService(config: TailscaleConfig) {
        setStatus("Tailscale: starting…")
        TailscaleProxyService.start(this, config)
        pollRunnable = object : Runnable {
            override fun run() {
                setStatus(TailscaleProxyService.status)
                when {
                    TailscaleProxyService.isReady -> startWhenReady()
                    TailscaleProxyService.status.startsWith("Tailscale error") -> {
                        setStatus(TailscaleProxyService.status); btnStart.isEnabled = true
                    }
                    else -> mainHandler.postDelayed(this, POLL_MS)
                }
            }
        }.also { mainHandler.postDelayed(it, POLL_MS) }
    }

    private fun startWhenReady() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }; pollRunnable = null
        if (texturePreview.isAvailable) startStream()
        else {
            setStatus("Waiting for surface…")
            texturePreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    texturePreview.surfaceTextureListener = null; startStream()
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun startStream() {
        if (isStreaming) return
        val isPortrait = resources.displayMetrics.let { it.heightPixels > it.widthPixels }
        val w = if (isPortrait) 1080 else 1920
        val h = if (isPortrait) 1920 else 1080
        val s = GenericStream(this, this, Camera2Source(this), MicrophoneSource())
        if (!s.prepareVideo(w, h, 30, 8_000_000) || !s.prepareAudio(44100, true, 128_000)) {
            s.release(); setStatus("Encoder prepare failed"); btnStart.isEnabled = true; return
        }
        s.startPreview(texturePreview)
        val url = "rtmp://127.0.0.1:${TailscaleProxyService.proxyPort}$streamSuffix"
        s.startStream(url)
        stream = s; isStreaming = true
        scheduleKeyframeRequests(s)
        startForegroundService(Intent(this, StreamService::class.java))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TailscaleSample:wake").also { it.acquire() }
        btnStart.visibility = View.GONE
        btnStop.visibility  = View.VISIBLE
        Log.i(TAG, "Stream → $url")
    }

    private fun stopStream() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }; pollRunnable = null
        keyframeRunnable?.let { keyframeHandler.removeCallbacks(it) }; keyframeRunnable = null
        stream?.let { if (it.isStreaming) it.stopStream(); if (it.isOnPreview) it.stopPreview(); it.release() }
        stream = null; isStreaming = false
        TailscaleProxyService.stop(this)
        stopService(Intent(this, StreamService::class.java))
        wakeLock?.release(); wakeLock = null
        btnStart.isEnabled = true; btnStart.visibility = View.VISIBLE; btnStop.visibility = View.GONE
        setStatus("Stopped")
    }

    private fun scheduleKeyframeRequests(s: GenericStream) {
        var attempts = 0
        val r = object : Runnable {
            override fun run() {
                if (!isStreaming) return
                s.requestKeyframe(); attempts++
                keyframeHandler.postDelayed(this, if (attempts < 10) 500L else 2000L)
            }
        }
        keyframeRunnable = r; keyframeHandler.postDelayed(r, 300)
    }

    override fun onConnectionStarted(url: String) = mainHandler.post { setStatus("Connecting → $url") }.let {}
    override fun onConnectionSuccess() = mainHandler.post { setStatus("Live ●") }.let {}
    override fun onConnectionFailed(reason: String) = mainHandler.post { setStatus("Failed: $reason — retrying…") }.let {}
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() = mainHandler.post { if (isStreaming) setStatus("Disconnected — reconnecting…") }.let {}
    override fun onAuthError() = mainHandler.post { setStatus("Auth error") }.let {}
    override fun onAuthSuccess() {}

    private fun setStatus(msg: String) { Log.i(TAG, msg); tvStatus.text = msg }
}
