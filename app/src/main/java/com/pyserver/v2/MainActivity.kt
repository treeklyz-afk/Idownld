package com.pyserver.v2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
        setContent { PyServerAnimatedApp() }
    }
}

@Composable
fun PyServerAnimatedApp() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val animCfg = remember { PythonRunner.loadAnimationConfig(context) }
    val scaleMin = animCfg.optDouble("scale_min", 0.96).toFloat()
    val scaleMax = animCfg.optDouble("scale_max", 1.04).toFloat()

    val infiniteTransition = rememberInfiniteTransition(label = "pyserver_pulse")
    val pulseScale = infiniteTransition.animateFloat(
        initialValue = scaleMin,
        targetValue = scaleMax,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "badge_scale"
    )

    val tabState = remember { mutableStateOf(0) }
    val mediaUrlState = remember { mutableStateOf("") }
    val useAria2State = remember { mutableStateOf(true) }
    val usePyBridgeState = remember { mutableStateOf(true) }
    val analysisState = remember { mutableStateOf<MediaAnalysis?>(null) }
    val selectedFormatState = remember { mutableStateOf<FormatChoice?>(null) }

    val manifestUrlState = remember { mutableStateOf(AssetsAndPackageManager.getManifestUrl(context)) }
    val packagesState = remember { mutableStateOf<List<ManifestPackage>>(emptyList()) }
    val versionMapState = remember { mutableStateOf>(emptyMap()) }
    val toolStatusesState = remember { mutableStateOf<List<ToolStatus>>(emptyList()) }

    val gatewayUrlState = remember { mutableStateOf("https://your-gateway.onrender.com") }
    val apiTokenState = remember { mutableStateOf("") }
    val bgActiveState = remember { mutableStateOf(false) }

    val workingState = remember { mutableStateOf(false) }
    val statusMsgState = remember { mutableStateOf("Booting animated engine...") }
    val logsState = remember { mutableStateOf(listOf<String>()) }

    fun appendLog(msg: String) {
        logsState.value = (logsState.value + msg).takeLast(12)
    }

    fun refreshTools() {
        toolStatusesState.value = AssetsAndPackageManager.getCoreToolsStatus(context)
        versionMapState.value = packagesState.value.associate { pkg ->
            pkg.id to AssetsAndPackageManager.getInstalledVersion(context, pkg.id)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            AssetsAndPackageManager.extractAssetsOnStartup(context) { line ->
                appendLog(line)
            }
        }
        refreshTools()
        statusMsgState.value = "Ready • GIF + JSON Animation + Engine"
    }

    val bgDark = Color(0xFF070A10)
    val cardDark = Color(0xFF101724)
    val borderNeon = Color(0xFF1E293B)
    val cyanAccent = Color(0xFF00F0FF)
    val greenOk = Color(0xFF10B981)
    val amberWarn = Color(0xFFF59E0B)

    val badgeColorState = animateColorAsState(
        targetValue = if (bgActiveState.value) greenOk else cyanAccent,
        label = "badge_color"
    )

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
                    icon = { Text("DL", fontWeight = FontWeight.Bold, color = if (tabState.value == 0) cyanAccent else Color.Gray) },
                    label = { Text("Downloader", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = tabState.value == 1,
                    onClick = { tabState.value = 1; refreshTools() },
                    icon = { Text("PKG", fontWeight = FontWeight.Bold, color = if (tabState.value == 1) cyanAccent else Color.Gray) },
                    label = { Text("Packages", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                NavigationBarItem(
                    selected = tabState.value == 2,
                    onClick = { tabState.value = 2 },
                    icon = { Text("BG", fontWeight = FontWeight.Bold, color = if (tabState.value == 2) cyanAccent else Color.Gray) },
                    label = { Text("Token & BG", maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardDark),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderNeon, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
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
                            Surface(
                                color = badgeColorState.value.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.scale(pulseScale.value)
                            ) {
                                Text(
                                    if (bgActiveState.value) "BG LIVE" else "ANIM READY",
                                    color = badgeColorState.value,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
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

            when (tabState.value) {
                0 -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, borderNeon, RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = mediaUrlState.value,
                                    onValueChange = { mediaUrlState.value = it },
                                    label = { Text("Paste Video / GIF / Media URL") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Python Bridge", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = usePyBridgeState.value, onCheckedChange = { usePyBridgeState.value = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("aria2c Accelerator", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = useAria2State.value, onCheckedChange = { useAria2State.value = it })
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = { clipboard.getText()?.text?.let { mediaUrlState.value = it } },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Paste", color = Color.White, maxLines = 1)
                                    }
                                    Button(
                                        onClick = {
                                            workingState.value = true
                                            statusMsgState.value = "Analyzing..."
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
                                                    statusMsgState.value = "Found ${info.formats.size} formats"
                                                }.onFailure { err ->
                                                    statusMsgState.value = "Error: ${err.message}"
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
                                        Text(if (workingState.value) "Working..." else "Analyze", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }

                    analysisState.value?.let { info ->
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = cardDark),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        info.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${info.extractor} • ${info.duration}",
                                        color = Color(0xFF94A3B8),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        items(info.formats) { fmt ->
                            val picked = selectedFormatState.value == fmt
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (picked) Color(0xFF17253B) else cardDark
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (picked) cyanAccent else borderNeon,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { selectedFormatState.value = fmt }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
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
                                        selected = picked,
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
                                    statusMsgState.value = "Downloading ${fmt.titleLabel}..."
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            MediaDownloaderEngine.downloadMedia(
                                                context,
                                                mediaUrlState.value,
                                                fmt,
                                                useAria2State.value
                                            ) { line -> appendLog(line) }
                                        }
                                        workingState.value = false
                                        statusMsgState.value = res.getOrElse { err -> "Failed: ${err.message}" }
                                    }
                                },
                                enabled = !workingState.value && selectedFormatState.value != null,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cyanAccent,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Download Selected Format")
                            }
                        }
                    }
                }

                1 -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, borderNeon, RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Tools & Python Status", color = cyanAccent, fontWeight = FontWeight.Bold)
                                toolStatusesState.value.forEach { st ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(st.name, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            if (st.installed) "Ready" else "Missing",
                                            color = if (st.installed) greenOk else amberWarn,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, borderNeon, RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = manifestUrlState.value,
                                    onValueChange = { manifestUrlState.value = it },
                                    label = { Text("GitHub manifest.json URL") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        workingState.value = true
                                        AssetsAndPackageManager.setManifestUrl(context, manifestUrlState.value)
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                AssetsAndPackageManager.fetchManifest(context, manifestUrlState.value)
                                            }
                                            workingState.value = false
                                            res.onSuccess { list ->
                                                packagesState.value = list
                                                refreshTools()
                                                statusMsgState.value = "Loaded ${list.size} packages"
                                            }.onFailure { err ->
                                                statusMsgState.value = "Error: ${err.message}"
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
                                    Text("Fetch Manifest & Check Updates")
                                }
                            }
                        }
                    }
                    items(packagesState.value) { pkg ->
                        val localVer = versionMapState.value[pkg.id]
                        val installedTag = if (localVer != null) "v$localVer" else "None"
                        val action = when {
                            localVer == null -> "Install"
                            localVer != pkg.version -> "Update"
                            else -> "Reinstall"
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, borderNeon, RoundedCornerShape(14.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        pkg.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "v${pkg.version} • Installed: $installedTag",
                                        color = Color(0xFF94A3B8),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Button(
                                    onClick = {
                                        workingState.value = true
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                AssetsAndPackageManager.installPackage(context, pkg) { prog ->
                                                    statusMsgState.value = prog
                                                }
                                            }
                                            workingState.value = false
                                            refreshTools()
                                            statusMsgState.value = res.getOrElse { err -> "Failed: ${err.message}" }
                                        }
                                    },
                                    enabled = !workingState.value,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = cyanAccent,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Text(action, maxLines = 1)
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
                                .border(1.dp, borderNeon, RoundedCornerShape(16.dp))
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
                                        val base = gatewayUrlState.value.trim().trimEnd('/')
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/tokenrender")))
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

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F18)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderNeon, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Engine Console",
                            color = cyanAccent,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
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
