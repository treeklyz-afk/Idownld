package com.pyserver.v2

import android.content.Context
import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class FormatChoice(
    val formatId: String,
    val titleLabel: String,
    val mediaType: String,
    val metaInfo: String,
    val sourceUrl: String? = null,
    val height: Int = 0
)

data class MediaEntry(
    val id: String,
    val title: String,
    val playlistIndex: Int,
    val webpageUrl: String
)

data class MediaAnalysis(
    val title: String,
    val extractor: String,
    val duration: String,
    val formats: List<FormatChoice>,
    val thumbnailUrl: String? = null,
    val entries: List<MediaEntry> = emptyList(),
    val entryLimit: Int = 100
)

object MediaDownloaderEngine {
    private const val PLAYLIST_PREVIEW_LIMIT = 100

    fun analyzeUrl(
        context: Context,
        rawUrl: String,
        maxHeight: Int = 1080,
        onStage: (Int) -> Unit = {}
    ): Result<MediaAnalysis> {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Result.failure(IllegalArgumentException("URL paste karein"))
        val parsedUrl = try { URL(url) } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("Enter a valid HTTP or HTTPS URL"))
        }
        if (parsedUrl.protocol !in setOf("http", "https") || parsedUrl.host.isBlank()) {
            return Result.failure(IllegalArgumentException("Only HTTP and HTTPS URLs are supported"))
        }

        onStage(0)
        val direct = nativeAnalyzeFallback(url)
        if (direct.isSuccess) {
            onStage(3)
            return direct
        }
        onStage(1)
        return try {
            onStage(2)
            val parsed = parseYtDlpJson(YtDlpRuntime.analyze(context, url), maxHeight)
            onStage(3)
            Result.success(parsed)
        } catch (e: Exception) {
            onStage(4)
            jsoupAnalyzeFallback(url).recoverCatching { fallbackError ->
                throw IllegalStateException("yt-dlp failed (${e.message ?: "extractor error"}); static HTML fallback found no direct media (${fallbackError.message ?: "no video metadata"})")
            }
        }
    }

    private fun jsoupAnalyzeFallback(url: String): Result<MediaAnalysis> = runCatching {
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .timeout(15000)
            .followRedirects(true)
            .get()
        val candidates = listOf(
            "meta[property=og:video:secure_url]" to "content",
            "meta[property=og:video]" to "content",
            "meta[name=twitter:player:stream]" to "content",
            "video source[src]" to "abs:src",
            "video[src]" to "abs:src"
        )
        val mediaUrl = candidates.asSequence()
            .map { (selector, attribute) -> document.selectFirst(selector)?.attr(attribute).orEmpty() }
            .firstOrNull { candidate -> candidate.startsWith("https://") || candidate.startsWith("http://") }
            ?: throw IllegalStateException("No static video/audio source was present")
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: document.title().ifBlank { "Web video" }
        val mediaType = when {
            mediaUrl.substringBefore('?').substringBefore('#').endsWith(".mp3", true) -> "audio"
            mediaUrl.substringBefore('?').substringBefore('#').endsWith(".m4a", true) -> "audio"
            else -> "video"
        }
        MediaAnalysis(
            title = title.take(240),
            extractor = "Static HTML metadata",
            duration = "Direct source metadata",
            formats = listOf(FormatChoice("direct", "Direct media source", mediaType, "Found in Open Graph/video HTML metadata; JavaScript pages may not work", mediaUrl)),
            thumbnailUrl = document.selectFirst("meta[property=og:image]")?.absUrl("content")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        )
    }

    private fun parseYtDlpJson(raw: String, maxHeight: Int): MediaAnalysis {
        val root = JSONObject(raw.trim())
        val formatsJson = root.optJSONArray("formats")
        val formats = ArrayList<FormatChoice>()
        val videoCandidates = ArrayList<FormatChoice>()
        val seen = mutableSetOf<String>()
        if (formatsJson != null) {
            for (i in 0 until formatsJson.length()) {
                val item = formatsJson.optJSONObject(i) ?: continue
                val id = item.optString("format_id", "").takeIf { it.isNotBlank() } ?: continue
                val vcodec = item.optString("vcodec", "none")
                val acodec = item.optString("acodec", "none")
                val height = item.optInt("height", 0)
                val ext = item.optString("ext", "unknown").uppercase()
                val fps = item.optInt("fps", 0)
                val size = item.optLong("filesize", item.optLong("filesize_approx", -1L))
                val sizeText = if (size > 0L) " • ${size / (1024 * 1024)} MB" else ""
                val sourceUrl = item.optString("url", "").takeIf { it.startsWith("http://") || it.startsWith("https://") }
                val abr = item.optInt("abr", 0)
                val label = when {
                    vcodec != "none" && acodec != "none" -> "${if (height > 0) "${height}p" else "Video"} • $ext${if (fps > 0) " • ${fps}fps" else ""}$sizeText"
                    vcodec == "none" && acodec != "none" -> "Audio • $ext${if (abr > 0) " • ${abr} kbps" else ""}$sizeText"
                    else -> continue
                }
                if (seen.add("$label|$id")) {
                    val choice = FormatChoice(
                        id, label, if (vcodec == "none") "audio" else "video",
                        if (vcodec == "none") "Audio format $id" else "Muxed video and audio • $id",
                        sourceUrl, height
                    )
                    if (vcodec == "none") formats += choice else videoCandidates += choice
                }
            }
        }
        val cap = maxHeight.coerceIn(480, 2160)
        val bestPreview = videoCandidates.filter { it.height == 0 || it.height <= cap }.maxByOrNull { it.height }
            ?: videoCandidates.maxByOrNull { it.height }
        formats += FormatChoice(
            "best$cap", "Best single-file video • up to ${cap}p", "video",
            "Muxed video/audio; no FFmpeg CLI merge required", bestPreview?.sourceUrl, bestPreview?.height ?: 0
        )
        formats += videoCandidates.sortedByDescending { it.height }
        if (formats.none { it.mediaType == "audio" }) {
            formats += FormatChoice("bestaudio", "Audio only (original format)", "audio", "No conversion; preserves the source codec")
        }

        val entries = ArrayList<MediaEntry>()
        val entriesJson = root.optJSONArray("entries")
        if (entriesJson != null) {
            for (i in 0 until entriesJson.length()) {
                val item = entriesJson.optJSONObject(i) ?: continue
                val id = item.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                val webpage = item.optString("webpage_url", item.optString("url", ""))
                entries += MediaEntry(
                    id = id.take(160),
                    title = item.optString("title", id).take(240),
                    playlistIndex = item.optInt("playlist_index", i + 1).coerceAtLeast(1),
                    webpageUrl = webpage.take(2048)
                )
            }
        }
        val title = root.optString("title", "Media collection").take(240)
        val duration = root.optString("duration_string", if (entries.isNotEmpty()) "${entries.size} listed" else "Unknown")
        return MediaAnalysis(
            title = title,
            extractor = root.optString("extractor_key", "yt-dlp"),
            duration = duration,
            formats = formats,
            thumbnailUrl = root.optString("thumbnail", "").takeIf { it.startsWith("http://") || it.startsWith("https://") },
            entries = entries
        )
    }

    private fun nativeAnalyzeFallback(url: String): Result<MediaAnalysis> {
        val parsed = try { URL(url) } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("Enter a valid HTTP or HTTPS media URL"))
        }
        if (parsed.protocol !in setOf("http", "https")) {
            return Result.failure(IllegalArgumentException("Only HTTP and HTTPS media URLs are supported"))
        }
        val host = parsed.host.lowercase()
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
            if (code !in 200..299) return Result.failure(IllegalStateException("Media server returned HTTP $code"))
            if (contentType == "text/html" || contentType.contains("mpegurl") || finalUrl.substringBefore('?').endsWith(".m3u8", true)) {
                return Result.failure(IllegalStateException("This URL is a webpage or playlist; yt-dlp will try site extraction."))
            }
            val path = finalUrl.substringBefore('?').substringAfterLast('/').lowercase()
            val ext = path.substringAfterLast('.', "")
            val mediaType = when {
                contentType.startsWith("video/") || ext in setOf("mp4", "m4v", "mov", "webm", "mkv") -> "video"
                contentType.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") -> "audio"
                contentType == "image/gif" || ext == "gif" -> "gif"
                else -> return Result.failure(IllegalStateException("URL is not a supported direct media file (server type: ${contentType.ifBlank { "unknown" }})"))
            }
            val title = disposition.substringAfter("filename=", "").trim().trim('"').ifBlank { path.ifBlank { "Media file" } }
            val label = when (mediaType) {
                "audio" -> "Audio file (original)"
                "gif" -> "GIF image (original)"
                else -> "Video file (original)"
            }
            Result.success(
                MediaAnalysis(
                    title = title,
                    extractor = "Direct file",
                    duration = "Original stream",
                    formats = listOf(FormatChoice("direct", label, mediaType, "No re-encoding", finalUrl))
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not inspect direct media URL: ${e.message}"))
        }
    }

    fun downloadMedia(
        context: Context,
        rawUrl: String,
        choice: FormatChoice,
        useAria2: Boolean = false,
        onLog: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<String> {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Result.failure(IllegalArgumentException("URL empty"))
        if (choice.formatId == "direct") return nativeStreamDownload(context, url, choice, onLog, onProgress)
        return try {
            onLog("Downloading with APK-bundled yt-dlp...")
            publishYtDlpFiles(context, YtDlpRuntime.download(context, url, choice.formatId, onProgress = onProgress), choice.mediaType)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("yt-dlp download failed: ${e.message ?: "unknown error"}"))
        }
    }

    /** selectedIndices=null downloads the entire collection; otherwise only the chosen preview items. */
    fun downloadPlaylist(
        context: Context,
        rawUrl: String,
        choice: FormatChoice,
        selectedIndices: List<Int>? = null,
        useAria2: Boolean = false,
        onLog: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<String> {
        if (selectedIndices != null && selectedIndices.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one playlist item"))
        }
        if (choice.formatId == "direct") {
            return Result.failure(IllegalArgumentException("Direct media URLs do not contain a selectable playlist"))
        }
        return try {
            onLog("Downloading collection with APK-bundled yt-dlp...")
            publishYtDlpFiles(
                context,
                YtDlpRuntime.download(context, rawUrl, choice.formatId, selectedIndices, playlistMode = true, onProgress = onProgress),
                choice.mediaType
            )
        } catch (e: Exception) {
            Result.failure(IllegalStateException("yt-dlp playlist download failed: ${e.message ?: "unknown error"}"))
        }
    }

    private fun publishYtDlpFiles(context: Context, files: List<File>, defaultType: String): Result<String> {
        val saved = ArrayList<String>()
        for (file in files) {
            val type = when (file.extension.lowercase()) {
                "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac" -> "audio"
                "gif" -> "gif"
                else -> defaultType
            }
            val result = MediaStoreDownloads.publish(context, file, type)
            if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("Could not save media"))
            saved += result.getOrThrow()
        }
        return Result.success(if (saved.size == 1) "Saved to ${saved.single()}" else "Saved ${saved.size} files:\n${saved.joinToString("\n")}")
    }

    private fun nativeStreamDownload(
        context: Context,
        url: String,
        choice: FormatChoice,
        onLog: (String) -> Unit,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        if (choice.formatId != "direct") return Result.failure(IllegalArgumentException("Only verified direct media URLs use the built-in downloader"))
        return try {
            val parsed = URL(url)
            if (parsed.protocol !in setOf("http", "https")) return Result.failure(IllegalArgumentException("Only HTTP and HTTPS downloads are supported"))
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
            val contentLength = conn.contentLengthLong
            if (code !in 200..299) {
                conn.disconnect()
                return Result.failure(IllegalStateException("Source returned HTTP $code"))
            }
            if (contentType == "text/html" || contentType.contains("mpegurl") || finalUrl.substringBefore('?').endsWith(".m3u8", true)) {
                conn.disconnect()
                return Result.failure(IllegalStateException("Server returned a webpage or playlist, not a direct media file"))
            }
            val path = finalUrl.substringBefore('?').substringAfterLast('/').ifBlank { "download" }
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
                    return Result.failure(IllegalStateException("Unsupported direct media type: ${contentType.ifBlank { "unknown" }}"))
                }
            }
            val base = path.substringBeforeLast('.', path).replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "media_${System.currentTimeMillis()}" }
            val stageDir = MediaStoreDownloads.stagingDirectory(context)
            val staged = File(stageDir, "$base.$ext")
            val temp = File(stageDir, "$base.$ext.part")
            onProgress(DownloadProgress(0L, contentLength, staged.name, "downloading"))
            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(16384)
                    var total = 0L
                    var lastReported = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 2L * 1024 * 1024 * 1024) throw IllegalStateException("File exceeds the 2 GiB safety limit")
                        output.write(buffer, 0, count)
                        if (total - lastReported >= 256L * 1024L || (contentLength > 0L && total >= contentLength)) {
                            onProgress(DownloadProgress(total, contentLength, staged.name, "downloading"))
                            lastReported = total
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()
            onProgress(DownloadProgress(temp.length(), contentLength, staged.name, "saving"))
            if (staged.exists()) staged.delete()
            if (!temp.renameTo(staged)) {
                temp.copyTo(staged, overwrite = true)
                temp.delete()
            }
            val type = when {
                ext == "gif" -> "gif"
                contentType.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") -> "audio"
                else -> "video"
            }
            MediaStoreDownloads.publish(context, staged, type).map { "Saved to $it" }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Direct download failed: ${e.message}"))
        }
    }
}
