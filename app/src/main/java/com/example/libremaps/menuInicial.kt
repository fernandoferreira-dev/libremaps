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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // Estados da Viagem (Velocidade, Distância e Duração)
    var isTripActive by remember { mutableStateOf(true) }
    var totalDistanceMeters by remember { mutableFloatStateOf(0f) }
    var tripStartTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsedTimeMs by remember { mutableLongStateOf(0L) }
    var previousLocation by remember { mutableStateOf<Location?>(null) }

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

    // Estado da câmara
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

    // Cronómetro da viagem
    LaunchedEffect(isTripActive, tripStartTimeMs) {
        while (isTripActive) {
            elapsedTimeMs = System.currentTimeMillis() - tripStartTimeMs
            delay(1000L)
        }
    }

    // Cálculo da distância percorrida
    LaunchedEffect(currentLocation) {
        currentLocation?.let { newLoc ->
            if (isTripActive) {
                previousLocation?.let { oldLoc ->
                    val distance = oldLoc.distanceTo(newLoc)
                    // Filtra pequenas oscilações de ruído GPS (< 2 metros ou baixa precisão)
                    if (distance >= 2.0f && newLoc.accuracy < 30f) {
                        totalDistanceMeters += distance
                    }
                }
                previousLocation = newLoc
            }
        }
    }

    // GPS só enquanto o ecrã está visível
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
                myLocationButtonEnabled = false,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    textAlign = TextAlign.Center
                )

                else -> LocationInfoPanel(
                    location = currentLocation!!,
                    totalDistanceMeters = totalDistanceMeters,
                    elapsedTimeMs = elapsedTimeMs,
                    isTripActive = isTripActive,
                    onToggleTrip = {
                        if (isTripActive) {
                            isTripActive = false
                        } else {
                            tripStartTimeMs = System.currentTimeMillis() - elapsedTimeMs
                            isTripActive = true
                        }
                    },
                    onResetTrip = {
                        totalDistanceMeters = 0f
                        tripStartTimeMs = System.currentTimeMillis()
                        elapsedTimeMs = 0L
                        previousLocation = currentLocation
                    }
                )
            }
        }
    }
}

@Composable
private fun LocationInfoPanel(
    location: Location,
    totalDistanceMeters: Float,
    elapsedTimeMs: Long,
    isTripActive: Boolean,
    onToggleTrip: () -> Unit,
    onResetTrip: () -> Unit
) {
    val speedKmh = if (location.hasSpeed()) location.speed * MS_TO_KMH else 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Métricas principais: Velocidade | Distância | Duração
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Velocidade
            TripMetricItem(
                icon = Icons.Default.Speed,
                value = "%.0f".format(speedKmh),
                unit = "km/h",
                label = "Velocidade"
            )

            // Distância
            TripMetricItem(
                icon = Icons.Default.Route,
                value = formatDistanceValue(totalDistanceMeters),
                unit = formatDistanceUnit(totalDistanceMeters),
                label = "Distância"
            )

            // Duração
            TripMetricItem(
                icon = Icons.Default.Timer,
                value = formatDuration(elapsedTimeMs),
                unit = "",
                label = "Duração"
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))

        // Controlos da viagem e Coordenadas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botões de Controlo (Pausar/Iniciar e Reiniciar)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onToggleTrip) {
                    Icon(
                        imageVector = if (isTripActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isTripActive) "Pausar viagem" else "Iniciar viagem",
                        tint = if (isTripActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = onResetTrip) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reiniciar viagem",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Coordenadas GPS e Precisão
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%.4f, %.4f".format(location.latitude, location.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Precisão: %.0f m".format(location.accuracy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TripMetricItem(
    icon: ImageVector,
    value: String,
    unit: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

private fun formatDistanceValue(meters: Float): String {
    return if (meters >= 1000f) {
        "%.2f".format(meters / 1000f)
    } else {
        "%.0f".format(meters)
    }
}

private fun formatDistanceUnit(meters: Float): String {
    return if (meters >= 1000f) "km" else "m"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
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