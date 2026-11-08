package com.k2fsa.sherpa.onnx.vad.asr

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Vkladanie textu do AKTIVNEJ appky - robustne, s viacerymi strategiami.
 *
 *  1. ACTION_SET_TEXT so zachovanim existujuceho textu (vlozi na kurzor)
 *  2. clipboard + ACTION_PASTE
 *  3. diagnostika co appka vidi
 */
class SpeakAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility pripojena")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Vlozi text do zameraneho policka. Zachova existujuci obsah. */
    fun pasteText(text: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "paste: rootInActiveWindow = null")
                return false
            }
            val node = findBestEditable(root)
            if (node == null) {
                Log.w(TAG, "paste: ziadne editovatelne pole")
                return false
            }
            if (tryAppendSetText(node, text)) return true
            if (tryPasteAction(node, text)) return true
            Log.w(TAG, "paste: zlyhali vsetky strategie")
            false
        } catch (e: Exception) {
            Log.e(TAG, "pasteText err: $e")
            false
        }
    }

    /** Zachova existujuci text a vlozi novy na poziciu kurzora / na koniec. */
    private fun tryAppendSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val existing = node.text?.toString() ?: ""
            var selStart = -1
            var selEnd = -1
            try {
                selStart = node.textSelectionStart
                selEnd = node.textSelectionEnd
            } catch (e: Exception) { }

            val result: String = if (existing.isEmpty()) {
                text
            } else if (selStart in 0..existing.length && selEnd in 0..existing.length) {
                existing.substring(0, minOf(selStart, selEnd)) + text +
                    existing.substring(maxOf(selStart, selEnd))
            } else {
                existing + text
            }

            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "paste SET_TEXT ok=$ok existing=${existing.length}")
            if (ok) {
                // kurzor na koniec vlozeneho textu
                try {
                    val base = if (existing.isEmpty()) 0
                               else if (selStart in 0..existing.length) minOf(selStart, selEnd)
                               else existing.length
                    val pos = base + text.length
                    val cargs = Bundle()
                    cargs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                    cargs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cargs)
                } catch (e: Exception) { }
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "appendSetText err: $e")
            false
        }
    }

    /** clipboard + ACTION_PASTE (vlozi na kurzor). */
    private fun tryPasteAction(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("speako", text))
            Thread.sleep(80)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "paste ACTION_PASTE ok=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "pasteAction err: $e")
            false
        }
    }

    /**
     * Najde najlepsi editovatelny node.
     * Priorita: focusnuty input > enabled+visible > enabled > prvy.
     */
    private fun findBestEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focus != null && focus.isEditable && focus.isEnabled) return focus
        } catch (e: Exception) { }

        val all = ArrayList<AccessibilityNodeInfo>()
        collectEditable(root, all, 0)
        if (all.isEmpty()) return null
        return all.firstOrNull { it.isEnabled && it.isVisibleToUser }
            ?: all.firstOrNull { it.isEnabled }
            ?: all.first()
    }

    private fun collectEditable(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 25) return
        try {
            if (node.isEditable) out.add(node)
            for (i in 0 until node.childCount) collectEditable(node.getChild(i), out, depth + 1)
        } catch (e: Exception) { }
    }

    /** Diagnostika - co appka v aktívnom okne vidi. */
    fun describeActiveWindow(): String {
        val sb = StringBuilder()
        try {
            val root = rootInActiveWindow ?: return "rootInActiveWindow = null (ziadna aktivna appka?)"
            sb.append("App: ").append(root.packageName).append("\n")
            val all = ArrayList<AccessibilityNodeInfo>()
            collectEditable(root, all, 0)
            sb.append("Editovatelnych poli: ").append(all.size).append("\n")
            all.forEachIndexed { i, n ->
                sb.append("[").append(i).append("] enabled=").append(n.isEnabled)
                    .append(" visible=").append(n.isVisibleToUser)
                    .append(" text=").append(n.text?.toString()?.take(25))
                    .append("\n")
            }
        } catch (e: Exception) { sb.append("err: ").append(e) }
        return sb.toString()
    }

    companion object {
        private const val TAG = "sherpa-onnx"
        @Volatile var instance: SpeakAccessibilityService? = null
    }
}