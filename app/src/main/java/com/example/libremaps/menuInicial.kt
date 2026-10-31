package com.example.libremaps

import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch

/*
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
*/

private const val DEFAULT_ZOOM = 17f
private const val MS_TO_KMH = 3.6

@Composable
fun MenuInicial(onLogout: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val tracker = remember { LocationTracker(context) }

    var hasPermission by remember { mutableStateOf(tracker.hasPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var followUser by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.any { it }
        permissionDenied = !hasPermission
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Estado da câmara (substitui o MapView + controller)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(39.7436, -8.8071), DEFAULT_ZOOM)
    }

    // Se o utilizador mexer no mapa com o dedo, deixa de o seguir
    LaunchedEffect(cameraPositionState) {
        snapshotFlow {
            cameraPositionState.isMoving to cameraPositionState.cameraMoveStartedReason
        }.collect { (moving, reason) ->
            if (moving && reason == CameraMoveStartedReason.GESTURE) followUser = false
        }
    }

    // GPS só enquanto o ecrã está visível (o mapa gere o seu próprio ciclo de vida)
    DisposableEffect(lifecycleOwner, hasPermission) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    if (hasPermission) tracker.start { currentLocation = it }
                Lifecycle.Event.ON_PAUSE -> tracker.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        if (hasPermission && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            tracker.start { currentLocation = it }
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.stop()
        }
    }

    // Move a câmara quando chega uma nova localização
    LaunchedEffect(currentLocation) {
        currentLocation?.let { location ->
            if (followUser) {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLng(LatLng(location.latitude, location.longitude))
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = hasPermission),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = false, // usamos o nosso FAB
                zoomControlsEnabled = false
            )
        )

        FloatingActionButton(
            onClick = {
                followUser = true
                currentLocation?.let {
                    scope.launch {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(it.latitude, it.longitude), DEFAULT_ZOOM
                            )
                        )
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Centrar na minha posição")
        }

        ElevatedCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            when {
                !hasPermission -> PermissionPanel(
                    denied = permissionDenied,
                    onRequest = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                )

                currentLocation == null -> Text(
                    text = "A procurar localização GPS...",
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    textAlign = TextAlign.Center
                )

                else -> LocationInfoPanel(currentLocation!!)
            }
        }
    }
}

@Composable
private fun LocationInfoPanel(location: Location) {
    val speedKmh = if (location.hasSpeed()) location.speed * MS_TO_KMH else 0.0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "%.0f".format(speedKmh),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(text = "km/h", style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "%.5f, %.5f".format(location.latitude, location.longitude),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Precisão: %.0f m".format(location.accuracy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionPanel(
    denied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "O LibreMaps precisa da sua localização para mostrar a sua posição no mapa.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 12.dp)) {
            Text("Permitir localização")
        }
        if (denied) {
            TextButton(onClick = onOpenSettings) {
                Text("Abrir definições da aplicação")
            }
        }
    }
}