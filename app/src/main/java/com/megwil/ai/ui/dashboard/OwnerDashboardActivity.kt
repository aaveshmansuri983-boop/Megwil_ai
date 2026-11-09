package com.megwil.ai.ui.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class OwnerDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF070B14)) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("MEGWIL • OWNER CONSOLE", color = Color(0xFF00E5FF), fontSize = 23.sp)
                    Spacer(Modifier.height(20.dp))
                    Text("OWNER ID", color = Color.Gray, fontSize = 11.sp)
                    Text("aaveshmansuri983@gmail.com", color = Color.White)
                    Spacer(Modifier.height(20.dp))
                    Text("SYSTEM MODULES", color = Color(0xFF00E5FF))
                    listOf("Groq AI conversation — available after API key setup", "Google account sign-in — email identity check", "Speech recognition — while app is active", "Text-to-speech — spoken assistant replies", "Android app actions — permission and user confirmation required").forEach {
                        Text("• $it", color = Color.White, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Microphone background access is controlled by Android. This app cannot grant an always-allow permission or bypass system restrictions.", color = Color.LightGray, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { finish() }) { Text("BACK TO MEGWIL") }
                }
            }
        }
    }
}
