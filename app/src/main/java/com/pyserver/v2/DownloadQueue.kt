package com.pyserver.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Durable view of one queued or completed media download. */
data class DownloadTaskSnapshot(
    val id: UUID,
    val taskId: String,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val state: WorkInfo.State,
    val isPaused: Boolean,
    val failureMessage: String
) {
    val paused: Boolean get() = isPaused
}

/** Enqueue independent requests: each can be observed, paused, resumed, or cancelled separately. */
object DownloadQueue {
    const val TAG = "pyserver-download"

    private const val KEY_TASK_ID = "task_id"
    private const val KEY_TITLE = "title"
    private const val KEY_URL = "source_url"
    private const val KEY_THUMBNAIL_URL = "thumbnail_url"
    private const val KEY_FORMAT_ID = "format_id"
    private const val KEY_MEDIA_TYPE = "media_type"
    private const val KEY_META_INFO = "meta_info"
    private const val KEY_FORMAT_SOURCE_URL = "format_source_url"
    private const val KEY_HEIGHT = "height"
    private const val KEY_SELECTED_INDICES = "selected_indices_csv"
    private const val KEY_IS_PLAYLIST = "is_playlist"

    fun enqueue(
        context: Context,
        title: String,
        sourceUrl: String,
        choice: FormatChoice,
        selectedPlaylistIndices: List<Int>? = null,
        isPlaylist: Boolean = false,
        wifiOnly: Boolean = false,
        thumbnailUrl: String = ""
    ): UUID {
        val taskId = UUID.randomUUID().toString()
        val input = workDataOf(
            KEY_TASK_ID to taskId,
            KEY_TITLE to title,
            KEY_URL to sourceUrl,
            KEY_THUMBNAIL_URL to thumbnailUrl,
            KEY_FORMAT_ID to choice.formatId,
            KEY_MEDIA_TYPE to choice.mediaType,
            KEY_META_INFO to choice.metaInfo,
            KEY_FORMAT_SOURCE_URL to (choice.sourceUrl ?: ""),
            KEY_HEIGHT to choice.height,
            KEY_SELECTED_INDICES to (selectedPlaylistIndices?.joinToString(",") ?: ""),
            KEY_IS_PLAYLIST to isPlaylist
        )
        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(taskId)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
        return request.id
    }

    /** Pause is cooperative: the engine observes a marker file during its I/O loop. */
    fun pause(context: Context, id: UUID): Boolean = setPauseMarker(context, id, paused = true)

    fun resume(context: Context, id: UUID): Boolean = setPauseMarker(context, id, paused = false)

