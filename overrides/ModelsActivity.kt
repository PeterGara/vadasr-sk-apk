package com.k2fsa.sherpa.onnx.vad.asr

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * SPRÁVA MODELOV - výber a sťahovanie ASR modelov.
 * Sťahuje priamo do internej pamäte appky (FUSE problém vyriešený).
 */
class ModelsActivity : AppCompatActivity() {

    data class M(val id: String, val názov: String, val repo: String, val popis: String)

    private val models = listOf(
        M("sk", "SK Parakeet (KInIT)", "vibeide-dev/parakeet-tdt-0.6b-v3-sk-sherpa-onnx-int8",
          "Najlepšia slovenčina, WER 6,6 %"),
        M("multi", "Parakeet v3 UNIVERZÁLNY", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
          "EN, DE, ES, FR + 20 jazykov. Jeden model pre všetko."),
        M("en", "Parakeet EN", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-int8",
          "Angličtina, najrýchlejší")
    )

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(getExternalFilesDir(null) ?: filesDir)
        Theme.init(getExternalFilesDir(null) ?: filesDir)
        buildUi()
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
            text = "Modely"
            setTextColor(Theme.c(Theme.text)); textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        status = TextView(this).apply {
            setTextColor(Theme.c(Theme.textDim)); textSize = 12f
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(status)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        root.addView(TextView(this).apply {
            text = "\nModely sa ukladajú do internej pamäte appky.\nPo stiahnutí vyber jazyk v Nastaveniach."
            setTextColor(Theme.c(Theme.textDim)); textSize = 11f
            setPadding(0, dp(16), 0, 0)
        })

        root.addView(mkBtn("Späť") { finish() })

        scroll.addView(root)
        setContentView(scroll)
        refresh()
    }

    private fun mkBtn(t: String, onClick: () -> Unit): Button {
        val b = android.widget.Button(this)
        b.text = t; b.isAllCaps = false
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.background = Theme.rounded(Theme.neutral, 12)
        b.setTextColor(Theme.c(Theme.text))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun modelDir(id: String) = File(filesDir, "models/asr/" + id)

    private fun isInstalled(id: String): Boolean {
        val e = File(modelDir(id), "encoder.int8.onnx")
        return e.exists() && e.length() > 600_000_000L
    }

    private fun refresh() {
        list.removeAllViews()
        var any = false
        models.forEach { m ->
            val installed = isInstalled(m.id)
            if (installed) any = true
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            row.layoutParams = lp

            row.addView(TextView(this).apply {
                text = (if (installed) "✅ " else "⬇ ") + m.názov
                setTextColor(Theme.c(if (installed) Theme.accent else Theme.text))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = m.popis + (if (installed) "\nStiahnuté" else "\n~660 MB")
                setTextColor(Theme.c(Theme.textDim)); textSize = 11f
            })

            val b = android.widget.Button(this)
            b.isAllCaps = false
            b.text = if (installed) "PREINŠTALOVAŤ" else "STIAHNUŤ"
            b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) }
            b.background = Theme.rounded(if (installed) Theme.neutral else Theme.accent, 10)
            b.setTextColor(Theme.c(Theme.text))
            b.setOnClickListener { confirmDownload(m) }
            row.addView(b)

            list.addView(row)
        }
        status.text = if (any) "Aspoň jeden model je nainštalovaný." else "Žiadny model nie je nainštalovaný."
    }

    private fun confirmDownload(m: M) {
        AlertDialog.Builder(this)
            .setTitle(m.názov)
            .setMessage("Stiahnuť ~660 MB z HuggingFace?\n\nOdporúčam WiFi.")
            .setPositiveButton("Stiahnuť") { _, _ -> download(m) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun download(m: M) {
        val dir = modelDir(m.id)
        dir.mkdirs()
        status.text = "Sťahujem " + m.názov + "..."
        Toast.makeText(this, "Sťahujem na pozadí", Toast.LENGTH_SHORT).show()

        Thread {
            val files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            var ok = 0
            for (f in files) {
                val url = "https://huggingface.co/" + m.repo + "/resolve/main/" + f
                val out = File(dir, f)
                val success = dl(url, out, f)
                if (success) ok++
                ui.post { status.text = m.názov + "\n" + ok + "/4 hotovo (" + f + ")" }
            }
            ui.post {
                if (ok == 4) {
                    Settings.asrLang = m.id
                    Settings.save()
                    status.text = "✅ " + m.názov + " nainštalovaný"
                    Toast.makeText(this, "Hotovo!", Toast.LENGTH_LONG).show()
                } else {
                    status.text = "❌ Chyba: " + ok + "/4"
                }
                refresh()
            }
        }.start()
    }

    private fun dl(urlStr: String, out: File, name: String): Boolean {
        return try {
            var attempt = 0
            while (attempt < 3) {
                attempt++
                try {
                    val c = URL(urlStr).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000
                    c.readTimeout = 300000
                    c.instanceFollowRedirects = true
                    if (c.responseCode !in 200..299) { c.disconnect(); Thread.sleep(2000); continue }
                    c.inputStream.use { inp ->
                        FileOutputStream(out).use { o -> inp.copyTo(o, 65536) }
                    }
                    c.disconnect()
                    if (out.length() > 1000) return true
                } catch (e: Exception) {
                    android.util.Log.e("sherpa-onnx", "dl $name pokus $attempt: $e")
                    Thread.sleep(2000)
                }
            }
            false
        } catch (e: Exception) { false }
    }
}