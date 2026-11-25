package com.pyserver.v2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PyServerStudioApp() }
    }
}

@Composable
fun PyServerStudioApp() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }
    var mediaUrl by remember { mutableStateOf("") }
    var useAria2 by remember { mutableStateOf(true) }
    var usePyBridge by remember { mutableStateOf(true) }
    var analysis by remember { mutableStateOf<MediaAnalysis?>(null) }
    var selectedFormat by remember { mutableStateOf<FormatChoice?>(null) }

    var manifestUrl by remember { mutableStateOf(AssetsAndPackageManager.getManifestUrl(context)) }
    var packages by remember { mutableStateOf<List<ManifestPackage>>(emptyList()) }
    var versionMap by remember { mutableStateOf>(emptyMap()) }
    var toolStatuses by remember { mutableStateOf<List<ToolStatus>>(emptyList()) }

    var gatewayUrl by remember { mutableStateOf("https://your-gateway.onrender.com") }
    var apiToken by remember { mutableStateOf("") }
    var bgActive by remember { mutableStateOf(false) }

    var working by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Initializing...") }
    var logs by remember { mutableStateOf(listOf<String>()) }

    fun appendLog(msg: String) {
        logs = (logs + msg).takeLast(14)
    }

    fun refreshTools() {
        toolStatuses = AssetsAndPackageManager.getCoreToolsStatus(context)
        versionMap = packages.associate { it.id to AssetsAndPackageManager.getInstalledVersion(context, it.id) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            AssetsAndPackageManager.extractAssetsOnStartup(context) { appendLog(it) }
        }
        refreshTools()
        statusMsg = "Ready • PyServer 2.0 + Python Bridge"
    }

    val bgDark = Color(0xFF070A10)
    val cardDark = Color(0xFF101724)
    val borderNeon = Color(0xFF1E293B)
    val cyanAccent = Color(0xFF00F0FF)
    val greenOk = Color(0xFF10B981)
    val amberWarn = Color(0xFFF59E0B)

    Scaffold(
        containerColor = bgDark,
        bottomBar = {
            NavigationBar(containerColor = cardDark) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    label = { Text("Downloader") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1; refreshTools() },
                    icon = { Icon(Icons.Default.Extension, contentDescription = null) },
                    label = { Text("Packages") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Key, contentDescription = null) },
                    label = { Text("Token & BG") }
                )
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardDark),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, borderNeon, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("PyServer 2.0", color = cyanAccent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(statusMsg, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            when (tab) {
                0 -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, borderNeon, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = mediaUrl,
                                    onValueChange = { mediaUrl = it },
                                    label = { Text("Paste Video / Media URL") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Python Bridge Analyze", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = usePyBridge, onCheckedChange = { usePyBridge = it })
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Use aria2c Accelerator", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = useAria2, onCheckedChange = { useAria2 = it })
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = { clipboard.getText()?.text?.let { mediaUrl = it } }, modifier = Modifier.weight(1f)) {
                                        Text("Paste", color = Color.White)
                                    }
                                    Button(
                                        onClick = {
                                            working = true
                                            statusMsg = "Analyzing..."
                                            scope.launch {
                                                val res = withContext(Dispatchers.IO) {
                                                    MediaDownloaderEngine.analyzeUrl(context, mediaUrl, usePyBridge)
                                                }
                                                working = false
                                                res.onSuccess {
                                                    analysis = it
                                                    selectedFormat = it.formats.firstOrNull()
                                                    statusMsg = "Found ${it.formats.size} formats"
                                                }.onFailure {
                                                    statusMsg = "Error: ${it.message}"
                                                }
                                            }
                                        },
                                        enabled = !working,
                                        colors = ButtonDefaults.buttonColors(containerColor = cyanAccent, contentColor = Color.Black),
                                        modifier = Modifier.weight(2f)
                                    ) {
                                        Text(if (working) "Working..." else "Analyze URL")
                                    }
                                }
                            }
                        }
                    }

                    analysis?.let { info ->
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = cardDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(info.title, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Engine: ${info.extractor} • Duration: ${info.duration}", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        items(info.formats) { fmt ->
                            val picked = selectedFormat == fmt
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (picked) Color(0xFF17253B) else cardDark),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().border(1.dp, if (picked) cyanAccent else borderNeon, RoundedCornerShape(12.dp)).clickable { selectedFormat = fmt }
                            ) {
                                Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(fmt.titleLabel, color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text(fmt.metaInfo, color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                                    }
                                    RadioButton(selected = picked, onClick = { selectedFormat = fmt })
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    val fmt = selectedFormat ?: return@Button
                                    working = true
                                    statusMsg = "Downloading ${fmt.titleLabel}..."
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            MediaDownloaderEngine.downloadMedia(context, mediaUrl, fmt, useAria2) { appendLog(it) }
                                        }
                                        working = false
                                        statusMsg = res.getOrElse { "Failed: ${it.message}" }
                                    }
                                },
                                enabled = !working && selectedFormat != null,
                                colors = ButtonDefaults.buttonColors(containerColor = cyanAccent, contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Download Selected Format")
                            }
                        }
                    }
                }

                1 -> {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = cardDark), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, borderNeon, RoundedCornerShape(16.dp))) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Tools & Python Status", color = cyanAccent, fontWeight = FontWeight.Bold)
                                toolStatuses.forEach { st ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(st.name, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                        Text(if (st.installed) "Ready" else "Missing", color = if (st.installed) greenOk else amberWarn, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = cardDark), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, borderNeon, RoundedCornerShape(16.dp))) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = manifestUrl, onValueChange = { manifestUrl = it }, label = { Text("GitHub manifest.json URL") }, modifier = Modifier.fillMaxWidth())
                                Button(
                                    onClick = {
                                        working = true
                                        AssetsAndPackageManager.setManifestUrl(context, manifestUrl)
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) { AssetsAndPackageManager.fetchManifest(context, manifestUrl) }
                                            working = false
                                            res.onSuccess { list -> packages = list; refreshTools(); statusMsg = "Loaded ${list.size} packages" }
                                                .onFailure { statusMsg = "Error: ${it.message}" }
                                        }
                                    },
                                    enabled = !working,
                                    colors = ButtonDefaults.buttonColors(containerColor = cyanAccent, contentColor = Color.Black),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Fetch Manifest & Check Updates")
                                }
                            }
                        }
                    }
                    items(packages) { pkg ->
                        val localVer = versionMap[pkg.id]
                        val action = when {
                            localVer == null -> "Install"
                            localVer != pkg.version -> "Update"
                            else -> "Reinstall"
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = cardDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().border(1.dp, borderNeon, RoundedCornerShape(14.dp))) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pkg.name, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Latest: v${pkg.version} • Installed: ${localVer?.let { "v$it" } ?: "None"}", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                                }
                                Button(
                                    onClick = {
                                        working = true
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) { AssetsAndPackageManager.installPackage(context, pkg) { statusMsg = it } }
                                            working = false
                                            refreshTools()
                                            statusMsg = res.getOrElse { "Failed: ${it.message}" }
                                        }
                                    },
                                    enabled = !working,
                                    colors = ButtonDefaults.buttonColors(containerColor = cyanAccent, contentColor = Color.Black)
                                ) {
                                    Text(action)
                                }
                            }
                        }
                    }
                }

                else -> {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = cardDark), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, borderNeon, RoundedCornerShape(16.dp))) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Background Service", color = Color.White, fontWeight = FontWeight.Bold)
                                    Switch(
                                        checked = bgActive,
                                        onCheckedChange = { enabled ->
                                            bgActive = enabled
                                            val intent = Intent(context, BackgroundEngineService::class.java).apply {
                                                putExtra("gateway_url", gatewayUrl.trim())
                                                putExtra("token", apiToken.trim())
                                            }
                                            if (enabled) ContextCompat.startForegroundService(context, intent)
                                            else context.stopService(intent)
                                        }
                                    )
                                }
                                OutlinedTextField(value = gatewayUrl, onValueChange = { gatewayUrl = it }, label = { Text("Render Gateway URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = apiToken, onValueChange = { apiToken = it }, label = { Text("API Token (/tokenrender)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedButton(
                                    onClick = {
                                        val base = gatewayUrl.trim().trimEnd('/')
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/tokenrender")))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Open /tokenrender", color = cyanAccent)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F18)), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().border(1.dp, borderNeon, RoundedCornerShape(14.dp))) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Engine Console", color = cyanAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        logs.forEach { line ->
                            Text(line, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
