package com.pyserver.v2

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val RUNTIME_MANIFEST_URL = "https://github.com/treeklyz-afk/Idownld/releases/latest/download/runtime-manifest.json"
private const val RUNTIME_REPOSITORY = "treeklyz-afk/Idownld"
private const val MAX_RUNTIME_ASSET_BYTES = 100L * 1024L * 1024L

data class RuntimeAsset(
    val component: String,
    val version: String,
    val abi: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)

data class RuntimePackageManifest(
    val runtimeVersion: String,
    val releaseTag: String,
    val assets: List<RuntimeAsset>
)

data class InstalledRuntime(val version: String, val abi: String, val quickJs: File, val aria2: File)

/** Downloads only the two yt-dlp helper executables. Python/yt-dlp remain app-bundled. */
object RuntimePackageManager {
    private fun root(context: Context) = File(context.noBackupFilesDir, "lib")

    fun installed(context: Context): InstalledRuntime? = runCatching {
        val base = root(context)
        val active = JSONObject(File(base, "active.json").readText())
        val version = active.getString("runtimeVersion")
        val abi = active.getString("abi")
        val dirName = active.getString("directory")
        require(dirName.matches(Regex("[A-Za-z0-9_.+-]{1,100}")))
        require(abi in setOf("arm64-v8a", "x86_64") && Build.SUPPORTED_ABIS.contains(abi))
        val dir = File(base, dirName).canonicalFile
        require(dir.parentFile == base.canonicalFile)
        val qjs = File(dir, "qjs")
        val aria = File(dir, "aria2c")
        require(qjs.isFile && qjs.length() > 0L && qjs.canExecute())
        require(aria.isFile && aria.length() > 0L && aria.canExecute())
        InstalledRuntime(version, abi, qjs, aria)
    }.getOrNull()

    fun hasInstalled(context: Context): Boolean = installed(context) != null
    fun installedVersion(context: Context): String? = installed(context)?.version

    /** Reuse older app-private helpers only after making and testing a fresh copy. */
    fun migrateLegacy(context: Context): Boolean {
        if (installed(context) != null) return true
        val abi = runCatching { preferredAbi() }.getOrNull() ?: return false
        val old = File(context.noBackupFilesDir, "runtime")
        val oldQjs = File(old, "qjs-0.12.1-$abi")
        val oldAria = File(old, "aria2c-1.37.0-$abi")
        if (!oldQjs.isFile || !oldAria.isFile) return false
        val version = "quickjs-0.12.1+aria2-1.37.0"
        val base = root(context).apply { mkdirs() }
        val dirName = "$version-$abi"
        val finalDir = File(base, dirName)
        val stage = File(base, ".migration-${System.nanoTime()}").apply { mkdirs() }
        return try {
            val qjs = File(stage, "qjs")
            val aria = File(stage, "aria2c")
            oldQjs.copyTo(qjs, overwrite = true)
            oldAria.copyTo(aria, overwrite = true)
            makeExecutable(qjs)
            makeExecutable(aria)
            selfTest(qjs, listOf("-e", "if (1 + 1 !== 2) throw new Error('probe')"), "QuickJS")
            selfTest(aria, listOf("--version"), "aria2")
            if (finalDir.exists()) finalDir.deleteRecursively()
            check(stage.renameTo(finalDir)) { "Could not migrate verified helper binaries" }
            activate(base, version, abi, dirName)
            true
        } catch (_: Throwable) {
            stage.deleteRecursively()
            false
        }
    }

    fun fetchManifest(): RuntimePackageManifest {
        val connection = (URL(RUNTIME_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Idownld-Android/$APP_VERSION_NAME")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("GitHub runtime manifest returned HTTP $code; a tagged release may not exist yet")
            val raw = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 256 * 1024) { "Runtime manifest is too large" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }
            val json = JSONObject(raw)
            require(json.optInt("schemaVersion") == 1) { "Unsupported runtime manifest schema" }
            require(json.optString("repository") == RUNTIME_REPOSITORY) { "Runtime manifest repository mismatch" }
            val assetsJson = json.getJSONArray("assets")
            val assets = (0 until assetsJson.length()).map { index ->
                val item = assetsJson.getJSONObject(index)
                RuntimeAsset(
                    component = item.getString("component"),
                    version = item.getString("version"),
                    abi = item.getString("abi"),
                    fileName = item.getString("fileName"),
                    url = item.getString("url"),
                    sizeBytes = item.getLong("sizeBytes"),
                    sha256 = item.getString("sha256").lowercase()
                )
            }
            require(assets.size == 4 && assets.map { "${it.component}/${it.abi}" }.toSet().size == 4)
            require(assets.all { it.component in setOf("quickjs", "aria2") && it.abi in setOf("arm64-v8a", "x86_64") })
            require(assets.all { it.fileName.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && it.sizeBytes in 1..MAX_RUNTIME_ASSET_BYTES && it.sha256.matches(Regex("[a-f0-9]{64}")) })
            require(assets.all { asset ->
                val uri = URL(asset.url)
                uri.protocol == "https" && uri.host.equals("github.com", true) && uri.path.startsWith("/$RUNTIME_REPOSITORY/releases/download/")
            }) { "Runtime asset URL is outside the expected GitHub Releases repository" }
            val version = json.getString("runtimeVersion")
            require(version.matches(Regex("[A-Za-z0-9_.+-]{1,100}")))
            val tag = json.getString("releaseTag")
            require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
            return RuntimePackageManifest(version, tag, assets)
        } finally {
            connection.disconnect()
        }
    }

