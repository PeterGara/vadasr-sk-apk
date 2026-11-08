package com.k2fsa.sherpa.onnx.vad.asr

import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** Preklad: ML Kit (offline, jednoduche) alebo GGUF server (vlastny model). */
object Translate {

    private const val TAG = "SpeakO-tr"

    private fun code(lang: String): String = when (lang.uppercase()) {
        "SK" -> TranslateLanguage.SLOVAK
        "EN" -> TranslateLanguage.ENGLISH
        "DE" -> TranslateLanguage.GERMAN
        "FR" -> TranslateLanguage.FRENCH
        "ES" -> TranslateLanguage.SPANISH
        "CZ", "CS" -> TranslateLanguage.CZECH
        "PL" -> TranslateLanguage.POLISH
        "IT" -> TranslateLanguage.ITALIAN
        else -> TranslateLanguage.ENGLISH
    }

    /** Pri ktory jazyk sa uklada z druheho; potrebne pre ML Kit (treba 2 jazyky). */
    suspend fun translateMlkit(text: String, fromLang: String, toLang: String): String? {
        return try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(code(fromLang))
                .setTargetLanguage(code(toLang))
                .build()
            val translator = Translation.getClient(options)
            // stiahni model (ak nie je)
            val downloaded = suspendCancellableCoroutine<Boolean> { cont ->
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { cont.resume(false) }
            }
            if (!downloaded) { translator.close(); return null }
            val res = suspendCancellableCoroutine<String?> { cont ->
                translator.translate(text)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
            translator.close()
            res
        } catch (e: Exception) { Log.e(TAG, "mlkit err: $e"); null }
    }

    /** Stiahne ML Kit model pre jazyk (aby to slo offline neskor). */
    suspend fun downloadMlkit(fromLang: String, toLang: String): Boolean {
        return try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(code(fromLang))
                .setTargetLanguage(code(toLang))
                .build()
            val t = Translation.getClient(options)
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                t.downloadModelIfNeeded()
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { cont.resume(false) }
            }
            t.close(); ok
        } catch (e: Exception) { false }
    }

    /** GGUF cez lokalny OpenAI-kompatibilny server. */
    fun translateGguf(text: String, toLang: String): String? {
        return try {
            val body = JSONObject()
                .put("model", Settings.translateModel.ifBlank { "gemma" })
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", Settings.translatePrompt + " Target language: " + toLang))
                    .put(JSONObject().put("role", "user").put("content", text)))
                .put("temperature", 0.2)
            val c = URL(Settings.translateUrl).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 8000; c.readTimeout = 120000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            if (c.responseCode !in 200..299) { c.disconnect(); return null }
            val resp = BufferedReader(InputStreamReader(c.inputStream)).readText(); c.disconnect()
            JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) { Log.e(TAG, "gguf err: $e"); null }
    }

    /** TranslateGemma na boxe - volanie cez MCP tunel (mcp.box.simular.ai). */
    fun translateGemmaBox(text: String, fromLang: String, toLang: String): String? {
        // pozor: text ide do shell prikazu na boxe - escapneme uvodzovky a spatne lomitka
        val esc = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
        val from = fromLang.lowercase()
        val to = toLang.lowercase()
        val curl = "curl -s --max-time 60 http://127.0.0.1:8080/v1/chat/completions " +
                "-H \"Content-Type: application/json\" " +
                "-d \"{\\\"messages\\\":[{\\\"role\\\":\\\"user\\\",\\\"content\\\":\\\"$esc\\\"}]," +
                "\\\"max_tokens\\\":200," +
                "\\\"chat_template_kwargs\\\":{\\\"source_lang_code\\\":\\\"$from\\\",\\\"target_lang_code\\\":\\\"$to\\\"}}\""
        return try {
            // 1. initialize -> dostaneme session id
            val initBody = JSONObject()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                .put("params", JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "speako").put("version", "1")))
            val (sid, _) = mcpPost(initBody.toString(), null)
            if (sid.isNullOrBlank()) return null
            // initialized notifikacia
            val notif = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString()
            mcpPost(notif, sid)
            // 2. tools/call shell
            val call = JSONObject()
                .put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                .put("params", JSONObject().put("name", "shell")
                    .put("arguments", JSONObject().put("command", curl)))
            val (_, resp) = mcpPost(call.toString(), sid)
            // 3. vytiahni prelozeny text z output
            parseShellResult(resp)
        } catch (e: Exception) { Log.e(TAG, "gemma_box err: $e"); null }
    }

    private fun mcpPost(body: String, sid: String?): Pair<String?, String> {
        val c = URL(Settings.boxMcpUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 90000
        c.setRequestProperty("Authorization", "Bearer " + Settings.boxMcpKey)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        if (sid != null) c.setRequestProperty("mcp-session-id", sid)
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
        if (c.responseCode !in 200..299) { c.disconnect(); return Pair(null, "") }
        val resp = BufferedReader(InputStreamReader(c.inputStream)).readText(); c.disconnect()
        return Pair(c.headerFields?.get("mcp-session-id")?.firstOrNull(), resp)
    }

    /** Z SSE streamu MCP vytiahne text vysledku shell prikazu. */
    private fun parseShellResult(sse: String): String? {
        for (line in sse.split("\n")) {
            var l = line.trim()
            if (l.startsWith("data:")) l = l.substring(5).trim()
            if (!l.startsWith("{")) continue
            try {
                val d = JSONObject(l)
                val content = d.optJSONObject("result")?.optJSONArray("content") ?: continue
                for (i in 0 until content.length()) {
                    val cItem = content.getJSONObject(i)
                    if (cItem.optString("type") == "text") {
                        val t = cItem.optString("text")
                        // hladame content pole v JSON odpovedi od llama-servera
                        val contentStart = t.indexOf("\"content\":\"")
                        if (contentStart >= 0) {
                            val rest = t.substring(contentStart + 11)
                            val end = rest.indexOf("\",")
                            if (end > 0) return unescapeJson(rest.substring(0, end))
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        return null
    }

    private fun unescapeJson(s: String): String {
        return s.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", "\n").replace("\\u0027", "'")
    }
}