package com.k2fsa.sherpa.onnx.vad.asr

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** EDITOR MENU - celé menu sa mení v appke, BEZ BUILDU. Ukladá sa do menu.json. */
class MenuActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(getExternalFilesDir(null) ?: filesDir)
        Theme.init(getExternalFilesDir(null) ?: filesDir)
        Menu.init(getExternalFilesDir(null) ?: filesDir)
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
            text = "Menu"
            setTextColor(Theme.c(Theme.text)); textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Menu (⋮) sa generuje z tohto zoznamu. Zmena = hneď platná, žiadny build."
            setTextColor(Theme.c(Theme.textDim)); textSize = 12f
            setPadding(0, dp(8), 0, dp(10))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        root.addView(btn("+ Pridať položku") {
            Menu.items.add(Menu.Item("Nova polozka", "action:noop"))
            Menu.save(); rebuild()
        })
        root.addView(btn("Obnoviť predvolené menu") {
            Menu.defaults(); Menu.save(); rebuild()
            Toast.makeText(this, "Obnovené", Toast.LENGTH_SHORT).show()
        })
        root.addView(btn("HOTOVO — uložiť") { Menu.save(); finish() })
        root.addView(btn("Späť") { finish() })

        scroll.addView(root)
        setContentView(scroll)
        rebuild()
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

    private fun rebuild() {
        list.removeAllViews()
        Menu.items.forEachIndexed { i, it ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
            lp.topMargin = dp(4)
            row.layoutParams = lp

            val tv = TextView(this).apply {
                text = it.text + "\n" + it.action
                setTextColor(Theme.c(Theme.text))
                textSize = 13f
                setPadding(dp(10), dp(4), dp(4), dp(4))
                background = Theme.rounded(Theme.card, 8)
            }
            tv.layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
            tv.setOnClickListener { edit(i) }
            row.addView(tv)

            row.addView(small("▲") { if (i > 0) { val t = Menu.items[i]; Menu.items[i] = Menu.items[i-1]; Menu.items[i-1] = t; Menu.save(); rebuild() } })
            row.addView(small("▼") { if (i < Menu.items.size-1) { val t = Menu.items[i]; Menu.items[i] = Menu.items[i+1]; Menu.items[i+1] = t; Menu.save(); rebuild() } })
            row.addView(small("✕") { Menu.items.removeAt(i); Menu.save(); rebuild() })

            list.addView(row)
        }
    }

    private fun small(t: String, onClick: () -> Unit): Button {
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

    private fun edit(idx: Int) {
        val it = Menu.items[idx]
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

        label("Názov položky")
        val edText = EditText(this).apply {
            setText(it.text); setTextColor(Theme.c(Theme.text))
            setHintTextColor(Theme.c(Theme.hint))
            background = Theme.rounded(Theme.card, 8)
        }
        box.addView(edText)

        label("Akcia")
        val edAction = EditText(this).apply {
            setText(it.action); setTextColor(Theme.c(Theme.text))
            setHintTextColor(Theme.c(Theme.hint))
            background = Theme.rounded(Theme.card, 8)
            setSingleLine()
        }
        box.addView(edAction)

        box.addView(TextView(this).apply {
            text = "Rýchle akcie:"; setTextColor(Theme.c(Theme.textDim)); textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
        })
        val acts = listOf(
            "screen:settings", "screen:theme", "screen:buttons",
            "action:copy", "action:save", "action:stop", "action:clear",
            "action:del_veta", "action:send_new", "action:paste_app",
            "action:translate", "action:undo", "action:transcribe_file",
            "action:trim_audio", "url:https://"
        )
        var r: LinearLayout? = null
        acts.forEachIndexed { i, a ->
            if (i % 3 == 0) { r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; box.addView(r) }
            val sb = android.widget.Button(this).apply {
                text = a.removePrefix("action:").removePrefix("screen:")
                isAllCaps = false; textSize = 9f
                minWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(3); topMargin = dp(3) }
                background = Theme.rounded(Theme.neutral, 6)
                setTextColor(Theme.c(Theme.text))
                setOnClickListener { edAction.setText(a) }
            }
            r?.addView(sb)
        }

        AlertDialog.Builder(this)
            .setTitle(it.text)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Uložiť") { _, _ ->
                it.text = edText.text.toString().ifBlank { "Polozka" }
                it.action = edAction.text.toString().ifBlank { "action:noop" }
                Menu.save(); rebuild()
                Toast.makeText(this, "Uložené", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Zmazať") { _, _ -> Menu.items.removeAt(idx); Menu.save(); rebuild() }
            .setNegativeButton("Zrušiť", null)
            .show()
    }
}