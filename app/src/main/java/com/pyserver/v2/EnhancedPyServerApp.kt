package com.pyserver.v2

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Ink = Color(0xFF070B13)
private val Panel = Color(0xFF111A2B)
private val PanelRaised = Color(0xFF17243A)
private val Cyan = Color(0xFFBD36FF)
private val Violet = Color(0xFF32D5FF)
private val TextMuted = Color(0xFFAAB7CC)
private val Good = Color(0xFF67D9A2)

@Composable
fun EnhancedPyServerApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("pyserver2_settings", Context.MODE_PRIVATE) }
    var entry by rememberSaveable { mutableStateOf("splash") }
    fun finishPermissionSetup() {
        preferences.edit().putBoolean("permission_setup_complete", true).apply()
        entry = if (preferences.getBoolean("onboarding_complete", false)) "app" else "onboarding"
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        finishPermissionSetup()
    }
    LaunchedEffect(Unit) {
        delay(850)
        entry = when {
            !preferences.getBoolean("permission_setup_complete", false) -> "permissions"
            !preferences.getBoolean("onboarding_complete", false) -> "onboarding"
            else -> "app"
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Violet, background = Ink, surface = Panel, onPrimary = Ink, onSecondary = Color.White, onBackground = Color.White, onSurface = Color.White)) {
        when (entry) {
            "splash" -> SplashPage()
            "permissions" -> PermissionSetupPage(
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                onEnableNotifications = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else finishPermissionSetup()
                },
                onContinueWithoutNotifications = ::finishPermissionSetup,
                onBackgroundSettings = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
                }
            )
            "onboarding" -> OnboardingPage(onDone = {
                preferences.edit().putBoolean("onboarding_complete", true).apply()
                entry = "app"
            })
            else -> MainNavigation(preferences)
        }
    }
}

@Composable
private fun SplashPage() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0C1323), Ink))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(92.dp).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Cyan, Violet))), contentAlignment = Alignment.Center) {
                Text("⇩", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black, color = Ink)
            }
            Text("PyServer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text("MEDIA • DOWNLOADS • TOOLS", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            LinearProgressIndicator(Modifier.width(190.dp).clip(CircleShape), color = Cyan, trackColor = PanelRaised)
        }
    }
}

