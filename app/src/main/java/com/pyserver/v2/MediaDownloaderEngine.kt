package com.pyserver.v2

import android.content.Context
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
    val metaInfo: String
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
    val entries: List<MediaEntry> = emptyList(),
    val entryLimit: Int = 100
)

object MediaDownloaderEngine {
    private const val PLAYLIST_PREVIEW_LIMIT = 100

    fun analyzeUrl(
        context: Context,
        rawUrl: String
    ): Result<MediaAnalysis> {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Result.failure(IllegalArgumentException("URL paste karein"))
        val parsedUrl = try { URL(url) } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("Enter a valid HTTP or HTTPS URL"))
        }
        if (parsedUrl.protocol !in setOf("http", "https") || parsedUrl.host.isBlank()) {
            return Result.failure(IllegalArgumentException("Only HTTP and HTTPS URLs are supported"))
        }

        return nativeAnalyzeFallback(url)
    }

    private fun parseYtDlpJson(raw: String): MediaAnalysis {
        val root = JSONObject(raw.trim())
        val formatsJson = root.optJSONArray("formats")
        val formats = ArrayList<FormatChoice>()
        formats += FormatChoice("best", "Best available (auto merge)", "video", "yt-dlp selects compatible streams")
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
                val label = when {
                    vcodec != "none" && height > 0 -> "${height}p • $ext${if (fps > 0) " • ${fps}fps" else ""}$sizeText"
                    else -> continue
                }
                if (seen.add("$label|$id")) {
                    val isCombined = acodec != "none"
                    formats += FormatChoice(
                        id,
                        label,
                        "video",
                        if (isCombined) "Video and audio stream $id" else "Video stream $id; yt-dlp will try to merge audio"
                    )
                }
            }
        }
        formats += FormatChoice("bestaudio", "Audio only (original format)", "audio", "No conversion; preserves the source codec")

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
        if (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host.endsWith(".youtu.be")) {
            return Result.failure(IllegalStateException("This APK does not yet bundle yt-dlp. Site/playlist analysis will be available after a compatible signed APK includes the extractor runtime."))
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
            if (code !in 200..299) return Result.failure(IllegalStateException("Media server returned HTTP $code"))
            if (contentType == "text/html" || contentType.contains("mpegurl") || finalUrl.substringBefore('?').endsWith(".m3u8", true)) {
                return Result.failure(IllegalStateException("This page/playlist needs yt-dlp, which is not bundled in this APK yet."))
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
                    formats = listOf(FormatChoice("direct", label, mediaType, "No re-encoding"))
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
        onLog: (String) -> Unit = {}
    ): Result<String> {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Result.failure(IllegalArgumentException("URL empty"))
        if (choice.formatId == "direct") return nativeStreamDownload(context, url, choice, onLog)
        return Result.failure(IllegalStateException("yt-dlp is not bundled in this APK yet. Direct media-file downloads remain available; site extraction requires a compatible signed APK update."))
    }

    /** selectedIndices=null downloads the entire collection; otherwise only the chosen preview items. */
    fun downloadPlaylist(
        context: Context,
        rawUrl: String,
        choice: FormatChoice,
        selectedIndices: List<Int>? = null,
        useAria2: Boolean = false,
        onLog: (String) -> Unit = {}
    ): Result<String> {
        if (selectedIndices != null && selectedIndices.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one playlist item"))
        }
        if (choice.formatId == "direct") {
            return Result.failure(IllegalArgumentException("Direct media URLs do not contain a selectable playlist"))
        }
        return Result.failure(IllegalStateException("Playlist downloads need yt-dlp, which is not bundled in this APK yet. A compatible signed APK update is required."))
    }

    private fun nativeStreamDownload(
        context: Context,
        url: String,
        choice: FormatChoice,
        onLog: (String) -> Unit
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
            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 2L * 1024 * 1024 * 1024) throw IllegalStateException("File exceeds the 2 GiB safety limit")
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    onLog("Downloaded ${total / 1024} KB")
                }
            }
            conn.disconnect()
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
