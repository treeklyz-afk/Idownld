package com.pyserver.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BackgroundEngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gatewayUrl = ""
    private var token = ""
    private var pollJob: Job? = null
    private var prefetchedPayload: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        gatewayUrl = intent?.getStringExtra("gateway_url")?.trim()?.trimEnd('/') ?: ""
        token = intent?.getStringExtra("token")?.trim() ?: ""
        prefetchedPayload = intent?.getStringExtra("prefetched_response")
        val gateway = try { URL(gatewayUrl) } catch (_: Exception) { null }
        if (gateway?.protocol != "https" || gateway.host.isBlank() || gateway.userInfo != null || token.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        createChannel()
        val notif: Notification = NotificationCompat.Builder(this, "pyserver2_channel")
            .setContentTitle("PyServer 2.0 Engine Active")
            .setContentText("Background service & token bridge running")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(303, notif)
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                pollGateway()
                delay(4000)
            }
        }
        return START_NOT_STICKY
    }

    private fun pollGateway() {
        try {
            val cached = prefetchedPayload
            prefetchedPayload = null
            val payload = if (cached != null) cached else {
            val conn = (URL("$gatewayUrl/api/bridge/next-job").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 6000
                readTimeout = 6000
            }
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                conn.disconnect()
                updateNotification("Gateway rejected token (HTTP $code); stopped")
                stopSelf()
                return
            }
            if (code !in 200..299) {
                conn.disconnect()
                updateNotification("Gateway error HTTP $code; retrying")
                return
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            response
            }
            val obj = JSONObject(payload)
            if (obj.optBoolean("has_job", false)) {
                    val jobId = obj.getString("job_id")
                    val url = obj.getString("url")
                    val type = obj.optString("media_type", "video")
                    updateNotification("Analyzing gateway URL with bundled yt-dlp")
                    val analysis = MediaDownloaderEngine.analyzeUrl(this, url)
                    val res: Result<String> = analysis.fold(
                        onSuccess = { info ->
                            val choice = if (type.equals("audio", ignoreCase = true)) {
                                info.formats.firstOrNull { it.mediaType == "audio" }
                            } else {
                                info.formats.firstOrNull { it.formatId == "best" }
                                    ?: info.formats.firstOrNull { it.mediaType == "video" }
                            }
                            when {
                                choice == null -> Result.failure(IllegalStateException("No compatible format was found for this gateway job"))
                                info.entries.isNotEmpty() -> MediaDownloaderEngine.downloadPlaylist(this, url, choice, null)
                                else -> MediaDownloaderEngine.downloadMedia(this, choice.sourceUrl ?: url, choice)
                            }
                        },
                        onFailure = { Result.failure(it) }
                    )
                    sendCompletion(jobId, res.isSuccess, res.getOrElse { it.message ?: "Error" })
                    updateNotification("Gateway service active; next check in a few seconds")
            }
        } catch (e: Exception) {
            updateNotification("Gateway error: ${e.message ?: "connection failed"}")
        }
    }

    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, "pyserver2_channel")
            .setContentTitle("PyServer 2.0 Gateway")
            .setContentText(message.take(120))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(303, notification)
    }

    private fun sendCompletion(jobId: String, ok: Boolean, detail: String) {
        try {
            val conn = (URL("$gatewayUrl/api/bridge/complete").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val payload = JSONObject().apply {
                put("job_id", jobId)
                put("success", ok)
                put("detail", detail)
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                "pyserver2_channel",
                "PyServer 2.0 Service",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    @androidx.annotation.RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        pollJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
