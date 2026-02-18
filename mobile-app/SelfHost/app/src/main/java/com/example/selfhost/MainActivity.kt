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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.Build



class MainActivity : ComponentActivity() {

    private var server: LocalServer? = null
    private var gatewaySocket: GatewaySocket? = null

    private var selectedHtmlUri by mutableStateOf<Uri?>(null)
    private var publicUrl by mutableStateOf<String?>(null)
    private var isRunning by mutableStateOf(false)
    private var slug by mutableStateOf("")


    private val pickHtmlFile =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                selectedHtmlUri = uri
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startTunnelService()
            } else {
                Toast.makeText(this, "Notification permission required", Toast.LENGTH_LONG).show()
            }
        }

    private fun startTunnelService() {
        val intent = Intent(this, TunnelService::class.java)
        intent.putExtra("slug", slug)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }


    private fun copyFolderFromUri(uri: Uri, destDir: File) {
        val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri) ?: return

        for (file in docFile.listFiles()) {
            if (file.isDirectory) {
                val newDir = File(destDir, file.name!!)
                newDir.mkdirs()
                copyFolderFromUri(file.uri, newDir)
            } else if (file.isFile) {
                val destFile = File(destDir, file.name!!)
                contentResolver.openInputStream(file.uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
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

            OutlinedTextField(
                value = slug,
                onValueChange = {
                    slug = it.lowercase().replace(" ", "")
                },
                label = { Text("Enter site slug") },
                singleLine = true
            )


            Button(onClick = {
                pickHtmlFile.launch(null)
            }) {
                Text(if (selectedHtmlUri == null) "Select HTML File" else "HTML Selected")
            }

            Button(
                onClick = { startHosting() },
                enabled = selectedHtmlUri != null && !isRunning && slug.isNotBlank()
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
            Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            server?.stop()

            val rootDir = File(filesDir, "site")

            if (rootDir.exists()) rootDir.deleteRecursively()
            rootDir.mkdirs()

            copyFolderFromUri(selectedHtmlUri!!, rootDir)

            server = LocalServer(6969, rootDir)
            server?.start()

            isRunning = true

            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                lifecycleScope.launchWhenResumed {
                    startTunnelService()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }



    private fun stopHosting() {
        server?.stop()
        server = null

        stopService(Intent(this, TunnelService::class.java))


        publicUrl = null
        isRunning = false

        Toast.makeText(this, "Server stopped", Toast.LENGTH_SHORT).show()
    }






    override fun onDestroy() {
        super.onDestroy()
        stopHosting()
    }
}
