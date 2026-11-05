package com.example.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AuthRequest(val email: String, val password: String)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val message: String,
    val token: String? = null,
    val userId: Long? = null
)

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
    val longitude: Double,
    val confirmations: Int = 0
)

@Serializable
data class BookmarkReportRequest(val reason: String? = null)

private data class UserCredentials(val id: Long, val passwordHash: String)

private val sessions = ConcurrentHashMap<String, Long>()
private val reportTimestamps = ConcurrentHashMap<Long, MutableList<Long>>()

private fun authenticatedUserId(call: io.ktor.server.application.ApplicationCall): Long? {
    val authorization = call.request.headers["Authorization"] ?: return null
    if (!authorization.startsWith("Bearer ")) return null
    return sessions[authorization.removePrefix("Bearer ").trim()]
}

private fun isWithinReportLimit(userId: Long): Boolean {
    val now = System.currentTimeMillis()
    val timestamps = reportTimestamps.computeIfAbsent(userId) { mutableListOf() }
    synchronized(timestamps) {
        timestamps.removeAll { now - it >= 60_000L }
        if (timestamps.size >= 10) return false
        timestamps += now
        return true
    }
}

fun Application.configureRouting() {
    routing {
        get("/bookmarks") {
            try {
                val bookmarks = dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "SELECT b.id, b.type, b.description, b.latitude, b.longitude, " +
                            "(SELECT COUNT(*) FROM bookmark_confirmations c WHERE c.bookmark_id = b.id) AS confirmations " +
                            "FROM map_bookmarks b " +
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
                                            longitude = result.getDouble("longitude"),
                                            confirmations = result.getInt("confirmations")
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
            val userId = authenticatedUserId(call)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, AuthResponse(false, "Sessão inválida"))
            if (!isWithinReportLimit(userId)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, AuthResponse(false, "Limite de ocorrências atingido"))
            }
            val req = call.receive<BookmarkRequest>()
            val allowedTypes = setOf("ACCIDENT", "DANGER", "ROAD_CLOSED")
            if (req.type !in allowedTypes || req.latitude !in -90.0..90.0 || req.longitude !in -180.0..180.0) {
                return@post call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "Dados da ocorrência inválidos"))
            }

            val duplicate = dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id FROM map_bookmarks WHERE user_id = ? AND type = ? " +
                        "AND ABS(latitude - ?) < 0.0005 AND ABS(longitude - ?) < 0.0005 " +
                        "AND created_at >= CURRENT_TIMESTAMP - INTERVAL 5 MINUTE LIMIT 1"
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, req.type)
                    statement.setDouble(3, req.latitude)
                    statement.setDouble(4, req.longitude)
                    statement.executeQuery().use { it.next() }
                }
            }
            if (duplicate) {
                return@post call.respond(HttpStatusCode.Conflict, AuthResponse(false, "Já comunicou esta ocorrência"))
            }

            try {
                val bookmark = dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO map_bookmarks (user_id, type, description, latitude, longitude) VALUES (?, ?, ?, ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setString(2, req.type)
                        statement.setString(3, req.description?.take(255))
                        statement.setDouble(4, req.latitude)
                        statement.setDouble(5, req.longitude)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            keys.next()
                            Bookmark(keys.getLong(1), req.type, req.description?.take(255), req.latitude, req.longitude, 0)
                        }
                    }
                }
                call.respond(HttpStatusCode.Created, bookmark)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.InternalServerError, AuthResponse(false, "Erro ao guardar a ocorrência"))
            }
        }

        delete("/bookmarks/{id}") {
            val userId = authenticatedUserId(call)
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, AuthResponse(false, "Sessão inválida"))
            val bookmarkId = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "ID inválido"))

            val deleted = dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM map_bookmarks WHERE id = ? AND user_id = ?").use { statement ->
                    statement.setLong(1, bookmarkId)
                    statement.setLong(2, userId)
                    statement.executeUpdate()
                }
            }
            if (deleted == 0) {
                call.respond(HttpStatusCode.NotFound, AuthResponse(false, "Ocorrência não encontrada"))
            } else {
                call.respond(AuthResponse(true, "Ocorrência removida"))
            }
        }

        post("/bookmarks/{id}/reports") {
            val userId = authenticatedUserId(call)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, AuthResponse(false, "Sessão inválida"))
            val bookmarkId = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "ID inválido"))
            val req = call.receive<BookmarkReportRequest>()

            try {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO bookmark_reports (bookmark_id, user_id, reason) VALUES (?, ?, ?)"
                    ).use { statement ->
                        statement.setLong(1, bookmarkId)
                        statement.setLong(2, userId)
                        statement.setString(3, req.reason?.take(255))
                        statement.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.Created, AuthResponse(true, "Ocorrência sinalizada"))
            } catch (e: SQLIntegrityConstraintViolationException) {
                call.respond(HttpStatusCode.Conflict, AuthResponse(false, "Já sinalizou esta ocorrência"))
            }
        }

        post("/bookmarks/{id}/confirm") {
            val userId = authenticatedUserId(call)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, AuthResponse(false, "Sessão inválida"))
            val bookmarkId = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "ID inválido"))
            try {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO bookmark_confirmations (bookmark_id, user_id) VALUES (?, ?)"
                    ).use { statement ->
                        statement.setLong(1, bookmarkId)
                        statement.setLong(2, userId)
                        statement.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.Created, AuthResponse(true, "Ocorrência confirmada"))
            } catch (e: SQLIntegrityConstraintViolationException) {
                call.respond(HttpStatusCode.Conflict, AuthResponse(false, "Já confirmou esta ocorrência"))
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
                val credentials = dataSource.connection.use { c ->
                    c.prepareStatement("SELECT id, password_hash FROM users WHERE email = ?").use { stmt ->
                        stmt.setString(1, email)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) {
                                UserCredentials(rs.getLong("id"), rs.getString("password_hash"))
                            } else null
                        }
                    }
                }

                val passwordValid = credentials != null && try {
                    BCrypt.checkpw(req.password, credentials.passwordHash)
                } catch (_: Exception) {
                    false
                }

                if (passwordValid) {
                    val token = UUID.randomUUID().toString()
                    sessions[token] = credentials.id
                    call.respond(HttpStatusCode.OK, AuthResponse(true, "Login OK", token, credentials.id))
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

        post("/logout") {
            val authorization = call.request.headers["Authorization"]
            if (authorization?.startsWith("Bearer ") == true) {
                sessions.remove(authorization.removePrefix("Bearer ").trim())
            }
            call.respond(AuthResponse(true, "Logout OK"))
        }
    }
}
