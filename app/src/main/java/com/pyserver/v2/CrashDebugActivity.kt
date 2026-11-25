package com.pyserver.v2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object CrashLogStore {
    private const val PREFS = "pyserver_crash_debug"
    private const val KEY_LOG = "latest_crash"

    fun install(context: android.content.Context) {
        val app = context.applicationContext
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = error.stackTraceToString().take(60_000)
                app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                    .putString(KEY_LOG, "Thread: ${thread.name}\nTime: ${java.util.Date()}\n\n$trace")
                    .commit()
            }
            prior?.uncaughtException(thread, error)
        }
    }

    fun recordHandled(context: android.content.Context, contextLabel: String, error: Throwable) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString(KEY_LOG, "$contextLabel\nTime: ${java.util.Date()}\n\n${error.stackTraceToString().take(60_000)}")
                .apply()
        }
    }

    fun read(context: android.content.Context): String = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY_LOG, "").orEmpty()
    fun clear(context: android.content.Context) { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().remove(KEY_LOG).apply() }
}

class CrashDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val log = remember { mutableStateOf(CrashLogStore.read(this)) }
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Color(0xFF36D9E8), background = Color(0xFF070B13), surface = Color(0xFF111A2B))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF070B13)) {
                    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Crash diagnostics", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Latest uncaught app error stored on this device. Share this log when reporting a crash; it may contain URLs or other private data.", color = Color(0xFFAAB7CC), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f)) { Text("Back") }
                            Button(onClick = { CrashLogStore.clear(this@CrashDebugActivity); log.value = "" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF36D9E8), contentColor = Color(0xFF070B13))) { Text("Clear log") }
                        }
                        Text(if (log.value.isBlank()) "No crash log recorded." else log.value, modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111A2B), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(12.dp).verticalScroll(rememberScrollState()), color = Color(0xFFD7E2F2), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
