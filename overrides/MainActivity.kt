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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

private const val TAG = "sherpa-onnx"
private const val REQ_MIC = 200

class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var editText: EditText
    private lateinit var statusView: TextView
    private lateinit var translateLabel: TextView
    private lateinit var translateText: EditText
    private lateinit var vad: Vad
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private var sessionFile: File? = null
    private var wavFile: File? = null
    private var pcmOut: FileOutputStream? = null
    private val undoStack = ArrayDeque<String>()

    @Volatile private var isRecording = false
    @Volatile private var wantRecording = false

    private var toneGen: ToneGenerator? = null
    private val ui = Handler(Looper.getMainLooper())
    private var translateJob: Job? = null

    private val buttonIds = listOf(R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4, R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8)
    private val buttonViews = ArrayList<Button>()

    // ---------------- pipanie ----------------
    private fun beepFor(kind: String) {
        if (!Settings.beep) return
        val n = when (kind) {
            "send" -> Settings.beepSend
            "err" -> Settings.beepErr
            "del_veta" -> Settings.beepDelVeta
            "del_odstavec" -> Settings.beepDelOdstavec
            "clear" -> Settings.beepClear
            "save" -> Settings.beepSave
            "start" -> Settings.beepStart
            "stop" -> Settings.beepStop
            else -> 1
        }
        beep(n)
    }

    private fun beep(times: Int) {
        if (!Settings.beep || times <= 0) return
        try {
            ui.post {
                if (toneGen == null) {
                    toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, (Settings.beepVolume * 100).toInt().coerceIn(1, 100))
                }
                var n = 0
                val tick = object : Runnable {
                    override fun run() {
                        try { toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, Settings.beepToneMs) } catch (e: Exception) {}
                        n++
                        if (n < times) ui.postDelayed(this, (Settings.beepToneMs + 60).toLong())
                    }
                }
                ui.post(tick)
            }
        } catch (e: Exception) { Log.e(TAG, "beep err: $e") }
    }

    // ---------------- onCreate ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ActivityCompat.requestPermissions(this, permissions, REQ_MIC)
        Settings.init(getExternalFilesDir(null) ?: filesDir)

        recordButton = findViewById(R.id.record_button)
        editText = findViewById(R.id.my_text)
        statusView = findViewById(R.id.status_view)
        translateLabel = findViewById(R.id.translate_label)
        translateText = findViewById(R.id.translate_text)

        recordButton.setOnClickListener { toggleRecord() }
        findViewById<Button>(R.id.btn_menu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.act_send).setOnClickListener { sendToOperit() }
        findViewById<Button>(R.id.act_dellast).setOnClickListener { doDelVeta() }
        findViewById<Button>(R.id.act_clear).setOnClickListener { doClear() }

        buttonViews.clear()
        buttonIds.forEach { id ->
            val v = findViewById<Button>(id)
            buttonViews.add(v)
            v.visibility = View.GONE
            v.setOnClickListener {
                val n = buttonIds.indexOf(id) + 1
                val f = buttonsFile()
                if (f.exists()) f.readLines().forEach { line ->
                    val s = line.trim(); if (s.isEmpty() || s.startsWith("#")) return@forEach
                    val p = s.split('|', limit = 3)
                    if (p.size == 3 && p[0].trim().toIntOrNull() == n) runAction(p[2].trim())
                }
            }
        }
        loadButtons()
        updateTranslateVisibility()
        startWatchdog()

        editText.setText("Inicializujem modely...")
        lifecycleScope.launch(Dispatchers.IO) {
            initVadModel(); initAsr()
            withContext(Dispatchers.Main) { recordButton.isEnabled = true; editText.setText(""); updateStatus() }
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { scheduleLiveTranslate() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ---------------- preklad ----------------
    private fun updateTranslateVisibility() {
        val v = if (Settings.translateEnabled) View.VISIBLE else View.GONE
        translateLabel.visibility = v
        translateText.visibility = v
    }

    private fun scheduleLiveTranslate() {
        if (!Settings.translateEnabled) return
        translateJob?.cancel()
        translateJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(1500)
            val src = editText.text.toString()
            if (src.isBlank()) return@launch
            val out = if (Settings.translateEngine == "mlkit")
                Translate.translateMlkit(src, Settings.asrLang, Settings.translateTarget)
            else Translate.translateGguf(src, Settings.translateTarget)
            if (out != null) withContext(Dispatchers.Main) { translateText.setText(out) }
        }
    }

    private fun doTranslateNow() {
        if (!Settings.translateEnabled) { toast("Zapni preklad v nastaveniach"); return }
        val src = editText.text.toString()
        if (src.isBlank()) return
        statusView.text = "Prekladam..."
        lifecycleScope.launch(Dispatchers.IO) {
            val out = if (Settings.translateEngine == "mlkit")
                Translate.translateMlkit(src, Settings.asrLang, Settings.translateTarget)
            else Translate.translateGguf(src, Settings.translateTarget)
            withContext(Dispatchers.Main) {
                if (out != null) { translateText.setText(out); statusView.text = "Prelozene" }
                else statusView.text = "Preklad zlyhal"
            }
        }
    }

    // ---------------- menu ----------------
    private fun showMenu() {
        val items = arrayOf(
            "Nastavenia",
            "Prepis nahravky (audio subor)",
            "Preklad (teraz)",
            "Strihat nahravku",
            "Poslat do Operitu",
            "Zmazat vetu",
            "Zmazat odstavec",
            "Zmazat vsetko",
            "Spat",
            "Ulozit TXT",
            "Kopirovat",
            "Zdielat",
            "Notifikacia",
            "Vlozit do appky"
        )
        AlertDialog.Builder(this).setTitle("Menu").setItems(items) { _, i -> when (i) {
            0 -> startActivity(Intent(this, SettingsActivity::class.java))
            1 -> pickAudioForTranscribe()
            2 -> doTranslateNow()
            3 -> trimLastRecording()
            4 -> sendToOperit()
            5 -> doDelVeta()
            6 -> doDelOdstavec()
            7 -> doClear()
            8 -> doUndo()
            9 -> doSavePublic()
            10 -> doCopy()
            11 -> doShare()
            12 -> notifyText(txt())
            13 -> doPasteToApp()
        } }.show()
    }

    // ---------------- prepis audio suboru ----------------
    private fun pickAudioForTranscribe() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" }
            startActivityForResult(Intent.createChooser(i, "Vyber audio"), 1001)
        } catch (e: Exception) { toast("Neda sa otvorit vyber suboru") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            transcribeUri(uri)
        }
    }

    private fun transcribeUri(uri: Uri) {
        statusView.text = "Prepisujem subor..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tmp = File(cacheDir, "audio_in.tmp")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out) }
                }
                val text = transcribeFile(tmp)
                withContext(Dispatchers.Main) {
                    if (text.isBlank()) { statusView.text = "Nic sa nepodarilo prepisat" }
                    else {
                        val cur = txt()
                        setTxt(if (cur.isBlank()) text else cur + "\n" + text)
                        statusView.text = "Prepisane"; persist(); beepFor("send")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "transcribe err: $e")
                withContext(Dispatchers.Main) { statusView.text = "Chyba: $e" }
            }
        }
    }

    /** Prepis celeho audio suboru cez aktualny ASR model (Parakeet). */
    private fun transcribeFile(f: File): String {
        val rec = recognizer ?: return ""
        val bytes = f.readBytes()
        // ak je WAV, preskoc 44B hlavicku
        val isWav = bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()
        val start = if (isWav) 44 else 0
        val n = (bytes.size - start) / 2
        if (n <= 0) return ""
        val samples = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[start + i * 2].toInt() and 0xFF
            val hi = bytes[start + i * 2 + 1].toInt()
            val v = (hi shl 8) or lo
            samples[i] = v / 32768.0f
        }
        return try {
            val s = rec.createStream()
            s.acceptWaveform(samples, sampleRateInHz)
            rec.decode(s)
            val r = rec.getResult(s); s.release(); r.text
        } catch (e: Exception) { "" }
    }

    // ---------------- strihanie nahravky ----------------
    private fun trimLastRecording() {
        val src = wavFile
        if (src == null || !src.exists()) { toast("Ziadna nahravka"); return }
        val v = EditText(this).apply {
            hint = "od-do v sekundach, napr. 3.5-12"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this).setTitle("Strihat nahravku").setView(v)
            .setPositiveButton("Strihnut") { _, _ ->
                val parts = v.text.toString().split("-")
                if (parts.size != 2) { toast("Zly format"); return@setPositiveButton }
                val a = parts[0].trim().toDoubleOrNull() ?: 0.0
                val b = parts[1].trim().toDoubleOrNull() ?: 0.0
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = trimWav(src, a, b)
                    withContext(Dispatchers.Main) { toast(if (ok != null) "Ostrihane: ${ok.name}" else "Strihanie zlyhalo") }
                }
            }.setNegativeButton("Zrusit", null).show()
    }

    private fun trimWav(src: File, from: Double, to: Double): File? {
        return try {
            val all = src.readBytes()
            val hdr = if (all[0] == 'R'.code.toByte()) 44 else 0
            val s = hdr + (from * sampleRateInHz * 2).toInt()
            val e = (hdr + (to * sampleRateInHz * 2).toInt()).coerceAtMost(all.size)
            if (s >= e) return null
            val data = all.copyOfRange(s.coerceAtLeast(hdr), e)
            val out = File(diktatDir(), "ostrihane_" + System.currentTimeMillis() + ".wav")
            DataOutputStream(FileOutputStream(out)).use { o ->
                writeWavHeader(o, data.size)
                o.write(data)
            }
            out
        } catch (e: Exception) { Log.e(TAG, "trim err: $e"); null }
    }

    private fun writeWavHeader(o: DataOutputStream, dataLen: Int) {
        o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + dataLen)); o.writeBytes("WAVE")
        o.writeBytes("fmt "); o.writeInt(Integer.reverseBytes(16))
        o.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        o.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        o.writeInt(Integer.reverseBytes(sampleRateInHz))
        o.writeInt(Integer.reverseBytes(sampleRateInHz * 2))
        o.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
        o.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
        o.writeBytes("data"); o.writeInt(Integer.reverseBytes(dataLen))
    }

    // ---------------- pomocne ----------------
    private fun txt(): String = editText.text.toString()
    private fun setTxt(s: String) { editText.setText(s); editText.setSelection(s.length) }
    private fun push() { undoStack.addLast(txt()); if (undoStack.size > 100) undoStack.removeFirst() }
    private fun doUndo() { if (undoStack.isEmpty()) return; setTxt(undoStack.removeLast()); beepFor("del_veta") }

    private fun doDelVeta() {
        var s = txt().trimEnd('\n')
        if (s.isBlank()) { beepFor("err"); return }
        push()
        val lastNl = s.lastIndexOf('\n')
        val lastDot = maxOf(s.lastIndexOf('.'), s.lastIndexOf('!'), s.lastIndexOf('?'))
        val cut = if (lastDot > lastNl) lastDot + 1 else if (lastNl >= 0) lastNl else 0
        setTxt(s.substring(0, cut).trimEnd('\n')); updateStatus(); beepFor("del_veta")
    }

    private fun doDelOdstavec() {
        val s = txt().trimEnd('\n')
        if (s.isBlank()) { beepFor("err"); return }
        push()
        val i = s.lastIndexOf('\n')
        setTxt(if (i >= 0) s.substring(0, i).trimEnd('\n') else ""); updateStatus(); beepFor("del_odstavec")
    }

    private fun doClear() {
        if (txt().isBlank()) return
        push(); setTxt(""); translateText.setText(""); updateStatus(); beepFor("clear")
    }

    private fun updateStatus() {
        val t = txt()
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        statusView.text = "${if (isRecording) "NAHRAVA" else "stop"} | $words slov | ${t.length} znakov"
    }

    private fun persist() {
        val f = sessionFile ?: return
        try { FileOutputStream(f, false).use { it.write(txt().toByteArray(Charsets.UTF_8)) } } catch (e: Exception) {}
    }

    private fun doSavePublic() {
        try {
            val d = File("/sdcard/Documents/Speak-o"); d.mkdirs()
            val f = File(d, "speako_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
            f.writeText(txt()); beepFor("save"); toast("Ulozene: ${f.name}")
        } catch (e: Exception) { toast("Chyba") }
    }

    private fun doCopy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("speako", txt())); beepFor("send"); toast("Skopirovane")
    }

    private fun doShare() {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txt()) }
        startActivity(Intent.createChooser(i, "Zdielat"))
    }

    private fun notifyText(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("speako", "Speak-o", NotificationManager.IMPORTANCE_HIGH))
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "speako") else Notification.Builder(this)
        nm.notify(1, b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Speak-o").setStyle(Notification.BigTextStyle().bigText(text)).build())
    }

    private fun doPasteToApp() {
        val text = txt()
        if (text.isBlank()) { beepFor("err"); return }
        val ok = SpeakAccessibilityService.instance?.pasteText(text) ?: false
        beepFor(if (ok) "send" else "err")
        toast(if (ok) "Vlozene do aktivnej appky" else "Accessibility nie je zapnuta")
    }

    // ---------------- Operit ----------------
    private fun sendToOperit() {
        val text = txt()
        if (text.isBlank()) { beepFor("err"); return }
        if (!Settings.operitEnabled || Settings.operitToken.isBlank()) { toast("Operit nie je nastaveny"); beepFor("err"); return }
        thread(true) {
            val ok = httpSend(text)
            runOnUiThread { beepFor(if (ok) "send" else "err"); toast(if (ok) "Poslane do Operitu" else "Odoslanie zlyhalo") }
        }
    }

    private fun httpSend(message: String): Boolean {
        try {
            val json = "{\"message\":\"" + esc(message) + "\",\"response_mode\":\"async_callback\",\"callback_url\":\"http://127.0.0.1:1/cb\",\"show_floating\":false,\"chat_id\":\"" + esc(Settings.operitChatId) + "\"}"
            val c = URL("http://127.0.0.1:" + Settings.operitPort + "/api/external-chat").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 20000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Authorization", "Bearer " + Settings.operitToken)
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode; c.disconnect(); return code in 200..299
        } catch (e: Exception) { return false }
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) when (ch) {
            '\\' -> sb.append("\\\\"); '"' -> sb.append("\\\""); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r")
            else -> if (ch < ' ') sb.append(" ") else sb.append(ch)
        }
        return sb.toString()
    }

    // ---------------- subory / tlacidla ----------------
    private fun buttonsFile(): File = File(getExternalFilesDir(null) ?: filesDir, "buttons.txt")

    private fun loadButtons() {
        try {
            val f = buttonsFile()
            if (!f.exists()) f.writeText("1|Kopir.|copy\n2|Uloz|save\n3|Vloz|paste_app\n4|Notif.|notify\n", Charsets.UTF_8)
            val map = HashMap<Int, Pair<String, String>>()
            f.readLines().forEach { line ->
                val s = line.trim(); if (s.isEmpty() || s.startsWith("#")) return@forEach
                val p = s.split('|', limit = 3); if (p.size != 3) return@forEach
                val n = p[0].trim().toIntOrNull() ?: return@forEach
                map[n] = p[1].trim() to p[2].trim()
            }
            runOnUiThread {
                buttonViews.forEachIndexed { i, v ->
                    val c = map[i + 1]
                    if (c == null) v.visibility = View.GONE else { v.visibility = View.VISIBLE; v.text = c.first }
                }
            }
        } catch (e: Exception) {}
    }

    private fun runAction(action: String) = when (action) {
        "copy" -> doCopy()
        "save" -> doSavePublic()
        "paste_app" -> doPasteToApp()
        "notify" -> notifyText(txt())
        "del_veta" -> doDelVeta()
        "del_odstavec" -> doDelOdstavec()
        "clear" -> doClear()
        "send_new", "send_all" -> sendToOperit()
        "translate" -> doTranslateNow()
        "transcribe_file" -> pickAudioForTranscribe()
        "trim_audio" -> trimLastRecording()
        else -> {}
    }

    // ---------------- nahravanie ----------------
    private fun toggleRecord() {
        if (!isRecording) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, REQ_MIC); return
            }
            val min = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
            audioRecord = AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, min * 2)
            audioRecord!!.startRecording()
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, DiktatService::class.java))
            recordButton.setText("STOP")
            isRecording = true; wantRecording = true
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            sessionFile = File(diktatDir(), "diktat_$ts.txt")
            wavFile = File(diktatDir(), "zaznam_$ts.wav")
            try { pcmOut = FileOutputStream(wavFile) } catch (e: Exception) {}
            if (Settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            vad.reset(); beepFor("start")
            thread(true) { processSamples() }
        } else {
            isRecording = false; wantRecording = false
            try { stopService(Intent(this, DiktatService::class.java)) } catch (e: Exception) {}
            audioRecord?.stop(); audioRecord?.release(); audioRecord = null
            try { pcmOut?.close() } catch (e: Exception) {}; pcmOut = null
            recordButton.setText("NAHRAVAT")
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            beepFor("stop"); persist()
        }
        updateStatus()
    }

    private fun startWatchdog() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (wantRecording && !isRecording && recordButton.isEnabled) toggleRecord()
                ui.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun initVadModel() {
        val c = getVadModelConfig(0)!!
        c.sileroVadModelConfig.threshold = 0.5f
        c.sileroVadModelConfig.minSilenceDuration = 0.4f
        vad = Vad(assetManager = application.assets, config = c)
    }

    /** Nacita ASR: SK Parakeet (assets) alebo externy model z asrModelDir/<lang>/ */
    private fun initAsr() {
        try {
            if (Settings.asrLang.lowercase() == "sk") {
                val cfg = OfflineRecognizerConfig(
                    featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
                    modelConfig = getOfflineModelConfig(type = 40)!!)
                recognizer = OfflineRecognizer(assetManager = application.assets, config = cfg)
                return
            }
            val d = File(Settings.asrModelDir, Settings.asrLang.lowercase())
            val enc = d.listFiles()?.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }
            val dec = d.listFiles()?.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") }
            val joi = d.listFiles()?.firstOrNull { it.name.contains("joiner") && it.name.endsWith(".onnx") }
            val tok = d.listFiles()?.firstOrNull { it.name.contains("tokens") && it.name.endsWith(".txt") }
            if (enc != null && dec != null && joi != null && tok != null) {
                val mc = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                        encoder = enc.absolutePath, decoder = dec.absolutePath, joiner = joi.absolutePath),
                    tokens = tok.absolutePath, modelType = "nemo_transducer")
                recognizer = OfflineRecognizer(application.assets,
                    OfflineRecognizerConfig(featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80), modelConfig = mc))
            } else {
                // fallback SK
                val cfg = OfflineRecognizerConfig(
                    featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
                    modelConfig = getOfflineModelConfig(type = 40)!!)
                recognizer = OfflineRecognizer(assetManager = application.assets, config = cfg)
            }
        } catch (e: Exception) { Log.e(TAG, "initAsr err: $e") }
    }

    private fun processSamples() {
        val buffer = ShortArray(512)
        val scope = CoroutineScope(Dispatchers.IO)
        // hlavicka WAV na zaciatku
        try { pcmOut?.let { DataOutputStream(it).also { d -> writeWavHeader(d, 0) } } } catch (e: Exception) {}
        var total = 0
        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (ret > 0) {
                try {
                    val b = ByteArray(ret * 2)
                    for (i in 0 until ret) {
                        b[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        b[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                    }
                    pcmOut?.write(b); total += ret
                } catch (e: Exception) {}
                vad.acceptWaveform(FloatArray(ret) { buffer[it] / 32768.0f })
                while (!vad.empty()) {
                    val seg = vad.front()
                    scope.launch {
                        val t = runSecondPass(seg.samples)
                        if (t.isNotBlank()) runOnUiThread {
                            val cur = txt()
                            setTxt(if (cur.isBlank()) t else cur + "\n" + t)
                            updateStatus(); persist()
                        }
                    }
                    vad.pop()
                }
            }
        }
        scope.cancel()
    }

    private fun runSecondPass(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        return try {
            val s = rec.createStream()
            s.acceptWaveform(samples, sampleRateInHz)
            rec.decode(s)
            val r = rec.getResult(s); s.release(); r.text
        } catch (e: Exception) { "" }
    }

    private fun diktatDir(): File { val d = File(getExternalFilesDir(null) ?: filesDir, "Diktat"); d.mkdirs(); return d }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(r: Int, p: Array<String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == REQ_MIC && (g.isEmpty() || g[0] != PackageManager.PERMISSION_GRANTED)) finish()
    }

    override fun onResume() {
        super.onResume()
        Settings.init(getExternalFilesDir(null) ?: filesDir)
        updateTranslateVisibility(); loadButtons()
        lifecycleScope.launch(Dispatchers.IO) { initAsr() }
    }

    override fun onDestroy() { super.onDestroy(); try { toneGen?.release() } catch (e: Exception) {} }
}