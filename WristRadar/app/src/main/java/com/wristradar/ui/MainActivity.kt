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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Screens where swiping right must not close the screen (drawing, games, holding buttons). */
private val NoSwipeRoutes = setOf("call", "draw", "game", "taps", "crop/{purpose}")

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
                androidx.compose.foundation.layout.Box {
                    WrNav(openChatSignal, ::requestPermissions)
                    if (settings.dynamicIsland) com.wristradar.ui.components.DynamicIsland()
                }
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
    // Announce each new version once, right after it is installed.
    LaunchedEffect(Unit) {
        if (g.settings.current.seenVersion < com.wristradar.BuildConfig.VERSION_CODE) nav.navigate("whatsnew") { launchSingleTop = true }
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
    SwipeDismissableNavHost(navController = nav, startDestination = "home", userSwipeEnabled = entry?.destination?.route !in NoSwipeRoutes) {
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
        composable("themes/{folder}") { com.wristradar.ui.screens.ThemeFolderScreen(it.arguments?.getString("folder") ?: "GLASS") }
        composable("custom") { CustomThemeScreen(nav) }
        composable("color/{slot}") {
            val slot = runCatching { ColorSlot.valueOf(it.arguments?.getString("slot") ?: "") }.getOrDefault(ColorSlot.PRIMARY)
            ColorPickerScreen(nav, slot)
        }
        composable("notifications") {
            com.wristradar.ui.screens.QuickRepliesNav.go = { nav.navigate("quickreplies") }
            NotificationSettingsScreen()
        }
        composable("privacy") { PrivacyScreen(nav) }
        composable("safety") { com.wristradar.ui.screens.SafetyCodeScreen() }
        composable("connection") { ConnectionInfoScreen() }
        composable("about") { AboutScreen() }
        composable("msg/{id}") { com.wristradar.ui.screens.MessageActionsScreen(nav, it.arguments?.getString("id") ?: "") }
        composable("pinned") { com.wristradar.ui.screens.PinnedScreen(nav) }
        composable("search") { com.wristradar.ui.screens.SearchScreen(nav) }
        composable("crop/{purpose}") { com.wristradar.ui.screens.CropScreen(nav, it.arguments?.getString("purpose") ?: "avatar") }
        composable("effects") { com.wristradar.ui.screens.EffectPickerScreen(nav) }
        composable("quickreplies") { com.wristradar.ui.screens.QuickRepliesScreen() }
        composable("fun") { com.wristradar.ui.screens.FunMenuScreen(nav) }
        composable("draw") { com.wristradar.ui.screens.DrawScreen(nav) }
        composable("blast") { com.wristradar.ui.screens.BlastScreen(nav) }
        composable("games") { com.wristradar.ui.screens.GamesScreen(nav) }
        composable("game") { com.wristradar.ui.screens.GameScreen(nav) }
        composable("taps") { com.wristradar.ui.screens.TapCodeScreen(nav) }
        composable("hideseek") { com.wristradar.ui.screens.HideSeekScreen() }
        composable("todo") { com.wristradar.ui.screens.TodoScreen() }
        composable("mood") { com.wristradar.ui.screens.MoodScreen(nav) }
        composable("updates") { com.wristradar.ui.screens.UpdatesScreen() }
        composable("walk") {
            com.wristradar.ui.screens.HideSeekNav.go = { nav.navigate("hideseek") }
            com.wristradar.ui.screens.WalkScreen()
        }
        composable("whatsnew") { com.wristradar.ui.screens.WhatsNewScreen(nav) }
        composable("disclaimer") { com.wristradar.ui.screens.DisclaimerScreen() }
        composable("blackbox") { BlackboxSettingsScreen(nav) }
        composable("securitylog") { SecurityLogScreen() }
        composable("call") { com.wristradar.ui.screens.CallScreen(nav) }
        composable("voicenote/{mode}") { com.wristradar.ui.screens.VoiceNoteScreen(nav, it.arguments?.getString("mode") ?: "note") }
        composable("picker/{purpose}") { ImagePickerScreen(nav, it.arguments?.getString("purpose") ?: "chat") }
        composable("preview/{purpose}") { ImagePreviewScreen(nav, it.arguments?.getString("purpose") ?: "chat") }
    }
    // Back: the watch's back key / swipe goes to the previous screen instead of leaving the app,
    // and a small "‹" button on the left edge does the same.
    val route = entry?.destination?.route
    val canGoBack = route != null && route != "home" && nav.previousBackStackEntry != null
    androidx.activity.compose.BackHandler(enabled = canGoBack) { nav.popBackStack() }
    if (canGoBack && route != "call") BackButton { nav.popBackStack() }
    // Overlays above every screen: in-app banner, security warning (users), BLACKBOX (intruder only).
    com.wristradar.ui.components.TopBanner(onOpen = { nav.navigate("chat") { launchSingleTop = true } })
    com.wristradar.ui.blackbox.SecurityWarningOverlay(onOpenLog = { nav.navigate("securitylog") { launchSingleTop = true } })
    com.wristradar.ui.screens.GameInviteOverlay(onOpen = { nav.navigate("game") { launchSingleTop = true } })
    com.wristradar.ui.screens.BlastOverlay()
    BlackboxOverlay(onOpenLog = { nav.navigate("securitylog") { launchSingleTop = true } })
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    val p = com.wristradar.ui.theme.LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
        Box(
            Modifier.padding(start = 2.dp).size(28.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(p.surface.copy(alpha = 0.85f))
                .clickable(onClickLabel = "Back") { onBack() },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { androidx.wear.compose.material.Text("‹", fontSize = 20.sp, color = p.text) }
    }
}
