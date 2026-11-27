package com.pyserver.v2

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

object CrashLogStore {
    private const val PREFS = "pyserver_crash_debug"
    private const val KEY_LOG = "latest_crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = error.stackTraceToString().take(60_000)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LOG, "Thread: ${thread.name}\nTime: ${java.util.Date()}\n\n$trace")
                    .commit()
            }
            prior?.uncaughtException(thread, error)
        }
    }

    fun recordHandled(context: Context, contextLabel: String, error: Throwable) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LOG, "$contextLabel\nTime: ${java.util.Date()}\n\n${error.stackTraceToString().take(60_000)}")
                .apply()
        }
    }

    fun read(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, "").orEmpty()
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LOG).apply() }
}

class CrashDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var log by remember { mutableStateOf(CrashLogStore.read(this)) }
            var copied by rememberSaveable { mutableStateOf(false) }
            val clipboard = LocalClipboardManager.current
            val accent = Color(0xFFBD36FF)
            val ink = Color(0xFF070612)
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = accent, background = ink, surface = Color(0xFF15132A))) {
                Surface(modifier = Modifier.fillMaxSize(), color = ink) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = CircleShape, color = accent.copy(alpha = 0.17f)) {
                                Text("!", color = accent, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Crash diagnostics", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("IDOWNLD • DEVICE LOG", color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { finish() }, shape = RoundedCornerShape(14.dp)) { Text("Back", color = accent) }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF15132A)),
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (log.isBlank()) "No crash recorded" else "Latest app error", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Logs stay on this device. Copy or share them when reporting a problem. A log can contain private URLs or other sensitive details.",
                                    color = Color(0xFFB8B4CE), style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    clipboard.setText(AnnotatedString(log.ifBlank { "No crash log recorded." }))
                                    copied = true
                                },
                                enabled = log.isNotBlank(),
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(17.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = ink)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.padding(3.dp))
                                Text(if (copied) "Copied" else "Copy log", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = {
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "Idownld crash log")
                                        putExtra(Intent.EXTRA_TEXT, log)
                                    }
                                    runCatching { startActivity(Intent.createChooser(share, "Share crash log")) }
                                },
                                enabled = log.isNotBlank(),
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(17.dp)
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, tint = accent)
                                Spacer(Modifier.padding(3.dp))
                                Text("Share log", color = accent, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(
                                onClick = { CrashLogStore.clear(this@CrashDebugActivity); log = ""; copied = false },
                                enabled = log.isNotBlank(),
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = Color(0xFFFF9B9B))
                                Spacer(Modifier.padding(3.dp))
                                Text("Clear log", color = Color(0xFFFF9B9B))
                            }
                        }
                        if (copied) Text("Crash log copied to clipboard.", color = accent, style = MaterialTheme.typography.labelMedium)

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF11101F)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (log.isBlank()) "No crash log recorded yet. Reproduce the problem, reopen Idownld, then return here."
                                else log,
                                modifier = Modifier.fillMaxWidth().height(420.dp).padding(15.dp).verticalScroll(rememberScrollState()),
                                color = Color(0xFFE1DDF2),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
