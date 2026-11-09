package com.megwil.ai

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.media.AudioManager
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
                    onDashboard = {
                        val signedIn = GoogleSignIn.getLastSignedInAccount(this)?.email
                        if (signedIn.equals(ownerEmail, ignoreCase = true)) startActivity(Intent(this, OwnerDashboardActivity::class.java)) else startGoogleLogin()
                    },
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
        if (requestCode == 501) {
            val callback = voiceCallback
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && callback != null) {
                listen(callback)
            } else {
                voiceCallback = null
                callback?.invoke("Microphone permission nahi mili. Settings mein allow karke dobara try karo.")
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

        // Save only facts the user explicitly asks MEGWIL to remember.
        if (lower.startsWith("remember ") || lower.startsWith("yaad rakhna ") || lower.startsWith("याद रखना ")) {
            val fact = Regex("(?i)^(remember|yaad\\s+rakhna|याद\\s+रखना)\\s+").replaceFirst(question.trim(), "").trim()
            if (fact.isBlank()) { done("Kya yaad rakhna hai?"); return }
            val facts = prefs.getStringSet("long_term_memory", emptySet())?.toMutableSet() ?: mutableSetOf()
            facts.add(fact)
            prefs.edit().putStringSet("long_term_memory", facts).apply()
            done("Theek hai, maine ye baat is phone mein yaad rakh li: " + fact)
            return
        }
        if (lower.contains("what do you remember") || lower.contains("tumhe kya yaad") || lower.contains("meri memory dikhao") || lower.contains("yaad kya hai")) {
            val facts = prefs.getStringSet("long_term_memory", emptySet()).orEmpty()
            done(if (facts.isEmpty()) "Abhi long-term memory khaali hai. 'Yaad rakhna ...' bolkar koi baat save kara sakte ho." else "Meri saved memory:\n" + facts.joinToString("\n") { "• " + it })
            return
        }
        if (lower == "forget memory" || lower == "clear memory" || lower == "meri memory bhool jao" || lower == "memory delete karo") {
            prefs.edit().remove("long_term_memory").apply()
            done("Is device par save ki hui MEGWIL memory clear kar di.")
            return
        }

        // Android permits these controls through public intents/APIs; OS restrictions remain in force.
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (listOf("volume up", "awaaz badhao", "volume badhao").any { lower.contains(it) }) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            done("Media volume badha diya."); return
        }
        if (listOf("volume down", "awaaz kam", "volume kam karo").any { lower.contains(it) }) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            done("Media volume kam kar diya."); return
        }
        val settingsIntent = when {
            lower.contains("wifi settings") || lower.contains("wi-fi settings") || lower.contains("wifi setting") -> Intent(Settings.ACTION_WIFI_SETTINGS)
            lower.contains("bluetooth settings") || lower.contains("bluetooth setting") -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            lower.contains("display settings") || lower.contains("brightness settings") -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            lower.contains("sound settings") || lower.contains("audio settings") -> Intent(Settings.ACTION_SOUND_SETTINGS)
            lower == "phone settings kholo" || lower == "open phone settings" || lower == "settings kholo" || lower == "open settings" -> Intent(Settings.ACTION_SETTINGS)
            else -> null
        }
        if (settingsIntent != null) {
            try { startActivity(settingsIntent); done("Android settings screen khol diya.") }
            catch (_: Exception) { done("Ye settings screen is phone par available nahi hai.") }
            return
        }
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
        if (lower.contains("timer") || lower.contains("टाइमर")) {
            val amount = Regex("(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|घंटे?|मिनट|सेकंड)", RegexOption.IGNORE_CASE).find(lower)
            if (amount == null) { done("Kitne minute ya second ka timer lagana hai?"); return }
            val value = amount.groupValues[1].toIntOrNull() ?: 0
            val unit = amount.groupValues[2]
            val seconds = when {
                unit.startsWith("hour") || unit.startsWith("घंटा") -> value * 3600
                unit.startsWith("min") || unit.startsWith("मिनट") -> value * 60
                else -> value
            }
            if (seconds <= 0) { done("Timer duration sahi nahi hai."); return }
            try {
                startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER).putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "MEGWIL timer").putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false))
                done("Phone ka timer " + value + " " + unit + " ke liye khol diya.")
            } catch (_: Exception) { done("Is phone par timer app available nahi hai.") }
            return
        }
        if (lower.contains("alarm") || lower.contains("अलार्म")) {
            val time = Regex("(?:at\\s*)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(lower)
            if (time == null) { done("Alarm ka time batao, jaise 7:30 AM."); return }
            var hour = time.groupValues[1].toIntOrNull() ?: -1
            val minute = time.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: 0
            val meridiem = time.groupValues[3].lowercase(Locale.ROOT)
            if (meridiem == "pm" && hour in 1..11) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            if (hour !in 0..23 || minute !in 0..59) { done("Alarm ka time valid nahi hai."); return }
            try {
                startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "MEGWIL alarm").putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false))
                done("Alarm set karne ka screen khol diya. Save confirm kar dena.")
            } catch (_: Exception) { done("Is phone par alarm app available nahi hai.") }
            return
        }
        if (lower.startsWith("navigate to ") || lower.startsWith("directions to ") || lower.startsWith("maps par ") || lower.startsWith("map kholo ")) {
            val destination = question.substringAfter("to ", question.substringAfter("par ", question.substringAfter("kholo ", ""))).trim()
            if (destination.isBlank()) { done("Kahan jana hai?"); return }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination))))
            done("Maps navigation khol di: " + destination)
            return
        }
        if (lower.startsWith("weather ") || lower.startsWith("mausam ") || lower.contains("weather in ") || lower.contains("mausam batao")) {
            val place = question.substringAfter("weather in ", question.substringAfter("weather ", question.substringAfter("mausam ", ""))).trim().removeSuffix("batao").trim()
            val query = if (place.isBlank()) "weather today" else "weather in " + place
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
            done("Latest mausam ki jankari browser mein khol di: " + query)
            return
        }
        val appMap = listOf("whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube", "instagram" to "com.instagram.android", "chrome" to "com.android.chrome", "maps" to "com.google.android.apps.maps", "gmail" to "com.google.android.gm", "camera" to "com.android.camera", "settings" to "com.android.settings")
        if (lower.startsWith("open ") || lower.startsWith("kholo ")) {
            val target = lower.removePrefix("open ").removePrefix("kholo ").trim()
            val app = appMap.firstOrNull { target.contains(it.first) }
            val knownLaunch = app?.let { packageManager.getLaunchIntentForPackage(it.second) }
            if (knownLaunch != null) {
                startActivity(knownLaunch)
                done(app!!.first + " khol diya.")
                return
            }
            val installedMatch = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .firstOrNull { info ->
                    val label = packageManager.getApplicationLabel(info).toString().lowercase(Locale.ROOT)
                    label == target || (target.length >= 3 && label.contains(target))
                }
            val anyLaunch = installedMatch?.let { packageManager.getLaunchIntentForPackage(it.packageName) }
            if (anyLaunch != null) {
                startActivity(anyLaunch)
                done(packageManager.getApplicationLabel(installedMatch!!).toString() + " khol diya.")
            } else {
                done("Is naam ka launchable app nahi mila: " + target)
            }
            return
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
                val savedMemory = prefs.getStringSet("long_term_memory", emptySet()).orEmpty().toList()
                val memoryContext = if (savedMemory.isEmpty()) "" else "\nUser-approved long-term memory (local device): " + savedMemory.joinToString("; ")
                val system = Message("system", "You are MEGWIL, a capable, honest personal AI assistant. Reply naturally in the user's language, especially Hindi/Hinglish. Use the user's saved memory when relevant. Never claim to perform phone actions unless actually completed." + memoryContext)
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
                Text("PERSONAL AI • INTERNET REQUIRED", color = Color.LightGray, fontSize = 10.sp)
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
                    Text("PHONE CONTROL", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("Apps kholne, volume badalne/ghatane aur Wi-Fi, Bluetooth, Display ya Sound settings kholne ke liye MEGWIL ko bolo. Calls aur WhatsApp messages hamesha YES/NO confirmation ke baad hi chalenge. Android security restrictions bypass nahi ki ja sakti.", color = Color.LightGray, fontSize = 11.sp)
                    Text("LONG-TERM MEMORY", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("Kisi baat ko save karne ke liye 'Yaad rakhna ...' bolo. 'Meri memory dikhao' se dekho aur 'Memory delete karo' se clear karo. Memory isi phone par local save hoti hai.", color = Color.LightGray, fontSize = 11.sp)
                    Text("WHATSAPP ACCESSIBILITY", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("WhatsApp actions ke liye Android Accessibility Settings mein MEGWIL service manually enable karein.", color = Color.LightGray, fontSize = 11.sp)
                    TextButton(onClick = onAccessibilitySettings) { Text("ENABLE ACCESSIBILITY CONTROL", color = Cyan) }
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
