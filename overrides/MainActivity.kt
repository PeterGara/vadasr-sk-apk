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
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.SeekBar
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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

private const val TAG = "sherpa-onnx"
private const val REQ_MIC = 200
private const val MODE_TRANS = 0
private const val MODE_TRANSLATE = 1
private const val MODE_RECORDS = 2

class MainActivity : AppCompatActivity(), EngineHost {

    private lateinit var recordButton: Button
    private lateinit var editText: EditText
    private lateinit var statusView: TextView
    private lateinit var translateLabel: TextView
    private lateinit var translateText: EditText

    private lateinit var btnMode1: Button
    private lateinit var btnMode2: Button
    private lateinit var btnMode3: Button
    private lateinit var mainActions: View
    private lateinit var btnRow1: View
    private lateinit var btnRow2: View
    private lateinit var transActions: View
    private lateinit var recordsPage: View
    private lateinit var lvRecords: ListView

    private var mode = MODE_TRANS

    private lateinit var vad: Vad
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agcFx: AutomaticGainControl? = null
    private val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private var sessionFile: File? = null
    private var wavFile: File? = null
    private var pcmOut: FileOutputStream? = null
    private val undoStack = ArrayDeque<String>()

    @Volatile private var modelsReady = false
    private var initRunning = false
    @Volatile private var isRecording = false
    @Volatile private var wantRecording = false

    private var toneGen: ToneGenerator? = null
    private val ui = Handler(Looper.getMainLooper())
    private var translateJob: Job? = null
    private var player: MediaPlayer? = null

    private val buttonIds = listOf(R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4, R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8)
    private val buttonViews = ArrayList<Button>()
    private val recordFiles = ArrayList<File>()

    // ---------------- dynamicke tlacidla z layout.json ----------------
    private fun buildDynamicButtons() {
        try {
            val container = findViewById<android.widget.LinearLayout>(R.id.btn_row1)
            container.removeAllViews()
            val row1 = findViewById<android.widget.LinearLayout>(R.id.btn_row2)
            row1.removeAllViews()
            buttonViews.clear()
            // schovame obe povodne riadky, vytvorime vlastny kontajner
            container.visibility = View.VISIBLE
            row1.visibility = View.GONE

            var currentRow: android.widget.LinearLayout? = null
            var used = 0
            for (b in Layout.buttons) {
                if (currentRow == null || used + b.w > 4) {
                    val nr = android.widget.LinearLayout(this)
                    nr.orientation = android.widget.LinearLayout.HORIZONTAL
                    nr.layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    nr.setPadding(0, dp(3), 0, 0)
                    container.addView(nr)
                    currentRow = nr
                    used = 0
                }
                val btn = android.widget.Button(this)
                btn.text = b.text
                btn.isAllCaps = false
                btn.textSize = b.textSize.toFloat()
                btn.minWidth = 0
                btn.setPadding(dp(2), 0, dp(2), 0)
                val lp = android.widget.LinearLayout.LayoutParams(0, dp(b.h), b.w.toFloat())
                lp.marginStart = dp(3)
                btn.layoutParams = lp
                btn.background = shapeDrawable(b.shape, b.color)
                btn.setTextColor(Theme.c(b.textColor))
                btn.setOnClickListener { runAction(b.action) }
                currentRow?.addView(btn)
                buttonViews.add(btn)
                used += b.w
            }
        } catch (e: Exception) { Log.e(TAG, "buildDynamicButtons err: " + e) }
    }

    private fun shapeDrawable(shape: String, color: String): android.graphics.drawable.GradientDrawable {
        val c = try { android.graphics.Color.parseColor(color) } catch (e: Exception) { android.graphics.Color.DKGRAY }
        return when (shape) {
            "circle" -> android.graphics.drawable.GradientDrawable().apply { this.shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c) }
            "pill" -> android.graphics.drawable.GradientDrawable().apply { cornerRadius = 500f; setColor(c) }
            "square" -> android.graphics.drawable.GradientDrawable().apply { cornerRadius = 0f; setColor(c) }
            else -> android.graphics.drawable.GradientDrawable().apply { cornerRadius = 10 * resources.displayMetrics.density; setColor(c) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun parseButtons(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val f = buttonsFile()
            if (!f.exists()) f.writeText(
                "1|¶ Odst.|paragraph\n2|Uloz|save\n3|Kopir.|copy\n" +
                "4|Zdiel.|share\n5|Cas|time+append: \n6|Uloz TXT|save_public\n7|Notif.|notify\n8|Stop|stop\n", Charsets.UTF_8)
            f.readLines().forEach { line ->
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) return@forEach
                val p = s.split('|', limit = 3)
                if (p.size == 3) out.add(p[1].trim() to p[2].trim())
            }
        } catch (e: Exception) { Log.e(TAG, "parseButtons err: $e") }
        return out.sortedBy { 0 }
    }

    // ---------------- EngineHost ----------------
    override fun getText(): String = txt()
    override fun setText(s: String) { setTxt(s) }
    override fun action(name: String) {
        when (name) {
            "send_new", "send_all" -> sendToOperit()
            "del_veta" -> doDelVeta()
            "del_odstavec" -> doDelOdstavec()
            "clear" -> doClear()
            "undo" -> doUndo()
            "copy" -> doCopy()
            "save" -> doSavePublic()
            "share" -> doShare()
            "notify" -> notifyText(txt())
            "paste_app" -> doPasteToApp()
            "records" -> setMode(MODE_RECORDS)
            "translate" -> { setMode(MODE_TRANSLATE); doTranslateNow() }
            "stop" -> stopRecording()
            "start" -> startRecording()
            "trim_audio" -> pickAudioForTrim()
            "transcribe_file" -> pickAudioForTranscribe()
            "paste_diag" -> showPasteDiag()
        }
    }

