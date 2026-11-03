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
import com.example.libremaps.ui.theme.LibremapsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Infla o layout XML tradicional (activity_main.xml)
        setContentView(R.layout.activity_main)

        // 2. Obtém a referência do ComposeView presente no XML
        val composeView = findViewById<ComposeView>(R.id.compose_view)

        // 3. Define a UI em Jetpack Compose dentro do ComposeView hospedado no XML
        composeView.setContent {
            LibremapsTheme {
                // rememberSaveable mantém o estado ao rodar o ecrã
                var loggedIn by rememberSaveable { mutableStateOf(false) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (loggedIn) {
                            MenuInicial()
                        } else {
                            Login(
                                onLoggedIn = {
                                    // Login válido -> abre o menu inicial
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
