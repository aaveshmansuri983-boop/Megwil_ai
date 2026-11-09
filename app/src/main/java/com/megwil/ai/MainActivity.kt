package com.megwil.ai

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.megwil.ai.data.GroqApiClient
import com.megwil.ai.data.GroqRequest
import com.megwil.ai.data.Message
import com.megwil.ai.ui.dashboard.OwnerDashboardActivity
import com.megwil.ai.ui.theme.MegwilAITheme
import kotlinx.coroutines.launch
import java.util.Locale
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private val Cyan = Color(0xFF00E5FF)
private val Navy = Color(0xFF070B14)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val ownerEmail = BuildConfig.OWNER_EMAIL
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceCallback: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("megwil_secure_settings", MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        setContent {
            MegwilAITheme {
                MegwilScreen(
                    savedKey = prefs.getString("groq_key", "") ?: "",
                    savedHistory = loadHistory(),
                    onSaveKey = { prefs.edit().putString("groq_key", it.trim()).apply() },
                    onAsk = { question, history, done ->
                        askMegwil(question, history, done)
                    },
                    onHistoryChanged = { saveHistory(it) },
                    onSpeak = { speak(it) },
                    onListen = { callback -> listen(callback) },
                    onGoogleLogin = { startGoogleLogin() },
                    onDashboard = { startActivity(Intent(this, OwnerDashboardActivity::class.java)) }
                )
            }
        }
    }

    private fun loadHistory(): List<Message> {
        return try {
            val raw = prefs.getString("chat_history", null) ?: return emptyList()
            Gson().fromJson(raw, object : TypeToken<List<Message>>() {}.type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun saveHistory(history: List<Message>) {
        prefs.edit().putString("chat_history", Gson().toJson(history.takeLast(100))).apply()
    }

    private fun askMegwil(question: String, history: List<Message>, done: (String) -> Unit) {
        val lower = question.trim().lowercase(Locale.ROOT)
        val prefixes = listOf("chrome par search karo", "chrome me search karo", "search on chrome", "search in chrome", "google par search karo", "google me search karo", "search for", "search karo")
        val prefix = prefixes.firstOrNull { lower.startsWith(it) }
        if (prefix != null) {
            val query = question.trim().substring(prefix.length).trim()
            if (query.isBlank()) { done("Kya search karna hai?"); return }
            val url = "https://www.google.com/search?q=" + Uri.encode(query)
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome")) }
            catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            done("Chrome par search khol diya: " + query)
            return
        }
        val appMap = listOf("whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube", "instagram" to "com.instagram.android", "chrome" to "com.android.chrome", "maps" to "com.google.android.apps.maps", "gmail" to "com.google.android.gm", "camera" to "com.android.camera", "settings" to "com.android.settings")
        if (lower.startsWith("open ") || lower.startsWith("kholo ")) {
            val target = lower.removePrefix("open ").removePrefix("kholo ").trim()
            val app = appMap.firstOrNull { target.contains(it.first) }
            if (app != null) {
                val launch = packageManager.getLaunchIntentForPackage(app.second)
                if (launch != null) { startActivity(launch); done(app.first + " khol diya."); }
                else done(app.first + " phone mein nahi mila.")
                return
            }
        }
        val phone = Regex("(?:\\+?91[- ]?)?[6-9]\\d{9}").find(question)?.value?.replace(Regex("[^0-9+]"), "")
        if (phone != null && listOf("call", "phone lagao", "call karo", "कॉल", "फोन लगाओ").any { lower.contains(it) }) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone)))
            done("Dialer khol diya. Call karne ke liye Call button dabao.")
            return
        }
        if (phone != null && lower.contains("whatsapp") && listOf("message", "msg", "bhejo", "भेजो").any { lower.contains(it) }) {
            val body = question.substringAfter("bhejo", question.substringAfter("भेजो", question.substringAfter("message", question.substringAfter("msg", "")))).trim()
            if (body.isBlank()) { done("Message ka text bhi bolo."); return }
            val digits = phone.filter { it.isDigit() }.let { if (it.length == 10) "91" + it else it }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(body))))
            done("WhatsApp chat mein message draft khol diya. Check karke Send dabao.")
            return
        }
        if (phone != null && lower.contains("whatsapp") && lower.contains("call")) {
            val digits = phone.filter { it.isDigit() }.let { if (it.length == 10) "91" + it else it }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits)))
            done("WhatsApp chat khol di. Call icon dabakar call karo.")
            return
        }
        val key = prefs.getString("groq_key", "").orEmpty()
        if (key.isBlank()) { done("Pehle Settings mein apni Groq API key save karo."); return }
        lifecycleScope.launch {
            try {
                val system = Message("system", "You are MEGWIL, a capable, honest personal AI assistant. Reply naturally in the user's language, especially Hindi/Hinglish. Never claim to perform phone actions unless actually completed.")
                val messages = listOf(system) + history.filter { it.role == "user" || it.role == "assistant" }.takeLast(20)
                val result = GroqApiClient.api.getCompletion("Bearer $key", GroqRequest("llama-3.3-70b-versatile", messages))
                done(result.choices.firstOrNull()?.message?.content ?: "Mujhe response nahi mila.")
            } catch (e: Exception) {
                done("Connection/API error: ${e.localizedMessage ?: "unknown error"}. API key aur internet check karo.")
            }
        }
    }

    private fun speak(text: String) {
        if (tts == null) tts = TextToSpeech(this, this)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "megwil_reply")
    }

    private fun listen(callback: (String) -> Unit) {
        voiceCallback = callback
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 501)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { callback("Is phone par speech recognition available nahi hai."); return }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { callback("Voice input nahi samajh aaya. Dobara try karo.") }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) callback(spoken)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            startListening(intent)
        }
    }

    private fun startGoogleLogin() {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank()) {
            android.widget.Toast.makeText(this, "Google OAuth Client ID workflow secret mein configure nahi hai.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(webClientId)
            .build()
        startActivityForResult(GoogleSignIn.getClient(this, options).signInIntent, 100)
    }

    @Deprecated("Legacy Google Sign-In result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                if (account?.email.equals(ownerEmail, ignoreCase = true)) {
                    startActivity(Intent(this, OwnerDashboardActivity::class.java))
                } else {
                    android.widget.Toast.makeText(this, "Owner account required: $ownerEmail", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: ApiException) {
                android.widget.Toast.makeText(this, "Google sign-in failed: ${e.statusCode}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun MegwilScreen(
    savedKey: String,
    savedHistory: List<Message>,
    onSaveKey: (String) -> Unit,
    onHistoryChanged: (List<Message>) -> Unit,
    onAsk: (String, List<Message>, (String) -> Unit) -> Unit,
    onSpeak: (String) -> Unit,
    onListen: ((String) -> Unit) -> Unit,
    onGoogleLogin: () -> Unit,
    onDashboard: () -> Unit
) {
    var key by remember { mutableStateOf(savedKey) }
    var showSettings by remember { mutableStateOf(savedKey.isBlank()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("Namaste! Main MEGWIL hoon. Groq API key set karke mujhse baat shuru karo.") }
    val history = remember { mutableStateListOf<Message>().apply { addAll(savedHistory) } }

    Column(Modifier.fillMaxSize().background(Navy).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("MEGWIL AI", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text("PERSONAL INTELLIGENCE • ONLINE", color = Color.LightGray, fontSize = 10.sp)
            }
            TextButton(onClick = { showSettings = !showSettings }) { Text("SETTINGS", color = Cyan) }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(164.dp).background(Color(0xFF101C2C), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(104.dp).background(Cyan.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(78.dp).background(Color(0xFF0B2635), CircleShape), contentAlignment = Alignment.Center) {
                        Text("M", color = Cyan, fontSize = 42.sp, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (busy) "THINKING..." else if (listening) "LISTENING..." else "MEGWIL • READY", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (showSettings) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF142033))) {
                Column(Modifier.padding(12.dp)) {
                    Text("AI CONFIGURATION", color = Cyan, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Groq API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSaveKey(key); showSettings = false }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) { Text("SAVE KEY", color = Navy) }
                        TextButton(onClick = onGoogleLogin) { Text("GOOGLE LOGIN", color = Cyan) }
                    }
                    Text("Key device ke private app storage mein save hoti hai. API key kisi ke saath share na karein.", color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("CONVERSATION", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF142033))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("MEGWIL", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(answer, color = Color.White, fontSize = 14.sp)
                        TextButton(onClick = { onSpeak(answer) }) { Text("▶ SPEAK", color = Cyan) }
                    }
                }
            }
            items(history.filter { it.role == "user" }) { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18283B))) {
                    Text("YOU  •  ${msg.content}", Modifier.padding(12.dp), color = Color.White)
                }
            }
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask MEGWIL anything...") }, maxLines = 3
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                listening = true
                onListen { text -> input = text; listening = false }
            }, modifier = Modifier.weight(1f)) { Text("🎙 VOICE") }
            Button(
                onClick = {
                    val q = input.trim()
                    if (q.isNotEmpty() && !busy) {
                        history.add(Message("user", q))
                        onHistoryChanged(history.toList())
                        input = ""; busy = true
                        onAsk(q, history.toList()) { result ->
                            answer = result
                            history.add(Message("assistant", result))
                            onHistoryChanged(history.toList())
                            busy = false
                        }
                    }
                },
                enabled = input.isNotBlank() && !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, disabledContainerColor = Color.DarkGray)
            ) { Text(if (busy) "WAIT..." else "SEND", color = Navy) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { history.clear(); onHistoryChanged(emptyList()); answer = "Conversation cleared." }) { Text("CLEAR CHAT", color = Color.LightGray) }
            TextButton(onClick = onDashboard) { Text("OWNER DASHBOARD", color = Cyan) }
        }
    }
}
