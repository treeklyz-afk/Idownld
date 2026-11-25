package com.pyserver.v2

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import java.io.File

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
        playlistMode: Boolean = false
    ): List<File> {
        val outputDir = File(context.cacheDir, "PyServer2/yt-dlp/${System.currentTimeMillis()}")
        if (!outputDir.mkdirs() && !outputDir.isDirectory) {
            throw IllegalStateException("Could not create private yt-dlp staging folder")
        }
        val selection = playlistIndices?.joinToString(",") ?: ""
        val raw = module(context).callAttr("download", url, formatId, outputDir.absolutePath, selection, playlistMode).toString()
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
