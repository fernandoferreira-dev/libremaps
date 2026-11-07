package com.example.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureDatabases()
    configureHttp()
    configureSerialization()
    configureRouting()
    launch(Dispatchers.IO) {
        while (true) {
            runCatching { purgeExpiredBookmarks() }
            delay(60 * 60 * 1000L) // de hora a hora
        }
    }
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}

private fun purgeExpiredBookmarks() {
    val expired = "SELECT id FROM map_bookmarks WHERE created_at <= (NOW() - INTERVAL 48 HOUR)"
    dataSource.connection.use { c ->
        c.createStatement().use { s ->
            s.executeUpdate("DELETE FROM bookmark_reports WHERE bookmark_id IN ($expired)")
            s.executeUpdate("DELETE FROM bookmark_confirmations WHERE bookmark_id IN ($expired)")
            s.executeUpdate("DELETE FROM map_bookmarks WHERE created_at <= (NOW() - INTERVAL 48 HOUR)")
        }
    }
}