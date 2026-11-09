package com.k2fsa.sherpa.onnx.vad.asr

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug log — zapisuje do suboru debug.log v filesDir.
 * Vzdy citatelny cez file manager, nie cez logcat.
 * Zapne/vypne sa v nastaveniach (debugMode).
 */
object DebugLog {
    private var file: File? = null
    private var enabled = false

    fun init(dir: File, debugOn: Boolean) {
        file = File(dir, "debug.log")
        enabled = debugOn
    }

    fun setEnabled(on: Boolean) {
        enabled = on
    }

    fun log(tag: String, msg: String) {
        if (!enabled) return
        try {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val f = file ?: return
            FileWriter(f, true).use { it.write("$ts [$tag] $msg\n") }
            Log.d("SpeakO-dbg", "[$tag] $msg")
        } catch (e: Exception) { }
    }

    fun read(): String {
        try {
            val f = file ?: return "debug.log nenajdeny"
            if (!f.exists()) return "debug.log neexistuje"
            return f.readText()
        } catch (e: Exception) { return "err: $e" }
    }

    fun clear() {
        try { file?.writeText("") } catch (e: Exception) { }
    }
}