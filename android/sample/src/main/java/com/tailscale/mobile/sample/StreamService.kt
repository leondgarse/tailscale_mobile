package com.tailscale.mobile.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Foreground service that keeps WiFi and CPU alive when the screen turns off. */
class StreamService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("stream", getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, Notification.Builder(this, "stream")
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_streaming))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build())
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
