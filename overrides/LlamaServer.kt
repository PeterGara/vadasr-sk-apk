package com.k2fsa.sherpa.onnx.vad.asr

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * LLAMA MANAGER - spusti/zastav llama-server priamo v appke.
 *
 * Binarky su zabalene v APK ako jniLibs/lib*.so -> Android ich extrahuje
 * do nativeLibraryDir (spustitelne).
 *
 * Model (.gguf) si appka najde v:
 *   /sdcard/Download/Speak-o/models/llm/
 */
object LlamaServer {

    private const val TAG = "sherpa-onnx"
    const val PORT = 8080
    private var process: Process? = null

    /** Najde model .gguf v danom priecinku. */
    fun findModel(ctx: Context): File? {
        val dirs = listOf(
            File("/sdcard/Download/Speak-o/models/llm"),
            File("/sdcard/Speak-o/models/llm"),
            File("/sdcard/Download/Operit/models/llama")
        )
        for (d in dirs) {
            if (!d.exists()) continue
            val g = d.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) && !f.name.contains("mmproj", true) }
            if (!g.isNullOrEmpty()) return g.maxByOrNull { it.length() }
        }
        return null
    }

    /** Najde mmproj (vision) ak existuje. */
    fun findMmproj(model: File): File? {
        val d = model.parentFile ?: return null
        return d.listFiles { f -> f.isFile && f.name.contains("mmproj", true) && f.name.endsWith(".gguf", true) }
            ?.firstOrNull()
    }

    fun isRunning(): Boolean {
        process?.let { return it.isAlive }
        // skus aj HTTP ping
        return try {
            val s = java.net.Socket()
            s.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 300)
            s.close()
            true
        } catch (e: Exception) { false }
    }

    /** Spusti llama-server s danym modelom. Vrati chybovu spravu alebo null pri uspechu. */
    fun start(ctx: Context, model: File, threads: Int = 4, contextSize: Int = 4096, onLog: ((String) -> Unit)? = null): String? {
        if (isRunning()) return "Server uz bezi na porte $PORT"

        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        // binarka bola zabalena ako libllama_server.so -> hladame
        val serverBin = listOf(
            File(nativeDir, "libllama_server.so"),
            File(nativeDir, "llama-server")
        ).firstOrNull { it.exists() } ?: return "llama-server binarka sa nenasla v $nativeDir"

        val mmproj = findMmproj(model)

        val cmd = ArrayList<String>()
        cmd.add(serverBin.absolutePath)
        cmd.add("--model"); cmd.add(model.absolutePath)
        cmd.add("-c"); cmd.add(contextSize.toString())
        cmd.add("-t"); cmd.add(threads.toString())
        cmd.add("--host"); cmd.add("127.0.0.1")
        cmd.add("--port"); cmd.add(PORT.toString())
        cmd.add("-ngl"); cmd.add("0")
        if (mmproj != null) { cmd.add("--mmproj"); cmd.add(mmproj.absolutePath) }

        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(ctx.filesDir)
            pb.redirectErrorStream(true)
            // LD_LIBRARY_PATH na nativeLibraryDir
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            val p = pb.start()
            process = p
            onLog?.invoke("Spustene: " + cmd.joinToString(" "))

            // citaj vystup na pozadi
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        onLog?.invoke(line)
                        android.util.Log.i(TAG, "llama: $line")
                    }
                } catch (e: Exception) { }
            }.start()

            // pockaj kym zacne pocuvat (max 60s)
            var ok = false
            for (i in 0 until 120) {
                Thread.sleep(500)
                if (!p.isAlive) return "Proces skoncil. Skontroluj log."
                if (isRunning()) { ok = true; break }
            }
            if (!ok) "Server sa nespustil do 60s" else null
        } catch (e: Exception) {
            "Chyba spustenia: " + e.message
        }
    }

    fun stop() {
        try { process?.destroy() } catch (e: Exception) { }
        process = null
    }
}