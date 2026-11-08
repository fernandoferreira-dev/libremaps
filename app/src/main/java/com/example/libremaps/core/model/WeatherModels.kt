package com.example.libremaps.core.model

import com.google.gson.annotations.SerializedName

data class WeatherResponse(val current: CurrentWeather? = null)

data class CurrentWeather(
    @SerializedName("temperature_2m") val temperature: Double,
    @SerializedName("weather_code") val weatherCode: Int
)
