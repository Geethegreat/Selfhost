package com.example.selfhost

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

class TunnelService : Service() {

    private var gatewaySocket: GatewaySocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var slug: String? = null


    companion object {
        const val CHANNEL_ID = "tunnel_channel"
        const val EXTRA_HTML = "extra_html"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {


        startForeground(1, createNotification())

        slug = intent?.getStringExtra("slug")

        if (slug.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }


        connectSocket()

        return START_STICKY
    }

    private fun connectSocket() {
        gatewaySocket = GatewaySocket(
            gatewayUrl = "wss://untractably-hypothecary-vivienne.ngrok-free.dev",
            slug = slug!!
        )

        gatewaySocket?.connect()
    }

    override fun onDestroy() {
        gatewaySocket?.close()
        gatewaySocket = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --------------------------
    // Notification Stuff
    // --------------------------

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SelfHost Running")
            .setContentText("Tunnel is active")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
