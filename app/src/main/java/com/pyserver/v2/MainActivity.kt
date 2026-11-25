package com.pyserver.v2

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PyServerMediaApp() }
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

    val tabState = remember { mutableStateOf(0) } // 0: Media, 1: PKG & Terminal, 2: Token & BG
    val mediaUrlState = remember { mutableStateOf("") }
    val useAria2State = remember { mutableStateOf(false) }
    val usePyBridgeState = remember { mutableStateOf(false) }
    val showConsoleState = remember { mutableStateOf(false) } // Console hidden by default

    val analysisState = remember { mutableStateOf<MediaAnalysis?>(null) }
    val selectedFormatState = remember { mutableStateOf<FormatChoice?>(null) }

    val manifestUrlState = remember { mutableStateOf(AssetsAndPackageManager.getManifestUrl(context)) }
    val packagesState = remember { mutableStateOf<List<ManifestPackage>>(emptyList()) }
    val termuxInstalledState = remember { mutableStateOf(TermuxCommandBridge.isInstalled(context)) }
    val termuxPermissionState = remember { mutableStateOf(TermuxCommandBridge.hasRunCommandPermission(context)) }
    val playlistSelectionState = remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Terminal state inside PKG tab
    val termCmdState = remember { mutableStateOf("pkg list-installed") }
    val termOutputState = remember { mutableStateOf("Connect the official Termux app to run real pkg and shell commands.") }

    val gatewayUrlState = remember { mutableStateOf("") }
    val apiTokenState = remember { mutableStateOf("") }
    val bgActiveState = remember { mutableStateOf(false) }

    val workingState = remember { mutableStateOf(false) }
    val loaderTitleState = remember { mutableStateOf("Analyzing media...") }
    val statusMsgState = remember { mutableStateOf("Install Termux and yt-dlp for site analysis; direct media URLs can be inspected without it") }
    val logsState = remember { mutableStateOf(listOf<String>()) }

    fun appendLog(line: String) {
        logsState.value = (logsState.value + line).takeLast(12)
    }

    fun refreshTermuxStatus() {
        termuxInstalledState.value = TermuxCommandBridge.isInstalled(context)
        termuxPermissionState.value = TermuxCommandBridge.hasRunCommandPermission(context)
    }

    LaunchedEffect(Unit) {
        refreshTermuxStatus()
        val result = withContext(Dispatchers.IO) { AssetsAndPackageManager.fetchManifest(context) }
        result.onSuccess { packagesState.value = it }
            .onFailure { appendLog("Catalog load failed: ${it.message}") }
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
                    onClick = { tabState.value = 1; refreshTermuxStatus() },
                    icon = { Text(">_", color = if (tabState.value == 1) cyanAccent else Color.Gray) },
                    label = { Text("PKG Term", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = tabState.value == 2,
                    onClick = { tabState.value = 2 },
                    icon = { Text("⚡", color = if (tabState.value == 2) cyanAccent else Color.Gray) },
                    label = { Text("Token BG", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
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
                                    label = { Text("Paste a direct media-file URL (MP4, WebM, MP3, GIF)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Use aria2c if installed", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = useAria2State.value, onCheckedChange = { useAria2State.value = it })
                                }

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
                                                        mediaUrlState.value,
                                                        usePyBridgeState.value
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
                                            if (info.entries.isEmpty()) MediaDownloaderEngine.downloadMedia(context, mediaUrlState.value, fmt, useAria2State.value) { appendLog(it) }
                                            else MediaDownloaderEngine.downloadPlaylist(context, mediaUrlState.value, fmt, playlistSelectionState.value.toList(), useAria2State.value) { appendLog(it) }
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
                                        val res = withContext(Dispatchers.IO) { MediaDownloaderEngine.downloadPlaylist(context, mediaUrlState.value, fmt, null, useAria2State.value) { appendLog(it) } }
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
                    // Terminal Runner inside PKG tab
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
                                Text("Official Termux Companion", color = cyanAccent, fontWeight = FontWeight.Bold)
                                Text(if (!termuxInstalledState.value) "Install Termux separately to use real pkg and yt-dlp." else if (!termuxPermissionState.value) "Grant PyServer the Termux Run Command permission and enable allow-external-apps in Termux." else "Connected to Termux. Package installs use its official apt repository.", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = { if (termuxInstalledState.value) TermuxCommandBridge.openTermux(context) else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app/releases"))) }, modifier = Modifier.weight(1f)) { Text(if (termuxInstalledState.value) "Open Termux" else "Install Termux", color = cyanAccent) }
                                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.weight(1f)) { Text("Grant Run permission", color = cyanAccent) }
                                }
                                Text("One-time setup: in Android App Info > Permissions > Additional permissions, grant “Run commands in Termux environment” to this app. In Termux set allow-external-apps=true in ~/.termux/termux.properties. Run termux-setup-storage and grant Termux shared-storage access (Android 11+ may require its “manage all files” access). This app itself does not request broad all-files access.", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = termCmdState.value,
                                    onValueChange = { termCmdState.value = it },
                                    label = { Text("Termux command (example: pkg install python-yt-dlp)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        workingState.value = true
                                        loaderTitleState.value = "Running terminal command..."
                                        scope.launch {
                                            val out = withContext(Dispatchers.IO) {
                                                AssetsAndPackageManager.runTerminalCommand(context, termCmdState.value)
                                            }
                                            workingState.value = false
                                            termOutputState.value = out
                                        }
                                    },
                                    enabled = !workingState.value,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = cyanAccent,
                                        contentColor = Color.Black
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Execute Command", fontWeight = FontWeight.Bold)
                                }
                                Surface(
                                    color = Color(0xFF05080F),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        termOutputState.value,
                                        color = greenOk,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // GitHub Manifest Package Manager
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
                                OutlinedTextField(
                                    value = manifestUrlState.value,
                                    onValueChange = { manifestUrlState.value = it },
                                    label = { Text("GitHub manifest.json URL") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        workingState.value = true
                                        loaderTitleState.value = "Syncing packages..."
                                        scope.launch {
                                            val saved = AssetsAndPackageManager.setManifestUrl(context, manifestUrlState.value)
                                            val res = if (saved.isSuccess) withContext(Dispatchers.IO) { AssetsAndPackageManager.fetchManifest(context, manifestUrlState.value) }
                                                else Result.failure(saved.exceptionOrNull() ?: IllegalStateException("Could not save manifest URL"))
                                            workingState.value = false
                                            res.onSuccess { list ->
                                                packagesState.value = list
                                                statusMsgState.value = "${list.size} Termux packages ready; Install/update fetches the latest repository version"
                                            }.onFailure { err ->
                                                statusMsgState.value = err.message ?: "Manifest error"
                                            }
                                        }
                                    },
                                    enabled = !workingState.value,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = cyanAccent,
                                        contentColor = Color.Black
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Fetch Manifest")
                                }
                            }
                        }
                    }

                    items(packagesState.value) { pkg ->
                        val btnLabel = "Install / update"
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, borderSubtle, RoundedCornerShape(14.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(pkg.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${pkg.id} • catalog version ${pkg.version}", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                                    Text(pkg.description, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Button(
                                    onClick = {
                                        workingState.value = true
                                        loaderTitleState.value = "Installing ${pkg.name}..."
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                AssetsAndPackageManager.installPackage(context, pkg)
                                            }
                                            workingState.value = false
                                            refreshTermuxStatus()
                                            statusMsgState.value = res.getOrElse { it.message ?: "Failed" }
                                            termOutputState.value = statusMsgState.value
                                        }
                                    },
                                    enabled = !workingState.value,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = cyanAccent, contentColor = Color.Black)
                                ) {
                                    Text(btnLabel, maxLines = 1, softWrap = false)
                                }
                            }
                        }
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
                                        onCheckedChange = { enabled ->
                                            if (enabled && (!gatewayUrlState.value.trim().startsWith("https://", true) || apiTokenState.value.isBlank())) {
                                                bgActiveState.value = false
                                                statusMsgState.value = "Enter a valid HTTPS gateway URL and API token before starting the service"
                                                return@Switch
                                            }
                                            bgActiveState.value = enabled
                                            val intent = Intent(context, BackgroundEngineService::class.java).apply {
                                                putExtra("gateway_url", gatewayUrlState.value.trim())
                                                putExtra("token", apiTokenState.value.trim())
                                            }
                                            if (enabled) ContextCompat.startForegroundService(context, intent)
                                            else context.stopService(intent)
                                        }
                                    )
                                }
                                OutlinedTextField(
                                    value = gatewayUrlState.value,
                                    onValueChange = { gatewayUrlState.value = it },
                                    label = { Text("Render Gateway URL") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = apiTokenState.value,
                                    onValueChange = { apiTokenState.value = it },
                                    label = { Text("API Token (/tokenrender)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
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