@Composable
private fun PermissionSetupPage(
    notificationsEnabled: Boolean,
    onEnableNotifications: () -> Unit,
    onContinueWithoutNotifications: () -> Unit,
    onBackgroundSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("SET UP DOWNLOADS", color = Cyan, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text("A few Android settings", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("PyServer uses Android-managed media folders. No broad file access is needed for media created by this app.", color = TextMuted)
        SettingsGroup("Storage", "Shared Download folder") {
            Text("Files are saved under Download/PyServer/Video, Audio and Thumbnails. Android 10+ manages access to app-created MediaStore files without a storage permission prompt.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        SettingsGroup("Transfer notifications", "Progress and quick controls") {
            Text(if (notificationsEnabled) "Notifications are enabled." else "Allow notifications to see background download progress and Pause/Resume controls in the notification shade.", color = if (notificationsEnabled) Good else Color.White, style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Background activity", "Optional device battery setting") {
            Text("Queued downloads use Android WorkManager and a foreground notification. Battery saver or device policies may defer work. Android does not let an app promise that an in-progress task survives uninstall.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onBackgroundSettings, modifier = Modifier.fillMaxWidth()) { Text("Open app settings", color = Cyan) }
        }
        Button(onClick = onEnableNotifications, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) {
            Text(if (notificationsEnabled || Build.VERSION.SDK_INT < 33) "Continue" else "Enable notifications & continue", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onContinueWithoutNotifications, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Continue without notification permission", color = TextMuted) }
    }
}

@Composable
private fun OnboardingPage(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val slides = listOf(
        Triple("Paste a link", "Analyze supported media pages and direct files, then review available formats.", "↗"),
        Triple("Preview before saving", "Tap a thumbnail to preview supported streams. Downloads show live transfer progress.", "▶"),
        Triple("Everything in one place", "Browse your download history, adjust network preferences and check GitHub Releases.", "▦")
    )
    val slide = slides[page]
    Box(Modifier.fillMaxSize().background(Ink).padding(20.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().widthIn(max = 520.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
                Text(slide.third, color = Cyan, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
            Text("Idownld Downloader", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
            Text(slide.first, style = MaterialTheme.typography.displaySmall, color = Cyan, fontWeight = FontWeight.Bold)
            Text(slide.second, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(slides.size) { index -> Box(Modifier.size(if (index == page) 26.dp else 8.dp, 8.dp).clip(CircleShape).background(if (index == page) Cyan else PanelRaised)) }
            }
            Button(onClick = { if (page == slides.lastIndex) onDone() else page++ }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) {
                Text(if (page == slides.lastIndex) "Get started" else "Continue", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Skip", color = TextMuted) }
        }
    }
}

@Composable
private fun MainNavigation(preferences: android.content.SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf("home") }
    var flow by rememberSaveable { mutableStateOf("home") }
    var mediaUrl by rememberSaveable { mutableStateOf("") }
    var analysis by remember { mutableStateOf<MediaAnalysis?>(null) }
    var selectedFormat by remember { mutableStateOf<FormatChoice?>(null) }
    var playlistSelection by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var analysisStage by rememberSaveable { mutableIntStateOf(-1) }
    var analysisError by rememberSaveable { mutableStateOf("") }
    var audioPage by rememberSaveable { mutableIntStateOf(0) }
    var playingUrl by rememberSaveable { mutableStateOf("") }
    var playingTitle by rememberSaveable { mutableStateOf("") }
    var playerReturnFlow by rememberSaveable { mutableStateOf("formats") }
    var imageUri by rememberSaveable { mutableStateOf("") }
    var imageTitle by rememberSaveable { mutableStateOf("") }
    var imageReturnFlow by rememberSaveable { mutableStateOf("home") }
    var downloadMessage by rememberSaveable { mutableStateOf("") }
    var queueTasks by remember { mutableStateOf(emptyList<DownloadTaskSnapshot>()) }
    var thumbnailMessage by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<DownloadHistoryItem>()) }
    var wifiOnly by remember { mutableStateOf(preferences.getBoolean("wifi_only", false)) }
    var showThumbnails by remember { mutableStateOf(preferences.getBoolean("show_thumbnails", true)) }
    var keepScreenOn by remember { mutableStateOf(preferences.getBoolean("keep_screen_on", true)) }
    var maxHeight by remember { mutableIntStateOf(preferences.getInt("max_resolution", 1080)) }
    var updateBusy by rememberSaveable { mutableStateOf(false) }
    var updateMessage by rememberSaveable { mutableStateOf("Release check not run yet") }
    var release by remember { mutableStateOf<LatestGithubRelease?>(null) }
    var nativeReport by remember { mutableStateOf<NativeRuntimeReport?>(null) }
    var gatewayUrl by rememberSaveable { mutableStateOf("") }
    var gatewayToken by remember { mutableStateOf("") }
    var gatewayStatus by rememberSaveable { mutableStateOf("Token not verified") }
    var gatewayActive by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }

    fun refreshHistory() {
        scope.launch { history = withContext(Dispatchers.IO) { MediaStoreDownloads.queryHistory(context) } }
    }
    fun refreshQueue() {
        scope.launch { queueTasks = runCatching { withContext(Dispatchers.IO) { DownloadQueue.query(context) } }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(destination) { if (destination == "history" || destination == "downloads") refreshHistory() }
    LaunchedEffect(destination) {
        if (destination == "downloads") {
            while (true) {
                queueTasks = runCatching { withContext(Dispatchers.IO) { DownloadQueue.query(context) } }.getOrDefault(emptyList())
                delay(1200)
            }
        } else if (destination == "players") refreshHistory()
    }
    LaunchedEffect(flow, analysis) { if (flow == "formats" && analysis == null) flow = "home" }
    val activeQueueTransfer = destination == "downloads" && queueTasks.any { it.state == androidx.work.WorkInfo.State.RUNNING && !it.isPaused }
    DisposableEffect(activeQueueTransfer && keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (activeQueueTransfer && keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun beginAnalysis() {
        if (mediaUrl.isBlank()) { analysisError = "Pehle media URL paste karein."; return }
        flow = "analysis"
        analysisStage = 0
        analysisError = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                MediaDownloaderEngine.analyzeUrl(context, mediaUrl, maxHeight) { stage ->
                    Handler(Looper.getMainLooper()).post { analysisStage = stage }
                }
            }
            result.onSuccess { info ->
                analysis = info
                playlistSelection = info.entries.map { it.playlistIndex }.toSet()
                selectedFormat = info.formats.firstOrNull { it.formatId.startsWith("best") }
                    ?: info.formats.firstOrNull { it.mediaType == "video" }
                    ?: info.formats.firstOrNull()
                audioPage = 0
                flow = "formats"
            }.onFailure { error ->
                analysisError = error.message ?: "URL analyze nahi hua."
                flow = "analysis"
            }
        }
    }

    fun beginDownload(choice: FormatChoice, fullCollection: Boolean = false) {
        if (wifiOnly && !isConnectedToWifi(context)) {
            downloadMessage = "Wi-Fi only enabled hai. Download shuru karne ke liye Wi-Fi connect karein."
            destination = "downloads"
            flow = "home"
            return
        }
        val info = analysis ?: return
        selectedFormat = choice
        downloadMessage = "Added to the background download queue. Android may defer work under battery/network restrictions."
        destination = "downloads"
        flow = "home"
        runCatching {
            val source = if (choice.formatId == "direct") choice.sourceUrl ?: mediaUrl else mediaUrl
            val selected = if (fullCollection) null else playlistSelection.toList()
            if (info.entries.isNotEmpty() && !fullCollection && selected.isNullOrEmpty()) error("At least one collection item select karein.")
            DownloadQueue.enqueue(context, info.title, source, choice, selected, isPlaylist = info.entries.isNotEmpty(), wifiOnly = wifiOnly, thumbnailUrl = info.thumbnailUrl.orEmpty())
        }.onSuccess { refreshQueue() }
            .onFailure {
                downloadMessage = "Download queue mein add nahi hua: ${it.message ?: it::class.java.simpleName}"
                CrashLogStore.recordHandled(context, "Download enqueue", it)
            }
    }

    fun startGateway() {
        val cleanUrl = gatewayUrl.trim().trimEnd('/')
        val token = gatewayToken.trim()
        gatewayStatus = "Protected gateway token verify ho raha hai…"
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GatewayApi.fetchNextJob(cleanUrl, token) } }
                .onSuccess { preflight ->
                    val intent = Intent(context, BackgroundEngineService::class.java).apply {
                        putExtra("gateway_url", cleanUrl)
                        putExtra("token", token)
                        putExtra("prefetched_response", preflight.payload)
                    }
                    runCatching { ContextCompat.startForegroundService(context, intent) }
                        .onSuccess { gatewayActive = true; gatewayStatus = "Token verified • background jobs active" }
                        .onFailure { gatewayActive = false; gatewayStatus = "Service start failed: ${it.message}" }
                }
                .onFailure { gatewayActive = false; gatewayStatus = it.message ?: "Token verification failed" }
            busy = false
        }
    }

    fun checkGithubRelease() {
        updateBusy = true
        updateMessage = "GitHub Releases check ho raha hai…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GitHubUpdateChecker.checkLatestRelease() } }
                .onSuccess { found ->
                    release = found
                    updateMessage = when {
                        found == null -> "Abhi koi public GitHub Release nahi mila. Releases page par APK publish hone ke baad update yahan dikhega."
                        isNewerRelease(found.tag, APP_VERSION_NAME) -> "Update available: ${found.title} (${found.tag}) • installed $APP_VERSION_NAME"
                        else -> "You are up to date • latest release ${found.tag} • installed $APP_VERSION_NAME"
                    }
                }
                .onFailure { updateMessage = "Update check failed: ${it.message ?: "network error"}" }
            updateBusy = false
        }
    }

    BackHandler(enabled = flow != "home") {
        flow = when (flow) { "player" -> playerReturnFlow; "image" -> imageReturnFlow; else -> "home" }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            if (flow == "home") {
                NavigationBar(containerColor = Panel, tonalElevation = 8.dp) {
                    listOf("home" to "Home", "downloads" to "Queue", "players" to "Players", "history" to "History", "settings" to "Settings").forEach { (key, label) ->
                        NavigationBarItem(
                            selected = destination == key,
                            onClick = { destination = key },
                            icon = { Icon(imageVector = when (key) { "home" -> Icons.Filled.Home; "downloads" -> Icons.Filled.Download; "players" -> Icons.Filled.PlayArrow; "history" -> Icons.Filled.History; else -> Icons.Filled.Settings }, contentDescription = label, tint = if (destination == key) Cyan else TextMuted) },
                            label = { Text(if (key == "history") "Library" else label, maxLines = 1, softWrap = false) }
                        )
                    }
                }
            }
        }
    ) { inset ->
        Box(Modifier.fillMaxSize().padding(inset).background(Ink), contentAlignment = Alignment.TopCenter) {
            when {
                flow == "analysis" -> AnalysisProgressPage(
                    url = mediaUrl,
                    stage = analysisStage,
                    error = analysisError,
                    onBack = { flow = "home" },
                    onRetry = ::beginAnalysis
                )
                flow == "formats" && analysis != null -> FormatsPage(
                    info = analysis!!,
                    maxHeight = maxHeight,
                    showThumbnails = showThumbnails,
                    selected = selectedFormat,
                    onSelect = { selectedFormat = it },
                    onPlay = { source, title -> playerReturnFlow = "formats"; playingUrl = source; playingTitle = title; flow = "player" },
                    onBack = { flow = "home" },
                    onDownload = { full -> selectedFormat?.let { beginDownload(it, full) } },
                    onSaveThumbnail = {
                        val thumb = analysis?.thumbnailUrl
                        if (thumb.isNullOrBlank()) thumbnailMessage = "Is media ke liye thumbnail URL available nahi hai."
                        else scope.launch {
                            thumbnailMessage = "Saving thumbnail…"
                            val saved = withContext(Dispatchers.IO) { MediaDownloaderEngine.downloadThumbnail(context, thumb, analysis?.title ?: "thumbnail") }
                            thumbnailMessage = saved.getOrElse { it.message ?: "Thumbnail save failed" }
                            refreshHistory()
                        }
                    },
                    thumbnailMessage = thumbnailMessage,
                    selectedPlaylistIndices = playlistSelection,
                    onPlaylistSelection = { playlistSelection = it },
                    audioPage = audioPage,
                    onAudioPage = { audioPage = it }
                )
                flow == "player" -> MediaPlayerPage(
                    url = playingUrl,
                    title = playingTitle,
                    onBack = { flow = playerReturnFlow }
                )
                flow == "image" -> ImageViewerPage(uri = imageUri, title = imageTitle, onBack = { flow = imageReturnFlow })
                destination == "home" -> HomePage(
                    url = mediaUrl,
                    onUrl = { mediaUrl = it },
                    onAnalyze = ::beginAnalysis,
                    busy = analysisStage in 0..4 && flow == "analysis",
                    error = analysisError,
                    onHistory = { destination = "history" },
                    onSettings = { destination = "settings" },
                    onDiagnostics = { context.startActivity(Intent(context, CrashDebugActivity::class.java)) },
                    onQueue = { destination = "downloads" },
                    onPlayers = { destination = "players" },
                    onLibrary = { destination = "history" }
                )
                destination == "downloads" -> DownloadsPage(
                    tasks = queueTasks,
                    message = downloadMessage,
                    showThumbnails = showThumbnails,
                    recent = history.filter { it.mediaType == "video" || it.mediaType == "audio" }.take(4),
                    onOpenMedia = { item ->
                        if (item.mediaType == "thumbnail") { imageUri = item.uri.toString(); imageTitle = item.displayName; imageReturnFlow = "home"; flow = "image" }
                        else { playerReturnFlow = "home"; playingUrl = item.uri.toString(); playingTitle = item.displayName; flow = "player" }
                    },
                    onHistory = { destination = "history" },
                    onRefresh = { refreshHistory(); refreshQueue() },
                    onPause = { id -> scope.launch(Dispatchers.IO) { DownloadQueue.pause(context, id); refreshQueue() } },
                    onResume = { id -> scope.launch(Dispatchers.IO) { DownloadQueue.resume(context, id); refreshQueue() } },
                    onCancel = { id -> scope.launch(Dispatchers.IO) { DownloadQueue.cancel(context, id); refreshQueue() } }
                )
                destination == "players" -> PlayersPage(
                    items = history.filter { it.mediaType == "video" || it.mediaType == "audio" },
                    showThumbnails = showThumbnails,
                    onRefresh = ::refreshHistory,
                    onOpenMedia = { item ->
                        if (item.mediaType == "thumbnail") { imageUri = item.uri.toString(); imageTitle = item.displayName; imageReturnFlow = "home"; flow = "image" }
                        else { playerReturnFlow = "home"; playingUrl = item.uri.toString(); playingTitle = item.displayName; flow = "player" }
                    }
                )
                destination == "history" -> HistoryPage(
                    items = history,
                    showThumbnails = showThumbnails,
                    onRefresh = ::refreshHistory,
                    onOpenMedia = { item ->
                        if (item.mediaType == "thumbnail") { imageUri = item.uri.toString(); imageTitle = item.displayName; imageReturnFlow = "home"; flow = "image" }
                        else { playerReturnFlow = "home"; playingUrl = item.uri.toString(); playingTitle = item.displayName; flow = "player" }
                    }
                )
                else -> SettingsPage(
                    preferences = preferences,
                    wifiOnly = wifiOnly,
                    showThumbnails = showThumbnails,
                    keepScreenOn = keepScreenOn,
                    maxHeight = maxHeight,
                    onWifiOnly = { wifiOnly = it; preferences.edit().putBoolean("wifi_only", it).apply() },
                    onShowThumbnails = { showThumbnails = it; preferences.edit().putBoolean("show_thumbnails", it).apply() },
                    onKeepScreenOn = { keepScreenOn = it; preferences.edit().putBoolean("keep_screen_on", it).apply() },
                    onMaxHeight = { maxHeight = it; preferences.edit().putInt("max_resolution", it).apply() },
                    updateBusy = updateBusy,
                    updateMessage = updateMessage,
                    release = release,
                    onCheckUpdates = ::checkGithubRelease,
                    onOpenRelease = { url -> openUrl(context, url) },
                    gatewayUrl = gatewayUrl,
                    gatewayToken = gatewayToken,
                    gatewayStatus = gatewayStatus,
                    gatewayActive = gatewayActive,
                    gatewayBusy = busy,
                    onGatewayUrl = { gatewayUrl = it; gatewayStatus = "Token not verified" },
                    onGatewayToken = { gatewayToken = it; gatewayStatus = "Token not verified" },
                    onStartGateway = ::startGateway,
                    onStopGateway = { context.stopService(Intent(context, BackgroundEngineService::class.java)); gatewayActive = false; gatewayStatus = "Background service stopped" },
                    nativeReport = nativeReport,
                    onProbeNative = {
                        busy = true
                        scope.launch { nativeReport = withContext(Dispatchers.IO) { NativeMediaRuntime.probe() }; busy = false }
                    },
                    onOpenDiagnostics = { runCatching { context.startActivity(Intent(context, CrashDebugActivity::class.java)) } },
                    nativeBusy = busy
                )
            }
        }
    }
}

@Composable
private fun HomePage(
    url: String,
    onUrl: (String) -> Unit,
    onAnalyze: () -> Unit,
    busy: Boolean,
    error: String,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onQueue: () -> Unit,
    onPlayers: () -> Unit,
    onLibrary: () -> Unit
) {
    Column(Modifier.fillMaxSize().widthIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Idownld", color = Cyan, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Ultimate media downloader", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Box(Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Cyan, Violet))), contentAlignment = Alignment.Center) { Text("⇩", color = Ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Violet.copy(alpha = 0.55f), RoundedCornerShape(24.dp))) {
            Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF24054B), Color(0xFF101B37)))).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Your ultimate media downloader", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Fast • Secure • Organized", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text("▶", color = Violet, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(26.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text("Analyze a link", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Video, audio, image or playlist URL", color = TextMuted)
                OutlinedTextField(
                    value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("https://…") }, shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, focusedLabelColor = Cyan, cursorColor = Cyan)
                )
                Button(onClick = onAnalyze, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink) else Text("Analyze media", fontWeight = FontWeight.Bold)
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF9B9B), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction("Download history", "◷", onHistory, Modifier.weight(1f))
            QuickAction("Preferences", "⚙", onSettings, Modifier.weight(1f))
        }
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("Crash log & diagnostics", color = Cyan, fontWeight = FontWeight.SemiBold)
        }
        Text("Quick access", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("Video", "▶", onPlayers, Modifier.weight(1f))
            QuickAction("Audio", "♫", onPlayers, Modifier.weight(1f))
            QuickAction("Images", "▧", onLibrary, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("All media", "▦", onLibrary, Modifier.weight(1f))
            QuickAction("Queue", "⇩", onQueue, Modifier.weight(1f))
            QuickAction("Settings", "⚙", onSettings, Modifier.weight(1f))
        }
        Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Built for simple downloads", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Review formats, preview supported streams and track transfer progress. Availability depends on each site's access and extractor support.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun QuickAction(title: String, glyph: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(glyph, color = Violet, style = MaterialTheme.typography.headlineSmall)
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AnalysisProgressPage(url: String, stage: Int, error: String, onBack: () -> Unit, onRetry: () -> Unit) {
    val steps = listOf("Validate URL", "Check direct media", "Run site extractor", "Prepare formats")
    Column(Modifier.fillMaxSize().widthIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        TextButton(onClick = onBack) { Text("‹  Back", color = Cyan) }
        Text("Analyzing link", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(url, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                steps.forEachIndexed { index, label ->
                    val checked = stage > index || (stage == 3 && index == 3)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(if (checked) Good.copy(alpha = 0.16f) else if (stage == index) Cyan.copy(alpha = 0.15f) else PanelRaised), contentAlignment = Alignment.Center) {
                            if (checked) Text("✓", color = Good, fontWeight = FontWeight.Bold) else if (stage == index && error.isBlank()) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Cyan) else Text("${index + 1}", color = TextMuted)
                        }
                        Text(label, color = if (checked || stage == index) Color.White else TextMuted, fontWeight = if (stage == index) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                if (error.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Cyan, trackColor = PanelRaised)
            }
        }
        if (error.isNotBlank()) {
            Text(error, color = Color(0xFFFFA0A0))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) { Text("Try again", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun FormatsPage(
    info: MediaAnalysis,
    maxHeight: Int,
    showThumbnails: Boolean,
    selected: FormatChoice?,
    onSelect: (FormatChoice) -> Unit,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit,
    onDownload: (Boolean) -> Unit,
    onSaveThumbnail: () -> Unit,
    thumbnailMessage: String,
    selectedPlaylistIndices: Set<Int>,
    onPlaylistSelection: (Set<Int>) -> Unit,
    audioPage: Int,
    onAudioPage: (Int) -> Unit
) {
    val pagerScope = rememberCoroutineScope()
    val videoFormats = info.formats.filter { it.mediaType == "video" && (maxHeight <= 0 || it.height == 0 || it.height <= maxHeight) }
    val audioFormats = info.formats.filter { it.mediaType == "audio" }
    val pager = rememberPagerState(initialPage = audioPage.coerceIn(0, 1), pageCount = { 2 })
    LaunchedEffect(pager.currentPage) {
        onAudioPage(pager.currentPage)
        val pageFormats = if (pager.currentPage == 0) videoFormats else audioFormats
        if (pageFormats.isNotEmpty()) onSelect(pageFormats.first())
    }
    Column(Modifier.fillMaxSize().widthIn(max = 680.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Cyan) }
            Spacer(Modifier.weight(1f))
            Text("FORMATS", color = TextMuted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Text(info.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text("${info.extractor} • ${info.duration}", color = Cyan, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        Spacer(Modifier.height(8.dp))
        val preview = selected?.takeIf { it.mediaType == "video" && !it.sourceUrl.isNullOrBlank() }
            ?: videoFormats.firstOrNull { !it.sourceUrl.isNullOrBlank() }
        Box(Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(20.dp)).background(PanelRaised).clickable(enabled = preview != null) {
            preview?.sourceUrl?.let { onPlay(it, info.title) }
        }) {
            if (showThumbnails && !info.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(model = info.thumbnailUrl, contentDescription = "Video thumbnail", modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("▶", color = Cyan, style = MaterialTheme.typography.displayMedium) }
            }
            if (preview != null) {
                Box(Modifier.align(Alignment.Center).clip(CircleShape).background(Ink.copy(alpha = 0.82f)).padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("▶  Tap thumbnail to preview", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("Preview stream unavailable", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!info.thumbnailUrl.isNullOrBlank()) {
            OutlinedButton(onClick = onSaveThumbnail, modifier = Modifier.fillMaxWidth()) { Text("Save thumbnail to Download/PyServer/Thumbnails", color = Cyan) }
            if (thumbnailMessage.isNotBlank()) Text(thumbnailMessage, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (info.entries.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Collection • ${selectedPlaylistIndices.size}/${info.entries.size} selected", color = Color.White, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            onPlaylistSelection(if (selectedPlaylistIndices.size == info.entries.size) emptySet() else info.entries.map { it.playlistIndex }.toSet())
                        }) { Text(if (selectedPlaylistIndices.size == info.entries.size) "Clear" else "All shown", color = Cyan) }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 132.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(info.entries, key = { "playlist-${it.playlistIndex}-${it.id}" }) { entry ->
                            Row(Modifier.fillMaxWidth().clickable {
                                onPlaylistSelection(if (entry.playlistIndex in selectedPlaylistIndices) selectedPlaylistIndices - entry.playlistIndex else selectedPlaylistIndices + entry.playlistIndex)
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = entry.playlistIndex in selectedPlaylistIndices, onCheckedChange = { checked ->
                                    onPlaylistSelection(if (checked) selectedPlaylistIndices + entry.playlistIndex else selectedPlaylistIndices - entry.playlistIndex)
                                })
                                Text("${entry.playlistIndex}. ${entry.title}", color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        TabRow(selectedTabIndex = pager.currentPage, containerColor = Ink, contentColor = Cyan) {
            Tab(selected = pager.currentPage == 0, onClick = { onAudioPage(0); pagerScope.launch { pager.animateScrollToPage(0) } }, text = { Text("Video • ${videoFormats.size}") })
            Tab(selected = pager.currentPage == 1, onClick = { onAudioPage(1); pagerScope.launch { pager.animateScrollToPage(1) } }, text = { Text("Audio • ${audioFormats.size}") })
        }
        Text("Swipe sideways to switch video and audio formats", color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 5.dp))
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            val formats = if (page == 0) videoFormats else audioFormats
            if (formats.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No ${if (page == 0) "video" else "audio"} formats listed", color = TextMuted) }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(formats, key = { "${it.mediaType}-${it.formatId}" }) { format ->
                        val active = selected?.let { it.formatId == format.formatId && it.mediaType == format.mediaType } == true
                        Card(modifier = Modifier.fillMaxWidth().clickable { onSelect(format) }.border(1.dp, if (active) Cyan else Color.Transparent, RoundedCornerShape(16.dp)), colors = CardDefaults.cardColors(containerColor = if (active) PanelRaised else Panel), shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = if (format.mediaType == "audio") Icons.Filled.MusicNote else Icons.Filled.PlayArrow, contentDescription = if (format.mediaType == "audio") "Audio format" else "Video format", tint = if (format.mediaType == "audio") Violet else Cyan, modifier = Modifier.padding(end = 12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(format.titleLabel, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(format.metaInfo, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(format.estimatedSizeBytes?.let(::formatByteCount) ?: "Estimated size unavailable", color = Cyan, style = MaterialTheme.typography.labelSmall)
                                }
                                RadioButton(selected = active, onClick = { onSelect(format) })
                            }
                        }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 390.dp
            if (info.entries.isNotEmpty()) {
                if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDownload(false) }, enabled = selected != null && selectedPlaylistIndices.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) { Text("Download selected", fontWeight = FontWeight.Bold, maxLines = 1) }
                    OutlinedButton(onClick = { onDownload(true) }, enabled = selected != null, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Download full collection", color = Cyan, fontWeight = FontWeight.Bold, maxLines = 1) }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDownload(false) }, enabled = selected != null && selectedPlaylistIndices.isNotEmpty(), modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) { Text("Download selected", fontWeight = FontWeight.Bold, maxLines = 1) }
                    OutlinedButton(onClick = { onDownload(true) }, enabled = selected != null, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Full collection", color = Cyan, fontWeight = FontWeight.Bold, maxLines = 1) }
                }
            } else Button(onClick = { onDownload(false) }, enabled = selected != null, modifier = Modifier.fillMaxWidth().height(54.dp).padding(bottom = 4.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) {
                Text("Download selected format", fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MediaPlayerPage(url: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var playbackError by remember(url) { mutableStateOf("") }
    val playerResult = remember(url) {
        runCatching {
            require(url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("content://", true)) { "Unsupported or empty playback URL" }
            ExoPlayer.Builder(context.applicationContext).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playbackError = error.localizedMessage ?: "This stream or codec is not supported."
                        CrashLogStore.recordHandled(context, "Media playback", error)
                    }
                })
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
        }.onFailure { error ->
            playbackError = error.localizedMessage ?: "Could not open this media stream."
            CrashLogStore.recordHandled(context, "Media player setup", error)
        }
    }
    val player = playerResult.getOrNull()
    DisposableEffect(player) { onDispose { player?.release() } }
    Column(Modifier.fillMaxSize().widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back to formats", color = Cyan) }
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (player != null) AndroidView(
            factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))
        ) else Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
            Text(playbackError.ifBlank { "Playback could not start." }, color = Color(0xFFFF9B9B), modifier = Modifier.padding(16.dp))
        }
        if (playbackError.isNotBlank()) Text(playbackError, color = Color(0xFFFF9B9B), style = MaterialTheme.typography.bodySmall)
        Text("Playback depends on source availability, supported codec and site access. This preview does not bypass login, DRM or access restrictions.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DownloadsPage(
    tasks: List<DownloadTaskSnapshot>,
    message: String,
    showThumbnails: Boolean,
    recent: List<DownloadHistoryItem>,
    onOpenMedia: (DownloadHistoryItem) -> Unit,
    onHistory: () -> Unit,
    onRefresh: () -> Unit,
    onPause: (java.util.UUID) -> Unit,
    onResume: (java.util.UUID) -> Unit,
    onCancel: (java.util.UUID) -> Unit
) {
    val context = LocalContext.current
    var selectedQueueTab by rememberSaveable { mutableIntStateOf(0) }
    var showFullFailure by rememberSaveable { mutableStateOf(false) }
    val activeTasks = tasks.filter { !it.state.isFinished }
    val completedTasks = tasks.filter { it.state.isFinished }
    val visibleTasks = when (selectedQueueTab) { 1 -> activeTasks; 2 -> completedTasks; else -> tasks }
    Column(Modifier.fillMaxSize().widthIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Download manager", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Background tasks • pause, resume or cancel", color = TextMuted) }
            TextButton(onClick = onRefresh) { Text("Refresh", color = Cyan) }
        }
        TabRow(selectedTabIndex = selectedQueueTab, containerColor = Panel, contentColor = Cyan) {
            listOf("Queue ${tasks.size}", "Active ${activeTasks.size}", "Completed ${completedTasks.size}").forEachIndexed { index, tab ->
                Tab(selected = selectedQueueTab == index, onClick = { selectedQueueTab = index }, text = { Text(tab, maxLines = 1, softWrap = false) })
            }
        }
        if (visibleTasks.isEmpty()) EmptyStateCard("No ${listOf("queued", "active", "completed")[selectedQueueTab]} downloads", "Queued transfers show live progress and allow pause, resume or cancel.")
        else visibleTasks.forEach { task ->
            val done = task.state.isFinished
            val percent = if (task.totalBytes > 0L) (task.downloadedBytes * 100L / task.totalBytes).coerceIn(0L, 100L).toInt() else 0
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (showThumbnails && task.thumbnailUrl.isNotBlank()) AsyncImage(model = task.thumbnailUrl, contentDescription = "Queued download thumbnail", modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)))
                    Text(task.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (task.isPaused) "Paused" else task.state.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (task.state == androidx.work.WorkInfo.State.FAILED) Color(0xFFFF9B9B) else Cyan, style = MaterialTheme.typography.labelMedium)
                    if (task.failureMessage.isNotBlank()) {
                        Text(task.failureMessage, color = Color(0xFFFF9B9B), style = MaterialTheme.typography.bodySmall, maxLines = if (showFullFailure) 40 else 3, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showFullFailure = !showFullFailure }) {
                                Text(if (showFullFailure) "Hide details" else "Full details", color = Cyan)
                            }
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Idownld download error", task.failureMessage))
                                Toast.makeText(context, "Full error copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy error", color = Cyan) }
                        }
                    }
                    if (task.totalBytes > 0L) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape), color = Cyan, trackColor = PanelRaised)
                    else if (!done) LinearProgressIndicator(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape), color = Cyan, trackColor = PanelRaised)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (task.totalBytes > 0L) "$percent%" else "${formatByteCount(task.downloadedBytes)} transferred", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                        if (task.totalBytes > 0L) Text("${formatByteCount(task.downloadedBytes)} / ${formatByteCount(task.totalBytes)}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!done) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (task.isPaused) onResume(task.id) else onPause(task.id) }, modifier = Modifier.weight(1f)) { Text(if (task.isPaused) "Resume" else "Pause", color = Cyan) }
                        OutlinedButton(onClick = { onCancel(task.id) }, modifier = Modifier.weight(1f)) { Text("Cancel", color = Color(0xFFFF9B9B)) }
                    }
                }
            }
        }
        if (message.isNotBlank()) Text(message, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent files", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onHistory) { Text("See all", color = Cyan) }
        }
        if (recent.isEmpty()) Text("No saved downloads yet.", color = TextMuted) else recent.forEach { HistoryItemCard(it, showThumbnails, onClick = { onOpenMedia(it) }) }
    }
}

@Composable
private fun PlayersPage(items: List<DownloadHistoryItem>, showThumbnails: Boolean, onRefresh: () -> Unit, onOpenMedia: (DownloadHistoryItem) -> Unit) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val videos = items.filter { it.mediaType == "video" }
    val audios = items.filter { it.mediaType == "audio" }
    val visible = if (selectedTab == 0) videos else audios
    Column(Modifier.fillMaxSize().widthIn(max = 680.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Media players", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Your saved videos and audio", color = TextMuted, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onRefresh) { Text("Refresh", color = Cyan) }
        }
        Spacer(Modifier.height(10.dp))
        TabRow(selectedTabIndex = selectedTab, containerColor = Panel, contentColor = Cyan) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Videos ${videos.size}") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Audios ${audios.size}") })
        }
        if (items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyStateCard("No playable media yet", "Downloaded videos and audio saved in Download/PyServer appear here. Tap a file to open it in ExoPlayer.") }
        else if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyStateCard("No ${if (selectedTab == 0) "videos" else "audio"} yet", "Your saved ${if (selectedTab == 0) "videos" else "audio files"} appear here.") }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(visible, key = { it.uri.toString() }) { item -> HistoryItemCard(item, showThumbnails) { onOpenMedia(item) } }
        }
    }
}

