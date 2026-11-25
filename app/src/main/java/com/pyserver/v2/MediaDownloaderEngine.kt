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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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
        File(context.getExternalFilesDir(null), "PyServer2_Downloads").apply { mkdirs() }

    fun analyzeUrl(
        context: Context,
        rawUrl: String,
        preferPythonBridge: Boolean = false
    ): Result<MediaAnalysis> {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Result.failure(Exception("URL paste karein"))
        val parsedUrl = try { URL(url) } catch (_: Exception) {
            return Result.failure(Exception("Enter a valid HTTP or HTTPS URL"))
        }
        if (parsedUrl.protocol !in setOf("http", "https") || parsedUrl.host.isBlank()) {
            return Result.failure(Exception("Only HTTP and HTTPS URLs are supported"))
        }

        if (preferPythonBridge) {
            val pyRes = PythonRunner.analyzeWithPythonBridge(context, url)
            if (pyRes.isSuccess) return pyRes
        }

        val ytdlp = AssetsAndPackageManager.resolveTool(context, "yt-dlp")
        if (AssetsAndPackageManager.canExecuteFile(ytdlp)) {
            try {
                val logFile = File(context.cacheDir, "ytdlp_analyze_${System.currentTimeMillis()}.log")
                val proc = ProcessBuilder(ytdlp, "-J", "--no-playlist", url)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .start()
                val finished = proc.waitFor(60, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    proc.waitFor(2, TimeUnit.SECONDS)
                    logFile.delete()
                    return Result.failure(Exception("Media analysis timed out"))
                }
                val out = if (logFile.exists()) logFile.readText().take(8 * 1024 * 1024) else ""
                logFile.delete()
                val code = proc.exitValue()
                if (code == 0 && out.isNotBlank()) {
                    val root = JSONObject(out)
                    val arr: JSONArray = root.optJSONArray("formats") ?: JSONArray()
                    val list = mutableListOf(
                        FormatChoice("best", "Best Quality • Auto Merge", "video", "Highest video + audio")
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
                                list.add(FormatChoice(fmtId, label, "video", "Stream $fmtId"))
                            }
                        }
                    }
                    list.add(FormatChoice("gif_clip", "GIF Animation (480w • 12fps)", "gif", "Looping GIF"))
                    list.add(FormatChoice("bestaudio", "Audio Only (MP3 / M4A)", "audio", "Sound track"))
                    return Result.success(
                        MediaAnalysis(
                            title = root.optString("title", "Media Stream"),
                            extractor = root.optString("extractor_key", "yt-dlp"),
                            duration = root.optString("duration_string", "Stream"),
                            formats = list
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }

        // Reliable Android Native Stream & Metadata Analyzer (works even when binary exec is blocked)
        return nativeAnalyzeFallback(url)
    }

    private fun nativeAnalyzeFallback(url: String): Result<MediaAnalysis> {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            return Result.failure(Exception("Enter a valid HTTP or HTTPS media URL"))
        }
        if (parsed.protocol !in setOf("http", "https")) {
            return Result.failure(Exception("Only HTTP and HTTPS media URLs are supported"))
        }
        val host = parsed.host.lowercase()
        if (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host.endsWith(".youtu.be")) {
            return Result.failure(Exception("This site needs a compatible yt-dlp runtime; no verified runtime is available in this APK"))
        }

        return try {
            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = conn.responseCode
            val contentType = (conn.contentType ?: "").substringBefore(';').trim().lowercase()
            val finalUrl = conn.url.toString()
            val disposition = conn.getHeaderField("Content-Disposition") ?: ""
            conn.disconnect()
            if (code !in 200..299) {
                return Result.failure(Exception("Media server returned HTTP $code"))
            }
            if (contentType == "text/html" || contentType.contains("mpegurl") || finalUrl.substringBefore('?').endsWith(".m3u8", true)) {
                return Result.failure(Exception("This page/playlist requires a compatible media extractor; direct-file fallback cannot process it"))
            }

            val path = finalUrl.substringBefore('?').substringAfterLast('/').lowercase()
            val knownExt = path.substringAfterLast('.', "")
            val mediaType = when {
                contentType.startsWith("video/") || knownExt in setOf("mp4", "m4v", "mov", "webm", "mkv") -> "video"
                contentType.startsWith("audio/") || knownExt in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") -> "audio"
                contentType == "image/gif" || knownExt == "gif" -> "gif"
                else -> return Result.failure(Exception("URL did not identify a supported direct media file (server type: ${contentType.ifBlank { "unknown" }})"))
            }
            val title = disposition.substringAfter("filename=", "").trim().trim('"').ifBlank { path.ifBlank { "Media file" } }
            val kind = when (mediaType) {
                "audio" -> "Audio file"
                "gif" -> "GIF image"
                else -> "Video file"
            }
            Result.success(
                MediaAnalysis(
                    title = title,
                    extractor = "Direct file",
                    duration = "Original stream",
                    formats = listOf(FormatChoice("direct", "$kind (original)", mediaType, "Downloaded without conversion"))
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception("Could not inspect this URL as a direct media file: ${e.message}"))
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
        if (url.isEmpty()) return Result.failure(Exception("URL empty"))
        val outDir = outputDirectory(context)
        if (choice.formatId == "direct") {
            return nativeStreamDownload(url, choice, outDir, onLog)
        }

        val ytdlp = AssetsAndPackageManager.resolveTool(context, "yt-dlp")
        val ffmpeg = AssetsAndPackageManager.resolveTool(context, "ffmpeg")
        val aria2 = AssetsAndPackageManager.resolveTool(context, "aria2c")

        if (AssetsAndPackageManager.canExecuteFile(ytdlp)) {
            try {
                if (choice.mediaType == "gif") {
                    if (!AssetsAndPackageManager.canExecuteFile(ffmpeg)) {
                        return Result.failure(Exception("GIF conversion requires an Android-compatible FFmpeg runtime"))
                    }
                    val animCfg = PythonRunner.loadAnimationConfig(context)
                    val fps = animCfg.optInt("gif_fps", 12)
                    val width = animCfg.optInt("gif_width", 480)
                    val tempMp4 = File(outDir, "tmp_gif_${System.currentTimeMillis()}.mp4")
                    val outGif = File(outDir, "anim_${System.currentTimeMillis()}.gif")
                    val dlCmd = mutableListOf(ytdlp, "--no-playlist", "-f", "best[height<=720]/best", "-o", tempMp4.absolutePath)
                    if (AssetsAndPackageManager.canExecuteFile(ffmpeg)) {
                        dlCmd.addAll(listOf("--ffmpeg-location", ffmpeg))
                    }
                    dlCmd.add(url)
                    if (ProcessBuilder(dlCmd).redirectErrorStream(true).start().waitFor() == 0 && tempMp4.exists()) {
                        if (AssetsAndPackageManager.canExecuteFile(ffmpeg)) {
                            val ffCmd = listOf(
                                ffmpeg, "-y", "-i", tempMp4.absolutePath,
                                "-vf", "fps=$fps,scale=$width:-1:flags=lanczos",
                                "-loop", "0",
                                outGif.absolutePath
                            )
                            if (ProcessBuilder(ffCmd).redirectErrorStream(true).start().waitFor() == 0 && outGif.exists()) {
                                tempMp4.delete()
                                return Result.success("Saved GIF: ${outGif.absolutePath}")
                            }
                        }
                    }
                    tempMp4.delete()
                    outGif.delete()
                    return Result.failure(Exception("GIF download/conversion failed; check yt-dlp and FFmpeg"))
                } else {
                    val outTemplate = File(outDir, "%(title).70s [%(id)s].%(ext)s").absolutePath
                    val cmd = mutableListOf(ytdlp, "--no-playlist", "-o", outTemplate)
                    if (AssetsAndPackageManager.canExecuteFile(ffmpeg)) {
                        cmd.addAll(listOf("--ffmpeg-location", ffmpeg))
                    }
                    if (useAria2 && AssetsAndPackageManager.canExecuteFile(aria2)) {
                        cmd.addAll(listOf("--downloader", aria2, "--downloader-args", "aria2c:-x 8 -s 8 -k 1M"))
                    }
                    when {
                        choice.mediaType == "audio" -> cmd.addAll(listOf("-f", "bestaudio/best", "-x", "--audio-format", "mp3"))
                        choice.formatId == "best" -> cmd.addAll(listOf("-f", "bv*+ba/b", "--merge-output-format", "mp4"))
                        else -> cmd.addAll(listOf("-f", "${choice.formatId}+bestaudio/best", "--merge-output-format", "mp4"))
                    }
                    cmd.add(url)
                    val logFile = File(context.cacheDir, "ytdlp_download_${System.currentTimeMillis()}.log")
                    val proc = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(logFile).start()
                    val finished = proc.waitFor(2, TimeUnit.HOURS)
                    if (!finished) {
                        proc.destroyForcibly()
                        proc.waitFor(2, TimeUnit.SECONDS)
                    }
                    if (logFile.exists()) {
                        logFile.useLines { lines -> lines.take(200).forEach(onLog) }
                        logFile.delete()
                    }
                    if (finished && proc.exitValue() == 0) {
                        return Result.success("Saved in ${outDir.absolutePath}")
                    }
                    return Result.failure(Exception(if (finished) "yt-dlp failed; see the debug console" else "Download timed out after 2 hours"))
                }
            } catch (_: Exception) {
            }
        }

        return Result.failure(Exception("No compatible yt-dlp/FFmpeg runtime is available for this selected format. A direct media-file URL can still be downloaded."))
    }

    private fun nativeStreamDownload(
        url: String,
        choice: FormatChoice,
        outDir: File,
        onLog: (String) -> Unit
    ): Result<String> {
        if (choice.formatId != "direct") {
            return Result.failure(Exception("Only a verified direct media URL can use the native download fallback"))
        }
        return try {
            val parsed = URL(url)
            if (parsed.protocol !in setOf("http", "https")) {
                return Result.failure(Exception("Only HTTP and HTTPS downloads are supported"))
            }
            onLog("Fetching verified direct media file...")
            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                connectTimeout = 20000
                readTimeout = 60000
            }
            val code = conn.responseCode
            val contentType = (conn.contentType ?: "").substringBefore(';').trim().lowercase()
            val finalUrl = conn.url.toString()
            if (code !in 200..299) {
                conn.disconnect()
                return Result.failure(Exception("Source returned HTTP $code"))
            }
            if (contentType == "text/html" || contentType.contains("mpegurl") || finalUrl.substringBefore('?').endsWith(".m3u8", true)) {
                conn.disconnect()
                return Result.failure(Exception("Server returned a webpage or streaming playlist, not a direct media file"))
            }
            val path = finalUrl.substringBefore('?').substringAfterLast('/')
            val suffix = path.substringAfterLast('.', "").lowercase()
            val ext = when {
                contentType == "video/mp4" -> "mp4"
                contentType == "video/webm" -> "webm"
                contentType == "video/quicktime" -> "mov"
                contentType == "audio/mpeg" -> "mp3"
                contentType == "audio/mp4" -> "m4a"
                contentType == "audio/aac" -> "aac"
                contentType == "audio/ogg" -> "ogg"
                contentType == "audio/wav" || contentType == "audio/x-wav" -> "wav"
                contentType == "image/gif" -> "gif"
                contentType.startsWith("video/") && suffix in setOf("mp4", "m4v", "mov", "webm", "mkv") -> suffix
                contentType.startsWith("audio/") && suffix in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") -> suffix
                contentType == "application/octet-stream" && suffix in setOf("mp4", "m4v", "mov", "webm", "mkv", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "gif") -> suffix
                else -> {
                    conn.disconnect()
                    return Result.failure(Exception("Unsupported direct media type: ${contentType.ifBlank { "unknown" }}"))
                }
            }
            val safeBase = path.substringBeforeLast('.', path)
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .take(80)
                .ifBlank { "media_${System.currentTimeMillis()}" }
            val outFile = File(outDir, "$safeBase.$ext")
            val tempFile = File(outDir, "$safeBase.$ext.part")
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        if (total > 2L * 1024 * 1024 * 1024) {
                            throw Exception("File exceeds the 2 GiB safe download limit")
                        }
                    }
                    output.flush()
                    onLog("Downloaded ${total / 1024} KB")
                }
            }
            conn.disconnect()
            if (outFile.exists()) outFile.delete()
            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }
            Result.success("Saved: ${outFile.absolutePath}")
        } catch (e: Exception) {
            Result.failure(Exception("Direct download failed: ${e.message}"))
        }
    }
          }
