package com.pyserver.v2

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** FFmpegKit's Android AAR is packaged in the APK and called through JNI. */
data class NativeLibraryInfo(
    val id: String,
    val name: String,
    val role: String,
    val license: String
)

data class NativeRuntimeReport(
    val available: Boolean,
    val summary: String,
    val ffmpegOutput: String,
    val presentLibraries: List<String>,
    val featureLibraries: List<String>
)

object NativeMediaRuntime {
    private val apkInventoryCache = ConcurrentHashMap<String, Set<String>>()
    // License strings are kept concise here; full texts/notices are provided by the AAR and license assets.
    val requestedLibraries = listOf(
        NativeLibraryInfo("libavcodec", "libavcodec", "Audio/video codecs and decoders", "FFmpeg LGPL; feature-dependent"),
        NativeLibraryInfo("libavformat", "libavformat", "Container formats and protocols", "FFmpeg LGPL; feature-dependent"),
        NativeLibraryInfo("libavutil", "libavutil", "Shared utilities", "FFmpeg LGPL"),
        NativeLibraryInfo("libavfilter", "libavfilter", "Audio/video filters", "FFmpeg LGPL; feature-dependent"),
        NativeLibraryInfo("libswscale", "libswscale", "Pixel-format scaling", "FFmpeg LGPL"),
        NativeLibraryInfo("libswresample", "libswresample", "Audio resampling", "FFmpeg LGPL"),
        NativeLibraryInfo("libass", "libass", "Subtitle rendering", "ISC; dependencies carry separate terms"),
        NativeLibraryInfo("libx264", "libx264", "H.264 encoder", "GPL or separate commercial license; not assumed"),
        NativeLibraryInfo("libx265", "libx265", "H.265 encoder", "GPL or separate commercial license; not assumed"),
        NativeLibraryInfo("libvpx", "libvpx", "VP8/VP9 codec", "BSD-style"),
        NativeLibraryInfo("libwebp", "libwebp", "WebP codec", "BSD-style"),
        NativeLibraryInfo("libopus", "libopus", "Opus audio codec", "BSD-style"),
        NativeLibraryInfo("libmp3lame", "libmp3lame", "MP3 encoder", "LGPL-2.0; source/relinking terms apply"),
        NativeLibraryInfo("libvorbis", "libvorbis", "Vorbis audio codec", "BSD-style"),
        NativeLibraryInfo("libfdk-aac", "libfdk-aac", "AAC encoder", "Fraunhofer terms and patent clearance pending; excluded"),
        NativeLibraryInfo("zlib", "zlib", "Compression support", "zlib license"),
        NativeLibraryInfo("bzip2", "bzip2", "Compression support", "bzip2 license"),
        NativeLibraryInfo("liblzma", "liblzma", "XZ/LZMA compression", "0BSD for liblzma; other XZ tools have separate terms")
    )

    private val sonames = mapOf(
        "libavcodec" to "libavcodec.so",
        "libavformat" to "libavformat.so",
        "libavutil" to "libavutil.so",
        "libavfilter" to "libavfilter.so",
        "libswscale" to "libswscale.so",
        "libswresample" to "libswresample.so",
        "libass" to "libass.so",
        "libx264" to "libx264.so",
        "libx265" to "libx265.so",
        "libvpx" to "libvpx.so",
        "libwebp" to "libwebp.so",
        "libopus" to "libopus.so",
        "libmp3lame" to "libmp3lame.so",
        "libvorbis" to "libvorbis.so",
        "libfdk-aac" to "libfdk_aac.so",
        "zlib" to "libz.so",
        "bzip2" to "libbz2.so",
        "liblzma" to "liblzma.so"
    )

    fun isPresent(context: Context, id: String): Boolean {
        val soname = sonames[id] ?: return false
        if (File(context.applicationInfo.nativeLibraryDir, soname).isFile) return true
        val apkPaths = listOfNotNull(context.applicationInfo.sourceDir) + (context.applicationInfo.splitSourceDirs?.toList() ?: emptyList())
        return apkPaths.any { path ->
            val entries = apkInventoryCache.getOrPut(path) {
                runCatching {
                    ZipFile(path).use { zip ->
                        zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }.toSet()
                    }
                }.getOrDefault(emptySet())
            }
            entries.any { it.endsWith("/$soname") }
        }
    }

    fun probe(): NativeRuntimeReport = try {
        fun run(command: String): String {
            val session = FFmpegKit.execute(command)
            return session.getAllLogsAsString().trim()
        }
        val version = run("-hide_banner -version")
        val encoders = run("-hide_banner -encoders").lowercase()
        val filters = run("-hide_banner -filters").lowercase()
        val buildConf = run("-hide_banner -buildconf").lowercase()
        val sessionSuccess = version.contains("ffmpeg version", ignoreCase = true)
        val featureLibraries = buildList {
            if (Regex("\\bass\\b").containsMatchIn(filters)) add("libass")
            if ("libx264" in encoders) add("libx264")
            if ("libx265" in encoders) add("libx265")
            if ("libvpx" in encoders) add("libvpx")
            if ("libwebp" in encoders) add("libwebp")
            if ("libopus" in encoders) add("libopus")
            if ("libmp3lame" in encoders) add("libmp3lame")
            if ("libvorbis" in encoders) add("libvorbis")
            if ("libfdk_aac" in encoders || "libfdk-aac" in encoders) add("libfdk-aac")
            if ("--enable-zlib" in buildConf) add("zlib")
            if ("--enable-bzlib" in buildConf) add("bzip2")
            if ("--enable-lzma" in buildConf) add("liblzma")
        }
        NativeRuntimeReport(
            available = sessionSuccess,
            summary = if (sessionSuccess) "FFmpeg JNI engine loaded from the APK; optional features checked at runtime" else "Could not confirm the FFmpeg JNI engine",
            ffmpegOutput = version.take(5000),
            presentLibraries = emptyList(),
            featureLibraries = featureLibraries
        )
    } catch (e: Throwable) {
        NativeRuntimeReport(
            available = false,
            summary = "Native engine unavailable: ${e.message ?: e.javaClass.simpleName}",
            ffmpegOutput = "",
            presentLibraries = emptyList(),
            featureLibraries = emptyList()
        )
    }

    fun inventory(context: Context): List<NativeLibraryInfo> = requestedLibraries.filter { isPresent(context, it.id) }
}
