package com.example.libremaps

import android.util.Patterns
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
    val token: String? = null,
    val registered: Boolean = false,
    val passwordResetSuccess: Boolean = false,
    val passwordChangedSuccess: Boolean = false
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

    fun consumePasswordReset() {
        if (state.passwordResetSuccess) {
            state = state.copy(passwordResetSuccess = false)
        }
    }

    fun consumePasswordChanged() {
        if (state.passwordChangedSuccess) {
            state = state.copy(passwordChangedSuccess = false)
        }
    }

    // ---------- Validações ----------
    private fun validateEmail(email: String): String? {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return "O email é obrigatório"
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) return "Formato de email inválido"
        return null
    }

    private fun validatePassword(password: String): String? {
        if (password.length < 8) return "A palavra-passe deve ter pelo menos 8 caracteres"
        return null
    }

    // ---------- Login / Registo ----------
    fun submit(
        email: String,
        password: String,
        confirmPassword: String = "",
        isRegister: Boolean,
        sessionManager: UserSessionManager? = null
    ) {
        val emailError = validateEmail(email)
        if (emailError != null) {
            state = AuthUiState(error = emailError)
            return
        }

        val passwordError = validatePassword(password)
        if (passwordError != null) {
            state = AuthUiState(error = passwordError)
            return
        }

        if (isRegister && password != confirmPassword) {
            state = AuthUiState(error = "As palavras-passe não coincidem")
            return
        }

        viewModelScope.launch {
            state = AuthUiState(loading = true)
            try {
                val req = AuthRequest(email.trim(), password)
                val res = if (isRegister) ApiClient.api.register(req) else ApiClient.api.login(req)
                state = if (res.isSuccessful) {
                    val body = res.body()
                    when {
                        body?.success == false -> AuthUiState(error = body.message)

                        isRegister -> AuthUiState(
                            registered = true,
                            successMessage = "Conta criada com sucesso! Inicie sessão para continuar."
                        )

                        body?.token.isNullOrBlank() -> AuthUiState(
                            error = "O servidor não devolveu o token de sessão. " +
                                    "Confirma que estás a usar a versão do servidor com autenticação por token."
                        )

                        else -> {
                            val token = body?.token.orEmpty()
                            sessionManager?.saveSession(email.trim(), token)
                            AuthUiState(loggedIn = true, token = token)
                        }
                    }
                } else {
                    val serverMsg = parseServerError(res.errorBody()?.string())
                    val msg = serverMsg ?: when (res.code()) {
                        400 -> "Email inválido ou palavra-passe com menos de 8 caracteres"
                        401 -> "Email ou palavra-passe incorretos"
                        409 -> "Este email já está registado"
                        else -> "Erro no servidor (${res.code()})"
                    }
                    AuthUiState(error = msg)
                }
            } catch (e: Exception) {
                state = AuthUiState(error = "Sem ligação ao servidor: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    // ---------- Recuperar palavra-passe ----------
    fun resetPassword(email: String, newPassword: String, confirmPassword: String) {
        val emailError = validateEmail(email)
        if (emailError != null) {
            state = AuthUiState(error = emailError)
            return
        }

        val passwordError = validatePassword(newPassword)
        if (passwordError != null) {
            state = AuthUiState(error = passwordError)
            return
        }

        if (newPassword != confirmPassword) {
            state = AuthUiState(error = "As palavras-passe não coincidem")
            return
        }

        viewModelScope.launch {
            state = AuthUiState(loading = true)
            try {
                val req = ResetPasswordRequest(email.trim(), newPassword)
                val res = ApiClient.api.resetPassword(req)
                state = if (res.isSuccessful) {
                    AuthUiState(
                        passwordResetSuccess = true,
                        successMessage = "Palavra-passe redefinida com sucesso!"
                    )
                } else {
                    val serverMsg = parseServerError(res.errorBody()?.string())
                    val msg = serverMsg ?: when (res.code()) {
                        404 -> "Email não encontrado no sistema"
                        else -> "Erro ao redefinir palavra-passe (${res.code()})"
                    }
                    AuthUiState(error = msg)
                }
            } catch (e: Exception) {
                state = AuthUiState(error = "Sem ligação ao servidor: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    // ---------- Alterar palavra-passe ----------
    fun changePassword(
        email: String,
        oldPassword: String,
        newPassword: String,
        confirmPassword: String,
        onSuccess: () -> Unit = {}
    ) {
        val passwordError = validatePassword(newPassword)
        if (passwordError != null) {
            state = AuthUiState(error = passwordError)
            return
        }

        if (newPassword != confirmPassword) {
            state = AuthUiState(error = "As novas palavras-passe não coincidem")
            return
        }

        viewModelScope.launch {
            state = AuthUiState(loading = true)
            try {
                val req = ChangePasswordRequest(email.trim(), oldPassword, newPassword)
                val res = ApiClient.api.changePassword(req)
                if (res.isSuccessful) {
                    state = AuthUiState(
                        passwordChangedSuccess = true,
                        successMessage = "Palavra-passe alterada com sucesso!"
                    )
                    onSuccess()
                } else {
                    val serverMsg = parseServerError(res.errorBody()?.string())
                    val msg = serverMsg ?: when (res.code()) {
                        401 -> "Palavra-passe atual incorreta"
                        else -> "Erro ao alterar palavra-passe (${res.code()})"
                    }
                    state = AuthUiState(error = msg)
                }
            } catch (e: Exception) {
                state = AuthUiState(error = "Sem ligação ao servidor: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    // ---------- Logout ----------
    fun logout(sessionManager: UserSessionManager? = null) {
        sessionManager?.clearSession()
        state = AuthUiState()
    }

    private fun parseServerError(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            Gson().fromJson(errorBody, AuthResponse::class.java)?.message
        } catch (_: Exception) {
            null
        }
    }
}
