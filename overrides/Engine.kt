package com.k2fsa.sherpa.onnx.vad.asr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ENGINE PRÍKAZOV - rozšíriteľný za behu, BEZ BUILDU.
 *
 * commands.txt  ->  fraza | akcia
 * buttons.txt   ->  poradie | popis | akcia
 *
 * AKCIE (skladajú sa z primitív, môžeš ich reťaziť cez "+"):
 *   send_new  send_all  del_veta  del_odstavec  clear  paragraph  undo
 *   copy  save  save_public  share  notify  paste_app  records  translate
 *   stop  start  trim_audio  transcribe_file  noop
 *   beep:N       - N x pipnutie
 *   append:TEXT  - pripoji TEXT na koniec
 *   set:TEXT     - nahradi cely text
 *   up:TEXT      - prevedie na VELKE
 *   low:TEXT     - prevedie na male
 *   time         - vlozi aktualny cas
 *   date         - vlozi datum
 *   newline      - novy riadok
 *   ifempty:TEXT - vlozi TEXT len ak je pole prazdne
 *
 * RETAZENIE:  "clear+append:Ahoj+newline+append:Svet"
 */
object Engine {

    /** Vykona akciu. Vrati true ak akcia existuje. */
    fun run(action: String, ctx: EngineHost): Boolean {
        val parts = splitChain(action)
        var any = false
        for (p in parts) { if (runOne(p.trim(), ctx)) any = true }
        return any
    }

    /** Rozdeli retazec podla '+' ale nie vnutri append:TEXT (TEXT moze mat +). */
    private fun splitChain(a: String): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        var i = 0
        while (i < a.length) {
            val ch = a[i]
            if (ch == '+' && !(cur.startsWith("append:") || cur.startsWith("set:") || cur.startsWith("ifempty:"))) {
                out.add(cur.toString()); cur = StringBuilder()
            } else cur.append(ch)
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun runOne(a: String, ctx: EngineHost): Boolean {
        val text = ctx.getText()
        return when {
            a == "send_new" -> { ctx.action("send_new"); true }
            a == "send_all" -> { ctx.action("send_all"); true }
            a == "del_veta" -> { ctx.action("del_veta"); true }
            a == "del_odstavec" -> { ctx.action("del_odstavec"); true }
            a == "clear" -> { ctx.action("clear"); true }
            a == "paragraph" || a == "newline" -> { ctx.setText(if (text.isBlank()) text else text + "\n"); true }
            a == "undo" -> { ctx.action("undo"); true }
            a == "copy" -> { ctx.action("copy"); true }
            a == "save" || a == "save_public" -> { ctx.action("save"); true }
            a == "share" -> { ctx.action("share"); true }
            a == "notify" -> { ctx.action("notify"); true }
            a == "paste_app" -> { ctx.action("paste_app"); true }
            a == "paste_diag" -> { ctx.action("paste_diag"); true }
            a == "records" -> { ctx.action("records"); true }
            a == "translate" -> { ctx.action("translate"); true }
            a == "stop" -> { ctx.action("stop"); true }
            a == "start" -> { ctx.action("start"); true }
            a == "trim_audio" -> { ctx.action("trim_audio"); true }
            a == "transcribe_file" -> { ctx.action("transcribe_file"); true }
            a == "noop" -> true
            a == "time" -> { ctx.setText(text + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())); true }
            a == "date" -> { ctx.setText(text + SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())); true }
            a.startsWith("beep:") -> { ctx.beep(a.substringAfter("beep:").toIntOrNull() ?: 1); true }
            a.startsWith("append:") -> { ctx.setText(text + a.substringAfter("append:")); true }
            a.startsWith("set:") -> { ctx.setText(a.substringAfter("set:")); true }
            a.startsWith("up:") -> { ctx.setText(a.substringAfter("up:").uppercase()); true }
            a.startsWith("low:") -> { ctx.setText(a.substringAfter("low:").lowercase()); true }
            a.startsWith("ifempty:") -> { if (text.isBlank()) ctx.setText(a.substringAfter("ifempty:")); true }
            else -> false
        }
    }
}

/** Co engine potrebuje od aktivity. */
interface EngineHost {
    fun getText(): String
    fun setText(s: String)
    fun action(name: String)
    fun beep(n: Int)
}