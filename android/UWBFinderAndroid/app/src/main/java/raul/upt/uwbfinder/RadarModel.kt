package raul.upt.uwbfinder

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What the UI draws. Colour channels are on a 0-255 scale, as in the iOS
 * RadarModel; the UI converts them to a Compose Color.
 */
data class RadarUiState(
    val displayedDistanceM: Double? = null,
    val colorR: Double = 0.9 * 255.0,   // neutral yellow until a distance arrives
    val colorG: Double = 0.75 * 255.0,
    val colorB: Double = 0.24 * 255.0,
    val displayedYouAngle: Double = 0.0,
    val displayedTargetAngle: Double = 0.0,
    val targetVisible: Boolean = false,
    val confidencePct: Int = 0,
)

/**
 * Port of RadarModel.swift. Same fusion approach: whole-disc colour driven by
 * absolute distance, plus a continuously updated "which heading tends to
 * reduce distance" vector estimate for the target-direction arrow.
 *
 * All methods must be called on the main thread.
 */
class RadarModel {

    companion object {
        // ----- Config (identical to the iOS app) -----
        const val NEAR_M = 0.3              // fully green at/below this
        const val FAR_M = 4.0               // fully red at/above this
        private const val DIST_SMOOTH = 0.25     // EMA factor for displayed distance
        private const val HEADING_SMOOTH = 0.20  // EMA factor for displayed phone heading
        private const val ARROW_LERP = 0.12      // per-frame interpolation for arrows
        private const val NBINS = 36             // 10 deg per heading bin
        private const val TICK_HZ = 60.0         // animation rate, as on iOS
    }

    // ----- Published (drives the UI) -----
    private val _state = MutableStateFlow(RadarUiState())
    val state: StateFlow<RadarUiState> = _state.asStateFlow()

    private var displayedDistanceM: Double? = null
    private var displayedYouAngle = 0.0
    private var displayedTargetAngle = 0.0
    private var targetVisible = false
    private var confidencePct = 0

    // ----- Internal state -----
    private var rawDistanceM: Double? = null
    private var smoothDistanceM: Double? = null
    private var rawHeadingDeg: Double? = null
    private var smoothHeadingDeg: Double? = null

    private var lastFusionDistanceM: Double? = null
    private var lastFusionHeadingDeg: Double? = null
    private val bins = DoubleArray(NBINS)

    private var displayedColorRGB = doubleArrayOf(230.0, 190.0, 60.0)
    private var colorPublished = false

    private val colorRed = doubleArrayOf(225.0, 72.0, 77.0)
    private val colorYellow = doubleArrayOf(230.0, 190.0, 60.0)
    private val colorGreen = doubleArrayOf(62.0, 207.0, 142.0)

