package com.example.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt
import java.sql.SQLIntegrityConstraintViolationException

@Serializable
data class AuthRequest(val email: String, val password: String)

@Serializable
data class AuthResponse(val success: Boolean, val message: String)

fun Application.configureRouting() {
    routing {
        post("/register") {
            val req = call.receive<AuthRequest>()
            if (!req.email.contains("@") || req.password.length < 8) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(false, "Email inválido ou password com menos de 8 caracteres")
                )
            }
            val hash = BCrypt.hashpw(req.password, BCrypt.gensalt())
            try {
                dataSource.connection.use { c ->
                    c.prepareStatement("INSERT INTO users (email, password_hash) VALUES (?, ?)").use {
                        it.setString(1, req.email.trim().lowercase())
                        it.setString(2, hash)
                        it.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.Created, AuthResponse(true, "Conta criada"))
            } catch (e: SQLIntegrityConstraintViolationException) {
                call.respond(HttpStatusCode.Conflict, AuthResponse(false, "Email já registado"))
            }
        }

        post("/login") {
            val req = call.receive<AuthRequest>()
            val hash = dataSource.connection.use { c ->
                c.prepareStatement("SELECT password_hash FROM users WHERE email = ?").use {
                    it.setString(1, req.email.trim().lowercase())
                    it.executeQuery().let { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
            if (hash != null && BCrypt.checkpw(req.password, hash)) {
                call.respond(AuthResponse(true, "Login OK"))
            } else {
                call.respond(HttpStatusCode.Unauthorized, AuthResponse(false, "Credenciais inválidas"))
            }
        }
    }
}
