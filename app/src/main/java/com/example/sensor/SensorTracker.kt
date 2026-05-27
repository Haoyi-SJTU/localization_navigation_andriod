package com.example.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SensorState(
    // GPS / Location Data
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0, // Altitude from GPS (meters)
    val hasGps: Boolean = false,
    val provider: String = "None",
    val speed: Float = 0f, // Speed in m/s
    val bearing: Float = 0f, // Direction of motion from GPS in degrees
    val gpsAccuracy: Float = 0f,
    val gpsTime: Long = 0L,

    // Barometer Data
    val hasBarometer: Boolean = false,
    val pressure: Float = 0f, // Atmospheric pressure in hPa
    val pressureAltitude: Float = 0f, // Estimated altitude from barometric pressure

    // Gyroscope Data
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,

    // Accelerometer & Magnetometer details (IMU raw components)
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val magX: Float = 0f,
    val magY: Float = 0f,
    val magZ: Float = 0f,

    // IMU Orientation / Attitude
    val heading: Float = 0f, // Filtered azimuth / heading (0 = North, 90 = East, 180 = South, 270 = West)
    val pitch: Float = 0f,   // Tilting forward/backward (degrees)
    val roll: Float = 0f,    // Tilting side-to-side (degrees)
    val hasImu: Boolean = false
)

class SensorTracker(private val context: Context) : SensorEventListener, LocationListener {

    private val tag = "SensorTracker"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _sensorState = MutableStateFlow(SensorState())
    val sensorState: StateFlow<SensorState> = _sensorState.asStateFlow()

    // Raw IMU tracking arrays for heading calculation
    private var lastAccelerometer = FloatArray(3)
    private var lastMagnetometer = FloatArray(3)
    private var isAccelerometerSet = false
    private var isMagnetometerSet = false

    private var isTracking = false

    // Sensors to listen
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    fun startTracking() {
        if (isTracking) return
        isTracking = true

        // 1. Register Hardware Sensors
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            _sensorState.update { state -> state.copy(hasBarometer = true) }
        }
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            _sensorState.update { state -> state.copy(hasImu = true) }
        }

        // 2. Register GPS / Network Location Updates
        registerLocationUpdates()
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false

        sensorManager.unregisterListener(this)
        try {
            locationManager.removeUpdates(this)
        } catch (e: SecurityException) {
            Log.e(tag, "Permission error stopping location", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun registerLocationUpdates() {
        try {
            val hasFine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasFine || hasCoarse) {
                // Register GPS Provider for accurate offline tracking
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L, // 1 second intervals
                        0.5f,  // 0.5 meters change
                        this
                    )
                }
                // Register Network Provider as quick initial backup
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        3000L,
                        1.0f,
                        this
                    )
                }

                // Inject last known location as start point
                var lastKnown: Location? = null
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                }
                if (lastKnown == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }

                lastKnown?.let { onLocationChanged(it) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to register location updates", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val pressureValue = event.values[0]
                val calcAltitude = calculateAltitude(pressureValue)
                _sensorState.update { state ->
                    state.copy(
                        pressure = pressureValue,
                        pressureAltitude = calcAltitude
                    )
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                _sensorState.update { state ->
                    state.copy(
                        gyroX = event.values[0],
                        gyroY = event.values[1],
                        gyroZ = event.values[2]
                    )
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccelerometer, 0, event.values.size)
                isAccelerometerSet = true
                _sensorState.update { state ->
                    state.copy(
                        accelX = event.values[0],
                        accelY = event.values[1],
                        accelZ = event.values[2]
                    )
                }
                updateHeadingAndAttitude()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagnetometer, 0, event.values.size)
                isMagnetometerSet = true
                _sensorState.update { state ->
                    state.copy(
                        magX = event.values[0],
                        magY = event.values[1],
                        magZ = event.values[2]
                    )
                }
                updateHeadingAndAttitude()
            }
        }
    }

    private fun updateHeadingAndAttitude() {
        if (isAccelerometerSet && isMagnetometerSet) {
            val rMatrix = FloatArray(9)
            val iMatrix = FloatArray(9)
            if (SensorManager.getRotationMatrix(rMatrix, iMatrix, lastAccelerometer, lastMagnetometer)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rMatrix, orientation)

                // Azimuth (heading) is orientation[0] in radians. Convert to degrees.
                var headingDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (headingDeg < 0) {
                    headingDeg += 360f
                }
                
                // Pitch and Roll
                val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()

                _sensorState.update { state ->
                    state.copy(
                        heading = headingDeg,
                        pitch = pitchDeg,
                        roll = rollDeg
                    )
                }
            }
        }
    }

    private fun calculateAltitude(pressureHpa: Float): Float {
        // Standard formula: h = 44330 * (1 - (p / p0)^0.1903)
        // p0 is sea-level pressure, standard 1013.25
        val p0 = 1013.25f
        return (44330f * (1.0f - Math.pow((pressureHpa / p0).toDouble(), 0.1902949).toFloat()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // LocationListener Callbacks
    override fun onLocationChanged(location: Location) {
        _sensorState.update { state ->
            state.copy(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speed = location.speed,
                // If location has a heading/bearing, keep that as GPS movement bearing
                bearing = if (location.hasBearing()) location.bearing else state.bearing,
                gpsAccuracy = location.accuracy,
                gpsTime = location.time,
                provider = location.provider ?: "Unknown",
                hasGps = true
            )
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
