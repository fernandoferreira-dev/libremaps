package com.example.libremaps.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class LightSensorTracker(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var sensorEventListener: SensorEventListener? = null

    val isSupported: Boolean get() = lightSensor != null

    fun start(onLightChanged: (Float) -> Unit): Boolean {
        val sensor = lightSensor ?: return false
        stop()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_LIGHT) {
                    onLightChanged(event.values[0]) // Lux value
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorEventListener = listener
        return sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorEventListener?.let {
            sensorManager.unregisterListener(it)
        }
        sensorEventListener = null
    }
}