package com.wristradar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.wristradar.R
import com.wristradar.WristRadarApp
import com.wristradar.core.Phase
import com.wristradar.notify.Notifier
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps the GATT server, advertising, scanning
 * and the encrypted session alive while WristRadar is in the background or the screen is off.
 * Runs only while a partner is paired. Wear OS can still stop it under memory pressure; it is
 * START_STICKY and the boot receiver restores it after restarts.
 */
class WristRadarService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        val g = WristRadarApp.graph
        if (!g.engine.hasPermissions()) { stopSelf(); return }
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, Notifier.SERVICE_ID, buildNotification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess
        if (!ok) { Log.w("WristRadar", "foreground start refused"); stopSelf(); return }
        g.engine.start()
        lifecycleScope.launch {
            combine(g.engine.connection, g.profile.partnerName) { c, n -> c.phase to (n ?: "partner") }
                .distinctUntilChanged()
                .collect { (phase, name) ->
                    val text = when (phase) {
                        Phase.CONNECTED -> "Connected to $name · encrypted"
                        Phase.BLUETOOTH_OFF -> "Bluetooth is off"
                        Phase.NOT_PAIRED -> "Not paired"
                        else -> "Waiting for $name"
                    }
                    g.notifier.updateService(buildNotification(text))
                }
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val g = WristRadarApp.graph
        val b = g.notifier.serviceBuilder(text)
        runCatching {
            OngoingActivity.Builder(applicationContext, Notifier.SERVICE_ID, b)
                .setStaticIcon(R.drawable.ic_stat_radar)
                .setTouchIntent(g.notifier.openAppIntent(false))
                .setStatus(Status.Builder().addTemplate(text).build())
                .build()
                .apply(applicationContext)
        }
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (WristRadarApp.graph.trust.partner.value == null) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        WristRadarApp.graph.engine.stopIfIdle()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            val g = WristRadarApp.graph
            if (g.trust.partner.value == null || !g.engine.hasPermissions()) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, WristRadarService::class.java)) }
                .onFailure { Log.w("WristRadar", "service start not allowed now: ${it.javaClass.simpleName}") }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WristRadarService::class.java)) }
    }
}

/** Restores background operation after reboot / app update (connectedDevice FGS is allowed from boot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WristRadarService.start(context)
        }
    }
}
