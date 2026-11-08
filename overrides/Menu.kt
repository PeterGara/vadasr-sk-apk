package com.k2fsa.sherpa.onnx.vad.asr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MENU - celé menu sa definuje v menu.json, appka ho vykreslí.
 * Mení sa za behu, BEZ BUILDU.
 *
 * Format:
 * [
 *   {"text":"Nastavenia", "action":"screen:settings"},
 *   {"text":"Vzhľad", "action":"screen:theme"},
 *   {"text":"Tlačidlá", "action":"screen:buttons"},
 *   {"text":"Prepísať nahrávku", "action":"action:transcribe_file"},
 *   {"text":"Moja vec", "action":"copy+beep:2"}
 * ]
 *
 * ACTION typy:
 *   screen:settings | screen:theme | screen:buttons | screen:menu   -> otvorí obrazovku
 *   action:xxx                                                       -> interná akcia (Engine)
 *   url:https://...                                                  -> otvorí odkaz
 *   xxx                                                              -> hocičo iné = Engine akcia
 */
object Menu {

    data class Item(
        var text: String = "Položka",
        var action: String = "noop"
    )

    private var f: File? = null
    val items = ArrayList<Item>()

    fun init(dir: File) {
        f = File(dir, "menu.json")
        if (!f!!.exists()) { defaults(); save(); return }
        load()
        if (items.isEmpty()) { defaults(); save() }
    }

    fun load() {
        val file = f ?: return
        items.clear()
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(Item(o.optString("text", "Položka"), o.optString("action", "noop")))
            }
        } catch (e: Exception) { }
    }

    fun save() {
        val file = f ?: return
        try {
            val arr = JSONArray()
            for (it in items) arr.put(JSONObject().put("text", it.text).put("action", it.action))
            file.writeText(arr.toString(2))
        } catch (e: Exception) { }
    }

    fun defaults() {
        items.clear()
        items.add(Item("Nastavenia", "screen:settings"))
        items.add(Item("Vzhľad (farby, gradient)", "screen:theme"))
        items.add(Item("Modely (stiahnut, vybrat)", "screen:models"))
        items.add(Item("Tlačidlá (tvar, veľkosť)", "screen:buttons"))
        items.add(Item("Upraviť menu", "screen:menu"))
        items.add(Item("Prekladač (llama, offline)", "screen:llama"))
        items.add(Item("Prepis nahrávky (súbor)", "action:transcribe_file"))
        items.add(Item("Preklad (teraz)", "action:translate"))
        items.add(Item("Orezať nahrávku", "action:trim_audio"))
        items.add(Item("Poslať do Operitu", "action:send_new"))
        items.add(Item("Zmazať vetu", "action:del_veta"))
        items.add(Item("Zmazať odstavec", "action:del_odstavec"))
        items.add(Item("Zmazať všetko", "action:clear"))
        items.add(Item("Späť", "action:undo"))
        items.add(Item("Uložiť TXT", "action:save"))
        items.add(Item("Kopírovať", "action:copy"))
        items.add(Item("Zdieľať", "action:share"))
        items.add(Item("Notifikácia", "action:notify"))
        items.add(Item("Vložiť do appky", "action:paste_app"))
        items.add(Item("Zastaviť nahrávanie", "action:stop"))
    }
}