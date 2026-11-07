package com.k2fsa.sherpa.onnx.vad.asr

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Vklada text do AKTIVNEJ appky (do zameraneho policka).
 * Zapnutie: Nastavenia -> Zjednodusenie ovladania -> Diktat -> ZAPNUT.
 */
class SpeakAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i("sherpa-onnx", "Accessibility pripojena")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() { }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Vlozi text do zameraneho editovatelneho policka aktivnej appky. */
    fun pasteText(text: String): Boolean {
        try {
            val root = rootInActiveWindow ?: return false
            val node = findEditable(root) ?: return false
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i("sherpa-onnx", "pasteText ok=$ok")
            return ok
        } catch (e: Exception) {
            Log.e("sherpa-onnx", "pasteText err: $e")
            return false
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isEnabled) return node
        val focus = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) return focus
        for (i in 0 until node.childCount) {
            val r = findEditable(node.getChild(i))
            if (r != null) return r
        }
        return null
    }

    companion object {
        @Volatile var instance: SpeakAccessibilityService? = null
    }
}