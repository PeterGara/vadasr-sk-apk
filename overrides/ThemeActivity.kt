package com.k2fsa.sherpa.onnx.vad.asr

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * OBRAZOVKA VZHĽAD - výber farieb a gradientu priamo v appke.
 * Žiadne txt súbory. Všetko sa ukladá do theme.txt automaticky.
 */
class ThemeActivity : AppCompatActivity() {

    private lateinit var preview: View
    private val rows = ArrayList<Pair<String, () -> Unit>>()

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

        // nadpis
        root.addView(TextView(this).apply {
            text = "Vzhľad"
            setTextColor(Theme.c(Theme.text))
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        // náhľad
        preview = View(this)
        val lpPrev = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90))
        lpPrev.topMargin = dp(12)
        preview.layoutParams = lpPrev
        root.addView(preview)

        // tlačidlo pozadia / gradient
        val btnGradient = makeButton("Prechod pozadia: ZAP / VYP") { toggleGradient() }
        root.addView(btnGradient)

        // --- FARBY ---
        addColorRow(root, "Pozadie hore", { Theme.bg1 }, { Theme.bg1 = it })
        addColorRow(root, "Pozadie dole", { Theme.bg2 }, { Theme.bg2 = it })
        addColorRow(root, "Karty", { Theme.card }, { Theme.card = it })
        addColorRow(root, "Karty 2", { Theme.cardAlt }, { Theme.cardAlt = it })
        addColorRow(root, "Mikrofón / aktívne", { Theme.accent }, { Theme.accent = it })
        addColorRow(root, "Hlavné tlačidlo", { Theme.primary }, { Theme.primary = it })
        addColorRow(root, "Červené (Všetko)", { Theme.danger }, { Theme.danger = it })
        addColorRow(root, "Neutrálne tlačidlá", { Theme.neutral }, { Theme.neutral = it })
        addColorRow(root, "Text", { Theme.text }, { Theme.text = it })
        addColorRow(root, "Text vedľajší", { Theme.textDim }, { Theme.textDim = it })

