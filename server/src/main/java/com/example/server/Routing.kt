package com.example.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.Statement
import io.ktor.http.ContentType
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.put


// ---------- Modelos ----------
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
data class ResetPasswordRequest(val email: String, val newPassword: String)

@Serializable
data class ChangePasswordRequest(val email: String, val oldPassword: String, val newPassword: String)

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
    val description: String? = null,
    val latitude: Double,
    val longitude: Double,
    val confirmations: Int = 0
)

@Serializable
data class BookmarkReportRequest(val reason: String? = null)

@Serializable
data class ProfileResponse(val email: String, val displayName: String? = null, val hasAvatar: Boolean = false)

@Serializable
data class UpdateProfileRequest(val displayName: String)

// ---------- Constantes e helpers ----------
private val ALLOWED_TYPES = setOf("ACCIDENT", "DANGER", "ROAD_CLOSED")
private const val SESSION_DAYS = 30
private const val REPORT_HIDE_THRESHOLD = 3 // nº de sinalizações a partir do qual a ocorrência deixa de aparecer

private val secureRandom = SecureRandom()

private fun newToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().ifEmpty { null }
}

/** Devolve o id do utilizador autenticado ou null se o token for inválido/expirado. */
private fun ApplicationCall.authenticatedUserId(): Long? {
    val token = bearerToken() ?: return null
    return dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT user_id FROM sessions WHERE token = ? AND created_at > (NOW() - INTERVAL $SESSION_DAYS DAY)"
        ).use { stmt ->
            stmt.setString(1, token)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong("user_id") else null }
        }
    }
}

private suspend fun ApplicationCall.respondUnauthorized() =
    respond(HttpStatusCode.Unauthorized, AuthResponse(false, "Sessão inválida ou expirada"))

private fun isDuplicate(e: Exception) =
    e is SQLIntegrityConstraintViolationException || (e is SQLException && e.errorCode == 1062)

