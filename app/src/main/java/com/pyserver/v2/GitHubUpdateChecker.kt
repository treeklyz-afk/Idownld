package com.pyserver.v2

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val GITHUB_REPO = "treeklyz-afk/Idownld"
private const val GITHUB_RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
const val GITHUB_RELEASES_PAGE = "https://github.com/$GITHUB_REPO/releases"
const val APP_VERSION_NAME = "2.8.5"

data class LatestGithubRelease(
    val tag: String,
    val title: String,
    val pageUrl: String,
    val notes: String,
    val apkUrl: String?
)

object GitHubUpdateChecker {
    fun checkLatestRelease(): LatestGithubRelease? {
        val connection = (URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PyServer2-Android/$APP_VERSION_NAME")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) throw IllegalStateException("GitHub returned HTTP $code")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                        break
                    }
                }
            }
            return LatestGithubRelease(
                tag = json.optString("tag_name", "Unknown"),
                title = json.optString("name", json.optString("tag_name", "Latest release")),
                pageUrl = json.optString("html_url", GITHUB_RELEASES_PAGE),
                notes = json.optString("body", "").take(1200),
                apkUrl = apkUrl
            )
        } finally {
            connection.disconnect()
        }
    }
}
