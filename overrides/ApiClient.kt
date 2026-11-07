package com.k2fsa.sherpa.onnx.vad.asr

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {

    private const val TAG = "SpeakO-api"

    fun translate(provider: ApiProvider, systemPrompt: String, text: String): String? {
        if (provider.endpoint.isBlank() || provider.apiKey.isBlank()) return null
        try {
            val body = JSONObject()
                .put("model", provider.model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", text)))
                .put("temperature", 0.2)
            val resp = postJson(provider.endpoint.trimEnd('/') + "/chat/completions", provider.apiKey, body.toString(), 60000)
            val j = JSONObject(resp ?: return null)
            return j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) { Log.e(TAG, "translate err: $e"); return null }
    }

    fun tts(provider: ApiProvider, text: String, voice: String): ByteArray? {
        if (provider.endpoint.isBlank() || provider.apiKey.isBlank()) return null
        try {
            val body = JSONObject()
                .put("model", provider.model)
                .put("input", text)
                .put("voice", if (voice.isBlank()) "alloy" else voice)
                .put("response_format", "mp3")
            return postJsonBytes(provider.endpoint.trimEnd('/') + "/audio/speech", provider.apiKey, body.toString(), 60000)
        } catch (e: Exception) { Log.e(TAG, "tts err: $e"); return null }
    }

    fun sendToOperit(message: String): Boolean {
        try {
            if (Settings.operitToken.isBlank()) return false
            val body = JSONObject()
                .put("message", message)
                .put("response_mode", "sync")
                .put("show_floating", false)
                .put("return_tool_status", false)
                .put("chat_id", Settings.operitChatId)
            val code = postJsonCode("http://127.0.0.1:" + Settings.operitPort + "/api/external-chat", Settings.operitToken, body.toString(), 120000)
            return code != null && code in 200..299
        } catch (e: Exception) { Log.e(TAG, "operit err: $e"); return false }
    }

    private fun postJson(url: String, key: String, json: String, timeout: Int): String? {
        val c = open(url, key, json, timeout) ?: return null
        return try {
            if (c.responseCode in 200..299) BufferedReader(InputStreamReader(c.inputStream)).readText() else null
        } finally { c.disconnect() }
    }

    private fun postJsonBytes(url: String, key: String, json: String, timeout: Int): ByteArray? {
        val c = open(url, key, json, timeout) ?: return null
        return try { if (c.responseCode in 200..299) c.inputStream.readBytes() else null } finally { c.disconnect() }
    }

    private fun postJsonCode(url: String, key: String, json: String, timeout: Int): Int? {
        val c = open(url, key, json, timeout) ?: return null
        return try { c.responseCode } finally { c.disconnect() }
    }

    private fun open(url: String, key: String, json: String, timeout: Int): HttpURLConnection? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10000
            c.readTimeout = timeout
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Authorization", "Bearer " + key)
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            c
        } catch (e: Exception) { Log.e(TAG, "open err: $e"); null }
    }
}
