package com.example.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

lateinit var dataSource: DataSource

fun configureDatabases() {
    val config = HikariConfig().apply {
        driverClassName = "org.mariadb.jdbc.Driver"
        jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:mariadb://85.247.43.227:3306/libremaps"
        username = System.getenv("DB_USER") ?: "diogo"
        password = System.getenv("DB_PASS") ?: "1234"
        maximumPoolSize = 10
    }
    dataSource = HikariDataSource(config)
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS map_bookmarks (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    type VARCHAR(40) NOT NULL,
                    description VARCHAR(255),
                    latitude DOUBLE NOT NULL,
                    longitude DOUBLE NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
        }
    }
}
