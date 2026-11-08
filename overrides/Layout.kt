package com.k2fsa.sherpa.onnx.vad.asr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * LAYOUT - tlačidlá sa definujú v layout.json, appka ich vykreslí.
 * Mení sa za behu, BEZ BUILDU.
 *
 * Format:
 * {
 *   "buttons": [
 *     {"text":"Stop", "action":"stop", "w":2, "h":52, "shape":"pill", "color":"#8A4141"},
 *     {"text":"Kopir", "action":"copy", "w":1, "h":52, "shape":"round", "color":"#2A3340"}
 *   ]
 * }
 *
 * shape: pill | round | square | circle
 * w: 1-4 (relatívna šírka v riadku)
 * h: výška v dp
 */
object Layout {

    data class Btn(
        var text: String = "Tlacidlo",
        var action: String = "noop",
        var w: Int = 1,
        var h: Int = 52,
        var shape: String = "round",
        var color: String = "#2A3340",
        var textColor: String = "#E8ECF5",
        var textSize: Int = 13
    )

    private var f: File? = null
    val buttons = ArrayList<Btn>()

    fun init(dir: File) {
        f = File(dir, "layout.json")
        if (!f!!.exists()) { defaults(); save(); return }
        load()
        if (buttons.isEmpty()) { defaults(); save() }
    }

    fun load() {
        val file = f ?: return
        buttons.clear()
        try {
            val j = JSONObject(file.readText())
            val arr = j.optJSONArray("buttons") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                buttons.add(Btn(
                    text = o.optString("text", "Tlacidlo"),
                    action = o.optString("action", "noop"),
                    w = o.optInt("w", 1).coerceIn(1, 4),
                    h = o.optInt("h", 52).coerceIn(32, 160),
                    shape = o.optString("shape", "round"),
                    color = o.optString("color", "#2A3340"),
                    textColor = o.optString("textColor", "#E8ECF5"),
                    textSize = o.optInt("textSize", 13).coerceIn(8, 24)
                ))
            }
        } catch (e: Exception) { }
    }

    fun save() {
        val file = f ?: return
        try {
            val arr = JSONArray()
            for (b in buttons) {
                arr.put(JSONObject()
                    .put("text", b.text)
                    .put("action", b.action)
                    .put("w", b.w)
                    .put("h", b.h)
                    .put("shape", b.shape)
                    .put("color", b.color)
                    .put("textColor", b.textColor)
                    .put("textSize", b.textSize))
            }
            file.writeText(JSONObject().put("buttons", arr).toString(2))
        } catch (e: Exception) { }
    }

    fun defaults() {
        buttons.clear()
        buttons.add(Btn("Odst.", "paragraph", 1, 48, "round", "#2A3340"))
        buttons.add(Btn("Uloz", "save", 1, 48, "round", "#2A3340"))
        buttons.add(Btn("Kopir", "copy", 1, 48, "round", "#2A3340"))
        buttons.add(Btn("Zdiel", "share", 1, 48, "round", "#2A3340"))
        buttons.add(Btn("Cas", "time", 1, 48, "round", "#2A3340"))
        buttons.add(Btn("Uloz TXT", "save_public", 1, 48, "round", "#2A3340"))
        buttons.add(Btn("Notif", "notify", 1, 48, "round", "#2A3340"))
        buttons.add(Btn("Stop", "stop", 1, 48, "pill", "#8A4141"))
    }
}