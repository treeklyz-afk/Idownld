package com.pyserver.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class FormatChoice(
    val formatId: String,
    val titleLabel: String,
    val mediaType: String, // "video", "gif", "audio"
    val metaInfo: String
)

data class MediaAnalysis(
    val title: String,
    val extractor: String,
    val duration: String,
    val formats: List<FormatChoice>
)

object MediaDownloaderEngine {
    fun outputDirectory(context: Context): File =
        File(context.getExternalFilesDir(null), "PyServer2_Media").apply { mkdirs() }

    fun analyzeUrl(
        context: Context,
        rawUrl: String,
        preferPythonBridge: Boolean = true
    ): Result<MediaAnalysis> {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Result.failure(Exception("URL empty"))

        if (preferPythonBridge) {
            val pyResult = PythonRunner.analyzeWithPythonBridge(context, url)
            if (pyResult.isSuccess) return pyResult
        }

        val ytdlp = AssetsAndPackageManager.resolveTool(context, "yt-dlp")
        val ytdlpFile = File(ytdlp)
        if (!ytdlpFile.exists() || !ytdlpFile.canExecute()) {
            val name = url.substringAfterLast("/").substringBefore("?").ifBlank { "media.mp4" }
            return Result.success(
                MediaAnalysis(
                    title = name,
                    extractor = "DirectFallback",
                    duration = "N/A",
                    formats = listOf(
                        FormatChoice("direct", "Direct File Download", "video", "Direct stream"),
                        FormatChoice("gif_clip", "Animated GIF (480w • 12fps)", "gif", "Requires FFmpeg"),
                        FormatChoice("bestaudio", "Audio Only (MP3)", "audio", "Requires FFmpeg")
                    )
                )
            )
        }

        return try {
            val proc = ProcessBuilder(ytdlp, "-J", "--no-playlist", url)
                .redirectErrorStream(false)
                .start()
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(proc.errorStream)).readText()
            val code = proc.waitFor()
            if (code != 0 || out.isBlank()) {
                return Result.failure(Exception(err.ifBlank { "Exit $code" }))
            }
            val root = JSONObject(out)
            val arr: JSONArray = root.optJSONArray("formats") ?: JSONArray()
            val formats = mutableListOf(
                FormatChoice("best", "Best Quality • Auto Merge", "video", "Highest video + best audio")
            )
            val seen = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val vcodec = item.optString("vcodec", "none")
                val height = item.optInt("height", 0)
                val ext = item.optString("ext", "mp4")
                val fmtId = item.optString("format_id", "")
                if (vcodec != "none" && height > 0 && fmtId.isNotEmpty()) {
                    val label = "${height}p • ${ext.uppercase()}"
                    if (seen.add(label)) {
                        formats.add(FormatChoice(fmtId, label, "video", "Codec: $vcodec"))
                    }
                }
            }
            formats.add(FormatChoice("gif_clip", "Animated GIF (480w • 12fps)", "gif", "Converted via FFmpeg"))
            formats.add(FormatChoice("bestaudio", "Audio Only • MP3", "audio", "Extracted via FFmpeg"))
            Result.success(
                MediaAnalysis(
                    title = root.optString("title", "Media Stream"),
                    extractor = root.optString("extractor_key", "Generic"),
                    duration = root.optString("duration_string", "N/A"),
                    formats = formats
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun downloadMedia(
        context: Context,
        rawUrl: String,
        choice: FormatChoice,
        useAria2: Boolean = true,
        onLog: (String) -> Unit = {}
    ): Result<String> {
        val url = rawUrl.trim()
        val outDir = outputDirectory(context)
        val ytdlp = AssetsAndPackageManager.resolveTool(context, "yt-dlp")
        val ffmpeg = AssetsAndPackageManager.resolveTool(context, "ffmpeg")
        val aria2 = AssetsAndPackageManager.resolveTool(context, "aria2c")

        if (choice.formatId == "direct" || !File(ytdlp).exists() || !File(ytdlp).canExecute()) {
            return directHttpDownload(url, outDir, onLog)
        }

        return try {
            if (choice.mediaType == "gif") {
                val animCfg = PythonRunner.loadAnimationConfig(context)
                val fps = animCfg.optInt("gif_fps", 12)
                val width = animCfg.optInt("gif_width", 480)
                val tempMp4 = File(outDir, "tmp_gif_${System.currentTimeMillis()}.mp4")
                val outGif = File(outDir, "anim_${System.currentTimeMillis()}.gif")

                val dlCmd = mutableListOf(ytdlp, "--no-playlist", "-f", "best[height<=720]/best", "-o", tempMp4.absolutePath)
                if (File(ffmpeg).exists() && File(ffmpeg).canExecute()) {
                    dlCmd.addAll(listOf("--ffmpeg-location", ffmpeg))
                }
                dlCmd.add(url)
                val dlCode = ProcessBuilder(dlCmd).redirectErrorStream(true).start().waitFor()
                if (dlCode != 0 || !tempMp4.exists()) {
                    return Result.failure(Exception("GIF source download failed"))
                }

                val ffCmd = listOf(
                    ffmpeg, "-y", "-i", tempMp4.absolutePath,
                    "-vf", "fps=$fps,scale=$width:-1:flags=lanczos",
                    outGif.absolutePath
                )
                val ffCode = ProcessBuilder(ffCmd).redirectErrorStream(true).start().waitFor()
                if (tempMp4.exists()) tempMp4.delete()
                return if (ffCode == 0 && outGif.exists()) {
                    Result.success("Saved GIF: ${outGif.absolutePath}")
                } else {
                    Result.failure(Exception("FFmpeg GIF conversion failed"))
                }
            }

            val outTemplate = File(outDir, "%(title).75s [%(id)s].%(ext)s").absolutePath
            val cmd = mutableListOf(ytdlp, "--no-playlist", "-o", outTemplate)
            if (File(ffmpeg).exists() && File(ffmpeg).canExecute()) {
                cmd.addAll(listOf("--ffmpeg-location", ffmpeg))
            }
            if (useAria2 && File(aria2).exists() && File(aria2).canExecute()) {
                cmd.addAll(listOf("--downloader", aria2, "--downloader-args", "aria2c:-x 8 -s 8 -k 1M"))
                onLog("Using aria2c")
            }
            when {
                choice.mediaType == "audio" ->
                    cmd.addAll(listOf("-f", "bestaudio/best", "-x", "--audio-format", "mp3"))
                choice.formatId == "best" ->
                    cmd.addAll(listOf("-f", "bv*+ba/b", "--merge-output-format", "mp4"))
                else ->
                    cmd.addAll(listOf("-f", "${choice.formatId}+bestaudio/best", "--merge-output-format", "mp4"))
            }
            cmd.add(url)
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val tail = StringBuilder()
            proc.inputStream.bufferedReader().forEachLine { line ->
                onLog(line)
                if (tail.length > 2000) tail.delete(0, tail.length - 1200)
                tail.append(line).append("\n")
            }
            val code = proc.waitFor()
            if (code == 0) Result.success("Saved in ${outDir.absolutePath}")
            else Result.failure(Exception("Exit $code: ${tail.toString().takeLast(240)}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun directHttpDownload(
        url: String,
        outDir: File,
        onLog: (String) -> Unit
    ): Result<String> {
        return try {
            val name = url.substringAfterLast("/").substringBefore("?")
                .ifBlank { "media_${System.currentTimeMillis()}.mp4" }
            val dest = File(outDir, name)
            onLog("Direct download -> $name")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 60000
            }
            if (conn.responseCode !in 200..299) {
                return Result.failure(Exception("HTTP ${conn.responseCode}"))
            }
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val r = input.read(buf)
                        if (r == -1) break
                        output.write(buf, 0, r)
                    }
                    output.flush()
                }
            }
            conn.disconnect()
            Result.success("Saved to ${dest.absolutePath}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
