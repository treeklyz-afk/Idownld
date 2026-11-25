package com.pyserver.v2

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

object MediaStoreDownloads {
    fun stagingDirectory(context: Context): File =
        File(context.cacheDir, "PyServer2/staging").apply { mkdirs() }

    /** Publishes a finished staged file to Android's media library on Android 10+. */
    fun publish(context: Context, stagedFile: File, mediaType: String): Result<String> {
        if (!stagedFile.isFile || stagedFile.length() == 0L) {
            return Result.failure(IllegalArgumentException("Download is missing or empty"))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val base = when (mediaType.lowercase()) {
                "audio" -> File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "PyServer2/Audio")
                "gif", "image" -> File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PyServer2/GIF")
                else -> File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "PyServer2/Video")
            }
            if (!base.exists() && !base.mkdirs()) {
                return Result.failure(IllegalStateException("Could not create app media folder"))
            }
            val destination = uniqueFile(base, stagedFile.name)
            stagedFile.copyTo(destination, overwrite = false)
            stagedFile.delete()
            MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), null, null)
            return Result.success(destination.absolutePath)
        }

        val extension = stagedFile.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (mediaType.lowercase()) {
                "audio" -> "audio/*"
                "gif", "image" -> "image/gif"
                else -> "video/*"
            }
        val (collection, relativePath) = when (mediaType.lowercase()) {
            "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MUSIC}/PyServer2/Audio"
            "gif", "image" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_PICTURES}/PyServer2/GIF"
            else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MOVIES}/PyServer2/Video"
        }
        val resolver = context.contentResolver
        var inserted: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, stagedFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            inserted = resolver.insert(collection, values)
                ?: throw IllegalStateException("Android could not create the media entry")
            resolver.openOutputStream(inserted!!, "w")?.use { output ->
                stagedFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Android could not open the media entry")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(inserted!!, ready, null, null)
            stagedFile.delete()
            Result.success("${relativePath}/${stagedFile.name} (${inserted})")
        } catch (e: Exception) {
            inserted?.let { runCatching { resolver.delete(it, null, null) } }
            Result.failure(e)
        }
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val safeName = requestedName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "download" }
        var destination = File(directory, safeName)
        var counter = 1
        while (destination.exists()) {
            val dot = safeName.lastIndexOf('.')
            val base = if (dot > 0) safeName.substring(0, dot) else safeName
            val ext = if (dot > 0) safeName.substring(dot) else ""
            destination = File(directory, "$base ($counter)$ext")
            counter++
        }
        return destination
    }
}
