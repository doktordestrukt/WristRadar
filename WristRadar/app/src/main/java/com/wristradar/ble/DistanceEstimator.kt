package com.wristradar.ble

import com.wristradar.data.DistanceUnit
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * RSSI -> rough distance using the log-distance path-loss model with exponential smoothing.
 * This is an ESTIMATE. Bodies, walls, wrist orientation and interference easily move it by 2x.
 */
class DistanceEstimator(
    // Wrist-worn watches read ~8-10 dB weaker than phones at the same distance (body absorption).
    private val rssiAt1m: Double = -71.0,
    private val pathLossExponent: Double = 2.0,
    private val alpha: Double = 0.25,
) {
    var smoothedRssi: Double? = null; private set

    fun add(rssi: Int): Double {
        val s = smoothedRssi
        val n = if (s == null) rssi.toDouble() else s + alpha * (rssi - s)
        smoothedRssi = n
        return n
    }

    fun reset() { smoothedRssi = null }

    /** [calibrated] = signal measured at ~1 m on the user's own wrists (Settings > Calibrate distance). */
    fun distanceMetres(calibrated: Int? = null): Double? = smoothedRssi?.let {
        val rssiAt1m = calibrated?.toDouble() ?: rssiAt1m
        10.0.pow((rssiAt1m - it) / (10 * pathLossExponent)).coerceIn(0.3, 60.0)
    }

    companion object {
        /** Coarse, honest rounding - no fake precision. */
        fun format(metres: Double?, unit: DistanceUnit): String {
            if (metres == null) return "--"
            return when (unit) {
                DistanceUnit.METRES -> when {
                    metres < 1.0 -> "< 1 m"
                    metres < 5.0 -> "≈ ${"%.1f".format((metres * 2).roundToInt() / 2.0)} m"
                    metres < 15.0 -> "≈ ${metres.roundToInt()} m"
                    else -> "≈ ${(metres / 5).roundToInt() * 5} m"
                }
                DistanceUnit.FEET -> {
                    val ft = metres * 3.28084
                    when {
                        ft < 3 -> "< 3 ft"
                        ft < 15 -> "≈ ${((ft / 2).roundToInt() * 2)} ft"
                        ft < 50 -> "≈ ${((ft / 5).roundToInt() * 5)} ft"
                        else -> "≈ ${((ft / 10).roundToInt() * 10)} ft"
                    }
                }
            }
        }

        fun signalLabel(rssi: Int?): String = when {
            rssi == null -> "No signal"
            rssi >= -60 -> "Excellent"
            rssi >= -70 -> "Good"
            rssi >= -80 -> "Fair"
            else -> "Weak"
        }

        /** 0..4 bars. */
        fun bars(rssi: Int?): Int = when {
            rssi == null -> 0
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -80 -> 2
            else -> 1
        }
    }
}
