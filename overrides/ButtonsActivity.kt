package com.k2fsa.sherpa.onnx.vad.asr

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * EDITOR TLAČIDIEL - všetko sa dá meniť v appke, BEZ BUILDU.
 * Ukladá sa do layout.json.
 */
class ButtonsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(getExternalFilesDir(null) ?: filesDir)
        Theme.init(getExternalFilesDir(null) ?: filesDir)
        Layout.init(getExternalFilesDir(null) ?: filesDir)
        buildUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundDrawable(Theme.background())
        }
        val scroll = ScrollView(this).apply { setBackgroundDrawable(Theme.background()) }

        root.addView(TextView(this).apply {
            text = "Tlačidlá"
            setTextColor(Theme.c(Theme.text)); textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Klikni na tlačidlo pre úpravu. Zmeny sú hneď platné (po návrate do hlavnej obrazovky)."
            setTextColor(Theme.c(Theme.textDim)); textSize = 12f
            setPadding(0, dp(8), 0, dp(10))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        root.addView(btn("+ Pridať tlačidlo") {
            Layout.buttons.add(Layout.Btn("Nove", "noop", 1, 52, "round", Theme.neutral))
            Layout.save(); rebuildList()
        })

        root.addView(btn("Obnovit predvolene") {
            Layout.defaults(); Layout.save(); rebuildList()
            Toast.makeText(this, "Obnovené", Toast.LENGTH_SHORT).show()
        })

        root.addView(btn("HOTOVO — uložiť a zavrieť") {
            Layout.save(); finish()
        })
        root.addView(btn("Spať") { finish() })

        scroll.addView(root)
        setContentView(scroll)
        rebuildList()
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = android.widget.Button(this)
        b.text = t; b.isAllCaps = false
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        lp.topMargin = dp(6)
        b.layoutParams = lp
        b.background = Theme.rounded(Theme.neutral, 12)
        b.setTextColor(Theme.c(Theme.text))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun rebuildList() {
        list.removeAllViews()
        Layout.buttons.forEachIndexed { i, b ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
            lp.topMargin = dp(4)
            row.layoutParams = lp

            // náhľad tlačidla
            val prev = Button(this)
            prev.text = b.text
            prev.isAllCaps = false
            prev.textSize = b.textSize.toFloat()
            prev.setBackgroundDrawable(shapeDrawable(b.shape, b.color))
            prev.setTextColor(Theme.c(b.textColor))
            val plp = LinearLayout.LayoutParams(0, dp(b.h.coerceAtMost(56)), 1f)
            prev.layoutParams = plp
            prev.setOnClickListener { edit(i) }
            row.addView(prev)

            // šípky hore/dole
            row.addView(smallBtn("▲") { if (i > 0) { val t = Layout.buttons[i]; Layout.buttons[i] = Layout.buttons[i-1]; Layout.buttons[i-1] = t; Layout.save(); rebuildList() } })
            row.addView(smallBtn("▼") { if (i < Layout.buttons.size-1) { val t = Layout.buttons[i]; Layout.buttons[i] = Layout.buttons[i+1]; Layout.buttons[i+1] = t; Layout.save(); rebuildList() } })
            row.addView(smallBtn("✕") {
                Layout.buttons.removeAt(i); Layout.save(); rebuildList()
            })

            list.addView(row)
        }
    }

    private fun smallBtn(t: String, onClick: () -> Unit): Button {
        val b = android.widget.Button(this)
        b.text = t; b.isAllCaps = false; b.textSize = 14f
        b.minWidth = 0; b.setPadding(0, 0, 0, 0)
        val lp = LinearLayout.LayoutParams(dp(38), dp(38))
        lp.marginStart = dp(4)
        b.layoutParams = lp
        b.background = Theme.rounded(Theme.neutral, 8)
        b.setTextColor(Theme.c(Theme.text))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun shapeDrawable(shape: String, color: String): GradientDrawable {
        val c = try { Color.parseColor(color) } catch (e: Exception) { Color.DKGRAY }
        return when (shape) {
            "circle" -> GradientDrawable().apply { this.shape = GradientDrawable.OVAL; setColor(c) }
            "pill" -> GradientDrawable().apply { cornerRadius = 500f; setColor(c) }
            "square" -> GradientDrawable().apply { cornerRadius = 0f; setColor(c) }
            else -> GradientDrawable().apply { cornerRadius = 10 * resources.displayMetrics.density; setColor(c) }
        }
    }

    private fun edit(idx: Int) {
        val b = Layout.buttons[idx]
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(6))
        }

        fun label(t: String) {
            box.addView(TextView(this).apply {
                text = t; setTextColor(Theme.c(Theme.textDim)); textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
        }

        label("Text tlačidla")
        val edText = EditText(this).apply {
            setText(b.text); setTextColor(Theme.c(Theme.text))
            setHintTextColor(Theme.c(Theme.hint))
            background = Theme.rounded(Theme.card, 8)
        }
        box.addView(edText)

        label("Akcia (engine: copy, save, stop, append:Text, ...)")
        val edAction = EditText(this).apply {
            setText(b.action); setTextColor(Theme.c(Theme.text))
            setHintTextColor(Theme.c(Theme.hint))
            background = Theme.rounded(Theme.card, 8)
            setSingleLine()
        }
        box.addView(edAction)

        // rýchle akcie
        box.addView(TextView(this).apply {
            text = "Rýchle akcie:"; setTextColor(Theme.c(Theme.textDim)); textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
        })
        val acts = arrayOf("copy", "save", "stop", "clear", "del_veta", "send_new", "paste_app", "translate", "undo", "time", "share", "notify")
        var aRow: LinearLayout? = null
        acts.forEachIndexed { i, a ->
            if (i % 4 == 0) { aRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; box.addView(aRow) }
            val sb = android.widget.Button(this).apply {
                text = a; isAllCaps = false; textSize = 10f
                minWidth = 0; setPadding(0,0,0,0)
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(3); topMargin = dp(3) }
                background = Theme.rounded(Theme.neutral, 6)
                setTextColor(Theme.c(Theme.text))
                setOnClickListener { edAction.setText(a) }
            }
            aRow?.addView(sb)
        }

        label("Farba pozadia: ${b.color}")
        val colRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var curColor = b.color
        val colorOpts = arrayOf("#2A3340","#3F8F6E","#3B6FA0","#8A4141","#6B8F3F","#8F6B3F","#6B3F8F","#3F8F8F","#000000","#1C232B")
        colorOpts.forEach { c ->
            val sw = View(this)
            val lp = LinearLayout.LayoutParams(0, dp(38), 1f)
            lp.marginEnd = dp(3)
            sw.layoutParams = lp
            sw.background = Theme.rounded(c, 6)
            sw.setOnClickListener { curColor = c; Toast.makeText(this, "Farba: $c", Toast.LENGTH_SHORT).show() }
            colRow.addView(sw)
        }
        box.addView(colRow)

        label("Tvar")
        var curShape = b.shape
        val shapes = arrayOf("round", "pill", "square", "circle")
        val shapeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shapes.forEach { sh ->
            val sb = android.widget.Button(this).apply {
                text = sh; isAllCaps = false; textSize = 10f
                minWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) }
                background = shapeDrawable(sh, Theme.neutral)
                setTextColor(Theme.c(Theme.text))
                setOnClickListener { curShape = sh; Toast.makeText(this@ButtonsActivity, "Tvar: $sh", Toast.LENGTH_SHORT).show() }
            }
            shapeRow.addView(sb)
        }
        box.addView(shapeRow)

        label("Šírka v riadku: ${b.w}  (1-4)")
        var curW = b.w
        val sbW = SeekBar(this).apply {
            max = 3; progress = (b.w - 1).coerceIn(0, 3)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f2: Boolean) { curW = p + 1 }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(sbW)

        label("Výška: ${b.h} dp")
        var curH = b.h
        val sbH = SeekBar(this).apply {
            max = 100; progress = (b.h - 32).coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f2: Boolean) { curH = 32 + p }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(sbH)

        AlertDialog.Builder(this)
            .setTitle(b.text)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Uložiť") { _, _ ->
                b.text = edText.text.toString().ifBlank { "Tlacidlo" }
                b.action = edAction.text.toString().ifBlank { "noop" }
                b.color = curColor
                b.shape = curShape
                b.w = curW
                b.h = curH
                Layout.save()
                rebuildList()
                Toast.makeText(this, "Uložené", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Zmazať") { _, _ ->
                Layout.buttons.removeAt(idx); Layout.save(); rebuildList()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }
}