package com.k2fsa.sherpa.onnx.vad.asr

import org.json.JSONObject
import java.io.File

/**
 * Nastavenia Speak-o - ukladaju sa do settings.json.
 * Vsetko sa da nastavit v apke (obrazovka Nastavenia).
 */
object Settings {
    private var f: File? = null

    // jazyk rozpoznavania (prepis)
    // "sk" = SK Parakeet (zabudovany, najpresnejsi)
    // ine = externy model z asrDir/<lang>/
    var asrLang = "sk"
    var asrModelDir = "/sdcard/Download/Speak-o/models/asr"

    // VAD / citlivost
    var vadThreshold = 0.5f      // 0.10 - 0.90
    var minSilence = 0.4f        // sekundy ticha na ukoncenie vety
    var noiseSuppress = true     // potlacenie hluku (NS)
    var agc = false              // automaticke zosilnenie

    // preklad: "mlkit" (offline, jednoduche), "gguf" (vlastny model/server) alebo "gemma_box" (TranslateGemma na boxe cez MCP)
    var translateEngine = "mlkit"  // mlkit = offline, nic netreba stahovat rucne
    var translateEnabled = true
    var translateTarget = "EN"
    // gguf rezim
    var translateUrl = "http://127.0.0.1:8080/v1/chat/completions"
    var translateModel = "gemma"
    var translatePrompt = "You are a translator. Translate the user's text. Reply ONLY with the translation."
    // gemma_box rezim (MCP tunel na box)
    var boxMcpUrl = "https://mcp.box.simular.ai/mcp"
    var boxMcpKey = "cb_3b247d9465d5653519de835eac8fc78f0b9d4f6227f95dcde7d918d50fd18d06"

    // zvuk pri kazdom prepise vety na pozadi (nerusivy potvrdeni tuk)
    var chirpOnTranscribe = true
    var chirpVolume = 0.3f

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

    fun init(dir: File) {
        f = File(dir, "settings.json")
        load()
        // ak cesta k modelom neexistuje, skus najst automaticky
        if (asrModelDir.isBlank() || !File(asrModelDir).exists()) {
            val kandidati = listOf(
                "/sdcard/Download/Speak-o/models/asr",
                "/sdcard/Speak-o/models/asr",
                "/sdcard/Documents/Speak-o/models/asr"
            )
            for (k in kandidati) {
                if (File(k).exists()) { asrModelDir = k; save(); break }
            }
        }
        // ak stale nic, vytvor predvolenu
        try { File(asrModelDir).mkdirs() } catch (e: Exception) { }
    }

    private fun load() {
        val file = f ?: return
        if (!file.exists()) { save(); return }
        try {
            val j = JSONObject(file.readText())
            asrLang = j.optString("asrLang", asrLang)
            asrModelDir = j.optString("asrModelDir", asrModelDir)
            vadThreshold = j.optDouble("vadThreshold", 0.5).toFloat()
            minSilence = j.optDouble("minSilence", 0.4).toFloat()
            noiseSuppress = j.optBoolean("noiseSuppress", true)
            agc = j.optBoolean("agc", false)
            translateEngine = j.optString("translateEngine", translateEngine)
            translateEnabled = j.optBoolean("translateEnabled", false)
            translateTarget = j.optString("translateTarget", "EN")
            translateUrl = j.optString("translateUrl", translateUrl)
            translateModel = j.optString("translateModel", translateModel)
            translatePrompt = j.optString("translatePrompt", translatePrompt)
            boxMcpUrl = j.optString("boxMcpUrl", boxMcpUrl)
            boxMcpKey = j.optString("boxMcpKey", boxMcpKey)
            chirpOnTranscribe = j.optBoolean("chirpOnTranscribe", true)
            chirpVolume = j.optDouble("chirpVolume", 0.3).toFloat()
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
            j.put("vadThreshold", vadThreshold.toDouble())
            j.put("minSilence", minSilence.toDouble())
            j.put("noiseSuppress", noiseSuppress)
            j.put("agc", agc)
            j.put("translateEngine", translateEngine)
            j.put("translateEnabled", translateEnabled)
            j.put("translateTarget", translateTarget)
            j.put("translateUrl", translateUrl)
            j.put("translateModel", translateModel)
            j.put("translatePrompt", translatePrompt)
            j.put("boxMcpUrl", boxMcpUrl)
            j.put("boxMcpKey", boxMcpKey)
            j.put("chirpOnTranscribe", chirpOnTranscribe)
            j.put("chirpVolume", chirpVolume.toDouble())
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