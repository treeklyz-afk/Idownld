package com.pyserver.v2

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** CPython, yt-dlp and yt-dlp-ejs are packaged into the APK by Chaquopy at build time. */
object YtDlpRuntime {
    private fun module(context: Context) = synchronized(this) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        Python.getInstance().getModule("yt_dlp_bridge")
    }

    fun analyze(context: Context, url: String): String =
        module(context).callAttr("analyze", url).toString()

    fun download(
        context: Context,
        url: String,
        formatId: String,
        playlistIndices: List<Int>? = null,
        playlistMode: Boolean = false,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        taskId: String? = null
    ): List<File> {
        val outputDir = taskId?.let { DownloadQueue.taskDirectory(context, it) }
            ?: File(context.cacheDir, "PyServer2/yt-dlp/${System.currentTimeMillis()}")
        if (!outputDir.mkdirs() && !outputDir.isDirectory) {
            throw IllegalStateException("Could not create private yt-dlp staging folder")
        }
        val selection = playlistIndices?.joinToString(",") ?: ""
        val progressFile = File(outputDir, ".download-progress.json")
        val watching = AtomicBoolean(true)
        val monitor = if (onProgress == null) null else Thread {
            while (watching.get()) {
                runCatching {
                    if (progressFile.isFile) {
                        val snapshot = JSONObject(progressFile.readText())
                        onProgress.invoke(
                            DownloadProgress(
                                downloadedBytes = snapshot.optLong("downloaded", 0L),
                                totalBytes = snapshot.optLong("total", 0L),
                                fileName = snapshot.optString("filename", "Media"),
                                status = snapshot.optString("status", "downloading")
                            )
                        )
                    }
                }
                try { Thread.sleep(350) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "yt-dlp-progress"; isDaemon = true; start() }
        val raw = try {
            module(context).callAttr("download", url, formatId, outputDir.absolutePath, selection, playlistMode, progressFile.absolutePath, File(outputDir, ".paused").absolutePath).toString()
        } finally {
            watching.set(false)
            monitor?.join(1000)
            progressFile.delete()
            File(progressFile.path + ".tmp").delete()
        }
        val paths = JSONArray(raw)
        val root = outputDir.canonicalFile
        val files = (0 until paths.length()).map { index ->
            val file = File(paths.getString(index)).canonicalFile
            require(file.toPath().startsWith(root.toPath()) && file.isFile && file.length() > 0L) {
                "yt-dlp returned a missing or out-of-folder file"
            }
            file
        }
        require(files.isNotEmpty()) { "yt-dlp produced no media files" }
        return files
    }
}
