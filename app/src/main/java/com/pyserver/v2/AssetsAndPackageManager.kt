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

data class ManifestPackage(
    val id: String,
    val name: String,
    val version: String,
    val category: String,
    val filename: String,
    val url: String
)

data class ToolStatus(
    val name: String,
    val ready: Boolean,
    val info: String
)

object AssetsAndPackageManager {
    private const val PREFS = "pyserver2_prefs"
    private const val KEY_MANIFEST = "manifest_url"
    private const val DEFAULT_MANIFEST =
        "https://raw.githubusercontent.com/YOUR_USER/pyserver-assets/main/manifest.json"

    fun binDir(context: Context): File = File(context.filesDir, "bin").apply { mkdirs() }
    fun pkgDir(context: Context): File = File(context.filesDir, "packages").apply { mkdirs() }

    fun getManifestUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANIFEST, DEFAULT_MANIFEST) ?: DEFAULT_MANIFEST

    fun setManifestUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MANIFEST, url.trim()).apply()
    }

    fun getInstalledVersion(context: Context, id: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("pkg_ver_$id", null)

    private fun setInstalledVersion(context: Context, id: String, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("pkg_ver_$id", version).apply()
    }

    fun extractAssetsOnStartup(context: Context, onLog: (String) -> Unit = {}) {
        PythonRunner.extractPythonScripts(context)
        val folders = listOf("tools" to binDir(context), "packages" to pkgDir(context))
        for ((folder, root) in folders) {
            try {
                val list = context.assets.list(folder) ?: emptyArray()
                for (name in list) {
                    val out = File(root, name)
                    context.assets.open("$folder/$name").use { inp ->
                        FileOutputStream(out).use { outp -> inp.copyTo(outp) }
                    }
                    out.setReadable(true, false)
                    out.setExecutable(true, false)
                    onLog("Synced asset: $folder/$name")
                }
            } catch (_: Exception) {
            }
        }
    }

    fun resolveTool(context: Context, name: String): String {
        val clean = name.removePrefix("lib").removeSuffix(".so")
        val nativeCandidate = File(context.applicationInfo.nativeLibraryDir, "lib$clean.so")
        if (nativeCandidate.exists() && nativeCandidate.canExecute()) {
            return nativeCandidate.absolutePath
        }
        val localBin = File(binDir(context), clean)
        if (localBin.exists() && localBin.canExecute()) {
            return localBin.absolutePath
        }
        val localPkg = File(pkgDir(context), clean)
        if (localPkg.exists() && localPkg.canExecute()) {
            return localPkg.absolutePath
        }
        return clean
    }

    fun canExecuteFile(path: String): Boolean {
        val f = File(path)
        if (!f.exists() || !f.canExecute()) return false
        return try {
            val p = ProcessBuilder(f.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            p.inputStream.bufferedReader().readText()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun getCoreToolsStatus(context: Context): List<ToolStatus> {
        val tools = listOf("yt-dlp", "ffmpeg", "ffprobe", "aria2c")
        return tools.map { name ->
            val path = resolveTool(context, name)
            val runnable = canExecuteFile(path)
            val ver = getInstalledVersion(context, name)
            ToolStatus(
                name = name,
                ready = runnable,
                info = when {
                    runnable && ver != null -> "Active (v$ver)"
                    runnable -> "Active (Native/Asset)"
                    File(path).exists() -> "Fallback Mode"
                    else -> "Auto-Fallback Ready"
                }
            )
        }
    }

    fun runTerminalCommand(context: Context, rawCmd: String): String {
        val cmd = rawCmd.trim()
        if (cmd.isEmpty()) return "Enter a command (e.g. help, tools, ls, pkg list)"

        return when {
            cmd == "help" ->
                "Commands: help | tools | ls | pkg install <id> | <shell command>"
            cmd == "tools" ->
                getCoreToolsStatus(context).joinToString("\n") {
                    "${it.name}: ${it.info}"
                }
            cmd == "ls" -> {
                val b = binDir(context).listFiles()?.joinToString(", ") { it.name } ?: ""
                val p = pkgDir(context).listFiles()?.joinToString(", ") { it.name } ?: ""
                "bin: [$b]\npackages: [$p]"
            }
            cmd.startsWith("pkg install ") -> {
                val targetId = cmd.removePrefix("pkg install ").trim()
                val manifestRes = fetchManifest(context)
                val pkg = manifestRes.getOrNull()?.firstOrNull {
                    it.id.equals(targetId, true) || it.name.equals(targetId, true)
                } ?: return "Package '$targetId' not found in manifest"
                installPackage(context, pkg).fold(
                    onSuccess = { it },
                    onFailure = { "Install failed: ${it.message}" }
                )
            }
            else -> {
                try {
                    val proc = ProcessBuilder("sh", "-c", cmd)
                        .directory(pkgDir(context))
                        .redirectErrorStream(true)
                        .start()
                    val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
                    val code = proc.waitFor()
                    out.ifBlank { "Exit code: $code" }.take(2000)
                } catch (e: Exception) {
                    "Shell restricted by Android: ${e.message}"
                }
            }
        }
    }

    fun fetchManifest(context: Context, urlOverride: String? = null): Result<List<ManifestPackage>> {
        return try {
            val target = urlOverride?.trim()?.ifEmpty { null } ?: getManifestUrl(context)
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 15000
            }
            if (conn.responseCode !in 200..299) {
                return Result.failure(Exception("HTTP ${conn.responseCode}"))
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val root = JSONObject(body)
            val arr: JSONArray = root.optJSONArray("packages") ?: JSONArray()
            val items = mutableListOf<ManifestPackage>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    ManifestPackage(
                        id = o.getString("id"),
                        name = o.optString("name", o.getString("id")),
                        version = o.optString("version", "1.0.0"),
                        category = o.optString("category", "package"),
                        filename = o.optString("filename", o.getString("id")),
                        url = o.getString("url")
                    )
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun installPackage(
        context: Context,
        pkg: ManifestPackage,
        onProgress: (String) -> Unit = {}
    ): Result<String> {
        return try {
            val dir = if (pkg.category == "package") pkgDir(context) else binDir(context)
            val dest = File(dir, pkg.filename)
            val temp = File(dir, "${pkg.filename}.tmp")
            onProgress("Downloading ${pkg.name} v${pkg.version}...")
            val conn = (URL(pkg.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 60000
            }
            if (conn.responseCode !in 200..299) {
                return Result.failure(Exception("HTTP ${conn.responseCode}"))
            }
            conn.inputStream.use { inp ->
                FileOutputStream(temp).use { outp ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = inp.read(buf)
                        if (n == -1) break
                        outp.write(buf, 0, n)
                    }
                    outp.flush()
                }
            }
            conn.disconnect()
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            setInstalledVersion(context, pkg.id, pkg.version)
            Result.success("Installed ${pkg.name} v${pkg.version}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
