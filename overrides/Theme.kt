package com.k2fsa.sherpa.onnx.vad.asr

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import java.io.File

/**
 * VZHLAD APPKY - menitelny za behu cez theme.txt, BEZ BUILDU.
 * Subor: /sdcard/Android/data/com.k2fsa.sherpa.onnx/files/theme.txt
 */
object Theme {
    var bg1 = "#0F1319"
    var bg2 = "#1A2230"
    var card = "#1C232B"
    var cardAlt = "#16202B"
    var accent = "#3F8F6E"
    var primary = "#3B6FA0"
    var danger = "#8A4141"
    var neutral = "#2A3340"
    var text = "#E8ECF5"
    var textDim = "#8A93A6"
    var hint = "#5C6478"
    var radius = 16
    var useGradient = true
    var recordSize = 92

    private var f: File? = null

    fun init(dir: File) {
        f = File(dir, "theme.txt")
        if (!f!!.exists()) { save(); return }
        load()
    }

    fun load() {
        val file = f ?: return
        if (!file.exists()) return
        try {
            file.readLines().forEach { line ->
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) return@forEach
                val i = s.indexOf('=')
                if (i < 0) return@forEach
                val k = s.substring(0, i).trim().lowercase()
                val v = s.substring(i + 1).trim()
                when (k) {
                    "bg1" -> bg1 = v
                    "bg2" -> bg2 = v
                    "card" -> card = v
                    "cardalt" -> cardAlt = v
                    "accent" -> accent = v
                    "primary" -> primary = v
                    "danger" -> danger = v
                    "neutral" -> neutral = v
                    "text" -> text = v
                    "textdim" -> textDim = v
                    "hint" -> hint = v
                    "radius" -> radius = v.toIntOrNull() ?: radius
                    "recsize" -> recordSize = v.toIntOrNull() ?: recordSize
                    "gradient" -> useGradient = v == "1" || v.lowercase() == "true" || v.lowercase() == "ano"
                }
            }
        } catch (e: Exception) { }
    }

    fun save() {
        val file = f ?: return
        try {
            file.writeText(
                "# ============ VZHLAD - meni sa BEZ BUILDU (restartni appku) ============\n" +
                "# farby v tvare #RRGGBB\n" +
                "bg1=$bg1\n" +
                "bg2=$bg2\n" +
                "# gradient=1 zapne prechod bg1->bg2, gradient=0 vypne (jednofarebne bg1)\n" +
                "gradient=1\n" +
                "card=$card\n" +
                "cardalt=$cardAlt\n" +
                "accent=$accent\n" +
                "primary=$primary\n" +
                "danger=$danger\n" +
                "neutral=$neutral\n" +
                "text=$text\n" +
                "textdim=$textDim\n" +
                "hint=$hint\n" +
                "radius=$radius\n" +
                "recsize=$recordSize\n"
            )
        } catch (e: Exception) { }
    }

    fun c(hex: String): Int = try { Color.parseColor(hex) } catch (e: Exception) { Color.GRAY }

    fun rounded(colorHex: String, r: Int = radius): GradientDrawable =
        GradientDrawable().apply {
            setColor(c(colorHex))
            cornerRadius = r * 3f
        }

    fun ellipse(colorHex: String): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c(colorHex))
        }

    fun background(): GradientDrawable {
        return if (useGradient) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c(bg1), c(bg2)))
        else GradientDrawable().apply { setColor(c(bg1)) }
    }
}