fun Application.configureRouting() {
    routing {
        // ---------- Autenticação ----------
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
                if (isDuplicate(e)) {
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
                val user = dataSource.connection.use { c ->
                    c.prepareStatement("SELECT id, password_hash FROM users WHERE email = ?").use { stmt ->
                        stmt.setString(1, email)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) rs.getLong("id") to rs.getString("password_hash") else null
                        }
                    }
                }

                val passwordValid = user != null && try {
                    BCrypt.checkpw(req.password, user.second)
                } catch (_: Exception) {
                    false
                }

                if (user != null && passwordValid) {
                    val token = newToken()
                    dataSource.connection.use { c ->
                        c.prepareStatement("INSERT INTO sessions (token, user_id) VALUES (?, ?)").use { stmt ->
                            stmt.setString(1, token)
                            stmt.setLong(2, user.first)
                            stmt.executeUpdate()
                        }
                    }
                    call.respond(
                        HttpStatusCode.OK,
                        AuthResponse(true, "Login OK", token = token, userId = user.first)
                    )
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
            val token = call.bearerToken()
                ?: return@post call.respondUnauthorized()
            try {
                dataSource.connection.use { c ->
                    c.prepareStatement("DELETE FROM sessions WHERE token = ?").use { stmt ->
                        stmt.setString(1, token)
                        stmt.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.OK, AuthResponse(true, "Sessão terminada"))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AuthResponse(false, "Erro no servidor ao terminar sessão")
                )
            }
        }

        post("/reset-password") {
            val req = call.receive<ResetPasswordRequest>()
            val email = req.email.trim().lowercase()

            if (req.newPassword.length < 8) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(false, "A nova palavra-passe deve ter pelo menos 8 caracteres")
                )
            }

            try {
                val userId = dataSource.connection.use { c ->
                    c.prepareStatement("SELECT id FROM users WHERE email = ?").use { stmt ->
                        stmt.setString(1, email)
                        stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
                    }
                } ?: return@post call.respond(
                    HttpStatusCode.NotFound,
                    AuthResponse(false, "Email não encontrado")
                )

                val newHash = BCrypt.hashpw(req.newPassword, BCrypt.gensalt())
                dataSource.connection.use { c ->
                    c.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?").use { stmt ->
                        stmt.setString(1, newHash)
                        stmt.setLong(2, userId)
                        stmt.executeUpdate()
                    }
                    // Invalida as sessões existentes
                    c.prepareStatement("DELETE FROM sessions WHERE user_id = ?").use { stmt ->
                        stmt.setLong(1, userId)
                        stmt.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.OK, AuthResponse(true, "Palavra-passe redefinida com sucesso"))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AuthResponse(false, "Erro no servidor ao redefinir palavra-passe: ${e.message}")
                )
            }
        }

        post("/change-password") {
            val req = call.receive<ChangePasswordRequest>()
            val email = req.email.trim().lowercase()

            if (req.newPassword.length < 8) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(false, "A nova palavra-passe deve ter pelo menos 8 caracteres")
                )
            }

            try {
                val user = dataSource.connection.use { c ->
                    c.prepareStatement("SELECT id, password_hash FROM users WHERE email = ?").use { stmt ->
                        stmt.setString(1, email)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) rs.getLong("id") to rs.getString("password_hash") else null
                        }
                    }
                }

                val validOld = user != null && try {
                    BCrypt.checkpw(req.oldPassword, user.second)
                } catch (_: Exception) {
                    false
                }

                if (user == null || !validOld) {
                    return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        AuthResponse(false, "Palavra-passe atual incorreta")
                    )
                }

                val newHash = BCrypt.hashpw(req.newPassword, BCrypt.gensalt())
                dataSource.connection.use { c ->
                    c.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?").use { stmt ->
                        stmt.setString(1, newHash)
                        stmt.setLong(2, user.first)
                        stmt.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.OK, AuthResponse(true, "Palavra-passe alterada com sucesso"))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AuthResponse(false, "Erro no servidor ao alterar palavra-passe: ${e.message}")
                )
            }
        }

        // ---------- Bookmarks ----------
        get("/bookmarks") {
            try {
                val list = dataSource.connection.use { c ->
                    c.prepareStatement(
                        """
                        SELECT b.id, b.type, b.description, b.latitude, b.longitude,
                               (SELECT COUNT(*) FROM bookmark_confirmations bc WHERE bc.bookmark_id = b.id) AS confirmations
                        FROM map_bookmarks b
                        WHERE (SELECT COUNT(*) FROM bookmark_reports br WHERE br.bookmark_id = b.id) < ?
                        ORDER BY b.created_at DESC
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.setInt(1, REPORT_HIDE_THRESHOLD)
                        stmt.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        Bookmark(
                                            id = rs.getLong("id"),
                                            type = rs.getString("type"),
                                            description = rs.getString("description"),
                                            latitude = rs.getDouble("latitude"),
                                            longitude = rs.getDouble("longitude"),
                                            confirmations = rs.getInt("confirmations")
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, list)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AuthResponse(false, "Erro no servidor ao obter ocorrências")
                )
            }
        }

        post("/bookmarks") {
            val userId = call.authenticatedUserId() ?: return@post call.respondUnauthorized()
            val req = call.receive<BookmarkRequest>()

            if (req.type !in ALLOWED_TYPES ||
                req.latitude !in -90.0..90.0 ||
                req.longitude !in -180.0..180.0
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(false, "Tipo ou coordenadas inválidos")
                )
            }
            val description = req.description?.trim()?.take(255)?.ifEmpty { null }

            try {
                val id = dataSource.connection.use { c ->
                    c.prepareStatement(
                        "INSERT INTO map_bookmarks (user_id, type, description, latitude, longitude) VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS
                    ).use { stmt ->
                        stmt.setLong(1, userId)
                        stmt.setString(2, req.type)
                        stmt.setString(3, description)
                        stmt.setDouble(4, req.latitude)
                        stmt.setDouble(5, req.longitude)
                        stmt.executeUpdate()
                        stmt.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("Sem id gerado") }
                    }
                }
                call.respond(
                    HttpStatusCode.Created,
                    Bookmark(id, req.type, description, req.latitude, req.longitude, 0)
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AuthResponse(false, "Erro no servidor ao guardar ocorrência")
                )
            }
        }

        delete("/bookmarks/{id}") {
            val userId = call.authenticatedUserId() ?: return@delete call.respondUnauthorized()
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "Id inválido"))

            try {
                val deleted = dataSource.connection.use { c ->
                    val rows = c.prepareStatement("DELETE FROM map_bookmarks WHERE id = ? AND user_id = ?").use { stmt ->
                        stmt.setLong(1, id)
                        stmt.setLong(2, userId)
                        stmt.executeUpdate()
                    }
                    if (rows > 0) {
                        c.prepareStatement("DELETE FROM bookmark_reports WHERE bookmark_id = ?").use { s ->
                            s.setLong(1, id); s.executeUpdate()
                        }
                        c.prepareStatement("DELETE FROM bookmark_confirmations WHERE bookmark_id = ?").use { s ->
                            s.setLong(1, id); s.executeUpdate()
                        }
                    }
                    rows > 0
                }
                if (deleted) {
                    call.respond(HttpStatusCode.OK, AuthResponse(true, "Ocorrência removida"))
                } else {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        AuthResponse(false, "Só pode remover as suas ocorrências")
                    )
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AuthResponse(false, "Erro no servidor ao remover ocorrência")
                )
            }
        }

        post("/bookmarks/{id}/reports") {
            val userId = call.authenticatedUserId() ?: return@post call.respondUnauthorized()
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "Id inválido"))
            val req = runCatching { call.receive<BookmarkReportRequest>() }.getOrDefault(BookmarkReportRequest())

            try {
                dataSource.connection.use { c ->
                    c.prepareStatement(
                        "INSERT INTO bookmark_reports (bookmark_id, user_id, reason) VALUES (?, ?, ?)"
                    ).use { stmt ->
                        stmt.setLong(1, id)
                        stmt.setLong(2, userId)
                        stmt.setString(3, req.reason?.trim()?.take(255))
                        stmt.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.Created, AuthResponse(true, "Ocorrência sinalizada"))
            } catch (e: Exception) {
                if (isDuplicate(e)) {
                    call.respond(HttpStatusCode.Conflict, AuthResponse(false, "Já sinalizou esta ocorrência"))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AuthResponse(false, "Erro no servidor ao sinalizar ocorrência")
                    )
                }
            }
        }

        post("/bookmarks/{id}/confirm") {
            val userId = call.authenticatedUserId() ?: return@post call.respondUnauthorized()
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, AuthResponse(false, "Id inválido"))

            try {
                dataSource.connection.use { c ->
                    c.prepareStatement(
                        "INSERT INTO bookmark_confirmations (bookmark_id, user_id) VALUES (?, ?)"
                    ).use { stmt ->
                        stmt.setLong(1, id)
                        stmt.setLong(2, userId)
                        stmt.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.Created, AuthResponse(true, "Ocorrência confirmada"))
            } catch (e: Exception) {
                if (isDuplicate(e)) {
                    call.respond(HttpStatusCode.Conflict, AuthResponse(false, "Já confirmou esta ocorrência"))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AuthResponse(false, "Erro no servidor ao confirmar ocorrência")
                    )
                }
            }
        }

        // ---------- Perfil ----------
        get("/profile") {
            val userId = call.authenticatedUserId() ?: return@get call.respondUnauthorized()
            try {
                val profile = dataSource.connection.use { c ->
                    c.prepareStatement(
                        "SELECT email, display_name, (avatar IS NOT NULL) AS has_avatar FROM users WHERE id = ?"
                    ).use { stmt ->
                        stmt.setLong(1, userId)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) ProfileResponse(
                                email = rs.getString("email"),
                                displayName = rs.getString("display_name"),
                                hasAvatar = rs.getBoolean("has_avatar")
                            ) else null
                        }
                    }
                }
                if (profile != null) call.respond(HttpStatusCode.OK, profile)
                else call.respond(HttpStatusCode.NotFound, AuthResponse(false, "Utilizador não encontrado"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, AuthResponse(false, "Erro no servidor ao obter perfil"))
            }
        }

        put("/profile") {
            val userId = call.authenticatedUserId() ?: return@put call.respondUnauthorized()
            val req = call.receive<UpdateProfileRequest>()
            val name = req.displayName.trim()

            if (name.length !in 2..30) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(false, "O nome deve ter entre 2 e 30 caracteres")
                )
            }
            try {
                dataSource.connection.use { c ->
                    c.prepareStatement("UPDATE users SET display_name = ? WHERE id = ?").use { stmt ->
                        stmt.setString(1, name)
                        stmt.setLong(2, userId)
                        stmt.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.OK, AuthResponse(true, "Nome atualizado"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, AuthResponse(false, "Erro no servidor ao atualizar nome"))
            }
        }

        put("/profile/avatar") {
            val userId = call.authenticatedUserId() ?: return@put call.respondUnauthorized()
            val bytes = call.receive<ByteArray>()

            if (bytes.isEmpty() || bytes.size > 512 * 1024) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(false, "Imagem inválida ou demasiado grande (máx. 512 KB)")
                )
            }
            try {
                dataSource.connection.use { c ->
                    c.prepareStatement("UPDATE users SET avatar = ? WHERE id = ?").use { stmt ->
                        stmt.setBytes(1, bytes)
                        stmt.setLong(2, userId)
                        stmt.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.OK, AuthResponse(true, "Foto atualizada"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, AuthResponse(false, "Erro no servidor ao guardar foto"))
            }
        }

        get("/profile/avatar") {
            val userId = call.authenticatedUserId() ?: return@get call.respondUnauthorized()
            try {
                val bytes = dataSource.connection.use { c ->
                    c.prepareStatement("SELECT avatar FROM users WHERE id = ?").use { stmt ->
                        stmt.setLong(1, userId)
                        stmt.executeQuery().use { rs -> if (rs.next()) rs.getBytes("avatar") else null }
                    }
                }
                if (bytes != null) call.respondBytes(bytes, ContentType.Image.JPEG)
                else call.respond(HttpStatusCode.NotFound, AuthResponse(false, "Sem foto de perfil"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, AuthResponse(false, "Erro no servidor ao obter foto"))
            }
        }
    }
}