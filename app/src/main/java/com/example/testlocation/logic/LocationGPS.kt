package com.example.testlocation.logic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.testlocation.logic.LocationData
import com.example.testlocation.logic.LocationViewModel
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import android.location.Location
import kotlinx.coroutines.tasks.await
class LocationGPS(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val pdrSystem: PDRSystem
    private var trackingJob: Job? = null
    private var isTracking = false

    // State Tracking
    private var currentMode = "None"

    // Callback to update UI
    var onStatusChanged: ((mode: String) -> Unit)? = null

    // Internal location listener for active tracking
    private var activeLocationCallback: LocationCallback? = null
    private var updateListener: ((LocationData) -> Unit)? = null

    init {
        // Initialize PDR but don't start it yet
        pdrSystem = PDRSystem(context) { newLocation ->
            // PDR updates come here
            updateListener?.invoke(newLocation)
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking(viewModel: LocationViewModel, scope: CoroutineScope) {
        if (isTracking) return
        isTracking = true
        updateListener = { loc -> viewModel.updateLocation(loc) }

        // Start the 1-minute periodic check loop
        trackingJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                checkAndChooseBestMode()
                delay(60_000) // Wait 1 minute before checking again
            }
        }
    }

    fun stopTracking() {
        isTracking = false
        trackingJob?.cancel()
        stopActiveUpdates()
        pdrSystem.stop()
        updateListener = null
        currentMode = "Stopped"
        onStatusChanged?.invoke(currentMode)
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkAndChooseBestMode() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        Log.d("LocationGPS", "Checking for best provider...")

        // 1. Try to get a High Accuracy (GPS) location first
        // We use a cancellation token to prevent it from hanging forever if no GPS
        val tokenSource = CancellationTokenSource()
        val locationTask = fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            tokenSource.token
        )

        try {
            val location = locationTask.await()

            // DECISION LOGIC:
            // If we have a location AND it's accurate (e.g. < 20m), use GPS.
            // Otherwise, assume we are indoors/weak signal and use Network + PDR.
            if (location != null && location.accuracy <5.0f) {
                switchToGpsMode()
            } else {
                // If location is null (no GPS) or accuracy is bad (>20m), prefer Network + PDR
                val networkLoc = if (location != null) {
                    LocationData(location.latitude, location.longitude)
                } else {
                    // Try getting last known if current failed
                    val last = fusedLocationClient.lastLocation.addOnSuccessListener {}.result
                    if (last != null) LocationData(last.latitude, last.longitude) else null
                }

                switchToNetworkPdrMode(networkLoc)
            }
        } catch (e: Exception) {
            Log.e("LocationGPS", "Error checking location: ${e.message}")
            switchToNetworkPdrMode(null)
        }
    }

    private fun switchToGpsMode() {
        if (currentMode == "GPS (High Accuracy)") return

        Log.d("LocationGPS", ">>> Switching to GPS Mode")
        currentMode = "GPS (High Accuracy)"
        onStatusChanged?.invoke(currentMode)

        // 1. Stop PDR
        pdrSystem.stop()

        // 2. Start Active GPS Updates
        startActiveUpdates(Priority.PRIORITY_HIGH_ACCURACY)
    }

    private fun switchToNetworkPdrMode(startLocation: LocationData?) {
        if (currentMode == "Wifi/Network + PDR") return

        Log.d("LocationGPS", ">>> Switching to Network + PDR Mode")
        currentMode = "Wifi/Network + PDR"
        onStatusChanged?.invoke(currentMode)

        // 1. Start Active Network Updates (Balanced Power uses Wifi/Cell)
        startActiveUpdates(Priority.PRIORITY_BALANCED_POWER_ACCURACY)

        // 2. Start PDR (Needs a starting point)
        if (startLocation != null) {
            pdrSystem.start(startLocation)
        } else {
            Log.w("LocationGPS", "Cannot start PDR: No start location available")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startActiveUpdates(priority: Int) {
        // Stop previous callback if any
        stopActiveUpdates()

        activeLocationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let {
                    val locData = LocationData(it.latitude, it.longitude)

                    // If we are in GPS mode, we send updates directly.
                    // If we are in PDR mode, we assume PDR handles the fine movement,
                    // but we can use this to "reset" the PDR position occasionally.
                    if (currentMode == "GPS (High Accuracy)") {
                        updateListener?.invoke(locData)
                    } else {
                        // In PDR mode, you might want to re-sync PDR with this network location
                        // pdrSystem.start(locData) // Optional: Reset PDR anchor every network update
                    }
                }
            }
        }

        val request = LocationRequest.Builder(priority, 5000) // Update every 5s
            .setMinUpdateDistanceMeters(5f)
            .build()

        fusedLocationClient.requestLocationUpdates(request, activeLocationCallback!!, Looper.getMainLooper())
    }

    private fun stopActiveUpdates() {
        activeLocationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        activeLocationCallback = null
    }
}