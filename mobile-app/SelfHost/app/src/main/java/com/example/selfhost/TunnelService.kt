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
    private var isRunning = false
    private var reconnectAttempts = 0
    private var liveViewers = 0

    companion object {
        const val CHANNEL_ID = "tunnel_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification("Connecting..."))

        val newSlug = intent?.getStringExtra("slug")
        if (newSlug.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning && newSlug == slug) return START_STICKY

        slug = newSlug
        isRunning = true
        reconnectAttempts = 0
        connectSocket()

        return START_STICKY
    }

    private fun connectSocket() {
        gatewaySocket?.close()
        gatewaySocket = null

        gatewaySocket = GatewaySocket(
            // gatewayUrl = "wss://wheelstracker.com/selfhost/u/",
            gatewayUrl = "wss://untractably-hypothecary-vivienne.ngrok-free.dev",
            slug = slug!!,
            listener = object : GatewayListener {

                override fun onConnected() {
                    reconnectAttempts = 0
                    updateNotification("Live · $liveViewers viewing")
                }

                override fun onError(message: String) {
                    updateNotification("Error: $message")
                }

                override fun onDisconnected() {
                    updateNotification("Reconnecting...")
                }

                override fun onStats(
                    liveViewers: Int,
                    dailyVisits: Int,
                    monthlyVisits: Int,
                    totalVisits: Int
                ) {
                    this@TunnelService.liveViewers = liveViewers

                    // Update notification with live viewer count
                    updateNotification(
                        if (liveViewers == 0) "Live · no viewers"
                        else if (liveViewers == 1) "Live · 1 person viewing"
                        else "Live · $liveViewers people viewing"
                    )

                    // Broadcast to MainActivity for the stats UI
                    handler.post {
                        sendBroadcast(Intent("com.example.selfhost.STATS_UPDATE").apply {
                            setPackage(packageName)
                            putExtra("liveViewers", liveViewers)
                            putExtra("dailyVisits", dailyVisits)
                            putExtra("monthlyVisits", monthlyVisits)
                            putExtra("totalVisits", totalVisits)
                        })
                    }
                }
            }
        )

        gatewaySocket?.connect()
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        gatewaySocket?.close()
        gatewaySocket = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(text: String) {
        handler.post {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(1, createNotification(text))
        }
    }

    private fun createNotification(text: String = "Tunnel is active"): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SelfHost · $slug")
            .setContentText(text)
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
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}