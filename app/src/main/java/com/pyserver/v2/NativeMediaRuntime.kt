package com.pyserver.v2

import android.content.Context

/** Legacy diagnostics surface retained for older activities; FFmpeg JNI is intentionally not packaged. */
data class NativeLibraryInfo(val id: String, val name: String, val role: String, val license: String)

data class NativeRuntimeReport(
    val available: Boolean,
    val summary: String,
    val ffmpegOutput: String,
    val ffprobeOutput: String,
    val presentLibraries: List<String>,
    val featureLibraries: List<String>
)

object NativeMediaRuntime {
    val requestedLibraries = listOf(
        NativeLibraryInfo("quickjs", "QuickJS-NG", "JavaScript challenge runtime for yt-dlp EJS", "MIT; installed from verified GitHub release"),
        NativeLibraryInfo("aria2", "aria2c", "Optional multi-connection HTTP(S) transfer helper", "GPL-2.0-or-later; installed from verified GitHub release")
    )

    fun isPresent(context: Context, id: String): Boolean {
        val runtime = RuntimePackageManager.installed(context) ?: return false
        return when (id) {
            "quickjs" -> runtime.quickJs.isFile
            "aria2" -> runtime.aria2.isFile
            else -> false
        }
    }

    fun inventory(context: Context): List<NativeLibraryInfo> = requestedLibraries.filter { isPresent(context, it.id) }

    fun probe(context: Context? = null): NativeRuntimeReport {
        val names = context?.let { inventory(it).map(NativeLibraryInfo::id) }.orEmpty()
        val present = names.isNotEmpty()
        return NativeRuntimeReport(
            available = present,
            summary = if (present) "Verified QuickJS and aria2 helpers are installed under app-private /lib." else "No verified helper runtime is installed yet. Connect to the internet and open runtime setup.",
            ffmpegOutput = "FFmpeg JNI was removed: it was not used by yt-dlp's download/merge path.",
            ffprobeOutput = "",
            presentLibraries = names,
            featureLibraries = names
        )
    }
}
