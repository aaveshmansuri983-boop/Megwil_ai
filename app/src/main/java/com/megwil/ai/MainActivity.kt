package com.megwil.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.megwil.ai.ui.dashboard.OwnerDashboardActivity
import com.megwil.ai.ui.theme.MegwilAITheme

class MainActivity : ComponentActivity() {
    private val ownerEmail = "aaveshmansuri983@gmail.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MegwilAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0F19)
                ) {
                    MainScreen(
                        onGoogleLogin = { startGoogleLogin() },
                        onOpenDashboard = {
                            startActivity(Intent(this, OwnerDashboardActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    private fun startGoogleLogin() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        startActivityForResult(client.signInIntent, 100)
    }

    @Deprecated("Use Activity Result APIs in a future update")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException::class.java)
                if (account?.email.equals(ownerEmail, ignoreCase = true)) {
                    startActivity(Intent(this, OwnerDashboardActivity::class.java))
                }
            } catch (_: ApiException) {
                // Sign-in was cancelled or failed.
            }
        }
    }
}

@Composable
fun MainScreen(onGoogleLogin: () -> Unit, onOpenDashboard: () -> Unit) {
    var queryText by remember { mutableStateOf("") }
    val responseText = "Ready. Say 'Megwil' or type below."

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("MEGWIL AI", color = Color(0xFF00E5FF), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Android 15 Jarvis-Grade Assistant", color = Color.Gray, fontSize = 14.sp)
        }

        Box(
            modifier = Modifier.size(180.dp).background(Color(0xFF1E293B), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(140.dp).background(Color(0xFF00E5FF).copy(alpha = 0.2f), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("MEGWIL", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
            modifier = Modifier.fillMaxWidth().height(120.dp)
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                Text(responseText, color = Color.White)
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                label = { Text("Ask Megwil...", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = onGoogleLogin, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937))) {
                    Text("Google Login")
                }
                Button(onClick = onOpenDashboard, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))) {
                    Text("Owner Dashboard", color = Color.Black)
                }
            }
        }
    }
}
