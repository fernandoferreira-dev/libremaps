package com.example.libremaps.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.libremaps.core.data.repository.BookmarkRepository
import com.example.libremaps.core.database.AppDatabase
import com.example.libremaps.core.database.BookmarkEntity
import com.example.libremaps.core.database.toEntity
import com.example.libremaps.core.database.toModel
import com.example.libremaps.core.location.GeofenceBroadcastReceiver
import com.example.libremaps.core.location.LocationTracker
import com.example.libremaps.core.model.Bookmark
import com.example.libremaps.core.model.BookmarkReportRequest
import com.example.libremaps.core.model.BookmarkRequest
import com.example.libremaps.core.network.ApiClient
import com.example.libremaps.core.network.NetworkMonitor
import com.example.libremaps.core.network.WeatherClient
import com.example.libremaps.core.sensor.LightSensorTracker
import com.example.libremaps.feature.profile.ProfileDialog
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private const val DEFAULT_ZOOM = 17f
private const val MS_TO_KMH = 3.6

private const val GEOFENCE_ID = "user_geofence"
private const val DEFAULT_GEOFENCE_RADIUS_M = 150f

private const val BOOKMARK_TTL_MS = 48L * 60 * 60 * 1000


//Modelo de dados para o Histórico de Deslocações/Viagens.
//Guarda detalhes completos da deslocação (datas, distância, duração, velocidade média e máxima).

private data class TripHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val startedAtMs: Long,
    val endedAtMs: Long = System.currentTimeMillis(),
    val distanceMeters: Float,
    val durationMs: Long,
    val maxSpeedKmh: Double = 0.0
) {
    val averageSpeedKmh: Double
        get() = if (durationMs > 1000) (distanceMeters / 1000.0) / (durationMs / 3600000.0) else 0.0
}

private const val TRIP_HISTORY_PREFS = "trip_history"
private const val TRIP_HISTORY_KEY = "entries"

