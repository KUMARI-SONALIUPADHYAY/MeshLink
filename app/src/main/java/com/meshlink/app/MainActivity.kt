@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.meshlink.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Window
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meshlink.app.core.bluetooth.MeshPeer
import com.meshlink.app.core.mesh.MeshEngine
import com.meshlink.app.core.mesh.MeshUiState
import com.meshlink.app.core.safety.SafetyDecision
import com.meshlink.app.core.voice.VoicePlayer
import com.meshlink.app.data.database.EventEntity
import com.meshlink.app.data.database.MessageEntity
import com.meshlink.app.service.MeshForegroundService
import com.meshlink.app.ui.theme.MeshLinkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var engine: MeshEngine
    private val voicePlayer = VoicePlayer()
    private val playingVoicePath = mutableStateOf<String?>(null)
    private var pendingSos = false
    private var pendingLocation = false
    private var pendingSosMessage = ""
    private var pendingSosVoiceFile: File? = null
    private var lastVoiceFile: File? = null

    private val blePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val required = if (android.os.Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (required.all { result[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            MeshForegroundService.start(this)
        }
    }

    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.all { it } ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@registerForActivityResult
        if (pendingSos) { pendingSos = false; val message = pendingSosMessage; val voice = pendingSosVoiceFile; pendingSosMessage = ""; pendingSosVoiceFile = null; lifecycleScope.launch { engine.sendSos(voiceFile = voice, message = message) } }
        if (pendingLocation) { pendingLocation = false; lifecycleScope.launch { engine.sendLocation() } }
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine.startVoiceRecording()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.WHITE
        window.navigationBarColor = AndroidColor.WHITE
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        voicePlayer.onPlaybackChanged = { path -> runOnUiThread { playingVoicePath.value = path } }
        setContent {
            MeshLinkTheme {
                val state by engine.state.collectAsState()
                val messages by engine.messages.collectAsState(initial = emptyList())
                val events by engine.events.collectAsState(initial = emptyList())
                val safety by engine.safetyMessages.collectAsState(initial = emptyList())
                var onboarding by remember { mutableStateOf(!getPreferences(0).getBoolean("onboarded", false)) }

                MeshLinkScreen(
                    state = state,
                    messages = messages,
                    events = events,
                    safety = safety,
                    onMeshToggle = ::toggleMesh,
                    onText = { peer, text -> peer.nodeId.toUuidOrNull()?.let { lifecycleScope.launch { engine.sendText(it, text) } } },
                    onBroadcast = { lifecycleScope.launch { engine.broadcastText(it) } },
                    onAlert = { lifecycleScope.launch { engine.sendAlert(null, it) } },
                    onLocation = { pendingLocation = true; requestLocationPermission() },
                    onSos = { message, voice -> pendingSosMessage = message; pendingSosVoiceFile = voice; pendingSos = true; requestLocationPermission() },
                    onVoiceStart = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) engine.startVoiceRecording()
                        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSosVoiceStart = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) engine.startVoiceRecording()
                        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSosVoiceStop = {
                        lastVoiceFile = engine.stopVoiceRecording()
                        lastVoiceFile
                    },
                    onVoiceStop = { peer ->
                        lastVoiceFile = engine.stopVoiceRecording()
                        lastVoiceFile?.let { file ->
                            lifecycleScope.launch { engine.sendVoice(UUID.randomUUID(), file, peer?.nodeId?.toUuidOrNull()) }
                        }
                    },
                    onPlayVoice = { state.lastVoicePath?.let { path ->
                        if (playingVoicePath.value == path) voicePlayer.stop() else voicePlayer.play(this, path)
                    } },
                    onPlayVoicePath = { path ->
                        if (playingVoicePath.value == path) voicePlayer.stop() else voicePlayer.play(this, path)
                    },
                    onNameChange = engine::setDisplayName,
                    onAvatarChange = engine::setAvatarIndex,
                    onSafetyReview = { id, decision -> lifecycleScope.launch { engine.reviewSafety(id, decision) } },
                    onSosReview = { id, decision -> lifecycleScope.launch { engine.reviewSos(id, decision) } }
                )

                if (onboarding) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Welcome to MeshLink") },
                        text = { Text("Private, nearby communication over BLE. Peers are discovered automatically after permissions. Wi-Fi and mobile data are not required for the mesh path.") },
                        confirmButton = { Button(onClick = { getPreferences(0).edit().putBoolean("onboarded", true).apply(); onboarding = false }) { Text("Get started") } }
                    )
                }
            }
        }
    }

    private fun toggleMesh(enabled: Boolean) {
        if (!enabled) { MeshForegroundService.stop(this); return }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val permissions = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) MeshForegroundService.start(this)
            else {
                val request = missing + if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                blePermissions.launch(request.toTypedArray())
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) MeshForegroundService.start(this)
        else blePermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            if (pendingSos) { pendingSos = false; val message = pendingSosMessage; val voice = pendingSosVoiceFile; pendingSosMessage = ""; pendingSosVoiceFile = null; lifecycleScope.launch { engine.sendSos(voiceFile = voice, message = message) } }
            if (pendingLocation) { pendingLocation = false; lifecycleScope.launch { engine.sendLocation() } }
        } else locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

