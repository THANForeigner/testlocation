package com.example.testlocation

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope // [FIX 1] Add this import
import com.example.testlocation.logic.LocationGPS
import com.example.testlocation.logic.LocationViewModel // Ensure this matches your package
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

class MainActivity : ComponentActivity() {

    private val locationViewModel: LocationViewModel by viewModels()
    private lateinit var locationGPS: LocationGPS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationGPS = LocationGPS(this)

        setContent {
            var hasPermissions by remember { mutableStateOf(false) }

            // UI State for Status Dashboard
            var isIndoor by remember { mutableStateOf(false) }
            var isPdrActive by remember { mutableStateOf(false) }
            var isInZone by remember { mutableStateOf(false) }

            // Permission Launcher
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { perms ->
                val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
                if (fine) {
                    hasPermissions = true
                    // [FIX 2] Use 'lifecycleScope' directly (it belongs to ComponentActivity)
                    locationGPS.startTracking(locationViewModel, lifecycleScope)
                }
            }

            LaunchedEffect(Unit) {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACTIVITY_RECOGNITION
                    )
                )
            }

            // Listen to Logic Status Changes
            DisposableEffect(Unit) {
                locationGPS.onStatusChanged = { indoor, pdr ->
                    isIndoor = indoor
                    isPdrActive = pdr
                }
                onDispose { locationGPS.stopTracking() }
            }

            if (hasPermissions) {
                TestScreen(
                    locationViewModel = locationViewModel,
                    isIndoor = isIndoor,
                    isPdrActive = isPdrActive,
                    isInZone = isInZone,
                    onToggleZone = {
                        val newState = !isInZone
                        isInZone = newState
                        locationGPS.setZoneStatus(newState)
                    }
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Waiting for Permissions...")
                }
            }
        }
    }
}

@Composable
fun TestScreen(
    locationViewModel: LocationViewModel,
    isIndoor: Boolean,
    isPdrActive: Boolean,
    isInZone: Boolean,
    onToggleZone: () -> Unit
) {
    val locationState by locationViewModel.location
    val currentLatLng = locationState?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0)

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLatLng, 18f)
    }

    // Auto-follow camera
    LaunchedEffect(currentLatLng) {
        if (currentLatLng.latitude != 0.0) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(currentLatLng, 18f)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 1. Google Map
        Box(Modifier.weight(1f)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState
            ) {
                if (currentLatLng.latitude != 0.0) {
                    Marker(
                        state = MarkerState(position = currentLatLng),
                        title = "Current",
                        snippet = if (isPdrActive) "PDR Mode" else "GPS Mode"
                    )
                }
            }
        }

        // 2. Control Dashboard
        Column(
            Modifier
                .background(Color.White)
                .padding(16.dp)
        ) {
            Text("Location Logic Tester", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Indoor Detected: ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if(isIndoor) "YES" else "NO",
                    color = if(isIndoor) Color.Red else Color.Green,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Current System: ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if(isPdrActive) "PDR (Steps)" else "GPS (Satellite)",
                    color = if(isPdrActive) Color.Blue else Color.Gray,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onToggleZone,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isInZone) Color.Green else Color.Gray
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isInZone) "Status: INSIDE ZONE" else "Status: OUTSIDE ZONE (Tap to Enter)")
            }

            Text(
                "Tip: PDR only activates if Indoor=YES AND Zone=INSIDE",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}