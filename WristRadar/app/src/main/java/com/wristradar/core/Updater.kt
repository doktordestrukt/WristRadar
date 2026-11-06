package com.wristradar.core

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import com.wristradar.AppGraph
import com.wristradar.BuildConfig
import com.wristradar.WristRadarApp
import com.wristradar.crypto.Crypto
import com.wristradar.crypto.toHex
import com.wristradar.protocol.A
import com.wristradar.protocol.In
import com.wristradar.protocol.Out
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume

/** What the partner's app told us about itself (VERSION message). */
data class PartnerApp(val versionCode: Int, val label: String, val isWatch: Boolean)

data class UpdateState(
    /** Partner's app version, null = not connected yet / unknown. */
    val partner: PartnerApp? = null,
    /** Sending my APK to the partner: 0..100, null = not sending. */
    val sendingPct: Int? = null,
    /** Receiving an APK from the partner: 0..100, null = not receiving. */
    val receivingPct: Int? = null,
    val receivingLabel: String? = null,
    /** A verified, newer APK is downloaded and waiting to install. */
    val readyLabel: String? = null,
    /** Status line for the GitHub (Wi-Fi) updater. */
    val github: String = "Not checked yet",
    val githubBusy: Boolean = false,
    val note: String? = null,
)

/**
 * Two ways to update, both ending in the same verified install:
 *
 *  1. Bluetooth (offline): when the partner's watch runs an older WristRadar, this watch sends its
 *     own installed APK over the encrypted session (resumable). Automatic when they come back in
 *     range, or manual from Settings > Updates.
 *       VERSION(code, label, platform)  UPD_OFFER(code, size, sha256, label)
 *       UPD_REQ(offset | -1 = no)  UPD_CHUNK(offset, bytes)  UPD_ACK(next offset)
 *
 *  2. GitHub over Wi-Fi: every few hours (or "Check now") the latest GitHub release is checked;
 *     a newer .apk is downloaded over Wi-Fi only.
 *
 * Before installing, every APK must be WristRadar, newer than this one, and signed with the SAME
 * key as the installed app (Android enforces this again at install). Data is always kept.
 */
