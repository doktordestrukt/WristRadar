package com.wristradar.glance

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.common.util.concurrent.ListenableFuture
import com.wristradar.WristRadarApp
import com.wristradar.core.Phase
import com.wristradar.ui.MainActivity
import com.wristradar.ui.screens.streakDays
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** What the tile and complication show about your friend. */
data class GlanceInfo(
    val name: String,
    val connected: Boolean,
    val distance: String?,
    val unread: Int,
    val streak: Int,
    val mood: String?,
)

object Glance {
    suspend fun info(): GlanceInfo {
        val g = WristRadarApp.graph
        val conn = g.engine.connection.value
        val connected = conn.phase == Phase.CONNECTED
        val msgs = withTimeoutOrNull(1500) { g.messages.messages.first() }.orEmpty()
        val unit = g.settings.current.unit
        val dist = conn.distanceM?.takeIf { connected }?.let {
            if (unit == com.wristradar.data.DistanceUnit.FEET) "${(it * 3.28084).toInt()} ft" else "${it.toInt()} m"
        }
        return GlanceInfo(
            name = g.profile.partnerName.value ?: "Friend",
            connected = connected,
            distance = dist,
            unread = g.messages.unreadCount(),
            streak = streakDays(msgs),
            mood = g.engine.extras.partnerMood.value,
        )
    }

    /** Ask the system to redraw the tile and complication (connection / message changes). */
    fun refresh(ctx: Context) {
        runCatching { TileService.getUpdater(ctx).requestUpdate(FriendTileService::class.java) }
        runCatching { ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, FriendComplicationService::class.java)).requestUpdateAll() }
    }
}

/** Swipeable tile: your friend's name, connection, distance, unread messages, streak and status. */
class FriendTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { c ->
            val i = runBlocking { Glance.info() }
            c.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RES)
                    .setFreshnessIntervalMillis(5 * 60_000L)
                    .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(i)))
                    .build()
            )
            "tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { c -> c.set(ResourceBuilders.Resources.Builder().setVersion(RES).build()); "res" }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder().setText(s).setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build()
            ).build()

    private fun spacer(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    private fun layout(i: GlanceInfo): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name).build()
            ).build()
        ).build()
        val col = LayoutElementBuilders.Column.Builder()
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(text("WRISTRADAR", 10f, 0xFFB266FF.toInt(), bold = true))
            .addContent(spacer(4f))
            .addContent(text(i.name, 18f, 0xFFFFFFFF.toInt(), bold = true))
            .addContent(text(if (i.connected) "● Connected" + (i.distance?.let { " · $it" } ?: "") else "○ Not nearby", 12f,
                if (i.connected) 0xFF39FF88.toInt() else 0xFFAAAAAA.toInt()))
        i.mood?.let { col.addContent(text(it, 12f, 0xFFDDDDDD.toInt())) }
        col.addContent(spacer(6f))
        col.addContent(text((if (i.unread > 0) "💬 ${i.unread} unread   " else "") + (if (i.streak > 0) "🔥 ${i.streak}" else ""), 13f, 0xFFFFFFFF.toInt()))
        return LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .addContent(col.build()).build()
    }

    companion object { private const val RES = "1" }
}

/** Watch-face complication: unread count, or distance, or streak. Tap opens the chat. */
class FriendComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type != ComplicationType.SHORT_TEXT) null else build("2 💬", "WristRadar")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val i = Glance.info()
        val main = when {
            i.unread > 0 -> "${i.unread} 💬"
            i.connected && i.distance != null -> i.distance
            i.streak > 0 -> "🔥${i.streak}"
            else -> if (i.connected) "●" else "○"
        }
        return build(main, i.name)
    }

    private fun build(text: String, title: String): ShortTextComplicationData {
        val tap = android.app.PendingIntent.getActivity(
            this, 9, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CHAT, true),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return ShortTextComplicationData.Builder(PlainComplicationText.Builder(text).build(), PlainComplicationText.Builder("WristRadar").build())
            .setTitle(PlainComplicationText.Builder(title.take(10)).build())
            .setTapAction(tap)
            .build()
    }
}
