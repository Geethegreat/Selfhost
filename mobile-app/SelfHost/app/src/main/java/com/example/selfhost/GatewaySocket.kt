package com.example.selfhost

import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class GatewaySocket(
    private val gatewayUrl: String,
    private val slug: String
) {

    private val wsClient = OkHttpClient()
    private val httpClient = OkHttpClient()
    private var socket: WebSocket? = null
    private var isConnected = false

    fun connect() {

        val request = Request.Builder()
            .url(gatewayUrl)
            .build()

        socket = wsClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                android.util.Log.d("GatewaySocket", "Connected")
                val registerJson = JSONObject().apply {
                    put("type", "REGISTER")
                    put("slug", slug)
                }

                ws.send(registerJson.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {

                val json = JSONObject(text)

                // 🔥 Handle ERROR from gateway
                if (json.optString("type") == "ERROR") {
                    android.util.Log.e("GatewaySocket", "Gateway error: ${json.optString("message")}")
                    close()
                    return
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
                android.util.Log.e("GatewaySocket", "WS error", t)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
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

        val url = "http://localhost:6969$path"

        val requestBuilder = Request.Builder().url(url)

        // Forward headers
        headersJson?.keys()?.forEach { key ->
            val lower = key.lowercase()
            val value = headersJson.getString(key)

            if (lower != "host" && lower != "accept-encoding") {
                requestBuilder.addHeader(key, value)
            }
        }

        val requestBody = when {
            method == "POST" || method == "PUT" || method == "PATCH" ->
                RequestBody.create(null, bodyString ?: "")
            else -> null
        }

        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody ?: RequestBody.create(null, ByteArray(0)))
            "PUT" -> requestBuilder.put(requestBody ?: RequestBody.create(null, ByteArray(0)))
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        httpClient.newCall(requestBuilder.build()).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                sendError(id, "Local server error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {

                val body = response.body?.string() ?: ""

                val headersOut = JSONObject()
                for ((name, value) in response.headers) {
                    val lower = name.lowercase()

                    if (lower !in listOf(
                            "content-length",
                            "transfer-encoding",
                            "connection",
                            "content-encoding"
                        )
                    ) {
                        headersOut.put(name, value)
                    }
                }


                val json = JSONObject()
                    .put("id", id)
                    .put("status", response.code)
                    .put("headers", headersOut)
                    .put("body", body)

                socket?.send(json.toString())
            }
        })
    }

    private fun sendError(id: String, message: String) {
        val json = JSONObject()
            .put("id", id)
            .put("body", message)

        socket?.send(json.toString())
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }
}
