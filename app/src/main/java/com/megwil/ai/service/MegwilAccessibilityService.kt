package com.megwil.ai.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MegwilAccessibilityService : AccessibilityService() {
    private var tries = 0
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.whatsapp") return
        val p = getSharedPreferences("megwil_actions", Context.MODE_PRIVATE)
        if (p.getBoolean("pending_done", true)) return
        if (++tries > 30) { p.edit().putBoolean("pending_done", true).putString("action_result", "Could not find WhatsApp control. Finish manually.").apply(); return }
        val root = rootInActiveWindow ?: return
        when (p.getString("pending_type", "")) {
            "wa_message" -> {
                val msg = p.getString("pending_message", "") ?: return
                val field = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry").firstOrNull() ?: findEditable(root)
                if (field != null) {
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg) }
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    val send = findLabel(root, listOf("Send", "Send message", "भेजें"))
                    if (send != null && send.isClickable && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) p.edit().putBoolean("pending_done", true).putString("action_result", "WhatsApp send button tapped").apply()
                }
            }
            "wa_call" -> {
                val call = findLabel(root, listOf("Voice call", "Audio call", "Call", "वॉइस कॉल"))
                if (call != null && call.isClickable && call.performAction(AccessibilityNodeInfo.ACTION_CLICK)) p.edit().putBoolean("pending_done", true).putString("action_result", "WhatsApp call button tapped").apply()
            }
        }
    }
    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i) ?: continue)?.let { return it }
        return null
    }
    private fun findLabel(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) root.findAccessibilityNodeInfosByText(label).firstOrNull()?.let { return it }
        return null
    }
    override fun onInterrupt() { }
}
