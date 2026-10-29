package com.example.libremaps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat

class LocationTracker(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var listener: LocationListener? = null

    companion object {
        private const val MIN_TIME_MS = 1000L
        private const val MIN_DISTANCE_METERS = 1f
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

        val newListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocationChanged(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = newListener

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.filter { locationManager.isProviderEnabled(it) }.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider, MIN_TIME_MS, MIN_DISTANCE_METERS, newListener
            )
            // Mostra a última posição conhecida
            locationManager.getLastKnownLocation(provider)?.let(onLocationChanged)
        }
        return true
    }

    fun stop() {
        listener?.let { locationManager.removeUpdates(it) }
        listener = null
    }
}