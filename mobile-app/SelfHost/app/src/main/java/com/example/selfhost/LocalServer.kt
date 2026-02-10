package com.example.selfhost

import fi.iki.elonen.NanoHTTPD
import android.util.Log
import java.io.File

class LocalServer(
    port: Int,
    private val htmlContent: String,
    private val onTunnelUrl: (String) -> Unit
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {

        Log.e(
            "LocalServer",
            "HIT: method=${session.method} uri=${session.uri}"
        )

        if (session.method == Method.POST && session.uri == "/tunnel-url") {

            val files = HashMap<String, String>()
            session.parseBody(files) // REQUIRED

            val rawBody = files["postData"]
                ?: files.values.firstOrNull()?.let { path ->
                    try { File(path).readText() } catch (e: Exception) { null }
                }

            Log.e("LocalServer", "BODY = [$rawBody]")

            if (!rawBody.isNullOrBlank()) {
                onTunnelUrl(rawBody.trim())
            }

            return newFixedLengthResponse("OK")
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            htmlContent
        )
    }

}
