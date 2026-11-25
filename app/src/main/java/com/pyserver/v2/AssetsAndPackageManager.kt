package com.pyserver.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A catalog item describes a package installed by Termux's real apt/pkg repository. */
data class ManifestPackage(
    val id: String,
    val name: String,
    val version: String,
    val category: String,
    val description: String
)

object AssetsAndPackageManager {
    private const val PREFS = "pyserver2_prefs"
    private const val KEY_MANIFEST = "manifest_url"
    const val DEFAULT_MANIFEST =
        "https://raw.githubusercontent.com/treeklyz-afk/Idownld/main/packages/manifest.json"

    fun getManifestUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANIFEST, DEFAULT_MANIFEST) ?: DEFAULT_MANIFEST

    fun setManifestUrl(context: Context, url: String): Result<Unit> {
        val clean = url.trim()
        if (!clean.startsWith("https://", true)) {
            return Result.failure(IllegalArgumentException("Manifest URL must use HTTPS"))
        }
        return try {
            URL(clean)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MANIFEST, clean).apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun fetchManifest(context: Context, urlOverride: String? = null): Result<List<ManifestPackage>> {
        return try {
            val target = urlOverride?.trim()?.ifEmpty { null } ?: getManifestUrl(context)
            if (!target.startsWith("https://", true)) {
                return Result.failure(IllegalArgumentException("Enter a valid HTTPS package manifest URL first"))
            }
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 20000
            }
            val code = conn.responseCode
            if (code !in 200..299 || !conn.url.protocol.equals("https", true)) {
                conn.disconnect()
                return Result.failure(IllegalStateException("Manifest request failed (HTTP $code)"))
            }
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 512 * 1024) {
                        conn.disconnect()
                        return Result.failure(IllegalStateException("Manifest is larger than 512 KiB"))
                    }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            conn.disconnect()
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val array: JSONArray = root.optJSONArray("packages")
                ?: return Result.failure(IllegalArgumentException("Manifest has no packages array"))
            val packages = ArrayList<ManifestPackage>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id", "").trim()
                if (!Regex("[A-Za-z0-9][A-Za-z0-9+._-]{0,63}").matches(id)) continue
                packages += ManifestPackage(
                    id = id,
                    name = item.optString("name", id).take(100),
                    version = item.optString("version", "latest from Termux").take(80),
                    category = item.optString("category", "package").take(40),
                    description = item.optString("description", "Install from the official Termux package repository").take(240)
                )
            }
            Result.success(packages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** `pkg` resolves and downloads the current package plus dependencies from Termux apt. */
    fun installPackage(context: Context, pkg: ManifestPackage): Result<String> {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9+._-]{0,63}").matches(pkg.id)) {
            return Result.failure(IllegalArgumentException("Invalid Termux package id"))
        }
        val result = TermuxCommandBridge.execute(
            context = context,
            executable = "${TermuxCommandBridge.TERMUX_PREFIX}/bin/pkg",
            arguments = listOf("install", "-y", pkg.id),
            timeoutSeconds = 1800
        )
        return result.fold(
            onSuccess = { command ->
                if (command.exitCode == 0) {
                    Result.success("${pkg.name}: installed/updated from the latest Termux repository\n${command.stdout.takeLast(2500)}")
                } else {
                    Result.failure(IllegalStateException((command.stderr.ifBlank { command.stdout }).takeLast(2500).ifBlank { "pkg exited with ${command.exitCode}" }))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    fun runTerminalCommand(context: Context, rawCmd: String): String {
        val command = rawCmd.trim()
        if (command.isEmpty()) return "Type a Termux command, for example: pkg install python"
        val result = TermuxCommandBridge.runShell(context, command, timeoutSeconds = 1800)
        return result.fold(
            onSuccess = { response ->
                buildString {
                    if (response.stdout.isNotBlank()) append(response.stdout.trimEnd())
                    if (response.stderr.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(response.stderr.trimEnd())
                    }
                    if (isEmpty()) append("Command finished (exit code ${response.exitCode})")
                    if (response.exitCode != 0) append("\nExit code: ${response.exitCode}")
                }.takeLast(7000)
            },
            onFailure = { "Termux command failed: ${it.message ?: "unknown error"}" }
        )
    }
}