        // --- ZAOBLENIE ---
        root.addView(TextView(this).apply {
            text = "Zaoblenie: ${Theme.radius}"
            setTextColor(Theme.c(Theme.textDim))
            textSize = 13f
            setPadding(0, dp(14), 0, dp(4))
        })
        val sbR = SeekBar(this).apply {
            max = 30
            progress = Theme.radius
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    Theme.radius = p.coerceAtLeast(2)
                    refreshPreview()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(sbR)

        // --- VEĽKOSŤ MIKROFÓNU ---
        root.addView(TextView(this).apply {
            text = "Veľkosť mikrofónu: ${Theme.recordSize}"
            setTextColor(Theme.c(Theme.textDim))
            textSize = 13f
            setPadding(0, dp(14), 0, dp(4))
        })
        val sbM = SeekBar(this).apply {
            max = 80
            progress = (Theme.recordSize - 60).coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    Theme.recordSize = 60 + p
                    refreshPreview()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(sbM)

        // --- HOTOVO ---
        root.addView(makeButton("HOTOVO — uložiť a zavrieť") {
            Theme.save()
            Toast.makeText(this, "Uložené", Toast.LENGTH_SHORT).show()
            finish()
        })
        root.addView(makeButton("Obnoviť pôvodné farby") {
            resetDefaults(); buildUi(); Toast.makeText(this, "Obnovené", Toast.LENGTH_SHORT).show()
        })
        root.addView(makeButton("Späť") { finish() })

        scroll.addView(root)
        setContentView(scroll)
        refreshPreview()
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        val b = android.widget.Button(this)
        b.text = text
        b.isAllCaps = false
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.background = Theme.rounded(Theme.neutral, 12)
        b.setTextColor(Theme.c(Theme.text))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addColorRow(parent: LinearLayout, label: String, get: () -> String, set: (String) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        lp.topMargin = dp(4)
        row.layoutParams = lp

        val tv = TextView(this).apply {
            text = label
            setTextColor(Theme.c(Theme.text))
            textSize = 15f
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val swatch = View(this)
        val slp = LinearLayout.LayoutParams(dp(56), dp(36))
        swatch.layoutParams = slp
        swatch.setBackgroundDrawable(Theme.rounded(get(), 8))
        swatch.setOnClickListener { openPicker(label, get(), set, swatch) }
        row.addView(swatch)

        parent.addView(row)
    }

    /** Jednoduchý picker: 3 slidery (odtieň/sýtosť/jas) + rýchle farby + náhľad. */
    private fun openPicker(label: String, current: String, set: (String) -> Unit, swatch: View) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val hsv = FloatArray(3)
        Color.colorToHSV(Theme.c(current), hsv)

        val previewBox = View(this)
        val plp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64))
        previewBox.layoutParams = plp
        box.addView(previewBox)

        val tvHex = TextView(this).apply {
            setTextColor(Theme.c(Theme.text))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        box.addView(tvHex)

        var h = hsv[0]; var s = hsv[1]; var v = hsv[2]

        fun apply() {
            val c = Color.HSVToColor(floatArrayOf(h, s, v))
            previewBox.setBackgroundDrawable(Theme.rounded(String.format("#%06X", 0xFFFFFF and c), 10))
            tvHex.text = String.format("#%06X", 0xFFFFFF and c)
        }

        fun addSlider(title: String, max: Int, init: Int, onChange: (Int) -> Unit) {
            box.addView(TextView(this).apply {
                text = title; setTextColor(Theme.c(Theme.textDim)); textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
            val sb = SeekBar(this).apply {
                this.max = max
                progress = init
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb2: SeekBar?, p: Int, fromUser: Boolean) { onChange(p); apply() }
                    override fun onStartTrackingTouch(sb2: SeekBar?) {}
                    override fun onStopTrackingTouch(sb2: SeekBar?) {}
                })
            }
            box.addView(sb)
        }

        addSlider("Odtieň (farba)", 360, h.toInt()) { h = it.toFloat() }
        addSlider("Sýtosť", 100, (s * 100).toInt()) { s = it / 100f }
        addSlider("Jas", 100, (v * 100).toInt()) { v = it / 100f }

        // rýchle farby
        box.addView(TextView(this).apply {
            text = "Rýchle farby"; setTextColor(Theme.c(Theme.textDim)); textSize = 12f
            setPadding(0, dp(10), 0, dp(4))
        })
        val quick = intArrayOf(
            0xFF0F1319.toInt(), 0xFF1A2230.toInt(), 0xFF1C232B.toInt(), 0xFF16202B.toInt(),
            0xFF2A3340.toInt(), 0xFF3F8F6E.toInt(), 0xFF3B6FA0.toInt(), 0xFF8A4141.toInt(),
            0xFF6B8F3F.toInt(), 0xFF8F6B3F.toInt(), 0xFF6B3F8F.toInt(), 0xFF3F8F8F.toInt(),
            0xFFE8ECF5.toInt(), 0xFF8A93A6.toInt(), 0xFF5C6478.toInt(), 0xFF000000.toInt()
        )
        var qRow: LinearLayout? = null
        quick.forEachIndexed { i, col ->
            if (i % 8 == 0) {
                qRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(qRow)
            }
            val sw = View(this)
            val lp = LinearLayout.LayoutParams(0, dp(34), 1f)
            lp.marginEnd = dp(4)
            lp.topMargin = dp(4)
            sw.layoutParams = lp
            sw.setBackgroundDrawable(Theme.rounded(String.format("#%06X", 0xFFFFFF and col), 6))
            sw.setOnClickListener {
                Color.colorToHSV(col, hsv)
                h = hsv[0]; s = hsv[1]; v = hsv[2]
                apply()
            }
            qRow?.addView(sw)
        }

        apply()

        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(box)
            .setPositiveButton("Použiť") { _, _ ->
                val hex = String.format("#%06X", 0xFFFFFF and Color.HSVToColor(floatArrayOf(h, s, v)))
                set(hex)
                swatch.setBackgroundDrawable(Theme.rounded(hex, 8))
                Theme.save()
                refreshPreview()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun toggleGradient() {
        Theme.useGradient = !Theme.useGradient
        Theme.save()
        refreshPreview()
        Toast.makeText(this, if (Theme.useGradient) "Prechod ZAP" else "Prechod VYP", Toast.LENGTH_SHORT).show()
    }

    private fun refreshPreview() {
        try {
            preview.setBackgroundDrawable(Theme.background())
        } catch (e: Exception) {}
    }

    private fun resetDefaults() {
        Theme.bg1 = "#0F1319"; Theme.bg2 = "#1A2230"
        Theme.card = "#1C232B"; Theme.cardAlt = "#16202B"
        Theme.accent = "#3F8F6E"; Theme.primary = "#3B6FA0"
        Theme.danger = "#8A4141"; Theme.neutral = "#2A3340"
        Theme.text = "#E8ECF5"; Theme.textDim = "#8A93A6"; Theme.hint = "#5C6478"
        Theme.radius = 16; Theme.recordSize = 92; Theme.useGradient = true
        Theme.save()
    }
}