package com.example.selfhost

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.ClipboardManager
import android.content.ClipData
import java.io.File


class MainActivity : ComponentActivity() {

    private var server: LocalServer? = null

    private var selectedHtmlUri by mutableStateOf<Uri?>(null)
    private var publicUrl by mutableStateOf<String?>(null)
    private var isRunning by mutableStateOf(false)

    private val pickHtmlFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                selectedHtmlUri = uri
            }
        }

    private fun prepareCloudflared(): File {
        val bin = File(filesDir, "cloudflared")

        if (!bin.exists()) {
            assets.open("cloudflared").use { input ->
                bin.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            bin.setExecutable(true)
        }

        return bin
    }

    private fun getLocalIp(): String? {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            if (!intf.name.equals("wlan0", ignoreCase = true)) continue
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    private var cloudflaredProcess: Process? = null

    private fun startCloudflaredTunnel() {
        val bin = prepareCloudflared()
        val host = getLocalIp()

        if (host == null) {
            Toast.makeText(this, "No WLAN IP found", Toast.LENGTH_LONG).show()
            return
        }

        val process = ProcessBuilder(
            "/system/bin/linker64",
            bin.absolutePath,
            "tunnel",
            "--url",
            "http://$host:6969"
        )
            .redirectErrorStream(true)
            .start()


        cloudflaredProcess = process

        Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    android.util.Log.d("cloudflared", line)

                    val match =
                        Regex("https://.*trycloudflare.com").find(line)

                    if (match != null) {
                        runOnUiThread {
                            publicUrl = match.value
                        }
                    }
                }
            }
        }.start()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MainUI()
                }
            }
        }
    }

    @Composable
    fun MainUI() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text("SelfHost", style = MaterialTheme.typography.headlineMedium)

            Button(onClick = {
                pickHtmlFile.launch(arrayOf("text/html"))
            }) {
                Text(if (selectedHtmlUri == null) "Select HTML File" else "HTML Selected")
            }

            Button(
                onClick = { startHosting() },
                enabled = selectedHtmlUri != null && !isRunning
            ) {
                Text("START")
            }

            Button(
                onClick = { stopHosting() },
                enabled = isRunning
            ) {
                Text("STOP")
            }
            publicUrl?.let { url ->
                Text("Public URL:")

                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyLarge
                )

                Button(
                    onClick = {
                        val clipboard =
                            getSystemService(ClipboardManager::class.java)

                        val clip = ClipData.newPlainText(
                            "SelfHost URL",
                            url
                        )
                        clipboard.setPrimaryClip(clip)

                        Toast.makeText(
                            this@MainActivity,
                            "URL copied to clipboard",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text("COPY")
                }
            }


            Text(if (isRunning) "Status: RUNNING" else "Status: STOPPED")
        }
    }





    private fun startHosting() {
        if (selectedHtmlUri == null) {
            Toast.makeText(this, "No HTML file selected", Toast.LENGTH_SHORT).show()
            return
        }



        try {
            server?.stop()
            server = null

            val htmlText = contentResolver
                .openInputStream(selectedHtmlUri!!)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""


            server = LocalServer(6969, htmlText) { url ->
                runOnUiThread {
                    publicUrl = url
                }
            }

            server?.start()
            isRunning = true
            startCloudflaredTunnel()



        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                this,
                "Failed: ${e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun stopHosting() {
        server?.stop()
        server = null

        cloudflaredProcess?.destroy()
        cloudflaredProcess = null

        publicUrl = null
        isRunning = false

        Toast.makeText(this, "Server stopped", Toast.LENGTH_SHORT).show()
    }






    override fun onDestroy() {
        super.onDestroy()
        stopHosting()
    }
}
