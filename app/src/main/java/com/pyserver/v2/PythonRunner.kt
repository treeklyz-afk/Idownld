package com.pyserver.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

object PythonRunner {
    fun scriptsDir(context: Context): File = File(context.filesDir, "scripts").apply { mkdirs() }

    fun extractPythonScripts(context: Context) {
        val dir = scriptsDir(context)
        try {
            val files = context.assets.list("scripts") ?: emptyArray()
            for (name in files) {
                val outFile = File(dir, name)
                context.assets.open("scripts/$name").use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                outFile.setReadable(true, false)
            }
        } catch (_: Exception) {
        }
    }

    fun loadAnimationConfig(context: Context): JSONObject {
        return try {
            val raw = context.assets.open("animations/pulse_anim.json").bufferedReader().use { it.readText() }
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject().apply {
                put("fps", 24)
                put("gif_fps", 12)
                put("gif_width", 480)
            }
        }
    }

    fun findPythonExecutable(context: Context): String? {
        val nativePy = File(context.applicationInfo.nativeLibraryDir, "libpython3.so")
        if (nativePy.exists() && nativePy.canExecute()) return nativePy.absolutePath
        val candidates = listOf(
            File(context.filesDir, "bin/python3"),
            File(context.filesDir, "packages/python3"),
            File("/data/data/com.termux/files/usr/bin/python3"),
            File("/system/bin/python3")
        )
        for (c in candidates) {
            if (c.exists() && c.canExecute()) return c.absolutePath
        }
        return null
    }

    fun analyzeWithPythonBridge(context: Context, url: String): Result<MediaAnalysis> {
        val pyBin = findPythonExecutable(context)
            ?: return Result.failure(Exception("Python runtime not present"))
        val scriptFile = File(scriptsDir(context), "engine_bridge.py")
        if (!scriptFile.exists()) return Result.failure(Exception("Bridge script missing"))
        val ytdlpBin = AssetsAndPackageManager.resolveTool(context, "yt-dlp")

        return try {
            val proc = ProcessBuilder(pyBin, scriptFile.absolutePath, ytdlpBin, url)
                .redirectErrorStream(false)
                .start()
            val stdout = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val code = proc.waitFor()
            if (code != 0 || stdout.isBlank()) {
                return Result.failure(Exception("Python bridge exited with $code"))
            }
            val root = JSONObject(stdout)
            if (!root.optBoolean("ok", false)) {
                return Result.failure(Exception(root.optString("error", "Bridge failed")))
            }
            val arr: JSONArray = root.optJSONArray("formats") ?: JSONArray()
            val formats = mutableListOf<FormatChoice>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                formats.add(
                    FormatChoice(
                        formatId = o.optString("format_id", "best"),
                        titleLabel = o.optString("label", "Video"),
                        mediaType = o.optString("media_type", "video"),
                        metaInfo = o.optString("meta", "Python Engine")
                    )
                )
            }
            Result.success(
                MediaAnalysis(
                    title = root.optString("title", "Media"),
                    extractor = root.optString("extractor", "PythonBridge"),
                    duration = root.optString("duration", "N/A"),
                    formats = formats
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
