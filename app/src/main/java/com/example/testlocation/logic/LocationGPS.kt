package com.example.testlocation.logic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.testlocation.logic.LocationData
import com.example.testlocation.logic.LocationViewModel
import com.example.testlocation.logic.IndoorDetector
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LocationGPS(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val pdrSystem: PDRSystem
    private val indoorDetector = IndoorDetector(context)

    private var locationCallback: LocationCallback? = null
    private var locationJob: Job? = null
    private var trackingScope: CoroutineScope? = null

    // Helper callback to notify UI of status changes
    var onStatusChanged: ((isIndoor: Boolean, isPdrActive: Boolean) -> Unit)? = null

    private var lastKnownLocation: LocationData? = null
    private var isTracking = false
    private var isIndoor = false
    private var isInZone = false
    private var isPdrActive = false
    private var isAcquiringInitialLocation = false
    private var updateListener: ((LocationData) -> Unit)? = null

    init {
        pdrSystem = PDRSystem(context) { newLocation ->
            lastKnownLocation = newLocation
            updateListener?.invoke(newLocation)
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking(viewModel: LocationViewModel, scope: CoroutineScope) {
        if (isTracking) return
        isTracking = true
        trackingScope = scope

        updateListener = { loc -> viewModel.updateLocation(loc) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationJob = scope.launch(Dispatchers.Main) {
                indoorDetector.observeIndoorStatus().collectLatest { indoorStatus ->
                    isIndoor = indoorStatus
                    Log.d("LocationGPS", "Indoor Status: $isIndoor")
                    notifyStatus()
                    checkAndSwitchMode()
                }
            }
        }
        startGpsUpdates()
    }

    fun stopTracking() {
        isTracking = false
        locationJob?.cancel()
        stopGpsUpdates()
        pdrSystem.stop()
        updateListener = null
        isPdrActive = false
        isAcquiringInitialLocation = false
        notifyStatus()
    }

    fun setZoneStatus(inZone: Boolean) {
        if (isInZone != inZone) {
            isInZone = inZone
            Log.d("LocationGPS", "Zone Status Changed: $isInZone")
            checkAndSwitchMode()
        }
    }

    private fun checkAndSwitchMode() {
        if (isIndoor && isInZone) {
            if (lastKnownLocation != null) {
                switchToPDR()
            } else {
                if (!isAcquiringInitialLocation) startInitialLocationAcquisition()
            }
        } else {
            isAcquiringInitialLocation = false
            switchToGPS()
        }
    }

    private fun startInitialLocationAcquisition() {
        Log.d("LocationGPS", "Indoor detected but no location. Running GPS for 1 minute...")
        isAcquiringInitialLocation = true
        if (locationCallback == null) startGpsUpdates()

        trackingScope?.launch {
            delay(60_000)
            if (isAcquiringInitialLocation) {
                Log.d("LocationGPS", "1-minute acquisition finished. Switching to PDR.")
                isAcquiringInitialLocation = false
                checkAndSwitchMode()
            }
        }
    }

    private fun switchToPDR() {
        if (isPdrActive) return
        if (lastKnownLocation == null) return

        Log.d("LocationGPS", ">>> Switching to PDR Mode (Indoor + In Zone)")
        stopGpsUpdates()
        isPdrActive = true
        pdrSystem.start(lastKnownLocation!!)
        notifyStatus()
    }

    private fun switchToGPS() {
        if (!isPdrActive && locationCallback != null) return
        Log.d("LocationGPS", ">>> Switching to GPS Mode")
        isPdrActive = false
        pdrSystem.stop()
        startGpsUpdates()
        notifyStatus()
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (locationCallback != null) return

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let {
                    val locData = LocationData(it.latitude, it.longitude)
                    lastKnownLocation = locData
                    updateListener?.invoke(locData)
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateDistanceMeters(2f)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopGpsUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun notifyStatus() {
        onStatusChanged?.invoke(isIndoor, isPdrActive)
    }
}