@Composable
private fun MeshLinkScreen(
    state: MeshUiState,
    messages: List<MessageEntity>,
    events: List<EventEntity>,
    safety: List<MessageEntity>,
    onMeshToggle: (Boolean) -> Unit,
    onText: (MeshPeer, String) -> Unit,
    onBroadcast: (String) -> Unit,
    onAlert: (String) -> Unit,
    onLocation: () -> Unit,
    onSos: (String, File?) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: (MeshPeer?) -> Unit,
    onSosVoiceStart: () -> Unit,
    onSosVoiceStop: () -> File?,
    onPlayVoice: () -> Unit,
    onPlayVoicePath: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onAvatarChange: (Int) -> Unit,
    onSafetyReview: (String, SafetyDecision) -> Unit,
    onSosReview: (String, String) -> Unit,
    playingVoicePath: String? = null
) {
    var selected by remember { mutableStateOf<MeshPeer?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(state.localName) { mutableStateOf(state.localName) }
    var confirmSos by remember { mutableStateOf(false) }
    var showAlert by remember { mutableStateOf(false) }
    var alertText by remember { mutableStateOf("") }
    var sosMessage by remember { mutableStateOf("") }
    var sosVoice by remember { mutableStateOf<File?>(null) }
    var sosRecording by remember { mutableStateOf(false) }
    var groupChat by remember { mutableStateOf(false) }
    var avatarDraft by remember(state.localAvatarIndex) { mutableIntStateOf(state.localAvatarIndex) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (tab == 1 && (selected != null || groupChat)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(if (groupChat) "MG" else selected!!.displayName, 38.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(if (groupChat) "Mesh Group" else selected!!.displayName, fontWeight = FontWeight.SemiBold)
                                Text(if (groupChat) "${state.peers.size} authenticated peers" else if (state.active) "mesh connected" else "mesh offline", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = { selected = null; groupChat = false }) { Icon(Icons.Default.ArrowBack, "Back") } }
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    title = {
                        Column {
                            Text("MeshLink", fontWeight = FontWeight.Bold)
                            Text(if (state.active) "●  Mesh active" else "○  Mesh disabled", fontSize = 11.sp, color = if (state.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = { editingName = true }) { Icon(Icons.Default.Person, "Edit profile") }
                        Switch(checked = state.active, onCheckedChange = onMeshToggle)
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 3.dp
            ) {
                val labels = listOf("Mesh", "Chats", "Map", "Events", "Safety")
                val icons = listOf(Icons.Default.Hub, Icons.Default.Chat, Icons.Default.AccountTree, Icons.Default.List, Icons.Default.Shield)
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; if (i != 1) { selected = null; groupChat = false } },
                        icon = {
                            if (i == 4 && safety.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(safety.size.coerceAtMost(99).toString()) } }) {
                                    Icon(icons[i], label)
                                }
                            } else {
                                Icon(icons[i], label)
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        label = { Text(label, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> Home(state, onSos = { confirmSos = true }, onAlert = { showAlert = true }, onLocation = onLocation, onVoice = {
                    if (!recording) { recording = true; onVoiceStart() } else { recording = false; onVoiceStop(null) }
                }, recording = recording, onPlayVoice = onPlayVoice, onSosReview = onSosReview, onPlayVoicePath = onPlayVoicePath, playingVoicePath = playingVoicePath)
                1 -> Chat(messages, state.peers, selected, groupChat, { selected = null; groupChat = false }, { selected = it; groupChat = false }, { groupChat = true; selected = null }, text, { text = "" }, onText, onBroadcast, { text = it }, onVoiceStart, { peer -> onVoiceStop(if (groupChat) null else peer) }, recording, { recording = it }, state.lastVoicePath, onPlayVoicePath, onLocation, playingVoicePath)
                2 -> MapTab(state)
                3 -> Events(events)
                else -> Safety(safety, onSafetyReview)
            }
        }
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Send SOS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Send a real priority emergency packet to the authenticated local mesh. GPS is included when available.", fontSize = 12.sp)
                    OutlinedTextField(
                        value = sosMessage,
                        onValueChange = { sosMessage = it.take(500) },
                        label = { Text("Emergency message") },
                        placeholder = { Text("What happened?") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            if (!sosRecording) { sosRecording = true; onSosVoiceStart() }
                            else { sosRecording = false; sosVoice = onSosVoiceStop() }
                        }) {
                            Icon(if (sosRecording) Icons.Default.Stop else Icons.Default.Mic, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (sosRecording) "Stop voice" else "Attach voice")
                        }
                        Spacer(Modifier.width(8.dp))
                        if (sosVoice != null) Text("Voice attached", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val msg = sosMessage.trim().ifBlank { "Emergency assistance requested" }
                    confirmSos = false
                    onSos(msg, sosVoice)
                    sosMessage = ""
                    sosVoice = null
                    sosRecording = false
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Send SOS") }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false; sosRecording = false; sosVoice = null }) { Text("Cancel") } }
        )
    }

    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            icon = { Icon(Icons.Default.Campaign, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Send local alert") },
            text = {
                OutlinedTextField(
                    value = alertText,
                    onValueChange = { alertText = it.take(240) },
                    label = { Text("Alert message") },
                    placeholder = { Text("e.g. Road blocked near the main gate") },
                    minLines = 2,
                    maxLines = 5
                )
            },
            confirmButton = {
                Button(onClick = {
                    val message = alertText.trim()
                    if (message.isNotEmpty()) { onAlert(message); alertText = ""; showAlert = false }
                }) { Text("Send alert") }
            },
            dismissButton = { TextButton(onClick = { showAlert = false }) { Text("Cancel") } }
        )
    }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your MeshLink profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it.take(32) }, singleLine = true, label = { Text("Display name") }, leadingIcon = { Icon(Icons.Default.Person, null) })
                    Text("Choose an avatar", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        AvatarOptions.forEachIndexed { index, _ ->
                            Box(Modifier.clickable { avatarDraft = index }.padding(2.dp)) {
                                Avatar(nameDraft, 40.dp, index, avatarDraft == index)
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { onNameChange(nameDraft); onAvatarChange(avatarDraft); editingName = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Home(state: MeshUiState, onSos: () -> Unit, onAlert: () -> Unit, onLocation: () -> Unit, onVoice: () -> Unit, recording: Boolean, onPlayVoice: () -> Unit, onSosReview: (String, String) -> Unit = { _, _ -> }, onPlayVoicePath: (String) -> Unit = {}, playingVoicePath: String? = null) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("NODES", state.peers.size.toString())
                        Stat("STATUS", if (state.active) "ONLINE" else "OFFLINE")
                        Stat("HOPS", state.lastRoute.substringAfter("•", "—").trim())
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("LIVE MESH", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    if (state.peers.isEmpty()) Text("No authenticated peers yet. Enable Mesh Mode and bring another MeshLink phone nearby.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        state.peers.take(4).forEach { peer -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Avatar(peer.displayName, 44.dp); Spacer(Modifier.height(4.dp)); Text(peer.displayName.take(10), fontSize = 11.sp, maxLines = 1) } }
                    }
                    if (state.lastRoute.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text("Last route  ${state.lastRoute}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onAlert, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) { Icon(Icons.Default.Campaign, null, Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text("Alert", fontSize = 13.sp, maxLines = 1) }
                OutlinedButton(onClick = onLocation, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) { Icon(Icons.Default.LocationOn, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text("Location", fontSize = 13.sp, maxLines = 1) }
                OutlinedButton(onClick = onVoice, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) { Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text(if (recording) "Stop" else "Voice", fontSize = 13.sp, maxLines = 1) }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().height(122.dp).clickable(onClick = onSos),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1EF)),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.16f)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Warning, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp)); Text("SEND SOS", fontWeight = FontWeight.Bold)
                    Text("Priority 100 • GPS • 5-pulse alarm • relay", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        item {
            state.lastAlert?.let { alert ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(if (alert.local) "ALERT SENT" else "INCOMING ALERT", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text("From ${alert.sender}", fontSize = 12.sp)
                        Text(alert.text, fontSize = 14.sp)
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(alert.timestamp)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            state.lastSos?.let { sos ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(if (sos.reviewStatus == "PENDING") "INCOMING SOS • REVIEW REQUIRED" else "SOS ${sos.reviewStatus}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Text("From ${sos.sender}")
                        Text(sos.message, fontSize = 14.sp)
                        Text(sos.location, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                        if (sos.voicePath != null) {
                            val path = sos.voicePath
                            val isPlaying = playingVoicePath == path
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isPlaying) "Voice attachment playing" else "Voice attachment", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                FilledTonalButton(onClick = { onPlayVoicePath(path) }) {
                                    Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isPlaying) "Stop" else "Play")
                                }
                            }
                        } else if (sos.hasVoice) {
                            Text("Voice attachment is still transferring…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sos.timestamp)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sos.reviewStatus == "PENDING") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onSosReview(sos.messageId, "REVIEW") }) { Text("Review") }
                                TextButton(onClick = { onSosReview(sos.messageId, "REJECT") }) { Text("Reject") }
                                Button(onClick = { onSosReview(sos.messageId, "PASS") }) { Text("Pass") }
                            }
                        }
                    }
                }
            }
        }
        item {
            state.lastLocationText?.let { text ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("LAST LOCATION", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // Voice recordings are shown in their actual chat/SOS message. There is no
        // duplicate global "Voice message ready" card that could accidentally
        // point every recording at the latest file.
    }
}

@Composable private fun Stat(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun Chat(
    messages: List<MessageEntity>, peers: List<MeshPeer>, selected: MeshPeer?, groupMode: Boolean,
    onBack: () -> Unit, onSelect: (MeshPeer) -> Unit, onSelectGroup: () -> Unit,
    text: String, onClear: () -> Unit, onSend: (MeshPeer, String) -> Unit, onBroadcast: (String) -> Unit, onTextChange: (String) -> Unit,
    onVoiceStart: () -> Unit, onVoiceStop: (MeshPeer?) -> Unit, recording: Boolean, setRecording: (Boolean) -> Unit,
    lastVoicePath: String?, onPlayVoice: (String) -> Unit, onLocation: () -> Unit, playingVoicePath: String? = null
) {
    if (selected == null && !groupMode) {
        val latestGroup = messages.filter { it.receiverNodeId == "*" }.maxByOrNull { it.createdAt }
        Column(Modifier.fillMaxSize()) {
            Text("CONVERSATIONS", Modifier.padding(start = 18.dp, top = 14.dp, bottom = 8.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { onSelectGroup() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                ListItem(
                    headlineContent = { Text("Mesh Group", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(latestGroup?.payloadText ?: "Broadcast to authenticated MeshLink peers", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Avatar("MG", 48.dp) },
                    trailingContent = { latestGroup?.let { ChatTime(it.createdAt) } }
                )
            }
            Spacer(Modifier.height(6.dp))
            if (peers.isEmpty()) {
                EmptyState(Icons.Default.Chat, "No personal chats yet", "Authenticated MeshLink peers will appear here automatically.")
            } else {
                Text("PEERS", Modifier.padding(start = 18.dp, top = 8.dp, bottom = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(peers, key = { it.address }) { peer ->
                        val latest = messages.filter { it.senderNodeId.equals(peer.nodeId, true) || it.receiverNodeId.equals(peer.nodeId, true) }.maxByOrNull { it.createdAt }
                        ListItem(
                            headlineContent = { Text(peer.displayName, fontWeight = FontWeight.SemiBold) },
                            supportingContent = {
                                Text(
                                    latest?.let { if (it.type == "VOICE_CHUNK") "Voice message" else it.payloadText.orEmpty().ifBlank { it.status.lowercase().replace('_', ' ') } }
                                        ?: "${peer.nodeId.takeLast(8)} • ${signalText(peer.rssi)}",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = { Avatar(peer.displayName, 50.dp) },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    latest?.let { ChatTime(it.createdAt) }
                                    Spacer(Modifier.height(3.dp))
                                    Text(signalText(peer.rssi), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.clickable { onSelect(peer) }
                        )
                        HorizontalDivider(Modifier.padding(start = 82.dp))
                    }
                }
            }
        }
        return
    }

    if (groupMode) {
        val chatMessages = messages.filter { it.receiverNodeId == "*" && it.type != "SOS_REVIEW" }
        val listState = rememberLazyListState()
        LaunchedEffect(chatMessages.size) { if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.lastIndex) }
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 10.dp)) {
                if (chatMessages.isEmpty()) item { EmptyState(Icons.Default.Groups, "Mesh Group", "Messages sent here are broadcast through the authenticated mesh.") }
                items(chatMessages, key = { it.messageId }) { message -> MessageBubble(message, onPlayVoice, playingVoicePath) }
            }
            ChatComposer(text, onTextChange, onClear, onLocation, recording, setRecording, onVoiceStart, { onVoiceStop(null) }, null, onBroadcast)
        }
        return
    }

    val peer = selected ?: return
    val chatMessages = messages.filter { it.senderNodeId.equals(peer.nodeId, true) || it.receiverNodeId.equals(peer.nodeId, true) }
    val listState = rememberLazyListState()
    LaunchedEffect(chatMessages.size) { if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 10.dp)) {
            if (chatMessages.isEmpty()) item { EmptyState(Icons.Default.ChatBubbleOutline, "Start the conversation", "Messages are stored locally and travel through authenticated BLE peers.") }
            items(chatMessages, key = { it.messageId }) { message -> MessageBubble(message, onPlayVoice, playingVoicePath) }
        }
        lastVoicePath?.let { path ->
            Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Latest voice recording", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { onPlayVoice(path) }) { Icon(Icons.Default.PlayArrow, "Play voice") }
                }
            }
        }
        ChatComposer(text, onTextChange, onClear, onLocation, recording, setRecording, onVoiceStart, { onVoiceStop(peer) }, peer, null, onSend = onSend)
    }
}

@Composable
private fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onLocation: () -> Unit,
    recording: Boolean,
    setRecording: (Boolean) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    peer: MeshPeer?,
    onBroadcast: ((String) -> Unit)? = null,
    onSend: ((MeshPeer, String) -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 7.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { if (!recording) { setRecording(true); onVoiceStart() } else { setRecording(false); onVoiceStop() } }) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, if (recording) "Stop recording" else "Voice message")
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (peer == null) "Message everyone" else "Message") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            )
            IconButton(onClick = onLocation) { Icon(Icons.Default.LocationOn, "Share location") }
            IconButton(onClick = {
                if (text.isNotBlank()) {
                    if (peer != null && onSend != null) onSend(peer, text)
                    else onBroadcast?.invoke(text)
                    onClear()
                }
            }) {
                Icon(Icons.Default.Send, "Send message", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, onPlayVoice: (String) -> Unit, playingVoicePath: String? = null) {
    val incoming = message.isIncoming
    val isVoice = message.type == "VOICE_CHUNK" && !message.payloadReference.isNullOrBlank()
    val bubbleColor = if (incoming) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End) {
        Surface(
            color = bubbleColor,
            tonalElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(20.dp, 20.dp, if (incoming) 20.dp else 5.dp, if (incoming) 5.dp else 20.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                if (incoming) Text(message.senderName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (isVoice) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val path = message.payloadReference
                        val isPlaying = path != null && path == playingVoicePath
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                            IconButton(onClick = { path?.let(onPlayVoice) }) {
                                Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, if (isPlaying) "Stop voice" else "Play voice")
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Voice message", fontWeight = FontWeight.SemiBold)
                            Text(if (isPlaying) "Playing…" else "Tap to listen", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            color = if (isPlaying) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (isPlaying) "STOP" else "PLAY", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (isPlaying) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(message.payloadText.orEmpty(), fontSize = 15.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!incoming) {
                            Spacer(Modifier.width(5.dp))
                            val tint = if (message.delivered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Icon(Icons.Default.DoneAll, "Delivery status", Modifier.size(14.dp), tint = tint)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ChatTime(timestamp: Long) {
    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MapTab(state: MeshUiState) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Offline Map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Static local view using only real GPS fixes shared by authenticated MeshLink peers. No online tiles, fake coordinates or estimated distances.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { OfflineGeoMap(state) }
        item { TopologyCard(state) }
        item { RealLocationRadar(state) }
    }
}

@Composable
private fun OfflineGeoMap(state: MeshUiState) {
    val localLat = state.localLatitude
    val localLon = state.localLongitude
    val visible = state.peerLocations.values.filter { loc ->
        state.peers.any { peer -> peer.nodeId.equals(loc.nodeId, ignoreCase = true) }
    }
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Real GPS points", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (localLat == null || localLon == null) {
                Text("No local GPS fix yet. Share Location to establish the real map center.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val primary = MaterialTheme.colorScheme.primary
                    val secondary = MaterialTheme.colorScheme.secondary
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        // Local grid only; it is a geographic plotting surface, not an online map tile.
                        for (i in 1..4) {
                            val x = size.width * i / 5f
                            val y = size.height * i / 5f
                            drawLine(primary.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                            drawLine(primary.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                        }
                        drawCircle(primary, 9f, androidx.compose.ui.geometry.Offset(cx, cy))
                        visible.forEach { loc ->
                            val dx = ((loc.longitude - localLon) * 111320.0 * kotlin.math.cos(Math.toRadians(localLat))).toFloat()
                            val dy = ((loc.latitude - localLat) * 110540.0).toFloat()
                            val scale = 0.055f
                            val x = (cx + dx * scale).coerceIn(18f, size.width - 18f)
                            val y = (cy - dy * scale).coerceIn(18f, size.height - 18f)
                            drawLine(primary.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(x, y), 2f)
                            drawCircle(secondary, 8f, androidx.compose.ui.geometry.Offset(x, y))
                        }
                    }
                    Text("YOU", Modifier.align(Alignment.Center).offset(y = 18.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    if (visible.isEmpty()) {
                        Text("No authenticated peer GPS fixes shared yet", Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), fontSize = 11.sp)
                    } else {
                        Column(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                            visible.take(5).forEach { Text("• ${it.name}", fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyCard(state: MeshUiState) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Offline Mesh Topology", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Only observed authenticated BLE peers are shown. No fake nodes or distances.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val topologyPrimary = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val visiblePeers = state.peers.take(6)
                    if (visiblePeers.isNotEmpty()) {
                        visiblePeers.forEachIndexed { i, _ ->
                            val angle = (Math.PI * 2.0 * i / visiblePeers.size) - Math.PI / 2.0
                            val px = cx + kotlin.math.cos(angle).toFloat() * (size.minDimension * 0.34f)
                            val py = cy + kotlin.math.sin(angle).toFloat() * (size.minDimension * 0.34f)
                            drawLine(topologyPrimary.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(px, py), strokeWidth = 3f)
                            drawCircle(topologyPrimary.copy(alpha = 0.18f), 24f, androidx.compose.ui.geometry.Offset(px, py))
                        }
                    }
                    drawCircle(topologyPrimary, 30f, androidx.compose.ui.geometry.Offset(cx, cy))
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(state.localName, 56.dp, state.localAvatarIndex)
                    Text("YOU", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                if (state.peers.isEmpty()) {
                    Text("Waiting for authenticated peers…", Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), fontSize = 12.sp)
                } else {
                    state.peers.take(6).forEachIndexed { index, peer ->
                        val count = state.peers.take(6).size
                        val angle = (Math.PI * 2.0 * index / count) - Math.PI / 2.0
                        val x = kotlin.math.cos(angle).toFloat() * 88.dp.value
                        val y = kotlin.math.sin(angle).toFloat() * 88.dp.value
                        Column(Modifier.align(Alignment.Center).offset(x.dp, y.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(peer.displayName, 42.dp)
                            Text(peer.displayName.take(10), fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshTopology(state: MeshUiState) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Offline Mesh Topology", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Only observed authenticated BLE links are shown. No Internet map or fake nodes.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val topologyPrimary = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val count = state.peers.size.coerceAtMost(6)
                    if (count > 0) {
                        for (i in 0 until count) {
                            val angle = (Math.PI * 2.0 * i / count) - Math.PI / 2.0
                            val px = cx + kotlin.math.cos(angle).toFloat() * (size.minDimension * 0.34f)
                            val py = cy + kotlin.math.sin(angle).toFloat() * (size.minDimension * 0.34f)
                            drawLine(topologyPrimary.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(px, py), strokeWidth = 3f)
                            drawCircle(topologyPrimary.copy(alpha = 0.18f), 24f, androidx.compose.ui.geometry.Offset(px, py))
                        }
                    }
                    drawCircle(topologyPrimary, 30f, androidx.compose.ui.geometry.Offset(cx, cy))
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(state.localName, 56.dp, state.localAvatarIndex)
                    Text("YOU", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                if (state.peers.isEmpty()) {
                    Text("Waiting for authenticated peers…", Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), fontSize = 12.sp)
                } else {
                    state.peers.take(6).forEachIndexed { index, peer ->
                        val angle = (Math.PI * 2.0 * index / state.peers.take(6).size) - Math.PI / 2.0
                        val radius = 88.dp
                        val x = kotlin.math.cos(angle).toFloat() * radius.value
                        val y = kotlin.math.sin(angle).toFloat() * radius.value
                        Column(Modifier.align(Alignment.Center).offset(x.dp, y.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(peer.displayName, 42.dp)
                            Text(peer.displayName.take(10), fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        item {
            RealLocationRadar(state)
        }
        items(state.peers, key = { it.address }) { peer ->
            ListItem(
                headlineContent = { Text(peer.displayName, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("${peer.nodeId.takeLast(8)} • ${signalText(peer.rssi)}") },
                leadingContent = { Avatar(peer.displayName, 42.dp) },
                trailingContent = { Text("${peer.rssi} dBm", fontSize = 10.sp) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun RealLocationRadar(state: MeshUiState) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Real Location Radar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Only GPS coordinates actually shared by MeshLink devices are plotted. No estimated or fake distances.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            val lat0 = state.localLatitude
            val lon0 = state.localLongitude
            if (lat0 == null || lon0 == null) {
                Text("Your GPS location is not available yet. Tap Location to share a real fix.", fontSize = 12.sp)
            } else {
                Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val primary = MaterialTheme.colorScheme.primary
                    val secondary = MaterialTheme.colorScheme.secondary
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val radius = minOf(size.width, size.height) * 0.40f
                        for (ring in 1..3) drawCircle(primary.copy(alpha = 0.10f), radius * ring / 3f, androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                        drawCircle(primary, 10f, androidx.compose.ui.geometry.Offset(cx, cy))
                        state.peerLocations.values.filter { loc -> state.peers.any { peer -> peer.nodeId.equals(loc.nodeId, ignoreCase = true) } }.forEach { loc ->
                            val dx = ((loc.longitude - lon0) * 111320.0 * kotlin.math.cos(Math.toRadians(lat0))).toFloat()
                            val dy = ((loc.latitude - lat0) * 110540.0).toFloat()
                            val scale = 0.08f
                            val x = (cx + dx * scale).coerceIn(18f, size.width - 18f)
                            val y = (cy - dy * scale).coerceIn(18f, size.height - 18f)
                            drawCircle(secondary, 9f, androidx.compose.ui.geometry.Offset(x, y))
                        }
                    }
                    Text("YOU", Modifier.align(Alignment.Center).offset(y = 20.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    val visibleLocations = state.peerLocations.values.filter { loc -> state.peers.any { peer -> peer.nodeId.equals(loc.nodeId, ignoreCase = true) } }
                    if (visibleLocations.isEmpty()) Text("No connected peer GPS fixes shared yet", Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), fontSize = 11.sp)
                    else Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) { visibleLocations.take(4).forEach { Text("• ${it.name}: ${"%.5f".format(it.latitude)}, ${"%.5f".format(it.longitude)}", fontSize = 9.sp) } }
                }
            }
        }
    }
}

@Composable private fun Events(events: List<EventEntity>) {
    if (events.isEmpty()) EmptyState(Icons.Default.List, "No events yet", "Mesh, security, relay and SOS events will appear here.")
    else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(events, key = { it.eventId }) { event ->
            Card { ListItem(headlineContent = { Text(event.category, fontWeight = FontWeight.Bold) }, supportingContent = { Text("${event.details}\n${event.nodeId?.takeLast(8).orEmpty()} ${event.messageId?.takeLast(8).orEmpty()}") }, leadingContent = { Icon(Icons.Default.Info, null) }) }
        }
    }
}

@Composable
private fun Safety(items: List<MessageEntity>, onReview: (String, SafetyDecision) -> Unit) {
    val held = items.count { it.status == "HELD" }
    val blocked = items.count { it.status == "BLOCKED" }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp)
    ) {
        item {
            Text("Safety & Vulnerability", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(
                "Offline rule-based protection checks suspicious patterns, repetition, flooding and malformed traffic. It does not decide whether a real-world claim is true or false.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SafetyStat("OPEN", held, MaterialTheme.colorScheme.tertiaryContainer, Modifier.weight(1f))
                SafetyStat("BLOCKED", blocked, MaterialTheme.colorScheme.errorContainer, Modifier.weight(1f))
                SafetyStat("TOTAL", items.size, MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f))
            }
        }
        if (items.isEmpty()) {
            item { EmptyState(Icons.Default.Shield, "Nothing needs review", "Suspicious non-emergency traffic will appear here before it can propagate through the mesh.") }
        } else {
            items(items, key = { it.messageId }) { message ->
                val isBlocked = message.status == "BLOCKED"
                val isHeld = message.status == "HELD"
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isBlocked) MaterialTheme.colorScheme.errorContainer
                        else if (isHeld) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isBlocked) Icons.Default.Block else Icons.Default.Shield, null, tint = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (isBlocked) "HIGH-RISK / BLOCKED" else if (isHeld) "SUSPICIOUS / REVIEW" else "PASSED", fontWeight = FontWeight.Bold)
                                Text("${message.type} • ${message.senderName}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ChatTime(message.createdAt)
                        }
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                            Text(message.payloadText.orEmpty(), Modifier.fillMaxWidth().padding(12.dp), maxLines = 6, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(message.lastError ?: "Local policy result", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isHeld || isBlocked) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onReview(message.messageId, SafetyDecision.BLOCK) }) { Text("Block") }
                                TextButton(onClick = { onReview(message.messageId, SafetyDecision.HOLD) }) { Text("Keep Held") }
                                Button(onClick = { onReview(message.messageId, SafetyDecision.PASS) }) { Text("Pass & Release") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyStat(label: String, value: Int, container: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private val AvatarOptions = listOf("🟢", "🔵", "🟣", "🟠", "🌿", "⭐", "⚡", "🛡️")

@Composable private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, avatarIndex: Int? = null, selected: Boolean = false) {
    val accents = listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
    val accent = accents[(name.hashCode() and Int.MAX_VALUE) % accents.size]
    val emoji = avatarIndex?.let { AvatarOptions.getOrNull(it) }
    Box(Modifier.size(size).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
        if (emoji != null) Text(emoji, fontSize = (size.value * 0.43f).sp)
        else Text(name.trim().firstOrNull()?.uppercase() ?: "M", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (selected) {
            Surface(Modifier.matchParentSize(), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), shape = CircleShape) {}
        }
    }
}

@Composable private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp)); Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun signalText(rssi: Int): String = when { rssi > -60 -> "Excellent signal"; rssi > -80 -> "Good signal"; else -> "Weak signal" }

private fun Modifier.sosHoldGesture(onTriggered: () -> Unit): Modifier = pointerInput(onTriggered) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(2_100L) { waitForUpOrCancellation() }
        if (up != null && up.uptimeMillis - down.uptimeMillis >= 2_000L) {
            onTriggered()
        }
    }
}