    private fun setPauseMarker(context: Context, id: UUID, paused: Boolean): Boolean {
        val wm = WorkManager.getInstance(context.applicationContext)
        val info = runCatching { wm.getWorkInfoById(id).get() }.getOrNull() ?: return false
        if (info.state.isFinished) return false
        val taskId = info.inputData.getString(KEY_TASK_ID) ?: id.toString()
        val dir = taskDirectory(context, taskId)
        val marker = File(dir, ".paused")
        return runCatching {
            if (paused) {
                dir.mkdirs()
                marker.writeText("paused")
            } else {
                marker.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, id: UUID) {
        WorkManager.getInstance(context.applicationContext).cancelWorkById(id)
    }

    /** Query all queue-tagged requests, including persisted terminal WorkManager records. */
    suspend fun query(context: Context): List<DownloadTaskSnapshot> =
        WorkManager.getInstance(context.applicationContext).getWorkInfosByTag(TAG).get().map { info ->
            snapshot(context, info)
        }

    suspend fun queryById(context: Context, id: UUID): DownloadTaskSnapshot? =
        WorkManager.getInstance(context.applicationContext).getWorkInfoById(id).get()?.let { snapshot(context, it) }

    private fun snapshot(context: Context, info: WorkInfo): DownloadTaskSnapshot {
        val input = info.inputData
        val taskId = input.getString(KEY_TASK_ID) ?: info.id.toString()
        return DownloadTaskSnapshot(
            id = info.id,
            taskId = taskId,
            title = input.getString(KEY_TITLE).orEmpty().ifBlank { "Media download" },
            sourceUrl = input.getString(KEY_URL).orEmpty(),
            thumbnailUrl = input.getString(KEY_THUMBNAIL_URL).orEmpty(),
            downloadedBytes = info.progress.getLong(MediaDownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
            totalBytes = info.progress.getLong(MediaDownloadWorker.KEY_TOTAL_BYTES, 0L),
            state = info.state,
            isPaused = File(taskDirectory(context, taskId), ".paused").exists(),
            failureMessage = info.outputData.getString("error").orEmpty()
        )
    }

    internal fun taskDirectory(context: Context, taskId: String): File =
        File(File(context.filesDir, "PyServer2/tasks"), taskId)
}

/** Worker delegates actual transfer and cooperative pause handling to the media engine. */
class MediaDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_STATUS = "status"
        private const val CHANNEL_ID = "pyserver_downloads"
    }

    private val notificationId: Int get() = 0x51000000 or (id.hashCode() and 0x00ffffff)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString("task_id") ?: id.toString()
        val title = inputData.getString("title").orEmpty().ifBlank { "Media download" }
        val url = inputData.getString("source_url").orEmpty()
        val formatId = inputData.getString("format_id").orEmpty()
        if (url.isBlank() || formatId.isBlank()) return@withContext Result.failure()
        val choice = FormatChoice(
            formatId = formatId,
            titleLabel = title,
            mediaType = inputData.getString("media_type").orEmpty().ifBlank { "video" },
            metaInfo = inputData.getString("meta_info").orEmpty(),
            sourceUrl = inputData.getString("format_source_url")?.takeIf { it.isNotBlank() },
            height = inputData.getInt("height", 0)
        )
        val rawIndices = inputData.getString("selected_indices_csv").orEmpty()
        val selected = rawIndices.takeIf { it.isNotBlank() }?.split(',')?.mapNotNull { it.toIntOrNull() }
        val playlist = inputData.getBoolean("is_playlist", false)
        val dir = DownloadQueue.taskDirectory(applicationContext, taskId)
        dir.mkdirs()
        var lastUpdateMs = 0L
        var lastBytes = -1L
        var terminal = false
        try {
            setForeground(createForeground(title, taskId, 0L, 0L, paused = File(dir, ".paused").exists()))
            val callback: (DownloadProgress) -> Unit = { p ->
                // Engine I/O callbacks are synchronous; bridge to WorkManager's suspend APIs.
                runBlocking {
                    if (isStopped) throw kotlinx.coroutines.CancellationException("Download worker stopped")
                    val paused = File(dir, ".paused").exists()
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs >= 1000L || p.downloadedBytes - lastBytes >= 1024L * 1024L || p.status != "downloading") {
                        val data = workDataOf(
                            KEY_DOWNLOADED_BYTES to p.downloadedBytes.coerceAtLeast(0L),
                            KEY_TOTAL_BYTES to p.totalBytes.coerceAtLeast(0L),
                            KEY_FILE_NAME to p.fileName,
                            KEY_STATUS to p.status,
                            "paused" to paused
                        )
                        setProgress(data)
                        setForeground(createForeground(title, taskId, p.downloadedBytes, p.totalBytes, paused))
                        lastUpdateMs = now
                        lastBytes = p.downloadedBytes
                    }
                }
            }
            val result = if (playlist) {
                MediaDownloaderEngine.downloadPlaylist(
                    context = applicationContext,
                    rawUrl = url,
                    choice = choice,
                    selectedIndices = selected,
                    taskId = taskId,
                    onProgress = callback
                )
            } else {
                MediaDownloaderEngine.downloadMedia(
                    context = applicationContext,
                    rawUrl = url,
                    choice = choice,
                    taskId = taskId,
                    onProgress = callback
                )
            }
            if (isStopped) throw kotlinx.coroutines.CancellationException("Download worker stopped")
            result.fold(
                onSuccess = { message ->
                    setProgress(workDataOf(KEY_DOWNLOADED_BYTES to lastBytes.coerceAtLeast(0L), KEY_TOTAL_BYTES to 0L, KEY_STATUS to "complete", "paused" to false))
                    terminal = true
                    val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(notificationId, NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(title.take(100))
                        .setContentText(message.take(180))
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setAutoCancel(true)
                        .setOngoing(false)
                        .build())
                    Result.success(workDataOf("message" to message, KEY_STATUS to "complete"))
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException || isStopped) throw kotlinx.coroutines.CancellationException("Download cancelled")
                    val message = error.message ?: "Download failed"
                    val retry = isTransientFailure(message)
                    terminal = !retry
                    if (retry) Result.retry() else Result.failure(workDataOf("error" to message, KEY_STATUS to "failed"))
                }
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            terminal = true
            Result.failure(workDataOf("error" to (error.message ?: "Download failed"), KEY_STATUS to "failed"))
        } finally {
            if (terminal) {
                File(dir, ".paused").delete()
            }
        }
    }

    private fun isTransientFailure(message: String): Boolean {
        val text = message.lowercase()
        return listOf("timed out", "timeout", "connection reset", "connection refused", "network is unreachable", "temporary failure", "temporarily unavailable", "http error 429", "http error 5", "server returned http 5").any { text.contains(it) }
    }

    private suspend fun createForeground(title: String, taskId: String, downloaded: Long, total: Long, paused: Boolean): ForegroundInfo {
        val notification = buildNotification(title, taskId, downloaded, total, paused).build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }

    private fun buildNotification(title: String, taskId: String, downloaded: Long, total: Long, paused: Boolean): NotificationCompat.Builder {
        ensureChannel()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val percent = if (total > 0L) ((downloaded * 100L / total).coerceIn(0L, 100L)).toInt() else 0
        val text = if (total > 0L) "${formatByteCount(downloaded)} / ${formatByteCount(total)}${if (paused) " • Paused" else ""}" else if (paused) "Paused" else "Downloading…"
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.take(100))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (total > 0L) 100 else 0, percent, total <= 0L)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        builder.addAction(0, if (paused) "Resume" else "Pause", actionPendingIntent(taskId, DownloadQueueActionReceiver.ACTION_TOGGLE))
        builder.addAction(0, "Cancel", actionPendingIntent(taskId, DownloadQueueActionReceiver.ACTION_CANCEL))
        return builder
    }

    private fun actionPendingIntent(taskId: String, action: String): PendingIntent {
        val intent = Intent(applicationContext, DownloadQueueActionReceiver::class.java)
            .setAction(action)
            .putExtra(DownloadQueueActionReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(DownloadQueueActionReceiver.EXTRA_WORK_ID, id.toString())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(applicationContext, (id.hashCode() * 31) + action.hashCode(), intent, flags)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Media downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress and controls for queued media downloads"
                })
            }
        }
    }

}

/** Manifest-declarable receiver for the explicit notification controls. */
class DownloadQueueActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TOGGLE = "com.pyserver.v2.download.TOGGLE_PAUSE"
        const val ACTION_CANCEL = "com.pyserver.v2.download.CANCEL"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_WORK_ID = "work_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val workId = intent.getStringExtra(EXTRA_WORK_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: intent.getStringExtra(EXTRA_TASK_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@launch
                when (intent.action) {
                    ACTION_TOGGLE -> {
                        val info = runCatching { WorkManager.getInstance(context.applicationContext).getWorkInfoById(workId).get() }.getOrNull()
                        if (info?.inputData?.getString("task_id") != null) {
                            val markerExists = File(DownloadQueue.taskDirectory(context, info.inputData.getString("task_id")!!), ".paused").exists()
                            if (markerExists) DownloadQueue.resume(context, workId) else DownloadQueue.pause(context, workId)
                        }
                    }
                    ACTION_CANCEL -> DownloadQueue.cancel(context, workId)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
