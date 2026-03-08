package com.example.selfhost

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.combinedClickable


class MainActivity : ComponentActivity() {

    private var server: LocalServer? = null

    private var selectedHtmlUri by mutableStateOf<Uri?>(null)
    private var publicUrl by mutableStateOf<String?>(null)
    private var isRunning by mutableStateOf(false)
    private var slug by mutableStateOf("")

    // Stats state
    private var liveViewers by mutableStateOf(0)
    private var dailyVisits by mutableStateOf(0)
    private var monthlyVisits by mutableStateOf(0)
    private var totalVisits by mutableStateOf(0)

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            liveViewers = intent?.getIntExtra("liveViewers", 0) ?: 0
            dailyVisits = intent?.getIntExtra("dailyVisits", 0) ?: 0
            monthlyVisits = intent?.getIntExtra("monthlyVisits", 0) ?: 0
            totalVisits = intent?.getIntExtra("totalVisits", 0) ?: 0
        }
    }
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                selectedHtmlUri = uri
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTunnelService()
            else Toast.makeText(this, "Notification permission required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                statsReceiver,
                IntentFilter("com.example.selfhost.STATS_UPDATE"),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(statsReceiver, IntentFilter("com.example.selfhost.STATS_UPDATE"))
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0A0A0F)) {
                    MainUI()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statsReceiver)
        stopHosting()
    }

    private fun startTunnelService() {
        val intent = Intent(this, TunnelService::class.java).apply {
            putExtra("slug", slug)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        publicUrl = "https://wheelstracker.com/selfhost/u/$slug/"
    }

    private fun copyFolderFromUri(uri: Uri, destDir: File) {
        val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri) ?: return
        for (file in docFile.listFiles()) {
            if (file.isDirectory) {
                val newDir = File(destDir, file.name!!).also { it.mkdirs() }
                copyFolderFromUri(file.uri, newDir)
            } else if (file.isFile) {
                val destFile = File(destDir, file.name!!)
                contentResolver.openInputStream(file.uri)?.use { input ->
                    destFile.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun startHosting() {
        if (selectedHtmlUri == null) {
            Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            server?.stop()
            val rootDir = File(filesDir, "site").also {
                if (it.exists()) it.deleteRecursively()
                it.mkdirs()
            }
            copyFolderFromUri(selectedHtmlUri!!, rootDir)
            server = LocalServer(6969, rootDir).also { it.start() }
            isRunning = true

            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                lifecycleScope.launch { startTunnelService() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopHosting() {
        server?.stop()
        server = null
        stopService(Intent(this, TunnelService::class.java))
        publicUrl = null
        isRunning = false
        liveViewers = 0
        dailyVisits = 0
        monthlyVisits = 0
        totalVisits = 0
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    @Composable
    fun MainUI() {
        val scrollState = rememberScrollState()

        Box(Modifier.fillMaxSize()) {
            // Ambient background glow
            Box(
                Modifier
                    .size(400.dp)
                    .offset((-80).dp, (-60).dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0x2200C2FF), Color.Transparent)
                        )
                    )
                    .blur(80.dp)
            )
            Box(
                Modifier
                    .size(300.dp)
                    .align(Alignment.BottomEnd)
                    .offset(60.dp, 60.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0x1A7B61FF), Color.Transparent)
                        )
                    )
                    .blur(80.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                HeaderSection()

                // Slug input
                SlugInputCard()

                // Folder picker
                FolderPickerCard()

                // Control buttons
                ControlRow()

                // URL card (shown when running)
                AnimatedVisibility(
                    visible = publicUrl != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    publicUrl?.let { UrlCard(it) }
                }

                // Stats (shown when running)
                AnimatedVisibility(
                    visible = isRunning,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    StatsGrid()
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    fun HeaderSection() {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "selfhost",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.5).sp,
                color = Color.White
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Live status dot
                val dotColor by animateColorAsState(
                    if (isRunning) Color(0xFF00E676) else Color(0xFF444455),
                    animationSpec = tween(500), label = "dot"
                )
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "pulseAlpha"
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            dotColor.copy(alpha = if (isRunning) pulseAlpha else 1f),
                            CircleShape
                        )
                )
                Text(
                    if (isRunning) "LIVE" else "OFFLINE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = if (isRunning) Color(0xFF00E676) else Color(0xFF444455)
                )
            }
        }
    }

    @Composable
    fun SlugInputCard() {
        DarkCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("SITE SLUG")
                OutlinedTextField(
                    value = slug,
                    onValueChange = { slug = it.lowercase().replace(" ", "").replace("/", "") },
                    placeholder = { Text("my-portfolio", color = Color(0xFF444455), fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00C2FF),
                        unfocusedBorderColor = Color(0xFF222233),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF00C2FF),
                        focusedContainerColor = Color(0xFF111122),
                        unfocusedContainerColor = Color(0xFF111122)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                if (slug.isNotBlank()) {
                    Text(
                        "wheelstracker.com/selfhost/u/$slug/",
                        fontSize = 11.sp,
                        color = Color(0xFF00C2FF).copy(alpha = 0.7f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    @Composable
    fun FolderPickerCard() {
        DarkCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label("SITE FOLDER")
                    Text(
                        if (selectedHtmlUri != null) "Folder selected ✓" else "No folder selected",
                        fontSize = 13.sp,
                        color = if (selectedHtmlUri != null) Color(0xFF00E676) else Color(0xFF666677)
                    )
                }
                OutlinedButton(
                    onClick = { pickFolder.launch(null) },
                    border = BorderStroke(1.dp, Color(0xFF333344)),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text(
                        if (selectedHtmlUri != null) "CHANGE" else "BROWSE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }

    @Composable
    fun ControlRow() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // START button
            Button(
                onClick = { startHosting() },
                enabled = selectedHtmlUri != null && !isRunning && slug.isNotBlank(),
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C2FF),
                    contentColor = Color(0xFF0A0A0F),
                    disabledContainerColor = Color(0xFF1A1A2A),
                    disabledContentColor = Color(0xFF333344)
                )
            ) {
                Text("START", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 14.sp)
            }

            // STOP button
            Button(
                onClick = { stopHosting() },
                enabled = isRunning,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A1A2A),
                    contentColor = Color(0xFFFF4466),
                    disabledContainerColor = Color(0xFF111118),
                    disabledContentColor = Color(0xFF333344)
                ),
                border = BorderStroke(1.dp, if (isRunning) Color(0xFFFF4466).copy(0.4f) else Color.Transparent)
            ) {
                Text("STOP", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 14.sp)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun UrlCard(url: String) {
        DarkCard(borderColor = Color(0xFF00C2FF).copy(0.3f)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("PUBLIC URL · HOLD TO COPY")
                Text(
                    url,
                    fontSize = 13.sp,
                    color = Color(0xFF00C2FF),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D1A22), RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val clipboard = getSystemService(ClipboardManager::class.java)
                                clipboard.setPrimaryClip(ClipData.newPlainText("SelfHost URL", url))
                                Toast.makeText(this@MainActivity, "Copied!", Toast.LENGTH_SHORT).show()
                            }
                        )
                        .padding(12.dp)
                )
            }
        }
    }

    @Composable
    fun StatsGrid() {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("ANALYTICS")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = liveViewers.toString(),
                    label = "LIVE NOW",
                    accentColor = Color(0xFF00E676),
                    isLive = true
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = dailyVisits.toString(),
                    label = "TODAY",
                    accentColor = Color(0xFF00C2FF)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = monthlyVisits.toString(),
                    label = "THIS MONTH",
                    accentColor = Color(0xFF7B61FF)
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = if (totalVisits >= 1000) "${totalVisits / 1000}k" else totalVisits.toString(),
                    label = "ALL TIME",
                    accentColor = Color(0xFFFFB300)
                )
            }
        }
    }

    @Composable
    fun StatCard(
        modifier: Modifier = Modifier,
        value: String,
        label: String,
        accentColor: Color,
        isLive: Boolean = false
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "live")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.6f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "liveAlpha"
        )

        Box(
            modifier = modifier
                .background(Color(0xFF111122), RoundedCornerShape(14.dp))
                .border(1.dp, accentColor.copy(0.15f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isLive) accentColor.copy(alpha = pulseAlpha) else accentColor,
                    letterSpacing = (-1).sp
                )
                Text(
                    label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = Color(0xFF444455)
                )
            }
        }
    }

    @Composable
    fun DarkCard(
        borderColor: Color = Color(0xFF1E1E2E),
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F0F1A), RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }

    @Composable
    fun Label(text: String) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = Color(0xFF444455)
        )
    }
}