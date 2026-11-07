package com.k2fsa.sherpa.onnx.vad.asr

import org.json.JSONObject
import java.io.File

data class ApiProvider(
    var name: String = "",
    var endpoint: String = "",
    var apiKey: String = "",
    var model: String = ""
)

object Settings {
    private var f: File? = null
    var providers = ArrayList<ApiProvider>()
    var translateProviderIndex = 0
    var translateSystemPrompt = "You are a translator. Translate accurately, keep meaning and style. Reply ONLY with the translation."
    var translateTarget = "EN"
    var ttsMode = "android"
    var ttsProviderIndex = 0
    var ttsVoice = ""
    var ttsLang = "sk"
    var asrLang = "sk"
    var operitPort = "8094"
    var operitToken = ""
    var operitChatId = ""
    var keepScreenOn = true

    fun init(dir: File) { f = File(dir, "settings.json"); load() }

    private fun load() {
        val file = f ?: return
        if (!file.exists()) { save(); return }
        try {
            val j = JSONObject(file.readText())
            providers.clear()
            val arr = j.optJSONArray("providers")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                providers.add(ApiProvider(o.optString("name"), o.optString("endpoint"), o.optString("apiKey"), o.optString("model")))
            }
            translateProviderIndex = j.optInt("translateProviderIndex", 0)
            translateSystemPrompt = j.optString("translateSystemPrompt", translateSystemPrompt)
            translateTarget = j.optString("translateTarget", "EN")
            ttsMode = j.optString("ttsMode", "android")
            ttsProviderIndex = j.optInt("ttsProviderIndex", 0)
            ttsVoice = j.optString("ttsVoice", "")
            ttsLang = j.optString("ttsLang", "sk")
            asrLang = j.optString("asrLang", "sk")
            operitPort = j.optString("operitPort", "8094")
            operitToken = j.optString("operitToken", "")
            operitChatId = j.optString("operitChatId", "")
            keepScreenOn = j.optBoolean("keepScreenOn", true)
        } catch (e: Exception) { }
    }

    fun save() {
        val file = f ?: return
        try {
            val j = JSONObject()
            val arr = org.json.JSONArray()
            for (p in providers) arr.put(JSONObject().put("name", p.name).put("endpoint", p.endpoint).put("apiKey", p.apiKey).put("model", p.model))
            j.put("providers", arr)
            j.put("translateProviderIndex", translateProviderIndex)
            j.put("translateSystemPrompt", translateSystemPrompt)
            j.put("translateTarget", translateTarget)
            j.put("ttsMode", ttsMode)
            j.put("ttsProviderIndex", ttsProviderIndex)
            j.put("ttsVoice", ttsVoice)
            j.put("ttsLang", ttsLang)
            j.put("asrLang", asrLang)
            j.put("operitPort", operitPort)
            j.put("operitToken", operitToken)
            j.put("operitChatId", operitChatId)
            j.put("keepScreenOn", keepScreenOn)
            file.writeText(j.toString(2))
        } catch (e: Exception) { }
    }
}
