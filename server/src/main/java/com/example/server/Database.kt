package com.example.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

lateinit var dataSource: DataSource

fun configureDatabases() {
    val config = HikariConfig().apply {
        driverClassName = "org.mariadb.jdbc.Driver"
        jdbcUrl = "jdbc:mariadb://85.247.43.227:3306/libremaps"
        username = "fernando"
        password = "1234"
        maximumPoolSize = 10
        minimumIdle = 2
        maxLifetime = 600_000L
        keepaliveTime = 60_000L
    }
    dataSource = HikariDataSource(config)
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            // A tabela users já deve existir; isto só a cria se faltar.
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    email VARCHAR(255) NOT NULL UNIQUE,
                    password_hash VARCHAR(255) NOT NULL
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    token VARCHAR(64) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_sessions_user (user_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS map_bookmarks (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NULL,
                    type VARCHAR(40) NOT NULL,
                    description VARCHAR(255),
                    latitude DOUBLE NOT NULL,
                    longitude DOUBLE NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
            try {
                statement.executeUpdate("ALTER TABLE map_bookmarks ADD COLUMN user_id BIGINT NULL")
            } catch (_: Exception) {
                // Existing installations already have the column.
            }
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS bookmark_reports (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    bookmark_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    reason VARCHAR(255),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY one_report_per_user (bookmark_id, user_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS bookmark_confirmations (
                    bookmark_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (bookmark_id, user_id)
                )
                """.trimIndent()
            )
        }
    }
}