@Composable
private fun HistoryPage(items: List<DownloadHistoryItem>, showThumbnails: Boolean, onRefresh: () -> Unit, onOpenMedia: (DownloadHistoryItem) -> Unit) {
    var selectedKind by rememberSaveable { mutableStateOf("All") }
    val videos = items.filter { it.mediaType == "video" }
    val audios = items.filter { it.mediaType == "audio" }
    val images = items.filter { it.mediaType == "thumbnail" }
    val shown = when (selectedKind) { "Videos" -> videos; "Audios" -> audios; "Images" -> images; else -> items }
    val kinds = listOf("All", "Videos", "Audios", "Images")
    Column(Modifier.fillMaxSize().widthIn(max = 680.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("All media", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Videos, audio and saved thumbnails", color = TextMuted, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onRefresh) { Text("Refresh", color = Cyan) }
        }
        Spacer(Modifier.height(10.dp))
        ScrollableTabRow(selectedTabIndex = kinds.indexOf(selectedKind), containerColor = Ink, contentColor = Cyan, edgePadding = 0.dp) {
            kinds.forEach { kind ->
                val count = when (kind) { "Videos" -> videos.size; "Audios" -> audios.size; "Images" -> images.size; else -> items.size }
                Tab(selected = selectedKind == kind, onClick = { selectedKind = kind }, text = { Text("$kind  $count", maxLines = 1) })
            }
        }
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyStateCard("Nothing here yet", "Your saved videos, audios and thumbnails will appear in this library.") }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(shown, key = { it.uri.toString() }) { item -> HistoryItemCard(item, showThumbnails) { onOpenMedia(item) } }
        }
    }
}

