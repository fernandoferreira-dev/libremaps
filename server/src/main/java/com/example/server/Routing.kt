package com.example.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
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

fun Application.configureRouting() {
    routing {
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

                val passwordValid = if (hash != null) {
                    try {
                        BCrypt.checkpw(req.password, hash)
                    } catch (e: Exception) {
                        false
                    }
                } else {
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
                    AuthResponse(false, "Erro no servidor ao iniciar sessão")
                )
            }
        }
    }
}