private fun loadTripHistory(context: Context): List<TripHistoryEntry> {
    val rawHistory = context.getSharedPreferences(TRIP_HISTORY_PREFS, Context.MODE_PRIVATE)
        .getString(TRIP_HISTORY_KEY, null) ?: return emptyList()
    return try {
        val history = JSONArray(rawHistory)
        buildList {
            for (index in 0 until history.length()) {
                val entry = history.optJSONObject(index) ?: continue
                val startedAt = entry.optLong("startedAtMs")
                val duration = entry.optLong("durationMs")
                add(
                    TripHistoryEntry(
                        id = entry.optString("id", UUID.randomUUID().toString()),
                        startedAtMs = startedAt,
                        endedAtMs = entry.optLong("endedAtMs", startedAt + duration),
                        distanceMeters = entry.optDouble("distanceMeters").toFloat(),
                        durationMs = duration,
                        maxSpeedKmh = entry.optDouble("maxSpeedKmh", 0.0)
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveTripHistory(context: Context, entries: List<TripHistoryEntry>) {
    val history = JSONArray()
    entries.takeLast(50).forEach { entry ->
        history.put(
            JSONObject()
                .put("id", entry.id)
                .put("startedAtMs", entry.startedAtMs)
                .put("endedAtMs", entry.endedAtMs)
                .put("distanceMeters", entry.distanceMeters)
                .put("durationMs", entry.durationMs)
                .put("maxSpeedKmh", entry.maxSpeedKmh)
        )
    }
    context.getSharedPreferences(TRIP_HISTORY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(TRIP_HISTORY_KEY, history.toString())
        .apply()
}

private fun hasBackgroundLocationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

@Composable
fun MenuInicial(token: String, onLogout: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val tracker = remember { LocationTracker(context) }

    // Monitorização da rede para suporte a modo offline e standby de sincronização (RNF 6 e 10)
    val networkMonitor = remember { NetworkMonitor(context) }
    val isOnline by networkMonitor.isOnline.collectAsState(initial = networkMonitor.checkCurrentNetwork())

    var hasPermission by remember { mutableStateOf(tracker.hasPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }

    val db = remember { AppDatabase.getDatabase(context) }
    val bookmarkDao = remember { db.bookmarkDao() }
    val bookmarkRepository = remember { BookmarkRepository(dao = bookmarkDao) }
    val localBookmarkEntities by bookmarkDao.getAllBookmarks().collectAsState(initial = emptyList())
    val bookmarks = remember(localBookmarkEntities) {
        val now = System.currentTimeMillis()
        localBookmarkEntities
            .filter { it.createdAtMs <= 0L || (now - it.createdAtMs) < BOOKMARK_TTL_MS }
            .map { it.toModel() }
    }

    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var followUser by rememberSaveable { mutableStateOf(true) }
    var reportLocation by remember { mutableStateOf<LatLng?>(null) }
    var reportType by rememberSaveable { mutableStateOf("Acidente") }
    var reportDescription by rememberSaveable { mutableStateOf("") }
    var reportError by rememberSaveable { mutableStateOf<String?>(null) }
    var reportSubmitting by rememberSaveable { mutableStateOf(false) }
    var selectedBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var bookmarkActionError by rememberSaveable { mutableStateOf<String?>(null) }
    var showProfileDialog by rememberSaveable { mutableStateOf(false) }

    // Estado da viagem/deslocação
    var isTripActive by rememberSaveable { mutableStateOf(false) }
    var totalDistanceMeters by rememberSaveable { mutableFloatStateOf(0f) }
    var tripStartTimeMs by rememberSaveable { mutableLongStateOf(0L) }
    var elapsedTimeMs by rememberSaveable { mutableLongStateOf(0L) }
    var maxSpeedKmh by rememberSaveable { mutableDoubleStateOf(0.0) }
    var previousLocation by remember { mutableStateOf<Location?>(null) }
    val tripHistory = remember {
        mutableStateListOf<TripHistoryEntry>().apply { addAll(loadTripHistory(context)) }
    }
    var showTripHistory by remember { mutableStateOf(false) }

    fun finishAndSaveTrip() {
        if (elapsedTimeMs > 5000L || totalDistanceMeters > 5f) {
            val entry = TripHistoryEntry(
                id = UUID.randomUUID().toString(),
                startedAtMs = if (tripStartTimeMs > 0) tripStartTimeMs else (System.currentTimeMillis() - elapsedTimeMs),
                endedAtMs = System.currentTimeMillis(),
                distanceMeters = totalDistanceMeters,
                durationMs = elapsedTimeMs,
                maxSpeedKmh = maxSpeedKmh
            )
            tripHistory.add(entry)
            saveTripHistory(context, tripHistory)
            Toast.makeText(context, "Viagem guardada no histórico!", Toast.LENGTH_SHORT).show()
        }
        isTripActive = false
        totalDistanceMeters = 0f
        tripStartTimeMs = 0L
        elapsedTimeMs = 0L
        maxSpeedKmh = 0.0
        previousLocation = currentLocation
    }

    var geofenceCenter by rememberSaveable { mutableStateOf<LatLng?>(null) }
    var geofenceRadius by rememberSaveable { mutableFloatStateOf(DEFAULT_GEOFENCE_RADIUS_M) }
    var showGeofenceDialog by rememberSaveable { mutableStateOf(false) }
    var showLocationMenu by rememberSaveable { mutableStateOf(false) }
    var dialogRadius by rememberSaveable { mutableFloatStateOf(DEFAULT_GEOFENCE_RADIUS_M) }
    var hasBackgroundPermission by remember { mutableStateOf(hasBackgroundLocationPermission(context)) }
    var pendingGeofence by remember { mutableStateOf<Pair<LatLng, Float>?>(null) }

    // Sensor de luz dinâmico para brilho do ecrã
    var ambientLux by remember { mutableStateOf(0f) }
    val lightTracker = remember { LightSensorTracker(context) }

    DisposableEffect(Unit) {
        lightTracker.start { lux ->
            ambientLux = lux
        }
        onDispose {
            lightTracker.stop()
        }
    }

    val targetBrightness = when {
        ambientLux < 50f -> 1.0f
        ambientLux > 1000f -> 0.2f
        else -> {
            val fraction = (ambientLux - 50f) / (1000f - 50f)
            1.0f - (fraction * 0.8f)
        }
    }

    LaunchedEffect(targetBrightness) {
        val activity = context.findActivity()
        activity?.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.screenBrightness = targetBrightness
            window.attributes = layoutParams
        }
    }

    var temperatureC by remember { mutableStateOf<Double?>(null) }
    var weatherCode by remember { mutableStateOf<Int?>(null) }
    val weatherLocationKey = currentLocation?.let { location ->
        "${location.latitude.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP)}:" +
                location.longitude.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP)
    }
    val geofencingClient = remember { LocationServices.getGeofencingClient(context) }
    val geofencePendingIntent = remember {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun performLogout() {
        scope.launch {
            try {
                if (isOnline) {
                    ApiClient.api.logout("Bearer $token")
                }
            } catch (_: Exception) {
            } finally {
                onLogout()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun registerGeofence(center: LatLng, radius: Float) {
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(center.latitude, center.longitude, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnSuccessListener {
                geofenceCenter = center
                geofenceRadius = radius
                Toast.makeText(context, "Perímetro definido (${radius.toInt()} m)", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Erro ao definir perímetro: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    fun removeGeofence() {
        geofencingClient.removeGeofences(listOf(GEOFENCE_ID))
            .addOnSuccessListener {
                geofenceCenter = null
                Toast.makeText(context, "Perímetro removido", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Erro ao remover perímetro: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.any { it }
        permissionDenied = !hasPermission
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBackgroundPermission = granted || hasBackgroundLocationPermission(context)
        val pending = pendingGeofence
        pendingGeofence = null
        if (hasBackgroundPermission && pending != null) {
            registerGeofence(pending.first, pending.second)
        } else if (!hasBackgroundPermission) {
            Toast.makeText(
                context,
                "Para usar perímetros, escolha \"Permitir sempre\" nas definições de localização.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val pending = pendingGeofence
        if (pending != null) {
            if (hasBackgroundPermission) {
                pendingGeofence = null
                registerGeofence(pending.first, pending.second)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                pendingGeofence = null
                registerGeofence(pending.first, pending.second)
            }
        }
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

    // Sincronização inteligente: entra em standby quando offline (RNF 6 e 10)
    LaunchedEffect(token, isOnline) {
        if (isOnline) {
            bookmarkRepository.syncBookmarks(token, isOnline = true)
            while (isOnline) {
                delay(30_000L)
                bookmarkRepository.syncBookmarks(token, isOnline = true)
            }
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(39.7436, -8.8071), DEFAULT_ZOOM)
    }

    LaunchedEffect(cameraPositionState) {
        snapshotFlow {
            cameraPositionState.isMoving to cameraPositionState.cameraMoveStartedReason
        }.collect { (moving, reason) ->
            if (moving && reason == CameraMoveStartedReason.GESTURE) followUser = false
        }
    }

    LaunchedEffect(isTripActive, tripStartTimeMs) {
        while (isTripActive) {
            elapsedTimeMs = System.currentTimeMillis() - tripStartTimeMs
            delay(1000L)
        }
    }

    LaunchedEffect(currentLocation) {
        currentLocation?.let { newLoc ->
            if (isTripActive) {
                val currentSpeed = if (newLoc.hasSpeed()) newLoc.speed * MS_TO_KMH else 0.0
                if (currentSpeed > maxSpeedKmh) {
                    maxSpeedKmh = currentSpeed
                }
                previousLocation?.let { oldLoc ->
                    val distance = oldLoc.distanceTo(newLoc)
                    if (distance >= 2.0f && newLoc.accuracy < 30f) {
                        totalDistanceMeters += distance
                    }
                }
                previousLocation = newLoc
            }
        }
    }

    DisposableEffect(lifecycleOwner, hasPermission) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasBackgroundPermission = hasBackgroundLocationPermission(context)
                    if (hasPermission) tracker.start { currentLocation = it }
                }
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

    LaunchedEffect(currentLocation) {
        currentLocation?.let { location ->
            if (followUser) {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLng(LatLng(location.latitude, location.longitude))
                )
            }
        }
    }

    // Atualização de meteorologia apenas quando online (Standby offline - RNF 6 e 10)
    LaunchedEffect(weatherLocationKey, isOnline) {
        if (!isOnline) return@LaunchedEffect
        val location = currentLocation ?: run {
            temperatureC = null
            weatherCode = null
            return@LaunchedEffect
        }

        try {
            val response = WeatherClient.api.getCurrent(location.latitude, location.longitude)
            if (response.isSuccessful) {
                response.body()?.current?.let { weather ->
                    temperatureC = weather.temperature
                    weatherCode = weather.weatherCode
                }
            }
        } catch (_: Exception) {
            // Meteorologia é opcional
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            onMapClick = {
                reportLocation = it
                reportError = null
            },
            properties = MapProperties(isMyLocationEnabled = hasPermission),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = false,
                zoomControlsEnabled = false
            )
        ) {
            geofenceCenter?.let { center ->
                Circle(
                    center = center,
                    radius = geofenceRadius.toDouble(),
                    fillColor = Color(0x334285F4),
                    strokeColor = Color(0xFF4285F4),
                    strokeWidth = 4f
                )
            }

            bookmarks.forEach { bookmark ->
                Marker(
                    state = rememberMarkerState(position = LatLng(bookmark.latitude, bookmark.longitude)),
                    title = bookmark.type,
                    snippet = bookmark.description,
                    icon = BitmapDescriptorFactory.defaultMarker(bookmarkHue(bookmark.type)),
                    onInfoWindowClick = {
                        selectedBookmark = bookmark
                        bookmarkActionError = null
                    }
                )
            }
        }

        val geofenceActive = geofenceCenter != null

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            // Banner indicador de Modo Offline / Standby (RNF 1, 5, 6 e 10)
            AnimatedVisibility(
                visible = !isOnline,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = "Offline",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Modo Offline — Sincronização em pausa",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    FloatingActionButton(
                        onClick = { showLocationMenu = true },
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Mais opções")
                    }

                    DropdownMenu(
                        expanded = showLocationMenu,
                        onDismissRequest = { showLocationMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (geofenceActive) "Redefinir / Remover Geofence" else "Definir Perímetro (Geofence)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (geofenceActive) Icons.Default.EditLocationAlt else Icons.Default.AddLocationAlt,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showLocationMenu = false
                                when {
                                    !hasPermission -> Toast.makeText(
                                        context, "Permita primeiro o acesso à localização.", Toast.LENGTH_SHORT
                                    ).show()

                                    currentLocation == null -> Toast.makeText(
                                        context, "A aguardar localização GPS...", Toast.LENGTH_SHORT
                                    ).show()

                                    else -> {
                                        dialogRadius = if (geofenceActive) geofenceRadius else DEFAULT_GEOFENCE_RADIUS_M
                                        showGeofenceDialog = true
                                    }
                                }
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = { Text("Comunicar Perigo / Ocorrência") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.AddLocationAlt,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showLocationMenu = false
                                currentLocation?.let {
                                    reportLocation = LatLng(it.latitude, it.longitude)
                                    reportError = null
                                }
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = { Text("Histórico de viagens") },
                            leadingIcon = {
                                Icon(Icons.Default.History, contentDescription = null)
                            },
                            onClick = {
                                showLocationMenu = false
                                showTripHistory = true
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Perfil") },
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null)
                            },
                            onClick = {
                                showLocationMenu = false
                                showProfileDialog = true
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Reiniciar / Guardar viagem") },
                            leadingIcon = {
                                Icon(Icons.Default.RestartAlt, contentDescription = null)
                            },
                            onClick = {
                                showLocationMenu = false
                                finishAndSaveTrip()
                            }
                        )
                    }
                }

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
                    }
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Centrar na minha posição")
                }

                if (showProfileDialog) {
                    ProfileDialog(
                        token = token,
                        onDismiss = { showProfileDialog = false },
                        onLogout = {
                            showProfileDialog = false
                            performLogout()
                        }
                    )
                }
            }

            WeatherCard(
                temperatureC = temperatureC,
                weatherCode = weatherCode,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 400.dp)
                    .padding(horizontal = 16.dp)
            )
        }

        ElevatedCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            shape = RoundedCornerShape(20.dp)
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
                            if (tripStartTimeMs == 0L) {
                                tripStartTimeMs = System.currentTimeMillis()
                            } else {
                                tripStartTimeMs = System.currentTimeMillis() - elapsedTimeMs
                            }
                            isTripActive = true
                        }
                    },
                    onFinishTrip = {
                        finishAndSaveTrip()
                    }
                )
            }
        }
    }

    if (showGeofenceDialog) {
        GeofenceDialog(
            radius = dialogRadius,
            onRadiusChange = { dialogRadius = it },
            isActive = geofenceCenter != null,
            onConfirm = {
                val loc = currentLocation
                showGeofenceDialog = false
                if (loc != null) {
                    val center = LatLng(loc.latitude, loc.longitude)
                    pendingGeofence = center to dialogRadius
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (hasBackgroundPermission) {
                        pendingGeofence = null
                        registerGeofence(center, dialogRadius)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        pendingGeofence = null
                        registerGeofence(center, dialogRadius)
                    }
                }
            },
            onRemove = {
                showGeofenceDialog = false
                removeGeofence()
            },
            onDismiss = { showGeofenceDialog = false }
        )
    }

    if (showTripHistory) {
        TripHistoryDialog(
            entries = tripHistory.asReversed(),
            onDeleteEntry = { entryToDelete ->
                tripHistory.remove(entryToDelete)
                saveTripHistory(context, tripHistory)
            },
            onClearAll = {
                tripHistory.clear()
                saveTripHistory(context, tripHistory)
            },
            onDismiss = { showTripHistory = false }
        )
    }

    reportLocation?.let { location ->
        AlertDialog(
            onDismissRequest = { reportLocation = null },
            title = { Text("Comunicar perigo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Escolha o tipo de ocorrência para este local.")
                    listOf("Acidente" to "Acidente", "Perigo" to "Perigo", "Estrada Fechada" to "Estrada Fechada")
                        .forEach { (value, label) ->
                            FilterChip(
                                selected = reportType == value,
                                onClick = { reportType = value },
                                label = { Text(label) }
                            )
                        }
                    OutlinedTextField(
                        value = reportDescription,
                        onValueChange = { reportDescription = it },
                        label = { Text("Descrição (opcional)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    reportError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (reportSubmitting) return@TextButton
                        reportSubmitting = true
                        scope.launch {
                            val request = BookmarkRequest(
                                type = reportType,
                                description = reportDescription.trim().ifBlank { null },
                                latitude = location.latitude,
                                longitude = location.longitude
                            )
                            val localId = -System.currentTimeMillis()

                            if (!isOnline) {
                                bookmarkDao.insert(
                                    BookmarkEntity(
                                        id = localId,
                                        type = request.type,
                                        description = request.description,
                                        latitude = request.latitude,
                                        longitude = request.longitude,
                                        createdAtMs = System.currentTimeMillis(),
                                        syncState = BookmarkEntity.SYNC_STATE_PENDING_INSERT
                                    )
                                )
                                reportDescription = ""
                                reportLocation = null
                                Toast.makeText(
                                    context,
                                    "Ocorrência guardada localmente. Será sincronizada assim que tiver ligação.",
                                    Toast.LENGTH_LONG
                                ).show()
                                reportSubmitting = false
                                return@launch
                            }

                            try {
                                val response = bookmarkRepository.createBookmark(token, request)
                                if (response.isSuccessful) {
                                    response.body()?.let { newBookmark ->
                                        val validCreatedAt = if (newBookmark.createdAtMs > 0) newBookmark.createdAtMs else System.currentTimeMillis()
                                        bookmarkDao.insert(newBookmark.copy(createdAtMs = validCreatedAt).toEntity(BookmarkEntity.SYNC_STATE_SYNCED))
                                    }
                                    Toast.makeText(context, "Ocorrência comunicada com sucesso!", Toast.LENGTH_SHORT).show()
                                    reportDescription = ""
                                    reportLocation = null
                                } else {
                                    bookmarkDao.insert(
                                        BookmarkEntity(
                                            id = localId,
                                            type = request.type,
                                            description = request.description,
                                            latitude = request.latitude,
                                            longitude = request.longitude,
                                            createdAtMs = System.currentTimeMillis(),
                                            syncState = BookmarkEntity.SYNC_STATE_PENDING_INSERT
                                        )
                                    )
                                    reportDescription = ""
                                    reportLocation = null
                                    Toast.makeText(
                                        context,
                                        "Ocorrência guardada localmente. Será sincronizada em segundo plano.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (_: Exception) {
                                bookmarkDao.insert(
                                    BookmarkEntity(
                                        id = localId,
                                        type = request.type,
                                        description = request.description,
                                        latitude = request.latitude,
                                        longitude = request.longitude,
                                        createdAtMs = System.currentTimeMillis(),
                                        syncState = BookmarkEntity.SYNC_STATE_PENDING_INSERT
                                    )
                                )
                                reportDescription = ""
                                reportLocation = null
                                Toast.makeText(
                                    context,
                                    "Ocorrência guardada localmente. Será sincronizada assim que tiver ligação.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                reportSubmitting = false
                            }
                        }
                    }
                ) {
                    if (reportSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Comunicar")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { reportLocation = null }) { Text("Cancelar") }
            }
        )
    }

    selectedBookmark?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { selectedBookmark = null },
            title = { Text(bookmark.type) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bookmark.description ?: "Sem descrição")
                    Text("Confirmações: ${bookmark.confirmations}")
                    bookmarkActionError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (!isOnline) {
                                    val updatedBookmark = bookmark.copy(confirmations = bookmark.confirmations + 1)
                                    bookmarkDao.insert(updatedBookmark.toEntity())
                                    selectedBookmark = null
                                    Toast.makeText(context, "Confirmação registada localmente.", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                try {
                                    val response = bookmarkRepository.confirmBookmark(token, bookmark.id)
                                    if (response.isSuccessful) {
                                        val updatedBookmark = bookmark.copy(confirmations = bookmark.confirmations + 1)
                                        bookmarkDao.insert(updatedBookmark.toEntity())
                                        selectedBookmark = null
                                    } else {
                                        bookmarkActionError = response.body()?.message ?: "Não foi possível confirmar."
                                    }
                                } catch (_: Exception) {
                                    val updatedBookmark = bookmark.copy(confirmations = bookmark.confirmations + 1)
                                    bookmarkDao.insert(updatedBookmark.toEntity())
                                    selectedBookmark = null
                                    Toast.makeText(context, "Confirmação registada localmente.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) { Text("Confirmar") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (!isOnline) {
                                    selectedBookmark = null
                                    Toast.makeText(context, "Sinalização registada localmente.", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                try {
                                    val response = bookmarkRepository.reportBookmark(
                                        token = token,
                                        id = bookmark.id,
                                        req = BookmarkReportRequest("Informação incorreta")
                                    )
                                    if (response.isSuccessful) selectedBookmark = null
                                    else bookmarkActionError = response.body()?.message ?: "Não foi possível sinalizar."
                                } catch (_: Exception) {
                                    selectedBookmark = null
                                    Toast.makeText(context, "Sinalização registada localmente.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) { Text("Sinalizar") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            if (bookmark.id < 0) {
                                bookmarkDao.deleteById(bookmark.id)
                                selectedBookmark = null
                                Toast.makeText(context, "Ocorrência local removida.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }

                            if (!isOnline) {
                                bookmarkRepository.markForPendingDelete(bookmark.id)
                                selectedBookmark = null
                                Toast.makeText(context, "Remoção agendada para quando houver ligação.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }

                            try {
                                val response = bookmarkRepository.deleteBookmark(token, bookmark.id)
                                if (response.isSuccessful || response.code() == 404) {
                                    bookmarkDao.deleteById(bookmark.id)
                                    selectedBookmark = null
                                    Toast.makeText(context, "Ocorrência removida.", Toast.LENGTH_SHORT).show()
                                } else {
                                    bookmarkActionError = response.body()?.message ?: "Só pode remover as suas ocorrências."
                                }
                            } catch (_: Exception) {
                                bookmarkRepository.markForPendingDelete(bookmark.id)
                                selectedBookmark = null
                                Toast.makeText(context, "Remoção agendada para quando houver ligação.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) { Text("Remover") }
            }
        )
    }
}

@Composable
private fun WeatherCard(
    temperatureC: Double?,
    weatherCode: Int?,
    modifier: Modifier = Modifier
) {
    val temperature = temperatureC ?: return
    val (icon, description) = weatherPresentation(weatherCode)

    ElevatedCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(imageVector = icon, contentDescription = description)
            Text(
                text = "${temperature.roundToInt()} °C",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

 // Diálogo para consulta do histórico de deslocações e respetivos detalhes.
@Composable
private fun TripHistoryDialog(
    entries: List<TripHistoryEntry>,
    onDeleteEntry: (TripHistoryEntry) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedEntry by remember { mutableStateOf<TripHistoryEntry?>(null) }

    if (selectedEntry != null) {
        TripDetailDialog(
            entry = selectedEntry!!,
            onDismiss = { selectedEntry = null }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Histórico de viagens")
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = onClearAll) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Limpar histórico",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            text = {
                if (entries.isEmpty()) {
                    Text("Ainda não existem viagens guardadas.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            OutlinedCard(
                                onClick = { selectedEntry = entry },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale.getDefault())
                                                .format(Date(entry.startedAtMs)),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${formatDistanceValue(entry.distanceMeters)} ${formatDistanceUnit(entry.distanceMeters)} · ${formatDuration(entry.durationMs)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Vel. Média: %.1f km/h".format(entry.averageSpeedKmh),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { onDeleteEntry(entry) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Apagar viagem",
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        )
    }
}

 // Diálogo modal para consultar em detalhe as métricas de uma deslocação específica.
@Composable
private fun TripDetailDialog(
    entry: TripHistoryEntry,
    onDismiss: () -> Unit
) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    val startDateStr = dateFormat.format(Date(entry.startedAtMs))
    val endDateStr = dateFormat.format(Date(entry.endedAtMs))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Detalhes da Viagem")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow(label = "Início", value = startDateStr)
                DetailRow(label = "Fim", value = endDateStr)
                HorizontalDivider()
                DetailRow(
                    label = "Distância Total",
                    value = "${formatDistanceValue(entry.distanceMeters)} ${formatDistanceUnit(entry.distanceMeters)}"
                )
                DetailRow(
                    label = "Duração Total",
                    value = formatDuration(entry.durationMs)
                )
                DetailRow(
                    label = "Velocidade Média",
                    value = "%.1f km/h".format(entry.averageSpeedKmh)
                )
                if (entry.maxSpeedKmh > 0) {
                    DetailRow(
                        label = "Velocidade Máxima",
                        value = "%.1f km/h".format(entry.maxSpeedKmh)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Voltar") }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

private fun weatherPresentation(code: Int?): Pair<ImageVector, String> = when (code) {
    0 -> Icons.Default.WbSunny to "Céu limpo"
    1, 2, 3 -> Icons.Default.Cloud to "Parcialmente nublado"
    45, 48 -> Icons.Default.Cloud to "Nevoeiro"
    in 51..67, in 80..82 -> Icons.Default.WaterDrop to "Chuva"
    in 71..77, in 85..86 -> Icons.Default.AcUnit to "Neve"
    in 95..99 -> Icons.Default.Bolt to "Trovoada"
    else -> Icons.Default.Cloud to "Condição desconhecida"
}

@Composable
private fun GeofenceDialog(
    radius: Float,
    onRadiusChange: (Float) -> Unit,
    isActive: Boolean,
    onConfirm: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var textValue by remember(radius) { mutableStateOf(if (radius > 0) radius.toInt().toString() else "") }
    val parsedValue = textValue.toFloatOrNull()
    val isValid = parsedValue != null && parsedValue > 0f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isActive) "Redefinir geofence" else "Definir geofence") },
        text = {
            Column {
                Text(
                    text = "Cria uma zona circular centrada na sua posição atual. " +
                            "Será notificado ao entrar e ao sair.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { input ->
                        textValue = input
                        val newRadius = input.toFloatOrNull()
                        if (newRadius != null && newRadius > 0f) {
                            onRadiusChange(newRadius)
                        }
                    },
                    label = { Text("Raio (metros)") },
                    suffix = { Text("m") },
                    singleLine = true,
                    isError = !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onRemove) {
                        Text("Remover geofence atual", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = isValid
            ) { Text(if (isActive) "Redefinir aqui" else "Definir aqui") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

private fun bookmarkHue(type: String): Float = when (type) {
    "Acidente", "ACCIDENT" -> BitmapDescriptorFactory.HUE_RED
    "Perigo", "DANGER" -> BitmapDescriptorFactory.HUE_ORANGE
    "Estrada Fechada", "Estrada fechada", "ROAD_CLOSED" -> BitmapDescriptorFactory.HUE_VIOLET
    else -> BitmapDescriptorFactory.HUE_AZURE
}

@Composable
private fun LocationInfoPanel(
    location: Location,
    totalDistanceMeters: Float,
    elapsedTimeMs: Long,
    isTripActive: Boolean,
    onToggleTrip: () -> Unit,
    onFinishTrip: () -> Unit
) {
    val speedKmh = if (location.hasSpeed()) location.speed * MS_TO_KMH else 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TripMetricItem(
                icon = Icons.Default.Speed,
                value = "%.0f".format(speedKmh),
                unit = "km/h",
                label = "Velocidade"
            )

            TripMetricItem(
                icon = Icons.Default.Route,
                value = formatDistanceValue(totalDistanceMeters),
                unit = formatDistanceUnit(totalDistanceMeters),
                label = "Distância"
            )

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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

                if (isTripActive || elapsedTimeMs > 0L) {
                    IconButton(onClick = onFinishTrip) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Finalizar e Guardar Viagem",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
