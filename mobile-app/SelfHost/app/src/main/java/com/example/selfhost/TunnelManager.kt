package com.example.selfhost

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import android.util.Log

class TunnelManager(private val context: Context) {

    private var process: Process? = null
    private var tunnelThread: Thread? = null

    fun start(
        onUrlReady: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (process != null) {
            onError("Tunnel already running")
            return
        }

        tunnelThread = Thread {
            try {
                val cloudflaredFile = prepareCloudflared()

                val command = listOf(
                    cloudflaredFile.absolutePath,
                    "tunnel",
                    "--url",
                    "http://127.0.0.1:8080"
                )

                val processBuilder = ProcessBuilder(command)
                processBuilder.redirectErrorStream(true)

                process = processBuilder.start()

                val reader = BufferedReader(
                    InputStreamReader(process!!.inputStream)
                )

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // DEBUG
                     Log.d("Tunnel", line!!)

                    if (line!!.contains("trycloudflare.com")) {
                        val url = extractUrl(line!!)
                        if (url != null) {
                            onUrlReady(url)
                        }
                    }
                }

            } catch (e: Exception) {
                onError(e.message ?: "Tunnel failed")
            }
        }

        tunnelThread!!.start()
    }

    fun stop() {
        try {
            process?.destroy()
            process = null
            tunnelThread = null
        } catch (_: Exception) {
        }
    }

    // ---------------- HELPERS ----------------

    private fun prepareCloudflared(): File {
        val outFile = File(context.filesDir, "cloudflared")

        if (!outFile.exists()) {
            context.assets.open("cloudflared").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        outFile.setExecutable(true)

        return outFile
    }

    private fun extractUrl(line: String): String? {
        val regex = Regex("https://[a-zA-Z0-9.-]+\\.trycloudflare\\.com")
        return regex.find(line)?.value
    }
}