    // ---------------- vzhľad z theme.txt (bez buildu) ----------------
    private fun applyTheme() {
        try {
            // pozadie na skutocny root (ConstraintLayout v activity_main), nie android.R.id.content
            val contentView = findViewById<android.view.ViewGroup>(android.R.id.content)
            for (i in 0 until contentView.childCount) {
                contentView.getChildAt(i).setBackgroundDrawable(Theme.background())
            }
            window.decorView.setBackgroundDrawable(Theme.background())

            statusView.setTextColor(Theme.c(Theme.textDim))
            editText.setTextColor(Theme.c(Theme.text))
            editText.setHintTextColor(Theme.c(Theme.hint))
            editText.background = Theme.rounded(Theme.card, Theme.radius)
            translateText.setTextColor(Theme.c(Theme.text))
            translateText.setHintTextColor(Theme.c(Theme.hint))
            translateText.background = Theme.rounded(Theme.cardAlt, Theme.radius)

            // velke mikrofonove tlacidlo
            val sz = dp(Theme.recordSize)
            recordButton.layoutParams = (recordButton.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = sz; height = sz
            }
            recordButton.background = Theme.ellipse(if (isRecording) Theme.danger else Theme.accent)

            btnMode1.background = Theme.rounded(if (mode == MODE_TRANS) Theme.accent else Theme.neutral, 22)
            btnMode2.background = Theme.rounded(if (mode == MODE_TRANSLATE) Theme.accent else Theme.neutral, 22)
            btnMode3.background = Theme.rounded(if (mode == MODE_RECORDS) Theme.accent else Theme.neutral, 22)
            btnMode1.setTextColor(Theme.c(Theme.text))
            btnMode2.setTextColor(Theme.c(Theme.text))
            btnMode3.setTextColor(Theme.c(Theme.text))

            findViewById<android.widget.Button>(R.id.btn_menu).background = Theme.rounded(Theme.neutral, 12)

            findViewById<android.widget.Button>(R.id.act_send).background = Theme.rounded(Theme.primary, 14)
            findViewById<android.widget.Button>(R.id.act_dellast).background = Theme.rounded(Theme.neutral, 14)
            findViewById<android.widget.Button>(R.id.act_clear).background = Theme.rounded(Theme.danger, 14)
            findViewById<android.widget.Button>(R.id.act_copy).background = Theme.rounded(Theme.neutral, 14)

            // dynamicke tlacidla
            buttonViews.forEach { it.background = Theme.rounded(Theme.neutral, 10); it.setTextColor(Theme.c(Theme.text)) }

            // ostatne tlacidla
            findViewById<android.widget.Button>(R.id.act_dellast).background = Theme.rounded(Theme.neutral, 14)
            findViewById<android.widget.Button>(R.id.act_send).background = Theme.rounded(Theme.primary, 14)
            findViewById<android.widget.Button>(R.id.act_clear).background = Theme.rounded(Theme.danger, 14)
            findViewById<android.widget.Button>(R.id.act_copy).background = Theme.rounded(Theme.neutral, 14)
            listOf(R.id.act_send, R.id.act_dellast, R.id.act_clear, R.id.act_copy).forEach {
                findViewById<android.widget.Button>(it).setTextColor(Theme.c(Theme.text))
            }
            // prekladove tlacidla
            findViewById<android.widget.Button>(R.id.btn_trans_now).background = Theme.rounded(Theme.primary, 14)
            findViewById<android.widget.Button>(R.id.btn_trans_copy).background = Theme.rounded(Theme.neutral, 14)
            findViewById<android.widget.Button>(R.id.btn_trans_send).background = Theme.rounded(Theme.primary, 14)
            listOf(R.id.btn_trans_now, R.id.btn_trans_copy, R.id.btn_trans_send).forEach {
                findViewById<android.widget.Button>(it).setTextColor(Theme.c(Theme.text))
            }
            // nahravky
            findViewById<android.widget.Button>(R.id.rec_pick).background = Theme.rounded(Theme.primary, 14)
            findViewById<android.widget.Button>(R.id.rec_refresh).background = Theme.rounded(Theme.neutral, 14)
            // status
            statusView.background = Theme.rounded(Theme.card, Theme.radius)
            // nahravanie - text
            recordButton.setTextColor(Theme.c(Theme.text))
        } catch (e: Exception) { Log.e(TAG, "applyTheme err: $e") }
    }

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

