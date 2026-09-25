package com.metrobooming.sensortester

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class SensorSnapshot(
    val accelRms: Double,
    val accelPeak: Double,
    // Most recent linear (gravity removed) acceleration sample in device
    // coordinates. accelRms above is a magnitude, so it cannot tell a train
    // accelerating forwards from a phone being shaken; these keep the
    // direction the magnitude throws away.
    val accelX: Float?,
    val accelY: Float?,
    val accelZ: Float?,
    // The gravity vector the device frame is tilted against, logged so an
    // offline pass can re-derive the horizontal plane without replaying the
    // rotation vector.
    val gravityX: Float?,
    val gravityY: Float?,
    val gravityZ: Float?,
    // Tick means of the linear acceleration rotated into the world frame
    // (East / North / Up). The mean, not the RMS, is the point: handling a
    // phone produces a roughly zero mean because the hand comes back, while
    // a train accelerating along the track produces a sustained bias that
    // survives averaging. Null when the rotation vector was unavailable, so
    // no world frame could be established.
    val accelWorldEastMean: Double?,
    val accelWorldNorthMean: Double?,
    val accelWorldUpMean: Double?,
    val accelWorldSamples: Int,
    val gyroRms: Double,
    val gyroPeak: Double,
    val gyroX: Float?,
    val gyroY: Float?,
    val gyroZ: Float?,
    val magnetX: Float?,
    val magnetY: Float?,
    val magnetZ: Float?,
    val magnetMagnitude: Double?,
    // Accuracy class of the magnetometer specifically, unlike compassAccuracy
    // below, which falls back to the rotation vector's when the magnetometer
    // has not reported one. Kept separate because InferenceEngine logs the
    // magnet channel's own hardware accuracy alongside its usability verdict.
    val magnetAccuracy: Int?,
    val pressureHpa: Float?,
    val compassActive: Boolean,
    val compassSource: String,
    val compassMagneticAzimuthDeg: Double?,
    val compassTrueAzimuthDeg: Double?,
    val compassHeadingDeg: Double?,
    val compassDirection: String,
    val compassReference: String,
    val compassAccuracy: Int?,
    val compassAccuracyLabel: String,
    val compassCalibration: String,
    val magneticDisturbance: String,
    val magneticDeclinationDeg: Float?,
    val compassLocationAgeMs: Long?,
)

data class SensorCapability(
    val label: String,
    val available: Boolean,
    val name: String = "",
    val vendor: String = "",
    val maxRateHz: Double? = null,
)

class SensorCollector(private val context: Context) : SensorEventListener {
    companion object {
        private const val DECLINATION_REFRESH_MS = 10 * 60 * 1_000L
        private const val MAX_LOCATION_AGE_MS = 7 * 24 * 60 * 60 * 1_000L
    }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAcceleration = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val pressure = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val rotationVector = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

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
    private var rawAcceleration: FloatArray? = null
    private var rawMagnetic: FloatArray? = null
    private var accelX: Float? = null
    private var accelY: Float? = null
    private var accelZ: Float? = null
    // Device to world rotation, kept from the last rotation vector event so
    // recordAcceleration can project into the world frame as samples arrive
    // rather than only at snapshot time.
    private var deviceToWorld: FloatArray? = null
    private var accelWorldEastSum = 0.0
    private var accelWorldNorthSum = 0.0
    private var accelWorldUpSum = 0.0
    private var accelWorldCount = 0
    private var compassMagneticAzimuthDeg: Double? = null
    private var compassSource = "不可用"
    private var magnetAccuracy: Int? = null
    private var rotationAccuracy: Int? = null
    private var magneticDeclinationDeg: Float? = null
    private var declinationLocationTimeMs: Long? = null
    private var lastDeclinationRefreshAt = 0L

    // Low passed accelerometer, i.e. the gravity direction. Feeds the
    // fallback linear acceleration when TYPE_LINEAR_ACCELERATION is missing,
    // and is logged every tick either way.
    private val gravity = FloatArray(3)

