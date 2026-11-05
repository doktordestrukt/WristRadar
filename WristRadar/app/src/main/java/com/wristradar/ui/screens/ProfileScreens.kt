package com.wristradar.ui.screens

import androidx.compose.material.icons.filled.Star

import androidx.compose.material.icons.filled.Delete

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.wristradar.R
import com.wristradar.WristRadarApp
import com.wristradar.ble.DistanceEstimator
import com.wristradar.crypto.IdentityManager
import com.wristradar.ui.UiShared
import com.wristradar.ui.components.Avatar
import com.wristradar.ui.components.Hint
import com.wristradar.ui.components.KeyValue
import com.wristradar.ui.components.SectionLabel
import com.wristradar.ui.components.StatusLine
import com.wristradar.ui.components.WrChip
import com.wristradar.ui.components.WrScreen
import com.wristradar.ui.components.rememberTextInput
import com.wristradar.ui.theme.LocalPalette
import com.wristradar.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun MyProfileScreen(nav: NavHostController) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val name by g.profile.myName.collectAsState()
    val avatar by g.profile.myAvatar.collectAsState()
    val bio by g.profile.myBio.collectAsState()
    val presence by g.engine.presence.collectAsState()
    val editBio = rememberTextInput { t -> g.profile.setMyBio(t); g.engine.pushProfile() }
    val partner by g.trust.partner.collectAsState()
    val banner by g.profile.myBanner.collectAsState()
    val effect by g.profile.myEffect.collectAsState()
    val editName = rememberTextInput { t ->
        g.profile.setMyName(t)
        g.engine.pushProfile()      // synced over the encrypted channel; identity keys unchanged
    }
    WrScreen {
        item { ProfileHeader(banner, avatar, name, effect, onBanner = { nav.navigate("picker/banner") }, onAvatar = { nav.navigate("picker/avatar") }) }
        item { Text(name, fontSize = 17.sp, color = p.text) }
        item { Text(bio.ifBlank { "No bio yet" }, fontSize = 12.sp, color = if (bio.isBlank()) p.textDim else p.text, textAlign = TextAlign.Center) }
        item { Hint("Your display name. It is not your security identity.") }
        item { WrChip("Edit username", { editName("Username (max 20)") }, secondary = name, icon = { Icon(Icons.Filled.Edit, null) }) }
        item { WrChip("Edit bio", { editBio("Bio (max 120)") }, secondary = bio.ifBlank { null }, icon = { Icon(Icons.Filled.Edit, null) }) }
        item { KeyValue("This watch", when (presence.myPhone) { true -> "📱 Connected to phone"; false -> "No phone connected"; null -> "Phone status unknown" }) }
        item {
            WrChip("Change profile picture", { nav.navigate("picker/avatar") }, icon = { Icon(painterResource(R.drawable.ic_image), null) })
        }
        item { WrChip(if (banner == null) "Add a banner" else "Change banner", { nav.navigate("picker/banner") }, secondary = "Crop it to fit", icon = { Icon(painterResource(R.drawable.ic_image), null) }) }
        if (banner != null) item { WrChip("Remove banner", { g.profile.setMyBanner(null); g.engine.pushProfile() }, icon = { Icon(Icons.Filled.Delete, null) }) }
        item { WrChip("Profile effect", { nav.navigate("effects") }, secondary = effect.label, icon = { Icon(Icons.Filled.Star, null) }) }
        if (avatar != null) item {
            WrChip("Use default avatar", { g.profile.setMyAvatar(null); g.engine.pushProfile() }, icon = { Icon(Icons.Filled.Face, null) })
        }
        if (partner != null) item {
            WrChip("View partner profile", { nav.navigate("partner") }, icon = { Icon(Icons.Filled.Person, null) })
        }
        item { KeyValue("Device fingerprint", g.identity.fingerprint()) }
    }
}

