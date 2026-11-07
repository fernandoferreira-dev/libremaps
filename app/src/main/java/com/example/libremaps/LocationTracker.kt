package com.example.libremaps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationTracker(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null

    companion object {
        // Intervalo de 3 segundos com mínimo de 2s e 3m de deslocamento
        // reduz significativamente o consumo de bateria comparado com a escuta contínua de 1s
        private const val UPDATE_INTERVAL_MS = 3000L
        private const val MIN_UPDATE_INTERVAL_MS = 2000L
        private const val MIN_DISTANCE_METERS = 3f
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(onLocationChanged: (Location) -> Unit): Boolean {
        if (!hasPermission()) return false
        stop()

        // 1. Obtém a última localização conhecida para resposta imediata sem aguardar pelo lock de GPS
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            loc?.let(onLocationChanged)
        }

        // 2. Configura a requisição com Fused Location Provider (combinando GPS, Wi-Fi e sensores)
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(false)
        }.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(onLocationChanged)
            }
        }
        locationCallback = callback

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )
        return true
    }

    fun stop() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }
}
