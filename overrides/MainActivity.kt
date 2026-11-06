package com.k2fsa.sherpa.onnx.vad.asr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var saveButton: Button
    private lateinit var undoButton: Button
    private lateinit var textView: TextView

    private lateinit var vad: Vad

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    // VOICE_RECOGNITION: system noise suppression + AGC (outdoor/concert use)
    private val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private lateinit var offlineRecognizer: OfflineRecognizer

    // --- session state: live append to file + undo support ---
    private var sessionFile: File? = null
    private val segments = ArrayList<String>()
    private var isSessionOpen = false

    // --- config file: tunable WITHOUT rebuild ---
    // /sdcard/Android/data/com.k2fsa.sherpa.onnx/files/config.txt
    private var cfgThreshold = 0.65f
    private var cfgMinSilence = 0.25f
    private var cfgCmdUndo = "zmaž"
    private var cfgCmdParagraph = "nový odstavec"
    private var cfgCmdStop = "stop"

    @Volatile
    private var isRecording: Boolean = false

    // ---------- config ----------
    private fun loadConfig() {
        try {
            val f = File(getExternalFilesDir(null), "config.txt")
            if (!f.exists()) {
                // create default config so user can edit it later (no rebuild needed)
                f.writeText(
                    "# Konfiguracia Diktat APK - uprava a restart appky, BEZ nového buildu\n" +
                    "# prah VAD: vyssie = menej falsnych spusteni v sume (0.4-0.9)\n" +
                    "threshold=0.65\n" +
                    "# min pauza medzi vetami v sekundach\n" +
                    "min_silence=0.25\n" +
                    "# hlasove prikazy (len samostatny kratky segment!)\n" +
                    "cmd_undo=zmaž\n" +
                    "cmd_paragraph=nový odstavec\n" +
                    "cmd_stop=stop\n",
                    Charsets.UTF_8
                )
                return
            }
            f.readLines().forEach { line ->
                    val s = line.trim()
                    if (s.isEmpty() || s.startsWith("#")) return@forEach
                    val i = s.indexOf('=')
                    if (i < 0) return@forEach
                    val k = s.substring(0, i).trim()
                    val v = s.substring(i + 1).trim()
                    when (k) {
                        "threshold" -> cfgThreshold = v.toFloatOrNull() ?: cfgThreshold
                        "min_silence" -> cfgMinSilence = v.toFloatOrNull() ?: cfgMinSilence
                        "cmd_undo" -> cfgCmdUndo = v
                        "cmd_paragraph" -> cfgCmdParagraph = v
                        "cmd_stop" -> cfgCmdStop = v
                    }
                }
                Log.i(TAG, "config loaded: threshold=$cfgThreshold minSilence=$cfgMinSilence")
        } catch (e: Exception) {
            Log.e(TAG, "config load failed: $e")
        }
    }

    private fun diktatDir(): File {
        val d = File(getExternalFilesDir(null), "Diktat")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun newSessionFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(diktatDir(), "diktat_$ts.txt")
    }

    private fun persistSegments() {
        val f = sessionFile ?: return
        try {
            FileOutputStream(f, false).use { out ->
                out.write(segments.joinToString("\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "persist failed: $e")
        }
    }

    private fun render() {
        textView.text = segments.joinToString("\n")
    }

    // ---------- voice commands (short segments only, startswith) ----------
    private fun matchCommand(raw: String): String? {
        val t = raw.trim().lowercase()
        if (t.isEmpty()) return null
        // len kratke segmenty = pravdepodobne prikaz, nie sucast vety
        val words = t.split(Regex("\\s+"))
        if (words.size > 4) return null
        return when {
            t.startsWith(cfgCmdUndo.lowercase()) -> "undo"
            t.startsWith(cfgCmdParagraph.lowercase()) -> "paragraph"
            t.startsWith(cfgCmdStop.lowercase()) || t.startsWith("zastav") -> "stop"
            else -> null
        }
    }

    private fun applySegment(text: String) {
        val cmd = matchCommand(text)
        when (cmd) {
            "undo" -> {
                if (segments.isNotEmpty()) {
                    segments.removeAt(segments.lastIndex)
                    persistSegments()
                    runOnUiThread {
                        render()
                        textView.append("\n[zmazané]")
                    }
                }
            }
            "paragraph" -> {
                if (segments.isNotEmpty()) {
                    segments[segments.lastIndex] = segments.last() + "\n"
                    persistSegments()
                    runOnUiThread { render() }
                }
            }
            "stop" -> {
                runOnUiThread { if (isRecording) onclick() }
            }
            null -> {
                segments.add(text)
                persistSegments()
                runOnUiThread { render() }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = requestCode == REQUEST_RECORD_AUDIO_PERMISSION &&
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            Log.e(TAG, "Audio record is disallowed")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
        loadConfig()

        textView = findViewById(R.id.my_text)
        textView.movementMethod = ScrollingMovementMethod()

        recordButton = findViewById(R.id.record_button)
        recordButton.isEnabled = false
        recordButton.setOnClickListener { onclick() }

        saveButton = findViewById(R.id.save_button)
        saveButton.setOnClickListener { onSave() }

        undoButton = findViewById(R.id.undo_button)
        undoButton.setOnClickListener {
            if (segments.isNotEmpty()) {
                segments.removeAt(segments.lastIndex)
                persistSegments()
                render()
            }
        }

        textView.text = "Inicializujem modely..."

        lifecycleScope.launch(Dispatchers.IO) {
            initVadModel()
            initOfflineRecognizer()
            withContext(Dispatchers.Main) {
                recordButton.isEnabled = true
                textView.text = "Pripravené.\nUložený text: ${diktatDir().absolutePath}"
                Log.i(TAG, "Model initialization completed")
            }
        }
    }

    private fun onclick() {
        if (!isRecording) {
            val ret = initMicrophone()
            if (!ret) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            audioRecord!!.startRecording()

            // foreground service: mic keeps working with screen off / in pocket
            val svc = Intent(this, DiktatService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)

            recordButton.setText(R.string.stop)
            isRecording = true

            // new session file
            segments.clear()
            sessionFile = newSessionFile()
            isSessionOpen = true
            persistSegments()
            render()

            vad.reset()
            recordingThread = thread(true) { processSamples() }
            Log.i(TAG, "Started recording -> ${sessionFile?.name}")
        } else {
            isRecording = false
            stopService(Intent(this, DiktatService::class.java))
            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null
            recordButton.setText(R.string.start)
            Log.i(TAG, "Stopped recording")
        }
    }

    private fun onSave() {
        if (isRecording) onclick()
        val f = sessionFile
        if (f == null || !f.exists() || f.length() == 0L) {
            textView.append("\n[Nie je čo uložiť]")
            return
        }
        isSessionOpen = false
        // broadcast for Operit workflow (intent trigger)
        try {
            sendBroadcast(Intent("com.k2fsa.sherpa.onnx.DIKTAT_SAVED").putExtra("path", f.absolutePath))
        } catch (e: Exception) { }
        textView.append("\n[Uložené: ${f.name}]")
        Log.i(TAG, "SAVED ${f.absolutePath}")
    }

    private fun initVadModel() {
        val config = getVadModelConfig(0)!!
        // tunable from config.txt - no rebuild needed
        config.sileroVadModelConfig.threshold = cfgThreshold
        config.sileroVadModelConfig.minSilenceDuration = cfgMinSilence
        vad = Vad(assetManager = application.assets, config = config)
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }
        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            audioSource, sampleRateInHz, channelConfig, audioFormat, numBytes * 2
        )
        // noise suppressor + auto gain for noisy environments
        try {
            android.media.audiofx.NoiseSuppressor.create(audioRecord!!.audioSessionId)?.enabled = true
            android.media.audiofx.AutomaticGainControl.create(audioRecord!!.audioSessionId)?.enabled = true
        } catch (e: Exception) { }
        return true
    }

    private fun processSamples() {
        val bufferSize = 512
        val buffer = ShortArray(bufferSize)
        val coroutineScope = CoroutineScope(Dispatchers.IO)

        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size)
            if (ret != null && ret > 0) {
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                vad.acceptWaveform(samples)
                while (!vad.empty()) {
                    val segment = vad.front()
                    coroutineScope.launch {
                        val text = runSecondPass(segment.samples)
                        if (text.isNotBlank()) applySegment(text)
                    }
                    vad.pop()
                }
            }
        }
        coroutineScope.cancel()
    }

    private fun initOfflineRecognizer() {
        val asrModelType = 40 // Parakeet TDT v3 (SK model in assets)
        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getOfflineModelConfig(type = asrModelType)!!,
        )
        offlineRecognizer = OfflineRecognizer(
            assetManager = application.assets, config = config
        )
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