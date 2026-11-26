package com.pyserver.v2

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PyServerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogStore.install(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EnhancedPyServerApp() }
    }
}

@Composable
fun AnimatedMediaSpinner(label: String, accent: Color) {
    val transition = rememberInfiniteTransition(label = "gif_spinner_anim")
    val scale = transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "spinner_pulse"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1626)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .scale(scale.value)
                    .background(accent.copy(alpha = 0.15f), CircleShape)
            ) {
                CircularProgressIndicator(
                    color = accent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Animated Media Pipeline Active",
                    color = accent,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun PyServerMediaApp() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val tabState = remember { mutableStateOf(0) } // 0: Media, 1: Native Libraries, 2: Render
    val mediaUrlState = remember { mutableStateOf("") }
    val showConsoleState = remember { mutableStateOf(false) } // In-app diagnostics, not a shell

    val analysisState = remember { mutableStateOf<MediaAnalysis?>(null) }
    val selectedFormatState = remember { mutableStateOf<FormatChoice?>(null) }

    val playlistSelectionState = remember { mutableStateOf<Set<Int>>(emptySet()) }

    val gatewayUrlState = remember { mutableStateOf("") }
    val apiTokenState = remember { mutableStateOf("") }
    val bgActiveState = remember { mutableStateOf(false) }
    val gatewayVerifiedState = remember { mutableStateOf(false) }
    val gatewayStatusState = remember { mutableStateOf("Gateway token not verified") }
    val nativeReportState = remember { mutableStateOf<NativeRuntimeReport?>(null) }

    val workingState = remember { mutableStateOf(false) }
    val loaderTitleState = remember { mutableStateOf("Analyzing media...") }
    val statusMsgState = remember { mutableStateOf("Bundled yt-dlp can analyze supported sites and direct media URLs") }
    val logsState = remember { mutableStateOf(listOf<String>()) }

    fun appendLog(line: String) {
        logsState.value = (logsState.value + line).takeLast(12)
    }

    val bgDark = Color(0xFF060911)
    val cardDark = Color(0xFF0F172A)
    val borderSubtle = Color(0xFF1E293B)
    val cyanAccent = Color(0xFF00E5FF)
    val violetAccent = Color(0xFF8B5CF6)
    val greenOk = Color(0xFF10B981)

    Scaffold(
        containerColor = bgDark,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        bottomBar = {
            NavigationBar(containerColor = cardDark) {
                NavigationBarItem(
                    selected = tabState.value == 0,
                    onClick = { tabState.value = 0 },
                    icon = { Text("▶", color = if (tabState.value == 0) cyanAccent else Color.Gray) },
                    label = { Text("Media", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = tabState.value == 1,
                    onClick = { tabState.value = 1 },
                    icon = { Text("◈", color = if (tabState.value == 1) cyanAccent else Color.Gray) },
                    label = { Text("Libraries", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = tabState.value == 2,
                    onClick = { tabState.value = 2 },
                    icon = { Text("⚡", color = if (tabState.value == 2) cyanAccent else Color.Gray) },
                    label = { Text("Render", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Header Card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFF0F172A), Color(0xFF172038))),
                            RoundedCornerShape(18.dp)
                        )
                        .border(1.dp, borderSubtle, RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "PyServer 2.0",
                                color = cyanAccent,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (bgActiveState.value) "● BG ACTIVE" else "● READY",
                                color = if (bgActiveState.value) greenOk else cyanAccent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            statusMsgState.value,
                            color = Color(0xFFCBD5E1),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Animated Loading / GIF Spinner when busy
            if (workingState.value) {
                item {
                    AnimatedMediaSpinner(label = loaderTitleState.value, accent = cyanAccent)
                }
            }

            when (tabState.value) {
                0 -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, borderSubtle, RoundedCornerShape(18.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = mediaUrlState.value,
                                    onValueChange = { mediaUrlState.value = it },
                                    label = { Text("Paste a supported video-site or media URL") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Show Debug Console", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                                    Switch(checked = showConsoleState.value, onCheckedChange = { showConsoleState.value = it })
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            clipboard.getText()?.text?.let {
                                                mediaUrlState.value = it.trim()
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Paste", color = Color.White, maxLines = 1, softWrap = false)
                                    }
                                    Button(
                                        onClick = {
                                            workingState.value = true
                                            loaderTitleState.value = "Checking direct media URL..."
                                            scope.launch {
                                                val res = withContext(Dispatchers.IO) {
                                                    MediaDownloaderEngine.analyzeUrl(
                                                        context,
                                                        mediaUrlState.value
                                                    )
                                                }
                                                workingState.value = false
                                                res.onSuccess { info ->
                                                    analysisState.value = info
                                                    selectedFormatState.value = info.formats.firstOrNull()
                                                    playlistSelectionState.value = info.entries.map { it.playlistIndex }.toSet()
                                                    statusMsgState.value = if (info.entries.isEmpty()) "Loaded ${info.formats.size} formats" else "Listed ${info.entries.size} collection items"
                                                    appendLog("Analyzed: ${info.title}")
                                                }.onFailure { err ->
                                                    statusMsgState.value = err.message ?: "Analyze error"
                                                    appendLog("Analyze error: ${err.message}")
                                                }
                                            }
                                        },
                                        enabled = !workingState.value,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = cyanAccent,
                                            contentColor = Color.Black
                                        ),
                                        modifier = Modifier.weight(2f)
                                    ) {
                                        Text("Analyze", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }

                    analysisState.value?.let { info ->
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = cardDark),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, violetAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        info.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${info.extractor} • ${info.duration} • ${info.formats.size} formats${if (info.entries.isNotEmpty()) " • ${info.entries.size} items listed" else ""}",
                                        color = cyanAccent,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        if (info.entries.isNotEmpty()) {
                            item { Text("Collection preview is limited to ${info.entryLimit} items. Select listed items or download the whole collection.", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall) }
                        }
                        items(info.entries, key = { it.id }) { entry ->
                            Row(modifier = Modifier.fillMaxWidth().background(cardDark, RoundedCornerShape(10.dp)), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = entry.playlistIndex in playlistSelectionState.value, onCheckedChange = { checked ->
                                    playlistSelectionState.value = if (checked) playlistSelectionState.value + entry.playlistIndex else playlistSelectionState.value - entry.playlistIndex
                                })
                                Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text("${entry.playlistIndex}. ${entry.title}", color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(entry.id, color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        items(info.formats) { fmt ->
                            val chosen = selectedFormatState.value == fmt
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (chosen) Color(0xFF17253B) else cardDark
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (chosen) cyanAccent else borderSubtle,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { selectedFormatState.value = fmt }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            fmt.titleLabel,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            fmt.metaInfo,
                                            color = Color(0xFF94A3B8),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    RadioButton(
                                        selected = chosen,
                                        onClick = { selectedFormatState.value = fmt }
                                    )
                                }
                            }
                        }

                        item {
                            Button(
                                onClick = {
                                    val fmt = selectedFormatState.value ?: return@Button
                                    workingState.value = true
                                    loaderTitleState.value = "Downloading ${fmt.titleLabel}..."
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            if (info.entries.isEmpty()) MediaDownloaderEngine.downloadMedia(context, fmt.sourceUrl ?: mediaUrlState.value, fmt, useAria2 = false, onLog = { appendLog(it) })
                                            else MediaDownloaderEngine.downloadPlaylist(context, mediaUrlState.value, fmt, playlistSelectionState.value.toList(), useAria2 = false, onLog = { appendLog(it) })
                                        }
                                        workingState.value = false
                                        statusMsgState.value = res.getOrElse { it.message ?: "Download failed" }
                                        appendLog(statusMsgState.value)
                                    }
                                },
                                enabled = !workingState.value && selectedFormatState.value != null && (info.entries.isEmpty() || playlistSelectionState.value.isNotEmpty()),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cyanAccent,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (info.entries.isEmpty()) "Download Format" else "Download selected (${playlistSelectionState.value.size})", fontWeight = FontWeight.Bold)
                            }
                        }
                        if (info.entries.isNotEmpty()) {
                            item {
                                OutlinedButton(onClick = {
                                    val fmt = selectedFormatState.value ?: return@OutlinedButton
                                    workingState.value = true
                                    loaderTitleState.value = "Downloading full collection..."
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) { MediaDownloaderEngine.downloadPlaylist(context, mediaUrlState.value, fmt, selectedIndices = null, useAria2 = false, onLog = { appendLog(it) }) }
                                        workingState.value = false
                                        statusMsgState.value = res.getOrElse { it.message ?: "Download failed" }
                                        appendLog(statusMsgState.value)
                                    }
                                }, enabled = !workingState.value && selectedFormatState.value != null, modifier = Modifier.fillMaxWidth()) {
                                    Text("Download full collection", color = cyanAccent, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                1 -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, borderSubtle, RoundedCornerShape(18.dp))
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Native media runtime", color = cyanAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    nativeReportState.value?.summary ?: "Inspect the libraries packaged in this APK. No native tools are downloaded at runtime.",
                                    color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodyMedium
                                )
                                Button(
                                    onClick = {
                                        workingState.value = true
                                        loaderTitleState.value = "Inspecting APK native runtime..."
                                        scope.launch {
                                            val report = withContext(Dispatchers.IO) { NativeMediaRuntime.probe() }
                                            nativeReportState.value = report.copy(presentLibraries = NativeMediaRuntime.inventory(context).map { it.id })
                                            workingState.value = false
                                            statusMsgState.value = report.summary
                                        }
                                    },
                                    enabled = !workingState.value,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = cyanAccent, contentColor = Color.Black),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Inspect bundled runtime", fontWeight = FontWeight.Bold) }
                                nativeReportState.value?.ffmpegOutput?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                                nativeReportState.value?.ffprobeOutput?.takeIf { it.isNotBlank() }?.let {
                                    Text("FFprobe JNI check:\n$it", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                                Text(
                                    "Bundled extractor: CPython 3.13, yt-dlp 2026.11.23, yt-dlp-ejs 0.8.0, Jsoup 1.24.1. QuickJS is not included; YouTube EJS challenges may therefore fail. Site availability varies.",
                                    color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    items(NativeMediaRuntime.requestedLibraries) { library ->
                        val report = nativeReportState.value
                        val sharedObjectPresent = NativeMediaRuntime.isPresent(context, library.id)
                        val featurePresent = library.id in (report?.featureLibraries ?: emptyList())
                        val present = sharedObjectPresent || featurePresent
                        val verified = sharedObjectPresent || report != null
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, if (present) greenOk.copy(alpha = 0.6f) else borderSubtle, RoundedCornerShape(14.dp))
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(library.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when { featurePresent -> "FEATURE ON"; sharedObjectPresent -> "SO IN APK"; !verified -> "CHECK"; else -> "NOT INCLUDED" },
                                        color = if (present) greenOk else Color(0xFFFFD580),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(library.role, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall)
                                Text("License: ${library.license}", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    item {
                        Text(
                            "x264/x265 GPL encoders are not included in this LGPL build; FDK-AAC is excluded pending licensing and patent review. FFmpegKit's JNI API is bundled, but yt-dlp uses only muxed single-file video formats here because an FFmpeg CLI adapter is not bundled. QuickJS is not included, so full YouTube EJS challenge solving is not promised.",
                            color = Color(0xFFFFD580), style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                else -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, borderSubtle, RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Background Service", color = Color.White, fontWeight = FontWeight.Bold)
                                    Switch(
                                        checked = bgActiveState.value,
                                        enabled = !workingState.value,
                                        onCheckedChange = { enabled ->
                                            if (!enabled) {
                                                bgActiveState.value = false
                                                gatewayVerifiedState.value = false
                                                context.stopService(Intent(context, BackgroundEngineService::class.java))
                                            } else {
                                                val cleanUrl = gatewayUrlState.value.trim().trimEnd('/')
                                                val token = apiTokenState.value.trim()
                                                gatewayStatusState.value = "Verifying token with protected gateway endpoint…"
                                                workingState.value = true
                                                scope.launch {
                                                    runCatching { withContext(Dispatchers.IO) { GatewayApi.fetchNextJob(cleanUrl, token) } }
                                                        .onSuccess { preflight ->
                                                            val intent = Intent(context, BackgroundEngineService::class.java).apply {
                                                                putExtra("gateway_url", cleanUrl)
                                                                putExtra("token", token)
                                                                putExtra("prefetched_response", preflight.payload)
                                                            }
                                                            runCatching { ContextCompat.startForegroundService(context, intent) }
                                                                .onSuccess {
                                                                    gatewayVerifiedState.value = true
                                                                    bgActiveState.value = true
                                                                    gatewayStatusState.value = "Token verified. Background polling is active."
                                                                    statusMsgState.value = "Render gateway authenticated"
                                                                }
                                                                .onFailure { err ->
                                                                    gatewayVerifiedState.value = false
                                                                    bgActiveState.value = false
                                                                    gatewayStatusState.value = "Could not start service: ${err.message ?: "unknown error"}"
                                                                }
                                                        }
                                                        .onFailure { err ->
                                                            gatewayVerifiedState.value = false
                                                            bgActiveState.value = false
                                                            gatewayStatusState.value = err.message ?: "Gateway token verification failed"
                                                        }
                                                    workingState.value = false
                                                }
                                            }
                                        }
                                    )
                                }
                                OutlinedTextField(
                                    value = gatewayUrlState.value,
                                    onValueChange = { gatewayUrlState.value = it; gatewayVerifiedState.value = false },
                                    label = { Text("Render Gateway URL") },
                                    singleLine = true,
                                    enabled = !bgActiveState.value && !workingState.value,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = apiTokenState.value,
                                    onValueChange = { apiTokenState.value = it; gatewayVerifiedState.value = false },
                                    label = { Text("Gateway API Token") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    enabled = !bgActiveState.value && !workingState.value,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    gatewayStatusState.value,
                                    color = if (gatewayVerifiedState.value) greenOk else Color(0xFFFFD580),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Starting the service verifies the Bearer token against /api/bridge/next-job before polling. Background jobs can use bundled yt-dlp for supported site URLs and playlists. YouTube EJS may fail because QuickJS is not bundled.",
                                    color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    onClick = {
                                        val clean = gatewayUrlState.value.trim().trimEnd('/')
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$clean/tokenrender")))
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Open /tokenrender", color = cyanAccent)
                                }
                            }
                        }
                    }
                }
            }

            // Hidden by default; toggles on when user switches "Show Debug Console"
            item {
                AnimatedVisibility(visible = showConsoleState.value) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF05080F)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, borderSubtle, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Debug Console", color = cyanAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            logsState.value.forEach { line ->
                                Text(
                                    line,
                                    color = Color(0xFFCBD5E1),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
