package com.k2fsa.sherpa.onnx.vad.asr

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "sherpa-onnx"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

// Otvorena appka: PRAVIDLA (commands.txt) + KONFIG (config.txt) sa citaju za behu.
// Akekolvek nove slovo / akcia = uprava suboru + (hot reload) ziadny build.
class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var textView: TextView
    private lateinit var vad: Vad
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private lateinit var offlineRecognizer: OfflineRecognizer

    // session
    private var sessionFile: File? = null
    private val segments = ArrayList<String>()

    // ---------- KONFIG (config.txt) ----------
    private var cfgThreshold = 0.5f
    private var cfgMinSilence = 0.4f
    private var cfgCmdMaxWords = 4
    private var cfgNotifyMode = "on_save"     // off | on_change | on_save
    private var cfgNotifyIntervalS = 30
    private var cfgBtn2Label = "Späť"
    private var cfgBtn3Label = "Uložiť"

    // ---------- PRAVIDLA (commands.txt): fraza | akcia ----------
    private data class Rule(val phrase: String, val action: String)
    private var rules = listOf<Rule>()
    private var cfgMtime = 0L
    private var cmdMtime = 0L

    private var lastNotifyAt = 0L

    @Volatile
    private var isRecording = false

    // tlačidlo: id, label, akcia (rovnaký formát akcií ako commands.txt)
    private data class BtnCfg(val label: String, val action: String)
    private val buttonActions = HashMap<Int, BtnCfg>()
    private var btnMtime = 0L
    private val buttonIds = listOf(
        R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4,
        R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8
    )
    private val buttonViews = ArrayList<Button>()

    private fun buttonsFile(): File = File(filesDirCfg(), "buttons.txt")

    private fun writeDefaultButtons() {
        buttonsFile().writeText(
            "# === TLACIDLA: poradie 1-8 | popis | akcia ===\n" +
            "# akcie: undo/paragraph/stop/save/stop_save/clear/notify/append:TEXT/shell:CMD/noop\n" +
            "1|Späť|undo\n" +
            "2|Uložiť|save\n" +
            "3|Operitu|send_chat\n" +
            "4|Odstavec|paragraph\n" +
            "5|Vymaž|clear\n" +
            "6|Notifikuj|notify\n" +
            "7|B7|noop\n" +
            "8|B8|noop\n",
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
            // aplikovat na views (ak existuju)
            runOnUiThread {
                buttonViews.forEachIndexed { i, v ->
                    val cfg = buttonActions[i + 1]
                    v.text = cfg?.label ?: "B${i + 1}"
                    v.visibility = if (cfg == null) android.view.View.GONE else android.view.View.VISIBLE
                }
            }
            Log.i(TAG, "buttons: ${buttonActions.size}")
        } catch (e: Exception) { Log.e(TAG, "buttons err: $e") }
    }

    // ---------------- súbory konfigu ----------------
    private fun filesDirCfg(): File = getExternalFilesDir(null) ?: filesDir
    private fun configFile(): File = File(filesDirCfg(), "config.txt")
    private fun commandsFile(): File = File(filesDirCfg(), "commands.txt")

    private fun writeDefaultConfig() {
        configFile().writeText(
            "# === KONFIG (hladany pri starte a pri zmene suboru - BEZ buildu) ===\n" +
            "# prah VAD 0.4-0.9 (vyssie = menej sumu zachyti)\n" +
            "threshold=0.5\n" +
            "# pauza medzi segmentami v sekundach\n" +
            "min_silence=0.4\n" +
            "# maximalny pocet slov v prikaze\n" +
            "cmd_max_words=4\n" +
            "# notifikacia: off | on_change | on_save\n" +
            "#   on_change = po kazdej zmene textu (s intervalom notify_interval_s)\n" +
            "#   on_save   = len pri ULOZIT / pošli (odporucane)\n" +
            "notify_mode=on_save\n" +
            "notify_interval_s=30\n" +
            "# popisky tlacidiel\n" +
            "btn2_label=Späť\n" +
            "btn3_label=Uložiť\n",
            Charsets.UTF_8
        )
    }

    private fun writeDefaultCommands() {
        commandsFile().writeText(
            "# === PRAVIDLA: fraza | akcia (prefix, bez diakritiky, ~4 slova) ===\n" +
            "# AKCIE:\n" +
            "#   undo          = zmaze posledny riadok\n" +
            "#   paragraph     = novy odstavec\n" +
            "#   stop_save     = ukonci nahravanie + uloz + notifikacia\n" +
            "#   stop          = iba ukonci nahravanie\n" +
            "#   save          = iba uloz + notifikacia\n" +
            "#   clear         = vymaze celu relaciu\n" +
            "#   notify        = hodi notifikaciu s aktualnym textom\n" +
            "#   append:TEXT   = vlozi TEXT do dokumentu\n" +
            "#   shell:CMD     = spusti shell prikaz (AKOKOLVEK - tvorenie bez buildu!)\n" +
            "#   noop          = nic (len na vyskusanie zachytenia slova)\n" +
            "preč vetu|undo\n" +
            "nový odstavec|paragraph\n" +
            "pošli|send_chat\n" +
            "vymaž všetko|clear\n" +
            "ulož to|save\n" +
            "daj vedieť|notify\n",
            Charsets.UTF_8
        )
    }

    private fun normalize(s: String): String {
        val m = mapOf(
            'č' to 'c', 'š' to 's', 'ž' to 'z', 'ý' to 'y', 'á' to 'a', 'í' to 'i',
            'é' to 'e', 'ó' to 'o', 'ú' to 'u', 'ô' to 'o', 'ä' to 'a', 'ľ' to 'l',
            'ň' to 'n', 'ť' to 't', 'ď' to 'd', 'ŕ' to 'r'
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
                    "btn2_label" -> cfgBtn2Label = v
                    "btn3_label" -> cfgBtn3Label = v
                }
            }
            Log.i(TAG, "config: th=$cfgThreshold sil=$cfgMinSilence notify=$cfgNotifyMode")
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
            }.sortedByDescending { it.phrase.length } // najdlšie frazy prve
            Log.i(TAG, "rules: ${rules.size}")
        } catch (e: Exception) { Log.e(TAG, "commands err: $e") }
    }

    // hot reload: kontroluje mtimes (lacne stat volania)
    private fun maybeReloadConfig() {
        try {
            val c = configFile(); val d = commandsFile(); val b = buttonsFile()
            val cm = if (c.exists()) c.lastModified() else 0L
            val dm = if (d.exists()) d.lastModified() else 0L
            val bm = if (b.exists()) b.lastModified() else 0L
            if (cm != cfgMtime || dm != cmdMtime || bm != btnMtime) {
                Log.i(TAG, "config changed -> reload")
                loadConfig(); loadCommands(); loadButtons()
                runOnUiThread { textView.append("\n[konfig načítaný]") }
            }
        } catch (e: Exception) { }
    }

    // ---------------- notifikacia priamo z appky (ziadny watchdog!) ----------------
    private fun notifyText(title: String, text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel("diktat_text", "Diktát text", NotificationManager.IMPORTANCE_HIGH)
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

    // ---------------- sesia a zapis ----------------
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

    private fun notifyIfNeeded(force: Boolean) {
        if (cfgNotifyMode == "off") return
        if (!force && cfgNotifyMode != "on_change") return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < cfgNotifyIntervalS * 1000L) return
        lastNotifyAt = now
        val txt = segments.joinToString("\n")
        if (txt.isNotBlank()) notifyText("Diktát", txt)
    }

    // ---------------- akcie ----------------
    private fun runAction(action: String) {
        when {
            action == "undo" -> {
                if (segments.isNotEmpty()) {
                    segments.removeAt(segments.lastIndex); persist()
                    runOnUiThread { render(); textView.append("\n[zmazané]") }
                }
            }
            action == "paragraph" -> {
                if (segments.isNotEmpty()) {
                    segments[segments.lastIndex] = segments.last() + "\n"; persist()
                    runOnUiThread { render() }
                }
            }
            action == "stop" -> runOnUiThread { if (isRecording) toggleRecord() }
            action == "stop_save" -> runOnUiThread {
                if (isRecording) toggleRecord()
                notifyIfNeeded(force = true)
                sendSavedBroadcast()
            }
            action == "save" -> runOnUiThread { doSave(notify = true) }
            action == "clear" -> {
                segments.clear(); persist()
                runOnUiThread { render(); textView.append("\n[vymazané]") }
            }
            action == "notify" -> notifyIfNeeded(force = true)
            action == "send_chat" -> {
                // zastavi nahravanie + zapise flag (workflow ho cita) + broadcast (bonus)
                runOnUiThread { if (isRecording) toggleRecord() }
                try {
                    val dir = diktatDir()
                    var p = sessionFile?.absolutePath
                    if (p == null || !File(p).exists()) {
                        p = dir.listFiles()?.maxByOrNull { it.lastModified() }?.absolutePath
                    }
                    if (p != null) File(dir, "SENDME.txt").writeText(p)
                } catch (e: Exception) { Log.e(TAG, "flag err: $e") }
                sendSavedBroadcast()
                textView.post { textView.append("\n[odosielam do Operitu...]") }
            }
            action == "noop" -> { }
            action.startsWith("append:") -> {
                segments.add(action.removePrefix("append:")); persist()
                runOnUiThread { render() }
            }
            action.startsWith("shell:") -> {
                try {
                    ProcessBuilder("sh", "-c", action.removePrefix("shell:"))
                        .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
                    Log.i(TAG, "shell ok: ${action.take(60)}")
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
        segments.add(text); persist()
        runOnUiThread { render() }
        notifyIfNeeded(force = false) // len ak notify_mode=on_change
    }

    private fun sendSavedBroadcast() {
        try {
            sendBroadcast(Intent("com.k2fsa.sherpa.onnx.DIKTAT_SAVED")
                .putExtra("path", sessionFile?.absolutePath ?: ""))
        } catch (e: Exception) { }
    }

    private fun doSave(notify: Boolean) {
        val f = sessionFile
        if (f == null || !f.exists() || f.length() == 0L) {
            textView.append("\n[Nie je čo uložiť]"); return
        }
        if (notify) notifyText("Diktát uložený", segments.joinToString("\n"))
        sendSavedBroadcast()
        textView.append("\n[Uložené: ${f.name}]")
    }

    // ---------------- lifecycle ----------------
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
        loadConfig(); loadCommands()

        textView = findViewById(R.id.my_text)
        textView.movementMethod = ScrollingMovementMethod()
        recordButton = findViewById(R.id.record_button)
        recordButton.isEnabled = false
        recordButton.setOnClickListener { toggleRecord() }

        // 8 volnych tlacidiel
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

        textView.text = "Inicializujem modely..."
        lifecycleScope.launch(Dispatchers.IO) {
            initVadModel(); initOfflineRecognizer()
            withContext(Dispatchers.Main) {
                recordButton.isEnabled = true
                textView.text = "Pripravené.\nPravidlá: ${rules.size}\nText: ${diktatDir().absolutePath}"
            }
        }
    }

    private fun toggleRecord() {
        if (!isRecording) {
            if (!initMicrophone()) return
            audioRecord!!.startRecording()
            val svc = Intent(this, DiktatService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            recordButton.setText(R.string.stop)
            isRecording = true
            segments.clear()
            sessionFile = File(diktatDir(), "diktat_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
            persist(); render(); lastNotifyAt = 0
            vad.reset()
            recordingThread = thread(true) { processSamples() }
        } else {
            isRecording = false
            stopService(Intent(this, DiktatService::class.java))
            audioRecord!!.stop(); audioRecord!!.release(); audioRecord = null
            recordButton.setText(R.string.start)
        }
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
            if (++tick % 20 == 0) { // ~0.6s
                scope.launch { maybeReloadConfig() }
            }
        }
        scope.cancel()
    }

    private fun initOfflineRecognizer() {
        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getOfflineModelConfig(type = 40)!!, // SK Parakeet
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
}