class Updater(private val ctx: Context, private val g: AppGraph, private val engine: RadarEngine) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val dir = File(ctx.filesDir, "update").apply { mkdirs() }
    private val btFile = File(dir, "bt.apk")
    private val btMeta = File(dir, "bt.meta")
    private val ghFile = File(dir, "github.apk")
    private val readyFile = File(dir, "ready.apk")

    private val acks = Channel<Int>(Channel.CONFLATED)
    private var sendJob: Job? = null
    private var loopJob: Job? = null
    @Volatile private var offeredThisSession = false
    @Volatile private var incoming: Offer? = null
    private val myCode = BuildConfig.VERSION_CODE

    private data class Offer(val code: Int, val size: Int, val sha: ByteArray, val label: String)

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (loopJob != null) return
        cleanupOld()
        if (readyFile.exists()) archiveInfo(readyFile)?.let { if (it.longVersionCode > myCode) _state.update { s -> s.copy(readyLabel = labelOf(it)) } else readyFile.delete() }
        loopJob = engine.scope.launch {
            delay(20_000)
            while (true) {
                val s = g.settings.current
                if (s.githubUpdates && System.currentTimeMillis() - g.settings.current.lastGithubCheck > GITHUB_EVERY_MS) checkGithub(manual = false)
                delay(30 * 60_000L)
            }
        }
    }

    private fun cleanupOld() {
        // An APK that is not newer than the running app was installed already - delete it.
        listOf(btFile, ghFile, readyFile).forEach { f ->
            if (f.exists()) { val info = archiveInfo(f); if (info != null && info.longVersionCode <= myCode) f.delete() }
        }
    }

    private val updPrefs = ctx.getSharedPreferences("wr_upd", Context.MODE_PRIVATE)

    /** When this watch last installed WristRadar (first install or update). */
    val myUpdatedAt: Long get() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    /** When we first saw the friend on their current version (0 = never seen). */
    val partnerUpdatedAt: Long get() = updPrefs.getLong("partner_seen_at", 0L)

    private fun notePartnerVersion(code: Int) {
        if (updPrefs.getInt("partner_code", -1) != code) {
            updPrefs.edit().putInt("partner_code", code).putLong("partner_seen_at", System.currentTimeMillis()).apply()
        }
    }

    @Volatile private var gotVersion = false
    @Volatile private var versionReplied = false

    /**
     * Called synchronously when a session comes up, BEFORE its inbox starts being read, so the
     * friend's VERSION can never be overwritten by a placeholder (the old "you're on V1" bug).
     */
    fun prepareSession() {
        offeredThisSession = false
        gotVersion = false
        versionReplied = false
        _state.update { it.copy(partner = null) }   // unknown until VERSION arrives
    }

    suspend fun onSessionUp(s: SecureSession) {
        sendVersion(s)
        // The first VERSION can be lost if the other watch is still finishing its handshake.
        // Resend a couple of times; only if nothing ever comes back is the friend really on V1.
        engine.scope.launch {
            for (wait in longArrayOf(3_000, 6_000, 10_000)) {
                delay(wait)
                if (gotVersion || engine.currentSession() !== s) return@launch
                sendVersion(s)
            }
            delay(5_000)
            if (!gotVersion && engine.currentSession() === s) _state.update { it.copy(partner = PartnerApp(1, "V1", true)) }   // V1 never sends VERSION
        }
    }

    private suspend fun sendVersion(s: SecureSession) {
        runCatching { s.send(A.VERSION, Out().i(myCode).str(BuildConfig.RELEASE_LABEL).b(PLATFORM_WATCH).build()) }
    }

    fun onSessionDown() {
        sendJob?.cancel(); sendJob = null
        _state.update { it.copy(partner = null, sendingPct = null) }
    }

    // ------------------------------------------------------------------ messages

    suspend fun handle(s: SecureSession, type: Byte, p: ByteArray) {
        val r = In(p)
        when (type) {
            A.VERSION -> {
                val code = r.i(); val label = r.str(20); val platform = if (r.hasMore()) r.b() else PLATFORM_WATCH
                gotVersion = true
                notePartnerVersion(code)
                _state.update { it.copy(partner = PartnerApp(code, label.ifBlank { "V$code" }, platform == PLATFORM_WATCH)) }
                // Answer once per session, so the friend learns our version even if our first one was lost.
                if (!versionReplied) { versionReplied = true; sendVersion(s) }
                if (platform == PLATFORM_WATCH && code in 2 until myCode && g.settings.current.autoSendUpdates) offer(s)
            }
            A.UPD_OFFER -> {
                val o = Offer(r.i(), r.i(), r.raw(32), r.str(20))
                if (o.code <= myCode || o.size !in 1..MAX_APK) { s.send(A.UPD_REQ, Out().i(-1).build()); return }
                incoming = o
                val resume = withContext(Dispatchers.IO) {
                    if (btMeta.exists() && btMeta.readText() == "${o.code}:${o.sha.toHex()}" && btFile.exists()) btFile.length().toInt().coerceAtMost(o.size)
                    else { btFile.delete(); btMeta.writeText("${o.code}:${o.sha.toHex()}"); 0 }
                }
                _state.update { it.copy(receivingPct = resume * 100 / o.size, receivingLabel = o.label) }
                s.send(A.UPD_REQ, Out().i(resume).build())
                if (resume >= o.size) finishIncoming(s)
            }
            A.UPD_REQ -> {
                val from = r.i()
                if (from < 0) { _state.update { it.copy(sendingPct = null, note = "Your friend's watch didn't need the update") }; return }
                startSending(s, from)
            }
            A.UPD_CHUNK -> {
                val o = incoming ?: return
                val off = r.i(); val data = r.bytes(CHUNK + 16) ?: return
                val len = withContext(Dispatchers.IO) {
                    val cur = btFile.length().toInt()
                    if (off == cur && cur + data.size <= o.size) RandomAccessFile(btFile, "rw").use { it.seek(cur.toLong()); it.write(data) }
                    btFile.length().toInt()
                }
                s.send(A.UPD_ACK, Out().i(len).build())
                val pct = (len.toLong() * 100 / o.size).toInt()
                if (pct != _state.value.receivingPct) _state.update { it.copy(receivingPct = pct) }
                if (len >= o.size) finishIncoming(s)
            }
            A.UPD_ACK -> acks.trySend(r.i())
        }
    }

    // ------------------------------------------------------------------ Bluetooth: sending

    /** Manual "Send update to friend" (also used automatically on reconnect). */
    fun sendToPartner(): String {
        val s = engine.currentSession() ?: return "Not connected to your friend"
        val pa = _state.value.partner ?: return "Checking your friend's version - try again in a few seconds"
        if (!pa.isWatch) return "Your friend is on the PC app"
        if (pa.versionCode < 2) return "Your friend is on V1 - install V2 on their watch by hand once"
        if (pa.versionCode >= myCode) return "Your friend already has ${pa.label}"
        offeredThisSession = false
        engine.scope.launch { offer(s) }
        return "Sending update…"
    }

    private suspend fun offer(s: SecureSession) {
        if (offeredThisSession || sendJob?.isActive == true) return
        offeredThisSession = true
        val apk = File(ctx.applicationInfo.sourceDir)
        val sha = withContext(Dispatchers.IO) { sha256(apk) }
        _state.update { it.copy(sendingPct = 0, note = null) }
        s.send(A.UPD_OFFER, Out().i(myCode).i(apk.length().toInt()).raw(sha).str(BuildConfig.RELEASE_LABEL).build())
    }

    private fun startSending(s: SecureSession, from: Int) {
        sendJob?.cancel()
        sendJob = engine.scope.launch {
            val apk = File(ctx.applicationInfo.sourceDir)
            val size = apk.length().toInt()
            var acked = from
            var sent = from
            var stalls = 0
            while (acks.tryReceive().isSuccess) { /* drop stale */ }
            withContext(Dispatchers.IO) { RandomAccessFile(apk, "r") }.use { raf ->
                while (acked < size && !s.link.closed) {
                    // Chat, pictures and calls always go first: pause the update while they run.
                    if (engine.calls.state.value.live || engine.messenger.busy) { delay(1000); continue }
                    while (sent < size && sent - acked < CHUNK * WINDOW) {
                        val n = minOf(CHUNK, size - sent)
                        val buf = ByteArray(n)
                        withContext(Dispatchers.IO) { raf.seek(sent.toLong()); raf.readFully(buf) }
                        if (!s.send(A.UPD_CHUNK, Out().i(sent).bytes(buf).build())) return@launch
                        sent += n
                    }
                    val a = withTimeoutOrNull(10_000) { acks.receive() }
                    if (a == null) { if (++stalls > 4) break; sent = acked; continue }
                    stalls = 0
                    if (a < acked || a > size) continue
                    acked = a
                    if (sent < acked) sent = acked
                    val pct = (acked.toLong() * 100 / size).toInt()
                    if (pct != _state.value.sendingPct) _state.update { it.copy(sendingPct = pct) }
                }
            }
            _state.update {
                it.copy(sendingPct = null, note = if (acked >= size) "Update sent - your friend's watch will install it" else "Update paused - it continues next time you connect")
            }
            if (acked < size) offeredThisSession = false
        }
    }

    // ------------------------------------------------------------------ Bluetooth: receiving

    private suspend fun finishIncoming(s: SecureSession) {
        val o = incoming ?: return
        incoming = null
        val ok = withContext(Dispatchers.IO) { btFile.length().toInt() == o.size && Crypto.ctEquals(sha256(btFile), o.sha) }
        if (!ok) {
            withContext(Dispatchers.IO) { btFile.delete(); btMeta.delete() }
            _state.update { it.copy(receivingPct = null, note = "Update was damaged in transfer - it will be sent again") }
            return
        }
        _state.update { it.copy(receivingPct = null) }
        promote(btFile, "your friend")
        btMeta.delete()
    }

    // ------------------------------------------------------------------ GitHub over Wi-Fi

    fun checkNow() { engine.scope.launch { checkGithub(manual = true) } }

    private suspend fun checkGithub(manual: Boolean) {
        if (_state.value.githubBusy) return
        _state.update { it.copy(githubBusy = true, github = "Looking for Wi-Fi…") }
        try {
            val req = wifiNetwork()
            wifiRelease = req?.second
            val net = req?.first
            if (net == null) { _state.update { it.copy(github = "No Wi-Fi - will try again later") }; return }
            _state.update { it.copy(github = "Checking GitHub…") }
            val release = withContext(Dispatchers.IO) { latestRelease(net) }
            g.settings.update { it.copy(lastGithubCheck = System.currentTimeMillis()) }
            if (release == null) { _state.update { it.copy(github = "No releases on GitHub yet") }; return }
            if (release.code <= myCode) { _state.update { it.copy(github = "Up to date (${BuildConfig.RELEASE_LABEL})") }; return }
            _state.update { it.copy(github = "Downloading ${release.label}… 0%") }
            val ok = withContext(Dispatchers.IO) { download(net, release.url, ghFile) { pct -> _state.update { it.copy(github = "Downloading ${release.label}… $pct%") } } }
            if (!ok) { _state.update { it.copy(github = "Download failed - will retry") }; return }
            if (!promote(ghFile, "GitHub")) _state.update { it.copy(github = "Downloaded file was not a valid WristRadar update") }
            else _state.update { it.copy(github = "${release.label} downloaded") }
        } catch (e: Exception) {
            Log.w(TAG, "github update check failed: ${e.javaClass.simpleName}")
            _state.update { it.copy(github = if (manual) "Couldn't reach GitHub" else "Couldn't reach GitHub - will retry") }
        } finally {
            wifiRelease?.invoke(); wifiRelease = null
            _state.update { it.copy(githubBusy = false) }
        }
    }

    private data class Release(val code: Int, val label: String, val url: String)

    private fun latestRelease(net: Network): Release? {
        val c = net.openConnection(URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")) as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "WristRadar-Updater")
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val j = org.json.JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name")
            val code = Regex("\\d+").find(tag)?.value?.toIntOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true) && a.optLong("size") in 1..MAX_APK.toLong()) {
                    val url = a.optString("browser_download_url")
                    if (url.startsWith("https://github.com/$GITHUB_REPO/")) return Release(code, "V$code", url)
                }
            }
            return null
        } finally { c.disconnect() }
    }

    private fun download(net: Network, url: String, out: File, progress: (Int) -> Unit): Boolean {
        var u = URL(url)
        repeat(5) {
            val c = net.openConnection(u) as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "WristRadar-Updater")
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            try {
                when (c.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val next = URL(u, c.getHeaderField("Location") ?: return false)
                        if (next.protocol != "https") return false
                        u = next; return@repeat
                    }
                    200 -> {
                        val total = c.contentLengthLong
                        if (total > MAX_APK) return false
                        var done = 0L; var last = -1
                        out.outputStream().use { o ->
                            c.inputStream.use { i ->
                                val buf = ByteArray(32 * 1024)
                                while (true) {
                                    val n = i.read(buf); if (n < 0) break
                                    o.write(buf, 0, n); done += n
                                    if (done > MAX_APK) return false
                                    if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct / 5 != last / 5) { last = pct; progress(pct) } }
                                }
                            }
                        }
                        return true
                    }
                    else -> return false
                }
            } finally { c.disconnect() }
        }
        return false
    }

    /** Wear OS normally routes traffic through the phone; ask for a real Wi-Fi network instead. */
    @Volatile private var wifiRelease: (() -> Unit)? = null

    /** Returns the Wi-Fi network plus a function that releases it (so Wi-Fi isn't kept awake). */
    private suspend fun wifiNetwork(): Pair<Network, () -> Unit>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile var net: Network? = null
            var waiter: ((Network?) -> Unit)? = null
            override fun onAvailable(network: Network) { net = network; waiter?.invoke(network) }
        }
        val release = { runCatching { cm.unregisterNetworkCallback(cb) }; Unit }
        if (runCatching { cm.requestNetwork(req, cb) }.isFailure) return null
        val net = withTimeoutOrNull(25_000) {
            cb.net ?: suspendCancellableCoroutine<Network?> { cont ->
                cb.waiter = { n -> if (cont.isActive) runCatching { cont.resume(n) } }
                cb.net?.let { if (cont.isActive) runCatching { cont.resume(it) } }   // arrived before the waiter was set
            }
        }
        if (net == null) { release(); return null }
        return net to release
    }

    // ------------------------------------------------------------------ verify + install

    /** Checks the APK is a newer WristRadar signed with our key, then installs (or asks). */
    private suspend fun promote(file: File, source: String): Boolean {
        val info = withContext(Dispatchers.IO) { archiveInfo(file) }
        if (info == null || info.packageName != ctx.packageName || info.longVersionCode <= myCode || !sameSigner(info)) {
            file.delete()
            Log.w(TAG, "rejected update from $source")
            return false
        }
        withContext(Dispatchers.IO) { readyFile.delete(); file.renameTo(readyFile) }
        val label = labelOf(info)
        _state.update { it.copy(readyLabel = label) }
        if (g.settings.current.autoInstallUpdates) installWhenIdle()
        else g.notifier.showInfo("WristRadar $label is ready", "Open Settings › Updates to install it")
        return true
    }

    private fun installWhenIdle() {
        engine.scope.launch {
            // Never interrupt a call or a picture transfer.
            while (engine.calls.state.value.live || engine.messenger.busy) delay(5_000)
            install()
        }
    }

    /** Installs the waiting update. Keeps all data (it is an in-place upgrade). */
    fun install(): String {
        if (!readyFile.exists()) return "No update waiting"
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            g.notifier.showAction("Allow WristRadar to update itself", "Tap, then switch on \"Allow\"", i)
            if (g.uiVisibility.appVisible) runCatching { ctx.startActivity(i) }
            return "Allow WristRadar to install updates, then tap Install again"
        }
        return try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                session.openWrite("base.apk", 0, readyFile.length()).use { out ->
                    readyFile.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val cb = PendingIntent.getBroadcast(
                    ctx, 77, Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(cb.intentSender)
            }
            "Installing…"
        } catch (e: Exception) {
            Log.w(TAG, "install failed: ${e.javaClass.simpleName}")
            "Install failed - try again"
        }
    }

    fun onInstallResult(status: Int, confirm: Intent?, message: String?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                g.notifier.showAction("WristRadar ${_state.value.readyLabel ?: "update"} is ready", "Tap to install - your chats are kept", confirm)
                if (g.uiVisibility.appVisible) runCatching { ctx.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // the app restarts on the new version
            else -> _state.update { it.copy(note = "Update not installed" + (message?.let { m -> ": $m" } ?: "")) }
        }
    }

    // ------------------------------------------------------------------ helpers

    @Suppress("DEPRECATION")
    private fun archiveInfo(f: File): PackageInfo? = runCatching {
        ctx.packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
    }.getOrNull()

    private fun sameSigner(info: PackageInfo): Boolean = runCatching {
        val mine = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
        val theirs = info.signingInfo!!.apkContentsSigners
        mine.isNotEmpty() && mine.map { it.toByteArray().toHex() }.toSet() == theirs.map { it.toByteArray().toHex() }.toSet()
    }.getOrDefault(false)

    private fun labelOf(info: PackageInfo) = "V${info.longVersionCode}"

    private fun sha256(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest()
    }

    companion object {
        private const val TAG = "WristRadar"
        const val GITHUB_REPO = "doktordestrukt/WristRadar"
        const val GITHUB_EVERY_MS = 6 * 60 * 60_000L
        const val MAX_APK = 64 * 1024 * 1024
        const val CHUNK = 3000
        const val WINDOW = 6
        const val PLATFORM_WATCH: Byte = 0
        const val PLATFORM_PC: Byte = 1
    }
}

/** Receives the result of a PackageInstaller commit. */
class InstallResultReceiver : BroadcastReceiver() {
    @SuppressLint("UnsafeIntentLaunch")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        WristRadarApp.graph.engine.updater.onInstallResult(status, confirm, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
