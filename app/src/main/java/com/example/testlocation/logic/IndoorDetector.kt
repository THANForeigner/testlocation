package com.example.testlocation.logic

import android.os.Handler
import android.os.Looper
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class IndoorDetector(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val INDOOR_SNR_THRESHOLD = 18.0f
    private val OUTDOOR_SNR_THRESHOLD = 28.0f
    private val MIN_OUTDOOR_SATELLITES = 7
    private val MAX_INDOOR_SATELLITES = 3
    private val handler = Handler(Looper.getMainLooper())

    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("MissingPermission")
    fun observeIndoorStatus(): Flow<Boolean> = callbackFlow {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            close()
            return@callbackFlow
        }
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                super.onSatelliteStatusChanged(status)
                var totalSnr = 0f
                var usedSat = 0
                val totalSat = status.satelliteCount

                if (totalSat == 0) { trySend(true); return } // Deep indoor

                for (i in 0 until totalSat) {
                    if (status.usedInFix(i)) {
                        totalSnr += status.getCn0DbHz(i)
                        usedSat++
                    }
                }
                if (usedSat == 0) { trySend(true); return } // No fix

                val avgSnr = totalSnr / usedSat
                val isIndoor = (avgSnr <= INDOOR_SNR_THRESHOLD && usedSat <= MAX_INDOOR_SATELLITES)
                val isOutdoor = (avgSnr >= OUTDOOR_SNR_THRESHOLD && usedSat >= MIN_OUTDOOR_SATELLITES)

                when {
                    isIndoor -> trySend(true)
                    isOutdoor -> trySend(false)
                    else -> trySend(avgSnr <= INDOOR_SNR_THRESHOLD)
                }
            }
        }
        locationManager.registerGnssStatusCallback(callback, handler)
        awaitClose { locationManager.unregisterGnssStatusCallback(callback) }
    }
}