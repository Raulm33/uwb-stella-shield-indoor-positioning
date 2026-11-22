package raul.upt.uwbfinder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Provides the phone's compass heading (0-360, 0 = magnetic north) from the
 * rotation-vector sensor. Port of HeadingManager.swift.
 *
 * The heading is that of the top edge of the phone, so the activity is locked
 * to portrait. No runtime permission is needed for this sensor.
 */
class HeadingManager(context: Context) : SensorEventListener {

    companion object {
        /** Same as the default CLLocationManager.headingFilter on iOS (1 degree). */
        private const val HEADING_FILTER_DEG = 1.0
    }

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _headingDeg = MutableStateFlow<Double?>(null)
    val headingDeg: StateFlow<Double?> = _headingDeg.asStateFlow()

    /** False on devices without a rotation-vector sensor; no heading is produced then. */
    val isAvailable: Boolean
        get() = rotationSensor != null

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastReportedDeg: Double? = null
    private var registered = false

    fun start() {
        val sensor = rotationSensor ?: return
        if (registered) return
        // Without a Handler argument, events are delivered on the main thread.
        registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        // orientation[0] is the azimuth in radians, -pi..pi, 0 = magnetic north.
        val heading = (Math.toDegrees(orientation[0].toDouble()) + 360.0).rem(360.0)

        val last = lastReportedDeg
        if (last != null) {
            val delta = abs((heading - last + 540.0).rem(360.0) - 180.0)
            if (delta < HEADING_FILTER_DEG) return
        }
        lastReportedDeg = heading
        _headingDeg.value = heading
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used: unreliable samples are dropped in onSensorChanged.
    }
}
