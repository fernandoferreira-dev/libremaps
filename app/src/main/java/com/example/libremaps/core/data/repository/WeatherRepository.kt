package com.example.libremaps.core.data.repository

import com.example.libremaps.core.model.WeatherResponse
import com.example.libremaps.core.network.WeatherApi
import com.example.libremaps.core.network.WeatherClient
import retrofit2.Response

class WeatherRepository(private val api: WeatherApi = WeatherClient.api) {
    suspend fun getCurrentWeather(latitude: Double, longitude: Double): Response<WeatherResponse> {
        return api.getCurrent(latitude, longitude)
    }
}