    fun updateAvailable(context: Context, manifest: RuntimePackageManifest): Boolean {
        val current = installed(context) ?: return true
        return current.version != manifest.runtimeVersion || current.abi != preferredAbi()
    }

    fun install(
        context: Context,
        manifest: RuntimePackageManifest,
        onProgress: (component: String, downloaded: Long, total: Long) -> Unit = { _, _, _ -> }
    ): InstalledRuntime {
        val abi = preferredAbi()
        val existing = installed(context)
        if (existing?.version == manifest.runtimeVersion && existing.abi == abi) return existing
        val selected = manifest.assets.filter { it.abi == abi }.associateBy { it.component }
        require(selected.keys == setOf("quickjs", "aria2")) { "Release has no complete helper package for $abi" }

        val base = root(context).apply { mkdirs() }
        val safeVersion = manifest.runtimeVersion
        val directoryName = "$safeVersion-$abi"
        val finalDir = File(base, directoryName)
        if (finalDir.exists()) finalDir.deleteRecursively()
        val stage = File(base, ".$directoryName-${System.nanoTime()}.staging").apply { mkdirs() }
        try {
            val qjs = download(selected.getValue("quickjs"), File(stage, "qjs"), onProgress)
            val aria = download(selected.getValue("aria2"), File(stage, "aria2c"), onProgress)
            makeExecutable(qjs)
            makeExecutable(aria)
            selfTest(qjs, listOf("-e", "if (1 + 1 !== 2) throw new Error('probe')"), "QuickJS")
            selfTest(aria, listOf("--version"), "aria2")
            check(stage.renameTo(finalDir)) { "Could not atomically install runtime into app-private /lib" }
            val installed = InstalledRuntime(manifest.runtimeVersion, abi, File(finalDir, "qjs"), File(finalDir, "aria2c"))
            activate(base, manifest.runtimeVersion, abi, directoryName)
            return installed
        } catch (error: Throwable) {
            stage.deleteRecursively()
            throw error
        }
    }

    private fun preferredAbi(): String = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
        ?: throw IllegalStateException("This device ABI is not supported by the downloaded runtime packages")

    private fun download(asset: RuntimeAsset, target: File, onProgress: (String, Long, Long) -> Unit): File {
        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Idownld-Android/$APP_VERSION_NAME")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "GitHub asset ${asset.fileName} returned HTTP $code" }
            val hasher = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { input -> target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    require(received <= asset.sizeBytes && received <= MAX_RUNTIME_ASSET_BYTES) { "Runtime asset exceeded its manifest size" }
                    hasher.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    onProgress(asset.component, received, asset.sizeBytes)
                }
            } }
            require(received == asset.sizeBytes) { "Runtime asset size mismatch for ${asset.component}" }
            val digest = hasher.digest().joinToString("") { "%02x".format(it) }
            require(digest == asset.sha256) { "Runtime SHA-256 verification failed for ${asset.component}" }
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun makeExecutable(file: File) {
        check(file.setExecutable(true, true) || file.canExecute()) { "Could not mark ${file.name} executable" }
    }

    private fun activate(base: File, version: String, abi: String, directory: String) {
        val activeTemp = File(base, "active.json.tmp")
        val active = File(base, "active.json")
        activeTemp.writeText(JSONObject().put("runtimeVersion", version).put("abi", abi).put("directory", directory).toString())
        try {
            Files.move(activeTemp.toPath(), active.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(activeTemp.toPath(), active.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun selfTest(file: File, args: List<String>, label: String) {
        val process = try {
            ProcessBuilder(listOf(file.absolutePath) + args).redirectErrorStream(true).start()
        } catch (error: Exception) {
            throw IllegalStateException("Downloaded $label binary could not start", error)
        }
        val output = process.inputStream.bufferedReader().use { it.readText().take(500) }
        check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "$label binary self-test failed: $output" }
        process.destroy()
    }
}
