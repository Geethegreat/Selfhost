package com.example.selfhost

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
                        .size(72.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF00C2FF), Color(0xFF7B61FF))),
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("S", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White)
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
                    shape = RoundedCornerShape(14.dp),
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

                if (credential is GoogleIdTokenCredential) {
                    val idToken = credential.idToken
                    val signInResult = FirebaseRepository.signInWithCredential(idToken)
                    if (signInResult.isSuccess) {
                        goToMain()
                    } else {
                        showError("Sign in failed. Please try again.")
                    }
                } else {
                    showError("Unexpected credential type.")
                }
            } catch (e: GetCredentialException) {
                showError("Google sign in cancelled.")
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