    override fun beep(times: Int) {
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
        Log.i(TAG, "STEP 1 setContentView start")
        setContentView(R.layout.activity_main)
        Log.i(TAG, "STEP 2 setContentView OK")
        Settings.init(getExternalFilesDir(null) ?: filesDir)
        Log.i(TAG, "STEP 3 Settings.init OK")
        Theme.init(getExternalFilesDir(null) ?: filesDir)
        Log.i(TAG, "STEP 4 Theme.init OK")
        Layout.init(getExternalFilesDir(null) ?: filesDir)
        Log.i(TAG, "STEP 5 Layout.init OK")
        Menu.init(getExternalFilesDir(null) ?: filesDir)
        Log.i(TAG, "STEP 6 Menu.init OK")

        recordButton = findViewById(R.id.record_button)
        editText = findViewById(R.id.my_text)
        statusView = findViewById(R.id.status_view)
        translateLabel = findViewById(R.id.translate_label)
        translateText = findViewById(R.id.translate_text)
        btnMode1 = findViewById(R.id.btn_mode1)
        btnMode2 = findViewById(R.id.btn_mode2)
        btnMode3 = findViewById(R.id.btn_mode3)
        mainActions = findViewById(R.id.main_actions)
        btnRow1 = findViewById(R.id.btn_row1)
        btnRow2 = findViewById(R.id.btn_row2)
        transActions = findViewById(R.id.trans_actions)
        recordsPage = findViewById(R.id.records_page)
        lvRecords = findViewById(R.id.lv_records)

        recordButton.setOnClickListener { toggleRecord() }
        findViewById<Button>(R.id.btn_menu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.act_send).setOnClickListener { sendToOperit() }
        findViewById<Button>(R.id.act_dellast).setOnClickListener { doDelVeta() }
        findViewById<Button>(R.id.act_clear).setOnClickListener { doClear() }
        findViewById<Button>(R.id.act_copy).setOnClickListener { doCopy() }

        btnMode1.setOnClickListener { setMode(MODE_TRANS) }
        btnMode2.setOnClickListener { setMode(MODE_TRANSLATE) }
        btnMode3.setOnClickListener { setMode(MODE_RECORDS) }
        findViewById<Button>(R.id.btn_trans_now).setOnClickListener { doTranslateNow() }
        findViewById<Button>(R.id.btn_trans_copy).setOnClickListener { doCopyTranslate() }
        findViewById<Button>(R.id.btn_trans_send).setOnClickListener { doSendTranslate() }
        findViewById<Button>(R.id.rec_pick).setOnClickListener { pickAudioForTranscribe() }
        findViewById<Button>(R.id.rec_refresh).setOnClickListener { loadRecords() }

        lvRecords.setOnItemClickListener { _, _, pos, _ ->
            val f = recordFiles.getOrNull(pos) ?: return@setOnItemClickListener
            showRecordActions(f)
        }

        buttonViews.clear()
        buildDynamicButtons()
        loadCommands()
        loadButtons()
        applyTheme()
        setMode(MODE_TRANS)
        startWatchdog()

        editText.setText("Inicializujem modely...")
        lifecycleScope.launch(Dispatchers.IO) { initModelsOnce() }

        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { scheduleLiveTranslate() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ---------------- rezimy ----------------
    private fun setMode(m: Int) {
        mode = m
        val trans = m == MODE_TRANS
        val translate = m == MODE_TRANSLATE
        val records = m == MODE_RECORDS

        recordButton.visibility = if (trans) View.VISIBLE else View.GONE
        mainActions.visibility = if (trans) View.VISIBLE else View.GONE
        btnRow1.visibility = if (trans) View.VISIBLE else View.GONE
        btnRow2.visibility = if (trans) View.VISIBLE else View.GONE
        editText.visibility = if (records) View.GONE else View.VISIBLE
        translateLabel.visibility = if (translate) View.VISIBLE else View.GONE
        translateText.visibility = if (translate) View.VISIBLE else View.GONE
        transActions.visibility = if (translate) View.VISIBLE else View.GONE
        recordsPage.visibility = if (records) View.VISIBLE else View.GONE

        styleModeBtn(btnMode1, trans)
        styleModeBtn(btnMode2, translate)
        styleModeBtn(btnMode3, records)

        if (records) loadRecords() else updateStatus()
    }

    private fun styleModeBtn(b: Button, on: Boolean) {
        b.background = Theme.rounded(if (on) Theme.accent else Theme.neutral, 22)
        b.setTextColor(Theme.c(Theme.text))
        b.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    // ---------------- preklad ----------------
    private fun scheduleLiveTranslate() {
        if (!Settings.translateEnabled || mode != MODE_TRANSLATE) return
        translateJob?.cancel()
        translateJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(1500)
            val src = editText.text.toString()
            if (src.isBlank()) return@launch
            val out = translateText(src)
            if (out != null) withContext(Dispatchers.Main) { translateText.setText(out) }
        }
    }

    private suspend fun translateText(src: String): String? =
        if (Settings.translateEngine == "mlkit")
            Translate.translateMlkit(src, Settings.asrLang, Settings.translateTarget)
        else Translate.translateGguf(src, Settings.translateTarget)

    private fun doTranslateNow() {
        val src = editText.text.toString()
        if (src.isBlank()) { toast("Nic na preklad"); return }
        statusView.text = "Prekladam..."
        lifecycleScope.launch(Dispatchers.IO) {
            val out = translateText(src)
            withContext(Dispatchers.Main) {
                if (out != null) { translateText.setText(out); statusView.text = "Prelozene" }
                else statusView.text = "Preklad zlyhal"
            }
        }
    }

    private fun doCopyTranslate() {
        val t = translateText.text.toString()
        if (t.isBlank()) { toast("Nic na kopirovanie"); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("speako", t)); beepFor("send"); toast("Preklad skopirovany")
    }

    private fun doSendTranslate() {
        val t = translateText.text.toString()
        if (t.isBlank()) { toast("Nic na poslanie"); return }
        if (!Settings.operitEnabled || Settings.operitToken.isBlank()) { toast("Operit nie je nastaveny"); return }
        thread(true) {
            val ok = httpSend(t)
            runOnUiThread { beepFor(if (ok) "send" else "err"); toast(if (ok) "Preklad poslany" else "Odoslanie zlyhalo") }
        }
    }

    // ---------------- menu ----------------
    private fun showMenu() {
        Menu.load()
        val labels = Menu.items.map { it.text }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Menu").setItems(labels) { _, i ->
            val it = Menu.items.getOrNull(i) ?: return@setItems
            runMenuAction(it.action)
        }.show()
    }

    /** Vykona akciu z menu.json */
    private fun runMenuAction(action: String) {
        val a = action.trim()
        try {
            when {
                a == "screen:settings" -> startActivity(Intent(this, SettingsActivity::class.java))
                a == "screen:theme" -> startActivity(Intent(this, ThemeActivity::class.java))
                a == "screen:models" -> startActivity(Intent(this, ModelsActivity::class.java))
                a == "screen:buttons" -> startActivity(Intent(this, ButtonsActivity::class.java))
                a == "screen:menu" -> startActivity(Intent(this, MenuActivity::class.java))
                a == "screen:llama" -> startActivity(Intent(this, LlamaActivity::class.java))
                a.startsWith("action:") -> runAction(a.substringAfter("action:"))
                a.startsWith("url:") -> {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(a.substringAfter("url:"))))
                }
                else -> runAction(a)
            }
        } catch (e: Exception) { Log.e(TAG, "menu err: " + e); toast("Akcia zlyhala: " + a) }
    }

    // ---------------- univerzalny prepis ----------------
    private fun pickAudioForTranscribe() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" }
            startActivityForResult(Intent.createChooser(i, "Vyber audio"), 1001)
        } catch (e: Exception) { toast("Neda sa otvorit vyber suboru") }
    }

    private fun pickAudioForTrim() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" }
            startActivityForResult(Intent.createChooser(i, "Vyber audio"), 1002)
        } catch (e: Exception) { toast("Neda sa otvorit vyber suboru") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 1001 || requestCode == 1002) {
            lifecycleScope.launch(Dispatchers.IO) {
                val f = copyUriToCache(uri)
                withContext(Dispatchers.Main) {
                    if (f == null) toast("Neda sa precitat subor") else askWhatToDo(f)
                }
            }
        }
    }

    /** Po vybere suboru: prepisat cely, alebo najprv orezat. */
    private fun askWhatToDo(f: File) {
        val items = arrayOf("Prepisat cely subor", "Orezat (a potom prepisat)")
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> transcribePickedFile(f)
                    1 -> trimDialog(f)
                }
            }
            .setNegativeButton("Zrusit", null)
            .show()
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val f = File(cacheDir, "audio_pick_" + System.currentTimeMillis())
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(f).use { out -> input.copyTo(out) }
            }
            f
        } catch (e: Exception) { Log.e(TAG, "copy err: $e"); null }
    }

    private fun transcribeUri(uri: Uri) {
        statusView.text = "Prepisujem subor..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tmp = copyUriToCache(uri) ?: return@launch
                val text = transcribeFile(tmp)
                withContext(Dispatchers.Main) {
                    setMode(MODE_TRANS)
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

    /** Prepis hocijakeho audio suboru (mp3/m4a/ogg/opus/flac/wav) cez ASR model.
     *  Dekoduje sa po blokoch, aby nezdochnla pamat pri dlhych nahravkach. */
    private fun transcribeFile(f: File): String {
        val rec = recognizer ?: return ""
        val shorts = AudioDecoder.decodeToMono16kShorts(f) ?: return ""
        if (shorts.isEmpty()) return ""
        val block = 30 * sampleRateInHz
        val sb = StringBuilder()
        var i = 0
        while (i < shorts.size) {
            val end = minOf(i + block, shorts.size)
            val floats = FloatArray(end - i) { shorts[i + it] / 32768.0f }
            val t = runRecognizer(rec, floats)
            if (t.isNotBlank()) { if (sb.isNotEmpty()) sb.append("\n"); sb.append(t.trim()) }
            i = end
        }
        return sb.toString()
    }

    private fun runRecognizer(rec: OfflineRecognizer, samples: FloatArray): String {
        return try {
            val s = rec.createStream()
            s.acceptWaveform(samples, sampleRateInHz)
            rec.decode(s)
            val r = rec.getResult(s); s.release(); r.text
        } catch (e: Exception) { "" }
    }

    // ---------------- OKNO NA OREZAVANIE ----------------
    private fun trimDialog(src: File) {
        statusView.text = "Nacitavam audio..."
        lifecycleScope.launch(Dispatchers.IO) {
            val samples = AudioDecoder.decodeToMono16kShorts(src)
            withContext(Dispatchers.Main) {
                if (samples == null || samples.isEmpty()) { statusView.text = "Audio sa neda precitat"; toast("Neda sa dekodovat") }
                else showTrimDialogUi(src, samples)
            }
        }
    }

    private fun showTrimDialogUi(src: File, samples: ShortArray) {
                val dur = samples.size / sampleRateInHz.toFloat()
                statusView.text = String.format(Locale.US, "Dlzka: %.1f s", dur)

                val v = layoutInflater.inflate(R.layout.dialog_trim, null)
                val seekFrom = v.findViewById<SeekBar>(R.id.seek_from)
                val seekTo = v.findViewById<SeekBar>(R.id.seek_to)
                val tvRange = v.findViewById<TextView>(R.id.tv_range)
                val edFrom = v.findViewById<EditText>(R.id.ed_from)
                val edTo = v.findViewById<EditText>(R.id.ed_to)
                val btnPlay = v.findViewById<Button>(R.id.btn_play)

                seekFrom.max = 1000; seekTo.max = 1000
                seekTo.progress = 1000
                edTo.setText(String.format(Locale.US, "%.1f", dur))

                fun fmt(ms: Int): String {
                    val s = ms / 1000
                    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
                }
                fun update() {
                    val a = dur * seekFrom.progress / 1000f
                    val b = dur * seekTo.progress / 1000f
                    if (seekFrom.progress > seekTo.progress) seekTo.progress = seekFrom.progress
                    tvRange.text = String.format(Locale.US, "%.1f s  -  %.1f s", a, b)
                    edFrom.setText(String.format(Locale.US, "%.1f", a))
                    edTo.setText(String.format(Locale.US, "%.1f", b))
                }
                seekFrom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { update() }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                seekTo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { update() }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })

                btnPlay.setOnClickListener {
                    if (player?.isPlaying == true) { stopPlayer(); btnPlay.text = "Prehrat" }
                    else {
                        val from = edFrom.text.toString().toDoubleOrNull() ?: 0.0
                        val to = edTo.text.toString().toDoubleOrNull() ?: dur.toDouble()
                        playRange(src, from, to) {
                            ui.post { btnPlay.text = "Prehrat" }
                        }
                        btnPlay.text = "Stop"
                    }
                }

                val dlg = AlertDialog.Builder(this)
                    .setTitle("Orezat nahravku")
                    .setView(v)
                    .setPositiveButton("Orezat", null)
                    .setNeutralButton("Orezat + prepisat", null)
                    .setNegativeButton("Zrusit") { _, _ -> stopPlayer() }
                    .create()
                dlg.setOnShowListener {
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val a = edFrom.text.toString().toDoubleOrNull() ?: 0.0
                        val b = edTo.text.toString().toDoubleOrNull() ?: 0.0
                        if (b <= a) { toast("Do musi byt vacsie ako Od"); return@setOnClickListener }
                        stopPlayer(); dlg.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            val out = exportRange(samples, a, b)
                            withContext(Dispatchers.Main) { toast(if (out != null) "Orezane: ${out.name}" else "Orezanie zlyhalo") }
                        }
                    }
                    dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val a = edFrom.text.toString().toDoubleOrNull() ?: 0.0
                        val b = edTo.text.toString().toDoubleOrNull() ?: 0.0
                        if (b <= a) { toast("Do musi byt vacsie ako Od"); return@setOnClickListener }
                        stopPlayer(); dlg.dismiss()
                        statusView.text = "Prepisujem orezane..."
                        lifecycleScope.launch(Dispatchers.IO) {
                            val out = exportRange(samples, a, b) ?: return@launch
                            val text = transcribeFile(out)
                            withContext(Dispatchers.Main) {
                                setMode(MODE_TRANS)
                                if (text.isBlank()) statusView.text = "Nic sa nepodarilo prepisat"
                                else {
                                    val cur = txt()
                                    setTxt(if (cur.isBlank()) text else cur + "\n" + text)
                                    statusView.text = "Prepisane"; persist(); beepFor("send")
                                }
                            }
                        }
                    }
                }
                dlg.show()
    }

    private fun exportRange(all: ShortArray, from: Double, to: Double): File? {
        return try {
            val s = (from * sampleRateInHz).toInt().coerceIn(0, all.size)
            val e = (to * sampleRateInHz).toInt().coerceIn(0, all.size)
            if (e <= s) return null
            val part = all.copyOfRange(s, e)
            val out = File(diktatDir(), "orezane_" + System.currentTimeMillis() + ".wav")
            AudioDecoder.writeWav16k(out, part)
            out
        } catch (e: Exception) { Log.e(TAG, "export err: $e"); null }
    }

    private fun playRange(src: File, from: Double, to: Double, onDone: () -> Unit) {
        try {
            stopPlayer()
            player = MediaPlayer().apply {
                setDataSource(src.absolutePath)
                setOnCompletionListener { stopPlayer(); onDone() }
                prepare()
                seekTo((from * 1000).toInt())
                start()
            }
            ui.postDelayed({
                try { if (player?.isPlaying == true && player!!.currentPosition >= (to * 1000).toInt()) { stopPlayer(); onDone() } } catch (e: Exception) {}
            }, (to - from).toLong() * 1000 + 200)
        } catch (e: Exception) { Log.e(TAG, "play err: $e"); toast("Prehranie zlyhalo") }
    }

    private fun stopPlayer() { try { player?.stop() } catch (e: Exception) {}; try { player?.release() } catch (e: Exception) {}; player = null }

    private fun playWhole(f: File) {
        try {
            stopPlayer()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { stopPlayer() }
                prepare(); start()
            }
            toast("Prehravam...")
        } catch (e: Exception) { Log.e(TAG, "playWhole err: $e"); toast("Prehranie zlyhalo") }
    }

    // ---------------- zoznam nahravok ----------------
    private fun loadRecords() {
        try {
            val exts = setOf("wav", "mp3", "m4a", "aac", "ogg", "opus", "flac", "3gp", "amr")
            val files = diktatDir().listFiles { f -> f.isFile && f.extension.lowercase() in exts } ?: arrayOf()
            recordFiles.clear()
            recordFiles.addAll(files.sortedByDescending { it.lastModified() })
            val names = recordFiles.map { f ->
                val kb = f.length() / 1024
                val dt = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(f.lastModified()))
                "$dt   ${f.name}   (${kb} kB)"
            }
            runOnUiThread {
                lvRecords.adapter = ArrayAdapter(this, R.layout.records_row, names)
            }
        } catch (e: Exception) { Log.e(TAG, "loadRecords err: $e") }
    }

    private fun showRecordActions(f: File) {
        val items = arrayOf("Prehrat", "Prepisat", "Orezat", "Zdielat", "Zmazat")
        AlertDialog.Builder(this).setTitle(f.name).setItems(items) { _, i -> when (i) {
            0 -> playWhole(f)
            1 -> transcribePickedFile(f)
            2 -> trimDialog(f)
            3 -> shareFile(f)
            4 -> {
                f.delete(); loadRecords(); toast("Zmazane")
            }
        } }.show()
    }

    private fun transcribePickedFile(f: File) {
        statusView.text = "Prepisujem..."
        lifecycleScope.launch(Dispatchers.IO) {
            val text = transcribeFile(f)
            withContext(Dispatchers.Main) {
                setMode(MODE_TRANS)
                if (text.isBlank()) statusView.text = "Nic sa nepodarilo prepisat"
                else {
                    val cur = txt()
                    setTxt(if (cur.isBlank()) text else cur + "\n" + text)
                    statusView.text = "Prepisane"; persist(); beepFor("send")
                }
            }
        }
    }

    private fun shareFile(f: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply { type = "audio/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(i, "Zdielat"))
        } catch (e: Exception) { toast("Zdielanie zlyhalo") }
    }

    // ---------------- pomocne ----------------
    private fun txt(): String = editText.text.toString()
    private fun setTxt(s: String) { editText.setText(s); editText.setSelection(s.length) }
    private fun push() { undoStack.addLast(txt()); if (undoStack.size > 100) undoStack.removeFirst() }
    private fun doUndo() { if (undoStack.isEmpty()) return; setTxt(undoStack.removeLast()); beepFor("del_veta") }

    private fun doDelVeta() {
        val s = txt().trimEnd('\n')
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
        if (mode == MODE_RECORDS) return
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

    private fun showPasteDiag() {
        val svc = SpeakAccessibilityService.instance
        val txt = if (svc == null) "Accessibility sluzba NIE JE zapnuta!\n\nZapni: Nastavenia -> Zjednodusenie ovladania -> Diktat -> ZAPNUT"
                  else svc.describeActiveWindow()
        AlertDialog.Builder(this)
            .setTitle("Diagnostika vkladania")
            .setMessage(txt)
            .setPositiveButton("Zavrieť", null)
            .show()
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

    private fun runAction(action: String) {
        // najprv skus ENGINE (podporuje retazenie cez + a vsetky primitiva)
        if (Engine.run(action, this)) return
        Log.w(TAG, "neznama akcia: " + action)
    }

    private fun runActionOld(action: String) = when (action) {
        "copy" -> doCopy()
        "save" -> doSavePublic()
        "paste_app" -> doPasteToApp()
        "notify" -> notifyText(txt())
        "del_veta" -> doDelVeta()
        "del_odstavec" -> doDelOdstavec()
        "clear" -> doClear()
        "send_new", "send_all" -> sendToOperit()
        "translate" -> { setMode(MODE_TRANSLATE); doTranslateNow() }
        "transcribe_file" -> pickAudioForTranscribe()
        "trim_audio" -> pickAudioForTrim()
        "records" -> setMode(MODE_RECORDS)
        "stop", "stop_record", "stop_nahravanie" -> stopRecording()
        "start", "start_record" -> startRecording()
        "paragraph" -> { val cur = txt(); setTxt(if (cur.isBlank()) cur else cur + "\n") }
        "undo" -> doUndo()
        "time" -> toast(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
        "noop" -> {}
        "beep" -> beep(1)
        else -> {
            if (action.startsWith("beep:")) { beep(action.substringAfter("beep:").toIntOrNull() ?: 1) }
            else if (action.startsWith("append:")) { val cur = txt(); setTxt(cur + action.substringAfter("append:")) }
            else { /* neznama */ }
        }
    }

    // ---------------- hlasove prikazy (commands.txt) ----------------
    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            val r = when (ch) {
                'á','à','â','ä' -> 'a'; 'é','è','ê','ë' -> 'e'; 'í','ì','î','ï' -> 'i'
                'ó','ò','ô','ö' -> 'o'; 'ú','ù','û','ü' -> 'u'; 'ý','ÿ' -> 'y'
                'č' -> 'c'; 'š' -> 's'; 'ž' -> 'z'; 'ť' -> 't'; 'ď' -> 'd'; 'ň' -> 'n'; 'ľ','ĺ' -> 'l'
                'ř' -> 'r'; 'ě' -> 'e'; 'ů' -> 'u'
                else -> ch
            }
            if (r.isLetterOrDigit() || r == ' ') sb.append(r)
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }

    /** Skusi najst hlasovy prikaz na konci rozpoznaneho textu. Vrati akciu alebo null. */
    private fun matchVoiceCommand(text: String): String? {
        val f = commandsFile()
        if (!f.exists()) return null
        val t = norm(text)
        if (t.isBlank()) return null
        var best: String? = null
        var bestLen = 0
        try {
            f.readLines().forEach { line ->
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) return@forEach
                val p = s.split('|', limit = 2)
                if (p.size != 2) return@forEach
                val phrase = norm(p[0])
                val action = p[1].trim()
                if (phrase.isBlank()) return@forEach
                // musi byt na konci textu (moze mat bodku za sebou)
                val tt = t.trimEnd('.', '!', '?', ',', ' ')
                if (tt == phrase || tt.endsWith(" " + phrase)) {
                    if (phrase.length > bestLen) { bestLen = phrase.length; best = action }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "cmd err: $e") }
        return best
    }

    /** Odstrani prikaz z konca textu (aby sa neukladal do prepisu). */
    private fun stripCommand(text: String, action: String): String {
        val f = commandsFile()
        if (!f.exists()) return text
        var out = text
        try {
            f.readLines().forEach { line ->
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) return@forEach
                val p = s.split('|', limit = 2)
                if (p.size != 2) return@forEach
                if (p[1].trim() != action) return@forEach
                val phrase = p[0].trim()
                val re = Regex("(?i)\\s*" + Regex.escape(phrase) + "\\s*[.!?,]*\\s*$")
                out = out.replace(re, "")
            }
        } catch (e: Exception) {}
        return out.trimEnd()
    }

    private fun commandsFile(): File = File(getExternalFilesDir(null) ?: filesDir, "commands.txt")

    private fun loadCommands() {
        try {
            val f = commandsFile()
            if (!f.exists()) f.writeText(
                "# hlasove prikazy: fraza | akcia (bez diakritiky)\n" +
                "zastav nahravanie|stop\n" +
                "stopni nahravanie|stop\n" +
                "stop nahravanie|stop\n" +
                "zastav to|stop\n" +
                "stopni to|stop\n" +
                "ukonci nahravanie|stop\n" +
                "skonci nahravanie|stop\n" +
                "stop|stop\n" +
                "zmaz vetu|del_veta\n" +
                "zmaz odstavec|del_odstavec\n" +
                "vymaz vsetko|clear\n" +
                "posli|send_new\n" +
                "posli do operitu|send_new\n" +
                "skopiruj|copy\n" +
                "uloz|save\n" +
                "vloz do appky|paste_app\n" +
                "novy odstavec|paragraph\n" +
                "spat|undo\n", Charsets.UTF_8)
        } catch (e: Exception) {}
    }

    private fun startRecording() {
        if (!isRecording) toggleRecord()
    }

    private fun stopRecording() {
        if (isRecording) {
            toggleRecord()
            beepFor("stop")
            toast("Nahravanie zastavene")
        }
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
            setupAudioEffects()
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
            releaseAudioEffects()
            try { pcmOut?.close() } catch (e: Exception) {}; pcmOut = null
            recordButton.setText("NAHRAVAT")
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            beepFor("stop"); persist()
        }
        updateStatus()
    }

    private fun setupAudioEffects() {
        try {
            val sid = audioRecord?.audioSessionId ?: return
            if (Settings.noiseSuppress && NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sid)?.apply { enabled = true }
            }
            if (Settings.agc && AutomaticGainControl.isAvailable()) {
                agcFx = AutomaticGainControl.create(sid)?.apply { enabled = true }
            }
        } catch (e: Exception) { Log.e(TAG, "fx err: $e") }
    }

    private fun releaseAudioEffects() {
        try { noiseSuppressor?.release() } catch (e: Exception) {}; noiseSuppressor = null
        try { agcFx?.release() } catch (e: Exception) {}; agcFx = null
    }

    private fun startWatchdog() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (wantRecording && !isRecording && recordButton.isEnabled && recordButton.visibility == View.VISIBLE) toggleRecord()
                ui.postDelayed(this, 5000)
            }
        }, 5000)
    }

    /** Inicializuje sherpa-onnx PRESNE RAZ - nativna kniznica nie je thread-safe. */
    @Synchronized
    private fun initModelsOnce() {
        if (modelsReady || initRunning) { Log.i(TAG, "initModels: uz bezi/hotovo"); return }
        initRunning = true
        Log.i(TAG, "initModels: START")
        try {
            try { initVadModel(); Log.i(TAG, "initModels: Vad OK") }
            catch (e: Throwable) { Log.e(TAG, "initModels: Vad ERR " + e) }
            try { initAsr(); Log.i(TAG, "initModels: Asr OK") }
            catch (e: Throwable) { Log.e(TAG, "initModels: Asr ERR " + e) }
            modelsReady = true
        } finally { initRunning = false }
        Log.i(TAG, "initModels: DONE")
        runOnUiThread {
            try {
                recordButton.isEnabled = true
                if (editText.text.toString().startsWith("Inicializujem")) editText.setText("")
                updateStatus()
                if (recognizer == null) checkModelOrOfferDownload()
            } catch (e: Exception) { }
        }
    }

    private fun initVadModel() {
        val c = getVadModelConfig(0)!!
        c.sileroVadModelConfig.threshold = Settings.vadThreshold
        c.sileroVadModelConfig.minSilenceDuration = Settings.minSilence
        vad = Vad(assetManager = application.assets, config = c)
    }

    /** Nacita ASR: SK Parakeet (assets) alebo externy model z asrModelDir/<lang>/ */
    /** Nacita ASR model z externeho priecinka. */
    /** Skopiruje model z Download do internej pamate appky (FUSE problem). */
    private fun ensureModelInternal(lang: String): File {
        val internal = File(filesDir, "models/asr/" + lang)
        internal.mkdirs()
            val allPresent = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
                .all { f -> File(internal, f).let { it.exists() && it.length() > 0L } }
            if (allPresent) return internal

        // zdroje: najprv app-specific external (tam ma appka pristup), potom Download
        val ext = getExternalFilesDir(null) ?: filesDir
        val sources = listOf(
            File(ext, "models/asr/" + lang),
            File(ext, "models/" + lang),
            File("/sdcard/Android/data/com.k2fsa.sherpa.onnx/files/models/asr/" + lang),
            File("/sdcard/Download/Speak-o/models/asr/" + lang),
            File("/sdcard/Speak-o/models/asr/" + lang)
        )
        for (src in sources) {
            if (!src.exists()) continue
            val files = src.listFiles() ?: continue
            val e = files.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") } ?: continue
            val d = files.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") } ?: continue
            val j = files.firstOrNull { it.name.contains("joiner") && it.name.endsWith(".onnx") } ?: continue
            val t = files.firstOrNull { it.name.contains("tokens") && it.name.endsWith(".txt") } ?: continue
            Log.i(TAG, "ensureModel: kopirujem z " + src.absolutePath)
            try {
                e.copyTo(File(internal, "encoder.int8.onnx"), overwrite = true)
                Log.i(TAG, "ensureModel: encoder OK")
                d.copyTo(File(internal, "decoder.int8.onnx"), overwrite = true)
                j.copyTo(File(internal, "joiner.int8.onnx"), overwrite = true)
                t.copyTo(File(internal, "tokens.txt"), overwrite = true)
                Log.i(TAG, "ensureModel: vsetko skopirovane")
                return internal
            } catch (ex: Exception) {
                Log.e(TAG, "ensureModel: kopirovanie zlyhalo " + ex)
            }
        }
        Log.w(TAG, "ensureModel: ziaden zdroj nenajdeny pre " + lang)
        return internal
    }

    /** Nacita ASR model z internej pamate appky (skopiruje z Download ak treba). */
    private fun initAsr() {
        Log.i(TAG, "initAsr: begin")
        try {
            val lang = Settings.asrLang.lowercase()
            val d = ensureModelInternal(lang)
            val enc = d.listFiles()?.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }
            val dec = d.listFiles()?.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") }
            val joi = d.listFiles()?.firstOrNull { it.name.contains("joiner") && it.name.endsWith(".onnx") }
            val tok = d.listFiles()?.firstOrNull { it.name.contains("tokens") && it.name.endsWith(".txt") }
            if (enc == null || dec == null || joi == null || tok == null) {
                // POZOR: ziadny assets fallback - model v APK uz NIE JE (5.0.0+),
                // getOfflineModelConfig(40) by ukazoval na neexistujuce subory -> SIGSEGV
                recognizer = null
                Log.w(TAG, "initAsr: model NEEXISTUJE v " + d.absolutePath)
                return
            }
            Log.i(TAG, "initAsr: subory OK, nacitavam " + enc.name)
            val mc = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                    encoder = enc.absolutePath, decoder = dec.absolutePath, joiner = joi.absolutePath),
                tokens = tok.absolutePath, modelType = "nemo_transducer")
            recognizer = OfflineRecognizer(assetManager = null,
                OfflineRecognizerConfig(featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80), modelConfig = mc))
            Log.i(TAG, "initAsr: MODEL LOADED " + d.absolutePath)
        } catch (e: Throwable) {
            Log.e(TAG, "initAsr: ERR " + e)
            recognizer = null
        }
    }

    private fun modelExists(lang: String): Boolean {
        val d = File(Settings.asrModelDir, lang.lowercase())
        val enc = d.listFiles()?.any { it.name.contains("encoder") && it.name.endsWith(".onnx") } ?: false
        val tok = d.listFiles()?.any { it.name.contains("tokens") && it.name.endsWith(".txt") } ?: false
        return enc && tok
    }

    private fun checkModelOrOfferDownload() {
        val lang = Settings.asrLang.lowercase()
        if (modelExists(lang)) return
        val dir = File(Settings.asrModelDir, lang).absolutePath
        val msg = "Model pre " + lang.uppercase() + " chyba.\n\nPriecinok:\n" + dir +
            "\n\nVloz tam: encoder*.onnx, decoder*.onnx, joiner*.onnx, tokens.txt"
        AlertDialog.Builder(this)
            .setTitle("Model chyba")
            .setMessage(msg)
            .setPositiveButton("Rozumiem", null)
            .show()
    }

    private fun processSamples() {
        val buffer = ShortArray(512)
        val scope = CoroutineScope(Dispatchers.IO)
        try { pcmOut?.let { DataOutputStream(it).also { d -> AudioDecoder.writeWavHeader(d, 0) } } } catch (e: Exception) {}
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
                        val t = runRecognizer2(seg.samples)
                        if (t.isNotBlank()) {
                            val cmd = matchVoiceCommand(t)
                            if (cmd != null) {
                                val clean = stripCommand(t, cmd)
                                runOnUiThread {
                                    if (clean.isNotBlank()) {
                                        val cur = txt()
                                        setTxt(if (cur.isBlank()) clean else cur + "\n" + clean)
                                    }
                                    updateStatus(); persist()
                                    runAction(cmd)
                                }
                            } else {
                                runOnUiThread {
                                    val cur = txt()
                                    setTxt(if (cur.isBlank()) t else cur + "\n" + t)
                                    updateStatus(); persist()
                                }
                            }
                        }
                    }
                    vad.pop()
                }
            }
        }
        scope.cancel()
    }

    private fun runRecognizer2(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        return runRecognizer(rec, samples)
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
        Theme.init(getExternalFilesDir(null) ?: filesDir)
        Log.i(TAG, "RESUME 1 start")
        Theme.load(); Layout.load()
        Log.i(TAG, "RESUME 2 Theme+Layout loaded")
        Log.i(TAG, "RESUME 3 before buildDynamicButtons")
        buildDynamicButtons()
        Log.i(TAG, "RESUME 4 buttons OK")
        applyTheme()
        Log.i(TAG, "RESUME 5 applyTheme OK")
        setMode(mode)
        Log.i(TAG, "RESUME 6 setMode OK")
        loadCommands(); loadButtons()
        Log.i(TAG, "RESUME 7 commands+buttons OK")
        applyTheme()
        if (!modelsReady) lifecycleScope.launch(Dispatchers.IO) { initModelsOnce() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { toneGen?.release() } catch (e: Exception) {}
        stopPlayer()
    }
}

