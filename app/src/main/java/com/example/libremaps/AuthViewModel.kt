package com.example.libremaps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val loggedIn: Boolean = false,
    val registered: Boolean = false
)

class AuthViewModel : ViewModel() {
    var state by mutableStateOf(AuthUiState())
        private set

    fun clearError() {
        if (state.error != null || state.successMessage != null) {
            state = state.copy(error = null, successMessage = null)
        }
    }

    fun consumeRegistration() {
        if (state.registered) {
            state = state.copy(registered = false)
        }
    }

    fun submit(email: String, password: String, isRegister: Boolean) {
        viewModelScope.launch {
            state = AuthUiState(loading = true)
            try {
                val req = AuthRequest(email.trim(), password)
                val res = if (isRegister) ApiClient.api.register(req) else ApiClient.api.login(req)
                state = if (res.isSuccessful) {
                    if (isRegister) {
                        AuthUiState(
                            registered = true,
                            successMessage = "Conta criada com sucesso! Inicie sessão para continuar."
                        )
                    } else {
                        AuthUiState(loggedIn = true)
                    }
                } else {
                    val serverMsg = try {
                        val errorBody = res.errorBody()?.string()
                        if (!errorBody.isNullOrBlank()) {
                            Gson().fromJson(errorBody, AuthResponse::class.java)?.message
                        } else null
                    } catch (_: Exception) {
                        null
                    }

                    val msg = serverMsg ?: when (res.code()) {
                        400 -> "Email inválido ou password com menos de 8 caracteres"
                        401 -> "Email ou password incorretos"
                        409 -> "Este email já está registado"
                        else -> "Erro ${res.code()}"
                    }
                    AuthUiState(error = msg)
                }
            } catch (e: Exception) {
                state = AuthUiState(error = "Sem ligação ao servidor: ${e.localizedMessage ?: e.message}")
            }
        }
    }
}
