package com.k2fsa.sherpa.onnx.vad.asr

import org.json.JSONObject
import java.io.File

/**
 * Nastavenia Speak-o — ukladaju sa do settings.json (bez cloud API klucov).
 * Kazdy si ich nastavi v apke (obrazovka Nastavenia), netreba editovat subory.
 */
object Settings {
    private var f: File? = null

    // jazyk rozpoznavania
    var asrLang = "sk"

    // Operit (VOLITELNE - kto nema, nechava prazdne)
    var operitEnabled = false
    var operitPort = "8094"
    var operitToken = ""
    var operitChatId = ""

    // Preklad — lokalny server (llama.cpp / OpenAI-kompatibilny). Zadarmo, offline.
    var translateEnabled = false
    var translateUrl = "http://127.0.0.1:8080/v1/chat/completions"
    var translateModel = "gemma"
    var translateTarget = "EN"
    var translatePrompt = "You are a translator. Translate the user's text. Reply ONLY with the translation, no explanations."

    // pipanie
    var beep = true
    var beepVolume = 0.8f
    var beepToneMs = 200
    var beepSend = 1
    var beepErr = 2
    var beepDelVeta = 1
    var beepDelOdstavec = 2
    var beepClear = 3
    var beepSave = 2
    var beepStart = 1
    var beepStop = 1

    // ostatne
    var keepScreenOn = true
    var cmdMaxWords = 4
    var threshold = 0.5f
    var minSilence = 0.4f

    fun init(dir: File) { f = File(dir, "settings.json"); load() }

    private fun load() {
        val file = f ?: return
        if (!file.exists()) { save(); return }
        try {
            val j = JSONObject(file.readText())
            asrLang = j.optString("asrLang", asrLang)
            operitEnabled = j.optBoolean("operitEnabled", false)
            operitPort = j.optString("operitPort", operitPort)
            operitToken = j.optString("operitToken", "")
            operitChatId = j.optString("operitChatId", "")
            translateEnabled = j.optBoolean("translateEnabled", false)
            translateUrl = j.optString("translateUrl", translateUrl)
            translateModel = j.optString("translateModel", translateModel)
            translateTarget = j.optString("translateTarget", "EN")
            translatePrompt = j.optString("translatePrompt", translatePrompt)
            beep = j.optBoolean("beep", true)
            beepVolume = j.optDouble("beepVolume", 0.8).toFloat()
            beepToneMs = j.optInt("beepToneMs", 200)
            beepSend = j.optInt("beepSend", 1)
            beepErr = j.optInt("beepErr", 2)
            beepDelVeta = j.optInt("beepDelVeta", 1)
            beepDelOdstavec = j.optInt("beepDelOdstavec", 2)
            beepClear = j.optInt("beepClear", 3)
            beepSave = j.optInt("beepSave", 2)
            beepStart = j.optInt("beepStart", 1)
            beepStop = j.optInt("beepStop", 1)
            keepScreenOn = j.optBoolean("keepScreenOn", true)
            cmdMaxWords = j.optInt("cmdMaxWords", 4)
            threshold = j.optDouble("threshold", 0.5).toFloat()
            minSilence = j.optDouble("minSilence", 0.4).toFloat()
        } catch (e: Exception) { }
    }

    fun save() {
        val file = f ?: return
        try {
            val j = JSONObject()
            j.put("asrLang", asrLang)
            j.put("operitEnabled", operitEnabled)
            j.put("operitPort", operitPort)
            j.put("operitToken", operitToken)
            j.put("operitChatId", operitChatId)
            j.put("translateEnabled", translateEnabled)
            j.put("translateUrl", translateUrl)
            j.put("translateModel", translateModel)
            j.put("translateTarget", translateTarget)
            j.put("translatePrompt", translatePrompt)
            j.put("beep", beep)
            j.put("beepVolume", beepVolume.toDouble())
            j.put("beepToneMs", beepToneMs)
            j.put("beepSend", beepSend)
            j.put("beepErr", beepErr)
            j.put("beepDelVeta", beepDelVeta)
            j.put("beepDelOdstavec", beepDelOdstavec)
            j.put("beepClear", beepClear)
            j.put("beepSave", beepSave)
            j.put("beepStart", beepStart)
            j.put("beepStop", beepStop)
            j.put("keepScreenOn", keepScreenOn)
            j.put("cmdMaxWords", cmdMaxWords)
            j.put("threshold", threshold.toDouble())
            j.put("minSilence", minSilence.toDouble())
            file.writeText(j.toString(2))
        } catch (e: Exception) { }
    }
}