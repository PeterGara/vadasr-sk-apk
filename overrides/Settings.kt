package com.k2fsa.sherpa.onnx.vad.asr

import org.json.JSONObject
import java.io.File

/**
 * Nastavenia Speak-o — ukladaju sa do settings.json.
 * Vsetko sa da nastavit v apke (obrazovka Nastavenia).
 */
object Settings {
    private var f: File? = null

    // jazyk rozpoznavania (prepis)
    // "sk" = SK Parakeet (zabudovany, najpresnejsi)
    // ine = externy model z asrDir/<lang>/
    var asrLang = "sk"
    var asrModelDir = "/sdcard/Download/Speak-o/models/asr"

    // preklad: "mlkit" (offline, jednoduche) alebo "gguf" (vlastny model/server)
    var translateEngine = "mlkit"
    var translateEnabled = false
    var translateTarget = "EN"
    // gguf rezim
    var translateUrl = "http://127.0.0.1:8080/v1/chat/completions"
    var translateModel = "gemma"
    var translatePrompt = "You are a translator. Translate the user's text. Reply ONLY with the translation."

    // Operit (volitelne)
    var operitEnabled = false
    var operitPort = "8094"
    var operitToken = ""
    var operitChatId = ""

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

    fun init(dir: File) { f = File(dir, "settings.json"); load() }

    private fun load() {
        val file = f ?: return
        if (!file.exists()) { save(); return }
        try {
            val j = JSONObject(file.readText())
            asrLang = j.optString("asrLang", asrLang)
            asrModelDir = j.optString("asrModelDir", asrModelDir)
            translateEngine = j.optString("translateEngine", translateEngine)
            translateEnabled = j.optBoolean("translateEnabled", false)
            translateTarget = j.optString("translateTarget", "EN")
            translateUrl = j.optString("translateUrl", translateUrl)
            translateModel = j.optString("translateModel", translateModel)
            translatePrompt = j.optString("translatePrompt", translatePrompt)
            operitEnabled = j.optBoolean("operitEnabled", false)
            operitPort = j.optString("operitPort", operitPort)
            operitToken = j.optString("operitToken", "")
            operitChatId = j.optString("operitChatId", "")
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
        } catch (e: Exception) { }
    }

    fun save() {
        val file = f ?: return
        try {
            val j = JSONObject()
            j.put("asrLang", asrLang)
            j.put("asrModelDir", asrModelDir)
            j.put("translateEngine", translateEngine)
            j.put("translateEnabled", translateEnabled)
            j.put("translateTarget", translateTarget)
            j.put("translateUrl", translateUrl)
            j.put("translateModel", translateModel)
            j.put("translatePrompt", translatePrompt)
            j.put("operitEnabled", operitEnabled)
            j.put("operitPort", operitPort)
            j.put("operitToken", operitToken)
            j.put("operitChatId", operitChatId)
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
            file.writeText(j.toString(2))
        } catch (e: Exception) { }
    }
}