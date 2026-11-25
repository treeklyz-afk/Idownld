package com.pyserver.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ManifestPackage(
    val id: String,
    val name: String,
    val version: String,
    val category: String,
    val description: String,
    val bundled: Boolean
)

/** Catalog metadata only. Target SDK 35 builds executable tools into the signed APK. */
object AssetsAndPackageManager {
    private const val PREFS = "pyserver2_prefs"
    private const val KEY_MANIFEST = "manifest_url"
    const val DEFAULT_MANIFEST =
        "https://raw.githubusercontent.com/treeklyz-afk/Idownld/main/packages/manifest.json"

    fun getManifestUrl(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_MANIFEST, DEFAULT_MANIFEST) ?: DEFAULT_MANIFEST

    fun setManifestUrl(context: Context, url: String): Result<Unit> {
        val clean = url.trim()
        if (!clean.startsWith("https://", true)) return Result.failure(IllegalArgumentException("Manifest URL must use HTTPS"))
        return try {
            URL(clean)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MANIFEST, clean).apply()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    fun fetchManifest(context: Context, urlOverride: String? = null): Result<List<ManifestPackage>> {
        return try {
            val target = urlOverride?.trim()?.ifEmpty { null } ?: getManifestUrl(context)
            if (!target.startsWith("https://", true)) return Result.failure(IllegalArgumentException("Enter a valid HTTPS runtime catalog URL"))
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            val code = conn.responseCode
            if (code !in 200..299 || !conn.url.protocol.equals("https", true)) {
                conn.disconnect()
                return Result.failure(IllegalStateException("Catalog request failed (HTTP $code)"))
            }
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 512 * 1024) {
                        conn.disconnect()
                        return Result.failure(IllegalStateException("Catalog is larger than 512 KiB"))
                    }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            conn.disconnect()
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val array: JSONArray = root.optJSONArray("packages")
                ?: return Result.failure(IllegalArgumentException("Catalog has no packages array"))
            val packages = ArrayList<ManifestPackage>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id", "").trim()
                if (!Regex("[A-Za-z0-9][A-Za-z0-9+._-]{0,63}").matches(id)) continue
                packages += ManifestPackage(
                    id = id,
                    name = item.optString("name", id).take(100),
                    version = item.optString("version", "unknown").take(80),
                    category = item.optString("category", "runtime").take(40),
                    description = item.optString("description", "Version reference; executable requires APK delivery").take(240),
                    bundled = AppTerminalEngine.isPackageBundled(context, id)
                )
            }
            Result.success(packages)
        } catch (e: Exception) { Result.failure(e) }
    }

    fun packageUpdateInfo(pkg: ManifestPackage): String = if (pkg.bundled) {
        "${pkg.name} ${pkg.version} is marked as APK-bundled. Tool updates are delivered with a signed full-app APK update."
    } else {
        "${pkg.name} ${pkg.version} is catalog metadata only and is not bundled in this APK. At target SDK 35, executable tools cannot be installed into writable app storage; a compatible APK build is required."
    }

    fun runTerminalCommand(context: Context, rawCmd: String): String = AppTerminalEngine.runCommand(context, rawCmd).takeLast(7000)
}
