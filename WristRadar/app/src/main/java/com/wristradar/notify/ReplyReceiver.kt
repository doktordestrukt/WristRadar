package com.wristradar.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.wristradar.WristRadarApp
import kotlinx.coroutines.launch

/** Inline reply from the message notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString()
        if (text.isNullOrBlank()) return
        val pending = goAsync()
        val g = WristRadarApp.graph
        g.engine.scope.launch {
            try {
                g.engine.messenger.sendText(text)
                g.messages.markAllRead()
                g.notifier.cancelMessages()
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val KEY = "wr_reply" }
}
