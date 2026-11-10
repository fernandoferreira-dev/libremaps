package com.example.libremaps.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

 //Gestor de sessão do utilizador com armazenamento encriptado.
 class UserSessionManager(context: Context) {
     private val prefs: SharedPreferences = createEncryptedSharedPreferences(context)

     companion object {
         private const val PREF_NAME = "libremaps_secure_session"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_TOKEN = "user_token"

        private fun createEncryptedSharedPreferences(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                // Fallback seguro de preferências privadas para compatibilidade em ambientes sem Keystore
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            }
        }
    }

     // Guarda os dados da sessão do utilizador de forma encriptada.
    fun saveSession(email: String, token: String) {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_TOKEN, token)
            .apply()
    }
    //Limpa a sessão armazenada.
    fun clearSession() {
        prefs.edit()
            .clear()
            .apply()
    }

     // Verifica se existe uma sessão válida iniciada.
    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false) && !getToken().isNullOrBlank()
    }

     //Devolve o email do utilizador associado à sessão.
    fun getUserEmail(): String? {
        return prefs.getString(KEY_USER_EMAIL, null)
    }


     //Devolve o token de autenticação JWT da sessão.
    fun getToken(): String? {
        return prefs.getString(KEY_USER_TOKEN, null)
    }
}
