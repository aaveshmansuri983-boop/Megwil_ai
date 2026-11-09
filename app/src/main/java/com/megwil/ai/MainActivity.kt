package com.megwil.ai

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Settings
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
    private var pendingContactCommand: String? = null
    private var pendingContactCallback: ((String) -> Unit)? = null

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
                    onAsk = { question, history, done -> askMegwil(question, history, done) },
                    onConfirmAction = { payload -> runConfirmedAction(payload) },
                    onHistoryChanged = { saveHistory(it) },
                    onSpeak = { speak(it) },
                    onListen = { callback -> listen(callback) },
                    onGoogleLogin = { startGoogleLogin() },
                    onDashboard = { startActivity(Intent(this, OwnerDashboardActivity::class.java)) },
                    onAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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

    private fun findContactNumbers(name: String): List<String> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val matches = linkedSetOf<String>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cursor ->
            val ni = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pi = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(ni) ?: continue
                if (displayName.equals(name, true)) {
                    val number = cursor.getString(pi)?.trim().orEmpty()
                    if (number.isNotBlank()) matches.add(number)
                }
            }
        }
        return matches.toList()
    }

    private fun runConfirmedAction(payload: String) {
        val parts = payload.split("|", limit = 5)
        if (parts.size < 4) return
        val kind = parts[1]
        val number = parts[3].filter { it.isDigit() }
        val body = parts.getOrElse(4) { "" }
        if (kind == "phone_call") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putString("pending_call", number).apply()
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 503)
                return
            }
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        } else {
            val international = if (number.length == 10) "91" + number else number
            getSharedPreferences("megwil_actions", MODE_PRIVATE).edit()
                .putString("pending_type", kind).putString("pending_number", international)
                .putString("pending_contact", parts.getOrElse(2) { "" })
                .putString("pending_message", body).putBoolean("pending_done", false)
                .putString("action_result", "Waiting for the matching WhatsApp chat").apply()
            val link = if (kind == "wa_message") "https://wa.me/" + international + "?text=" + Uri.encode(body) else "https://wa.me/" + international
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 503 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val number = prefs.getString("pending_call", null)
            if (!number.isNullOrBlank()) {
                prefs.edit().remove("pending_call").apply()
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number)))
            }
        }
        if (requestCode == 502) {
            val command = pendingContactCommand
            val callback = pendingContactCallback
            pendingContactCommand = null
            pendingContactCallback = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && !command.isNullOrBlank() && callback != null) {
                askMegwil(command, loadHistory(), callback)
            } else if (callback != null) {
                callback("Contacts permission nahi mili. Android Settings mein permission allow karke command dobara bolo.")
            }
        }
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
        val cmd = Regex("^(.+?) ko (whatsapp(?: par)? )?(call(?: karo)?|message(?: bhejo)?|msg(?: bhejo)?|phone lagao)[: ]*(.*)$", RegexOption.IGNORE_CASE).find(question.trim())
        if (cmd != null) {
            val who = cmd.groupValues[1].trim()
            val wa = cmd.groupValues[2].isNotBlank() || lower.contains("whatsapp")
            val verb = cmd.groupValues[3].lowercase(Locale.ROOT)
            val body = cmd.groupValues[4].trim().removePrefix(":").trim()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                pendingContactCommand = question
                pendingContactCallback = done
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 502)
                done("Contacts permission maangi gayi hai. Allow karte hi command dobara process hogi.")
                return
            }
            val numbers = findContactNumbers(who)
            if (numbers.isEmpty()) { done("Is exact naam ka contact nahi mila: " + who); return }
            if (numbers.size > 1) {
                done("Is naam ke multiple numbers mile: " + numbers.joinToString(" / ") + ". Command mein contact ka poora naam ya number specify karo.")
                return
            }
            val number = numbers.first()
            val kind = if (verb.contains("call") || verb.contains("phone")) { if (wa) "wa_call" else "phone_call" } else "wa_message"
            if (kind == "wa_message" && body.isBlank()) { done("Message ka text bhi bolo."); return }
            done("CONFIRM_ACTION|" + kind + "|" + who + "|" + number + "|" + body)
            return
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
    onConfirmAction: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onListen: ((String) -> Unit) -> Unit,
    onGoogleLogin: () -> Unit,
    onDashboard: () -> Unit,
    onAccessibilitySettings: () -> Unit
) {
    var key by remember { mutableStateOf(savedKey) }
    var showSettings by remember { mutableStateOf(savedKey.isBlank()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("Namaste! Main MEGWIL hoon. Groq API key set karke mujhse baat shuru karo.") }
    var pendingAction by remember { mutableStateOf("") }
    if (pendingAction.isNotBlank()) {
        val parts = pendingAction.split("|", limit = 5)
        val who = parts.getOrElse(2) { "contact" }
        val kind = parts.getOrElse(1) { "action" }.replace("_", " ")
        val detail = parts.getOrElse(4) { "" }
        AlertDialog(onDismissRequest = { pendingAction = "" },
            title = { Text("MEGWIL confirmation") },
            text = { Text("Confirm " + kind + " for " + who + "? " + detail) },
            confirmButton = { TextButton(onClick = { onConfirmAction(pendingAction); pendingAction = "" }) { Text("YES") } },
            dismissButton = { TextButton(onClick = { pendingAction = "" }) { Text("NO") } })
    }
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
        Box(Modifier.fillMaxWidth().height(224.dp).background(Color(0xFF050D19), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RobotReactor(mode = if (listening) "LISTENING" else if (busy) "SPEAKING" else "READY")
                Text(if (busy) "MEGWIL IS THINKING..." else if (listening) "I'M LISTENING..." else "MEGWIL • READY", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    Divider(color = Color.DarkGray)
                    Text("WHATSAPP CONTROL", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("WhatsApp actions ke liye Android Accessibility Settings mein MEGWIL service manually enable karein. Android confirmation aur permissions bypass nahi kiye ja sakte.", color = Color.LightGray, fontSize = 11.sp)
                    TextButton(onClick = onAccessibilitySettings) { Text("ENABLE WHATSAPP CONTROL", color = Cyan) }
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
            items(history) { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = if (msg.role == "user") Color(0xFF18283B) else Color(0xFF142033))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (msg.role == "user") "YOU" else "MEGWIL", color = if (msg.role == "user") Color.LightGray else Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(msg.content, color = Color.White)
                    }
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
                            if (result.startsWith("CONFIRM_ACTION|")) {
                                pendingAction = result
                                answer = "Action confirmation screen khol di hai."
                                onSpeak(answer)
                            } else {
                                answer = result
                                onSpeak(result)
                            }
                            history.add(Message("assistant", answer))
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
