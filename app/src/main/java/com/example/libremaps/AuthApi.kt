package com.example.libremaps

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class AuthRequest(val email: String, val password: String)
data class AuthResponse(val success: Boolean, val message: String)

interface AuthApi {
    @POST("login") suspend fun login(@Body req: AuthRequest): Response<AuthResponse>
    @POST("register") suspend fun register(@Body req: AuthRequest): Response<AuthResponse>
}

object ApiClient {
    val api: AuthApi = Retrofit.Builder()
        //.baseUrl("http://127.0.0.1:8080/") - fix pessoal porque deu problema :P
        .baseUrl("http://10.0.2.2:8080/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AuthApi::class.java)
}