@Composable
private fun ImageViewerPage(uri: String, title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back to library", color = Cyan) }
            Spacer(Modifier.weight(1f))
            Text("IMAGE", color = TextMuted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        AsyncImage(model = Uri.parse(uri), contentDescription = title, modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(Panel), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        Text("Saved image • Idownld Library", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HistoryItemCard(item: DownloadHistoryItem, showThumbnails: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri, item.mediaType, showThumbnails) {
        if (showThumbnails && item.mediaType in setOf("video", "thumbnail")) {
            bitmap = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(item.uri, Size(480, 270), null) }.getOrNull() }
        }
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp, 70.dp).clip(RoundedCornerShape(12.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
                if (!showThumbnails) Text(when (item.mediaType) { "audio" -> "♫"; "thumbnail" -> "▧"; else -> "▶" }, color = Cyan, style = MaterialTheme.typography.headlineMedium)
                else if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = if (item.mediaType == "thumbnail") "Saved image" else "Video thumbnail", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Text(when (item.mediaType) { "audio" -> "♫"; "thumbnail" -> "▧"; else -> "▶" }, color = Cyan, style = MaterialTheme.typography.headlineMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${if (item.mediaType == "thumbnail") "IMAGE" else item.mediaType.uppercase()} • ${formatByteCount(item.sizeBytes)}", color = Cyan, style = MaterialTheme.typography.labelSmall)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.dateAddedSeconds * 1000)), color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
            Text(when (item.mediaType) { "video" -> "▶"; "thumbnail" -> "▧"; else -> "♫" }, color = Violet, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettingsPage(
    preferences: android.content.SharedPreferences,
    wifiOnly: Boolean,
    showThumbnails: Boolean,
    keepScreenOn: Boolean,
    maxHeight: Int,
    onWifiOnly: (Boolean) -> Unit,
    onShowThumbnails: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onMaxHeight: (Int) -> Unit,
    updateBusy: Boolean,
    updateMessage: String,
    release: LatestGithubRelease?,
    onCheckUpdates: () -> Unit,
    onOpenRelease: (String) -> Unit,
    gatewayUrl: String,
    gatewayToken: String,
    gatewayStatus: String,
    gatewayActive: Boolean,
    gatewayBusy: Boolean,
    onGatewayUrl: (String) -> Unit,
    onGatewayToken: (String) -> Unit,
    onStartGateway: () -> Unit,
    onStopGateway: () -> Unit,
    nativeReport: NativeRuntimeReport?,
    onProbeNative: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    nativeBusy: Boolean
) {
    val context = LocalContext.current
    var showResolutionMenu by remember { mutableStateOf(false) }
    val choices = listOf(0, 480, 720, 1080, 1440, 2160)
    Column(Modifier.fillMaxSize().widthIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Cyan, Violet))), contentAlignment = Alignment.Center) { Text("⇩", color = Ink, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black) }
                Column { Text("Idownld Downloader", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); Text("Made by Raja Nisarul", color = TextMuted); Text("Version $APP_VERSION_NAME • Android", color = Cyan, style = MaterialTheme.typography.labelSmall) }
            }
        }
        SettingsGroup("Network", "Transfer rules") {
            SettingSwitch("Wi-Fi only downloads", "Mobile data par transfer block karein", wifiOnly, onWifiOnly)
            Text("Downloader timeout: 30 seconds • site rate limits and connectivity can affect speed.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Video & audio", "Format and preview") {
            SettingSwitch("Show thumbnails", "Analysis aur download cards mein preview image", showThumbnails, onShowThumbnails)
            Box {
                OutlinedButton(onClick = { showResolutionMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Maximum resolution  •  ${if (maxHeight <= 0) "Any resolution" else "${maxHeight}p"}", color = Color.White) }
                DropdownMenu(expanded = showResolutionMenu, onDismissRequest = { showResolutionMenu = false }) {
                    choices.forEach { value -> DropdownMenuItem(text = { Text(if (value <= 0) "Any resolution" else "Up to ${value}p") }, onClick = { onMaxHeight(value); showResolutionMenu = false }) }
                }
            }
            Text("Separate-stream merging and format conversion are unavailable in this build. The picker filters to the selected height cap.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Storage & downloads", "Progress and history") {
            SettingSwitch("Keep screen awake while downloading", "Avoids screen sleep during an active transfer", keepScreenOn, onKeepScreenOn)
            Text("Shared files: Download/PyServer/Video • Audio • Thumbnails", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("GitHub updates", "Release-based update check") {
            Text(updateMessage, color = if (release != null) Good else TextMuted, style = MaterialTheme.typography.bodySmall)
            if (release?.notes?.isNotBlank() == true) Text(release.notes, color = TextMuted, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onCheckUpdates, enabled = !updateBusy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) {
                if (updateBusy) CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp) else Text("Check GitHub Releases", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { onOpenRelease(release?.apkUrl ?: release?.pageUrl ?: GITHUB_RELEASES_PAGE) }, modifier = Modifier.fillMaxWidth()) { Text(if (release?.apkUrl != null) "Open APK download" else "Open Releases page", color = Cyan) }
            Text("Android package updates open in the browser; review and install the APK yourself. This app does not silently install updates.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Background jobs", "Render gateway") {
            OutlinedTextField(value = gatewayUrl, onValueChange = onGatewayUrl, label = { Text("HTTPS gateway URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(value = gatewayToken, onValueChange = onGatewayToken, label = { Text("Gateway bearer token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Text(gatewayStatus, color = if (gatewayActive) Good else TextMuted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (gatewayActive) onStopGateway() else onStartGateway() }, enabled = !gatewayBusy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (gatewayActive) PanelRaised else Cyan, contentColor = if (gatewayActive) Color.White else Ink)) {
                Text(if (gatewayActive) "Stop background service" else "Verify token & start", fontWeight = FontWeight.Bold)
            }
            Text("Token is verified against the protected gateway before polling. Only use a gateway URL you trust.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Libraries & diagnostics", "Bundled runtime and crash report") {
            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Open crash log / diagnostics", color = Cyan) }
            Text("Recent unexpected app crashes are saved locally in the diagnostics screen. Logs can include sensitive link data.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Text(nativeReport?.summary ?: "Inspect FFmpeg / FFprobe JNI availability in this APK.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onProbeNative, enabled = !nativeBusy, modifier = Modifier.fillMaxWidth()) { Text(if (nativeBusy) "Checking…" else "Inspect bundled libraries", color = Cyan) }
            nativeReport?.let { report ->
                Text("FFmpeg JNI: ${if (report.available) "available" else "not confirmed"} • detected optional features: ${report.featureLibraries.joinToString().ifBlank { "none" }}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("QuickJS-NG and aria2 are bundled for supported ABIs. Site access, login, regional restrictions and upstream changes may still prevent extraction. x264/x265 and FDK-AAC are excluded pending licensing decisions.", color = Color(0xFFFFD580), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onOpenRelease(GITHUB_RELEASES_PAGE) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Repository & release history", color = Cyan) }
    }
}

@Composable
private fun SettingsGroup(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = Cyan, style = MaterialTheme.typography.labelSmall)
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold); Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EmptyStateCard(title: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("◌", color = Violet, style = MaterialTheme.typography.headlineLarge)
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(detail, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun isConnectedToWifi(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun isNewerRelease(tag: String, installed: String): Boolean {
    fun parts(version: String) = version.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val remote = parts(tag)
    val local = parts(installed)
    for (index in 0 until maxOf(remote.size, local.size)) {
        val comparison = (remote.getOrElse(index) { 0 }).compareTo(local.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return false
}