/** Univerzalny audio dekoder: hocijaky format -> mono 16 kHz. Ziadne externe kniznice. */
object AudioDecoder {
    const val SAMPLE_RATE = 16000

    /** Dekoduje cely subor do mono 16 kHz ShortArray (2 B/vzorka = 32 kB/s).
     *  WAV/PCM ("audio/raw") sa cita priamo - Android nema decoder pre audio/raw! */
    fun decodeToMono16kShorts(file: File): ShortArray? {
        val fromExtractor = decodeViaExtractor(file)
        if (fromExtractor != null && fromExtractor.isNotEmpty()) return fromExtractor
        // posledna poistka: manualny WAV parser
        return parseWavManually(file)
    }

    /** Manualny WAV (RIFF) parser - funguje vzdy, bez codecu. */
    private fun parseWavManually(file: File): ShortArray? {
        return try {
            val all = file.readBytes()
            if (all.size < 44) return null
            if (!(all[0] == 'R'.code.toByte() && all[1] == 'I'.code.toByte() &&
                  all[2] == 'F'.code.toByte() && all[3] == 'F'.code.toByte())) return null
            // najdi 'fmt ' a 'data' chunky
            var pos = 12
            var channels = 1
            var rate = SAMPLE_RATE
            var bits = 16
            var dataOff = -1
            var dataLen = 0
            while (pos + 8 <= all.size) {
                val id = String(byteArrayOf(all[pos], all[pos + 1], all[pos + 2], all[pos + 3]), Charsets.US_ASCII)
                val sz = (all[pos + 4].toInt() and 0xFF) or ((all[pos + 5].toInt() and 0xFF) shl 8) or
                         ((all[pos + 6].toInt() and 0xFF) shl 16) or ((all[pos + 7].toInt() and 0xFF) shl 24)
                if (id == "fmt " && pos + 8 + 16 <= all.size) {
                    val p = pos + 8
                    channels = ((all[p + 2].toInt() and 0xFF) or ((all[p + 3].toInt() and 0xFF) shl 8)).coerceAtLeast(1)
                    rate = (all[p + 4].toInt() and 0xFF) or ((all[p + 5].toInt() and 0xFF) shl 8) or
                           ((all[p + 6].toInt() and 0xFF) shl 16) or ((all[p + 7].toInt() and 0xFF) shl 24)
                    bits = ((all[p + 14].toInt() and 0xFF) or ((all[p + 15].toInt() and 0xFF) shl 8))
                } else if (id == "data") {
                    dataOff = pos + 8
                    dataLen = sz
                    if (dataLen <= 0 || dataOff + dataLen > all.size) dataLen = all.size - dataOff
                    break
                }
                pos += 8 + sz + (sz and 1)
            }
            if (dataOff < 0 || bits != 16) return null
            val n = dataLen / 2
            if (n <= 0) return null
            val raw = ShortArray(n)
            var i = 0
            var o = dataOff
            while (i < n && o + 1 < all.size) {
                raw[i] = (((all[o].toInt() and 0xFF) or (all[o + 1].toInt() shl 8))).toShort()
                i++; o += 2
            }
            val mono: ShortArray
            if (channels == 1) mono = raw
            else {
                val mc = n / channels
                mono = ShortArray(mc)
                for (k in 0 until mc) {
                    var sum = 0
                    for (c in 0 until channels) sum += raw[k * channels + c].toInt()
                    mono[k] = (sum / channels).toShort()
                }
            }
            return if (rate == SAMPLE_RATE) mono else resampleShort(mono, rate, SAMPLE_RATE)
        } catch (e: Exception) { null }
    }

