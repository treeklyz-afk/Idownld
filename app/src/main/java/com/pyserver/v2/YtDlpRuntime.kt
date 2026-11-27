package com.pyserver.v2

import android.content.Context
import android.os.Build
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Python packages plus ABI-matched QuickJS/aria2 executables are bundled into the APK at build time. */
object YtDlpRuntime {
    private fun module(context: Context) = synchronized(this) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        val bridge = Python.getInstance().getModule("yt_dlp_bridge")
        bridge.callAttr("configure_quickjs", installQuickJs(context))
        bridge.callAttr("configure_aria2", installAria2(context))
        bridge
    }

    private fun installQuickJs(context: Context): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: throw IllegalStateException("Bundled QuickJS is unavailable for this device ABI")
        val runtimeDir = File(context.noBackupFilesDir, "runtime").apply { mkdirs() }
        val executable = File(runtimeDir, "qjs-0.12.1-$abi")
        if (!executable.isFile || executable.length() == 0L) {
            context.assets.open("runtimes/qjs-$abi").use { input ->
                executable.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(executable.setExecutable(true, true) || executable.canExecute()) {
            "Could not mark the bundled QuickJS runtime executable"
        }
        val probe = try {
            ProcessBuilder(executable.absolutePath, "-e", "if (1 + 1 !== 2) throw new Error('probe')")
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            throw IllegalStateException("Bundled QuickJS could not start on this device", error)
        }
        val output = probe.inputStream.bufferedReader().use { it.readText().take(300) }
        check(probe.waitFor() == 0) { "Bundled QuickJS self-test failed: $output" }
        return executable.absolutePath
    }

    private fun installAria2(context: Context): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: throw IllegalStateException("Bundled aria2 is unavailable for this device ABI")
        val runtimeDir = File(context.noBackupFilesDir, "runtime").apply { mkdirs() }
        val executable = File(runtimeDir, "aria2c-1.37.0-$abi")
        if (!executable.isFile || executable.length() == 0L) {
            context.assets.open("runtimes/aria2c-$abi").use { input ->
                executable.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(executable.setExecutable(true, true) || executable.canExecute()) {
            "Could not mark the bundled aria2 executable"
        }
        val probe = try {
            ProcessBuilder(executable.absolutePath, "--version").redirectErrorStream(true).start()
        } catch (error: Exception) {
            throw IllegalStateException("Bundled aria2 could not start on this device", error)
        }
        val output = probe.inputStream.bufferedReader().use { it.readText().take(500) }
        check(probe.waitFor() == 0 && output.contains("aria2")) {
            "Bundled aria2 self-test failed: $output"
        }
        return executable.absolutePath
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
        taskId: String? = null,
        useAria2: Boolean = true
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
            module(context).callAttr("download", url, formatId, outputDir.absolutePath, selection, playlistMode, progressFile.absolutePath, File(outputDir, ".paused").absolutePath, useAria2).toString()
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
