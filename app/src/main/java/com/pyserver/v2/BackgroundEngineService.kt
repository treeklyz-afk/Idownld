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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        gatewayUrl = intent?.getStringExtra("gateway_url")?.trim()?.trimEnd('/') ?: ""
        token = intent?.getStringExtra("token")?.trim() ?: ""
        createChannel()
        val notif: Notification = NotificationCompat.Builder(this, "pyserver2_channel")
            .setContentTitle("PyServer 2.0 Engine Running")
            .setContentText("Background service & Render token bridge active")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(303, notif)
        scope.launch {
            while (isActive) {
                if (gatewayUrl.isNotEmpty() && token.isNotEmpty()) pollGateway()
                delay(4000)
            }
        }
        return START_STICKY
    }

    private fun pollGateway() {
        try {
            val conn = (URL("$gatewayUrl/api/bridge/next-job").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 200) {
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                if (obj.optBoolean("has_job", false)) {
                    val jobId = obj.getString("job_id")
                    val url = obj.getString("url")
                    val fmtId = obj.optString("format_id", "best")
                    val type = obj.optString("media_type", "video")
                    val res = MediaDownloaderEngine.downloadMedia(
                        this,
                        url,
                        FormatChoice(fmtId, fmtId, type, "Token Job")
                    )
                    sendCompletion(jobId, res.isSuccess, res.getOrElse { it.message ?: "Error" })
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun sendCompletion(jobId: String, ok: Boolean, detail: String) {
        try {
            val conn = (URL("$gatewayUrl/api/bridge/complete").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
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
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
