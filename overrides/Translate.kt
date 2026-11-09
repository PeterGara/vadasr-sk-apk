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
        val from = if (Settings.asrLang == "multi") "sk" else Settings.asrLang.lowercase()
        val to = toLang.lowercase()
        // preklad z neznámeho zdroja: source_lang_code vynechame (TranslateGemma ho odhadne)
        val ktArgs = if (Settings.asrLang == "multi") "\"target_lang_code\":\"$to\"" else "\"source_lang_code\":\"$from\",\"target_lang_code\":\"$to\""
        val curl = "curl -s --max-time 60 http://127.0.0.1:8080/v1/chat/completions " +
                "-H \"Content-Type: application/json\" " +
                "-d \"{\\\"messages\\\":[{\\\"role\\\":\\\"user\\\",\\\"content\\\":\\\"$esc\\\"}]," +
                "\\\"max_tokens\\\":200,\\\"chat_template_kwargs\\\":{$ktArgs}}\""
        return try {
            // 0. over, ci na boxe bezi llama-server; ak nie, spust ho a pockaj na load
            DebugLog.log("BOX", "translateGemmaBox: start, from=$from to=$to")
            ensureBoxServer()
            // 1. initialize -> dostaneme session id
            val initBody = JSONObject()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                .put("params", JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "speako").put("version", "1")))
            val (sid, _) = mcpPost(initBody.toString(), null)
            if (sid.isNullOrBlank()) { DebugLog.log("BOX", "gemma_box: NEDOSTAL som session id"); Log.e(TAG, "gemma_box: neziskal som session id"); return null }
            DebugLog.log("BOX", "gemma_box: session id OK: $sid")
            // initialized notifikacia
            val notif = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString()
            mcpPost(notif, sid)
            // 2. tools/call shell
            val call = JSONObject()
                .put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                .put("params", JSONObject().put("name", "shell")
                    .put("arguments", JSONObject().put("command", curl)))
            val (_, resp) = mcpPost(call.toString(), sid)
            DebugLog.log("BOX", "gemma_box: MCP odpoved (first 300): ${resp.take(300)}")
            // 3. vytiahni prelozeny text z output
            val out = parseShellResult(resp)
            DebugLog.log("BOX", "gemma_box: parseShellResult: ${out?.take(120) ?: "NULL"}")
            out
        } catch (e: Exception) { Log.e(TAG, "gemma_box err: $e"); null }
    }

    /** Ak na boxe nebezi llama-server (vyhlnul sa / reštart), spusti ho cez MCP a pockaj na load. */
    private fun ensureBoxServer() {
        try {
            val initBody = JSONObject()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                .put("params", JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "speako").put("version", "1")))
            val (sid, _) = mcpPost(initBody.toString(), null)
            if (sid.isNullOrBlank()) return
            val notif = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString()
            mcpPost(notif, sid)

            // rychla kontrola health
            val check = JSONObject()
                .put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                .put("params", JSONObject().put("name", "shell")
                    .put("arguments", JSONObject().put("command", "curl -s -o /dev/null -w \"%{http_code}\" --max-time 5 http://127.0.0.1:8080/health")))
            val (_, resp) = mcpPost(check.toString(), sid)
            val healthy = resp.contains("200")
            if (!healthy) {
                Log.w(TAG, "box server nebezi - spustam")
                val start = JSONObject()
                    .put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
                    .put("params", JSONObject().put("name", "shell")
                        .put("arguments", JSONObject().put("command",
                            "nohup /home/user/llama/build/bin/llama-server -m /home/user/models/translategemma-4b-it-Q4_K_M.gguf --host 0.0.0.0 --port 8080 -t 4 -c 4096 > /home/user/server.log 2>&1 & echo STARTED")))
                mcpPost(start.toString(), sid)
                // pockaj na load modelu (~15-25 s)
                try { Thread.sleep(20000) } catch (e: InterruptedException) { }
                // over, ci uz ide
                val check2 = JSONObject()
                    .put("jsonrpc", "2.0").put("id", 4).put("method", "tools/call")
                    .put("params", JSONObject().put("name", "shell")
                        .put("arguments", JSONObject().put("command", "curl -s -o /dev/null -w \"%{http_code}\" --max-time 5 http://127.0.0.1:8080/health")))
                val (_, resp2) = mcpPost(check2.toString(), sid)
                Log.i(TAG, "box server po starte: " + (if (resp2.contains("200")) "OK" else "NEIDE"))
            }
        } catch (e: Exception) { Log.e(TAG, "ensureBoxServer err: $e") }
    }

    private fun mcpPost(body: String, sid: String?): Pair<String?, String> {
        DebugLog.log("MCP", "POST ${Settings.boxMcpUrl} sid=${sid?.take(10) ?: "null"} body=${body.take(80)}")
        val c = URL(Settings.boxMcpUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 90000
        c.setRequestProperty("Authorization", "Bearer " + Settings.boxMcpKey)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        if (sid != null) c.setRequestProperty("mcp-session-id", sid)
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
        if (c.responseCode !in 200..299) { DebugLog.log("MCP", "HTTP ERROR: ${c.responseCode}"); c.disconnect(); return Pair(null, "") }
        val resp = BufferedReader(InputStreamReader(c.inputStream)).readText()
        // Hlavičky v Jave su case-sensitive - hladame session id case-insensitive
        var sessId: String? = null
        try {
            for (key in c.headerFields.keys) {
                if (key != null && key.equals("mcp-session-id", ignoreCase = true)) {
                    sessId = c.headerFields[key]?.firstOrNull()
                    break
                }
            }
        } catch (e: Exception) { }
        c.disconnect()
        return Pair(sessId, resp)
    }

    /** Z SSE/JSON streamu MCP vytiahne text vysledku shell prikazu -> preklad. */
    private fun parseShellResult(sse: String): String? {
        var shellText: String? = null
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
                        shellText = cItem.optString("text")
                        break
                    }
                }
                if (shellText != null) break
            } catch (e: Exception) { }
        }
        if (shellText.isNullOrBlank()) return null
        val t = shellText.trim()
        // shellText je vystup z curl -> JSON od llama-servera. Skus zparsovat poriadne.
        try {
            val j = JSONObject(t)
            return j.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            // nie je to cisty JSON - skus najst "content":"..." robustne
            return extractContentField(t)
        }
    }

    /** Fallback: vytiahne hodnotu "content":"..." z lubovolneho textu (escapovane aj nie). */
    private fun extractContentField(s: String): String? {
        val key = "\"content\":\""
        var idx = s.indexOf(key)
        if (idx < 0) return null
        idx += key.length
        val sb = StringBuilder()
        var i = idx
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(n)
                }
                i += 2
                continue
            }
            if (c == '"') break  // koniec hodnoty
            sb.append(c)
            i++
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) null else out
    }
}