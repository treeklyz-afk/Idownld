package com.pyserver.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    val installed: Boolean,
    val version: String?,
    val path: String
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
        val assetFolders = listOf("tools" to binDir(context), "packages" to pkgDir(context))
        for ((folder, targetRoot) in assetFolders) {
            try {
                val files = context.assets.list(folder) ?: emptyArray()
                for (name in files) {
                    val outFile = File(targetRoot, name)
                    context.assets.open("$folder/$name").use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                    outFile.setReadable(true, false)
                    outFile.setExecutable(true, false)
                    onLog("Loaded asset: $folder/$name")
                }
            } catch (_: Exception) {
            }
        }
    }

    fun resolveTool(context: Context, name: String): String {
        val candidates = listOf(
            File(binDir(context), name),
            File(pkgDir(context), name),
            File(context.applicationInfo.nativeLibraryDir, "lib$name.so")
        )
        for (f in candidates) {
            if (f.exists()) {
                f.setReadable(true, false)
                f.setExecutable(true, false)
                if (f.canExecute()) return f.absolutePath
            }
        }
        return name
    }

    fun getCoreToolsStatus(context: Context): List<ToolStatus> {
        val core = listOf("yt-dlp", "ffmpeg", "ffprobe", "aria2c", "python3")
        return core.map { tool ->
            val resolved = if (tool == "python3") {
                PythonRunner.findPythonExecutable(context) ?: "python3"
            } else {
                resolveTool(context, tool)
            }
            val file = File(resolved)
            val exists = file.exists() && file.canExecute()
            ToolStatus(
                name = tool,
                installed = exists,
                version = getInstalledVersion(context, tool) ?: if (exists) "Ready" else null,
                path = resolved
            )
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
            val targetDir = if (pkg.category == "package") pkgDir(context) else binDir(context)
            val dest = File(targetDir, pkg.filename)
            val temp = File(targetDir, "${pkg.filename}.download")
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
            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
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
            Result.success("Installed ${pkg.name} (v${pkg.version})")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun removePackage(context: Context, pkg: ManifestPackage): Result<String> {
        return try {
            val targetDir = if (pkg.category == "package") pkgDir(context) else binDir(context)
            val dest = File(targetDir, pkg.filename)
            if (dest.exists()) dest.delete()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove("pkg_ver_${pkg.id}").apply()
            Result.success("Removed ${pkg.name}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
