package com.example.selfhost

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface GatewayListener {
    fun onConnected()
    fun onError(message: String)
    fun onDisconnected()
    fun onStats(liveViewers: Int, dailyVisits: Int, monthlyVisits: Int, totalVisits: Int)
}

class GatewaySocket(
    private val gatewayUrl: String,
    private val slug: String,
    private val localPort: Int = 6969,
    private val listener: GatewayListener
) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(45, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private var shouldReconnect = true

    fun connect() {
        if (isConnected) return

        val request = Request.Builder()
            .url(gatewayUrl)
            .addHeader("ngrok-skip-browser-warning", "true")
            .addHeader("User-Agent", "SelfHostAndroidAgent/1.0")
            .build()

        shouldReconnect = true

        socket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                reconnectAttempts = 0
                android.util.Log.d("GatewaySocket", "Connected")

                ws.send(JSONObject().apply {
                    put("type", "REGISTER")
                    put("slug", slug)
                }.toString())

                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)

                when (json.optString("type")) {
                    "PING" -> {
                        ws.send(JSONObject().put("type", "PONG").toString())
                        return
                    }
                    "ERROR" -> {
                        val msg = json.optString("message")
                        android.util.Log.e("GatewaySocket", "Gateway error: $msg")
                        listener.onError(msg)
                        if (msg.contains("slug", ignoreCase = true)) {
                            shouldReconnect = false
                        }
                        return
                    }
                    "STATS" -> {
                        val liveViewers = json.optInt("liveViewers", 0)
                        val dailyVisits = json.optInt("dailyVisits", 0)
                        val monthlyVisits = json.optInt("monthlyVisits", 0)
                        val totalVisits = json.optInt("totalVisits", 0)
                        android.util.Log.d("GatewaySocket",
                            "Stats: live=$liveViewers daily=$dailyVisits monthly=$monthlyVisits total=$totalVisits")
                        listener.onStats(liveViewers, dailyVisits, monthlyVisits, totalVisits)
                        return
                    }
                }

                if (json.optString("method") == "HTTP_REQUEST") {
                    val id = json.getString("id")
                    val path = json.getString("path")
                    val httpMethod = json.getString("httpMethod")
                    val headers = json.optJSONObject("headers")
                    val body = json.optString("body", "")
                    forwardToLocalServer(id, path, httpMethod, headers, body)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                isConnected = false
                socket = null
                android.util.Log.e("GatewaySocket", "WS failure: ${t::class.java.simpleName} — ${t.message}", t)
                listener.onError("Connection failed: ${t::class.java.simpleName}: ${t.message}")
                attemptReconnect()
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                socket = null
                android.util.Log.d("GatewaySocket", "WS closed: $code $reason")
                listener.onDisconnected()
                attemptReconnect()
            }
        })
    }

    private fun forwardToLocalServer(
        id: String,
        path: String,
        method: String,
        headersJson: JSONObject?,
        bodyString: String?
    ) {
        val url = "http://localhost:$localPort$path"
        val requestBuilder = Request.Builder().url(url)

        headersJson?.keys()?.forEach { key ->
            val lower = key.lowercase()
            if (lower != "host" && lower != "accept-encoding") {
                requestBuilder.addHeader(key, headersJson.getString(key))
            }
        }

        val mediaType = headersJson?.optString("content-type")?.toMediaTypeOrNull()

        val bodyBytes: ByteArray = when {
            bodyString.isNullOrEmpty() -> ByteArray(0)
            isBinaryContentType(headersJson?.optString("content-type") ?: "") ->
                android.util.Base64.decode(bodyString, android.util.Base64.NO_WRAP)
            else -> bodyString.toByteArray(Charsets.UTF_8)
        }

        val requestBody = bodyBytes.toRequestBody(mediaType)

        when (method.uppercase()) {
            "GET"    -> requestBuilder.get()
            "POST"   -> requestBuilder.post(requestBody)
            "PUT"    -> requestBuilder.put(requestBody)
            "PATCH"  -> requestBuilder.patch(requestBody)
            "DELETE" -> requestBuilder.delete()
            else     -> requestBuilder.get()
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                sendError(id, "Local server error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                val contentType = response.headers["content-type"] ?: ""
                val isBinary = isBinaryContentType(contentType)

                val headersOut = JSONObject()
                for ((name, value) in response.headers) {
                    val lower = name.lowercase()
                    if (lower !in listOf("content-length", "transfer-encoding",
                            "connection", "content-encoding")) {
                        headersOut.put(name, value)
                    }
                }

                socket?.send(
                    JSONObject()
                        .put("id", id)
                        .put("status", response.code)
                        .put("headers", headersOut)
                        .put("binary", isBinary)
                        .put("body", if (isBinary)
                            android.util.Base64.encodeToString(bodyBytes, android.util.Base64.NO_WRAP)
                        else
                            String(bodyBytes, Charsets.UTF_8)
                        )
                        .toString()
                )
            }
        })
    }

    private fun isBinaryContentType(contentType: String): Boolean {
        if (contentType.isEmpty()) return false
        val textTypes = listOf(
            "text/",
            "application/json",
            "application/xml",
            "application/javascript",
            "application/ld+json",
            "application/x-www-form-urlencoded",
            "image/svg"
        )
        return textTypes.none { contentType.contains(it, ignoreCase = true) }
    }

    private fun sendError(id: String, message: String) {
        socket?.send(
            JSONObject()
                .put("id", id)
                .put("status", 500)
                .put("headers", JSONObject())
                .put("binary", false)
                .put("body", message)
                .toString()
        )
    }

    private fun attemptReconnect() {
        if (!shouldReconnect) return

        reconnectRunnable?.let { handler.removeCallbacks(it) }

        val delay = minOf(30_000L, 2_000L * (1L shl reconnectAttempts).coerceAtMost(15))
        reconnectAttempts++

        android.util.Log.d("GatewaySocket", "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")

        reconnectRunnable = Runnable {
            if (shouldReconnect) connect()
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }

    fun close() {
        shouldReconnect = false
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        socket?.close(1000, "bye")
        socket = null
        isConnected = false
    }
}