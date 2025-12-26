package com.example.testlocation.logic

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.testlocation.logic.LocationData
import kotlin.math.*

class PDRSystem(context: Context, private val onLocationUpdate: (LocationData) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var lastLocation: LocationData? = null
    private var isRunning = false

    private val STEP_THRESHOLD = 10.5f
    private val MIN_TIME_BETWEEN_STEPS = 350L
    private val STEP_LENGTH = 0.5f
    private var lastStepTime = 0L
    private var currentAccel = 0f
    private var lastAccel = 0f
    private var azimuth = 0f
    private val ALPHA = 0.97f

    fun start(startLocation: LocationData) {
        if (isRunning) return
        lastLocation = startLocation
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        isRunning = true
        Log.d("PDRSystem", "Started at ${startLocation.latitude}, ${startLocation.longitude}")
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        isRunning = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRunning) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            gravity = event.values.clone()
            detectStep(event.values)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic = event.values.clone()
        }
        if (gravity != null && geomagnetic != null) {
            val R = FloatArray(9)
            val I = FloatArray(9)
            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)
                azimuth = (ALPHA * azimuth + (1 - ALPHA) * orientation[0])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun detectStep(values: FloatArray) {
        val x = values[0]; val y = values[1]; val z = values[2]
        currentAccel = sqrt(x * x + y * y + z * z)
        val currentTime = System.currentTimeMillis()

        if ((currentTime - lastStepTime) > MIN_TIME_BETWEEN_STEPS) {
            if (currentAccel > STEP_THRESHOLD && lastAccel <= STEP_THRESHOLD) {
                lastStepTime = currentTime
                updateLocation()
            }
        }
        lastAccel = currentAccel
    }

    private fun updateLocation() {
        lastLocation?.let { loc ->
            val distance = STEP_LENGTH
            val R = 6378137.0
            val dx = distance * sin(azimuth)
            val dy = distance * cos(azimuth)
            val dLat = (dy / R) * (180 / Math.PI)
            val dLon = (dx / (R * cos(Math.toRadians(loc.latitude)))) * (180 / Math.PI)

            val newLoc = LocationData(loc.latitude + dLat, loc.longitude + dLon)
            lastLocation = newLoc
            onLocationUpdate(newLoc)
        }
    }
}