@Composable
fun PartnerProfileScreen() {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val name by g.profile.partnerName.collectAsState()
    val avatar by g.profile.partnerAvatar.collectAsState()
    val partner by g.trust.partner.collectAsState()
    val conn by g.engine.connection.collectAsState()
    val unit = g.settings.state.collectAsState().value.unit
    val pBio by g.profile.partnerBio.collectAsState()
    val presence by g.engine.presence.collectAsState()
    val lastSeen by g.trust.partnerLastSeen.collectAsState()
    val last by g.trust.lastConnected.collectAsState()
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    val pBanner by g.profile.partnerBanner.collectAsState()
    val pEffect by g.profile.partnerEffect.collectAsState()
    WrScreen {
        item { ProfileHeader(pBanner, avatar, name, pEffect) }
        item { Text(name ?: "No partner", fontSize = 17.sp, color = p.text) }
        if (pBio.isNotBlank()) item { Text(pBio, fontSize = 12.sp, color = p.text, textAlign = TextAlign.Center) }
        item { Text(com.wristradar.ui.components.presenceText(conn.phase == com.wristradar.core.Phase.CONNECTED, lastSeen), fontSize = 11.sp, color = p.primary) }
        item { StatusLine(conn.phase) }
        item { KeyValue("Their phone", when (presence.partnerPhone) { true -> "📱 Connected to their phone"; false -> "No phone connected"; null -> "Unknown" }) }
        val pt = partner
        if (pt != null) {
            item { KeyValue("Estimated distance", DistanceEstimator.format(conn.distanceM, unit)) }
            item { KeyValue("Signal", conn.rssi?.let { "$it dBm · ${DistanceEstimator.signalLabel(it)}" } ?: "--") }
            item { KeyValue("Their battery", conn.remoteBattery?.let { "$it%" } ?: "Not reported yet") }
            item { KeyValue("Paired since", fmt.format(Date(pt.pairedAt))) }
            item { KeyValue("Last connection", if (last > 0) fmt.format(Date(last)) else "Never") }
            item { KeyValue("Identity fingerprint", IdentityManager.fingerprintOf(pt.identityKey)) }
            item { Hint("Fingerprints let you double-check identity on both watches (Settings → Privacy).") }
        }
    }
}

// --------------------------------------------------------------------- image picking

private fun queryImages(ctx: android.content.Context): List<Uri> {
    val out = ArrayList<Uri>()
    val cols = arrayOf(MediaStore.Images.Media._ID)
    // Skip entries that are not really on the watch: pending writes, trashed items and
    // zero-byte cloud placeholders (these are what showed up as blank tiles).
    val where = "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.SIZE} > 0"
    ctx.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, where, null,
        "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (c.moveToNext() && out.size < 300) {
            out += Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol).toString())
        }
    }
    return out
}

/**
 * Intents that open the user's own gallery, best first: ACTION_PICK (Samsung Gallery and most
 * gallery apps), then GET_CONTENT, then the AndroidX photo picker. Only resolvable ones are kept.
 */
private fun galleryIntents(ctx: android.content.Context): List<Intent> {
    val pm = ctx.packageManager
    val candidates = listOf(
        Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType("image/*"),
        Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),
        Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"),
    )
    // Pin each intent to a specific on-watch gallery (Samsung Gallery first) and never Google
    // Photos, which shows the account's cloud library instead of pictures on the watch.
    val out = ArrayList<Intent>()
    for (base in candidates) {
        val handlers = runCatching { pm.queryIntentActivities(base, 0) }.getOrDefault(emptyList())
            .filter { it.activityInfo.packageName !in BlockedPickers }
            .sortedBy { if (it.activityInfo.packageName == SamsungGallery) 0 else 1 }
        val h = handlers.firstOrNull() ?: continue
        out += Intent(base).setClassName(h.activityInfo.packageName, h.activityInfo.name)
    }
    return out
}

private const val SamsungGallery = "com.samsung.android.gallery.watch"
private val BlockedPickers = setOf("com.google.android.apps.photos")

/**
 * Opens the person's gallery app automatically. If the watch has no gallery app, it falls back to
 * an in-app grid of photos stored on the watch (MediaStore).
 */
