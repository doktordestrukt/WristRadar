package com.wristradar

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import com.wristradar.core.RadarEngine
import com.wristradar.crypto.IdentityManager
import com.wristradar.crypto.KeystoreVault
import com.wristradar.data.MessageRepository
import com.wristradar.data.ProfileStore
import com.wristradar.data.SettingsStore
import com.wristradar.data.TrustStore
import com.wristradar.data.db.AppDatabase
import com.wristradar.notify.Notifier
import com.wristradar.security.Blackbox

class WristRadarApp : Application() {
    lateinit var graph: AppGraph; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = AppGraph(this)
    }

    companion object {
        private lateinit var instance: WristRadarApp
        val graph: AppGraph get() = instance.graph
    }
}

/** Manual dependency container - keeps the app free of a DI framework. */
class AppGraph(app: Application) {
    val vault = KeystoreVault()
    val settings = SettingsStore(app)
    val identity = IdentityManager(app, vault)
    val trust = TrustStore(app, vault)
    val profile = ProfileStore(app, vault)
    private val db = AppDatabase.build(app)
    val messages = MessageRepository(app, db.messages(), vault)
    val lock = com.wristradar.data.AppLock(app, vault)
    val notifier = Notifier(app, settings)
    val battery = BatteryReader(app)
    val uiVisibility = UiVisibility()
    val blackbox = Blackbox(app, db.securityEvents(), settings, notifier, uiVisibility).also { bb ->
        messages.onIntegrityFailure = { bb.reportLocalTamper("A stored message could not be authenticated with the Keystore key") }
    }
    val engine = RadarEngine(app, this)
}

class UiVisibility {
    @Volatile var chatVisible: Boolean = false
    @Volatile var appVisible: Boolean = false
}

class BatteryReader(private val context: Context) {
    fun percent(): Int? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (v in 0..100) v else null
    }
}
