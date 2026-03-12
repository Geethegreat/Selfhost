package com.example.selfhost

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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

class UpgradeActivity : ComponentActivity() {

    private var currentPlan by mutableStateOf("free")
    private var isUpdating by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load current plan
        lifecycleScope.launch {
            val uid = FirebaseRepository.currentFirebaseUser?.uid ?: return@launch
            val user = FirebaseRepository.getUser(uid)
            currentPlan = user?.plan ?: "free"
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF080810)) {
                    UpgradeScreen()
                }
            }
        }
    }

    private fun selectPlan(plan: String) {
        if (plan == currentPlan || plan == "free") return
        val uid = FirebaseRepository.currentFirebaseUser?.uid ?: run {
            Toast.makeText(this, "Not signed in", Toast.LENGTH_SHORT).show()
            return
        }
        isUpdating = true
        lifecycleScope.launch {
            try {
                FirebaseRepository.updatePlan(uid, plan)
                currentPlan = plan
                Toast.makeText(this@UpgradeActivity, "Plan updated!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@UpgradeActivity, "Failed to update plan", Toast.LENGTH_SHORT).show()
            } finally {
                isUpdating = false
            }
        }
    }

    @Composable
    fun UpgradeScreen() {
        val scrollState = rememberScrollState()

        Box(Modifier.fillMaxSize()) {
            Box(Modifier.size(350.dp).offset((-60).dp, (-40).dp).background(Brush.radialGradient(listOf(Color(0x1500C2FF), Color.Transparent))).blur(100.dp))
            Box(Modifier.size(300.dp).align(Alignment.BottomEnd).offset(60.dp, 60.dp).background(Brush.radialGradient(listOf(Color(0x12FFB300), Color.Transparent))).blur(100.dp))

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp).padding(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Top bar
                Row(modifier = Modifier.fillMaxWidth().padding(top = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(36.dp).background(Color(0xFF13131F), RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF1E1E2E), RoundedCornerShape(10.dp)).clickable { finish() },
                        contentAlignment = Alignment.Center
                    ) { Text("←", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                    Text("Choose a Plan", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = (-0.5).sp)
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Go further with Pro", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = (-1).sp, lineHeight = 32.sp)
                    Text("Unlock unlimited hosting and advanced analytics.", fontSize = 13.sp, color = Color(0xFF555566), lineHeight = 20.sp)
                }

                PlanCard(
                    name = "Free", price = "₹0", period = "1 week trial",
                    accentColor = Color(0xFF444455), borderColor = Color(0xFF1E1E2E),
                    isCurrentPlan = currentPlan == "free", isFeatured = false,
                    features = listOf("1 active site" to true, "Basic analytics" to true, "Community support" to true, "Custom slug" to true, "Unlimited sites" to false, "Priority reconnect" to false),
                    ctaText = if (currentPlan == "free") "Current Plan" else "Downgrade",
                    ctaEnabled = false,
                    onSelect = {}
                )

                PlanCard(
                    name = "Pro", price = "$2", period = "per month",
                    accentColor = Color(0xFF00C2FF), borderColor = Color(0xFF00C2FF).copy(0.3f),
                    isCurrentPlan = currentPlan == "pro", isFeatured = true,
                    features = listOf("Unlimited active sites" to true, "Full analytics" to true, "Priority reconnect" to true, "Custom slug" to true, "Email support" to true, "Early access features" to true),
                    ctaText = when (currentPlan) { "pro" -> "Current Plan"; else -> "Upgrade to Pro" },
                    ctaEnabled = currentPlan != "pro" && !isUpdating,
                    onSelect = { selectPlan("pro") }
                )

                PlanCard(
                    name = "Annual", price = "$20", period = "per year · save $4",
                    accentColor = Color(0xFFFFB300), borderColor = Color(0xFFFFB300).copy(0.25f),
                    isCurrentPlan = currentPlan == "annual", isFeatured = false, badge = "2 MONTHS FREE", badgeColor = Color(0xFFFFB300),
                    features = listOf("Everything in Pro" to true, "Unlimited active sites" to true, "Full analytics" to true, "Priority reconnect" to true, "Email support" to true, "Early access features" to true),
                    ctaText = when (currentPlan) { "annual" -> "Current Plan"; else -> "Get Annual" },
                    ctaEnabled = currentPlan != "annual" && !isUpdating,
                    onSelect = { selectPlan("annual") }
                )

                Text("Payments coming soon · Join the waitlist to be notified", fontSize = 10.sp, color = Color(0xFF333344), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }

    @Composable
    fun PlanCard(
        name: String, price: String, period: String,
        accentColor: Color, borderColor: Color,
        isCurrentPlan: Boolean, isFeatured: Boolean,
        badge: String? = null, badgeColor: Color = Color(0xFFFFB300),
        features: List<Pair<String, Boolean>>,
        ctaText: String, ctaEnabled: Boolean,
        onSelect: () -> Unit
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF0F0F1C), RoundedCornerShape(18.dp)).border(1.dp, if (isCurrentPlan) accentColor.copy(0.5f) else borderColor, RoundedCornerShape(18.dp)).padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = accentColor)
                            if (isFeatured) Box(Modifier.background(accentColor.copy(0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("POPULAR", fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = accentColor) }
                            badge?.let { Box(Modifier.background(badgeColor.copy(0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(it, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = badgeColor) } }
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(price, fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = (-1).sp)
                            Text(period, fontSize = 11.sp, color = Color(0xFF555566), modifier = Modifier.padding(bottom = 5.dp))
                        }
                    }
                    if (isCurrentPlan) {
                        Box(Modifier.background(accentColor.copy(0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("ACTIVE", fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = accentColor)
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E1E2E)))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    features.forEach { (feature, included) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).background(if (included) accentColor.copy(0.12f) else Color(0xFF1A1A1A), CircleShape), contentAlignment = Alignment.Center) {
                                Text(if (included) "✓" else "×", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (included) accentColor else Color(0xFF333344))
                            }
                            Text(feature, fontSize = 13.sp, color = if (included) Color(0xFFCCCCDD) else Color(0xFF444455))
                        }
                    }
                }

                Button(
                    onClick = onSelect,
                    enabled = ctaEnabled,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color(0xFF080810), disabledContainerColor = Color(0xFF1A1A2A), disabledContentColor = Color(0xFF444455))
                ) {
                    if (isUpdating && ctaText != "Current Plan") {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFF080810), strokeWidth = 2.dp)
                    } else {
                        Text(ctaText, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
