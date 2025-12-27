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
import androidx.lifecycle.lifecycleScope
import com.example.testlocation.logic.LocationGPS
import com.example.testlocation.logic.LocationViewModel
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
            var currentMode by remember { mutableStateOf("Initializing...") }

            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { perms ->
                val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
                if (fine) {
                    hasPermissions = true
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

            DisposableEffect(Unit) {
                locationGPS.onStatusChanged = { mode ->
                    currentMode = mode
                }
                onDispose { locationGPS.stopTracking() }
            }

            if (hasPermissions) {
                TestScreen(
                    locationViewModel = locationViewModel,
                    currentMode = currentMode
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
    currentMode: String
) {
    val locationState by locationViewModel.location
    val currentLatLng = locationState?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0)

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLatLng, 18f)
    }

    LaunchedEffect(currentLatLng) {
        if (currentLatLng.latitude != 0.0) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(currentLatLng, 18f)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState
            ) {
                if (currentLatLng.latitude != 0.0) {
                    Marker(
                        state = MarkerState(position = currentLatLng),
                        title = "Current",
                        snippet = currentMode
                    )
                }
            }
        }

        Column(
            Modifier
                .background(Color.White)
                .padding(16.dp)
        ) {
            Text("Auto-Switching Tracker", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))

            Text("Current Mode:", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = currentMode,
                color = if (currentMode.contains("GPS")) Color.Blue else Color.Red,
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Logic: Every 60s, checks accuracy.\n" +
                        "< 20m Accuracy -> GPS Mode\n" +
                        "> 20m Accuracy -> Network (Wifi/Cell) + PDR Mode",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}