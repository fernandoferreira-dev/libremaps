package com.example.libremaps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.libremaps.ui.theme.LibremapsTheme
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices

class MainActivity : ComponentActivity() {
    lateinit var geofencingClient: GeofencingClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        geofencingClient = LocationServices.getGeofencingClient(this)
        enableEdgeToEdge()

        val sessionManager = UserSessionManager(this)

        //Infla o layout XML tradicional (activity_main.xml)
        setContentView(R.layout.activity_main)

        // Obtém a referência do ComposeView presente no XML
        val composeView = findViewById<ComposeView>(R.id.compose_view)

        // Define a UI em Jetpack Compose dentro do ComposeView hospedado no XML
        composeView.setContent {
            LibremapsTheme {
                val authViewModel: AuthViewModel = viewModel()

                // Restaura a sessão guardada, se já tiver login anteriormente
                var loggedIn by rememberSaveable { mutableStateOf(sessionManager.isLoggedIn()) }
                var sessionToken by rememberSaveable { mutableStateOf<String?>(sessionManager.getToken()) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (loggedIn) {
                            MenuInicial(
                                token = sessionToken.orEmpty(),
                                onLogout = {
                                    authViewModel.logout(sessionManager)
                                    loggedIn = false
                                    sessionToken = null
                                }
                            )
                        } else {
                            Login(
                                vm = authViewModel,
                                onLoggedIn = { token ->
                                    // Login válido -> abre o menu inicial
                                    sessionToken = token
                                    loggedIn = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}