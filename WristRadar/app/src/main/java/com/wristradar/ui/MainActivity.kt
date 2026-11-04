package com.wristradar.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.wristradar.WristRadarApp
import com.wristradar.core.PairingState
import com.wristradar.core.RadarEngine
import com.wristradar.data.ColorSlot
import com.wristradar.service.WristRadarService
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.wristradar.ui.blackbox.BlackboxOverlay
import com.wristradar.ui.screens.AboutScreen
import com.wristradar.ui.screens.BlackboxSettingsScreen
import com.wristradar.ui.screens.SecurityLogScreen
import com.wristradar.ui.screens.AppearanceScreen
import com.wristradar.ui.screens.ChatScreen
import com.wristradar.ui.screens.ColorPickerScreen
import com.wristradar.ui.screens.ConnectionInfoScreen
import com.wristradar.ui.screens.CustomThemeScreen
import com.wristradar.ui.screens.DiscoveryScreen
import com.wristradar.ui.screens.HomeScreen
import com.wristradar.ui.screens.ImagePickerScreen
import com.wristradar.ui.screens.ImagePreviewScreen
import com.wristradar.ui.screens.ImageViewerScreen
import com.wristradar.ui.screens.MyProfileScreen
import com.wristradar.ui.screens.NotificationSettingsScreen
import com.wristradar.ui.screens.PairTargetScreen
import com.wristradar.ui.screens.PairingFlowScreen
import com.wristradar.ui.screens.PartnerProfileScreen
import com.wristradar.ui.screens.PrivacyScreen
import com.wristradar.ui.screens.SettingsScreen
import com.wristradar.ui.theme.WristRadarTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small in-memory hand-off between the picker and preview screens. */
/** Bumped when a call notification is tapped so the call screen opens. */
val callSignalFlow = MutableStateFlow(0)

object UiShared {
    var pickedBitmap: Bitmap? = null
}

class MainActivity : ComponentActivity() {

    private val openChatSignal = MutableStateFlow(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        requestPermissions()
        setContent {
            val settings by WristRadarApp.graph.settings.state.collectAsState()
            WristRadarTheme(settings) {
                WrNav(openChatSignal, ::requestPermissions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true) openChatSignal.value += 1
        if (i?.getBooleanExtra(EXTRA_CALL, false) == true) callSignalFlow.value += 1
    }

    override fun onStart() {
        super.onStart()
        val g = WristRadarApp.graph
        g.uiVisibility.appVisible = true
        g.engine.start()
    }

    override fun onStop() {
        val g = WristRadarApp.graph
        g.uiVisibility.appVisible = false
        g.engine.stopIfIdle()
        super.onStop()
    }

    fun requestPermissions() {
        val wanted = RadarEngine.REQUIRED_PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS + Manifest.permission.RECORD_AUDIO
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) onPermissionsResult() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsResult() {
        WristRadarApp.graph.engine.start()
        WristRadarService.start(this)
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"
        const val EXTRA_BLACKBOX = "blackbox"
        const val EXTRA_CALL = "call"
    }
}

@Composable
private fun WrNav(openChat: StateFlow<Int>, requestPermissions: () -> Unit) {
    val nav = rememberSwipeDismissableNavController()
    val g = WristRadarApp.graph
    val pairing by g.engine.pairing.state.collectAsState()
    val openChatCount by openChat.collectAsState()

    LaunchedEffect(openChatCount) {
        if (openChatCount > 0 && g.trust.partner.value != null) nav.navigate("chat") { launchSingleTop = true }
    }
    val callState by g.engine.calls.state.collectAsState()
    val callSignal by callSignalFlow.collectAsState()
    LaunchedEffect(callState, callSignal) {
        val live = callState is com.wristradar.audio.CallState.Incoming || callState is com.wristradar.audio.CallState.Outgoing ||
            callState is com.wristradar.audio.CallState.Active
        if (live && nav.currentDestination?.route != "call") nav.navigate("call") { launchSingleTop = true }
    }
    LaunchedEffect(pairing) {
        if (pairing is PairingState.Incoming) {
            val r = nav.currentDestination?.route
            if (r != "pairing" && r?.startsWith("pair/") != true) nav.navigate("pairing") { launchSingleTop = true }
        }
    }

    Box(Modifier.fillMaxSize()) {
    // Swipe-back is off on the call screen so it can't fight the hang-up/answer sliders.
    val entry by nav.currentBackStackEntryFlow.collectAsState(initial = null)
    SwipeDismissableNavHost(navController = nav, startDestination = "home", userSwipeEnabled = entry?.destination?.route != "call") {
        composable("home") { HomeScreen(nav, requestPermissions) }
        composable("discovery") { DiscoveryScreen(nav) }
        composable("pair/{id}") { PairTargetScreen(nav, it.arguments?.getString("id")?.toIntOrNull() ?: 0) }
        composable("pairing") { PairingFlowScreen(nav) }
        composable("chat") { ChatScreen(nav) }
        composable("image/{id}") { ImageViewerScreen(it.arguments?.getString("id") ?: "") }
        composable("profile") { MyProfileScreen(nav) }
        composable("partner") { PartnerProfileScreen() }
        composable("settings") { SettingsScreen(nav) }
        composable("appearance") { AppearanceScreen(nav) }
        composable("custom") { CustomThemeScreen(nav) }
        composable("color/{slot}") {
            val slot = runCatching { ColorSlot.valueOf(it.arguments?.getString("slot") ?: "") }.getOrDefault(ColorSlot.PRIMARY)
            ColorPickerScreen(nav, slot)
        }
        composable("notifications") { NotificationSettingsScreen() }
        composable("privacy") { PrivacyScreen() }
        composable("connection") { ConnectionInfoScreen() }
        composable("about") { AboutScreen() }
        composable("blackbox") { BlackboxSettingsScreen(nav) }
        composable("securitylog") { SecurityLogScreen() }
        composable("call") { com.wristradar.ui.screens.CallScreen(nav) }
        composable("voicenote/{mode}") { com.wristradar.ui.screens.VoiceNoteScreen(nav, it.arguments?.getString("mode") ?: "note") }
        composable("picker/{purpose}") { ImagePickerScreen(nav, it.arguments?.getString("purpose") ?: "chat") }
        composable("preview/{purpose}") { ImagePreviewScreen(nav, it.arguments?.getString("purpose") ?: "chat") }
    }
    // Overlays above every screen: in-app banner, security warning (users), BLACKBOX (intruder only).
    com.wristradar.ui.components.TopBanner(onOpen = { nav.navigate("chat") { launchSingleTop = true } })
    com.wristradar.ui.blackbox.SecurityWarningOverlay(onOpenLog = { nav.navigate("securitylog") { launchSingleTop = true } })
    BlackboxOverlay(onOpenLog = { nav.navigate("securitylog") { launchSingleTop = true } })
    }
}
