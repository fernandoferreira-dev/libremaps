package com.example.libremaps.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
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
import com.example.libremaps.core.network.WeatherClient
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
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny

private const val DEFAULT_ZOOM = 17f
private const val MS_TO_KMH = 3.6

private const val GEOFENCE_ID = "user_geofence"
private const val DEFAULT_GEOFENCE_RADIUS_M = 150f

private const val BOOKMARK_TTL_MS = 48L * 60 * 60 * 1000

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

    var hasPermission by remember { mutableStateOf(tracker.hasPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }

    val db = remember { AppDatabase.getDatabase(context) }
    val bookmarkDao = remember { db.bookmarkDao() }
    val localBookmarkEntities by bookmarkDao.getAllBookmarks().collectAsState(initial = emptyList())
    val bookmarks = remember(localBookmarkEntities) {
        val now = System.currentTimeMillis()
        localBookmarkEntities
            .filter { now - it.createdAtMs < BOOKMARK_TTL_MS }
            .map { it.toModel() }
    }

    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var followUser by remember { mutableStateOf(true) }
    var reportLocation by remember { mutableStateOf<LatLng?>(null) }
    var reportType by remember { mutableStateOf("ACCIDENT") }
    var reportDescription by remember { mutableStateOf("") }
    var reportError by remember { mutableStateOf<String?>(null) }
    var reportSubmitting by remember { mutableStateOf(false) }
    var selectedBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var bookmarkActionError by remember { mutableStateOf<String?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }

    var isTripActive by remember { mutableStateOf(true) }
    var totalDistanceMeters by remember { mutableFloatStateOf(0f) }
    var tripStartTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsedTimeMs by remember { mutableLongStateOf(0L) }
    var previousLocation by remember { mutableStateOf<Location?>(null) }

    var geofenceCenter by rememberSaveable { mutableStateOf<LatLng?>(null) }
    var geofenceRadius by rememberSaveable { mutableFloatStateOf(DEFAULT_GEOFENCE_RADIUS_M) }
    var showGeofenceDialog by remember { mutableStateOf(false) }
    var showLocationMenu by remember { mutableStateOf(false) }
    var dialogRadius by remember { mutableFloatStateOf(DEFAULT_GEOFENCE_RADIUS_M) }
    var hasBackgroundPermission by remember { mutableStateOf(hasBackgroundLocationPermission(context)) }
    var pendingGeofence by remember { mutableStateOf<Pair<LatLng, Float>?>(null) }

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
                ApiClient.api.logout("Bearer $token")
            } catch (_: Exception) {
            } finally {
                onLogout()
            }
        }
    }

    suspend fun synchronizeBookmarks() {
        bookmarkDao.getPendingBookmarks().forEach { pending ->
            try {
                val response = ApiClient.api.createBookmark(
                    authorization = "Bearer $token",
                    BookmarkRequest(
                        type = pending.type,
                        description = pending.description,
                        latitude = pending.latitude,
                        longitude = pending.longitude
                    )
                )
                if (response.isSuccessful) {
                    response.body()?.let { remoteBookmark ->
                        bookmarkDao.deleteById(pending.id)
                        bookmarkDao.insert(
                            remoteBookmark.copy(
                                createdAtMs = remoteBookmark.createdAtMs.takeIf { it > 0 }
                                    ?: System.currentTimeMillis()
                            ).toEntity()
                        )
                    }
                }
            } catch (_: Exception) {
                // Keep the pending marker for the next synchronization attempt.
            }
        }

        try {
            val response = ApiClient.api.getBookmarks()
            if (response.isSuccessful) {
                val receivedAt = System.currentTimeMillis()
                response.body().orEmpty().forEach { remoteBookmark ->
                    bookmarkDao.insert(
                        remoteBookmark.copy(
                            createdAtMs = remoteBookmark.createdAtMs.takeIf { it > 0 } ?: receivedAt
                        ).toEntity()
                    )
                }
            }
        } catch (_: Exception) {
            // Local markers remain available while the server is unreachable.
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
                Toast.makeText(context, "Perimetro definido (${radius.toInt()} m)", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Erro ao definir perimetro: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    fun removeGeofence() {
        geofencingClient.removeGeofences(listOf(GEOFENCE_ID))
            .addOnSuccessListener {
                geofenceCenter = null
                Toast.makeText(context, "Perimetro removido", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Erro ao remover perimetro: ${it.message}", Toast.LENGTH_LONG).show()
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
                "Para usar perimetros, escolha \"Permitir sempre\" nas definições de localização.",
                Toast.LENGTH_LONG
            ).show()
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

    LaunchedEffect(token) {
        while (true) {
            synchronizeBookmarks()
            delay(30_000L)
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

    LaunchedEffect(weatherLocationKey) {
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
            // Weather is optional and should not affect map or trip tracking.
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

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                FloatingActionButton(
                    onClick = { showLocationMenu = true },
                    containerColor = if (geofenceActive) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = if (geofenceActive) Icons.Default.EditLocationAlt else Icons.Default.AddLocationAlt,
                        contentDescription = "Menu de Geofence e Ocorrências"
                    )
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
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

                FloatingActionButton(
                    onClick = { showProfileDialog = true }
                ) {
                    Icon(Icons.Default.Person, contentDescription = "Perfil de Utilizador")
                }
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
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 88.dp, start = 16.dp, end = 16.dp)
        )

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
                    if (hasBackgroundPermission) {
                        registerGeofence(center, dialogRadius)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        pendingGeofence = center to dialogRadius
                        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
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

    reportLocation?.let { location ->
        AlertDialog(
            onDismissRequest = { reportLocation = null },
            title = { Text("Comunicar perigo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Escolha o tipo de ocorrência para este local.")
                    listOf("ACCIDENT" to "Acidente", "DANGER" to "Perigo", "ROAD_CLOSED" to "Estrada fechada")
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
                            bookmarkDao.insert(
                                BookmarkEntity(
                                    id = localId,
                                    type = request.type,
                                    description = request.description,
                                    latitude = request.latitude,
                                    longitude = request.longitude,
                                    createdAtMs = System.currentTimeMillis()
                                )
                            )
                            try {
                                val response = ApiClient.api.createBookmark(
                                    authorization = "Bearer $token",
                                    request
                                )
                                if (response.isSuccessful) {
                                    response.body()?.let { newBookmark ->
                                        bookmarkDao.deleteById(localId)
                                        bookmarkDao.insert(newBookmark.toEntity())
                                    }
                                    reportDescription = ""
                                    reportLocation = null
                                } else {
                                    reportDescription = ""
                                    reportLocation = null
                                    Toast.makeText(
                                        context,
                                        "Ocorrência guardada localmente; será sincronizada quando houver ligação.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (_: Exception) {
                                reportDescription = ""
                                reportLocation = null
                                Toast.makeText(
                                    context,
                                    "Ocorrência guardada localmente; será sincronizada quando houver ligação.",
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
                                try {
                                    val response = ApiClient.api.confirmBookmark("Bearer $token", bookmark.id)
                                    if (response.isSuccessful) {
                                        val updatedBookmark = bookmark.copy(confirmations = bookmark.confirmations + 1)
                                        bookmarkDao.insert(updatedBookmark.toEntity())
                                        selectedBookmark = null
                                    } else {
                                        bookmarkActionError = response.body()?.message ?: "Não foi possível confirmar."
                                    }
                                } catch (_: Exception) {
                                    bookmarkActionError = "Sem ligação ao servidor."
                                }
                            }
                        }
                    ) { Text("Confirmar") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val response = ApiClient.api.reportBookmark(
                                        authorization = "Bearer $token",
                                        id = bookmark.id,
                                        req = BookmarkReportRequest("Informação incorreta")
                                    )
                                    if (response.isSuccessful) selectedBookmark = null
                                    else bookmarkActionError = response.body()?.message ?: "Não foi possível sinalizar."
                                } catch (_: Exception) {
                                    bookmarkActionError = "Sem ligação ao servidor."
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
                                    return@launch
                                }
                            try {
                                val response = ApiClient.api.deleteBookmark("Bearer $token", bookmark.id)
                                if (response.isSuccessful) {
                                    bookmarkDao.deleteById(bookmark.id)
                                    selectedBookmark = null
                                } else {
                                    bookmarkActionError = response.body()?.message ?: "Só pode remover as suas ocorrências."
                                }
                            } catch (_: Exception) {
                                bookmarkActionError = "Sem ligação ao servidor."
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
    "ACCIDENT" -> BitmapDescriptorFactory.HUE_RED
    "DANGER" -> BitmapDescriptorFactory.HUE_ORANGE
    "ROAD_CLOSED" -> BitmapDescriptorFactory.HUE_VIOLET
    else -> BitmapDescriptorFactory.HUE_AZURE
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
                IconButton(onClick = onResetTrip) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reiniciar viagem",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
