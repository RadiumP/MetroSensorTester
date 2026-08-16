package com.metrobooming.sensortester

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class SensorSnapshot(
    val accelRms: Double,
    val accelPeak: Double,
    val gyroRms: Double,
    val gyroPeak: Double,
    val gyroX: Float?,
    val gyroY: Float?,
    val gyroZ: Float?,
    val magnetX: Float?,
    val magnetY: Float?,
    val magnetZ: Float?,
    val magnetMagnitude: Double?,
    val pressureHpa: Float?,
)

data class SensorCapability(
    val label: String,
    val available: Boolean,
    val name: String = "",
    val vendor: String = "",
    val maxRateHz: Double? = null,
)

class SensorCollector(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAcceleration = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val pressure = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val lock = Any()
    private var accelSquareSum = 0.0
    private var accelCount = 0
    private var accelPeak = 0.0
    private var gyroSquareSum = 0.0
    private var gyroCount = 0
    private var gyroPeak = 0.0
    private var gyroX: Float? = null
    private var gyroY: Float? = null
    private var gyroZ: Float? = null
    private var magnetX: Float? = null
    private var magnetY: Float? = null
    private var magnetZ: Float? = null
    private var pressureHpa: Float? = null

    // Used only when TYPE_LINEAR_ACCELERATION is unavailable.
    private val gravity = FloatArray(3)

    fun capabilities(): List<SensorCapability> = listOf(
        capability("线性加速度", linearAcceleration ?: accelerometer),
        capability("陀螺仪", gyroscope),
        capability("磁力计", magnetometer),
        capability("气压计", pressure),
    )

    private fun capability(label: String, sensor: Sensor?): SensorCapability {
        return SensorCapability(
            label = label,
            available = sensor != null,
            name = sensor?.name.orEmpty(),
            vendor = sensor?.vendor.orEmpty(),
            maxRateHz = sensor?.minDelay?.takeIf { it > 0 }?.let { 1_000_000.0 / it },
        )
    }

    fun start() {
        val rate = SensorManager.SENSOR_DELAY_GAME
        manager.registerListener(this, linearAcceleration ?: accelerometer, rate)
        manager.registerListener(this, gyroscope, rate)
        manager.registerListener(this, magnetometer, rate)
        manager.registerListener(this, pressure, rate)
    }

    fun stop() = manager.unregisterListener(this)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(lock) {
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> recordAcceleration(
                    event.values[0], event.values[1], event.values[2]
                )

                Sensor.TYPE_ACCELEROMETER -> {
                    // Fallback high-pass filter to remove gravity.
                    val alpha = 0.8f
                    for (i in 0..2) gravity[i] = alpha * gravity[i] + (1f - alpha) * event.values[i]
                    recordAcceleration(
                        event.values[0] - gravity[0],
                        event.values[1] - gravity[1],
                        event.values[2] - gravity[2],
                    )
                }

                Sensor.TYPE_GYROSCOPE -> {
                    gyroX = event.values[0]
                    gyroY = event.values[1]
                    gyroZ = event.values[2]
                    val magnitude = sqrt(
                        event.values[0] * event.values[0] +
                            event.values[1] * event.values[1] +
                            event.values[2] * event.values[2]
                    ).toDouble()
                    gyroSquareSum += magnitude * magnitude
                    gyroCount++
                    gyroPeak = max(gyroPeak, magnitude)
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magnetX = event.values[0]
                    magnetY = event.values[1]
                    magnetZ = event.values[2]
                }

                Sensor.TYPE_PRESSURE -> pressureHpa = event.values[0]
            }
        }
    }

    private fun recordAcceleration(x: Float, y: Float, z: Float) {
        val magnitude = sqrt(x * x + y * y + z * z).toDouble()
        accelSquareSum += magnitude * magnitude
        accelCount++
        accelPeak = max(accelPeak, abs(magnitude))
    }

    fun takeSnapshot(): SensorSnapshot = synchronized(lock) {
        val mx = magnetX
        val my = magnetY
        val mz = magnetZ
        val result = SensorSnapshot(
            accelRms = if (accelCount > 0) sqrt(accelSquareSum / accelCount) else 0.0,
            accelPeak = accelPeak,
            gyroRms = if (gyroCount > 0) sqrt(gyroSquareSum / gyroCount) else 0.0,
            gyroPeak = gyroPeak,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            magnetX = mx,
            magnetY = my,
            magnetZ = mz,
            magnetMagnitude = if (mx != null && my != null && mz != null) {
                sqrt((mx * mx + my * my + mz * mz).toDouble())
            } else null,
            pressureHpa = pressureHpa,
        )
        accelSquareSum = 0.0
        accelCount = 0
        accelPeak = 0.0
        gyroSquareSum = 0.0
        gyroCount = 0
        gyroPeak = 0.0
        result
    }
}
