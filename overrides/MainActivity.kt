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
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

private const val TAG2 = "Speak-o"
private const val REQ_MIC2 = 200

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var edit: EditText
    private lateinit var bigBtn: Button
    private lateinit var status: TextView
    private lateinit var langBtn: Button

    private lateinit var vad: Vad
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null

    private val sampleRate = 16000
    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val format = AudioFormat.ENCODING_PCM_16BIT
    private val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val perms = arrayOf(Manifest.permission.RECORD_AUDIO)

    @Volatile private var recording = false
    private var sessionFile: File? = null
    private var wavFile: File? = null
    private var pcmOut: FileOutputStream? = null
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null

    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Settings.init(getExternalFilesDir(null) ?: filesDir)

        edit = findViewById(R.id.edit_text)
        bigBtn = findViewById(R.id.big_button)
        status = findViewById(R.id.status)
        langBtn = findViewById(R.id.lang_btn)

        bigBtn.isEnabled = false
        bigBtn.setOnClickListener { toggleRecord() }
        langBtn.text = Settings.asrLang.uppercase() + " v"
        langBtn.setOnClickListener { pickLanguage() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_menu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.act_send).setOnClickListener { doSend() }
        findViewById<Button>(R.id.act_translate).setOnClickListener { doTranslate() }
        findViewById<Button>(R.id.act_save).setOnClickListener { doSaveText() }

        tts = TextToSpeech(this, this)
        tts?.language = Locale("sk", "SK")

        status.text = "Inicializujem..."
        lifecycleScope.launch(Dispatchers.IO) {
            initVad()
            val ok = initAsr()
            withContext(Dispatchers.Main) {
                bigBtn.isEnabled = true
                status.text = if (ok) "Pripravene (${Settings.asrLang.uppercase()})" else "Chyba modelu"
            }
        }
    }

    private fun pickLanguage() {
        val langs = arrayOf("SK", "EN", "DE", "FR", "ES")
        AlertDialog.Builder(this).setTitle("Jazyk rozpoznavania").setItems(langs) { _, i ->
            Settings.asrLang = langs[i].lowercase(); Settings.save()
            langBtn.text = langs[i] + " v"
            status.text = "Nacitavam ${langs[i]}..."
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = initAsr()
                withContext(Dispatchers.Main) { status.text = if (ok) "Pripravene (${langs[i]})" else "Model ${langs[i]} chyba" }
            }
        }.show()
    }

    private fun showMenu() {
        val items = arrayOf("Kopirovat", "Zdielat", "Notifikacia", "Citat (TTS)", "Strihat nahravku", "Ulozit TXT", "Vymazat text", "Spat")
        AlertDialog.Builder(this).setTitle("Menu").setItems(items) { _, i -> when (i) {
            0 -> copyText(); 1 -> shareText(); 2 -> notifyText(edit.text.toString())
            3 -> speak(edit.text.toString()); 4 -> trimAudio(); 5 -> saveTxt(); 6 -> edit.setText(""); 7 -> doUndo()
        } }.show()
    }

    private fun toggleRecord() { if (!recording) startRec() else stopRec() }

    private fun startRec() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, perms, REQ_MIC2); return
        }
        val min = AudioRecord.getMinBufferSize(sampleRate, channel, format)
        audioRecord = AudioRecord(audioSource, sampleRate, channel, format, min * 2)
        audioRecord!!.startRecording()
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, DiktatService::class.java))
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionFile = File(diktatDir(), "diktat_$ts.txt")
        wavFile = File(diktatDir(), "zaznam_$ts.wav")
        try { pcmOut = FileOutputStream(wavFile) } catch (e: Exception) {}
        push(); vad.reset()
        recording = true; bigBtn.text = "STOP"
        if (Settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread { loop() }.start()
        status.text = "Nahravam..."
    }

    private fun stopRec() {
        recording = false
        try { stopService(Intent(this, DiktatService::class.java)) } catch (e: Exception) {}
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        try { pcmOut?.close() } catch (e: Exception) {}; pcmOut = null
        bigBtn.text = "NAHRAVAT"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Hotovo"
    }

    private fun loop() {
        val buf = ShortArray(512)
        val scope = CoroutineScope(Dispatchers.IO)
        try { writeWavHeader(wavFile, 0) } catch (e: Exception) {}
        var total = 0
        while (recording) {
            val n = audioRecord?.read(buf, 0, buf.size) ?: -1
            if (n > 0) {
                vad.acceptWaveform(FloatArray(n) { buf[it] / 32768f })
                try {
                    val b = ByteArray(n * 2)
                    for (i in 0 until n) { b[i*2] = (buf[i].toInt() and 0xFF).toByte(); b[i*2+1] = ((buf[i].toInt() shr 8) and 0xFF).toByte() }
                    pcmOut?.write(b); total += n
                } catch (e: Exception) {}
                while (!vad.empty()) {
                    val seg = vad.front()
                    val smp = seg.samples
                    scope.launch { val t = recognize(smp); if (t.isNotBlank()) main.post { appendText(t) } }
                    vad.pop()
                }
            }
        }
        try { pcmOut?.close(); pcmOut = null; writeWavHeader(wavFile, total) } catch (e: Exception) {}
        scope.cancel()
    }

    private fun writeWavHeader(f: File?, samples: Int) {
        if (f == null) return
        try {
            val raf = java.io.RandomAccessFile(f, "rw"); raf.seek(0)
            val len = samples * 2
            val o = java.io.ByteArrayOutputStream()
            fun l32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
            fun l16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            o.write("RIFF".toByteArray()); l32(36 + len); o.write("WAVE".toByteArray())
            o.write("fmt ".toByteArray()); l32(16); l16(1); l16(1); l32(sampleRate); l32(sampleRate*2); l16(2); l16(16)
            o.write("data".toByteArray()); l32(len)
            raf.write(o.toByteArray()); raf.close()
        } catch (e: Exception) { Log.e(TAG2, "wav: $e") }
    }

    private fun appendText(t: String) {
        val cur = edit.text.toString()
        edit.setText(if (cur.isBlank()) t else cur + "\n" + t)
        edit.setSelection(edit.text.length)
        try { sessionFile?.writeText(edit.text.toString()) } catch (e: Exception) {}
    }

    private fun initVad() {
        val c = getVadModelConfig(0)!!
        c.sileroVadModelConfig.threshold = 0.5f
        c.sileroVadModelConfig.minSilenceDuration = 0.4f
        vad = Vad(assetManager = application.assets, config = c)
    }

    /** Priečinok s modelmi: /sdcard/Download/Speak-o-models/<jazyk>/ */
    private fun modelsDir() = File("/sdcard/Download/Speak-o-models")

    /**
     * Nacita ASR model pre aktualny jazyk.
     * Priorita: externy model z /sdcard/Download/Speak-o-models/<lang>/
     *          ak nie je, pouzije zabudovany SK Parakeet (assets, typ 40).
     */
    private fun initAsr(): Boolean {
        val lang = Settings.asrLang.lowercase()
        val dir = File(modelsDir(), lang)
        try {
            // skusime externy model (parakeet/whisper) z priečinka
            val cfg = buildExternalConfig(dir) ?: getOfflineModelConfig(type = 40)!!
            recognizer = OfflineRecognizer(assetManager = application.assets, config = cfg)
            Log.i(TAG2, "ASR loaded: ${if (dir.exists() && hasModel(dir)) dir.absolutePath else "assets SK"}")
            return true
        } catch (e: Exception) {
            Log.e(TAG2, "initAsr err: $e")
            // fallback na zabudovany SK
            return try { recognizer = OfflineRecognizer(application.assets, getOfflineModelConfig(40)!!); true } catch (e2: Exception) { false }
        }
    }

    private fun hasModel(d: File): Boolean {
        if (!d.exists()) return false
        val names = d.list()?.toList() ?: return false
        return names.any { it.endsWith(".onnx") }
    }

    /** Vytvori OfflineModelConfig z priečinka (absolutne cesty pre Parakeet/Whisper). */
    private fun buildExternalConfig(d: File): OfflineModelConfig? {
        if (!hasModel(d)) return null
        val enc = d.listFiles()?.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }
        val dec = d.listFiles()?.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") }
        val joi = d.listFiles()?.firstOrNull { it.name.contains("joiner") && it.name.endsWith(".onnx") }
        val tok = d.listFiles()?.firstOrNull { it.name.contains("tokens") && it.name.endsWith(".txt") }
        if (enc == null || dec == null || tok == null) return null
        return if (joi != null) {
            // Parakeet / transducer
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = enc.absolutePath, decoder = dec.absolutePath, joiner = joi.absolutePath),
                tokens = tok.absolutePath, modelType = "nemo_transducer")
        } else {
            // Whisper
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = enc.absolutePath, decoder = dec.absolutePath, language = Settings.asrLang),
                tokens = tok.absolutePath, modelType = "whisper")
        }
    }

    private fun recognize(s: FloatArray): String {
        val r = recognizer ?: return ""
        return try {
            val st = r.createStream()
            st.acceptWaveform(s, sampleRate); r.decode(st)
            val res = r.getResult(st); st.release(); res.text
        } catch (e: Exception) { "" }
    }

    private fun doSend() {
        val t = edit.text.toString(); if (t.isBlank()) return
        status.text = "Posielam do Operitu..."
        Thread { val ok = ApiClient.sendToOperit(t); main.post { status.text = if (ok) "Odoslane OK" else "Chyba (Operit?)" } }.start()
    }

    private fun doTranslate() {
        val t = edit.text.toString(); if (t.isBlank()) return
        if (Settings.providers.isEmpty()) { toast("Pridaj API v nastaveniach"); return }
        val p = Settings.providers.getOrNull(Settings.translateProviderIndex) ?: return
        status.text = "Prekladam..."
        Thread {
            val out = ApiClient.translate(p, Settings.translateSystemPrompt + " Target language: " + Settings.translateTarget, t)
            main.post { if (out != null) { push(); edit.setText(out); status.text = "Prelozene" } else status.text = "Preklad zlyhal" }
        }.start()
    }

    private fun doSaveText() {
        try {
            val d = File("/sdcard/Documents/Speak-o"); d.mkdirs()
            val f = File(d, "speako_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
            f.writeText(edit.text.toString()); toast("Ulozene: ${f.name}")
        } catch (e: Exception) { toast("Chyba") }
    }
    private fun saveTxt() = doSaveText()

    private fun copyText() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("speako", edit.text.toString())); toast("Skopirovane")
    }

    private fun shareText() {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, edit.text.toString()) }
        startActivity(Intent.createChooser(i, "Zdielat"))
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        if (Settings.ttsMode == "android" && ttsReady) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "s"); return }
        val p = Settings.providers.getOrNull(Settings.ttsProviderIndex)
        if (p == null) { toast("Pridaj TTS poskytovatela"); return }
        Thread {
            val data = ApiClient.tts(p, text, Settings.ttsVoice)
            main.post {
                if (data != null) try {
                    val tmp = File(cacheDir, "tts.mp3"); tmp.writeBytes(data)
                    player?.release(); player = MediaPlayer().apply { setDataSource(tmp.path); prepare(); start() }
                } catch (e: Exception) { toast("Prehravanie zlyhalo") } else toast("TTS zlyhalo")
            }
        }.start()
    }

    private fun notifyText(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("speako", "Speak-o", NotificationManager.IMPORTANCE_HIGH))
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "speako") else Notification.Builder(this)
        nm.notify(1, b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Speak-o").setStyle(Notification.BigTextStyle().bigText(text)).setContentText(text.take(120)).build())
    }

    private fun trimAudio() {
        val src = wavFile
        if (src == null || !src.exists()) { toast("Ziadna nahravka"); return }
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; hint = "napr. 3.5-12" }
        AlertDialog.Builder(this).setTitle("Strihat (sekundy od-do)").setView(input)
            .setPositiveButton("Strihnut") { _, _ ->
                val parts = input.text.toString().split("-")
                if (parts.size == 2) doTrim(src, parts[0].trim().toDoubleOrNull() ?: 0.0, parts[1].trim().toDoubleOrNull() ?: 0.0)
            }.setNegativeButton("Zrusit", null).show()
    }

    private fun doTrim(src: File, from: Double, to: Double) {
        try {
            val bytes = src.readBytes(); val hdr = 44
            val s = hdr + (from * sampleRate * 2).toInt(); val e = (hdr + (to * sampleRate * 2).toInt()).coerceAtMost(bytes.size)
            if (s >= e) { toast("Zly rozsah"); return }
            val data = bytes.copyOfRange(s.coerceAtLeast(hdr), e)
            val out = File(diktatDir(), "ostrihane_" + System.currentTimeMillis() + ".wav")
            FileOutputStream(out).use { it.write(ByteArray(hdr)); it.write(data) }
            writeWavHeader(out, data.size / 2)
            toast("Ostrihane: ${out.name}")
        } catch (e: Exception) { toast("Strihanie zlyhalo") }
    }

    private fun push() { undo.addLast(edit.text.toString()); if (undo.size > 100) undo.removeFirst(); redo.clear() }
    private fun doUndo() { if (undo.isEmpty()) return; redo.addLast(edit.text.toString()); edit.setText(undo.removeLast()) }
    private fun diktatDir(): File { val d = File(getExternalFilesDir(null) ?: filesDir, "Diktat"); d.mkdirs(); return d }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onInit(status: Int) { ttsReady = status == TextToSpeech.SUCCESS }

    override fun onRequestPermissionsResult(r: Int, p: Array<String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == REQ_MIC2 && (g.isEmpty() || g[0] != PackageManager.PERMISSION_GRANTED)) finish()
    }

    override fun onDestroy() { super.onDestroy(); tts?.shutdown(); player?.release() }
}
