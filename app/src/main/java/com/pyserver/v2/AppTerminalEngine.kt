package com.pyserver.v2

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * App-owned shell for the modern-target build. It uses Android's system shell and
 * the app UID; it is deliberately not root and does not execute downloaded files
 * from writable app storage. Native tools must be shipped in the signed APK.
 */
object AppTerminalEngine {
    private const val OUTPUT_LIMIT = 24_000
    private const val COMMAND_TIMEOUT_SECONDS = 300L
    private val workingDirectories = ConcurrentHashMap<String, File>()

    private val supportedNames = linkedMapOf(
        "python" to "libpyserver_python.so",
        "python3" to "libpyserver_python.so",
        "ffmpeg" to "libpyserver_ffmpeg.so",
        "aria2c" to "libpyserver_aria2.so",
        "deno" to "libpyserver_deno.so",
        "node" to "libpyserver_node.so",
        "yt-dlp" to "libpyserver_ytdlp.so"
    )

    fun isPackageBundled(context: Context, packageId: String): Boolean {
        val tool = when (packageId) {
            "python" -> "python3"
            "python-yt-dlp" -> "yt-dlp"
            "ffmpeg" -> "ffmpeg"
            "aria2" -> "aria2c"
            "deno" -> "deno"
            "nodejs-lts" -> "node"
            "yt-dlp-ejs" -> return false // data module is not included until a complete extractor bundle ships
            else -> return false
        }
        val fileName = supportedNames[tool] ?: return false
        val executable = File(context.applicationInfo.nativeLibraryDir, fileName)
        return executable.isFile && executable.canExecute()
    }

    fun home(context: Context): File = File(context.filesDir, "terminal/home").apply { mkdirs() }

    fun availableBundledTools(context: Context): List<String> = supportedNames
        .filterValues { name -> File(context.applicationInfo.nativeLibraryDir, name).let { it.isFile && it.canExecute() } }
        .keys.toList()

    fun runCommand(context: Context, rawCommand: String): String {
        val command = rawCommand.trim()
        if (command.isEmpty()) return "Type a command. Use 'help' to see the available shell notes."
        if (command == "help") return helpText(context)
        if (command == "clear") return "\u0000CLEAR"
        if (command.startsWith("pkg ") || command == "pkg") {
            return "Package manager installs are disabled on target SDK 35. Executable tools must be included in a signed APK; updates are delivered as full APK updates."
        }
        val cdMatch = Regex("^cd(?:\\s+(.+))?$").matchEntire(command)
        if (cdMatch != null) {
            val current = workingDirectories[context.packageName] ?: home(context)
            val rawPath = cdMatch.groupValues.getOrNull(1)?.trim()?.removeSurrounding("\"", "\"")?.removeSurrounding("'", "'")
            val destination = if (rawPath.isNullOrBlank() || rawPath == "~") home(context)
                else if (rawPath.startsWith("/")) File(rawPath) else File(current, rawPath)
            return try {
                val canonical = destination.canonicalFile
                if (!canonical.isDirectory) "cd: not a directory: $rawPath"
                else {
                    workingDirectories[context.packageName] = canonical
                    canonical.path
                }
            } catch (e: Exception) { "cd: ${e.message ?: "invalid path"}" }
        }

        val home = home(context)
        val cwd = workingDirectories[context.packageName] ?: home
        return try {
            val builder = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(cwd)
                .redirectErrorStream(true)
            builder.environment().apply {
                put("HOME", home.absolutePath)
                put("PWD", cwd.absolutePath)
                put("PATH", "/system/bin:/system/xbin:${context.applicationInfo.nativeLibraryDir}")
                put("TERM", "xterm-256color")
            }
            val process = builder.start()
            val collected = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(collected) {
                                collected.append(line).append('\n')
                                if (collected.length > OUTPUT_LIMIT) {
                                    collected.delete(0, collected.length - OUTPUT_LIMIT)
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; name = "app-terminal-output"; start() }

            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            reader.join(2_000)
            val output = synchronized(collected) { collected.toString().trimEnd() }
            buildString {
                if (output.isNotBlank()) append(output)
                if (!completed) {
                    if (isNotEmpty()) append('\n')
                    append("Command stopped after ${COMMAND_TIMEOUT_SECONDS}s timeout.")
                } else if (process.exitValue() != 0) {
                    if (isNotEmpty()) append('\n')
                    append("Exit code: ${process.exitValue()}")
                } else if (isEmpty()) append("Command completed successfully.")
            }.takeLast(OUTPUT_LIMIT)
        } catch (e: Exception) {
            "Terminal error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun helpText(context: Context): String = buildString {
        appendLine("PyServer app terminal • Android shell • app UID (not root)")
        appendLine("Home: ${home(context).absolutePath}")
        appendLine("Working directory: ${(workingDirectories[context.packageName] ?: home(context)).absolutePath}")
        appendLine("Shell: /system/bin/sh; Android system tools are available through /system/bin.")
        val tools = availableBundledTools(context)
        appendLine("APK-bundled tools detected: ${tools.ifEmpty { listOf("none in this build") }.joinToString()}")
        appendLine("Use cd, pwd, ls, mkdir, cat, grep, toybox, and other commands provided by Android.")
        append("Python/FFmpeg/aria2/Deno/yt-dlp executables are not shipped in this APK yet. `pkg install` is unavailable at target SDK 35; future executable updates require a signed APK update.")
    }
}