@Composable
fun ImagePickerScreen(nav: NavHostController, purpose: String) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
    }
    var uris by remember { mutableStateOf<List<Uri>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var pickError by remember { mutableStateOf<String?>(null) }
    fun choose(uri: Uri) {
        if (loading) return
        loading = true
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { Images.decodeUri(ctx, uri, 1024) }
            loading = false
            // Profile pictures and banners go to the crop & resize screen first.
            if (bmp != null) { UiShared.pickedBitmap = bmp; nav.navigate(if (purpose == "avatar" || purpose == "banner") "crop/$purpose" else "preview/$purpose") }
            else pickError = "That picture can't be opened on the watch. It may only be stored in the cloud, or in an unsupported format."
        }
    }
    // The user's own gallery app (Samsung Gallery on Galaxy Watch) is the primary picker.
    val galleryIntents = remember { galleryIntents(ctx) }
    var showGrid by rememberSaveable { mutableStateOf(galleryIntents.isEmpty()) }
    var autoLaunched by rememberSaveable { mutableStateOf(false) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.data?.let(::choose)
    }
    fun openGallery() {
        for (i in galleryIntents) {
            if (runCatching { galleryLauncher.launch(i) }.isSuccess) return
        }
        showGrid = true
    }
    LaunchedEffect(Unit) { if (!autoLaunched) { autoLaunched = true; openGallery() } }
    LaunchedEffect(granted, showGrid) {
        if (granted && showGrid) uris = withContext(Dispatchers.IO) { runCatching { queryImages(ctx) }.getOrDefault(emptyList()) }
    }

    WrScreen {
        item { SectionLabel(when (purpose) { "avatar" -> "Profile picture"; "banner" -> "Profile banner"; else -> "Send a picture" }) }
        if (loading) item { CircularProgressIndicator(Modifier.size(28.dp)) }
        pickError?.let { e -> item { Text(e, fontSize = 11.sp, color = p.danger, textAlign = TextAlign.Center) } }
        if (galleryIntents.isNotEmpty()) item {
            WrChip("Open my gallery", { openGallery() }, primary = true, icon = { Icon(painterResource(R.drawable.ic_image), null) })
        }
        if (!showGrid) item { WrChip("Browse watch photos here", { showGrid = true }) }
        if (showGrid && !granted) item {
            WrChip("Allow photo access", { permLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES) }, primary = galleryIntents.isEmpty())
        }
        val list = if (showGrid) uris else null
        if (granted && list != null && list.isEmpty()) item {
            Hint("No pictures stored on this watch. Save or sync photos to the watch gallery first.")
        }
        list?.chunked(3)?.forEachIndexed { i, row ->
            item(key = "row$i") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                    row.forEach { uri -> Thumb(uri) { choose(uri) } }
                }
            }
        }
    }
}

@Composable
private fun Thumb(uri: Uri, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var failed by remember(uri) { mutableStateOf(false) }
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        // Fast system thumbnail first; if that fails (unusual format, no cached thumbnail),
        // decode a small copy of the picture itself.
        val result = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(120, 120), null) }.getOrNull()
                ?: Images.decodeUri(ctx, uri, 120)
        }
        bmp = result
        failed = result == null
    }
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(p.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(46.dp)) }
            ?: if (failed) Text("?", color = p.textDim, fontSize = 16.sp) else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun ImagePreviewScreen(nav: NavHostController, purpose: String) {
    val g = WristRadarApp.graph
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val bmp = UiShared.pickedBitmap
    var busy by remember { mutableStateOf(false) }
    WrScreen {
        item { SectionLabel("Preview") }
        item {
            if (bmp != null) {
                val shape = if (purpose == "avatar") androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(12.dp)
                val img = if (purpose == "avatar") Images.squareCrop(bmp, 256) else bmp
                Image(
                    img.asImageBitmap(), "Selected picture", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(if (purpose == "avatar") 96.dp else 130.dp).clip(shape),
                )
            } else Hint("Nothing selected")
        }
        if (bmp != null) item {
            WrChip(
                if (purpose == "avatar") "Use as profile picture" else "Send",
                {
                    if (busy) return@WrChip
                    busy = true
                    scope.launch {
                        if (purpose == "avatar") {
                            withContext(Dispatchers.Default) { g.profile.setMyAvatar(bmp) }
                            g.engine.pushProfile()
                            UiShared.pickedBitmap = null
                            nav.popBackStack("profile", inclusive = false)
                        } else {
                            g.engine.messenger.sendImage(bmp)
                            UiShared.pickedBitmap = null
                            nav.popBackStack("chat", inclusive = false)
                        }
                    }
                },
                primary = true, enabled = !busy,
            )
        }
        item { WrChip("Cancel", { UiShared.pickedBitmap = null; nav.popBackStack() }) }
        if (purpose != "avatar") item {
            Text("Resized to ${Images.SEND_MAX_EDGE}px and encrypted before sending.", fontSize = 10.sp, color = p.textDim, textAlign = TextAlign.Center)
        }
    }
}