    fun capabilities(): List<SensorCapability> = listOf(
        capability("线性加速度", linearAcceleration ?: accelerometer),
        capability("陀螺仪", gyroscope),
        capability("磁力计", magnetometer),
        capability("旋转矢量", rotationVector),
        capability("罗盘", rotationVector ?: if (accelerometer != null) magnetometer else null),
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
        refreshDeclinationIfNeeded(force = true)
        val rate = SensorManager.SENSOR_DELAY_GAME
        manager.registerListener(this, linearAcceleration, rate)
        manager.registerListener(this, accelerometer, rate)
        manager.registerListener(this, gyroscope, rate)
        manager.registerListener(this, magnetometer, rate)
        manager.registerListener(this, pressure, rate)
        manager.registerListener(this, rotationVector, rate)
    }

    fun stop() = manager.unregisterListener(this)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        synchronized(lock) {
            when (sensor?.type) {
                Sensor.TYPE_MAGNETIC_FIELD -> magnetAccuracy = accuracy
                Sensor.TYPE_ROTATION_VECTOR -> rotationAccuracy = accuracy
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(lock) {
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> recordAcceleration(
                    event.values[0], event.values[1], event.values[2],
                )

                Sensor.TYPE_ACCELEROMETER -> {
                    rawAcceleration = event.values.copyOf(3)
                    // The low pass runs unconditionally now. It used to be
                    // needed only as a fallback path, but gravity is logged
                    // every tick, so it has to be maintained even when
                    // TYPE_LINEAR_ACCELERATION is doing the real work.
                    val alpha = 0.8f
                    for (i in 0..2) {
                        gravity[i] = alpha * gravity[i] + (1f - alpha) * event.values[i]
                    }
                    if (linearAcceleration == null) {
                        recordAcceleration(
                            event.values[0] - gravity[0],
                            event.values[1] - gravity[1],
                            event.values[2] - gravity[2],
                        )
                    }
                    if (rotationVector == null) updateCompassFromAccelerationAndMagnet()
                }

                Sensor.TYPE_GYROSCOPE -> {
                    gyroX = event.values[0]
                    gyroY = event.values[1]
                    gyroZ = event.values[2]
                    val magnitude = sqrt(
                        event.values[0] * event.values[0] +
                            event.values[1] * event.values[1] +
                            event.values[2] * event.values[2],
                    ).toDouble()
                    gyroSquareSum += magnitude * magnitude
                    gyroCount++
                    gyroPeak = max(gyroPeak, magnitude)
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magnetX = event.values[0]
                    magnetY = event.values[1]
                    magnetZ = event.values[2]
                    rawMagnetic = event.values.copyOf(3)
                    if (rotationVector == null) updateCompassFromAccelerationAndMagnet()
                }

                Sensor.TYPE_PRESSURE -> pressureHpa = event.values[0]
                Sensor.TYPE_ROTATION_VECTOR -> updateCompassFromRotationVector(event.values)
            }
        }
    }

    fun takeSnapshot(): SensorSnapshot {
        refreshDeclinationIfNeeded()
        return synchronized(lock) {
            val mx = magnetX
            val my = magnetY
            val mz = magnetZ
            val magnitude = if (mx != null && my != null && mz != null) {
                sqrt((mx * mx + my * my + mz * mz).toDouble())
            } else {
                null
            }
            val magneticAzimuth = compassMagneticAzimuthDeg
            val trueAzimuth = if (magneticAzimuth != null && magneticDeclinationDeg != null) {
                CompassMath.trueNorthDegrees(
                    magneticAzimuth,
                    magneticDeclinationDeg!!.toDouble(),
                )
            } else {
                null
            }
            val heading = trueAzimuth ?: magneticAzimuth
            val reference = when {
                trueAzimuth != null -> "真北"
                magneticAzimuth != null -> "磁北"
                else -> "不可用"
            }
            val accuracy = magnetAccuracy ?: rotationAccuracy
            val disturbance = CompassMath.magneticDisturbanceLabel(magnitude)
            val calibration = if (disturbance == "可能受干扰") {
                "磁场受干扰"
            } else {
                CompassMath.calibrationLabel(accuracy)
            }
            val now = System.currentTimeMillis()
            val locationAge = declinationLocationTimeMs?.let { (now - it).coerceAtLeast(0L) }

            val result = SensorSnapshot(
                accelRms = if (accelCount > 0) sqrt(accelSquareSum / accelCount) else 0.0,
                accelPeak = accelPeak,
                accelX = accelX,
                accelY = accelY,
                accelZ = accelZ,
                gravityX = gravity[0],
                gravityY = gravity[1],
                gravityZ = gravity[2],
                accelWorldEastMean = if (accelWorldCount > 0) accelWorldEastSum / accelWorldCount else null,
                accelWorldNorthMean = if (accelWorldCount > 0) accelWorldNorthSum / accelWorldCount else null,
                accelWorldUpMean = if (accelWorldCount > 0) accelWorldUpSum / accelWorldCount else null,
                accelWorldSamples = accelWorldCount,
                gyroRms = if (gyroCount > 0) sqrt(gyroSquareSum / gyroCount) else 0.0,
                gyroPeak = gyroPeak,
                gyroX = gyroX,
                gyroY = gyroY,
                gyroZ = gyroZ,
                magnetX = mx,
                magnetY = my,
                magnetZ = mz,
                magnetMagnitude = magnitude,
                magnetAccuracy = magnetAccuracy,
                pressureHpa = pressureHpa,
                compassActive = heading != null,
                compassSource = compassSource,
                compassMagneticAzimuthDeg = magneticAzimuth,
                compassTrueAzimuthDeg = trueAzimuth,
                compassHeadingDeg = heading,
                compassDirection = heading?.let(CompassMath::directionName).orEmpty(),
                compassReference = reference,
                compassAccuracy = accuracy,
                compassAccuracyLabel = CompassMath.accuracyLabel(accuracy),
                compassCalibration = calibration,
                magneticDisturbance = disturbance,
                magneticDeclinationDeg = magneticDeclinationDeg,
                compassLocationAgeMs = locationAge,
            )
            accelSquareSum = 0.0
            accelCount = 0
            accelPeak = 0.0
            accelWorldEastSum = 0.0
            accelWorldNorthSum = 0.0
            accelWorldUpSum = 0.0
            accelWorldCount = 0
            gyroSquareSum = 0.0
            gyroCount = 0
            gyroPeak = 0.0
            result
        }
    }