    private fun decodeViaExtractor(file: File): ShortArray? {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(file.absolutePath) } catch (e: Exception) { return null }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
        }
        if (trackIndex < 0 || format == null) { extractor.release(); return null }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run { extractor.release(); return null }
        val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else SAMPLE_RATE
        val srcCh = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        // ---- WAV / PCM: ziaden codec, citaj priamo ----
        if (mime == "audio/raw" || mime.startsWith("audio/raw")) {
            val res = readRawPcm(extractor, srcCh)
            extractor.release()
            if (res == null || res.isEmpty()) return null
            return if (srcRate == SAMPLE_RATE) res else resampleShort(res, srcRate, SAMPLE_RATE)
        }

        val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { null }
        if (codec == null) {
            // fallback: skus precitat ako surove PCM (napr. .pcm, divny kontajner)
            val res = readRawPcm(extractor, srcCh)
            extractor.release()
            if (res == null || res.isEmpty()) return null
            return if (srcRate == SAMPLE_RATE) res else resampleShort(res, srcRate, SAMPLE_RATE)
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            try { codec.release() } catch (x: Exception) {}
            val res = readRawPcm(extractor, srcCh)
            extractor.release()
            if (res == null || res.isEmpty()) return null
            return if (srcRate == SAMPLE_RATE) res else resampleShort(res, srcRate, SAMPLE_RATE)
        }

        var buf = ShortArray(1 shl 16)
        var len = 0
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)
                    val sz = if (ib != null) extractor.readSampleData(ib, 0) else -1
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10000)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val ob = codec.getOutputBuffer(outIdx)
                    if (ob != null) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val sh = ShortArray(info.size / 2)
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
                        val monoCount = if (srcCh > 1) sh.size / srcCh else sh.size
                        if (len + monoCount > buf.size) {
                            var newSize = buf.size
                            while (newSize < len + monoCount) newSize *= 2
                            buf = buf.copyOf(newSize)
                        }
                        if (srcCh == 1) {
                            System.arraycopy(sh, 0, buf, len, sh.size); len += sh.size
                        } else {
                            var i = 0
                            while (i < monoCount) {
                                var sum = 0
                                var c = 0
                                while (c < srcCh && i * srcCh + c < sh.size) { sum += sh[i * srcCh + c].toInt(); c++ }
                                buf[len++] = (if (c > 0) sum / c else 0).toShort()
                                i++
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
        try { codec.stop() } catch (e: Exception) {}
        try { codec.release() } catch (e: Exception) {}
        extractor.release()
        if (len == 0) return null
        val mono = buf.copyOf(len)
        return if (srcRate == SAMPLE_RATE) mono else resampleShort(mono, srcRate, SAMPLE_RATE)
    }

    /** Precita surove PCM data priamo z extractora (WAV a pod.) a zmiesi na mono. */
    private fun readRawPcm(extractor: MediaExtractor, srcCh: Int): ShortArray? {
        var buf = ShortArray(1 shl 16)
        var len = 0
        val tmp = java.nio.ByteBuffer.allocate(1 shl 16)
        while (true) {
            tmp.clear()
            val sz = extractor.readSampleData(tmp, 0)
            if (sz < 0) break
            tmp.position(0); tmp.limit(sz)
            val sh = ShortArray(sz / 2)
            tmp.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
            val ch = if (srcCh > 0) srcCh else 1
            val monoCount = if (ch > 1) sh.size / ch else sh.size
            if (len + monoCount > buf.size) {
                var ns = buf.size
                while (ns < len + monoCount) ns *= 2
                buf = buf.copyOf(ns)
            }
            if (ch == 1) {
                System.arraycopy(sh, 0, buf, len, sh.size); len += sh.size
            } else {
                var i = 0
                while (i < monoCount) {
                    var sum = 0; var c = 0
                    while (c < ch && i * ch + c < sh.size) { sum += sh[i * ch + c].toInt(); c++ }
                    buf[len++] = (if (c > 0) sum / c else 0).toShort()
                    i++
                }
            }
            extractor.advance()
        }
        if (len == 0) return null
        return buf.copyOf(len)
    }

    /** Spatna kompatibilita pre orezavanie (FloatArray). */
    fun decodeToMono16k(file: File): FloatArray? {
        val s = decodeToMono16kShorts(file) ?: return null
        return FloatArray(s.size) { s[it] / 32768.0f }
    }

    fun resampleShort(inp: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || inp.isEmpty()) return inp
        val outLen = (inp.size.toLong() * to / from).toInt()
        if (outLen <= 0) return ShortArray(0)
        val out = ShortArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, inp.size - 1)
            val i1 = (i0 + 1).coerceIn(0, inp.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = (inp[i0] * (1 - frac) + inp[i1] * frac).toInt().toShort()
        }
        return out
    }

    fun resample(inp: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || inp.isEmpty()) return inp
        val outLen = (inp.size.toLong() * to / from).toInt()
        if (outLen <= 0) return FloatArray(0)
        val out = FloatArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, inp.size - 1)
            val i1 = (i0 + 1).coerceIn(0, inp.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = inp[i0] * (1 - frac) + inp[i1] * frac
        }
        return out
    }

    fun writeWavHeader(o: DataOutputStream, dataLen: Int) {
        o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + dataLen)); o.writeBytes("WAVE")
        o.writeBytes("fmt "); o.writeInt(Integer.reverseBytes(16))
        o.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        o.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        o.writeInt(Integer.reverseBytes(SAMPLE_RATE))
        o.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2))
        o.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
        o.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
        o.writeBytes("data"); o.writeInt(Integer.reverseBytes(dataLen))
    }

    fun writeWav16k(out: File, samples: ShortArray) {
        DataOutputStream(FileOutputStream(out)).use { o ->
            writeWavHeader(o, samples.size * 2)
            val bb = java.nio.ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putShort(s)
            o.write(bb.array())
        }
    }

    fun writeWav16kFloat(out: File, samples: FloatArray) {
        val sh = ShortArray(samples.size) { (samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        writeWav16k(out, sh)
    }
}