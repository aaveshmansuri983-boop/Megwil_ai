package com.megwil.ui.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.megwil.BuildConfig

private val Cyan = Color(0xFF00E5FF)
private val Navy = Color(0xFF070B14)

class OwnerDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val account = GoogleSignIn.getLastSignedInAccount(this)
        val ownerEmail = BuildConfig.OWNER_EMAIL
        if (account?.email.isNullOrBlank() || !account?.email.equals(ownerEmail, ignoreCase = true)) {
            Toast.makeText(this, "Owner Google account se login zaroori hai.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val prefs = getSharedPreferences("megwil_secure_settings", MODE_PRIVATE)
        val groqReady = !prefs.getString("groq_key", "").isNullOrBlank()
        val micReady = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val contactsReady = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val callReady = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val accessibilityReady = isMegwilAccessibilityEnabled()
        val notificationReady = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Navy) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("MEGWIL • OWNER CONSOLE", color = Cyan, fontSize = 23.sp)
                        Text("LOCAL OWNER CHECK ONLY", color = Color(0xFFFFC266), fontSize = 11.sp)
                        Text(ownerEmail, color = Color.White)
                        Divider(color = Color.DarkGray)
                        Text("LIVE DEVICE STATUS", color = Cyan)
                        StatusLine("Groq AI key", groqReady, if (groqReady) "Saved on this device" else "Not configured")
                        StatusLine("Microphone", micReady, if (micReady) "Permission granted" else "Permission missing")
                        StatusLine("Contacts", contactsReady, if (contactsReady) "Permission granted" else "Permission missing")
                        StatusLine("Phone calls", callReady, if (callReady) "Permission granted" else "Permission missing")
                        StatusLine("WhatsApp accessibility", accessibilityReady, if (accessibilityReady) "Service enabled" else "Needs manual enable")
                        StatusLine("Notifications", notificationReady, if (notificationReady) "Permission granted" else "Permission missing")
                        Divider(color = Color.DarkGray)
                        Text("OWNER ACTIONS", color = Cyan)
                        ActionButton("OPEN MEGWIL SETTINGS") {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                        }
                        ActionButton("OPEN ACCESSIBILITY SETTINGS") {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        ActionButton("OPEN APP PERMISSIONS") {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                        }
                        ActionButton("CLEAR SAVED CHAT HISTORY") {
                            prefs.edit().remove("chat_history").apply()
                            Toast.makeText(this@OwnerDashboardActivity, "Saved chat history cleared.", Toast.LENGTH_SHORT).show()
                        }
                        Text("SECURITY NOTE", color = Cyan, fontSize = 12.sp)
                        Text("SECURITY LIMIT: Google email matching happens only inside this app. It is not server-side identity verification and must not protect private cloud data or privileged remote actions. A trusted backend with verified Google ID tokens and owner authorization is still required; no backend credentials or server are configured in this project.", color = Color.LightGray, fontSize = 12.sp)
                        Text("Android controls microphone access. MEGWIL cannot grant permanent background microphone permission or bypass Android restrictions.", color = Color.LightGray, fontSize = 12.sp)
                        Button(onClick = { finish() }) { Text("BACK TO MEGWIL") }
                    }
                }
            }
        }
    }

    private fun isMegwilAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.contains("$packageName/.service.MegwilAccessibilityService", true) ||
            it.contains("$packageName/com.megwil.service.MegwilAccessibilityService", true) }
    }
}

@Composable
private fun StatusLine(title: String, ready: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text(detail, color = Color.LightGray, fontSize = 11.sp)
        }
        Text(if (ready) "READY" else "SETUP", color = if (ready) Color(0xFF62E6A7) else Color(0xFFFFC266), fontSize = 11.sp)
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Cyan)
    }
}
