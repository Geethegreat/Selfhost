package com.example.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

class SignInActivity : ComponentActivity() {

    private var isLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already signed in, skip straight to MainActivity
        if (FirebaseRepository.isSignedIn) {
            goToMain()
            return
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF080810)) {
                    SignInScreen()
                }
            }
        }
    }

    @Composable
    fun SignInScreen() {
        Box(Modifier.fillMaxSize()) {
            // Glows
            Box(
                Modifier.size(400.dp).offset((-80).dp, (-60).dp)
                    .background(Brush.radialGradient(listOf(Color(0x1500C2FF), Color.Transparent)))
                    .blur(100.dp)
            )
            Box(
                Modifier.size(300.dp).align(Alignment.BottomEnd).offset(60.dp, 80.dp)
                    .background(Brush.radialGradient(listOf(Color(0x127B61FF), Color.Transparent)))
                    .blur(100.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo
                Box(
                    Modifier
                        .size(180.dp)
                        .background(Color(0xFF080810), RoundedCornerShape(22.dp)),
                   //     .border(1.dp, Color(0xFF1E1E2E), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(130.dp)) {

                        val cyan = Color(0xFF00C2FF)
                        val purple = Color(0xFF7B61FF)

                        val w = size.width
                        val h = size.height

                        fun sx(x: Float) = w * (x / 120f)
                        fun sy(y: Float) = h * (y / 120f)

                        // Phone body
                        drawRoundRect(
                            color = Color(0xFF0F0F20),
                            topLeft = Offset(sx(42f), sy(30f)),
                            size = Size(sx(36f), sy(60f)),
                            cornerRadius = CornerRadius(6f)
                        )

                        // Phone outline
                        drawRoundRect(
                            color = cyan,
                            topLeft = Offset(sx(42f), sy(30f)),
                            size = Size(sx(36f), sy(60f)),
                            cornerRadius = CornerRadius(6f),
                            style = Stroke(width = 2.5f)
                        )

                        // Screen
                        drawRoundRect(
                            color = cyan.copy(alpha = 0.15f),
                            topLeft = Offset(sx(48f), sy(36f)),
                            size = Size(sx(24f), sy(38f)),
                            cornerRadius = CornerRadius(2f)
                        )

                        // Bottom bar
                        drawRoundRect(
                            color = cyan.copy(alpha = 0.5f),
                            topLeft = Offset(sx(51f), sy(83f)),
                            size = Size(sx(18f), sy(3f)),
                            cornerRadius = CornerRadius(1.5f)
                        )

                        // Wave 1
                        drawPath(
                            path = Path().apply {
                                moveTo(sx(38f), sy(28f))
                                quadraticBezierTo(sx(60f), sy(10f), sx(82f), sy(28f))
                            },
                            color = cyan.copy(alpha = 0.9f),
                            style = Stroke(width = 3f, cap = StrokeCap.Round)
                        )

                        // Wave 2
                        drawPath(
                            path = Path().apply {
                                moveTo(sx(44f), sy(22f))
                                quadraticBezierTo(sx(60f), sy(8f), sx(76f), sy(22f))
                            },
                            color = purple.copy(alpha = 0.7f),
                            style = Stroke(width = 3f, cap = StrokeCap.Round)
                        )

                        // Wave 3
                        drawPath(
                            path = Path().apply {
                                moveTo(sx(50f), sy(17f))
                                quadraticBezierTo(sx(60f), sy(7f), sx(70f), sy(17f))
                            },
                            color = cyan.copy(alpha = 0.5f),
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                Text(
                    "selfhost",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.5).sp,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Host websites from your phone.",
                    fontSize = 14.sp,
                    color = Color(0xFF555566),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(56.dp))

                // Google Sign-In button
                Surface(
                    onClick = { if (!isLoading) signInWithGoogle() },
                    color = Color(0xFF13131F),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(1.dp, Color(0xFF2A2A3A)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isLoading) {
                            val inf = rememberInfiniteTransition(label = "spin")
                            val angle by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "a")
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .rotate(angle)
                                    .border(2.dp, Color(0xFF00C2FF), CircleShape)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Signing in...", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF888899))
                        } else {
                            // Google "G" icon drawn with text
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .background(Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("G", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF4285F4))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Continue with Google", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    "By continuing you agree to our Terms of Service",
                    fontSize = 11.sp,
                    color = Color(0xFF333344),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    private fun signInWithGoogle() {
        isLoading = true

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(getString(R.string.default_web_client_id))
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val credentialManager = CredentialManager.create(this@SignInActivity)
                val result = credentialManager.getCredential(this@SignInActivity, request)
                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleCredential.idToken
                    val signInResult = FirebaseRepository.signInWithCredential(idToken)
                    if (signInResult.isSuccess) {
                        goToMain()
                    } else {
                        showError("Sign in failed: ${signInResult.exceptionOrNull()?.message}")
                    }
                } else {
                    showError("Unexpected credential type: ${credential.type}")
                }
            } catch (e: GetCredentialException) {
                showError("Google sign in cancelled: ${e.message}")
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
