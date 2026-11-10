package com.example.libremaps.core.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.libremaps.MainActivity
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
        val transition = event.geofenceTransition

        val title: String
        val message: String

        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                title = "Entrada no Perímetro"
                message = "Entrou no perímetro definido."
                Log.d("Geofence", "Entered: ${fences.map { it.requestId }}")
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                title = "Saída do Perímetro"
                message = "Saiu do perímetro definido."
                Log.d("Geofence", "Exited: ${fences.map { it.requestId }}")
            }
            else -> return
        }

        showNotification(context, title, message)
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(context: Context, title: String, message: String) {
        val channelId = "geofence_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Geofence Alerts"
            val descriptionText = "Notificações de entrada e saída de perímetros"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(context)) {
                notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
            }
        } catch (e: SecurityException) {
            Log.e("Geofence", "SecurityException posting notification: ${e.message}")
        }
    }
}
