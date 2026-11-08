package com.k2fsa.sherpa.onnx.vad.asr

import android.util.Log
import com.google.mlkit.nl.translate.DownloadConditions
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
            val cond = DownloadConditions.Builder().build()
            val downloaded = suspendCancellableCoroutine<Boolean> { cont ->
                translator.downloadModelIfNeeded(cond)
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
                t.downloadModelIfNeeded(DownloadConditions.Builder().build())
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
}