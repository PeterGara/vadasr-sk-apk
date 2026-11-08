package com.k2fsa.sherpa.onnx.vad.asr

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * PREKLADAC - jednoduche UI pre bezneho cloveka.
 * Automaticky najde model, spusti server, vsetko jednim klikom.
 */
class LlamaActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(getExternalFilesDir(null) ?: filesDir)
        Theme.init(getExternalFilesDir(null) ?: filesDir)
        buildUi()
        refreshStatus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundDrawable(Theme.background())
        }
        val scroll = ScrollView(this).apply { setBackgroundDrawable(Theme.background()) }

        root.addView(TextView(this).apply {
            text = "Prekladač"
            setTextColor(Theme.c(Theme.text)); textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        // stav
        status = TextView(this).apply {
            setTextColor(Theme.c(Theme.textDim)); textSize = 13f
            setPadding(0, dp(12), 0, dp(10))
        }
        root.addView(status)

        // tlacidlo start/stop
        btnStart = Button(this)
        btnStart.isAllCaps = false
        btnStart.textSize = 16f
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60))
        lp.topMargin = dp(6)
        btnStart.layoutParams = lp
        btnStart.background = Theme.rounded(Theme.accent, 14)
        btnStart.setTextColor(Theme.c(Theme.text))
        btnStart.setOnClickListener { toggleServer() }
        root.addView(btnStart)

        // tlacidlo vybrat model
        root.addView(mkBtn("Vybrať iný model (.gguf)") { pickModel() })
        root.addView(mkBtn("Obnoviť stav") { refreshStatus() })

        // info
        root.addView(TextView(this).apply {
            text = "\nAko to funguje:\n" +
                   "1. Model .gguf polož do priečinka:\n" +
                   "   /sdcard/Download/Speak-o/models/llm/\n" +
                   "2. Appka ho nájde automaticky\n" +
                   "3. Klikni SPUSTIŤ\n" +
                   "4. V Nastaveniach nastav engine prekladu = GGUF\n\n" +
                   "Nič neinštaluješ, nič neinštaluješ — llama je súčasť appky."
            setTextColor(Theme.c(Theme.textDim)); textSize = 12f
        })

        // log
        root.addView(TextView(this).apply {
            text = "Priebeh:"
            setTextColor(Theme.c(Theme.text)); textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        })
        logView = TextView(this).apply {
            setTextColor(Theme.c(Theme.textDim)); textSize = 10f
            background = Theme.rounded(Theme.card, 8)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
        }
        root.addView(logView)

        root.addView(mkBtn("Späť") { finish() })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun mkBtn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t; b.isAllCaps = false
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.background = Theme.rounded(Theme.neutral, 12)
        b.setTextColor(Theme.c(Theme.text))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addLog(s: String) {
        ui.post {
            logLines.add(s)
            if (logLines.size > 60) logLines.removeAt(0)
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun refreshStatus() {
        val model = LlamaServer.findModel(this)
        val running = LlamaServer.isRunning()
        val sb = StringBuilder()
        sb.append(if (running) "🟢 Server BEŽÍ na porte 8080" else "🔴 Server nebeží").append("\n\n")
        if (model != null) {
            sb.append("Model: ").append(model.name).append("\n")
            sb.append("Veľkosť: ").append(model.length() / 1024 / 1024).append(" MB\n")
            sb.append("Cesta: ").append(model.parentFile?.absolutePath ?: "").append("\n")
        } else {
            sb.append("❌ Model sa nenašiel\n")
            sb.append("Vlož .gguf do:\n/sdcard/Download/Speak-o/models/llm/")
        }
        status.text = sb.toString()
        btnStart.text = if (running) "ZASTAVIŤ" else "SPUSTIŤ"
        btnStart.background = Theme.rounded(if (running) Theme.danger else Theme.accent, 14)
    }

    private fun toggleServer() {
        if (LlamaServer.isRunning()) {
            LlamaServer.stop()
            addLog("Server zastaveny")
            ui.postDelayed({ refreshStatus() }, 800)
            return
        }
        val model = LlamaServer.findModel(this)
        if (model == null) {
            AlertDialog.Builder(this)
                .setTitle("Model sa nenašiel")
                .setMessage("Vlož súbor .gguf do priečinka:\n\n/sdcard/Download/Speak-o/models/llm/\n\nPotom klikni Obnoviť stav.")
                .setPositiveButton("Rozumiem", null)
                .show()
            return
        }

        btnStart.isEnabled = false
        btnStart.text = "Spúšťam..."
        addLog("=== Spúšťam llama-server ===")
        addLog("Model: " + model.name)

        Thread {
            val err = LlamaServer.start(this, model, threads = 4, contextSize = 4096) { line ->
                addLog(line)
            }
            ui.post {
                btnStart.isEnabled = true
                if (err != null) {
                    addLog("CHYBA: " + err)
                    Toast.makeText(this, "Chyba: " + err, Toast.LENGTH_LONG).show()
                } else {
                    addLog("✓ Server beží")
                    // nastav preklad na GGUF
                    Settings.translateEngine = "gguf"
                    Settings.translateUrl = "http://127.0.0.1:8080/v1/chat/completions"
                    Settings.translateModel = model.name.substringBefore(".gguf")
                    Settings.save()
                    Toast.makeText(this, "Prekladač pripravený", Toast.LENGTH_SHORT).show()
                }
                refreshStatus()
            }
        }.start()
    }

    private fun pickModel() {
        val dir = File("/sdcard/Download/Speak-o/models/llm")
        dir.mkdirs()
        val files = dir.listFiles { f -> f.name.endsWith(".gguf", true) } ?: arrayOf()
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Žiadne modely")
                .setMessage("V priečinku nie sú .gguf súbory:\n\n" + dir.absolutePath)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = files.map { it.name + "  (" + it.length() / 1024 / 1024 + " MB)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Vyber model")
            .setItems(names) { _, i ->
                Toast.makeText(this, "Vybratý: " + files[i].name, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // server nechame bezat - je to samostatny proces
    }
}