    private fun recordAcceleration(x: Float, y: Float, z: Float) {
        val magnitude = sqrt(x * x + y * y + z * z).toDouble()
        accelSquareSum += magnitude * magnitude
        accelCount++
        accelPeak = max(accelPeak, abs(magnitude))
        accelX = x
        accelY = y
        accelZ = z
        val r = deviceToWorld ?: return
        // getRotationMatrix's convention: the matrix maps device coordinates
        // to the world frame whose axes are East, North and Up.
        accelWorldEastSum += (r[0] * x + r[1] * y + r[2] * z).toDouble()
        accelWorldNorthSum += (r[3] * x + r[4] * y + r[5] * z).toDouble()
        accelWorldUpSum += (r[6] * x + r[7] * y + r[8] * z).toDouble()
        accelWorldCount++
    }

    private fun updateCompassFromRotationVector(values: FloatArray) {
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        deviceToWorld = rotationMatrix
        updateCompassDegrees(orientation[0] * 180.0 / PI, "旋转矢量")
    }

    private fun updateCompassFromAccelerationAndMagnet() {
        val acceleration = rawAcceleration ?: return
        val magnetic = rawMagnetic ?: return
        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)
        if (!SensorManager.getRotationMatrix(
                rotationMatrix,
                inclinationMatrix,
                acceleration,
                magnetic,
            )
        ) {
            return
        }
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        updateCompassDegrees(orientation[0] * 180.0 / PI, "加速度计+磁力计")
    }

    private fun updateCompassDegrees(azimuthDegrees: Double, source: String) {
        compassMagneticAzimuthDeg = CompassMath.smoothDegrees(
            compassMagneticAzimuthDeg,
            CompassMath.normalizeDegrees(azimuthDegrees),
        )
        compassSource = source
    }

    @SuppressLint("MissingPermission")
    private fun refreshDeclinationIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDeclinationRefreshAt < DECLINATION_REFRESH_MS) return
        lastDeclinationRefreshAt = now

        val hasLocationPermission =
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            clearDeclination()
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }.maxByOrNull(Location::getTime)

        if (location == null || now - location.time > MAX_LOCATION_AGE_MS) {
            clearDeclination()
            return
        }

        val geomagneticField = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            if (location.hasAltitude()) location.altitude.toFloat() else 0f,
            now,
        )
        synchronized(lock) {
            magneticDeclinationDeg = geomagneticField.declination
            declinationLocationTimeMs = location.time
        }
    }

    private fun clearDeclination() {
        synchronized(lock) {
            magneticDeclinationDeg = null
            declinationLocationTimeMs = null
        }
    }
}