    // ----- 60 Hz ticker (the Android equivalent of the iOS Timer) -----
    private var handler: Handler? = null
    private var running = false
    private var startUptimeMs = 0L
    private var tickCount = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            scheduleNextTick()
        }
    }

    fun start() {
        stop()
        // Created lazily so the fusion logic can be unit-tested off-device.
        if (handler == null) handler = Handler(Looper.getMainLooper())
        running = true
        startUptimeMs = SystemClock.uptimeMillis()
        tickCount = 0L
        scheduleNextTick()
    }

    fun stop() {
        running = false
        handler?.removeCallbacks(tickRunnable)
    }

    private fun scheduleNextTick() {
        val h = handler ?: return
        tickCount += 1
        val target = startUptimeMs + Math.round(tickCount * 1000.0 / TICK_HZ)
        h.postAtTime(tickRunnable, max(target, SystemClock.uptimeMillis()))
    }

    /** Call whenever a new BLE distance reading arrives (metres). */
    fun onDistance(distanceM: Double) {
        rawDistanceM = distanceM
        val s = smoothDistanceM
        smoothDistanceM = if (s != null) lerp(s, distanceM, DIST_SMOOTH) else distanceM
        val h = rawHeadingDeg
        if (h != null) updateFusion(distanceM, h)
    }

    /** Call whenever a new compass heading arrives (degrees, 0 = north). */
    fun onHeading(headingDeg: Double) {
        rawHeadingDeg = headingDeg
        val s = smoothHeadingDeg
        smoothHeadingDeg = if (s != null) lerpAngle(s, headingDeg, HEADING_SMOOTH) else headingDeg
        val d = rawDistanceM
        if (d != null) updateFusion(d, headingDeg)
    }

    // ----- Fusion -----
    private fun headingToBin(h: Double): Int {
        var b = roundHalfAwayFromZero(h / (360.0 / NBINS)).toInt() % NBINS
        if (b < 0) b += NBINS
        return b
    }

    private fun updateFusion(distanceM: Double, headingDeg: Double) {
        val lastD = lastFusionDistanceM
        val lastH = lastFusionHeadingDeg
        if (lastD != null && lastH != null) {
            val deltaD = distanceM - lastD
            val deltaH = abs((headingDeg - lastH + 540.0).rem(360.0) - 180.0)
            if (abs(deltaD) * 100.0 > 4.0 && deltaH < 60.0) {
                val score = clamp(-deltaD / 0.4, -1.0, 1.0)
                val b = headingToBin(headingDeg)
                bins[b] = bins[b] * 0.6 + score * 0.4
                lastFusionDistanceM = distanceM
                lastFusionHeadingDeg = headingDeg
            } else if (abs(deltaD) * 100.0 > 4.0) {
                lastFusionDistanceM = distanceM
                lastFusionHeadingDeg = headingDeg
            }
        } else {
            lastFusionDistanceM = distanceM
            lastFusionHeadingDeg = headingDeg
        }
        for (i in 0 until NBINS) bins[i] *= 0.996
    }

    /** Returns (angle in degrees, confidence 0..1), or null while calibrating. */
    private fun targetDirection(): Pair<Double, Double>? {
        var sx = 0.0
        var sy = 0.0
        var mag = 0.0
        for (i in 0 until NBINS) {
            val w = max(0.0, bins[i])
            if (w <= 0.0) continue
            val ang = i * (360.0 / NBINS) * Math.PI / 180.0
            sx += cos(ang) * w
            sy += sin(ang) * w
            mag += w
        }
        if (mag < 0.15) return null
        val angle = (atan2(sy, sx) * 180.0 / Math.PI + 360.0).rem(360.0)
        val confidence = clamp(mag / (NBINS * 0.3), 0.0, 1.0)
        return Pair(angle, confidence)
    }

    private fun colorFor(distanceM: Double): DoubleArray {
        var t = (distanceM - NEAR_M) / (FAR_M - NEAR_M)
        t = clamp(t, 0.0, 1.0)
        return if (t <= 0.5) {
            val u = t / 0.5
            doubleArrayOf(
                lerp(colorGreen[0], colorYellow[0], u),
                lerp(colorGreen[1], colorYellow[1], u),
                lerp(colorGreen[2], colorYellow[2], u),
            )
        } else {
            val u = (t - 0.5) / 0.5
            doubleArrayOf(
                lerp(colorYellow[0], colorRed[0], u),
                lerp(colorYellow[1], colorRed[1], u),
                lerp(colorYellow[2], colorRed[2], u),
            )
        }
    }

    // ----- Per-frame animation -----
    /** One animation step. Driven by the internal 60 Hz ticker; public for tests. */
    fun tick() {
        val h = smoothHeadingDeg
        if (h != null) {
            displayedYouAngle = lerpAngle(displayedYouAngle, h, ARROW_LERP)
        }
        val t = targetDirection()
        if (t != null) {
            displayedTargetAngle = lerpAngle(displayedTargetAngle, t.first, ARROW_LERP)
            targetVisible = true
            confidencePct = (t.second * 100.0).toInt()
        } else {
            targetVisible = false
            confidencePct = 0
        }
        val d = smoothDistanceM
        if (d != null) {
            val target = colorFor(d)
            displayedColorRGB = doubleArrayOf(
                lerp(displayedColorRGB[0], target[0], 0.15),
                lerp(displayedColorRGB[1], target[1], 0.15),
                lerp(displayedColorRGB[2], target[2], 0.15),
            )
            colorPublished = true
            displayedDistanceM = d
        }
        val initial = RadarUiState()
        _state.value = RadarUiState(
            displayedDistanceM = displayedDistanceM,
            colorR = if (colorPublished) displayedColorRGB[0] else initial.colorR,
            colorG = if (colorPublished) displayedColorRGB[1] else initial.colorG,
            colorB = if (colorPublished) displayedColorRGB[2] else initial.colorB,
            displayedYouAngle = displayedYouAngle,
            displayedTargetAngle = displayedTargetAngle,
            targetVisible = targetVisible,
            confidencePct = confidencePct,
        )
    }
}

// ----- Small math helpers -----
internal fun clamp(v: Double, a: Double, b: Double): Double = max(a, min(b, v))

internal fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

internal fun lerpAngle(a: Double, b: Double, t: Double): Double {
    val diff = (b - a + 540.0).rem(360.0) - 180.0
    return (a + diff * t + 360.0).rem(360.0)
}

/** Swift's Double.rounded(): ties go away from zero (kotlin.math.round ties go to even). */
internal fun roundHalfAwayFromZero(x: Double): Double =
    if (x >= 0.0) floor(x + 0.5) else -floor(-x + 0.5)
