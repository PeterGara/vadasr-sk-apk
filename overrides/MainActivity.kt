package com.k2fsa.sherpa.onnx.vad.asr

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.R
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getOfflineModelConfig
import com.k2fsa.sherpa.onnx.getVadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

private const val TAG = "sherpa-onnx"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

/**
 * v6 "engi ne":
 *  - send_new / send_all  (posle len nove / vsetko)
 *  - beep:N               (N x jemne pipnutie cez STREAM_MUSIC, hudbu nezastavi)
 *  - del_veta / del_odstavec / clear
 *  - engine: http: / shell: / append:TEXT / beep:N / ...
 *  - watchdog: obnovi nahravanie ak spadne
 * Vsetky akcie sa citaju z commands.txt a buttons.txt ZA BEHU -> zmeny bez buildu.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var textView: TextView
    private lateinit var statusView: TextView
    private lateinit var vad: Vad
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private lateinit var offlineRecognizer: OfflineRecognizer

    private var sessionFile: File? = null
    private val segments = ArrayList<String>()
    private var sentCount = 0

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    // config
    private var cfgThreshold = 0.5f
    private var cfgMinSilence = 0.4f
    private var cfgCmdMaxWords = 4
    private var cfgNotifyMode = "on_save"
    private var cfgNotifyIntervalS = 30
    private var cfgKeepScreenOn = true
    private var cfgOperitPort = "8094"
    private var cfgOperitToken = ""
    private var cfgOperitChatId = ""
    private var cfgBeep = true

    private data class Rule(val phrase: String, val action: String)
    private var rules = listOf<Rule>()
    private var cfgMtime = 0L
    private var cmdMtime = 0L

    private var lastNotifyAt = 0L

    @Volatile private var isRecording = false
    @Volatile private var wantRecording = false   // "ma sa nahravat" (pre watchdog)

    private var toneGen: ToneGenerator? = null
    private val ui = Handler(Looper.getMainLooper())

    private data class BtnCfg(val label: String, val action: String)
    private val buttonActions = HashMap<Int, BtnCfg>()
    private var btnMtime = 0L
    private val buttonIds = listOf(
        R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4,
        R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8
    )
    private val buttonViews = ArrayList<Button>()

    // ---------------- pipanie: 3 rozne tony, pocty z configu ----------------
    // cfgBeepXxx = pocet pipnuti pre danu akciu (0 = nepipat)
    private var cfgBeepVolume = 0.8f
    private var cfgBeepSend = 1
    private var cfgBeepErr = 2
    private var cfgBeepDelVeta = 1
    private var cfgBeepDelOdstavec = 2
    private var cfgBeepClear = 3
    private var cfgBeepSave = 2
    private var cfgBeepStart = 1
    private var cfgBeepStop = 1
    private var cfgBeepToneMs = 200

    /** Konkretny ton podla druhu akcie. */
    private fun beepFor(kind: String) {
        val n = when (kind) {
            "send" -> cfgBeepSend
            "err" -> cfgBeepErr
            "del_veta" -> cfgBeepDelVeta
            "del_odstavec" -> cfgBeepDelOdstavec
            "clear" -> cfgBeepClear
            "save" -> cfgBeepSave
            "start" -> cfgBeepStart
            "stop" -> cfgBeepStop
            else -> 1
        }
        beep(n)
    }

    private fun beep(times: Int) {
        if (!cfgBeep || times <= 0) return
        try {
            ui.post {
                if (toneGen == null) {
                    val vol = (cfgBeepVolume * 100).toInt().coerceIn(1, 100)
                    toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, vol)
                }
                var n = 0
                val tick = object : Runnable {
                    override fun run() {
                        try { toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, cfgBeepToneMs) } catch (e: Exception) {}
                        n++
                        if (n < times) ui.postDelayed(this, (cfgBeepToneMs + 60).toLong())
                    }
                }
                ui.post(tick)
            }
        } catch (e: Exception) { Log.e(TAG, "beep err: $e") }
    }

    private fun buttonsFile(): File = File(filesDirCfg(), "buttons.txt")

    private fun writeDefaultButtons() {
        buttonsFile().writeText(
            "# === TLACIDLA: poradie 1-8 | popis | akcia ===\n" +
            "1|\u00b6 Odst.|paragraph\n" +
            "2|Uloz|save\n" +
            "3|Kopir.|copy\n" +
            "4|Zdiel.|share\n" +
            "5|Cas|time\n" +
            "6|Uloz TXT|save_public\n" +
            "7|Notif.|notify\n" +
            "8|Stop|stop\n",
            Charsets.UTF_8
        )
    }

    private fun loadButtons() {
        try {
            val f = buttonsFile()
            if (!f.exists()) writeDefaultButtons()
            btnMtime = f.lastModified()
            buttonActions.clear()
            f.readLines().forEach { line ->
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) return@forEach
                val p = s.split('|', limit = 3)
                if (p.size != 3) return@forEach
                val n = p[0].trim().toIntOrNull() ?: return@forEach
                if (n in 1..8) buttonActions[n] = BtnCfg(p[1].trim(), p[2].trim())
            }
            runOnUiThread {
                buttonViews.forEachIndexed { i, v ->
                    val cfg = buttonActions[i + 1]
                    v.text = cfg?.label ?: "B${i + 1}"
                    v.visibility = if (cfg == null) android.view.View.GONE else android.view.View.VISIBLE
                }
            }
        } catch (e: Exception) { Log.e(TAG, "buttons err: $e") }
    }

    private fun filesDirCfg(): File = getExternalFilesDir(null) ?: filesDir
    private fun configFile(): File = File(filesDirCfg(), "config.txt")
    private fun commandsFile(): File = File(filesDirCfg(), "commands.txt")

    private fun writeDefaultConfig() {
        configFile().writeText(
            "# === KONFIG ===\n" +
            "threshold=0.5\n" +
            "min_silence=0.4\n" +
            "cmd_max_words=4\n" +
            "notify_mode=on_save\n" +
            "notify_interval_s=30\n" +
            "keep_screen_on=true\n" +
            "operit_port=8094\n" +
            "operit_token=SEM_VLOZ_TOKEN\n" +
            "operit_chat_id=\n" +
            "beep=true\n" +
            "beep_volume=0.3\n",
            Charsets.UTF_8
        )
    }

    private fun writeDefaultCommands() {
        commandsFile().writeText(
            "# === PRAVIDLA: fraza | akcia ===\n" +
            "prec vetu|del_veta\n" +
            "cela veta|del_veta\n" +
            "cela vetu|del_veta\n" +
            "zmaz vetu|del_veta\n" +
            "cely odstavec|del_odstavec\n" +
            "zmaz odstavec|del_odstavec\n" +
            "vymaz vsetko|clear\n" +
            "posli|send_new\n" +
            "posli vsetko|send_all\n" +
            "vrat to|redo\n" +
            "novy odstavec|paragraph\n" +
            "uloz to|save\n" +
            "uloz do suboru|save_public\n" +
            "skopiruj|copy\n" +
            "zdielaj|share\n" +
            "daj vediet|notify\n" +
            "cas|time\n" +
            "stop|stop\n",
            Charsets.UTF_8
        )
    }

    private fun normalize(s: String): String {
        val m = mapOf(
            '\u010d' to 'c', '\u0161' to 's', '\u017e' to 'z', '\u00fd' to 'y', '\u00e1' to 'a', '\u00ed' to 'i',
            '\u00e9' to 'e', '\u00f3' to 'o', '\u00fa' to 'u', '\u00f4' to 'o', '\u00e4' to 'a', '\u013e' to 'l',
            '\u0148' to 'n', '\u0165' to 't', '\u010f' to 'd', '\u0155' to 'r', '\u00f4' to 'o'
        )
        return s.lowercase().map { m[it] ?: it }.joinToString("")
    }

    private fun loadConfig() {
        try {
            val f = configFile()
            if (!f.exists()) { writeDefaultConfig() }
            cfgMtime = f.lastModified()
            f.readLines().forEach { line ->
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) return@forEach
                val i = s.indexOf('='); if (i < 0) return@forEach
                val k = s.substring(0, i).trim(); val v = s.substring(i + 1).trim()
                when (k) {
                    "threshold" -> cfgThreshold = v.toFloatOrNull() ?: cfgThreshold
                    "min_silence" -> cfgMinSilence = v.toFloatOrNull() ?: cfgMinSilence
                    "cmd_max_words" -> cfgCmdMaxWords = v.toIntOrNull() ?: cfgCmdMaxWords
                    "notify_mode" -> cfgNotifyMode = v
                    "notify_interval_s" -> cfgNotifyIntervalS = v.toIntOrNull() ?: cfgNotifyIntervalS
                    "keep_screen_on" -> cfgKeepScreenOn = v.equals("true", true)
                    "operit_port" -> cfgOperitPort = v
                    "operit_token" -> cfgOperitToken = v
                    "operit_chat_id" -> cfgOperitChatId = v
                    "beep" -> cfgBeep = v.equals("true", true)
                    "beep_volume" -> cfgBeepVolume = v.toFloatOrNull() ?: cfgBeepVolume
                    "beep_tone_ms" -> cfgBeepToneMs = v.toIntOrNull() ?: cfgBeepToneMs
                    "beep_send" -> cfgBeepSend = v.toIntOrNull() ?: cfgBeepSend
                    "beep_err" -> cfgBeepErr = v.toIntOrNull() ?: cfgBeepErr
                    "beep_del_veta" -> cfgBeepDelVeta = v.toIntOrNull() ?: cfgBeepDelVeta
                    "beep_del_odstavec" -> cfgBeepDelOdstavec = v.toIntOrNull() ?: cfgBeepDelOdstavec
                    "beep_clear" -> cfgBeepClear = v.toIntOrNull() ?: cfgBeepClear
                    "beep_save" -> cfgBeepSave = v.toIntOrNull() ?: cfgBeepSave
                    "beep_start" -> cfgBeepStart = v.toIntOrNull() ?: cfgBeepStart
                    "beep_stop" -> cfgBeepStop = v.toIntOrNull() ?: cfgBeepStop
                }
            }
        } catch (e: Exception) { Log.e(TAG, "config err: $e") }
    }

    private fun loadCommands() {
        try {
            val f = commandsFile()
            if (!f.exists()) { writeDefaultCommands() }
            cmdMtime = f.lastModified()
            rules = f.readLines().mapNotNull { line ->
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) return@mapNotNull null
                val p = s.split('|', limit = 2)
                if (p.size != 2) return@mapNotNull null
                Rule(p[0].trim(), p[1].trim())
            }.sortedByDescending { it.phrase.length }
        } catch (e: Exception) { Log.e(TAG, "commands err: $e") }
    }

    private fun maybeReloadConfig() {
        try {
            val c = configFile(); val d = commandsFile(); val b = buttonsFile()
            val cm = if (c.exists()) c.lastModified() else 0L
            val dm = if (d.exists()) d.lastModified() else 0L
            val bm = if (b.exists()) b.lastModified() else 0L
            if (cm != cfgMtime || dm != cmdMtime || bm != btnMtime) {
                loadConfig(); loadCommands(); loadButtons()
                beepFor("send")
                runOnUiThread { textView.append("\n[konfig nacitany]") }
            }
        } catch (e: Exception) { }
    }

    private fun notifyText(title: String, text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel("diktat_text", "Diktat text", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "diktat_text")
                    else Notification.Builder(this)
            val n = b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text.take(200))
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        } catch (e: Exception) { Log.e(TAG, "notify err: $e") }
    }

    private fun diktatDir(): File {
        val d = File(filesDirCfg(), "Diktat"); if (!d.exists()) d.mkdirs(); return d
    }

    private fun persist() {
        val f = sessionFile ?: return
        try {
            FileOutputStream(f, false).use {
                it.write(segments.joinToString("\n").toByteArray(Charsets.UTF_8)); it.flush()
            }
        } catch (e: Exception) { Log.e(TAG, "persist err: $e") }
    }

    private fun render() { textView.text = segments.joinToString("\n") }

    private fun updateStatus() {
        val chars = segments.joinToString("\n").length
        val words = segments.joinToString(" ").split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val rec = if (isRecording) "NAHRAVA" else "stop"
        val newCount = (segments.size - sentCount).coerceAtLeast(0)
        statusView.text = "$rec | ${segments.size} casti | novych: $newCount | $words slov | $chars znakov"
    }

    private fun notifyIfNeeded(force: Boolean) {
        if (cfgNotifyMode == "off") return
        if (!force && cfgNotifyMode != "on_change") return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < cfgNotifyIntervalS * 1000L) return
        lastNotifyAt = now
        val txt = segments.joinToString("\n")
        if (txt.isNotBlank()) notifyText("Diktat", txt)
    }

    private fun pushHistory() {
        undoStack.addLast(segments.joinToString("\n"))
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restore(state: String) {
        segments.clear()
        if (state.isNotEmpty()) state.split("\n").forEach { segments.add(it) }
        if (sentCount > segments.size) sentCount = segments.size
        persist(); runOnUiThread { render(); updateStatus() }
    }

    private fun doUndo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(segments.joinToString("\n"))
        restore(undoStack.removeLast())
        beepFor("del_veta")
        runOnUiThread { textView.append("\n[spat]") }
    }

    private fun doRedo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(segments.joinToString("\n"))
        restore(redoStack.removeLast())
        beepFor("send")
        runOnUiThread { textView.append("\n[znova]") }
    }

    /** zmaze poslednu vetu (segment) */
    private fun doDelVeta() {
        if (segments.isEmpty()) { beepFor("err"); return }
        pushHistory()
        segments.removeAt(segments.lastIndex)
        if (sentCount > segments.size) sentCount = segments.size
        persist(); beepFor("del_veta")
        runOnUiThread { render(); updateStatus(); textView.append("\n[zmazana veta]") }
    }

    /** zmaze posledny odstavec (skupina viet od posledneho prazdneho riadku) */
    private fun doDelOdstavec() {
        if (segments.isEmpty()) { beepFor("err"); return }
        pushHistory()
        var i = segments.size - 1
        while (i >= 0 && segments[i].isBlank()) { segments.removeAt(i); i-- }
        while (i >= 0 && segments[i].isNotBlank()) { segments.removeAt(i); i-- }
        if (sentCount > segments.size) sentCount = segments.size
        persist(); beepFor("del_odstavec")
        runOnUiThread { render(); updateStatus(); textView.append("\n[zmazany odstavec]") }
    }

    private fun doClear() {
        if (segments.isEmpty()) return
        pushHistory(); segments.clear(); sentCount = 0; persist()
        beepFor("clear")
        runOnUiThread { render(); updateStatus(); textView.append("\n[vymazane]") }
    }

    private fun runAction(action: String) {
        when {
            action == "send_new" -> sendToOperit(all = false)
            action == "send_all" -> sendToOperit(all = true)
            action == "undo" -> doUndo()
            action == "redo" -> doRedo()
            action == "del_veta" -> doDelVeta()
            action == "del_odstavec" -> doDelOdstavec()
            action == "clear" -> doClear()
            action == "paragraph" -> {
                pushHistory(); segments.add(""); persist()
                runOnUiThread { render(); updateStatus() }
            }
            action == "stop" -> runOnUiThread { if (isRecording) toggleRecord() }
            action == "save" -> runOnUiThread { doSave(notify = true) }
            action == "save_public" -> runOnUiThread { doSavePublic() }
            action == "notify" -> notifyIfNeeded(force = true)
            action == "copy" -> doCopy()
            action == "share" -> doShare()
            action == "time" -> {
                pushHistory()
                segments.add("[" + SimpleDateFormat("HH:mm", Locale.US).format(Date()) + "]")
                persist(); runOnUiThread { render(); updateStatus() }
            }
            action == "paste_app" -> doPasteToApp()
            action == "noop" -> { }
            action.startsWith("beep:") -> {
                val n = action.removePrefix("beep:").trim().toIntOrNull() ?: 1
                beep(n)
            }
            action.startsWith("append:") -> {
                pushHistory(); segments.add(action.removePrefix("append:")); persist()
                runOnUiThread { render(); updateStatus() }
            }
            action.startsWith("shell:") -> {
                try {
                    ProcessBuilder("sh", "-c", action.removePrefix("shell:"))
                        .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
                } catch (e: Exception) { Log.e(TAG, "shell err: $e") }
            }
        }
    }

    private fun matchAndRun(segmentText: String): Boolean {
        val t = normalize(segmentText.trim())
        if (t.isEmpty()) return false
        val words = t.split(Regex("\\s+")).size
        if (words > cfgCmdMaxWords) return false
        for (r in rules) {
            if (t.startsWith(normalize(r.phrase))) { runAction(r.action); return true }
        }
        return false
    }

    private fun applySegment(text: String) {
        if (matchAndRun(text)) return
        pushHistory()
        segments.add(text); persist()
        runOnUiThread { render(); updateStatus() }
        notifyIfNeeded(force = false)
    }

    // ---------------- poslanie (len nove / vsetko) ----------------
    private fun sendToOperit(all: Boolean) {
        val from = if (all) 0 else sentCount
        if (segments.size <= from) {
            beepFor("err")
            runOnUiThread { Toast.makeText(this, "Nic nove na odoslanie", Toast.LENGTH_SHORT).show() }
            return
        }
        val newText = segments.subList(from, segments.size).joinToString("\n")
        thread(true) {
            val ok = httpSend(newText)
            if (ok) sentCount = segments.size
            runOnUiThread {
                if (ok) beepFor("send") else beepFor("err")
                textView.post {
                    textView.append(if (ok) "\n[odoslane OK]" else "\n[CHYBA odoslania: token/port?]")
                }
                updateStatus()
            }
        }
    }

    private fun httpSend(message: String): Boolean {
        try {
            if (cfgOperitToken.isBlank() || cfgOperitToken == "SEM_VLOZ_TOKEN") {
                Log.e(TAG, "operit_token nie je nastaveny v config.txt")
                return false
            }
            val json = "{\"message\":\"" + esc(message) + "\"," +
                    "\"response_mode\":\"async_callback\"," +
                    "\"callback_url\":\"http://127.0.0.1:1/cb\"," +
                    "\"show_floating\":false," +
                    "\"return_tool_status\":false," +
                    "\"chat_id\":\"" + esc(cfgOperitChatId) + "\"}"
            val url = URL("http://127.0.0.1:" + cfgOperitPort + "/api/external-chat")
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Authorization", "Bearer " + cfgOperitToken)
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(json) }
            val code = c.responseCode
            Log.i(TAG, "HTTP external-chat -> $code")
            c.disconnect()
            return code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "httpSend err: $e")
            return false
        }
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(" ") else sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** vlozi text do aktivnej appky (cez accessibility) */
    private fun doPasteToApp() {
        val text = segments.joinToString("\n")
        if (text.isBlank()) { beepFor("err"); return }
        val ok = SpeakAccessibilityService.instance?.pasteText(text) ?: false
        beepFor(if (ok) "send" else "err")
        runOnUiThread {
            Toast.makeText(this, if (ok) "Vlozene do aktivnej appky" else "Accessibility nie je zapnuta", Toast.LENGTH_SHORT).show()
        }
    }

    private fun doCopy() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("diktat", segments.joinToString("\n")))
            beepFor("send")
            runOnUiThread { Toast.makeText(this, "Skopirovane", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { Log.e(TAG, "copy err: $e") }
    }

    private fun doShare() {
        val text = segments.joinToString("\n")
        if (text.isBlank()) return
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            runOnUiThread { startActivity(Intent.createChooser(i, "Zdielat diktat")) }
        } catch (e: Exception) { Log.e(TAG, "share err: $e") }
    }

    private fun doSave(notify: Boolean) {
        val f = sessionFile
        if (f == null || !f.exists() || f.length() == 0L) {
            beepFor("err")
            runOnUiThread { textView.append("\n[Nie je co ulozit]") }; return
        }
        if (notify) notifyText("Diktat ulozeny", segments.joinToString("\n"))
        beepFor("save")
        runOnUiThread { textView.append("\n[Ulozene: ${f.name}]") }
    }

    private fun doSavePublic() {
        try {
            val outDir = File("/sdcard/Documents/Diktat")
            if (!outDir.exists()) outDir.mkdirs()
            val name = "diktat_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
            val out = File(outDir, name)
            out.writeText(segments.joinToString("\n"), Charsets.UTF_8)
            beepFor("save")
            runOnUiThread {
                Toast.makeText(this, "Ulozene do Documents/Diktat", Toast.LENGTH_LONG).show()
                textView.append("\n[Ulozene: ${out.absolutePath}]")
            }
        } catch (e: Exception) { Log.e(TAG, "save_public err: $e") }
    }

    private fun showMenu() {
        val items = arrayOf("Poslat NOVE do Operitu", "Poslat VSETKO do Operitu",
            "Zmazat vetu", "Zmazat odstavec", "Zmazat vsetko",
            "Spat (undo)", "Znova (redo)", "Ulozit TXT", "Kopirovat", "Zdielat", "Notifikacia", "Vlozit do appky")
        AlertDialog.Builder(this).setTitle("Menu").setItems(items) { _, i -> when (i) {
            0 -> sendToOperit(false)
            1 -> sendToOperit(true)
            2 -> doDelVeta()
            3 -> doDelOdstavec()
            4 -> doClear()
            5 -> doUndo()
            6 -> doRedo()
            7 -> doSavePublic()
            8 -> doCopy()
            9 -> doShare()
            10 -> notifyIfNeeded(force = true)
            11 -> doPasteToApp()
        } }.show()
    }

    // ---------------- zivotny cyklus ----------------
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = requestCode == REQUEST_RECORD_AUDIO_PERMISSION &&
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!ok) { Log.e(TAG, "mic denied"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
        loadConfig(); loadCommands(); loadButtons()

        textView = findViewById(R.id.my_text)
        textView.movementMethod = ScrollingMovementMethod()
        statusView = findViewById(R.id.status_view)
        recordButton = findViewById(R.id.record_button)
        recordButton.isEnabled = false
        recordButton.setOnClickListener { toggleRecord() }

        findViewById<Button>(R.id.btn_menu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.act_send).setOnClickListener { sendToOperit(false) }
        findViewById<Button>(R.id.act_clear).setOnClickListener { doClear() }
        findViewById<Button>(R.id.act_dellast).setOnClickListener { doDelVeta() }

        buttonViews.clear()
        buttonIds.forEach { id ->
            val v = findViewById<Button>(id)
            buttonViews.add(v)
            v.setOnClickListener {
                val n = buttonIds.indexOf(id) + 1
                val cfg = buttonActions[n]
                if (cfg != null) runAction(cfg.action)
            }
        }
        loadButtons()

        startWatchdog()

        textView.text = "Inicializujem modely..."
        lifecycleScope.launch(Dispatchers.IO) {
            initVadModel(); initOfflineRecognizer()
            withContext(Dispatchers.Main) {
                recordButton.isEnabled = true
                textView.text = "Pripravene.\nPravidla: ${rules.size}\nText: ${diktatDir().absolutePath}"
                updateStatus()
            }
        }
    }

    /** watchdog: ak ma nahravanie bezat a nebezi (ruca), skus obnovit */
    private fun startWatchdog() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (wantRecording && !isRecording && recordButton.isEnabled) {
                        Log.i(TAG, "watchdog: obnovujem nahravanie")
                        toggleRecord()
                    }
                } catch (e: Exception) {}
                ui.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun toggleRecord() {
        if (!isRecording) {
            if (!initMicrophone()) return
            audioRecord!!.startRecording()
            val svc = Intent(this, DiktatService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            recordButton.setText(R.string.stop)
            isRecording = true
            wantRecording = true
            segments.clear(); undoStack.clear(); redoStack.clear(); sentCount = 0
            sessionFile = File(diktatDir(), "diktat_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
            persist(); render(); lastNotifyAt = 0
            if (cfgKeepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            vad.reset()
            beepFor("start")
            recordingThread = thread(true) { processSamples() }
        } else {
            isRecording = false
            wantRecording = false
            try { stopService(Intent(this, DiktatService::class.java)) } catch (e: Exception) {}
            audioRecord?.stop(); audioRecord?.release(); audioRecord = null
            recordButton.setText(R.string.start)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            beepFor("stop")
        }
        updateStatus()
    }

    private fun initVadModel() {
        val config = getVadModelConfig(0)!!
        config.sileroVadModelConfig.threshold = cfgThreshold
        config.sileroVadModelConfig.minSilenceDuration = cfgMinSilence
        vad = Vad(assetManager = application.assets, config = config)
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }
        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        audioRecord = AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, numBytes * 2)
        try {
            android.media.audiofx.NoiseSuppressor.create(audioRecord!!.audioSessionId)?.enabled = true
            android.media.audiofx.AutomaticGainControl.create(audioRecord!!.audioSessionId)?.enabled = true
        } catch (e: Exception) { }
        return true
    }

    private fun processSamples() {
        val buffer = ShortArray(512)
        val scope = CoroutineScope(Dispatchers.IO)
        var tick = 0
        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size)
            if (ret != null && ret > 0) {
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                vad.acceptWaveform(samples)
                while (!vad.empty()) {
                    val seg = vad.front()
                    scope.launch {
                        val text = runSecondPass(seg.samples)
                        if (text.isNotBlank()) applySegment(text)
                    }
                    vad.pop()
                }
            }
            if (++tick % 20 == 0) { scope.launch { maybeReloadConfig() } }
        }
        scope.cancel()
    }

    private fun initOfflineRecognizer() {
        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getOfflineModelConfig(type = 40)!!,
        )
        offlineRecognizer = OfflineRecognizer(assetManager = application.assets, config = config)
    }

    private fun runSecondPass(samples: FloatArray): String {
        val stream = offlineRecognizer.createStream()
        stream.acceptWaveform(samples, sampleRateInHz)
        offlineRecognizer.decode(stream)
        val result = offlineRecognizer.getResult(stream)
        stream.release()
        return result.text
    }

    override fun onDestroy() {
        super.onDestroy()
        try { toneGen?.release() } catch (e: Exception) {}
    }
}