package com.jarvis.android.host

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Android-only, permission-aware action bridge for the JARVIS voice runtime. */
object AndroidActionRouter {
    @Volatile private var host: Activity? = null
    @JvmStatic fun install(activity: Activity) { host = activity }
    @JvmStatic fun clear() { host = null }

    @JvmStatic
    fun execute(action: String, target: String?, value: String?): String {
        val activity = host ?: return failure("Android host is not initialized")
        if (Looper.myLooper() == Looper.getMainLooper()) return safely(activity, action, target, value)
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try { result.set(safely(activity, action, target, value)) }
            finally { latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return failure("Android action timed out")
        return result.get() ?: failure("Android action returned no result")
    }

    private fun safely(activity: Activity, action: String, target: String?, value: String?): String {
        return try {
            when (action.lowercase(Locale.ROOT)) {
                "open_app" -> openApp(activity, target)
                "dial" -> dial(activity, target)
                "sms" -> composeSms(activity, target, value)
                "whatsapp" -> composeWhatsApp(activity, target, value)
                "flashlight" -> setFlashlight(activity, value)
                "volume" -> setVolume(activity, value)
                "settings" -> openSettings(activity, target)
                else -> failure("Unsupported Android action")
            }
        } catch (error: Throwable) {
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun openApp(activity: Activity, rawTarget: String?): String {
        val target = rawTarget.orEmpty().trim().lowercase(Locale.ROOT)
        if (target == "camera") return launch(activity, Intent(MediaStore.ACTION_IMAGE_CAPTURE), "Camera opened")
        if (target == "settings") return openSettings(activity, "settings")
        val packages = mapOf(
            "whatsapp" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
        )
        val packageName = packages[target] ?: target
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
            ?: return failure("App is not installed or not visible: " + target)
        return launch(activity, intent, "Opened " + target)
    }

    private fun dial(activity: Activity, rawNumber: String?): String {
        val number = rawNumber.orEmpty().filter { it.isDigit() || it == '+' }
        if (number.count { it.isDigit() } < 6) return failure("A valid phone number is required")
        return launch(activity, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)), "Dialer opened; review and tap Call")
    }

    private fun composeSms(activity: Activity, rawNumber: String?, body: String?): String {
        val number = rawNumber.orEmpty().filter { it.isDigit() || it == '+' }
        if (number.count { it.isDigit() } < 6) return failure("A valid phone number is required")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number)).putExtra("sms_body", body.orEmpty())
        return launch(activity, intent, "Message composer opened; review and tap Send")
    }

    private fun composeWhatsApp(activity: Activity, rawNumber: String?, body: String?): String {
        val number = rawNumber.orEmpty().filter { it.isDigit() }
        val message = body.orEmpty()
        if (number.isNotEmpty()) {
            val encoded = URLEncoder.encode(message, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + number + "?text=" + encoded)).setPackage("com.whatsapp")
            if (intent.resolveActivity(activity.packageManager) == null) intent.setPackage(null)
            return launch(activity, intent, "WhatsApp composer opened; review and tap Send")
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage("com.whatsapp")
        }
        return launch(activity, share, "WhatsApp share opened; review and tap Send")
    }

    private fun setFlashlight(activity: Activity, rawValue: String?): String {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("ok", false).put("permission_required", true)
                .put("message", "Camera permission is required for flashlight. Grant it in the JARVIS dashboard and repeat the command.")
                .toString()
        }
        val enabled = rawValue.equals("on", ignoreCase = true)
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return failure("This device does not expose a camera flash")
        manager.setTorchMode(cameraId, enabled)
        return success(if (enabled) "Flashlight turned on" else "Flashlight turned off")
    }

    private fun setVolume(activity: Activity, rawValue: String?): String {
        val percent = rawValue?.toIntOrNull()?.coerceIn(0, 100) ?: return failure("Volume must be between 0 and 100")
        val manager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (maximum * percent / 100.0).roundToInt()
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
        return success("Media volume set to " + percent + " percent")
    }

    private fun openSettings(activity: Activity, rawTarget: String?): String {
        val action = when (rawTarget.orEmpty().lowercase(Locale.ROOT)) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return launch(activity, Intent(action), "Android settings opened")
    }

    private fun launch(activity: Activity, intent: Intent, message: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return success(message)
    }

    private fun success(message: String): String = JSONObject().put("ok", true).put("message", message).toString()
    private fun failure(message: String): String = JSONObject().put("ok", false).put("message", message).toString()
}
