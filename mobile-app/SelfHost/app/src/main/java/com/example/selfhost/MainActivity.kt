package com.example.app

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private var selectedHtmlUri by mutableStateOf<Uri?>(null)
    private var publicUrl by mutableStateOf<String?>(null)
    private var isRunning by mutableStateOf(false)
    private var slug by mutableStateOf("")
    private var slugError by mutableStateOf<String?>(null)
    private var hasSeenOnboarding by mutableStateOf(false)

    // Stats
    private var liveViewers by mutableStateOf(0)
    private var dailyVisits by mutableStateOf(0)
    private var monthlyVisits by mutableStateOf(0)
    private var totalVisits by mutableStateOf(0)

    // User — loaded from Firestore
    private var currentUser by mutableStateOf<UserModel?>(null)

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            liveViewers = intent?.getIntExtra("liveViewers", 0) ?: 0
            dailyVisits = intent?.getIntExtra("dailyVisits", 0) ?: 0
            monthlyVisits = intent?.getIntExtra("monthlyVisits", 0) ?: 0
            totalVisits = intent?.getIntExtra("totalVisits", 0) ?: 0
        }
    }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: return
            if (message.contains("slug", ignoreCase = true)) {
                slugError = "This slug is already taken — try another"
                isRunning = false
            }
        }
    }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

        // Guard: if not signed in, go back to SignInActivity
        if (!FirebaseRepository.isSignedIn) {
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
            return
        }

        val prefs = getSharedPreferences("selfhost", MODE_PRIVATE)
        slug = prefs.getString("last_slug", "") ?: ""
        hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false)

        // Restore UI state if service is already running (e.g. user reopened app)
        restoreRunningState()

        // Load user from Firestore
        lifecycleScope.launch {
            val uid = FirebaseRepository.currentFirebaseUser?.uid ?: return@launch
            currentUser = FirebaseRepository.getUser(uid)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsReceiver, IntentFilter("com.example.selfhost.STATS_UPDATE"), RECEIVER_NOT_EXPORTED)
            registerReceiver(errorReceiver, IntentFilter("com.example.selfhost.TUNNEL_ERROR"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statsReceiver, IntentFilter("com.example.selfhost.STATS_UPDATE"))
            registerReceiver(errorReceiver, IntentFilter("com.example.selfhost.TUNNEL_ERROR"))
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF080810)) {
                    AppShell()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        restoreRunningState()
        val uid = FirebaseRepository.currentFirebaseUser?.uid ?: return
        lifecycleScope.launch { currentUser = FirebaseRepository.getUser(uid) }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statsReceiver)
        unregisterReceiver(errorReceiver)
        // Don't call stopHosting here — service should keep running after activity is destroyed
    }

    @Suppress("DEPRECATION")
    private fun isTunnelServiceRunning(): Boolean {
        val manager = getSystemService(android.app.ActivityManager::class.java)
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == TunnelService::class.java.name }
    }

    private fun restoreRunningState() {
        if (isTunnelServiceRunning() && slug.isNotBlank()) {
            isRunning = true
            publicUrl = "https://untractably-hypothecary-vivienne.ngrok-free.dev/$slug"
            // Mark folder as selected so UI doesn't show "no folder selected"
            selectedHtmlUri = Uri.fromFile(java.io.File(filesDir, "site"))
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    // Fallback — open general battery settings
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }

    private fun startTunnelService() {
        val intent = Intent(this, TunnelService::class.java).apply { putExtra("slug", slug) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        publicUrl = "https://untractably-hypothecary-vivienne.ngrok-free.dev/$slug"

        // Save slug to Firestore
        val uid = FirebaseRepository.currentFirebaseUser?.uid ?: return
        lifecycleScope.launch { FirebaseRepository.saveSlug(uid, slug) }
    }

    private fun copyFolderFromUri(uri: Uri, destDir: File) {
        val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri) ?: return
        for (file in docFile.listFiles()) {
            if (file.isDirectory) {
                File(destDir, file.name!!).also { it.mkdirs() }.let { copyFolderFromUri(file.uri, it) }
            } else if (file.isFile) {
                contentResolver.openInputStream(file.uri)?.use { input ->
                    File(destDir, file.name!!).outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun startHosting() {
        if (!slug.matches(Regex("^[a-z0-9-]{3,30}$"))) {
            slugError = when {
                slug.length < 3 -> "Slug must be at least 3 characters"
                slug.length > 30 -> "Slug must be under 30 characters"
                else -> "Only letters, numbers and hyphens allowed"
            }
            return
        }
        slugError = null
        if (selectedHtmlUri == null) {
            Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("selfhost", MODE_PRIVATE).edit()
            .putString("last_slug", slug).putBoolean("has_seen_onboarding", true).apply()
        hasSeenOnboarding = true

        try {
            // Copy site files to filesDir/site so TunnelService can access them
            val rootDir = File(filesDir, "site").also {
                if (it.exists()) it.deleteRecursively()
                it.mkdirs()
            }
            copyFolderFromUri(selectedHtmlUri!!, rootDir)
            isRunning = true
            requestBatteryOptimizationExemption()
            if (Build.VERSION.SDK_INT >= 33)
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else lifecycleScope.launch { startTunnelService() }
        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopHosting() {
        val uid = FirebaseRepository.currentFirebaseUser?.uid
        if (uid != null && slug.isNotBlank()) {
            lifecycleScope.launch { FirebaseRepository.deactivateSlug(uid, slug) }
        }
        stopService(Intent(this, TunnelService::class.java))
        publicUrl = null; isRunning = false
        liveViewers = 0; dailyVisits = 0; monthlyVisits = 0; totalVisits = 0
    }

    private fun signOut() {
        stopHosting()
        FirebaseRepository.signOut()
        startActivity(Intent(this, SignInActivity::class.java))
        finish()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APP SHELL
    // ─────────────────────────────────────────────────────────────────────────

    @Composable
    fun AppShell() {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerSheet(onClose = { scope.launch { drawerState.close() } })
            },
            scrimColor = Color(0xAA000000),
            gesturesEnabled = true
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFF080810))) {
                Box(
                    Modifier.size(400.dp).offset((-80).dp, (-60).dp)
                        .background(Brush.radialGradient(listOf(Color(0x1500C2FF), Color.Transparent)))
                        .blur(100.dp)
                )
                Box(
                    Modifier.size(300.dp).align(Alignment.BottomEnd).offset(60.dp, 60.dp)
                        .background(Brush.radialGradient(listOf(Color(0x0F7B61FF), Color.Transparent)))
                        .blur(100.dp)
                )
                MainContent(onOpenDrawer = { scope.launch { drawerState.open() } })
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DRAWER
    // ─────────────────────────────────────────────────────────────────────────

    @Composable
    fun DrawerSheet(onClose: () -> Unit) {
        val user = currentUser
        val planLabel = when (user?.plan) {
            "pro" -> "Pro"
            "annual" -> "Annual"
            else -> "Free"
        }
        val planColor = when (user?.plan) {
            "pro" -> Color(0xFF00C2FF)
            "annual" -> Color(0xFFFFB300)
            else -> Color(0xFF00C2FF)
        }

        ModalDrawerSheet(
            drawerContainerColor = Color(0xFF0C0C18),
            modifier = Modifier.width(280.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 60.dp, bottom = 36.dp, start = 20.dp, end = 20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ── Top ──
                Column {
                    Text("selfhost", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text("Host sites from your phone", fontSize = 11.sp, color = Color(0xFF444455))
                    Spacer(Modifier.height(32.dp))

                    // Tunnel status
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF13131F), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("TUNNEL", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFF444455))
                            Text(
                                if (isRunning) "Active" else "Inactive",
                                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = if (isRunning) Color(0xFF00E676) else Color(0xFF444455)
                            )
                            if (isRunning && slug.isNotBlank()) {
                                Text(slug, fontSize = 10.sp, color = Color(0xFF00C2FF).copy(0.6f), fontWeight = FontWeight.Medium)
                            }
                        }
                        val inf = rememberInfiniteTransition(label = "ds")
                        val pulse by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dp")
                        Box(
                            Modifier.size(10.dp).background(
                                if (isRunning) Color(0xFF00E676).copy(alpha = pulse) else Color(0xFF222233), CircleShape
                            )
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    AnimatedVisibility(visible = isRunning) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF13131F), RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("LIVE VIEWERS", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFF444455))
                                Text(liveViewers.toString(), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF00E676), letterSpacing = (-1).sp)
                            }
                            Text("👁", fontSize = 20.sp)
                        }
                    }
                }

                // ── Bottom ──
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // User card
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF13131F), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .background(Brush.linearGradient(listOf(Color(0xFF00C2FF), Color(0xFF7B61FF))), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                (user?.name ?: "?").take(1).uppercase(),
                                fontSize = 17.sp, fontWeight = FontWeight.Black, color = Color.White
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                            Text(user?.name ?: "Loading...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(user?.email ?: "", fontSize = 10.sp, color = Color(0xFF555566), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    // Plan card → UpgradeActivity
                    Surface(
                        onClick = {
                            onClose()
                            startActivity(Intent(this@MainActivity, UpgradeActivity::class.java))
                        },
                        color = Color(0xFF13131F),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, planColor.copy(0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("PLAN", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFF444455))
                                Text(planLabel, fontSize = 15.sp, fontWeight = FontWeight.Black, color = planColor)
                            }
                            if (user?.plan == "free" || user?.plan == null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(planColor.copy(0.12f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("UPGRADE", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = planColor)
                                    Text("→", fontSize = 10.sp, color = planColor)
                                }
                            }
                        }
                    }

                    // Sign out
                    Surface(
                        onClick = { signOut() },
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E1E2E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                            Text("Sign Out", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF555566))
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN CONTENT
    // ─────────────────────────────────────────────────────────────────────────

    @Composable
    fun MainContent(onOpenDrawer: () -> Unit) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TopBar(onOpenDrawer)

            AnimatedVisibility(!hasSeenOnboarding, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                OnboardingHint()
            }

            SlugInputCard()
            FolderPickerCard()
            ControlRow()

            AnimatedVisibility(publicUrl != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                publicUrl?.let { UrlCard(it) }
            }

            AnimatedVisibility(isRunning, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                StatsGrid()
            }

            AnimatedVisibility(!isRunning && hasSeenOnboarding && selectedHtmlUri == null, enter = fadeIn(), exit = fadeOut()) {
                EmptyState()
            }
        }
    }

    @Composable
    fun TopBar(onOpenDrawer: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 52.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF13131F), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF1E1E2E), RoundedCornerShape(10.dp))
                    .clickable { onOpenDrawer() },
                contentAlignment = Alignment.Center
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    repeat(3) {
                        Box(Modifier.width(14.dp).height(1.5.dp).background(Color(0xFF888899), RoundedCornerShape(1.dp)))
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("selfhost", fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val inf = rememberInfiniteTransition(label = "tp")
                    val pulse by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "tpp")
                    Box(Modifier.size(6.dp).background(
                        if (isRunning) Color(0xFF00E676).copy(alpha = pulse) else Color(0xFF333344), CircleShape
                    ))
                    Text(
                        if (isRunning) "LIVE" else "OFFLINE",
                        fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                        color = if (isRunning) Color(0xFF00E676) else Color(0xFF333344)
                    )
                }
            }

            Spacer(Modifier.size(40.dp))
        }
    }

    @Composable
    fun OnboardingHint() {
        DarkCard(borderColor = Color(0xFF00C2FF).copy(0.2f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("👋", fontSize = 20.sp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Welcome to SelfHost", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Pick a folder with your HTML/CSS/JS site, choose a slug, then tap START to go live instantly.",
                        fontSize = 12.sp, color = Color(0xFF888899), lineHeight = 18.sp
                    )
                    Text("your-domain.com / your-slug /", fontSize = 11.sp, color = Color(0xFF00C2FF).copy(0.6f), fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    @Composable
    fun EmptyState() {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📁", fontSize = 36.sp)
            Text("No site selected", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF555566))
            Text("Pick a folder above to get started", fontSize = 12.sp, color = Color(0xFF333344))
        }
    }

    @Composable
    fun SlugInputCard() {
        DarkCard(borderColor = if (slugError != null) Color(0xFFFF4466).copy(0.4f) else Color(0xFF1E1E2E)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("SITE SLUG")
                OutlinedTextField(
                    value = slug,
                    onValueChange = { slug = it.lowercase().replace(" ", "").replace("/", ""); slugError = null },
                    placeholder = { Text("my-portfolio", color = Color(0xFF444455), fontSize = 14.sp) },
                    singleLine = true,
                    isError = slugError != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (slugError != null) Color(0xFFFF4466) else Color(0xFF00C2FF),
                        unfocusedBorderColor = if (slugError != null) Color(0xFFFF4466).copy(0.6f) else Color(0xFF222233),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF00C2FF),
                        focusedContainerColor = Color(0xFF111122), unfocusedContainerColor = Color(0xFF111122),
                        errorBorderColor = Color(0xFFFF4466), errorContainerColor = Color(0xFF111122),
                        errorTextColor = Color.White, errorCursorColor = Color(0xFFFF4466)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
                )
                AnimatedVisibility(visible = slugError != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠", fontSize = 11.sp, color = Color(0xFFFF4466))
                        Text(slugError ?: "", fontSize = 11.sp, color = Color(0xFFFF4466), fontWeight = FontWeight.Medium)
                    }
                }
                if (slug.isNotBlank() && slugError == null) {
                    Text("untractably-hypothecary-vivienne.ngrok-free.dev/$slug/", fontSize = 11.sp, color = Color(0xFF00C2FF).copy(0.7f), fontWeight = FontWeight.Medium)
                }
                if (slug.isBlank()) {
                    Text("3–30 chars · letters, numbers, hyphens only", fontSize = 10.sp, color = Color(0xFF333344))
                }
            }
        }
    }

    @Composable
    fun FolderPickerCard() {
        DarkCard {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label("SITE FOLDER")
                    Text(
                        if (selectedHtmlUri != null) "Folder selected ✓" else "No folder selected",
                        fontSize = 13.sp,
                        color = if (selectedHtmlUri != null) Color(0xFF00E676) else Color(0xFF666677)
                    )
                    if (selectedHtmlUri == null) {
                        Text("Select your HTML/CSS/JS project folder", fontSize = 11.sp, color = Color(0xFF333344))
                    }
                }
                OutlinedButton(
                    onClick = { pickFolder.launch(null) },
                    border = BorderStroke(1.dp, Color(0xFF333344)),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text(if (selectedHtmlUri != null) "CHANGE" else "BROWSE", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
    }

    @Composable
    fun ControlRow() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { startHosting() },
                    enabled = selectedHtmlUri != null && !isRunning && slug.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C2FF), contentColor = Color(0xFF080810),
                        disabledContainerColor = Color(0xFF1A1A2A), disabledContentColor = Color(0xFF333344)
                    )
                ) { Text("START", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 14.sp) }

                Button(
                    onClick = { stopHosting() },
                    enabled = isRunning,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A2A), contentColor = Color(0xFFFF4466),
                        disabledContainerColor = Color(0xFF111118), disabledContentColor = Color(0xFF333344)
                    ),
                    border = BorderStroke(1.dp, if (isRunning) Color(0xFFFF4466).copy(0.4f) else Color.Transparent)
                ) { Text("STOP", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 14.sp) }
            }

            if (!isRunning) {
                val hint = when {
                    slug.isBlank() -> "Enter a slug to continue"
                    selectedHtmlUri == null -> "Select a folder to continue"
                    else -> null
                }
                AnimatedVisibility(visible = hint != null) {
                    Text(hint ?: "", fontSize = 11.sp, color = Color(0xFF444455), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
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
                    url, fontSize = 13.sp, color = Color(0xFF00C2FF), fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D1A22), RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = {}, onLongClick = {
                            val clipboard = getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("SelfHost URL", url))
                            Toast.makeText(this@MainActivity, "Copied!", Toast.LENGTH_SHORT).show()
                        })
                        .padding(12.dp)
                )
            }
        }
    }

    @Composable
    fun StatsGrid() {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("ANALYTICS")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(Modifier.weight(1f), liveViewers.toString(), "LIVE NOW", Color(0xFF00E676), isLive = true)
                StatCard(Modifier.weight(1f), dailyVisits.toString(), "TODAY", Color(0xFF00C2FF))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(Modifier.weight(1f), monthlyVisits.toString(), "THIS MONTH", Color(0xFF7B61FF))
                StatCard(Modifier.weight(1f), if (totalVisits >= 1000) "${totalVisits / 1000}k" else totalVisits.toString(), "ALL TIME", Color(0xFFFFB300))
            }
        }
    }

    @Composable
    fun StatCard(modifier: Modifier = Modifier, value: String, label: String, accentColor: Color, isLive: Boolean = false) {
        val inf = rememberInfiniteTransition(label = "sc")
        val pulseAlpha by inf.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "sca")
        Box(
            modifier = modifier
                .background(Color(0xFF0F0F1C), RoundedCornerShape(14.dp))
                .border(1.dp, accentColor.copy(0.15f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = if (isLive) accentColor.copy(alpha = pulseAlpha) else accentColor, letterSpacing = (-1).sp)
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color(0xFF444455))
            }
        }
    }

    @Composable
    fun DarkCard(borderColor: Color = Color(0xFF1E1E2E), content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F0F1C), RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }

    @Composable
    fun Label(text: String) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFF444455))
    }
}