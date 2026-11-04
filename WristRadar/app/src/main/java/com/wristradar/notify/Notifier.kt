package com.wristradar.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.wristradar.R
import com.wristradar.data.SettingsStore
import com.wristradar.ui.MainActivity

class Notifier(private val ctx: Context, private val settings: SettingsStore) {
    private val nm = NotificationManagerCompat.from(ctx)

    init {
        val sys = ctx.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(
            NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_MSG, ctx.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(false) } // vibration is driven by the in-app setting
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_BLACKBOX, "Security warnings & calls", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(false); setSound(null, null); setBypassDnd(false) }
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_MSG_QUIET, ctx.getString(R.string.channel_messages_quiet), NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(false); setSound(null, null) }
        )
    }

    private fun canPost() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    fun openAppIntent(openChat: Boolean): PendingIntent = PendingIntent.getActivity(
        ctx, if (openChat) 11 else 10,
        Intent(ctx, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_CHAT, openChat)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun serviceBuilder(text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("WristRadar")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent(false))

    /** Single, updatable notification for incoming messages (no duplicates). */
    @SuppressLint("MissingPermission")
    fun showMessage(sender: String, preview: String, unread: Int) {
        val s = settings.current
        if (!s.notifications) return
        if (canPost()) {
            val reply = NotificationCompat.Action.Builder(
                R.drawable.ic_keyboard, "Reply",
                PendingIntent.getBroadcast(
                    ctx, 20, Intent(ctx, ReplyReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY).setLabel("Reply to $sender").build())
                .setAllowGeneratedReplies(true)
                .build()
            val body = if (s.previews) preview else "New message"
            val b = NotificationCompat.Builder(ctx, if (s.sound) CH_MSG else CH_MSG_QUIET)
                .setSmallIcon(R.drawable.ic_stat_radar)
                .setContentTitle(sender)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setNumber(unread)
                .setSubText(if (unread > 1) "$unread unread" else null)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(true))
                .addAction(reply)
                .setVisibility(if (s.previews) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            if (!s.previews) {
                b.setPublicVersion(
                    NotificationCompat.Builder(ctx, CH_MSG_QUIET).setSmallIcon(R.drawable.ic_stat_radar)
                        .setContentTitle("WristRadar").setContentText("New message").build()
                )
            }
            nm.notify(MSG_ID, b.build())
        }
        vibrate(longArrayOf(0, 90, 70, 140))
    }

    @SuppressLint("MissingPermission")
    fun showInfo(title: String, text: String) {
        if (!canPost()) return
        nm.notify(
            INFO_ID,
            NotificationCompat.Builder(ctx, CH_MSG_QUIET).setSmallIcon(R.drawable.ic_stat_radar)
                .setContentTitle(title).setContentText(text).setAutoCancel(true)
                .setContentIntent(openAppIntent(false)).build()
        )
    }

    /** Subtle haptic when a message lands while the chat is on screen. */
    fun tick() = vibrate(longArrayOf(0, 40))

    private fun vibrate(pattern: LongArray) {
        if (!settings.current.vibration || !settings.current.notifications) return
        val vm = ctx.getSystemService(VibratorManager::class.java) ?: return
        runCatching { vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    /** Background alert for a genuine Blackbox event. Opens the app, which shows the skull screen. */
    @SuppressLint("MissingPermission")
    fun showBlackbox(event: String, device: String) {
        if (!canPost()) return
        val pi = PendingIntent.getActivity(
            ctx, 12,
            Intent(ctx, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_BLACKBOX, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            BLACKBOX_ID,
            NotificationCompat.Builder(ctx, CH_BLACKBOX)
                .setSmallIcon(R.drawable.ic_stat_radar)
                .setContentTitle("⚠ Security warning")
                .setContentText("$event · $device")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$event\nDevice: $device"))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelBlackbox() = nm.cancel(BLACKBOX_ID)

    /** Incoming call: full-screen where Android allows it, otherwise a heads-up notification. */
    @SuppressLint("MissingPermission")
    fun showIncomingCall(name: String) {
        if (!canPost()) return
        val pi = PendingIntent.getActivity(
            ctx, 13,
            Intent(ctx, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_CALL, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            CALL_ID,
            NotificationCompat.Builder(ctx, CH_BLACKBOX)
                .setSmallIcon(R.drawable.ic_stat_radar)
                .setContentTitle(name)
                .setContentText("Incoming WristRadar call")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build(),
        )
    }

    fun cancelCall() = nm.cancel(CALL_ID)

    fun cancelMessages() = nm.cancel(MSG_ID)

    @SuppressLint("MissingPermission")
    fun updateService(n: Notification) {
        if (canPost()) nm.notify(SERVICE_ID, n)
    }

    companion object {
        const val CH_SERVICE = "wr_service"
        const val CH_MSG = "wr_messages"
        const val CH_MSG_QUIET = "wr_messages_quiet"
        const val SERVICE_ID = 1001
        const val MSG_ID = 2001
        const val INFO_ID = 2002
        const val BLACKBOX_ID = 2666
        const val CALL_ID = 2700
        const val CH_BLACKBOX = "wr_blackbox"
    }
}
