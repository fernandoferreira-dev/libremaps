package com.example.libremaps

import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private const val DEFAULT_ZOOM = 17.0
private const val MS_TO_KMH = 3.6

@Composable
fun MenuInicial(onLogout: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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

    // Pede a permissão ao abrir o ecrã
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

    // Configuração do osmdroid + criação do mapa (uma só vez)
    val mapView = remember {
        // Carregar a configuração primeiro
        Configuration.getInstance().load(
            context, PreferenceManager.getDefaultSharedPreferences(context)
        )
        // Definir o User-Agent (identificação única da app)
        Configuration.getInstance().userAgentValue = "RoadWatch-UPS-Miniprojeto/1.0 (${context.packageName})"

        MapView(context).apply {
            setTileSource(TileSourceFactory.OpenTopo)
            setMultiTouchControls(true)
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(GeoPoint(39.7436, -8.8071))
        }
    }

    val userMarker = remember {
        Marker(mapView).apply {
            title = "A sua posição"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            mapView.overlays.add(this)
        }
    }

    // Se o utilizador mexer no mapa, deixa de o seguir automaticamente
    DisposableEffect(mapView) {
        mapView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) followUser = false
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Usa GPS e mapa quando o ecrã está visível (poupa bateria)
    DisposableEffect(lifecycleOwner, hasPermission) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    if (hasPermission) {
                        tracker.start { location -> currentLocation = location }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapView.onPause()
                    tracker.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Se o ecrã já está visível quando a permissão é concedida
        if (hasPermission && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            tracker.start { location -> currentLocation = location }
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.stop()
        }
    }

    // Atualiza marcador e câmara quando chega uma nova localização
    LaunchedEffect(currentLocation) {
        currentLocation?.let { location ->
            val point = GeoPoint(location.latitude, location.longitude)
            userMarker.position = point
            if (followUser) mapView.controller.animateTo(point)
            mapView.invalidate()
        }
    }

    // Liberta recursos do mapa ao sair
    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Botão para recentrar no utilizador
        FloatingActionButton(
            onClick = {
                followUser = true
                currentLocation?.let {
                    mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                    mapView.controller.setZoom(DEFAULT_ZOOM)
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Centrar na minha posição")
        }

        // Painel inferior com velocidade e coordenadas
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
            text = "O RoadWatch precisa da sua localização para mostrar a sua posição no mapa.",
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