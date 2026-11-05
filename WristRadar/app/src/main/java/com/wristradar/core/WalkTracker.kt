package com.wristradar.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import com.wristradar.data.SettingsStore

/** Distance to the partner expressed as a walk: steps, units and time, learned from how you walk. */
data class WalkEstimate(
    val metres: Double,
    val steps: Int,
    val seconds: Int,
    val strideM: Double,
    val speedMs: Double,
    val learned: Boolean,
) {
    val km get() = metres / 1000.0
    val miles get() = metres / 1609.344
    val feet get() = metres * 3.28084
    val yards get() = metres * 1.09361
}

/**
 * Learns your walking speed from the watch's step detector: every steady walk of 20+ steps
 * updates your cadence (steps per second); speed = cadence x step length (from your height).
 * Only step timestamps are used, kept in memory; only the averaged speed is saved.
 */
class WalkTracker(private val ctx: Context, private val settings: SettingsStore) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var registered = false
    private var boutStart = 0L
    private var lastStep = 0L
    private var boutSteps = 0

    val hasSensor: Boolean get() = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (registered || !hasPermission) return
        val s = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        registered = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() { if (registered) sm?.unregisterListener(this); registered = false }

    override fun onSensorChanged(e: SensorEvent) {
        val t = System.currentTimeMillis()
        if (t - lastStep > 2_000) { closeBout(); boutStart = t; boutSteps = 0 }
        boutSteps++
        lastStep = t
    }

    private fun closeBout() {
        val dur = (lastStep - boutStart) / 1000.0
        if (boutSteps >= 20 && dur > 5) {
            val cadence = (boutSteps - 1) / dur
            if (cadence in 1.0..3.2) {   // walking, not running or noise
                val speed = (cadence * stride()).toFloat()
                val old = settings.current.walkSpeed
                settings.update { it.copy(walkSpeed = if (old <= 0f) speed else old * 0.7f + speed * 0.3f) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Step length from height (standard 0.415 x height for walking). */
    fun stride(): Double = settings.current.heightCm * 0.415 / 100.0

    fun estimate(metres: Double): WalkEstimate {
        val stride = stride()
        val learned = settings.current.walkSpeed > 0f
        val speed = if (learned) settings.current.walkSpeed.toDouble() else 1.8 * stride   // ~1.8 steps/s default
        return WalkEstimate(metres, (metres / stride).toInt().coerceAtLeast(0), (metres / speed).toInt(), stride, speed, learned)
    }
}
