package com.wristradar.core

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class Presence(
    val partnerTyping: Boolean = false,
    val partnerPhone: Boolean? = null,
    val myPhone: Boolean? = null,
)

/**
 * Is this watch currently connected to its paired phone? Read-only query of the Wear OS data
 * layer's connected nodes (no data is sent anywhere). Returns null if Play services is unavailable.
 */
object PhoneLink {
    suspend fun isPhoneConnected(ctx: Context): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes, 5, TimeUnit.SECONDS)
            nodes.any { it.isNearby }
        }.getOrNull()
    }
}
