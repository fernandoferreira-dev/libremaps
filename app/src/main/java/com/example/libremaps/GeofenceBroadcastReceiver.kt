package com.example.libremaps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        //definir o evento de quando uma geofence é cruzada. ?: return, serve de failsafe se 'event' for null
        val event = GeofencingEvent.fromIntent(intent) ?: return
        //se acontecer um erro, dizer que o erro é do evento e enviar o codigo do erro
        if (event.hasError()) {
            Log.e("Geofence", "Error code: ${event.errorCode}")
            return
        }

        //definir as fences de onde o evento foi disparado
        val fences = event.triggeringGeofences ?: return

        //quando uma transição acontecer (quando o utilizador sair ou entrar numa geofence)
        //executar uma função
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> Log.d("Geofence", "Entered: ${fences.map { it.requestId }}")
            Geofence.GEOFENCE_TRANSITION_EXIT  -> Log.d("Geofence", "Exited: ${fences.map { it.requestId }}")
        }
    }
}