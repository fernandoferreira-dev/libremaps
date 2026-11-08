package com.example.libremaps.core.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e("Geofence", "Error code: ${event.errorCode}")
            return
        }

        val fences = event.triggeringGeofences ?: return

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> Log.d("Geofence", "Entered: ${fences.map { it.requestId }}")
            Geofence.GEOFENCE_TRANSITION_EXIT  -> Log.d("Geofence", "Exited: ${fences.map { it.requestId }}")
        }
    }
}
