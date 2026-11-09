package com.megwil.ai.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class MegwilAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle explicitly enabled accessibility features here.
    }

    override fun onInterrupt() {
        // Service interrupted.
    }
}
