package com.example.app

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File

class TunnelService : Service() {

    private var gatewaySocket: GatewaySocket? = null
    private var localServer: LocalServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var slug: String? = null
    private var isRunning = false
    private var liveViewers = 0

    companion object {
        const val CHANNEL_ID = "tunnel_channel"
        const val ACTION_STOP = "com.example.selfhost.ACTION_STOP"
        private const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }

        val newSlug = intent?.getStringExtra("slug")
        if (newSlug.isNullOrBlank()) {
            stopTunnel()
            return START_NOT_STICKY
        }

        if (isRunning && newSlug == slug) return START_STICKY

        slug = newSlug
        isRunning = true

        startForeground(NOTIF_ID, createNotification("Connecting..."))
        startLocalServer()
        connectSocket()

        return START_STICKY
    }

    private fun startLocalServer() {
        try {
            localServer?.stop()
            val rootDir = File(filesDir, "site")
            if (!rootDir.exists() || !rootDir.isDirectory) {
                updateNotification("Error: site folder missing")
                return
            }
            localServer = LocalServer(6969, rootDir).also { it.start() }
        } catch (e: Exception) {
            updateNotification("Local server error: ${e.message}")
        }
    }

    private fun connectSocket() {
        gatewaySocket?.close()
        gatewaySocket = null

        gatewaySocket = GatewaySocket(
            gatewayUrl = "wss://untractably-hypothecary-vivienne.ngrok-free.dev",
            slug = slug!!,
            listener = object : GatewayListener {

                override fun onConnected() {
                    updateNotification("Live · no viewers")
                }

                override fun onError(message: String) {
                    updateNotification("Error: $message")
                }

                override fun onDisconnected() {
                    if (isRunning) updateNotification("Reconnecting...")
                }

                override fun onStats(
                    liveViewers: Int,
                    dailyVisits: Int,
                    monthlyVisits: Int,
                    totalVisits: Int
                ) {
                    this@TunnelService.liveViewers = liveViewers

                    updateNotification(
                        when (liveViewers) {
                            0 -> "Live · no viewers"
                            1 -> "Live · 1 person viewing"
                            else -> "Live · $liveViewers people viewing"
                        }
                    )

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isRunning || slug.isNullOrBlank()) return

        val restartIntent = Intent(this, TunnelService::class.java).apply {
            putExtra("slug", slug)
        }
        val pendingIntent = PendingIntent.getService(
            this, 2, restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun stopTunnel() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        gatewaySocket?.close()
        gatewaySocket = null
        localServer?.stop()
        localServer = null

        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIF_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(text: String) {
        if (!isRunning) return
        handler.post {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIF_ID, createNotification(text))
        }
    }

    private fun createNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TunnelService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SelfHost · $slug")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}