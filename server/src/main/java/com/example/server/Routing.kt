package com.example.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException

@Serializable
data class AuthRequest(val email: String, val password: String)

@Serializable
data class AuthResponse(val success: Boolean, val message: String)

@Serializable
data class BookmarkRequest(
    val type: String,
    val description: String? = null,
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class Bookmark(
    val id: Long,
    val type: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double
)

fun Application.configureRouting() {
    routing {
        get("/bookmarks") {
            try {
                val bookmarks = dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "SELECT id, type, description, latitude, longitude FROM map_bookmarks " +
                            "WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL 24 HOUR " +
                            "ORDER BY created_at DESC"
                    ).use { statement ->
                        statement.executeQuery().use { result ->
                            buildList {
                                while (result.next()) {
                                    add(
                                        Bookmark(
                                            id = result.getLong("id"),
                                            type = result.getString("type"),
                                            description = result.getString("description"),
                                            latitude = result.getDouble("latitude"),
                                            longitude = result.getDouble("longitude")
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                call.respond(bookmarks)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, emptyList<Bookmark>())
            }
        }

        post("/bookmarks") {
            val req = call.receive<BookmarkRequest>()
            val allowedTypes = setOf("ACCIDENT", "DANGER", "ROAD_CLOSED")
            if (req.type !in allowedTypes || req.latitude !in -90.0..90.0 || req.longitude !in -180.0..180.0) {
                return@post call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "Dados da ocorrência inválidos"))
            }

            try {
                val bookmark = dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO map_bookmarks (type, description, latitude, longitude) VALUES (?, ?, ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS
                    ).use { statement ->
                        statement.setString(1, req.type)
                        statement.setString(2, req.description?.take(255))
                        statement.setDouble(3, req.latitude)
                        statement.setDouble(4, req.longitude)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            keys.next()
                            Bookmark(keys.getLong(1), req.type, req.description?.take(255), req.latitude, req.longitude)
                        }
                    }
                }
                call.respond(HttpStatusCode.Created, bookmark)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, AuthResponse(false, "Erro ao guardar a ocorrência"))
            }
        }

        post("/register") {
            val req = call.receive<AuthRequest>()
            val email = req.email.trim().lowercase()

            if (!email.contains("@") || req.password.length < 8) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(false, "Email inválido ou password com menos de 8 caracteres")
                )
            }

            val hash = BCrypt.hashpw(req.password, BCrypt.gensalt())
            try {
                dataSource.connection.use { c ->
                    c.prepareStatement("INSERT INTO users (email, password_hash) VALUES (?, ?)").use { stmt ->
                        stmt.setString(1, email)
                        stmt.setString(2, hash)
                        stmt.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.Created, AuthResponse(true, "Conta criada"))
            } catch (e: Exception) {
                if (e is SQLIntegrityConstraintViolationException || (e is SQLException && e.errorCode == 1062)) {
                    call.respond(HttpStatusCode.Conflict, AuthResponse(false, "Email já registado"))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AuthResponse(false, "Erro no servidor ao criar conta")
                    )
                }
            }
        }

        post("/login") {
            val req = call.receive<AuthRequest>()
            val email = req.email.trim().lowercase()

            try {
                val hash = dataSource.connection.use { c ->
                    c.prepareStatement("SELECT password_hash FROM users WHERE email = ?").use { stmt ->
                        stmt.setString(1, email)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) rs.getString("password_hash") else null
                        }
                    }
                }

                val passwordValid = hash != null && try {
                    BCrypt.checkpw(req.password, hash)
                } catch (_: Exception) {
                    false
                }

                if (passwordValid) {
                    call.respond(HttpStatusCode.OK, AuthResponse(true, "Login OK"))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, AuthResponse(false, "Credenciais inválidas"))
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AuthResponse(false, "Erro no servidor ao iniciar sessão: ${e.message}")
                )
            }
        }
    }
}
